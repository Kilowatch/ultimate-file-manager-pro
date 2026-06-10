package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object DefaultStartScreenPreferenceManager {

    private const val PREFS_NAME = "default_start_screen_prefs"
    private const val KEY_START_SCREEN_ID = "default_start_screen_id"

    const val ID_STORAGE_BROWSER = "STORAGE_BROWSER"
    const val ID_TWIN_WINDOW = "TWIN_WINDOW"
    const val ID_FILE_SERVER = "FILE_SERVER"
    const val PREFIX_STORAGE = "storage:"

    fun getStartScreenId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Also handle the legacy key for backward compatibility just in case
        val legacyKey = "default_start_screen"
        if (prefs.contains(legacyKey) && !prefs.contains(KEY_START_SCREEN_ID)) {
            val legacyValue = prefs.getString(legacyKey, ID_STORAGE_BROWSER) ?: ID_STORAGE_BROWSER
            setStartScreenId(context, legacyValue)
            prefs.edit().remove(legacyKey).apply()
            return legacyValue
        }
        return prefs.getString(KEY_START_SCREEN_ID, ID_STORAGE_BROWSER) ?: ID_STORAGE_BROWSER
    }

    fun setStartScreenId(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_START_SCREEN_ID, id).apply()
    }
}
