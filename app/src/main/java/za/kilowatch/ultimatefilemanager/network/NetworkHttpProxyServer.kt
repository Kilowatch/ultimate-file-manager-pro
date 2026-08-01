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
 * Local HTTP/1.1 proxy server that serves network files to external media players (VLC, MX Player, etc.)
 * via standard HTTP Range requests — enabling full seekable playback without FUSE mount points.
 *
 * Architecture:
 *   External Player ──GET /uuid ──→  NetworkHttpProxyServer (127.0.0.1:random)
 *                                          │
 *                                  IRandomAccessFile.read(offset)
 *                                          │
 *                              SMB / SFTP / Cloud network
 *
 * Usage:
 *   val url = NetworkHttpProxyServer.register(share, "/Movies/film.mkv", "video/x-matroska")
 *   // Pass `url` (http://127.0.0.1:PORT/uuid) to an external player Intent
 *   // The server handles Range requests from the player transparently.
 *
 * The server picks a random available port on startup and falls back automatically if the
 * initial bind fails (extremely rare but possible on locked-down devices).
 */
object NetworkHttpProxyServer {

    private const val TAG = "NetworkHttpProxy"

    /** How long an idle session is kept alive before being evicted (ms). */
    private const val SESSION_TTL_MS = 60 * 60 * 1000L // 1 hour

    /** Maximum bytes to stream in one HTTP response chunk. */
    private const val CHUNK_SIZE = 256 * 1024 // 256 KB

    /** Max attempts for a single chunk read before the stream is declared dead. */
    private const val MAX_READ_ATTEMPTS = 3

    /** Backoff between chunk-read retries (ms). */
    private const val READ_RETRY_BACKOFF_MS = 150L

    // ── Session registry ──────────────────────────────────────────────────────

    /**
     * A registered stream that an external player can open.
     *
     * The session owns the pinned streaming [handle]: it is opened lazily on the
     * first HTTP request and kept alive for the session's lifetime, so seeks
     * across requests reuse one dedicated connection instead of paying a fresh
     * handshake each time. The handle is only released on session eviction,
     * [unregister], or proxy [stop].
     */
    class Session(
        val share: NetworkShare,
        val path: String,
        val mimeType: String,
        val fileSize: Long,
        val authToken: String = generateAuthToken(),
        val createdAt: Long = System.currentTimeMillis()
    ) {
        /** Last time any HTTP request touched this session (used for idle eviction). */
        @Volatile var lastAccessMs: Long = System.currentTimeMillis()

        /** The pinned streaming handle for this session. Opened lazily, owned here. */
        @Volatile var handle: IRandomAccessFile? = null

        /** Set after a fatal read error so the next request reopens a fresh handle. */
        @Volatile var handleInvalid: Boolean = false

        /** Serializes lazy open/reopen of [handle] (double-checked locking). */
        val createLock = Any()

        /** Serializes individual [IRandomAccessFile.read] calls on the shared handle. */
        val readLock = Any()

        /** Close the pinned handle and reset its state. Safe to call repeatedly. */
        fun closeHandle() {
            synchronized(createLock) {
                runCatching { handle?.close() }
                handle = null
                handleInvalid = false
            }
        }
    }

    private fun generateAuthToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    // ── Server lifecycle ──────────────────────────────────────────────────────

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var port: Int = 0

    /**
     * Bounded cached-style thread pool for client connections. Threads spawn on demand
     * and die after 60 s idle, but the pool is capped at 32 so a connection storm
     * (pathological players, leaked sockets) cannot exhaust threads. Excess connections
     * are rejected in [acceptLoop] and closed.
     */
    private val executor = ThreadPoolExecutor(
        0, 32, 60L, TimeUnit.SECONDS, SynchronousQueue()
    )

