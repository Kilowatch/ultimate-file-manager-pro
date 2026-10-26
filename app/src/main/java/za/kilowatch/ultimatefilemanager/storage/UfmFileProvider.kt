package za.kilowatch.ultimatefilemanager.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import za.kilowatch.ultimatefilemanager.util.MimeTypeHelper
import java.io.File
import java.io.FileNotFoundException

/**
 * Custom [FileProvider] subclass that intercepts root-protected file requests.
 *
 * When external editors or viewers (such as QuickEdit Pro) request a ParcelFileDescriptor
 * for a root-partition file (e.g. `/data/adb/...`), [UfmFileProvider] serves a transparently
 * staged copy from app-private storage. An [ParcelFileDescriptor.OnCloseListener] is attached
 * to trigger automatic elevated write-back to the root filesystem whenever the external application
 * saves and closes the file descriptor.
 */
class UfmFileProvider : FileProvider() {

    companion object {
        private const val TAG = "UfmFileProvider"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context
        if (ctx == null) {
            return super.openFile(uri, mode) ?: throw FileNotFoundException("Provider context is null for $uri")
        }
        val uriPath = uri.path ?: ""

        // Case 1: Root path URI (content://.../root/data/adb/...)
        if (uriPath.startsWith("/root/")) {
            val originalPath = uriPath.removePrefix("/root")
            if (RootStagingManager.isRootFile(ctx, originalPath)) {
                Log.d(TAG, "Intercepting openFile for root path: $originalPath (mode=$mode)")
                val stagedFile = RootStagingManager.getOrStageFile(ctx, originalPath)
                if (stagedFile != null) {
                    return openStagedDescriptor(ctx, stagedFile, originalPath, mode)
                }
            }
        }

        // Case 2: Staged file in cache path (content://.../cache/root_staging/...)
        if (uriPath.contains("/root_staging/")) {
            val relativePath = if (uriPath.startsWith("/cache/")) uriPath.removePrefix("/cache/") else "root_staging/" + uriPath.substringAfter("/root_staging/")
            val stagedFile = File(ctx.cacheDir, relativePath)
            val originalPath = RootStagingManager.getOriginalPathForStagedPath(stagedFile.absolutePath)
            if (originalPath != null && stagedFile.exists()) {
                Log.d(TAG, "Serving staged cache file: ${stagedFile.absolutePath} for root: $originalPath (mode=$mode)")
                return openStagedDescriptor(ctx, stagedFile, originalPath, mode)
            }
        }

        // Case 3: Standard FileProvider handling with graceful fallback for root EACCES errors
        return try {
            super.openFile(uri, mode) ?: throw FileNotFoundException("FileProvider returned null descriptor for $uri")
        } catch (e: Exception) {
            val isPermissionError = e is SecurityException ||
                    e is FileNotFoundException ||
                    (e.message?.contains("EACCES", ignoreCase = true) == true) ||
                    (e.message?.contains("Permission denied", ignoreCase = true) == true)

            if (isPermissionError && uriPath.startsWith("/root/")) {
                val originalPath = uriPath.removePrefix("/root")
                if (RootShellWrapper.isAuthorized(ctx)) {
                    Log.w(TAG, "super.openFile failed with permission error; falling back to RootStagingManager for $originalPath")
                    val staged = RootStagingManager.getOrStageFile(ctx, originalPath)
                    if (staged != null) {
                        return openStagedDescriptor(ctx, staged, originalPath, mode)
                    }
                }
            }
            throw e
        }
    }

    private fun openStagedDescriptor(
        context: android.content.Context,
        stagedFile: File,
        originalPath: String,
        mode: String
    ): ParcelFileDescriptor {
        val modeBits = ParcelFileDescriptor.parseMode(mode)
        val mainHandler = Handler(Looper.getMainLooper())

        val onCloseListener = ParcelFileDescriptor.OnCloseListener { ioException ->
            if (ioException == null) {
                Log.d(TAG, "External application closed descriptor for $originalPath; syncing back to root")
            } else {
                Log.w(TAG, "External application closed descriptor with error for $originalPath: ${ioException.message}")
            }
            // Trigger elevated write-back if the file was modified
            RootStagingManager.syncBackToRootAsync(context, originalPath)
        }

        return ParcelFileDescriptor.open(stagedFile, modeBits, mainHandler, onCloseListener)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context
        val uriPath = uri.path ?: ""

        val defaultCols = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.DATA,
            "title"
        )
        val cols = projection ?: defaultCols

        val isRootPath = uriPath.startsWith("/root/")
        val isRootStaging = uriPath.contains("/root_staging/")

