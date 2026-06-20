package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the user's preferred auto-hide duration for media player controls
 * (play/pause, seek bar, top bar, etc.).
 *
 * Range  : 1 s → 10 s in 10 equal steps of 1 s.
 * Storage: SharedPreferences ("ufm_controls_timeout_prefs", key "controls_timeout_step")
 */
object ControlsTimeoutManager {

    private const val PREFS_NAME = "ufm_controls_timeout_prefs"
    private const val KEY_STEP   = "controls_timeout_step"

    /** All valid durations in milliseconds, index = slider step (0–9). */
    val STEPS_MS = longArrayOf(1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000)

    /** Default step index — 3000 ms (index 2). */
    const val DEFAULT_STEP = 2

    /** Number of discrete steps. */
    val STEP_COUNT get() = STEPS_MS.size   // 10

    // ── Persistence ───────────────────────────────────────────────────────

    fun saveStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_STEP, step.coerceIn(0, STEP_COUNT - 1))
            .apply()
    }

    fun loadStep(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_STEP, DEFAULT_STEP)
            .coerceIn(0, STEP_COUNT - 1)

    /** Convenience: return the saved duration directly in milliseconds. */
    fun loadDurationMs(context: Context): Long = STEPS_MS[loadStep(context)]

    // ── Formatting ────────────────────────────────────────────────────────

    /** Format a step index as a display string, e.g. "3.0 s". */
    fun formatStep(step: Int): String {
        val ms = STEPS_MS.getOrElse(step) { STEPS_MS[DEFAULT_STEP] }
        val sec = ms / 1000.0
        return "%.1f s".format(sec)
    }

    /** Format the currently saved duration as a display string. */
    fun formatSaved(context: Context): String = formatStep(loadStep(context))
}
