package za.kilowatch.ultimatefilemanager.bookmarks.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkContentType
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ClonedFileResult(
    val filePath: String,
    val thumbnailPath: String?,
    val fileName: String,
    val fileSize: Long,
    val contentType: String
)

object BookmarkFileCloner {

    fun getMediaDir(context: Context): File {
        val dir = File(context.filesDir, "bookmarks/media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getThumbnailDir(context: Context): File {
        val dir = File(context.filesDir, "bookmarks/thumbnails")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun cloneStream(context: Context, uri: Uri, defaultMime: String? = null): ClonedFileResult? = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val (rawName, sizeFromQuery) = queryMetadata(cr, uri)
        val mime = cr.getType(uri) ?: defaultMime ?: "*/*"
        val contentType = determineContentType(mime, rawName)

        val safeName = rawName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifBlank { "shared_file" }
        val ext = safeName.substringAfterLast('.', "")
        val uniqueFileName = "${UUID.randomUUID()}_$safeName"
        val mediaDir = getMediaDir(context)
        val destinationFile = File(mediaDir, uniqueFileName)

        try {
            cr.openInputStream(uri)?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                if (destinationFile.exists()) destinationFile.delete()
                return@withContext null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (destinationFile.exists()) destinationFile.delete()
            return@withContext null
        }

        val actualSize = destinationFile.length().takeIf { it > 0 } ?: sizeFromQuery
        val thumbnailPath = generateThumbnail(context, destinationFile, contentType)

        return@withContext ClonedFileResult(
            filePath = destinationFile.absolutePath,
            thumbnailPath = thumbnailPath,
            fileName = safeName,
            fileSize = actualSize,
            contentType = contentType
        )
    }

    private fun queryMetadata(cr: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = "shared_file"
        var size = 0L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.let { name = it }
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {
            uri.lastPathSegment?.let { name = it }
        }
        return Pair(name, size)
    }

    private fun determineContentType(mime: String, fileName: String): String {
        val lowerMime = mime.lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            lowerMime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic") -> BookmarkContentType.IMAGE
            lowerMime.startsWith("video/") || ext in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts") -> BookmarkContentType.VIDEO
            lowerMime.startsWith("audio/") || ext in setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus") -> BookmarkContentType.AUDIO
            lowerMime.startsWith("application/pdf") || ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip", "rar", "7z", "apk") -> BookmarkContentType.DOCUMENT
            else -> BookmarkContentType.DOCUMENT
        }
    }

    private fun generateThumbnail(context: Context, file: File, contentType: String): String? {
        val thumbDir = getThumbnailDir(context)
        val thumbFile = File(thumbDir, "thumb_${UUID.randomUUID()}.jpg")

        return try {
            when (contentType) {
                BookmarkContentType.IMAGE -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val sampleSize = calculateInSampleSize(bounds, 400, 400)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                    FileOutputStream(thumbFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    bmp.recycle()
                    thumbFile.absolutePath
                }
                BookmarkContentType.VIDEO -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 400, 400)
                        } else {
                            retriever.getFrameAtTime(1000000)
                        }
                        if (bmp != null) {
                            FileOutputStream(thumbFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            }
                            bmp.recycle()
                            thumbFile.absolutePath
                        } else null
                    } finally {
                        retriever.release()
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
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
}
