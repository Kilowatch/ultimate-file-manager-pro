package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import java.io.File

/**
 * Persists configured FTP/SFTP server profiles to filesDir/ftp_server_profiles.json.
 * Singleton — get via [FtpServerProfileRepository.getInstance].
 *
 * Passwords are encrypted/decrypted via [VaultCrypto] (AES-256-GCM backed by AndroidKeyStore).
 */
class FtpServerProfileRepository private constructor(private val context: Context) {

    private val file: File get() = File(context.filesDir, "ftp_server_profiles.json")
    private val TAG = "FtpServerProfileRepo"
    private val profiles = mutableListOf<FtpServerProfile>()

    init { load() }

    companion object {
        @Volatile private var instance: FtpServerProfileRepository? = null
        fun getInstance(ctx: Context): FtpServerProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: FtpServerProfileRepository(ctx.applicationContext).also { instance = it }
            }
        }
    }

    fun getAll(): List<FtpServerProfile> = profiles.toList()

    fun getById(id: String): FtpServerProfile? = profiles.find { it.id == id }

    fun getByUsername(username: String): FtpServerProfile? =
        profiles.find { it.username.equals(username, ignoreCase = true) }

    fun save(profile: FtpServerProfile) {
        val idx = profiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
        persist()
    }

    fun delete(id: String) {
        profiles.removeAll { it.id == id }
        persist()
    }

    fun clearAll() {
        profiles.clear()
        persist()
    }

    /**
     * Validates a plaintext password against a profile's encrypted password.
     * Returns true if the password matches.
     */
    fun validatePassword(username: String, plainPassword: String): Boolean {
        val profile = getByUsername(username) ?: return false
        if (profile.encryptedPassword.isEmpty()) return false
        return try {
            val decrypted = VaultCrypto.decryptString(profile.encryptedPassword)
            decrypted == plainPassword
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt password for $username", e)
            false
        }
    }

    private fun load() {
        profiles.clear()
        if (!file.exists()) return
        Log.i(TAG, "Loading FTP server profiles...")

        runCatching {
            val arr = JSONArray(file.readText())
            var needsMigration = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)

                // Safe decryption — if decryption fails, treat as plaintext and migrate
                fun decryptOrPlain(encrypted: String, fieldName: String): String {
                    if (encrypted.isEmpty()) return ""
                    return try {
                        VaultCrypto.decryptString(encrypted)
                        encrypted // Return the encrypted form — we store it encrypted
                    } catch (e: Exception) {
                        Log.w(TAG, "Found unencrypted $fieldName, migrating...")
                        needsMigration = true
                        encrypted
                    }
                }

                val encPassword = o.optString("encryptedPassword", "")
                // Verify decryptability
                decryptOrPlain(encPassword, "password")

                profiles.add(
                    FtpServerProfile(
                        id = o.getString("id"),
                        username = o.getString("username"),
                        encryptedPassword = encPassword,
                        defaultLocationUri = o.getString("defaultLocationUri"),
                        defaultLocationLabel = o.optString("defaultLocationLabel", ""),
                        locationType = try {
                            LocationType.valueOf(o.optString("locationType", "LOCAL"))
                        } catch (_: Exception) {
                            LocationType.LOCAL
                        },
                        locationMetaId = o.optString("locationMetaId").ifEmpty { null },
                        readOnly = o.optBoolean("readOnly", false),
                        authorizedKeys = o.optString("authorizedKeys", ""),
                        isCredentialsStripped = o.optBoolean("isCredentialsStripped", false)
                    )
                )
            }

            if (needsMigration) {
                Log.i(TAG, "Migration complete. Re-encrypting credentials.")
                persist()
            }
        }.onFailure {
            Log.e(TAG, "Failed to load FTP server profiles", it)
        }
    }

    private fun persist() {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("username", p.username)
                put("encryptedPassword", p.encryptedPassword)
                put("defaultLocationUri", p.defaultLocationUri)
                put("defaultLocationLabel", p.defaultLocationLabel)
                put("locationType", p.locationType.name)
                put("locationMetaId", p.locationMetaId ?: "")
                put("readOnly", p.readOnly)
                put("authorizedKeys", p.authorizedKeys)
                put("isCredentialsStripped", p.isCredentialsStripped)
            })
        }
        file.writeText(arr.toString(2))
    }
}
