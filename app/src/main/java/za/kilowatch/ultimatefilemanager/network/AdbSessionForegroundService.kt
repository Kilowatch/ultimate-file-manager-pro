package za.kilowatch.ultimatefilemanager.network

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
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.ui.TerminalActivity

/**
 * Foreground service that maintains an active ADB connection alive when switching
 * between apps or windows, preventing Android from severing the background socket.
 *
 * SEC-POLICY:
 * 1. Requires an ongoing low-priority notification so the user is always aware of the
 *    active privileged ADB shell session running on their device/network.
 * 2. Provides an instant one-tap "Disconnect" action directly on the notification to allow
 *    immediate session revocation without navigating into the app.
 * 3. Tears down the connection cleanly if the app task is dismissed (swiped from Recents).
 * 4. Uses START_NOT_STICKY: if the OS terminates the service, it does not auto-restart with
 *    a broken or dangling socket.
 */
class AdbSessionForegroundService : Service() {

    companion object {
        private const val TAG = "AdbSessionFgService"
        const val NOTIFICATION_ID = 7003
        private const val CHANNEL_ID = "ufm_adb_session_channel"
        const val ACTION_DISCONNECT = "za.kilowatch.ultimatefilemanager.action.ADB_DISCONNECT"
        private const val EXTRA_HOST = "extra_host"
        private const val EXTRA_PORT = "extra_port"

        /**
         * Start the foreground service for an active ADB session.
         */
        fun start(host: String, port: Int) {
            try {
                val context = UfmApplication.instance.applicationContext
                val isForeground = try {
                    androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.STARTED
                    )
                } catch (_: Exception) {
                    true
                }

                val intent = Intent(context, AdbSessionForegroundService::class.java).apply {
                    putExtra(EXTRA_HOST, host)
                    putExtra(EXTRA_PORT, port)
                }

                var started = false
                if (isForeground) {
                    try {
                        context.startService(intent)
                        started = true
                    } catch (e: Exception) {
                        try {
                            ContextCompat.startForegroundService(context, intent)
                            started = true
                        } catch (t: Throwable) {
                            Log.w(TAG, "startForegroundService failed: ${t.message}")
                        }
                    }
                }

                if (!started) {
                    // On Android 14+ (API 34+), starting a connectedDevice FGS while backgrounded is restricted
                    // by OS policy (ForegroundServiceStartNotAllowedException). Post fallback notification directly
                    // so status is visible, and the FGS will be promoted when the app resumes into foreground.
                    try {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        val channel = NotificationChannel(
                            CHANNEL_ID,
                            context.getString(R.string.adb_session_notification_channel_name),
                            NotificationManager.IMPORTANCE_LOW
                        ).apply {
                            description = context.getString(R.string.adb_session_notification_channel_desc)
                            setShowBadge(false)
                            enableVibration(false)
                        }
                        nm.createNotificationChannel(channel)
                        val disconnectIntent = Intent(context, AdbDisconnectReceiver::class.java).apply {
                            action = ACTION_DISCONNECT
                        }
                        val disconnectPendingIntent = PendingIntent.getBroadcast(
                            context,
                            1,
                            disconnectIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val openIntent = PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, TerminalActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_terminal)
                            .setContentTitle(context.getString(R.string.adb_session_notification_title))
                            .setContentText(context.getString(R.string.adb_session_notification_desc, host, port))
                            .setContentIntent(openIntent)
                            .addAction(R.drawable.ic_close, context.getString(R.string.disconnect), disconnectPendingIntent)
                            .setOngoing(true)
                            .setSilent(true)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setCategory(NotificationCompat.CATEGORY_SERVICE)
                            .build()
                        nm.notify(NOTIFICATION_ID, notification)
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallback notification post in background failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ADB session foreground service", e)
            }
        }

        /**
         * Stop the foreground service and remove its notification.
         */
        fun stop() {
            try {
                val context = UfmApplication.instance.applicationContext
                context.stopService(Intent(context, AdbSessionForegroundService::class.java))
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop ADB session foreground service", e)
            }
        }
    }

    private var currentHost: String = "127.0.0.1"
    private var currentPort: Int = 5555

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        promoteToForeground(currentHost, currentPort)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Log.i(TAG, "Disconnect requested via intent action")
            try {
                AdbManager.getInstance().disconnectExplicit()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting AdbManager: ${e.message}")
            }
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra(EXTRA_HOST)
        val port = intent?.getIntExtra(EXTRA_PORT, 5555) ?: 5555
        if (!host.isNullOrBlank()) {
            currentHost = host
            currentPort = port
        }

        // Mandatory foreground transition: satisfy startForegroundService() contract on every start command
        promoteToForeground(currentHost, currentPort)

        return START_NOT_STICKY
    }

    private fun promoteToForeground(host: String, port: Int) {
        val notification = buildNotification(host, port)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed for ADB session service: ${e.message}", e)
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "App task removed — disconnecting ADB session")
        try {
            AdbManager.getInstance().disconnectExplicit()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting on task removed: ${e.message}")
        }
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification Helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.adb_session_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.adb_session_notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(host: String, port: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, AdbDisconnectReceiver::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(getString(R.string.adb_session_notification_title))
            .setContentText(getString(R.string.adb_session_notification_desc, host, port))
            .setContentIntent(openIntent)
            .addAction(
                R.drawable.ic_close,
                getString(R.string.disconnect),
                disconnectPendingIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(host: String, port: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(host, port))
    }
}

/**
 * BroadcastReceiver triggered by the ADB Notification "Disconnect" action button.
 * Android 12+ (API 31+) restricts starting background services from notification actions;
 * firing a broadcast ensures immediate, guaranteed delivery even when the app is in the background.
 */
class AdbDisconnectReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("AdbDisconnectReceiver", "Disconnect requested via notification action")
        try {
            AdbManager.getInstance().disconnectExplicit()
        } catch (e: Exception) {
            Log.w("AdbDisconnectReceiver", "Error disconnecting AdbManager: ${e.message}")
        }
        AdbSessionForegroundService.stop()
    }
}
