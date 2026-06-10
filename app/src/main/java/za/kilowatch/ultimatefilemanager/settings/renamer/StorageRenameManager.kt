package za.kilowatch.ultimatefilemanager.settings.renamer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager wrapper for the StorageRename database to make fetches easier.
 */
class StorageRenameManager private constructor(private val context: Context) {

    private val dao = StorageRenameDatabase.getInstance(context).storageRenameDao()

    private fun hashId(id: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(id.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun encrypt(text: String): String {
        return za.kilowatch.ultimatefilemanager.storage.VaultCrypto.encryptString(text)
    }

    private fun decrypt(text: String): String {
        return try {
            za.kilowatch.ultimatefilemanager.storage.VaultCrypto.decryptString(text)
        } catch (e: Exception) {
            text // Fallback useful if upgrading from unencrypted data
        }
    }

    fun getAllRenamesSync(): List<StorageRenameEntity> {
        return dao.getAllRenames().map { 
            it.copy(
                customName = decrypt(it.customName),
                originalName = decrypt(it.originalName)
            )
        }
    }

    /**
     * Map of hashed deviceId to StorageRenameEntity
     */
    fun getAllRenameMapSync(): Map<String, StorageRenameEntity> {
        return getAllRenamesSync().associateBy { it.deviceId }
    }

    fun getRenameMap(): Map<String, String> {
        return getAllRenamesSync().associate { it.deviceId to it.customName }
    }

    suspend fun saveRenameByHashedId(hashedId: String, customName: String, originalName: String, totalBytes: Long) {
        val encCustom = encrypt(customName)
        val encOriginal = encrypt(originalName)

        withContext(Dispatchers.IO) {
            dao.insertOrUpdate(StorageRenameEntity(hashedId, encCustom, encOriginal, totalBytes))
        }
    }

    suspend fun deleteRenameByHashedId(hashedId: String) {
        withContext(Dispatchers.IO) {
            dao.deleteById(hashedId)
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            dao.deleteAll()
        }
    }

    companion object {
        fun hashDeviceId(id: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(id.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
        @Volatile
        private var instance: StorageRenameManager? = null

        fun getInstance(context: Context): StorageRenameManager {
            return instance ?: synchronized(this) {
                instance ?: StorageRenameManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
