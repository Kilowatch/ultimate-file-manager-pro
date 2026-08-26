package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import za.kilowatch.ultimatefilemanager.util.GoRoLog
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
    private const val TAG = "RCloneConfig"

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

    /**
     * Builds the rclone.conf section for a Proton Drive remote.
     *
     * Proton Drive authenticates via username and password.
     * Supports optional 2FA code, OTP secret key (for automatic TOTP code generation),
     * and mailbox password (for legacy two-password Proton accounts).
     *
     * Passwords and keys must be obscured via rclone's `core/obscure` RC method.
     *
     * @param username The Proton account username or email address.
     * @param password The Proton account password (obscured via core/obscure).
     * @param twoFA Optional 2FA code (e.g. "123456").
     * @param otpSecretKey Optional OTP secret key (obscured via core/obscure) for 2FA.
     * @param mailboxPassword Optional mailbox password (obscured via core/obscure) for two-password accounts.
     */
    fun protonDriveConfig(
        username: String,
        password: String,
        twoFA: String? = null,
        otpSecretKey: String? = null,
        mailboxPassword: String? = null
    ): Map<String, String> {
        val map = mutableMapOf(
            "type" to "protondrive",
            "username" to username,
            "password" to password
        )
        if (!twoFA.isNullOrBlank()) {
            map["2fa"] = twoFA
        }
        if (!otpSecretKey.isNullOrBlank()) {
            map["otp_secret_key"] = otpSecretKey
        }
        if (!mailboxPassword.isNullOrBlank()) {
            map["mailbox_password"] = mailboxPassword
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

    // ─────────────────────────────────────────────
    // Box OAuth token helpers
    // ─────────────────────────────────────────────

    /**
     * Finds the first Box OAuth provider entry in the encrypted config and
     * returns its (storageId, tokenJson) pair.
     *
     * Returns null if no Box provider exists or the encrypted config cannot
     * be read (no config yet, decryption failure).
     */
    fun getBoxTokenEntry(context: Context): Pair<String, String>? {
        val providers = try {
            readEncrypted(context)
        } catch (_: Exception) {
            return null
        }
        for ((storageId, config) in providers) {
            if (config["type"] == "box") {
                val token = config["token"] ?: continue
                return storageId to token
            }
        }
        return null
    }

    /**
     * Updates the `token` value for a specific Box provider in the encrypted
     * config. Re-reads the full config, patches the entry by [storageId],
     * re-encrypts, and saves.
     *
     * @throws IllegalArgumentException if [storageId] is not found in the config
     * @throws IllegalStateException if re-encryption fails
     */
    fun updateBoxToken(context: Context, storageId: String, newTokenJson: String) {
        val providers = readEncrypted(context).toMutableMap()
        val entry = providers[storageId]?.toMutableMap()
            ?: throw IllegalArgumentException("No RClone provider found with storageId=$storageId")
        entry["token"] = newTokenJson
        providers[storageId] = entry
        saveEncrypted(context, providers)
    }

    /**
     * Checks whether a Box OAuth token JSON has an expired `expiry` timestamp.
     *
     * If the JSON has an `expiry` field, it is parsed as ISO 8601 and compared
     * to the current time minus a 5-minute buffer (to avoid near-expiry races
     * during active use).
     *
     * If no `expiry` field is present but a `refresh_token` IS present, the
     * token is treated as **expired**. This handles the common case where the
     * token was obtained directly from the initial Box OAuth flow — Box's raw
     * response contains `expires_in` (seconds from issue), not an absolute
     * `expiry` timestamp. Without an `expiry` field we cannot know when the
     * token was issued, so the conservative approach is to attempt a refresh.
     * If the refresh_token is still valid, the refresh succeeds; if not,
     * [RCloneShareClient]'s Layer 3 fallback handles it.
     *
     * If neither `expiry` nor a non-empty `refresh_token` is present, returns
     * false (conservative — defers to rclone's internal handling).
     *
     * @return true if the token should be refreshed (expired or plausibly expired)
     */
    fun isTokenExpired(tokenJson: String): Boolean {
        val obj = try {
            JSONObject(tokenJson)
        } catch (_: Exception) {
            GoRoLog.w(TAG, "isTokenExpired: cannot parse token JSON, assuming expired")
            return true
        }

        // If there's an explicit `expiry` field, check it against the clock
        if (obj.has("expiry")) {
            val expiryStr = obj.optString("expiry", "")
            if (expiryStr.isNotEmpty()) {
                return try {
                    val expiry = java.time.Instant.parse(expiryStr)
                    val isExpired = java.time.Instant.now().isAfter(expiry.minusSeconds(300))
                    GoRoLog.d(TAG, "isTokenExpired: expiry=$expiryStr isExpired=$isExpired")
                    isExpired
                } catch (_: Exception) {
                    GoRoLog.w(TAG, "isTokenExpired: could not parse expiry='$expiryStr', assuming not expired")
                    false
                }
            }
        }

        // No `expiry` field — this is a token straight from Box's OAuth flow.
        // If it has a refresh_token, assume the access_token is expired so we
        // proactively refresh it.
        val hasRefreshToken = obj.has("refresh_token") &&
                obj.optString("refresh_token", "").isNotEmpty()
        GoRoLog.d(TAG, "isTokenExpired: no expiry field, hasRefreshToken=$hasRefreshToken → expired=$hasRefreshToken")
        return hasRefreshToken
    }

    /**
     * Deletes the temp config directory.
     */
    fun cleanTempConfig(context: Context) {
        File(context.cacheDir, "rclone").deleteRecursively()
    }
}
