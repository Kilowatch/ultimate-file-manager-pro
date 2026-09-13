package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the mobile-only setting for displaying selection checkboxes in edit/selection mode.
 * Default is disabled (false), meaning selection is indicated by row background highlighting
 * without showing the checkbox.
 */
object SelectionCheckboxPreferenceManager {

    const val PREFS_NAME = "selection_checkbox_prefs"
    private const val KEY_ENABLED = "selection_checkbox_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false) // disabled by default on mobile
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
