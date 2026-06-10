package za.kilowatch.ultimatefilemanager.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import za.kilowatch.ultimatefilemanager.R
import java.io.File

/**
 * Receives the result broadcast from PackageInstaller after an APK / XAPK
 * installation session is committed.
 *
 * KEY BEHAVIOUR — STATUS_PENDING_USER_ACTION:
 * Android 10+ blocks startActivity() from background contexts, and the remote
 * desktop use-case means RemoteManageActivity is always backgrounded when this
 * callback fires. Instead we post a high-priority heads-up notification; the
 * user taps it to confirm the install. This is exempt from background
 * activity-start restrictions.
 *
 * On completion (success or failure), cleans up temp files for both the
 * single-APK path (apk_install/) and the XAPK path (xapk_temp/<jobId>/).
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        // jobId is "" for single-APK installs; non-empty for XAPK jobs.
        val jobId  = intent.getStringExtra("jobId") ?: ""

        val statusLabel = when (status) {
            PackageInstaller.STATUS_SUCCESS              -> "SUCCESS"
            PackageInstaller.STATUS_FAILURE              -> "FAILURE"
            PackageInstaller.STATUS_FAILURE_ABORTED      -> "FAILURE_ABORTED"
            PackageInstaller.STATUS_FAILURE_BLOCKED      -> "FAILURE_BLOCKED"
            PackageInstaller.STATUS_FAILURE_CONFLICT     -> "FAILURE_CONFLICT"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILURE_INCOMPATIBLE"
            PackageInstaller.STATUS_FAILURE_INVALID      -> "FAILURE_INVALID"
            PackageInstaller.STATUS_FAILURE_STORAGE      -> "FAILURE_STORAGE"
            PackageInstaller.STATUS_PENDING_USER_ACTION  -> "PENDING_USER_ACTION"
            else                                         -> "UNKNOWN($status)"
        }
        Log.d(TAG, "Install result for job='$jobId': $statusLabel")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmIntent == null) {
                Log.w(TAG, "STATUS_PENDING_USER_ACTION but EXTRA_INTENT was null")
                return
            }
            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Strategy: try startActivity() first, THEN also post a notification.
            //
            // • TV: RemoteManageActivity is visible on the TV screen, so startActivity()
            //   succeeds and the install dialog appears directly. The notification is a
            //   no-op on most TV launchers.
            //
            // • Mobile + remote desktop: RemoteManageActivity is behind the remote
            //   desktop app, so Android 10+ silently drops startActivity(). The
            //   notification provides the fallback — the user taps it to confirm.
            //
            // Both calls are safe to fire together; at most one will be visible.
            try {
                context.startActivity(confirmIntent)
            } catch (e: Exception) {
                Log.w(TAG, "startActivity for install prompt failed (expected on Android 10+ background): ${e.message}")
            }
            showInstallPromptNotification(context, confirmIntent)
            return // Cleanup happens after the user confirms (next callback)
        }

        // Dismiss the install-prompt notification now that the result is known.
        cancelInstallNotification(context)

        // Clean up XAPK extraction dir (non-empty jobId = XAPK job).
        if (jobId.isNotEmpty()) {
            val xapkDir = File(context.cacheDir, "xapk_temp/$jobId")
            if (xapkDir.exists()) {
                val deleted = xapkDir.deleteRecursively()
                Log.d(TAG, "Cleaned up $xapkDir (deleted=$deleted)")
            }
        }

        // Clean up single-APK temp files (safe no-op for XAPK paths).
        val apkInstallDir = File(context.cacheDir, "apk_install")
        if (apkInstallDir.exists()) {
            apkInstallDir.listFiles()?.forEach { it.delete() }
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun showInstallPromptNotification(context: Context, confirmIntent: Intent) {
        ensureNotificationChannel(context)

        val pi = PendingIntent.getActivity(
            context,
            NOTIF_REQUEST_CODE,
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.notif_apk_ready_title))
            .setContentText(context.getString(R.string.notif_apk_ready_desc))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        notifManager(context).notify(NOTIF_ID, notification)
        Log.d(TAG, "Install-prompt notification posted")
    }

    private fun cancelInstallNotification(context: Context) {
        notifManager(context).cancel(NOTIF_ID)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_apk_install_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_apk_install_desc)
                enableVibration(true)
            }
            notifManager(context).createNotificationChannel(channel)
        }
    }

    private fun notifManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG               = "InstallReceiver"
        private const val CHANNEL_ID        = "ufm_apk_install"
        private const val NOTIF_ID          = 0x4150_4B00  // "APK\0" in hex
        private const val NOTIF_REQUEST_CODE = 0x4150_4B01

        /** Action fired by PackageInstaller on session completion. */
        const val ACTION_INSTALL_COMPLETE = "za.kilowatch.ultimatefilemanager.INSTALL_COMPLETE"
    }
}
