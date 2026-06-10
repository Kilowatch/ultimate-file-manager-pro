package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that checks if FTP/SFTP servers should be running
 * (based on saved preferences) and restarts the [FileServerService] if needed.
 *
 * Runs every 15 minutes to ensure server resilience when the app is backgrounded.
 *
 * M-3: This worker only restarts a server when the user has **explicitly** left
 * the enabled preference set to `true` AND the server is not currently running.
 * It does NOT restart a server that has been stopped by the user (stopFtp/stopSftp
 * or stopAll both set the preference to `false`). If the server state cannot be
 * determined it skips the restart to avoid silently bringing up an unwanted server.
 */
class ServerMonitorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "ServerMonitorWorker"
        private const val WORK_NAME = "file_server_monitor"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServerMonitorWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val ftpEnabled  = FileServerService.isFtpEnabled(applicationContext)
        val sftpEnabled = FileServerService.isSftpEnabled(applicationContext)

        // M-3: No servers were enabled by the user — skip entirely.
        if (!ftpEnabled && !sftpEnabled) {
            Log.d(TAG, "Monitor: no servers enabled by user, skipping")
            return Result.success()
        }

        // M-3: Only restart a server if the user left it enabled AND it is not currently
        // running. We check the live ServerState so we never restart a server the user
        // just stopped from the UI (stopFtp/stopSftp also clears the preference, so
        // isFtpEnabled would already be false in that case — this is belt-and-suspenders).
        val state = FileServerService.serverState.value

        if (ftpEnabled && state?.ftpRunning != true) {
            Log.i(TAG, "Monitor: FTP enabled but not running — restarting")
            FileServerService.startFtp(applicationContext)
        }

        if (sftpEnabled && state?.sftpRunning != true) {
            Log.i(TAG, "Monitor: SFTP enabled but not running — restarting")
            FileServerService.startSftp(applicationContext)
        }

        return Result.success()
    }
}
