package za.kilowatch.ultimatefilemanager.network

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * High-level NFS client backed by libnfs (native, via JNI).
 *
 * Supports NFSv2, NFSv3, NFSv4 and custom port configuration
 * (bypassing Portmapper). This is the same NFS engine used by VLC and Kodi.
 *
 * Usage:
 * - Preferred over the legacy [NfsShareClient] (EMC nfs-client-java).
 * - Automatically used when libnfs is available (native library loads).
 */
object LibNfsClient {

    private const val TAG = "LibNfsClient"

    /** Whether the native library loaded successfully. */
    val isAvailable: Boolean by lazy {
        try {
            LibNfsBridge // triggers init block / System.loadLibrary
            val h = LibNfsBridge.nfsInit()
            if (h != 0L) {
                LibNfsBridge.nfsDestroy(h)
                true
            } else false
        } catch (e: Throwable) {
            Log.w(TAG, "libnfs not available: ${e.message}")
            false
        }
    }

    /** Sentinel error keys matching [NfsShareClient.ErrorSentinel]. */
    object ErrorSentinel {
        const val STALE_HANDLE = NfsShareClient.ErrorSentinel.STALE_HANDLE
        const val PORTMAPPER_UNREACHABLE = NfsShareClient.ErrorSentinel.PORTMAPPER_UNREACHABLE
        const val PERMISSION_DENIED = NfsShareClient.ErrorSentinel.PERMISSION_DENIED
    }

    /* ── Helpers ─────────────────────────────────────────────────────────────── */

    /**
     * Build an NFS URL from the share config.
     * e.g. `nfs://192.168.1.10/export?nfsport=2049&version=3`
     */
    private fun buildNfsUrl(share: NetworkShare): String {
        val host = share.host
        val export = normalizePath(share.remotePath)
        val params = mutableListOf<String>()

        // If user set a non-default port, pass it to libnfs (bypasses Portmapper)
        val port = share.effectivePort
        if (port != 2049) {
            params.add("nfsport=$port")
        }

        // NFS version from share config (default: v3)
        // share.username is repurposed as UID for NFS; no version field yet
        // Could be extended with a share.nfsVersion property

        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return "nfs://$host$export$query"
    }

    private fun normalizePath(path: String): String {
        var p = path.trim().replace('\\', '/')
        if (!p.startsWith("/")) p = "/$p"
        if (p.length > 1 && p.endsWith("/")) p = p.removeSuffix("/")
        return p
    }

    private fun buildChildPath(parentPath: String, childName: String): String {
        val p = normalizePath(parentPath)
        return if (p == "/") "/$childName" else "$p/$childName"
    }

    /**
     * Create and mount an NFS context for the given share.
     * Returns a pair of (contextHandle, null) on success,
     * or (0, errorMessage) on failure.
     */
    private fun mountContext(share: NetworkShare): Pair<Long, String?> {
        val handle = LibNfsBridge.nfsInit()
        if (handle == 0L) {
            return 0L to "Failed to create NFS context"
        }

        // Set UID/GID
        val uid = share.username.toIntOrNull() ?: 0
        LibNfsBridge.nfsSetUid(handle, uid)
        LibNfsBridge.nfsSetGid(handle, 0)

        // Mount via URL (supports custom port and version)
        val url = buildNfsUrl(share)
        Log.i(TAG, "Mounting: $url")
        val err = LibNfsBridge.nfsMountUrl(handle, url)
        if (err != null) {
            LibNfsBridge.nfsDestroy(handle)
            Log.e(TAG, "Mount failed: $err")
            return 0L to err
        }
        return handle to null
    }

    /**
     * Classify a raw libnfs error message into a sentinel key for the UI.
     */
    private fun classifyError(rawMessage: String): String {
        val msg = rawMessage.lowercase()
        return when {
            msg.contains("stale") || msg.contains("nfs3err_stale") ->
                ErrorSentinel.STALE_HANDLE
            msg.contains("permission denied") || msg.contains("access denied") ||
            msg.contains("nfs3err_acces") ->
                ErrorSentinel.PERMISSION_DENIED
            msg.contains("portmap") || msg.contains("rpcbind") ||
            msg.contains("connection refused") ->
                ErrorSentinel.PORTMAPPER_UNREACHABLE
            else -> rawMessage
        }
    }

