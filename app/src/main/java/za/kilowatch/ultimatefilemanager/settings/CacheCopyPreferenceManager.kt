package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the "Cache Copying" preference.
 *
 * Default: disabled — files are copied directly without a temp file.
 * Enabled: files are first written to a `<name>.ufm_tmp` temp file,
 *          verified, and then atomically renamed to the final destination.
 *          This is a more secure copy method but may add additional time.
 */
object CacheCopyPreferenceManager {

    private const val PREFS_NAME = "cache_copy_prefs"
    private const val KEY_ENABLED = "cache_copy_enabled"

    /** Returns true when the user has enabled secure cache copying. */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false) // default OFF
    }

    /** Persists the enabled state. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
