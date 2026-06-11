package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object GridIndicatorsPreferenceManager {

    private const val PREFS_NAME = "grid_indicators_prefs"
    private const val KEY_HIDDEN = "grid_indicators_hidden"

    fun isHidden(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDDEN, false) // default false = not hidden = show indicators
    }

    fun setHidden(context: Context, hidden: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDDEN, hidden)
            .apply()
    }
}
