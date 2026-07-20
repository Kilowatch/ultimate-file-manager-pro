package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages list row size preferences for the Settings Activity.
 */
object SettingsListSizeManager {

    private const val PREFS_NAME = "ufm_settings_list_prefs"
    private const val KEY_ITEM_SIZE = "settings_list_item_size"

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
