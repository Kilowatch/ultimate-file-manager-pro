package za.kilowatch.ultimatefilemanager.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * High-performance WebDAV client built on native Go engine ([webdavclient.Webdavclient]).
 *
 * Runs on Linux kernel sockets via Gomobile, completely independent of Java OkHttp
 * and Android's NetworkSecurityConfig cleartext policies.
 *
 * Supports: PROPFIND (listing), MKCOL (mkdir), GET (download/streaming),
 *           PUT (upload), DELETE (delete), MOVE (rename).
 */
object WebDavShareClient {

    private const val TAG = "WebDavShareClient"

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun testConnection(share: NetworkShare): Boolean = withContext(Dispatchers.IO) {
        if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.testConnection(share)
        try {
            webdavclient.Webdavclient.webdavTestConnection(share.host, share.username, share.password)
            true
        } catch (e: Exception) {
            GoRoLog.e(TAG, "testConnection failed for ${share.host}", e)
            false
        }
    }

    suspend fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> =
        withContext(Dispatchers.IO) {
            if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.listFiles(share, remotePath)
            val cleanPath = remotePath.trim('/')
            val jsonStr = try {
                webdavclient.Webdavclient.webdavListFiles(share.host, share.username, share.password, cleanPath)
            } catch (e: Exception) {
                GoRoLog.e(TAG, "listFiles failed for $cleanPath on ${share.host}", e)
                throw IOException("WebDAV list failed: ${e.message}")
            }

            val result = mutableListOf<NetworkFile>()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.getString("name")
                val isDir = item.getBoolean("isDir")
                val size = item.getLong("size")
                val modTime = item.getLong("modTime")

                val fullPath = if (cleanPath.isEmpty()) name else "$cleanPath/$name"
                result.add(
                    NetworkFile(
                        name = name,
                        path = fullPath,
                        isDirectory = isDir,
                        size = if (isDir) 0L else size,
                        lastModified = modTime
                    )
                )
            }

