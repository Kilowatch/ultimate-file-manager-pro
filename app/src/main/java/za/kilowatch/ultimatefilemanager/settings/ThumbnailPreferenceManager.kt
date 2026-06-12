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

    /** Returns true when the user has enabled media thumbnail previews. */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true) // default ON
    }

    /** Persists the enabled state. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
