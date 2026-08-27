package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import za.kilowatch.ultimatefilemanager.R

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
    private const val KEY_SKIP_LENGTH = "skip_length_seconds"
    private const val KEY_GESTURES_ENABLED = "player_gestures_enabled"
    private const val KEY_PLAYER_BUTTON_TOASTS = "player_button_toasts_enabled"

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

    // ── Skip Length (forward/back seek) ─────────────────────────────

    /** Sentinel value for "Disable" — hides the forward/back skip controls. */
    const val SKIP_DISABLED = -1

    /** Default skip length in seconds (matches the previous hardcoded 10s). */
    const val DEFAULT_SKIP_SECONDS = 10

    /** Selectable skip durations in seconds (excluding Disable). */
    val SKIP_OPTIONS = intArrayOf(3, 5, 10, 20, 30)

    fun getSkipLengthSeconds(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SKIP_LENGTH, DEFAULT_SKIP_SECONDS)
    }

    fun isSkipEnabled(context: Context): Boolean =
        getSkipLengthSeconds(context) != SKIP_DISABLED

    fun setSkipLengthSeconds(context: Context, seconds: Int) {
        val value = if (seconds == SKIP_DISABLED || seconds in SKIP_OPTIONS) seconds else DEFAULT_SKIP_SECONDS
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SKIP_LENGTH, value)
            .apply()
    }

    /** Current skip length in milliseconds (0 when disabled). */
    fun getSkipLengthMs(context: Context): Long {
        val seconds = getSkipLengthSeconds(context)
        return if (seconds == SKIP_DISABLED) 0L else seconds * 1000L
    }

    /** Skip-length option labels for the settings dialog: "3s", "5s", …, "Disable". */
    fun skipOptionLabels(context: Context): Array<String> = arrayOf(
        context.getString(R.string.skip_length_option_3s),
        context.getString(R.string.skip_length_option_5s),
        context.getString(R.string.skip_length_option_10s),
        context.getString(R.string.skip_length_option_20s),
        context.getString(R.string.skip_length_option_30s),
        context.getString(R.string.skip_length_option_disable)
    )

    /** Human-readable label for the current setting, e.g. "10s" or "Disable". */
    fun formatSkipLabel(context: Context): String {
        val res = when (getSkipLengthSeconds(context)) {
            3 -> R.string.skip_length_option_3s
            5 -> R.string.skip_length_option_5s
            20 -> R.string.skip_length_option_20s
            30 -> R.string.skip_length_option_30s
            SKIP_DISABLED -> R.string.skip_length_option_disable
            else -> R.string.skip_length_option_10s
        }
        return context.getString(res)
    }

    // ── Gestures ────────────────────────────────────────────────────

    fun isGesturesEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GESTURES_ENABLED, true)
    }

    fun setGesturesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GESTURES_ENABLED, enabled)
            .apply()
    }

    // ── Player Button Toasts ────────────────────────────────────────

    fun isButtonToastsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PLAYER_BUTTON_TOASTS, false)
    }

    fun setButtonToastsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PLAYER_BUTTON_TOASTS, enabled)
            .apply()
    }
}
