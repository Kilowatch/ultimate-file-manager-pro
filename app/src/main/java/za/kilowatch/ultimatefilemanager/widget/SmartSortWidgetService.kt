package za.kilowatch.ultimatefilemanager.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.smartsort.SmartSortSavedConfig
import za.kilowatch.ultimatefilemanager.smartsort.SmartSortSavedConfigRepository
import java.io.File

class SmartSortWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return SmartSortWidgetFactory(applicationContext)
    }
}

class SmartSortWidgetFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var configs: List<SmartSortSavedConfig> = emptyList()

    override fun onCreate() { load() }
    override fun onDataSetChanged() { load() }
    override fun onDestroy() { configs = emptyList() }
    override fun getCount(): Int = configs.size
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun getViewTypeCount(): Int = 1
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val config = configs.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_smart_sort_item)

        val views = RemoteViews(context.packageName, R.layout.widget_smart_sort_item)
        views.setTextViewText(R.id.widget_item_description, config.description)
        views.setTextViewText(R.id.widget_item_path, config.folderPath)

        val fillIntent = Intent().apply {
            putExtra(SmartSortWidgetProvider.EXTRA_CONFIG_ID, config.id)
        }
        views.setOnClickFillInIntent(R.id.widget_item_root, fillIntent)

        return views
    }

    private fun load() {
        configs = try {
            SmartSortSavedConfigRepository.getAll(context)
        } catch (_: Exception) { emptyList() }
    }
}
