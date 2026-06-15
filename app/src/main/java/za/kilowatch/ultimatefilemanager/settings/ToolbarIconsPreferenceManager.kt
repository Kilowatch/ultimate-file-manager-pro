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
    const val KEY_COMPRESS = "pref_icon_compress"
    const val KEY_DELETE = "pref_icon_delete"
    const val KEY_IMAGE_COMPRESS = "pref_icon_image_compress"
    const val KEY_CREATE_NEW = "pref_icon_create_new"

    fun isIconEnabled(context: Context, key: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, true)
    }

    fun setIconEnabled(context: Context, key: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
    }
}
