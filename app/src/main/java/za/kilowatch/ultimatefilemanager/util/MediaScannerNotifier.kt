package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import java.io.File

/**
 * Utility to notify Android's [MediaScannerConnection] / MediaStore indexer
 * when new or updated media files (images, videos, audio, documents) are saved,
 * copied, moved, or extracted to local storage from local, SMB/NAS network,
 * or online cloud storage providers.
 */
object MediaScannerNotifier {

    private const val TAG = "MediaScannerNotifier"

    /**
     * Scans a single local file path with [MediaScannerConnection].
     */
    fun scanFile(context: Context? = null, path: String?) {
        val targetContext = context ?: runCatching { za.kilowatch.ultimatefilemanager.UfmApplication.instance }.getOrNull()
        if (targetContext == null || path.isNullOrBlank()) return
        val file = File(path)
        scanFile(targetContext, file)
    }

    /**
     * Scans a single [File] if it exists and is a regular file (not a hidden/system file).
     */
    fun scanFile(context: Context? = null, file: File?) {
        val targetContext = context ?: runCatching { za.kilowatch.ultimatefilemanager.UfmApplication.instance }.getOrNull()
        if (targetContext == null || file == null || !file.exists() || file.isDirectory) return
        if (file.name.startsWith(".") || isUnderHiddenDirectory(file)) return

        try {
            MediaScannerConnection.scanFile(
                targetContext.applicationContext,
                arrayOf(file.absolutePath),
                null
            ) { scannedPath, uri ->
                Log.d(TAG, "Scanned $scannedPath -> $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning file ${file.absolutePath}: ${e.message}")
        }
    }

    /**
     * Scans a collection of file paths in batch via [MediaScannerConnection].
     */
    fun scanFiles(context: Context? = null, paths: Collection<String>?) {
        val targetContext = context ?: runCatching { za.kilowatch.ultimatefilemanager.UfmApplication.instance }.getOrNull()
        if (targetContext == null || paths.isNullOrEmpty()) return
        val validPaths = paths
            .filter { it.isNotBlank() }
            .map { File(it) }
            .filter { it.exists() && !it.isDirectory && !it.name.startsWith(".") && !isUnderHiddenDirectory(it) }
            .map { it.absolutePath }
            .toTypedArray()

        if (validPaths.isEmpty()) return

        try {
            MediaScannerConnection.scanFile(
                targetContext.applicationContext,
                validPaths,
                null
            ) { scannedPath, uri ->
                Log.d(TAG, "Batch scanned $scannedPath -> $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning batch of ${validPaths.size} files: ${e.message}")
        }
    }

    /**
     * Scans all non-hidden files inside a directory (recursively if requested).
     */
    fun scanDirectory(context: Context? = null, dir: File?, recursive: Boolean = true) {
        val targetContext = context ?: runCatching { za.kilowatch.ultimatefilemanager.UfmApplication.instance }.getOrNull()
        if (targetContext == null || dir == null || !dir.exists() || !dir.isDirectory) return
        val collectedFiles = mutableListOf<String>()

        fun collect(target: File) {
            if (target.name.startsWith(".") || File(target, ".nomedia").exists()) return
            val children = target.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    if (recursive) collect(child)
                } else if (!child.name.startsWith(".")) {
                    collectedFiles.add(child.absolutePath)
                }
            }
        }

        collect(dir)
        scanFiles(context, collectedFiles)
    }

    private fun isUnderHiddenDirectory(file: File): Boolean {
        var parent = file.parentFile
        while (parent != null) {
            if (parent.name.startsWith(".")) return true
            parent = parent.parentFile
        }
        return false
    }
}
