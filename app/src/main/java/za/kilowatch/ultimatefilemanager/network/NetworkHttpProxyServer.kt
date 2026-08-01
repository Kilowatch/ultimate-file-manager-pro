package za.kilowatch.ultimatefilemanager.network

import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Local HTTP/1.1 proxy server for network-file streaming to external players (VLC, MX Player, etc.)
 * via standard HTTP Range requests — enabling full seekable playback.
 *
 * The proxy mirrors the internal player's access model: ONE pinned [IRandomAccessFile] per session,
 * and ALL reads are serialized through [Session.readLock]. Concurrent HTTP connections from the
 * player therefore behave like the internal player's single-threaded sequential reads — never two
 * threads inside the same smbj handle at once, which is what corrupts the SMB connection and makes
 * the NAS drop it.
 *
 * On seek, the player closes the old HTTP socket; the old streaming loop's next socket write
 * fails and it exits without touching the handle. The new request reuses the same pinned handle
 * at the new offset — no new SMB connection is opened for a seek.
 */
object NetworkHttpProxyServer {

    private const val TAG = "NetworkHttpProxy"
    private const val SESSION_TTL_MS = 60 * 60 * 1000L
    private const val CHUNK_SIZE = 256 * 1024

    /** Max read attempts with a fresh handle before returning EOF. */
    private const val MAX_READ_ATTEMPTS = 4

    /** Base backoff between reopen attempts (multiplied by attempt number). */
    private const val READ_RETRY_BACKOFF_MS = 400L

    class Session(
        val share: NetworkShare,
        val path: String,
        val mimeType: String,
        val fileSize: Long,
        val authToken: String = generateAuthToken(),
        val createdAt: Long = System.currentTimeMillis()
    ) {
        @Volatile var lastAccessMs: Long = System.currentTimeMillis()

        /** Pinned handle — opened once, reused across all HTTP requests (seeks). */
        @Volatile var handle: IRandomAccessFile? = null

        /**
         * Serializes ALL reads AND handle replacement. Exactly like the internal player's
         * single-threaded reads: only one thread is ever inside the smbj handle at a time,
         * so a read failure's connection invalidation can't affect a concurrent reader.
         */
        val readLock = Any()

        fun closeHandle() {
            synchronized(readLock) {
                runCatching { handle?.close() }
                handle = null
            }
        }
    }

    private fun generateAuthToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var port: Int = 0

