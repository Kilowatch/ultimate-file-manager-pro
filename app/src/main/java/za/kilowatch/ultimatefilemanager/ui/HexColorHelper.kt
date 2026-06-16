package za.kilowatch.ultimatefilemanager.ui

import android.graphics.Color

/**
 * Utility for parsing, formatting, and validating hex colour strings.
 *
 * Supports both 6-digit (#RRGGBB) and 8-digit (#AARRGGBB) formats.
 * All operations are safe — never throws on invalid input.
 */
object HexColorHelper {

    private const val HEX_DIGITS = "0123456789ABCDEF"

    /**
     * Parses a hex colour string to an ARGB [Int].
     *
     * Accepts `#RRGGBB`, `#AARRGGBB`, `RRGGBB`, or `AARRGGBB`.
     * 6-digit strings are treated as fully opaque (alpha = FF).
     * Returns `null` for invalid input (wrong length, non-hex chars, etc.).
     */
    fun parseHex(hex: String): Int? {
        val cleaned = hex.removePrefix("#").trim().uppercase()
        return when (cleaned.length) {
            6 -> {
                val rgb = cleaned.toIntOrNull(16) ?: return null
                0xFF000000.toInt() or rgb
            }
            8 -> {
                cleaned.toLongOrNull(16)?.toInt() ?: return null
            }
            else -> null
        }
    }

    /**
     * Formats an ARGB colour [Int] to a hex string.
     *
     * - When alpha == 0xFF: returns `#RRGGBB` (6 digits).
     * - Otherwise: returns `#AARRGGBB` (8 digits).
     *
     * Always strips the alpha for fully opaque colours for readability.
     *
     * @param alwaysAlpha if true, always emit 8-digit `#AARRGGBB` even when alpha is 0xFF.
     */
    fun formatHex(color: Int, alwaysAlpha: Boolean = false): String {
        val alpha = Color.alpha(color)
        return if (alpha == 255 && !alwaysAlpha) {
            String.format("#%06X", 0x00FFFFFF and color)
        } else {
            String.format("#%08X", color)
        }
    }

    /**
     * Returns true if [hex] is a valid 6- or 8-digit hex colour string
     * (with or without leading `#`).
     */
    fun isValidHex(hex: String): Boolean {
        val cleaned = hex.removePrefix("#").trim()
        if (cleaned.length != 6 && cleaned.length != 8) return false
        return cleaned.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
    }

    /**
     * Returns true if [hex] is a valid partial hex string (0–8 hex digits
     * with an optional leading `#`). Useful for real-time validation during typing.
     */
    fun isValidOrPartialHex(hex: String): Boolean {
        val cleaned = hex.removePrefix("#").trim()
        if (cleaned.length > 8) return false
        return cleaned.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
    }
}
