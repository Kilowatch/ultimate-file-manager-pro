package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Looper
import java.util.concurrent.Executors

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

    /**
     * In-memory cache for the saved long-press step index.
     * Eliminates synchronous [android.content.SharedPreferences] disk I/O
     * and lock contention ([android.app.SharedPreferencesImpl.awaitLoadedLocked])
     * from the main-thread key-event and touch-event dispatch hot path.
     */
    @Volatile private var cachedStep: Int? = null

    private val bgExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "ufm-long-press-prefs").apply { isDaemon = true }
        }
    }

    /**
     * Pre-warms the in-memory cache and underlying SharedPreferences file from disk.
     * Safe to call from [za.kilowatch.ultimatefilemanager.UfmApplication.onCreate] before
     * the ANR watchdog arms and before any Activity or View touches key/touch handlers.
     */
    fun init(context: Context) {
        if (cachedStep != null) return
        val appContext = context.applicationContext
        try {
            val step = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_STEP, DEFAULT_STEP)
                .coerceIn(0, STEP_COUNT - 1)
            cachedStep = step
        } catch (_: Throwable) {
            if (cachedStep == null) cachedStep = DEFAULT_STEP
        }
    }

    /** Clears the in-memory cache, e.g. after a settings restore or test. */
    fun invalidateCache() {
        cachedStep = null
    }

    // ── Persistence ───────────────────────────────────────────────────────

    fun saveStep(context: Context, step: Int) {
        val validStep = step.coerceIn(0, STEP_COUNT - 1)
        cachedStep = validStep
        val appContext = context.applicationContext
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_STEP, validStep)
                .apply()
        } catch (_: Throwable) {}
    }

    fun loadStep(context: Context): Int {
        cachedStep?.let { return it }
        val appContext = context.applicationContext
        // If called from the main thread before cache is populated, avoid blocking
        // in SharedPreferencesImpl.awaitLoadedLocked(): return default and warm asynchronously.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bgExecutor.execute {
                try {
                    val step = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getInt(KEY_STEP, DEFAULT_STEP)
                        .coerceIn(0, STEP_COUNT - 1)
                    cachedStep = step
                } catch (_: Throwable) {}
            }
            return DEFAULT_STEP
        }
        return try {
            val step = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_STEP, DEFAULT_STEP)
                .coerceIn(0, STEP_COUNT - 1)
            cachedStep = step
            step
        } catch (_: Throwable) {
            DEFAULT_STEP
        }
    }

    /** Convenience: return the saved duration directly in milliseconds. */
    fun loadDurationMs(context: Context): Long {
        val step = cachedStep ?: loadStep(context)
        return STEPS_MS.getOrElse(step) { STEPS_MS[DEFAULT_STEP] }
    }

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
