package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import za.kilowatch.ultimatefilemanager.ui.elevated.ElevatedManager

/**
 * Manages user preferences for Elevated Access managers (Porter, Shizuku, Shevery).
 * Allows users to enable or disable elevated access for a specific manager cleanly
 * without executing destructive OS-level permission revokes.
 */
object ElevatedAccessPreferenceManager {

    private const val PREFS_NAME = "elevated_access_preferences"
    private const val KEY_PREFIX_ENABLED = "elevated_manager_enabled_"

    /**
     * Returns whether elevated access for the specified [manager] is enabled by the user.
     * Defaults to true.
     */
    fun isManagerEnabled(context: Context, manager: ElevatedManager): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PREFIX_ENABLED + manager.name, true)
    }

    /**
     * Sets whether elevated access for the specified [manager] is enabled by the user.
     */
    fun setManagerEnabled(context: Context, manager: ElevatedManager, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREFIX_ENABLED + manager.name, enabled)
            .apply()
    }
}
