package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

const val SSDP_MULTICAST_ADDRESS = "239.255.255.250"
const val SSDP_PORT = 1900
const val SSDP_MAX_PACKET_SIZE = 1500

interface SsdpListener {
    fun onMSearchResponse(
        remoteAddress: InetAddress,
        st: String,
        usn: String,
        location: String,
        server: String
    )

    fun onNotifyAlive(
        remoteAddress: InetAddress,
        nt: String,
        usn: String,
        location: String,
        server: String
    )

    fun onNotifyByeBye(
        remoteAddress: InetAddress,
        nt: String,
        usn: String
    )
}

object DlnaSsdpEngine {
    private const val TAG = "DlnaSsdpEngine"
    private const val MSEARCH_RATE_LIMIT_MS = 30_000L
    private const val NOTIFY_RATE_LIMIT_MS = 60_000L

    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val listeners = CopyOnWriteArrayList<SsdpListener>()
    private var listenerThread: Thread? = null

    @Volatile
    private var running = false

    private var lastMSearchTime = 0L
    private val lastNotifyTime = HashMap<String, Long>()
    // Registered services: NT, USN, location URL
    private data class RegisteredService(val nt: String, val usn: String, val location: String)
    private val registeredServices = CopyOnWriteArrayList<RegisteredService>()

