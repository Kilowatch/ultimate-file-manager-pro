package za.kilowatch.ultimatefilemanager.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.widget.FtpSftpWidgetProvider
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.*

/**
 * Foreground service that manages the FTP, SFTP, and DLNA server lifecycles.
 *
 * Shows a persistent notification with the server address(es) when active.
 * Stops servers when the app's task is removed (swipe-away) via [onTaskRemoved].
 *
 * Security notes:
 * - Anonymous login is NOT supported. All access requires a named profile.
 * - H-4: All shared-state flags are @Volatile to prevent data races between
 *   the IO threads that start servers and the Main thread that reads status.
 */
class FileServerService : Service() {

    companion object {
        private const val TAG = "FileServerService"
        private const val CHANNEL_ID = "ufm_file_server_channel"
        private const val NOTIFICATION_ID = 9910
        private const val PREFS_NAME = "file_server_prefs"
        private const val KEY_FTP_ENABLED = "ftp_enabled"
        private const val KEY_SFTP_ENABLED = "sftp_enabled"
        private const val KEY_DLNA_SERVER_ENABLED = "dlna_server_enabled"
        private const val KEY_DLNA_RENDERER_ENABLED = "dlna_renderer_enabled"

        private const val ACTION_START_FTP = "action_start_ftp"
        private const val ACTION_START_DLNA = "action_start_dlna"
        private const val ACTION_STOP_DLNA = "action_stop_dlna"
        private const val ACTION_START_RENDERER = "action_start_renderer"
        private const val ACTION_STOP_RENDERER = "action_stop_renderer"
        private const val ACTION_START_SFTP = "action_start_sftp"
        private const val ACTION_STOP_FTP = "action_stop_ftp"
        private const val ACTION_STOP_SFTP = "action_stop_sftp"
        private const val ACTION_STOP_ALL = "action_stop_all"
        private const val ACTION_REFRESH = "action_refresh"

        /** Observable server state for the UI. */
        private val _serverState = MutableLiveData(ServerState())
        val serverState: LiveData<ServerState> = _serverState

        // H-4: @Volatile prevents torn reads across Main/IO threads.
        @Volatile private var ftpServer: UfmFtpServer? = null
        @Volatile private var sftpServer: UfmSftpServer? = null
        @Volatile private var isFtpStarting = false
        @Volatile private var isSftpStarting = false
        @Volatile private var dlnaServer: UfmDlnaServer? = null
        @Volatile private var dlnaRenderer: DlnaRendererServer? = null
        @Volatile private var isDlnaStarting = false
        @Volatile private var isRendererStarting = false

        fun startFtp(context: Context) {
            val intent = Intent(context, FileServerService::class.java).apply {
                action = ACTION_START_FTP
            }
            startServiceSafely(context, intent)
        }

        fun startSftp(context: Context) {
            val intent = Intent(context, FileServerService::class.java).apply {
                action = ACTION_START_SFTP
            }
            startServiceSafely(context, intent)
        }

        fun stopFtp(context: Context) {
            val intent = Intent(context, FileServerService::class.java).apply {
                action = ACTION_STOP_FTP
            }
            startServiceSafely(context, intent)
        }

        fun stopSftp(context: Context) {
            val intent = Intent(context, FileServerService::class.java).apply {
                action = ACTION_STOP_SFTP
            }
            startServiceSafely(context, intent)
        }

        fun stopAll(context: Context) {
            val intent = Intent(context, FileServerService::class.java).apply {
                action = ACTION_STOP_ALL
            }
            try {
                context.startService(intent)
            } catch (_: Exception) { }
        }

        fun refreshNotification(context: Context) {
            val intent = Intent(context, FileServerService::class.java).apply {
                action = ACTION_REFRESH
            }
            try { context.startService(intent) } catch (_: Exception) { }
        }

        fun startDlna(context: Context) {
            val intent = Intent(context, FileServerService::class.java).apply { action = ACTION_START_DLNA }
            startServiceSafely(context, intent)
        }

        fun stopDlna(context: Context) {
            val intent = Intent(context, FileServerService::class.java).apply { action = ACTION_STOP_DLNA }
            startServiceSafely(context, intent)
        }

        fun startRenderer(context: Context) {
            val intent = Intent(context, FileServerService::class.java).apply { action = ACTION_START_RENDERER }
            startServiceSafely(context, intent)
        }

        fun stopRenderer(context: Context) {
            val intent = Intent(context, FileServerService::class.java).apply { action = ACTION_STOP_RENDERER }
            startServiceSafely(context, intent)
        }

        fun isDlnaServerEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DLNA_SERVER_ENABLED, false)
        }

        fun isDlnaRendererEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DLNA_RENDERER_ENABLED, false)
        }

        fun setDlnaServerEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DLNA_SERVER_ENABLED, enabled).apply()
        }

        fun setDlnaRendererEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DLNA_RENDERER_ENABLED, enabled).apply()
        }

        /** Persists the FTP/SFTP enabled preference. */
        fun setFtpEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FTP_ENABLED, enabled).apply()
        }

        fun setSftpEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SFTP_ENABLED, enabled).apply()
        }

        fun isFtpEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FTP_ENABLED, false)
        }

        fun isSftpEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SFTP_ENABLED, false)
        }

        private fun startServiceSafely(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FileServerService", e)
            }
        }
    }


    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8.0+ requires that any service started via startForegroundService()
        // MUST call startForeground() within seconds, even if it is about to stop.
        val ftpRunning = ftpServer?.isRunning == true
        val sftpRunning = sftpServer?.isRunning == true
        showForegroundNotification(ftpRunning || isFtpStarting, sftpRunning || isSftpStarting)

        when (intent?.action) {
            ACTION_START_FTP -> startFtpServer()
            ACTION_START_SFTP -> startSftpServer()
            ACTION_START_DLNA -> startDlnaServer()
            ACTION_START_RENDERER -> startRendererServer()
            ACTION_STOP_FTP -> { stopFtpServer(); setFtpEnabled(this, false) }
            ACTION_STOP_SFTP -> { stopSftpServer(); setSftpEnabled(this, false) }
            ACTION_STOP_DLNA -> { stopDlnaServer(); setDlnaServerEnabled(this, false) }
            ACTION_STOP_RENDERER -> { stopRendererServer(); setDlnaRendererEnabled(this, false) }
            ACTION_STOP_ALL -> {
                stopFtpServer()
                stopSftpServer()
                stopDlnaServer()
                stopRendererServer()
                setFtpEnabled(this, false)
                setSftpEnabled(this, false)
                setDlnaServerEnabled(this, false)
                setDlnaRendererEnabled(this, false)
                FtpSftpWidgetProvider.updateAllWidgets(this)
                updateState(false, false, false, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> { /* already called showForegroundNotification above */ }
        }

        // Re-check status after actions
        val fRunning = ftpServer?.isRunning == true
        val sRunning = sftpServer?.isRunning == true
        val dlnaRunning = dlnaServer?.isRunning == true
        val rendererRunning = dlnaRenderer?.isRunning == true

        if (!fRunning && !sRunning && !dlnaRunning && !rendererRunning && !isFtpStarting && !isSftpStarting && !isDlnaStarting && !isRendererStarting) {
            updateState(false, false, false, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Update notification with new status
        showForegroundNotification(fRunning || isFtpStarting, sRunning || isSftpStarting, dlnaRunning || isDlnaStarting, rendererRunning || isRendererStarting)
        updateState(fRunning, sRunning, dlnaRunning, rendererRunning)

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task removed — stopping all servers")
        // Demote from foreground FIRST — required by Android 15 to avoid
        // ForegroundServiceDidNotStopInTimeException.
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Dispatch blocking server.stop() calls off the Main thread so we
        // don't hold the main thread long enough to hit the system timeout.
        serviceScope.launch(Dispatchers.IO) {
            stopFtpServer()
            stopSftpServer()
            stopDlnaServer()
            stopRendererServer()
        }
        setFtpEnabled(this, false)
        setSftpEnabled(this, false)
        setDlnaServerEnabled(this, false)
        setDlnaRendererEnabled(this, false)
        updateState(false, false, false, false)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Demote from foreground before any cleanup — prevents
        // ForegroundServiceDidNotStopInTimeException on Android 15.
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Server shutdown is blocking I/O — run on IO, then cancel the job.
        // We use a fresh scope (not serviceScope) because serviceJob is
        // cancelled right after, which would race with the coroutine launch.
        CoroutineScope(Dispatchers.IO).launch {
            stopFtpServer()
            stopSftpServer()
            stopDlnaServer()
            stopRendererServer()
        }
        serviceJob.cancel()
        updateState(false, false, false, false)
    }

    private fun startFtpServer() {
        if (ftpServer?.isRunning == true || isFtpStarting) return
        isFtpStarting = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                val ip = getDeviceIpAddress()
                val server = UfmFtpServer(this@FileServerService)
                server.start(bindAddress = ip)
                ftpServer = server
                setFtpEnabled(this@FileServerService, true)
                
                withContext(Dispatchers.Main) {
                    isFtpStarting = false
                    val sRunning = sftpServer?.isRunning == true
                    showForegroundNotification(true, sRunning || isSftpStarting)
                    updateState(true, sRunning)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FTP server", e)
                withContext(Dispatchers.Main) {
                    isFtpStarting = false
                    setFtpEnabled(this@FileServerService, false)
                    FtpSftpWidgetProvider.updateAllWidgets(this@FileServerService)
                    checkServiceCleanup()
                }
            }
        }
    }

    private fun checkServiceCleanup() {
        val fRunning = ftpServer?.isRunning == true
        val sRunning = sftpServer?.isRunning == true
        val dRunning = dlnaServer?.isRunning == true
        val rRunning = dlnaRenderer?.isRunning == true
        if (!fRunning && !sRunning && !dRunning && !rRunning && !isFtpStarting && !isSftpStarting && !isDlnaStarting && !isRendererStarting) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopFtpServer() {
        isFtpStarting = false
        ftpServer?.stop()
        ftpServer = null
    }

    private fun startSftpServer() {
        if (sftpServer?.isRunning == true || isSftpStarting) return
        isSftpStarting = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                val ip = getDeviceIpAddress()
                val server = UfmSftpServer(this@FileServerService)
                server.start(bindAddress = ip)
                sftpServer = server
                setSftpEnabled(this@FileServerService, true)

                withContext(Dispatchers.Main) {
                    isSftpStarting = false
                    val fRunning = ftpServer?.isRunning == true
                    showForegroundNotification(fRunning || isFtpStarting, true)
                    updateState(fRunning, true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SFTP server", e)
                withContext(Dispatchers.Main) {
                    isSftpStarting = false
                    setSftpEnabled(this@FileServerService, false)
                    FtpSftpWidgetProvider.updateAllWidgets(this@FileServerService)
                    checkServiceCleanup()
                }
            }
        }
    }

    private fun stopSftpServer() {
        isSftpStarting = false
        sftpServer?.stop()
        sftpServer = null
    }

    private fun startDlnaServer() {
        if (dlnaServer?.isRunning == true || isDlnaStarting) return
        isDlnaStarting = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                val ip = getDeviceIpAddress()
                val server = UfmDlnaServer(this@FileServerService)
                server.start(bindAddress = ip)
                dlnaServer = server
                setDlnaServerEnabled(this@FileServerService, true)
                try {
                    withContext(Dispatchers.Main) {
                        isDlnaStarting = false
                        refreshNotificationAfterChange()
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    isDlnaStarting = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DLNA server", e)
                isDlnaStarting = false
                setDlnaServerEnabled(this@FileServerService, false)
                FtpSftpWidgetProvider.updateAllWidgets(this@FileServerService)
                checkServiceCleanup()
            }
        }
    }

    private fun stopDlnaServer() {
        isDlnaStarting = false
        val server = dlnaServer
        dlnaServer = null
        // SSDP bye-bye sends UDP multicast — must run off main thread
        serviceScope.launch(Dispatchers.IO) {
            server?.stop()
        }
    }

    private fun startRendererServer() {
        if (dlnaRenderer?.isRunning == true || isRendererStarting) return
        isRendererStarting = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                val ip = getDeviceIpAddress()
                val renderer = DlnaRendererServer(this@FileServerService)
                renderer.start(bindAddress = ip)
                dlnaRenderer = renderer
                setDlnaRendererEnabled(this@FileServerService, true)
                try {
                    withContext(Dispatchers.Main) {
                        isRendererStarting = false
                        refreshNotificationAfterChange()
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // UI update cancelled, but server is running
                    isRendererStarting = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DLNA renderer", e)
                isRendererStarting = false
                setDlnaRendererEnabled(this@FileServerService, false)
                FtpSftpWidgetProvider.updateAllWidgets(this@FileServerService)
                checkServiceCleanup()
            }
        }
    }

    private fun stopRendererServer() {
        isRendererStarting = false
        val server = dlnaRenderer
        dlnaRenderer = null
        // SSDP bye-bye sends UDP multicast — must run off main thread
        serviceScope.launch(Dispatchers.IO) {
            server?.stop()
        }
    }

    private fun showForegroundNotification(ftpRunning: Boolean, sftpRunning: Boolean, dlnaRunning: Boolean = false, rendererRunning: Boolean = false) {
        val ip = getDeviceIpAddress()
        val lines = mutableListOf<String>()
        if (ftpRunning) lines.add("FTP: $ip:${UfmFtpServer.PORT} ⚠️ Unencrypted")
        if (sftpRunning) lines.add("SFTP: $ip:${UfmSftpServer.PORT} 🔒 Encrypted")
        if (dlnaRunning) {
            val dlnaPort = DlnaServerPrefs.getDlnaServerPort(this)
            lines.add("DLNA: $ip:$dlnaPort 📡 Media streaming")
        }
        if (rendererRunning) lines.add("DLNA Renderer: $ip 📡")

        val title = when {
            (ftpRunning || sftpRunning) && (dlnaRunning || rendererRunning) -> getString(R.string.file_server_title)
            ftpRunning && sftpRunning -> getString(R.string.file_server_ftp_sftp_running)
            ftpRunning -> getString(R.string.file_server_ftp_running)
            sftpRunning -> getString(R.string.file_server_sftp_running)
            dlnaRunning || rendererRunning -> getString(R.string.dlna_server_title)
            else -> getString(R.string.file_server_title)
        }

        val isMobile = !DeviceUtils.isTvDevice(this)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(lines.joinToString(" • "))
            .setSmallIcon(R.drawable.ic_file_server)
            .setOngoing(true)
            .setSilent(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))

        if (isMobile) {
            // Tap to open ServerHostActivity
            val contentIntent = Intent(this, ServerHostActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            notificationBuilder.setContentIntent(
                PendingIntent.getActivity(
                    this, 0, contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            // "Stop" action button
            val stopIntent = Intent(this, FileServerService::class.java).apply {
                action = ACTION_STOP_ALL
            }
            notificationBuilder.addAction(
                R.drawable.ic_close,
                getString(R.string.file_server_notification_stop),
                PendingIntent.getService(
                    this, 1, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        // Keep the widget in sync with the current server state
        FtpSftpWidgetProvider.updateAllWidgets(this)

        val notification = notificationBuilder.build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed — stopping service gracefully", e)
            stopSelf()
        }
    }

    private fun refreshNotificationAfterChange() {
        val fRunning = ftpServer?.isRunning == true
        val sRunning = sftpServer?.isRunning == true
        val dRunning = dlnaServer?.isRunning == true
        val rRunning = dlnaRenderer?.isRunning == true
        showForegroundNotification(fRunning || isFtpStarting, sRunning || isSftpStarting, dRunning || isDlnaStarting, rRunning || isRendererStarting)
        updateState(fRunning, sRunning, dRunning, rRunning)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.file_server_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.file_server_notification_desc)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun updateState(ftpRunning: Boolean, sftpRunning: Boolean, dlnaRunning: Boolean = false, rendererRunning: Boolean = false) {
        val ip = getDeviceIpAddress()
        val dlnaPort = if (dlnaRunning) DlnaServerPrefs.getDlnaServerPort(this) else 0
        _serverState.postValue(ServerState(
            ftpRunning = ftpRunning,
            sftpRunning = sftpRunning,
            dlnaRunning = dlnaRunning,
            rendererRunning = rendererRunning,
            ipAddress = ip,
            dlnaPort = dlnaPort
        ))
    }

    fun getDeviceIpAddress(): String {
        val isTv = DeviceUtils.isTvDevice(this)

        // On TVs, skip WifiManager entirely — TVs are almost always on Ethernet
        // and WifiManager.connectionInfo.ipAddress returns 0 for wired connections,
        // which causes servers to advertise http://0.0.0.0:xxxx as their LOCATION.
        if (!isTv) {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val wifiInfo = wifiManager.connectionInfo
                val ip = wifiInfo.ipAddress
                if (ip != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ip and 0xff,
                        ip shr 8 and 0xff,
                        ip shr 16 and 0xff,
                        ip shr 24 and 0xff
                    )
                }
            } catch (_: Exception) { }
        }

        // Enumerate network interfaces — works for both Ethernet (TV) and WiFi.
        // On TV, prefer eth0 first (most common wired interface name), then wlan0,
        // then any other non-loopback IPv4 address.
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val candidates = mutableListOf<String>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        // Prioritise known TV Ethernet interface names
                        when {
                            iface.name.startsWith("eth") -> candidates.add(0, ip)
                            iface.name.startsWith("wlan") -> candidates.add(ip)
                            else -> candidates.add(ip)
                        }
                    }
                }
            }
            if (candidates.isNotEmpty()) return candidates.first()
        } catch (_: Exception) { }

        return "0.0.0.0"
    }
}

/**
 * Observable state of the file servers.
 */
data class ServerState(
    val ftpRunning: Boolean = false,
    val sftpRunning: Boolean = false,
    val dlnaRunning: Boolean = false,
    val rendererRunning: Boolean = false,
    val ipAddress: String = "0.0.0.0",
    val dlnaPort: Int = 0
) {
    val ftpAddress: String get() = "$ipAddress:${UfmFtpServer.PORT}"
    val sftpAddress: String get() = "$ipAddress:${UfmSftpServer.PORT}"
    val dlnaAddress: String get() = if (dlnaRunning && dlnaPort > 0) "$ipAddress:$dlnaPort" else "N/A"
    val anyRunning: Boolean get() = ftpRunning || sftpRunning || dlnaRunning || rendererRunning
}
