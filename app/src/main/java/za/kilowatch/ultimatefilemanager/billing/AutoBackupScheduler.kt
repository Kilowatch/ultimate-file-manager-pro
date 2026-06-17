package za.kilowatch.ultimatefilemanager.billing

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules and cancels the periodic auto-backup [AutoBackupWorker] via WorkManager.
 *
 * Also provides [runOnceNow] for event-driven immediate backups.
 */
object AutoBackupScheduler {
    private const val WORK_NAME = "auto_backup"

    /**
     * Schedule a periodic backup based on [scheduleType].
     *
     * @param scheduleType one of "daily", "weekly", "monthly"
     */
    fun schedule(context: Context, scheduleType: String) {
        val intervalMinutes = when (scheduleType) {
            "daily"   -> TimeUnit.DAYS.toMinutes(1)
            "weekly"  -> TimeUnit.DAYS.toMinutes(7)
            "monthly" -> TimeUnit.DAYS.toMinutes(30)
            else      -> TimeUnit.DAYS.toMinutes(7) // default: weekly
        }

        val workRequest = PeriodicWorkRequestBuilder<AutoBackupWorker>(
            intervalMinutes, TimeUnit.MINUTES
        )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    /**
     * Cancel any scheduled periodic auto-backup.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Enqueue a one-shot backup immediately.
     * Uses [ExistingWorkPolicy.KEEP] so if one is already queued, it is not duplicated.
     */
    fun runOnceNow(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${WORK_NAME}_once",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}
