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
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
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
    val customTiles: List<BackupItem>,
    val rawJson: String
)

object SettingsBackupManager {
    private const val TAG = "SettingsBackupManager"
    private const val MAGIC_HEADER = "UFM_PRO_CFG" // 11 bytes
    private const val AAD_STRING = "UFM_PRO_AAD_V1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128

    // ── V3 constants ──────────────────────────────────────────────────────
    private const val MAGIC_V3 = "UFM_PRO_V3"        // 11 bytes
    private const val AAD_V3 = "UFM_PRO_V3_AAD"
    private const val FLAG_ENCRYPTED: Byte = 0x01
    private const val FLAG_PLAINTEXT: Byte = 0x00
    private const val PBKDF2_ITERATIONS = 260_000
    private const val PBKDF2_KEY_LENGTH = 256
    private const val SALT_SIZE = 32

    enum class BackupFormat { V2_LEGACY, V3_ENCRYPTED, V3_PLAIN, RAW_JSON, UNKNOWN }

    private fun getSecretKey(): SecretKeySpec {
        val passphrase = "za.kilowatch.ultimatefilemanager.backup.secret.key.v1"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(passphrase.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val rawKey = factory.generateSecret(spec).encoded
        return SecretKeySpec(rawKey, "AES")
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

    fun encryptBackupV3(payload: String, password: String): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(salt)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE, iv))
        cipher.updateAAD(AAD_V3.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

        val headerBytes = MAGIC_V3.toByteArray(Charsets.US_ASCII)
        val flagByte = byteArrayOf(FLAG_ENCRYPTED)
        val finalBytes = ByteArray(headerBytes.size + 1 + salt.size + iv.size + ciphertext.size)
        var pos = 0
        System.arraycopy(headerBytes, 0, finalBytes, pos, headerBytes.size); pos += headerBytes.size
        System.arraycopy(flagByte, 0, finalBytes, pos, 1); pos += 1
        System.arraycopy(salt, 0, finalBytes, pos, salt.size); pos += salt.size
        System.arraycopy(iv, 0, finalBytes, pos, iv.size); pos += iv.size
        System.arraycopy(ciphertext, 0, finalBytes, pos, ciphertext.size)
        return finalBytes
    }

    fun encryptBackupPlain(payload: String): ByteArray {
        val headerBytes = MAGIC_V3.toByteArray(Charsets.US_ASCII)
        val flagByte = byteArrayOf(FLAG_PLAINTEXT)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val finalBytes = ByteArray(headerBytes.size + 1 + payloadBytes.size)
        var pos = 0
        System.arraycopy(headerBytes, 0, finalBytes, pos, headerBytes.size); pos += headerBytes.size
        System.arraycopy(flagByte, 0, finalBytes, pos, 1); pos += 1
        System.arraycopy(payloadBytes, 0, finalBytes, pos, payloadBytes.size)
        return finalBytes
    }

