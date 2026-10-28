package za.kilowatch.ultimatefilemanager.storage

import android.graphics.drawable.Drawable

/**
 * High-speed native cloud or network protocol supported directly by UFM's
 * internal engines (RClone, SMB, WebDAV, etc.).
 */
enum class NativeCloudType {
    PROTON_DRIVE,
    GOOGLE_DRIVE,
    SYNOLOGY,
    WEBDAV,
    TERMUX
}

/**
 * Metadata representing an installed Storage Access Framework (SAF) DocumentsProvider.
 */
data class DocumentProviderInfo(
    val authority: String,
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
    val isTreeCapable: Boolean = false,
    val isNativeSupported: Boolean = false,
    val nativeType: NativeCloudType? = null
)
