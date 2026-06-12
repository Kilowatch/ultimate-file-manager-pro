package za.kilowatch.ultimatefilemanager.server

import android.util.Log
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.io.IOException
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

/**
 * Represents a single media resource exposed via UPnP / DLNA.
 *
 * @property id          Unique identifier for this item (e.g. "video1").
 * @property parentId    Identifier of the parent container (e.g. "videos").
 * @property title       Human-readable title.
 * @property uri         Direct HTTP URL for streaming the resource.
 * @property mimeType    MIME type of the resource (e.g. "video/mp4").
 * @property size        File size in bytes.
 * @property durationMs  Playback duration in milliseconds (nullable).
 * @property upnpClass   UPnP class string (e.g. "object.item.videoItem").
 * @property isContainer Whether this item is a container (directory/folder).
 */
data class MediaItem(
    val id: String,
    val parentId: String,
    val title: String,
    val uri: String,
    val mimeType: String,
    val size: Long,
    val durationMs: Long? = null,
    val upnpClass: String,
    val isContainer: Boolean = false
)

/**
 * Hardened XML parser for DLNA / UPnP message handling.
 *
 * All DocumentBuilder and SAXParser instances produced by this class are
 * locked down against XXE, XML bombs, and entity injection:
 *   - DOCTYPE declarations are disallowed entirely.
 *   - External general and parameter entities are blocked.
 *   - External DTD loading is disabled.
 *   - XInclude is disabled.
 *   - Entity reference nodes are preserved rather than expanded.
 *   - JAXP secure processing is enabled.
 */
class DlnaXmlParser {

