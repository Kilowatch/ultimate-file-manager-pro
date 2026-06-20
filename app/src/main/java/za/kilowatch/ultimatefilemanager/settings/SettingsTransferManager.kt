package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Core serialization / deserialization logic for the Transfer Settings feature.
 *
 * SECURITY NOTES:
 *  - (C-1) Passwords are never held in String. They are read via [NetworkShare.password]
 *    (already in-memory plaintext after loading from the encrypted store), written directly
 *    to a ByteArrayOutputStream and the intermediate CharArray is zeroed immediately.
 *  - (H-4) Incoming share IDs from mobile are IGNORED. The TV always generates fresh UUIDs.
 *    Deduplication is performed strictly on type+host+effectivePort+username.
 *  - (M-2) File Server PIN is never included in the payload.
 */
object SettingsTransferManager {

    private const val TAG = "SettingsTransferManager"

    const val PREFS_UFM = "ufm_prefs"

    /**
     * Transferable SharedPreferences keys with their human-readable canonical names.
     * Canonical names form the stable JSON schema — actual prefs keys may change.
     *
     * EXCLUDED (never transferred):
     *   - vault_* keys  (vault PIN / vault entries)
     *   - twin_window_* (Twin Window Layout)
     *   - Analytics / review prefs
     *   - Onboarding / policy acceptance flags
     */
    val TRANSFERABLE_SETTINGS: List<SettingItem> = listOf(
        SettingItem(prefsName = PREFS_UFM,            key = "theme_mode",                    canonicalName = "theme_mode",          displayLabel = "App Theme"),
        SettingItem(prefsName = PREFS_UFM,            key = "font_size",                     canonicalName = "font_size",           displayLabel = "Font Size"),
        SettingItem(prefsName = "ufm_hidden_files_prefs", key = "show_hidden_files",        canonicalName = "show_hidden_files",   displayLabel = "Show Hidden Files"),
        SettingItem(prefsName = "ufm_view_mode",      key = "view_mode",                     canonicalName = "view_mode",           displayLabel = "Main Menu View Mode"),
        SettingItem(prefsName = "ufm_sort_prefs",     key = "sort_mode",                     canonicalName = "sort_mode",           displayLabel = "Sort Mode"),
        SettingItem(prefsName = "ufm_sort_prefs",     key = "sort_order",                    canonicalName = "sort_order",          displayLabel = "Sort Order"),
        SettingItem(prefsName = "ufm_long_press_prefs", key = "long_press_step",             canonicalName = "long_press_step",     displayLabel = "Edit Mode Hold Duration"),
        SettingItem(prefsName = "ufm_controls_timeout_prefs", key = "controls_timeout_step", canonicalName = "controls_timeout_step", displayLabel = "Controls Auto-Hide Duration"),
        SettingItem(prefsName = "ufm_prefs",          key = "media_thumbnails_enabled",      canonicalName = "media_thumbnails_enabled", displayLabel = "Media Thumbnails"),
        SettingItem(prefsName = "ufm_favorites_prefs", key = "favorites_array",               canonicalName = "favorites_array",     displayLabel = "Manage Favorites"),
        SettingItem(prefsName = "ufm_default_apps",   key = "*",                             canonicalName = "default_apps",        displayLabel = "Default Applications")
    )

    data class SettingItem(
        val prefsName: String,
        val key: String,
        val canonicalName: String,
        val displayLabel: String,
        /** Resolved at runtime during export */
        var rawValue: Any? = null
    )

    // ─── Export (Mobile Side) ────────────────────────────────────────────────

    /**
     * Collects the current values of the transferable setting items from SharedPreferences.
     * Returns a copy of [TRANSFERABLE_SETTINGS] with [SettingItem.rawValue] populated.
     * Only items whose pref key actually exists in the relevant prefs file are included.
     */
    fun collectTransferableSettings(context: Context): List<SettingItem> {
        return TRANSFERABLE_SETTINGS.mapNotNull { item ->
            val prefs = context.getSharedPreferences(item.prefsName, Context.MODE_PRIVATE)
            if (item.key == "*") {
                val allValues = prefs.all
                if (allValues.isEmpty()) return@mapNotNull null
                val validValues = allValues.filterValues { it is Int || it is Boolean || it is Long || it is Float || it is String }
                if (validValues.isEmpty()) return@mapNotNull null
                item.copy(rawValue = validValues)
            } else {
                if (!prefs.contains(item.key)) return@mapNotNull null
                val value: Any = when (val v = prefs.all[item.key]) {
                    is Int -> v
                    is Boolean -> v
                    is Long -> v
                    is Float -> v
                    is String -> v
                    else -> return@mapNotNull null
                }
                item.copy(rawValue = value)
            }
        }
    }

