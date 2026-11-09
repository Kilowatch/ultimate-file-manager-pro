package za.kilowatch.ultimatefilemanager.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import za.kilowatch.ultimatefilemanager.R

/**
 * Foreground Service for active downloads.
 * Holds partial wake lock and Wi-Fi lock to ensure transfers complete without interruption.
 */
class DownloadService : Service(), DownloadTransferManager.TransferUpdateListener {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createNotificationChannel()

        acquireLocks()
        DownloadTransferManager.addListener(this)

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        DownloadTransferManager.removeListener(this)
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTransfersChanged() {
        updateNotification()
    }

    override fun onTransferProgress(item: DownloadTransferManager.TransferItem) {
        updateNotification()
    }

    override fun onPreflightFailed(
        item: DownloadTransferManager.TransferItem,
        result: StoragePreflightValidator.ValidationResult
    ) {
        // Handled by UI
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UFM:DownloadWakeLock")?.apply {
                acquire(12 * 60 * 60 * 1000L) // 12 hours max safety timeout
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "UFM:DownloadWifiLock")?.apply {
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
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

    private fun updateNotification() {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    companion object {
        const val CHANNEL_ID = "ufm_downloads"
        const val NOTIFICATION_ID = 4099
    }
}
