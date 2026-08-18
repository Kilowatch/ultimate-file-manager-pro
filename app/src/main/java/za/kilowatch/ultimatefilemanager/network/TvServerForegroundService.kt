package za.kilowatch.ultimatefilemanager.network

import android.app.Notification
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.settings.TvBackgroundServerPreferenceManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Foreground service that keeps [PairingServer] active in the background on Android TV
 * when at least one paired mobile device exists and the feature is enabled in Settings.
 *
 * Security and lifecycle design:
 * - Isolated strictly to TV manifest (android:exported="false").
 * - Only starts if [TvBackgroundServerPreferenceManager.isEnabled] is true and [PairingManager.getAllPairedDevices] is non-empty.
 * - Immediately stops itself when all paired devices are removed or the setting is disabled.
 * - Acquires a partial wake lock and Wi-Fi lock to ensure responsive networking during TV standby.
 */
class TvServerForegroundService : Service() {

    companion object {
        private const val TAG = "TvServerFgService"
        const val NOTIFICATION_ID = 7003
        private const val CHANNEL_ID = "ufm_tv_server_channel"

        /**
         * Safely starts the TV server foreground service.
         * Only executes if the current device is an Android TV / Fire TV, the feature is enabled in Settings,
         * and has at least one paired device configured.
         */
        fun start(context: Context) {
            try {
                val appContext = context.applicationContext
                if (!DeviceUtils.isTvDevice(appContext)) {
                    return
                }
                if (!TvBackgroundServerPreferenceManager.isEnabled(appContext)) {
                    Log.d(TAG, "TV background server disabled in settings — skipping service start")
                    stop(appContext)
                    return
                }
                val pairedDevices = PairingManager.getInstance(appContext).getAllPairedDevices()
                if (pairedDevices.isEmpty()) {
                    Log.d(TAG, "No paired devices found — skipping TV server foreground service start")
                    stop(appContext)
                    return
                }

                val intent = Intent(appContext, TvServerForegroundService::class.java)
                ContextCompat.startForegroundService(appContext, intent)
                Log.d(TAG, "Requested TvServerForegroundService start")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TvServerForegroundService", e)
            }
        }

        /**
         * Safely stops the TV server foreground service.
         */
        fun stop(context: Context) {
            try {
                val appContext = context.applicationContext
                appContext.stopService(Intent(appContext, TvServerForegroundService::class.java))
                Log.d(TAG, "Requested TvServerForegroundService stop")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop TvServerForegroundService", e)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TvServerForegroundService onCreate")

        createNotificationChannel()
        val notification = buildNotification()

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed — stopping TvServerForegroundService", e)
            stopSelf()
            return
        }

        acquireLocks()
        try {
            UfmApplication.instance.ensurePairingServerRunning()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure PairingServer running on create", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isEnabled = TvBackgroundServerPreferenceManager.isEnabled(this)
        val pairedDevices = PairingManager.getInstance(this).getAllPairedDevices()
        if (!isEnabled || pairedDevices.isEmpty() || !DeviceUtils.isTvDevice(this)) {
            Log.d(TAG, "Service disabled, no paired devices, or not TV — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            UfmApplication.instance.ensurePairingServerRunning()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure PairingServer running on start command", e)
        }

        // START_STICKY: If killed under memory pressure, restart so paired mobile devices
        // continue to have access to TV storage.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "TvServerForegroundService onDestroy")
        releaseLocks()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tv_server_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.tv_server_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tv_server_notification_title))
            .setContentText(getString(R.string.tv_server_notification_desc))
            .setSmallIcon(R.drawable.ic_tv)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UFM:TvServerWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "TV Server WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire TV Server WakeLock", e)
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wifiLock == null) {
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockType, "UFM:TvServerWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "TV Server WifiLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire TV Server WifiLock", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "TV Server WakeLock released")
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TV Server WakeLock", e)
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "TV Server WifiLock released")
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TV Server WifiLock", e)
        }
    }
}
