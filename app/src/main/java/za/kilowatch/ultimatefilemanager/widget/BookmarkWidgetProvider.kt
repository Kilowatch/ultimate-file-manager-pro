package za.kilowatch.ultimatefilemanager.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import java.io.File

/**
 * AppWidgetProvider for the Bookmark Launcher widget.
 *
 * Responsibilities:
 *  - Build and bind the RemoteViews frame on each update.
 *  - Handle ACTION_OPEN_BOOKMARK broadcasts (taps on list items).
 *  - Handle ACTION_OPEN_APP broadcasts (tap on the "Open" header button).
 */
class BookmarkWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_OPEN_BOOKMARK = "za.kilowatch.ultimatefilemanager.widget.ACTION_OPEN_BOOKMARK"
        const val ACTION_OPEN_APP     = "za.kilowatch.ultimatefilemanager.widget.ACTION_OPEN_APP"

        // Extras carried in the fill-in intent from each list row
        const val EXTRA_FAV_PATH       = "widget_fav_path"
        const val EXTRA_FAV_IS_FOLDER  = "widget_fav_is_folder"
        const val EXTRA_FAV_IS_NETWORK = "widget_fav_is_network"
        const val EXTRA_FAV_SHARE_ID   = "widget_fav_share_id"
        const val EXTRA_FAV_LABEL      = "widget_fav_label"

        /**
         * Builds and applies RemoteViews for [appWidgetId].
         * Called on update and after favourites change.
         */
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_bookmark_list)

            // ── List adapter (RemoteViewsService) ──
            val serviceIntent = Intent(context, BookmarkWidgetService::class.java).apply {
                // Include the widget ID so the service can serve different widgets independently
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // Must be unique per widget so the system doesn't collapse intents
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list_view, serviceIntent)

            // Empty view shown when the list has no items
            views.setEmptyView(R.id.widget_list_view, R.id.widget_empty_view)

            // ── Template PendingIntent for list item taps ──
            // Each item's fill-in intent (set in BookmarkWidgetFactory.getViewAt) will
            // merge its extras into this broadcast, which fires onReceive below.
            val itemClick = android.app.PendingIntent.getBroadcast(
                context,
                appWidgetId,
                Intent(context, BookmarkWidgetProvider::class.java).apply {
                    action = ACTION_OPEN_BOOKMARK
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_list_view, itemClick)

            // ── "Open" header button → launch StorageBrowserActivity ──
            val openAppIntent = android.app.PendingIntent.getBroadcast(
                context,
                appWidgetId + 1000, // offset to avoid clash with itemClick
                Intent(context, BookmarkWidgetProvider::class.java).apply {
                    action = ACTION_OPEN_APP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_open_app, openAppIntent)

            // ── Header tap also opens the app ──
            views.setOnClickPendingIntent(R.id.widget_header, openAppIntent)

            // ── Programmatic tints (android:tint is unsupported in RemoteViews XML) ──
            // Header star icon → white
            views.setInt(R.id.widget_header_icon, "setColorFilter", 0xFFFFFFFF.toInt())

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    // ── AppWidgetProvider lifecycle ──────────────────────────────────────────

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // Re-render when the widget is resized
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    // ── Broadcast receiver — handles item taps ───────────────────────────────

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // lets AppWidgetProvider handle APPWIDGET_UPDATE etc.

        when (intent.action) {
            ACTION_OPEN_APP -> {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent(context, StorageBrowserActivity::class.java)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }

            ACTION_OPEN_BOOKMARK -> {
                val path      = intent.getStringExtra(EXTRA_FAV_PATH)      ?: return
                val isFolder  = intent.getBooleanExtra(EXTRA_FAV_IS_FOLDER,  true)
                val isNetwork = intent.getBooleanExtra(EXTRA_FAV_IS_NETWORK, false)
                val shareId   = intent.getStringExtra(EXTRA_FAV_SHARE_ID)
                val label     = intent.getStringExtra(EXTRA_FAV_LABEL)      ?: ""

                val target: Intent = when {
                    // ── Network folder ──
                    isNetwork && isFolder -> {
                        if (shareId == null) return
                        Intent(context, NetworkBrowserActivity::class.java).apply {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID,      shareId)
                            putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH,  path)
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, label)
                        }
                    }

                    // ── Network file ──
                    isNetwork && !isFolder -> {
                        if (shareId == null) return
                        val parentPath = path.substringBeforeLast("/", "")
                        val fileName   = path.substringAfterLast("/")
                        Intent(context, NetworkBrowserActivity::class.java).apply {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID,       shareId)
                            putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH,   parentPath)
                            putExtra(NetworkBrowserActivity.EXTRA_OPEN_FILE_PATH, path)
                            putExtra(NetworkBrowserActivity.EXTRA_OPEN_FILE_NAME, fileName)
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL,  label)
                        }
                    }

                    // ── Local folder ──
                    isFolder -> {
                        val (sid, stype, volumeRoot) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(path)
                        Intent(context, FileBrowserActivity::class.java).apply {
                            putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH,     volumeRoot)
                            putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH,   path)
                            putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL,  label)
                            putExtra(FileBrowserActivity.EXTRA_STORAGE_ID,     sid)
                            putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE,   stype)
                        }
                    }

                    // ── Local file — open parent folder and highlight the file ──
                    else -> {
                        val parentPath = File(path).parent ?: path
                        val (sid, stype, volumeRoot) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(parentPath)
                        Intent(context, FileBrowserActivity::class.java).apply {
                            putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH,    volumeRoot)
                            putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH,  parentPath)
                            putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, label)
                            putExtra(FileBrowserActivity.EXTRA_FOCUS_PATH,    path)
                            putExtra(FileBrowserActivity.EXTRA_STORAGE_ID,    sid)
                            putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE,  stype)
                        }
                    }
                }

                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(target)
            }
        }
    }
}
