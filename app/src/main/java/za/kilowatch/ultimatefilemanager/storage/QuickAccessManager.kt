package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import org.json.JSONArray

/**
 * Manages persistence for Quick Access (Mobile Bottom Floating Bar & Edge Swipe Menu).
 *
 * Supported presentation modes:
 * - [MODE_DISABLED]: Quick Access is turned off.
 * - [MODE_BOTTOM_BAR]: Floating bottom dock anchored on main storage dashboard.
 * - [MODE_EDGE_MENU]: Slide-out side drawer accessible from screen edge anywhere in the app.
 *
 * Supported edge positions:
 * - [EDGE_LEFT]: Inward swipe from the left edge of the screen.
 * - [EDGE_RIGHT]: Inward swipe from the right edge of the screen.
 *
 * SharedPreferences file: "floating_bar_prefs"
 */
object QuickAccessManager {

    const val PREFS_NAME = "floating_bar_prefs"

    // Modes
    const val MODE_DISABLED   = 0
    const val MODE_BOTTOM_BAR = 1
    const val MODE_EDGE_MENU  = 2

    // Edge Positions
    const val EDGE_LEFT  = 0
    const val EDGE_RIGHT = 1

    private const val KEY_ENABLED = "pref_floating_bar_enabled"
    private const val KEY_MODE = "pref_quick_access_mode"
    private const val KEY_EDGE_POSITION = "pref_quick_access_edge_position"
    private const val KEY_SHOW_EDGE_HANDLE = "pref_quick_access_edge_handle"
    private const val KEY_ITEMS = "pref_floating_bar_items"
    private const val KEY_HIDE_MAIN_TILES = "pref_quick_access_hide_main_tiles"

    private const val KEY_LAST_ACTIVE_MODE = "pref_quick_access_last_mode"

    fun getMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_MODE)) {
            return prefs.getInt(KEY_MODE, MODE_DISABLED)
        }
        // Backward-compatibility: if legacy KEY_ENABLED was true, default to MODE_BOTTOM_BAR
        val legacyEnabled = prefs.getBoolean(KEY_ENABLED, false)
        return if (legacyEnabled) MODE_BOTTOM_BAR else MODE_DISABLED
    }

    fun setMode(context: Context, mode: Int) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putInt(KEY_MODE, mode)
        editor.putBoolean(KEY_ENABLED, mode != MODE_DISABLED)
        if (mode != MODE_DISABLED) {
            editor.putInt(KEY_LAST_ACTIVE_MODE, mode)
        }
        editor.apply()
    }

    fun isEnabled(context: Context): Boolean {
        return getMode(context) != MODE_DISABLED
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (!enabled) {
            setMode(context, MODE_DISABLED)
        } else {
            if (getMode(context) == MODE_DISABLED) {
                val lastMode = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt(KEY_LAST_ACTIVE_MODE, MODE_BOTTOM_BAR)
                setMode(context, lastMode)
            }
        }
    }

    fun getEdgePosition(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_EDGE_POSITION, EDGE_LEFT)
    }

    fun setEdgePosition(context: Context, edge: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_EDGE_POSITION, edge)
            .apply()
    }

    fun isEdgeHandleEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_EDGE_HANDLE, true)
    }

    fun setEdgeHandleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_EDGE_HANDLE, enabled)
            .apply()
    }

    fun isHideMainTilesEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_MAIN_TILES, true)
    }

    fun setHideMainTilesEnabled(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_MAIN_TILES, hide)
            .apply()
    }

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

    fun setItemIds(context: Context, ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }

    fun isItemDocked(context: Context, tileId: String): Boolean {
        return getItemIds(context).contains(tileId)
    }

    fun addItem(context: Context, tileId: String) {
        val current = getItemIds(context).toMutableList()
        if (!current.contains(tileId)) {
            current.add(tileId)
            setItemIds(context, current)
        }
    }

    fun removeItem(context: Context, tileId: String) {
        val current = getItemIds(context).toMutableList()
        if (current.remove(tileId)) {
            setItemIds(context, current)
        }
    }
}
