package za.kilowatch.ultimatefilemanager.sync.advanced

import android.content.Context
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

object AdvancedSyncScheduler {

    fun scheduleSync(context: Context, profile: AdvancedSyncProfile) {
        val workName = "advanced_sync_${profile.id}"

        if (!profile.enabled || profile.scheduleType == "manual") {
            cancelSync(context, profile.id)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (profile.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        val inputData = workDataOf("PROFILE_ID" to profile.id)

        when (profile.scheduleType) {
            "scheduled" -> {
                val delayMinutes = computeInitialDelayMinutes(profile)
                val intervalMinutes = when (profile.scheduledPeriod) {
                    "weekly"  -> TimeUnit.DAYS.toMinutes(7)
                    "monthly" -> TimeUnit.DAYS.toMinutes(30)
                    else      -> TimeUnit.DAYS.toMinutes(1) // daily
                }

                val workRequest = PeriodicWorkRequestBuilder<AdvancedSyncWorker>(
                    intervalMinutes, TimeUnit.MINUTES
                )
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setInputData(inputData)
                    .addTag("advanced_sync")
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    workName,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
            }
            else -> {
                // interval sync (default)
                val interval = profile.intervalMinutes.toLong().coerceAtLeast(5)

                val workRequest = PeriodicWorkRequestBuilder<AdvancedSyncWorker>(
                    interval, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setInputData(inputData)
                    .addTag("advanced_sync")
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    workName,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
            }
        }
    }

    private fun computeInitialDelayMinutes(profile: AdvancedSyncProfile): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, profile.scheduledHour)
            set(Calendar.MINUTE, profile.scheduledMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (profile.scheduledPeriod == "weekly") {
            val calDay = ((profile.scheduledDayOfWeek % 7) + 1)
                .coerceIn(Calendar.SUNDAY, Calendar.SATURDAY)
            target.set(Calendar.DAY_OF_WEEK, calDay)
            if (target.before(now) || target == now) {
                target.add(Calendar.WEEK_OF_YEAR, 1)
            }
        } else if (profile.scheduledPeriod == "monthly") {
            target.set(Calendar.DAY_OF_MONTH, profile.scheduledDayOfMonth.coerceIn(1, 28))
            if (target.before(now) || target == now) {
                target.add(Calendar.MONTH, 1)
            }
        } else {
            // daily
            if (target.before(now) || target == now) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val delayMs = target.timeInMillis - now.timeInMillis
        return TimeUnit.MILLISECONDS.toMinutes(delayMs).coerceAtLeast(1)
    }

    fun cancelSync(context: Context, profileId: String) {
        val workName = "advanced_sync_$profileId"
        WorkManager.getInstance(context).cancelUniqueWork(workName)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag("advanced_sync")
    }
}
