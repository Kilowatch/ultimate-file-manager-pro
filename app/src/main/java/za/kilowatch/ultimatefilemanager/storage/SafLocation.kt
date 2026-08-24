package za.kilowatch.ultimatefilemanager.storage

import org.json.JSONObject
import java.util.UUID

/**
 * Represents a user-added storage location mounted via Android's Storage Access Framework (SAF).
 * Allows persistent access to external document providers such as Termux, external SD/USB drives,
 * cloud provider folders, or arbitrary local directories.
 */
data class SafLocation(
    val id: String = UUID.randomUUID().toString(),
    var displayName: String,
    val treeUriString: String,
    val authority: String,
    val rootDocId: String,
    var customColor: String? = null,
    var iconType: String = "folder",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getDisplayPath(): String {
        val decodedDocId = try { android.net.Uri.decode(rootDocId) } catch (_: Exception) { rootDocId }
        return when {
            authority.contains("termux") -> {
                val clean = decodedDocId.trim()
                when {
                    clean.startsWith("/data/data/com.termux/files/") -> clean
                    clean.startsWith("/") -> clean
                    clean.isEmpty() || clean == "home" -> "/data/data/com.termux/files/home"
                    else -> "/data/data/com.termux/files/$clean"
                }
            }
            authority == "com.android.externalstorage.documents" -> {
                if (decodedDocId.startsWith("primary:")) {
                    val sub = decodedDocId.removePrefix("primary:").trimStart('/')
                    if (sub.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$sub"
                } else if (decodedDocId.contains(":")) {
                    val uuid = decodedDocId.substringBefore(":")
                    val sub = decodedDocId.substringAfter(":").trimStart('/')
                    if (sub.isEmpty()) "/storage/$uuid" else "/storage/$uuid/$sub"
                } else if (decodedDocId.startsWith("/")) {
                    decodedDocId
                } else {
                    decodedDocId
                }
            }
            authority.contains("downloads") -> {
                if (decodedDocId.startsWith("raw:")) {
                    decodedDocId.removePrefix("raw:")
                } else {
                    "/storage/emulated/0/Download"
                }
            }
            decodedDocId.isNotEmpty() -> decodedDocId
            else -> {
                try {
                    val parsed = android.net.Uri.parse(treeUriString)
                    android.net.Uri.decode(parsed.lastPathSegment ?: authority)
                } catch (_: Exception) {
                    authority
                }
            }
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("displayName", displayName)
            put("treeUriString", treeUriString)
            put("authority", authority)
            put("rootDocId", rootDocId)
            put("customColor", customColor ?: "")
            put("iconType", iconType)
            put("createdAt", createdAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SafLocation {
            return SafLocation(
                id = json.optString("id", UUID.randomUUID().toString()),
                displayName = json.optString("displayName", "Custom Storage"),
                treeUriString = json.optString("treeUriString", ""),
                authority = json.optString("authority", ""),
                rootDocId = json.optString("rootDocId", ""),
                customColor = json.optString("customColor").ifEmpty { null },
                iconType = json.optString("iconType", "folder"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
