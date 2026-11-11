package za.kilowatch.ultimatefilemanager.viewer.openwith

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import za.kilowatch.ultimatefilemanager.util.MimeTypeHelper
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.viewer.MediaIntentHelper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Resolves local file paths to official Android system [MediaStore] content URIs
 * (e.g. `content://media/external/video/media/<id>`).
 *
 * Background (Issue #67):
 * On modern Android versions (Android 13–16+), external media players such as VLC for Android
 * often hold standard [android.Manifest.permission.READ_MEDIA_VIDEO] or
 * [android.Manifest.permission.READ_MEDIA_AUDIO] rather than broad `MANAGE_EXTERNAL_STORAGE`.
 * When handed private [androidx.core.content.FileProvider] URIs, VLC's native media engine
 * (`libmedialibrary`) fails `File(path).canRead()` checks and downgrades to an anonymous
 * transient file descriptor stream (`fd://`), explicitly excluding the played media from
 * VLC's playback history and resume database (`MedialibraryImpl` ignores non-file/non-media schemes).
 *
 * By resolving and passing the official [MediaStore] content URI when available, external players
 * can verify and track playback history, display filenames, and record resume progress.
 */
object MediaStoreUriResolver {

    private const val TAG = "MediaStoreUriResolver"
    const val DEFAULT_TIMEOUT_MS = 250L

    /**
     * Testing hook allowing unit tests to simulate MediaStore query and scanner responses.
     */
    internal var queryOverride: ((context: Context, file: File, mimeType: String?) -> Uri?)? = null

    /**
     * Resolves a local [file] to a [MediaStore] content URI.
     *
     * @param context Calling context
     * @param file Target local file
     * @param mimeType Optional MIME type
     * @param timeoutMs Timeout budget for on-demand MediaScanner scan (default 250ms)
     * @return [Uri] pointing to MediaStore item, or `null` if unindexed or non-media
     */
    fun resolve(
        context: Context,
        file: File?,
        mimeType: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Uri? {
        if (file == null || !file.exists() || !file.isFile) {
            return null
        }

        queryOverride?.let { override ->
            return override(context, file, mimeType)
        }

        val ext = file.extension.lowercase().trimStart('.')
        val isVideo = FileViewerRouter.isVideo(ext)
        val isAudio = FileViewerRouter.isAudio(ext)

        // Only resolve for video and audio files
        if (!isVideo && !isAudio) {
            return null
        }

        // Exclude app-private or internal cache paths that are not indexed by MediaStore
        val path = file.absolutePath
        val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
        if (isPrivateOrRootPath(context, path)) {
            return null
        }

        val resolvedMime = MediaIntentHelper.normalizeMediaMimeType(
            ext,
            mimeType ?: MimeTypeHelper.getOrFallback(ext)
        )

        val collectionUri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // 1. Direct query on primary media collection (checking both absolute and canonical paths)
        queryMediaStoreForId(context, collectionUri, path, canonicalPath)?.let { id ->
            return ContentUris.withAppendedId(collectionUri, id)
        }

        // 2. Fallback query on generic Files collection
        queryMediaStoreForId(context, MediaStore.Files.getContentUri("external"), path, canonicalPath)?.let { id ->
            return ContentUris.withAppendedId(collectionUri, id)
        }

        // 3. Fast on-demand MediaScanner scan with tight timeout
        val scannedUri = scanOnDemand(context, file, resolvedMime, timeoutMs)
        if (scannedUri != null) {
            return scannedUri
        }

        // 4. One final query in case scan completed without returning URI directly
        queryMediaStoreForId(context, collectionUri, path, canonicalPath)?.let { id ->
            return ContentUris.withAppendedId(collectionUri, id)
        }
        queryMediaStoreForId(context, MediaStore.Files.getContentUri("external"), path, canonicalPath)?.let { id ->
            return ContentUris.withAppendedId(collectionUri, id)
        }

        return null
    }

    private fun isPrivateOrRootPath(context: Context, path: String): Boolean {
        val appDataDir = runCatching { context.applicationInfo.dataDir }.getOrNull()
        if (appDataDir != null && path.startsWith(appDataDir)) return true

        val cachePath = runCatching { context.cacheDir.absolutePath }.getOrNull()
        val filesPath = runCatching { context.filesDir.absolutePath }.getOrNull()
        if (cachePath != null && path.startsWith(cachePath)) return true
        if (filesPath != null && path.startsWith(filesPath)) return true

        if (path.startsWith("/data/") || path.startsWith("/system/") ||
            path.startsWith("/vendor/") || path.startsWith("/apex/") ||
            path.startsWith("/root/")) {
            return true
        }

        return false
    }

    private fun queryMediaStoreForId(
        context: Context,
        collection: Uri,
        path: String,
        altPath: String? = null
    ): Long? {
        val cr = context.contentResolver ?: return null
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val hasAlt = !altPath.isNullOrBlank() && altPath != path
        val selection = if (hasAlt) {
            "${MediaStore.MediaColumns.DATA} = ? OR ${MediaStore.MediaColumns.DATA} = ?"
        } else {
            "${MediaStore.MediaColumns.DATA} = ?"
        }
        val selectionArgs = if (hasAlt) arrayOf(path, altPath!!) else arrayOf(path)

        return try {
            cr.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idCol >= 0) cursor.getLong(idCol) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed for $path on $collection: ${e.message}")
            null
        }
    }

    private fun scanOnDemand(
        context: Context,
        file: File,
        mimeType: String,
        timeoutMs: Long
    ): Uri? {
        if (timeoutMs <= 0L) return null

        var resultUri: Uri? = null
        val latch = CountDownLatch(1)

        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { _, uri ->
                resultUri = uri
                latch.countDown()
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "On-demand scanFile failed for ${file.absolutePath}: ${e.message}")
        }

        return resultUri
    }
}
