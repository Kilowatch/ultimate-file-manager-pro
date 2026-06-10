package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import za.kilowatch.ultimatefilemanager.network.DlnaSsdpEngine
import java.io.InputStream
import java.io.IOException

/**
 * Embedded DLNA Media Server powered by Ktor Netty.
 *
 * Serves UPnP device descriptions, ContentDirectory / ConnectionManager
 * SOAP endpoints, and media file streaming to DLNA control points on the
 * local network.  Discovery is handled by [DlnaSsdpEngine].
 *
 * ## Architecture
 * ```
 * UfmDlnaServer (this)
 *  ├── Ktor Netty embedded server (HTTP)
 *  │   ├── GET  /description.xml      – UPnP device description
 *  │   ├── GET  /ContentDirectory.xml – ContentDirectory:1 SCPD
 *  │   ├── GET  /ConnectionManager.xml – ConnectionManager:1 SCPD
 *  │   ├── POST /cds/control          – ContentDirectory SOAP
 *  │   ├── POST /cms/control          – ConnectionManager SOAP
 *  │   ├── GET  /media/{id}           – Media file streaming (Range)
 *  │   └── HEAD /media/{id}           – Media file headers
 *  └── DlnaSsdpEngine (periodic NOTIFY alive)
 * ```
 *
 * ## Thread safety
 * - The Ktor server is started/stopped from the service thread.
 * - SSDP NOTIFY runs on the main looper (Handler).
 * - Route handlers run on Netty event-loop threads and delegate SOAP
 *   dispatch to thread-safe stateless objects ([DlnaContentDirectory],
 *   [DlnaConnectionManager]).
 * - [DlnaSecurityFilter] provides connection limiting, rate limiting,
 *   and path-validation services that are all thread-safe.
 */
class UfmDlnaServer(private val context: Context) {

    companion object {
        const val TAG = "UfmDlnaServer"
        const val DEFAULT_PORT = 8200

        /** Interval (ms) between periodic SSDP NOTIFY alive messages. */
        private const val NOTIFY_INTERVAL_MS = 1_700_000L

        /** Buffer size (bytes) used for media file streaming. */
        private const val STREAM_BUFFER_SIZE = 65536
    }

    // -----------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------

    private var ktorServer: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    /** Unique device name, generated once per instance. */
    private val udn: String = java.util.UUID.randomUUID().toString()

    private val notifyHandler = Handler(Looper.getMainLooper())

