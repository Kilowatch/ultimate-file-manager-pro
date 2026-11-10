package za.kilowatch.ultimatefilemanager.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import za.kilowatch.ultimatefilemanager.R

/**
 * Foreground Service for active downloads.
 * Holds partial wake lock and Wi-Fi lock to ensure transfers complete without interruption.
 */
class DownloadService : Service(), DownloadTransferManager.TransferUpdateListener {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var notificationManager: NotificationManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotificationUpdateTime = 0L

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createNotificationChannel()

        acquireLocks()
        DownloadTransferManager.addListener(this)

        safeStartForeground(buildNotification())

        val hasActive = DownloadTransferManager.getAllTransfers().any {
            it.state == DownloadTransferManager.TransferState.DOWNLOADING
        }
        if (!hasActive) {
            stopForegroundAndSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasActive = DownloadTransferManager.getAllTransfers().any {
            it.state == DownloadTransferManager.TransferState.DOWNLOADING
        }
        if (!hasActive) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        updateNotification(force = true)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(TAG, "DownloadService reached system timeout for fgsType $fgsType (startId $startId) — stopping service")
        // Demote from foreground immediately to prevent ForegroundServiceDidNotStopInTimeException on Android 15+
        stopForegroundCompat()
        releaseLocks()
        DownloadTransferManager.onServiceTimeout()
        showTimeoutNotification()
        stopSelf()
    }

    override fun onDestroy() {
        DownloadTransferManager.removeListener(this)
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTransfersChanged() {
        val hasActive = DownloadTransferManager.getAllTransfers().any {
            it.state == DownloadTransferManager.TransferState.DOWNLOADING
        }
        if (!hasActive) {
            stopForegroundAndSelf()
            return
        }
        updateNotification(force = true)
    }

    override fun onTransferProgress(item: DownloadTransferManager.TransferItem) {
        updateNotification(force = false)
    }

    override fun onPreflightFailed(
        item: DownloadTransferManager.TransferItem,
        result: StoragePreflightValidator.ValidationResult
    ) {
        // Handled by UI
    }

    private fun safeStartForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground with FOREGROUND_SERVICE_TYPE_DATA_SYNC failed: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (t: Throwable) {
                Log.e(TAG, "Fallback startForeground failed: ${t.message}")
                stopForegroundAndSelf()
            }
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForegroundCompat failed: ${e.message}")
        }
    }

    private fun stopForegroundAndSelf() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            performStop()
        } else {
            mainHandler.post { performStop() }
        }
    }

    private fun performStop() {
        stopForegroundCompat()
        releaseLocks()
        stopSelf()
    }

    private fun showTimeoutNotification() {
        try {
            val contentIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, DownloadManagerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(getString(R.string.download_notif_channel_name))
                .setContentText(getString(R.string.download_timeout_stopped))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            notificationManager?.notify(TIMEOUT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show download timeout notification: ${e.message}")
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UFM:DownloadWakeLock")?.apply {
                acquire(6 * 60 * 60 * 1000L) // 6 hours max safety timeout (matches Android 15 dataSync limit)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wakeLock: ${e.message}")
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "UFM:DownloadWifiLock")?.apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_notif_channel_desc)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val transfers = DownloadTransferManager.getAllTransfers()
        val activeTransfers = transfers.filter { it.state == DownloadTransferManager.TransferState.DOWNLOADING }

        val totalSpeed = activeTransfers.sumOf { it.speedBytesPerSec }
        val speedStr = formatSpeed(totalSpeed)

        val title = if (activeTransfers.size == 1) {
            activeTransfers.first().title
        } else {
            "${activeTransfers.size} Active Downloads"
        }

        val avgProgress = if (activeTransfers.isNotEmpty()) {
            activeTransfers.map { it.progressPercent }.average().toInt()
        } else 0

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DownloadManagerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText("$speedStr • $avgProgress%")
            .setProgress(100, avgProgress, false)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationUpdateTime < 500L) {
            return
        }
        lastNotificationUpdateTime = now
        try {
            notificationManager?.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "ufm_downloads"
        const val NOTIFICATION_ID = 4099
        const val TIMEOUT_NOTIFICATION_ID = 4100
    }
}
