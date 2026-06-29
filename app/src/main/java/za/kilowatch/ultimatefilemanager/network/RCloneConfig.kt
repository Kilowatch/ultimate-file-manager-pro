package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import java.io.File

/**
 * Builds an rclone.conf file at runtime for all supported storage providers.
 *
 * All saved credentials are encrypted at rest via [VaultCrypto].
 * The plaintext rclone.conf is only written to a temp file during active use.
 *
 * Usage:
 * ```
 * // Save encrypted credentials
 * RCloneConfig.saveEncrypted(context, mapOf(
 *     "filen" to filenConfig("email", obscuredPw, apiKey),
 * ))
 *
 * // At runtime, generate a temp config
 * val tempFile = RCloneConfig.decryptToTempFile(context)
 * // ... use tempFile.absolutePath with Gomobile ...
 * tempFile.delete()
 * ```
 */
object RCloneConfig {

    private const val ENCRYPTED_CONFIG_FILE = "rclone_encrypted.json"

    // ─────────────────────────────────────────────
    // Provider config builders
    // ─────────────────────────────────────────────

    /**
     * Builds the rclone.conf section for a Drime remote.
     *
     * Drime authenticates via an API access token generated from the web control panel
     * at https://app.drime.cloud/ → Developer → Create Token.
     *
     * @param accessToken The API access token.
     */
    fun drimeConfig(accessToken: String): Map<String, String> = mapOf(
        "type"  to "drime",
        "access_token" to accessToken
    )

    /**
     * Builds the rclone.conf section for a Mega remote.
     *
     * Mega authenticates via email (user) and password (pass).
     * The [pass] value must be obscured via rclone's `core/obscure` RC method
     * before being passed here — this happens automatically in
     * [RCloneProviderActivity] during the save flow.
     *
     * @param user The Mega account email address.
     * @param pass The Mega account password (already obscured via core/obscure).
     */
    fun megaConfig(user: String, pass: String): Map<String, String> = mapOf(
        "type" to "mega",
        "user" to user,
        "pass" to pass
    )

    /**
     * Builds the rclone.conf section for a Koofr remote.
     *
     * Koofr authenticates via email (user) and password.
     * An optional custom server endpoint can be provided for different
     * datacenter regions or self-hosted instances.
     *
     * Note: rclone's Koofr backend has [IsPassword] set on the password field,
     * so it obscures the password automatically — [pass] should be RAW, not
     * pre-obscured via core/obscure.
     *
     * @param user     The Koofr account email address.
     * @param password The Koofr account password (RAW — rclone obscures it).
     * @param endpoint Optional custom server endpoint (e.g. "https://app.koofr.net").
     *                 When set, also sends provider="other" so the custom
     *                 endpoint is used. When null or blank, uses the default
     *                 Koofr provider (https://app.koofr.net).
     */
    /**
     * Builds the rclone.conf section for a Box remote.
     *
     * Box authenticates via OAuth 2.0. The [tokenJson] is the full OAuth token
     * response JSON (access_token + refresh_token + expiry) returned by the
     * OAuth flow.
     *
     * box_sub_type is always "user" (not "enterprise") for consumer accounts.
     *
     * @param tokenJson The full OAuth token JSON string from Box's token endpoint.
     */
    fun boxConfig(tokenJson: String): Map<String, String> = mapOf(
        "type" to "box",
        "token" to tokenJson
    )

    fun koofrConfig(user: String, password: String, endpoint: String? = null): Map<String, String> {
        val map = mutableMapOf(
            "type" to "koofr",
            "provider" to "koofr",
            "user" to user,
            "password" to password
        )
        // Custom endpoint → switch to "other" provider (backend scopes
        // the endpoint option to Provider:"other")
        if (!endpoint.isNullOrBlank() && endpoint != "https://app.koofr.net") {
            map["provider"] = "other"
            map["endpoint"] = endpoint
        }
        return map
    }

    // ─────────────────────────────────────────────
    // Core writer — builds rclone.conf from a map
    // (public for testing / one-off use, but prefer saveEncrypted for production)
    // ─────────────────────────────────────────────
    fun writeConfig(context: Context, providers: Map<String, Map<String, String>>): File {
        val configDir = File(context.filesDir, "rclone").also { it.mkdirs() }
        val configFile = File(configDir, "rclone.conf")

        val sb = StringBuilder()
        for ((remoteName, options) in providers) {
            sb.appendLine("[$remoteName]")
            for ((key, value) in options) {
                sb.appendLine("$key = $value")
            }
            sb.appendLine()
        }

        configFile.writeText(sb.toString())
        return configFile
    }

