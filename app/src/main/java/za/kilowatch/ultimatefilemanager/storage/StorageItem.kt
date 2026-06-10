package za.kilowatch.ultimatefilemanager.storage

import android.graphics.Color
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R

/**
 * Represents a mounted storage volume.
 *
 * @param id         Unique identifier (e.g. volume UUID or path)
 * @param label      User-facing name (e.g. "Internal Storage")
 * @param iconRes    Drawable resource for the icon
 * @param totalBytes Total capacity in bytes
 * @param usedBytes  Used space in bytes
 * @param mountPath  Absolute path to the mount point
 * @param isRemovable Whether this is a removable device (USB, SD)
 * @param isNewlyMounted Whether this device was just inserted (shows NEW badge)
 */
data class StorageItem(
    val id: String,
    val label: String,
    val iconRes: Int,
    val totalBytes: Long,
    val usedBytes: Long,
    val mountPath: String,
    val isRemovable: Boolean = false,
    var isNewlyMounted: Boolean = false,
    val isAppsTile: Boolean = false,
    val isRemoteTile: Boolean = false,
    val isSearchTile: Boolean = false,
    val isAnalyzerTile: Boolean = false,
    val isVaultTile: Boolean = false,
    val isTvRemoteTile: Boolean = false,
    val isSettingsTile: Boolean = false,
    val isLegalTile: Boolean = false,
    val isRateUsTile: Boolean = false,
    val isSafTile: Boolean = false,
    val isNetworkTile: Boolean = false,
    val isNetworkRoot: Boolean = false,
    val isPairedDevicesTile: Boolean = false,
    val isExtractsTile: Boolean = false,
    val isTipJarTile: Boolean = false,
    val isSyncTile: Boolean = false,
    val isTwinWindowTile: Boolean = false,
    val isTerminalTile: Boolean = false,
    val isShizukuTile: Boolean = false,
    val isOnlineStoragesTile: Boolean = false,
    val isOnlineStorage: Boolean = false,
    val isFileServerTile: Boolean = false,
    val isAboutTile: Boolean = false,
    val isNotepadTile: Boolean = false,
    val isScannerTile: Boolean = false,
    val isSmartSortTile: Boolean = false,
    val networkShare: za.kilowatch.ultimatefilemanager.network.NetworkShare? = null,
    val onlineStorage: za.kilowatch.ultimatefilemanager.network.OnlineStorage? = null,
    val subtitle: String? = null,   // Optional override shown instead of auto-generated subtitle
    val isIndexed: Boolean = false,
    val indexedFileCount: Long = 0,
    val isFavoriteTile: Boolean = false,
    val isRecycleBinTile: Boolean = false,
    val favoritePath: String? = null,
    val favoriteIsFolder: Boolean = false,
    val favoriteIsNetwork: Boolean = false,
    val colorConfig: TileColorConfig = TileColorConfig()
) {
    val freeBytes: Long get() = totalBytes - usedBytes

    /** True for tiles that must always remain at the bottom and cannot be moved or dragged. */
    val isLocked: Boolean get() = isLegalTile || isRateUsTile || isTipJarTile

    /**
     * True for tiles that the user is allowed to hide via the drag-to-FAB gesture or
     * edit-mode ✕ button.
     * Raw storage volumes (internal, SD, USB) are excluded because hiding them would
     * be confusing — the user would lose access to their primary storage.
     * Network roots (SMB/FTP/TV) are also excluded (they are managed via their own screens).
     * NOTE: isLocked tiles (Rate Us, Legal, Tip Jar) ARE hideable — they are only
     * position-locked (pinned to the bottom), not visibility-locked.
     */
    val isHideable: Boolean get() =
        !isNetworkRoot &&
        (isAppsTile || isRemoteTile || isSearchTile || isAnalyzerTile ||
         isVaultTile || isTvRemoteTile || isSafTile || isNetworkTile ||
         isPairedDevicesTile || isExtractsTile || isSyncTile || isSettingsTile ||
         isFavoriteTile || isTwinWindowTile || isTerminalTile || isShizukuTile ||
          isOnlineStoragesTile || isFileServerTile || isAboutTile ||
            isRecycleBinTile || isLegalTile || isRateUsTile || isTipJarTile ||
            isNotepadTile || isScannerTile || isSmartSortTile)


    val usagePercent: Int get() {
        if (totalBytes <= 0) return 0
        return ((usedBytes.toFloat() / totalBytes.toFloat()) * 100).toInt()
    }

    companion object {
        fun iconForType(isRemovable: Boolean, description: String): Int {
            val lower = description.lowercase()
            return when {
                lower.contains("usb") -> R.drawable.ic_storage_usb
                isRemovable -> R.drawable.ic_storage_sdcard
                else -> R.drawable.ic_storage_internal
            }
        }
    }
}

data class TileColorConfig(
    val ringColor:   Int = Color.TRANSPARENT,
    val iconColor:   Int = Color.TRANSPARENT,
    val iconBgColor: Int = Color.TRANSPARENT,
    val tileBgColor: Int = Color.TRANSPARENT,
    val labelColor:  Int = Color.TRANSPARENT
) {
    companion object {
        fun fromJson(json: JSONObject): TileColorConfig {
            return TileColorConfig(
                ringColor   = json.optInt("ring",      Color.TRANSPARENT),
                iconColor   = json.optInt("iconColor", Color.TRANSPARENT),
                iconBgColor = json.optInt("iconBg",   Color.TRANSPARENT),
                tileBgColor = json.optInt("tileBg",   Color.TRANSPARENT),
                labelColor  = json.optInt("label",    Color.TRANSPARENT)
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("ring",      ringColor)
            put("iconColor", iconColor)
            put("iconBg",    iconBgColor)
            put("tileBg",    tileBgColor)
            put("label",     labelColor)
        }
    }
}

/**
 * Describes a custom icon configuration for a tile.
 * Passed from the color sheet to the activity on Done.
 */
data class TileIconConfig(
    val selectedIconRes: Int,
    val customIconPath: String?,
    val originalIconRes: Int
) {
    val hasCustomIcon: Boolean get() = customIconPath != null || selectedIconRes != originalIconRes
    val isBuiltinSelection: Boolean get() = selectedIconRes != originalIconRes && customIconPath == null
    val effectiveIconRes: Int get() = if (customIconPath != null) 0 else selectedIconRes
}
