package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object ToolbarIconsPreferenceManager {

    private const val PREFS_NAME = "toolbar_icons_prefs"

    const val KEY_COPY = "pref_icon_copy"
    const val KEY_MOVE = "pref_icon_move"
    const val KEY_RENAME = "pref_icon_rename"
    const val KEY_SHARE = "pref_icon_share"
    const val KEY_COPY_ENCRYPT = "pref_icon_copy_encrypt"
    const val KEY_MOVE_ENCRYPT = "pref_icon_move_encrypt"
    const val KEY_FAVORITE = "pref_icon_favorite"
    const val KEY_HIDE = "pref_icon_hide"
    const val KEY_UNHIDE = "pref_icon_unhide"
    const val KEY_SELECT_ALL = "pref_icon_select_all"
    const val KEY_INVERT_SELECTION = "pref_icon_invert_selection"
    const val KEY_COMPRESS = "pref_icon_compress"
    const val KEY_EXTRACT = "pref_icon_extract"
    const val KEY_DELETE = "pref_icon_delete"
    const val KEY_IMAGE_COMPRESS = "pref_icon_image_compress"
    const val KEY_CREATE_NEW = "pref_icon_create_new"
    const val KEY_PROTECT = "pref_icon_protect"
    const val KEY_UNPROTECT = "pref_icon_unprotect"
    const val KEY_RETRIGGER_THUMBNAILS = "pref_icon_retrigger_thumbnails"
    const val KEY_PIN = "pref_icon_pin"
    const val KEY_UNPIN = "pref_icon_unpin"
    const val KEY_DUPLICATE_FINDER = "pref_icon_duplicate_finder"
    const val KEY_LARGE_FILES_FINDER = "pref_icon_large_files_finder"
    const val KEY_SET_HOME_WALLPAPER = "pref_icon_set_home_wallpaper"
    const val KEY_SET_LOCK_WALLPAPER = "pref_icon_set_lock_wallpaper"
    const val KEY_CREATE_GIF = "pref_icon_create_gif"
    const val KEY_EXIF_TOOLS = "pref_icon_exif_tools"
    const val KEY_CHECKSUM = "pref_icon_checksum"

    const val KEY_QUICK_BAR_ENABLED = "pref_quick_bar_enabled"
    const val KEY_QUICK_BAR_ITEMS = "pref_quick_bar_items"
    const val MAX_QUICK_BAR_ITEMS = 5

    const val ACTION_DELETE = "delete"
    const val ACTION_COMPRESS = "compress"
    const val ACTION_MOVE = "move"
    const val ACTION_COPY = "copy"
    const val ACTION_RENAME = "rename"
    const val ACTION_SHARE = "share"
    const val ACTION_PROTECT_UNPROTECT = "protect_unprotect"
    const val ACTION_HIDE_UNHIDE = "hide_unhide"
    const val ACTION_PIN_UNPIN = "pin_unpin"
    const val ACTION_FAVORITE = "favorite"
    const val ACTION_SELECT_ALL = "select_all"
    const val ACTION_INVERT_SELECTION = "invert_selection"
    const val ACTION_EXTRACT = "extract"
    const val ACTION_IMAGE_COMPRESS = "image_compress"
    const val ACTION_CREATE_GIF = "create_gif"
    const val ACTION_EXIF_TOOLS = "exif_tools"
    const val ACTION_SET_HOME_WALLPAPER = "set_home_wallpaper"
    const val ACTION_SET_LOCK_WALLPAPER = "set_lock_wallpaper"
    const val ACTION_DUPLICATE_FINDER = "duplicate_finder"
    const val ACTION_LARGE_FILES_FINDER = "large_files_finder"
    const val ACTION_CREATE_NEW = "create_new"
    const val ACTION_RETRIGGER_THUMBNAILS = "retrigger_thumbnails"
    const val ACTION_COPY_ENCRYPT = "copy_encrypt"
    const val ACTION_MOVE_ENCRYPT = "move_encrypt"
    const val ACTION_CHECKSUM = "checksum"
    const val ACTION_MORE = "more"

    val DEFAULT_QUICK_BAR_ITEMS = listOf(
        ACTION_COPY,
        ACTION_MOVE,
        ACTION_DELETE,
        ACTION_RENAME,
        ACTION_MORE
    )

    fun isIconEnabled(context: Context, key: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, true)
    }

    fun setIconEnabled(context: Context, key: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
    }

    fun isQuickBarEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_QUICK_BAR_ENABLED, true)
    }

    fun setQuickBarEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_QUICK_BAR_ENABLED, enabled).apply()
    }

    fun getQuickBarItems(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_QUICK_BAR_ITEMS, null)
        if (raw.isNullOrBlank()) {
            return DEFAULT_QUICK_BAR_ITEMS
        }
        val items = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (items.isNotEmpty()) items.take(MAX_QUICK_BAR_ITEMS) else DEFAULT_QUICK_BAR_ITEMS
    }

    fun setQuickBarItems(context: Context, items: List<String>) {
        val validItems = items.take(MAX_QUICK_BAR_ITEMS).joinToString(",")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_QUICK_BAR_ITEMS, validItems).apply()
    }

    fun filterItemsForBottomSheet(
        context: Context,
        items: List<za.kilowatch.ultimatefilemanager.storage.FileToolsBottomSheet.ActionItem>
    ): List<za.kilowatch.ultimatefilemanager.storage.FileToolsBottomSheet.ActionItem> {
        if (!isQuickBarEnabled(context) || za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(context)) {
            return items
        }
        val quickItems = getQuickBarItems(context).toSet()
        return items.filter { item ->
            when (item.id) {
                "protect", "unprotect" -> !quickItems.contains(ACTION_PROTECT_UNPROTECT)
                "hide", "unhide" -> !quickItems.contains(ACTION_HIDE_UNHIDE)
                "pin", "unpin" -> !quickItems.contains(ACTION_PIN_UNPIN)
                "extract_here", "extract" -> !quickItems.contains(ACTION_EXTRACT)
                else -> !quickItems.contains(item.id)
            }
        }
    }

    fun resetToDefaults(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

