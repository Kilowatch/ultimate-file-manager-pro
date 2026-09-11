package za.kilowatch.ultimatefilemanager.network

import java.io.InputStream
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-performance, robust SMB2/3 client powered by native Go smbclient.aar.
 *
 * Provides multiplexed connection pooling, keepalive Echo, seekable streams,
 * and reliable directory/file operations without the connection drops or
 * TransportExceptions of legacy Java SMB libraries.
 */
object SmbShareClient {

    const val SIZE_UNKNOWN_SENTINEL: Long = 2147483647L // 2GB

    // ── Read operations ───────────────────────────────────────────────────────

    fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> {
        val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
        if (shareName.isBlank()) {
            throw IllegalArgumentException("Cannot determine SMB share name for share.remotePath='${share.remotePath}' and remotePath='$remotePath'")
        }
        val json = smbclient.Smbclient.smbListFiles(
            share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
            shareName, innerPath
        )
        val jsonArray = JSONArray(json)
        val list = ArrayList<NetworkFile>(jsonArray.length())
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val name = obj.getString("name")
            val isDir = obj.getBoolean("isDir")
            var fileSize = obj.getLong("size")
            if (!isDir && fileSize == 0L) {
                fileSize = SIZE_UNKNOWN_SENTINEL
            }
            val modTime = obj.optLong("modTime", System.currentTimeMillis())
            val itemRemotePath = "/" + joinPath(remotePath, name).replace('\\', '/').trimStart('/')
            list.add(
                NetworkFile(
                    name         = name,
                    path         = itemRemotePath,
                    isDirectory  = isDir,
                    size         = fileSize,
                    lastModified = modTime
                )
            )
        }
        return list
    }

    suspend fun openInputStream(
        share: NetworkShare,
        remotePath: String,
        dedicated: Boolean = true,
        onConnectionReady: ((AutoCloseable) -> Unit)? = null
    ): InputStream {
        val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
        val handleId = try {
            smbclient.Smbclient.smbOpenFile(
                share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
                shareName, innerPath, "r", dedicated
            )
        } catch (e: Exception) {
            if (!dedicated) {
                // If opening via pooled session fails, retry once with dedicated session
                smbclient.Smbclient.smbOpenFile(
                    share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
                    shareName, innerPath, "r", true
                )
            } else {
                throw e
            }
        }
        val closeable = AutoCloseable {
            runCatching { smbclient.Smbclient.smbCloseFile(handleId) }
        }
        onConnectionReady?.invoke(closeable)

        val fileSize = runCatching { smbclient.Smbclient.smbGetFileSize(handleId) }.getOrDefault(0L)

        return object : InputStream() {
            private var pos = 0L
            private var closed = false

            override fun read(): Int {
                val buf = ByteArray(1)
                val n = read(buf, 0, 1)
                return if (n <= 0) -1 else (buf[0].toInt() and 0xFF)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (closed) throw java.io.IOException("Stream closed")
                if (len <= 0) return 0
                if (off < 0 || len > b.size - off) throw IndexOutOfBoundsException("off=$off, len=$len, buffer=${b.size}")

                if (fileSize > 0L && pos >= fileSize) {
                    return -1
                }

                val toRead = if (fileSize > 0L) minOf(len.toLong(), fileSize - pos).toInt() else len
                if (toRead <= 0) return -1

                val bytes = try {
                    smbclient.Smbclient.smbReadAt(handleId, pos, toRead.toLong())
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    if (msg.contains("EOF", ignoreCase = true) ||
                        msg.contains("STATUS_END_OF_FILE", ignoreCase = true) ||
                        msg.contains("0xc0000011", ignoreCase = true) ||
                        (fileSize > 0L && pos >= fileSize) ||
                        pos > 0L) {
                        return -1
                    }
                    throw java.io.IOException("SMB read failed at offset $pos: ${e.message}", e)
                }

                if (bytes == null || bytes.isEmpty()) {
                    return -1
                }

                val bytesToCopy = minOf(bytes.size, len)
                System.arraycopy(bytes, 0, b, off, bytesToCopy)
                pos += bytesToCopy
                return bytesToCopy
            }

            override fun skip(n: Long): Long {
                if (closed) throw java.io.IOException("Stream closed")
                if (n <= 0L) return 0L
                val toSkip = if (fileSize > 0L) {
                    minOf(n, maxOf(0L, fileSize - pos))
                } else {
                    n
                }
                pos += toSkip
                return toSkip
            }

            override fun available(): Int {
                if (closed) return 0
                if (fileSize > 0L) {
                    val remaining = fileSize - pos
                    return remaining.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                }
                return 0
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    closeable.close()
                }
            }
        }
    }

    /** Query the server for the actual size of a single remote file. Returns null if unavailable. */
    fun getFileSize(share: NetworkShare, remotePath: String): Long? {
        return runCatching {
            val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
            val json = smbclient.Smbclient.smbStat(
                share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
                shareName, innerPath
            )
            val obj = JSONObject(json)
            if (obj.optBoolean("exists", false) && !obj.optBoolean("isDir", false)) {
                obj.optLong("size", -1L).takeIf { it >= 0L }
            } else null
        }.getOrNull()
    }

    /**
     * Opens a file for random-access reads (seeking) and optionally writes.
     * Backed by native Go go-smb2 client handles for high performance.
     */
    fun openRandomAccessFile(
        share: NetworkShare,
        remotePath: String,
        isWrite: Boolean = false,
        dedicated: Boolean = true,
        onConnectionReady: ((AutoCloseable) -> Unit)? = null,
        suppressInvalidateOnReadError: Boolean = false
    ): SmbRandomAccess {
        val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
        val mode = if (isWrite) "rw" else "r"
        val handleId = smbclient.Smbclient.smbOpenFile(
            share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
            shareName, innerPath, mode, dedicated
        )
        val closeable = AutoCloseable {
            runCatching { smbclient.Smbclient.smbCloseFile(handleId) }
        }
        onConnectionReady?.invoke(closeable)

        val size = runCatching { smbclient.Smbclient.smbGetFileSize(handleId) }.getOrDefault(0L)
        return SmbRandomAccess(
            handleId = handleId,
            size = if (size <= 0L) SIZE_UNKNOWN_SENTINEL else size,
            onClose = { closeable.close() }
        )
    }

    /** Handle for seekable reads/writes on an SMB file. */
    class SmbRandomAccess(
        private val handleId: Long,
        override var size: Long,
        private val onClose: () -> Unit
    ) : IRandomAccessFile {

        override fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(this) {
            if (size > 0L && size != SIZE_UNKNOWN_SENTINEL && offset >= size) return -1
            return try {
                val bytes = smbclient.Smbclient.smbReadAt(handleId, offset, length.toLong())
                if (bytes.isEmpty()) {
                    -1
                } else {
                    System.arraycopy(bytes, 0, buffer, 0, bytes.size)
                    bytes.size
                }
            } catch (_: Exception) {
                -1
            }
        }

        override fun write(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(this) {
            val data = if (length == buffer.size) buffer else buffer.copyOfRange(0, length)
            val written = smbclient.Smbclient.smbWriteAt(handleId, offset, data)
            val endOffset = offset + written
            if (endOffset > size) {
                size = endOffset
            }
            written.toInt()
        }

        override fun close() = onClose()
    }

    // ── Write operations ──────────────────────────────────────────────────────

    suspend fun openOutputStream(
        share: NetworkShare,
        remotePath: String,
        onConnectionReady: ((AutoCloseable) -> Unit)? = null
    ): OutputStream {
        val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
        val handleId = smbclient.Smbclient.smbOpenFile(
            share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
            shareName, innerPath, "w", true
        )
        val closeable = AutoCloseable {
            runCatching { smbclient.Smbclient.smbCloseFile(handleId) }
        }
        onConnectionReady?.invoke(closeable)

        return object : OutputStream() {
            private var pos = 0L
            private var closed = false

            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) throw java.io.IOException("Stream closed")
                if (len <= 0) return
                val data = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
                val written = smbclient.Smbclient.smbWriteAt(handleId, pos, data)
                pos += written
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    closeable.close()
                }
            }
        }
    }

    fun mkdir(share: NetworkShare, remotePath: String) {
        val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
        smbclient.Smbclient.smbMkdir(
            share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
            shareName, innerPath
        )
    }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) {
        val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
        smbclient.Smbclient.smbRemove(
            share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
            shareName, innerPath
        )
    }

    suspend fun deleteDir(share: NetworkShare, remotePath: String) {
        val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
        smbclient.Smbclient.smbRemoveAll(
            share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
            shareName, innerPath
        )
    }

    fun rename(share: NetworkShare, fromPath: String, toPath: String) {
        val (shareFrom, innerFrom) = splitSharePath(share.remotePath, fromPath)
        val (_, innerTo) = splitSharePath(share.remotePath, toPath)
        smbclient.Smbclient.smbRename(
            share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
            shareFrom, innerFrom, innerTo
        )
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    object ErrorSentinel {
        const val MAX_CONNECTIONS = "ERR_SMB_MAX_CONNECTIONS"
    }

    fun friendlyMessage(raw: String?): String {
        val msg = raw ?: return ErrorSentinel.MAX_CONNECTIONS
        return when {
            msg.contains("no more connections", ignoreCase = true) ||
            msg.contains("STATUS_REQUEST_NOT_ACCEPTED", ignoreCase = true) ||
            msg.contains("0xc00000d0", ignoreCase = true) -> ErrorSentinel.MAX_CONNECTIONS
            else -> msg
        }
    }

    fun testConnection(share: NetworkShare): String? {
        val (shareName, _) = splitSharePath(share.remotePath, "")
        val targetShare = if (share.isServerMode) "" else shareName
        val err = smbclient.Smbclient.smbTestConnection(
            share.host, share.effectivePort.toLong(), share.username, share.password, share.domain,
            targetShare
        )
        return if (err.isNullOrBlank()) null else friendlyMessage(err)
    }

    fun isShareAccessible(
        host: String,
        shareName: String,
        username: String,
        password: String,
        domain: String
    ): Boolean {
        val err = smbclient.Smbclient.smbTestConnection(
            host, 445L, username, password, domain, shareName
        )
        return err.isNullOrBlank()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun splitSharePath(basePath: String, subPath: String): Pair<String, String> {
        val cleanBase = basePath.replace('\\', '/').trim('/').trim()
        val cleanSub = subPath.replace('\\', '/').trim('/').trim()

        if (cleanBase.isEmpty()) {
            if (cleanSub.isEmpty()) return "" to ""
            val segments = cleanSub.split('/').filter { it.isNotEmpty() }
            val share = segments.firstOrNull() ?: ""
            val inner = segments.drop(1).joinToString("\\")
            return share to inner
        }

        val baseSegments = cleanBase.split('/').filter { it.isNotEmpty() }
        val shareName = baseSegments.firstOrNull() ?: ""
        val baseInnerSegments = baseSegments.drop(1)

        if (cleanSub.isEmpty()) {
            return shareName to baseInnerSegments.joinToString("\\")
        }

        var subSegments = cleanSub.split('/').filter { it.isNotEmpty() }

        // If subSegments starts with shareName, strip it
        if (subSegments.isNotEmpty() && subSegments.first().equals(shareName, ignoreCase = true)) {
            subSegments = subSegments.drop(1)
        }

        // If subSegments already starts with base inner segments, avoid duplicating them
        val matchesBaseInner = baseInnerSegments.isNotEmpty() &&
                subSegments.size >= baseInnerSegments.size &&
                baseInnerSegments.indices.all { idx ->
                    subSegments[idx].equals(baseInnerSegments[idx], ignoreCase = true)
                }

        val finalInnerSegments = if (matchesBaseInner) {
            subSegments
        } else {
            baseInnerSegments + subSegments
        }

        return shareName to finalInnerSegments.joinToString("\\")
    }

    private fun joinPath(base: String, sub: String): String {
        if (sub.isBlank()) return base
        val normalizedSub = sub.replace('/', '\\')
        if (base.isBlank()) return normalizedSub.trimStart('\\')
        return base.trimEnd('\\') + "\\" + normalizedSub.trimStart('\\')
    }
}