            result.sortWith { a, b ->
                when {
                    a.isDirectory && !b.isDirectory -> -1
                    !a.isDirectory && b.isDirectory ->  1
                    else -> NaturalSort.naturalCompare(a.name, b.name)
                }
            }
            result
        }

    suspend fun mkdir(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.mkdir(share, remotePath)
        val cleanPath = remotePath.trim('/')
        try {
            webdavclient.Webdavclient.webdavMkdirAll(share.host, share.username, share.password, cleanPath)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "mkdir failed for $cleanPath", e)
            throw IOException("WebDAV mkdir failed: ${e.message}")
        }
    }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.deleteFile(share, remotePath)
        val cleanPath = remotePath.trim('/')
        try {
            webdavclient.Webdavclient.webdavRemove(share.host, share.username, share.password, cleanPath)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "deleteFile failed for $cleanPath", e)
            throw IOException("WebDAV delete failed: ${e.message}")
        }
    }

    suspend fun deleteDir(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.deleteDir(share, remotePath)
        val cleanPath = remotePath.trim('/')
        try {
            webdavclient.Webdavclient.webdavRemoveAll(share.host, share.username, share.password, cleanPath)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "deleteDir failed for $cleanPath", e)
            throw IOException("WebDAV deleteDir failed: ${e.message}")
        }
    }

    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String, isDirectory: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.rename(share, fromPath, toPath, isDirectory)
            val cleanFrom = fromPath.trim('/')
            val cleanTo = toPath.trim('/')
            try {
                webdavclient.Webdavclient.webdavRename(share.host, share.username, share.password, cleanFrom, cleanTo, true)
            } catch (e: Exception) {
                GoRoLog.e(TAG, "rename failed from $cleanFrom to $cleanTo", e)
                throw IOException("WebDAV rename failed: ${e.message}")
            }
        }

    suspend fun openInputStream(share: NetworkShare, remotePath: String): Pair<InputStream, Long> =
        withContext(Dispatchers.IO) {
            if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.openInputStream(share, remotePath)
            val cleanPath = remotePath.trim('/')
            val statJson = webdavclient.Webdavclient.webdavStat(share.host, share.username, share.password, cleanPath)
            val stat = JSONObject(statJson)
            val size = if (stat.optBoolean("exists", false)) stat.optLong("size", -1L) else -1L

            // Return a streaming input stream backed by range reads
            val stream = object : InputStream() {
                private var pos = 0L
                private val bufSize = 64 * 1024
                private var currentBuf: ByteArray? = null
                private var bufPos = 0

                override fun read(): Int {
                    if (currentBuf == null || bufPos >= currentBuf!!.size) {
                        if (size in 0..pos) return -1
                        val toRead = if (size > 0) minOf(bufSize.toLong(), size - pos).toInt() else bufSize
                        val chunk = try {
                            webdavclient.Webdavclient.webdavReadRange(share.host, share.username, share.password, cleanPath, pos, toRead.toLong())
                        } catch (_: Exception) {
                            return -1
                        }
                        if (chunk == null || chunk.isEmpty()) return -1
                        currentBuf = chunk
                        bufPos = 0
                    }
                    val b = currentBuf!![bufPos++].toInt() and 0xFF
                    pos++
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (size in 0..pos) return -1
                    val toRead = if (size > 0) minOf(len.toLong(), size - pos).toInt() else len
                    if (toRead <= 0) return -1
                    val chunk = try {
                        webdavclient.Webdavclient.webdavReadRange(share.host, share.username, share.password, cleanPath, pos, toRead.toLong())
                    } catch (_: Exception) {
                        return -1
                    }
                    if (chunk == null || chunk.isEmpty()) return -1
                    System.arraycopy(chunk, 0, b, off, chunk.size)
                    pos += chunk.size
                    return chunk.size
                }
            }

            Pair(stream, size)
        }

    fun openRandomAccessFile(
        share: NetworkShare,
        remotePath: String,
        fileSize: Long = 0L
    ): IRandomAccessFile {
        if (RCloneShareClient.isRCloneShare(share)) {
            return RCloneShareClient.openRandomAccessFile(share, remotePath)
        }

        val cleanPath = remotePath.trim('/')
        return object : IRandomAccessFile {
            private val lock = Any()
            private var isClosed = false
            private var actualSize: Long = fileSize

            override val size: Long
                get() = synchronized(lock) {
                    if (actualSize <= 0L) {
                        try {
                            val statJson = webdavclient.Webdavclient.webdavStat(share.host, share.username, share.password, cleanPath)
                            val stat = JSONObject(statJson)
                            if (stat.optBoolean("exists", false)) {
                                actualSize = stat.optLong("size", 0L)
                            }
                        } catch (e: Exception) {
                            GoRoLog.d(TAG, "webdavStat failed for $cleanPath: ${e.message}")
                        }
                    }
                    actualSize
                }

            override fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(lock) {
                val currentSize = size
                if (isClosed || offset >= currentSize || length <= 0) return -1
                val toRead = minOf(length.toLong(), currentSize - offset).toInt()
                if (toRead <= 0) return -1

                try {
                    val data = webdavclient.Webdavclient.webdavReadRange(
                        share.host,
                        share.username,
                        share.password,
                        cleanPath,
                        offset,
                        toRead.toLong()
                    )
                    if (data == null || data.isEmpty()) return -1
                    System.arraycopy(data, 0, buffer, 0, data.size)
                    data.size
                } catch (e: Exception) {
                    GoRoLog.e(TAG, "WebDAV readRange error at offset $offset", e)
                    -1
                }
            }

            override fun write(offset: Long, buffer: ByteArray, length: Int): Int =
                throw IOException("Random writes not supported on WebDAV")

            override fun close() = synchronized(lock) {
                isClosed = true
            }
        }
    }

    suspend fun uploadStream(
        share: NetworkShare,
        remotePath: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: ((bytesSent: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (RCloneShareClient.isRCloneShare(share)) {
            RCloneShareClient.uploadStream(share, remotePath, inputStream, totalSize)
            return@withContext
        }

        val cleanPath = remotePath.trim('/')
        val tempFile = File(UfmApplication.instance.cacheDir, "webdav_up_${System.currentTimeMillis()}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var sent = 0L
                while (inputStream.read(buf).also { read = it } != -1) {
                    fos.write(buf, 0, read)
                    sent += read
                    onProgress?.invoke(sent)
                }
            }

            webdavclient.Webdavclient.webdavUploadFile(
                share.host,
                share.username,
                share.password,
                cleanPath,
                tempFile.absolutePath
            )
        } finally {
            tempFile.delete()
        }
    }

    suspend fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream =
        withContext(Dispatchers.IO) {
            if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.openOutputStream(share, remotePath)
            val cleanPath = remotePath.trim('/')
            val tempFile = File(UfmApplication.instance.cacheDir, "webdav_out_${System.currentTimeMillis()}.tmp")

            object : OutputStream() {
                private val fileOut = FileOutputStream(tempFile)
                override fun write(b: Int) = fileOut.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = fileOut.write(b, off, len)
                override fun flush() = fileOut.flush()
                override fun close() {
                    fileOut.close()
                    try {
                        webdavclient.Webdavclient.webdavUploadFile(
                            share.host,
                            share.username,
                            share.password,
                            cleanPath,
                            tempFile.absolutePath
                        )
                    } catch (e: Exception) {
                        GoRoLog.e(TAG, "webdavUploadFile failed for $cleanPath on close", e)
                        throw IOException("WebDAV upload failed on stream close: ${e.message}", e)
                    } finally {
                        tempFile.delete()
                    }
                }
            }
        }
}