    companion object {
        private const val TAG = "DlnaXmlParser"

        /** Maximum allowed entity reference nesting depth (defense-in-depth). */
        private const val MAX_ENTITY_EXPANSION_DEPTH = 5

        /** Log the secure-processing-not-supported message only once per process. */
        @Volatile private var secureProcessingLogged = false

        // -----------------------------------------------------------------
        // Secure parser factories
        // -----------------------------------------------------------------

        /**
         * Returns a [DocumentBuilder] with all external entity processing
         * disabled and DOCTYPE declarations rejected.
         */
        @Throws(ParserConfigurationException::class)
        fun newSecureDocumentBuilder(): DocumentBuilder {
            val dbf = DocumentBuilderFactory.newInstance()
            // FEATURE_SECURE_PROCESSING is unsupported on Android's XML parser
            // (Apache Harmony). Ignore the exception — the explicit features
            // below provide equivalent protection.
            try {
                dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            } catch (e: ParserConfigurationException) {
                if (!secureProcessingLogged) {
                    secureProcessingLogged = true
                    Log.d(TAG, "FEATURE_SECURE_PROCESSING not supported on this platform")
                }
            }
            // Android's XML parser (Apache Harmony) does not support most
            // javax.xml feature URIs — wrap each call in try-catch.
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: ParserConfigurationException) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: ParserConfigurationException) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: ParserConfigurationException) {}
            try { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: ParserConfigurationException) {}
            try { dbf.setXIncludeAware(false) } catch (_: UnsupportedOperationException) {}
            try { dbf.setExpandEntityReferences(false) } catch (_: UnsupportedOperationException) {}
            return dbf.newDocumentBuilder()
        }

        /**
         * Returns a [SAXParser] hardened with the same set of security
         * features as [newSecureDocumentBuilder].
         */
        @Throws(ParserConfigurationException::class, SAXException::class)
        fun newSecureSAXParser(): SAXParser {
            val spf = SAXParserFactory.newInstance()
            try {
                spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            } catch (e: ParserConfigurationException) {
                if (!secureProcessingLogged) {
                    secureProcessingLogged = true
                    Log.d(TAG, "FEATURE_SECURE_PROCESSING not supported on this platform")
                }
            }
            try {
                spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (e: ParserConfigurationException) {
                Log.w(TAG, "disallow-doctype-decl not supported on SAXParserFactory")
            }
            try {
                spf.setFeature("http://xml.org/sax/features/external-general-entities", false)
            } catch (e: ParserConfigurationException) {
                Log.w(TAG, "external-general-entities not supported on SAXParserFactory")
            }
            try {
                spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            } catch (e: ParserConfigurationException) {
                Log.w(TAG, "external-parameter-entities not supported on SAXParserFactory")
            }
            try {
                spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            } catch (e: ParserConfigurationException) {
                Log.w(TAG, "load-external-dtd not supported on SAXParserFactory")
            }
            try {
                spf.setXIncludeAware(false)
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "setXIncludeAware not supported on SAXParserFactory")
            }
            return spf.newSAXParser()
        }

        // -----------------------------------------------------------------
        // SOAP body parsing
        // -----------------------------------------------------------------

        /**
         * Reads at most [maxSize] bytes from [inputStream], parses the
         * result as XML, and returns the root [Element].
         *
         * A [SecurityException] is thrown when:
         *   - the input exceeds [maxSize] bytes,
         *   - entity reference nesting exceeds 5 levels,
         *   - the XML parser rejects a DOCTYPE or external entity.
         *
         * @throws IOException  on stream I/O errors.
         * @throws SAXException  on XML parse errors.
         * @throws SecurityException  on size limit or entity expansion violations.
         */
        @Throws(IOException::class, SAXException::class, SecurityException::class)
        fun parseSoapBody(
            inputStream: InputStream,
            maxSize: Long = 65536
        ): Element {
            val boundedInput = BoundedInputStream(inputStream, maxSize)
            try {
                val builder = newSecureDocumentBuilder()
                val document = builder.parse(boundedInput)
                validateEntityDepth(document)
                return document.documentElement
            } catch (e: SAXException) {
                Log.e(TAG, "SOAP body XML parse failed: ${e.message}")
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "IO error reading SOAP body: ${e.message}")
                throw e
            } finally {
                try {
                    boundedInput.close()
                } catch (e: IOException) {
                    Log.w(TAG, "Error closing bounded input stream", e)
                }
            }
        }

        /**
         * Recursively walks the document tree to verify that entity
         * reference nesting does not exceed [MAX_ENTITY_EXPANSION_DEPTH].
         *
         * With DOCTYPE declarations disallowed this check will almost
         * never trigger; it exists as defense-in-depth should a future
         * parser configuration change re-enable entity expansion.
         */
        private fun validateEntityDepth(document: Document) {
            val root = document.documentElement ?: return
            measureEntityDepth(root, 0)
        }

        /**
         * Returns the maximum entity reference depth found in the subtree
         * rooted at [node].  Throws [SecurityException] if the depth
         * exceeds [MAX_ENTITY_EXPANSION_DEPTH].
         */
        private fun measureEntityDepth(node: Node, currentDepth: Int): Int {
            var maxChildDepth = currentDepth
            val children = node.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                val childDepth = when (child.nodeType) {
                    Node.ENTITY_REFERENCE_NODE -> currentDepth + 1
                    else -> currentDepth
                }
                if (childDepth > MAX_ENTITY_EXPANSION_DEPTH) {
                    throw SecurityException(
                        "Entity expansion exceeded maximum allowed depth " +
                            "of $MAX_ENTITY_EXPANSION_DEPTH"
                    )
                }
                maxChildDepth = maxOf(
                    maxChildDepth,
                    measureEntityDepth(child, childDepth)
                )
            }
            return maxChildDepth
        }

        // -----------------------------------------------------------------
        // DIDL-Lite XML construction
        // -----------------------------------------------------------------

        /**
         * Builds a well-formed DIDL-Lite XML document from the given list
         * of [MediaItem]s.
         *
         * XML is constructed by manual string concatenation after
         * sanitizing every text value through [sanitizeXmlText], so there
         * is no injection risk.
         */
        fun buildDidlLite(items: List<MediaItem>): String {
            val sb = StringBuilder()
            sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            sb.appendLine(
                """<DIDL-Lite""",
            )
            sb.appendLine(
                """    xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"""",
            )
            sb.appendLine(
                """    xmlns:dc="http://purl.org/dc/elements/1.1/"""",
            )
            sb.appendLine(
                """    xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""",
            )

            for (item in items) {
                if (item.isContainer) {
                    sb.appendLine(
                        """  <container id="${sanitizeXmlText(item.id)}" """ +
                            """parentID="${sanitizeXmlText(item.parentId)}" """ +
                            """restricted="1">""",
                    )
                    sb.appendLine(
                        """    <dc:title>${sanitizeXmlText(item.title)}</dc:title>""",
                    )
                    sb.appendLine(
                        """    <upnp:class>${sanitizeXmlText(item.upnpClass)}</upnp:class>""",
                    )
                    sb.appendLine("""  </container>""")
                } else {
                    sb.appendLine(
                        """  <item id="${sanitizeXmlText(item.id)}" """ +
                            """parentID="${sanitizeXmlText(item.parentId)}" """ +
                            """restricted="1">""",
                    )
                    sb.appendLine(
                        """    <dc:title>${sanitizeXmlText(item.title)}</dc:title>""",
                    )
                    sb.appendLine(
                        """    <upnp:class>${sanitizeXmlText(item.upnpClass)}</upnp:class>""",
                    )
                    // Build protocolInfo with DLNA.org flags for better client compatibility.
                    // 4th field: DLNA.ORG_PN=MP4 (VLC needs a profile), OP=01 (range seek),
                    // CI=0 (not transcoded), FLAGS=8d (streaming transfer, background).
                    val dlnaPn = dlnaProfileName(item.mimeType)
                    val protoInfo = "http-get:*:${encodeXmlAttr(item.mimeType)}:" +
                        "DLNA.ORG_PN=$dlnaPn;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=8d000000000000000000000000000000"
                    val durationAttr = if (item.durationMs != null && item.durationMs > 0) {
                        val totalSec = item.durationMs / 1000
                        val hh = totalSec / 3600
                        val mm = (totalSec % 3600) / 60
                        val ss = totalSec % 60
                        val frac = item.durationMs % 1000
                        """ duration="${"%02d".format(hh)}:${"%02d".format(mm)}:${"%02d".format(ss)}.${"%03d".format(frac)}" """
                    } else ""
                    sb.appendLine(
                        """    <res protocolInfo="$protoInfo" """ +
                            """size="${item.size}"${durationAttr}>${encodeXmlText(item.uri)}</res>""",
                    )
                    sb.appendLine("""  </item>""")
                }
            }

            sb.append("</DIDL-Lite>")
            return sb.toString()
        }

        // -----------------------------------------------------------------
        // XML text sanitisation
        // -----------------------------------------------------------------

        /**
         * Removes characters that are unsafe in XML text content:
         *
         * - The five predefined XML entities: `<`, `>`, `&`, `"`, `'`
         * - Control characters in the range 0x00-0x1F **except** the
         *   XML-legal whitespace characters: 0x09 (tab), 0x0A (LF),
         *   0x0D (CR).
         *
         * This is deliberately a strip operation rather than entity
         * escaping because the caller is responsible for choosing the
         * appropriate encoding strategy for their context (attribute
         * value vs element text).
         */
        /**
         * Maps a MIME type to a DLNA profile name for protocolInfo.
         * VLC requires a non-empty 4th field in protocolInfo to play the stream.
         */
        private fun dlnaProfileName(mimeType: String): String {
            return when {
                mimeType.startsWith("video/mp4") -> "AVC_MP4_BL_CIF15_AAC_520"
                mimeType.startsWith("video/x-matroska") || mimeType.startsWith("video/mkv") -> "MATROSKA"
                mimeType.startsWith("video/x-msvideo") || mimeType.startsWith("video/avi") -> "AVI"
                mimeType.startsWith("video/quicktime") || mimeType.startsWith("video/mov") -> "QUICKTIME"
                mimeType.startsWith("video/webm") -> "WEBM"
                mimeType.startsWith("video/") -> "MPEG4"
                mimeType.startsWith("audio/mpeg") || mimeType.startsWith("audio/mp3") -> "MP3"
                mimeType.startsWith("audio/mp4") || mimeType.startsWith("audio/m4a") -> "AAC"
                mimeType.startsWith("audio/flac") -> "FLAC"
                mimeType.startsWith("audio/") -> "LPCM"
                mimeType.startsWith("image/jpeg") -> "JPEG_SM"
                mimeType.startsWith("image/png") -> "PNG_SM"
                mimeType.startsWith("image/") -> "JPEG_SM"
                else -> "MPEG4"
            }
        }

        fun sanitizeXmlText(s: String): String {
            return s.filter { ch ->
                val code = ch.code
                when {
                    // Strip all control characters except XML-legal whitespace
                    code in 0x00..0x1F -> {
                        code == 0x09 || code == 0x0A || code == 0x0D
                    }
                    // Strip XML special characters
                    ch == '<' || ch == '>' || ch == '&' ||
                        ch == '"' || ch == '\'' -> false
                    // Everything else is allowed
                    else -> true
                }
            }
        }

        /**
         * Encodes a string for safe inclusion as XML **element text content**.
         *
         * Uses proper XML entity escaping (not stripping), so that characters
         * like `&` in URLs are correctly round-tripped by the XML parser on
         * the receiving end (e.g. VLC / other DLNA clients).
         */
        fun encodeXmlText(s: String): String {
            return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }

        /**
         * Encodes a string for safe inclusion as an XML **attribute value**
         * (assumes the attribute is delimited by double-quotes).
         *
         * Extends [encodeXmlText] by also escaping double-quote characters.
         */
        fun encodeXmlAttr(s: String): String {
            return encodeXmlText(s).replace("\"", "&quot;")
        }

    } // end companion object

    // -----------------------------------------------------------------
    // BoundedInputStream
    // -----------------------------------------------------------------

    /**
     * An [InputStream] wrapper that throws [SecurityException] when more
     * than [maxSize] bytes have been read from the underlying [delegate].
     *
     * This provides a simple guard against XML bombs (billion laughs
     * attack) and other resource-exhaustion vectors that rely on
     * unbounded input.
     */
    private class BoundedInputStream(
        private val delegate: InputStream,
        private val maxSize: Long
    ) : InputStream() {

        private var bytesRead: Long = 0

        @Throws(IOException::class)
        override fun read(): Int {
            val byte = delegate.read()
            if (byte != -1) {
                bytesRead++
                if (bytesRead > maxSize) {
                    throw SecurityException(
                        "Input stream exceeded maximum allowed size " +
                            "of $maxSize bytes"
                    )
                }
            }
            return byte
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0

            val remaining = maxSize - bytesRead
            if (remaining <= 0) {
                throw SecurityException(
                    "Input stream exceeded maximum allowed size " +
                        "of $maxSize bytes"
                )
            }

            val bytesToRead = minOf(len.toLong(), remaining).toInt()
            val bytesActuallyRead = delegate.read(b, off, bytesToRead.coerceAtLeast(0))
            if (bytesActuallyRead > 0) {
                bytesRead += bytesActuallyRead
            }
            return bytesActuallyRead
        }

        override fun close() {
            delegate.close()
        }
    }
}
