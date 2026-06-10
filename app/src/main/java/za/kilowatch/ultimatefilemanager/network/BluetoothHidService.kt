package za.kilowatch.ultimatefilemanager.network

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.R

/**
 * Foreground service that owns the BluetoothHidDevice profile registration.
 *
 * By running as a foreground service with type="connectedDevice", Android treats
 * this process as "perceptible" and will not kill it under normal battery
 * optimization — solving connection drops on MiBox and similar AOSP TV devices.
 *
 * Lifecycle:
 *  - Started by TvRemoteActivity when BT permissions are granted and BT is on.
 *  - Stopped explicitly when the user presses Back in TvRemoteActivity.
 *  - On STATE_DISCONNECTED → schedules auto-reconnect (up to 3 attempts, 2 s apart).
 *  - On HID app registered → auto-connects to the saved default TV (if any).
 */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.P)
class BluetoothHidService : Service() {

    companion object {
        private const val TAG = "BluetoothHidService"
        const val NOTIFICATION_ID = 7001
        private const val CHANNEL_ID = "ufm_bt_remote_channel"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, BluetoothHidService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BluetoothHidService::class.java))
        }
    }

    private var btManager: BluetoothRemoteManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        val notification = buildNotification(getString(R.string.bt_remote_notification_starting))
        // Android 14+ (API 34+) requires the foreground service type to be passed explicitly
        // to startForeground() when the service declares foregroundServiceType in the manifest.
        // On Android 16 (API 36), omitting the type throws MissingForegroundServiceTypeException.
        // ServiceCompat handles the version check gracefully.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        btManager = BluetoothRemoteManager.getInstance(this)
        btManager?.initialize()
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if killed by OS, restart the service automatically
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed — cleaning up HID registration")
        reconnectJob?.cancel()
        scope.cancel()
        btManager?.cleanup()
        btManager = null
    }

    /**
     * Called when the user removes the app from the recents screen (app close).
     * Stop the service so onDestroy() runs and the BT connection is cleanly closed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "App task removed — stopping BT remote service and disconnecting")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Monitoring ───────────────────────────────────────────────────────────

    private fun startMonitoring() {
        // Observe connection state → update notification + trigger reconnect
        scope.launch {
            btManager?.connectionState?.collect { state ->
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Don't reset reconnectAttempts immediately — the TV may
                        // drop the connection within seconds (seen on onn/Mecool).
                        // Only reset after the connection is stable for 3s.
                        reconnectJob?.cancel()
                        val stableResetJob = scope.launch {
                            delay(3_000)
                            if (btManager?.connectionState?.value == BluetoothProfile.STATE_CONNECTED) {
                                Log.d(TAG, "[SERVICE] Connection stable for 3s — resetting reconnect counter")
                                reconnectAttempts = 0
                            }
                        }
                        // Store as reconnectJob so it gets cancelled if state changes
                        reconnectJob = stableResetJob
                        val name = btManager?.connectedDeviceName?.value
                            ?: getString(R.string.connected)
                        updateNotification(
                            getString(R.string.bt_remote_notification_connected, name)
                        )
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        updateNotification(getString(R.string.bt_remote_notification_connecting))
                        // Timeout is now managed by BluetoothRemoteManager.connectToDevice()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "[SERVICE] STATE_DISCONNECTED observed, manualDisconnect=${btManager?.manualDisconnect}, wasVCU=${btManager?.wasVirtualCableUnplugged}")
                        updateNotification(getString(R.string.bt_remote_notification_waiting))
                        scheduleReconnect()
                    }
                }
            }
        }

        // Observe app registration → auto-connect to default TV once registered
        scope.launch {
            btManager?.appRegistrationState?.collect { registered ->
                if (registered) {
                    Log.d(TAG, "HID app registered — attempting auto-connect to saved TV")
                    btManager?.autoConnectToSavedTv()
                }
            }
        }

        // Observe device name → keep notification current when name is resolved
        scope.launch {
            btManager?.connectedDeviceName?.collect { name ->
                if (!name.isNullOrEmpty() &&
                    btManager?.connectionState?.value == BluetoothProfile.STATE_CONNECTED
                ) {
                    updateNotification(
                        getString(R.string.bt_remote_notification_connected, name)
                    )
                }
            }
        }
    }

    // ── Auto-Reconnect ───────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        Log.d(TAG, "[scheduleReconnect] called — manualDisconnect=${btManager?.manualDisconnect}, attempts=$reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
        if (btManager?.manualDisconnect == true) {
            Log.d(TAG, "[scheduleReconnect] SKIPPED — manualDisconnect is true")
            return
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.d(TAG, "[scheduleReconnect] SKIPPED — max attempts reached")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "[scheduleReconnect] waiting ${RECONNECT_DELAY_MS}ms before attempt...")
            delay(RECONNECT_DELAY_MS)
            val currentState = btManager?.connectionState?.value
            Log.d(TAG, "[scheduleReconnect] after delay: state=$currentState, manualDisconnect=${btManager?.manualDisconnect}")
            if (currentState == BluetoothProfile.STATE_DISCONNECTED) {
                reconnectAttempts++
                Log.d(TAG, "[scheduleReconnect] Auto-reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
                val reconnected = btManager?.autoConnectToSavedTv() ?: false
                if (!reconnected) {
                    Log.d(TAG, "[scheduleReconnect] No saved TV found for auto-reconnect")
                }
            } else {
                Log.d(TAG, "[scheduleReconnect] SKIPPED — state is no longer DISCONNECTED (state=$currentState)")
            }
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bt_remote_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.bt_remote_notification_channel_desc)
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
