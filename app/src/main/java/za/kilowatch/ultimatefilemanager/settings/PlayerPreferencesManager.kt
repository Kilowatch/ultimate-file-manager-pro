package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

enum class BackgroundVideoMode(val value: String) {
    PIP("pip"),
    AUDIO_ONLY("audio_only");

    companion object {
        fun fromValue(value: String): BackgroundVideoMode =
            entries.firstOrNull { it.value == value } ?: AUDIO_ONLY
    }
}

/**
 * Manages all UFM Media Player settings in a single SharedPreferences file.
 *
 * Prefs file: "ufm_player_prefs"
 */
object PlayerPreferencesManager {

    private const val PREFS_NAME = "ufm_player_prefs"
    private const val KEY_BACKGROUND_VIDEO_MODE = "background_video_mode"
    private const val KEY_MINI_PLAYER_ENABLED = "mini_player_enabled"
    private const val KEY_RESUME_AFTER_INTERRUPTION = "resume_after_interruption"

    // ── Background Video Mode ───────────────────────────────────────

    fun getBackgroundVideoMode(context: Context): BackgroundVideoMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return BackgroundVideoMode.fromValue(
            prefs.getString(KEY_BACKGROUND_VIDEO_MODE, BackgroundVideoMode.PIP.value) ?: BackgroundVideoMode.PIP.value
        )
    }

    fun setBackgroundVideoMode(context: Context, mode: BackgroundVideoMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKGROUND_VIDEO_MODE, mode.value)
            .apply()
    }

    /** Cycle to the next mode (PIP → AUDIO_ONLY → PIP). */
    fun cycleBackgroundVideoMode(context: Context): BackgroundVideoMode {
        val current = getBackgroundVideoMode(context)
        val next = when (current) {
            BackgroundVideoMode.PIP -> BackgroundVideoMode.AUDIO_ONLY
            BackgroundVideoMode.AUDIO_ONLY -> BackgroundVideoMode.PIP
        }
        setBackgroundVideoMode(context, next)
        return next
    }

    // ── Mini-Player ─────────────────────────────────────────────────

    fun isMiniPlayerEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MINI_PLAYER_ENABLED, true)
    }

    fun setMiniPlayerEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MINI_PLAYER_ENABLED, enabled)
            .apply()
    }

    // ── Resume After Interruption ───────────────────────────────────

    fun isResumeAfterInterruption(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESUME_AFTER_INTERRUPTION, true)
    }

    fun setResumeAfterInterruption(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RESUME_AFTER_INTERRUPTION, enabled)
            .apply()
    }
}
