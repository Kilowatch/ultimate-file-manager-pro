package za.kilowatch.ultimatefilemanager.network

import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
 * On seek, when a new Range request arrives on the same session, any in-flight superseded stream
 * is actively aborted and its socket closed. This unblocks the writer thread immediately, clears
 * kernel loopback buffers, and avoids hitting the client's per-host connection limit.
 *
 * Pinned handles are automatically closed after [IDLE_HANDLE_CLOSE_MS] of zero active connections
 * and reopened transparently on demand, preventing server-side handle and session leaks.
 */
object NetworkHttpProxyServer {

    private const val TAG = "NetworkHttpProxy"
    private const val SESSION_TTL_MS = 10 * 60 * 1000L // 10 minutes idle session TTL
    internal const val IDLE_HANDLE_CLOSE_MS = 60 * 1000L // 60s idle handle reclamation
    private const val CHUNK_SIZE = 256 * 1024
    const val PREFERRED_PORT = 28842

    internal data class PersistentSession(
        val key: String,
        val shareId: String,
        val path: String,
        val mimeType: String,
        val fileSize: Long,
        val authToken: String,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val persistentSessions = ConcurrentHashMap<String, PersistentSession>()

    /** Max read attempts with a fresh handle before returning EOF. */
    private const val MAX_READ_ATTEMPTS = 4

    /** Base backoff between reopen attempts (multiplied by attempt number). */
    private const val READ_RETRY_BACKOFF_MS = 400L

    internal class ActiveStream(
        val socket: Socket,
        val rangeStart: Long,
        val rangeEnd: Long
    ) {
        @Volatile var isAborted: Boolean = false

        fun abort() {
            if (!isAborted) {
                isAborted = true
                runCatching { socket.close() }
            }
        }
    }

    class Session(
        val share: NetworkShare,
        val path: String,
        @Volatile var mimeType: String,
        @Volatile var fileSize: Long,
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

        /** Currently active streaming context on this session. Replaced & aborted on seek. */
        @Volatile internal var activeStream: ActiveStream? = null

        /** Count of concurrently active HTTP streaming connections on this session. */
        internal val activeStreamCount = AtomicInteger(0)

        /** Scheduled future for closing handle when idle. */
        @Volatile internal var idleCloseFuture: ScheduledFuture<*>? = null

        fun closeHandle() {
            synchronized(readLock) {
                runCatching { handle?.close() }
                handle = null
            }
        }

        @Synchronized
        internal fun cancelIdleHandleCloseLocked() {
            idleCloseFuture?.cancel(false)
            idleCloseFuture = null
        }

        @Synchronized
        internal fun scheduleIdleHandleCloseLocked(delayMs: Long) {
            idleCloseFuture?.cancel(false)
            idleCloseFuture = scheduler.schedule({
                if (activeStreamCount.get() == 0) {
                    closeHandle()
                    GoRoLog.d(TAG, "Closed idle handle for session $path")
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun generateAuthToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateDeterministicKey(share: NetworkShare, path: String): String {
        val raw = "${share.id}:${share.type}:${share.host}:${share.port}:${share.remotePath}:$path"
        return UUID.nameUUIDFromBytes(raw.toByteArray(Charsets.UTF_8)).toString()
    }

    private fun generateDeterministicAuthToken(share: NetworkShare, path: String): String {
        val raw = "auth:${share.id}:${share.type}:${share.host}:${share.port}:${share.remotePath}:$path"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var port: Int = 0

    private val executor = ThreadPoolExecutor(0, 32, 60L, TimeUnit.SECONDS, SynchronousQueue())

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ufm-proxy-scheduler").apply { isDaemon = true }
    }

    /**
     * Handle-factory seam for creating streaming handles. Defaults to the real
     * [openHandleForSession]; tests override it to inject a fake [IRandomAccessFile].
     */
    internal var handleFactory: (Session) -> IRandomAccessFile? = ::openHandleForSession

    /**
     * Share-lookup seam for dynamic session revival. Tests override to provide mock shares.
     */
    internal var shareResolver: (String) -> NetworkShare? = { id ->
        val ctx = runCatching { za.kilowatch.ultimatefilemanager.UfmApplication.instance }.getOrNull()
        if (ctx != null) NetworkShareRepository.getInstance(ctx).getById(id) else null
    }

    private fun getStorageFile(): File? {
        val ctx = runCatching { za.kilowatch.ultimatefilemanager.UfmApplication.instance }.getOrNull() ?: return null
        return File(ctx.filesDir, "proxy_streaming_sessions.json")
    }

    internal fun loadPersistentSessions() {
        try {
            val file = getStorageFile() ?: return
            if (!file.exists()) return
            val jsonStr = file.readText()
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val key = obj.getString("key")
                val session = PersistentSession(
                    key = key,
                    shareId = obj.getString("shareId"),
                    path = obj.getString("path"),
                    mimeType = obj.optString("mimeType", "video/mp4"),
                    fileSize = obj.optLong("fileSize", 0L),
                    authToken = obj.getString("authToken"),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                )
                persistentSessions[key] = session
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to load persistent proxy sessions: ${e.message}")
        }
    }

    private fun savePersistentSessionsAsync() {
        executor.submit {
            try {
                val file = getStorageFile() ?: return@submit
                val array = org.json.JSONArray()
                val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L // 7-day persistent session retention
                val sorted = persistentSessions.values
                    .filter { it.updatedAt > cutoff }
                    .sortedByDescending { it.updatedAt }
                    .take(50)
                for (s in sorted) {
                    val obj = org.json.JSONObject().apply {
                        put("key", s.key)
                        put("shareId", s.shareId)
                        put("path", s.path)
                        put("mimeType", s.mimeType)
                        put("fileSize", s.fileSize)
                        put("authToken", s.authToken)
                        put("updatedAt", s.updatedAt)
                    }
                    array.put(obj)
                }
                val tempFile = File(file.parentFile, "${file.name}.tmp")
                tempFile.writeText(array.toString())
                if (tempFile.exists()) {
                    if (file.exists()) file.delete()
                    tempFile.renameTo(file)
                }
            } catch (e: Exception) {
                GoRoLog.w(TAG, "Failed to save persistent proxy sessions: ${e.message}")
            }
        }
    }

    internal fun tryReviveSession(uuid: String): Session? {
        val meta = persistentSessions[uuid] ?: return null
        val resolver = shareResolver
        val share = resolver(meta.shareId) ?: return null
        val revived = Session(share, meta.path, meta.mimeType, meta.fileSize, meta.authToken)
        sessions[uuid] = revived
        GoRoLog.i(TAG, "Revived persistent streaming session for $uuid → ${share.host}${meta.path}")
        return revived
    }

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val ss = try {
                ServerSocket(PREFERRED_PORT, 50, loopback).apply { reuseAddress = true }
            } catch (e: Exception) {
                GoRoLog.w(TAG, "Preferred port $PREFERRED_PORT unavailable (${e.message}); using ephemeral port")
                ServerSocket(0, 50, loopback).apply { reuseAddress = true }
            }
            serverSocket = ss
            port = ss.localPort
            GoRoLog.d(TAG, "HTTP proxy started on port $port")
            loadPersistentSessions()
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
        val key = generateDeterministicKey(share, path)
        val authToken = generateDeterministicAuthToken(share, path)
        sessions.compute(key) { _, existing ->
            if (existing != null) {
                existing.mimeType = mimeType
                existing.fileSize = fileSize
                existing.cancelIdleHandleCloseLocked()
                existing
            } else {
                Session(share, path, mimeType, fileSize, authToken)
            }
        }
        persistentSessions[key] = PersistentSession(
            key = key,
            shareId = share.id,
            path = path,
            mimeType = mimeType,
            fileSize = fileSize,
            authToken = authToken
        )
        savePersistentSessionsAsync()
        GoRoLog.d(TAG, "Registered session $key → ${share.host}$path (size=$fileSize)")
        val rawFileName = path.substringAfterLast('/').ifBlank { "media" }
        val fileName = android.net.Uri.encode(rawFileName)
        return "http://127.0.0.1:$port/$key/$fileName?auth=$authToken"
    }

    fun unregister(uuid: String) {
        val session = sessions.remove(uuid)
        session?.cancelIdleHandleCloseLocked()
        session?.activeStream?.abort()
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
            // Defense-in-depth: Reject any connection not originating from the local loopback interface
            if (!socket.inetAddress.isLoopbackAddress) {
                runCatching { socket.close() }
                return
            }

            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            runCatching { socket.sendBufferSize = 512 * 1024 }
            runCatching { socket.receiveBufferSize = 64 * 1024 }

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1].trimStart('/')

            val headers = mutableMapOf<String, String>()
            var line: String?
            var headerCount = 0
            while (true) {
                line = reader.readLine()
                if (line.isNullOrBlank()) break
                headerCount++
                if (headerCount > 64) break // Bound maximum header count
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
            var session = sessions[uuid]
            if (session == null) {
                session = tryReviveSession(uuid)
            }
            val providedAuth = queryParams["auth"]
            val authMatches = providedAuth != null && session != null &&
                MessageDigest.isEqual(providedAuth.toByteArray(Charsets.UTF_8), session.authToken.toByteArray(Charsets.UTF_8))
            if (session == null || !authMatches) {
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

            val currentStream = ActiveStream(socket, rangeStart, rangeEnd)
            val prevStream: ActiveStream?
            synchronized(session) {
                prevStream = session.activeStream
                session.activeStream = currentStream
                session.cancelIdleHandleCloseLocked()
            }
            if (prevStream != null && prevStream.socket !== socket) {
                GoRoLog.d(TAG, "Aborting superseded stream (${prevStream.rangeStart}-${prevStream.rangeEnd}) on session $uuid for new seek ($rangeStart-$rangeEnd)")
                prevStream.abort()
            }
            session.activeStreamCount.incrementAndGet()

            try {
                streamResponse(out, session, currentStream, fileSize, isRangeRequest, rangeStart, rangeEnd)
            } finally {
                synchronized(session) {
                    if (session.activeStream === currentStream) {
                        session.activeStream = null
                    }
                    val remainingActive = session.activeStreamCount.decrementAndGet()
                    if (remainingActive <= 0) {
                        session.scheduleIdleHandleCloseLocked(IDLE_HANDLE_CLOSE_MS)
                    }
                }
            }
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
        out: OutputStream, session: Session, stream: ActiveStream,
        fileSize: Long, isRangeRequest: Boolean, rangeStart: Long, rangeEnd: Long
    ) {
        val rawFileName = session.path.substringAfterLast('/').ifBlank { "media" }
        val safeFileName = rawFileName.replace("\"", "\\\"").replace("\r", "").replace("\n", "")
        val encodedFileName = android.net.Uri.encode(rawFileName, "UTF-8")
        val safeTitle = rawFileName.substringBeforeLast('.').ifBlank { rawFileName }
            .replace("\r", "").replace("\n", "")

        val contentLength = rangeEnd - rangeStart + 1
        val statusLine = if (isRangeRequest) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
        val responseHeaders = buildString {
            append(statusLine)
            append("Content-Type: ${session.mimeType}\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (isRangeRequest) append("Content-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\n")
            append("Content-Disposition: inline; filename=\"$safeFileName\"; filename*=UTF-8''$encodedFileName\r\n")
            append("icy-name: $safeTitle\r\n")
            append("icy-description: $safeTitle\r\n")
            append("X-Title: $safeTitle\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(responseHeaders.toByteArray(Charsets.US_ASCII)); out.flush()

        val buffer = ByteArray(CHUNK_SIZE)
        var position = rangeStart
        var remaining = contentLength

        while (remaining > 0) {
            if (stream.isAborted || stream.socket.isClosed) break
            val toRead = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
            val bytesRead = readChunk(session, stream, position, buffer, toRead)
            if (bytesRead <= 0 || stream.isAborted || stream.socket.isClosed) break

            try { out.write(buffer, 0, bytesRead) }
            catch (e: Exception) { break } // client disconnected or aborted (seek/close)

            position += bytesRead
            remaining -= bytesRead
        }
        runCatching { out.flush() }
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
    private fun readChunk(session: Session, stream: ActiveStream?, offset: Long, buffer: ByteArray, length: Int): Int {
        var attempts = 0
        while (attempts < MAX_READ_ATTEMPTS) {
            if (stream?.isAborted == true || stream?.socket?.isClosed == true) return 0
            attempts++
            val (readBytes, error) = synchronized(session.readLock) {
                val handle = getOrCreatePinnedHandleLocked(session) ?: return 0
                try {
                    val n = handle.read(offset, buffer, length)
                    Pair(if (n < 0) 0 else n, null)
                } catch (e: Exception) {
                    Pair(0, e)
                }
            }

            if (error == null) {
                return readBytes
            }

            GoRoLog.w(TAG, "Pinned handle read failed at offset $offset (attempt $attempts/$MAX_READ_ATTEMPTS)", null)
            if (attempts >= MAX_READ_ATTEMPTS) {
                GoRoLog.e(TAG, "Pinned handle read failed after $MAX_READ_ATTEMPTS attempts at offset $offset", error)
                return 0
            }

            // Close the dead handle under lock so the next attempt opens a fresh one.
            synchronized(session.readLock) {
                runCatching { session.handle?.close() }
                session.handle = null
            }

            if (stream?.isAborted == true || stream?.socket?.isClosed == true) return 0

            // Sleep OUTSIDE readLock so other session operations are not blocked during backoff.
            try { Thread.sleep(READ_RETRY_BACKOFF_MS * attempts) }
            catch (_: InterruptedException) { return 0 }

            if (stream?.isAborted == true || stream?.socket?.isClosed == true) return 0
        }
        return 0
    }

    /**
     * Returns the pinned handle, opening it under [Session.readLock] if needed.
     * Callers must already hold the lock (or be inside [readChunk]).
     */
    private fun getOrCreatePinnedHandleLocked(session: Session): IRandomAccessFile? {
        session.handle?.let { return it }
        val h = try { handleFactory(session) }
        catch (e: Exception) { GoRoLog.e(TAG, "Failed to open pinned handle for ${session.path}", e); null }
        session.handle = h
        return h
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun sendHeadResponse(out: OutputStream, session: Session, fileSize: Long,
                                  isRangeRequest: Boolean, rangeStart: Long, rangeEnd: Long) {
        val rawFileName = session.path.substringAfterLast('/').ifBlank { "media" }
        val safeFileName = rawFileName.replace("\"", "\\\"").replace("\r", "").replace("\n", "")
        val encodedFileName = android.net.Uri.encode(rawFileName, "UTF-8")
        val safeTitle = rawFileName.substringBeforeLast('.').ifBlank { rawFileName }
            .replace("\r", "").replace("\n", "")

        val contentLength = rangeEnd - rangeStart + 1
        val statusLine = if (isRangeRequest) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
        val response = buildString {
            append(statusLine)
            append("Content-Type: ${session.mimeType}\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (isRangeRequest) append("Content-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\n")
            append("Content-Disposition: inline; filename=\"$safeFileName\"; filename*=UTF-8''$encodedFileName\r\n")
            append("icy-name: $safeTitle\r\n")
            append("icy-description: $safeTitle\r\n")
            append("X-Title: $safeTitle\r\n")
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
        val share = if (session.share.type == ShareType.SMB && session.share.isServerMode && session.share.remotePath.isEmpty() && session.path.trimStart('/').contains('/')) {
            val shareName = session.path.trimStart('/').substringBefore('/')
            session.share.copy(remotePath = "/$shareName")
        } else {
            session.share
        }
        return when (share.type) {
            // suppressInvalidateOnReadError = true: the proxy's pinned handle must NOT be
            // auto-invalidated (connection closed) when a read throws — an external player
            // that aborts a request on seek would otherwise destroy the shared SMB/SSH session.
            ShareType.SMB -> SmbShareClient.openRandomAccessFile(
                share, session.path, isWrite = false, dedicated = true,
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
        scheduler.scheduleAtFixedRate({
            val now = System.currentTimeMillis()
            val expired = sessions.entries.filter { now - it.value.lastAccessMs > SESSION_TTL_MS }
            expired.forEach { (uuid, session) ->
                session.cancelIdleHandleCloseLocked()
                session.closeHandle()
                sessions.remove(uuid)
                GoRoLog.d(TAG, "Evicted idle session $uuid")
            }
        }, 1, 1, TimeUnit.MINUTES)
    }
}