    /**
     * Start the HTTP proxy server on a random available port.
     * Safe to call multiple times — no-ops if already running.
     */
    @Synchronized
    fun start() {
        if (serverSocket != null) return
        try {
            // Port 0 = OS picks any available port
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

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Register a network file for streaming and return its proxy URL.
     *
     * @param share     The network share the file lives on.
     * @param path      The remote path of the file on the share.
     * @param mimeType  MIME type to advertise in Content-Type headers.
     * @return          A `http://127.0.0.1:PORT/uuid` URL the external player can open.
     */
    fun register(share: NetworkShare, path: String, mimeType: String, fileSize: Long): String {
        if (!isRunning) start()

        val uuid = UUID.randomUUID().toString()
        val session = Session(share, path, mimeType, fileSize)
        sessions[uuid] = session
        GoRoLog.d(TAG, "Registered session $uuid → ${share.host}$path (size=$fileSize)")
        
        // Append the file name so external players like VLC can determine the container format from the extension
        val fileName = android.net.Uri.encode(path.substringAfterLast('/'))
        return "http://127.0.0.1:$port/$uuid/$fileName?auth=${session.authToken}"
    }

    /** Remove a session immediately (call when the player activity is destroyed). */
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
                try {
                    executor.submit { handleClient(client) }
                } catch (e: RejectedExecutionException) {
                    // Executor saturated — drop this connection rather than leak a thread.
                    runCatching { client.close() }
                    GoRoLog.w(TAG, "Proxy executor saturated; closing new connection", null)
                }
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

            // Read request line
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1].trimStart('/')

            // Read headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line.isNullOrBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            // Parse query string for auth token
            val pathWithoutQuery = fullPath.substringBefore('?')
            val queryString = fullPath.substringAfter('?', "")
            val queryParams = queryString.split("&").mapNotNull { kv ->
                val eq = kv.split("=", limit = 2)
                if (eq.size == 2) eq[0] to eq[1] else null
            }.toMap()

            val uuid = pathWithoutQuery.substringBefore('/')
            val session = sessions[uuid]
            if (session == null || queryParams["auth"] != session.authToken) {
                send403(out)
                return
            }
            session.lastAccessMs = System.currentTimeMillis()

            val fileSize = session.fileSize

            // Parse Range header: "bytes=X-Y" or "bytes=X-"
            val rangeHeader = headers["range"]
            val (rangeStart, rangeEnd) = parseRange(rangeHeader, fileSize)
            val isRangeRequest = rangeHeader != null

            if (method == "HEAD") {
                sendHeadResponse(out, session, fileSize, isRangeRequest, rangeStart, rangeEnd)
                return
            }

            // Resolve the session's pinned streaming handle (opened lazily, kept
            // alive for the session's lifetime — never closed per request).
            val clientHandle = try {
                getOrCreateHandle(session)
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Failed to open handle for ${session.path}", e)
                null
            }

            if (clientHandle == null) {
                send500(out)
                return
            }

            try {
                streamResponse(out, session, fileSize, isRangeRequest, rangeStart, rangeEnd)
            } catch (e: Exception) {
                // Client aborted or socket closed
            }
            // NOTE: the handle is pinned to the session and must NOT be closed here.
            // It is released on session eviction, unregister, or proxy stop.
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Client handler error", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private fun streamResponse(
        out: OutputStream,
        session: Session,
        fileSize: Long,
        isRangeRequest: Boolean,
        rangeStart: Long,
        rangeEnd: Long
    ) {
        val contentLength = rangeEnd - rangeStart + 1
        val statusLine = if (isRangeRequest) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"

        val responseHeaders = buildString {
            append(statusLine)
            append("Content-Type: ${session.mimeType}\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (isRangeRequest) {
                append("Content-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(responseHeaders.toByteArray(Charsets.US_ASCII))
        out.flush()

        val buffer = ByteArray(CHUNK_SIZE)
        var position = rangeStart
        var remaining = contentLength

        while (remaining > 0) {
            val toRead = minOf(CHUNK_SIZE.toLong(), remaining).toInt()

            // Concurrent player connections (separate audio/video tracks, read-ahead)
            // share the session's single pinned handle. Reads are offset-based so they
            // interleave safely, but each read call must be serialized on the handle —
            // it happens under readLock. Socket writes stay OUTSIDE the lock so a slow
            // client never holds the shared handle.
            val bytesRead = readChunkWithRetry(session, position, buffer, toRead)

            if (bytesRead <= 0) break
            out.write(buffer, 0, bytesRead)
            position += bytesRead
            remaining -= bytesRead
        }
        out.flush()
    }

    /**
     * Read one chunk at [offset], retrying transient failures before giving up.
     *
     * On a read error the current handle is discarded (SMB's `SmbRandomAccess` already
     * closes its connection on error) and a fresh dedicated one is opened for the same
     * offset, so a one-off blip — NAS disk spin-up, a reset Wi-Fi packet — recovers
     * instead of aborting the whole response. Returns the bytes read, or -1 once the
     * retry budget is exhausted.
     */
    internal fun readChunkWithRetry(session: Session, offset: Long, buffer: ByteArray, length: Int): Int {
        var attempts = 0
        while (attempts < MAX_READ_ATTEMPTS) {
            attempts++
            try {
                // Attempt 1 reuses the pinned handle; later attempts force a fresh one,
                // because a failed read means the handle (and its connection) is suspect.
                val handle = getOrCreateHandle(session, forceReopen = attempts > 1)
                if (handle == null) return -1
                // Lock scope = the read call only, so a slow client or a retry backoff
                // never blocks a concurrent request's chunk read.
                return synchronized(session.readLock) { handle.read(offset, buffer, length) }
            } catch (e: Exception) {
                if (attempts >= MAX_READ_ATTEMPTS) {
                    GoRoLog.e(TAG, "NAS read failed at offset $offset after $MAX_READ_ATTEMPTS attempts", e)
                } else {
                    GoRoLog.w(TAG, "NAS read failed at offset $offset (attempt $attempts/$MAX_READ_ATTEMPTS)", null)
                }
                if (attempts < MAX_READ_ATTEMPTS) {
                    try { Thread.sleep(READ_RETRY_BACKOFF_MS) } catch (_: InterruptedException) { break }
                }
            }
        }
        // Budget exhausted — mark the session invalid so the next request reopens it,
        // and signal the streaming loop to abort (clean truncated-response error).
        session.handleInvalid = true
        return -1
    }

    private fun sendHeadResponse(
        out: OutputStream,
        session: Session,
        fileSize: Long,
        isRangeRequest: Boolean,
        rangeStart: Long,
        rangeEnd: Long
    ) {
        val contentLength = rangeEnd - rangeStart + 1
        val statusLine = if (isRangeRequest) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
        val response = buildString {
            append(statusLine)
            append("Content-Type: ${session.mimeType}\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (isRangeRequest) {
                append("Content-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(response.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun send403(out: OutputStream) {
        val r = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching { out.write(r.toByteArray()); out.flush() }
    }

    private fun send404(out: OutputStream) {
        val r = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching { out.write(r.toByteArray()); out.flush() }
    }

    private fun send500(out: OutputStream) {
        val r = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching { out.write(r.toByteArray()); out.flush() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parse the Range header into (start, end) byte positions (inclusive).
     * Returns (0, fileSize-1) if no Range header is present.
     */
    internal fun parseRange(rangeHeader: String?, fileSize: Long): Pair<Long, Long> {
        if (rangeHeader == null || fileSize <= 0) return Pair(0L, maxOf(0L, fileSize - 1))
        return try {
            val value = rangeHeader.removePrefix("bytes=").trim()
            val dashIdx = value.indexOf('-')

            val (rawStart, rawEnd) = when {
                // "bytes=-500" -> Last 500 bytes of the file
                dashIdx == 0 -> {
                    val suffixLength = value.substring(1).toLong()
                    val start = maxOf(0L, fileSize - suffixLength)
                    Pair(start, fileSize - 1)
                }
                // "bytes=500-" -> From byte 500 to the end
                dashIdx == value.length - 1 -> {
                    val start = value.substring(0, dashIdx).toLong()
                    Pair(start, fileSize - 1)
                }
                // "bytes=500-1000" -> Exact range
                dashIdx > 0 -> {
                    val start = value.substring(0, dashIdx).toLong()
                    val end = value.substring(dashIdx + 1).toLong()
                    Pair(start, end)
                }
                else -> Pair(0L, fileSize - 1)
            }
            normalizeRange(rawStart, rawEnd, fileSize)
        } catch (e: Exception) {
            Pair(0L, maxOf(0L, fileSize - 1))
        }
    }

    /**
     * Clamp a byte range to the file and guarantee start <= end, so the response's
     * Content-Length is always >= 1 — a reversed or out-of-bounds range must never
     * produce a negative Content-Length.
     */
    private fun normalizeRange(rawStart: Long, rawEnd: Long, fileSize: Long): Pair<Long, Long> {
        val last = maxOf(0L, fileSize - 1)
        val start = rawStart.coerceIn(0L, last)
        val end = rawEnd.coerceIn(start, last)
        return Pair(start, end)
    }

    /**
     * Handle-factory seam for the pinned streaming handle. Defaults to the real
     * [openHandleForSession]; tests override it to inject a fake [IRandomAccessFile]
     * so the retry/reopen logic is unit-testable without sockets.
     */
    internal var handleFactory: (Session) -> IRandomAccessFile? = ::openHandleForSession

    /**
     * Returns the session's pinned streaming handle, opening a fresh dedicated one
     * if needed. Each session owns one handle for its whole lifetime, so seeks on
     * different HTTP requests reuse the same connection instead of paying a fresh
     * handshake per request.
     *
     * SMB/SFTP/SCP handles are opened as *dedicated* connections — never pooled —
     * because pooled SMB sessions carry a short socket timeout that breaks slow
     * seeks (external players close or hang when the stream aborts mid-response).
     *
     * @param forceReopen close the current handle (if any) and open a new one.
     * @return the handle, or null if opening failed (the caller responds 500).
     */
    internal fun getOrCreateHandle(session: Session, forceReopen: Boolean = false): IRandomAccessFile? {
        val current = session.handle
        if (current != null && !session.handleInvalid && !forceReopen) return current

        synchronized(session.createLock) {
            // Double-checked: another thread may have opened/repaired it while we waited.
            val current2 = session.handle
            if (current2 != null && !session.handleInvalid && !forceReopen) return current2

            if (forceReopen || session.handleInvalid) {
                runCatching { session.handle?.close() }
                session.handle = null
                session.handleInvalid = false
            }

            val handle = handleFactory(session)
            if (handle != null) {
                session.handle = handle
            } else {
                // Failed to open — stay marked invalid so the next request retries.
                session.handleInvalid = true
            }
            return handle
        }
    }

    /**
     * Open a fresh streaming handle for the given session. Each session's handle is
     * dedicated (not pooled) so long-lived, seekable streaming never hits the pooled
     * connections' short socket timeout.
     */
    internal fun openHandleForSession(session: Session): IRandomAccessFile? {
        return when (session.share.type) {
            ShareType.SMB ->
                SmbShareClient.openRandomAccessFile(session.share, session.path, isWrite = false, dedicated = true)
            ShareType.SFTP, ShareType.SCP ->
                SshShareClient.openRandomAccessFile(session.share, session.path, dedicated = true)
            ShareType.GOOGLE_DRIVE ->
                GoogleDriveShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.ONEDRIVE ->
                OnedriveShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.DROPBOX ->
                DropboxShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 ->
                S3ShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openRandomAccessFile(
                session.share, session.path
            )
            ShareType.DLNA -> DlnaShareClient.openRandomAccessFile(session.share, session.path)
            ShareType.WEBDAV -> WebDavShareClient.openRandomAccessFile(session.share, session.path, session.fileSize)
            else -> null // FTP/TV: not supported via proxy
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
