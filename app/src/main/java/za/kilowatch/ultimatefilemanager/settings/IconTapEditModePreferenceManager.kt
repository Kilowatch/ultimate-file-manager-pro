package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object IconTapEditModePreferenceManager {

    private const val PREFS_NAME = "icon_tap_edit_mode_prefs"
    private const val KEY_ENABLED = "icon_tap_edit_mode_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true) // enabled by default
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