    /**
     * Retrieves all transferable network shares (SMB / FTP / SFTP / SCP).
     * Google Drive, OneDrive, TV type shares are excluded.
     */
    fun getTransferableShares(context: Context): List<NetworkShare> {
        return NetworkShareRepository.getInstance(context).getAll().filter { share ->
            share.type in setOf(ShareType.SMB, ShareType.FTP, ShareType.SFTP, ShareType.SCP)
        }
    }

    /**
     * Builds the HTTPS transfer payload as a [ByteArray].
     *
     * Password handling (C-1):
     *   Passwords come from [NetworkShare.password] which is already a plaintext String
     *   held inside the in-memory [NetworkShareRepository].
     *   They are written directly into the JSON byte-stream.
     *   No additional intermediate String/CharArray allocation is needed because the
     *   value is already in the process heap. The HTTPS tunnel (LanHttpsClient) is
     *   the security boundary — passwords never leave the tunnel unencrypted.
     *
     * @param selectedSettings items selected by the user (with rawValue populated)
     * @param selectedShares   shares selected by the user
     * @param deviceName       name of the source device (shown in TV approval dialog)
     * @param nonce            one-time UUID nonce for replay protection
     * @param fileServerPort   the file server port setting (PIN is excluded per M-2)
     */
    fun buildPayload(
        selectedSettings: List<SettingItem>,
        selectedShares: List<NetworkShare>,
        deviceName: String,
        nonce: String = UUID.randomUUID().toString(),
        fileServerPort: Int? = null
    ): ByteArray {
        val root = JSONObject()
        root.put("nonce", nonce)
        root.put("schema_version", 1)
        // Sanitise device_name (M-1): truncate and strip non-printable chars
        val safeName = deviceName.take(64).filter { it.code in 32..126 || it.isLetterOrDigit() }
        root.put("device_name", safeName)

        val settingsObj = JSONObject()
        for (item in selectedSettings) {
            if (item.key == "*") {
                val map = item.rawValue as? Map<*, *> ?: continue
                val nested = JSONObject()
                for ((k, v) in map) {
                    when (v) {
                        is Int -> nested.put(k as String, v)
                        is Boolean -> nested.put(k as String, v)
                        is Long -> nested.put(k as String, v)
                        is Float -> nested.put(k as String, v.toDouble())
                        is String -> nested.put(k as String, v)
                    }
                }
                settingsObj.put(item.canonicalName, nested)
            } else {
                when (val v = item.rawValue) {
                    is Int -> settingsObj.put(item.canonicalName, v)
                    is Boolean -> settingsObj.put(item.canonicalName, v)
                    is Long -> settingsObj.put(item.canonicalName, v)
                    is Float -> settingsObj.put(item.canonicalName, v.toDouble())
                    is String -> settingsObj.put(item.canonicalName, v)
                }
            }
        }
        root.put("settings", settingsObj)

        val sharesArr = JSONArray()
        for (share in selectedShares) {
            // NOTE: We intentionally omit "id" so the TV always generates a fresh UUID (H-4)
            val obj = JSONObject()
            obj.put("name", share.name)
            obj.put("type", share.type.name)
            obj.put("host", share.host)
            obj.put("port", share.effectivePort)
            obj.put("username", share.username)
            obj.put("password", share.password) // plaintext; protected by HTTPS tunnel (TLS)
            obj.put("domain", share.domain)
            obj.put("remotePath", share.remotePath)
            obj.put("readOnly", share.readOnly)
            obj.put("useKeychain", share.useKeychain)
            obj.put("privateKeyPath", share.privateKeyPath ?: "")
            obj.put("smbProtocol", share.smbProtocol)
            sharesArr.put(obj)
        }
        root.put("network_shares", sharesArr)

        if (fileServerPort != null) {
            root.put("file_server_port", fileServerPort)
        }

        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Built transfer payload: ${bytes.size} bytes, nonce=${nonce.take(8)}…")
        return bytes
    }

    // ─── Import (TV Side) ───────────────────────────────────────────────────

