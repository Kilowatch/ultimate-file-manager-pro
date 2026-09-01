package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the "Check for Updates" preferences for FOSS editions.
 *
 * Persists:
 *  - Whether automatic update checks on app open are enabled (default: true)
 *  - The last check timestamp (to throttle network queries)
 *  - The last notified release version (to avoid duplicate notifications)
 *  - Dismissed release versions (user chose "Remind Me Later" or dismissed)
 */
object FossUpdatePreferenceManager {

    private const val PREFS_NAME = "ufm_foss_update_prefs"
    private const val KEY_AUTO_CHECK_ENABLED = "foss_update_auto_check_enabled"
    private const val KEY_LAST_CHECK_TIME = "foss_update_last_check_timestamp"
    private const val KEY_LAST_NOTIFIED_VERSION = "foss_update_last_notified_version"
    private const val KEY_DISMISSED_VERSION = "foss_update_dismissed_version"

    fun isAutoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CHECK_ENABLED, false)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CHECK_ENABLED, enabled)
            .apply()
    }

    fun getLastCheckTime(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK_TIME, 0L)

    fun setLastCheckTime(context: Context, timestamp: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK_TIME, timestamp)
            .apply()
    }

    fun getLastNotifiedVersion(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_NOTIFIED_VERSION, "") ?: ""

    fun setLastNotifiedVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_NOTIFIED_VERSION, version)
            .apply()
    }

    fun isVersionDismissed(context: Context, version: String): Boolean {
        val dismissed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_VERSION, "") ?: ""
        return dismissed.equals(version, ignoreCase = true)
    }

    fun dismissVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_VERSION, version)
            .apply()
    }
}
