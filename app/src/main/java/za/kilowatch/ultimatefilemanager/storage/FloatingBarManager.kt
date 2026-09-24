package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import org.json.JSONArray

/**
 * Manages persistence for the Mobile Floating Bottom Bar (Dock).
 *
 * Stores:
 * - Master enable/disable toggle (disabled by default)
 * - User-ordered list of tile IDs docked in the floating bar
 *
 * SharedPreferences file: "floating_bar_prefs"
 */
object FloatingBarManager {

    private const val PREFS_NAME = "floating_bar_prefs"
    private const val KEY_ENABLED = "pref_floating_bar_enabled"
    private const val KEY_ITEMS = "pref_floating_bar_items"

    /**
     * Returns true if the floating bar is enabled.
     * Default is false (disabled).
     */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    /**
     * Enables or disables the floating bar.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /**
     * Loads the list of tile IDs docked in the floating bar, in user-defined order.
     */
    fun getItemIds(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Persists the list of tile IDs in user-defined order.
     */
    fun setItemIds(context: Context, ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }

    /**
     * Checks if a tile is currently docked in the floating bar.
     */
    fun isItemDocked(context: Context, tileId: String): Boolean {
        return getItemIds(context).contains(tileId)
    }

    /**
     * Appends a tile ID to the docked items list if not already present.
     */
    fun addItem(context: Context, tileId: String) {
        val current = getItemIds(context).toMutableList()
        if (!current.contains(tileId)) {
            current.add(tileId)
            setItemIds(context, current)
        }
    }

    /**
     * Removes a tile ID from the docked items list.
     */
    fun removeItem(context: Context, tileId: String) {
        val current = getItemIds(context).toMutableList()
        if (current.remove(tileId)) {
            setItemIds(context, current)
        }
    }
}
