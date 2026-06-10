package za.kilowatch.ultimatefilemanager.storage

import android.content.Context

/**
 * Manages view preferences for the Main Menu (Storage Browser).
 */
object MainMenuViewModeManager {

    private const val PREFS_NAME = "ufm_main_menu_prefs"
    private const val KEY_VIEW_MODE = "main_menu_view_mode"
    private const val KEY_COLUMN_COUNT = "main_menu_column_count"

    enum class ViewMode { LIST, GRID }

    fun saveViewMode(context: Context, mode: ViewMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VIEW_MODE, mode.ordinal)
            .apply()
    }

    fun loadViewMode(context: Context): ViewMode {
        val ordinal = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_VIEW_MODE, ViewMode.LIST.ordinal)
        return ViewMode.entries.getOrElse(ordinal) { ViewMode.LIST }
    }

    fun saveColumnCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_COLUMN_COUNT, count)
            .apply()
    }

    fun loadColumnCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COLUMN_COUNT, 3)
        
        // Migrate legacy 2-column or other unsupported values to 3
        if (count != 3 && count != 4) {
            prefs.edit().putInt(KEY_COLUMN_COUNT, 3).apply()
            return 3
        }
        return count
    }

    private const val KEY_ITEM_SIZE = "main_menu_item_size"

    enum class ItemSize { LARGE, MEDIUM, SMALL }

    fun saveItemSize(context: Context, size: ItemSize) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ITEM_SIZE, size.ordinal)
            .apply()
    }

    fun loadItemSize(context: Context): ItemSize {
        val ordinal = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ITEM_SIZE, ItemSize.MEDIUM.ordinal)
        return ItemSize.entries.getOrElse(ordinal) { ItemSize.MEDIUM }
    }
}
