package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object BreadcrumbsPreferenceManager {

    private const val PREFS_NAME = "breadcrumbs_prefs"
    private const val KEY_ENABLED = "breadcrumbs_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true) // enabled by default
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
