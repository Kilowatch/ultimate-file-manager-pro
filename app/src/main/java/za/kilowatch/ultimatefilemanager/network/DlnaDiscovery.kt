package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import za.kilowatch.ultimatefilemanager.server.DlnaSecurityFilter
import za.kilowatch.ultimatefilemanager.server.DlnaXmlParser
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SSDP discovery system for the DLNA Media Client.
 *
 * Discovers DLNA MediaServer devices on the LAN via M-SEARCH probes and
 * passive NOTIFY:ssdp::alive listening.  Device descriptions (XML) are
 * fetched from each server's LOCATION URL and parsed to produce
 * [DlnaServerInfo] objects that the client can then use for browsing
 * and streaming.
 */
object DlnaDiscovery {

    private const val TAG = "DlnaDiscovery"

    /** Maximum age (ms) before a passive-discovery entry is evicted. */
    private const val CACHE_EXPIRY_MS = 300_000L // 5 minutes

    /** How often the cleanup tick runs (only while passive discovery is active). */
    private const val CLEANUP_INTERVAL_MS = 60_000L // 1 minute

    /** How long [scanLan] waits for M-SEARCH responses to accumulate. */
    private const val SCAN_WAIT_MS = 3000L // 3 seconds

    // ── State ────────────────────────────────────────────────────────────────

    /** Map keyed by UDN (Unique Device Name). */
    private val discoveredServers = ConcurrentHashMap<String, DlnaServerInfo>()

    /** Set of UDNs that have been seen for the first time this session. */
    private val newlyDiscovered = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var passiveListening = false

    private var appContext: Context? = null

    private var cleanupHandler: Handler? = null
    private var cleanupRunnable: Runnable? = null

