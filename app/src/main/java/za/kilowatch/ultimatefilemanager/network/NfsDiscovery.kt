package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Utilities for discovering NFS servers on the LAN and listing their exports.
 *
 * Uses two complementary methods:
 * 1. RPC broadcast ([LibNfsBridge.nfsFindLocalServers]) — finds NFS servers
 *    on any reachable subnet, including servers on different subnets than the
 *    device's Wi-Fi (e.g. WSL2, separate VLANs).
 * 2. TCP port-2049 subnet scan — probes the device's active network for
 *    responsive NFS servers, then queries their exports via the MOUNT protocol.
 *
 * Follows the same pattern as [SmbDiscovery].
 */
object NfsDiscovery {

    private const val TAG = "NfsDiscovery"
    private const val NFS_PORT = 2049
    private const val PROBE_TIMEOUT_MS = 600
    private const val MAX_THREADS = 32

    // ── Host discovery ─────────────────────────────────────────────────────────

    /**
     * Discover NFS servers on the local network.
     *
     * **Phase 1** — RPC broadcast via [LibNfsBridge.nfsFindLocalServers].
     * This sends an RPC broadcast to the network and collects responding
     * NFS servers regardless of subnet. Fast (~1 second).
     *
     * **Phase 2** — TCP port-2049 subnet scan. Probes all /24 IPs on the
     * active network interface for responsive NFS servers. Catches servers
     * that don't respond to RPC broadcast.
     *
     * Results from both phases are merged and deduplicated by IP. Servers
     * discovered via broadcast are immediately queried for exports; subnet
     * scan results are queried progressively.
     *
     * Pass [cancelled] as a lambda that returns `true` to stop the scan
     * early. The lambda is polled between phases and inside probe loops.
     *
     * Must be called off the main thread.
     */
    fun scanLan(
        context: Context,
        onServerFound: ((NfsDiscoveredServer) -> Unit)? = null,
        cancelled: () -> Boolean = { false }
    ): List<NfsDiscoveredServer> {
        val seenIps = mutableSetOf<String>()
        val found = mutableListOf<NfsDiscoveredServer>()
        val lock = Any()
        val startTimeMs = System.currentTimeMillis()

        Log.i(TAG, "=== NFS Discovery START ===")

        fun addServer(server: NfsDiscoveredServer) {
            synchronized(lock) {
                if (seenIps.add(server.ip)) {
                    found.add(server)
                    Log.i(TAG, "DISCOVERED server: ${server.ip}  exports=${server.exports.size}  error=${server.exportsError}")
                    onServerFound?.invoke(server)
                } else {
                    Log.d(TAG, "Skipping duplicate server: ${server.ip}")
                }
            }
        }

        // ── Phase 1: RPC broadcast discovery ───────────────────────────────
        if (!cancelled()) {
            Log.i(TAG, "Phase 1: RPC broadcast discovery via nfsFindLocalServers()")
            val p1Start = System.currentTimeMillis()
            try {
                val broadcastServers = LibNfsBridge.nfsFindLocalServers()
                val p1Elapsed = System.currentTimeMillis() - p1Start
                if (broadcastServers != null) {
                    Log.i(TAG, "Phase 1: ${broadcastServers.size} server(s) in ${p1Elapsed}ms")
                    for (ip in broadcastServers) {
                        Log.i(TAG, "Phase 1: server=$ip")
                        if (cancelled()) {
                            Log.w(TAG, "Phase 1 cancelled mid-iteration")
                            break
                        }
                        val (exports, error) = queryExports(ip)
                        val server = if (error != null) {
                            Log.w(TAG, "Phase 1: exports failed for $ip: $error")
                            NfsDiscoveredServer(ip = ip, exports = emptyList(), exportsError = error)
                        } else {
                            Log.i(TAG, "Phase 1: $ip exports: ${exports.joinToString(", ")}")
                            NfsDiscoveredServer(ip = ip, exports = exports)
                        }
                        addServer(server)
                    }
                } else {
                    Log.w(TAG, "Phase 1: nfsFindLocalServers returned null after ${p1Elapsed}ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Phase 1: EXCEPTION: ${e::class.simpleName}: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "Phase 1: SKIPPED (cancelled)")
        }

        // ── Phase 2: Active subnet TCP scan ─────────────────────────────────
        if (!cancelled()) {
            Log.i(TAG, "Phase 2: resolving active subnet...")
            val subnetStart = System.currentTimeMillis()
            val subnet = getActiveSubnetPrefix(context)
            Log.i(TAG, "Phase 2: subnet=\"$subnet\" resolved in ${System.currentTimeMillis() - subnetStart}ms")
            if (subnet != null) {
                Log.i(TAG, "Phase 2: TCP scan on $subnet.0/24  (254 hosts, $MAX_THREADS threads, ${PROBE_TIMEOUT_MS}ms timeout)")
                tcpSubnetScan(subnet, cancelled, ::addServer)
            } else {
                Log.e(TAG, "Phase 2: could not determine active subnet — no TCP scan")
            }
        } else {
            Log.w(TAG, "Phase 2: SKIPPED (cancelled)")
        }

        val totalTime = System.currentTimeMillis() - startTimeMs
        Log.i(TAG, "=== NFS Discovery END: ${found.size} server(s) in ${totalTime}ms ===")
        for (srv in found) {
            Log.i(TAG, "  Result: ${srv.ip}  exports=${srv.exports.size}  error=${srv.exportsError}")
        }
        if (found.isEmpty()) {
            Log.w(TAG, "  No NFS servers discovered. Possible causes:")
            Log.w(TAG, "    - NFS service not running on target server")
            Log.w(TAG, "    - Firewall blocking port 2049 or 111")
            Log.w(TAG, "    - Android device on different subnet than NFS server")
            Log.w(TAG, "    - WifiManager returned wrong IP / ConnectivityManager failed")
        }
        return found.sortedWith(compareBy { it.ip.split(".").last().toIntOrNull() ?: 0 })
    }

    /**
     * Probe all 254 hosts in a /24 subnet for TCP-2049.
     */
    private fun tcpSubnetScan(
        subnet: String,
        cancelled: () -> Boolean,
        onServerFound: (NfsDiscoveredServer) -> Unit
    ) {
        val pool = Executors.newFixedThreadPool(MAX_THREADS)
        val wasCancelled = AtomicBoolean(false)
        val probesAttempted = AtomicInteger(0)
        val probesConnected = AtomicInteger(0)
        val tcpStart = System.currentTimeMillis()

        val futures = (1..254).map { i ->
            if (cancelled()) {
                Log.w(TAG, "tcpSubnetScan: cancelled before submitting host $i")
                wasCancelled.set(true)
                return@map null
            }
            val ip = "$subnet.$i"
            pool.submit {
                if (cancelled()) return@submit
                val probeStart = System.currentTimeMillis()
                val connected = probeTcp(ip, NFS_PORT, PROBE_TIMEOUT_MS)
                val probeTime = System.currentTimeMillis() - probeStart
                probesAttempted.incrementAndGet()
                if (connected) {
                    probesConnected.incrementAndGet()
                    Log.i(TAG, "TCP PROBE OK: $ip:$NFS_PORT (${probeTime}ms, host #$i)")
                    if (cancelled()) return@submit
                    val (exports, error) = queryExports(ip)
                    val server = if (error != null) {
                        Log.w(TAG, "TCP probe $ip: exports query failed: $error")
                        NfsDiscoveredServer(ip = ip, exports = emptyList(), exportsError = error)
                    } else {
                        Log.i(TAG, "TCP probe $ip exports: ${exports.joinToString(", ")}")
                        NfsDiscoveredServer(ip = ip, exports = exports)
                    }
                    onServerFound(server)
                } else {
                    if (probeTime >= PROBE_TIMEOUT_MS) {
                        Log.v(TAG, "TCP probe TIMEOUT: $ip:$NFS_PORT (${probeTime}ms)")
                    }
                }
                val attempted = probesAttempted.get()
                if (attempted % 50 == 0 || attempted == 254) {
                    val elapsed = System.currentTimeMillis() - tcpStart
                    Log.d(TAG, "TCP scan progress: $attempted/254 probed, ${probesConnected.get()} connected (${elapsed}ms)")
                }
            }
        }

        if (wasCancelled.get()) {
            Log.w(TAG, "tcpSubnetScan: cancelled — shutting down")
            pool.shutdownNow()
        } else {
            pool.shutdown()
        }
        runCatching { pool.awaitTermination(30, TimeUnit.SECONDS) }
        futures.forEach { f -> f?.let { runCatching { it.cancel(false) } } }

        val total = System.currentTimeMillis() - tcpStart
        Log.i(TAG, "tcpSubnetScan: ${probesAttempted.get()}/254 probed, ${probesConnected.get()} connected in ${total}ms")
    }

    // ── Subnet detection ───────────────────────────────────────────────────────

    /**
     * Derives the /24 subnet prefix from the **active** network interface.
     *
     * Fallback chain:
     * 1. ConnectivityManager → LinkProperties routes (active network).
     * 2. NetworkInterface enumeration (any non-loopback, up, IPv4).
     * 3. WifiManager.getConnectionInfo() (legacy fallback).
     */
    private fun getActiveSubnetPrefix(context: Context): String? {
        Log.d(TAG, "getActiveSubnetPrefix: starting detection...")

        // ── Attempt 1: ConnectivityManager ──────────────────────────────────
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                Log.w(TAG, "getActiveSubnetPrefix: ConnectivityManager not available")
            } else {
                val activeNetwork = cm.activeNetwork
                Log.d(TAG, "getActiveSubnetPrefix: activeNetwork=$activeNetwork")
                if (activeNetwork != null) {
                    val lp = cm.getLinkProperties(activeNetwork)
                    Log.d(TAG, "getActiveSubnetPrefix: linkProperties=$lp")
                    if (lp != null) {
                        val routes = lp.routes
                        Log.d(TAG, "getActiveSubnetPrefix: route count=${routes.size}")
                        for (r in routes) {
                            val dest = r.destination
                            Log.d(TAG, "  route: isDefault=${r.isDefaultRoute} dest=${dest} addr=${dest?.address}")
                            if (!r.isDefaultRoute && dest?.address is Inet4Address) {
                                val addr = dest.address as Inet4Address
                                val parts = addr.hostAddress?.split(".")
                                if (parts != null && parts.size == 4) {
                                    val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                                    Log.i(TAG, "getActiveSubnetPrefix: from ConnectivityManager → $prefix")
                                    return prefix
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getActiveSubnetPrefix: ConnectivityManager failed: ${e::class.simpleName}: ${e.message}")
        }

        // ── Attempt 2: NetworkInterface enumeration ─────────────────────────
        try {
            Log.d(TAG, "getActiveSubnetPrefix: trying NetworkInterface...")
            val ifaces = NetworkInterface.getNetworkInterfaces()
            if (ifaces == null) {
                Log.w(TAG, "getActiveSubnetPrefix: getNetworkInterfaces() returned null")
            } else {
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    Log.d(TAG, "  iface: ${iface.name} loopback=${iface.isLoopback} up=${iface.isUp}")
                    if (iface.isLoopback || !iface.isUp) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        Log.d(TAG, "    addr: ${addr.hostAddress} (${addr::class.simpleName})")
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val parts = addr.hostAddress?.split(".")
                            if (parts != null && parts.size == 4) {
                                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                                Log.i(TAG, "getActiveSubnetPrefix: from ${iface.name} → $prefix (IP=${addr.hostAddress})")
                                return prefix
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getActiveSubnetPrefix: NetworkInterface failed: ${e::class.simpleName}: ${e.message}")
        }

        // ── Attempt 3: WifiManager fallback ─────────────────────────────────
        Log.d(TAG, "getActiveSubnetPrefix: falling back to WifiManager...")
        val wifiResult = wifiFallback(context)
        Log.i(TAG, "getActiveSubnetPrefix: WifiManager subnet=\"$wifiResult\"")
        return wifiResult
    }

    /**
     * Legacy fallback: derive subnet from Wi-Fi IP via [WifiManager].
     */
    private fun wifiFallback(context: Context): String? {
        return try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm == null) {
                Log.w(TAG, "wifiFallback: WifiManager not available")
                return null
            }
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (info == null) {
                Log.w(TAG, "wifiFallback: connectionInfo is null (not connected to Wi-Fi?)")
                return null
            }
            @Suppress("DEPRECATION")
            val ip = info.ipAddress
            Log.d(TAG, "wifiFallback: raw ipAddress int = $ip")
            if (ip == 0) {
                Log.w(TAG, "wifiFallback: ipAddress is 0 (no Wi-Fi)")
                return null
            }
            val a = ip and 0xFF
            val b = (ip shr 8) and 0xFF
            val c = (ip shr 16) and 0xFF
            val d = (ip shr 24) and 0xFF
            val fullIp = "$a.$b.$c.$d"
            val prefix = "$a.$b.$c"
            Log.i(TAG, "wifiFallback: device IP=$fullIp → subnet=$prefix")
            return prefix
        } catch (e: Exception) {
            Log.e(TAG, "wifiFallback failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    // ── TCP probe ──────────────────────────────────────────────────────────────

    /**
     * Quick TCP connect to [host]:[port] with [timeoutMs] timeout.
     */
    private fun probeTcp(host: String, port: Int, timeoutMs: Int): Boolean {
        val start = System.nanoTime()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                val elapsedUs = (System.nanoTime() - start) / 1000L
                Log.d(TAG, "probeTcp OK: $host:$port (${elapsedUs}µs)")
                true
            }
        } catch (e: java.net.ConnectException) {
            Log.v(TAG, "probeTcp REFUSED: $host:$port — ${e.message}")
            false
        } catch (e: java.net.SocketTimeoutException) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            Log.v(TAG, "probeTcp TIMEOUT: $host:$port (${elapsedMs}ms)")
            false
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            Log.v(TAG, "probeTcp FAILED: $host:$port (${elapsedMs}ms) — ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    // ── Export listing ─────────────────────────────────────────────────────────

    /**
     * Query NFS exports on [host] via the MOUNT protocol.
     *
     * **Fast-fail portmapper check**: Before calling [LibNfsBridge.nfsListExports],
     * a quick 300 ms TCP probe of port 111 is performed. If portmapper is
     * unreachable (firewall, rpcbind not running, WSL2, etc.) we fall through
     * to [probeNfsV4Exports] instead of blocking for the full
     * `mount_getexports()` RPC timeout (~30 s).
     *
     * When portmapper IS reachable, [LibNfsBridge.nfsListExports] is submitted
     * with a 5 s safety timeout in case the RPC call hangs for another reason.
     */
    private fun queryExports(host: String): Pair<List<String>, String?> {
        // ── Step 1: fast-fail port-111 check ────────────────────────────────
        Log.d(TAG, "queryExports($host): probing port 111 (portmapper) with 300ms timeout...")
        if (!probeTcp(host, 111, 300)) {
            Log.w(TAG, "queryExports($host): port 111 unreachable — falling back to NFSv4 direct listing")
            return probeNfsV4Exports(host)
        }

        // ── Step 2: query exports via MOUNT protocol ─────────────────────────
        Log.d(TAG, "queryExports($host): port 111 open, submitting nfsListExports with 5s timeout...")
        val start = System.currentTimeMillis()
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit(Callable {
                LibNfsBridge.nfsListExports(host)
            })
            val exports = try {
                future.get(5, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                val elapsed = System.currentTimeMillis() - start
                Log.w(TAG, "queryExports($host): nfsListExports TIMEOUT after ${elapsed}ms — trying NFSv4 direct listing")
                // Port 111 is reachable but the portmapper RPC hangs (common on
                // WSL2 portproxy, Windows NFS, and some NAS devices). Fall through
                // to NFSv4 direct listing which bypasses portmapper entirely.
                return probeNfsV4Exports(host)
            }
            val elapsed = System.currentTimeMillis() - start
            if (exports.isNullOrEmpty()) {
                Log.w(TAG, "queryExports($host): null/empty in ${elapsed}ms")
                Pair(emptyList(), null)
            } else {
                Log.i(TAG, "queryExports($host): ${exports.size} export(s) in ${elapsed}ms: ${exports.joinToString(", ")}")
                Pair(exports.toList(), null)
            }
        } catch (e: ExecutionException) {
            val elapsed = System.currentTimeMillis() - start
            Log.e(TAG, "queryExports($host): EXCEPTION after ${elapsed}ms: ${e.cause?.let { "${it::class.simpleName}: ${it.message}" } ?: e.message}")
            Pair(emptyList(), e.cause?.message ?: e.message ?: "Unknown error")
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            Log.e(TAG, "queryExports($host): EXCEPTION after ${elapsed}ms: ${e::class.simpleName}: ${e.message}")
            Pair(emptyList(), e.message ?: "Unknown error")
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * NFSv4 direct-listing fallback used when portmapper (port 111) is blocked.
     *
     * NFSv4 communicates exclusively over port 2049 and requires no portmapper.
     * This function mounts the server's root via NFSv4 and lists the top-level
     * directories, which are returned as selectable export paths — equivalent
     * to what `mount_getexports()` would have returned on an NFSv3 server.
     *
     * A 4 s Java [java.util.concurrent.Future] provides the hard cutoff, since
     * libnfs enforces a minimum `timeo` of 100 (= 10 s) and cannot be
     * interrupted from the JVM side once a native call has started.
     *
     * Returns:
     * - `Pair(dirs, null)` — NFSv4 succeeded; [dirs] is a sorted list of
     *   top-level paths the user can choose as an export (e.g. `["/data", "/media"]`).
     *   Falls back to `["/"]` if the root directory is empty.
     * - `Pair(emptyList, message)` — NFSv4 is unavailable or timed out.
     */
    private fun probeNfsV4Exports(host: String): Pair<List<String>, String?> {
        Log.d(TAG, "probeNfsV4Exports($host): attempting NFSv4 direct mount on port 2049 with minor version cascade...")
        // Try NFSv4.2 → 4.1 → 4.0 to handle servers that disable specific minor versions
        for (minor in intArrayOf(2, 1, 0)) {
            val result = tryNfsV4Mount(host, minor)
            if (result.first.isNotEmpty() || result.second == null) {
                return result
            }
            Log.d(TAG, "probeNfsV4Exports($host): NFSv4.$minor failed, trying next minor version")
        }
        return Pair(emptyList(), "Portmapper (port 111) unreachable — enter share path manually")
    }

    /**
     * Attempt a single NFSv4 mount with a specific minor version for export discovery.
     */
    private fun tryNfsV4Mount(host: String, minorVersion: Int): Pair<List<String>, String?> {
        val executor = Executors.newSingleThreadExecutor()
        val start = System.currentTimeMillis()
        return try {
            val future = executor.submit(Callable<Pair<List<String>, String?>> {
                val handle = LibNfsBridge.nfsInit()
                if (handle == 0L) {
                    Log.w(TAG, "tryNfsV4Mount($host, v4.$minorVersion): nfsInit returned 0")
                    return@Callable Pair(emptyList(), "NFSv4: failed to init context")
                }
                try {
                    // version=4  → NFSv4, no MOUNT protocol / portmapper needed
                    // minor=X    → NFSv4 minor version (0=v4.0, 1=v4.1, 2=v4.2)
                    // timeo=100  → libnfs minimum (10 s); Java Future is our real cutoff
                    // NOTE: do NOT add nfsport=2049 — testing confirmed the working URL
                    // is nfs://host/?version=4&timeo=100 without explicit nfsport.
                    // Adding nfsport changes libnfs internal routing and causes timeouts
                    // in portproxy/WSL2 environments.
                    val minorParam = if (minorVersion > 0) "&minor=$minorVersion" else ""
                    val url = "nfs://$host/?version=4${minorParam}&timeo=100"
                    Log.d(TAG, "tryNfsV4Mount($host, v4.$minorVersion): nfsMountUrl($url)")
                    val mountErr = LibNfsBridge.nfsMountUrl(handle, url)
                    if (mountErr != null) {
                        Log.d(TAG, "tryNfsV4Mount($host, v4.$minorVersion): NFSv4 mount failed: $mountErr")
                        return@Callable Pair(
                            emptyList(),
                            mountErr
                        )
                    }
                    Log.i(TAG, "tryNfsV4Mount($host, v4.$minorVersion): NFSv4 mount OK — listing root dirs...")

                    // nfsListDir returns "name\ttype\tsize\tmtime" tab-separated strings
                    val entries = LibNfsBridge.nfsListDir(handle, "/")
                    val dirs = entries
                        ?.mapNotNull { raw ->
                            val parts = raw.split("\t")
                            val name = parts.getOrElse(0) { "" }
                            val type = parts.getOrElse(1) { "f" }
                            if (type == "d" && name.isNotEmpty()) "/$name" else null
                        }
                        ?.sorted()
                        ?: emptyList()

                    Log.i(TAG, "tryNfsV4Mount($host, v4.$minorVersion): ${dirs.size} dir(s) found: ${dirs.joinToString(", ")}")
                    if (dirs.isEmpty()) Pair(listOf("/"), null) else Pair(dirs, null)
                } finally {
                    LibNfsBridge.nfsDestroy(handle)
                }
            })

            try {
                val result = future.get(15, TimeUnit.SECONDS)
                val elapsed = System.currentTimeMillis() - start
                Log.d(TAG, "tryNfsV4Mount($host, v4.$minorVersion): completed in ${elapsed}ms")
                result
            } catch (e: TimeoutException) {
                future.cancel(true)
                val elapsed = System.currentTimeMillis() - start
                Log.w(TAG, "tryNfsV4Mount($host, v4.$minorVersion): NFSv4 probe TIMEOUT after ${elapsed}ms")
                Pair(emptyList(), "Portmapper (port 111) unreachable — enter share path manually")
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