    private val executor = ThreadPoolExecutor(0, 32, 60L, TimeUnit.SECONDS, SynchronousQueue())

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        try {
            val ss = ServerSocket(0)
            ss.reuseAddress = true
            serverSocket = ss
            port = ss.localPort
            GoRoLog.d(TAG, "HTTP proxy started on port $port")
            executor.submit { acceptLoop(ss) }
            scheduleSessionEviction()
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Failed to start HTTP proxy server", e)
        }
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
        sessions.values.forEach { it.closeHandle() }
        sessions.clear()
        GoRoLog.d(TAG, "HTTP proxy stopped")
    }

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    fun register(share: NetworkShare, path: String, mimeType: String, fileSize: Long): String {
        if (!isRunning) start()
        val uuid = UUID.randomUUID().toString()
        val session = Session(share, path, mimeType, fileSize)
        sessions[uuid] = session
        GoRoLog.d(TAG, "Registered session $uuid → ${share.host}$path (size=$fileSize)")
        val fileName = android.net.Uri.encode(path.substringAfterLast('/'))
        return "http://127.0.0.1:$port/$uuid/$fileName?auth=${session.authToken}"
    }

    fun unregister(uuid: String) {
        val session = sessions.remove(uuid)
        session?.closeHandle()
        GoRoLog.d(TAG, "Unregistered session $uuid")
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            try {
                val client = ss.accept()
                try { executor.submit { handleClient(client) } }
                catch (e: RejectedExecutionException) { runCatching { client.close() } }
            } catch (e: Exception) {
                if (!ss.isClosed) GoRoLog.e(TAG, "Accept error", e)
            }
        }
    }

    // ── Request handler ───────────────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1].trimStart('/')

            val headers = mutableMapOf<String, String>()
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line.isNullOrBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val pathWithoutQuery = fullPath.substringBefore('?')
            val queryString = fullPath.substringAfter('?', "")
            val queryParams = queryString.split("&").mapNotNull { kv ->
                val eq = kv.split("=", limit = 2)
                if (eq.size == 2) eq[0] to eq[1] else null
            }.toMap()

            val uuid = pathWithoutQuery.substringBefore('/')
            val session = sessions[uuid]
            if (session == null || queryParams["auth"] != session.authToken) {
                send403(out); return
            }
            session.lastAccessMs = System.currentTimeMillis()

            val fileSize = session.fileSize
            val rangeHeader = headers["range"]
            val (rangeStart, rangeEnd) = parseRange(rangeHeader, fileSize)
            val isRangeRequest = rangeHeader != null

            if (method == "HEAD") {
                sendHeadResponse(out, session, fileSize, isRangeRequest, rangeStart, rangeEnd)
                return
            }

            streamResponse(out, session, fileSize, isRangeRequest, rangeStart, rangeEnd)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Client handler error", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    /**
     * Streams a byte range to the client. All reads are serialized under [Session.readLock],
     * exactly matching the internal player's single-threaded access to its handle.
     *
     * If a read fails, this request returns EOF (0 bytes) — it does NOT reopen a connection.
     * The player sees the stream end and issues a new HTTP request, which opens a fresh handle
     * if the previous one died. This mirrors the internal player: it never reconnect-storms
     * the NAS; reconnects are paced by the player's own request cadence.
     */
    private fun streamResponse(
        out: OutputStream, session: Session,
        fileSize: Long, isRangeRequest: Boolean, rangeStart: Long, rangeEnd: Long
    ) {
        val contentLength = rangeEnd - rangeStart + 1
        val statusLine = if (isRangeRequest) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
        val responseHeaders = buildString {
            append(statusLine)
            append("Content-Type: ${session.mimeType}\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (isRangeRequest) append("Content-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(responseHeaders.toByteArray(Charsets.US_ASCII)); out.flush()

        val buffer = ByteArray(CHUNK_SIZE)
        var position = rangeStart
        var remaining = contentLength

        while (remaining > 0) {
            val toRead = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
            val bytesRead = readChunk(session, position, buffer, toRead)
            if (bytesRead <= 0) break

            try { out.write(buffer, 0, bytesRead) }
            catch (e: Exception) { break } // client disconnected (seek/close)

            position += bytesRead
            remaining -= bytesRead
        }
        out.flush()
    }

    /**
     * Reads one chunk under [Session.readLock], transparently surviving a dead connection.
     *
     * The NAS (Windows) periodically resets idle/streaming connections. The internal player
     * survives this because ExoPlayer re-opens its data source. This method does the same:
     * on ANY read failure it closes the dead handle, opens a fresh one (with exponential
     * backoff so we don't hammer the NAS during its reset cooldown), and retries the SAME
     * offset. It only returns EOF after [MAX_READ_ATTEMPTS] reopen attempts — mirroring
     * ExoPlayer's resilience so external players also survive server-side resets.
     *
     * @return bytes read, or 0 (EOF) only if every reopen attempt failed.
     */
    private fun readChunk(session: Session, offset: Long, buffer: ByteArray, length: Int): Int {
        synchronized(session.readLock) {
            var handle: IRandomAccessFile = getOrCreatePinnedHandleLocked(session) ?: return 0

            var attempts = 0
            while (attempts < MAX_READ_ATTEMPTS) {
                attempts++
                try {
                    val n = handle.read(offset, buffer, length)
                    return if (n < 0) 0 else n
                } catch (e: Exception) {
                    GoRoLog.w(TAG, "Pinned handle read failed at offset $offset (attempt $attempts/$MAX_READ_ATTEMPTS)", null)
                    if (attempts >= MAX_READ_ATTEMPTS) {
                        GoRoLog.e(TAG, "Pinned handle read failed after $MAX_READ_ATTEMPTS attempts at offset $offset", e)
                        return 0
                    }
                    // Close the dead handle; open a fresh one after a short backoff so the
                    // NAS's reset cooldown has time to pass before we reconnect.
                    runCatching { handle.close() }
                    session.handle = null
                    try { Thread.sleep(READ_RETRY_BACKOFF_MS * attempts) }
                    catch (_: InterruptedException) { return 0 }
                    handle = try { openHandleForSession(session) }
                    catch (ex: Exception) { GoRoLog.e(TAG, "Failed to reopen pinned handle for ${session.path}", ex); null }
                        ?: return 0
                    session.handle = handle
                }
            }
            return 0
        }
    }

    /**
     * Returns the pinned handle, opening it under [Session.readLock] if needed.
     * Callers must already hold the lock (or be inside [readChunk]).
     */
    private fun getOrCreatePinnedHandleLocked(session: Session): IRandomAccessFile? {
        session.handle?.let { return it }
        val h = try { openHandleForSession(session) }
        catch (e: Exception) { GoRoLog.e(TAG, "Failed to open pinned handle for ${session.path}", e); null }
        session.handle = h
        return h
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun sendHeadResponse(out: OutputStream, session: Session, fileSize: Long,
                                  isRangeRequest: Boolean, rangeStart: Long, rangeEnd: Long) {
        val contentLength = rangeEnd - rangeStart + 1
        val statusLine = if (isRangeRequest) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
        val response = buildString {
            append(statusLine)
            append("Content-Type: ${session.mimeType}\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (isRangeRequest) append("Content-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(response.toByteArray(Charsets.US_ASCII)); out.flush()
    }

    private fun send403(out: OutputStream) {
        val r = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching { out.write(r.toByteArray()); out.flush() }
    }

    private fun send500(out: OutputStream) {
        val r = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching { out.write(r.toByteArray()); out.flush() }
    }

    // ── Range parsing ─────────────────────────────────────────────────────────

    internal fun parseRange(rangeHeader: String?, fileSize: Long): Pair<Long, Long> {
        if (rangeHeader == null || fileSize <= 0) return Pair(0L, maxOf(0L, fileSize - 1))
        return try {
            val value = rangeHeader.removePrefix("bytes=").trim()
            val dashIdx = value.indexOf('-')
            val (rawStart, rawEnd) = when {
                dashIdx == 0 -> { val s = value.substring(1).toLong(); Pair(maxOf(0L, fileSize - s), fileSize - 1) }
                dashIdx == value.length - 1 -> Pair(value.substring(0, dashIdx).toLong(), fileSize - 1)
                dashIdx > 0 -> Pair(value.substring(0, dashIdx).toLong(), value.substring(dashIdx + 1).toLong())
                else -> Pair(0L, fileSize - 1)
            }
            normalizeRange(rawStart, rawEnd, fileSize)
        } catch (e: Exception) { Pair(0L, maxOf(0L, fileSize - 1)) }
    }

    private fun normalizeRange(rawStart: Long, rawEnd: Long, fileSize: Long): Pair<Long, Long> {
        val last = maxOf(0L, fileSize - 1)
        val start = rawStart.coerceIn(0L, last)
        val end = rawEnd.coerceIn(start, last)
        return Pair(start, end)
    }

    // ── Handle factory ────────────────────────────────────────────────────────

    internal fun openHandleForSession(session: Session): IRandomAccessFile? {
        return when (session.share.type) {
            // suppressInvalidateOnReadError = true: the proxy's pinned handle must NOT be
            // auto-invalidated (connection closed) when a read throws — an external player
            // that aborts a request on seek would otherwise destroy the shared SMB/SSH session.
            ShareType.SMB -> SmbShareClient.openRandomAccessFile(
                session.share, session.path, isWrite = false, dedicated = true,
                suppressInvalidateOnReadError = true
            )
            ShareType.SFTP, ShareType.SCP -> SshShareClient.openRandomAccessFile(
                session.share, session.path, dedicated = true,
                suppressInvalidateOnReadError = true
            )
            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.ONEDRIVE -> OnedriveShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.DROPBOX -> DropboxShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.NFS -> NfsShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.DLNA -> DlnaShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.WEBDAV -> WebDavShareClient.openRandomAccessFile(session.share, session.path, session.fileSize)
            ShareType.FTP -> FtpShareClient.openRandomAccessFile(session.share, session.path)
            else -> null
        }
    }

    // ── Session eviction ──────────────────────────────────────────────────────

    private fun scheduleSessionEviction() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler.scheduleAtFixedRate({
            val now = System.currentTimeMillis()
            val expired = sessions.entries.filter { now - it.value.lastAccessMs > SESSION_TTL_MS }
            expired.forEach { (uuid, session) ->
                session.closeHandle()
                sessions.remove(uuid)
                GoRoLog.d(TAG, "Evicted idle session $uuid")
            }
        }, 10, 10, TimeUnit.MINUTES)
    }
}
