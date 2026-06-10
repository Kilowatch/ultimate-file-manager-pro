package za.kilowatch.ultimatefilemanager.network

import android.util.Log
import com.emc.ecs.nfsclient.nfs.NfsSetAttributes
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.io.NfsFileOutputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

object NfsShareClient {

    private const val TAG = "NfsShareClient"

    /** Whether the native libnfs backend is available (preferred over legacy EMC library). */
    private val useNative: Boolean by lazy {
        val available = LibNfsClient.isAvailable
        Log.i(TAG, if (available) "Using native libnfs backend" else "Falling back to EMC nfs-client-java")
        available
    }

    /** Sentinel error keys that can be mapped to localised strings by the UI layer. */
    object ErrorSentinel {
        const val STALE_HANDLE = "NFS_STALE_HANDLE"
        const val PORTMAPPER_UNREACHABLE = "NFS_PORTMAPPER_UNREACHABLE"
        const val PERMISSION_DENIED = "NFS_PERMISSION_DENIED"
    }

    fun testConnection(share: NetworkShare): String? {
        if (useNative) return LibNfsClient.testConnection(share)

        // 1. TCP connect test on the user-configured NFS port
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(share.host, share.effectivePort), 1500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP connect to ${share.host}:${share.effectivePort} failed", e)
            return "TCP connect failed: ${e.message}"
        }

