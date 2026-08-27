package za.kilowatch.ultimatefilemanager.indexing

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File

/**
 * Indexing Repository — public API facade for the entire indexing system.
 *
 * ## Indexing lifecycle (enforced here)
 *
 *  1. **First open** — [isStorageFullyIndexed] returns false → [indexingService.startFirstTimeIndex]
 *     is called.  When it finishes it marks the storage as fully indexed and saves [lastIndexedAt].
 *
 *  2. **Subsequent opens** — [isStorageFullyIndexed] returns true → only
 *     [incrementalIndexFromMediaStore] is run with the saved [lastIndexedAt] timestamp to reconcile
 *     any changes that occurred while the app was closed.
 *
 *  3. **While app is open** — [MediaStoreChangeObserver] is registered and calls back into
 *     [indexFile], [indexFolder], and [handleFileDeleted] for every real-time change.
 *
 * A full directory scan is **never** triggered after [isStorageFullyIndexed] returns true.
 */
class IndexingRepository(
    private val context: Context,
    private val database: UfmIndexingDatabase = UfmIndexingDatabase.getInstance(context),
    private val indexingService: FileIndexingService = FileIndexingService.getInstance(context),
    private val searchEngine: FileSearchEngine = FileSearchEngine.getInstance(context)
) {

    private val TAG = "IndexingRepository"
    private val dao = database.fileIndexDao()

    // Single global MediaStore observer (lazy — created on first use)
    private val mediaStoreChangeObserver by lazy { MediaStoreChangeObserver(context, this) }

    // ============ INITIALIZATION ============

    /**
     * Boot the indexing system.
     *
     * For each storage we check whether it has ever been fully indexed:
     *  - NO  → trigger first-time full scan (async, shows progress)
     *  - YES → run a lightweight incremental reconciliation for offline changes, then return
     *
     * Then the [MediaStoreChangeObserver] is registered for live change detection, and the periodic
     * WorkManager job is (re-)scheduled.
     */
    fun initialize() {
        GoRoLog.i(TAG, "Initializing indexing system")

        // Discover every mounted local volume and reconcile each one individually
        val volumes = enumerateLocalVolumes()
        volumes.forEach { (storageId, storagePath, storageType) ->
            reconcileOnStartup(storageId, storageType, storagePath)
        }

        // Register the single global MediaStore observer — it already covers all volumes because
        // MediaStore.Files.getContentUri("external") is device-wide
        startMonitoring()

        // Schedule a periodic WorkManager job for each volume
        volumes.forEach { (storageId, storagePath, storageType) ->
            try {
                IndexingWorker.schedulePeriodicIndex(context, storageId, storageType, storagePath)
            } catch (e: Exception) {
                GoRoLog.w(TAG, "Failed to schedule periodic indexing for $storageId: ${e.message}")
            }
        }
    }

    /**
     * Enumerate all currently mounted local storage volumes.
     *
     * Returns a list of (storageId, storagePath, storageType) triples:
     *  - Internal primary storage is always "internal" → /storage/emulated/0
     *  - Removable volumes (SD card, USB OTG) are "sdcard_<UUID>" → /storage/<UUID>
     */
    private fun enumerateLocalVolumes(): List<Triple<String, String, String>> {
        val volumes = mutableListOf<Triple<String, String, String>>()

        // Primary internal storage — always present
        volumes.add(Triple("internal", "/storage/emulated/0", "internal"))

        // Removable volumes (SD cards, USB OTG)
        try {
            context.getExternalFilesDirs(null).forEach { appDir ->
                if (appDir == null || !appDir.exists()) return@forEach

                // Derive the volume root by stripping the app-specific suffix (/Android/data/<pkg>/files)
                val volumeRoot = appDir.absolutePath.substringBefore("/Android/")
                if (volumeRoot == "/storage/emulated/0") return@forEach  // Already added above

                val volumeId = volumeRoot.removePrefix("/storage/").replace("/", "_")
                val storageId = "sdcard_$volumeId"
                volumes.add(Triple(storageId, volumeRoot, "sdcard"))
                GoRoLog.d(TAG, "Discovered external volume: $storageId at $volumeRoot")
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to enumerate external volumes: ${e.message}")
        }

        return volumes
    }

    /**
     * Reconcile a single storage at startup:
     *  - First time → start the one-time full index asynchronously
     *  - Already indexed → run an incremental reconcile since the last run
     */
    private fun reconcileOnStartup(storageId: String, storageType: String, storagePath: String) {
        if (!isStorageFullyIndexed(storageId)) {
            GoRoLog.i(TAG, "Storage not yet indexed — skipping startup reconcile: $storageId")
            return
        } else {
            // Incremental reconcile — catch anything that changed while the app was closed
            CoroutineScope(Dispatchers.IO).launch {
                // ── DB-wipe detection ────────────────────────────────────────────────────
                // If SharedPrefs say "fully indexed" but the DB is empty, the database was
                // wiped (e.g. schema migration). Reset lastIndexedAt to 0 so the MediaStore
                // incremental scan behaves like a full scan and re-indexes everything.
                val dbCount = try { dao.getFileCountByStorage(storageId) } catch (_: Exception) { 1L }
                val lastIndexedAt = if (dbCount == 0L) {
                    GoRoLog.w(TAG, "DB empty but storage marked as fully indexed for $storageId — DB was wiped, resetting lastIndexedAt")
                    clearLastIndexedAt(storageId)
                    0L
                } else {
                    getLastIndexedAt(storageId)
                }
                GoRoLog.i(TAG, "Storage already indexed — reconciling since $lastIndexedAt for $storageId")

                try {
                    // 1. MediaStore incremental scan — picks up new/changed FILES
                    val mediaIndexed = indexingService.incrementalIndexFromMediaStore(storageId, lastIndexedAt, storageType, storagePath)
                    GoRoLog.d(TAG, "Startup media reconcile: $mediaIndexed entries for $storageId")
                } catch (e: Exception) {
                    GoRoLog.w(TAG, "Startup media reconcile failed for $storageId: ${e.message}")
                }

                try {
                    // 2. Directory-only filesystem walk — picks up new FOLDERS (MediaStore ignores dirs)
                    val lastIndexedAt2 = getLastIndexedAt(storageId)
                    val dirsIndexed = indexingService.reconcileNewDirectoriesSince(storageId, storagePath, lastIndexedAt2, storageType)
                    GoRoLog.d(TAG, "Startup directory reconcile: $dirsIndexed new/changed dirs for $storageId")
                } catch (e: Exception) {
                    GoRoLog.w(TAG, "Startup directory reconcile failed for $storageId: ${e.message}")
                }

                try {
                    // 3. Deletion reconcile — remove entries for files/folders deleted while closed
                    val deletedCount = indexingService.reconcileDeletedEntries(storageId, storagePath)
                    GoRoLog.d(TAG, "Startup deletion reconcile: $deletedCount entries removed for $storageId")
                } catch (e: Exception) {
                    GoRoLog.w(TAG, "Startup deletion reconcile failed for $storageId: ${e.message}")
                }

                saveLastIndexedAt(storageId)
            }
        }
    }

    /**
     * Shutdown the indexing system (call from Application or lifecycle owner).
     */
    fun shutdown() {
        GoRoLog.i(TAG, "Shutting down indexing system")
        stopMonitoring()
        indexingService.cancelAllIndexing()
        indexingService.shutdown()
    }

    // ============ INDEXING STATUS ============

    /**
     * Returns true if this storage has ever completed a full initial scan.
     * Once true, a full scan will never be triggered again.
     */
    fun isStorageFullyIndexed(storageId: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("$PREF_FULLY_INDEXED$storageId", false)
    }

    /**
     * Mark a storage as fully indexed. Called internally by [FileIndexingService] after a
     * first-time full scan completes.
     */
    fun markStorageAsFullyIndexed(storageId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("$PREF_FULLY_INDEXED$storageId", true).apply()
        GoRoLog.d(TAG, "Marked as fully indexed: $storageId")
    }

    /**
     * Clear the full-indexed status for a storage (e.g. after the user explicitly clears the index).
     * The next [initialize] call will trigger a new first-time full scan.
     */
    fun clearFullIndexingStatus(storageId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("$PREF_FULLY_INDEXED$storageId").apply()
    }

    /**
     * Check if the indexing service is currently actively scanning a storage.
     */
    fun isIndexing(storageId: String): Boolean = indexingService.isIndexing(storageId)

    suspend fun reindexFolder(folderPath: String, storageId: String, storageType: String, recursive: Boolean): Int {
        if (hasUserDeclinedIndexing(storageId)) return 0
        return indexingService.reindexFolder(folderPath, storageId, storageType, recursive)
    }

    // ============ LIVE INDEXING (called by MediaStoreChangeObserver) ============

    /**
     * Index or re-index a single file. Called by [MediaStoreChangeObserver] on file events.
     */
    fun indexFile(file: File, storageId: String, storageType: String) {
        if (hasUserDeclinedIndexing(storageId)) return
        indexingService.indexFile(file, storageId, storageType)
    }

    /**
     * Index all immediate children of a folder. Called by [MediaStoreChangeObserver] on
     * directory create/change events.
     */
    fun indexFolder(folderPath: String, storageId: String, storageType: String) {
        if (hasUserDeclinedIndexing(storageId)) return
        indexingService.indexFolder(folderPath, storageId, storageType)
    }

    /**
     * Hard-delete a single entry from the index.
     * Called by [MediaStoreChangeObserver] on deletion events.
     */
    fun handleFileDeleted(path: String, storageId: String) {
        if (hasUserDeclinedIndexing(storageId)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                dao.deleteByPath(path)
                GoRoLog.d(TAG, "Hard-deleted from index: $path")
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Error deleting $path from index: ${e.message}")
            }
        }
    }

    /**
     * Hard-delete a single path from the index.
     * Called after a file is moved (cut+paste) to remove the stale source entry.
     */
    suspend fun deleteFromIndex(path: String) {
        try {
            dao.deleteByPath(path)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "deleteFromIndex failed for $path: ${e.message}")
        }
    }

    /**
     * Hard-delete all entries whose path starts with [folderPath].
     * Called after a directory is moved to remove the entire stale source tree.
     */
    suspend fun deleteTreeFromIndex(folderPath: String) {
        try {
            dao.deleteByPathPrefix(folderPath)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "deleteTreeFromIndex failed for $folderPath: ${e.message}")
        }
    }

    // ============ PROGRESS LISTENERS ============

    fun addProgressListener(listener: FileIndexingService.IndexingProgressListener) {
        indexingService.addProgressListener(listener)
    }

    fun removeProgressListener(listener: FileIndexingService.IndexingProgressListener) {
        indexingService.removeProgressListener(listener)
    }

    // ============ MONITORING ============

    private fun startMonitoring() {
        try {
            context.contentResolver.registerContentObserver(
                android.provider.MediaStore.Files.getContentUri("external"),
                true,
                mediaStoreChangeObserver
            )
            GoRoLog.d(TAG, "MediaStoreChangeObserver registered")
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Failed to register MediaStoreChangeObserver: ${e.message}")
        }
    }

    private fun stopMonitoring() {
        try {
            context.contentResolver.unregisterContentObserver(mediaStoreChangeObserver)
            GoRoLog.d(TAG, "MediaStoreChangeObserver unregistered")
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Failed to unregister MediaStoreChangeObserver: ${e.message}")
        }
    }

    // ============ SEARCH ============
    
    suspend fun searchSmart(
        query: String,
        storageId: String = "",
        folderScope: String? = null,
        limit: Int = 500,
        offset: Int = 0
    ): List<FileIndex> = searchEngine.searchSmart(query, storageId, folderScope, limit, offset)

    suspend fun search(query: String, storageId: String = "", limit: Int = 50): List<FileIndex> =
        searchEngine.searchByFilename(query, storageId, limit)

    suspend fun searchByExtension(extension: String, storageId: String = "", limit: Int = 100): List<FileIndex> =
        searchEngine.searchByExtension(extension, storageId, limit)

    suspend fun searchByMimeType(mimePattern: String, storageId: String = "", limit: Int = 100): List<FileIndex> =
        searchEngine.searchByMimeType(mimePattern, storageId, limit)

    suspend fun searchBySize(minSize: Long, maxSize: Long, storageId: String = "", limit: Int = 1000): List<FileIndex> =
        searchEngine.searchBySize(minSize, maxSize, storageId, limit)

    suspend fun getFolderContents(folderPath: String): List<FileIndex> =
        searchEngine.getFolderContents(folderPath)

    fun getFolderContentsFlow(folderPath: String): Flow<List<FileIndex>> =
        searchEngine.getFolderContentsFlow(folderPath)

    suspend fun getRecentlyModified(sinceMinutesAgo: Int = 60, storageId: String = "", limit: Int = 100): List<FileIndex> =
        searchEngine.getRecentlyModified(sinceMinutesAgo, storageId, limit)

    suspend fun getRecentlyIndexed(sinceMinutesAgo: Int = 60, storageId: String = "", limit: Int = 100): List<FileIndex> =
        searchEngine.getRecentlyIndexed(sinceMinutesAgo, storageId, limit)

    suspend fun getLargestFiles(storageId: String, limit: Int = 100): List<FileIndex> =
        searchEngine.getLargestFiles(storageId, limit)

    suspend fun getFile(path: String): FileIndex? =
        searchEngine.getFile(path)

    // ============ DUPLICATE DETECTION ============

    suspend fun findDuplicates(hash: String, size: Long): List<FileIndex> =
        searchEngine.findDuplicates(hash, size)

    // ============ STORAGE ANALYTICS ============

    suspend fun getStorageAnalytics(): StorageAnalytics? {
        return try {
            val stats = dao.getIndexStats()
            val usageByDevice = dao.getStorageUsageByDevice()
            if (stats != null) {
                StorageAnalytics(
                    totalFiles = stats.totalFiles,
                    totalSize = stats.totalSize,
                    deviceCount = stats.deviceCount,
                    usageByDevice = usageByDevice
                )
            } else null
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting storage analytics: ${e.message}")
            null
        }
    }

    suspend fun getStorageUsageByType(storageId: String): List<FileTypeUsage> =
        searchEngine.getStorageUsageByType(storageId)

    suspend fun getFileCount(storageId: String): Long =
        searchEngine.getFileCount(storageId)

    // ============ MAINTENANCE ============

    /**
     * Clear the index for a storage and reset its fully-indexed status.
     * The next [initialize] will trigger a fresh first-time scan.
     */
    fun clearIndexForStorage(storageId: String) {
        indexingService.clearIndexForStorage(storageId)
        clearFullIndexingStatus(storageId)
        clearLastIndexedAt(storageId)
    }

    /**
     * Clear all index data and all storage statuses.
     */
    fun clearAllIndex() {
        indexingService.clearAllIndex()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.forEach { key ->
            if (key.startsWith(PREF_FULLY_INDEXED) ||
                key.startsWith(PREF_LAST_INDEXED) ||
                key.startsWith(PREF_DECLINED)) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    /** Returns true if the user previously tapped "Not Now" for this storage. */
    fun hasUserDeclinedIndexing(storageId: String): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("$PREF_DECLINED$storageId", false)

    /** Persist the user's "Not Now" choice so we don't ask again. */
    fun setUserDeclinedIndexing(storageId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("$PREF_DECLINED$storageId", true).apply()
    }

    fun cancelAllIndexing() = indexingService.cancelAllIndexing()

    // ============ SHAREDPREFS HELPERS ============

    private fun getLastIndexedAt(storageId: String): Long {
        val ts = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("$PREF_LAST_INDEXED$storageId", 0L)
        // If never saved, fall back to 7 days ago to avoid missing recent files
        return if (ts > 0L) ts else System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1_000)
    }

    private fun clearLastIndexedAt(storageId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("$PREF_LAST_INDEXED$storageId").apply()
    }

    private fun saveLastIndexedAt(storageId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong("$PREF_LAST_INDEXED$storageId", System.currentTimeMillis()).apply()
    }

    // ============ SINGLETON ============

    companion object {
        private const val PREFS_NAME         = "ufm_index_prefs"
        private const val PREF_FULLY_INDEXED = "fullyIndexed_"
        private const val PREF_LAST_INDEXED  = "lastIndexedAt_"
        private const val PREF_DECLINED      = "declinedIndexing_"

        @Volatile private var INSTANCE: IndexingRepository? = null

        fun getInstance(context: Context): IndexingRepository {
            return INSTANCE ?: synchronized(this) {
                IndexingRepository(context).also { INSTANCE = it }
            }
        }

        /**
         * Resolve the (storageId, storageType, volumeRoot) for any absolute local file path.
         *
         *  - /storage/emulated/0/...   → ("internal",       "internal", "/storage/emulated/0")
         *  - /storage/ABCD-1234/...    → ("sdcard_ABCD-1234","sdcard",  "/storage/ABCD-1234")
         *  - anything else             → ("internal",       "internal", "/storage/emulated/0")
         */
        fun resolveStorageForPath(path: String): Triple<String, String, String> {
            return when {
                path.startsWith("/storage/emulated/0") ->
                    Triple("internal", "internal", "/storage/emulated/0")

                path.startsWith("/storage/") -> {
                    val parts = path.split("/")          // ["","storage","UUID",...]
                    val uuid = parts.getOrElse(2) { "" }
                    val root = "/storage/$uuid"
                    Triple("sdcard_$uuid", "sdcard", root)
                }

                else -> Triple("internal", "internal", "/storage/emulated/0")
            }
        }
    }
}

/**
 * Storage analytics summary.
 */
data class StorageAnalytics(
    val totalFiles: Long,
    val totalSize: Long,
    val deviceCount: Long,
    val usageByDevice: List<StorageUsage> = emptyList()
)
