package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Executors

/**
 * Manages preferences for Flat View (Indexed Root Browser Mode).
 * 
 * Default system folders: false (Android/data and Android/obb are excluded)
 * Default expansion: true (All folder sections expanded by default)
 */
object FlatViewPreferenceManager {

    const val PREFS_NAME = "flat_view_prefs"
    const val KEY_INCLUDE_SYSTEM_FOLDERS = "flat_view_include_system_folders"
    const val KEY_DEFAULT_EXPANDED = "flat_view_default_expanded"
    const val KEY_CONTENT_MODE = "flat_view_content_mode"

    const val MODE_FILES_ONLY = "files_only"
    const val MODE_FILES_AND_FOLDERS = "files_and_folders"

    private val ioWriter = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ufm-flatview-prefs-writer").apply { isDaemon = true }
    }

    private fun editAsync(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val appContext = context.applicationContext
        ioWriter.execute {
            try {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.block()
                editor.commit()
            } catch (e: Exception) {
                // Ignore write failures in background
            }
        }
    }

    /** Returns true if system directories (Android/data, Android/obb) should be included in Flat View. Default: false. */
    fun isSystemFoldersIncluded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INCLUDE_SYSTEM_FOLDERS, false)
    }

    /** Persists whether system directories are included. */
    fun setSystemFoldersIncluded(context: Context, included: Boolean) {
        editAsync(context) {
            putBoolean(KEY_INCLUDE_SYSTEM_FOLDERS, included)
        }
    }

    /** Returns true if all folders should be expanded by default when entering Flat View. Default: true. */
    fun isDefaultExpanded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEFAULT_EXPANDED, true)
    }

    /** Persists the default expansion state. */
    fun setDefaultExpanded(context: Context, expanded: Boolean) {
        editAsync(context) {
            putBoolean(KEY_DEFAULT_EXPANDED, expanded)
        }
    }

    /** Returns the current content mode: "files_only" (default) or "files_and_folders". */
    fun getContentMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONTENT_MODE, MODE_FILES_ONLY) ?: MODE_FILES_ONLY
    }

    /** Persists the content mode. */
    fun setContentMode(context: Context, mode: String) {
        editAsync(context) {
            putString(KEY_CONTENT_MODE, mode)
        }
    }
}
