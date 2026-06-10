package za.kilowatch.ultimatefilemanager.recycle

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecycleBinCleanupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "RecycleBinCleanup"
        const val WORK_NAME = "recycle_bin_cleanup"
    }

    override suspend fun doWork(): Result {
        val days = RecycleBinSettingsManager.getAutoDeleteDays(applicationContext)
        if (days <= 0) return Result.success()

        Log.d(TAG, "Running cleanup: deleting entries older than $days days")

        val cutoff = System.currentTimeMillis() - (days * 86400000L)
        val entries = withContext(Dispatchers.IO) {
            RecycleBinManager.getAllEntries()
        }

        val expired = entries.filter { it.dateDeleted < cutoff }
        if (expired.isEmpty()) {
            Log.d(TAG, "No expired entries found")
            return Result.success()
        }

        Log.d(TAG, "Deleting ${expired.size} expired entries")
        for (entity in expired) {
            RecycleBinManager.permanentDelete(entity)
        }

        return Result.success()
    }
}