        // 2. Check if Portmapper (port 111) is reachable — the EMC NFS library requires it
        val portmapperReachable = try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(share.host, 111), 1500)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Portmapper (port 111) on ${share.host} is not reachable: ${e.message}")
            false
        }

        if (!portmapperReachable) {
            return ErrorSentinel.PORTMAPPER_UNREACHABLE
        }

        // 3. Attempt NFS MOUNT + READDIR
        return runCatching {
            val nfs = createNfs(share)
            val rootFile = Nfs3File(nfs, "/")
            rootFile.listFiles()
            Log.i(TAG, "NFS test connection to ${share.host}:${share.remotePath} succeeded")
            null
        }.getOrElse { e ->
            Log.e(TAG, "NFS test connection failed for ${share.host}:${share.remotePath}", e)
            val msg = e.message ?: e.javaClass.simpleName
            classifyNfsError(msg)
        }
    }

    fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> {
        if (useNative) return LibNfsClient.listFiles(share, remotePath)

        return runCatching {
            val nfs = createNfs(share)
            val dir = Nfs3File(nfs, normalizePath(remotePath))
            dir.listFiles().map { entry ->
                NetworkFile(
                    name = entry.getName(),
                    path = "/" + buildChildPath(remotePath, entry.getName()).trimStart('/'),
                    isDirectory = entry.isDirectory(),
                    size = if (entry.isDirectory()) 0L else entry.lengthEx(),
                    lastModified = entry.lastModified()
                )
            }.filter { it.name != "." && it.name != ".." }
        }.getOrElse { e ->
            Log.e(TAG, "listFiles failed for ${share.host}:${share.remotePath} path=$remotePath", e)
            emptyList()
        }
    }

    fun getFileSize(share: NetworkShare, remotePath: String): Long? {
        if (useNative) return LibNfsClient.getFileSize(share, remotePath)

        return runCatching {
            val nfs = createNfs(share)
            val file = Nfs3File(nfs, normalizePath(remotePath))
            file.lengthEx()
        }.getOrElse { e ->
            Log.e(TAG, "getFileSize failed for ${share.host}:$remotePath", e)
            null
        }
    }

    fun openInputStream(share: NetworkShare, remotePath: String): InputStream {
        if (useNative) return LibNfsClient.openInputStream(share, remotePath)

        val nfs = createNfs(share)
        val file = Nfs3File(nfs, normalizePath(remotePath))
        return NfsFileInputStream(file)
    }

    fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream {
        if (useNative) return LibNfsClient.openOutputStream(share, remotePath)

        val nfs = createNfs(share)
        val file = Nfs3File(nfs, normalizePath(remotePath))
        return NfsFileOutputStream(file)
    }

    fun openRandomAccessFile(
        share: NetworkShare,
        remotePath: String,
        isWrite: Boolean = false
    ): IRandomAccessFile {
        if (useNative) return LibNfsClient.openRandomAccessFile(share, remotePath, isWrite)

        return NfsRandomAccess(share, normalizePath(remotePath))
    }

    class NfsRandomAccess(
        private val share: NetworkShare,
        private val remotePath: String
    ) : IRandomAccessFile {
        private var nfs: Nfs3 = createNfs(share)
        private var file: Nfs3File = Nfs3File(nfs, normalizePath(remotePath))
        private var cachedSize: Long = -1L

        override val size: Long get() {
            if (cachedSize < 0L) {
                try {
                    cachedSize = file.lengthEx()
                } catch (e: Exception) {
                    Log.w(TAG, "NfsRandomAccess.size failed, attempting reconnect", e)
                    if (reconnect()) {
                        cachedSize = file.lengthEx()
                    } else {
                        throw IOException("Failed to get file size")
                    }
                }
            }
            return cachedSize
        }

        private fun reconnect(): Boolean {
            return try {
                val newNfs = createNfs(share)
                val newFile = Nfs3File(newNfs, normalizePath(remotePath))
                nfs = newNfs
                file = newFile
                Log.i(TAG, "NfsRandomAccess reconnected successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "NfsRandomAccess reconnect failed", e)
                false
            }
        }

        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            var lastError: Exception? = null
            for (attempt in 0..4) {
                try {
                    if (offset >= size) return -1
                    val toRead = minOf(length.toLong(), size - offset).toInt()
                    if (toRead <= 0) return -1
                    val response = file.read(offset, toRead, buffer, 0)
                    val bytesRead = response.bytesRead
                    return if (bytesRead <= 0) -1 else bytesRead
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "NfsRandomAccess.read attempt $attempt failed at offset $offset", e)
                    if (attempt < 4) {
                        try { Thread.sleep(500L * (attempt + 1)) } catch (_: InterruptedException) {}
                        if (reconnect()) cachedSize = -1L
                    }
                }
            }
            throw lastError ?: IOException("Read failed after 5 attempts")
        }

        override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
            var lastError: Exception? = null
            for (attempt in 0..4) {
                try {
                    file.write(offset, listOf(ByteBuffer.wrap(buffer, 0, length)), 2)
                    return length
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "NfsRandomAccess.write attempt $attempt failed at offset $offset", e)
                    if (attempt < 4) {
                        try { Thread.sleep(500L * (attempt + 1)) } catch (_: InterruptedException) {}
                        if (reconnect()) cachedSize = -1L
                    }
                }
            }
            throw lastError ?: IOException("Write failed after 5 attempts")
        }

        override fun close() {
            runCatching {
                val m = nfs.javaClass.getMethod("close")
                m.invoke(nfs)
            }
        }
    }

    fun mkdir(share: NetworkShare, remotePath: String) {
        if (useNative) return LibNfsClient.mkdir(share, remotePath)

        val nfs = createNfs(share)
        val dir = Nfs3File(nfs, normalizePath(remotePath))
        val attrs = NfsSetAttributes()
        attrs.setMode(0x01C0L)
        dir.mkdir(attrs)
    }

    fun deleteFile(share: NetworkShare, remotePath: String) {
        if (useNative) return LibNfsClient.deleteFile(share, remotePath)

        val nfs = createNfs(share)
        val file = Nfs3File(nfs, normalizePath(remotePath))
        file.delete()
    }

    fun deleteDir(share: NetworkShare, remotePath: String) {
        if (useNative) return LibNfsClient.deleteDir(share, remotePath)

        val nfs = createNfs(share)
        val dir = Nfs3File(nfs, normalizePath(remotePath))
        dir.rmdir()
    }

    fun rename(share: NetworkShare, fromPath: String, toPath: String) {
        if (useNative) return LibNfsClient.rename(share, fromPath, toPath)

        val nfs = createNfs(share)
        val srcFile = Nfs3File(nfs, normalizePath(fromPath))
        val dstFile = Nfs3File(nfs, normalizePath(toPath))
        srcFile.renameTo(dstFile)
    }

    /**
     * Classifies a raw NFS error message into a sentinel key (for UI localisation)
     * or returns a cleaned-up version of the raw message.
     */
    private fun classifyNfsError(rawMessage: String): String {
        val msg = rawMessage.lowercase()
        return when {
            // NfsStatus:70 = NFS3ERR_STALE — stale file handle
            msg.contains("nfsstatus:70") || msg.contains("stale") ->
                ErrorSentinel.STALE_HANDLE

            // NfsStatus:13 = NFS3ERR_ACCES — permission denied
            msg.contains("nfsstatus:13") || msg.contains("permission denied") ||
            msg.contains("access denied") ->
                ErrorSentinel.PERMISSION_DENIED

            else -> rawMessage
        }
    }

    private fun createNfs(share: NetworkShare): Nfs3 {
        val exportPath = normalizePath(share.remotePath)
        val uid = share.username.toIntOrNull() ?: 0
        val gid = 0
        val absolutePath = "${share.host}:$exportPath"
        return Nfs3(absolutePath, uid, gid, 3)
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
}
