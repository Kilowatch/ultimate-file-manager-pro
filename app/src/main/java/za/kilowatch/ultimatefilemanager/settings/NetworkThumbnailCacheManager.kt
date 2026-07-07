package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import za.kilowatch.ultimatefilemanager.network.*
import za.kilowatch.ultimatefilemanager.network.DlnaShareClient
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import android.content.Intent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

class NetworkThumbnailCacheManager(private val context: Context) {

    private val db = NetworkThumbnailDatabase.getInstance(context)

    companion object {
        /**
         * Global semaphore that caps simultaneous thumbnail extractions to 3.
         *
         * A permit must be acquired BEFORE opening any SMB/SFTP/FTP connection or
         * creating a MediaMetadataRetriever instance.  This has two effects:
         *   1. Memory: at most 3 native retriever buffers exist at any time, preventing
         *      the OOM / process-kill seen with large HDR video files.
         *   2. Connections: at most 3 dedicated SMB TCP sessions are opened for thumbnails,
         *      staying well below the typical home-NAS limit of 10-20 sessions.
         */
        private val extractionSemaphore = Semaphore(3)

        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "m4v", "3gp", "webm", "wmv", "vob", "ogv",
            "ts", "m2ts", "mts", "flv", "mpg", "mpeg", "rmvb", "asf", "divx", "xvid"
        )

        /**
         * Containers where MediaMetadataRetriever is known to hang / OOM on HDR content
         * (MPEG-TS, BDMV, FLV, VOB).  On SMB we skip these entirely; on non-SMB shares
         * we still attempt the download-then-ThumbnailUtils path but skip the retriever.
         */
        private val SKIP_RETRIEVER_EXTENSIONS = setOf("ts", "m2ts", "mts", "flv", "vob")

        /** Max width/height for cached thumbnails. */
        private const val THUMB_MAX_PX = 512

