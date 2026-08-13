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


    fun isIconEnabled(context: Context, key: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, true)
    }

    fun setIconEnabled(context: Context, key: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
    }
}
