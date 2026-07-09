package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object LeftHandedFabPreferenceManager {

    private const val PREFS_NAME = "left_handed_fab_prefs"
    private const val KEY_LEFT_HANDED = "left_handed_fab_enabled"

    fun isLeftHanded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LEFT_HANDED, false)
    }

    fun setLeftHanded(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LEFT_HANDED, enabled)
            .apply()
    }
}
