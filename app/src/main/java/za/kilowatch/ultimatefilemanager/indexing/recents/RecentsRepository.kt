package za.kilowatch.ultimatefilemanager.indexing.recents

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath
import java.io.File

/**
 * Progress details during a recent files refresh scan.
 */
data class ScanProgress(
    val scannedFolders: Int,
    val totalFolders: Int,
    val currentFolder: String,
    val estimatedRemainingSeconds: Int
)

/**
 * Coordinates fetching, caching, observing, and pruning of recently modified files
 * across local storage volumes (Internal, SD, USB) under both Full Access and Limited Access.
 */
class RecentsRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RecentsRepository"
        const val MAX_RECENTS_LIMIT = 200

        @Volatile
        private var instance: RecentsRepository? = null

        fun getInstance(context: Context): RecentsRepository {
            return instance ?: synchronized(this) {
                instance ?: RecentsRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = UfmIndexingDatabase.getInstance(context)
    private val dao = database.recentFileDao()

    @Volatile
    private var lastObservedTier: AccessTier? = null

    /**
     * Observes the top recent files formatted into domain items.
     * Filters out stale or non-existent files so the UI is always accurate.
     */
    fun observeRecentFiles(limit: Int = MAX_RECENTS_LIMIT): Flow<List<RecentFileItem>> {
        return dao.observeRecentFiles(limit).map { entities ->
            entities.filter { entity ->
                if (entity.source == RecentFileSource.FULL_FS.name) {
                    val f = File(entity.uriOrPath)
                    f.exists() && f.isFile
                } else {
                    true
                }
            }.map { it.toItem() }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Reconciles current access tier and triggers refresh if needed.
     */
    suspend fun refresh(
        forceFullScan: Boolean = false,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<RecentFileItem> = withContext(Dispatchers.IO) {
        if (!RecentsSettingsManager.isEnabled(context)) {
            return@withContext emptyList()
        }

        val currentTier = AccessTierDetector.currentTier(context)
        val previousTier = lastObservedTier
        lastObservedTier = currentTier

        if (previousTier != null && previousTier != currentTier) {
            handleTierTransition(previousTier, currentTier)
        }

        // Purge non-existent/deleted/moved/renamed files from the database
        purgeNonExistentFiles()

        val items = when (currentTier) {
            AccessTier.FULL -> loadFullAccessRecents(forceFullScan, onProgress)
            AccessTier.LIMITED -> loadLimitedAccessRecents()
        }

        if (items.isNotEmpty()) {
            dao.upsertAll(items.map { it.toEntity() })
            dao.pruneLru(MAX_RECENTS_LIMIT)
        }

        dao.getRecentFiles(MAX_RECENTS_LIMIT)
            .filter { entity ->
                if (entity.source == RecentFileSource.FULL_FS.name) {
                    val f = File(entity.uriOrPath)
                    f.exists() && f.isFile
                } else {
                    true
                }
            }
            .map { it.toItem() }
    }

    /**
     * Handles transition between Full and Limited access tiers.
     */
    private suspend fun handleTierTransition(from: AccessTier, to: AccessTier) {
        GoRoLog.i(TAG, "Tier transition: $from -> $to")
        if (from == AccessTier.FULL && to == AccessTier.LIMITED) {
            // Downgrade: Purge FULL_FS entries whose paths are not reachable via persisted SAF trees
            val persistedUris = getPersistedSafTrees().map { it.first.toString() }.toSet()
            if (persistedUris.isEmpty()) {
                dao.clearAll()
            } else {
                dao.deleteBySource(RecentFileSource.FULL_FS.name)
            }
        } else if (from == AccessTier.LIMITED && to == AccessTier.FULL) {
            // Upgrade: Clear old SAF entries and trigger clean full scan
            dao.deleteBySource(RecentFileSource.SAF.name)
        }
    }

    /**
     * Loads recent files across all mounted local volumes under Full Access.
     */
    private suspend fun loadFullAccessRecents(
        forceFullScan: Boolean,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<RecentFileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RecentFileItem>()
        val seenPaths = mutableSetOf<String>()

        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volumes = sm?.storageVolumes ?: emptyList()

        // 1. Query MediaStore (instant device-wide query)
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
            )

            // Look back up to 30 days, or fetch top entries
            val sinceSeconds = (System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)) / 1000L
            val selection = "${MediaStore.MediaColumns.DATE_MODIFIED} > ?"
            val selectionArgs = arrayOf(sinceSeconds.toString())
            val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT $MAX_RECENTS_LIMIT"

            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null ?: continue
                    if (isExcludedPath(path)) continue

                    val file = File(path)
                    if (!file.exists() || !file.isFile) continue

                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: file.name else file.name
                    val modSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val lastModified = if (modSec > 0) modSec * 1000L else file.lastModified()
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else file.length()
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null

                    val (volId, volLabel) = resolveVolumeInfo(path, volumes)

                    results.add(
                        RecentFileItem(
                            displayName = name,
                            uriOrPath = path,
                            lastModified = lastModified,
                            volumeLabel = volLabel,
                            volumeId = volId,
                            source = RecentFileSource.FULL_FS,
                            sizeBytes = size,
                            mimeType = mime
                        )
                    )
                    seenPaths.add(path)
                }
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Error querying MediaStore for recents: ${e.message}")
        }

        // 2. Hot folders deep scan (recursive up to 2 levels, ~10ms):
        // Instantly catches brand-new downloads, camera photos, or documents created in the last 7 days.
        // Recursive so it also finds files in subdirectories like Download/Telegram/, DCIM/Camera/, etc.
        val hotFolderNames = listOf("Download", "Downloads", "Documents", "DCIM", "Pictures", "Movies", "Music")
        val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000L)
        for (vol in volumes) {
            val rootPath = vol.safeDirectoryPath ?: continue
            val (volId, volLabel) = resolveVolumeInfo(rootPath, volumes)
            for (sub in hotFolderNames) {
                val hotDir = File(rootPath, sub)
                if (hotDir.exists() && hotDir.canRead()) {
                    try {
                        hotDir.walkTopDown()
                            .maxDepth(2)
                            .onEnter { d -> d == hotDir || (!d.name.startsWith(".") && !d.name.equals("Android", ignoreCase = true)) }
                            .filter { it.isFile }
                            .forEach { file ->
                                if (file.lastModified() >= sevenDaysAgo && !seenPaths.contains(file.absolutePath) && !isExcludedPath(file.absolutePath)) {
                                    results.add(
                                        RecentFileItem(
                                            displayName = file.name,
                                            uriOrPath = file.absolutePath,
                                            lastModified = file.lastModified(),
                                            volumeLabel = volLabel,
                                            volumeId = volId,
                                            source = RecentFileSource.FULL_FS,
                                            sizeBytes = file.length(),
                                            mimeType = null
                                        )
                                    )
                                    seenPaths.add(file.absolutePath)
                                }
                            }
                    } catch (_: Exception) {}
                }
            }
        }
        GoRoLog.i(TAG, "Hot folders scan found ${results.size - seenPaths.size + results.size} files (total results so far: ${results.size})")

        // 3. Load from SQLite Storage Index database (~5ms)
        val indexedFiles = try {
            database.fileIndexDao().getTopRecentlyModifiedFiles(MAX_RECENTS_LIMIT * 2)
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to load recents from file index database: ${e.message}")
            emptyList()
        }

        if (indexedFiles.isNotEmpty()) {
            GoRoLog.i(TAG, "Loaded recents from index database (${indexedFiles.size} candidates)")
            for (fi in indexedFiles) {
                if (isExcludedPath(fi.path) || seenPaths.contains(fi.path)) continue
                val file = File(fi.path)
                if (!file.exists() || !file.isFile) continue

                val (volId, volLabel) = resolveVolumeInfo(fi.path, volumes)
                results.add(
                    RecentFileItem(
                        displayName = fi.filename,
                        uriOrPath = fi.path,
                        lastModified = fi.lastModified,
                        volumeLabel = volLabel,
                        volumeId = volId,
                        source = RecentFileSource.FULL_FS,
                        sizeBytes = fi.size,
                        mimeType = fi.mimeType.ifEmpty { null }
                    )
                )
                seenPaths.add(fi.path)
                if (results.size >= MAX_RECENTS_LIMIT) break
            }
        }

        // Flush fast results (MediaStore + hot folders + index) to DB immediately
        // so the Room Flow observer updates the UI before the disk walk starts.
        if (results.isNotEmpty()) {
            val fastItems = results.sortedByDescending { it.lastModified }.take(MAX_RECENTS_LIMIT)
            dao.upsertAll(fastItems.map { it.toEntity() })
            dao.pruneLru(MAX_RECENTS_LIMIT)
            GoRoLog.i(TAG, "Flushed ${fastItems.size} fast results to DB (UI should update immediately)")
            onProgress?.invoke(ScanProgress(1, 1, "Fast scan complete", 0))
        }


        // 4. Full disk walk: Discover and scan user folders across all volumes
        // This supplements the fast results with any files missed by MediaStore, hot folders, and the index.
        // SKIP when all storages are fully indexed (fast sources are sufficient) unless force scan is requested.
        val allIndexed = try {
            za.kilowatch.ultimatefilemanager.UfmApplication.indexingRepository.areAllLocalStoragesFullyIndexed()
        } catch (_: Exception) { false }

        if (allIndexed && !forceFullScan) {
            GoRoLog.i(TAG, "All storages fully indexed — skipping disk walk, returning ${results.size} fast results")
            results.sortByDescending { it.lastModified }
            return@withContext results.take(MAX_RECENTS_LIMIT)
        }

        GoRoLog.i(TAG, "Starting full disk walk (indexed=$allIndexed, forceFullScan=$forceFullScan)")
        val allScanDirs = mutableListOf<Pair<File, Pair<String, String>>>()
        for (vol in volumes) {
            val rootPath = vol.safeDirectoryPath ?: continue
            val rootDir = File(rootPath)
            if (!rootDir.exists() || !rootDir.canRead()) continue


            val volInfo = resolveVolumeInfo(rootPath, volumes)
            allScanDirs.add(Pair(rootDir, volInfo))
            rootDir.listFiles()?.forEach { child ->
                if (child.isDirectory && !isExcludedName(child.name) && !isExcludedPath(child.absolutePath)) {
                    allScanDirs.add(Pair(child, volInfo))
                }
            }
        }

        val totalFolders = allScanDirs.size
        val scanStartTime = System.currentTimeMillis()
        val cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)

        allScanDirs.forEachIndexed { idx, (dir, volInfo) ->
            val elapsed = System.currentTimeMillis() - scanStartTime
            val avgPerFolder = if (idx > 0) elapsed / idx else 150L
            val remaining = totalFolders - idx
            val estSec = ((remaining * avgPerFolder + 999) / 1000).toInt().coerceAtLeast(1)
            onProgress?.invoke(ScanProgress(idx + 1, totalFolders, dir.name, if (idx == 0) 0 else estSec))

            if (dir.exists() && dir.canRead()) {
                try {
                    val isVolumeRoot = volumes.any { it.safeDirectoryPath == dir.absolutePath }
                    val filesSeq = if (isVolumeRoot) {
                        dir.listFiles()?.filter { it.isFile }?.asSequence() ?: emptySequence()
                    } else {
                        dir.walkTopDown()
                            .onEnter { d -> d == dir || (!isExcludedName(d.name) && !isExcludedPath(d.absolutePath)) }
                            .maxDepth(3)
                            .filter { it.isFile }
                    }

                    for (file in filesSeq) {
                        if (file.lastModified() < cutoffTime) continue
                        val absPath = file.absolutePath
                        if (!seenPaths.contains(absPath) && !isExcludedPath(absPath)) {
                            results.add(
                                RecentFileItem(
                                    displayName = file.name,
                                    uriOrPath = absPath,
                                    lastModified = file.lastModified(),
                                    volumeLabel = volInfo.second,
                                    volumeId = volInfo.first,
                                    source = RecentFileSource.FULL_FS,
                                    sizeBytes = file.length(),
                                    mimeType = null
                                )
                            )
                            seenPaths.add(absPath)
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        onProgress?.invoke(ScanProgress(totalFolders, totalFolders, "", 0))

        results.sortByDescending { it.lastModified }
        results.take(MAX_RECENTS_LIMIT)
    }

    /**
     * Loads recent files from persisted SAF trees under Limited Access.
     */
    private suspend fun loadLimitedAccessRecents(): List<RecentFileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RecentFileItem>()
        val trees = getPersistedSafTrees()
        if (trees.isEmpty()) return@withContext emptyList()

        for ((treeUri, displayLabel) in trees) {
            try {
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                walkSafTree(treeUri, docId, displayLabel, results, maxDepth = 4)
            } catch (e: Exception) {
                GoRoLog.w(TAG, "Error walking SAF tree $treeUri: ${e.message}")
            }
        }

        results.sortByDescending { it.lastModified }
        results.take(MAX_RECENTS_LIMIT)
    }

    /**
     * Recursively walks a SAF document tree to collect recently modified files.
     */
    private fun walkSafTree(
        treeUri: Uri,
        parentDocId: String,
        volumeLabel: String,
        outList: MutableList<RecentFileItem>,
        maxDepth: Int,
        currentDepth: Int = 0
    ) {
        if (currentDepth > maxDepth) return

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol   = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modCol  = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val docId = if (idCol >= 0) cursor.getString(idCol) else null ?: continue
                val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "Unknown" else "Unknown"
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                if (name.startsWith(".") || isExcludedName(name)) continue

                if (isDir) {
                    walkSafTree(treeUri, docId, volumeLabel, outList, maxDepth, currentDepth + 1)
                } else {
                    val mtime = if (modCol >= 0) cursor.getLong(modCol) else 0L
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val fileDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    outList.add(
                        RecentFileItem(
                            displayName = name,
                            uriOrPath = fileDocUri.toString(),
                            lastModified = mtime,
                            volumeLabel = volumeLabel,
                            volumeId = treeUri.toString(),
                            source = RecentFileSource.SAF,
                            sizeBytes = size,
                            mimeType = mime
                        )
                    )
                }
            }
        }
    }

    /**
     * Resolves human-readable volume description and ID for a file path.
     */
    private fun resolveVolumeInfo(path: String, volumes: List<android.os.storage.StorageVolume>): Pair<String, String> {
        for (vol in volumes) {
            val dirPath = vol.safeDirectoryPath ?: continue
            if (path.startsWith(dirPath)) {
                val volId = vol.uuid ?: "internal"
                val desc = vol.getDescription(context)
                val label = when {
                    vol.isPrimary -> context.getString(za.kilowatch.ultimatefilemanager.R.string.internal_storage)
                    vol.isRemovable -> desc.ifEmpty { "SD Card" }
                    else -> desc.ifEmpty { "Storage" }
                }
                return Pair(volId, label)
            }
        }
        return Pair("internal", context.getString(za.kilowatch.ultimatefilemanager.R.string.internal_storage))
    }

    /**
     * Excludes system sandboxes, cloud-sync, and hidden folders.
     */
    private fun isExcludedPath(path: String): Boolean {
        if (path.contains("/Android/data") || path.contains("/Android/obb")) return true
        if (path.contains("/Dropbox") || path.contains("/OneDrive") || path.contains("/Google Drive")) return true
        if (path.contains("/.thumbnails") || path.contains("/.cache") || path.contains("/.nomedia")) return true
        val segments = path.split("/")
        return segments.any { it.startsWith(".") && it.length > 1 }
    }

    private fun isExcludedName(name: String): Boolean {
        return name.startsWith(".") || name.equals("Android", ignoreCase = true)
    }

    private fun getPersistedSafTrees(): List<Pair<Uri, String>> {
        val list = mutableListOf<Pair<Uri, String>>()
        try {
            val perms = context.contentResolver.persistedUriPermissions
            for (perm in perms) {
                if (!perm.isReadPermission) continue
                val uri = perm.uri
                val doc = DocumentFile.fromTreeUri(context, uri)
                val name = doc?.name ?: "Storage Folder"
                list.add(Pair(uri, name))
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Error querying persistedUriPermissions: ${e.message}")
        }
        return list
    }

    /**
     * Handles volume ejection or unmounting.
     */
    suspend fun onVolumeUnmounted(volumeId: String) = withContext(Dispatchers.IO) {
        dao.deleteByVolumeId(volumeId)
    }

    /**
     * Upserts an individual file when modified or created within UFM.
     */
    suspend fun recordFile(file: File) = withContext(Dispatchers.IO) {
        if (!RecentsSettingsManager.isEnabled(context)) return@withContext
        if (isExcludedPath(file.absolutePath)) return@withContext

        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volumes = sm?.storageVolumes ?: emptyList()
        val (volId, volLabel) = resolveVolumeInfo(file.absolutePath, volumes)

        val entity = RecentFileEntity(
            uriOrPath = file.absolutePath,
            displayName = file.name,
            lastModified = file.lastModified(),
            volumeLabel = volLabel,
            volumeId = volId,
            source = RecentFileSource.FULL_FS.name,
            sizeBytes = file.length()
        )
        dao.upsert(entity)
        dao.pruneLru(MAX_RECENTS_LIMIT)
    }

    /**
     * Purges database records for any files that no longer exist on disk (renamed, moved, or deleted).
     */
    suspend fun purgeNonExistentFiles() = withContext(Dispatchers.IO) {
        val current = dao.getRecentFiles(MAX_RECENTS_LIMIT)
        val deadPaths = mutableListOf<String>()
        for (entity in current) {
            if (entity.source == RecentFileSource.FULL_FS.name) {
                val f = File(entity.uriOrPath)
                if (!f.exists() || !f.isFile) {
                    deadPaths.add(entity.uriOrPath)
                }
            }
        }
        for (path in deadPaths) {
            dao.deleteByUriOrPath(path)
        }
    }

    /**
     * Removes an individual path from recents cache.
     */
    suspend fun removePath(path: String) = withContext(Dispatchers.IO) {
        dao.deleteByUriOrPath(path)
    }

    /**
     * Removes multiple paths from recents cache in batch.
     */
    suspend fun removePaths(paths: Collection<String>) = withContext(Dispatchers.IO) {
        for (path in paths) {
            dao.deleteByUriOrPath(path)
        }
    }

    /**
     * Handles file rename by removing the old path record and inserting the new file.
     */
    suspend fun onFileRenamed(oldPath: String, newFile: File) = withContext(Dispatchers.IO) {
        dao.deleteByUriOrPath(oldPath)
        if (newFile.exists() && newFile.isFile) {
            recordFile(newFile)
        }
    }

    /**
     * Handles file move by removing the old path record and inserting the new destination file.
     */
    suspend fun onFileMoved(oldPath: String, newFile: File) = withContext(Dispatchers.IO) {
        dao.deleteByUriOrPath(oldPath)
        if (newFile.exists() && newFile.isFile) {
            recordFile(newFile)
        }
    }

    /**
     * Clears all recents history.
     */
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    private fun RecentFileEntity.toItem() = RecentFileItem(
        displayName = displayName,
        uriOrPath = uriOrPath,
        lastModified = lastModified,
        volumeLabel = volumeLabel,
        volumeId = volumeId,
        source = try { RecentFileSource.valueOf(source) } catch (_: Exception) { RecentFileSource.FULL_FS },
        sizeBytes = sizeBytes,
        mimeType = mimeType
    )

    private fun RecentFileItem.toEntity() = RecentFileEntity(
        uriOrPath = uriOrPath,
        displayName = displayName,
        lastModified = lastModified,
        volumeLabel = volumeLabel,
        volumeId = volumeId,
        source = source.name,
        sizeBytes = sizeBytes,
        mimeType = mimeType
    )
}
