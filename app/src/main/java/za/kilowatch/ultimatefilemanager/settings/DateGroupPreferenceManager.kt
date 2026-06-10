package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Persists the "Group by Date" toggle that inserts year/month section headers
 * into [FileAdapter] and [NetworkFileAdapter].
 *
 * Key is stored in the shared "ufm_prefs" file alongside sort/filter settings.
 */
object DateGroupPreferenceManager {

    private const val PREFS = "ufm_prefs"
    private const val KEY   = "group_by_date"
    private const val KEY_COLLAPSED = "group_by_date_collapsed"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }

    fun getCollapsedGroups(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_COLLAPSED, emptySet()) ?: emptySet()
    }

    fun setCollapsedGroups(context: Context, collapsed: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_COLLAPSED, collapsed)
            .apply()
    }
}
