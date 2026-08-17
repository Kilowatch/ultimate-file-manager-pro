package za.kilowatch.ultimatefilemanager.storage

import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.DlnaShareClient
import java.io.File
import java.io.IOException
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase

private val VIDEO_EXTENSIONS = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS

/**
 * Exposes UFM's storage volumes AND configured network shares (SMB/FTP) through the
 * Android Storage Access Framework.
 *
 * Document ID scheme:
 *  - Local files:  absolute path, e.g. "/storage/emulated/0/Movies/film.mkv"
 *  - Network docs: "net:<shareId>/<remote/path>", e.g. "net:abc-123/Videos/show.mkv"
 *
 * This is the key piece that allows apps like Daijisho on Android TV 14 to browse
 * Samba/FTP shares through the system file picker — since Google removed the default
 * Files picker from the Leanback launcher in Android TV 11+.
 */
class UfmDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val NET_SCHEME = "net:"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_MIME_TYPES
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
    }

    override fun onCreate(): Boolean = true

    // ── Roots ────────────────────────────────────────────────────────────────

    override fun queryRoots(projection: Array<String>?): Cursor {
        GoRoLog.i("queryRoots: projection=${projection?.contentToString()}")
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return result

        // 1. Local storage volumes
        addLocalRoots(ctx, result)

        // 2. Network shares (SMB / FTP)
        addNetworkRoots(ctx, result)

        return result
    }

    private fun addLocalRoots(ctx: Context, result: MatrixCursor) {
        val storageManager = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (volume in storageManager.storageVolumes) {
            val path = volumePath(volume) ?: continue
            val file = File(path)
            if (!file.exists() || !file.canRead()) continue
            val label = try {
                when {
                    volume.isPrimary   -> ctx.getString(R.string.storage_internal)
                    volume.isRemovable -> {
                        val desc = volume.getDescription(ctx).lowercase()
                        if (desc.contains("usb")) ctx.getString(R.string.storage_usb)
                        else ctx.getString(R.string.storage_sd_card)
                    }
                    else -> volume.getDescription(ctx)
                }
            } catch (e: Exception) {
                GoRoLog.w("queryRoots: failed to get label for volume", e)
                volume.getDescription(ctx)
            }
            val safDocId = toSafDocId(path)
            val flags = Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or
                    Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_SUPPORTS_RECENTS
            result.newRow().apply {
                add(Root.COLUMN_ROOT_ID,         safDocId)
                add(Root.COLUMN_DOCUMENT_ID,     safDocId)
                add(Root.COLUMN_TITLE,           label)
                add(Root.COLUMN_SUMMARY,         ctx.getString(R.string.app_name))
                add(Root.COLUMN_ICON,            R.mipmap.ic_launcher)
                add(Root.COLUMN_FLAGS,           flags)
                add(Root.COLUMN_AVAILABLE_BYTES, file.freeSpace)
                add(Root.COLUMN_MIME_TYPES,      "*/*")
            }
        }
    }

    private fun addNetworkRoots(ctx: Context, result: MatrixCursor) {
        val repo = NetworkShareRepository.getInstance(ctx)
        for (share in repo.getAll()) {
            // Server-mode SMB shares have no single remotePath, so they cannot
            // be exposed as a SAF root. Skip them — users browse server-mode
            // shares through the app's own UI.
            if (share.isServerMode) continue

            val rootDocId = share.docIdPrefix         // e.g. "net:abc-123/"
            val flags = Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD or
                    if (share.readOnly) 0 else Root.FLAG_SUPPORTS_CREATE
            result.newRow().apply {
                add(Root.COLUMN_ROOT_ID,         share.id)
                add(Root.COLUMN_DOCUMENT_ID,     rootDocId)
                add(Root.COLUMN_TITLE,           share.name)
                add(Root.COLUMN_SUMMARY,         share.host)
                add(Root.COLUMN_ICON,            R.drawable.ic_network)
                add(Root.COLUMN_FLAGS,           flags)
                add(Root.COLUMN_AVAILABLE_BYTES, Long.MAX_VALUE)
                add(Root.COLUMN_MIME_TYPES,      "*/*")
            }
        }
    }

    // ── Document queries ─────────────────────────────────────────────────────

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        GoRoLog.d("queryDocument: id=$documentId")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (isNetworkDoc(documentId)) {
            val (share, path) = resolveNetwork(documentId) ?: return result
            // For the root doc of a network share, synthesise a directory entry
            if (path.isEmpty() || path == "/") {
                synthesizeNetworkDir(result, documentId, share.name, share.readOnly)
            } else {
                val files = listNetworkFiles(share, parentPath(path))
                val match = files.find { it.path == path || it.name == path.substringAfterLast('/') }
                if (match != null) {
                    includeNetworkFile(result, share, match)
                } else {
                    // Fallback: the document might be a directory that doesn't exist
                    // yet, or the parent listing couldn't find it. Apps like PPSSPP
                    // construct tree-document URIs for intermediate paths before
                    // calling createDocument().
                    val displayName = path.trimEnd('/').substringAfterLast('/')
                    if (displayName.contains('.') && !displayName.endsWith("/")) {
                        // It looks like a file (e.g. game rom). Mock a valid file entry
                        // because our listing check missed it (cache drop, SMB delay).
                        val fakeSize = 2147483647L // 2GB fake size so emulators don't drop it
                        val mime = getMimeType(displayName)
                        val flags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
                        val docId = buildNetDocId(share, path)
                        result.newRow().apply {
                            add(Document.COLUMN_DOCUMENT_ID, docId)
                            add(Document.COLUMN_DISPLAY_NAME, displayName)
                            add(Document.COLUMN_MIME_TYPE, mime)
                            add(Document.COLUMN_FLAGS, flags)
                            add(Document.COLUMN_SIZE, fakeSize)
                            add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                        }
                    } else {
                        // It's an intermediate directory path
                        synthesizeNetworkDir(result, documentId, displayName, share.readOnly)
                    }
                }
            }
        } else {
            // For local paths, we must NEVER return an empty cursor for a Tree root query,
            // otherwise Android crashes the caller (like Daijisho) with a SecurityException when they poll the dead URI.
            try {
                val absPath = fromSafDocId(documentId)
                val file = File(absPath)
                if (file.exists()) {
                    includeFile(result, file)
                } else {
                    // Spoof an empty directory so the caller doesn't crash on dead, cached URIs
                    synthesizeGhostDir(result, documentId, absPath.substringAfterLast('/'))
                }
            } catch (e: Exception) {
                // Failsafe for completely malformed legacy URIs
                synthesizeGhostDir(result, documentId, "Unknown")
            }
        }
        return result
    }

    private fun synthesizeGhostDir(cursor: MatrixCursor, documentId: String, displayName: String) {
        val flags = Document.FLAG_DIR_PREFERS_GRID
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID,   documentId)
            add(Document.COLUMN_DISPLAY_NAME,  displayName.ifEmpty { "Missing Folder" })
            add(Document.COLUMN_MIME_TYPE,     Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS,         flags)
            add(Document.COLUMN_SIZE,          null)
            add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        GoRoLog.d("queryChildDocuments: parentId=$parentDocumentId")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val showHidden = HiddenFilesManager.isShowHiddenFilesEnabled
        if (isNetworkDoc(parentDocumentId)) {
            val (share, path) = resolveNetwork(parentDocumentId) ?: return result
            val rawFiles = listNetworkFiles(share, path)
            val visibleFiles = if (showHidden) rawFiles else {
                rawFiles.filter { !HiddenFilesManager.isJunkOrHidden(it.name) }
            }
            visibleFiles.sortedWith(
                compareBy<NetworkFile> { !it.isDirectory }.thenBy(NaturalSort.order) { it.name }
            ).forEach { includeNetworkFile(result, share, it) }
        } else {
            try {
                val absPath = fromSafDocId(parentDocumentId)
                val parent = File(absPath)
                if (parent.exists() && parent.isDirectory) {
                    val rawFiles = parent.listFiles() ?: emptyArray()
                    val hiddenPaths = if (showHidden) emptySet() else {
                        try {
                            val db = HiddenFilesDatabase.getInstance(context ?: za.kilowatch.ultimatefilemanager.UfmApplication.instance)
                            db.hiddenFileDao().getAllPaths().toSet()
                        } catch (e: Exception) {
                            emptySet()
                        }
                    }
                    val visibleFiles = if (showHidden) rawFiles.toList() else {
                        rawFiles.filter { !HiddenFilesManager.isJunkOrHidden(it.name) && it.absolutePath !in hiddenPaths }
                    }
                    visibleFiles.sortedWith(
                        compareBy<File> { !it.isDirectory }.thenBy(NaturalSort.order) { it.name }
                    ).forEach { includeFile(result, it) }
                }
            } catch (e: Exception) {
                // Return empty cursor for dead/deleted local root
                GoRoLog.w("queryChildDocuments: Caught exception for dead root $parentDocumentId - ${e.message}")
            }
        }
        return result
    }

    // ── File access ──────────────────────────────────────────────────────────

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        GoRoLog.d("GoRoAuth", "UfmDocumentsProvider: openDocument $documentId, mode $mode")
        if (isNetworkDoc(documentId)) {
            return openNetworkDocument(documentId, mode, signal)
        }
        val absPath = fromSafDocId(documentId)
        return ParcelFileDescriptor.open(File(absPath), ParcelFileDescriptor.parseMode(mode))
    }

    /**
     * Opens a network file for reading or writing.
     *
     * - SMB (API 26+): uses StorageManager.openProxyFileDescriptor() for seekable
     *   random access (read and read/write). Crucial for emulators playing game files.
     *   Write-only mode uses the sequential pipe.
     * - FTP/TV: background thread streams bytes through a sequential pipe.
     */
    private fun openNetworkDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        GoRoLog.d("GoRoAuth", "UfmDocumentsProvider: openNetworkDocument $documentId")
        val (share, path) = resolveNetwork(documentId)
            ?: throw IOException("Unknown network share for: $documentId")

        val parsedMode = ParcelFileDescriptor.parseMode(mode)
        val isWrite = (parsedMode and ParcelFileDescriptor.MODE_WRITE_ONLY) != 0
        val isReadWrite = (parsedMode and ParcelFileDescriptor.MODE_READ_WRITE) != 0
        val isWriteAction = isWrite || isReadWrite
        
        val isStrictlyWrite = isWrite && !isReadWrite
        val isSmb = share.type == ShareType.SMB
        
        // Only use the seekable ProxyFileDescriptor for share types that support TRUE random access
        // without per-request reconnections:
        //   - SMB (JCIFS): persistent TCP connection, supports REST-equivalent seeks
        //   - SFTP/SCP: persistent SSH connection, supports true byte-offset reads
        //   - Cloud (GDrive, OneDrive, Dropbox, S3): HTTP Range requests, no reconnect overhead
        //
        // FTP and TV are EXCLUDED because FTP is a sequential transfer protocol. Every seek
        // would require tearing down and reconnecting (port 21), which exhausts the server's
        // connection limit and causes SocketTimeoutException cascades.
        val supportsRandomAccess = share.type == ShareType.SMB ||
            share.type == ShareType.SFTP ||
            share.type == ShareType.SCP ||
            share.type == ShareType.GOOGLE_DRIVE ||
            share.type == ShareType.ONEDRIVE ||
            share.type == ShareType.DROPBOX ||
            share.type == ShareType.AWS_S3 ||
            share.type == ShareType.IDRIVE_E2 ||
            share.type == ShareType.WEBDAV
            // NFS: uses ProxyFileDescriptor which opens a new NFS connection per call,
            // exhausting the server's connection limit. Use sequential pipe instead.
            // WEBDAV: HTTP Range requests via OkHttp provide stateless random access

        val canUseProxy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            supportsRandomAccess && (!isStrictlyWrite || isSmb)
        
        return if (canUseProxy) {
            openNetworkDocumentWithProxy(share, path, isWriteAction)
        } else if (isStrictlyWrite) {
            // Strictly Write-only fallback
            openNetworkDocumentForWrite(share, path)
        } else {
            // Sequential pipe fallback for FTP/TV (downgrades 'rw' to 'r' — pipes are unidirectional)
            openNetworkDocumentForRead(share, path)
        }
    }

    /**
     * Opens a network file via a ProxyFileDescriptor — provides a seekable fd backed
     * by random-access reads/writes. Requires API 26+.
     *
     * The callback runs on a dedicated HandlerThread (not the main looper)
     * because onRead()/onWrite() perform network I/O.
     */
    private fun openNetworkDocumentWithProxy(
        share: NetworkShare,
        path: String,
        isWrite: Boolean = false
    ): ParcelFileDescriptor {
        val ctx = context ?: throw IOException("No context")
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        // Dedicated background thread for I/O callbacks — network reads
        // MUST NOT run on the main looper.
        val handlerThread = android.os.HandlerThread("ufm-net-proxy").apply { start() }

        var handle: za.kilowatch.ultimatefilemanager.network.IRandomAccessFile? = null
        
        fun getOrOpenHandle(): za.kilowatch.ultimatefilemanager.network.IRandomAccessFile {
            if (handle == null) {
                handle = when (share.type) {
                    ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.JcifsFallbackClient.openRandomAccessFile(share, path, isWrite)
                    ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.openRandomAccessFile(share, path)
                    ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openRandomAccessFile(share, path)
                    ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openRandomAccessFile(share, path)
                    ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openRandomAccessFile(share, path)
                    ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openRandomAccessFile(share, path)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openRandomAccessFile(share, path)
                    ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openRandomAccessFile(share, path)
                    ShareType.DLNA -> DlnaShareClient.openRandomAccessFile(share, path)
                    else -> throw IOException("Random access not supported for ${share.type}")
                }
            }
            return handle!!
        }

        val callback = object : ProxyFileDescriptorCallback() {
            // 2 MB cache buffer for read-ahead to minimize network IO calls from Android FUSE
            private val CACHE_SIZE = 2 * 1024 * 1024
            private val cacheBuffer = ByteArray(CACHE_SIZE)
            private var cacheStartPos = -1L
            private var cacheEndPos = -1L
            override fun onGetSize(): Long {
                return try {
                    getOrOpenHandle().size
                } catch (e: Exception) {
                    GoRoLog.e("ProxyFileDescriptor error getting size", e)
                    throw ErrnoException("onGetSize", OsConstants.EIO)
                }
            }

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                return try {
                    val handleObj = getOrOpenHandle()
                    if (offset >= handleObj.size) return 0

                    val bytesToRead = size
                    if (bytesToRead <= 0) return 0

                    // Fast path: fulfill from cache
                    if (offset >= cacheStartPos && offset < cacheEndPos) {
                        val availableInCache = (cacheEndPos - offset).toInt()
                        val toCopy = minOf(bytesToRead, availableInCache)
                        System.arraycopy(cacheBuffer, (offset - cacheStartPos).toInt(), data, 0, toCopy)
                        return toCopy
                    }

                    // Cache miss: Load large chunk block from network into cache
                    val fetchSize = minOf(CACHE_SIZE.toLong(), handleObj.size - offset).toInt()
                    if (fetchSize <= 0) return 0

                    val readLength = handleObj.read(offset, cacheBuffer, fetchSize)
                    if (readLength <= 0) return 0

                    cacheStartPos = offset
                    cacheEndPos = offset + readLength

                    // Immediately fulfill after caching
                    val toCopy = minOf(bytesToRead, readLength)
                    System.arraycopy(cacheBuffer, 0, data, 0, toCopy)
                    return toCopy
                } catch (e: Exception) {
                    GoRoLog.e("ProxyFileDescriptor error reading", e)
                    throw ErrnoException("onRead", OsConstants.EIO)
                }
            }
            
            override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                if (!isWrite) throw ErrnoException("onWrite", OsConstants.EBADF)
                return try {
                    getOrOpenHandle().write(offset, data, size)
                } catch (e: Exception) {
                    GoRoLog.e("ProxyFileDescriptor error writing", e)
                    throw ErrnoException("onWrite", OsConstants.EIO)
                }
            }

            override fun onRelease() {
                try {
                    handle?.close()
                } catch (e: Exception) {
                    GoRoLog.e("ProxyFileDescriptor error closing", e)
                } finally {
                    handlerThread.quitSafely()
                }
            }
        }
        
        val mode = if (isWrite) ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY
        return try {
            sm.openProxyFileDescriptor(mode, callback, android.os.Handler(handlerThread.looper))
        } catch (e: IOException) {
            // Android has a finite pool of FUSE mount points (~10-15 system-wide).
            // If a prior session's onRelease hasn't fully cleaned up, new proxy requests fail
            // with FuseUnavailableMountException. Fall back to a sequential pipe so playback
            // still works, just without seeking support.
            GoRoLog.e("openNetworkDocumentWithProxy: FUSE unavailable, falling back to pipe", e)
            handlerThread.quitSafely()
            runCatching { handle?.close() }
            openNetworkDocumentForRead(share, path)
        }
    }

    /** Read pipe: network → write-end → caller reads from read-end (FTP/TV fallback). */
    private fun openNetworkDocumentForRead(
        share: za.kilowatch.ultimatefilemanager.network.NetworkShare,
        path: String
    ): ParcelFileDescriptor {
        GoRoLog.d("GoRoAuth", "UfmDocumentsProvider: openNetworkDocumentForRead ${share.host}, $path")
        val pipes = ParcelFileDescriptor.createPipe()
        val readEnd  = pipes[0]
        val writeEnd = pipes[1]
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    runBlocking(Dispatchers.IO) {
                        val inputStream = when (share.type) {
                            ShareType.SMB -> SmbShareClient.openInputStream(share, path)
                            ShareType.FTP -> FtpShareClient.openInputStream(share, path)
                            ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.openInputStream(share, path)
                            ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(share, path)
                            ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, path).first
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, path).first
                            ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, path).first
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, path).first
                            ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(share, path).first
                            ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(share, path).first
                            ShareType.NFS -> NfsShareClient.openInputStream(share, path)
                            ShareType.DLNA -> DlnaShareClient.openInputStream(share, path)
                        }
                        inputStream.use { it.copyTo(out) }
                    }
                }
            } catch (e: Exception) {
                runCatching { writeEnd.closeWithError(e.message ?: "Stream error") }
            }
        }.also { it.name = "ufm-net-read"; it.isDaemon = true }.start()
        return readEnd
    }

    /**
     * Write pipe: caller writes into write-end; background thread reads from read-end
     * and pushes to the network.
     *
     * For TV shares we must know the content length before uploading (NanoHTTPD cannot
     * handle chunked bodies), so we spool to a temp file first.
     */
    private fun openNetworkDocumentForWrite(
        share: za.kilowatch.ultimatefilemanager.network.NetworkShare,
        path: String
    ): ParcelFileDescriptor {
        if (share.type == ShareType.TV) {
            return openTvDocumentForWrite(share, path)
        }

        // SMB / FTP — direct streaming via reverse pipe
        val pipes = ParcelFileDescriptor.createPipe()
        val readEnd  = pipes[0]
        val writeEnd = pipes[1]
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                    runBlocking(Dispatchers.IO) {
                        when (share.type) {
                            ShareType.SMB ->
                                SmbShareClient.openOutputStream(share, path).use { out -> input.copyTo(out) }
                            ShareType.FTP ->
                                FtpShareClient.openOutputStream(share, path).use { out -> input.copyTo(out) }
                            ShareType.SFTP, ShareType.SCP ->
                                za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(share, path).use { out -> input.copyTo(out) }
                            ShareType.NFS ->
                                NfsShareClient.openOutputStream(share, path).use { out -> input.copyTo(out) }
                            ShareType.DLNA ->
                                throw UnsupportedOperationException("DLNA is read-only")
                            else -> { /* TV handled above */ }
                        }
                    }
                }
            } catch (e: Exception) {
                GoRoLog.e("ufm-net-write error for $path", e)
                runCatching { writeEnd.closeWithError(e.message ?: "Write error") }
            }
        }.also { it.name = "ufm-net-write"; it.isDaemon = true }.start()
        return writeEnd
    }

    /**
     * TV write: spool the incoming bytes to a temp file so we know the total
     * Content-Length, then upload to the TV with [TvShareClient.uploadStream].
     */
    private fun openTvDocumentForWrite(
        share: za.kilowatch.ultimatefilemanager.network.NetworkShare,
        path: String
    ): ParcelFileDescriptor {
        val pipes = ParcelFileDescriptor.createPipe()
        val readEnd  = pipes[0]
        val writeEnd = pipes[1]
        val ctx = context ?: run {
            runCatching { pipes[0].close(); pipes[1].close() }
            throw IOException("No context available")
        }
        val tempFile = java.io.File(ctx.cacheDir, "ufm_tv_upload_${System.currentTimeMillis()}.tmp")
        Thread {
            try {
                // 1. Spool caller's data into temp file
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                    tempFile.outputStream().use { out -> input.copyTo(out) }
                }
                // 2. Upload with correct Content-Length
                tempFile.inputStream().use { input ->
                    runBlocking(Dispatchers.IO) {
                        za.kilowatch.ultimatefilemanager.network.TvShareClient.uploadStream(
                            share, path, input, tempFile.length()
                        )
                    }
                }
            } catch (e: Exception) {
                GoRoLog.e("ufm-tv-write error for $path", e)
                runCatching { writeEnd.closeWithError(e.message ?: "TV write error") }
            } finally {
                runCatching { tempFile.delete() }
            }
        }.also { it.name = "ufm-tv-write"; it.isDaemon = true }.start()
        return writeEnd
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor? {
        if (isNetworkDoc(documentId)) return null
        val absPath = try { fromSafDocId(documentId) } catch (e: Exception) { return null }
        val file = File(absPath)
        if (!file.exists() || !file.isFile) return null
        val ext = file.extension.lowercase()
        val isImage = ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
        val isVideo = ext in VIDEO_EXTENSIONS
        if (!isImage && !isVideo) return null

        return try {
            val hint = sizeHint ?: Point(256, 256)
            val bitmap: android.graphics.Bitmap? = if (isImage) {
                if (ext == "jxl") {
                    // BitmapFactory cannot decode JXL — use JxlCoder.decodeSampled()
                    try {
                        val bytes = file.readBytes()
                        val maxDim = maxOf(hint.x, hint.y).coerceAtLeast(64)
                        com.awxkee.jxlcoder.JxlCoder.decodeSampled(bytes, maxDim, maxDim)
                    } catch (_: Exception) { null }
                } else {
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        android.graphics.BitmapFactory.decodeFile(absPath, this)
                        val maxDim = maxOf(hint.x, hint.y).coerceAtLeast(64)
                        inSampleSize = maxOf(1, minOf(outWidth, outHeight) / maxDim)
                        inJustDecodeBounds = false
                    }
                    var decoded = android.graphics.BitmapFactory.decodeFile(absPath, opts)
                    if (decoded == null) {
                        decoded = try {
                            val exif = android.media.ExifInterface(absPath)
                            exif.thumbnailBitmap ?: exif.thumbnailBytes?.let { bytes ->
                                val bOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bOpts)
                                val maxDim = maxOf(hint.x, hint.y).coerceAtLeast(64)
                                bOpts.inSampleSize = maxOf(1, minOf(bOpts.outWidth, bOpts.outHeight) / maxDim)
                                bOpts.inJustDecodeBounds = false
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bOpts)
                            } ?: za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(absPath, 0, hint.x.coerceAtLeast(64), hint.y.coerceAtLeast(64))
                        } catch (_: Exception) {
                            za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(absPath, 0, hint.x.coerceAtLeast(64), hint.y.coerceAtLeast(64))
                        }
                    }
                    decoded
                }
            } else {
                // Video thumbnail
                val pct = context?.let { za.kilowatch.ultimatefilemanager.settings.VideoThumbnailTimePreferenceManager.getPercent(it) } ?: 10
                var vidBmp: android.graphics.Bitmap? = za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(
                    absPath, pct, hint.x.coerceAtLeast(64), hint.y.coerceAtLeast(64)
                )
                if (vidBmp == null) {
                    vidBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            android.media.ThumbnailUtils.createVideoThumbnail(
                                file, android.util.Size(hint.x.coerceAtLeast(64), hint.y.coerceAtLeast(64)), signal
                            )
                        } catch (_: Exception) { null }
                    } else {
                        try {
                            @Suppress("DEPRECATION")
                            android.media.ThumbnailUtils.createVideoThumbnail(
                                absPath, android.provider.MediaStore.Video.Thumbnails.MINI_KIND
                            )
                        } catch (_: Exception) { null }
                    }
                }
                vidBmp
            }
            if (bitmap == null) return null

            // Write bitmap to a pipe so the caller can stream it
            val pipes = ParcelFileDescriptor.createPipe()
            val readEnd  = pipes[0]
            val writeEnd = pipes[1]
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    }
                } catch (_: Exception) {
                    runCatching { writeEnd.close() }
                } finally {
                    bitmap.recycle()
                }
            }.also { it.name = "ufm-thumb"; it.isDaemon = true }.start()
            AssetFileDescriptor(readEnd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        } catch (e: Exception) {
            GoRoLog.w("openDocumentThumbnail: failed for $documentId", e)
            null
        }
    }

    // ── Write operations (local only — network write is future work) ──────────

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val safeDisplayName = displayName.replace(Regex("[/\\\\:]|\\.\\."), "").trim().ifEmpty { "unnamed_document" }
        
        if (isNetworkDoc(parentDocumentId)) {
            val (share, path) = resolveNetwork(parentDocumentId)
                ?: throw IOException("Unknown network share")
            if (share.readOnly) throw IOException("Share is read-only")
            val newPath = if (path.isEmpty()) safeDisplayName else "$path/$safeDisplayName"
            if (mimeType == Document.MIME_TYPE_DIR) {
                runBlocking(Dispatchers.IO) {
                    when (share.type) {
                        ShareType.SMB -> SmbShareClient.mkdir(share, newPath)
                        ShareType.FTP -> FtpShareClient.mkdir(share, newPath)
                        ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.mkdir(share, newPath)
                        ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.mkdir(share, newPath)
                        ShareType.ONEDRIVE -> OnedriveShareClient.mkdir(share, newPath)
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, newPath)
                        ShareType.DROPBOX -> DropboxShareClient.mkdir(share, newPath)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, newPath)
                        ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.mkdir(share, newPath)
                        ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.mkdir(share, newPath)
                        ShareType.NFS -> NfsShareClient.mkdir(share, newPath)
                        ShareType.DLNA -> DlnaShareClient.mkdir(share, newPath)
                    }
                }
            } else {
                // Create an empty file via a write stream
                runBlocking(Dispatchers.IO) {
                    when (share.type) {
                        ShareType.SMB -> SmbShareClient.openOutputStream(share, newPath).close()
                        ShareType.FTP -> FtpShareClient.openOutputStream(share, newPath).close()
                        ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.uploadStream(
                            share, newPath, java.io.ByteArrayInputStream(ByteArray(0)), 0L
                        )
                        ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(share, newPath).close()
                        ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(share, newPath).close()
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(share, newPath).close()
                        ShareType.DROPBOX -> DropboxShareClient.openOutputStream(share, newPath).close()
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.openOutputStream(share, newPath).close() }
                        ShareType.WEBDAV -> { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(share, newPath).close() }
                        ShareType.WEBDAV -> { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(share, newPath).close() }
                        ShareType.NFS -> NfsShareClient.openOutputStream(share, newPath).close()
                        ShareType.DLNA -> DlnaShareClient.openOutputStream(share, newPath).close()
                    }
                }
            }
            return buildNetDocId(share, newPath)
        }
        val absPath = fromSafDocId(parentDocumentId)
        val parent = File(absPath)
        val newFile = File(parent, safeDisplayName)
        if (mimeType == Document.MIME_TYPE_DIR) newFile.mkdirs() else newFile.createNewFile()
        return toSafDocId(newFile.absolutePath)
    }

    override fun deleteDocument(documentId: String) {
        if (isNetworkDoc(documentId)) {
            val (share, path) = resolveNetwork(documentId)
                ?: throw IOException("Unknown network share")
            if (share.readOnly) throw IOException("Share is read-only")
            // We don't know if it's a file or dir without listing, so try file first, then dir
            runBlocking(Dispatchers.IO) {
                runCatching {
                    when (share.type) {
                        ShareType.SMB -> SmbShareClient.deleteFile(share, path)
                        ShareType.FTP -> FtpShareClient.deleteFile(share, path)
                        ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteFile(share, path)
                        ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(share, path, false)
                        ShareType.ONEDRIVE -> OnedriveShareClient.deleteFile(share, path)
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, path)
                        ShareType.DROPBOX -> DropboxShareClient.deleteFile(share, path)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, path)
                        ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(share, path)
                        ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(share, path)
                        ShareType.NFS -> NfsShareClient.deleteFile(share, path)
                        ShareType.DLNA -> DlnaShareClient.deleteFile(share, path)
                    }
                }.onFailure {
                    when (share.type) {
                        ShareType.SMB -> SmbShareClient.deleteDir(share, path)
                        ShareType.FTP -> FtpShareClient.deleteDir(share, path)
                        ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteDir(share, path)
                        ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(share, path, true)
                        ShareType.ONEDRIVE -> OnedriveShareClient.deleteFile(share, path)
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, path)
                        ShareType.DROPBOX -> DropboxShareClient.deleteFile(share, path)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, path)
                        ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(share, path)
                        ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(share, path)
                        ShareType.NFS -> NfsShareClient.deleteDir(share, path)
                        ShareType.DLNA -> DlnaShareClient.deleteDir(share, path)
                    }
                }
            }
            return
        }
        val absPath = fromSafDocId(documentId)
        val file = File(absPath)
        if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(absPath)) {
            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(absPath)
        } else {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (isNetworkDoc(documentId)) {
            val (share, path) = resolveNetwork(documentId)
                ?: throw IOException("Unknown network share")
            if (share.readOnly) throw IOException("Share is read-only")
            val parentPath = parentPath(path)
            val newPath = if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"
            runBlocking(Dispatchers.IO) {
                when (share.type) {
                    ShareType.SMB -> SmbShareClient.rename(share, path, newPath)
                    ShareType.FTP -> FtpShareClient.rename(share, path, newPath)
                    ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.rename(share, path, newPath)
                    ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.rename(share, path, newPath)
                    ShareType.ONEDRIVE -> OnedriveShareClient.rename(share, path, newPath)
                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, path, newPath)
                    ShareType.DROPBOX -> DropboxShareClient.rename(share, path, newPath)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, path, newPath)
                    ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.rename(share, path, newPath)
                    ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.rename(share, path, newPath)
                    ShareType.NFS -> NfsShareClient.rename(share, path, newPath)
                    ShareType.DLNA -> DlnaShareClient.rename(share, path, newPath)
                }
            }
            return buildNetDocId(share, newPath)
        }
        val absPath = fromSafDocId(documentId)
        val file = File(absPath)
        val renamed = File(file.parent, displayName)
        file.renameTo(renamed)
        return toSafDocId(renamed.absolutePath)
    }

    // ── Search ───────────────────────────────────────────────────────────────

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        // Local search only for now
        val absPath = fromSafDocId(rootId)
        val root = File(absPath)
        if (root.exists()) searchFiles(root, query.lowercase(), result, depth = 0)
        return result
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<String>?): Cursor {
        GoRoLog.d("queryRecentDocuments: rootId=$rootId")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val absPath = fromSafDocId(rootId)
        val root = File(absPath)
        if (!root.exists()) return result

        val recentsDirs = listOf(
            "DCIM", "Pictures", "Downloads", "Documents", "Music", "Movies", "Android/media"
        )
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 // 7 days
        val allFiles = mutableListOf<File>()

        for (dirName in recentsDirs) {
            val dir = File(root, dirName)
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() > cutoff) {
                        allFiles.add(file)
                    }
                }
            }
        }

        allFiles.sortedByDescending { it.lastModified() }.take(64).forEach { includeFile(result, it) }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (isNetworkDoc(parentDocumentId) && isNetworkDoc(documentId)) {
            val (parentShare, parentPath) = resolveNetwork(parentDocumentId) ?: return false
            val (childShare, childPath) = resolveNetwork(documentId) ?: return false
            if (parentShare.id != childShare.id) return false
            
            val pPath = parentPath.trimEnd('/')
            if (pPath.isEmpty()) return true
            return childPath.startsWith("$pPath/")
        } else if (!isNetworkDoc(parentDocumentId) && !isNetworkDoc(documentId)) {
            if (parentDocumentId == documentId) return true
            val parent = if (parentDocumentId.endsWith("/")) parentDocumentId else "$parentDocumentId/"
            return documentId.startsWith(parent)
        }
        return false
    }

    // ── Network helpers ───────────────────────────────────────────────────────

    private fun isNetworkDoc(documentId: String) = documentId.startsWith(NET_SCHEME)

    /**
     * Parses "net://<shareId>/<remote/path>" → (NetworkShare, "/remote/path")
     */
    private fun resolveNetwork(documentId: String): Pair<NetworkShare, String>? {
        val withoutScheme = if(documentId.startsWith("net://")) {
            documentId.removePrefix("net://")
        } else {
            documentId.removePrefix(NET_SCHEME)
        }
        val slashIdx = withoutScheme.indexOf('/')
        val shareId  = if (slashIdx < 0) withoutScheme else withoutScheme.substring(0, slashIdx)
        val path     = if (slashIdx < 0) "" else withoutScheme.substring(slashIdx + 1)
        val ctx = context ?: return null
        val share = NetworkShareRepository.getInstance(ctx).getById(shareId) ?: return null
        return share to path
    }

    private fun buildNetDocId(share: NetworkShare, path: String): String {
        return "${share.docIdPrefix}${path.trimStart('/')}"
    }

    private fun listNetworkFiles(share: NetworkShare, path: String): List<NetworkFile> {
        return try {
            runBlocking(Dispatchers.IO) {
                when (share.type) {
                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB ->
                        SmbShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP ->
                        FtpShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.TV ->
                        za.kilowatch.ultimatefilemanager.network.TvShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP ->
                        za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE ->
                        za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE ->
                        za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX ->
                        za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 ->
                        za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV ->
                        za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV ->
                        za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS ->
                        NfsShareClient.listFiles(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA ->
                        DlnaShareClient.listFiles(share, path)
                }
            }
        } catch (e: Exception) {
            za.kilowatch.ultimatefilemanager.util.GoRoLog.e("listNetworkFiles failed for share=${share.host}, path=$path", e)
            emptyList()
        }
    }

    private fun includeNetworkFile(cursor: MatrixCursor, share: NetworkShare, file: NetworkFile) {
        val docId   = buildNetDocId(share, file.path)
        val mime    = if (file.isDirectory) Document.MIME_TYPE_DIR else getMimeType(file.name)
        val flags   = when {
            share.readOnly  -> 0  // read-only: no write flags at all
            file.isDirectory -> Document.FLAG_DIR_SUPPORTS_CREATE or
                    Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME
            else -> Document.FLAG_SUPPORTS_WRITE or
                    Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME
        }
        var size = if (file.isDirectory) null else file.size
        
        if (size == 0L && !file.isDirectory) {
            size = 2147483647L // 2GB fake size
            GoRoLog.w("Mocking 2GB size for 0-byte file: ${file.name}")
        }
        
        GoRoLog.d("includeNetworkFile: docId=$docId, mime=$mime, flags=$flags, size=$size")
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID,   docId)
            add(Document.COLUMN_DISPLAY_NAME,  file.name)
            add(Document.COLUMN_MIME_TYPE,     mime)
            add(Document.COLUMN_FLAGS,         flags)
            add(Document.COLUMN_SIZE,          size)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified)
        }
    }

    /**
     * Adds a synthetic directory row to the cursor.
     *
     * Used when queryDocument() is called for a network path that isn't found
     * in a parent listing — e.g. the tree root itself, or an intermediate path
     * that an app like PPSSPP constructs when navigating the document tree.
     */
    private fun synthesizeNetworkDir(
        cursor: MatrixCursor,
        documentId: String,
        displayName: String,
        readOnly: Boolean
    ) {
        val flags = if (readOnly) 0
                    else Document.FLAG_DIR_SUPPORTS_CREATE or
                         Document.FLAG_SUPPORTS_DELETE or
                         Document.FLAG_SUPPORTS_RENAME
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID,   documentId)
            add(Document.COLUMN_DISPLAY_NAME,  displayName)
            add(Document.COLUMN_MIME_TYPE,     Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS,         flags)
            add(Document.COLUMN_SIZE,          null)
            add(Document.COLUMN_LAST_MODIFIED, 0L)
        }
    }

    private fun parentPath(path: String): String {
        val idx = path.trimEnd('/').lastIndexOf('/')
        return if (idx <= 0) "" else path.substring(0, idx)
    }

    // ── Local helpers ─────────────────────────────────────────────────────────

    private fun includeFile(cursor: MatrixCursor, file: File) {
        if (!file.exists()) return
        
        val isDir = file.isDirectory
        val mimeType = if (isDir) Document.MIME_TYPE_DIR else getMimeType(file.name)
        val flags = buildDocumentFlags(file)
        
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID,   toSafDocId(file.absolutePath))
            add(Document.COLUMN_DISPLAY_NAME,  file.name)
            add(Document.COLUMN_MIME_TYPE,     mimeType)
            add(Document.COLUMN_FLAGS,         flags)
            add(Document.COLUMN_SIZE,          if (file.isFile) file.length() else null)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun buildDocumentFlags(file: File): Int {
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE or
                    Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        } else {
            if (file.canWrite()) flags = flags or Document.FLAG_SUPPORTS_WRITE or
                    Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            // Advertise thumbnail support for images and videos so picker apps
            // (e.g. Projectivity) know they can call openDocumentThumbnail()
            val ext = file.extension.lowercase()
            val hasThumbnail = ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS || ext in VIDEO_EXTENSIONS
            if (hasThumbnail) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }
        return flags
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return za.kilowatch.ultimatefilemanager.util.MimeTypeHelper.getOrFallback(ext)
    }

    private fun searchFiles(dir: File, query: String, cursor: MatrixCursor, depth: Int) {
        if (depth > 5) return
        val showHidden = HiddenFilesManager.isShowHiddenFilesEnabled
        dir.listFiles()?.forEach { file ->
            val isHidden = !showHidden && HiddenFilesManager.isJunkOrHidden(file.name)
            if (!isHidden) {
                if (file.name.lowercase().contains(query)) includeFile(cursor, file)
                if (file.isDirectory) searchFiles(file, query, cursor, depth + 1)
            }
        }
    }

    // ── SAF document-ID helpers ────────────────────────────────────────────

    /**
     * Converts an absolute filesystem path to a SAF-compatible document ID.
     *
     * Android's built-in ExternalStorageProvider uses the format `volumeId:relativePath`:
     *  - `/storage/emulated/0/Documents/test`  →  `primary:Documents/test`
     *  - `/storage/6622-27F5/roms/psp`          →  `6622-27F5:roms/psp`
     *
     * This avoids the double-slash problem that occurs when
     * `DocumentsContract.buildTreeDocumentUri()` prepends `/tree/` to an
     * absolute path that starts with `/`.
     */
    private fun toSafDocId(absolutePath: String): String {
        val ctx = context ?: return absolutePath
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (volume in sm.storageVolumes) {
            val volPath = volumePath(volume) ?: continue
            // Ensure volPath ends with / for reliable prefix matching
            val prefix = if (volPath.endsWith("/")) volPath else "$volPath/"
            if (absolutePath == volPath || absolutePath.startsWith(prefix)) {
                val volumeId = if (volume.isPrimary) "primary" else (volume.uuid ?: continue)
                val relativePath = if (absolutePath == volPath) "" else absolutePath.removePrefix(prefix)
                return "loc:$volumeId/$relativePath"
            }
        }
        // No matching volume — return the absolute path as-is (should be rare)
        GoRoLog.w("toSafDocId: no volume match for $absolutePath, using raw path")
        return absolutePath
    }

    /**
     * Converts a SAF-formatted document ID back to an absolute filesystem path.
     *
     * If the document ID does not contain `:`, it is treated as an absolute path
     * already (backward compatibility).
     */
    private fun fromSafDocId(safDocId: String): String {
        if (isNetworkDoc(safDocId)) return safDocId
        
        val volumeId: String
        val relativePath: String
        
        if (safDocId.startsWith("loc:")) {
            val withoutPrefix = safDocId.removePrefix("loc:")
            val slashIdx = withoutPrefix.indexOf('/')
            volumeId = if (slashIdx < 0) withoutPrefix else withoutPrefix.substring(0, slashIdx)
            relativePath = if (slashIdx < 0 || slashIdx == withoutPrefix.lastIndex) "" else withoutPrefix.substring(slashIdx + 1)
        } else {
            // Backward compatibility for legacy volumeId:relativePath format
            val colonIdx = safDocId.indexOf(':')
            if (colonIdx < 0) return safDocId // Raw absolute path
            
            volumeId = safDocId.substring(0, colonIdx)
            relativePath = safDocId.substring(colonIdx + 1)
        }

        val ctx = context ?: return safDocId
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (volume in sm.storageVolumes) {
            val id = if (volume.isPrimary) "primary" else (volume.uuid ?: continue)
            if (id == volumeId) {
                val volPath = volumePath(volume) ?: continue
                return if (relativePath.isEmpty()) volPath else "$volPath/$relativePath"
            }
        }
        // Volume not found (maybe ejected, or Android StorageVolumes API mismatch for OTG drives)
        // Fallback: Manually reconstruct standard Android mount paths since java.io.File won't understand "UUID:path"
        val fallbackPath = if (volumeId == "primary") {
            "/storage/emulated/0" + if (relativePath.isNotEmpty()) "/$relativePath" else ""
        } else {
            "/storage/$volumeId" + if (relativePath.isNotEmpty()) "/$relativePath" else ""
        }
        
        GoRoLog.w("fromSafDocId: volume '$volumeId' not found via StorageManager. Falling back to derived path: $fallbackPath")
        return fallbackPath
    }

    @Suppress("DEPRECATION")
    private fun volumePath(volume: android.os.storage.StorageVolume): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.absolutePath
        } else {
            try {
                val method = volume.javaClass.getMethod("getPath")
                method.invoke(volume) as? String
            } catch (e: Exception) {
                GoRoLog.w("volumePath: reflection failed for volume ${volume.uuid}", e)
                null
            }
        }
    }
}
