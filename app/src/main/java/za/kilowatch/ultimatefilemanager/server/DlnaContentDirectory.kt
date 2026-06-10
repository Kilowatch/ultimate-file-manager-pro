package za.kilowatch.ultimatefilemanager.server

import android.util.Log
import org.w3c.dom.Element
import org.w3c.dom.Node
import za.kilowatch.ultimatefilemanager.server.DlnaSecurityFilter.EndpointType

/**
 * ContentDirectory:1 SOAP service handler for the DLNA Media Server.
 *
 * Dispatches incoming UPnP Browse() actions against [DlnaMediaIndex] and
 * returns well-formed SOAP response envelopes.
 *
 * ## Thread safety
 *
 * This object is stateless — all reads flow through [DlnaMediaIndex] which is
 * guarded by a [java.util.concurrent.locks.ReentrantReadWriteLock].  Calling
 * [handleSoapAction] from multiple server threads is safe.
 */
object DlnaContentDirectory {

    private const val TAG = "DlnaContentDirectory"

    /** Maximum number of children returned in a single browse page. */
    private const val MAX_PAGE_SIZE = 200

    /** Content directory system update ID (incremented on index change). */
    private const val UPDATE_ID = 1

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Entry point for SOAP action dispatch.
     *
     * @param actionName   The UPnP action name (e.g. `"Browse"`).
     * @param soapBody     The `<s:Body>` [Element] of the parsed SOAP envelope.
     * @param sourceIp     The requesting client's IP address (for rate limiting).
     * @param allowedRoots File-system root paths permitted for access.
     * @return A complete SOAP 1.1 envelope XML string (response or fault).
     */
    fun handleSoapAction(
        actionName: String,
        soapBody: Element,
        sourceIp: String,
        allowedRoots: List<String>,
        baseUrl: String = ""
    ): String {
        return when (actionName) {
            "Browse" -> handleBrowse(soapBody, sourceIp, allowedRoots, baseUrl)
            else -> {
                Log.w(TAG, "Unsupported SOAP action: $actionName")
                buildSoapFault("Unknown action: $actionName")
            }
        }
    }

    // =========================================================================
    // Browse dispatch
    // =========================================================================

    /**
     * Handle the Browse() SOAP action.
     *
     * Extracts parameters from the `<u:Browse>` element and dispatches to
     * [handleBrowseMetadata] or [handleBrowseDirectChildren] depending on the
     * [BrowseFlag] value.
     */
    private fun handleBrowse(
        soapBody: Element,
        sourceIp: String,
        allowedRoots: List<String>,
        baseUrl: String = ""
    ): String {
        // 1. Rate-limit per source IP
        if (!DlnaSecurityFilter.allowRequest(sourceIp, EndpointType.HTTP_BROWSE)) {
            Log.w(TAG, "Rate limit exceeded for $sourceIp")
            return buildSoapFault("Rate limit exceeded")
        }

        // 2. Navigate: <s:Envelope> -> <s:Body> -> <u:Browse>
        val bodyElement = findChildElement(soapBody, "Body") ?: soapBody
        val browseElement = findChildElement(bodyElement, "Browse")
            ?: return buildSoapFault("Malformed Browse request")

        // 3. Extract standard Browse parameters
        val rawObjectId = extractStringParam(browseElement, "ObjectID", "0")
        val browseFlag = extractStringParam(browseElement, "BrowseFlag", "BrowseMetadata")
        val filter = extractStringParam(browseElement, "Filter", "*")
        val startingIndex = extractIntParam(browseElement, "StartingIndex", 0)
        var requestedCount = extractIntParam(browseElement, "RequestedCount", 0)
        // SortCriteria is accepted but ignored in ContentDirectory:1
        // (passed through for future-proofing / logging)
        val sortCriteria = extractStringParam(browseElement, "SortCriteria", "")

        Log.d(TAG, "Browse: objectId=$rawObjectId flag=$browseFlag " +
            "startIndex=$startingIndex count=$requestedCount sort=$sortCriteria")

        // 4. Validate object ID against path traversal
        val objectId = validateObjectId(rawObjectId)
            ?: return buildSoapFault("Invalid object ID")

        // 5. Cap requested count (0 means return all, but we cap at MAX_PAGE_SIZE)
        if (requestedCount <= 0 || requestedCount > MAX_PAGE_SIZE) {
            requestedCount = MAX_PAGE_SIZE
        }

        // 6. Dispatch by BrowseFlag
        return when (browseFlag) {
            "BrowseMetadata" -> handleBrowseMetadata(objectId, filter, allowedRoots)
            "BrowseDirectChildren" -> handleBrowseDirectChildren(
                objectId, startingIndex, requestedCount, filter, allowedRoots, baseUrl
            )
            else -> buildSoapFault("Unsupported BrowseFlag: $browseFlag")
        }
    }

