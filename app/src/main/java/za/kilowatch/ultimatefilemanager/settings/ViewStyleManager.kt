package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import za.kilowatch.ultimatefilemanager.storage.ViewModeManager.ViewMode

/**
 * Manages persisted styling configurations for List and Grid view presets in mobile storage browsing.
 * Strictly isolated from Storage Main Menu and Settings row heights.
 */
object ViewStyleManager {

    private const val PREFS_NAME = "ufm_view_style_prefs_v2"

    // ── List Style Keys ────────────────────────────────────────────────────────
    private fun keyListThumb(mode: ViewMode) = "list_thumb_${mode.name}"
    private fun keyListPadH(mode: ViewMode) = "list_pad_h_${mode.name}"
    private fun keyListPadV(mode: ViewMode) = "list_pad_v_${mode.name}"
    private fun keyListSpacing(mode: ViewMode) = "list_spacing_${mode.name}"
    private fun keyListTextPri(mode: ViewMode) = "list_text_pri_${mode.name}"
    private fun keyListTextSec(mode: ViewMode) = "list_text_sec_${mode.name}"
    private fun keyListCorner(mode: ViewMode) = "list_corner_${mode.name}"

    // ── Grid Style Keys ────────────────────────────────────────────────────────
    private fun keyGridTargetW(mode: ViewMode) = "grid_target_w_${mode.name}"
    private fun keyGridMargin(mode: ViewMode) = "grid_margin_${mode.name}"
    private fun keyGridCorner(mode: ViewMode) = "grid_corner_${mode.name}"
    private fun keyGridTextPri(mode: ViewMode) = "grid_text_pri_${mode.name}"
    private fun keyGridIconPad(mode: ViewMode) = "grid_icon_pad_${mode.name}"

    /**
     * Loads the configured [ListViewStyle] for [mode], falling back to factory defaults.
     */
    fun getListStyle(context: Context, mode: ViewMode): ListViewStyle {
        val def = ListViewStyle.getDefault(mode)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(keyListThumb(mode))) return def

        return ListViewStyle(
            thumbnailSizeDp = prefs.getInt(keyListThumb(mode), def.thumbnailSizeDp),
            itemPaddingHorizontalDp = prefs.getInt(keyListPadH(mode), def.itemPaddingHorizontalDp),
            itemPaddingVerticalDp = prefs.getInt(keyListPadV(mode), def.itemPaddingVerticalDp),
            itemSpacingDp = prefs.getInt(keyListSpacing(mode), def.itemSpacingDp),
            primaryTextSizeSp = prefs.getInt(keyListTextPri(mode), def.primaryTextSizeSp),
            secondaryTextSizeSp = prefs.getInt(keyListTextSec(mode), def.secondaryTextSizeSp),
            iconCornerRadiusDp = prefs.getInt(keyListCorner(mode), def.iconCornerRadiusDp)
        )
    }

    /**
     * Persists [style] for [mode].
     */
    fun saveListStyle(context: Context, mode: ViewMode, style: ListViewStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(keyListThumb(mode), style.thumbnailSizeDp)
            .putInt(keyListPadH(mode), style.itemPaddingHorizontalDp)
            .putInt(keyListPadV(mode), style.itemPaddingVerticalDp)
            .putInt(keyListSpacing(mode), style.itemSpacingDp)
            .putInt(keyListTextPri(mode), style.primaryTextSizeSp)
            .putInt(keyListTextSec(mode), style.secondaryTextSizeSp)
            .putInt(keyListCorner(mode), style.iconCornerRadiusDp)
            .apply()
    }

    /**
     * Resets a List preset back to its factory default values.
     */
    fun resetListStyleToDefault(context: Context, mode: ViewMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(keyListThumb(mode))
            .remove(keyListPadH(mode))
            .remove(keyListPadV(mode))
            .remove(keyListSpacing(mode))
            .remove(keyListTextPri(mode))
            .remove(keyListTextSec(mode))
            .remove(keyListCorner(mode))
            .apply()
    }

    /**
     * Loads the configured [GridViewStyle] for [mode], falling back to factory defaults.
     */
    fun getGridStyle(context: Context, mode: ViewMode): GridViewStyle {
        val def = GridViewStyle.getDefault(mode)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(keyGridTargetW(mode))) return def

        return GridViewStyle(
            targetWidthDp = prefs.getInt(keyGridTargetW(mode), def.targetWidthDp),
            cardMarginDp = prefs.getInt(keyGridMargin(mode), def.cardMarginDp),
            cardCornerRadiusDp = prefs.getInt(keyGridCorner(mode), def.cardCornerRadiusDp),
            primaryTextSizeSp = prefs.getInt(keyGridTextPri(mode), def.primaryTextSizeSp),
            iconPaddingDp = prefs.getInt(keyGridIconPad(mode), def.iconPaddingDp)
        )
    }

    /**
     * Persists [style] for [mode].
     */
    fun saveGridStyle(context: Context, mode: ViewMode, style: GridViewStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(keyGridTargetW(mode), style.targetWidthDp)
            .putInt(keyGridMargin(mode), style.cardMarginDp)
            .putInt(keyGridCorner(mode), style.cardCornerRadiusDp)
            .putInt(keyGridTextPri(mode), style.primaryTextSizeSp)
            .putInt(keyGridIconPad(mode), style.iconPaddingDp)
            .apply()
    }

    /**
     * Resets a Grid preset back to its factory default values.
     */
    fun resetGridStyleToDefault(context: Context, mode: ViewMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(keyGridTargetW(mode))
            .remove(keyGridMargin(mode))
            .remove(keyGridCorner(mode))
            .remove(keyGridTextPri(mode))
            .remove(keyGridIconPad(mode))
            .apply()
    }

    /**
     * Resets all list and grid presets back to factory defaults.
     */
    fun resetAllToDefaults(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * Checks if [mode] has been customized by the user.
     */
    fun isCustomized(context: Context, mode: ViewMode): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (mode in listOf(ViewMode.GRID_SMALL, ViewMode.GRID_MEDIUM, ViewMode.GRID_LARGE)) {
            prefs.contains(keyGridTargetW(mode))
        } else {
            prefs.contains(keyListThumb(mode))
        }
    }

    /**
     * Computes the dynamic row height in pixels for List presets based on thumbnail and text dimensions,
     * ensuring row layouts auto-adjust gracefully without clipping.
     */
    fun computeMinRowHeightPx(context: Context, mode: ViewMode): Int {
        val style = getListStyle(context, mode)
        val density = context.resources.displayMetrics.density
        // Estimated text block height = primaryTextSp + secondaryTextSp + 6dp gap
        val textBlockEstimatedDp = style.primaryTextSizeSp + style.secondaryTextSizeSp + 6
        val contentHeightDp = maxOf(style.thumbnailSizeDp, textBlockEstimatedDp)
        val totalDp = contentHeightDp + (2 * style.itemPaddingVerticalDp)
        return (totalDp * density + 0.5f).toInt()
    }
}
