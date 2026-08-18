package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the "Background TV Sharing" preference on Android TV.
 * When enabled (default: true), TV keeps the PairingServer foreground service active
 * whenever at least one paired mobile device is configured, allowing background browsing.
 * When disabled, TV only serves requests while UFM is actively open on screen.
 */
object TvBackgroundServerPreferenceManager {

    private const val PREFS_NAME = "ufm_tv_server_prefs"
    private const val KEY_ENABLED = "tv_background_server_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true) // Enabled by default

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
