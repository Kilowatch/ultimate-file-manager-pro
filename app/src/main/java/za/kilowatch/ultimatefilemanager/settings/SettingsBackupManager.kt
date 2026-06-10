package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Environment
import android.util.Log
import za.kilowatch.ultimatefilemanager.R
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorage
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.server.FtpServerProfile
import za.kilowatch.ultimatefilemanager.server.FtpServerProfileRepository
import za.kilowatch.ultimatefilemanager.server.LocationType
import za.kilowatch.ultimatefilemanager.settings.renamer.StorageRenameManager
import za.kilowatch.ultimatefilemanager.smartsort.SmartSortSavedConfig
import za.kilowatch.ultimatefilemanager.smartsort.SmartSortSavedConfigRepository
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BackupItem(
    val id: String,
    val category: String, // "shared_preferences", "network_shares", "online_storages", "ftp_server_profiles", "storage_renames"
    val displayName: String,
    val extraInfo: String = "",
    var isSelected: Boolean = true
)

data class BackupDetails(
    val sharedPrefs: List<BackupItem>,
    val shares: List<BackupItem>,
    val storages: List<BackupItem>,
    val ftpProfiles: List<BackupItem>,
    val renames: List<BackupItem>,
    val smartSortConfigs: List<BackupItem>,
    val rawJson: String
)

object SettingsBackupManager {
    private const val TAG = "SettingsBackupManager"
    private const val MAGIC_HEADER = "UFM_PRO_CFG" // 11 bytes
    private const val AAD_STRING = "UFM_PRO_AAD_V1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128

