package za.kilowatch.ultimatefilemanager.util

import android.webkit.MimeTypeMap

/**
 * Extension of [MimeTypeMap] that adds manual fallbacks for modern image formats
 * (AVIF, HEIC, HEIF) that are missing from [MimeTypeMap] on older Android API levels.
 *
 * Use [getOrFallback] instead of calling [MimeTypeMap.getMimeTypeFromExtension] directly
 * when the MIME type will be used in an [android.content.Intent] or file-sharing flow
 * where `null` / `*&#47;*` would degrade the user experience.
 */
object MimeTypeHelper {

    /**
     * Returns the MIME type for [ext] (case-insensitive, without leading dot),
     * falling back to a hard-coded value for formats not covered by [MimeTypeMap]
     * on older API levels, or `"application/octet-stream"` as a last resort.
     */
    fun getOrFallback(ext: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: fallback(ext.lowercase())

    private fun fallback(ext: String): String = when (ext) {
        "avif"          -> "image/avif"
        "heic"          -> "image/heic"
        "heif"          -> "image/heif"
        "jxl"           -> "image/jxl"   // future-proofing
        "yaml", "yml"   -> "text/yaml"
        "m3u", "m3u8"   -> "audio/x-mpegurl"
        "epub"          -> "application/epub+zip"
        else            -> "application/octet-stream"
    }
}
