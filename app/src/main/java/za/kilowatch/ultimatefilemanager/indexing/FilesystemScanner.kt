package za.kilowatch.ultimatefilemanager.indexing

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.MediaStore
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Filesystem Scanner - Provides two scanning strategies:
 *
 * 1. [scanDirectory]          – Full recursive walk used ONCE for a storage's first-time index.
 * 2. [scanMediaStoreSince]    – Incremental MediaStore query for files changed after a timestamp.
 *                              Used at startup (to catch offline changes) and by the WorkManager job.
 * 3. [scanFolder]             – Shallow non-recursive scan of a single folder (live monitoring).
 * 4. [scanFile]               – Single-file metadata extraction (live monitoring).
 *
 * Full-storage scans ([scanInternalStorage], [scanExternalStorage], etc.) have been intentionally
 * removed. After the first full index, all updates are incremental.
 */
class FilesystemScanner(private val context: Context) {

    private val TAG = "FilesystemScanner"
    private val metadataExtractor = MetadataExtractor(context)

    // ============ FIRST-TIME FULL SCAN ============

    /**
     * Recursively scan a directory and emit a [FileIndex] for every new or changed file/folder.
     * Before extracting metadata the scanner checks the database — if the path is already indexed
     * and [FileIndex.lastScannedAt] >= [File.lastModified], the entry is skipped.
     *
     * This method is called ONCE per storage (guarded by [IndexingRepository.isStorageFullyIndexed]).
     */
    suspend fun scanDirectory(
        dir: File,
        storageId: String,
        storageType: String,
        emit: suspend (FileIndex) -> Unit,
        maxDepth: Int = -1,
        excludePaths: List<String> = DEFAULT_EXCLUDE_PATHS,
        currentDepth: Int = 0
    ) {
        if (maxDepth >= 0 && currentDepth >= maxDepth) return
        if (!dir.canRead()) return

        try {
            val dao = UfmIndexingDatabase.getInstance(context).fileIndexDao()
            dir.listFiles()?.forEach { file ->
                if (shouldExclude(file, excludePaths)) return@forEach

                try {
                    val existing = dao.getByPath(file.absolutePath)
                    val needsHash = !file.isDirectory && file.length() > 0L && (existing == null || existing.hash.isEmpty())
                    if (existing == null || existing.lastScannedAt < file.lastModified() || needsHash) {
                        val fileIndex = metadataExtractor.extractMetadata(file, storageId, storageType)
                        emit(fileIndex)
                    }
                } catch (inner: Exception) {
                    // DB check failed — extract metadata anyway so we don't silently miss the file
                    try {
                        emit(metadataExtractor.extractMetadata(file, storageId, storageType))
                    } catch (_: Exception) { }
                }

                if (file.isDirectory) {
                    scanDirectory(file, storageId, storageType, emit, maxDepth, excludePaths, currentDepth + 1)
                }
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error scanning directory ${dir.absolutePath}: ${e.message}")
        }
    }

    // ============ INCREMENTAL MEDIASTORE SCAN ============

    /**
     * Query the MediaStore for image, video, and audio files whose [MediaStore.MediaColumns.DATE_MODIFIED]
     * is after [sinceMillis].  Used for startup reconciliation and periodic WorkManager jobs.
     *
     * Note: MediaStore stores DATE_MODIFIED in seconds, so [sinceMillis] is divided by 1000.
     */
    fun scanMediaStoreSince(storageId: String, storageType: String, sinceMillis: Long, storagePath: String): Flow<FileIndex> = flow {
        val cr = context.contentResolver
        // Normalize the volume root so path.startsWith() works reliably
        val volumePrefix = if (storagePath.endsWith("/")) storagePath else "$storagePath/"

        val mediaSources = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            // Include the generic Files table so non-media files (docs, APKs) are also detected
            MediaStore.Files.getContentUri("external")
        )

        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection = "(${MediaStore.MediaColumns.DATE_MODIFIED} > ? OR ${MediaStore.MediaColumns.DATE_ADDED} > ?)"
        val sinceSeconds = (sinceMillis / 1000).toString()
        val selectionArgs = arrayOf(sinceSeconds, sinceSeconds)

        mediaSources.forEach { uri: Uri ->
            var cursor: Cursor? = null
            try {
                cursor = cr.query(uri, projection, selection, selectionArgs, null)
                cursor?.use { c ->
                    val dataIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    while (c.moveToNext()) {
                        try {
                            val rawPath = c.getString(dataIdx) ?: continue
                            // FUSE paths are case-insensitive. Canonicalize to avoid duplication if external apps write to "download" instead of "Download".
                            val path = try { File(rawPath).canonicalPath } catch (e: Exception) { rawPath }
                            // ── Volume filter ──────────────────────────────────────────────────
                            // Only index files that physically live on this storage volume.
                            // Without this check, a scan for "sdcard_7DE2-1219" would pick up
                            // internal storage files and store them under the wrong storageId,
                            // causing deletion reconcile to false-delete them next startup.
                            if (!path.startsWith(volumePrefix)) continue
                            // ──────────────────────────────────────────────────────────────────
                            val file = File(path)
                            if (!file.exists()) continue
                            if (shouldExclude(file, DEFAULT_EXCLUDE_PATHS)) continue
                            emit(metadataExtractor.extractMetadata(file, storageId, storageType))
                        } catch (_: Exception) { /* skip malformed rows */ }
                    }
                }
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Error querying MediaStore since $uri: ${e.message}")
            } finally {
                cursor?.close()
            }
        }
    }