    /* ── Public API (mirrors NfsShareClient) ────────────────────────────────── */

    fun testConnection(share: NetworkShare): String? {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) return classifyError(mountErr)

        return try {
            val entries = LibNfsBridge.nfsListDir(handle, "/")
            Log.i(TAG, "testConnection OK — ${entries?.size ?: 0} entries in root")
            null // success
        } catch (e: Exception) {
            Log.e(TAG, "testConnection listDir failed", e)
            classifyError(e.message ?: e.javaClass.simpleName)
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) {
            Log.e(TAG, "listFiles mount failed: $mountErr")
            return emptyList()
        }

        return try {
            val path = normalizePath(remotePath)
            val entries = LibNfsBridge.nfsListDir(handle, path) ?: return emptyList()
            entries.map { raw ->
                // Format: "name\ttype\tsize\tmtime"
                val parts = raw.split("\t")
                val name = parts.getOrElse(0) { "" }
                val type = parts.getOrElse(1) { "f" }
                val size = parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0L
                val mtime = (parts.getOrElse(3) { "0" }.toLongOrNull() ?: 0L) * 1000L
                NetworkFile(
                    name = name,
                    path = "/" + buildChildPath(remotePath, name).trimStart('/'),
                    isDirectory = type == "d",
                    size = if (type == "d") 0L else size,
                    lastModified = mtime
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "listFiles failed for path=$remotePath", e)
            emptyList()
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun getFileSize(share: NetworkShare, remotePath: String): Long? {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) return null

        return try {
            val size = LibNfsBridge.nfsFileSize(handle, normalizePath(remotePath))
            if (size >= 0) size else null
        } catch (e: Exception) {
            Log.e(TAG, "getFileSize failed", e)
            null
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun openInputStream(share: NetworkShare, remotePath: String): InputStream {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")

        val fh = LibNfsBridge.nfsOpen(handle, normalizePath(remotePath), 0)
        if (fh == 0L) {
            LibNfsBridge.nfsDestroy(handle)
            throw IOException("Failed to open file for reading: $remotePath")
        }
        return NfsNativeInputStream(handle, fh)
    }

    fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")

        val fh = LibNfsBridge.nfsOpen(handle, normalizePath(remotePath), 1)
        if (fh == 0L) {
            LibNfsBridge.nfsDestroy(handle)
            throw IOException("Failed to open file for writing: $remotePath")
        }
        return NfsNativeOutputStream(handle, fh)
    }

    fun openRandomAccessFile(
        share: NetworkShare,
        remotePath: String,
        isWrite: Boolean = false
    ): IRandomAccessFile {
        return LibNfsRandomAccess(share, normalizePath(remotePath), isWrite)
    }

    fun mkdir(share: NetworkShare, remotePath: String) {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")
        try {
            val err = LibNfsBridge.nfsMkdir(handle, normalizePath(remotePath))
            if (err != null) throw IOException("mkdir failed: $err")
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun deleteFile(share: NetworkShare, remotePath: String) {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")
        try {
            val err = LibNfsBridge.nfsUnlink(handle, normalizePath(remotePath))
            if (err != null) throw IOException("delete failed: $err")
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun deleteDir(share: NetworkShare, remotePath: String) {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")
        try {
            val err = LibNfsBridge.nfsRmdir(handle, normalizePath(remotePath))
            if (err != null) throw IOException("rmdir failed: $err")
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun rename(share: NetworkShare, fromPath: String, toPath: String) {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")
        try {
            val err = LibNfsBridge.nfsRename(
                handle, normalizePath(fromPath), normalizePath(toPath)
            )
            if (err != null) throw IOException("rename failed: $err")
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    /** List available exports on a server. */
    fun listExports(server: String): List<String> {
        return try {
            LibNfsBridge.nfsListExports(server)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "listExports failed for $server", e)
            emptyList()
        }
    }

    /* ── Stream Wrappers ────────────────────────────────────────────────────── */

    private class NfsNativeInputStream(
        private val ctxHandle: Long,
        private val fileHandle: Long
    ) : InputStream() {
        private var closed = false
        private var position: Long = 0L

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n <= 0) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream closed")
            if (len == 0) return 0
            val res = LibNfsBridge.nfsPread(ctxHandle, fileHandle, position, b, off, len)
            if (res < 0) throw IOException("NFS read failed")
            if (res == 0) return -1 // EOF
            position += res
            return res
        }

        override fun close() {
            if (!closed) {
                closed = true
                LibNfsBridge.nfsClose(ctxHandle, fileHandle)
                LibNfsBridge.nfsDestroy(ctxHandle)
            }
        }
    }

    private class NfsNativeOutputStream(
        private val ctxHandle: Long,
        private val fileHandle: Long
    ) : OutputStream() {
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            var bytesWritten = 0
            while (bytesWritten < len) {
                val toWrite = len - bytesWritten
                val res = LibNfsBridge.nfsWrite(ctxHandle, fileHandle, b, off + bytesWritten, toWrite)
                if (res < 0) throw IOException("NFS write failed")
                if (res == 0) throw IOException("NFS write returned 0 bytes")
                bytesWritten += res
            }
        }

        override fun close() {
            if (!closed) {
                closed = true
                LibNfsBridge.nfsClose(ctxHandle, fileHandle)
                LibNfsBridge.nfsDestroy(ctxHandle)
            }
        }
    }

    /** Random-access wrapper using libnfs pread/pwrite. */
    class LibNfsRandomAccess(
        private val share: NetworkShare,
        private val remotePath: String,
        isWrite: Boolean
    ) : IRandomAccessFile {
        private var ctxHandle: Long = 0L
        private var fileHandle: Long = 0L
        private var cachedSize: Long = -1L

        init {
            val (h, err) = mountContext(share)
            if (err != null) throw IOException("NFS mount failed: $err")
            ctxHandle = h
            fileHandle = LibNfsBridge.nfsOpen(h, remotePath, if (isWrite) 2 else 0)
            if (fileHandle == 0L) {
                LibNfsBridge.nfsDestroy(ctxHandle)
                throw IOException("Failed to open file: $remotePath")
            }
        }

        override val size: Long
            get() = synchronized(this) {
                if (cachedSize < 0) {
                    cachedSize = LibNfsBridge.nfsFileSize(ctxHandle, remotePath)
                    if (cachedSize < 0) throw IOException("Failed to get file size")
                }
                return cachedSize
            }

        override fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(this) {
            if (offset >= size) return -1
            var bytesRead = 0
            val toReadTotal = minOf(length.toLong(), size - offset).toInt()
            if (toReadTotal <= 0) return -1
            while (bytesRead < toReadTotal) {
                val toRead = toReadTotal - bytesRead
                val res = LibNfsBridge.nfsPread(
                    ctxHandle, fileHandle, offset + bytesRead, buffer, bytesRead, toRead
                )
                if (res < 0) throw IOException("NFS random-access read failed")
                if (res == 0) break // EOF reached earlier than expected
                bytesRead += res
            }
            return if (bytesRead == 0) -1 else bytesRead
        }

        override fun write(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(this) {
            var bytesWritten = 0
            while (bytesWritten < length) {
                val toWrite = length - bytesWritten
                val res = LibNfsBridge.nfsPwrite(
                    ctxHandle, fileHandle, offset + bytesWritten, buffer, bytesWritten, toWrite
                )
                if (res < 0) throw IOException("NFS random-access write failed")
                if (res == 0) throw IOException("NFS random-access write returned 0 bytes")
                bytesWritten += res
            }
            return bytesWritten
        }

        override fun close() = synchronized(this) {
            if (fileHandle != 0L) {
                LibNfsBridge.nfsClose(ctxHandle, fileHandle)
                fileHandle = 0L
            }
            if (ctxHandle != 0L) {
                LibNfsBridge.nfsDestroy(ctxHandle)
                ctxHandle = 0L
            }
        }
    }
}
