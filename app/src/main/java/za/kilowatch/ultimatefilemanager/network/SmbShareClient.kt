package za.kilowatch.ultimatefilemanager.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around smbj for SMB2/3 access.
 *
 * Connections are managed by [SmbSessionPool] — operations reuse a single
 * pooled TCP connection per share, which means Windows only sees ONE session
 * per device instead of one per API call.
 *
 * Streaming operations (openInputStream / openOutputStream) borrow a dedicated
 * connection for the lifetime of the stream; the pool connection stays free for
 * concurrent list/delete/rename calls during a copy.
 */
object SmbShareClient {

    // Use shorter timeouts for UI responsiveness; longer operations (uploads) still work
    // but will fail faster when the host is unreachable or slow.
    // Sentinel used when listing returns 0 size; indicates size unknown until explicitly queried.
    const val SIZE_UNKNOWN_SENTINEL: Long = 2147483647L // 2GB

    // ── Read operations ───────────────────────────────────────────────────────

    fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> {
        return withDiskShare(share, remotePath) { diskShare, innerPath ->
            diskShare.list(innerPath).map { info ->
                za.kilowatch.ultimatefilemanager.util.GoRoLog.w("RAW LIST ITEM: name=${info.fileName}, size=${info.endOfFile}, alloc=${info.allocationSize}, attr=${info.fileAttributes}")
                val isDir = info.fileAttributes and 0x10L != 0L
                var fileSize = info.endOfFile
                // Some SMB servers return 0 for size in directory listings. Avoid expensive
                // per-file queries (getFileInformation/openFile) during listing because those
                // cause heavy latency on some servers/NAS devices. If size is 0, use a
                // large sentinel value so clients that need a size (e.g. games) can still work.
                if (!isDir && fileSize == 0L) {
                    fileSize = SIZE_UNKNOWN_SENTINEL
                }
                NetworkFile(
                    name         = info.fileName,
                    path         = "/" + joinPath(remotePath, info.fileName).replace('\\', '/').trimStart('/'),
                    isDirectory  = isDir,
                    size         = fileSize,
                    lastModified = info.lastWriteTime.toEpochMillis()
                )
            }.filter { it.name != "." && it.name != ".." }
        }
    }

