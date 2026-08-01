package za.kilowatch.ultimatefilemanager.util

import android.content.Context

/**
 * Single source of truth for the "Usage Analytics" preference.
 *
 * The Firebase Analytics toggle in Settings (SettingsActivity) and the Google-build
 * Analytics initialisation both read/write this same file + key, so the persisted
 * state stays consistent no matter which side changes it. The file/key names are the
 * historical ones ("analytics_prefs" / "analytics_enabled") so existing users keep
 * their saved choice and existing backups remain forward-compatible.
 *
 * On Amazon/FOSS builds this is still used by SettingsActivity to persist the
 * preference-only toggle; the Analytics stubs simply ignore the value.
 */
object AnalyticsPrefs {
    private const val PREFS_ANALYTICS = "analytics_prefs"
    private const val KEY_ANALYTICS_ENABLED = "analytics_enabled"

    /** Default when no preference has ever been saved — matches the pre-existing behaviour. */
    private const val DEFAULT_ENABLED = true

    /** @return whether usage analytics collection is currently enabled (defaults to true). */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_ANALYTICS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ANALYTICS_ENABLED, DEFAULT_ENABLED)

    /** Persists the usage-analytics enabled state. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_ANALYTICS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ANALYTICS_ENABLED, enabled)
            .apply()
    }
}
