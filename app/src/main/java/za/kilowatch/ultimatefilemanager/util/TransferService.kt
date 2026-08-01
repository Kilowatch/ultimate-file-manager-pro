package za.kilowatch.ultimatefilemanager.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
 * No actual work is done here — the transfer runs in the Activity's coroutine.
 */
class TransferService : Service() {

    companion object {
        private const val CHANNEL_ID = "ufm_transfer_channel"
        private const val NOTIFICATION_ID = 9901

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
        return START_NOT_STICKY
    }

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
