package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object PinnedFilesManager {
    private const val PREFS_NAME = "ufm_pinned_files_prefs"

    fun isPinned(context: Context, path: String, shareId: String? = null): Boolean {
        val key = getStorageKey(path, shareId)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(key, false)
    }

    fun setPinned(context: Context, path: String, shareId: String? = null, pinned: Boolean) {
        val key = getStorageKey(path, shareId)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (pinned) {
            prefs.edit().putBoolean(key, true).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    private fun getStorageKey(path: String, shareId: String?): String {
        return if (!shareId.isNullOrEmpty()) {
            "network:$shareId:$path"
        } else {
            path
        }
    }
}
