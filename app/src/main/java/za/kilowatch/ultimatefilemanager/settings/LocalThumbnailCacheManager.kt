package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper
import za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper
import za.kilowatch.ultimatefilemanager.storage.FileAdapter
import za.kilowatch.ultimatefilemanager.storage.SafFile
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Manages persistent caching of local thumbnails (videos, audio covers, APKs, and camera/RAW images).
 * Caches compressed 512px WebP files on disk and indexes them in [LocalThumbnailDatabase].
 */
class LocalThumbnailCacheManager(private val context: Context) {

    private val db = LocalThumbnailDatabase.getInstance(context)

    companion object {
        private const val TAG = "LocalThumbnailCache"

        @Volatile
        private var instance: LocalThumbnailCacheManager? = null

        fun getInstance(context: Context): LocalThumbnailCacheManager {
            return instance ?: synchronized(this) {
                instance ?: LocalThumbnailCacheManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Global semaphore capping simultaneous local thumbnail extractions to 4.
         * Balances multi-core CPU decoding speed with smooth UI scrolling and low RAM usage.
         */
        private val extractionSemaphore = Semaphore(4)

        val VIDEO_EXTENSIONS = FileViewerRouter.VIDEO_EXTENSIONS
        val IMAGE_EXTENSIONS = FileViewerRouter.IMAGE_EXTENSIONS
        val APK_EXTENSIONS = setOf("apk", "xapk", "apks")
        val RAW_EXTENSIONS = setOf("raw", "dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "pef", "raf", "kdc", "dcr", "mos", "mef", "mrw")

        /** Max width/height for cached thumbnails. */
        private const val THUMB_MAX_PX = 512

        const val ACTION_LOCAL_THUMBNAIL_CREATED = "za.kilowatch.ultimatefilemanager.ACTION_LOCAL_THUMBNAIL_CREATED"
    }

    private fun normalizePath(file: File): String = file.absolutePath

    private fun getHashName(normalizedPath: String): String {
        val md = MessageDigest.getInstance("MD5")
        val hashBytes = md.digest(normalizedPath.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) } + ".webp"
    }

    /**
     * Fast synchronous check for existing thumbnail file on disk.
     * Returns absolute path of cached .webp if present and non-empty.
     */
    fun getExistingDiskThumbnail(file: File): String? {
        if (!ThumbnailPreferenceManager.isEnabled(context)) return null
        try {
            val cacheFolderPath = ThumbnailPreferenceManager.getCachePath(context)
            val hashName = getHashName(normalizePath(file))
            val destFile = File(cacheFolderPath, hashName)
            if (destFile.exists() && destFile.length() > 0) {
                return destFile.absolutePath
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Checks if a thumbnail already exists in the cache database and on disk,
     * and verifies that the source file has not been modified since the thumbnail was generated.
     */
    suspend fun getCachedThumbnailPath(file: File, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!force && !ThumbnailPreferenceManager.isEnabled(context)) return@withContext null

        val normPath = normalizePath(file)
        var entity = db.dao().get(normPath)
        if (entity == null && normPath != file.path) {
            entity = db.dao().get(file.path)
        }
        if (entity == null) return@withContext null

        val cacheFolderPath = ThumbnailPreferenceManager.getCachePath(context)
        val cacheFile = File(cacheFolderPath, entity.localFileName)

        // Validate that the cached file exists and the original file has not been modified
        if (cacheFile.exists() && cacheFile.length() > 0) {
            val fileModified = file.lastModified()
            val fileLen = file.length()
            val isModified = fileModified > 0L && entity.lastModified > 0L && Math.abs(fileModified - entity.lastModified) > 2000L
            val isSizeChanged = fileLen > 0L && entity.fileSize > 0L && fileLen != entity.fileSize
            if (!isModified && !isSizeChanged) {
                return@withContext cacheFile.absolutePath
            } else {
                // Source file was modified or replaced; invalidate stale cache
                cacheFile.delete()
                db.dao().delete(entity.filePath)
            }
        } else {
            // Missing physical file; clean up DB entry
            db.dao().delete(entity.filePath)
        }
        return@withContext null
    }

    /**
     * Pre-warms the in-memory thumbnail cache for an entire local directory using a single Room DB query.
     */
    suspend fun warmCacheForFolder(parentFolder: String) = withContext(Dispatchers.IO) {
        try {
            val cleanFolder = parentFolder.trimEnd('/')
            val entries = db.dao().getByParentFolder(cleanFolder)
            val cacheFolderPath = ThumbnailPreferenceManager.getCachePath(context)
            for (entry in entries) {
                val f = File(cacheFolderPath, entry.localFileName)
                if (f.exists() && f.length() > 0) {
                    FileAdapter.thumbnailPathCache[entry.filePath] = f.absolutePath
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Pre-warms the in-memory thumbnail cache for an arbitrary collection of files (including Search results or filtered categories).
     */
    suspend fun warmCacheForFiles(files: List<File>) = withContext(Dispatchers.IO) {
        if (files.isEmpty() || !ThumbnailPreferenceManager.isEnabled(context)) return@withContext
        try {
            val cacheFolderPath = ThumbnailPreferenceManager.getCachePath(context)
            val cacheFolder = File(cacheFolderPath)
            for (file in files) {
                if (file.isDirectory) continue
                val hashName = getHashName(normalizePath(file))
                val f = File(cacheFolder, hashName)
                if (f.exists() && f.length() > 0) {
                    FileAdapter.thumbnailPathCache[file.absolutePath] = f.absolutePath
                }
            }
            val parentFolders = files.mapNotNull { it.parentFile?.absolutePath?.trimEnd('/') }.distinct()
            for (parent in parentFolders) {
                val entries = db.dao().getByParentFolder(parent)
                for (entry in entries) {
                    val f = File(cacheFolderPath, entry.localFileName)
                    if (f.exists() && f.length() > 0) {
                        FileAdapter.thumbnailPathCache[entry.filePath] = f.absolutePath
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Main entry point to get or generate a cached thumbnail.
     */
    suspend fun getThumbnail(file: File, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val isSaf = file is SafFile ||
            SafTreeManager.isSafPath(file.absolutePath) ||
            SafTreeManager.hasTreePermissionForPath(context, file.absolutePath)
        if (isSaf) {
            val safShare = za.kilowatch.ultimatefilemanager.network.OnlineSafDoc.resolveSafShare(context, file.absolutePath)
            if (safShare != null) {
                val (netShare, remotePath) = safShare
                val netFile = za.kilowatch.ultimatefilemanager.network.NetworkFile(
                    name = file.name,
                    path = if (remotePath.startsWith("/")) remotePath else "/$remotePath",
                    isDirectory = false,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
                val netCacheMgr = za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager(context)
                return@withContext netCacheMgr.getThumbnail(netShare, netFile, force = force)
            }
        }

        val cached = getCachedThumbnailPath(file, force = force)
        if (cached != null) return@withContext cached

        return@withContext generateAndCache(file, force = force)
    }

    /**
     * Extracts, scales, compresses to WebP, and records the thumbnail in the database.
     */
    suspend fun generateAndCache(file: File, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val isSaf = file is SafFile ||
            SafTreeManager.isSafPath(file.absolutePath) ||
            SafTreeManager.hasTreePermissionForPath(context, file.absolutePath)
        if (isSaf) {
            val safShare = za.kilowatch.ultimatefilemanager.network.OnlineSafDoc.resolveSafShare(context, file.absolutePath)
            if (safShare != null) {
                val (netShare, remotePath) = safShare
                val netFile = za.kilowatch.ultimatefilemanager.network.NetworkFile(
                    name = file.name,
                    path = if (remotePath.startsWith("/")) remotePath else "/$remotePath",
                    isDirectory = false,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
                val netCacheMgr = za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager(context)
                return@withContext netCacheMgr.getThumbnail(netShare, netFile, force = force)
            }
        }

        if (!force && !ThumbnailPreferenceManager.isEnabled(context)) return@withContext null

        val cacheFolderPath = ThumbnailPreferenceManager.getCachePath(context)
        val cacheFolder = File(cacheFolderPath)
        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs()
        }

        // Check storage quota
        val currentSize = db.dao().getTotalSizeBytes() ?: 0L
        val limitMb = ThumbnailPreferenceManager.getCacheLimitMb(context)
        val limitBytes = limitMb * 1024L * 1024L
        if (currentSize >= limitBytes) {
            GoRoLog.w(TAG, "Cache limit reached ($limitMb MB). Skipping local thumbnail generation.")
            return@withContext null
        }

        val ext = file.extension.lowercase()
        val isImage = ext in IMAGE_EXTENSIONS
        val isVideo = ext in VIDEO_EXTENSIONS
        val isApk = ext in APK_EXTENSIONS
        val isAudio = FileViewerRouter.isAudio(ext)

        if (!isImage && !isVideo && !isApk && !isAudio) return@withContext null

        val normPath = normalizePath(file)
        val hashName = getHashName(normPath)
        val destFile = File(cacheFolder, hashName)

        var finalBitmap: Bitmap? = null

        extractionSemaphore.withPermit {
            try {
                val isSaf = file is SafFile ||
                    SafTreeManager.isSafPath(file.absolutePath) ||
                    SafTreeManager.hasTreePermissionForPath(context, file.absolutePath)
                val safDocUri = if (isSaf) {
                    (file as? SafFile)?.documentUri ?: SafTreeManager.getDocumentUriForPath(context, file.absolutePath)
                } else null

                if (isVideo) {
                    val pct = VideoThumbnailTimePreferenceManager.getPercent(context)
                    val isMjpeg = ext in listOf("mjpeg", "mjpg", "mjp")

                    if (isSaf && safDocUri != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                finalBitmap = context.contentResolver.loadThumbnail(safDocUri, Size(THUMB_MAX_PX, THUMB_MAX_PX), null)
                            } catch (_: Throwable) {}
                        }
                        if (finalBitmap == null) {
                            try {
                                val retriever = MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(context, safDocUri)
                                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    val durationUs = durationMs * 1000L
                                    val timeUs = if (durationUs > 0) durationUs * pct / 100L else 0L
                                    val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    if (raw != null) finalBitmap = scaleBitmap(raw)
                                } finally {
                                    try { retriever.release() } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                        }
                    } else {
                        // Native FFmpeg frame extraction
                        finalBitmap = FFmpegThumbnailHelper.extractVideoFrame(file.absolutePath, pct, THUMB_MAX_PX, THUMB_MAX_PX)
                        if (finalBitmap == null) {
                            finalBitmap = try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    ThumbnailUtils.createVideoThumbnail(file, Size(THUMB_MAX_PX, THUMB_MAX_PX), null)
                                } else {
                                    @Suppress("DEPRECATION")
                                    ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                                }
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }
                } else if (isAudio) {
                    finalBitmap = AudioCoverHelper.extractCover(context, file, THUMB_MAX_PX)
                } else if (isApk) {
                    val drawable = resolveApkIcon(file)
                    if (drawable != null) {
                        finalBitmap = try {
                            drawable.toBitmap(
                                width = drawable.intrinsicWidth.coerceIn(1, THUMB_MAX_PX),
                                height = drawable.intrinsicHeight.coerceIn(1, THUMB_MAX_PX),
                                config = Bitmap.Config.ARGB_8888
                            )
                        } catch (_: Throwable) {
                            val bmp = Bitmap.createBitmap(
                                drawable.intrinsicWidth.coerceIn(1, THUMB_MAX_PX),
                                drawable.intrinsicHeight.coerceIn(1, THUMB_MAX_PX),
                                Bitmap.Config.ARGB_8888
                            )
                            val canvas = Canvas(bmp)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)
                            bmp
                        }
                    }
                } else if (isImage) {
                    // Pre-scale large camera images, RAW, JXL, AVIF, HEIC
                    val isRaw = ext in RAW_EXTENSIONS
                    val isJxl = ext == "jxl"
                    val isAvif = ext == "avif"

                    if (isJxl) {
                        try {
                            val bytes = file.readBytes()
                            finalBitmap = com.awxkee.jxlcoder.JxlCoder.decodeSampled(bytes, THUMB_MAX_PX, THUMB_MAX_PX)
                        } catch (_: Throwable) {}
                    } else if (isAvif) {
                        try {
                            val bytes = file.readBytes()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                                finalBitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                                }
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                finalBitmap = com.radzivon.bartoshyk.avif.coder.HeifCoder().decodeSampled(
                                    bytes, THUMB_MAX_PX, THUMB_MAX_PX,
                                    com.radzivon.bartoshyk.avif.coder.PreferredColorConfig.RGBA_8888
                                )
                            }
                        } catch (_: Throwable) {}
                    } else {
                        // Standard or RAW sampled decode
                        if (isRaw) {
                            try {
                                if (isSaf && safDocUri != null) {
                                    context.contentResolver.openInputStream(safDocUri)?.use { stream ->
                                        val exif = androidx.exifinterface.media.ExifInterface(stream)
                                        val exifBmp = exif.thumbnailBitmap
                                        if (exifBmp != null) {
                                            finalBitmap = exifBmp
                                        } else {
                                            val bytes = exif.thumbnailBytes
                                            if (bytes != null) {
                                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                                val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / THUMB_MAX_PX)
                                                val sampledOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                                                finalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampledOpts)
                                            }
                                        }
                                    }
                                } else {
                                    val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
                                    val exifBmp = exif.thumbnailBitmap
                                    if (exifBmp != null) {
                                        finalBitmap = exifBmp
                                    } else {
                                        val bytes = exif.thumbnailBytes
                                        if (bytes != null) {
                                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                            val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / THUMB_MAX_PX)
                                            val sampledOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                                            finalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampledOpts)
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}

                            if (finalBitmap == null && !isSaf) {
                                finalBitmap = FFmpegThumbnailHelper.extractVideoFrame(file.absolutePath, 0, THUMB_MAX_PX, THUMB_MAX_PX)
                            }
                        }

                        if (finalBitmap == null) {
                            val inStream: InputStream? = if (isSaf && safDocUri != null) {
                                context.contentResolver.openInputStream(safDocUri)
                            } else {
                                file.inputStream()
                            }
                            inStream?.use { stream ->
                                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeStream(stream, null, boundsOptions)
                                val reqW = THUMB_MAX_PX
                                val reqH = THUMB_MAX_PX
                                var inSampleSize = 1
                                if (boundsOptions.outHeight > reqH || boundsOptions.outWidth > reqW) {
                                    val halfHeight = boundsOptions.outHeight / 2
                                    val halfWidth = boundsOptions.outWidth / 2
                                    while (halfHeight / inSampleSize >= reqH && halfWidth / inSampleSize >= reqW) {
                                        inSampleSize *= 2
                                    }
                                }
                                val decodeOptions = BitmapFactory.Options().apply {
                                    this.inSampleSize = inSampleSize
                                    inPreferredConfig = Bitmap.Config.RGB_565
                                }

                                val contentStream: InputStream? = if (isSaf && safDocUri != null) {
                                    context.contentResolver.openInputStream(safDocUri)
                                } else {
                                    file.inputStream()
                                }
                                contentStream?.use { secondStream ->
                                    finalBitmap = BitmapFactory.decodeStream(secondStream, null, decodeOptions)
                                }
                            }
                        }

                        if (finalBitmap == null && !isSaf) {
                            finalBitmap = FFmpegThumbnailHelper.extractVideoFrame(file.absolutePath, 0, THUMB_MAX_PX, THUMB_MAX_PX)
                        }
                    }
                }
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Failed to extract thumbnail for ${file.name}", e)
            }
        }

        if (finalBitmap != null) {
            val scaled = scaleBitmap(finalBitmap!!)
            try {
                if (!cacheFolder.exists()) {
                    cacheFolder.mkdirs()
                }
                FileOutputStream(destFile).use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                    } else {
                        @Suppress("DEPRECATION")
                        scaled.compress(Bitmap.CompressFormat.WEBP, 80, out)
                    }
                }

                val parentFolder = file.parentFile?.absolutePath?.trimEnd('/') ?: ""
                val entity = LocalThumbnailEntity(
                    filePath = normPath,
                    localFileName = hashName,
                    sizeBytes = destFile.length(),
                    lastModified = file.lastModified(),
                    fileSize = file.length(),
                    parentFolder = parentFolder
                )
                db.dao().insert(entity)
                FileAdapter.thumbnailPathCache[normPath] = destFile.absolutePath

                try {
                    val intent = Intent(ACTION_LOCAL_THUMBNAIL_CREATED).apply {
                        putExtra("filePath", normPath)
                        putExtra("localPath", destFile.absolutePath)
                    }
                    context.sendBroadcast(intent)
                } catch (_: Exception) {}

                return@withContext destFile.absolutePath
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Failed to write local thumbnail cache file", e)
                destFile.delete()
            } finally {
                if (scaled !== finalBitmap) {
                    scaled.recycle()
                }
                finalBitmap?.recycle()
            }
        }

        return@withContext null
    }

    private fun scaleBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= THUMB_MAX_PX && h <= THUMB_MAX_PX) return src
        val scale = THUMB_MAX_PX.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    private fun resolveApkIcon(file: File): Drawable? {
        return try {
            val isSaf = file is SafFile ||
                SafTreeManager.isSafPath(file.absolutePath) ||
                SafTreeManager.hasTreePermissionForPath(context, file.absolutePath)

            if (isSaf) {
                val inStream = SafTreeManager.openInputStream(context, file.absolutePath) ?: return null
                var bestRank = -1
                var bestBytes: ByteArray? = null

                fun densityRank(name: String): Int = when {
                    "xxxhdpi" in name -> 6
                    "xxhdpi"  in name -> 5
                    "xhdpi"   in name -> 4
                    "hdpi"    in name -> 3
                    "mdpi"    in name -> 2
                    "ldpi"    in name -> 1
                    else              -> 0
                }

                ZipInputStream(inStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val n = entry.name.lowercase()
                        val isIcon = n == "icon.png" || n == "ic_launcher.png" ||
                            (n.startsWith("res/mipmap") && n.endsWith(".png") && "ic_launcher" in n) ||
                            (n.startsWith("res/drawable") && n.endsWith(".png") && "ic_launcher" in n) ||
                            n.endsWith("/icon.png")
                        if (isIcon) {
                            val rank = densityRank(n)
                            if (rank > bestRank) {
                                bestRank = rank
                                bestBytes = zip.readBytes()
                                if (rank == 6) break
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                if (bestBytes != null && bestBytes!!.isNotEmpty()) {
                    val bmp = BitmapFactory.decodeByteArray(bestBytes, 0, bestBytes!!.size)
                    if (bmp != null) return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
                }
            }

            val pm = context.packageManager
            val pi = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
            pi.applicationInfo?.sourceDir = file.absolutePath
            pi.applicationInfo?.publicSourceDir = file.absolutePath
            pi.applicationInfo?.loadIcon(pm)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Cleans up database entries for a folder that no longer exist on disk,
     * and deletes their physical cache files.
     */
    suspend fun pruneStaleThumbnails(parentFolder: String, currentFiles: List<File>) = withContext(Dispatchers.IO) {
        if (currentFiles.isEmpty()) return@withContext

        val cacheFolderPath = ThumbnailPreferenceManager.getCachePath(context)
        val cacheFolder = File(cacheFolderPath)
        val cleanParent = parentFolder.trimEnd('/')
        val currentPaths = currentFiles.map { it.absolutePath }.toSet()
        val cachedEntries = db.dao().getByParentFolder(cleanParent)

        for (entry in cachedEntries) {
            if (!currentPaths.contains(entry.filePath)) {
                val originalFile = File(entry.filePath)
                if (!originalFile.exists()) {
                    val f = File(cacheFolder, entry.localFileName)
                    if (f.exists()) f.delete()
                    db.dao().delete(entry.filePath)
                }
            }
        }
    }

    /**
     * Evicts a single file's thumbnail from cache and DB.
     */
    suspend fun evictThumbnail(filePath: String) = withContext(Dispatchers.IO) {
        clearCacheForPath(filePath)
    }

    suspend fun clearCacheForPath(filePath: String) = withContext(Dispatchers.IO) {
        val entity = db.dao().get(filePath)
        if (entity != null) {
            val cacheFolderPath = ThumbnailPreferenceManager.getCachePath(context)
            val file = File(cacheFolderPath, entity.localFileName)
            if (file.exists()) file.delete()
            db.dao().delete(filePath)
        }
    }

    suspend fun clearCacheForFolder(folderPath: String) = withContext(Dispatchers.IO) {
        val prefix = if (folderPath.endsWith(File.separator)) "$folderPath%" else "$folderPath${File.separator}%"
        val entities = db.dao().getUnderFolder(folderPath, prefix)
        val cacheFolderPath = ThumbnailPreferenceManager.getCachePath(context)
        for (entity in entities) {
            val file = File(cacheFolderPath, entity.localFileName)
            if (file.exists()) file.delete()
        }
        db.dao().deleteUnderFolder(folderPath, prefix)
    }

    suspend fun clearCacheForSelection(selectedFiles: List<File>) = withContext(Dispatchers.IO) {
        for (f in selectedFiles) {
            if (f.isDirectory) {
                clearCacheForFolder(f.absolutePath)
            } else {
                clearCacheForPath(f.absolutePath)
            }
        }
    }

    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        db.dao().deleteAll()
        val cacheFolderPath = ThumbnailPreferenceManager.getCachePath(context)
        if (cacheFolderPath.isNotEmpty()) {
            val cacheFolder = File(cacheFolderPath)
            if (cacheFolder.exists() && cacheFolder.isDirectory) {
                cacheFolder.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".webp")) file.delete()
                }
            }
        }
    }

    suspend fun getCurrentCacheSize(): Long = withContext(Dispatchers.IO) {
        return@withContext db.dao().getTotalSizeBytes() ?: 0L
    }

    suspend fun getCachedCount(): Int = withContext(Dispatchers.IO) {
        return@withContext db.dao().getCount()
    }
}
