package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import java.io.File

/**
 * Persists configured network shares to filesDir/network_shares.json.
 * Singleton — get via [NetworkShareRepository.getInstance].
 */
class NetworkShareRepository private constructor(private val context: Context) {

    private val file: File get() = File(context.filesDir, "network_shares.json")
    private val TAG_GOROPASS = "GoRoPass"
    private val shares = mutableListOf<NetworkShare>()

    init { load() }

    companion object {
        @Volatile private var instance: NetworkShareRepository? = null
        fun getInstance(ctx: Context): NetworkShareRepository {
            return instance ?: synchronized(this) {
                instance ?: NetworkShareRepository(ctx.applicationContext).also { instance = it }
            }
        }
    }

    fun getAll(): List<NetworkShare> = shares.toList()

    fun getById(id: String): NetworkShare? = shares.find { it.id == id }

    fun save(share: NetworkShare) {
        val idx = shares.indexOfFirst { it.id == share.id }
        if (idx >= 0) shares[idx] = share else shares.add(share)
        persist()
        notifyProvider()
    }

    fun delete(id: String) {
        shares.removeAll { it.id == id }
        persist()
        notifyProvider()
    }

    fun clearAll() {
        shares.clear()
        persist()
        notifyProvider()
    }

    private fun load() {
        shares.clear()
        if (!file.exists()) return
        Log.i(TAG_GOROPASS, "Checking network shares for unencrypted credentials...")
        var needsMigration = false
        
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val shareId = o.getString("id")
                val shareName = o.getString("name")
                
                // --- Safe decryption logic ---
                fun decryptOrPlain(encrypted: String, fieldName: String): String {
                    if (encrypted.isEmpty()) return ""
                    return try {
                        VaultCrypto.decryptString(encrypted)
                    } catch (e: Exception) {
                        // Decryption failed -> assume it is plaintext
                        Log.w(TAG_GOROPASS, "Found unencrypted $fieldName for share: $shareName. Migrating...")
                        needsMigration = true
                        encrypted
                    }
                }

                val useKeychain = o.optBoolean("useKeychain", false)
                var pass = decryptOrPlain(o.optString("password", ""), "password")

                // SEB-SPEC: Migration for double-encryption bug in older versions for keychains.
                // If it was encrypted twice, the first decrypt leaves it still encrypted.
                // We call it again; if it was already plaintext, decryptOrPlain safely returns it.
                if (useKeychain && pass.isNotEmpty()) {
                    val doubleDecrypted = decryptOrPlain(pass, "password (second-pass)")
                    if (doubleDecrypted != pass) {
                        Log.i(TAG_GOROPASS, "Migrated double-encrypted password for: $shareName")
                        pass = doubleDecrypted
                        needsMigration = true
                    }
                }

                shares.add(
                    NetworkShare(
                        id         = shareId,
                        name       = shareName,
                        type       = ShareType.valueOf(o.getString("type")),
                        host       = o.getString("host"),
                        port       = o.optInt("port", 0),
                        username   = o.optString("username", ""),
                        password   = pass,
                        domain     = o.optString("domain", "WORKGROUP"),
                        remotePath = o.optString("remotePath", ""),
                        readOnly   = o.optBoolean("readOnly", true),
                        privateKeyPath = decryptOrPlain(o.optString("privateKeyPath", ""), "privateKeyPath").takeIf { it.isNotEmpty() },
                        useKeychain = useKeychain,
                        smbProtocol = normalizeProtocol(o.optString("smbProtocol", "AUTO")),
                        isCredentialsStripped = o.optBoolean("isCredentialsStripped", false)
                    )
                )
            }
            
            if (needsMigration) {
                Log.i(TAG_GOROPASS, "Migration complete. Saving encrypted credentials back to disk.")
                persist()
            } else {
                Log.i(TAG_GOROPASS, "All credentials already encrypted.")
            }
        }.onFailure {
            Log.e(TAG_GOROPASS, "Failed to load/migrate network shares", it)
        }
    }

    private fun persist() {
        val arr = JSONArray()
        shares.forEach { s ->
            arr.put(JSONObject().apply {
                put("id",         s.id)
                put("name",       s.name)
                put("type",       s.type.name)
                put("host",       s.host)
                put("port",       s.port)
                put("username",   s.username)
                
                // Always encrypt before writing
                put("password",   VaultCrypto.encryptString(s.password))
                
                put("domain",     s.domain)
                put("remotePath", s.remotePath)
                put("readOnly",   s.readOnly)
                
                put("privateKeyPath", s.privateKeyPath?.let { VaultCrypto.encryptString(it) } ?: "")
                
                put("useKeychain", s.useKeychain)
                put("smbProtocol", s.smbProtocol)
                put("isCredentialsStripped", s.isCredentialsStripped)
            })
        }
        file.writeText(arr.toString(2))
    }

    /** Tell the DocumentsProvider that roots have changed so the picker refreshes. */
    private fun notifyProvider() {
        runCatching {
            val authority = "${context.packageName}.documents"
            val rootsUri = android.provider.DocumentsContract.buildRootsUri(authority)
            context.contentResolver.notifyChange(rootsUri, null)
        }
    }

    /**
     * Normalize the [smbProtocol] field to a known value.
     * Only "AUTO", "SMB2", and "SMB3" are valid.  Anything else
     * (corrupt config, tampered file, or hypothetically "SMB1")
     * is silently mapped to "AUTO" so the share continues to work
     * with SMB2/3 only.
     */
    private fun normalizeProtocol(raw: String): String = when (raw) {
        "AUTO", "SMB2", "SMB3" -> raw
        else -> {
            Log.w(TAG_GOROPASS, "Normalizing unrecognized smbProtocol '$raw' → AUTO")
            "AUTO"
        }
    }
}
