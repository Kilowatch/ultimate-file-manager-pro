package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Persists configured online storages to filesDir/online_storages.json.
 * Singleton — get via [OnlineStorageRepository.getInstance].
 */
class OnlineStorageRepository private constructor(private val context: Context) {

    private val file: File get() = File(context.filesDir, "online_storages.json")
    private val TAG = "GoRoAuth"

    private val storages = mutableListOf<OnlineStorage>()

    init { load() }

    companion object {
        @Volatile private var instance: OnlineStorageRepository? = null
        fun getInstance(ctx: Context): OnlineStorageRepository {
            return instance ?: synchronized(this) {
                instance ?: OnlineStorageRepository(ctx.applicationContext).also { instance = it }
            }
        }
    }

    fun getAll(): List<OnlineStorage> = storages.toList()

    fun getById(id: String): OnlineStorage? = storages.find { it.id == id }

    fun save(storage: OnlineStorage) {
        GoRoLog.d("GoRoAuth", "OnlineStorageRepository: saving storage ${storage.email} (${storage.provider})")
        val idx = storages.indexOfFirst { it.id == storage.id }
        if (idx >= 0) storages[idx] = storage else storages.add(storage)
        persist()
        notifyProvider()
    }

    fun delete(id: String) {
        storages.removeAll { it.id == id }
        persist()
        notifyProvider()
    }

    fun clearAll() {
        storages.clear()
        persist()
        notifyProvider()
    }

    private fun load() {
        storages.clear()
        if (!file.exists()) return
        GoRoLog.d(TAG, "Checking online storages for unencrypted credentials...")
        var needsMigration = false

        runCatching {
            val jsonText = file.readText()
            val arr = JSONArray(jsonText)
            GoRoLog.d(TAG, "OnlineStorageRepository: loading ${arr.length()} storages")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)

                // --- Safe decryption logic (mirrors NetworkShareRepository) ---
                fun decryptOrPlain(encrypted: String, fieldName: String): String {
                    if (encrypted.isEmpty()) return ""
                    return try {
                        VaultCrypto.decryptString(encrypted)
                    } catch (e: Exception) {
                        // Decryption failed -> assume it is plaintext (pre-encryption era)
                        GoRoLog.d(TAG, "Found unencrypted $fieldName for storage: ${o.optString("email", "")}. Migrating...")
                        needsMigration = true
                        encrypted
                    }
                }

                val rawToken = o.optString("refreshToken", null)
                val decryptedToken = if (rawToken.isNullOrEmpty()) null else decryptOrPlain(rawToken, "refreshToken")

                // S3 secret key — encrypted same as refreshToken
                val rawS3Secret = o.optString("s3SecretKey", null)
                val decryptedS3Secret = if (rawS3Secret.isNullOrEmpty()) null else decryptOrPlain(rawS3Secret, "s3SecretKey")

                // S3 access key — also encrypted at rest
                val rawS3Access = o.optString("s3AccessKey", null)
                val decryptedS3Access = if (rawS3Access.isNullOrEmpty()) null else decryptOrPlain(rawS3Access, "s3AccessKey")

                // WebDAV password — encrypted same as S3 secret key
                val rawWebDavPass = o.optString("webDavPassword", null)
                val decryptedWebDavPass = if (rawWebDavPass.isNullOrEmpty()) null else decryptOrPlain(rawWebDavPass, "webDavPassword")

                storages.add(
                    OnlineStorage(
                        id          = o.getString("id"),
                        provider    = OnlineStorageProvider.valueOf(o.getString("provider")),
                        email       = o.optString("email", ""),
                        displayName = o.optString("displayName", ""),
                        refreshToken = decryptedToken,
                        // S3-family fields (null for OAuth / WebDAV providers)
                        s3Endpoint  = o.optString("s3Endpoint",  null).takeUnless { it.isNullOrEmpty() },
                        s3Bucket    = o.optString("s3Bucket",    null).takeUnless { it.isNullOrEmpty() },
                        s3Region    = o.optString("s3Region",    null).takeUnless { it.isNullOrEmpty() },
                        s3AccessKey = decryptedS3Access,
                        s3SecretKey = decryptedS3Secret,
                        // WebDAV fields (null for OAuth / S3 providers)
                        webDavUrl      = o.optString("webDavUrl",      null).takeUnless { it.isNullOrEmpty() },
                        webDavUsername = o.optString("webDavUsername",  null).takeUnless { it.isNullOrEmpty() },
                        webDavPassword = decryptedWebDavPass,
                        isCredentialsStripped = o.optBoolean("isCredentialsStripped", false)
                    )
                )
            }

            if (needsMigration) {
                GoRoLog.d(TAG, "Migration complete. Saving encrypted credentials back to disk.")
                persist()
            } else {
                GoRoLog.d(TAG, "All online storage credentials already encrypted.")
            }
        }.onFailure {
            GoRoLog.e(TAG, "OnlineStorageRepository: load failed", it)
        }
    }

    private fun persist() {
        val arr = JSONArray()
        storages.forEach { s ->
            arr.put(JSONObject().apply {
                put("id",          s.id)
                put("provider",    s.provider.name)
                put("email",       s.email)
                put("displayName", s.displayName)
                // OAuth token — encrypt before writing
                put("refreshToken", s.refreshToken?.let { VaultCrypto.encryptString(it) } ?: "")
                // S3 fields — only written for S3 providers; secret is encrypted
                if (s.isS3Provider) {
                    put("s3Endpoint",  s.s3Endpoint  ?: "")
                    put("s3Bucket",    s.s3Bucket    ?: "")
                    put("s3Region",    s.s3Region    ?: "")
                    put("s3AccessKey", s.s3AccessKey?.let { VaultCrypto.encryptString(it) } ?: "")
                    put("s3SecretKey", s.s3SecretKey?.let { VaultCrypto.encryptString(it) } ?: "")
                }
                // WebDAV fields — only written for WebDAV providers; password is encrypted
                if (s.isWebDavProvider) {
                    put("webDavUrl",      s.webDavUrl      ?: "")
                    put("webDavUsername", s.webDavUsername ?: "")
                    put("webDavPassword", s.webDavPassword?.let { VaultCrypto.encryptString(it) } ?: "")
                }
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
}
