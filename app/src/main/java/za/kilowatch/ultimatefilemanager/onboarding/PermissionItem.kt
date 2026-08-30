package za.kilowatch.ultimatefilemanager.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Represents a permission card shown on the Welcome Screen.
 */
data class PermissionItem(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
    val iconRes: Int,
    val permissions: List<String>,
    val isOptional: Boolean = false,
    val isSpecialAccess: Boolean = false,
    var status: PermissionStatus = PermissionStatus.NOT_REQUESTED
)

enum class PermissionStatus {
    NOT_REQUESTED,
    GRANTED,
    DENIED
}

/**
 * Factory providing the list of permission items to display on the Welcome Screen.
 * Handles API-level differences and hides permissions that cannot be granted
 * on the current device (e.g. Android TV missing certain Settings pages).
 */
object PermissionItemFactory {

    fun createPermissionItems(context: Context): List<PermissionItem> {
        val items = mutableListOf<PermissionItem>()
        val isTv = DeviceUtils.isTvDevice(context)

        // 1. Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            items.add(
                PermissionItem(
                    id = "notifications",
                    titleRes = R.string.perm_notifications_title,
                    descRes = R.string.perm_notifications_desc,
                    iconRes = R.drawable.ic_notifications,
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)
                )
            )
        }

        // 2. Storage permissions — API-dependent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!isTv) {
                // Mobile API 30+: Storage Access allows Selected Folders or All Files Access
                items.add(
                    PermissionItem(
                        id = "storage_access",
                        titleRes = R.string.perm_storage_access_title,
                        descRes = R.string.perm_storage_access_desc,
                        iconRes = R.drawable.ic_folder,
                        permissions = listOf(),
                        isSpecialAccess = true
                    )
                )
            } else {
                // TV API 30+: All Files Access supersedes all media permissions
                items.add(
                    PermissionItem(
                        id = "all_files",
                        titleRes = R.string.perm_all_files_title,
                        descRes = R.string.perm_all_files_desc,
                        iconRes = R.drawable.ic_folder,
                        permissions = listOf(Manifest.permission.MANAGE_EXTERNAL_STORAGE),
                        isSpecialAccess = true
                    )
                )
            }
        } else {
            // Pre-30: no MANAGE_EXTERNAL_STORAGE, use legacy storage permission
            items.add(
                PermissionItem(
                    id = "media",
                    titleRes = R.string.perm_media_title,
                    descRes = R.string.perm_media_desc,
                    iconRes = R.drawable.ic_photo_video,
                    permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            )
        }

        // 3. App Access — QUERY_ALL_PACKAGES (API 30+, mandatory)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            items.add(
                PermissionItem(
                    id = "query_apps",
                    titleRes = R.string.perm_query_apps_title,
                    descRes = R.string.perm_query_apps_desc,
                    iconRes = R.drawable.ic_apps,
                    permissions = listOf(),  // Manifest-only permission, no runtime request
                    isOptional = false,
                    isSpecialAccess = true
                )
            )
        }

        // 4. Install Apps (optional) — only show if the Settings page exists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val installIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            val canResolve = installIntent.resolveActivity(context.packageManager) != null

            if (canResolve || !isTv) {
                items.add(
                    PermissionItem(
                        id = "install_apps",
                        titleRes = R.string.perm_install_apps_title,
                        descRes = R.string.perm_install_apps_desc,
                        iconRes = R.drawable.ic_install,
                        permissions = listOf(Manifest.permission.REQUEST_INSTALL_PACKAGES),
                        isOptional = true,
                        isSpecialAccess = true
                    )
                )
            }
        }

        return items
    }
}