    // =========================================================================
    // BrowseMetadata — single container / item
    // =========================================================================

    /**
     * Return metadata for a single container or item identified by [objectId].
     *
     * The result DIDL-Lite document contains exactly one element (the requested
     * container or item itself).
     */
    private fun handleBrowseMetadata(
        objectId: String,
        filter: String,
        allowedRoots: List<String>
    ): String {
        val item = DlnaMediaIndex.getItem(objectId)

        if (item == null) {
            Log.d(TAG, "Metadata requested for unknown object: $objectId")
            return buildBrowseResponse("", 0, 0)
        }

        // Validate file path if the item has one
        val itemsToReturn = if (item.uri.isNotBlank()) {
            val validatedPath = DlnaSecurityFilter.validatePath(item.uri, allowedRoots)
            if (validatedPath != null) {
                listOf(item)
            } else {
                Log.w(TAG, "Metadata path validation failed for item ${item.id}")
                emptyList()
            }
        } else {
            // Virtual container or item without a file-system URI — always safe
            listOf(item)
        }

        val didlLite = DlnaXmlParser.buildDidlLite(itemsToReturn)
        return buildBrowseResponse(didlLite, itemsToReturn.size, itemsToReturn.size)
    }

    // =========================================================================
    // BrowseDirectChildren — paginated child listing
    // =========================================================================

    /**
     * Return a paginated list of child items for container [objectId].
     *
     * All returned items have their file paths validated against [allowedRoots]
     * via [DlnaSecurityFilter.validatePath]; items with invalid paths are
     * silently omitted from the result.
     */
    private fun handleBrowseDirectChildren(
        objectId: String,
        startingIndex: Int,
        requestedCount: Int,
        filter: String,
        allowedRoots: List<String>,
        baseUrl: String = ""
    ): String {
        val children = DlnaMediaIndex.getChildren(objectId, startingIndex, requestedCount)
        val totalMatches = DlnaMediaIndex.getTotalMatches(objectId)

        // Validate every path-bearing item against the allowed roots,
        // and map file URIs to DLNA HTTP stream URLs.
        val mediaBaseUrl = baseUrl.trimEnd('/')
        val validatedChildren = children.filter { item ->
            if (item.uri.isNotBlank()) {
                val valid = DlnaSecurityFilter.validatePath(item.uri, allowedRoots) != null
                if (!valid) {
                    Log.w(TAG, "Filtering out item ${item.id} with invalid path: ${item.uri}")
                }
                valid
            } else {
                true
            }
        }.map { item ->
            if (!item.isContainer && item.uri.isNotBlank() && mediaBaseUrl.isNotEmpty()) {
                item.copy(uri = "$mediaBaseUrl/media/${item.id}")
            } else {
                item
            }
        }

        val didlLite = DlnaXmlParser.buildDidlLite(validatedChildren)
        Log.d(TAG, "BrowseDirectChildren: returned ${validatedChildren.size}/$totalMatches " +
            "items for container $objectId")
        return buildBrowseResponse(didlLite, validatedChildren.size, totalMatches)
    }

    // =========================================================================
    // SOAP response builders
    // =========================================================================

