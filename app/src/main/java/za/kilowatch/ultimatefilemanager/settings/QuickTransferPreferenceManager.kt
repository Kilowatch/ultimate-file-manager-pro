package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the "Quick Copy/Move" preference.
 * When enabled, Copy and Move buttons in the file browser open a destination-folder
 * picker and execute the transfer immediately — no clipboard paste step required.
 * Default is disabled (standard clipboard behaviour is preserved).
 */
object QuickTransferPreferenceManager {

    private const val PREFS_NAME = "ufm_quick_transfer_prefs"
    private const val KEY_ENABLED = "quick_transfer_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
