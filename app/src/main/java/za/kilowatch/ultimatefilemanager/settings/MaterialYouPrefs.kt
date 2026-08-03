package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Reads/writes the Material You (dynamic color) preference.
 *
 * The preference lives in the existing [ufm_prefs] file (same file as the theme
 * mode in [ThemeHelper]) so it is automatically covered by the settings backup
 * system under the already-registered "ufm_prefs" entry — no extra backup
 * registration is required.
 *
 * Default is OFF: the app keeps its original brand palette unless the user
 * explicitly enables Material You from the Appearance screen. When enabled,
 * wallpaper-derived dynamic colors apply on Android 12+ (API 31+) for non-TV
 * devices.
 */
object MaterialYouPrefs {

    private const val PREFS = "ufm_prefs"
    private const val KEY = "material_you_enabled"

    /**
     * Whether Material You (wallpaper dynamic color) is enabled. Default false.
     */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }

    /**
     * Persist the Material You preference. Writes via [android.content.SharedPreferences.Editor.apply]
     * so callers never touch the main thread.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }
}
