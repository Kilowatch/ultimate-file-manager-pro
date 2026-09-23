package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

object DefaultStartScreenPreferenceManager {

    private const val PREFS_NAME = "default_start_screen_prefs"
    private const val KEY_START_SCREEN_ID = "default_start_screen_id"

    const val ID_STORAGE_BROWSER = "STORAGE_BROWSER"
    const val ID_TWIN_WINDOW = "TWIN_WINDOW"
    const val ID_FILE_SERVER = "FILE_SERVER"
    const val ID_LAST_OPENED = "LAST_OPENED"
    const val PREFIX_STORAGE = "storage:"

    fun getStartScreenId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultId = if (DeviceUtils.isTvDevice(context)) ID_STORAGE_BROWSER else ID_LAST_OPENED

        // Also handle the legacy key for backward compatibility just in case
        val legacyKey = "default_start_screen"
        if (prefs.contains(legacyKey) && !prefs.contains(KEY_START_SCREEN_ID)) {
            val legacyValue = prefs.getString(legacyKey, defaultId) ?: defaultId
            setStartScreenId(context, legacyValue)
            prefs.edit().remove(legacyKey).apply()
            return legacyValue
        }
        return prefs.getString(KEY_START_SCREEN_ID, defaultId) ?: defaultId
    }

    fun setStartScreenId(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_START_SCREEN_ID, id).apply()
    }
}
