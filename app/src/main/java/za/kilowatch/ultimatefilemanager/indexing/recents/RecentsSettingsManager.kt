package za.kilowatch.ultimatefilemanager.indexing.recents

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user preferences for the Recent Files feature.
 * Independent of the full content indexer.
 */
object RecentsSettingsManager {

    const val PREFS_NAME = "ufm_recents_prefs"
    private const val KEY_ENABLED = "recents_enabled"
    private const val KEY_VIEW_MODE = "recents_view_mode" // 0: list, 1: grid

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isGridView(context: Context): Boolean {
        val mode = loadViewMode(context)
        return za.kilowatch.ultimatefilemanager.storage.ViewModeManager.isGrid(mode)
    }

    fun setGridView(context: Context, isGrid: Boolean) {
        val mode = if (isGrid) za.kilowatch.ultimatefilemanager.storage.ViewModeManager.ViewMode.GRID_MEDIUM
                   else za.kilowatch.ultimatefilemanager.storage.ViewModeManager.ViewMode.LIST_MEDIUM
        saveViewMode(context, mode)
    }

    fun loadViewMode(context: Context): za.kilowatch.ultimatefilemanager.storage.ViewModeManager.ViewMode {
        return za.kilowatch.ultimatefilemanager.storage.ViewModeManager.load(context)
    }

    fun saveViewMode(context: Context, mode: za.kilowatch.ultimatefilemanager.storage.ViewModeManager.ViewMode) {
        za.kilowatch.ultimatefilemanager.storage.ViewModeManager.save(context, mode)
    }
}
