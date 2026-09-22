package za.kilowatch.ultimatefilemanager.viewer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * Utility helper for SVG and SVGZ animation detection, safe decompression,
 * SMIL timeline parsing, security sanitization, and HTML wrapping for WebView playback.
 */
object SvgAnimationHelper {

    /** Maximum allowed decompressed size (15 MB) to guard against zip-bomb denial of service. */
    const val MAX_DECOMPRESSED_BYTES: Int = 15 * 1024 * 1024

    private val GZIP_HEADER = byteArrayOf(0x1F.toByte(), 0x8B.toByte())

    private val SMIL_TAGS = setOf(
        "animate",
        "animatetransform",
        "animatemotion",
        "set",
        "discard",
        "animatecolor"
    )

    private val SCRIPT_TAG_PATTERN = Pattern.compile("<script[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE)
    private val EVENT_HANDLER_PATTERN = Pattern.compile("\\s+on[a-zA-Z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE)

    /**
     * Checks whether [bytes] represent an uncompressed SVG or a gzip-compressed SVGZ.
     */
    fun isSvgOrSvgz(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        if (isGzipHeader(bytes)) {
            return try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
                    val buf = ByteArray(512)
                    val len = gz.read(buf)
                    if (len > 0) {
                        val text = String(buf, 0, len, Charsets.UTF_8).lowercase()
                        text.contains("<svg") || (text.contains("<?xml") && text.contains("svg"))
                    } else false
                }
            } catch (_: Throwable) {
                false
            }
        }
        val headerLen = bytes.size.coerceAtMost(2048)
        val sample = String(bytes, 0, headerLen, Charsets.UTF_8).lowercase()
        return sample.contains("<svg") || (sample.contains("<?xml") && sample.contains("svg"))
    }

