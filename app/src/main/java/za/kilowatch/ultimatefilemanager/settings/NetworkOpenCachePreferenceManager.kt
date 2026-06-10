package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the "Network Open Caching" preference.
 *
 * Default: disabled — when disabled, network files opened externally are piped directly
 *          via the UfmDocumentsProvider, instead of caching the entire file locally first.
 */
object NetworkOpenCachePreferenceManager {

    private const val PREFS_NAME = "network_open_cache_prefs"
    private const val KEY_ENABLED = "network_open_cache_enabled"

    /** Returns true when the user has enabled network file caching before opening. */
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
