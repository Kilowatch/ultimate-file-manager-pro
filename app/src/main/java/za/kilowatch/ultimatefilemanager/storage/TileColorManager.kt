package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

object TileColorManager {

    private const val PREFS_NAME = "tile_colors_prefs"
    private const val KEY_COLORS = "tile_colors"

    fun saveTileColors(context: Context, colors: Map<String, TileColorConfig>) {
        val json = JSONObject()
        colors.forEach { (id, config) ->
            json.put(id, config.toJson())
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COLORS, json.toString())
            .commit()
    }

    fun loadTileColors(context: Context): Map<String, TileColorConfig> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COLORS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, TileColorConfig>()
            json.keys().forEach { id ->
                val obj = json.getJSONObject(id)
                result[id] = TileColorConfig.fromJson(obj)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getTileColor(context: Context, tileId: String): TileColorConfig {
        return loadTileColors(context)[tileId] ?: TileColorConfig()
    }

    fun saveTileColor(context: Context, tileId: String, config: TileColorConfig) {
        val colors = loadTileColors(context).toMutableMap()
        if (config.ringColor    == Color.TRANSPARENT &&
            config.iconColor    == Color.TRANSPARENT &&
            config.iconBgColor  == Color.TRANSPARENT &&
            config.tileBgColor  == Color.TRANSPARENT &&
            config.labelColor   == Color.TRANSPARENT) {
            colors.remove(tileId)
        } else {
            colors[tileId] = config
        }
        saveTileColors(context, colors)
    }
}