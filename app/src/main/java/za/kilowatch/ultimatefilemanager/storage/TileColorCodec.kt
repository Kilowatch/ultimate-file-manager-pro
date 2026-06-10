package za.kilowatch.ultimatefilemanager.storage

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Encodes and decodes shareable tile-colour codes.
 *
 * Code format:  UFM-{16chars}-{16chars}-{16chars}   (54 chars total)
 *
 * Internals:
 *   Binary payload  = 5 × Int (ARGB colors: icon, tileBg, ring, iconBg, label)
 *                   + 4 random nonce bytes  →  24 bytes total
 *   Base64url(24 bytes) = 32 chars   (no padding; 24 is divisible by 3)
 *   HMAC-SHA256(payload)[0..7] as hex = 16 chars
 *   data = base64(32) + hmac(16) = 48 chars
 *   Split into 3 × 16 and join with "-" → "UFM-{16}-{16}-{16}"
 *
 * Security:
 *   - HMAC with embedded key prevents forged codes.
 *   - Random 4-byte nonce makes every export unique even for identical colours.
 *   - Constant-time comparison used when verifying HMAC to prevent timing attacks.
 */
object TileColorCodec {

    // ── Constants ────────────────────────────────────────────────────────────

    private const val PREFIX       = "UFMP"
    private const val CHUNK_COUNT  = 3
    private const val CHUNK_LEN    = 16
    private const val PAYLOAD_BYTES = 24   // 5 colors × 4 + 4 nonce
    private const val ALGO         = "HmacSHA256"

    // Embedded signing key — not a user-visible secret; prevents trivial forgery.
    // Changing this invalidates all previously generated codes.
    @Suppress("SpellCheckingInspection")
    private val SIGNING_KEY = "UFMP-TC-v1-SIGN-2025-K9x!mP".toByteArray(Charsets.UTF_8)

    private val rng = SecureRandom()

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Encode a [TileColorConfig] into a shareable, HMAC-verified code string.
     * Returns a new unique string on every call due to the random nonce.
     */
    fun encode(config: TileColorConfig): String {
        val nonce = ByteArray(4).also { rng.nextBytes(it) }

        val payload = ByteArray(PAYLOAD_BYTES)
        writeInt(payload, 0,  config.iconColor)
        writeInt(payload, 4,  config.tileBgColor)
        writeInt(payload, 8,  config.ringColor)
        writeInt(payload, 12, config.iconBgColor)
        writeInt(payload, 16, config.labelColor)
        nonce.copyInto(payload, 20)

        val b64     = Base64.encodeToString(payload, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
        val hmacHex = hmacHex(payload)
        val data    = b64 + hmacHex                    // 32 + 16 = 48 chars

        val chunks  = data.chunked(CHUNK_LEN).joinToString("-")
        return "$PREFIX-$chunks"
    }

    /**
     * Decode a code string produced by [encode].
     * Returns [TileColorConfig] on success, or `null` if the code is
     * structurally invalid or the HMAC check fails.
     */
    fun decode(code: String): TileColorConfig? {
        if (!isValid(code)) return null

        val data = code.removePrefix("$PREFIX-").replace("-", "")   // 48 chars
        if (data.length != CHUNK_COUNT * CHUNK_LEN) return null

        val b64Portion  = data.take(32)
        val givenHmac   = data.drop(32)

        val payload = try {
            Base64.decode(b64Portion, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
        } catch (_: Exception) { return null }

        if (payload.size != PAYLOAD_BYTES) return null

        // Constant-time HMAC comparison
        val expectedHmac = hmacHex(payload)
        if (!MessageDigest.isEqual(givenHmac.toByteArray(), expectedHmac.toByteArray())) return null

        return TileColorConfig(
            iconColor   = readInt(payload, 0),
            tileBgColor = readInt(payload, 4),
            ringColor   = readInt(payload, 8),
            iconBgColor = readInt(payload, 12),
            labelColor  = readInt(payload, 16)
        )
    }

    /**
     * Quick structural validity check (does not verify HMAC).
     * Use [decode] for full validation with tamper detection.
     */
    fun isValid(code: String): Boolean {
        if (!code.startsWith("$PREFIX-")) return false
        val parts = code.removePrefix("$PREFIX-").split("-")
        return parts.size == CHUNK_COUNT && parts.all { it.length == CHUNK_LEN }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Returns the first 8 bytes of HMAC-SHA256(payload) as a 16-char lowercase hex string. */
    private fun hmacHex(payload: ByteArray): String {
        val mac = Mac.getInstance(ALGO)
        mac.init(SecretKeySpec(SIGNING_KEY, ALGO))
        return mac.doFinal(payload).take(8).joinToString("") { "%02x".format(it) }
    }

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr  8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun readInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt()     and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl  8) or
         (buf[offset + 3].toInt() and 0xFF)
}
