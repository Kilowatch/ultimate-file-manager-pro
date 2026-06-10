package za.kilowatch.ultimatefilemanager.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.TvRemoteActivity

/**
 * Foreground service that keeps the ADB remote process alive and shows
 * an ongoing notification, mirroring the pattern of [BluetoothHidService].
 *
 * Started when an ADB remote connection is established; stopped on explicit
 * disconnect. Uses START_NOT_STICKY — if the OS kills the service, the ADB
 * socket is gone anyway so there is nothing to auto-restart.
 */
class AdbRemoteForegroundService : Service() {

    companion object {
        private const val TAG = "AdbRemoteFgService"
        const val NOTIFICATION_ID = 7002
        private const val CHANNEL_ID = "ufm_adb_remote_channel"
        private const val EXTRA_TV_NAME = "extra_tv_name"

        /**
         * Start the foreground service with the given TV name shown in the notification.
         */
        fun start(tvName: String) {
            try {
                val context = za.kilowatch.ultimatefilemanager.UfmApplication.instance.applicationContext
                val intent = Intent(context, AdbRemoteForegroundService::class.java).apply {
                    putExtra(EXTRA_TV_NAME, tvName)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        /**
         * Stop the foreground service and remove the notification.
         */
        fun stop() {
            try {
                val context = za.kilowatch.ultimatefilemanager.UfmApplication.instance.applicationContext
                context.stopService(Intent(context, AdbRemoteForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop foreground service", e)
            }
        }
    }

    private var tvName: String = ""

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        val notification = buildNotification(getString(R.string.adb_remote_notification_connecting))
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tvName = intent?.getStringExtra(EXTRA_TV_NAME) ?: tvName
        if (tvName.isNotEmpty()) {
            updateNotification(getString(R.string.adb_remote_notification_connected, tvName))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "App task removed — stopping ADB remote service")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.adb_remote_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.adb_remote_notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TvRemoteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tv_remote)
            .setContentTitle(getString(R.string.tv_remote_title))
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
