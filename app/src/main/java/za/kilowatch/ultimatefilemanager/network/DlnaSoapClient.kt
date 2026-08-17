package za.kilowatch.ultimatefilemanager.network

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import za.kilowatch.ultimatefilemanager.server.DlnaSecurityFilter
import za.kilowatch.ultimatefilemanager.server.DlnaXmlParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SOAP client helper for the DLNA Media Client.
 *
 * Constructs and sends SOAP requests (Browse, GetProtocolInfo) to remote
 * DLNA / UPnP media servers and parses the DIDL-Lite responses into
 * [NetworkFile] lists.
 *
 * All network calls are blocking (synchronous OkHttp).  Callers should
 * invoke methods off the main thread.
 */
object DlnaSoapClient {

    private const val TAG = "DlnaSoapClient"

    // -----------------------------------------------------------------
    // HTTP Client
    // -----------------------------------------------------------------

    private val client: OkHttpClient = BypassCleartextOkHttpClient.applyBypass(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
    ).build()

    // -----------------------------------------------------------------
    // Media-URL cache
    // -----------------------------------------------------------------

    /**
     * Maps DLNA object IDs to their streaming media URLs.
     *
     * Populated by [browse] every time a DIDL-Lite response is parsed.
     * Consumers that need the raw stream URL for playback can call [getUrl].
     */
    private val urlCache = ConcurrentHashMap<String, String>()

    // -----------------------------------------------------------------
    // SOAP constants
    // -----------------------------------------------------------------

    private val XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaTypeOrNull()!!

    private const val SOAP_ACTION_BROWSE =
        "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"

    private const val SOAP_ACTION_PROTOCOL_INFO =
        "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo"

    private const val DIDL_LITE_NS =
        "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    private const val DC_NS =
        "http://purl.org/dc/elements/1.1/"

    // =================================================================
    // Public API
    // =================================================================

