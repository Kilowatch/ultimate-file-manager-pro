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

    // ── Subtitle Styling ─────────────────────────────────────────────

    const val SUBTITLE_MODE_SYSTEM = 0
    const val SUBTITLE_MODE_CUSTOM = 1

    private const val KEY_SUBTITLE_STYLE_MODE = "subtitle_style_mode"
    private const val KEY_SUBTITLE_CUSTOM_TEXT_SIZE = "subtitle_custom_text_size"
    private const val KEY_SUBTITLE_CUSTOM_TEXT_COLOR = "subtitle_custom_text_color"
    private const val KEY_SUBTITLE_CUSTOM_BG_COLOR = "subtitle_custom_bg_color"
    private const val KEY_SUBTITLE_CUSTOM_EDGE_TYPE = "subtitle_custom_edge_type"
    private const val KEY_SUBTITLE_CUSTOM_EDGE_COLOR = "subtitle_custom_edge_color"
    private const val KEY_SUBTITLE_CUSTOM_BOTTOM_PADDING_DP = "subtitle_custom_bottom_padding_dp"
    private const val KEY_SUBTITLE_APPLY_EMBEDDED_STYLES = "subtitle_apply_embedded_styles"

    const val DEFAULT_SUBTITLE_TEXT_COLOR = 0xFFFBBF24.toInt() // High-visibility amber/yellow
    const val DEFAULT_SUBTITLE_BG_COLOR = 0x00000000 // Fully transparent
    const val DEFAULT_SUBTITLE_EDGE_COLOR = 0xFF000000.toInt() // Solid black outline
    const val DEFAULT_SUBTITLE_EDGE_TYPE = 1 // CaptionStyleCompat.EDGE_TYPE_OUTLINE
    const val DEFAULT_SUBTITLE_TEXT_SIZE_SCALE = 1.0f
    const val DEFAULT_SUBTITLE_BOTTOM_PADDING_DP_MOBILE = 16
    const val DEFAULT_SUBTITLE_BOTTOM_PADDING_DP_TV = 28
    const val DEFAULT_SUBTITLE_APPLY_EMBEDDED_STYLES = false

    fun getSubtitleStyleMode(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SUBTITLE_STYLE_MODE, SUBTITLE_MODE_SYSTEM)
    }

    fun setSubtitleStyleMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SUBTITLE_STYLE_MODE, mode)
            .apply()
    }

    fun getSubtitleCustomTextSize(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SUBTITLE_CUSTOM_TEXT_SIZE, DEFAULT_SUBTITLE_TEXT_SIZE_SCALE)
    }

    fun setSubtitleCustomTextSize(context: Context, scale: Float) {
        val clamped = scale.coerceIn(0.5f, 2.0f)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SUBTITLE_CUSTOM_TEXT_SIZE, clamped)
            .apply()
    }

    fun getSubtitleCustomTextColor(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SUBTITLE_CUSTOM_TEXT_COLOR, DEFAULT_SUBTITLE_TEXT_COLOR)
    }

    fun setSubtitleCustomTextColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SUBTITLE_CUSTOM_TEXT_COLOR, color)
            .apply()
    }

    fun getSubtitleCustomBgColor(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SUBTITLE_CUSTOM_BG_COLOR, DEFAULT_SUBTITLE_BG_COLOR)
    }

    fun setSubtitleCustomBgColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SUBTITLE_CUSTOM_BG_COLOR, color)
            .apply()
    }

    fun getSubtitleCustomEdgeType(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SUBTITLE_CUSTOM_EDGE_TYPE, DEFAULT_SUBTITLE_EDGE_TYPE)
    }

    fun setSubtitleCustomEdgeType(context: Context, edgeType: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SUBTITLE_CUSTOM_EDGE_TYPE, edgeType)
            .apply()
    }

    fun getSubtitleCustomEdgeColor(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SUBTITLE_CUSTOM_EDGE_COLOR, DEFAULT_SUBTITLE_EDGE_COLOR)
    }

    fun setSubtitleCustomEdgeColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SUBTITLE_CUSTOM_EDGE_COLOR, color)
            .apply()
    }

    fun getSubtitleBottomPaddingDp(context: Context, isTv: Boolean = false): Int {
        val defaultDp = if (isTv) DEFAULT_SUBTITLE_BOTTOM_PADDING_DP_TV else DEFAULT_SUBTITLE_BOTTOM_PADDING_DP_MOBILE
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SUBTITLE_CUSTOM_BOTTOM_PADDING_DP, defaultDp)
    }

    fun setSubtitleBottomPaddingDp(context: Context, dp: Int) {
        val clamped = dp.coerceIn(0, 100)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SUBTITLE_CUSTOM_BOTTOM_PADDING_DP, clamped)
            .apply()
    }

    fun isSubtitleApplyEmbeddedStyles(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUBTITLE_APPLY_EMBEDDED_STYLES, DEFAULT_SUBTITLE_APPLY_EMBEDDED_STYLES)
    }

    fun setSubtitleApplyEmbeddedStyles(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SUBTITLE_APPLY_EMBEDDED_STYLES, enabled)
            .apply()
    }

    /** Formats a short summary label for Settings cards. */
    fun formatSubtitleStyleSummary(context: Context): String {
        return try {
            if (getSubtitleStyleMode(context) == SUBTITLE_MODE_SYSTEM) {
                context.getString(R.string.subtitle_style_summary_system)
            } else {
                val colorName = when (getSubtitleCustomTextColor(context)) {
                    DEFAULT_SUBTITLE_TEXT_COLOR -> context.getString(R.string.subtitle_style_color_yellow)
                    0xFFFFFFFF.toInt() -> context.getString(R.string.subtitle_style_color_white)
                    0xFF00E5FF.toInt() -> context.getString(R.string.subtitle_style_color_cyan)
                    0xFF76FF03.toInt() -> context.getString(R.string.subtitle_style_color_green)
                    0xFFFF4081.toInt() -> context.getString(R.string.subtitle_style_color_magenta)
                    0xFFFF9100.toInt() -> context.getString(R.string.subtitle_style_color_orange)
                    else -> context.getString(R.string.subtitle_style_color_yellow)
                }
                context.getString(R.string.subtitle_style_summary_custom, colorName)
            }
        } catch (e: Exception) {
            if (getSubtitleStyleMode(context) == SUBTITLE_MODE_SYSTEM) "System caption style"
            else "UFM custom style"
        }
    }
}
