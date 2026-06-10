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
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ThemePackManager {
    private const val TAG = "ThemePackManager"
    private const val MAGIC_HEADER = "UFM_THEME_V1"
    private const val AAD_STRING = "UFM_THEME_AAD_V1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128
    private const val MAX_FILE_SIZE = 10L * 1024 * 1024

    private fun getSecretKey(): SecretKeySpec {
        val passphrase = "za.kilowatch.ultimatefilemanager.theme.secret.key.v1"
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(passphrase.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
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

    fun decryptPack(encryptedBytes: ByteArray): String {
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
                entry.put("res", override.builtinRes)
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
                entry.put("res", res)
            }
            iconsObj.put(iconId, entry)
        }

        root.put("iconOverrides", iconsObj)
        return root.toString(2)
    }

    fun performExport(context: Context, selectedIconIds: Set<String>, targetFile: File): Boolean {
        try {
            val jsonString = buildExportJson(context, selectedIconIds)
            val encryptedData = encryptPack(jsonString)
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
                    val res = entry.optInt("res", 0)
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

    fun performImport(context: Context, sourceFile: File): Pair<Boolean, Map<String, IconOverride>> {
        return try {
            if (!sourceFile.exists() || sourceFile.length() > MAX_FILE_SIZE) {
                return Pair(false, emptyMap())
            }
            val encryptedBytes = sourceFile.readBytes()
            val jsonString = decryptPack(encryptedBytes)
            val overrides = parseImportJson(context, jsonString)
            Pair(true, overrides)
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
