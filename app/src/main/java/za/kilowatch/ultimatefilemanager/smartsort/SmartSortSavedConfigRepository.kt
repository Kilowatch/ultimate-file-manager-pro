package za.kilowatch.ultimatefilemanager.smartsort

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.widget.SmartSortWidgetProvider
import java.io.File

object SmartSortSavedConfigRepository {
    private const val FILE_NAME = "ufm_smart_sort_saved_configs.json"
    private val lock = Any()

    fun getAll(context: android.content.Context? = null): List<SmartSortSavedConfig> {
        synchronized(lock) {
            return try {
                val baseDir = context?.filesDir ?: UfmApplication.instance.filesDir
                val file = File(baseDir, FILE_NAME)
                if (!file.exists()) return emptyList()
                val json = JSONArray(file.readText())
                (0 until json.length()).map { i ->
                    val obj = json.getJSONObject(i)
                    SmartSortSavedConfig(
                        id = obj.getString("id"),
                        folderPath = obj.getString("folderPath"),
                        description = obj.optString("description", ""),
                        savedAt = obj.optLong("savedAt", System.currentTimeMillis()),
                        configJson = obj.getString("configJson")
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    fun getForFolder(folderPath: String): SmartSortSavedConfig? {
        return getAll().firstOrNull { it.folderPath == folderPath }
    }

    fun getById(id: String): SmartSortSavedConfig? {
        return getAll().firstOrNull { it.id == id }
    }

    fun save(config: SmartSortSavedConfig) {
        synchronized(lock) {
            val all = getAll().toMutableList()
            all.removeAll { it.folderPath == config.folderPath }
            all.add(0, config)
            saveAll(all)
        }
        triggerWidgetUpdate()
    }

    fun delete(id: String) {
        synchronized(lock) {
            val all = getAll().toMutableList()
            all.removeAll { it.id == id }
            saveAll(all)
        }
        triggerWidgetUpdate()
    }

    fun deleteForFolder(folderPath: String) {
        synchronized(lock) {
            val all = getAll().toMutableList()
            all.removeAll { it.folderPath == folderPath }
            saveAll(all)
        }
        triggerWidgetUpdate()
    }

    fun saveAll(entries: List<SmartSortSavedConfig>, context: android.content.Context? = null) {
        synchronized(lock) {
            try {
                val json = JSONArray(entries.map { entry ->
                    JSONObject().apply {
                        put("id", entry.id)
                        put("folderPath", entry.folderPath)
                        put("description", entry.description)
                        put("savedAt", entry.savedAt)
                        put("configJson", entry.configJson)
                    }
                })
                val baseDir = context?.filesDir ?: try { UfmApplication.instance.filesDir } catch (_: Exception) { null }
                if (baseDir == null) return
                val file = File(baseDir, FILE_NAME)
                file.parentFile?.mkdirs()
                file.writeText(json.toString(2))
            } catch (_: Exception) {}
        }
    }

    private fun triggerWidgetUpdate() {
        try {
            val context = UfmApplication.instance
            val provider = ComponentName(context, SmartSortWidgetProvider::class.java)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(provider)
            if (ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            }
        } catch (_: Exception) {}
    }
}
