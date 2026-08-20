package za.kilowatch.ultimatefilemanager.network

import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Thin wrapper around Apache Commons Net FTPClient.
 * Uses passive mode (PASV) by default — works behind NAT and TV firewalls.
 * Connections are opened and closed per call.
 */
object FtpShareClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val DATA_TIMEOUT_MS    = 0  // Infinite — large file transfers must not time out mid-stream

    // ── Read operations ───────────────────────────────────────────────────────

    fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> {
        return withFtp(share) { ftp ->
            val path = joinPath(share.remotePath, remotePath)
            val files: Array<FTPFile> = ftp.listFiles(path) ?: emptyArray()
            files.filter { it.name != "." && it.name != ".." }.map { f ->
                NetworkFile(
                    name         = f.name,
                    path         = joinPath(remotePath, f.name),
                    isDirectory  = f.isDirectory,
                    size         = f.size,
                    lastModified = f.timestamp?.timeInMillis ?: 0L
                )
            }
        }
    }

    /** Query the server for the size of a single remote file. Returns null if unavailable. */
    fun getFileSize(share: NetworkShare, remotePath: String): Long? {
        return runCatching {
            withFtp(share) { ftp ->
                val path = joinPath(share.remotePath, remotePath)
                val file = ftp.mlistFile(path)
                if (file != null && file.isFile) {
                    file.size
                } else {
                    // Fallback: list the specific path; many FTP servers return a 1-element array for a file path
                    val files = ftp.listFiles(path)
                    if (files != null && files.isNotEmpty() && files[0].isFile) {
                        files[0].size
                    } else {
                        null
                    }
                }
            }
        }.getOrNull()
    }

    suspend fun uploadStream(share: NetworkShare, remotePath: String, inputStream: InputStream, totalSize: Long) {
        val ftp = buildClientOnIo(share)
        val path = joinPath(share.remotePath, remotePath)
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        
        val out = ftp.storeFileStream(path)
            ?: throw java.io.IOException("Could not open FTP output stream: ${ftp.replyString}")
            
        out.use { 
            za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inputStream, it, totalSize)
        }
        
        if (!ftp.completePendingCommand()) {
            throw java.io.IOException("FTP upload completion failed: ${ftp.replyString}")
        }
        
        runCatching { ftp.logout() }
        runCatching { ftp.disconnect() }
    }

    suspend fun openInputStream(share: NetworkShare, remotePath: String, startOffset: Long = 0): InputStream {
        val ftp = buildClientOnIo(share)
        val path = joinPath(share.remotePath, remotePath)
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        
        // Performance: Use REST command to jump directly to any byte offset
        if (startOffset > 0) {
            ftp.setRestartOffset(startOffset)
        }
        
        val raw = ftp.retrieveFileStream(path)
            ?: throw java.io.IOException("Could not open FTP stream: ${ftp.replyString}")
        return object : InputStream() {
            override fun read(): Int = raw.read()
            override fun read(b: ByteArray, off: Int, len: Int) = raw.read(b, off, len)
            override fun close() {
                runCatching { raw.close() }
                runCatching { ftp.completePendingCommand() }
                runCatching { ftp.logout() }
                runCatching { ftp.disconnect() }
            }
        }
    }

    fun openRandomAccessFile(share: NetworkShare, remotePath: String): IRandomAccessFile {
        val fileSize = getFileSize(share, remotePath) ?: 0L
        return object : IRandomAccessFile {
            override var size = fileSize

            private var currentFtp: FTPClient? = null
            private var currentStream: InputStream? = null
            private var currentPosition: Long = -1L

            override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                if (offset >= size) return -1

                var retries = 0
                while (retries < 2) {
                    try {
                        if (currentStream == null || offset != currentPosition) {
                            closeInternal()
                            val ftp = buildClient(share)
                            val fullPath = joinPath(share.remotePath, remotePath)
                            ftp.setRestartOffset(offset)
                            val stream = ftp.retrieveFileStream(fullPath)
                            if (stream == null) {
                                runCatching { ftp.disconnect() }
                                return -1
                            }
                            currentFtp = ftp
                            currentStream = stream
                            currentPosition = offset
                        }

                        val stream = currentStream!!
                        var totalRead = 0
                        while (totalRead < length) {
                            val rd = stream.read(buffer, totalRead, length - totalRead)
                            if (rd <= 0) break
                            totalRead += rd
                        }
                        
                        if (totalRead > 0) {
                            currentPosition += totalRead
                            return totalRead
                        } else {
                            return -1 // EOF
                        }
                    } catch (e: Exception) {
                        closeInternal()
                        retries++
                        if (retries >= 2) throw e
                    }
                }
                return -1
            }

            override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
                return 0 // Unsupported
            }

            private fun closeInternal() {
                val ftp = currentFtp
                val stream = currentStream
                currentStream = null
                currentFtp = null
                currentPosition = -1L
                
                // Disconnect first to aggressively kill the underlying sockets.
                // If we call stream.close() while the socket is active, Commons Net 
                // will attempt to download the ENTIRE rest of the file to "cleanly" finish!
                runCatching { ftp?.disconnect() }
                runCatching { stream?.close() }
            }

            override fun close() {
                closeInternal()
            }
        }
    }

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Opens a write stream to a remote file. Creates or overwrites it.
     * Closing the returned OutputStream also closes the FTP connection.
     */
    suspend fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream {
        val ftp = buildClientOnIo(share)
        val fullPath = joinPath(share.remotePath, remotePath)
        za.kilowatch.ultimatefilemanager.util.GoRoLog.d("FtpClient", "OPEN OUTPUT STREAM fullPath: $fullPath (share.remotePath: ${share.remotePath}, remotePath: $remotePath)")
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        val raw = ftp.storeFileStream(fullPath)
            ?: throw java.io.IOException("Could not open FTP write stream: ${ftp.replyString}")
        return object : OutputStream() {
            override fun write(b: Int) = raw.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = raw.write(b, off, len)
            override fun flush() = raw.flush()
            override fun close() {
                runCatching { raw.close() }
                runCatching { ftp.completePendingCommand() }
                runCatching { ftp.logout() }
                runCatching { ftp.disconnect() }
            }
        }
    }

    fun mkdir(share: NetworkShare, remotePath: String) {
        withFtp(share) { ftp ->
            val fullPath = joinPath(share.remotePath, remotePath)
            za.kilowatch.ultimatefilemanager.util.GoRoLog.d("FtpClient", "MKDIR fullPath: $fullPath (share.remotePath: ${share.remotePath}, remotePath: $remotePath)")
            if (!ftp.makeDirectory(fullPath))
                throw java.io.IOException("FTP mkdir failed: ${ftp.replyString}")
        }
    }

    fun deleteFile(share: NetworkShare, remotePath: String) {
        withFtp(share) { ftp ->
            val path = joinPath(share.remotePath, remotePath)
            if (!ftp.deleteFile(path))
                throw java.io.IOException("FTP deleteFile failed: ${ftp.replyString}")
        }
    }

    fun deleteDir(share: NetworkShare, remotePath: String) {
        withFtp(share) { ftp ->
            val path = joinPath(share.remotePath, remotePath)
            // FTP only supports removeDirectory for empty dirs; recursion handled by caller
            if (!ftp.removeDirectory(path))
                throw java.io.IOException("FTP rmdir failed: ${ftp.replyString}")
        }
    }

    fun rename(share: NetworkShare, fromPath: String, toPath: String) {
        withFtp(share) { ftp ->
            val from = joinPath(share.remotePath, fromPath)
            val to   = joinPath(share.remotePath, toPath)
            if (!ftp.rename(from, to))
                throw java.io.IOException("FTP rename failed: ${ftp.replyString}")
        }
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    /** Returns null on success, or an error message string on failure. */
    fun testConnection(share: NetworkShare): String? {
        return runCatching {
            withFtp(share) { null }
        }.getOrElse { e -> e.message ?: e.javaClass.simpleName }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun <T> withFtp(share: NetworkShare, block: (FTPClient) -> T): T {
        val ftp = buildClient(share)
        return try {
            block(ftp)
        } finally {
            runCatching { ftp.logout() }
            runCatching { ftp.disconnect() }
        }
    }

    /**
     * Suspends to the IO dispatcher so the blocking connect/login never runs on the
     * calling coroutine's thread (e.g. Dispatchers.Main). If the coroutine was cancelled
     * while the blocking connect was in flight, the connection failure is surfaced as a
     * normal cancellation (CancellationException) instead of a fatal non-cancellation
     * exception — a cancelled coroutine that throws a plain IOException crashes the app.
     */
    private suspend fun buildClientOnIo(share: NetworkShare): FTPClient = withContext(Dispatchers.IO) {
        try {
            buildClient(share)
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        }
    }

    private fun buildClient(share: NetworkShare): FTPClient {
        val ftp = FTPClient()
        ftp.connectTimeout = CONNECT_TIMEOUT_MS
        @Suppress("DEPRECATION")
        ftp.setDataTimeout(DATA_TIMEOUT_MS)  // commons-net 3.9: Int overload deprecated but functional

        // Performance: Set a large internal buffer (1MB) to reduce overhead
        ftp.setBufferSize(1024 * 1024)
        // Stability: Prevent control connection timeout during long transfers
        ftp.setControlKeepAliveTimeout(30)

        try {
            // The blocking socket connect is pure network I/O and must never execute on the
            // main thread. If a caller has invoked FTP from the main dispatcher (a caller
            // bug), offload the blocking work to an IO worker so the main looper is never
            // parked inside a native connect.
            if (isMainThread()) {
                runBlocking(Dispatchers.IO) { connectAndLogin(ftp, share) }
            } else {
                connectAndLogin(ftp, share)
            }
        } catch (e: IOException) {
            runCatching { ftp.disconnect() }
            val friendly = if (e is FtpConnectionException) e else FtpConnectionException(share.host, share.effectivePort, e)
            if (isMainThread()) {
                // A connection failure on the main thread would otherwise escape as an uncaught
                // non-cancellation exception from a (possibly cancelled) Main-dispatcher coroutine
                // and crash the app. Surface it as a cancellation so the coroutine completes
                // normally; the correct IO-dispatched call sites still receive the
                // FtpConnectionException and surface the connection error to the user.
                throw CancellationException(friendly.message)
            }
            throw friendly
        }
        return ftp
    }

    private fun connectAndLogin(ftp: FTPClient, share: NetworkShare) {
        ftp.connect(share.host, share.effectivePort)
        val ok = if (share.username.isBlank()) {
            ftp.login("anonymous", "UFM@android")
        } else {
            ftp.login(share.username, share.password)
        }
        if (!ok) throw java.io.IOException("FTP login failed: ${ftp.replyString}")
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        ftp.setListHiddenFiles(true)
    }

    private fun isMainThread(): Boolean =
        Looper.myLooper() == Looper.getMainLooper()

    private fun joinPath(base: String, sub: String): String {
        if (sub.isBlank()) return base.ifBlank { "/" }
        return base.trimEnd('/') + "/" + sub.trimStart('/')
    }
}

/**
 * Thrown when the FTP control connection cannot be established (unreachable host,
 * connect timeout, refused connection, unknown host, ...). Carries the target
 * host/port so error messages are actionable.
 */
class FtpConnectionException(host: String, port: Int, cause: Throwable?) :
    IOException("Failed to connect to FTP server $host:$port — ${cause?.message ?: "connection failed"}", cause)
