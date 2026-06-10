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

    // ── Session registry ──────────────────────────────────────────────────────

    data class Session(
        val share: NetworkShare,
        val path: String,
        val mimeType: String,
        val fileSize: Long,
        val authToken: String = generateAuthToken(),
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var lastAccessMs: Long = System.currentTimeMillis()
    ) {
        var handle: IRandomAccessFile? = null
        val handleLock = Any()
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
    private val executor = Executors.newCachedThreadPool()

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
        sessions.values.forEach { runCatching { it.handle?.close() } }
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
        runCatching { session?.handle?.close() }
        GoRoLog.d(TAG, "Unregistered session $uuid")
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            try {
                val client = ss.accept()
                executor.submit { handleClient(client) }
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

            // Retrieve or initialize the shared stream handle for this session
            var currentHandle = session.handle
            if (currentHandle == null) {
                synchronized(session.handleLock) {
                    if (session.handle == null) {
                        try {
                            session.handle = openHandleForSession(session)
                        } catch (e: Exception) {
                            GoRoLog.e(TAG, "Failed to open handle for ${session.path}", e)
                        }
                    }
                    currentHandle = session.handle
                }
            }

            if (currentHandle == null) {
                send500(out)
                return
            }

            try {
                streamResponse(out, currentHandle!!, session, fileSize, isRangeRequest, rangeStart, rangeEnd)
            } catch (e: Exception) {
                // If the stream is broken, we DO NOT close the handle here.
                // The client may have just aborted the stream to seek.
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Client handler error", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private fun streamResponse(
        out: OutputStream,
        handle: IRandomAccessFile,
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
            
            val bytesRead: Int
            try {
                bytesRead = handle.read(position, buffer, toRead)
            } catch (e: Exception) {
                GoRoLog.e(TAG, "NAS read failed at offset $position", e)
                // The underlying SMB/SFTP socket died. Nullify the session handle 
                // so the next HTTP request recreates it!
                synchronized(session.handleLock) {
                    if (session.handle === handle) {
                        runCatching { session.handle?.close() }
                        session.handle = null
                    }
                }
                break // Abort streaming this chunk
            }

            if (bytesRead <= 0) break
            out.write(buffer, 0, bytesRead)
            position += bytesRead
            remaining -= bytesRead
        }
        out.flush()
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
    private fun parseRange(rangeHeader: String?, fileSize: Long): Pair<Long, Long> {
        if (rangeHeader == null || fileSize <= 0) return Pair(0L, maxOf(0L, fileSize - 1))
        return try {
            val value = rangeHeader.removePrefix("bytes=").trim()
            val dashIdx = value.indexOf('-')
            
            when {
                // "bytes=-500" -> Last 500 bytes of the file
                dashIdx == 0 -> {
                    val suffixLength = value.substring(1).toLong()
                    val start = maxOf(0L, fileSize - suffixLength)
                    Pair(start, fileSize - 1)
                }
                // "bytes=500-" -> From byte 500 to the end
                dashIdx == value.length - 1 -> {
                    val start = value.substring(0, dashIdx).toLong()
                    Pair(start.coerceIn(0, maxOf(0, fileSize - 1)), maxOf(0, fileSize - 1))
                }
                // "bytes=500-1000" -> Exact range
                dashIdx > 0 -> {
                    val start = value.substring(0, dashIdx).toLong()
                    val end = value.substring(dashIdx + 1).toLong()
                    Pair(start.coerceIn(0, maxOf(0, fileSize - 1)), end.coerceIn(0, maxOf(0, fileSize - 1)))
                }
                else -> Pair(0L, maxOf(0L, fileSize - 1))
            }
        } catch (e: Exception) {
            Pair(0L, maxOf(0L, fileSize - 1))
        }
    }

    /**
     * Open a fresh IRandomAccessFile for the given session.
     * Each HTTP request gets its own handle so seeks are isolated.
     */
    private fun openHandleForSession(session: Session): IRandomAccessFile? {
        return when (session.share.type) {
            ShareType.SMB ->
                SmbShareClient.openRandomAccessFile(session.share, session.path, isWrite = false, dedicated = false)
            ShareType.SFTP, ShareType.SCP ->
                SshShareClient.openRandomAccessFile(session.share, session.path, dedicated = false)
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
            else -> null // FTP/TV: not supported via proxy
        }
    }



    // ── Session eviction ──────────────────────────────────────────────────────

    private fun scheduleSessionEviction() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler.scheduleAtFixedRate({
            val now = System.currentTimeMillis()
            val expired = sessions.entries.filter { now - it.value.lastAccessMs > SESSION_TTL_MS }
            expired.forEach { (uuid, _) ->
                sessions.remove(uuid)
                GoRoLog.d(TAG, "Evicted idle session $uuid")
            }
        }, 10, 10, TimeUnit.MINUTES)
    }
}
