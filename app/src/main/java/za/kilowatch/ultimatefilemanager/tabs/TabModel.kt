package za.kilowatch.ultimatefilemanager.tabs

import android.graphics.Color
import androidx.annotation.Keep
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R
import java.util.UUID

@Keep
enum class StorageType {
    LOCAL,
    SAF,
    NETWORK,
    CLOUD
}

@Keep
data class TabModel(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var isCustomName: Boolean = false,
    val storageType: StorageType,
    val rootPath: String,
    var currentPath: String,
    val shareId: String? = null,
    val protocol: String? = null,
    val storageLabel: String,
    var isEditing: Boolean = false,
    var scrollPosition: Int = 0
) {
    fun getIconRes(): Int {
        val proto = protocol?.uppercase()
        if (proto == "USB" || rootPath.contains("usb", ignoreCase = true) || storageLabel.contains("usb", ignoreCase = true)) {
            return R.drawable.ic_storage_usb
        }
        return when (storageType) {
            StorageType.LOCAL -> R.drawable.ic_storage_internal
            StorageType.SAF -> R.drawable.ic_storage_sdcard
            StorageType.NETWORK -> R.drawable.ic_network
            StorageType.CLOUD -> R.drawable.ic_cloud
        }
    }

    fun getAccentColor(): Int {
        val proto = protocol?.uppercase()
        val isUsb = proto == "USB" || rootPath.contains("usb", ignoreCase = true) || storageLabel.contains("usb", ignoreCase = true)
        val isSd = storageType == StorageType.SAF || proto == "SD" || rootPath.contains("sd", ignoreCase = true) || storageLabel.contains("sd", ignoreCase = true)

        return when {
            // USB Removable Drive
            isUsb -> Color.parseColor("#FF5722") // Coral / Deep Orange

            // SD Card
            isSd -> Color.parseColor("#FF9100") // Vivid Amber / Orange

            // SMB / Windows Share / NAS
            proto == "SMB" || (storageType == StorageType.NETWORK && (proto == null || proto.contains("SMB"))) -> {
                Color.parseColor("#00E676") // Emerald Green
            }

            // FTP / SFTP / SSH
            proto == "FTP" || proto == "SFTP" || proto == "SCP" -> {
                Color.parseColor("#A855F7") // Electric Purple
            }

            // NFS / WebDAV / DLNA / TV
            proto == "NFS" || proto == "WEBDAV" || proto == "DLNA" || proto == "TV" -> {
                Color.parseColor("#14B8A6") // Mint / Teal
            }

            // Cloud (Google Drive, OneDrive, Dropbox, S3, Box)
            storageType == StorageType.CLOUD || proto in listOf("GOOGLE_DRIVE", "ONEDRIVE", "DROPBOX", "AWS_S3", "IDRIVE_E2", "BOX") -> {
                Color.parseColor("#00E5FF") // Sky Blue / Turquoise
            }

            // Internal Storage (Default Local)
            else -> Color.parseColor("#2979FF") // Vibrant Azure Blue
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("isCustomName", isCustomName)
            put("storageType", storageType.name)
            put("rootPath", rootPath)
            put("currentPath", currentPath)
            put("shareId", shareId ?: "")
            put("protocol", protocol ?: "")
            put("storageLabel", storageLabel)
            put("scrollPosition", scrollPosition)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): TabModel? {
            return try {
                val id = obj.optString("id", UUID.randomUUID().toString())
                val title = obj.getString("title")
                val isCustomName = obj.optBoolean("isCustomName", false)
                val typeStr = obj.optString("storageType", StorageType.LOCAL.name)
                val storageType = try {
                    StorageType.valueOf(typeStr)
                } catch (_: Exception) {
                    StorageType.LOCAL
                }
                val rootPath = obj.optString("rootPath", "")
                val currentPath = obj.optString("currentPath", rootPath)
                val shareIdRaw = obj.optString("shareId", "")
                val shareId = if (shareIdRaw.isNotEmpty()) shareIdRaw else null
                val protocolRaw = obj.optString("protocol", "")
                val protocol = if (protocolRaw.isNotEmpty()) protocolRaw else null
                val storageLabel = obj.optString("storageLabel", title)
                val scrollPosition = obj.optInt("scrollPosition", 0)

                TabModel(
                    id = id,
                    title = title,
                    isCustomName = isCustomName,
                    storageType = storageType,
                    rootPath = rootPath,
                    currentPath = currentPath,
                    shareId = shareId,
                    protocol = protocol,
                    storageLabel = storageLabel,
                    scrollPosition = scrollPosition
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