    private val notifyRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            // Periodic keep-alive for all 3 UPnP NT types.
            val nt = "urn:schemas-upnp-org:device:MediaServer:1"
            val ntTypes = listOf(
                "upnp:rootdevice" to "uuid:$udn::upnp:rootdevice",
                "uuid:$udn"       to "uuid:$udn",
                nt                to "uuid:$udn::$nt"
            )
            for ((ntVal, usnVal) in ntTypes) {
                DlnaSsdpEngine.sendNotifyAlive(
                    nt = ntVal,
                    usn = usnVal,
                    location = serverLocation
                )
            }
            notifyHandler.postDelayed(this, NOTIFY_INTERVAL_MS)
        }
    }

    /** The current server location URL used in SSDP notifications. */
    @Volatile
    private var serverLocation: String = ""

    @Volatile
    var isRunning: Boolean = false
        private set

    // ── Start / Stop ──────────────────────────────────────────────────────────

    /**
     * Starts the DLNA Media Server on the given [bindAddress] and the
     * port configured through [DlnaServerPrefs].
     *
     * @param bindAddress The local Wi-Fi IP address to bind to.
     * @return `true` if the server started successfully; `false` on failure
     *         (port conflict, invalid address, etc.).
     */
    fun start(bindAddress: String): Boolean {
        if (isRunning) {
            Log.w(TAG, "DLNA server already running")
            return true
        }

        val port = DlnaServerPrefs.getDlnaServerPort(context)

        return try {
            ktorServer = embeddedServer(Netty, port = port, host = bindAddress) {
                routing {
                    get("/description.xml") {
                        handleDeviceDescription(call)
                    }
                    get("/ContentDirectory.xml") {
                        handleContentDirectoryScpd(call)
                    }
                    get("/ConnectionManager.xml") {
                        handleConnectionManagerScpd(call)
                    }
                    post("/cds/control") {
                        handleCdsControl(call)
                    }
                    post("/cms/control") {
                        handleCmsControl(call)
                    }
                    get("/media/{id}") {
                        handleMediaStream(call)
                    }
                    head("/media/{id}") {
                        handleMediaHead(call)
                    }
                }
            }
            ktorServer?.start(wait = false)

            serverLocation = "http://$bindAddress:$port/description.xml"

            // Initialize media index and trigger background scan
            val folders = DlnaServerPrefs.getSharedFolders(context)
            DlnaMediaIndex.initialize(context, folders)
            DlnaMediaIndex.rescan()
            Log.i(TAG, "Media index scan started for ${folders.size} folder(s)")

            // Initialize SSDP engine (multicast socket, join group, acquire lock)
            val ssdpOk = DlnaSsdpEngine.initialize(context, bindAddress)
            Log.i(TAG, "SSDP engine initialization: ${if (ssdpOk) "OK" else "FAILED"}")

            // UPnP 1.0 §1.2.2: send NOTIFY for all 3 NT types in order:
            //   1. upnp:rootdevice
            //   2. uuid:<UDN>  (device-specific)
            //   3. urn:...device:MediaServer:1  (device type)
            // bypassRateLimit=true ensures all 3 packets are sent during the burst
            // (the rate-limiter would otherwise silently drop bursts 2 and 3).
            val nt = "urn:schemas-upnp-org:device:MediaServer:1"
            val notifyLocation = serverLocation
            val ntTypes = listOf(
                "upnp:rootdevice" to "uuid:$udn::upnp:rootdevice",
                "uuid:$udn"       to "uuid:$udn",
                nt                to "uuid:$udn::$nt"
            )
            for (i in 0 until 3) {
                for ((ntVal, usnVal) in ntTypes) {
                    DlnaSsdpEngine.sendNotifyAlive(
                        nt = ntVal,
                        usn = usnVal,
                        location = notifyLocation,
                        bypassRateLimit = true
                    )
                }
                Log.d(TAG, "Sent SSDP NOTIFY alive burst ${i + 1}/3")
                if (i < 2) Thread.sleep(1000L)
            }

            // Schedule periodic announcements
            notifyHandler.postDelayed(notifyRunnable, NOTIFY_INTERVAL_MS)

            isRunning = true
            Log.i(TAG, "DLNA server started on $bindAddress:$port (SSDP ${if (ssdpOk) "active" else "offline — check Wi-Fi"})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DLNA server on $bindAddress:$port", e)
            ktorServer = null
            isRunning = false
            false
        }
    }

    /**
     * Stops the DLNA Media Server, sends SSDP byebye, cancels the periodic
     * NOTIFY loop, and shuts down the Ktor server gracefully.
     */
    fun stop() {
        if (!isRunning) {
            Log.w(TAG, "DLNA server already stopped")
            return
        }

        // Send SSDP byebye so control points know we are going away
        DlnaSsdpEngine.sendByeBye(
            nt = "urn:schemas-upnp-org:device:MediaServer:1",
            usn = "uuid:$udn::urn:schemas-upnp-org:device:MediaServer:1"
        )

        // Stop the periodic NOTIFY loop
        notifyHandler.removeCallbacks(notifyRunnable)

        // Graceful Ktor shutdown (1000 ms grace, 2000 ms timeout)
        try {
            ktorServer?.stop(1000, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Ktor server", e)
        }
        ktorServer = null
        isRunning = false
        Log.i(TAG, "DLNA server stopped")
    }

    // ── Route Handlers ───────────────────────────────────────────────────────

    /**
     * GET /description.xml — UPnP device description.
     */
    private suspend fun handleDeviceDescription(call: ApplicationCall) {
        if (!acquireConnection(call)) return
        try {
            val serverName = DlnaServerPrefs.getDlnaServerName(context)
            val description = buildDeviceDescription(serverName)
            call.respondText(description, contentType = ContentType.Text.Xml)
        } finally {
            releaseConnection()
        }
    }

    /**
     * GET /ContentDirectory.xml — ContentDirectory:1 SCPD.
     */
    private suspend fun handleContentDirectoryScpd(call: ApplicationCall) {
        if (!acquireConnection(call)) return
        try {
            call.respondText(
                text = CONTENT_DIRECTORY_SCPD,
                contentType = ContentType.Text.Xml
            )
        } finally {
            releaseConnection()
        }
    }

    /**
     * GET /ConnectionManager.xml — ConnectionManager:1 SCPD.
     */
    private suspend fun handleConnectionManagerScpd(call: ApplicationCall) {
        if (!acquireConnection(call)) return
        try {
            call.respondText(
                text = CONNECTION_MANAGER_SCPD,
                contentType = ContentType.Text.Xml
            )
        } finally {
            releaseConnection()
        }
    }

    /**
     * POST /cds/control — ContentDirectory SOAP action dispatch.
     */
    private suspend fun handleCdsControl(call: ApplicationCall) {
        if (!acquireConnection(call)) return
        try {
            val bodyText = call.receiveText()
            val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)

            // Validate request size before parsing
            val headersMap = buildHeadersMap(call)
            if (!DlnaSecurityFilter.validateRequestSize(headersMap, bodyBytes)) {
                call.respondText("Payload Too Large", status = HttpStatusCode(413, "Payload Too Large"))
                return
            }

            val soapActionHeader = call.request.headers["SOAPACTION"] ?: ""
            val sourceIp = call.request.local.remoteHost
            val actionName = extractSoapActionName(soapActionHeader)

            val soapBody = DlnaXmlParser.parseSoapBody(bodyBytes.inputStream())

            val allowedRoots = DlnaServerPrefs.getSharedFolders(context).map { it.uri }

            // Strip "/description.xml" to get the base URL for media streams
            val mediaBaseUrl = serverLocation.removeSuffix("/description.xml")
            val responseXml = DlnaContentDirectory.handleSoapAction(
                actionName = actionName,
                soapBody = soapBody,
                sourceIp = sourceIp,
                allowedRoots = allowedRoots,
                baseUrl = mediaBaseUrl
            )

            call.respondText(responseXml, contentType = ContentType.Text.Xml)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling CDS control action", e)
            call.respondText(
                buildSoapFault("Internal server error"),
                contentType = ContentType.Text.Xml
            )
        } finally {
            releaseConnection()
        }
    }

    /**
     * POST /cms/control — ConnectionManager SOAP action dispatch.
     */
    private suspend fun handleCmsControl(call: ApplicationCall) {
        if (!acquireConnection(call)) return
        try {
            val bodyText = call.receiveText()
            val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)

            val headersMap = buildHeadersMap(call)
            if (!DlnaSecurityFilter.validateRequestSize(headersMap, bodyBytes)) {
                call.respondText("Payload Too Large", status = HttpStatusCode(413, "Payload Too Large"))
                return
            }

            val soapActionHeader = call.request.headers["SOAPACTION"] ?: ""
            val actionName = extractSoapActionName(soapActionHeader)

            val soapBody = DlnaXmlParser.parseSoapBody(bodyBytes.inputStream())

            val responseXml = DlnaConnectionManager.handleSoapAction(actionName, soapBody)

            call.respondText(responseXml, contentType = ContentType.Text.Xml)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling CMS control action", e)
            call.respondText(
                buildSoapFault("Internal server error"),
                contentType = ContentType.Text.Xml
            )
        } finally {
            releaseConnection()
        }
    }

    /**
     * GET /media/{id} — Stream a media file with optional HTTP Range support.
     */
    private suspend fun handleMediaStream(call: ApplicationCall) {
        if (!acquireConnection(call)) return
        try {
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest)
                return
            }

            val mediaItem = DlnaMediaIndex.getItem(id)
            if (mediaItem == null) {
                call.respond(HttpStatusCode.NotFound)
                return
            }

            // Validate the item's path against allowed roots
            val allowedRoots = DlnaServerPrefs.getSharedFolders(context).map { it.uri }
            val validatedPath = DlnaSecurityFilter.validatePath(mediaItem.uri, allowedRoots)
            if (validatedPath == null) {
                call.respond(HttpStatusCode.Forbidden)
                return
            }

            // Rate-limit per source IP
            val sourceIp = call.request.local.remoteHost
            if (!DlnaSecurityFilter.allowRequest(
                    sourceIp, DlnaSecurityFilter.EndpointType.HTTP_STREAM
                )
            ) {
                call.respond(HttpStatusCode.TooManyRequests)
                return
            }

            streamMedia(call, mediaItem)
        } finally {
            releaseConnection()
        }
    }

    /**
     * HEAD /media/{id} — Return media headers without the body.
     */
    private suspend fun handleMediaHead(call: ApplicationCall) {
        if (!acquireConnection(call)) return
        try {
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest)
                return
            }

            val mediaItem = DlnaMediaIndex.getItem(id)
            if (mediaItem == null) {
                call.respond(HttpStatusCode.NotFound)
                return
            }

            val allowedRoots = DlnaServerPrefs.getSharedFolders(context).map { it.uri }
            val validatedPath = DlnaSecurityFilter.validatePath(mediaItem.uri, allowedRoots)
            if (validatedPath == null) {
                call.respond(HttpStatusCode.Forbidden)
                return
            }

            val sourceIp = call.request.local.remoteHost
            if (!DlnaSecurityFilter.allowRequest(
                    sourceIp, DlnaSecurityFilter.EndpointType.HTTP_STREAM
                )
            ) {
                call.respond(HttpStatusCode.TooManyRequests)
                return
            }

            call.response.header(HttpHeaders.ContentType, mediaItem.mimeType)
            call.response.header(HttpHeaders.ContentLength, mediaItem.size)
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            call.respond(HttpStatusCode.OK)
        } finally {
            releaseConnection()
        }
    }

    // ── Media Streaming ──────────────────────────────────────────────────────

    /**
     * Streams [mediaItem] content to the client, handling HTTP Range requests
     * for partial content delivery.
     */
    private suspend fun streamMedia(
        call: ApplicationCall,
        mediaItem: MediaItem
    ) {
        val fileSize = mediaItem.size
        val rangeHeader = call.request.headers[HttpHeaders.Range]
        val range = parseRangeHeader(rangeHeader, fileSize)

        var inputStream: InputStream? = null
        try {
            if (range != null) {
                val (start, end) = range
                val contentLength = end - start + 1

                inputStream = UfmFileSystemBridge.openInputStream(
                    context, mediaItem.uri, start
                )

                // RFC 7233: a successful response to a Range request MUST use 206 Partial Content.
                // Responding with 200 OK causes VLC (and most DLNA clients) to treat the server
                // as not supporting range requests and immediately disconnect.
                call.response.status(HttpStatusCode.PartialContent)
                call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$fileSize")
                call.response.header(HttpHeaders.ContentLength, contentLength)
                call.response.header(HttpHeaders.AcceptRanges, "bytes")

                // Stream the requested byte range in 64KB chunks
                call.respondOutputStream(ContentType.parse(mediaItem.mimeType), HttpStatusCode.PartialContent) {
                    val buf = ByteArray(STREAM_BUFFER_SIZE)
                    var remaining = contentLength
                    inputStream.use { stream ->
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = stream.read(buf, 0, toRead)
                            if (n == -1) break
                            // WriterScope.write only accepts ByteArray (no offset)
                            write(if (n == buf.size) buf else buf.copyOf(n))
                            remaining -= n
                        }
                    }
                }
            } else {
                inputStream = UfmFileSystemBridge.openInputStream(
                    context, mediaItem.uri, 0
                )

                // Stream entire file in 64KB chunks
                call.response.header(HttpHeaders.ContentLength, fileSize)
                call.respondOutputStream(ContentType.parse(mediaItem.mimeType)) {
                    val buf = ByteArray(STREAM_BUFFER_SIZE)
                    inputStream.use { stream ->
                        var n: Int
                        while (stream.read(buf).also { n = it } != -1) {
                            write(if (n == buf.size) buf else buf.copyOf(n))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // Client disconnect is expected — log at debug level
            Log.d(TAG, "Client disconnected during media stream: ${mediaItem.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming media: ${mediaItem.id}", e)
        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing input stream for ${mediaItem.id}", e)
            }
        }
    }

    // ── Security Helpers ────────────────────────────────────────────────────

    /**
     * Acquires a connection slot and validates the request URI length.
     *
     * @return `true` if the request should proceed, `false` if the response
     *         has already been sent (503 Service Unavailable or 414 URI Too
     *         Long).
     */
    private suspend fun acquireConnection(call: ApplicationCall): Boolean {
        if (!DlnaSecurityFilter.tryAcquireConnection()) {
            call.respond(HttpStatusCode.ServiceUnavailable)
            return false
        }
        if (!DlnaSecurityFilter.validateUriLength(call.request.uri)) {
            DlnaSecurityFilter.releaseConnection()
            call.respondText("URI Too Long", status = HttpStatusCode(414, "URI Too Long"))
            return false
        }
        return true
    }

    /** Releases a connection slot acquired via [acquireConnection]. */
    private fun releaseConnection() {
        DlnaSecurityFilter.releaseConnection()
    }

    /**
     * Converts the Ktor request headers into a flat [Map] for the security
     * size validator.  When a header has multiple values only the first is
     * included.
     */
    private fun buildHeadersMap(call: ApplicationCall): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (name in call.request.headers.names()) {
            map[name] = call.request.headers[name] ?: ""
        }
        return map
    }

    // ── SOAP Helpers ────────────────────────────────────────────────────────

    /**
     * Extracts the action name from a SOAPACTION header value.
     *
     * Input:  `"urn:schemas-upnp-org:service:ContentDirectory:1#Browse"`
     * Output: `"Browse"`
     */
    private fun extractSoapActionName(soapAction: String): String {
        val cleaned = soapAction.trim().trim('"')
        val hashIndex = cleaned.lastIndexOf('#')
        return if (hashIndex >= 0) {
            cleaned.substring(hashIndex + 1)
        } else {
            cleaned
        }
    }

    /**
     * Builds a minimal SOAP 1.1 fault envelope for internal error responses.
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

    // ── Range Header Parser ─────────────────────────────────────────────────

    /**
     * Parses an HTTP Range header and returns the (start, end) byte range,
     * or `null` if the header is absent or cannot be parsed.
     *
     * Supported formats:
     * - `bytes=0-499`   explicit range
     * - `bytes=500-`    open-ended (from 500 to end of file)
     * - `bytes=-500`    suffix (last 500 bytes)
     */
    private fun parseRangeHeader(
        rangeHeader: String?,
        fileSize: Long
    ): Pair<Long, Long>? {
        if (rangeHeader == null) return null
        if (!rangeHeader.startsWith("bytes=", ignoreCase = true)) return null

        val range = rangeHeader.substringAfter("bytes=").trim()
        val parts = range.split("-")
        if (parts.size != 2) return null

        val startStr = parts[0].trim()
        val endStr = parts[1].trim()

        return when {
            // Suffix range: "-500" → last 500 bytes
            startStr.isEmpty() && endStr.isNotEmpty() -> {
                val suffixLength = endStr.toLongOrNull() ?: return null
                if (suffixLength <= 0) return null
                val start = maxOf(0L, fileSize - suffixLength)
                Pair(start, fileSize - 1)
            }
            // Explicit or open-ended range: "0-499" or "500-"
            startStr.isNotEmpty() -> {
                val start = startStr.toLongOrNull() ?: return null
                if (start < 0 || start >= fileSize) return null
                val end = if (endStr.isNotEmpty()) {
                    endStr.toLongOrNull() ?: return null
                } else {
                    fileSize - 1
                }
                if (end < start) return null
                Pair(start, minOf(end, fileSize - 1))
            }
            else -> null
        }
    }

    // ── Device Description XML ──────────────────────────────────────────────

    /**
     * Builds a complete UPnP device description XML string for a MediaServer:1
     * device.
     */
    private fun buildDeviceDescription(friendlyName: String): String {
        val safeName = DlnaXmlParser.sanitizeXmlText(friendlyName)
        return """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
    <friendlyName>$safeName</friendlyName>
    <manufacturer>UFM</manufacturer>
    <manufacturerURL></manufacturerURL>
    <modelName>UFM Media Server</modelName>
    <UDN>uuid:$udn</UDN>
    <iconList>
      <icon>
        <mimetype>image/png</mimetype>
        <width>48</width>
        <height>48</height>
        <depth>24</depth>
        <url>/icon.png</url>
      </icon>
    </iconList>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
        <SCPDURL>/ContentDirectory.xml</SCPDURL>
        <controlURL>/cds/control</controlURL>
        <eventSubURL>/cds/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/ConnectionManager.xml</SCPDURL>
        <controlURL>/cms/control</controlURL>
        <eventSubURL>/cms/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""
    }

    // ── Static SCPD Documents ───────────────────────────────────────────────

    /**
     * Complete ContentDirectory:1 SCPD XML document describing the Browse,
     * Search, GetSearchCapabilities, GetSortCapabilities, and
     * GetSystemUpdateID actions along with their state variables.
     */
    private val CONTENT_DIRECTORY_SCPD: String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<scpd xmlns="urn:schemas-upnp-org:service-1-0">""")
        appendLine("""  <specVersion>""")
        appendLine("""    <major>1</major>""")
        appendLine("""    <minor>0</minor>""")
        appendLine("""  </specVersion>""")
        appendLine("""  <actionList>""")

        // --- Browse ---
        appendLine("""    <action>""")
        appendLine("""      <name>Browse</name>""")
        appendLine("""      <argumentList>""")
        appendLine("""        <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>""")
        appendLine("""      </argumentList>""")
        appendLine("""    </action>""")

        // --- Search ---
        appendLine("""    <action>""")
        appendLine("""      <name>Search</name>""")
        appendLine("""      <argumentList>""")
        appendLine("""        <argument><name>ContainerID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>SearchCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SearchCriteria</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>""")
        appendLine("""      </argumentList>""")
        appendLine("""    </action>""")

        // --- GetSearchCapabilities ---
        appendLine("""    <action>""")
        appendLine("""      <name>GetSearchCapabilities</name>""")
        appendLine("""      <argumentList>""")
        appendLine("""        <argument><name>SearchCaps</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_SearchCapabilities</relatedStateVariable></argument>""")
        appendLine("""      </argumentList>""")
        appendLine("""    </action>""")

        // --- GetSortCapabilities ---
        appendLine("""    <action>""")
        appendLine("""      <name>GetSortCapabilities</name>""")
        appendLine("""      <argumentList>""")
        appendLine("""        <argument><name>SortCaps</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_SortCapabilities</relatedStateVariable></argument>""")
        appendLine("""      </argumentList>""")
        appendLine("""    </action>""")

        // --- GetSystemUpdateID ---
        appendLine("""    <action>""")
        appendLine("""      <name>GetSystemUpdateID</name>""")
        appendLine("""      <argumentList>""")
        appendLine("""        <argument><name>Id</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument>""")
        appendLine("""      </argumentList>""")
        appendLine("""    </action>""")

        appendLine("""  </actionList>""")
        appendLine("""  <serviceStateTable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType><allowedValueList><allowedValue>BrowseMetadata</allowedValue><allowedValue>BrowseDirectChildren</allowedValue></allowedValueList></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SearchCriteria</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SearchCapabilities</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCapabilities</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="yes"><name>SystemUpdateID</name><dataType>ui4</dataType></stateVariable>""")
        appendLine("""  </serviceStateTable>""")
        append("""</scpd>""")
    }

    /**
     * Complete ConnectionManager:1 SCPD XML document describing the
     * GetProtocolInfo, GetCurrentConnectionIDs, GetCurrentConnectionInfo,
     * and PrepareForConnection actions along with their state variables.
     */
    private val CONNECTION_MANAGER_SCPD: String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<scpd xmlns="urn:schemas-upnp-org:service-1-0">""")
        appendLine("""  <specVersion>""")
        appendLine("""    <major>1</major>""")
        appendLine("""    <minor>0</minor>""")
        appendLine("""  </specVersion>""")
        appendLine("""  <actionList>""")

        // --- GetProtocolInfo ---
        appendLine("""    <action>""")
        appendLine("""      <name>GetProtocolInfo</name>""")
        appendLine("""      <argumentList>""")
        appendLine("""        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>""")
        appendLine("""      </argumentList>""")
        appendLine("""    </action>""")

        // --- GetCurrentConnectionIDs ---
        appendLine("""    <action>""")
        appendLine("""      <name>GetCurrentConnectionIDs</name>""")
        appendLine("""      <argumentList>""")
        appendLine("""        <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>""")
        appendLine("""      </argumentList>""")
        appendLine("""    </action>""")

        // --- GetCurrentConnectionInfo ---
        appendLine("""    <action>""")
        appendLine("""      <name>GetCurrentConnectionInfo</name>""")
        appendLine("""      <argumentList>""")
        appendLine("""        <argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Status</relatedStateVariable></argument>""")
        appendLine("""      </argumentList>""")
        appendLine("""    </action>""")

        // --- PrepareForConnection ---
        appendLine("""    <action>""")
        appendLine("""      <name>PrepareForConnection</name>""")
        appendLine("""      <argumentList>""")
        appendLine("""        <argument><name>RemoteProtocolInfo</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>PeerConnectionManager</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>PeerConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>Direction</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>ConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>""")
        appendLine("""        <argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>""")
        appendLine("""      </argumentList>""")
        appendLine("""    </action>""")

        appendLine("""  </actionList>""")
        appendLine("""  <serviceStateTable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="yes"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionID</name><dataType>ui4</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType><allowedValueList><allowedValue>Input</allowedValue><allowedValue>Output</allowedValue></allowedValueList></stateVariable>""")
        appendLine("""    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Status</name><dataType>string</dataType><allowedValueList><allowedValue>OK</allowedValue><allowedValue>ContentFormatMismatch</allowedValue><allowedValue>InsufficientBandwidth</allowedValue><allowedValue>UnreliableChannel</allowedValue><allowedValue>Unknown</allowedValue></allowedValueList></stateVariable>""")
        appendLine("""  </serviceStateTable>""")
        append("""</scpd>""")
    }
}