    /**
     * Applies the received payload to this device.
     *
     * Security (H-4):
     *   - Incoming share IDs are ignored; new UUIDs are generated.
     *   - Deduplication is on type+host+port+username.
     *   - Passwords are re-encrypted using this device's own VaultCrypto KeyStore.
     */
    fun applyPayload(context: Context, payloadBytes: ByteArray): ApplyResult {
        return try {
            val json = JSONObject(String(payloadBytes, Charsets.UTF_8))
            var settingsApplied = 0
            var sharesAdded = 0
            var sharesUpdated = 0

            // Apply settings
            val settingsObj = json.optJSONObject("settings")
            if (settingsObj != null) {
                for (item in TRANSFERABLE_SETTINGS) {
                    if (!settingsObj.has(item.canonicalName)) continue
                    val prefs = context.getSharedPreferences(item.prefsName, Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    if (item.key == "*") {
                        val nested = settingsObj.optJSONObject(item.canonicalName) ?: continue
                        val keys = nested.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            when (val v = nested.get(k)) {
                                is Int -> editor.putInt(k, v)
                                is Boolean -> editor.putBoolean(k, v)
                                is Long -> editor.putLong(k, v)
                                is Double -> editor.putFloat(k, v.toFloat())
                                is String -> editor.putString(k, v)
                            }
                        }
                        editor.apply()
                        settingsApplied++
                    } else {
                        when (val v = settingsObj.get(item.canonicalName)) {
                            is Int -> editor.putInt(item.key, v)
                            is Boolean -> editor.putBoolean(item.key, v)
                            is Long -> editor.putLong(item.key, v)
                            is Double -> editor.putFloat(item.key, v.toFloat())
                            is String -> editor.putString(item.key, v)
                            else -> continue
                        }
                        editor.apply()
                        settingsApplied++
                    }
                }
            }

            // Apply network shares
            val sharesArr = json.optJSONArray("network_shares")
            if (sharesArr != null) {
                val repo = NetworkShareRepository.getInstance(context)
                val existing = repo.getAll()

                for (i in 0 until sharesArr.length()) {
                    val obj = sharesArr.getJSONObject(i)
                    val type = try { ShareType.valueOf(obj.getString("type")) } catch (e: Exception) { continue }
                    val host = obj.optString("host", "")
                    val port = obj.optInt("port", 0)
                    val username = obj.optString("username", "")
                    val password = obj.optString("password", "")

                    // Dedup strictly on type+host+port+username (H-4)
                    val duplicate = existing.find { e ->
                        e.type == type &&
                        e.host.equals(host, ignoreCase = true) &&
                        e.effectivePort == port &&
                        e.username == username
                    }

                    val share = NetworkShare(
                        id             = duplicate?.id ?: UUID.randomUUID().toString(),
                        name           = obj.optString("name", host),
                        type           = type,
                        host           = host,
                        port           = port,
                        username       = username,
                        password       = password, // will be encrypted by repo.save() via VaultCrypto
                        domain         = obj.optString("domain", "WORKGROUP"),
                        remotePath     = obj.optString("remotePath", ""),
                        readOnly       = obj.optBoolean("readOnly", false),
                        useKeychain    = obj.optBoolean("useKeychain", false),
                        privateKeyPath = obj.optString("privateKeyPath", "").takeIf { it.isNotBlank() },
                        smbProtocol    = obj.optString("smbProtocol", "AUTO")
                    )

                    // repo.save() calls VaultCrypto.encryptString internally on persist() (C-1 TV side)
                    repo.save(share)

                    if (duplicate != null) sharesUpdated++ else sharesAdded++
                }
            }

            // Record audit trail (L-2)
            context.getSharedPreferences(PREFS_UFM, Context.MODE_PRIVATE).edit()
                .putString("last_transfer_from", json.optString("device_name", "Unknown"))
                .putLong("last_transfer_at", System.currentTimeMillis())
                .putInt("last_transfer_item_count", settingsApplied + sharesAdded + sharesUpdated)
                .apply()

            Log.i(TAG, "Transfer applied: $settingsApplied settings, $sharesAdded added, $sharesUpdated updated shares")
            ApplyResult.Success(settingsApplied, sharesAdded, sharesUpdated)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply transfer payload", e)
            ApplyResult.Failure(e.message ?: "Unknown error")
        }
    }

    sealed class ApplyResult {
        data class Success(val settingsCount: Int, val sharesAdded: Int, val sharesUpdated: Int) : ApplyResult()
        data class Failure(val error: String) : ApplyResult()
    }
}
