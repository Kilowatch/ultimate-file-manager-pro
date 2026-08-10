package za.kilowatch.ultimatefilemanager.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import za.kilowatch.ultimatefilemanager.R

class SmartSortWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateAppWidget(context, appWidgetManager, id)
    }

    companion object {
        const val EXTRA_CONFIG_ID = "extra_config_id"

        fun updateWidget(context: Context) {
            // AppWidgetManager.getInstance() returns null on devices with no widget
            // host (e.g. Android TV / Fire TV launchers) — bail out instead of crashing.
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, SmartSortWidgetProvider::class.java))
            for (id in ids) {
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
            }
        }

        private fun updateAppWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_smart_sort)

            val serviceIntent = Intent(context, SmartSortWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            val executeIntent = Intent(context, SmartSortWidgetExecuteService::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getForegroundService(context, id, executeIntent, piFlags)
            views.setPendingIntentTemplate(R.id.widget_list, pi)

            manager.updateAppWidget(id, views)
        }
    }
}