    // ============ INCREMENTAL DIRECTORY-ONLY SCAN ============

    /**
     * Walk the filesystem looking for **directories** that are newer than [sinceMillis].
     *
     * Android's MediaStore does not index directories, so this supplemental scan ensures that
     * newly created folders are picked up at startup reconciliation time.
     *
     * The walk is bounded efficiently: a directory is only recursed into when its own
     * [File.lastModified] timestamp is after [sinceMillis], meaning an entire untouched subtree
     * is skipped in O(1). This makes the scan extremely fast after a short app-close period.
     */
    fun scanNewDirectoriesSince(
        root: File,
        storageId: String,
        storageType: String,
        sinceMillis: Long
    ): Flow<FileIndex> = flow {
        scanDirsRecursive(root, storageId, storageType, sinceMillis, this)
    }

    private suspend fun scanDirsRecursive(
        dir: File,
        storageId: String,
        storageType: String,
        sinceMillis: Long,
        collector: kotlinx.coroutines.flow.FlowCollector<FileIndex>
    ) {
        if (!dir.canRead()) return
        try {
            dir.listFiles()?.forEach { file ->
                if (!file.isDirectory) return@forEach
                if (shouldExclude(file, DEFAULT_EXCLUDE_PATHS)) return@forEach

                // Only process directories that were created or last touched after sinceMillis.
                // A directory's lastModified changes when its direct children change, so this
                // ensures we descend into any folder that had a new child added.
                if (file.lastModified() > sinceMillis) {
                    val dao = UfmIndexingDatabase.getInstance(context).fileIndexDao()
                    val existingDir = try { dao.getByPath(file.absolutePath) } catch (_: Exception) { null }
                    val isNewDir = existingDir == null

                    // Index the directory entry itself if it's new or changed
                    if (isNewDir || existingDir!!.lastScannedAt < file.lastModified()) {
                        try {
                            collector.emit(metadataExtractor.extractMetadata(file, storageId, storageType))
                        } catch (_: Exception) { }
                    }

                    // Scan files in this directory because it was modified (new child or update).
                    // This ensures files with old timestamps (e.g. copied from another device)
                    // are indexed even if they were added while the app was closed.
                    val existingPaths = if (!isNewDir) {
                        try {
                            dao.getIndexedPathsInFolder(file.absolutePath).toSet()
                        } catch (_: Exception) { emptySet() }
                    } else {
                        emptySet()
                    }

                    file.listFiles()?.forEach { child ->
                        if (child.isFile && !shouldExclude(child, DEFAULT_EXCLUDE_PATHS)) {
                            // If new dir, index everything. If existing dir, check if file is new to us.
                            if (isNewDir || !existingPaths.contains(child.absolutePath)) {
                                try {
                                    collector.emit(metadataExtractor.extractMetadata(child, storageId, storageType))
                                } catch (_: Exception) { }
                            }
                        }
                    }

                    // Recurse — there may be new subdirectories inside
                    scanDirsRecursive(file, storageId, storageType, sinceMillis, collector)
                }
                // If lastModified <= sinceMillis, skip the entire subtree
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error scanning directories under ${dir.absolutePath}: ${e.message}")
        }
    }



    /**
     * Shallow (non-recursive) scan of a single folder. Used by [MediaStoreChangeObserver] when a
     * directory create/change is detected, and by [FileIndexingService.indexFolder].
     */
    fun scanFolder(
        folderPath: String,
        storageId: String,
        storageType: String
    ): Flow<FileIndex> = flow {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return@flow

        try {
            val dao = UfmIndexingDatabase.getInstance(context).fileIndexDao()
            folder.listFiles()?.forEach { file ->
                if (shouldExclude(file, DEFAULT_EXCLUDE_PATHS)) return@forEach
                try {
                    val existing = dao.getByPath(file.absolutePath)
                    val needsHash = !file.isDirectory && file.length() > 0L && (existing == null || existing.hash.isEmpty())
                    if (existing == null || existing.lastScannedAt < file.lastModified() || needsHash) {
                        emit(metadataExtractor.extractMetadata(file, storageId, storageType))
                    }
                } catch (e: Exception) {
                    try {
                        emit(metadataExtractor.extractMetadata(file, storageId, storageType))
                    } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error scanning folder $folderPath: ${e.message}")
        }
    }

    /**
     * Scan (or re-scan) a single file. Returns null if the file doesn't exist or is excluded.
     */
    suspend fun scanFile(
        file: File,
        storageId: String,
        storageType: String
    ): FileIndex? {
        return if (file.exists() && !shouldExclude(file, DEFAULT_EXCLUDE_PATHS)) {
            metadataExtractor.extractMetadata(file, storageId, storageType)
        } else {
            null
        }
    }

    // ============ HELPERS ============

    private fun shouldExclude(file: File, excludePaths: List<String>): Boolean {
        val path = file.absolutePath.lowercase()
        if (path.endsWith(".ufm_tmp")) return true
        return excludePaths.any { path.contains(it) }
    }

    companion object {
        val DEFAULT_EXCLUDE_PATHS = emptyList<String>()
    }
}
