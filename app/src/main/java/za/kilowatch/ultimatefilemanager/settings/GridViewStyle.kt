package za.kilowatch.ultimatefilemanager.settings

import za.kilowatch.ultimatefilemanager.storage.ViewModeManager.ViewMode

/**
 * Visual styling configuration for a Grid preset.
 * All units are in dp (sp for text sizes).
 */
data class GridViewStyle(
    val targetWidthDp: Int,
    val cardMarginDp: Int,
    val cardCornerRadiusDp: Int,
    val primaryTextSizeSp: Int,
    val iconPaddingDp: Int
) {
    companion object {
        val DEFAULT_SMALL = GridViewStyle(
            targetWidthDp = 95,
            cardMarginDp = 4,
            cardCornerRadiusDp = 10,
            primaryTextSizeSp = 11,
            iconPaddingDp = 0
        )

        val DEFAULT_MEDIUM = GridViewStyle(
            targetWidthDp = 130,
            cardMarginDp = 6,
            cardCornerRadiusDp = 14,
            primaryTextSizeSp = 13,
            iconPaddingDp = 0
        )

        val DEFAULT_LARGE = GridViewStyle(
            targetWidthDp = 195,
            cardMarginDp = 8,
            cardCornerRadiusDp = 18,
            primaryTextSizeSp = 15,
            iconPaddingDp = 0
        )

        fun getDefault(mode: ViewMode): GridViewStyle = when (mode) {
            ViewMode.GRID_SMALL -> DEFAULT_SMALL
            ViewMode.GRID_MEDIUM -> DEFAULT_MEDIUM
            ViewMode.GRID_LARGE -> DEFAULT_LARGE
            else -> DEFAULT_MEDIUM
        }
    }
}
