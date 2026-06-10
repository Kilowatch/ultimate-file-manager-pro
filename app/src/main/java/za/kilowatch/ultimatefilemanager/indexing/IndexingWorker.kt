package za.kilowatch.ultimatefilemanager.indexing

import android.content.Context
import android.content.Context.MODE_PRIVATE
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.util.concurrent.TimeUnit

private const val PREFS_NAME = "ufm_index_prefs"
private const val PREF_LAST_INDEXED_PREFIX = "lastIndexedAt_"

/**
 * WorkManager worker for background incremental indexing.
 *
 * This worker is **always incremental** — it reads [lastIndexedAt] from SharedPreferences and
 * only processes MediaStore entries that were modified since that timestamp.  A full re-scan is
 * never triggered here.  If no timestamp exists (e.g., storage was never indexed through
 * WorkManager) it falls back to 7 days ago as a safe window.
 *
 * Constraints: device must be charging and idle so it does not impact battery or performance.
 */
class IndexingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val TAG = "IndexingWorker"

    override suspend fun doWork(): Result {
        return try {
            val storageId   = inputData.getString(KEY_STORAGE_ID)   ?: "internal"
            val storageType = inputData.getString(KEY_STORAGE_TYPE)  ?: "internal"
            val storagePath = inputData.getString(KEY_STORAGE_PATH)  ?: "/storage/emulated/0"

            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val lastIndexedAt = prefs.getLong(PREF_LAST_INDEXED_PREFIX + storageId, 0L)

            // Use last recorded timestamp; fall back to 7 days ago if none saved yet
            val sinceMillis = if (lastIndexedAt > 0L) {
                lastIndexedAt
            } else {
                System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1_000)
            }

            val service = FileIndexingService.getInstance(applicationContext)
            
            // 1. MediaStore incremental scan — picks up new/changed FILES (including DATE_ADDED)
            val mediaIndexed = service.incrementalIndexFromMediaStore(storageId, sinceMillis, storageType, storagePath)
            GoRoLog.d(TAG, "Periodic media reconcile: $mediaIndexed entries for $storageId")

            // 2. Directory-only filesystem walk — picks up new FOLDERS and missed files (including old timestamps)
            val dirsIndexed = service.reconcileNewDirectoriesSince(storageId, storagePath, sinceMillis, storageType)
            GoRoLog.d(TAG, "Periodic directory reconcile: $dirsIndexed new/changed entries for $storageId")

            // 3. Deletion reconcile — remove entries for files/folders deleted while closed
            val deletedCount = service.reconcileDeletedEntries(storageId, storagePath)
            GoRoLog.d(TAG, "Periodic deletion reconcile: $deletedCount entries removed for $storageId")

            // Persist updated timestamp
            prefs.edit().putLong(PREF_LAST_INDEXED_PREFIX + storageId, System.currentTimeMillis()).apply()

            Result.success()
        } catch (e: Exception) {
            GoRoLog.e(TAG, "IndexingWorker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val KEY_STORAGE_ID   = "storageId"
        private const val KEY_STORAGE_TYPE = "storageType"
        private const val KEY_STORAGE_PATH = "storagePath"

        // Unique names for WorkManager deduplication
        private const val WORK_PERIODIC   = "ufm_indexing_periodic"
        private fun workOneTimeName(storageId: String) = "ufm_indexing_incremental_$storageId"

        /**
         * Schedule a one-time incremental index job.  Called by [MediaStoreChangeObserver] on
         * burst detection.  Uses conservative constraints so it never runs at a bad time.
         */
        fun enqueueIncrementalIndex(
            context: Context,
            storageId: String = "internal",
            storagePath: String = "/storage/emulated/0",
            storageType: String = "internal"
        ) {
            val data = Data.Builder()
                .putString(KEY_STORAGE_ID, storageId)
                .putString(KEY_STORAGE_TYPE, storageType)
                .putString(KEY_STORAGE_PATH, storagePath)
                .build()

            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresDeviceIdle(true)
                .build()

            val request = OneTimeWorkRequestBuilder<IndexingWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(workOneTimeName(storageId), ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Schedule (or keep existing) daily periodic incremental indexing.
         * Runs only when the device is charging and idle.  Called from [IndexingRepository.initialize].
         */
        fun schedulePeriodicIndex(context: Context, storageId: String = "internal", storageType: String = "internal", storagePath: String = "/storage/emulated/0") {
            val data = Data.Builder()
                .putString(KEY_STORAGE_ID, storageId)
                .putString(KEY_STORAGE_TYPE, storageType)
                .putString(KEY_STORAGE_PATH, storagePath)
                .build()

            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<IndexingWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodicRequest)
        }
    }
}