    // ─────────────────────────────────────────────
    // Encrypted save — credentials are NEVER stored as plaintext on disk
    // ─────────────────────────────────────────────

    /**
     * Serialises the provider config map to JSON, encrypts it with [VaultCrypto],
     * and writes the encrypted blob to app-private storage.
     *
     * No plaintext credentials are ever written to disk.
     *
     * @throws IllegalStateException if VaultCrypto encryption fails
     */
    fun saveEncrypted(context: Context, providers: Map<String, Map<String, String>>) {
        val json = JSONObject()
        for ((remoteName, options) in providers) {
            val providerJson = JSONObject()
            for ((key, value) in options) {
                providerJson.put(key, value)
            }
            json.put(remoteName, providerJson)
        }

        val plaintext = json.toString(2)
        val encrypted = try {
            VaultCrypto.encryptString(plaintext)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to encrypt RClone configuration", e)
        }

        val configFile = File(context.filesDir, ENCRYPTED_CONFIG_FILE)
        configFile.writeText(encrypted)
    }

    /**
     * Reads the encrypted config file, decrypts it with [VaultCrypto],
     * and writes a plaintext rclone.conf to a **temp** file suitable for
     * Gomobile initialisation.
     *
     * The caller MUST delete the returned file after use.
     *
     * @return A temporary rclone.conf file, or null if no encrypted config exists
     * @throws IllegalStateException if decryption fails
     */
    fun decryptToTempFile(context: Context): File? {
        val encryptedFile = File(context.filesDir, ENCRYPTED_CONFIG_FILE)
        if (!encryptedFile.exists()) return null

        val encryptedBlob = encryptedFile.readText()
        val decryptedJson = try {
            VaultCrypto.decryptString(encryptedBlob)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to decrypt RClone configuration — credentials may be corrupted", e)
        }

        val providers = JSONObject(decryptedJson)
        val configDir = File(context.cacheDir, "rclone").also { it.mkdirs() }
        val tempFile = File(configDir, "rclone_temp.conf")

        val sb = StringBuilder()
        for (remoteName in providers.keys()) {
            val options = providers.getJSONObject(remoteName)
            sb.appendLine("[$remoteName]")
            for (key in options.keys()) {
                sb.appendLine("$key = ${options.getString(key)}")
            }
            sb.appendLine()
        }

        tempFile.writeText(sb.toString())
        return tempFile
    }

    /**
     * Returns true if an encrypted RClone configuration exists on disk.
     */
    fun hasEncryptedConfig(context: Context): Boolean {
        return File(context.filesDir, ENCRYPTED_CONFIG_FILE).exists()
    }

    /**
     * Reads and decrypts the encrypted config file, returning the provider map.
     * Returns an empty map if no encrypted config exists.
     *
     * @throws IllegalStateException if decryption fails
     */
    fun readEncrypted(context: Context): Map<String, Map<String, String>> {
        val encryptedFile = File(context.filesDir, ENCRYPTED_CONFIG_FILE)
        if (!encryptedFile.exists()) return emptyMap()

        val encryptedBlob = encryptedFile.readText()
        val decryptedJson = try {
            VaultCrypto.decryptString(encryptedBlob)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to decrypt RClone configuration — credentials may be corrupted", e)
        }

        val providers = JSONObject(decryptedJson)
        val result = mutableMapOf<String, Map<String, String>>()
        for (key in providers.keys()) {
            val obj = providers.getJSONObject(key)
            val entry = mutableMapOf<String, String>()
            for (field in obj.keys()) {
                entry[field] = obj.getString(field)
            }
            result[key] = entry
        }
        return result
    }

    /**
     * Removes a single provider from the encrypted config by its storage ID.
     * Does nothing if the config doesn't exist or the key isn't found.
     *
     * @throws IllegalStateException if decryption/encryption fails
     */
    fun removeProvider(context: Context, storageId: String) {
        val providers = readEncrypted(context).toMutableMap()
        if (providers.remove(storageId) != null) {
            saveEncrypted(context, providers)
        }
    }

    /**
     * Deletes the encrypted config file (e.g. on provider removal).
     */
    fun deleteEncryptedConfig(context: Context) {
        File(context.filesDir, ENCRYPTED_CONFIG_FILE).delete()
    }

    /**
     * Deletes the temp config directory.
     */
    fun cleanTempConfig(context: Context) {
        File(context.cacheDir, "rclone").deleteRecursively()
    }
}
