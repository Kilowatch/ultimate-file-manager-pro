package za.kilowatch.ultimatefilemanager.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R

/**
 * Manages user "Favorites" saved across the application.
 */
object FavoritesManager {
    private const val PREFS_NAME = "ufm_favorites_prefs"
    private const val KEY_FAVORITES = "favorites_array"

    data class FavoriteItem(
        val id: String,
        val path: String,
        val label: String,
        val isFolder: Boolean,
        val isNetwork: Boolean,
        val shareId: String? = null
    ) {
        fun toJson(): JSONObject {
            val json = JSONObject()
            json.put("id", id)
            json.put("path", path)
            json.put("label", label)
            json.put("isFolder", isFolder)
            json.put("isNetwork", isNetwork)
            json.put("shareId", shareId)
            return json
        }

        companion object {
            fun fromJson(json: JSONObject): FavoriteItem? {
                return try {
                    FavoriteItem(
                        id = json.getString("id"),
                        path = json.getString("path"),
                        label = json.getString("label"),
                        isFolder = json.getBoolean("isFolder"),
                        isNetwork = json.optBoolean("isNetwork", false),
                        shareId = if (json.has("shareId")) json.optString("shareId", null) else null
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getFavorites(context: Context): List<FavoriteItem> {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        val list = mutableListOf<FavoriteItem>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val item = FavoriteItem.fromJson(array.getJSONObject(i))
                if (item != null) {
                    list.add(item)
                }
            }
        } catch (e: Exception) {
            Log.e("FavoritesManager", "Failed to parse favorites JSON", e)
        }
        return list
    }

    fun addFavorite(context: Context, favorite: FavoriteItem) {
        val currentFavorites = getFavorites(context).toMutableList()
        // Prevent exact duplicates (by path)
        if (currentFavorites.none { it.path == favorite.path }) {
            currentFavorites.add(favorite)
            saveFavorites(context, currentFavorites)
            notifyWidget(context)
        }
    }

    fun removeFavorite(context: Context, path: String) {
        val currentFavorites = getFavorites(context).toMutableList()
        val removed = currentFavorites.removeAll { it.path == path }
        if (removed) {
            saveFavorites(context, currentFavorites)
            notifyWidget(context)
        }
    }

    fun isFavorite(context: Context, path: String): Boolean {
        return getFavorites(context).any { it.path == path }
    }

    private fun saveFavorites(context: Context, favorites: List<FavoriteItem>) {
        val array = JSONArray()
        for (item in favorites) {
            array.put(item.toJson())
        }
        getPrefs(context).edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    /**
     * Notifies all active Bookmark Launcher widgets to refresh their list.
     * This is a lightweight IPC call to AppWidgetManager — safe to call from any thread.
     * Is a no-op when no widget is currently placed on the home screen, so TV builds
     * (which have no BookmarkWidgetProvider registered) are completely unaffected.
     */
    private fun notifyWidget(context: Context) {
        try {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, "za.kilowatch.ultimatefilemanager.widget.BookmarkWidgetProvider")
            val ids = manager.getAppWidgetIds(provider)
            if (ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_view)
            }
        } catch (e: Exception) {
            // Silently ignore — widget refresh is best-effort and must never crash the app
            Log.w("FavoritesManager", "Widget notify skipped: ${e.message}")
        }
    }
}
