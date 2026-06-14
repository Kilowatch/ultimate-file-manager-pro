package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object AutoplayPreferenceManager {

    private const val PREFS_NAME = "autoplay_prefs"
    private const val KEY_ENABLED = "autoplay_next_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
