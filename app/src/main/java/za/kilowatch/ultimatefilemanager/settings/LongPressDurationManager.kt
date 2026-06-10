package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the user's preferred long-press hold duration for entering
 * tile edit mode on the main StorageBrowser screen.
 *
 * Range  : 0.5 s → 5.0 s in 10 equal steps of 0.5 s.
 * Storage: SharedPreferences ("ufm_long_press_prefs", key "long_press_step")
 */
object LongPressDurationManager {

    private const val PREFS_NAME = "ufm_long_press_prefs"
    private const val KEY_STEP   = "long_press_step"

    /** All valid durations in milliseconds, index = slider step (0–9). */
    val STEPS_MS = longArrayOf(500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000)

    /** Default step index — 2000 ms (index 3). */
    const val DEFAULT_STEP = 3

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

    /** Format a step index as a display string, e.g. "2.0 s". */
    fun formatStep(step: Int): String {
        val ms = STEPS_MS.getOrElse(step) { STEPS_MS[DEFAULT_STEP] }
        val sec = ms / 1000.0
        return "%.1f s".format(sec)
    }

    /** Format the currently saved duration as a display string. */
    fun formatSaved(context: Context): String = formatStep(loadStep(context))
}