    /**
     * Checks whether [file] represents an uncompressed SVG or a gzip-compressed SVGZ.
     */
    fun isSvgOrSvgz(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext == "svg" || ext == "svgz") return true
        if (!file.exists() || !file.isFile || file.length() < 2) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(512)
                val read = input.read(header)
                if (read >= 2) {
                    isSvgOrSvgz(header.copyOf(read))
                } else false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks whether [stream] starts with GZIP or SVG headers.
     */
    fun isSvgOrSvgz(stream: InputStream): Boolean {
        return try {
            val header = ByteArray(512)
            val read = stream.read(header)
            if (read >= 2) {
                isSvgOrSvgz(header.copyOf(read))
            } else false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * True if the first 2 bytes match GZIP magic: 0x1F, 0x8B.
     */
    fun isGzipHeader(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && bytes[0] == GZIP_HEADER[0] && bytes[1] == GZIP_HEADER[1]
    }

    /**
     * Safely decompresses [bytes] if compressed with GZIP, enforcing [maxAllowedBytes]
     * to prevent decompression zip bombs. If uncompressed, returns [bytes] directly.
     */
    @Throws(SecurityException::class, Exception::class)
    fun decompressIfNeeded(
        bytes: ByteArray,
        maxAllowedBytes: Int = MAX_DECOMPRESSED_BYTES
    ): ByteArray {
        if (!isGzipHeader(bytes)) {
            return bytes
        }
        val out = ByteArrayOutputStream(bytes.size.coerceAtLeast(1024))
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
            val buf = ByteArray(8192)
            var totalRead = 0
            var len: Int
            while (gz.read(buf).also { len = it } != -1) {
                totalRead += len
                if (totalRead > maxAllowedBytes) {
                    throw SecurityException("Decompressed SVG size ($totalRead bytes) exceeds safe limit of $maxAllowedBytes bytes")
                }
                out.write(buf, 0, len)
            }
        }
        return out.toByteArray()
    }

    /**
     * Safely reads and decompresses [file], enforcing [maxAllowedBytes] size threshold.
     */
    @Throws(SecurityException::class, Exception::class)
    fun decompressIfNeeded(
        file: File,
        maxAllowedBytes: Int = MAX_DECOMPRESSED_BYTES
    ): ByteArray {
        val raw = file.readBytes()
        return decompressIfNeeded(raw, maxAllowedBytes)
    }

    /**
     * Fast check to determine if the SVG contains any SMIL animation elements.
     */
    fun containsSmilAnimation(svgXml: String): Boolean {
        val lower = svgXml.lowercase()
        return SMIL_TAGS.any { tag -> lower.contains("<$tag") }
    }

    /**
     * Pre-parses animation duration across all SMIL tags (<animate>, <animateTransform>, etc.).
     *
     * Returns:
     * - `null` if the animation is unbounded (e.g. repeatCount="indefinite", dur="indefinite",
     *   repeatDur="indefinite") or if no animation tags are found.
     * - `Long` duration in milliseconds if all active animations are finite.
     */
    fun parseAnimationDurationMs(svgXml: String): Long? {
        if (!containsSmilAnimation(svgXml)) {
            return null
        }

        return try {
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = false
            }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(svgXml))

            var maxTagDurationMs = 0L
            var hasAnyAnimationTag = false
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tagName = parser.name.lowercase()
                    if (tagName in SMIL_TAGS) {
                        hasAnyAnimationTag = true
                        val durAttr = parser.getAttributeValue(null, "dur")?.trim()?.lowercase()
                        val repeatCountAttr = parser.getAttributeValue(null, "repeatCount")?.trim()?.lowercase()
                        val repeatDurAttr = parser.getAttributeValue(null, "repeatDur")?.trim()?.lowercase()
                        val beginAttr = parser.getAttributeValue(null, "begin")?.trim()?.lowercase()
                        val endAttr = parser.getAttributeValue(null, "end")?.trim()?.lowercase()

                        // Unbounded check
                        if (durAttr == "indefinite" || repeatCountAttr == "indefinite" || repeatDurAttr == "indefinite") {
                            return null
                        }

                        val durMs = parseClockValueMs(durAttr) ?: 0L
                        if (durMs <= 0L) {
                            // Without a finite duration, the SMIL spec treats it as indefinite or static
                            // Continue evaluating other tags or return null if unbounded
                        }

                        val beginMs = parseClockValueMs(beginAttr) ?: 0L
                        val repeatCount = repeatCountAttr?.toFloatOrNull() ?: 1.0f

                        var tagTotalMs = (durMs * repeatCount).toLong()

                        // Check repeatDur clamp
                        val repeatDurMs = parseClockValueMs(repeatDurAttr)
                        if (repeatDurMs != null && repeatDurMs < tagTotalMs) {
                            tagTotalMs = repeatDurMs
                        }

                        var tagEndMs = beginMs + tagTotalMs

                        // Check explicit end clamp
                        val endMs = parseClockValueMs(endAttr)
                        if (endMs != null && endMs < tagEndMs) {
                            tagEndMs = endMs
                        }

                        if (tagEndMs > maxTagDurationMs) {
                            maxTagDurationMs = tagEndMs
                        }
                    }
                }
                eventType = parser.next()
            }

            if (hasAnyAnimationTag && maxTagDurationMs > 0L) {
                maxTagDurationMs
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Parses standard SMIL clock values (e.g. "5s", "500ms", "1.5s", "0.2s", "10").
     */
    fun parseClockValueMs(clockStr: String?): Long? {
        if (clockStr.isNullOrEmpty()) return null
        val lower = clockStr.trim().lowercase()
        return try {
            when {
                lower.endsWith("ms") -> {
                    lower.removeSuffix("ms").trim().toDoubleOrNull()?.toLong()
                }
                lower.endsWith("s") -> {
                    val sec = lower.removeSuffix("s").trim().toDoubleOrNull() ?: return null
                    (sec * 1000.0).toLong()
                }
                lower.endsWith("min") -> {
                    val min = lower.removeSuffix("min").trim().toDoubleOrNull() ?: return null
                    (min * 60.0 * 1000.0).toLong()
                }
                lower.endsWith("h") -> {
                    val hr = lower.removeSuffix("h").trim().toDoubleOrNull() ?: return null
                    (hr * 3600.0 * 1000.0).toLong()
                }
                lower.contains(':') -> {
                    // HH:MM:SS or MM:SS format
                    val parts = lower.split(':')
                    var totalSeconds = 0.0
                    for (part in parts) {
                        totalSeconds = totalSeconds * 60.0 + (part.toDoubleOrNull() ?: 0.0)
                    }
                    (totalSeconds * 1000.0).toLong()
                }
                else -> {
                    // Default unit is seconds in SMIL if not specified
                    val sec = lower.toDoubleOrNull() ?: return null
                    (sec * 1000.0).toLong()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Strips any `<script>` tags and inline event listeners (`onload`, `onclick`) from the SVG
     * XML to protect against XSS when rendered in WebView.
     */
    fun sanitizeSvg(svgXml: String): String {
        var sanitized = SCRIPT_TAG_PATTERN.matcher(svgXml).replaceAll("")
        sanitized = EVENT_HANDLER_PATTERN.matcher(sanitized).replaceAll("")
        // Strip XML declaration if present so inline HTML parser doesn't trip
        sanitized = sanitized.replace(Regex("<\\?xml[^>]*\\?>", RegexOption.IGNORE_CASE), "")
        sanitized = sanitized.replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
        return sanitized.trim()
    }

    /**
     * Wraps the sanitized SVG in an HTML5 shell configured with strict Content Security Policy,
     * full-bleed responsive layout, and dark background matching UFM Media Player aesthetics.
     */
    fun wrapSvgInHtml(svgXml: String): String {
        val sanitizedSvg = sanitizeSvg(svgXml)
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
              <link rel="icon" href="data:,">
              <!-- Strict Content Security Policy: block all external scripts, fonts, networks -->
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; frame-src 'none'; object-src 'none';">
              <style>
                * {
                  margin: 0;
                  padding: 0;
                  box-sizing: border-box;
                }
                html, body {
                  width: 100vw;
                  height: 100vh;
                  background-color: #000000;
                  overflow: hidden;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  user-select: none;
                  -webkit-user-select: none;
                  -webkit-touch-callout: none;
                }
                svg {
                  width: 100vw;
                  height: 100vh;
                  max-width: 100%;
                  max-height: 100%;
                  object-fit: contain;
                }
              </style>
            </head>
            <body>
              $sanitizedSvg
            </body>
            </html>
        """.trimIndent()
    }
}