    /**
     * Sends a Browse SOAP request to a UPnP ContentDirectory service and
     * returns the resulting DIDL-Lite entries as a list of [NetworkFile].
     *
     * @param serviceUrl     The control URL of the ContentDirectory service.
     * @param objectId       The DLNA object ID to browse (e.g. "0" for root).
     * @param browseFlag     "BrowseDirectChildren" or "BrowseMetadata".
     * @param startIndex     Offset into the result set (pagination).
     * @param requestedCount Maximum number of entries to return (0 = all).
     * @return Parsed entries, or an empty list on any failure.
     */
    fun browse(
        serviceUrl: String,
        objectId: String,
        browseFlag: String,
        startIndex: Int = 0,
        requestedCount: Int = 0
    ): List<NetworkFile> {
        if (!DlnaSecurityFilter.validateUrl(serviceUrl)) {
            Log.w(TAG, "browse: server URL blocked by security filter: $serviceUrl")
            return emptyList()
        }

        val soapXml = buildBrowseSoapBody(objectId, browseFlag, startIndex, requestedCount)
        val requestBody = soapXml.toRequestBody(XML_MEDIA_TYPE)

        val request = Request.Builder()
            .url(serviceUrl)
            .post(requestBody)
            .addHeader("SOAPACTION", SOAP_ACTION_BROWSE)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "browse: HTTP ${response.code} from $serviceUrl (objectId=$objectId)")
                response.close()
                return emptyList()
            }

            val responseBody = response.body?.string() ?: ""
            val bodyLen = responseBody.length
            response.close()

            if (responseBody.isEmpty()) {
                Log.w(TAG, "browse: empty response body from $serviceUrl (objectId=$objectId)")
                return emptyList()
            }

            Log.d(TAG, "browse: HTTP 200 from $serviceUrl, bodyLen=$bodyLen, objectId=$objectId")
            val files = parseBrowseResponse(responseBody)
            Log.d(TAG, "browse: parsed ${files.size} items from DIDL-Lite")
            files
        } catch (e: IOException) {
            Log.w(TAG, "browse: network error for $serviceUrl (objectId=$objectId)", e)
            emptyList()
        }
    }

    /**
     * Sends a GetProtocolInfo SOAP request to a UPnP ConnectionManager
     * service to verify that the server is reachable and responds correctly.
     *
     * @return `true` if a valid SOAP response was received.
     */
    fun getProtocolInfo(serviceUrl: String): Boolean {
        if (!DlnaSecurityFilter.validateUrl(serviceUrl)) {
            Log.w(TAG, "getProtocolInfo: server URL blocked by security filter: $serviceUrl")
            return false
        }

        val soapXml = buildProtocolInfoSoapBody()
        val requestBody = soapXml.toRequestBody(XML_MEDIA_TYPE)

        val request = Request.Builder()
            .url(serviceUrl)
            .post(requestBody)
            .addHeader("SOAPACTION", SOAP_ACTION_PROTOCOL_INFO)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                Log.w(TAG, "getProtocolInfo: HTTP ${response.code} from $serviceUrl")
            }
            response.close()
            success
        } catch (e: IOException) {
            Log.w(TAG, "getProtocolInfo: network error for $serviceUrl", e)
            false
        }
    }

    /**
     * Returns the media-stream URL previously cached for the given
     * [objectId], or `null` if no such entry exists (e.g. the ID belongs
     * to a container or has not been browsed yet).
     */
    fun getUrl(objectId: String): String? = urlCache[objectId]

    // =================================================================
    // SOAP body construction
    // =================================================================

    /**
     * Builds the XML SOAP envelope for a Browse request.
     */
    private fun buildBrowseSoapBody(
        objectId: String,
        browseFlag: String,
        startIndex: Int,
        requestedCount: Int
    ): String {
        val safeId = DlnaXmlParser.sanitizeXmlText(objectId)
        val safeFlag = DlnaXmlParser.sanitizeXmlText(browseFlag)

        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <ObjectID>$safeId</ObjectID>
      <BrowseFlag>$safeFlag</BrowseFlag>
      <Filter>*</Filter>
      <StartingIndex>$startIndex</StartingIndex>
      <RequestedCount>$requestedCount</RequestedCount>
      <SortCriteria></SortCriteria>
    </u:Browse>
  </s:Body>
</s:Envelope>"""
    }

    /**
     * Builds the XML SOAP envelope for a GetProtocolInfo request.
     */
    private fun buildProtocolInfoSoapBody(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
    </u:GetProtocolInfo>
  </s:Body>
</s:Envelope>"""
    }

    // =================================================================
    // SOAP response parsing
    // =================================================================

    /**
     * Parses a Browse SOAP response XML string.
     *
     * Extracts the DIDL-Lite CDATA payload from the `<Result>` element and
     * delegates to [parseDidlLite].
     */
    private fun parseBrowseResponse(xml: String): List<NetworkFile> {
        return try {
            val builder = DlnaXmlParser.newSecureDocumentBuilder()
            val document = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val envelope = document.documentElement ?: return emptyList()

            val body = findChildByLocalName(envelope, "Body") ?: return emptyList()
            val browseResponse = findChildByLocalName(body, "BrowseResponse")
                ?: return emptyList()
            val resultElement = findChildByLocalName(browseResponse, "Result")
                ?: return emptyList()

            val didlLiteXml = resultElement.textContent?.trim()
            if (didlLiteXml.isNullOrBlank()) {
                Log.w(TAG, "parseBrowseResponse: empty or missing DIDL-Lite content")
                return emptyList()
            }

            parseDidlLite(didlLiteXml)
        } catch (e: Exception) {
            Log.w(TAG, "parseBrowseResponse: failed to parse SOAP response", e)
            emptyList()
        }
    }

    // =================================================================
    // DIDL-Lite parsing
    // =================================================================

    /**
     * Parses a DIDL-Lite XML document into a list of [NetworkFile] objects.
     *
     * - `<container>` elements are converted to directory entries (isDirectory = true).
     * - `<item>` elements are converted to file entries (isDirectory = false) with
     *   the size extracted from `res@size` and the media URL stored in [urlCache].
     */
    private fun parseDidlLite(didlXml: String): List<NetworkFile> {
        return try {
            val builder = DlnaXmlParser.newSecureDocumentBuilder()
            val document = builder.parse(
                ByteArrayInputStream(didlXml.toByteArray(Charsets.UTF_8))
            )
            val root = document.documentElement ?: return emptyList()

            val files = mutableListOf<NetworkFile>()
            val children = root.childNodes

            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType != Node.ELEMENT_NODE) continue

                val element = child as Element
                val nodeName = element.nodeName
                val localPart = if (nodeName.contains(":")) {
                    nodeName.substringAfterLast(':')
                } else {
                    nodeName
                }

                when (localPart) {
                    "container" -> {
                        parseContainerElement(element)?.let { files.add(it) }
                    }
                    "item" -> {
                        parseItemElement(element)?.let { files.add(it) }
                    }
                }
            }

            // Diagnostic: dump what we found
            if (files.isEmpty()) {
                Log.w(TAG, "parseDidlLite: no containers or items found in DIDL-Lite (${didlXml.length} chars)")
            } else {
                val sample = files.take(10).joinToString(", ") { "${it.name}[${it.path}]" + if (it.isDirectory) "/" else " (${it.size}b)" }
                Log.d(TAG, "parseDidlLite: ${files.size} items: $sample${if (files.size > 10) " ..." else ""}")
            }

            files
        } catch (e: Exception) {
            Log.w(TAG, "parseDidlLite: failed to parse DIDL-Lite XML", e)
            emptyList()
        }
    }

    /**
     * Converts a DIDL-Lite `<container>` element into a [NetworkFile].
     */
    private fun parseContainerElement(element: Element): NetworkFile? {
        val id = element.getAttribute("id")
        if (id.isBlank()) {
            Log.w(TAG, "parseContainerElement: container with no id attribute, skipping")
            return null
        }

        val title = resolveChildText(element, "title") ?: "Unknown"

        return NetworkFile(
            name = title,
            path = id,
            isDirectory = true,
            size = 0L,
            lastModified = 0L,
            iconRes = 0
        )
    }

    /**
     * Converts a DIDL-Lite `<item>` element into a [NetworkFile].
     *
     * The media-stream URL is extracted from the `<res>` child element and
     * cached in [urlCache] under the object ID.  The returned [NetworkFile]
     * uses the DLNA object ID as its path so the caller can re-browse into
     * it if needed; the actual URL is retrievable via [getUrl].
     */
    private fun parseItemElement(element: Element): NetworkFile? {
        val id = element.getAttribute("id")
        if (id.isBlank()) {
            Log.w(TAG, "parseItemElement: item with no id attribute, skipping")
            return null
        }

        var title = resolveChildText(element, "title") ?: "Unknown"

        // Extract the <res> element for the media URL, size, etc.
        val resElement = findChildByLocalName(element, "res")
        var size = 0L
        var mediaUrl = ""

        if (resElement != null) {
            mediaUrl = resElement.textContent?.trim() ?: ""

            val sizeAttr = resElement.getAttribute("size")
            if (sizeAttr.isNotBlank()) {
                try {
                    size = sizeAttr.toLong()
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "parseItemElement: invalid res@size '$sizeAttr' for item '$id'")
                }
            }

            // Many DLNA servers omit the file extension from dc:title and
            // use opaque IDs in media URLs. Try the media URL first, then
            // fall back to the MIME type from protocolInfo.
            val titleExt = title.substringAfterLast('.', "")
            var resolvedExt = ""
            if (mediaUrl.isNotBlank()) {
                val urlPath = try {
                    java.net.URI(mediaUrl).path ?: ""
                } catch (_: Exception) { "" }
                val urlFile = urlPath.substringAfterLast('/').substringBefore('?')
                resolvedExt = urlFile.substringAfterLast('.', "")
            }
            if (resolvedExt.isBlank() && resElement != null) {
                // Extract MIME type from protocolInfo="http-get:*:video/mp4:..."
                val protoInfo = resElement.getAttribute("protocolInfo")
                if (protoInfo.isNotBlank()) {
                    val parts = protoInfo.split(':')
                    if (parts.size >= 3) {
                        resolvedExt = mimeToExtension(parts[2])
                    }
                }
            }
            if (resolvedExt.isNotBlank() && !resolvedExt.equals(titleExt, ignoreCase = true)) {
                title = "$title.$resolvedExt"
            }
            if (resolvedExt.isBlank()) {
                Log.d(TAG, "parseItemElement: no extension resolved for '$title' (mediaUrl=$mediaUrl)")
            }
        }

        // Cache the media URL for later retrieval
        if (mediaUrl.isNotBlank()) {
            urlCache[id] = mediaUrl
        }

        return NetworkFile(
            name = title,
            path = id,
            isDirectory = false,
            size = size,
            lastModified = 0L,
            iconRes = 0
        )
    }

    // =================================================================
    // MIME type → file extension
    // =================================================================

    /** Maps common MIME types to file extensions for DLNA items that omit them. */
    private fun mimeToExtension(mimeType: String): String {
        val mime = mimeType.lowercase().trim()
        return when {
            mime.startsWith("video/mp4") -> "mp4"
            mime.startsWith("video/x-m4v") -> "m4v"
            mime.startsWith("video/x-matroska") -> "mkv"
            mime.startsWith("video/x-msvideo") -> "avi"
            mime.startsWith("video/quicktime") -> "mov"
            mime.startsWith("video/webm") -> "webm"
            mime.startsWith("video/x-motion-jpeg") || mime.startsWith("video/mjpeg") -> "mjpeg"
            mime.startsWith("video/mp2t") -> "ts"
            mime.startsWith("video/x-ms-vob") -> "vob"
            mime.startsWith("video/mpeg") || mime.startsWith("video/mpg") -> "mpg"
            mime.startsWith("video/ogg") -> "ogv"
            mime.startsWith("video/x-pn-realvideo") -> "rmvb"
            mime.startsWith("video/x-ms-asf") || mime.startsWith("video/x-ms-wmv") -> "wmv"
            mime.startsWith("video/x-flv") || mime.startsWith("video/x-f4v") -> "flv"
            mime.startsWith("video/3gpp2") -> "3g2"
            mime.startsWith("video/3gpp") -> "3gp"
            mime.startsWith("video/") -> "mp4"  // default video
            mime.startsWith("audio/mpeg") -> "mp3"
            mime.startsWith("audio/mp4") || mime.startsWith("audio/x-m4a") -> "m4a"
            mime.startsWith("audio/flac") -> "flac"
            mime.startsWith("audio/ogg") -> "ogg"
            mime.startsWith("audio/wav") || mime.startsWith("audio/x-wav") -> "wav"
            mime.startsWith("audio/aac") -> "aac"
            mime.startsWith("audio/wma") -> "wma"
            mime.startsWith("audio/") -> "mp3"  // default audio
            mime.startsWith("image/jpeg") -> "jpg"
            mime.startsWith("image/png") -> "png"
            mime.startsWith("image/gif") -> "gif"
            mime.startsWith("image/webp") -> "webp"
            mime.startsWith("image/bmp") -> "bmp"
            mime.startsWith("image/svg") -> "svg"
            mime.startsWith("image/heic") -> "heic"
            mime.startsWith("image/heif") -> "heif"
            mime.startsWith("image/avif") -> "avif"
            mime.startsWith("image/jxl") -> "jxl"
            mime.startsWith("image/x-canon") -> "cr3"
            mime.startsWith("image/x-nikon") -> "nef"
            mime.startsWith("image/x-sony") -> "arw"
            mime.startsWith("image/x-fuji") -> "raf"
            mime.startsWith("image/x-panasonic") -> "rw2"
            mime.startsWith("image/x-olympus") -> "orf"
            mime.startsWith("image/x-adobe-dng") -> "dng"
            mime.startsWith("image/vnd.adobe.photoshop") -> "psd"
            mime.startsWith("image/") -> "jpg"  // default image
            else -> ""
        }
    }

    // =================================================================
    // DOM helpers
    // =================================================================

    /**
     * Finds the first child element of [parent] whose local name matches
     * [localName].
     *
     * Handles both prefixed (`dc:title`) and un-prefixed (`title`) node
     * names, which is necessary because [DocumentBuilder] obtained from
     * [DlnaXmlParser.newSecureDocumentBuilder] is not namespace-aware.
     */
    private fun findChildByLocalName(parent: Element, localName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                val nodeName = el.nodeName
                if (nodeName == localName || nodeName.endsWith(":$localName")) {
                    return el
                }
            }
        }
        return null
    }

    /**
     * Tries to read the text content of the first child element with the
     * given [localName], returning `null` if no such child exists.
     *
     * First attempts a direct [localName] lookup (for un-prefixed elements
     * such as `<res>`), then falls back to a prefixed search (for elements
     * such as `<dc:title>` or `<upnp:class>`).
     */
    private fun resolveChildText(parent: Element, localName: String): String? {
        // Try exact match first (un-prefixed)
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                val nodeName = el.nodeName

                // Exact match: "title" or "dc:title" or "upnp:class"
                if (nodeName == localName || nodeName.endsWith(":$localName")) {
                    val text = el.textContent?.trim()
                    if (!text.isNullOrBlank()) {
                        return text
                    }
                }
            }
        }
        return null
    }

    /**
     * Clears the internal media-URL cache.
     *
     * Useful when switching between different DLNA servers or forcing a
     * refresh of cached URLs.
     */
    fun clearUrlCache() {
        urlCache.clear()
    }

    /**
     * Returns the number of entries currently in the media-URL cache.
     */
    fun urlCacheSize(): Int = urlCache.size
}
