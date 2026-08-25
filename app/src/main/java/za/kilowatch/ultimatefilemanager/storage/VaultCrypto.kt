package za.kilowatch.ultimatefilemanager.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object VaultCrypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ufm_vault_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128
    private const val BUFFER_SIZE = 1024 * 128 // 128 KB high-throughput buffer

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                cachedKey = existing
                return existing
            }

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            val newKey = keyGenerator.generateKey()
            cachedKey = newKey
            newKey
        }
    }

    /** Clears the cached KeyStore SecretKey reference from memory. */
    fun clearKeyCache() {
        cachedKey = null
    }

    fun encryptFile(input: File, output: File) {
        encryptFile(context = null, input = input, output = output)
    }

    fun encryptFile(context: android.content.Context? = null, input: File, output: File) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv

        output.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(output), BUFFER_SIZE).use { fileOut ->
            fileOut.write(iv.size)
            fileOut.write(iv)
            CipherOutputStream(fileOut, cipher).use { cipherOut ->
                val isSaf = context != null && (input is SafFile ||
                            SafTreeManager.isSafPath(input.absolutePath) ||
                            SafTreeManager.hasTreePermissionForPath(context, input.absolutePath))
                val rawInStream = if (isSaf) {
                    SafTreeManager.openInputStream(context!!, input.absolutePath)
                } else {
                    FileInputStream(input)
                } ?: throw java.io.FileNotFoundException("Cannot read ${input.absolutePath}")

                BufferedInputStream(rawInStream, BUFFER_SIZE).use { inputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } > 0) {
                        cipherOut.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    fun decryptFile(input: File, output: File) {
        val key = getOrCreateKey()
        BufferedInputStream(FileInputStream(input), BUFFER_SIZE).use { fileIn ->
            val ivLength = fileIn.read()
            if (ivLength <= 0 || ivLength > 32) return
            val iv = ByteArray(ivLength)
            var bytesRead = 0
            while (bytesRead < ivLength) {
                val read = fileIn.read(iv, bytesRead, ivLength - bytesRead)
                if (read < 0) return
                bytesRead += read
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE, iv))

            output.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(output), BUFFER_SIZE).use { fileOut ->
                CipherInputStream(fileIn, cipher).use { cipherIn ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (cipherIn.read(buffer).also { read = it } > 0) {
                        fileOut.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    /** Encrypts a string and returns a Base64-encoded blob containing the IV and ciphertext. */
    fun encryptString(plainText: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        // Output format: [1 byte IV length][IV][Ciphertext]
        val output = ByteArray(1 + iv.size + cipherText.size)
        output[0] = iv.size.toByte()
        System.arraycopy(iv, 0, output, 1, iv.size)
        System.arraycopy(cipherText, 0, output, 1 + iv.size, cipherText.size)
        
        return android.util.Base64.encodeToString(output, android.util.Base64.NO_WRAP)
    }

    /** Decrypts a Base64-encoded blob created by [encryptString]. */
    fun decryptString(encryptedBlob: String): String {
        val key = getOrCreateKey()
        val data = android.util.Base64.decode(encryptedBlob, android.util.Base64.NO_WRAP)
        
        val ivLength = data[0].toInt()
        val iv = ByteArray(ivLength)
        System.arraycopy(data, 1, iv, 0, ivLength)
        
        val cipherTextLength = data.size - 1 - ivLength
        val cipherText = ByteArray(cipherTextLength)
        System.arraycopy(data, 1 + ivLength, cipherText, 0, cipherTextLength)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE, iv))
        val plainTextBytes = cipher.doFinal(cipherText)
        
        return String(plainTextBytes, Charsets.UTF_8)
    }

    /** Encrypts a list of strings into a single encrypted Base64 payload. */
    fun encryptStrings(list: List<String>): String {
        val jsonArray = JSONArray()
        list.forEach { jsonArray.put(it) }
        return encryptString(jsonArray.toString())
    }

    /** Decrypts a Base64 payload containing a JSON array of strings. */
    fun decryptStrings(encryptedBlob: String): List<String> {
        val jsonString = decryptString(encryptedBlob)
        val jsonArray = JSONArray(jsonString)
        val list = ArrayList<String>(jsonArray.length())
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list
    }
}

