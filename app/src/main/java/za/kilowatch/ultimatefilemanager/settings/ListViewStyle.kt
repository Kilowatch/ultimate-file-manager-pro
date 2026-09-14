package za.kilowatch.ultimatefilemanager.settings

import za.kilowatch.ultimatefilemanager.storage.ViewModeManager.ViewMode

/**
 * Visual styling configuration for a List density preset.
 * All units are in dp (sp for text sizes).
 */
data class ListViewStyle(
    val thumbnailSizeDp: Int,
    val itemPaddingHorizontalDp: Int,
    val itemPaddingVerticalDp: Int,
    val itemSpacingDp: Int,
    val primaryTextSizeSp: Int,
    val secondaryTextSizeSp: Int,
    val iconCornerRadiusDp: Int
) {
    companion object {
        val DEFAULT_SMALL = ListViewStyle(
            thumbnailSizeDp = 36,
            itemPaddingHorizontalDp = 16,
            itemPaddingVerticalDp = 2,
            itemSpacingDp = 0,
            primaryTextSizeSp = 14,
            secondaryTextSizeSp = 11,
            iconCornerRadiusDp = 10
        )

        val DEFAULT_MEDIUM = ListViewStyle(
            thumbnailSizeDp = 48,
            itemPaddingHorizontalDp = 16,
            itemPaddingVerticalDp = 2,
            itemSpacingDp = 0,
            primaryTextSizeSp = 16,
            secondaryTextSizeSp = 12,
            iconCornerRadiusDp = 10
        )

        val DEFAULT_LARGE = ListViewStyle(
            thumbnailSizeDp = 56,
            itemPaddingHorizontalDp = 16,
            itemPaddingVerticalDp = 4,
            itemSpacingDp = 0,
            primaryTextSizeSp = 18,
            secondaryTextSizeSp = 13,
            iconCornerRadiusDp = 10
        )

        val DEFAULT_XLARGE = ListViewStyle(
            thumbnailSizeDp = 64,
            itemPaddingHorizontalDp = 16,
            itemPaddingVerticalDp = 6,
            itemSpacingDp = 0,
            primaryTextSizeSp = 20,
            secondaryTextSizeSp = 14,
            iconCornerRadiusDp = 10
        )

        fun getDefault(mode: ViewMode): ListViewStyle = when (mode) {
            ViewMode.LIST_SMALL -> DEFAULT_SMALL
            ViewMode.LIST_MEDIUM -> DEFAULT_MEDIUM
            ViewMode.LIST_LARGE -> DEFAULT_LARGE
            ViewMode.LIST_XLARGE -> DEFAULT_XLARGE
            else -> DEFAULT_MEDIUM
        }
    }
}
