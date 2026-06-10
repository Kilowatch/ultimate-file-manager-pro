package za.kilowatch.ultimatefilemanager.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.w3c.dom.Element
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.DlnaSsdpEngine
import za.kilowatch.ultimatefilemanager.viewer.UFMPlayerActivity
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DLNA Media Renderer server.
 *
 * Advertises UFM as a UPnP AV play target on the local network, handles
 * AVTransport:1 and RenderingControl:1 SOAP commands from DLNA control
 * points such as BubbleUPnP, VLC, or Windows Media Player.
 *
 * When a control point pushes a media URL via SetAVTransportURI, the
 * renderer launches [UFMPlayerActivity] with the URL as the playback
 * source, and notifies the user with a system notification.
 *
 * Security (SR-03):
 * - All incoming URLs are validated through [DlnaSecurityFilter.validateUrl]
 *   to reject public-Internet and loopback addresses.
 * - SetAVTransportURI is rate-limited per source IP (2 s debounce,
 *   5 changes per minute).
 * - Volume is capped at the Android system maximum.
 * - All XML text values are sanitized through [DlnaXmlParser.sanitizeXmlText].
 * - SOAP bodies are parsed through the hardened [DlnaXmlParser.parseSoapBody]
 *   parser (XXE-defended, DOCTYPE-disallowed).
 */
class DlnaRendererServer(private val context: Context) {

    companion object {
        const val TAG = "DlnaRendererServer"

        /** Default HTTP port for the Ktor embedded server. */
        const val DEFAULT_PORT = 8201

        /** UPnP service type URNs. */
        private const val AV_TRANSPORT_SERVICE =
            "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RENDERING_CONTROL_SERVICE =
            "urn:schemas-upnp-org:service:RenderingControl:1"

        /** UPnP device type. */
        private const val DEVICE_TYPE =
            "urn:schemas-upnp-org:device:MediaRenderer:1"

        /** SOAP 1.1 namespace constants. */
        private const val NS_SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val SOAP_ENCODING = "http://schemas.xmlsoap.org/soap/encoding/"
        private const val NS_UPNP_CONTROL = "urn:schemas-upnp-org:control-1-0"

        /** Rate limiting constants for SetAVTransportURI. */
        private const val DEBOUNCE_MS = 2000L
        private const val MAX_URI_CHANGES_PER_MIN = 5
        private const val RATE_WINDOW_MS = 60_000L

        /** SSDP NOTIFY alive interval in milliseconds (1700 seconds). */
        private const val NOTIFY_INTERVAL_MS = 1_700_000L

        /** Timeout after which a pending push is considered expired. */
        private const val PENDING_PUSH_TIMEOUT_MS = 30_000L

        /** Notification channel for playback requests. */
        private const val NOTIFICATION_CHANNEL_ID = "dlna_renderer_playback"
        private const val NOTIFICATION_ID = 1001
    }

    // -----------------------------------------------------------------
    // Server instance
    // -----------------------------------------------------------------

    private var ktorServer: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var udn: String = UUID.randomUUID().toString()

    @Volatile
    var isRunning: Boolean = false
        private set

    // -----------------------------------------------------------------
    // Playback state machine
    // -----------------------------------------------------------------

    @Volatile
    private var transportState: TransportState = TransportState.STOPPED

    @Volatile
    private var currentUri: String? = null

    @Volatile
    private var currentTitle: String = ""

    @Volatile
    private var currentDurationMs: Long = 0L

    @Volatile
    private var currentPositionMs: Long = 0L

    @Volatile
    private var currentTrackMetaData: String = ""

    /** Track duration string in DLNA format (H:MM:SS). */
    @Volatile
    private var currentDurationStr: String = "00:00:00"

    /** Track position string in DLNA format (H:MM:SS). */
    @Volatile
    private var currentPositionStr: String = "00:00:00"

    // -----------------------------------------------------------------
    // Pending push tracking
    // -----------------------------------------------------------------

    /**
     * True when a SetAVTransportURI has been processed and the resulting
     * notification / activity launch has not yet been resolved.
     */
    @Volatile
    private var hasPendingPush = false

    /**
     * Timestamp (System.currentTimeMillis) of the last pending push,
     * used to expire stale pending states.
     */
    @Volatile
    private var pendingPushTimestamp: Long = 0L

    // -----------------------------------------------------------------
    // Rate limiting (per source IP)
    // -----------------------------------------------------------------

    /**
     * Maps source IP to a list of timestamps (epoch millis) of recent
     * SetAVTransportURI calls. Used for both debounce and per-minute limits.
     */
    private val uriChangeHistory = ConcurrentHashMap<String, MutableList<Long>>()

    // -----------------------------------------------------------------
    // Periodic SSDP NOTIFY alive
    // -----------------------------------------------------------------

    private val notifyHandler = Handler(Looper.getMainLooper())
    private var notifyRunnable: Runnable? = null
    private var serverLocation: String = ""

    // ==================================================================
    // Public API: start / stop
    // ==================================================================