        /** Timeout for a single retriever session (connect → probe → first sync frame). */
        private const val RETRIEVER_TIMEOUT_MS = 15_000L
    }

    /**
     * Main entry point for the adapter to get a thumbnail.
     * Tries cache first, then generates and caches if missing.
     */
    suspend fun getThumbnail(share: NetworkShare, networkFile: NetworkFile): String? = withContext(Dispatchers.IO) {
        GoRoLog.d("UFM_CACHE", "🚀 [GoRo] getThumbnail entry: ${networkFile.path}")
        val cached = getCachedThumbnailPath(share.id, networkFile.path)
        if (cached != null) return@withContext cached

        return@withContext generateAndCache(share, networkFile)
    }

    /**
     * Checks if a thumbnail already exists in the cache database and on disk.
     */
    suspend fun getCachedThumbnailPath(shareId: String, networkPath: String): String? = withContext(Dispatchers.IO) {
        if (!NetworkThumbnailPreferenceManager.isEnabled(context)) return@withContext null

        GoRoLog.d("UFM_CACHE", "🚀 [GoRo] Querying DB for: $networkPath")
        val entity = db.dao().get(shareId, networkPath)
        if (entity != null) {
            val cacheFolderPath = NetworkThumbnailPreferenceManager.getCachePath(context)
            val file = File(cacheFolderPath, entity.localFileName)
            if (file.exists()) {
                GoRoLog.d("UFM_CACHE", "🚀 [GoRo] Cache hit for ${networkPath}")
                return@withContext file.absolutePath
            } else {
                // If it's in DB but not on disk, clean up DB
                db.dao().delete(shareId, networkPath)
            }
        }
        return@withContext null
    }

    /**
     * Retrieves the stream and caches the thumbnail to disk.
     * Returns the absolute path of the generated cache file, or null if failed/limit reached.
     *
     * The global [extractionSemaphore] is acquired here — before any network connection is
     * opened — so that at most 2 extractions (and therefore 2 SMB connections) run at once.
     */
    suspend fun generateAndCache(
        share: NetworkShare,
        networkFile: NetworkFile
    ): String? = withContext(Dispatchers.IO) {
        if (!NetworkThumbnailPreferenceManager.isEnabled(context)) return@withContext null

        val cacheFolderPath = NetworkThumbnailPreferenceManager.getCachePath(context)
        val cacheFolder = File(cacheFolderPath)
        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs()
        }

        // 1. Check max size boundary
        val currentSize = db.dao().getTotalSizeBytes() ?: 0L
        val limitMb = NetworkThumbnailPreferenceManager.getCacheLimitMb(context)
        val limitBytes = limitMb * 1024L * 1024L
        if (currentSize >= limitBytes) {
            GoRoLog.w("UFM_CACHE", "Cache limit reached ($limitMb MB). Skipping generation.")
            return@withContext null
        }

        val ext = networkFile.name.substringAfterLast('.', "").lowercase()
        val isImage = ext in listOf("jpg", "jpeg", "png", "bmp", "webp", "gif")
        val isVideo = ext in VIDEO_EXTENSIONS
        val isApk = ext in listOf("apk", "xapk", "apks")

        if (!isImage && !isVideo && !isApk) return@withContext null

        // Hash the combination of shareId and network path to use as unique local filename
        val md = MessageDigest.getInstance("MD5")
        val input = share.id + networkFile.path
        val hashBytes = md.digest(input.toByteArray())
        val hashName = hashBytes.joinToString("") { "%02x".format(it) } + ".webp"
        val destFile = File(cacheFolder, hashName)

        val tempFile = if (isVideo || isApk) File(context.cacheDir, "temp_thumb_${System.currentTimeMillis()}.$ext") else null

        var finalBitmap: Bitmap? = null

        // ── Acquire semaphore BEFORE opening any network connection ────────────
        // This caps both simultaneous MediaMetadataRetriever instances (memory) and
        // simultaneous dedicated SMB TCP sessions (NAS connection limit).
        extractionSemaphore.withPermit {
            try {
                GoRoLog.d("UFM_CACHE", "Semaphore acquired for: ${networkFile.path}")
                GoRoLog.d("UFM_CACHE", "Generating thumbnail for: ${networkFile.path} on share: ${share.id}")

                val isRandomAccessCapable = share.type in listOf(
                    ShareType.SMB, ShareType.SFTP, ShareType.SCP, ShareType.FTP, ShareType.NFS,
                    ShareType.GOOGLE_DRIVE, ShareType.ONEDRIVE
                )
                val skipRetriever = ext in SKIP_RETRIEVER_EXTENSIONS

                if (isVideo && isRandomAccessCapable && !skipRetriever && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // ── Random-access path (SMB / SFTP / FTP / cloud) ─────────────────────
                    var randomAccess: IRandomAccessFile? = null
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        randomAccess = when (share.type) {
                            ShareType.SMB          -> SmbShareClient.openRandomAccessFile(share, networkFile.path)
                            ShareType.SFTP,
                            ShareType.SCP          -> SshShareClient.openRandomAccessFile(share, networkFile.path)
                            ShareType.FTP          -> FtpShareClient.openRandomAccessFile(share, networkFile.path)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openRandomAccessFile(share, networkFile.path)
                            ShareType.DROPBOX      -> DropboxShareClient.openRandomAccessFile(share, networkFile.path)
                            ShareType.AWS_S3,
                            ShareType.IDRIVE_E2    -> S3ShareClient.openRandomAccessFile(share, networkFile.path)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.openRandomAccessFile(share, networkFile.path)
                            ShareType.NFS          -> NfsShareClient.openRandomAccessFile(share, networkFile.path)
                            ShareType.DLNA       -> DlnaShareClient.openRandomAccessFile(share, networkFile.path)
                            else -> throw IllegalStateException("Unsupported RandomAccess ShareType")
                        }
                        val dataSource = RemoteMediaDataSource(randomAccess)

                        // Fix 2: 15-second timeout covers the entire retriever session
                        // (NTLM handshake + container probe + first-frame decode).
                        // TimeoutCancellationException is caught below and treated as a miss.
                        withTimeout(RETRIEVER_TIMEOUT_MS) {
                            // Fix 3: catch Throwable so OutOfMemoryError is handled gracefully.
                            try {
                                retriever.setDataSource(dataSource)
                                val durationUs = retriever.extractMetadata(
                                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                                )?.toLongOrNull()?.times(1000) ?: 0L
                                val pct = VideoThumbnailTimePreferenceManager.getPercent(context)
                                val timeUs = if (durationUs > 0) durationUs * pct / 100L else 0L
                                val rawFrame = retriever.getFrameAtTime(
                                    timeUs,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                                )
                                // Fix 6: Downscale immediately — do NOT hold a full 4K frame in memory.
                                finalBitmap = rawFrame?.let { scaleBitmap(it) }
                                rawFrame?.takeIf { it !== finalBitmap }?.recycle()
                            } catch (oom: OutOfMemoryError) {
                                GoRoLog.e("UFM_CACHE", "OOM decoding frame for ${networkFile.path} — nudging GC", oom)
                                finalBitmap?.recycle()
                                finalBitmap = null
                                System.gc()
                            } catch (e: Exception) {
                                GoRoLog.e("UFM_CACHE", "MediaMetadataRetriever failed on remote data stream", e)
                            }
                        }
                    } catch (tce: TimeoutCancellationException) {
                        GoRoLog.w("UFM_CACHE", "Retriever timed out for ${networkFile.path} — skipping")
                    } catch (e: Exception) {
                        GoRoLog.e("UFM_CACHE", "Remote Random Access video thumbnail failed for ${networkFile.path}", e)
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                        try { (retriever as? android.media.MediaDataSource)?.close() } catch (_: Exception) {}
                        randomAccess?.close()
                    }
                }

                if (finalBitmap == null) {
                    // ── Stream / download path ─────────────────────────────────────────────
                    try {
                        val inputStream: InputStream = when (share.type) {
                            ShareType.SMB          -> SmbShareClient.openInputStream(share, networkFile.path)
                            ShareType.FTP          -> FtpShareClient.openInputStream(share, networkFile.path)
                            ShareType.TV           -> TvShareClient.openInputStream(share, networkFile.path)
                            ShareType.SFTP,
                            ShareType.SCP          -> SshShareClient.openInputStream(share, networkFile.path)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.openInputStream(share, networkFile.path).first
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, networkFile.path).first
                            ShareType.DROPBOX      -> DropboxShareClient.openInputStream(share, networkFile.path).first
                            ShareType.AWS_S3,
                            ShareType.IDRIVE_E2    -> S3ShareClient.openInputStream(share, networkFile.path).first
                            ShareType.WEBDAV       -> WebDavShareClient.openInputStream(share, networkFile.path).first
                            ShareType.WEBDAV       -> WebDavShareClient.openInputStream(share, networkFile.path).first
                            ShareType.NFS          -> NfsShareClient.openInputStream(share, networkFile.path)
                            ShareType.DLNA       -> DlnaShareClient.openInputStream(share, networkFile.path)
                        }

                        inputStream.use { stream: InputStream ->
                            if (isImage) {
                                // Read the complete image (up to 5 MB) for reliable decode.
                                // A partial-read strategy does not work for JPEG: BitmapFactory
                                // returns a non-null but incomplete bitmap from truncated data,
                                // so the fallback path would never trigger.
                                val maxImageSize = 5 * 1024 * 1024
                                val data = ByteArray(16384)
                                val buffer = ByteArrayOutputStream()
                                var totalRead = 0
                                var bytesRead: Int
                                while (stream.read(data, 0, data.size).also { bytesRead = it } != -1) {
                                    buffer.write(data, 0, bytesRead)
                                    totalRead += bytesRead
                                    if (totalRead > maxImageSize) break
                                }
                                val imageBytes = buffer.toByteArray()
                                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
                                val sampledOptions = BitmapFactory.Options().apply {
                                    inSampleSize = calculateInSampleSize(options, THUMB_MAX_PX, THUMB_MAX_PX)
                                }
                                finalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, sampledOptions)

                            } else if (tempFile != null) {
                                // Video or APK: download into a temp file
                                FileOutputStream(tempFile).use { fos: FileOutputStream ->
                                    val data = ByteArray(16384)
                                    var totalRead = 0
                                    var bytesRead: Int
                                    // 50 MB limit for APKs; 10 MB for non-random-access video fallback
                                    // (first 10 MB typically contains the MOOV atom and keyframes)
                                    val limitSize = if (isApk) 50 * 1024 * 1024 else 10 * 1024 * 1024
                                    while (stream.read(data, 0, data.size).also { bytesRead = it } != -1) {
                                        fos.write(data, 0, bytesRead)
                                        totalRead += bytesRead
                                        if (totalRead > limitSize) break
                                    }
                                }

                                if (isApk) {
                                    val pm = context.packageManager
                                    val pi = pm.getPackageArchiveInfo(tempFile.absolutePath, 0)
                                    if (pi != null) {
                                        pi.applicationInfo?.sourceDir = tempFile.absolutePath
                                        pi.applicationInfo?.publicSourceDir = tempFile.absolutePath
                                        val drawable = pi.applicationInfo?.loadIcon(pm)
                                        if (drawable != null) {
                                            finalBitmap = Bitmap.createBitmap(
                                                drawable.intrinsicWidth.coerceAtLeast(1),
                                                drawable.intrinsicHeight.coerceAtLeast(1),
                                                Bitmap.Config.ARGB_8888
                                            )
                                            val canvas = android.graphics.Canvas(finalBitmap!!)
                                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                                            drawable.draw(canvas)
                                        }
                                    }
                                } else if (isVideo) {
                                    val pct = VideoThumbnailTimePreferenceManager.getPercent(context)
                                    var extractedFrame: Bitmap? = za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(
                                        tempFile.absolutePath,
                                        pct,
                                        THUMB_MAX_PX,
                                        THUMB_MAX_PX
                                    )

                                    if (extractedFrame == null) {
                                        extractedFrame = try {
                                            val localRetriever = android.media.MediaMetadataRetriever()
                                            try {
                                                localRetriever.setDataSource(tempFile.absolutePath)
                                                val durationMs = localRetriever.extractMetadata(
                                                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                                                )?.toLongOrNull() ?: 0L
                                                val timeUs = if (durationMs > 0) durationMs * 1000L * pct / 100L else 0L
                                                localRetriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                            } finally {
                                                localRetriever.release()
                                            }
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }

                                    finalBitmap = extractedFrame?.let { scaleBitmap(it) }
                                    extractedFrame?.takeIf { it !== finalBitmap }?.recycle()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        GoRoLog.e("UFM_CACHE", "Failed to fetch/decode ${networkFile.path}", e)
                    }
                }
            } finally {
                tempFile?.delete()
                GoRoLog.d("UFM_CACHE", "Semaphore released for: ${networkFile.path}")
            }
        } // ── end semaphore.withPermit ──────────────────────────────────────────

        if (finalBitmap != null) {
            try {
                FileOutputStream(destFile).use { out: FileOutputStream ->
                    finalBitmap?.compress(Bitmap.CompressFormat.WEBP, 80, out)
                }

                // Add to DB
                val parentFolder = if (networkFile.path.contains("/")) {
                    networkFile.path.substringBeforeLast("/", "")
                } else {
                    ""
                }
                val entity = NetworkThumbnailEntity(
                    shareId = share.id,
                    networkPath = networkFile.path,
                    localFileName = hashName,
                    sizeBytes = destFile.length(),
                    parentFolder = parentFolder
                )
                db.dao().insert(entity)

                GoRoLog.d("UFM_CACHE", "Successfully cached thumb: ${destFile.name}")

                // Broadcast so UI adapters can refresh if they are listening
                try {
                    val intent = Intent("za.kilowatch.ultimatefilemanager.ACTION_NETWORK_THUMBNAIL_CREATED")
                    intent.putExtra("shareId", share.id)
                    intent.putExtra("networkPath", networkFile.path)
                    intent.putExtra("localPath", destFile.absolutePath)
                    context.sendBroadcast(intent)
                } catch (broadcastEx: Exception) {
                    GoRoLog.w("UFM_CACHE", "Failed to send thumbnail-created broadcast: ${broadcastEx.message}")
                }

                return@withContext destFile.absolutePath
            } catch (e: Exception) {
                GoRoLog.e("UFM_CACHE", "Failed to write cache file", e)
                destFile.delete()
            } finally {
                finalBitmap?.recycle()
            }
        } else {
            GoRoLog.w("UFM_CACHE", "Final bitmap was null for ${networkFile.path}")
        }

        return@withContext null
    }

    /**
     * Fix 6: Scale a bitmap down so neither dimension exceeds [THUMB_MAX_PX].
     * Returns the original bitmap unchanged if it is already within bounds
     * (so the caller can detect equality and skip recycling the original).
     */
    private fun scaleBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= THUMB_MAX_PX && h <= THUMB_MAX_PX) return src
        val scale = THUMB_MAX_PX.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Cleans up database entries for the current folder that no longer exist,
     * and deletes their physical cache files.
     */
    suspend fun pruneStaleThumbnails(share: NetworkShare, currentFiles: List<NetworkFile>) = withContext(Dispatchers.IO) {
        if (currentFiles.isEmpty()) return@withContext

        val cacheFolderPath = NetworkThumbnailPreferenceManager.getCachePath(context)
        val cacheFolder = File(cacheFolderPath)

        // Use the first file to determine the parent folder
        val firstFile = currentFiles.first()
        val remoteFolder = if (firstFile.path.contains("/")) {
            firstFile.path.substringBeforeLast("/", "")
        } else {
            "" // Root
        }

        val currentRemotePaths = currentFiles.map { it.path }.toSet()
        val cachedEntries = db.dao().getByParentFolder(share.id, remoteFolder)

        for (entry in cachedEntries) {
            if (!currentRemotePaths.contains(entry.networkPath)) {
                // File deleted remotely, erase cache and DB entry
                val f = File(cacheFolder, entry.localFileName)
                if (f.exists()) f.delete()
                db.dao().delete(share.id, entry.networkPath)
                GoRoLog.d("UFM_CACHE", "Pruned stale thumb: ${entry.localFileName}")
            }
        }
    }

    /**
     * Returns the total usage of cached thumbnails in bytes.
     */
    suspend fun getCurrentCacheSize(): Long = withContext(Dispatchers.IO) {
        return@withContext db.dao().getTotalSizeBytes() ?: 0L
    }

    /**
     * Deletes all cached remote thumbnails from disk and clears the database.
     */
    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        db.dao().deleteAll()

        val cacheFolderPath = NetworkThumbnailPreferenceManager.getCachePath(context)
        if (cacheFolderPath.isNotEmpty()) {
            val cacheFolder = File(cacheFolderPath)
            if (cacheFolder.exists() && cacheFolder.isDirectory) {
                cacheFolder.listFiles()?.forEach { file: File ->
                    if (file.name.endsWith(".webp")) file.delete()
                }
            }
        }
        GoRoLog.i("UFM_CACHE", "Cache cleared completely.")
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
class RemoteMediaDataSource(
    private val randomAccess: IRandomAccessFile
) : android.media.MediaDataSource() {

    // Fix 4: Pre-allocate a reusable read buffer to avoid per-call heap churn.
    // MediaMetadataRetriever calls readAt() hundreds of times during container
    // probing; each ByteArray(size) allocation was fragmenting the heap.
    // The buffer grows lazily if a single read requests more than the current capacity.
    private var readBuffer = ByteArray(65536) // sized to match typical SMB max read

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        synchronized(this) {
            return try {
                // Cap per-read to 1 MB to prevent OOM from excessive reads
                val safeSize = minOf(size, 1_048_576)
                if (readBuffer.size < safeSize) {
                    readBuffer = ByteArray(safeSize)
                }
                val bytesRead = randomAccess.read(position, readBuffer, safeSize)
                if (bytesRead > 0) {
                    val copyLen = minOf(bytesRead, buffer.size - offset)
                    System.arraycopy(readBuffer, 0, buffer, offset, copyLen)
                }
                bytesRead
            } catch (e: Exception) {
                // Returning -1 tells MediaMetadataRetriever "I/O error, abort"
                // instead of throwing into native code which can crash the process.
                GoRoLog.w("UFM_CACHE", "readAt error at pos=$position size=$size: ${e.message}")
                -1
            }
        }
    }

    override fun getSize(): Long = randomAccess.size

    override fun close() {
        randomAccess.close()
    }
}
