package za.kilowatch.ultimatefilemanager.billing

import android.content.Context

object ActiveBadgeIconHelper {

    private const val PREFS_NAME = "ufm_badge_prefs"
    private const val KEY_SELECTED_POINTS = "selected_badge_points"

    /**
     * Returns the active badge app icon resource ID, or null if the default icon is active
     * or on flavors/devices where badges are not installed.
     */
    fun getActiveAppIcon(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val points = prefs.getInt(KEY_SELECTED_POINTS, 0)
        if (points <= 0) return null
        val id = context.resources.getIdentifier("app_icon_points_$points", "drawable", context.packageName)
        return if (id != 0) id else null
    }

    /**
     * Returns the active badge cutout emblem resource ID, or null if default.
     */
    fun getActiveBadgeCutout(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val points = prefs.getInt(KEY_SELECTED_POINTS, 0)
        if (points <= 0) return null
        val id = context.resources.getIdentifier("badge_points_$points", "drawable", context.packageName)
        return if (id != 0) id else null
    }
}