    /**
     * Build a complete SOAP 1.1 envelope for a BrowseResponse.
     *
     * The DIDL-Lite XML is placed inside a `<![CDATA[ ... ]]>` block within the
     * `<Result>` element so that SOAP XML parsers do not attempt to interpret
     * the embedded DIDL-Lite markup.
     */
    private fun buildBrowseResponse(
        didlLite: String,
        numberReturned: Int,
        totalMatches: Int
    ): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <Result><![CDATA[$didlLite]]></Result>
      <NumberReturned>$numberReturned</NumberReturned>
      <TotalMatches>$totalMatches</TotalMatches>
      <UpdateID>$UPDATE_ID</UpdateID>
    </u:BrowseResponse>
  </s:Body>
</s:Envelope>"""
    }

    /**
     * Build a SOAP 1.1 fault envelope.
     *
     * The [message] is sanitised via [DlnaXmlParser.sanitizeXmlText] before
     * inclusion to prevent XML injection.
     */
    private fun buildSoapFault(message: String): String {
        val safeMessage = DlnaXmlParser.sanitizeXmlText(message)
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <s:Fault>
      <faultcode>s:Client</faultcode>
      <faultstring>$safeMessage</faultstring>
    </s:Fault>
  </s:Body>
</s:Envelope>"""
    }

    // =========================================================================
    // Parameter extraction helpers
    // =========================================================================

    /**
     * Extract a string parameter from the child elements of [parent].
     *
     * @param parent       The parent element (e.g. `<u:Browse>`).
     * @param paramName    The child element name to find.
     * @param defaultValue Value returned when the child element is absent.
     */
    private fun extractStringParam(
        parent: Element,
        paramName: String,
        defaultValue: String
    ): String {
        val child = findChildElement(parent, paramName)
        val value = child?.textContent?.trim()
        return if (value != null) value else defaultValue
    }

    /**
     * Extract an integer parameter from the child elements of [parent].
     *
     * @param parent       The parent element (e.g. `<u:Browse>`).
     * @param paramName    The child element name to find.
     * @param defaultValue Value returned when the child element is absent or
     *                     its text content is not a valid integer.
     */
    private fun extractIntParam(
        parent: Element,
        paramName: String,
        defaultValue: Int
    ): Int {
        val child = findChildElement(parent, paramName)
        if (child == null) return defaultValue
        return child.textContent?.trim()?.toIntOrNull() ?: defaultValue
    }

    // =========================================================================
    // DOM navigation helpers
    // =========================================================================

    /**
     * Find the first child [Element] of [body] whose local name or tag name
     * matches [actionName].
     *
     * Handles both namespace-aware DOM parsing (where [localName] is set) and
     * non-namespace-aware parsing (where [tagName] may carry a prefix such as
     * `u:Browse`).
     */
    private fun findActionElement(body: Element, actionName: String): Element? {
        var child = body.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                if (elementNameMatches(el, actionName)) return el
            }
            child = child.nextSibling
        }
        return null
    }

    /**
     * Find the first child [Element] of [parent] whose local name or tag name
     * matches [childName].
     */
    private fun findChildElement(parent: Element, childName: String): Element? {
        var child = parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                if (elementNameMatches(el, childName)) return el
            }
            child = child.nextSibling
        }
        return null
    }

    /**
     * Returns `true` if [element]'s local name or tag name equals [name].
     *
     * Covers three cases:
     * 1. Namespace-aware: `localName == "Browse"`
     * 2. No prefix: `tagName == "Browse"`
     * 3. With prefix: `tagName == "u:Browse"` (matches via `endsWith`)
     */
    private fun elementNameMatches(element: Element, name: String): Boolean {
        val localName = element.localName
        if (localName == name) return true
        val tagName = element.tagName
        return tagName == name || tagName.endsWith(":$name")
    }

    // =========================================================================
    // Input validation
    // =========================================================================

    /**
     * Validate an object ID against path traversal attacks.
     *
     * Rejects IDs containing:
     * - `..` (parent directory traversal)
     * - `/` or `\` (path separators)
     * - Null bytes (0x00)
     *
     * @return The trimmed object ID if valid, `null` if the ID is empty or
     *         contains traversal characters.
     */
    private fun validateObjectId(id: String): String? {
        if (id.isBlank()) return null
        if (id.contains("..") || id.contains("/") || id.contains("\\")) {
            Log.w(TAG, "Object ID rejected (contains traversal chars): $id")
            return null
        }
        return id.trim()
    }
}
