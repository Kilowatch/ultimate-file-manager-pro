package za.kilowatch.ultimatefilemanager.util

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
import android.util.Log
import androidx.core.app.NotificationCompat
import za.kilowatch.ultimatefilemanager.R

/**
 * Foreground service that keeps the process and CPU alive during file transfers.
 *
 * Android kills TCP connections (SMB/FTP sockets) a few seconds after screen-off
 * unless the app has a visible foreground service. This service shows a persistent
 * notification ("Transferring files…") with a **Cancel** action and is started/stopped
 * exclusively by [TransferManager] (the single transfer holder — no Activity or engine
 * ever starts/stops it directly).
 *
 * The service holds a [PowerManager.PARTIAL_WAKE_LOCK] and a [WifiManager.WifiLock] for
 * the full duration of the transfer: a foreground service alone only prevents
 * process-death — without a PARTIAL_WAKE_LOCK the CPU can still be suspended when the
 * screen turns off, interrupting active socket I/O on NAS transfers.
 *
 * The actual I/O runs in [TransferManager]'s application-scoped coroutine. This service
 * only:
 *  - keeps the process/CPU/Wi-Fi alive,
 *  - hosts the progress + retry notification,
 *  - hosts the Cancel action (routed to [TransferManager.cancelAll]).
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

        /** Action on the notification's Cancel button. */
        const val ACTION_CANCEL = "za.kilowatch.ufm.action.CANCEL_TRANSFER"

        /** Live service instance, set in onStartCommand / cleared in onDestroy. */
        @Volatile
        private var activeInstance: TransferService? = null

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

        /**
         * Re-publish the live notification. No-op if the service is not currently up.
         * Safe to call from any thread (the service posts to the main looper).
         */
        fun update(context: Context, title: String, text: String, indeterminate: Boolean, percent: Int?) {
            activeInstance?.publish(title, text, indeterminate, percent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeInstance = this

        // Notification Cancel button → cancel the active transfer(s). The holder stops
        // the service once no transfers remain, so nothing else to do here.
        if (intent?.action == ACTION_CANCEL) {
            TransferManager.cancelAll()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.ufm_file_transfer)
        val text  = intent?.getStringExtra(EXTRA_TEXT)  ?: getString(R.string.transferring_files_1)
        publish(title, text, indeterminate = true, percent = null)
        acquireLocks()

        // START_REDELIVER_INTENT: if the process is killed mid-transfer Android restarts
        // the service and re-delivers the last intent. There is no surviving transfer in
        // that case (the copy job lived in the dead process), so showing a zombie
        // notification would be a lie — stop immediately instead (FR-08: no stale state).
        // A genuine fresh start always arrives AFTER the holder has registered the active
        // transfer, so this guard cannot misfire on a real transfer.
        if (!TransferManager.isActiveTransfers()) {
            Log.w(TAG, "No active transfer on start/redelivery — stopping to avoid zombie notification")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        activeInstance = null
        releaseLocks()
        super.onDestroy()
    }

    // ── Notification (build + foreground) ──────────────────────────────────────

    private fun publish(title: String, text: String, indeterminate: Boolean, percent: Int?) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_network)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (percent != null && !indeterminate) {
                    setProgress(100, percent.coerceIn(0, 100), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .addAction(cancelAction())
            .build()

        mainHandler.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (_: Exception) {
                // Service already stopping / app in background — ignore.
            }
        }
    }

    private fun cancelAction(): NotificationCompat.Action {
        val cancelIntent = Intent(this, TransferService::class.java).setAction(ACTION_CANCEL)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getService(this, 0, cancelIntent, flags)
        return NotificationCompat.Action.Builder(
            R.drawable.ic_close,
            getString(R.string.cancel),
            pi
        ).build()
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
