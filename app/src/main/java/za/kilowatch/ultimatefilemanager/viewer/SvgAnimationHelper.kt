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
     * Checks whether the SVG contains interactive elements such as embedded scripts,
     * view targets, internal anchor links, or SMIL user-interaction triggers.
     */
    fun containsInteractiveElements(svgXml: String): Boolean {
        val lower = svgXml.lowercase()
        return lower.contains("<script") ||
                lower.contains("<view") ||
                Regex("<a\\s+[^>]*href\\s*=\\s*[\"']#", RegexOption.IGNORE_CASE).containsMatchIn(svgXml) ||
                Regex("begin\\s*=\\s*[\"'][^\"']*\\b(click|mousedown|mouseup|keydown|mouseover)\\b", RegexOption.IGNORE_CASE).containsMatchIn(svgXml) ||
                lower.contains(":target")
    }

    /**
     * Fast check for CSS `@keyframes` rules in the SVG's embedded `<style>` blocks.
     * CSS animations use the Web Animations API (not SMIL) for play/pause/seek.
     */
    fun containsCssAnimation(svgXml: String): Boolean {
        return svgXml.lowercase().contains("@keyframes")
    }

    /**
     * Extracts IDs of `<view id="...">` tags in the SVG document (useful for slide/panel navigation).
     */
    fun extractViewPanels(svgXml: String): List<String> {
        val pattern = Pattern.compile("<view\\s+[^>]*\\bid=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(svgXml)
        val panels = mutableListOf<String>()
        while (matcher.find()) {
            val id = matcher.group(1)
            if (!id.isNullOrEmpty() && !panels.contains(id)) {
                panels.add(id)
            }
        }
        return panels
    }

    /**
     * Sanitizes SVG XML. When [allowInlineScripts] is false (default), all `<script>` tags
     * and event listeners are stripped. When true (for rendering in our offline sandboxed WebView),
     * safe inline `<script>` tags are preserved while external script loads (`src=...`) and
     * remote URLs are stripped.
     */
    fun sanitizeSvg(svgXml: String, allowInlineScripts: Boolean = false): String {
        var sanitized = if (allowInlineScripts) {
            // Strip external script loads (src, href, xlink:href)
            val externalScript = Pattern.compile("<script\\b[^>]*\\b(src|href|xlink:href)\\s*=[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE)
            var s = externalScript.matcher(svgXml).replaceAll("")
            // Strip remote external URL anchors
            val remoteAnchors = Pattern.compile("<a\\b[^>]*\\b(href|xlink:href)\\s*=\\s*[\"']https?://[^\"']*[\"']", Pattern.CASE_INSENSITIVE)
            s = remoteAnchors.matcher(s).replaceAll("<a ")
            s
        } else {
            var s = SCRIPT_TAG_PATTERN.matcher(svgXml).replaceAll("")
            s = EVENT_HANDLER_PATTERN.matcher(s).replaceAll("")
            s
        }
        // Strip XML declaration if present so inline HTML parser doesn't trip
        sanitized = sanitized.replace(Regex("<\\?xml[^>]*\\?>", RegexOption.IGNORE_CASE), "")
        sanitized = sanitized.replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
        return sanitized.trim()
    }

    /**
     * Wraps the sanitized SVG in an HTML5 shell configured with strict Content Security Policy,
     * full-bleed responsive layout, and dark background matching UFM Media Player aesthetics.
     *
     * For interactive SVGs (those with `<view>` elements, `<script>`, CSS `:target`, or
     * `@keyframes` animations), the wrapper uses minimal CSS that does NOT override the SVG's
     * own styles, and injects a JavaScript shim that emulates `<view>` viewBox switching —
     * a browser feature that only works for standalone SVG documents, not inline SVGs in HTML.
     */
    fun wrapSvgInHtml(svgXml: String, allowInlineScripts: Boolean = true): String {
        val sanitizedSvg = sanitizeSvg(svgXml, allowInlineScripts = allowInlineScripts)
        val scriptCsp = if (allowInlineScripts) "script-src 'unsafe-inline';" else ""
        val isInteractive = containsInteractiveElements(svgXml) || containsCssAnimation(svgXml)

        return if (isInteractive) {
            wrapInteractiveSvgInHtml(sanitizedSvg, scriptCsp)
        } else {
            wrapStaticSvgInHtml(sanitizedSvg, scriptCsp)
        }
    }

    /**
     * Wraps a static/SMIL-only SVG with full viewport-fitting CSS.
     */
    private fun wrapStaticSvgInHtml(sanitizedSvg: String, scriptCsp: String): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=yes">
              <link rel="icon" href="data:,">
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; $scriptCsp img-src 'self' data:; frame-src 'none'; object-src 'none';">
              <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                html, body {
                  width: 100vw; height: 100vh;
                  background-color: #000000;
                  overflow: hidden;
                  display: flex; align-items: center; justify-content: center;
                  touch-action: manipulation;
                }
                svg {
                  width: 100vw; height: 100vh;
                  max-width: 100%; max-height: 100%;
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

    /**
     * Wraps an interactive SVG (with `<view>`, `<script>`, CSS animations, or `:target` selectors)
     * using minimal CSS that lets the SVG's own embedded styles control layout. Injects a
     * JavaScript shim that emulates native SVG `<view>` viewBox switching, which only works in
     * standalone SVG documents but not when the SVG is inlined in HTML.
     */
    private fun wrapInteractiveSvgInHtml(sanitizedSvg: String, scriptCsp: String): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=yes">
              <link rel="icon" href="data:,">
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; $scriptCsp img-src 'self' data:; frame-src 'none'; object-src 'none';">
              <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                html { background-color: #000000; width: 100vw; height: 100vh; overflow: hidden; }
                body {
                  width: 100vw; height: 100vh;
                  overflow: hidden;
                  display: flex; align-items: center; justify-content: center;
                  background-color: #000000;
                  touch-action: manipulation;
                }
                svg {
                  width: 100%;
                  height: 100%;
                  max-width: 100vw;
                  max-height: 100vh;
                  object-fit: contain;
                  overflow: hidden !important;
                }
              </style>
            </head>
            <body>
              $sanitizedSvg
              <script>
              /* UFM viewBox & panel clip shim: emulate native <view> viewBox switching and isolate panels */
              (function() {
                var svg = document.querySelector('svg');
                if (!svg) return;
                var origVB = svg.getAttribute('viewBox') || '0 0 1728 1728';

                var defs = svg.querySelector('defs');
                if (!defs) {
                  defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
                  svg.insertBefore(defs, svg.firstChild);
                }

                var clipPath = document.getElementById('ufm-viewbox-clip');
                var clipRect;
                if (!clipPath) {
                  clipPath = document.createElementNS('http://www.w3.org/2000/svg', 'clipPath');
                  clipPath.setAttribute('id', 'ufm-viewbox-clip');
                  clipRect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
                  clipRect.setAttribute('id', 'ufm-clip-rect');
                  clipPath.appendChild(clipRect);
                  defs.appendChild(clipPath);
                } else {
                  clipRect = document.getElementById('ufm-clip-rect');
                }

                // Clip #ss so other comicstrip panels do not bleed outside the active viewBox
                var ss = document.getElementById('ss');
                if (ss) {
                  ss.style.clipPath = 'url(#ufm-viewbox-clip)';
                }

                function applyViewBox() {
                  var h = location.hash.substring(1);
                  var vb = origVB;
                  if (h) {
                    var v = document.getElementById(h);
                    if (v && v.tagName && v.tagName.toLowerCase() === 'view' && v.getAttribute('viewBox')) {
                      vb = v.getAttribute('viewBox');
                    }
                  }
                  svg.setAttribute('viewBox', vb);
                  if (clipRect) {
                    var parts = vb.trim().split(/\s+/);
                    if (parts.length === 4) {
                      clipRect.setAttribute('x', parts[0]);
                      clipRect.setAttribute('y', parts[1]);
                      clipRect.setAttribute('width', parts[2]);
                      clipRect.setAttribute('height', parts[3]);
                    }
                  }
                }

                window.addEventListener('hashchange', applyViewBox);
                applyViewBox();
              })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