    /** Dedicated thread for fetching device description XML without blocking SSDP. */
    private val fetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DlnaFetchThread").also { it.isDaemon = true }
    }

    private val okHttpClient = BypassCleartextOkHttpClient.applyBypass(
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
    ).build()

    // ── SSDP Listener ────────────────────────────────────────────────────────

    private val ssdpListener = object : SsdpListener {

        override fun onMSearchResponse(
            remoteAddress: InetAddress,
            st: String,
            usn: String,
            location: String,
            server: String
        ) {
            if (location.isBlank()) return
            fetchExecutor.submit {
                val info = fetchDeviceDescription(location)
                if (info != null) {
                    discoveredServers[info.udn] = info
                    if (newlyDiscovered.add(info.udn)) {
                        Log.i(
                            TAG,
                            "New DLNA server found: ${info.friendlyName} at ${info.ip}. " +
                                "Only connect to servers you trust."
                        )
                    }
                }
            }
        }

        override fun onNotifyAlive(
            remoteAddress: InetAddress,
            nt: String,
            usn: String,
            location: String,
            server: String
        ) {
            if (location.isBlank()) return
            fetchExecutor.submit {
                val info = fetchDeviceDescription(location)
                if (info != null) {
                    val existing = discoveredServers[info.udn]
                    if (existing == null) {
                        // Brand new server
                        discoveredServers[info.udn] = info
                        if (passiveListening && newlyDiscovered.add(info.udn)) {
                            Log.i(
                                TAG,
                                "New DLNA server found: ${info.friendlyName} at ${info.ip}. " +
                                    "Only connect to servers you trust."
                            )
                        }
                    } else {
                        // Refresh the lastSeen timestamp
                        discoveredServers[info.udn] = existing.copy(
                            lastSeen = System.currentTimeMillis()
                        )
                    }
                }
            }
        }

        override fun onNotifyByeBye(
            remoteAddress: InetAddress,
            nt: String,
            usn: String
        ) {
            // USN format: "uuid:UDN::urn:schemas-upnp-org:device:MediaServer:1"
            // Extract the UDN portion by taking everything before "::".
            val udn = usn.split("::").firstOrNull()?.takeIf { it.isNotEmpty() } ?: usn
            val removed = discoveredServers.remove(udn)
            if (removed != null) {
                Log.d(TAG, "Server went offline: ${removed.friendlyName} ($udn)")
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Initialise the discovery system and register the SSDP listener.
     *
     * Must be called once (typically from Application.onCreate()) before any
     * other method on this object is used.
     */
    fun initialize(context: Context) {
        if (appContext != null) return  // already initialized
        appContext = context.applicationContext
        // Initialize the SSDP engine if not already running (idempotent).
        // This sets up the multicast socket needed for M-SEARCH send/receive.
        val ssdpOk = DlnaSsdpEngine.initialize(context, "")
        Log.i(TAG, "DlnaDiscovery initialized (SSDP engine: ${if (ssdpOk) "OK" else "FAILED"})")
        DlnaSsdpEngine.addListener(ssdpListener)
    }

    /**
     * Perform an active M-SEARCH scan of the LAN for MediaServer:1 devices.
     *
     * Sends an M-SEARCH, waits [SCAN_WAIT_MS] ms for responses, fetches device
     * descriptions for every responding server, and returns the results.
     *
     * Should be called from a background thread.
     */
    fun scanLan(): List<DlnaServerInfo> {
        discoveredServers.clear()
        DlnaSsdpEngine.sendMSearch(
            searchTarget = "urn:schemas-upnp-org:device:MediaServer:1",
            mx = 3
        )
        // Give responses time to arrive and device-description fetches to complete
        try {
            Thread.sleep(SCAN_WAIT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return getDiscoveredServers()
    }

    /**
     * Start listening for passive NOTIFY:ssdp::alive advertisements.
     *
     * While active, any new server that appears on the LAN is automatically
     * discovered, its description is fetched, and the server is cached.
     * Stale entries are evicted after [CACHE_EXPIRY_MS] of silence.
     */
    fun startPassiveDiscovery() {
        passiveListening = true
        scheduleCleanup()
        Log.d(TAG, "Passive discovery started")
    }

    /**
     * Stop listening for passive NOTIFY advertisements and halt cache cleanup.
     *
     * Already-discovered servers remain in the cache until they expire or are
     * explicitly removed.
     */
    fun stopPassiveDiscovery() {
        passiveListening = false
        cleanupHandler?.removeCallbacksAndMessages(null)
        cleanupRunnable = null
        Log.d(TAG, "Passive discovery stopped")
    }

    /**
     * Returns the current list of known servers, filtering out any entries
     * whose [DlnaServerInfo.lastSeen] is older than [CACHE_EXPIRY_MS].
     */
    fun getDiscoveredServers(): List<DlnaServerInfo> {
        val now = System.currentTimeMillis()
        discoveredServers.entries.removeIf { (_, info) ->
            now - info.lastSeen > CACHE_EXPIRY_MS
        }
        return discoveredServers.values.toList()
    }

    /**
     * Look up a server by its UDN.
     */
    fun getServerByUdn(udn: String): DlnaServerInfo? = discoveredServers[udn]

    /**
     * Returns `true` if the server identified by [udn] was discovered for the
     * first time this session and has not yet been acknowledged via [markSeen].
     */
    fun isNewlyDiscovered(udn: String): Boolean = newlyDiscovered.contains(udn)

    /**
     * Acknowledge a "new server" warning for the given [udn].
     *
     * After calling this, [isNewlyDiscovered] returns `false` for that UDN
     * until a future session (application restart).
     */
    fun markSeen(udn: String) {
        newlyDiscovered.remove(udn)
    }

    // ── Device Description Parser ───────────────────────────────────────────

    /**
     * Fetches the UPnP device-description XML at [locationUrl], parses out the
     * relevant fields, and returns a [DlnaServerInfo], or `null` on failure.
     *
     * Expected XML structure (namespace-insensitive):
     * ```
     * <root>
     *   <device>
     *     <UDN>uuid:...</UDN>
     *     <friendlyName>My Server</friendlyName>
     *     <serviceList>
     *       <service>
     *         <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
     *         <controlURL>/upnp/control/content_directory</controlURL>
     *       </service>
     *       <service>
     *         <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
     *         <controlURL>/upnp/control/connection_manager</controlURL>
     *       </service>
     *     </serviceList>
     *   </device>
     * </root>
     * ```
     *
     * Relative controlURLs are resolved against [locationUrl].
     */
    private fun fetchDeviceDescription(locationUrl: String): DlnaServerInfo? {
        return try {
            val url = URL(locationUrl)
            val host = url.host
            if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "[::1]") {
                Log.d(TAG, "Ignoring loopback device description URL: $locationUrl")
                return null
            }
            if (!DlnaSecurityFilter.validateUrl(locationUrl)) {
                Log.w(TAG, "Ignoring blocked device description URL: $locationUrl")
                return null
            }

            val request = Request.Builder().url(locationUrl).get().build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} fetching $locationUrl")
                response.close()
                return null
            }

            val bodyBytes = response.body?.bytes()
            response.close()

            if (bodyBytes == null || bodyBytes.isEmpty()) {
                Log.w(TAG, "Empty response body from $locationUrl")
                return null
            }

            val builder = DlnaXmlParser.newSecureDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(bodyBytes))

            val deviceElement = doc.getElementsByTagName("device")?.item(0) as? Element
                ?: return null

            val udn = getElementText(deviceElement, "UDN")?.takeIf { it.isNotBlank() }
                ?: return null
            val friendlyName = getElementText(deviceElement, "friendlyName") ?: "Unknown"

            // Locate service control URLs
            var contentDirectoryUrl = ""
            var connectionManagerUrl = ""

            val serviceListElement =
                doc.getElementsByTagName("serviceList")?.item(0) as? Element

            if (serviceListElement != null) {
                val serviceElements = serviceListElement.getElementsByTagName("service")
                for (i in 0 until serviceElements.length) {
                    val service = serviceElements.item(i) as? Element ?: continue
                    val serviceType = getElementText(service, "serviceType") ?: ""
                    val controlUrl = getElementText(service, "controlURL") ?: ""
                    if (controlUrl.isBlank()) continue

                    val resolved = resolveUrl(locationUrl, controlUrl)

                    when {
                        serviceType.contains("ContentDirectory:1") ->
                            contentDirectoryUrl = resolved
                        serviceType.contains("ConnectionManager:1") ->
                            connectionManagerUrl = resolved
                    }
                }
            }

            DlnaServerInfo(
                udn = udn,
                friendlyName = friendlyName,
                ip = url.host,
                port = if (url.port > 0) url.port else 80,
                contentDirectoryUrl = contentDirectoryUrl,
                connectionManagerUrl = connectionManagerUrl,
                lastSeen = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch/parse device description from $locationUrl", e)
            null
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Returns the trimmed text content of the first child element with the
     * given [tagName] under [parent], or `null` if no such element exists.
     */
    private fun getElementText(parent: Element, tagName: String): String? {
        val nodeList = parent.getElementsByTagName(tagName)
        if (nodeList.length == 0) return null
        val element = nodeList.item(0) as? Element ?: return null
        return element.textContent?.trim()
    }

    /**
     * Resolves [relativeUrl] against [baseUrl] and returns the absolute URL.
     *
     * If resolution fails (malformed URL, etc.) the original [relativeUrl] is
     * returned as a fallback.
     */
    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            URL(URL(baseUrl), relativeUrl).toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }

    // ── Cache Cleanup (passive discovery) ───────────────────────────────────

    /**
     * Starts a periodic cleanup loop on the main looper that evicts stale
     * entries (lastSeen older than [CACHE_EXPIRY_MS]).
     *
     * The loop is automatically stopped when [stopPassiveDiscovery] is called.
     */
    private fun scheduleCleanup() {
        cleanupHandler?.removeCallbacksAndMessages(null)
        cleanupHandler = Handler(Looper.getMainLooper())
        cleanupRunnable = Runnable {
            if (!passiveListening) return@Runnable
            val now = System.currentTimeMillis()
            discoveredServers.entries.removeIf { (_, info) ->
                now - info.lastSeen > CACHE_EXPIRY_MS
            }
            cleanupHandler?.postDelayed(cleanupRunnable!!, CLEANUP_INTERVAL_MS)
        }
        cleanupHandler?.postDelayed(cleanupRunnable!!, CLEANUP_INTERVAL_MS)
    }
}
