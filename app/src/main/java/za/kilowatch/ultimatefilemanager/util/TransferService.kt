package za.kilowatch.ultimatefilemanager.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import za.kilowatch.ultimatefilemanager.R

/**
 * Minimal foreground service that keeps the process alive during file transfers.
 *
 * Android kills TCP connections (SMB/FTP sockets) a few seconds after
 * screen-off unless the app has a visible foreground service.  This service
 * shows a persistent notification ("Transferring files…") and is started/stopped
 * around every paste operation.
 *
 * The service also holds a [PowerManager.PARTIAL_WAKE_LOCK] and a
 * [WifiManager.WifiLock] for the full duration of the transfer.  A foreground
 * service alone only prevents process-death — without a PARTIAL_WAKE_LOCK the
 * CPU can still be suspended by the power manager when the screen turns off,
 * which interrupts active socket I/O on NAS transfers.
 *
 * No actual I/O work is done here — the transfer runs in the Activity's coroutine.
 */
class TransferService : Service() {

    companion object {
        private const val TAG = "TransferService"
        private const val CHANNEL_ID = "ufm_transfer_channel"
        private const val NOTIFICATION_ID = 9901

        /** 2-hour safety cap — any transfer running longer will have its lock released. */
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"

        fun start(context: Context, title: String? = null, text: String? = null) {
            val intent = Intent(context, TransferService::class.java).apply {
                if (title != null) putExtra(EXTRA_TITLE, title)
                if (text != null) putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.ufm_file_transfer)
        val text  = intent?.getStringExtra(EXTRA_TEXT)  ?: getString(R.string.transferring_files_1)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_network)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireLocks()

        // START_REDELIVER_INTENT: if the process is killed mid-transfer Android will
        // restart the service and re-deliver the last intent, keeping the notification
        // and locks alive again until the Activity's finally-block calls stop().
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    // ── Lock management ───────────────────────────────────────────────────────

    private fun acquireLocks() {
        // PARTIAL_WAKE_LOCK: keeps the CPU running even when the screen is off.
        // This is the critical piece that prevents TCP socket interruption on NAS copies.
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UFM:TransferService").also {
                it.acquire(WAKE_LOCK_TIMEOUT_MS)
                Log.d(TAG, "PARTIAL_WAKE_LOCK acquired (timeout ${WAKE_LOCK_TIMEOUT_MS / 1000}s)")
            }
        }

        // WifiLock: prevents the Wi-Fi radio from entering low-power mode,
        // which would throttle or drop active NAS socket connections.
        // Held unconditionally across all share types (SMB, FTP, NFS, SSH, WebDAV, cloud, etc.)
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "UFM:TransferService").also {
                it.acquire()
                Log.d(TAG, "WifiLock acquired (mode=$mode)")
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "PARTIAL_WAKE_LOCK released")
            }
            wakeLock = null
        }
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WifiLock released")
            }
            wifiLock = null
        }
    }

    // ── Notification channel ──────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.file_transfer),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.keeps_file_transfers_alive_during_screenoff)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
