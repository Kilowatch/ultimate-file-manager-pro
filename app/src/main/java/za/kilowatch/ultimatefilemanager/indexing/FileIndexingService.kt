package za.kilowatch.ultimatefilemanager.indexing

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * File Indexing Service — orchestrates the indexing pipeline.
 *
 * Lifecycle of a storage device:
 *  1. FIRST OPEN  → [firstTimeFullIndex] is called exactly once; scans every file/folder and
 *                   marks the storage as fully indexed in SharedPreferences.
 *  2. SUBSEQUENT OPENS → [incrementalIndexFromMediaStore] reconciles anything that changed while
 *                        the app was closed, keyed by the saved [lastIndexedAt] timestamp.
 *  3. WHILE APP IS OPEN → [indexFile] / [indexFolder] are called on-demand by
 *                         [MediaStoreChangeObserver] for individual changes and directory events.
 *
 * Full scans ([firstTimeFullIndex]) are never repeated once a storage is marked fully indexed.
 */
class FileIndexingService(
    private val context: Context,
    private val database: UfmIndexingDatabase = UfmIndexingDatabase.getInstance(context)
) {

    private val TAG = "FileIndexingService"
    private val dao = database.fileIndexDao()
    private val scanner = FilesystemScanner(context)
    private val metadataExtractor = MetadataExtractor(context)

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // Active indexing jobs. A whole-storage run ([startFirstTimeIndex]) is keyed by the bare
    // storageId; the per-path live jobs from [indexFile]/[indexFolder] are keyed by
    // [liveJobKey] so they cannot clobber — or be clobbered by — a full index of the same
    // storage. Every lookup that takes a storageId resolves the key through [storageIdOfJobKey],
    // so both forms are found and cancelled together.
    private val activeIndexingJobs = ConcurrentHashMap<String, Job>()

    /**
     * Separator between the storageId and the per-path suffix in a live job key.
     *
     * A NUL is used because it cannot occur in a storage identifier or a filesystem path, so
     * a plain storageId key (`"7DE2-1219"`) can never be confused with a live key
     * (`"7DE2-1219\u0000folder:/storage/.../x"`) by a prefix test.
     */
    private val liveJobKeySep = '\u0000'

    private fun liveJobKey(storageId: String, kind: String, target: String): String =
        "$storageId$liveJobKeySep$kind:$target"

    /** The storageId a job key belongs to, for both the plain and the live key form. */
    private fun storageIdOfJobKey(key: String): String = key.substringBefore(liveJobKeySep)

    // Progress throttling
    private var lastProgressNotifyTs: Long = 0
    private val progressNotifyIntervalMs: Long = 500L
    private val progressNotifyEveryN: Int = 100

    // ============ PROGRESS LISTENER ============

    interface IndexingProgressListener {
        fun onIndexingStarted(storageId: String)
        fun onFileIndexed(fileIndex: FileIndex, count: Int)
        fun onProgressUpdate(storageId: String, currentCount: Int, totalEstimated: Int)
        fun onIndexingCompleted(storageId: String, totalIndexed: Int)
        fun onIndexingError(storageId: String, error: Exception)
    }

    private val progressListeners = java.util.concurrent.CopyOnWriteArrayList<IndexingProgressListener>()

    fun addProgressListener(listener: IndexingProgressListener) { progressListeners.addIfAbsent(listener) }
    fun removeProgressListener(listener: IndexingProgressListener) { progressListeners.remove(listener) }

    // ============ FIRST-TIME FULL INDEX ============

    /**
     * Perform the first-time full index for a storage.  Call this only when
     * [IndexingRepository.isStorageFullyIndexed] returns **false**.
     *
     * Can be launched asynchronously via [startFirstTimeIndex] from [IndexingManager], or called
     * directly (suspend) from [IndexingWorker] — hence it is public and suspendable.
     */
    suspend fun firstTimeFullIndex(
        storageId: String,
        storagePath: String,
        storageType: String,
        hashAlgorithm: MetadataExtractor.HashAlgorithm = MetadataExtractor.HashAlgorithm.MD5_QUICK
    ): Int {
        notifyIndexingStarted(storageId)

        val storageFile = File(storagePath)
        var batchCount = 0
        var currentIndexedCount = 0
        val fileIndices = mutableListOf<FileIndex>()

        // Walk the directory tree directly (MediaStore may miss non-media files)
        scanner.scanDirectory(
            dir = storageFile,
            storageId = storageId,
            storageType = storageType,
            emit = { fileIndex ->
                fileIndices.add(fileIndex)
                currentIndexedCount++

                if (fileIndices.size >= BATCH_SIZE) {
                    dao.insertAll(fileIndices)
                    batchCount += fileIndices.size
                    fileIndices.clear()
                    val now = System.currentTimeMillis()
                    if (now - lastProgressNotifyTs >= progressNotifyIntervalMs) {
                        lastProgressNotifyTs = now
                        notifyProgressUpdate(storageId, batchCount, -1)
                    }
                }

                val now = System.currentTimeMillis()
                if (currentIndexedCount % progressNotifyEveryN == 0 ||
                    now - lastProgressNotifyTs >= progressNotifyIntervalMs
                ) {
                    lastProgressNotifyTs = now
                    notifyFileIndexed(fileIndex, currentIndexedCount)
                }
            }
        )

        if (fileIndices.isNotEmpty()) {
            dao.insertAll(fileIndices)
            batchCount += fileIndices.size
        }

        GoRoLog.i(TAG, "First-time full index completed for $storageId: $batchCount entries")
        return batchCount
    }

    /**
     * Async launcher for [firstTimeFullIndex] — stores the job so it can be cancelled.
     */
    fun startFirstTimeIndex(
        storageId: String,
        storagePath: String,
        storageType: String,
        hashAlgorithm: MetadataExtractor.HashAlgorithm = MetadataExtractor.HashAlgorithm.MD5_QUICK
    ) {
        activeIndexingJobs[storageId]?.cancel()

        val job = serviceScope.launch {
            try {
                val total = firstTimeFullIndex(storageId, storagePath, storageType, hashAlgorithm)
                notifyIndexingCompleted(storageId, total)

                // Mark as fully indexed so this path is never taken again
                IndexingRepository.getInstance(context).markStorageAsFullyIndexed(storageId)
                // Persist the timestamp so startup reconciliation has a baseline
                saveLastIndexedAt(storageId)

                GoRoLog.i(TAG, "Storage marked as fully indexed: $storageId")
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Error during first-time index for $storageId: ${e.message}", e)
                notifyIndexingError(storageId, e)
            } finally {
                // Conditional removal, as in indexFile/indexFolder: this call cancels and
                // replaces the previous job for the same storageId, so an unconditional
                // remove here could evict the replacement while it is still running — which
                // would also make awaitIdle return before the work had actually stopped.
                activeIndexingJobs.remove(storageId, coroutineContext[Job])
            }
        }

        activeIndexingJobs[storageId] = job
    }

    // ============ INCREMENTAL INDEX (startup reconciliation + WorkManager) ============

    /**
     * Query MediaStore for entries modified after [sinceMillis] and upsert them into the database.
     * This is the primary update path after a storage has been fully indexed.
     */
    suspend fun incrementalIndexFromMediaStore(
        storageId: String,
        sinceMillis: Long,
        storageType: String = "internal",
        storagePath: String = ""
    ): Int {
        var currentIndexed = 0
        val fileIndices = mutableListOf<FileIndex>()
        var batchCount = 0

        scanner.scanMediaStoreSince(storageId, storageType, sinceMillis, storagePath).collect { fileIndex ->
            fileIndices.add(fileIndex)
            currentIndexed++

            if (fileIndices.size >= BATCH_SIZE) {
                dao.insertAll(fileIndices)
                batchCount += fileIndices.size
                fileIndices.clear()
                val now = System.currentTimeMillis()
                if (now - lastProgressNotifyTs >= progressNotifyIntervalMs) {
                    lastProgressNotifyTs = now
                    notifyProgressUpdate(storageId, batchCount, -1)
                }
            }

            val now = System.currentTimeMillis()
            if (currentIndexed % progressNotifyEveryN == 0 || now - lastProgressNotifyTs >= progressNotifyIntervalMs) {
                lastProgressNotifyTs = now
                notifyFileIndexed(fileIndex, currentIndexed)
            }
        }

        if (fileIndices.isNotEmpty()) {
            dao.insertAll(fileIndices)
            batchCount += fileIndices.size
        }

        notifyProgressUpdate(storageId, batchCount, -1)
        GoRoLog.i(TAG, "Incremental media index done for $storageId: $batchCount entries (since $sinceMillis)")
        return batchCount
    }

    // ============ INCREMENTAL DIRECTORY-ONLY RECONCILE ============

    /**
     * Scan the filesystem for directories that were created or modified after [sinceMillis] and
     * upsert them into the database.  This supplements [incrementalIndexFromMediaStore] because
     * Android's MediaStore does not track directories.
     *
     * The walk is very efficient: entire untouched subtrees (whose root dir has
     * lastModified <= sinceMillis) are skipped without any DB query.
     */
    suspend fun reconcileNewDirectoriesSince(
        storageId: String,
        storagePath: String,
        sinceMillis: Long,
        storageType: String = "internal"
    ): Int {
        var count = 0
        val batch = mutableListOf<FileIndex>()

        scanner.scanNewDirectoriesSince(java.io.File(storagePath), storageId, storageType, sinceMillis)
            .collect { fileIndex ->
                batch.add(fileIndex)
                count++
                if (batch.size >= BATCH_SIZE) {
                    dao.insertAll(batch)
                    batch.clear()
                }
            }

        if (batch.isNotEmpty()) {
            dao.insertAll(batch)
        }

        GoRoLog.i(TAG, "Directory reconcile done for $storageId: $count new/changed dirs (since $sinceMillis)")
        return count
    }

    // ============ DELETION RECONCILE ============

    /**
     * Check every indexed path for [storageId] against the filesystem and soft-delete any entry
     * whose file or folder no longer exists.
     *
     * When a folder is deleted, [softDeleteByPathPrefix] handles all its children in a single SQL
     * statement, so this is far more efficient than N individual soft-deletes.
     *
     * Called at startup after the incremental MediaStore and directory-only scans.
     */
    suspend fun reconcileDeletedEntries(storageId: String, storagePath: String): Int {
        // Safety guard: if the storage root itself is not accessible via the File API
        // (SD card on Android 11+ can return false from exists() without MANAGE_EXTERNAL_STORAGE
        // at this moment, or the volume hasn't fully mounted yet), skip entirely.
        // It is far safer to skip than to false-delete 100+ valid entries.
        val storageRoot = java.io.File(storagePath)
        if (!storageRoot.exists() || !storageRoot.canRead()) {
            GoRoLog.w(TAG, "Deletion reconcile skipped — storage root not readable: $storagePath")
            return 0
        }

        val allPaths = dao.getAllPathsForStorage(storageId)
        var deletedCount = 0
        val deletedPrefixes = mutableListOf<String>()

        var diagCount = 0
        for (path in allPaths) {
            if (path.endsWith(".ufm_tmp")) {
                dao.deleteByPath(path)
                continue
            }
            if (deletedPrefixes.any { path.startsWith(it) }) continue

            val file = File(path)
            val isProtected = za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isProtectedPath(path)
            val fileExists = if (isProtected && za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(path)) {
                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.exists(path)
            } else {
                file.exists()
            }
            val parentReadable = file.parentFile?.canRead() == true

            // --- Scoped-storage guard -------------------------------------------
            // On Android 11+, File.exists() (a stat() syscall) returns false for
            // subdirectories inside /Android/data/ that belong to other apps, even
            // when the directory is physically present on the SD card.
            // Directory enumeration (listFiles/list) is NOT blocked the same way,
            // so we use parentFile.list() as a secondary truth-check.
            // Only proceed with deletion when BOTH agree the path is gone.
            // If parent is unreadable via standard Java IO or protected, skip deletion.
            val trulyGone = if (isProtected) {
                if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(path)) {
                    !fileExists
                } else {
                    false // Shizuku not active - preserve existing index
                }
            } else if (!fileExists && parentReadable) {
                val listedByParent = file.parentFile?.list()?.contains(file.name) == true
                !listedByParent
            } else if (!fileExists && !parentReadable) {
                false // exists=false but parent unreadable → skip deletion
            } else {
                false // exists=true → keep
            }

            if (trulyGone) {
                GoRoLog.w(TAG, "Deletion candidate #${++diagCount} — path=$path")
                if (allPaths.any { it.startsWith("$path/") }) {
                    val affected = dao.deleteByPathPrefix(path)
                    deletedPrefixes.add("$path/")
                    deletedCount += affected
                } else {
                    dao.deleteByPath(path)
                    deletedCount++
                }
            }
        }

        GoRoLog.i(TAG, "Deletion reconcile done for $storageId: $deletedCount entries removed")
        return deletedCount
    }

    /**
     * Scan a folder to index any new/changed files, and reconcile any deleted files that
     * are no longer physically present. Can run recursively or shallowly.
     */
    suspend fun reindexFolder(
        folderPath: String,
        storageId: String,
        storageType: String,
        recursive: Boolean
    ): Int {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return 0

        var currentIndexedCount = 0
        val fileIndices = mutableListOf<FileIndex>()

        if (recursive) {
            // 1. Walk directory recursively and index new/changed files/folders
            scanner.scanDirectory(
                dir = folder,
                storageId = storageId,
                storageType = storageType,
                emit = { fileIndex ->
                    fileIndices.add(fileIndex)
                    currentIndexedCount++
                    if (fileIndices.size >= BATCH_SIZE) {
                        dao.insertAll(fileIndices)
                        fileIndices.clear()
                    }
                }
            )
        } else {
            // Shallow scan of immediate files/folders
            scanner.scanFolder(folderPath, storageId, storageType).collect { fileIndex ->
                fileIndices.add(fileIndex)
                currentIndexedCount++
                if (fileIndices.size >= BATCH_SIZE) {
                    dao.insertAll(fileIndices)
                    fileIndices.clear()
                }
            }
        }
        if (fileIndices.isNotEmpty()) {
            dao.insertAll(fileIndices)
        }

        // 2. Reconcile deletions under this specific folder
        val dbPaths = if (recursive) {
            dao.getPathsByPrefix(folderPath)
        } else {
            dao.getIndexedPathsInFolder(folderPath)
        }
        var deletedCount = 0
        for (path in dbPaths) {
            val file = File(path)
            val fileExists = file.exists()
            val parentReadable = file.parentFile?.canRead() == true
            val trulyGone = if (!fileExists && parentReadable) {
                val listedByParent = file.parentFile?.list()?.contains(file.name) == true
                !listedByParent
            } else {
                !fileExists
            }
            if (trulyGone) {
                dao.deleteByPath(path)
                deletedCount++
            }
        }

        GoRoLog.i(TAG, "Re-indexed folder $folderPath (recursive=$recursive): indexed $currentIndexedCount, deleted $deletedCount")
        return currentIndexedCount
    }


    // ============ LIVE / ON-DEMAND INDEXING ============



    /**
     * Index a single file. Called by [MediaStoreChangeObserver] for individual file events.
     *
     * Registered in [activeIndexingJobs] under a per-path key so the eject release phase can
     * cancel it and await its completion — without this it was fire-and-forget on
     * [serviceScope] and survived every cancellation, leaving a walk running against a volume
     * that was about to disappear.
     */
    fun indexFile(file: File, storageId: String, storageType: String) {
        val key = liveJobKey(storageId, "file", file.absolutePath)
        activeIndexingJobs.remove(key)?.cancel()

        val job = serviceScope.launch {
            try {
                if (file.exists()) {
                    val fileIndex = metadataExtractor.extractMetadata(file, storageId, storageType)
                    dao.insert(fileIndex)
                    GoRoLog.d(TAG, "Indexed file: ${file.name}")
                }
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Error indexing file ${file.name}: ${e.message}")
            } finally {
                // Conditional removal: a newer job may already have claimed this key, and an
                // unconditional remove here would evict it while it is still running.
                activeIndexingJobs.remove(key, coroutineContext[Job])
            }
        }

        activeIndexingJobs[key] = job
    }

    /**
     * Index all immediate children of a folder (non-recursive shallow scan).
     * Called by [MediaStoreChangeObserver] when a directory creation/change is detected.
     * Registered in [activeIndexingJobs] for the same reason as [indexFile].
     */
    fun indexFolder(folderPath: String, storageId: String, storageType: String) {
        val key = liveJobKey(storageId, "folder", folderPath)
        activeIndexingJobs.remove(key)?.cancel()

        val job = serviceScope.launch {
            try {
                val fileIndices = mutableListOf<FileIndex>()
                var count = 0

                scanner.scanFolder(folderPath, storageId, storageType).collect { fileIndex ->
                    fileIndices.add(fileIndex)
                    if (fileIndices.size >= BATCH_SIZE) {
                        dao.insertAll(fileIndices)
                        count += fileIndices.size
                        fileIndices.clear()
                    }
                }

                if (fileIndices.isNotEmpty()) {
                    dao.insertAll(fileIndices)
                    count += fileIndices.size
                }

                GoRoLog.i(TAG, "Folder indexed: $folderPath ($count entries)")
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Error indexing folder $folderPath: ${e.message}")
            } finally {
                activeIndexingJobs.remove(key, coroutineContext[Job])
            }
        }

        activeIndexingJobs[key] = job
    }

    // ============ JOB MANAGEMENT ============

    fun cancelIndexing(storageId: String) {
        // Cancels both the whole-storage run and every live per-path job for this storage.
        activeIndexingJobs.keys
            .filter { storageIdOfJobKey(it) == storageId }
            .forEach { key -> activeIndexingJobs.remove(key)?.cancel() }
    }

    fun cancelAllIndexing() {
        activeIndexingJobs.values.forEach { it.cancel() }
        activeIndexingJobs.clear()
    }

    fun isIndexing(storageId: String): Boolean =
        activeIndexingJobs.any { storageIdOfJobKey(it.key) == storageId && it.value.isActive }

    fun getActiveIndexingStorages(): List<String> =
        activeIndexingJobs.filter { it.value.isActive }
            .keys.map { storageIdOfJobKey(it) }
            .distinct()

    /**
     * Suspends until no indexing job remains for [storageId], or [timeoutMs] elapses.
     *
     * FR-04 requires background volume work to be cancelled **and awaited** before the unmount
     * is issued — cancelling alone leaves a job mid-walk with descriptors still open. The loop
     * re-checks after joining because a finishing job can register another.
     *
     * Never throws: a timeout is logged and returns. The caller proceeds to the inspection
     * stage, which reports whatever actually survived rather than assuming this succeeded.
     */
    suspend fun awaitIdle(storageId: String, timeoutMs: Long = 5_000L) {
        val completed = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val pending = activeIndexingJobs
                    .filterKeys { storageIdOfJobKey(it) == storageId }
                    .values.toList()
                if (pending.isEmpty()) break
                pending.forEach { job -> try { job.join() } catch (_: Exception) { } }
            }
            true
        }
        if (completed == null) {
            GoRoLog.w(TAG, "awaitIdle timed out after ${timeoutMs}ms for $storageId")
        }
    }

    // ============ MAINTENANCE ============



    fun clearIndexForStorage(storageId: String) {
        serviceScope.launch {
            try {
                dao.deleteByStorageId(storageId)
                GoRoLog.d(TAG, "Index cleared for storage: $storageId")
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Error clearing index for $storageId: ${e.message}")
            }
        }
    }

    fun clearAllIndex() {
        serviceScope.launch {
            try {
                dao.deleteAll()
                GoRoLog.i(TAG, "All index data cleared")
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Error clearing all index data: ${e.message}")
            }
        }
    }

    fun shutdown() {
        cancelAllIndexing()
        serviceScope.launch {
            try { database.close() } catch (e: Exception) {
                GoRoLog.e(TAG, "Error closing database: ${e.message}")
            }
        }
    }

    // ============ SHARED PREFS HELPERS ============

    private fun saveLastIndexedAt(storageId: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_LAST_INDEXED_PREFIX + storageId, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to save lastIndexedAt for $storageId: ${e.message}")
        }
    }

    // ============ PROGRESS NOTIFIERS ============

    private fun notifyIndexingStarted(storageId: String) {
        Handler(Looper.getMainLooper()).post {
            progressListeners.forEach { it.onIndexingStarted(storageId) }
        }
    }

    private fun notifyFileIndexed(fileIndex: FileIndex, count: Int) {
        Handler(Looper.getMainLooper()).post {
            progressListeners.forEach { it.onFileIndexed(fileIndex, count) }
        }
    }

    private fun notifyProgressUpdate(storageId: String, currentCount: Int, totalEstimated: Int) {
        Handler(Looper.getMainLooper()).post {
            progressListeners.forEach { it.onProgressUpdate(storageId, currentCount, totalEstimated) }
        }
    }

    private fun notifyIndexingCompleted(storageId: String, totalIndexed: Int) {
        Handler(Looper.getMainLooper()).post {
            progressListeners.forEach { it.onIndexingCompleted(storageId, totalIndexed) }
        }
    }

    private fun notifyIndexingError(storageId: String, error: Exception) {
        Handler(Looper.getMainLooper()).post {
            progressListeners.forEach { it.onIndexingError(storageId, error) }
        }
    }

    companion object {
        private const val BATCH_SIZE = 500
        private const val PREFS_NAME = "ufm_index_prefs"
        private const val PREF_LAST_INDEXED_PREFIX = "lastIndexedAt_"

        @Volatile private var INSTANCE: FileIndexingService? = null

        fun getInstance(context: Context): FileIndexingService {
            return INSTANCE ?: synchronized(this) {
                FileIndexingService(context).also { INSTANCE = it }
            }
        }
    }
}
