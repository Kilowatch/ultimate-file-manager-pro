package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.storage.TileIconManager
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object ThemePackManager {
    private const val TAG = "ThemePackManager"
    private const val MAGIC_HEADER = "UFM_THEME_V1"
    private const val AAD_STRING = "UFM_THEME_AAD_V1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128
    private const val MAX_FILE_SIZE = 10L * 1024 * 1024

    // ── V2 constants ──────────────────────────────────────────────────────
    private const val MAGIC_V2 = "UFM_THEME_V2"       // 13 bytes
    private const val AAD_V2 = "UFM_THEME_V2_AAD"
    private const val FLAG_ENCRYPTED: Byte = 0x01
    private const val FLAG_PLAINTEXT: Byte = 0x00
    private const val PBKDF2_ITERATIONS = 260_000
    private const val PBKDF2_KEY_LENGTH = 256
    private const val SALT_SIZE = 32

    enum class ThemePackFormat { V1_LEGACY, V2_ENCRYPTED, V2_PLAIN, UNKNOWN }

    private fun getSecretKey(): SecretKeySpec {
        val passphrase = "za.kilowatch.ultimatefilemanager.theme.secret.key.v1"
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(passphrase.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val rawKey = factory.generateSecret(spec).encoded
        return SecretKeySpec(rawKey, "AES")
    }

    fun encryptPack(payload: String): ByteArray {
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

    fun encryptPackV2(payload: String, password: String): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(salt)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE, iv))
        cipher.updateAAD(AAD_V2.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

        val headerBytes = MAGIC_V2.toByteArray(Charsets.US_ASCII)
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

    fun encryptPackPlain(payload: String): ByteArray {
        val headerBytes = MAGIC_V2.toByteArray(Charsets.US_ASCII)
        val flagByte = byteArrayOf(FLAG_PLAINTEXT)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val finalBytes = ByteArray(headerBytes.size + 1 + payloadBytes.size)
        var pos = 0
        System.arraycopy(headerBytes, 0, finalBytes, pos, headerBytes.size); pos += headerBytes.size
        System.arraycopy(flagByte, 0, finalBytes, pos, 1); pos += 1
        System.arraycopy(payloadBytes, 0, finalBytes, pos, payloadBytes.size)
        return finalBytes
    }

    fun decryptPackV2(bytes: ByteArray, password: String): String {
        val headerBytes = MAGIC_V2.toByteArray(Charsets.US_ASCII)
        val minSize = headerBytes.size + 1 + SALT_SIZE + IV_SIZE + 1
        if (bytes.size < minSize) {
            throw IllegalArgumentException("Invalid theme pack: file too small")
        }
        for (i in headerBytes.indices) {
            if (bytes[i] != headerBytes[i]) {
                throw IllegalArgumentException("Invalid theme pack: magic header mismatch")
            }
        }
        if (bytes[headerBytes.size] != FLAG_ENCRYPTED) {
            throw IllegalArgumentException("Invalid theme pack: expected encrypted flag")
        }

        var pos = headerBytes.size + 1
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
        cipher.updateAAD(AAD_V2.toByteArray(Charsets.UTF_8))
        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, Charsets.UTF_8)
    }

    fun detectFormat(bytes: ByteArray): ThemePackFormat {
        val v1Header = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
        if (bytes.size >= v1Header.size && v1Header.indices.all { bytes[it] == v1Header[it] }) {
            return ThemePackFormat.V1_LEGACY
        }
        val v2Header = MAGIC_V2.toByteArray(Charsets.US_ASCII)
        if (bytes.size >= v2Header.size + 1 && v2Header.indices.all { bytes[it] == v2Header[it] }) {
            return when (bytes[v2Header.size]) {
                FLAG_ENCRYPTED -> ThemePackFormat.V2_ENCRYPTED
                FLAG_PLAINTEXT -> ThemePackFormat.V2_PLAIN
                else -> ThemePackFormat.UNKNOWN
            }
        }
        return ThemePackFormat.UNKNOWN
    }

    private fun decryptPackV1(encryptedBytes: ByteArray): String {
        val headerBytes = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
        if (encryptedBytes.size < headerBytes.size + IV_SIZE) {
            throw IllegalArgumentException("Invalid theme pack: file too small")
        }
        for (i in headerBytes.indices) {
            if (encryptedBytes[i] != headerBytes[i]) {
                throw IllegalArgumentException("Invalid theme pack: not a valid UFM theme file")
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

    fun decryptPack(bytes: ByteArray, password: String?): String {
        return when (detectFormat(bytes)) {
            ThemePackFormat.V1_LEGACY -> decryptPackV1(bytes)
            ThemePackFormat.V2_ENCRYPTED -> {
                val pw = password ?: throw IllegalArgumentException("Password required for encrypted theme pack")
                decryptPackV2(bytes, pw)
            }
            ThemePackFormat.V2_PLAIN -> {
                val start = MAGIC_V2.toByteArray(Charsets.US_ASCII).size + 1
                String(bytes, start, bytes.size - start, Charsets.UTF_8)
            }
            ThemePackFormat.UNKNOWN -> throw IllegalArgumentException("Unsupported theme pack file format")
        }
    }

    // Keep old signature for backward compat with callers that don't pass password
    @Deprecated("Use decryptPack(bytes, password) instead", ReplaceWith("decryptPack(bytes, null)"))
    fun decryptPack(encryptedBytes: ByteArray): String {
        return decryptPack(encryptedBytes, null)
    }

    fun getExportDirectory(): File {
        return File(
            android.os.Environment.getExternalStorageDirectory(),
            "UFM"
        )
    }

    fun getDefaultExportFile(): File {
        return File(getExportDirectory(), "ufm_icons_theme.UFMTheme")
    }

    // ── Export ──────────────────────────────────────────────────────

    fun buildExportJson(context: Context, selectedIconIds: Set<String>): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val iconsObj = JSONObject()

        // Read from IconCustomizationManager
        val allOverrides = IconCustomizationManager.getAll(context)
        for ((iconId, override) in allOverrides) {
            if (iconId !in selectedIconIds) continue
            val entry = JSONObject()
            if (!override.customPath.isNullOrEmpty()) {
                val file = File(override.customPath)
                if (file.exists()) {
                    val imageBytes = file.readBytes()
                    val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    entry.put("type", "custom")
                    entry.put("data", b64)
                } else {
                    continue
                }
            } else if (override.builtinRes != 0) {
                entry.put("type", "builtin")
                entry.put("res", override.builtinRes)  // keep for backward compat with older app versions
                val resName = TileIconManager.resolveResName(context, override.builtinRes)
                if (!resName.isNullOrEmpty()) entry.put("res_name", resName)
            } else {
                continue
            }
            iconsObj.put(iconId, entry)
        }

        // Read tile icon overrides from TileIconManager
        val tilePaths = TileIconManager.getAllTileIcons(context)
        val tileRes = TileIconManager.getAllTileIconRes(context)
        val allTileIds = (tilePaths.keys + tileRes.keys).toSet()
        for (tileId in allTileIds) {
            val iconId = "tile_$tileId"
            if (iconId !in selectedIconIds) continue
            val entry = JSONObject()
            val customPath = tilePaths[tileId]
            if (!customPath.isNullOrEmpty()) {
                val file = File(customPath)
                if (file.exists()) {
                    val imageBytes = file.readBytes()
                    val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    entry.put("type", "custom")
                    entry.put("data", b64)
                } else {
                    continue
                }
            } else {
                val res = tileRes[tileId] ?: continue
                entry.put("type", "builtin")
                entry.put("res", res)  // keep for backward compat with older app versions
                val resName = TileIconManager.resolveResName(context, res)
                if (!resName.isNullOrEmpty()) entry.put("res_name", resName)
            }
            iconsObj.put(iconId, entry)
        }

        root.put("iconOverrides", iconsObj)
        return root.toString(2)
    }

    fun performExport(context: Context, selectedIconIds: Set<String>, targetFile: File, password: String? = null): Boolean {
        try {
            val jsonString = buildExportJson(context, selectedIconIds)
            val encryptedData = if (password != null && password.isNotEmpty()) {
                encryptPackV2(jsonString, password)
            } else {
                encryptPackPlain(jsonString)
            }
            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { out ->
                out.write(encryptedData)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export theme pack", e)
            return false
        }
    }

    // ── Import ──────────────────────────────────────────────────────

    fun parseImportJson(context: Context, jsonString: String): Map<String, IconOverride> {
        val root = JSONObject(jsonString)
        val iconsObj = root.optJSONObject("iconOverrides") ?: return emptyMap()

        val result = mutableMapOf<String, IconOverride>()
        val ids = iconsObj.keys()
        while (ids.hasNext()) {
            val iconId = ids.next()
            val entry = iconsObj.getJSONObject(iconId)
            val type = entry.optString("type", "")

            when (type) {
                "builtin" -> {
                    // Prefer stable name; fall back to legacy numeric ID for old packs
                    val resName = entry.optString("res_name", null)?.takeIf { it.isNotEmpty() }
                    val res: Int = if (resName != null) {
                        TileIconManager.resolveResId(context, resName)
                    } else {
                        entry.optInt("res", 0)
                    }
                    if (res != 0) {
                        result[iconId] = IconOverride(null, res)
                    }
                }
                "custom" -> {
                    val data = entry.optString("data", null)
                    if (data != null) {
                        val imageBytes = Base64.decode(data, Base64.DEFAULT)
                        val storageId = if (iconId.startsWith("tile_")) {
                            IconCustomizationManager::class.java.simpleName // won't be used
                        } else {
                            iconId
                        }
                        // Write to private storage
                        val iconsDir = File(context.filesDir, "custom_icons").also { it.mkdirs() }
                        val outFile = File(iconsDir, "${iconId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")}.png")
                        FileOutputStream(outFile).use { it.write(imageBytes) }
                        result[iconId] = IconOverride(outFile.absolutePath, 0)
                    }
                }
            }
        }
        return result
    }

    fun performImport(context: Context, sourceFile: File, password: String? = null): Pair<Boolean, Map<String, IconOverride>> {
        return try {
            if (!sourceFile.exists() || sourceFile.length() > MAX_FILE_SIZE) {
                return Pair(false, emptyMap())
            }
            val encryptedBytes = sourceFile.readBytes()
            val jsonString = decryptPack(encryptedBytes, password)
            val overrides = parseImportJson(context, jsonString)
            Pair(true, overrides)
        } catch (e: AEADBadTagException) {
            throw e // propagate so caller can do retry logic
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import theme pack", e)
            Pair(false, emptyMap())
        }
    }

    fun applyOverrides(context: Context, overrides: Map<String, IconOverride>) {
        for ((iconId, override) in overrides) {
            if (iconId.startsWith("tile_")) {
                val tileId = iconId.removePrefix("tile_")
                if (!override.customPath.isNullOrEmpty()) {
                    TileIconManager.saveTileIcon(context, tileId, override.customPath)
                } else if (override.builtinRes != 0) {
                    TileIconManager.saveTileIconRes(context, tileId, override.builtinRes)
                }
            } else {
                if (!override.customPath.isNullOrEmpty()) {
                    IconCustomizationManager.setCustomPath(context, iconId, override.customPath)
                } else if (override.builtinRes != 0) {
                    IconCustomizationManager.setBuiltinRes(context, iconId, override.builtinRes)
                }
            }
        }
    }
}