    // Background executor used to apply the random MX delay before unicast M-SEARCH responses.
    // A single thread is sufficient because M-SEARCH responses are low-volume.
    private val msearchResponseExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DlnaSsdpMSearchResponder").also { it.isDaemon = true }
    }

    // Dedicated background executor for outbound UDP multicast and unicast packets,
    // ensuring DatagramSocket.send never executes on the main looper.
    private val ssdpSendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DlnaSsdpSender").also { it.isDaemon = true }
    }

    // ─── Public API ──────────────────────────────────────────────────────

    fun initialize(context: Context, bindAddress: String = ""): Boolean {
        if (running) return true

        try {
            val bindAddr = resolveBindAddress(bindAddress)
            val networkInterface = findInterfaceForAddress(bindAddr)

            val socket = MulticastSocket(null)
            socket.reuseAddress = true
            // Bind to INADDR_ANY (0.0.0.0) so the socket receives ALL multicast
            // packets on port 1900, regardless of which interface they arrive on.
            // Binding to the specific unicast IP (e.g. 192.168.10.76) causes Android
            // to silently drop incoming multicast on many Android TV builds because
            // the OS only delivers multicast to sockets bound to INADDR_ANY or the
            // multicast group address itself.
            socket.bind(InetSocketAddress(SSDP_PORT))
            socket.timeToLive = 4
            @Suppress("DEPRECATION")
            socket.loopbackMode = false

            // Direct outbound multicast through the correct physical interface.
            if (networkInterface != null) {
                socket.networkInterface = networkInterface
            }

            multicastSocket = socket

            val groupAddress = InetAddress.getByName(SSDP_MULTICAST_ADDRESS)
            if (networkInterface != null) {
                socket.joinGroup(java.net.InetSocketAddress(groupAddress, SSDP_PORT), networkInterface)
                Log.d(TAG, "Joined multicast group on ${networkInterface.name} (${bindAddr.hostAddress})")
            } else {
                socket.joinGroup(groupAddress)
                Log.w(TAG, "Joined multicast group without specific network interface")
            }

            // Use safe-cast: on WiFi-absent TV builds, WifiManager can be null.
            val wm = context.applicationContext.getSystemService(
                Context.WIFI_SERVICE
            ) as? WifiManager
            if (wm != null) {
                multicastLock = wm.createMulticastLock("UFMDlnaSsdpLock")
                multicastLock?.setReferenceCounted(true)
                multicastLock?.acquire()
                Log.d(TAG, "MulticastLock acquired")
            } else {
                Log.w(TAG, "WifiManager unavailable — MulticastLock not acquired (TV/Ethernet-only device)")
            }

            running = true
            startListenerThread()

            Log.d(TAG, "SSDP engine initialized on ${bindAddr.hostAddress}")
            return true
        } catch (e: BindException) {
            Log.e(TAG, "Failed to bind SSDP socket to port $SSDP_PORT", e)
            cleanupSocketAndLock()
            return false
        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize SSDP engine", e)
            cleanupSocketAndLock()
            return false
        }
    }

    fun shutdown() {
        for ((nt, usn) in registeredServices) {
            sendByeBye(nt, usn)
        }
        registeredServices.clear()
        cleanupSocketAndLock()
        running = false
        listeners.clear()
        lastNotifyTime.clear()
        lastMSearchTime = 0L
        Log.d(TAG, "SSDP engine shut down")
    }

    fun sendMSearch(
        searchTarget: String = "urn:schemas-upnp-org:device:MediaServer:1",
        mx: Int = 3
    ) {
        val now = System.currentTimeMillis()
        if (now - lastMSearchTime < MSEARCH_RATE_LIMIT_MS) return
        lastMSearchTime = now

        val message = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_MULTICAST_ADDRESS:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: $mx\r\n")
            append("ST: $searchTarget\r\n")
            append("\r\n")
        }
        sendMulticast(message)
        Log.d(TAG, "Sent M-SEARCH for $searchTarget")
    }

    fun sendNotifyAlive(
        nt: String,
        usn: String,
        location: String,
        server: String = "UFM DLNA Server/1.0",
        bypassRateLimit: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        // Rate-limit applies only to periodic keep-alive announcements.
        // Initial startup bursts pass bypassRateLimit=true so all 3 UDP
        // packets are sent (UDP is lossy; 1 packet is insufficient).
        // Bug 4 fix: do NOT update lastNotifyTime during a bypass — doing so
        // would cause the next regular burst (e.g. after a rapid stop/restart
        // within NOTIFY_RATE_LIMIT_MS) to be silently dropped.
        if (!bypassRateLimit) {
            val lastTime = lastNotifyTime[nt] ?: 0L
            if (now - lastTime < NOTIFY_RATE_LIMIT_MS) return
            lastNotifyTime[nt] = now
        }

        val message = buildString {
            append("NOTIFY * HTTP/1.1\r\n")
            append("HOST: $SSDP_MULTICAST_ADDRESS:$SSDP_PORT\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("LOCATION: $location\r\n")
            append("NT: $nt\r\n")
            append("NTS: ssdp:alive\r\n")
            append("SERVER: $server\r\n")
            append("USN: $usn\r\n")
            append("\r\n")
        }

        if (sendMulticast(message)) {
            val service = RegisteredService(nt, usn, location)
            if (service !in registeredServices) {
                registeredServices.add(service)
            }
            Log.d(TAG, "Sent NOTIFY ssdp:alive for $nt at $location")
        }
    }

    fun sendByeBye(nt: String, usn: String) {
        val message = buildString {
            append("NOTIFY * HTTP/1.1\r\n")
            append("HOST: $SSDP_MULTICAST_ADDRESS:$SSDP_PORT\r\n")
            append("NT: $nt\r\n")
            append("NTS: ssdp:byebye\r\n")
            append("USN: $usn\r\n")
            append("\r\n")
        }
        sendMulticast(message)
        registeredServices.removeAll { it.nt == nt && it.usn == usn }
        Log.d(TAG, "Sent NOTIFY ssdp:byebye for $nt")
    }

    fun addListener(callback: SsdpListener) {
        if (callback !in listeners) {
            listeners.add(callback)
        }
    }

    fun removeListener(callback: SsdpListener) {
        listeners.remove(callback)
    }

    // ─── Packet Sending ──────────────────────────────────────────────────

    // Rate limit M-SEARCH responses per source IP: 10/min, burst 5
    private val msearchResponseTimes = ConcurrentHashMap<String, Long>()
    private val msearchResponseCounts = ConcurrentHashMap<String, Int>()
    private const val MSEARCH_RESPONSE_RATE_LIMIT_MS = 60_000L
    private const val MSEARCH_RESPONSE_BURST = 5

    private fun allowMsearchResponse(sourceIp: String): Boolean {
        val now = System.currentTimeMillis()
        val lastWindow = msearchResponseTimes.getOrDefault(sourceIp, 0L)
        if (now - lastWindow > MSEARCH_RESPONSE_RATE_LIMIT_MS) {
            msearchResponseTimes[sourceIp] = now
            msearchResponseCounts[sourceIp] = 1
            return true
        }
        val count = msearchResponseCounts.getOrDefault(sourceIp, 0) + 1
        msearchResponseCounts[sourceIp] = count
        return count <= MSEARCH_RESPONSE_BURST
    }

    private fun sendUnicast(message: String, address: InetAddress, port: Int) {
        val socket = multicastSocket ?: return
        val bytes = message.toByteArray(Charsets.UTF_8)
        if (bytes.size > SSDP_MAX_PACKET_SIZE) {
            Log.w(TAG, "Unicast response too large (${bytes.size} bytes), dropping")
            return
        }
        ssdpSendExecutor.submit {
            try {
                val packet = DatagramPacket(bytes, bytes.size, address, port)
                socket.send(packet)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send unicast response to $address", e)
            }
        }
    }

    private fun sendMulticast(message: String): Boolean {
        val socket = multicastSocket ?: return false
        val bytes = message.toByteArray(Charsets.UTF_8)
        if (bytes.size > SSDP_MAX_PACKET_SIZE) {
            Log.w(
                TAG,
                "Packet too large (${bytes.size} bytes > $SSDP_MAX_PACKET_SIZE), dropping"
            )
            return false
        }
        ssdpSendExecutor.submit {
            try {
                val packet = DatagramPacket(
                    bytes, bytes.size,
                    InetAddress.getByName(SSDP_MULTICAST_ADDRESS), SSDP_PORT
                )
                socket.send(packet)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send multicast packet", e)
            }
        }
        return true
    }

    // ─── Listener Thread ─────────────────────────────────────────────────

    private fun startListenerThread() {
        listenerThread = Thread({
            val buffer = ByteArray(SSDP_MAX_PACKET_SIZE)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)
                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    processIncomingPacket(data, packet.address, packet.port)
                } catch (e: SocketException) {
                    if (running) {
                        Log.e(TAG, "Socket error in listener thread", e)
                    }
                    break
                } catch (e: IOException) {
                    if (running) {
                        Log.e(TAG, "IO error in listener thread", e)
                    }
                }
            }
            Log.d(TAG, "Listener thread stopped")
        }, "DlnaSsdpListener").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun processIncomingPacket(data: String, remoteAddress: InetAddress, remotePort: Int) {
        val lines = data.split("\\r?\\n".toRegex())
        if (lines.isEmpty()) return

        val firstLine = lines[0].trim()
        val headers = parseHeaders(lines.drop(1))

        // Handle M-SEARCH requests from control points (VLC, TVs, etc.)
        if (firstLine.startsWith("M-SEARCH", ignoreCase = true)) {
            val st = headers["st"] ?: return
            if (!isOnLocalSubnet(remoteAddress)) return

            // Rate limit M-SEARCH responses per source to prevent amplification
            val sourceKey = remoteAddress.hostAddress ?: return
            if (!allowMsearchResponse(sourceKey)) return

            // Bug 3 fix: per UPnP §1.3.2, a device responding to a multicast M-SEARCH
            // MUST wait a random interval between 0 and MX seconds before sending its
            // unicast response. Without this delay some strict control points (including
            // certain BubbleUPnP builds) reject responses that arrive before the MX
            // window has elapsed. We cap MX at 5 s and floor it at 1 s for safety.
            val mxSeconds = headers["mx"]?.trim()?.toIntOrNull()?.coerceIn(1, 5) ?: 3
            val delayMs = (Math.random() * mxSeconds * 1000L).toLong()

            // Snapshot the services list so the closure captures stable data.
            val matchedServices = registeredServices.filter { service ->
                // Bug 2 fix: each registered service responds ONLY when the request ST
                // equals "ssdp:all" OR the ST exactly matches that service's own NT.
                // Previously, the code also matched any NT when ST == "upnp:rootdevice",
                // causing the upnp:rootdevice service entry to wrongly respond to
                // ST: MediaRenderer:1 requests (and vice versa), which violates the spec
                // and causes BubbleUPnP to reject the response.
                st.equals("ssdp:all", ignoreCase = true) ||
                    st.equals(service.nt, ignoreCase = true)
            }

            if (matchedServices.isEmpty()) return

            // Capture state needed inside the executor closure.
            val capturedRemoteAddress = remoteAddress
            val capturedRemotePort = remotePort

            msearchResponseExecutor.submit {
                try {
                    if (delayMs > 0) Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return@submit
                }

                for (service in matchedServices) {
                    // Per UPnP §1.3.2: M-SEARCH response must include DATE header.
                    val dateStr = java.text.SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss z",
                        java.util.Locale.US
                    ).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
                     .format(java.util.Date())

                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("CACHE-CONTROL: max-age=1800\r\n")
                        append("DATE: $dateStr\r\n")
                        append("EXT:\r\n")
                        append("LOCATION: ${service.location}\r\n")
                        append("SERVER: UFM DLNA Server/1.0\r\n")
                        // Per UPnP §1.2.2: response ST MUST be identical to request ST
                        append("ST: $st\r\n")
                        append("USN: ${service.usn}\r\n")
                        append("\r\n")
                    }

                    sendUnicast(response, capturedRemoteAddress, capturedRemotePort)
                    Log.d(TAG, "Responded to M-SEARCH from $sourceKey (delay=${delayMs}ms): ST=$st -> ${service.nt}")
                }
            }
            return
        }

        if (firstLine.startsWith("NOTIFY")) {
            val nts = headers["nts"] ?: return
            val nt = headers["nt"] ?: return
            val usn = headers["usn"] ?: return

            if (!isOnLocalSubnet(remoteAddress)) return

            when {
                nts.equals("ssdp:alive", ignoreCase = true) -> {
                    val location = headers["location"] ?: ""
                    val server = headers["server"] ?: ""
                    for (listener in listeners) {
                        listener.onNotifyAlive(remoteAddress, nt, usn, location, server)
                    }
                }
                nts.equals("ssdp:byebye", ignoreCase = true) -> {
                    for (listener in listeners) {
                        listener.onNotifyByeBye(remoteAddress, nt, usn)
                    }
                }
            }
        } else if (firstLine.contains("200 OK", ignoreCase = true)) {
            val st = headers["st"] ?: return
            val usn = headers["usn"] ?: return
            val location = headers["location"] ?: ""
            val server = headers["server"] ?: ""

            if (!isOnLocalSubnet(remoteAddress)) return

            for (listener in listeners) {
                listener.onMSearchResponse(remoteAddress, st, usn, location, server)
            }
        }
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val headers = HashMap<String, String>()
        for (line in lines) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim()
                if (key.isNotEmpty()) {
                    headers[key] = value
                }
            }
        }
        return headers
    }

    // Cache local network interface subnets with a 10-second TTL to avoid expensive
    // JNI Linux.getifaddrs() system calls on every incoming SSDP packet.
    private data class SubnetInfo(val localInt: Int, val mask: Int)

    @Volatile
    private var cachedSubnets: List<SubnetInfo> = emptyList()
    @Volatile
    private var lastSubnetCacheTime = 0L
    private const val SUBNET_CACHE_TTL_MS = 10_000L
    private val subnetCacheLock = Any()

    private fun getOrUpdateSubnets(): List<SubnetInfo> {
        val now = System.currentTimeMillis()
        val current = cachedSubnets
        if (now - lastSubnetCacheTime <= SUBNET_CACHE_TTL_MS && current.isNotEmpty()) {
            return current
        }
        return synchronized(subnetCacheLock) {
            val nowInLock = System.currentTimeMillis()
            if (nowInLock - lastSubnetCacheTime <= SUBNET_CACHE_TTL_MS && cachedSubnets.isNotEmpty()) {
                return@synchronized cachedSubnets
            }
            val newSubnets = mutableListOf<SubnetInfo>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                if (interfaces != null) {
                    while (interfaces.hasMoreElements()) {
                        val iface = interfaces.nextElement()
                        if (!iface.isUp || iface.isLoopback) continue

                        for (ifAddr in iface.interfaceAddresses) {
                            val local = ifAddr.address
                            if (local is Inet4Address && !local.isLoopbackAddress) {
                                val prefixLen = ifAddr.networkPrefixLength.toInt()
                                if (prefixLen in 1..32) {
                                    val mask = if (prefixLen < 32) {
                                        (-1 shl (32 - prefixLen))
                                    } else {
                                        -1
                                    }
                                    val localInt = bytesToInt(local.address)
                                    newSubnets.add(SubnetInfo(localInt, mask))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error refreshing network subnets", e)
            }
            cachedSubnets = newSubnets
            lastSubnetCacheTime = System.currentTimeMillis()
            cachedSubnets
        }
    }

    private fun isOnLocalSubnet(remoteAddress: InetAddress): Boolean {
        if (remoteAddress !is Inet4Address) return false
        if (remoteAddress.isLoopbackAddress) return false

        try {
            val remoteInt = bytesToInt(remoteAddress.address)
            val subnets = getOrUpdateSubnets()
            for (subnet in subnets) {
                if ((remoteInt and subnet.mask) == (subnet.localInt and subnet.mask)) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking subnet for ${remoteAddress.hostAddress}", e)
        }
        return false
    }

    private fun bytesToInt(bytes: ByteArray): Int {
        return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun resolveBindAddress(bindAddress: String): InetAddress {
        if (bindAddress.isNotBlank()) {
            return InetAddress.getByName(bindAddress)
        }
        // No specific IP provided — find the active LAN address.
        // InetAddress.getLocalHost() returns 127.0.0.1 on Android, which would
        // make the SSDP socket unreachable from other devices on the network.
        // Prioritize Ethernet (eth*) over WiFi (wlan*) since TVs are often wired,
        // following the same pattern as FileServerService.getDeviceIpAddress().
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return InetAddress.getLocalHost()
            val candidates = mutableListOf<Pair<Int, InetAddress>>()

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()

                // Priority: eth* = 0, wlan* = 1, other = 2
                val priority = when {
                    name.startsWith("eth") -> 0
                    name.startsWith("wlan") -> 1
                    else -> 2
                }

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        candidates.add(priority to addr)
                    }
                }
            }

            candidates.sortBy { it.first }
            val selected = candidates.firstOrNull()?.second
            if (selected != null) {
                Log.i(TAG, "Auto-detected bind address: ${selected.hostAddress}")
                return selected
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate interfaces, falling back to localhost", e)
        }
        return InetAddress.getLocalHost()
    }

    private fun findInterfaceForAddress(addr: InetAddress): NetworkInterface? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ifAddr in iface.interfaceAddresses) {
                    if (ifAddr.address == addr) {
                        return iface
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding network interface for ${addr.hostAddress}", e)
        }
        return null
    }

    private fun cleanupSocketAndLock() {
        try {
            multicastSocket?.let { socket ->
                try {
                    socket.leaveGroup(InetAddress.getByName(SSDP_MULTICAST_ADDRESS))
                } catch (e: Exception) {
                    Log.w(TAG, "Error leaving multicast group", e)
                }
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing multicast socket", e)
        }
        multicastSocket = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "MulticastLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MulticastLock", e)
        }
        multicastLock = null
        cachedSubnets = emptyList()
        lastSubnetCacheTime = 0L
    }
}
