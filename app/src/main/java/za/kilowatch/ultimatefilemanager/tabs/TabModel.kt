package za.kilowatch.ultimatefilemanager.tabs

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
    val storageLabel: String,
    var isEditing: Boolean = false,
    var scrollPosition: Int = 0
) {
    fun getIconRes(): Int {
        return when (storageType) {
            StorageType.LOCAL -> R.drawable.ic_storage_internal
            StorageType.SAF -> R.drawable.ic_storage_sdcard
            StorageType.NETWORK -> R.drawable.ic_network
            StorageType.CLOUD -> R.drawable.ic_cloud
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
                    storageLabel = storageLabel,
                    scrollPosition = scrollPosition
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
