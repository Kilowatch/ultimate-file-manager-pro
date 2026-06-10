package za.kilowatch.ultimatefilemanager.sync

import android.content.Context
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

object SyncScheduler {

    fun scheduleSync(context: Context, profile: SyncProfile) {
        val workName = "sync_${profile.id}"

        if (!profile.enabled) {
            cancelSync(context, profile.id)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf("PROFILE_ID" to profile.id)

        when (profile.scheduleType) {
            "scheduled" -> {
                // Calculate how many minutes to the next trigger
                val delayMinutes = computeInitialDelayMinutes(profile)
                val intervalMinutes = when (profile.scheduledPeriod) {
                    "weekly"  -> TimeUnit.DAYS.toMinutes(7)
                    "monthly" -> TimeUnit.DAYS.toMinutes(30)
                    else      -> TimeUnit.DAYS.toMinutes(1) // daily
                }

                val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                    intervalMinutes, TimeUnit.MINUTES
                )
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setInputData(inputData)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    workName,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
            }
            else -> {
                // interval sync (default)
                val interval = profile.intervalMinutes.toLong().coerceAtLeast(15)

                val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setInputData(inputData)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    workName,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
            }
        }
    }

    /**
     * Computes the delay in minutes from now until the next scheduled trigger time.
     * For daily: same time every day.
     * For weekly: same day-of-week + time.
     * For monthly: same day-of-month + time.
     */
    private fun computeInitialDelayMinutes(profile: SyncProfile): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, profile.scheduledHour)
            set(Calendar.MINUTE, profile.scheduledMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Advance to the correct day for weekly/monthly
        if (profile.scheduledPeriod == "weekly") {
            // scheduledDayOfWeek: 1=Mon, 7=Sun → Calendar uses 1=Sun, 2=Mon … 7=Sat
            val calDay = ((profile.scheduledDayOfWeek % 7) + 1) // map 1..7 (Mon-Sun) → 2..8 then wrap to Calendar constants
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
            // daily: if target is already past today, push to tomorrow
            if (target.before(now) || target == now) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val delayMs = target.timeInMillis - now.timeInMillis
        return TimeUnit.MILLISECONDS.toMinutes(delayMs).coerceAtLeast(1)
    }

    fun cancelSync(context: Context, profileId: String) {
        val workName = "sync_$profileId"
        WorkManager.getInstance(context).cancelUniqueWork(workName)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWork()
    }
}
