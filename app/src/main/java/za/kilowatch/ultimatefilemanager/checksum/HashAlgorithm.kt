package za.kilowatch.ultimatefilemanager.checksum

import java.security.MessageDigest
import java.util.Locale

/**
 * Supported cryptographic and error-detection hashing algorithms for UFM Checksum Tools.
 */
enum class HashAlgorithm(
    val displayName: String,
    val standardName: String,
    val fileExtension: String,
    val hexLength: Int
) {
    CRC32("CRC32", "CRC32", "crc", 8),
    MD5("MD5", "MD5", "md5", 32),
    SHA1("SHA-1", "SHA-1", "sha1", 40),
    SHA256("SHA-256", "SHA-256", "sha256", 64),
    SHA512("SHA-512", "SHA-512", "sha512", 128);

    fun createMessageDigest(): MessageDigest? {
        return if (this == CRC32) {
            null
        } else {
            MessageDigest.getInstance(standardName)
        }
    }

    companion object {
        val ALL: List<HashAlgorithm> = entries.toList()

        fun fromStandardName(name: String): HashAlgorithm? {
            val normalized = name.trim().uppercase(Locale.ROOT).replace("-", "")
            return when (normalized) {
                "CRC32", "CRC" -> CRC32
                "MD5" -> MD5
                "SHA1", "SHA" -> SHA1
                "SHA256" -> SHA256
                "SHA512" -> SHA512
                else -> entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
            }
        }

        fun fromExtension(ext: String): HashAlgorithm? {
            val clean = ext.trim().lowercase(Locale.ROOT).removePrefix(".")
            return entries.firstOrNull { it.fileExtension == clean }
        }

        /**
         * Detects the algorithm purely from hex length, assuming valid hexadecimal input.
         */
        fun detectAlgorithmFromHex(hex: String): HashAlgorithm? {
            val clean = hex.trim().lowercase(Locale.ROOT)
            if (clean.isEmpty() || !clean.all { it in '0'..'9' || it in 'a'..'f' }) {
                return null
            }
            return when (clean.length) {
                8 -> CRC32
                32 -> MD5
                40 -> SHA1
                64 -> SHA256
                128 -> SHA512
                else -> null
            }
        }

        fun formatDigest(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(Integer.toHexString(v))
            }
            return sb.toString()
        }

        fun formatCrc32(crcValue: Long): String {
            return String.format(Locale.ROOT, "%08x", crcValue)
        }
    }
}
