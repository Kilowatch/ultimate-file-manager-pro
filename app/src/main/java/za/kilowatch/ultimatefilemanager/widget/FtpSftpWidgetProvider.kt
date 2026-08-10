package za.kilowatch.ultimatefilemanager.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.server.FileServerService
import za.kilowatch.ultimatefilemanager.server.ServerHostActivity

class FtpSftpWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TOGGLE_FTP =
            "za.kilowatch.ultimatefilemanager.widget.ACTION_WIDGET_TOGGLE_FTP"
        const val ACTION_WIDGET_TOGGLE_SFTP =
            "za.kilowatch.ultimatefilemanager.widget.ACTION_WIDGET_TOGGLE_SFTP"

        fun updateAllWidgets(context: Context) {
            // AppWidgetManager.getInstance() returns null on devices with no widget
            // host (e.g. Android TV / Fire TV launchers). There is nothing to update
            // then, so bail out instead of crashing the caller — this is called from
            // FileServerService.onStartCommand's foreground-notification path.
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, FtpSftpWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_ftp_sftp_control)

            val ftpRunning = FileServerService.isFtpEnabled(context)
            val sftpRunning = FileServerService.isSftpEnabled(context)

            views.setTextViewText(R.id.widget_ftp_status,
                if (ftpRunning) context.getString(R.string.widget_ftp_sftp_running)
                else context.getString(R.string.widget_ftp_sftp_stopped))
            views.setInt(R.id.widget_ftp_status, "setTextColor",
                if (ftpRunning) 0xFF4ADE80.toInt() else 0xFF6B7280.toInt())

            views.setTextViewText(R.id.widget_sftp_status,
                if (sftpRunning) context.getString(R.string.widget_ftp_sftp_running)
                else context.getString(R.string.widget_ftp_sftp_stopped))
            views.setInt(R.id.widget_sftp_status, "setTextColor",
                if (sftpRunning) 0xFF4ADE80.toInt() else 0xFF6B7280.toInt())

            views.setTextViewText(R.id.widget_ftp_btn,
                if (ftpRunning) context.getString(R.string.widget_ftp_sftp_stop)
                else context.getString(R.string.widget_ftp_sftp_start))

            views.setTextViewText(R.id.widget_sftp_btn,
                if (sftpRunning) context.getString(R.string.widget_ftp_sftp_stop)
                else context.getString(R.string.widget_ftp_sftp_start))

            val toggleFtp = PendingIntent.getBroadcast(
                context, id * 2,
                Intent(context, FtpSftpWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_TOGGLE_FTP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_ftp_btn, toggleFtp)

            val toggleSftp = PendingIntent.getBroadcast(
                context, id * 2 + 1,
                Intent(context, FtpSftpWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_TOGGLE_SFTP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_sftp_btn, toggleSftp)

            val openSettings = PendingIntent.getActivity(
                context, id * 2 + 2,
                Intent(context, ServerHostActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_header, openSettings)

            manager.updateAppWidget(id, views)
        }
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, manager, id)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        updateWidget(context, manager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_WIDGET_TOGGLE_FTP -> {
                val isRunning = FileServerService.isFtpEnabled(context)
                if (isRunning) {
                    FileServerService.stopFtp(context)
                } else {
                    FileServerService.startFtp(context)
                }
                FileServerService.setFtpEnabled(context, !isRunning)
                updateAllWidgets(context)
                FileServerService.refreshNotification(context)
            }

            ACTION_WIDGET_TOGGLE_SFTP -> {
                val isRunning = FileServerService.isSftpEnabled(context)
                if (isRunning) {
                    FileServerService.stopSftp(context)
                } else {
                    FileServerService.startSftp(context)
                }
                FileServerService.setSftpEnabled(context, !isRunning)
                updateAllWidgets(context)
                FileServerService.refreshNotification(context)
            }
        }
    }
}
