package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.Configuration

/**
 * Helper to read/write and apply the app-wide font size preference.
 *
 * Supports three levels:
 *   FONT_SMALL  (0) → scale factor 0.85
 *   FONT_NORMAL (1) → scale factor 1.00  (Android default)
 *   FONT_LARGE  (2) → scale factor 1.15
 *
 * Apply by overriding [attachBaseContext] in every Activity:
 *   override fun attachBaseContext(base: Context) =
 *       super.attachBaseContext(FontSizeHelper.applyTo(base))
 */
object FontSizeHelper {

    private const val PREFS    = "ufm_prefs"
    private const val KEY_FONT = "font_size"

    const val FONT_SMALL  = 0
    const val FONT_NORMAL = 1
    const val FONT_LARGE  = 2

    /**
     * Set to true by [FontSizeActivity] after a size change.
     * [StorageBrowserActivity] reads and clears this in onResume() and calls recreate()
     * so that all tiles and labels are re-laid-out with the new fontScale.
     * This is in-memory only — it intentionally resets on process restart.
     */
    var restartPending: Boolean = false

    /** Scale factors mapped to each size option. */
    private val SCALE = mapOf(
        FONT_SMALL  to 0.85f,
        FONT_NORMAL to 1.00f,
        FONT_LARGE  to 1.15f
    )

    /**
     * In-memory cache for the saved font size index.
     * Eliminates [getSharedPreferences] calls from the main-thread
     * [android.app.Application.ActivityLifecycleCallbacks] hot path, preventing
     * lock contention ANRs (same root cause as [LocaleHelper]).
     */
    @Volatile private var cachedSize: Int? = null

    fun getSavedSize(context: Context): Int {
        cachedSize?.let { return it }
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FONT, FONT_NORMAL)
        cachedSize = value
        return value
    }

    fun save(context: Context, size: Int) {
        cachedSize = size   // update cache synchronously before the write
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FONT, size)
            .commit()   // commit() = synchronous — the value is safe to read immediately
    }

    /**
     * Returns a new [Context] whose [Configuration.fontScale] is set to
     * the user-chosen scale factor. Pass the result to [super.attachBaseContext].
     */
    fun applyTo(context: Context): Context {
        val scale = SCALE[getSavedSize(context)] ?: 1.00f
        val config = Configuration(context.resources.configuration)
        config.fontScale = scale
        return context.createConfigurationContext(config)
    }
}