    suspend fun openInputStream(
        share: NetworkShare,
        remotePath: String,
        dedicated: Boolean = true,
        onConnectionReady: ((AutoCloseable) -> Unit)? = null
    ): InputStream {
        // Use a DEDICATED connection for streaming if requested, to avoid blocking the pool.
        val pooled = SmbSessionPool.borrow(share, authContext(share), dedicated = dedicated)
        // Expose connection so caller can close it to force-abort a slow download
        onConnectionReady?.invoke(pooled.connection)
        return try {
            val session = pooled.session
            val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
            android.util.Log.d("SmbClientTrace", "openInputStream: share.remotePath='${share.remotePath}' remotePath='$remotePath' → shareName='$shareName' innerPath='$innerPath' host=${share.host}")
            val diskShare = session.connectShare(shareName) as DiskShare
            val file = diskShare.openFile(
                innerPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            val inputStream = file.inputStream
            object : InputStream() {
                override fun read(): Int = inputStream.read()
                override fun read(b: ByteArray, off: Int, len: Int) = inputStream.read(b, off, len)
                override fun close() {
                    runCatching { inputStream.close() }
                    runCatching { file.close() }
                    pooled.release()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClientTrace", "openInputStream FAILED: share.remotePath='${share.remotePath}' remotePath='$remotePath' host=${share.host} error=${e.message}")
            pooled.invalidate()
            throw e
        }
    }

    /** Query the server for the actual size of a single remote file. Returns null if unavailable. */
    fun getFileSize(share: NetworkShare, remotePath: String): Long? {
        return runCatching {
            withDiskShare(share, remotePath) { diskShare, innerPath ->
                val fi = diskShare.getFileInformation(innerPath)
                var size = fi.standardInformation.endOfFile
                if (size == 0L) size = fi.standardInformation.allocationSize
                if (size <= 0L) null else size
            }
        }.getOrNull()
    }

    /**
     * Opens a file for random-access reads (seeking) and optionally writes.
     *
     * Returns an [SmbRandomAccess] handle whose [SmbRandomAccess.read]/write supports
     * reading at arbitrary offsets — required by `ProxyFileDescriptorCallback`
     * for apps like PPSSPP that need to seek within large game files.
     *
     * The caller MUST call [SmbRandomAccess.close] when done.
     */
    fun openRandomAccessFile(
        share: NetworkShare,
        remotePath: String,
        isWrite: Boolean = false,
        dedicated: Boolean = true,
        onConnectionReady: ((AutoCloseable) -> Unit)? = null,
        suppressInvalidateOnReadError: Boolean = false
    ): SmbRandomAccess {
        // Random-access handles are long-lived (e.g. game running for hours) so
        // always use a dedicated connection rather than holding the pool entry.
        val pooled  = SmbSessionPool.borrow(share, authContext(share), dedicated = dedicated, forWrite = isWrite)
        onConnectionReady?.invoke(pooled.connection)
        return try {
            val session  = pooled.session
            val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
            val diskShare = session.connectShare(shareName) as DiskShare

            val accessMask = if (isWrite) {
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE)
            } else {
                EnumSet.of(AccessMask.GENERIC_READ)
            }

            val file = diskShare.openFile(
                innerPath,
                accessMask,
                null,
                SMB2ShareAccess.ALL,
                if (isWrite) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OPEN,
                null
            )
            val fileInfo = file.fileInformation
            val fileSize = fileInfo.standardInformation.endOfFile
            SmbRandomAccess(
                file,
                fileSize,
                onClose = {
                    runCatching { file.close() }
                    pooled.release()
                },
                onInvalidate = {
                    pooled.invalidate()
                },
                suppressInvalidateOnReadError = suppressInvalidateOnReadError
            )
        } catch (e: Exception) {
            pooled.invalidate()
            throw e
        }
    }

    /** Handle for seekable reads/writes on an SMB file. */
    class SmbRandomAccess(
        private val file: com.hierynomus.smbj.share.File,
        override var size: Long,
        private val onClose: () -> Unit,
        private val onInvalidate: () -> Unit,
        private val suppressInvalidateOnReadError: Boolean = false
    ) : IRandomAccessFile {
        /**
         * Reads up to [length] bytes starting at [offset] into [buffer].
         * ProxyFileDescriptorCallback REQUIRES returning the exact requested size unless EOF.
         * smbj reads might be shorter than requested (e.g. max SMB read size 64KB),
         * so we loop until fulfilled.
         *
         * When [suppressInvalidateOnReadError] is true, a transient read failure does NOT
         * close the underlying SMB connection. This is used by the HTTP proxy's pinned
         * streaming handle: an external player (VLC) that aborts a request on seek must not
         * destroy the session that other concurrent requests still need. The proxy decides
         * when the handle is truly dead and closes it itself.
         */
        override fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(this) {
            if (offset >= size) return -1
            var toRead = minOf(length.toLong(), size - offset).toInt()
            var totalRead = 0
            var currentOffset = offset

            try {
                while (toRead > 0) {
                    val bytesRead = file.read(buffer, currentOffset, totalRead, toRead)
                    if (bytesRead <= 0) break // EOF or error
                    totalRead += bytesRead
                    currentOffset += bytesRead
                    toRead -= bytesRead
                }
            } catch (e: Exception) {
                if (!suppressInvalidateOnReadError) {
                    onInvalidate()
                }
                throw e
            }
            return if (totalRead == 0) -1 else totalRead
        }

        /**
         * Writes [length] bytes from [buffer] starting at [offset] to the file.
         */
        override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
            file.write(buffer, offset, 0, length)
            val endOffset = offset + length
            if (endOffset > size) {
                size = endOffset
            }
            return length
        }

        override fun close() = onClose()
    }

    // ── Write operations ──────────────────────────────────────────────────────

    /** Opens a write stream; creates the file if it doesn't exist.
     *  [onConnectionReady] is invoked with the raw TCP connection before the copy starts,
     *  so the caller can close it immediately on cancel (kills the socket, no 15s timeout). */
    suspend fun openOutputStream(
        share: NetworkShare,
        remotePath: String,
        onConnectionReady: ((AutoCloseable) -> Unit)? = null
    ): OutputStream {
        // Use a DEDICATED connection for streaming to avoid blocking the pool
        // and prevent concurrent metadata calls from closing the share.
        val pooled = SmbSessionPool.borrow(share, authContext(share), dedicated = true, forWrite = true)
        // Expose the connection immediately — caller can close() it to abort the copy instantly
        onConnectionReady?.invoke(pooled.connection)
        return try {
            val session  = pooled.session
            val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)

            za.kilowatch.ultimatefilemanager.util.GoRoLog.d("SmbClient", "OPEN OUTPUT STREAM")
            za.kilowatch.ultimatefilemanager.util.GoRoLog.d("SmbClient", "share.remotePath: ${share.remotePath}")
            za.kilowatch.ultimatefilemanager.util.GoRoLog.d("SmbClient", "remotePath arg: $remotePath")
            za.kilowatch.ultimatefilemanager.util.GoRoLog.d("SmbClient", "shareName: $shareName")
            za.kilowatch.ultimatefilemanager.util.GoRoLog.d("SmbClient", "innerPath: $innerPath")

            val diskShare = session.connectShare(shareName) as DiskShare

            // Retry loop: if a previous delete is still pending on the server,
            // wait briefly and retry instead of failing immediately.
            var file: com.hierynomus.smbj.share.File? = null
            for (attempt in 1..5) {
                try {
                    file = diskShare.openFile(
                        innerPath,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        null
                    )
                    break  // success
                } catch (e: Exception) {
                    val isDeletePending = e.message?.contains("STATUS_DELETE_PENDING") == true
                            || e.cause?.message?.contains("STATUS_DELETE_PENDING") == true
                    android.util.Log.w("UFM_COPY", "openOutputStream attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}, isDeletePending=$isDeletePending")
                    if (isDeletePending && attempt < 5) {
                        kotlinx.coroutines.delay(2000)  // wait for server to finish deleting
                        continue
                    }
                    throw e  // non-retryable or exhausted retries
                }
            }
            val out = file!!.outputStream
            object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun flush() = out.flush()
                override fun close() {
                    runCatching { out.close() }
                    runCatching { file.close() }
                    pooled.release()
                }
            }
        } catch (e: Exception) {
            pooled.invalidate()
            throw e
        }
    }

    fun mkdir(share: NetworkShare, remotePath: String) {
        withDiskShare(share, remotePath) { diskShare, innerPath ->
            za.kilowatch.ultimatefilemanager.util.GoRoLog.d("SmbClient", "MKDIR innerPath: $innerPath (remotePath: $remotePath)")
            diskShare.mkdir(innerPath)
        }
    }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) {
        // Use pooled connections for rm() and verification.
        // The delay between delete and verify is a suspend (non-blocking) delay.
        for (attempt in 1..2) {
            // Step 1: delete (ignore failures — file may already be gone)
            withDiskShare(share, remotePath) { diskShare, innerPath ->
                runCatching { diskShare.rm(innerPath) }
            }

            // Step 2: wait briefly for the server to process the delete (suspend, don't block)
            kotlinx.coroutines.delay(300)

            // Step 3: verify deletion on a pooled connection
            val stillExists = withDiskShare(share, remotePath) { diskShare, innerPath ->
                try {
                    diskShare.fileExists(innerPath)
                } catch (_: Exception) {
                    false   // exception querying the path = it's gone
                }
            }

            if (!stillExists) {
                android.util.Log.w("UFM_COPY", "deleteFile: $remotePath confirmed deleted (attempt $attempt)")
                return
            }
            android.util.Log.w("UFM_COPY", "deleteFile: $remotePath still exists, retrying (attempt $attempt)")
        }
        android.util.Log.e("UFM_COPY", "deleteFile: $remotePath could not be deleted after 2 attempts")
    }

    suspend fun deleteDir(share: NetworkShare, remotePath: String) {
        withDiskShare(share, remotePath) { diskShare, innerPath ->
            diskShare.rmdir(innerPath, true)
        }
    }

    fun rename(share: NetworkShare, fromPath: String, toPath: String) {
        val (shareFrom, innerFrom) = splitSharePath(share.remotePath, fromPath)
        val (_, innerTo) = splitSharePath(share.remotePath, toPath)
        withDiskShare(share, fromPath) { diskShare, _ ->
            try {
                diskShare.openFile(
                    innerFrom,
                    EnumSet.of(AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { f -> f.rename(innerTo, true) }
            } catch (e: Exception) {
                diskShare.openDirectory(
                    innerFrom,
                    EnumSet.of(AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { d -> d.rename(innerTo, true) }
            }
        }
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    /**
     * Sentinel strings returned by [testConnection] and [friendlyMessage] when a well-known
     * SMB error is detected.  The UI layer maps these to localised string resources so the
     * user never sees a raw Windows/NTSTATUS message.
     */
    object ErrorSentinel {
        /** Windows limit: the server has reached its maximum concurrent SMB sessions. */
        const val MAX_CONNECTIONS = "ERR_SMB_MAX_CONNECTIONS"
    }

    /**
     * Maps a raw smbj / JCIFS exception message to a human-readable sentinel (or returns
     * the original message if no mapping exists).  Call this anywhere you catch an SMB
     * exception and want to surface it to the UI layer.
     */
    fun friendlyMessage(raw: String?): String {
        val msg = raw ?: return ErrorSentinel.MAX_CONNECTIONS // shouldn't happen, but safe
        return when {
            // Windows error 0x47 / NT STATUS_REQUEST_NOT_ACCEPTED:
            // "No more connections can be made to this remote computer at this time
            //  because there are already as many connections as the computer can accept."
            msg.contains("no more connections", ignoreCase = true) ||
            msg.contains("STATUS_REQUEST_NOT_ACCEPTED", ignoreCase = true) ||
            msg.contains("0xc00000d0", ignoreCase = true) -> ErrorSentinel.MAX_CONNECTIONS
            else -> msg
        }
    }

    /** Returns null on success, or an error sentinel / message string on failure. */
    fun testConnection(share: NetworkShare): String? {
        // Use a dedicated, short-timeout client so:
        //  1. A slow NAS (spinning up its disk) fails in ≤10 s instead of ≤120 s.
        //  2. The probe is never placed in the session pool, so a timeout can't
        //     poison subsequent real sync operations.
        // One retry with 2 s back-off handles transient NAS spin-up delays.
        var lastError: String? = null
        for (attempt in 1..2) {
            if (attempt == 2) Thread.sleep(2000)
            val client = SMBClient(SmbSessionPool.buildTestConfig())
            val error = runCatching {
                client.use { smb ->
                    val conn    = smb.connect(share.host, share.effectivePort)
                    val auth    = authContext(share)
                    val session = conn.authenticate(auth)
                    if (share.isServerMode || share.remotePath.isBlank()) {
                        // In server mode, verifying authentication with the host is sufficient
                        null
                    } else {
                        val (shareName, basePath) = splitSharePath(share.remotePath, "")
                        val diskShare = session.connectShare(shareName) as DiskShare
                        diskShare.use { ds -> ds.list(basePath.ifBlank { "" }) }
                    }
                }
                null // success
            }.getOrElse { e -> friendlyMessage(e.message ?: e.javaClass.simpleName) }

            if (error == null) return null  // success on this attempt
            lastError = error
            android.util.Log.w("SmbShareClient",
                "testConnection attempt $attempt failed: $lastError")
        }
        return lastError
    }

    /**
     * Returns true if [shareName] is readable with the given credentials.
     * Used by the share browser to filter the listed shares to only those
     * the current credentials can actually open.
     */
    fun isShareAccessible(
        host: String,
        shareName: String,
        username: String,
        password: String,
        domain: String
    ): Boolean {
        // Build a minimal NetworkShare pointing at the share root
        val probe = NetworkShare(
            host       = host,
            type       = ShareType.SMB,
            username   = username,
            password   = password,
            domain     = domain.ifBlank { "WORKGROUP" },
            remotePath = "/$shareName"
        )
        return runCatching {
            withDiskShare(probe, maxAttempts = 1) { diskShare, _ -> diskShare.list("") }
            true
        }.getOrElse { false }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun <T> withDiskShare(
        share: NetworkShare,
        remotePath: String = "",
        maxAttempts: Int = 2,
        block: (DiskShare, String) -> T
    ): T {
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            val pooled = SmbSessionPool.borrow(share, authContext(share))
            try {
                val (shareName, innerPath) = splitSharePath(share.remotePath, remotePath)
                if (shareName.isBlank()) {
                    throw IllegalArgumentException("Cannot determine SMB share name for share.remotePath='${share.remotePath}' and remotePath='$remotePath'")
                }
                val diskShare = pooled.session.connectShare(shareName) as DiskShare

                if (!diskShare.isConnected) {
                    pooled.invalidate()
                    if (attempt < maxAttempts) {
                        continue
                    }
                }

                val result = diskShare.use { connectedShare ->
                    block(connectedShare, innerPath)
                }
                pooled.release()
                return result
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    pooled.release()
                    throw e
                }
                pooled.invalidate()
                lastError = e
                if (attempt < maxAttempts) {
                    // Give the pool time to process the invalidation before retrying,
                    // otherwise borrow() may race and return the same broken entry.
                    Thread.sleep(150)
                    continue
                }
                throw e
            }
        }
        throw lastError ?: Exception("Unknown SMB error")
    }

    private fun authContext(share: NetworkShare): AuthenticationContext {
        if (share.username.isBlank()) {
            return AuthenticationContext("GUEST", "".toCharArray(), "")
        }

        val atIndex = share.username.indexOf('@')
        return if (atIndex > 0) {
            val localPart  = share.username.substring(0, atIndex)
            val emailDomain = share.username.substring(atIndex + 1)
            val effectiveDomain = if (share.domain.isNotBlank()) share.domain else emailDomain
            AuthenticationContext(localPart, share.password.toCharArray(), effectiveDomain)
        } else {
            AuthenticationContext(share.username, share.password.toCharArray(), share.domain)
        }
    }

    private fun splitSharePath(basePath: String, subPath: String): Pair<String, String> {
        val cleanBase = basePath.replace('\\', '/').trimStart('/').trimEnd('/')
        if (cleanBase.isBlank()) {
            val cleanSub = subPath.replace('\\', '/').trimStart('/').trimEnd('/')
            if (cleanSub.isBlank()) {
                return "" to ""
            }
            val parts = cleanSub.split('/', limit = 2)
            val shareName = parts.getOrElse(0) { "" }
            val inner = parts.getOrElse(1) { "" }.replace('/', '\\')
            return shareName to inner
        }
        val parts = cleanBase.split("/", limit = 2)
        val shareName = parts.getOrElse(0) { "" }
        val inner = parts.getOrElse(1) { "" }.replace('/', '\\')

        var cleanSub = subPath.replace('\\', '/').trimStart('/')
        if (cleanSub.equals(shareName, ignoreCase = true)) {
            cleanSub = ""
        } else if (cleanSub.startsWith("$shareName/", ignoreCase = true)) {
            cleanSub = cleanSub.substring(shareName.length + 1).trimStart('/')
        } else if (cleanSub.equals(cleanBase, ignoreCase = true)) {
            cleanSub = ""
        } else if (cleanSub.startsWith("$cleanBase/", ignoreCase = true)) {
            cleanSub = cleanSub.substring(cleanBase.length + 1).trimStart('/')
        }

        val normalizedSub = cleanSub.replace('/', '\\').trimStart('\\')
        val combined = listOf(inner, normalizedSub).filter { it.isNotBlank() }.joinToString("\\")
        return shareName to combined
    }

    private fun joinPath(base: String, sub: String): String {
        if (sub.isBlank()) return base
        val normalizedSub = sub.replace('/', '\\')
        if (base.isBlank()) return normalizedSub.trimStart('\\')
        return base.trimEnd('\\') + "\\" + normalizedSub.trimStart('\\')
    }
}
