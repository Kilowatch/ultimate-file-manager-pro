package za.kilowatch.ultimatefilemanager.support

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences wrapper for the remembered support-email state.
 *
 * Stored in a dedicated `support_prefs` file that is deliberately NOT registered
 * in [za.kilowatch.ultimatefilemanager.settings.SettingsBackupManager.getAvailableBackupItems].
 *
 * WARNING: This file must NEVER be added to `SettingsBackupManager.getAvailableBackupItems()`.
 * It is intentionally excluded from config backup/export for privacy (spec NFR-01) — the
 * backup system uses an explicit allowlist, so leaving this file unregistered keeps the
 * email off exported backups.
 *
 * Two persisted states:
 * - `remember_email_enabled` — the "Remember my email address" checkbox preference,
 *   persisted immediately whenever the user toggles it (spec FR-11).
 * - `remembered_email` — the email value, written on a successful send when the box is
 *   checked and the field is non-empty (FR-03/FR-05), purged on a successful send when
 *   the box is unchecked (FR-04).
 */
object SupportEmailPrefs {
    private const val PREFS_NAME = "support_prefs"
    private const val KEY_ENABLED = "remember_email_enabled"
    private const val KEY_EMAIL = "remembered_email"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return prefs ?: synchronized(this) {
            prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
        }
    }

    /** Whether the "Remember my email address" preference is currently enabled. */
    fun isEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ENABLED, false)

    /** The stored email value (empty string when nothing has been saved yet). */
    fun getEmail(context: Context): String =
        getPrefs(context).getString(KEY_EMAIL, "") ?: ""

    /** True only when the preference is enabled AND an email is stored — the pre-fill condition. */
    fun isEmailRemembered(context: Context): Boolean =
        isEnabled(context) && getEmail(context).isNotEmpty()

    /** Persists the checkbox preference immediately on toggle (FR-11). */
    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Saves the email and enables remembering (FR-03/FR-05). */
    fun saveEmail(context: Context, email: String) {
        getPrefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_EMAIL, email)
            .apply()
    }

    /** Disables remembering and permanently clears the stored email (FR-04). */
    fun purge(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_ENABLED, false)
            .putString(KEY_EMAIL, "")
            .apply()
    }
}
