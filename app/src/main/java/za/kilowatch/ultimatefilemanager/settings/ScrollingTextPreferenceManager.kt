package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object ScrollingTextPreferenceManager {

    private const val PREFS_NAME = "scrolling_text_prefs"
    private const val KEY_ENABLED = "scrolling_text_enabled"
    private const val KEY_DISPLAY_MODE = "file_name_display_mode"

    const val MODE_MARQUEE = "marquee"
    const val MODE_MULTILINE_2 = "multiline_2"
    const val MODE_MULTILINE_3 = "multiline_3"
    const val MODE_MULTILINE_UNLIMITED = "multiline_unlimited"
    const val MODE_TRUNCATE = "truncate"

    fun getMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DISPLAY_MODE, null)?.let { return it }
        val legacyEnabled = prefs.getBoolean(KEY_ENABLED, true)
        return if (legacyEnabled) MODE_MARQUEE else MODE_TRUNCATE
    }

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_MODE, mode)
            .putBoolean(KEY_ENABLED, mode == MODE_MARQUEE)
            .apply()
    }

    fun isEnabled(context: Context): Boolean {
        return getMode(context) == MODE_MARQUEE
    }

    fun isMultiLine(context: Context): Boolean {
        val mode = getMode(context)
        return mode == MODE_MULTILINE_2 || mode == MODE_MULTILINE_3 || mode == MODE_MULTILINE_UNLIMITED
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        setMode(context, if (enabled) MODE_MARQUEE else MODE_TRUNCATE)
    }
}