    /**
     * Starts the DLNA Media Renderer server.
     *
     * @param bindAddress The local Wi-Fi IP address to bind the HTTP server to.
     * @return `true` if the server started successfully, `false` on failure.
     */
    fun start(bindAddress: String): Boolean {
        if (isRunning) {
            Log.w(TAG, "Renderer server already running")
            return true
        }

        if (bindAddress.isBlank()) {
            Log.e(TAG, "Cannot start renderer: bindAddress is empty")
            return false
        }

        val port = DEFAULT_PORT
        serverLocation = "http://$bindAddress:$port"

        try {
            // Build the Ktor Netty embedded server
            ktorServer = embeddedServer(Netty, port = port, host = bindAddress) {
                routing {
                    // Device description
                    get("/description.xml") {
                        val descXml = getDeviceDescriptionXml(serverLocation)
                        call.respondText(descXml, ContentType.Text.Xml)
                    }

                    // Service control protocol descriptions
                    get("/AVTransport.xml") {
                        call.respondText(
                            getAvTransportScpdXml(),
                            ContentType.Text.Xml
                        )
                    }

                    get("/RenderingControl.xml") {
                        call.respondText(
                            getRenderingControlScpdXml(),
                            ContentType.Text.Xml
                        )
                    }

                    // SOAP control endpoints
                    get("/ConnectionManager.xml") {
                        call.respondText(getConnectionManagerScpdXml(), ContentType.Text.Xml)
                    }
                    post("/cms/control") { handleConnectionManagerSoap(call) }
                    post("/avt/control") { handleAvTransportSoap(call) }
                    post("/rrc/control") { handleRenderingControlSoap(call) }
                }
            }

            ktorServer?.start(wait = false)

            // Bug C fix: poll the actual bindAddress — Ktor is bound to a specific IP
            // (not 0.0.0.0), so a loopback connection to 127.0.0.1 will always be
            // refused. We must probe the same IP that Ktor is listening on.
            val readyDeadline = System.currentTimeMillis() + 2_000L
            var serverReady = false
            while (System.currentTimeMillis() < readyDeadline) {
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(bindAddress, port), 200)
                    }
                    serverReady = true
                    break
                } catch (_: Exception) {
                    Thread.sleep(100)
                }
            }
            if (!serverReady) {
                Log.w(TAG, "Ktor server did not become ready within 2 s on port $port")
            }
            Log.i(TAG, "Renderer HTTP server started on $serverLocation (ready=$serverReady)")

            // Bug 5 fix: if the SSDP engine fails to initialize (e.g. port 1900 is
            // already taken by another app), the renderer cannot be discovered at all.
            // Surface the error clearly and abort startup rather than running silently
            // invisible on the network.
            val ssdpOk = DlnaSsdpEngine.initialize(context, bindAddress)
            Log.i(TAG, "SSDP engine initialization: ${if (ssdpOk) "OK" else "FAILED"}")
            if (!ssdpOk) {
                Log.e(TAG, "Aborting renderer start — SSDP engine could not bind port 1900. " +
                    "Another app may be occupying the port.")
                ktorServer?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
                ktorServer = null
                isRunning = false
                return false
            }

            // UPnP 1.0 §1.2.2: send NOTIFY for all 3 NT types in order:
            //   1. upnp:rootdevice
            //   2. uuid:<UDN>  (device-specific)
            //   3. urn:...device:MediaRenderer:1  (device type)
            // bypassRateLimit=true ensures all 3 packets are sent during the burst
            // (the rate-limiter would otherwise silently drop bursts 2 and 3).
            val location = "$serverLocation/description.xml"
            val serverHeader = "UFM DLNA Renderer/1.0"

            val ntTypes = listOf(
                "upnp:rootdevice" to "uuid:$udn::upnp:rootdevice",
                "uuid:$udn"       to "uuid:$udn",
                DEVICE_TYPE       to "uuid:$udn::$DEVICE_TYPE"
            )

            // Send initial NOTIFY alive burst: 3 messages per NT type, 1 second apart
            for (i in 1..3) {
                for ((nt, usn) in ntTypes) {
                    DlnaSsdpEngine.sendNotifyAlive(
                        nt = nt,
                        usn = usn,
                        location = location,
                        server = serverHeader,
                        bypassRateLimit = true
                    )
                }
                if (i < 3) Thread.sleep(1000L)
            }

            // Start periodic NOTIFY alive every 1700s (for all 3 NT types).
            // The runnable captures location / serverHeader / ntTypes declared above.
            val runnable = object : Runnable {
                override fun run() {
                    if (isRunning) {
                        for ((nt, usn) in ntTypes) {
                            DlnaSsdpEngine.sendNotifyAlive(
                                nt = nt,
                                usn = usn,
                                location = location,
                                server = serverHeader
                            )
                        }
                        notifyHandler.postDelayed(this, NOTIFY_INTERVAL_MS)
                    }
                }
            }
            notifyRunnable = runnable
            notifyHandler.postDelayed(runnable, NOTIFY_INTERVAL_MS)

            isRunning = true
            Log.i(TAG, "DLNA Media Renderer started (UDN: $udn)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DLNA Media Renderer", e)
            // Clean up partial state
            ktorServer?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
            ktorServer = null
            notifyRunnable?.let { notifyHandler.removeCallbacks(it) }
            notifyRunnable = null
            isRunning = false
            return false
        }
    }

    /**
     * Stops the DLNA Media Renderer server and sends SSDP byebye.
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false

        // Cancel periodic NOTIFY
        notifyRunnable?.let { notifyHandler.removeCallbacks(it) }
        notifyRunnable = null

        try {
            // Send SSDP byebye for all 3 NT types
            val ntTypes = listOf(
                "upnp:rootdevice" to "uuid:$udn::upnp:rootdevice",
                "uuid:$udn"       to "uuid:$udn",
                DEVICE_TYPE       to "uuid:$udn::$DEVICE_TYPE"
            )
            for ((nt, usn) in ntTypes) {
                DlnaSsdpEngine.sendByeBye(nt, usn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error sending SSDP byebye", e)
        }

        // Shut down Ktor server
        try {
            ktorServer?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Ktor server", e)
        }
        ktorServer = null

        // Reset playback state
        transportState = TransportState.STOPPED
        currentUri = null
        currentTitle = ""
        currentDurationMs = 0L
        currentPositionMs = 0L
        currentDurationStr = "00:00:00"
        currentPositionStr = "00:00:00"
        currentTrackMetaData = ""
        hasPendingPush = false
        pendingPushTimestamp = 0L
        uriChangeHistory.clear()

        Log.i(TAG, "DLNA Media Renderer stopped")
    }

    // ==================================================================
    // SOAP Handlers
    // ==================================================================

    /**
     * Handles a POST to /avt/control (AVTransport:1 SOAP actions).
     */
    private suspend fun handleAvTransportSoap(call: ApplicationCall) {
        val soapAction = call.request.headers["SOAPACTION"] ?: ""
        val actionName = soapAction.substringAfter("#").trim('"', ' ')

        if (actionName.isBlank()) {
            call.respondText(
                buildSoapFault(401, "Missing SOAPACTION header"),
                ContentType.Text.Xml,
                HttpStatusCode.InternalServerError
            )
            return
        }

        val rawBody: String = try {
            call.receiveText()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read SOAP body for $actionName", e)
            call.respondText(
                buildSoapFault(402, "Invalid SOAP body"),
                ContentType.Text.Xml,
                HttpStatusCode.InternalServerError
            )
            return
        }

        val response = handleAvTransportAction(actionName, rawBody)
        call.respondText(response, ContentType.Text.Xml)
    }

    /**
     * Handles a POST to /rrc/control (RenderingControl:1 SOAP actions).
     */
    private suspend fun handleRenderingControlSoap(call: ApplicationCall) {
        val soapAction = call.request.headers["SOAPACTION"] ?: ""
        val actionName = soapAction.substringAfter("#").trim('"', ' ')

        if (actionName.isBlank()) {
            call.respondText(
                buildSoapFault(401, "Missing SOAPACTION header"),
                ContentType.Text.Xml,
                HttpStatusCode.InternalServerError
            )
            return
        }

        val rawBody: String = try {
            call.receiveText()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read SOAP body for $actionName", e)
            call.respondText(
                buildSoapFault(402, "Invalid SOAP body"),
                ContentType.Text.Xml,
                HttpStatusCode.InternalServerError
            )
            return
        }

        val response = handleRenderingControlAction(actionName, rawBody)
        call.respondText(response, ContentType.Text.Xml)
    }

    // ==================================================================
    // AVTransport:1 Action Dispatch
    // ==================================================================

    /**
     * Dispatches an AVTransport:1 SOAP action and returns the response XML.
     */
    private fun handleAvTransportAction(actionName: String, rawBody: String): String {
        Log.d(TAG, "AVTransport action: $actionName")

        return try {
            val soapBody = parseSoapBodyElement(rawBody)
            val sourceIp = extractSourceIpFromSoapBody(rawBody)

            when (actionName) {
                "SetAVTransportURI" -> {
                    handleSetAVTransportURI(soapBody, sourceIp)
                }
                "Play" -> {
                    transportState = TransportState.PLAYING
                    buildSoapResponse(
                        AV_TRANSPORT_SERVICE,
                        "Play",
                        ""
                    )
                }
                "Pause" -> {
                    transportState = TransportState.PAUSED_PLAYBACK
                    buildSoapResponse(
                        AV_TRANSPORT_SERVICE,
                        "Pause",
                        ""
                    )
                }
                "Stop" -> {
                    transportState = TransportState.STOPPED
                    currentUri = null
                    currentTitle = ""
                    currentPositionMs = 0L
                    currentPositionStr = "00:00:00"
                    buildSoapResponse(
                        AV_TRANSPORT_SERVICE,
                        "Stop",
                        ""
                    )
                }
                "Seek" -> {
                    handleSeek(soapBody)
                }
                "GetTransportInfo" -> {
                    buildSoapResponse(
                        AV_TRANSPORT_SERVICE,
                        "GetTransportInfo",
                        buildGetTransportInfoBody()
                    )
                }
                "GetPositionInfo" -> {
                    buildSoapResponse(
                        AV_TRANSPORT_SERVICE,
                        "GetPositionInfo",
                        buildGetPositionInfoBody()
                    )
                }
                "GetMediaInfo" -> {
                    buildSoapResponse(
                        AV_TRANSPORT_SERVICE,
                        "GetMediaInfo",
                        buildGetMediaInfoBody()
                    )
                }
                else -> {
                    Log.w(TAG, "Unsupported AVTransport action: $actionName")
                    buildSoapFault(401, "Unsupported action: $actionName")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security error handling AVTransport action $actionName", e)
            buildSoapFault(702, e.message ?: "Security violation")
        } catch (e: Exception) {
            Log.w(TAG, "Error handling AVTransport action $actionName", e)
            buildSoapFault(501, "Action failed: ${e.message}")
        }
    }

    // ==================================================================
    // AVTransport:1 Action Implementations
    // ==================================================================

    /**
     * Handles SetAVTransportURI with security validation and rate limiting.
     */
    private fun handleSetAVTransportURI(
        soapBody: Element,
        sourceIp: String
    ): String {
        // Extract parameters
        val instanceId = getTagText(soapBody, "InstanceID") ?: "0"
        val currentUriRaw = getTagText(soapBody, "CurrentURI")
        val currentUriMetaData = getTagText(soapBody, "CurrentURIMetaData") ?: ""

        if (currentUriRaw.isNullOrBlank()) {
            return buildSoapFault(402, "CurrentURI is required")
        }

        val validatedUrl = currentUriRaw.trim()

        // Validate URL is LAN-only
        if (!DlnaSecurityFilter.validateUrl(validatedUrl)) {
            Log.w(TAG, "SetAVTransportURI blocked — URL not in local network: $validatedUrl")
            return buildSoapFault(702, "URL blocked: outside local network")
        }

        // Check for pending push timeout
        if (hasPendingPush) {
            val elapsed = System.currentTimeMillis() - pendingPushTimestamp
            if (elapsed < PENDING_PUSH_TIMEOUT_MS) {
                Log.w(TAG, "SetAVTransportURI denied — pending push not yet confirmed from $sourceIp")
                return buildSoapFault(701, "Another playback request is pending confirmation")
            } else {
                // Pending push expired, reset
                hasPendingPush = false
                pendingPushTimestamp = 0L
            }
        }

        // Rate limit: debounce + max 5/min per source IP
        if (isRateLimited(sourceIp)) {
            Log.w(TAG, "SetAVTransportURI rate-limited for $sourceIp")
            return buildSoapFault(701, "Request rate-limited. Please wait before sending another.")
        }

        // Sanitize metadata
        val safeMetaData = DlnaXmlParser.sanitizeXmlText(currentUriMetaData)

        // Extract a title from the metadata if possible, otherwise use the URL
        val title = extractTitleFromMetaData(currentUriMetaData)
            ?: extractTitleFromUrl(validatedUrl)

        // Update state
        currentUri = validatedUrl
        currentTitle = title
        currentTrackMetaData = safeMetaData
        transportState = TransportState.STOPPED
        currentPositionMs = 0L
        currentPositionStr = "00:00:00"

        // Record the rate-limit entry
        recordUriChange(sourceIp)

        // Mark pending push
        hasPendingPush = true
        pendingPushTimestamp = System.currentTimeMillis()

        // Launch UFMPlayerActivity
        launchPlayerActivity(validatedUrl, title)

        // Show notification for the unsolicited push
        showPlaybackRequestNotification(sourceIp, title, validatedUrl)

        Log.i(TAG, "SetAVTransportURI: $validatedUrl (title=$title) from $sourceIp")

        return buildSoapResponse(
            AV_TRANSPORT_SERVICE,
            "SetAVTransportURI",
            ""
        )
    }

    /**
     * Handles the Seek action.
     */
    private fun handleSeek(soapBody: Element): String {
        val seekMode = getTagText(soapBody, "Unit") ?: getTagText(soapBody, "SeekMode") ?: ""
        val target = getTagText(soapBody, "Target") ?: ""

        if (target.isBlank()) {
            return buildSoapFault(402, "Seek target is required")
        }

        when (seekMode.uppercase()) {
            "ABS_TIME", "REL_TIME" -> {
                // Parse H:MM:SS or HH:MM:SS format
                val posMs = parseDurationToMs(target)
                if (posMs >= 0) {
                    currentPositionMs = posMs
                    currentPositionStr = target
                } else {
                    return buildSoapFault(701, "Invalid seek target format: $target")
                }
            }
            "TRACK_NR" -> {
                // Track number seek — not implemented
                Log.d(TAG, "Seek mode TRACK_NR ignored")
            }
            else -> {
                Log.w(TAG, "Unsupported seek mode: $seekMode")
                return buildSoapFault(701, "Unsupported seek mode: $seekMode")
            }
        }

        return buildSoapResponse(
            AV_TRANSPORT_SERVICE,
            "Seek",
            ""
        )
    }

    // ==================================================================
    // AVTransport:1 Response Body Builders
    // ==================================================================

    /**
     * Builds the response body XML for GetTransportInfo.
     */
    private fun buildGetTransportInfoBody(): String {
        val state = DlnaXmlParser.sanitizeXmlText(transportState.name)
        return """
            <CurrentTransportState>$state</CurrentTransportState>
            <CurrentTransportStatus>OK</CurrentTransportStatus>
            <CurrentSpeed>1</CurrentSpeed>
        """.trimIndent()
    }

    /**
     * Builds the response body XML for GetPositionInfo.
     */
    private fun buildGetPositionInfoBody(): String {
        val track = if (currentUri != null) "1" else "0"
        val trackDuration = DlnaXmlParser.sanitizeXmlText(currentDurationStr)
        val relTime = DlnaXmlParser.sanitizeXmlText(currentPositionStr)
        val absTime = DlnaXmlParser.sanitizeXmlText(currentPositionStr)
        val trackUri = DlnaXmlParser.sanitizeXmlText(currentUri ?: "")
        val metaData = DlnaXmlParser.sanitizeXmlText(currentTrackMetaData)
        val title = DlnaXmlParser.sanitizeXmlText(currentTitle)

        return """
            <Track>$track</Track>
            <TrackDuration>$trackDuration</TrackDuration>
            <TrackMetaData>$metaData</TrackMetaData>
            <TrackURI>$trackUri</TrackURI>
            <RelTime>$relTime</RelTime>
            <AbsTime>$absTime</AbsTime>
            <RelCount>0</RelCount>
            <AbsCount>0</AbsCount>
        """.trimIndent()
    }

    /**
     * Builds the response body XML for GetMediaInfo.
     */
    private fun buildGetMediaInfoBody(): String {
        val nrTracks = if (currentUri != null) "1" else "0"
        val mediaDuration = DlnaXmlParser.sanitizeXmlText(currentDurationStr)
        val currentUriStr = DlnaXmlParser.sanitizeXmlText(currentUri ?: "")
        val metaData = DlnaXmlParser.sanitizeXmlText(currentTrackMetaData)

        return """
            <NrTracks>$nrTracks</NrTracks>
            <MediaDuration>$mediaDuration</MediaDuration>
            <CurrentURI>$currentUriStr</CurrentURI>
            <CurrentURIMetaData>$metaData</CurrentURIMetaData>
            <NextURI></NextURI>
            <NextURIMetaData></NextURIMetaData>
            <PlayMedium>NETWORK</PlayMedium>
            <RecordMedium>NOT_IMPLEMENTED</RecordMedium>
            <WriteStatus>NOT_IMPLEMENTED</WriteStatus>
        """.trimIndent()
    }

    // ==================================================================
    // RenderingControl:1 Action Dispatch
    // ==================================================================

    /**
     * Dispatches a RenderingControl:1 SOAP action and returns the response XML.
     */
    private fun handleRenderingControlAction(actionName: String, rawBody: String): String {
        Log.d(TAG, "RenderingControl action: $actionName")

        return try {
            val soapBody = parseSoapBodyElement(rawBody)

            when (actionName) {
                "SetVolume" -> handleSetVolume(soapBody)
                "GetVolume" -> handleGetVolume(soapBody)
                "SetMute" -> handleSetMute(soapBody)
                "GetMute" -> handleGetMute(soapBody)
                else -> {
                    Log.w(TAG, "Unsupported RenderingControl action: $actionName")
                    buildSoapFault(401, "Unsupported action: $actionName")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security error handling RenderingControl action $actionName", e)
            buildSoapFault(702, e.message ?: "Security violation")
        } catch (e: Exception) {
            Log.w(TAG, "Error handling RenderingControl action $actionName", e)
            buildSoapFault(501, "Action failed: ${e.message}")
        }
    }

    // ==================================================================
    // RenderingControl:1 Action Implementations
    // ==================================================================

    /**
     * Handles SetVolume — sets the Android system media volume.
     */
    private fun handleSetVolume(soapBody: Element): String {
        val instanceId = getTagText(soapBody, "InstanceID") ?: "0"
        val channel = getTagText(soapBody, "Channel") ?: "Master"
        val desiredVolumeStr = getTagText(soapBody, "DesiredVolume") ?: return buildSoapFault(
            402,
            "DesiredVolume is required"
        )

        val desiredVolume = desiredVolumeStr.toIntOrNull()
        if (desiredVolume == null || desiredVolume < 0) {
            return buildSoapFault(402, "Invalid DesiredVolume value: $desiredVolumeStr")
        }

        // Cap at Android system max volume (100)
        val cappedVolume = desiredVolume.coerceIn(0, 100)

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            // Scale 0-100 to Android's internal volume range
            val scaledVolume = (cappedVolume * maxVol) / 100
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                scaledVolume,
                0 // flags = 0 means no UI shown
            )
            Log.d(TAG, "SetVolume: $desiredVolume (capped=$cappedVolume, scaled=$scaledVolume)")
        } catch (e: SecurityException) {
            Log.w(TAG, "SetVolume denied by system policy", e)
            return buildSoapFault(501, "Volume control not permitted")
        }

        return buildSoapResponse(
            RENDERING_CONTROL_SERVICE,
            "SetVolume",
            ""
        )
    }

    /**
     * Handles GetVolume — returns the current system media volume (0-100).
     */
    private fun handleGetVolume(soapBody: Element): String {
        val channel = getTagText(soapBody, "Channel") ?: "Master"

        val volume: Int = try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (maxVol > 0) {
                (currentVol * 100) / maxVol
            } else {
                0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read system volume", e)
            0
        }

        val safeChannel = DlnaXmlParser.sanitizeXmlText(channel)

        return buildSoapResponse(
            RENDERING_CONTROL_SERVICE,
            "GetVolume",
            """
                <CurrentVolume>$volume</CurrentVolume>
                <Channel>$safeChannel</Channel>
            """.trimIndent()
        )
    }

    /**
     * Handles SetMute — sets system media mute state.
     */
    private fun handleSetMute(soapBody: Element): String {
        val channel = getTagText(soapBody, "Channel") ?: "Master"
        val desiredMuteStr = getTagText(soapBody, "DesiredMute") ?: return buildSoapFault(
            402,
            "DesiredMute is required"
        )

        val mute = desiredMuteStr.equals("1", ignoreCase = true) ||
                desiredMuteStr.equals("true", ignoreCase = true)

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
            } else {
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, mute)
            }
            Log.d(TAG, "SetMute: $mute")
        } catch (e: Exception) {
            Log.w(TAG, "SetMute failed", e)
            return buildSoapFault(501, "Mute control not permitted")
        }

        return buildSoapResponse(
            RENDERING_CONTROL_SERVICE,
            "SetMute",
            ""
        )
    }

    /**
     * Handles GetMute — returns current system media mute state.
     */
    private fun handleGetMute(soapBody: Element): String {
        val channel = getTagText(soapBody, "Channel") ?: "Master"

        val isMuted: Boolean = try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            } else {
                // isStreamMute is not available pre-API 23; return false
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read mute state", e)
            false
        }

        val currentMute = if (isMuted) "1" else "0"
        val safeChannel = DlnaXmlParser.sanitizeXmlText(channel)

        return buildSoapResponse(
            RENDERING_CONTROL_SERVICE,
            "GetMute",
            """
                <CurrentMute>$currentMute</CurrentMute>
                <Channel>$safeChannel</Channel>
            """.trimIndent()
        )
    }

    // ==================================================================
    // SOAP XML Utilities
    // ==================================================================

    /**
     * Wraps [bodyXml] in a standard SOAP 1.1 response envelope.
     */
    private fun buildSoapResponse(
        serviceType: String,
        actionName: String,
        bodyXml: String
    ): String {
        val safeServiceType = DlnaXmlParser.sanitizeXmlText(serviceType)
        return buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append(
                """<s:Envelope xmlns:s="$NS_SOAP_ENV" """ +
                    """s:encodingStyle="$SOAP_ENCODING">"""
            )
            append("<s:Body>")
            append("<u:${actionName}Response xmlns:u=\"$safeServiceType\">")
            append(bodyXml)
            append("</u:${actionName}Response>")
            append("</s:Body>")
            append("</s:Envelope>")
        }
    }

    /**
     * Builds a SOAP 1.1 fault response.
     */
    private fun buildSoapFault(errorCode: Int, errorDescription: String): String {
        val safeDesc = DlnaXmlParser.sanitizeXmlText(errorDescription)
        return buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append(
                """<s:Envelope xmlns:s="$NS_SOAP_ENV" """ +
                    """s:encodingStyle="$SOAP_ENCODING">"""
            )
            append("<s:Body>")
            append("<s:Fault>")
            append("<faultcode>s:Client</faultcode>")
            append("<faultstring>UPnPError</faultstring>")
            append("<detail>")
            append("<UPnPError xmlns=\"$NS_UPNP_CONTROL\">")
            append("<errorCode>$errorCode</errorCode>")
            append("<errorDescription>$safeDesc</errorDescription>")
            append("</UPnPError>")
            append("</detail>")
            append("</s:Fault>")
            append("</s:Body>")
            append("</s:Envelope>")
        }
    }

    /**
     * Parses the raw SOAP XML string through the hardened [DlnaXmlParser]
     * and returns the root [Element] of the SOAP body.
     *
     * Returns the document root (<Envelope>). Use [getTagText] to extract
     * parameter values from the action-specific child elements.
     */
    @Throws(SecurityException::class, Exception::class)
    private fun parseSoapBodyElement(rawXml: String): Element {
        val inputStream = ByteArrayInputStream(rawXml.toByteArray(Charsets.UTF_8))
        return DlnaXmlParser.parseSoapBody(inputStream)
    }

    /**
     * Extracts the text content of the first element with [tagName]
     * found anywhere in the [parent] element tree.
     *
     * @return The trimmed text content, or `null` if no matching element exists.
     */
    private fun getTagText(parent: Element, tagName: String): String? {
        val nodes = parent.getElementsByTagName(tagName)
        return if (nodes.length > 0) {
            nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }

    /**
     * Extracts the source IP address from the raw SOAP XML by scanning
     * for a control-point-provided source hint, or falls back to a best-effort
     * placeholder.
     *
     * In a Ktor POST handler we do not have the raw remote host available
     * from within the synchronous handler path. We rely on the
     * rate-limiting key derived from whatever identifying information
     * is available.
     */
    private fun extractSourceIpFromSoapBody(rawXml: String): String {
        // Try to extract from the SOAP XML first — some control points
        // include a Source or Peer header. Fall back to a hashed key.
        val sourceMatch = Regex("""<Source[^>]*>([^<]+)</Source>""").find(rawXml)
        return sourceMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "unknown:" + rawXml.hashCode().toUInt().toString(16)
    }

    // ==================================================================
    // Rate Limiting
    // ==================================================================

    /**
     * Checks whether a SetAVTransportURI request from [sourceIp] is
     * allowed under the debounce and per-minute rate limits.
     *
     * @return `true` if the request is rate-limited (should be denied).
     */
    private fun isRateLimited(sourceIp: String): Boolean {
        val now = System.currentTimeMillis()
        val history = uriChangeHistory[sourceIp] ?: return false

        synchronized(history) {
            // Remove entries outside the rate window
            history.removeAll { it < now - RATE_WINDOW_MS }

            // Debounce: check if the last request was within DEBOUNCE_MS
            if (history.isNotEmpty() && (now - history.last()) < DEBOUNCE_MS) {
                return true
            }

            // Check per-minute limit
            if (history.size >= MAX_URI_CHANGES_PER_MIN) {
                return true
            }
        }

        return false
    }

    /**
     * Records a SetAVTransportURI from [sourceIp] for rate-limiting purposes.
     */
    private fun recordUriChange(sourceIp: String) {
        val now = System.currentTimeMillis()
        val history = uriChangeHistory.computeIfAbsent(sourceIp) {
            mutableListOf()
        }
        synchronized(history) {
            history.add(now)
            // Trim old entries
            history.removeAll { it < now - RATE_WINDOW_MS }
        }
    }

    // ==================================================================
    // Player Activity Launch
    // ==================================================================

    /**
     * Launches [UFMPlayerActivity] with the given URL as the media source.
     */
    private fun launchPlayerActivity(url: String, title: String) {
        try {
            val intent = Intent(
                context,
                UFMPlayerActivity::class.java
            ).apply {
                putExtra("provider", "DLNA_RENDERER")
                putExtra("initialPath", url)
                putExtra("shareName", "DLNA Renderer")
                putExtra("initialSize", 0L)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Launched UFMPlayerActivity for: $title")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch UFMPlayerActivity", e)
        }
    }

    // ==================================================================
    // Notification for Unsolicited Playback Push
    // ==================================================================

    /**
     * Shows a system notification informing the user of an unsolicited
     * playback request from a DLNA control point.
     */
    private fun showPlaybackRequestNotification(
        sourceIp: String,
        title: String,
        url: String
    ) {
        try {
            val notificationManager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            // Create notification channel (Android 8+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.dlna_unsolicited_playback_title),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(
                        R.string.dlna_unsolicited_playback_message,
                        sourceIp,
                        title
                    )
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Build intent to open UFMPlayerActivity
            val intent = Intent(
                context,
                UFMPlayerActivity::class.java
            ).apply {
                putExtra("provider", "DLNA_RENDERER")
                putExtra("initialPath", url)
                putExtra("shareName", "DLNA Renderer")
                putExtra("initialSize", 0L)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

            val notification = android.app.Notification.Builder(
                context,
                NOTIFICATION_CHANNEL_ID
            ).apply {
                setContentTitle(
                    context.getString(R.string.dlna_unsolicited_playback_title)
                )
                setContentText(
                    context.getString(
                        R.string.dlna_unsolicited_playback_message,
                        sourceIp,
                        title
                    )
                )
                setSmallIcon(R.drawable.ic_notifications)
                setContentIntent(pendingIntent)
                setAutoCancel(true)
                setPriority(android.app.Notification.PRIORITY_HIGH)
                setCategory(android.app.Notification.CATEGORY_EVENT)
            }.build()

            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Playback request notification shown for: $title")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show playback request notification", e)
        }
    }

    // ==================================================================
    // URL / Metadata Helpers
    // ==================================================================

    /**
     * Attempts to extract a human-readable title from the DIDL-Lite
     * metadata XML that control points send as CurrentURIMetaData.
     */
    private fun extractTitleFromMetaData(metaData: String): String? {
        if (metaData.isBlank()) return null
        try {
            val inputStream = ByteArrayInputStream(metaData.toByteArray(Charsets.UTF_8))
            val dbf = DocumentBuilderFactory.newInstance()
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(inputStream)
            val titleNodes = doc.getElementsByTagName("dc:title")
            if (titleNodes.length > 0) {
                val title = titleNodes.item(0).textContent?.trim()
                if (!title.isNullOrBlank()) return title
            }
            // Try without namespace prefix
            val altTitleNodes = doc.getElementsByTagName("title")
            if (altTitleNodes.length > 0) {
                val title = altTitleNodes.item(0).textContent?.trim()
                if (!title.isNullOrBlank()) return title
            }
        } catch (e: Exception) {
            // Metadata parsing is best-effort; fall through to URL extraction
            Log.v(TAG, "Could not parse metadata XML for title", e)
        }
        return null
    }

    /**
     * Extracts a human-readable title from a URL string by taking the
     * last path segment and decoding percent-encoded characters.
     */
    private fun extractTitleFromUrl(url: String): String {
        val pathSegment = url.substringAfterLast('/').substringBefore('?')
        if (pathSegment.isBlank()) return "Remote Media"
        return java.net.URLDecoder.decode(pathSegment, Charsets.UTF_8.name())
    }

    /**
     * Parses a duration string in H:MM:SS or HH:MM:SS format to
     * milliseconds. Returns -1 on parse failure.
     */
    private fun parseDurationToMs(duration: String): Long {
        val parts = duration.trim().split(':')
        return when (parts.size) {
            3 -> {
                val hours = parts[0].toLongOrNull() ?: return -1L
                val minutes = parts[1].toLongOrNull() ?: return -1L
                val seconds = parts[2].toLongOrNull() ?: return -1L
                if (minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59) {
                    return -1L
                }
                ((hours * 3600) + (minutes * 60) + seconds) * 1000L
            }
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return -1L
                val seconds = parts[1].toLongOrNull() ?: return -1L
                if (minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59) {
                    return -1L
                }
                ((minutes * 60) + seconds) * 1000L
            }
            else -> -1L
        }
    }

    // ==================================================================
    // Device Description XML
    // ==================================================================

    /**
     * Builds the UPnP MediaRenderer:1 device description XML served
     * at `/description.xml`.
     */
    private fun getDeviceDescriptionXml(locationBase: String): String {
        val friendlyName = DlnaXmlParser.sanitizeXmlText(
            DlnaServerPrefs.getDlnaRendererName(context)
        )
        val safeUdn = DlnaXmlParser.sanitizeXmlText(udn)

        return buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\">")
            append("<specVersion><major>1</major><minor>0</minor></specVersion>")
            append("<device>")
            append("<deviceType>$DEVICE_TYPE</deviceType>")
            append("<friendlyName>$friendlyName</friendlyName>")
            append("<manufacturer>Kilowatch</manufacturer>")
            append("<manufacturerURL>https://kilowatch.za</manufacturerURL>")
            append("<modelDescription>UFM DLNA Media Renderer</modelDescription>")
            append("<modelName>UFM Player</modelName>")
            append("<modelNumber>1.0</modelNumber>")
            append("<modelURL>https://kilowatch.za</modelURL>")
            append("<UDN>uuid:$safeUdn</UDN>")
            append("<serviceList>")

            // AVTransport:1
            // UPnP 1.0 §2.5: SCPDURL, controlURL, eventSubURL MUST be relative paths.
            // Absolute URLs cause many control points (including BubbleUPnP) to
            // construct malformed double-URLs when resolving against the LOCATION base.
            append("<service>")
            append("<serviceType>$AV_TRANSPORT_SERVICE</serviceType>")
            append("<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>")
            append("<SCPDURL>/AVTransport.xml</SCPDURL>")
            append("<controlURL>/avt/control</controlURL>")
            append("<eventSubURL>/avt/event</eventSubURL>")
            append("</service>")

            // RenderingControl:1
            append("<service>")
            append("<serviceType>$RENDERING_CONTROL_SERVICE</serviceType>")
            append("<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>")
            append("<SCPDURL>/RenderingControl.xml</SCPDURL>")
            append("<controlURL>/rrc/control</controlURL>")
            append("<eventSubURL>/rrc/event</eventSubURL>")
            append("</service>")

            // ConnectionManager:1 — required by DLNA spec on all devices.
            // BubbleUPnP filters renderers that lack this service.
            append("<service>")
            append("<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>")
            append("<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>")
            append("<SCPDURL>/ConnectionManager.xml</SCPDURL>")
            append("<controlURL>/cms/control</controlURL>")
            append("<eventSubURL>/cms/event</eventSubURL>")
            append("</service>")

            append("</serviceList>")
            append("</device>")
            append("</root>")
        }
    }

    // ==================================================================
    // SCPD XML — ConnectionManager:1 (minimal)
    // ==================================================================

    private fun getConnectionManagerScpdXml(): String {
        return buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">")
            append("<specVersion><major>1</major><minor>0</minor></specVersion>")
            append("<actionList>")
            append("<action><name>GetProtocolInfo</name>")
            append("<argumentList>")
            append("<argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>")
            append("<argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>")
            append("</argumentList></action>")
            append("<action><name>GetCurrentConnectionIDs</name>")
            append("<argumentList>")
            append("<argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>")
            append("</argumentList></action>")
            append("<action><name>GetCurrentConnectionInfo</name>")
            append("<argumentList>")
            append("<argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>")
            append("<argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>")
            append("<argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>")
            append("<argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>")
            append("<argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>")
            append("<argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>")
            append("<argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>")
            append("<argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument>")
            append("</argumentList></action>")
            append("</actionList>")
            append("<serviceStateTable>")
            append("<stateVariable sendEvents=\"yes\"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType><allowedValueList><allowedValue>Input</allowedValue><allowedValue>Output</allowedValue></allowedValueList></stateVariable>")
            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType><allowedValueList><allowedValue>OK</allowedValue><allowedValue>ContentFormatMismatch</allowedValue><allowedValue>InsufficientBandwidth</allowedValue><allowedValue>UnreliableChannel</allowedValue><allowedValue>Unknown</allowedValue></allowedValueList></stateVariable>")
            append("</serviceStateTable>")
            append("</scpd>")
        }
    }

    private suspend fun handleConnectionManagerSoap(call: ApplicationCall) {
        val bodyText = call.receiveText()
        val soapBody = DlnaXmlParser.parseSoapBody(bodyText.byteInputStream())
        val soapAction = call.request.headers["SOAPACTION"] ?: ""
        val actionName = soapAction.substringAfterLast('#').trim('"')
        val responseXml = DlnaConnectionManager.handleSoapAction(actionName, soapBody)
        call.respondText(responseXml, ContentType.Text.Xml)
    }

    // ==================================================================
    // SCPD XML — AVTransport:1
    // ==================================================================

    /**
     * Returns the static SCPD XML document for the AVTransport:1 service,
     * served at `/AVTransport.xml`.
     */
    private fun getAvTransportScpdXml(): String {
        return buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append(
                "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
            )
            append("<specVersion><major>1</major><minor>0</minor></specVersion>")

            // --- Action list ---
            append("<actionList>")

            // SetAVTransportURI
            append("<action>")
            append("<name>SetAVTransportURI</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("<argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>")
            append("<argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            // Play
            append("<action>")
            append("<name>Play</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("<argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            // Pause
            append("<action>")
            append("<name>Pause</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            // Stop
            append("<action>")
            append("<name>Stop</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            // Seek
            append("<action>")
            append("<name>Seek</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("<argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>")
            append("<argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            // GetTransportInfo
            append("<action>")
            append("<name>GetTransportInfo</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("<argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>")
            append("<argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>")
            append("<argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            // GetPositionInfo
            append("<action>")
            append("<name>GetPositionInfo</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("<argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>")
            append("<argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>")
            append("<argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>")
            append("<argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>")
            append("<argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>")
            append("<argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>")
            append("<argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>")
            append("<argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            // GetMediaInfo
            append("<action>")
            append("<name>GetMediaInfo</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("<argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>")
            append("<argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>")
            append("<argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>")
            append("<argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>")
            append("<argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>")
            append("<argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>")
            append("<argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlayMedium</relatedStateVariable></argument>")
            append("<argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordMedium</relatedStateVariable></argument>")
            append("<argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>WriteStatus</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            append("</actionList>")

            // --- Service state table ---
            append("<serviceStateTable>")

            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType><allowedValueList><allowedValue>ABS_TIME</allowedValue><allowedValue>REL_TIME</allowedValue><allowedValue>TRACK_NR</allowedValue></allowedValueList></stateVariable>")
            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>")

            // Bug B fix: UPnP AVTransport:1 spec Table 2-5 defines the pause state as
            // "PAUSED_PLAYBACK", not "PAUSED". BubbleUPnP validates the SCPD allowedValueList
            // and will reject a device that reports an unrecognised state string.
            append("<stateVariable sendEvents=\"yes\"><name>TransportState</name><dataType>string</dataType><allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>TRANSITIONING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue><allowedValue>NO_MEDIA_PRESENT</allowedValue></allowedValueList></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>TransportStatus</name><dataType>string</dataType><allowedValueList><allowedValue>OK</allowedValue><allowedValue>ERROR_OCCURRED</allowedValue></allowedValueList></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>TransportPlaySpeed</name><dataType>string</dataType><allowedValueList><allowedValue>1</allowedValue></allowedValueList></stateVariable>")

            append("<stateVariable sendEvents=\"yes\"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>")

            append("<stateVariable sendEvents=\"yes\"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>")

            append("<stateVariable sendEvents=\"yes\"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>PlayMedium</name><dataType>string</dataType><allowedValueList><allowedValue>NETWORK</allowedValue><allowedValue>NONE</allowedValue></allowedValueList></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>RecordMedium</name><dataType>string</dataType><allowedValueList><allowedValue>NOT_IMPLEMENTED</allowedValue><allowedValue>NONE</allowedValue></allowedValueList></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>WriteStatus</name><dataType>string</dataType><allowedValueList><allowedValue>NOT_IMPLEMENTED</allowedValue><allowedValue>NONE</allowedValue></allowedValueList></stateVariable>")

            append("</serviceStateTable>")
            append("</scpd>")
        }
    }

    // ==================================================================
    // SCPD XML — RenderingControl:1
    // ==================================================================

    /**
     * Returns the static SCPD XML document for the RenderingControl:1
     * service, served at `/RenderingControl.xml`.
     */
    private fun getRenderingControlScpdXml(): String {
        return buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append(
                "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
            )
            append("<specVersion><major>1</major><minor>0</minor></specVersion>")

            // --- Action list ---
            append("<actionList>")

            // SetVolume
            append("<action>")
            append("<name>SetVolume</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>")
            append("<argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            // GetVolume
            append("<action>")
            append("<name>GetVolume</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>")
            append("<argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            // SetMute
            append("<action>")
            append("<name>SetMute</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>")
            append("<argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            // GetMute
            append("<action>")
            append("<name>GetMute</name>")
            append("<argumentList>")
            append("<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>")
            append("<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>")
            append("<argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>")
            append("</argumentList>")
            append("</action>")

            append("</actionList>")

            // --- Service state table ---
            append("<serviceStateTable>")

            append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>Channel</name><dataType>string</dataType><allowedValueList><allowedValue>Master</allowedValue></allowedValueList></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>")
            append("<stateVariable sendEvents=\"yes\"><name>Mute</name><dataType>boolean</dataType><allowedValueList><allowedValue>0</allowedValue><allowedValue>1</allowedValue></allowedValueList></stateVariable>")

            append("</serviceStateTable>")
            append("</scpd>")
        }
    }
}

/**
 * DLNA transport state machine values.
 *
 * Reflects the standard UPnP AV Transport state transitions:
 * - STOPPED -> PLAYING (via [Play])
 * - PLAYING -> PAUSED_PLAYBACK (via [Pause])
 * - PAUSED_PLAYBACK -> PLAYING (via [Play])
 * - PLAYING -> STOPPED (via [Stop])
 * - PAUSED_PLAYBACK -> STOPPED (via [Stop])
 *
 * Enum names are used directly as the TransportState string value reported
 * to control points via GetTransportInfo. They MUST match the allowedValueList
 * in AVTransport.xml exactly (UPnP AVTransport:1 spec Table 2-5).
 */
enum class TransportState {
    STOPPED,
    PLAYING,
    PAUSED_PLAYBACK,
    TRANSITIONING,
    NO_MEDIA_PRESENT
}