        var resolvedFile: File? = null
        var isRootFile = false
        var originalPath: String? = null

        if (ctx != null && isRootPath) {
            val rootOriginal = uriPath.removePrefix("/root")
            if (RootStagingManager.isRootFile(ctx, rootOriginal)) {
                isRootFile = true
                originalPath = rootOriginal
                resolvedFile = RootStagingManager.getOrStageFile(ctx, rootOriginal) ?: File(rootOriginal)
            }
        } else if (ctx != null && isRootStaging) {
            val relativePath = if (uriPath.startsWith("/cache/")) uriPath.removePrefix("/cache/") else "root_staging/" + uriPath.substringAfter("/root_staging/")
            val stagedFile = File(ctx.cacheDir, relativePath)
            val rootOriginal = RootStagingManager.getOriginalPathForStagedPath(stagedFile.absolutePath)
            if (rootOriginal != null) {
                isRootFile = true
                originalPath = rootOriginal
                resolvedFile = stagedFile
            }
        }

        if (resolvedFile == null && ctx != null) {
            resolvedFile = resolveFileFromUri(ctx, uriPath)
        }

        if (resolvedFile != null) {
            val cursor = MatrixCursor(cols, 1)
            val row = cursor.newRow()

            val fileName = when {
                isRootFile && originalPath != null -> File(originalPath).name
                else -> resolvedFile.name
            }
            val fileSize = when {
                isRootFile && originalPath != null && ctx != null ->
                    RootStagingManager.getStagedOrRootFileSize(ctx, originalPath)
                else -> resolvedFile.length()
            }
            val filePath = when {
                isRootFile && originalPath != null -> originalPath
                else -> resolvedFile.absolutePath
            }
            val title = fileName.substringBeforeLast('.').ifBlank { fileName }

            for (col in cols) {
                when (col) {
                    OpenableColumns.DISPLAY_NAME, "display_name", MediaStore.MediaColumns.DISPLAY_NAME ->
                        row.add(col, fileName)
                    OpenableColumns.SIZE, "size", MediaStore.MediaColumns.SIZE ->
                        row.add(col, fileSize)
                    "_data", MediaStore.MediaColumns.DATA ->
                        row.add(col, filePath)
                    "title", MediaStore.MediaColumns.TITLE ->
                        row.add(col, title)
                    "mime_type", MediaStore.MediaColumns.MIME_TYPE ->
                        row.add(col, getType(uri))
                    "date_modified", MediaStore.MediaColumns.DATE_MODIFIED ->
                        row.add(col, resolvedFile.lastModified() / 1000L)
                    else ->
                        row.add(col, null)
                }
            }
            return cursor
        }

        return try {
            super.query(uri, projection, selection, selectionArgs, sortOrder)
        } catch (e: Exception) {
            Log.w(TAG, "super.query failed for $uri: ${e.message}")
            val cursor = MatrixCursor(cols, 1)
            val row = cursor.newRow()
            val fileName = File(uriPath).name.ifBlank { "media" }
            val title = fileName.substringBeforeLast('.').ifBlank { fileName }
            for (col in cols) {
                when (col) {
                    OpenableColumns.DISPLAY_NAME, "display_name", MediaStore.MediaColumns.DISPLAY_NAME ->
                        row.add(col, fileName)
                    OpenableColumns.SIZE, "size", MediaStore.MediaColumns.SIZE ->
                        row.add(col, 0L)
                    "title", MediaStore.MediaColumns.TITLE ->
                        row.add(col, title)
                    else ->
                        row.add(col, null)
                }
            }
            cursor
        }
    }

    private fun resolveFileFromUri(context: android.content.Context, uriPath: String): File? {
        return try {
            when {
                uriPath.startsWith("/external/") -> {
                    val rel = uriPath.removePrefix("/external/")
                    File(Environment.getExternalStorageDirectory(), rel)
                }
                uriPath.startsWith("/root/") -> {
                    val rel = uriPath.removePrefix("/root/")
                    File("/$rel")
                }
                uriPath.startsWith("/cache/") -> {
                    val rel = uriPath.removePrefix("/cache/")
                    File(context.cacheDir, rel)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun getType(uri: Uri): String? {
        try {
            val superType = super.getType(uri)
            if (!superType.isNullOrEmpty() && superType != "application/octet-stream") {
                return superType
            }
        } catch (_: Exception) {}

        val path = uri.path ?: return "application/octet-stream"
        val name = File(path).name
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeHelper.getOrFallback(ext)
    }
}
