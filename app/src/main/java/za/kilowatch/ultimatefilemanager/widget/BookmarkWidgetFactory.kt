package za.kilowatch.ultimatefilemanager.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager

/**
 * RemoteViewsFactory that provides the row views for the Bookmark Launcher widget's ListView.
 *
 * Data flow:
 *  1. onCreate / onDataSetChanged  →  reads FavoritesManager.getFavorites()
 *  2. getViewAt(position)          →  inflates widget_item_bookmark.xml and populates it
 *  3. getLoadingView               →  null (system uses a default spinner)
 *
 * Click handling:
 *  Each item exposes a fill-in Intent which the system merges with the
 *  PendingIntent template set in BookmarkWidgetProvider.updateWidget().
 *  The resulting broadcast fires BookmarkWidgetProvider.onReceive() with
 *  the bookmark's path, isFolder, isNetwork, shareId, and label extras.
 */
class BookmarkWidgetFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private var favorites: List<FavoritesManager.FavoriteItem> = emptyList()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        // Called by notifyAppWidgetViewDataChanged() — re-read favourites
        loadData()
    }

    override fun onDestroy() {
        favorites = emptyList()
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun loadData() {
        favorites = FavoritesManager.getFavorites(context)
    }

    // ── List contract ─────────────────────────────────────────────────────────

    override fun getCount(): Int = favorites.size

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    override fun getViewTypeCount(): Int = 1

    override fun getLoadingView(): RemoteViews? = null

    // ── Row view ──────────────────────────────────────────────────────────────

    override fun getViewAt(position: Int): RemoteViews {
        val item = favorites.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_item_bookmark)

        val views = RemoteViews(context.packageName, R.layout.widget_item_bookmark)

        // Label
        views.setTextViewText(R.id.widget_item_label, item.label.ifBlank { item.path })

        // Icon — network overrides folder/file icon
        val iconRes = when {
            item.isNetwork -> R.drawable.ic_network
            item.isFolder  -> R.drawable.ic_folder
            else           -> R.drawable.ic_file
        }
        views.setImageViewResource(R.id.widget_item_icon, iconRes)

        // Icon tint — network = amber accent, local folder = white, file = light grey
        val tintColor = when {
            item.isNetwork -> 0xFFF59E0B.toInt() // amber-400
            item.isFolder  -> 0xFFFFFFFF.toInt() // white
            else           -> 0xFF94A3B8.toInt() // slate-400
        }
        views.setInt(R.id.widget_item_icon, "setColorFilter", tintColor)

        // Chevron — fixed dark-slate tint
        views.setInt(R.id.widget_item_chevron, "setColorFilter", 0xFF3D3D5C.toInt())

        // ── Fill-in intent for this item ──
        // The system combines this with the PendingIntent template so that
        // BookmarkWidgetProvider.onReceive() fires with the correct extras.
        val fillIn = Intent().apply {
            putExtra(BookmarkWidgetProvider.EXTRA_FAV_PATH,       item.path)
            putExtra(BookmarkWidgetProvider.EXTRA_FAV_IS_FOLDER,  item.isFolder)
            putExtra(BookmarkWidgetProvider.EXTRA_FAV_IS_NETWORK, item.isNetwork)
            putExtra(BookmarkWidgetProvider.EXTRA_FAV_SHARE_ID,   item.shareId)
            putExtra(BookmarkWidgetProvider.EXTRA_FAV_LABEL,      item.label)
        }
        views.setOnClickFillInIntent(R.id.widget_item_root, fillIn)

        return views
    }
}
