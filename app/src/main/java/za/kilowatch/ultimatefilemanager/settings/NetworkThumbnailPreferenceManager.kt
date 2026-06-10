package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Manages configuration for Network/Remote Drive Thumbnails.
 */
object NetworkThumbnailPreferenceManager {
    private const val PREFS_NAME = "network_thumbnail_prefs"
    private const val KEY_ENABLED = "network_thumbnails_enabled"
    private const val KEY_CACHE_LIMIT_MB = "network_thumbnails_limit_mb"
    private const val KEY_CACHE_PATH = "network_thumbnails_cache_path"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** True if the user has enabled remote thumbnails. */
    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Cache limit in Megabytes. Default 500MB if not set. */
    fun getCacheLimitMb(context: Context): Int {
        return prefs(context).getInt(KEY_CACHE_LIMIT_MB, 500)
    }

    fun setCacheLimitMb(context: Context, limitMb: Int) {
        prefs(context).edit().putInt(KEY_CACHE_LIMIT_MB, limitMb).apply()
    }

    /** 
     * User-selected filesystem path for offline cache.
     * Defaults to the app's external cache directory if not set.
     */
    fun getCachePath(context: Context): String {
        val savedPath = prefs(context).getString(KEY_CACHE_PATH, "") ?: ""
        if (savedPath.isNotEmpty()) return savedPath
        
        // Default to external cache dir (internal SD card area)
        val defaultDir = File(context.externalCacheDir, "network_thumbnails")
        if (!defaultDir.exists()) {
            defaultDir.mkdirs()
        }
        return defaultDir.absolutePath
    }

    fun setCachePath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_CACHE_PATH, path).apply()
    }

    /** Whether the user has chosen a valid cache directory. */
    fun isConfigured(context: Context): Boolean {
        val path = getCachePath(context)
        if (path.isEmpty()) return false
        val file = File(path)
        return file.exists() && file.isDirectory && file.canWrite()
    }
}
