package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Manages the user's preferred view mode for the file browser screens
 * (List Small, Medium, Large, and Grid Small, Medium, Large).
 *
 * Persisted in SharedPreferences alongside sort/filter settings ("ufm_prefs").
 * Applies to both [FileBrowserActivity] and [NetworkBrowserActivity] (mobile and TV).
 */
object ViewModeManager {

    private const val PREFS_NAME = "ufm_prefs"
    private const val KEY_VIEW_MODE = "view_mode"
    private const val KEY_VIEW_MODE_V2 = "view_mode_v2"

    enum class ViewMode {
        LIST_SMALL,
        LIST_MEDIUM,
        LIST_LARGE,
        LIST_XLARGE,
        GRID_SMALL,
        GRID_MEDIUM,
        GRID_LARGE
    }

    /** Persist the chosen mode. */
    fun save(context: Context, mode: ViewMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIEW_MODE_V2, mode.name)
            .apply()
    }

    /** Load the saved mode, migrating legacy settings if needed. */
    fun load(context: Context): ViewMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_VIEW_MODE_V2)) {
            val name = prefs.getString(KEY_VIEW_MODE_V2, ViewMode.LIST_MEDIUM.name)
            return try {
                ViewMode.valueOf(name!!)
            } catch (e: Exception) {
                ViewMode.LIST_MEDIUM
            }
        }

        // Migrate legacy integer preference if it exists
        if (prefs.contains(KEY_VIEW_MODE)) {
            val legacyOrdinal = prefs.getInt(KEY_VIEW_MODE, 0)
            val migrated = when (legacyOrdinal) {
                0 -> ViewMode.LIST_MEDIUM
                1 -> ViewMode.GRID_SMALL
                2 -> ViewMode.GRID_MEDIUM
                else -> ViewMode.LIST_MEDIUM
            }
            save(context, migrated)
            return migrated
        }

        return ViewMode.LIST_MEDIUM
    }

    /** Checks if the mode is a grid view mode. */
    fun isGrid(mode: ViewMode): Boolean = when (mode) {
        ViewMode.GRID_SMALL,
        ViewMode.GRID_MEDIUM,
        ViewMode.GRID_LARGE -> true
        else -> false
    }

    /**
     * Number of columns for [GridLayoutManager].
     * List modes are handled separately (LinearLayoutManager).
     */
    fun spanCount(context: Context, mode: ViewMode): Int {
        val isTv = DeviceUtils.isTvDevice(context)
        return when (mode) {
            ViewMode.GRID_SMALL  -> if (isTv) 6 else 4
            ViewMode.GRID_MEDIUM -> if (isTv) 5 else 3
            ViewMode.GRID_LARGE  -> if (isTv) 4 else 2
            else -> 1
        }
    }

    /** Returns the icon drawable res for the current mode (shown on the toggle button). */
    fun iconRes(mode: ViewMode): Int = when (mode) {
        ViewMode.LIST_SMALL,
        ViewMode.LIST_MEDIUM,
        ViewMode.LIST_LARGE,
        ViewMode.LIST_XLARGE -> R.drawable.ic_view_list
        ViewMode.GRID_SMALL  -> R.drawable.ic_view_grid_small
        ViewMode.GRID_MEDIUM -> R.drawable.ic_view_grid_medium
        ViewMode.GRID_LARGE  -> R.drawable.ic_view_grid_large
    }

    /** Shows a single-choice dialog to select the view mode. */
    fun showSelectionDialog(context: Context, currentMode: ViewMode, onSelected: (ViewMode) -> Unit) {
        val modes = ViewMode.entries.toTypedArray()
        val options = modes.map { mode ->
            val resId = when (mode) {
                ViewMode.LIST_SMALL  -> R.string.view_mode_list_small
                ViewMode.LIST_MEDIUM -> R.string.view_mode_list_medium
                ViewMode.LIST_LARGE  -> R.string.view_mode_list_large
                ViewMode.LIST_XLARGE -> R.string.view_mode_list_xlarge
                ViewMode.GRID_SMALL  -> R.string.view_mode_grid_small
                ViewMode.GRID_MEDIUM -> R.string.view_mode_grid_medium
                ViewMode.GRID_LARGE  -> R.string.view_mode_grid_large
            }
            context.getString(resId)
        }.toTypedArray()

        val selectedIndex = modes.indexOf(currentMode)

        MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setTitle(R.string.dialog_view_mode_title)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                onSelected(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