    private fun getSecretKey(): SecretKeySpec {
        val passphrase = "za.kilowatch.ultimatefilemanager.backup.secret.key.v1"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(passphrase.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptBackup(payload: String): ByteArray {
        val key = getSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE, iv))
        cipher.updateAAD(AAD_STRING.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

        val headerBytes = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
        val finalBytes = ByteArray(headerBytes.size + iv.size + ciphertext.size)
        System.arraycopy(headerBytes, 0, finalBytes, 0, headerBytes.size)
        System.arraycopy(iv, 0, finalBytes, headerBytes.size, iv.size)
        System.arraycopy(ciphertext, 0, finalBytes, headerBytes.size + iv.size, ciphertext.size)
        return finalBytes
    }

    fun decryptBackup(encryptedBytes: ByteArray): String {
        val headerBytes = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
        if (encryptedBytes.size < headerBytes.size + IV_SIZE) {
            throw IllegalArgumentException("Invalid backup file: file too small")
        }
        for (i in headerBytes.indices) {
            if (encryptedBytes[i] != headerBytes[i]) {
                throw IllegalArgumentException("Invalid backup file: magic header mismatch")
            }
        }
        val iv = ByteArray(IV_SIZE)
        System.arraycopy(encryptedBytes, headerBytes.size, iv, 0, IV_SIZE)

        val ciphertextLen = encryptedBytes.size - headerBytes.size - IV_SIZE
        val ciphertext = ByteArray(ciphertextLen)
        System.arraycopy(encryptedBytes, headerBytes.size + IV_SIZE, ciphertext, 0, ciphertextLen)

        val key = getSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE, iv))
        cipher.updateAAD(AAD_STRING.toByteArray(Charsets.UTF_8))
        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, Charsets.UTF_8)
    }

    fun getBackupDirectory(): File {
        return File(Environment.getExternalStorageDirectory(), "UFM")
    }

    fun getBackupFile(): File {
        return File(getBackupDirectory(), "ufm_backup.UFMConfig")
    }

    fun getAvailableBackupItems(context: Context): List<BackupItem> {
        val items = mutableListOf<BackupItem>()

        fun add(prefsName: String, displayName: String) {
            items.add(BackupItem(prefsName, "shared_preferences", displayName, ""))
        }

        // ── Core Settings & Customizations ─────────────────────────────────────
        add("tile_colors_prefs",         context.getString(R.string.backup_pref_tile_colors))
        add("ufm_prefs",                 context.getString(R.string.backup_pref_theme_sort))
        add("ufm_hidden_files_prefs",    context.getString(R.string.backup_pref_hidden_files))
        add("ufm_main_menu_prefs",       context.getString(R.string.backup_pref_main_menu))
        add("ufm_long_press_prefs",      context.getString(R.string.backup_pref_long_press))
        add("ufm_date_group_prefs",      context.getString(R.string.backup_pref_date_group))
        add("default_start_screen_prefs",context.getString(R.string.backup_pref_default_start))
        add("toolbar_icons_prefs",       context.getString(R.string.backup_pref_toolbar_icons))
        add("twin_window_prefs",         context.getString(R.string.backup_pref_twin_window))
        add("tile_order_prefs",          context.getString(R.string.backup_pref_tile_order))
        add("ufm_favorites_prefs",       context.getString(R.string.backup_pref_favorites))
        add("ufm_default_apps",          context.getString(R.string.backup_pref_default_apps))
        add("thumbnail_prefs",           context.getString(R.string.backup_pref_thumbnails))
        add("network_thumbnail_prefs",   context.getString(R.string.backup_pref_network_thumbnails))
        add("network_open_cache_prefs",  context.getString(R.string.backup_pref_network_cache))
        add("side_by_side_video_prefs",  context.getString(R.string.backup_pref_side_by_side))
        add("ufm_quick_transfer_prefs",  context.getString(R.string.backup_pref_quick_transfer))
        add("recycle_bin_prefs",         context.getString(R.string.backup_pref_recycle_bin))
        add("apk_extract_prefs",         context.getString(R.string.backup_pref_apk_extract))
        add("cache_copy_prefs",          context.getString(R.string.backup_pref_cache_copy))
        add("video_thumbnail_time_prefs",context.getString(R.string.backup_pref_video_thumb_time))

        val shares = NetworkShareRepository.getInstance(context).getAll()
        for (share in shares) {
            if (share.type != ShareType.ONEDRIVE && share.type != ShareType.GOOGLE_DRIVE && share.type != ShareType.DROPBOX) {
                items.add(BackupItem(share.id, "network_shares", share.name, context.getString(R.string.backup_format_type_host, share.type.toString(), share.host)))
            }
        }

        val storages = OnlineStorageRepository.getInstance(context).getAll()
        for (storage in storages) {
            if (storage.provider != OnlineStorageProvider.ONEDRIVE &&
                storage.provider != OnlineStorageProvider.GOOGLE_DRIVE &&
                storage.provider != OnlineStorageProvider.DROPBOX) {
                items.add(BackupItem(storage.id, "online_storages", storage.displayName.takeIf { it.isNotEmpty() } ?: storage.email, "${storage.provider}"))
            }
        }

        val ftpProfiles = FtpServerProfileRepository.getInstance(context).getAll()
        for (profile in ftpProfiles) {
            items.add(BackupItem(profile.id, "ftp_server_profiles", profile.username, context.getString(R.string.backup_format_location, profile.defaultLocationLabel)))
        }

        val renames = StorageRenameManager.getInstance(context).getAllRenamesSync()
        for (rename in renames) {
            items.add(BackupItem(rename.deviceId, "storage_renames", rename.customName, context.getString(R.string.backup_format_original, rename.originalName)))
        }

        val smartSortConfigs = try {
            SmartSortSavedConfigRepository.getAll(context)
        } catch (e: Exception) {
            emptyList()
        }
        for (config in smartSortConfigs) {
            items.add(BackupItem(config.id, "smart_sort_configs", config.folderPath, config.description))
        }

        return items
    }

    fun performExport(context: Context, selectedItems: List<BackupItem>, targetFile: File): Boolean {
        try {
            val root = JSONObject()
            root.put("version", 1)

            val selectedIds = selectedItems.filter { it.isSelected }.map { it.id }.toSet()

            val prefsObj = JSONObject()
            for (item in selectedItems) {
                if (item.category == "shared_preferences" && item.isSelected) {
                    val prefs = context.getSharedPreferences(item.id, Context.MODE_PRIVATE)
                    val fileObj = JSONObject()
                    for ((k, v) in prefs.all) {
                        when (v) {
                            is Int -> fileObj.put(k, v)
                            is Boolean -> fileObj.put(k, v)
                            is Long -> fileObj.put(k, v)
                            is Float -> fileObj.put(k, v.toDouble())
                            is Double -> fileObj.put(k, v)
                            is String -> fileObj.put(k, v)
                        }
                    }
                    prefsObj.put(item.id, fileObj)
                }
            }
            root.put("shared_preferences", prefsObj)

            val sharesArr = JSONArray()
            val shares = NetworkShareRepository.getInstance(context).getAll()
            for (share in shares) {
                if (selectedIds.contains(share.id)) {
                    val obj = JSONObject()
                    obj.put("id", share.id)
                    obj.put("name", share.name)
                    obj.put("type", share.type.name)
                    obj.put("host", share.host)
                    obj.put("port", share.port)
                    obj.put("username", share.username)
                    
                    obj.put("password", "")
                    obj.put("privateKeyPath", "")
                    
                    obj.put("domain", share.domain)
                    obj.put("remotePath", share.remotePath)
                    obj.put("readOnly", share.readOnly)
                    obj.put("useKeychain", share.useKeychain)
                    obj.put("smbProtocol", share.smbProtocol)
                    obj.put("isCredentialsStripped", true)
                    sharesArr.put(obj)
                }
            }
            root.put("network_shares", sharesArr)

            val onlineArr = JSONArray()
            val storages = OnlineStorageRepository.getInstance(context).getAll()
            for (storage in storages) {
                if (selectedIds.contains(storage.id)) {
                    val obj = JSONObject()
                    obj.put("id", storage.id)
                    obj.put("provider", storage.provider.name)
                    obj.put("email", storage.email)
                    obj.put("displayName", storage.displayName)
                    
                    obj.put("refreshToken", "")
                    if (storage.isS3Provider) {
                        obj.put("s3Endpoint", storage.s3Endpoint ?: "")
                        obj.put("s3Bucket", storage.s3Bucket ?: "")
                        obj.put("s3Region", storage.s3Region ?: "")
                        obj.put("s3AccessKey", storage.s3AccessKey ?: "")
                        obj.put("s3SecretKey", "")
                    }
                    if (storage.isWebDavProvider) {
                        obj.put("webDavUrl", storage.webDavUrl ?: "")
                        obj.put("webDavUsername", storage.webDavUsername ?: "")
                        obj.put("webDavPassword", "")
                    }
                    obj.put("isCredentialsStripped", true)
                    onlineArr.put(obj)
                }
            }
            root.put("online_storages", onlineArr)

            val ftpArr = JSONArray()
            val ftpProfiles = FtpServerProfileRepository.getInstance(context).getAll()
            for (profile in ftpProfiles) {
                if (selectedIds.contains(profile.id)) {
                    val obj = JSONObject()
                    obj.put("id", profile.id)
                    obj.put("username", profile.username)
                    
                    obj.put("encryptedPassword", "")
                    
                    obj.put("defaultLocationUri", profile.defaultLocationUri)
                    obj.put("defaultLocationLabel", profile.defaultLocationLabel)
                    obj.put("locationType", profile.locationType.name)
                    obj.put("locationMetaId", profile.locationMetaId ?: "")
                    obj.put("readOnly", profile.readOnly)
                    obj.put("authorizedKeys", profile.authorizedKeys)
                    obj.put("isCredentialsStripped", true)
                    ftpArr.put(obj)
                }
            }
            root.put("ftp_server_profiles", ftpArr)

            val renameArr = JSONArray()
            val renames = StorageRenameManager.getInstance(context).getAllRenamesSync()
            for (rename in renames) {
                if (selectedIds.contains(rename.deviceId)) {
                    val obj = JSONObject()
                    obj.put("deviceId", rename.deviceId)
                    obj.put("customName", rename.customName)
                    obj.put("originalName", rename.originalName)
                    obj.put("totalBytes", rename.totalBytes)
                    renameArr.put(obj)
                }
            }
            root.put("storage_renames", renameArr)

            val smartSortArr = JSONArray()
            val smartSortConfigs = try {
                SmartSortSavedConfigRepository.getAll(context)
            } catch (e: Exception) {
                emptyList()
            }
            for (config in smartSortConfigs) {
                if (selectedIds.contains(config.id)) {
                    val obj = JSONObject()
                    obj.put("id", config.id)
                    obj.put("folderPath", config.folderPath)
                    obj.put("description", config.description)
                    obj.put("savedAt", config.savedAt)
                    obj.put("configJson", config.configJson)
                    smartSortArr.put(obj)
                }
            }
            root.put("smart_sort_configs", smartSortArr)

            val jsonString = root.toString(2)
            val encryptedData = encryptBackup(jsonString)

            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { out ->
                out.write(encryptedData)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export backup", e)
            return false
        }
    }

    fun parseBackupContent(context: Context, jsonString: String): BackupDetails {
        val root = JSONObject(jsonString)

        val sharedPrefs = mutableListOf<BackupItem>()
        val prefsObj = root.optJSONObject("shared_preferences")
        if (prefsObj != null) {
            val keys = prefsObj.keys()
            val prefsMapping = mapOf(
                "tile_colors_prefs"         to R.string.backup_pref_tile_colors,
                "ufm_prefs"                 to R.string.backup_pref_theme_sort,
                "ufm_hidden_files_prefs"    to R.string.backup_pref_hidden_files,
                "ufm_main_menu_prefs"       to R.string.backup_pref_main_menu,
                "ufm_long_press_prefs"      to R.string.backup_pref_long_press,
                "ufm_favorites_prefs"       to R.string.backup_pref_favorites,
                "ufm_default_apps"          to R.string.backup_pref_default_apps,
                "apk_extract_prefs"         to R.string.backup_pref_apk_extract,
                "ufm_date_group_prefs"      to R.string.backup_pref_date_group,
                "default_start_screen_prefs" to R.string.backup_pref_default_start,
                "twin_window_prefs"         to R.string.backup_pref_twin_window,
                "thumbnail_prefs"           to R.string.backup_pref_thumbnails,
                "video_thumbnail_time_prefs" to R.string.backup_pref_video_thumb_time,
                "network_open_cache_prefs"  to R.string.backup_pref_network_cache,
                "network_thumbnail_prefs"   to R.string.backup_pref_network_thumbnails,
                "side_by_side_video_prefs"  to R.string.backup_pref_side_by_side,
                "ufm_quick_transfer_prefs"  to R.string.backup_pref_quick_transfer,
                "toolbar_icons_prefs"       to R.string.backup_pref_toolbar_icons,
                "recycle_bin_prefs"         to R.string.backup_pref_recycle_bin,
                "tile_order_prefs"          to R.string.backup_pref_tile_order,
                "cache_copy_prefs"          to R.string.backup_pref_cache_copy
            )
            while (keys.hasNext()) {
                val key = keys.next()
                val displayName = context.getString(prefsMapping[key] ?: R.string.backup_restore_title)
                sharedPrefs.add(BackupItem(key, "shared_preferences", displayName, ""))
            }
        }

        val shares = mutableListOf<BackupItem>()
        val sharesArr = root.optJSONArray("network_shares")
        if (sharesArr != null) {
            for (i in 0 until sharesArr.length()) {
                val obj = sharesArr.getJSONObject(i)
                shares.add(BackupItem(obj.getString("id"), "network_shares", obj.getString("name"), context.getString(R.string.backup_format_type_host, obj.getString("type"), obj.getString("host"))))
            }
        }

        val storages = mutableListOf<BackupItem>()
        val onlineArr = root.optJSONArray("online_storages")
        if (onlineArr != null) {
            for (i in 0 until onlineArr.length()) {
                val obj = onlineArr.getJSONObject(i)
                val label = obj.optString("displayName").takeIf { it.isNotEmpty() } ?: obj.optString("email")
                storages.add(BackupItem(obj.getString("id"), "online_storages", label, obj.getString("provider")))
            }
        }

        val ftpProfiles = mutableListOf<BackupItem>()
        val ftpArr = root.optJSONArray("ftp_server_profiles")
        if (ftpArr != null) {
            for (i in 0 until ftpArr.length()) {
                val obj = ftpArr.getJSONObject(i)
                ftpProfiles.add(BackupItem(obj.getString("id"), "ftp_server_profiles", obj.getString("username"), context.getString(R.string.backup_format_location, obj.optString("defaultLocationLabel"))))
            }
        }

        val renames = mutableListOf<BackupItem>()
        val renameArr = root.optJSONArray("storage_renames")
        if (renameArr != null) {
            for (i in 0 until renameArr.length()) {
                val obj = renameArr.getJSONObject(i)
                renames.add(BackupItem(obj.getString("deviceId"), "storage_renames", obj.getString("customName"), context.getString(R.string.backup_format_original, obj.optString("originalName"))))
            }
        }

        val smartSortConfigs = mutableListOf<BackupItem>()
        val smartSortArr = root.optJSONArray("smart_sort_configs")
        if (smartSortArr != null) {
            for (i in 0 until smartSortArr.length()) {
                val obj = smartSortArr.getJSONObject(i)
                smartSortConfigs.add(BackupItem(obj.getString("id"), "smart_sort_configs", obj.getString("folderPath"), obj.optString("description", "")))
            }
        }

        return BackupDetails(sharedPrefs, shares, storages, ftpProfiles, renames, smartSortConfigs, jsonString)
    }

    suspend fun performRestore(context: Context, details: BackupDetails): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val root = JSONObject(details.rawJson)

                val prefsObj = root.optJSONObject("shared_preferences")
                if (prefsObj != null) {
                    val keys = prefsObj.keys()
                    while (keys.hasNext()) {
                        val prefsName = keys.next()
                        val fileObj = prefsObj.getJSONObject(prefsName)
                        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                        val editor = prefs.edit()
                        editor.clear()
                        val valKeys = fileObj.keys()
                        while (valKeys.hasNext()) {
                            val k = valKeys.next()
                            when (val v = fileObj.get(k)) {
                                is Int -> editor.putInt(k, v)
                                is Boolean -> editor.putBoolean(k, v)
                                is Long -> editor.putLong(k, v)
                                is Double -> editor.putFloat(k, v.toFloat())
                                is String -> editor.putString(k, v)
                            }
                        }
                        editor.commit()
                    }
                }

                val sharesArr = root.optJSONArray("network_shares")
                if (sharesArr != null) {
                    val repo = NetworkShareRepository.getInstance(context)
                    repo.clearAll()
                    for (i in 0 until sharesArr.length()) {
                        val obj = sharesArr.getJSONObject(i)
                        val share = NetworkShare(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            type = ShareType.valueOf(obj.getString("type")),
                            host = obj.getString("host"),
                            port = obj.optInt("port", 0),
                            username = obj.optString("username", ""),
                            password = obj.optString("password", ""),
                            domain = obj.optString("domain", "WORKGROUP"),
                            remotePath = obj.optString("remotePath", ""),
                            readOnly = obj.optBoolean("readOnly", true),
                            privateKeyPath = obj.optString("privateKeyPath", "").takeIf { it.isNotEmpty() },
                            useKeychain = obj.optBoolean("useKeychain", false),
                            smbProtocol = obj.optString("smbProtocol", "AUTO"),
                            isCredentialsStripped = obj.optBoolean("isCredentialsStripped", true)
                        )
                        repo.save(share)
                    }
                }

                val onlineArr = root.optJSONArray("online_storages")
                if (onlineArr != null) {
                    val repo = OnlineStorageRepository.getInstance(context)
                    repo.clearAll()
                    for (i in 0 until onlineArr.length()) {
                        val obj = onlineArr.getJSONObject(i)
                        val provider = OnlineStorageProvider.valueOf(obj.getString("provider"))
                        val storage = OnlineStorage(
                            id = obj.getString("id"),
                            provider = provider,
                            email = obj.optString("email", ""),
                            displayName = obj.optString("displayName", ""),
                            refreshToken = null,
                            s3Endpoint = obj.optString("s3Endpoint", "").takeUnless { it.isNullOrEmpty() },
                            s3Bucket = obj.optString("s3Bucket", "").takeUnless { it.isNullOrEmpty() },
                            s3Region = obj.optString("s3Region", "").takeUnless { it.isNullOrEmpty() },
                            s3AccessKey = obj.optString("s3AccessKey", "").takeUnless { it.isNullOrEmpty() },
                            s3SecretKey = null,
                            webDavUrl = obj.optString("webDavUrl", "").takeUnless { it.isNullOrEmpty() },
                            webDavUsername = obj.optString("webDavUsername", "").takeUnless { it.isNullOrEmpty() },
                            webDavPassword = null,
                            isCredentialsStripped = obj.optBoolean("isCredentialsStripped", true)
                        )
                        repo.save(storage)
                    }
                }

                val ftpArr = root.optJSONArray("ftp_server_profiles")
                if (ftpArr != null) {
                    val repo = FtpServerProfileRepository.getInstance(context)
                    repo.clearAll()
                    for (i in 0 until ftpArr.length()) {
                        val obj = ftpArr.getJSONObject(i)
                        val profile = FtpServerProfile(
                            id = obj.getString("id"),
                            username = obj.getString("username"),
                            encryptedPassword = "",
                            defaultLocationUri = obj.getString("defaultLocationUri"),
                            defaultLocationLabel = obj.optString("defaultLocationLabel", ""),
                            locationType = LocationType.valueOf(obj.optString("locationType", "LOCAL")),
                            locationMetaId = obj.optString("locationMetaId", "").takeUnless { it.isNullOrEmpty() },
                            readOnly = obj.optBoolean("readOnly", false),
                            authorizedKeys = obj.optString("authorizedKeys", ""),
                            isCredentialsStripped = obj.optBoolean("isCredentialsStripped", true)
                        )
                        repo.save(profile)
                    }
                }

                val renameArr = root.optJSONArray("storage_renames")
                if (renameArr != null) {
                    val manager = StorageRenameManager.getInstance(context)
                    manager.clearAll()
                    for (i in 0 until renameArr.length()) {
                        val obj = renameArr.getJSONObject(i)
                        manager.saveRenameByHashedId(
                            hashedId = obj.getString("deviceId"),
                            customName = obj.getString("customName"),
                            originalName = obj.getString("originalName"),
                            totalBytes = obj.getLong("totalBytes")
                        )
                    }
                }

                val smartSortArr = root.optJSONArray("smart_sort_configs")
                if (smartSortArr != null) {
                    val list = mutableListOf<SmartSortSavedConfig>()
                    for (i in 0 until smartSortArr.length()) {
                        val obj = smartSortArr.getJSONObject(i)
                        list.add(
                            SmartSortSavedConfig(
                                id = obj.getString("id"),
                                folderPath = obj.getString("folderPath"),
                                description = obj.optString("description", ""),
                                savedAt = obj.optLong("savedAt", System.currentTimeMillis()),
                                configJson = obj.getString("configJson")
                            )
                        )
                    }
                    SmartSortSavedConfigRepository.saveAll(list, context)
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore backup", e)
                false
            }
        }
    }
}
