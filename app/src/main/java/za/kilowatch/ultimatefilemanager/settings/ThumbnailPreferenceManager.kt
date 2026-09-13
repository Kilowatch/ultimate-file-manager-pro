package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the "Show media thumbnails in list view" preference.
 *
 * Default: disabled — shows the normal icon-in-circle.
 * Enabled: shows the actual image/video frame cropped to a rounded square
 *          (no blue circle background), matching the Android Files reference UX.
 *
 * Coil is used for thumbnail loading so that all fetched bitmaps are automatically
 * stored in Coil's LRU memory cache and its disk cache, preventing redundant decodes
 * on scroll.
 */
object ThumbnailPreferenceManager {

    private const val PREFS_NAME = "thumbnail_prefs"
    private const val KEY_ENABLED = "show_media_thumbnails"
    private const val KEY_CACHE_LIMIT_MB = "local_thumbnails_limit_mb"
    private const val KEY_CACHE_PATH = "local_thumbnails_cache_path"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns true when the user has enabled media thumbnail previews. */
    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, true) // default ON
    }

    /** Persists the enabled state. */
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
        val defaultDir = java.io.File(context.externalCacheDir, "local_thumbnails")
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
        val file = java.io.File(path)
        return file.exists() && file.isDirectory && file.canWrite()
    }
}
