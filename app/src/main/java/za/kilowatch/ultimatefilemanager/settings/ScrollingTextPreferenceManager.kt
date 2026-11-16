package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object ScrollingTextPreferenceManager {

    private const val PREFS_NAME = "scrolling_text_prefs"
    private const val KEY_ENABLED = "scrolling_text_enabled"
    private const val KEY_DISPLAY_MODE = "file_name_display_mode"
    private const val KEY_SCROLL_SPEED = "scrolling_text_speed"
    private const val KEY_LOOP_MODE = "scrolling_text_loop"
    private const val KEY_SCROLL_SCOPE = "scrolling_text_scope"

    const val MODE_MARQUEE = "marquee"
    const val MODE_MULTILINE_2 = "multiline_2"
    const val MODE_MULTILINE_3 = "multiline_3"
    const val MODE_MULTILINE_UNLIMITED = "multiline_unlimited"
    const val MODE_TRUNCATE = "truncate"
    const val MODE_TRUNCATE_MIDDLE = "truncate_middle"

    const val SPEED_SLOW = "slow"
    const val SPEED_NORMAL = "normal"
    const val SPEED_FAST = "fast"

    const val LOOP_ONE_SHOT = "one_shot"
    const val LOOP_CONTINUOUS = "continuous"

    const val SCOPE_ALL = "all"
    const val SCOPE_FOCUSED = "focused"

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

    fun isTruncateMiddle(context: Context): Boolean {
        return getMode(context) == MODE_TRUNCATE_MIDDLE
    }

    fun isMultiLine(context: Context): Boolean {
        val mode = getMode(context)
        return mode == MODE_MULTILINE_2 || mode == MODE_MULTILINE_3 || mode == MODE_MULTILINE_UNLIMITED
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        setMode(context, if (enabled) MODE_MARQUEE else MODE_TRUNCATE)
    }

    fun getScrollSpeed(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SCROLL_SPEED, SPEED_NORMAL) ?: SPEED_NORMAL
    }

    fun setScrollSpeed(context: Context, speed: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCROLL_SPEED, speed)
            .apply()
    }

    fun getLoopMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOOP_MODE, LOOP_ONE_SHOT) ?: LOOP_ONE_SHOT
    }

    fun setLoopMode(context: Context, loopMode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOOP_MODE, loopMode)
            .apply()
    }

    fun getScrollScope(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SCROLL_SCOPE, SCOPE_ALL) ?: SCOPE_ALL
    }

    fun setScrollScope(context: Context, scope: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCROLL_SCOPE, scope)
            .apply()
    }
}
