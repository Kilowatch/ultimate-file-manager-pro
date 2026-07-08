package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object SideBySideVideoPreferenceManager {

    private const val PREFS_NAME = "side_by_side_video_prefs"
    private const val KEY_ENABLED = "side_by_side_video_enabled"
    private const val KEY_SHOW_CONTROLS_ON_REPEAT = "side_by_side_video_show_controls_on_repeat"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isShowControlsOnRepeat(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_CONTROLS_ON_REPEAT, false)
    }

    fun setShowControlsOnRepeat(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_CONTROLS_ON_REPEAT, enabled)
            .apply()
    }
}
