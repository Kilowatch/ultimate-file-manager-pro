package za.kilowatch.ultimatefilemanager.indexing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/**
 * IndexingManager — entry-point coordinator for the indexing system.
 *
 * Responsibilities:
 *  - Kick off a background index for a user-initiated storage selection (first-time or re-index)
 *  - Expose progress callbacks to the UI through [FileIndexingService.IndexingProgressListener]
 *  - Expose status helpers ([isIndexingDone], [getIndexingStatus])
 *
 * The manager does NOT decide whether a full or incremental scan happens — that decision lives
 * entirely in [IndexingRepository.initialize] / [FileIndexingService.startFirstTimeIndex].
 */
class IndexingManager(
    private val context: Context,
    private val indexingRepository: IndexingRepository = IndexingRepository.getInstance(context)
) {

    private val TAG = "IndexingManager"
    private val managerScope = CoroutineScope(Dispatchers.IO + Job())

    private var indexingJob: Job? = null
    private val isIndexingComplete = mutableMapOf<String, Boolean>()

    /**
     * Start background indexing for a specific storage, with progress reporting to the UI.
     *
     * If the storage has never been indexed, [FileIndexingService.startFirstTimeIndex] runs a full
     * directory walk.  If it has already been indexed, the listener will fire immediately with a
     * lightweight incremental update (the guard is in [FileIndexingService.startFirstTimeIndex]).
     */
    fun startBackgroundIndexing(
        storageId: String,
        storagePath: String,
        storageType: String,
        onProgress: (current: Int, total: Int) -> Unit,
        onComplete: () -> Unit,
        onError: ((Exception) -> Unit)? = null
    ) {
        // If the user chose "Not Now" for this storage, respect it — open at normal speed.
        if (indexingRepository.hasUserDeclinedIndexing(storageId)) {
            Log.d(TAG, "Indexing skipped — user declined for $storageId")
            onComplete()
            return
        }

        val listener = object : FileIndexingService.IndexingProgressListener {
            override fun onIndexingStarted(storageId: String) {
                Log.d(TAG, "Indexing started for $storageId")
            }

            override fun onFileIndexed(fileIndex: FileIndex, count: Int) {
                if (fileIndex.storageId == storageId) {
                    onProgress(count, -1)
                }
            }

            override fun onProgressUpdate(id: String, currentCount: Int, totalEstimated: Int) {
                if (id == storageId) onProgress(currentCount, totalEstimated)
            }

            override fun onIndexingCompleted(id: String, totalIndexed: Int) {
                if (id == storageId) {
                    Log.d(TAG, "Indexing completed for $id: $totalIndexed files")
                    isIndexingComplete[id] = true

                    // Start live monitoring now that the initial index is done
                    indexingRepository.removeProgressListener(this)
                    onComplete()
                }
            }

            override fun onIndexingError(id: String, error: Exception) {
                if (id == storageId) {
                    Log.e(TAG, "Indexing error for $id: ${error.message}")
                    indexingRepository.removeProgressListener(this)
                    onError?.invoke(error)
                }
            }
        }

        indexingRepository.addProgressListener(listener)

        // Route through the service — it enforces the first-time / incremental guard
        FileIndexingService.getInstance(context).startFirstTimeIndex(storageId, storagePath, storageType)
    }

    // ============ STATUS ============

    fun isIndexingDone(storageId: String = "internal"): Boolean =
        isIndexingComplete[storageId] ?: false

    fun resetAllIndexingStatus() = isIndexingComplete.clear()

    fun getIndexingStatus(): Map<String, Boolean> = isIndexingComplete.toMap()

    // ============ LIFECYCLE ============

    fun stopIndexing() {
        indexingJob?.cancel()
        indexingRepository.cancelAllIndexing()
        Log.d(TAG, "All indexing operations stopped")
    }

    fun shutdown() {
        stopIndexing()
        indexingRepository.shutdown()
    }

    // ============ SINGLETON ============

    companion object {
        @Volatile private var INSTANCE: IndexingManager? = null

        fun getInstance(context: Context): IndexingManager {
            return INSTANCE ?: synchronized(this) {
                IndexingManager(context).also { INSTANCE = it }
            }
        }
    }
}