    fun decryptBackupV3(bytes: ByteArray, password: String): String {
        val headerBytes = MAGIC_V3.toByteArray(Charsets.US_ASCII)
        val minSize = headerBytes.size + 1 + SALT_SIZE + IV_SIZE + 1 // +1 for min ciphertext
        if (bytes.size < minSize) {
            throw IllegalArgumentException("Invalid backup file: file too small")
        }
        for (i in headerBytes.indices) {
            if (bytes[i] != headerBytes[i]) {
                throw IllegalArgumentException("Invalid backup file: magic header mismatch")
            }
        }
        if (bytes[headerBytes.size] != FLAG_ENCRYPTED) {
            throw IllegalArgumentException("Invalid backup file: expected encrypted flag")
        }

        var pos = headerBytes.size + 1 // skip magic + flag
        val salt = ByteArray(SALT_SIZE)
        System.arraycopy(bytes, pos, salt, 0, SALT_SIZE); pos += SALT_SIZE

        val iv = ByteArray(IV_SIZE)
        System.arraycopy(bytes, pos, iv, 0, IV_SIZE); pos += IV_SIZE

        val ciphertextLen = bytes.size - pos
        val ciphertext = ByteArray(ciphertextLen)
        System.arraycopy(bytes, pos, ciphertext, 0, ciphertextLen)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE, iv))
        cipher.updateAAD(AAD_V3.toByteArray(Charsets.UTF_8))
        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, Charsets.UTF_8)
    }

    fun detectFormat(bytes: ByteArray): BackupFormat {
        // Check for V2 legacy magic
        val v2Header = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
        if (bytes.size >= v2Header.size && v2Header.indices.all { bytes[it] == v2Header[it] }) {
            return BackupFormat.V2_LEGACY
        }
        // Check for V3 magic
        val v3Header = MAGIC_V3.toByteArray(Charsets.US_ASCII)
        if (bytes.size >= v3Header.size + 1 && v3Header.indices.all { bytes[it] == v3Header[it] }) {
            return when (bytes[v3Header.size]) {
                FLAG_ENCRYPTED -> BackupFormat.V3_ENCRYPTED
                FLAG_PLAINTEXT -> BackupFormat.V3_PLAIN
                else -> BackupFormat.UNKNOWN
            }
        }
        // Loose compat: plain JSON without any header
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            return BackupFormat.RAW_JSON
        }
        return BackupFormat.UNKNOWN
    }

    private fun decryptBackupV2(encryptedBytes: ByteArray): String {
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

    fun decryptBackup(bytes: ByteArray, password: String?): String {
        return when (detectFormat(bytes)) {
            BackupFormat.V2_LEGACY -> decryptBackupV2(bytes)
            BackupFormat.V3_ENCRYPTED -> {
                val pw = password ?: throw IllegalArgumentException("Password required for encrypted backup")
                decryptBackupV3(bytes, pw)
            }
            BackupFormat.V3_PLAIN -> {
                // Skip magic (11) + flag (1), return JSON
                val start = MAGIC_V3.toByteArray(Charsets.US_ASCII).size + 1
                String(bytes, start, bytes.size - start, Charsets.UTF_8)
            }
            BackupFormat.RAW_JSON -> {
                // No header — parse entire bytes as JSON
                String(bytes, 0, bytes.size, Charsets.UTF_8)
            }
            BackupFormat.UNKNOWN -> throw IllegalArgumentException("Unsupported backup file format")
        }
    }

    // Keep old signature for backward compat with callers that don't pass password
    @Deprecated("Use decryptBackup(bytes, password) instead", ReplaceWith("decryptBackup(bytes, null)"))
    fun decryptBackup(encryptedBytes: ByteArray): String {
        return decryptBackup(encryptedBytes, null)
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
        add("ufm_folder_sort_prefs",     context.getString(R.string.backup_pref_folder_sort))
        add("ufm_hidden_files_prefs",    context.getString(R.string.backup_pref_hidden_files))
        add("ufm_protected_files_prefs", context.getString(R.string.backup_pref_protected_files))
        add("ufm_pinned_files_prefs",    context.getString(R.string.backup_pref_pinned_files))
        add("ufm_main_menu_prefs",       context.getString(R.string.backup_pref_main_menu))
        add("ufm_long_press_prefs",      context.getString(R.string.backup_pref_long_press))
        add("ufm_controls_timeout_prefs",context.getString(R.string.backup_pref_controls_timeout))
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
        add("autoplay_prefs",            context.getString(R.string.backup_pref_autoplay))
        add("ufm_player_prefs",          context.getString(R.string.backup_pref_ufm_player))
        add("breadcrumbs_prefs",         context.getString(R.string.backup_pref_breadcrumbs))
        add("grid_indicators_prefs",     context.getString(R.string.backup_pref_grid_indicators))
        add("scrolling_text_prefs",      context.getString(R.string.backup_pref_scrolling_text))
        add("settings_search_prefs",      context.getString(R.string.backup_pref_settings_search))
        add("ufm_settings_list_prefs",    context.getString(R.string.settings_list_size_title))
        add("file_server_prefs",         context.getString(R.string.backup_pref_file_server))
        add("tile_icons_prefs",          context.getString(R.string.backup_pref_tile_icons))
        add("icon_customization_prefs",  context.getString(R.string.backup_pref_icon_customization))
        add("loyalty_prefs",             context.getString(R.string.backup_pref_loyalty))
        add("ufm_file_tags",             context.getString(R.string.backup_pref_file_tags))
        add("analytics_prefs",           context.getString(R.string.backup_pref_analytics))
        add("icon_tap_edit_mode_prefs",  context.getString(R.string.backup_pref_icon_tap_edit_mode))
        add("ufm_keyboard_shortcuts_prefs", context.getString(R.string.backup_pref_keyboard_shortcuts))
        add("ufm_saf_locations_prefs",   context.getString(R.string.backup_pref_saf_locations))
        if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(context)) {
            add("ufm_tv_server_prefs",   context.getString(R.string.backup_pref_tv_server))
        }

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

        // Custom tiles
        val customTiles = za.kilowatch.ultimatefilemanager.storage.CustomTileManager.loadCustomTiles(context)
        for (ct in customTiles) {
            val childCount = za.kilowatch.ultimatefilemanager.storage.CustomTileManager.getChildTiles(context, ct.id).size
            val extra = if (ct.subtitle.isNotEmpty()) ct.subtitle else "$childCount tiles"
            items.add(BackupItem(ct.id, "custom_tiles", ct.title, extra))
        }

        // Advanced Sync profiles
        items.add(BackupItem("advanced_sync_profiles", "advanced_sync", context.getString(R.string.backup_pref_advanced_sync), ""))

        return items
    }

    fun performExport(context: Context, selectedItems: List<BackupItem>, targetFile: File, password: String? = null): Boolean {
        try {
            val root = JSONObject()
            root.put("version", 3)

            val selectedIds = selectedItems.filter { it.isSelected }.map { it.id }.toSet()

            val prefsObj = JSONObject()
            for (item in selectedItems) {
                if (item.category == "shared_preferences" && item.isSelected) {
                    val fileObj = JSONObject()
                    if (item.id == "ufm_folder_sort_prefs") {
                        val entries = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.getAllEntriesForBackup(context)
                        for ((k, v) in entries) {
                            when (v) {
                                is Int -> fileObj.put(k, v)
                                is Boolean -> fileObj.put(k, v)
                                is Long -> fileObj.put(k, v)
                                is Float -> fileObj.put(k, v.toDouble())
                                is Double -> fileObj.put(k, v)
                                is String -> fileObj.put(k, v)
                            }
                        }
                    } else {
                        val prefs = context.getSharedPreferences(item.id, Context.MODE_PRIVATE)
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
                    obj.put("isServerMode", share.isServerMode)
                    obj.put("hostKeyFingerprint", "") // stripped — device-specific
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

            // Custom tiles (version 2+) — only export the tiles the user selected
            val customTilesArr = za.kilowatch.ultimatefilemanager.storage.CustomTileManager.getAllCustomTileDataForExport(context, selectedIds)
            root.put("custom_tiles", customTilesArr)

            // Advanced Sync profiles — export the full JSON file content
            if (selectedIds.contains("advanced_sync_profiles")) {
                val advSyncFile = java.io.File(context.filesDir, "advanced_sync_profiles.json")
                if (advSyncFile.exists()) {
                    root.put("advanced_sync_profiles", advSyncFile.readText())
                } else {
                    root.put("advanced_sync_profiles", "[]")
                }
            }

            // Embed icon image files as base64 for cross-device portability
            val iconFilesObj = JSONObject()
            val iconDirs = mutableListOf<Pair<String, String>>() // (prefsName, dirName)
            if (selectedIds.contains("tile_icons_prefs")) {
                iconDirs.add("tile_icons_prefs" to "tile_icons")
            }
            if (selectedIds.contains("icon_customization_prefs")) {
                iconDirs.add("icon_customization_prefs" to "custom_icons")
            }
            for ((_, dirName) in iconDirs) {
                val dir = java.io.File(context.filesDir, dirName)
                if (dir.exists() && dir.isDirectory) {
                    val pngFiles = dir.listFiles { f -> f.extension.equals("png", ignoreCase = true) }
                    pngFiles?.forEach { file ->
                        if (file.length() < 1_048_576) { // 1 MB limit per icon
                            val bytes = file.readBytes()
                            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            iconFilesObj.put("$dirName/${file.name}", b64)
                        }
                    }
                }
            }
            if (iconFilesObj.length() > 0) {
                root.put("icon_files", iconFilesObj)
            }

            val jsonString = root.toString(2)
            val encryptedData = if (password != null && password.isNotEmpty()) {
                encryptBackupV3(jsonString, password)
            } else {
                encryptBackupPlain(jsonString)
            }

            val isSaf = targetFile is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(targetFile.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, targetFile.absolutePath)

            val outStream = if (isSaf) {
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(context, targetFile.absolutePath)
            } else {
                targetFile.parentFile?.mkdirs()
                FileOutputStream(targetFile)
            } ?: return false

            outStream.use { out ->
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
                "ufm_folder_sort_prefs"     to R.string.backup_pref_folder_sort,
                "ufm_hidden_files_prefs"    to R.string.backup_pref_hidden_files,
                "ufm_protected_files_prefs" to R.string.backup_pref_protected_files,
                "ufm_pinned_files_prefs"    to R.string.backup_pref_pinned_files,
                "ufm_main_menu_prefs"       to R.string.backup_pref_main_menu,
                "ufm_long_press_prefs"      to R.string.backup_pref_long_press,
                "ufm_controls_timeout_prefs" to R.string.backup_pref_controls_timeout,
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
                "cache_copy_prefs"          to R.string.backup_pref_cache_copy,
                "autoplay_prefs"            to R.string.backup_pref_autoplay,
                "ufm_player_prefs"          to R.string.backup_pref_ufm_player,
                "breadcrumbs_prefs"         to R.string.backup_pref_breadcrumbs,
                "grid_indicators_prefs"     to R.string.backup_pref_grid_indicators,
                "scrolling_text_prefs"      to R.string.backup_pref_scrolling_text,
                "settings_search_prefs"     to R.string.backup_pref_settings_search,
                "file_server_prefs"         to R.string.backup_pref_file_server,
                "tile_icons_prefs"          to R.string.backup_pref_tile_icons,
                "icon_customization_prefs"  to R.string.backup_pref_icon_customization,
                "loyalty_prefs"             to R.string.backup_pref_loyalty,
                "ufm_file_tags"             to R.string.backup_pref_file_tags,
                "analytics_prefs"           to R.string.backup_pref_analytics,
                "icon_tap_edit_mode_prefs"  to R.string.backup_pref_icon_tap_edit_mode,
                "ufm_keyboard_shortcuts_prefs" to R.string.backup_pref_keyboard_shortcuts,
                "ufm_saf_locations_prefs"   to R.string.backup_pref_saf_locations,
                "ufm_tv_server_prefs"       to R.string.backup_pref_tv_server
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

        // Custom tiles (version 2+)
        val customTiles = mutableListOf<BackupItem>()
        val customTilesArr = root.optJSONArray("custom_tiles")
        if (customTilesArr != null) {
            for (i in 0 until customTilesArr.length()) {
                val obj = customTilesArr.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val childrenArr = obj.optJSONArray("children")
                val childCount = childrenArr?.length() ?: 0
                val extra = obj.optString("subtitle", "").ifEmpty { "$childCount tiles" }
                customTiles.add(BackupItem(id, "custom_tiles", title, extra))
            }
        }

        // Advanced Sync profiles — show in import preview if present
        val advSyncStr = root.optString("advanced_sync_profiles", "")
        if (advSyncStr.isNotEmpty()) {
            sharedPrefs.add(BackupItem("advanced_sync_profiles", "shared_preferences",
                context.getString(R.string.backup_pref_advanced_sync), ""))
        }

        return BackupDetails(sharedPrefs, shares, storages, ftpProfiles, renames, smartSortConfigs, customTiles, jsonString)
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
                        if (prefsName == "ufm_folder_sort_prefs") {
                            val map = mutableMapOf<String, Any?>()
                            val valKeys = fileObj.keys()
                            while (valKeys.hasNext()) {
                                val k = valKeys.next()
                                if (k.startsWith("__androidx_security_crypto_")) continue
                                map[k] = fileObj.get(k)
                            }
                            za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.restoreEntriesFromBackup(context, map)
                        } else {
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
                    // FileTagsManager keeps an in-memory mirror of ufm_file_tags; drop it so the
                    // next read reloads the restored file instead of serving stale pre-restore data.
                    za.kilowatch.ultimatefilemanager.storage.FileTagsManager.invalidateCache()
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
                            isCredentialsStripped = obj.optBoolean("isCredentialsStripped", true),
                            isServerMode = obj.optBoolean("isServerMode", false),
                            hostKeyFingerprint = null // force re-TOFU on restored device
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

                // Custom tiles (version 2+) — skip if absent (version 1 import).
                // Only restore the tiles the user selected; build a filtered array from rawJson.
                val customTilesArr = root.optJSONArray("custom_tiles")
                if (customTilesArr != null) {
                    val selectedCustomTileIds = details.customTiles
                        .filter { it.isSelected }
                        .map { it.id }
                        .toSet()
                    // If no custom-tile items exist in the details list (e.g. older backup
                    // parsed without a UI selection step) fall back to restoring everything.
                    val filteredArr = if (selectedCustomTileIds.isEmpty()) {
                        customTilesArr
                    } else {
                        val arr = org.json.JSONArray()
                        for (i in 0 until customTilesArr.length()) {
                            val obj = customTilesArr.getJSONObject(i)
                            if (selectedCustomTileIds.contains(obj.getString("id"))) {
                                arr.put(obj)
                            }
                        }
                        arr
                    }
                    za.kilowatch.ultimatefilemanager.storage.CustomTileManager.restoreFromExport(context, filteredArr)
                }

                // Restore embedded icon image files (base64) — version 2+
                val iconFilesObj = root.optJSONObject("icon_files")
                if (iconFilesObj != null) {
                    val keys = iconFilesObj.keys()
                    while (keys.hasNext()) {
                        val relPath = keys.next() // e.g. "tile_icons/custom_f07ef337.png"
                        val b64 = iconFilesObj.getString(relPath)
                        try {
                            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                            val outFile = java.io.File(context.filesDir, relPath)
                            outFile.parentFile?.mkdirs()
                            outFile.writeBytes(bytes)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to restore icon file: $relPath", e)
                        }
                    }
                    // Fix device-specific paths in tile_icons_prefs and icon_customization_prefs.
                    // The source device's filesDir differs from this device's filesDir, so any
                    // absolute paths stored in the prefs JSON need to be rewritten.
                    fixIconPathsInPrefs(context, "tile_icons_prefs", "tile_icons", "tile_icons")
                    fixIconPathsInPrefs(context, "icon_customization_prefs", "icon_overrides", "custom_icons")
                }

                // Advanced Sync profiles — restore the full JSON file content
                val advSyncStr = root.optString("advanced_sync_profiles", "")
                if (advSyncStr.isNotEmpty()) {
                    val advSyncFile = java.io.File(context.filesDir, "advanced_sync_profiles.json")
                    advSyncFile.writeText(advSyncStr)
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore backup", e)
                false
            }
        }
    }

    /**
     * Rewrites absolute icon paths in a SharedPreferences JSON entry so they
     * point to the current device's `filesDir` instead of the source device's.
     *
     * Both `tile_icons_prefs` and `icon_customization_prefs` store a JSON object
     * where each entry can have a `"path"` field containing an absolute path like
     * `/data/user/0/za.kilowatch.ultimatefilemanager/files/tile_icons/foo.png`.
     * After restoring on a different device the prefix will be different, but the
     * `tile_icons/foo.png` or `custom_icons/foo.png` suffix is the same.
     *
     * @param prefsName  the SharedPreferences file name (e.g. `"tile_icons_prefs"`)
     * @param jsonKey    the key inside SharedPreferences that holds the JSON string
     * @param dirName    the sub-directory inside filesDir used in the file paths
     */
    private fun fixIconPathsInPrefs(context: Context, prefsName: String, jsonKey: String, dirName: String) {
        try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val raw = prefs.getString(jsonKey, null) ?: return
            val json = JSONObject(raw)
            val localFilesDir = context.filesDir.absolutePath
            var changed = false

            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val value = json.get(id)
                if (value is JSONObject) {
                    val path = value.optString("path", "")
                    if (!path.isNullOrEmpty()) {
                        // Extract the relative suffix after the dir name
                        // e.g. "/data/user/0/.../files/tile_icons/foo.png" → "tile_icons/foo.png"
                        val marker = "/$dirName/"
                        val idx = path.indexOf(marker)
                        if (idx >= 0) {
                            val filename = path.substring(idx + marker.length)
                            val newPath = "$localFilesDir/$dirName/$filename"
                            if (newPath != path) {
                                value.put("path", newPath)
                                changed = true
                            }
                        }
                    }
                }
            }

            if (changed) {
                prefs.edit().putString(jsonKey, json.toString()).commit()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fix icon paths in $prefsName", e)
        }
    }
}
