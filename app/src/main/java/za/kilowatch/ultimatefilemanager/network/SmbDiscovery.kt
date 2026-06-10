package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.net.wifi.WifiManager
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Utilities for discovering SMB hosts on the LAN and listing their top-level shares.
 */
object SmbDiscovery {

    private const val SMB_PORT = 445
    private const val PROBE_TIMEOUT_MS = 600
    private const val MAX_THREADS = 32

    // ── Host discovery ─────────────────────────────────────────────────────────

    /**
     * Scans the device's /24 subnet for hosts with TCP-445 open (SMB).
     *
     * @param onHostFound optional callback fired on a probe thread as each host
     *   responds — callers can use this to show progressive results in the UI.
     * @return all responding IPs sorted naturally.
     *
     * Must be called off the main thread.
     */
    fun scanLan(
        context: Context,
        timeoutMs: Int = 3000,
        onHostFound: ((String) -> Unit)? = null
    ): List<String> {
        val subnet = getSubnetPrefix(context) ?: return emptyList()
        val pool = Executors.newFixedThreadPool(MAX_THREADS)
        val found = mutableListOf<String>()
        val lock = Any()
        val futures = (1..254).map { i ->
            val ip = "$subnet.$i"
            pool.submit {
                if (probeTcp(ip, SMB_PORT, PROBE_TIMEOUT_MS)) {
                    synchronized(lock) { found.add(ip) }
                    onHostFound?.invoke(ip)
                }
            }
        }
        // Wait for all probes but respect the overall timeout
        pool.shutdown()
        pool.awaitTermination(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        futures.forEach { runCatching { it.cancel(false) } }

        return found.sortedWith(compareBy { it.split(".").last().toIntOrNull() ?: 0 })
    }

    private fun probeTcp(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) { false }
    }

    /** Derives the 24-bit subnet prefix (e.g. "192.168.1") from the Wi-Fi IP. */
    private fun getSubnetPrefix(context: Context): String? {
        return try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo?.ipAddress ?: return null
            if (ip == 0) return null
            // Android stores IP in little-endian int
            val a = ip and 0xFF
            val b = (ip shr 8) and 0xFF
            val c = (ip shr 16) and 0xFF
            "$a.$b.$c"
        } catch (_: Exception) { null }
    }

    // ── Share enumeration ─────────────────────────────────────────────────────

    /**
     * Lists the top-level shares on an SMB host using jcifs-ng.
     * Filters out admin/system shares (IPC$, ADMIN$, print$, etc.).
     *
     * Must be called off the main thread.
     * Throws if unable to connect.
     */
    fun listShares(
        host: String,
        username: String,
        password: String,
        domain: String
    ): List<String> {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.smb.client.responseTimeout", "8000")
            setProperty("jcifs.smb.client.soTimeout", "10000")
            // jcifs-ng by default enforces IPC signing on the client side, which
            // causes "IPC signing is enforced, but no signing is available" when
            // connecting to open/guest shares on servers that advertise signing.
            // Disabling client-side IPC signing enforcement fixes anonymous enumeration.
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            // Explicitly disable SMB1 fallback — only negotiate SMB2+.
            // If this property is unrecognized (jcifs-ng < 2.1.x) it is silently ignored.
            setProperty("jcifs.smb.client.minVersion", "SMB202")
        }
        val baseCtx: CIFSContext = BaseContext(PropertyConfiguration(props))
        val auth = if (username.isBlank()) {
            baseCtx.withGuestCrendentials()
        } else {
            val domainStr = domain.ifBlank { "WORKGROUP" }
            baseCtx.withCredentials(NtlmPasswordAuthenticator(domainStr, username, password))
        }

        val url = "smb://$host/"
        val smbFile = SmbFile(url, auth)
        return smbFile.listFiles()
            ?.mapNotNull { f ->
                val n = f.name.trimEnd('/')
                // Hide system / admin shares
                if (n.endsWith("$") || n.equals("IPC\$", true) ||
                    n.equals("print\$", true) || n.isBlank()
                ) null
                else n
            }
            ?.sorted()
            ?: emptyList()
    }
}
