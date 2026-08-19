package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
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
        if (isTv) {
            return when (mode) {
                ViewMode.GRID_SMALL  -> 6
                ViewMode.GRID_MEDIUM -> 5
                ViewMode.GRID_LARGE  -> 4
                else -> 1
            }
        }
        val widthDp = context.resources.configuration.screenWidthDp
        if (widthDp > 0) {
            val targetDp = when (mode) {
                ViewMode.GRID_SMALL  -> 95f
                ViewMode.GRID_MEDIUM -> 130f
                ViewMode.GRID_LARGE  -> 195f
                else -> return 1
            }
            return (widthDp / targetDp).let { kotlin.math.round(it).toInt() }.coerceAtLeast(1)
        }
        val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return when (mode) {
            ViewMode.GRID_SMALL  -> if (isLandscape) 8 else 4
            ViewMode.GRID_MEDIUM -> if (isLandscape) 6 else 3
            ViewMode.GRID_LARGE  -> if (isLandscape) 4 else 2
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

    /** Shows a single-choice dialog to select the view mode (modernized on mobile, classic single-choice on TV). */
    fun showSelectionDialog(context: Context, currentMode: ViewMode, onSelected: (ViewMode) -> Unit) {
        val isTv = DeviceUtils.isTvDevice(context)
        if (isTv) {
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
            return
        }

        // Modern Mobile Dialog
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_select_view_mode, null)

        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Configure checkmarks
        dialogView.findViewById<View>(R.id.checkModeListSmall)?.visibility =
            if (currentMode == ViewMode.LIST_SMALL) View.VISIBLE else View.GONE
        dialogView.findViewById<View>(R.id.checkModeListMedium)?.visibility =
            if (currentMode == ViewMode.LIST_MEDIUM) View.VISIBLE else View.GONE
        dialogView.findViewById<View>(R.id.checkModeListLarge)?.visibility =
            if (currentMode == ViewMode.LIST_LARGE) View.VISIBLE else View.GONE
        dialogView.findViewById<View>(R.id.checkModeListXLarge)?.visibility =
            if (currentMode == ViewMode.LIST_XLARGE) View.VISIBLE else View.GONE
        dialogView.findViewById<View>(R.id.checkModeGridSmall)?.visibility =
            if (currentMode == ViewMode.GRID_SMALL) View.VISIBLE else View.GONE
        dialogView.findViewById<View>(R.id.checkModeGridMedium)?.visibility =
            if (currentMode == ViewMode.GRID_MEDIUM) View.VISIBLE else View.GONE
        dialogView.findViewById<View>(R.id.checkModeGridLarge)?.visibility =
            if (currentMode == ViewMode.GRID_LARGE) View.VISIBLE else View.GONE

        // Wire click handlers
        val modeMap = listOf(
            R.id.btnModeListSmall to ViewMode.LIST_SMALL,
            R.id.btnModeListMedium to ViewMode.LIST_MEDIUM,
            R.id.btnModeListLarge to ViewMode.LIST_LARGE,
            R.id.btnModeListXLarge to ViewMode.LIST_XLARGE,
            R.id.btnModeGridSmall to ViewMode.GRID_SMALL,
            R.id.btnModeGridMedium to ViewMode.GRID_MEDIUM,
            R.id.btnModeGridLarge to ViewMode.GRID_LARGE
        )

        for ((viewId, mode) in modeMap) {
            dialogView.findViewById<View>(viewId)?.setOnClickListener {
                dialog.dismiss()
                onSelected(mode)
            }
        }

        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
}

