package za.kilowatch.ultimatefilemanager.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Pre-mount network diagnostics for NFS servers.
 *
 * Checks DNS resolution and TCP connectivity to ports 2049 (nfsd) and
 * 111 (portmapper/rpcbind) before attempting the actual NFS mount.
 */
data class NfsDiagnosticsResult(
    /** Whether hostname resolution succeeded. */
    val hostResolved: Boolean,
    /** The resolved IP address string, if resolution succeeded. */
    val resolvedAddress: String?,
    /** Duration of DNS resolution in milliseconds. */
    val resolveTimeMs: Long,
    /** Whether TCP connect to port 2049 succeeded. null if DNS failed. */
    val port2049Reachable: Boolean?,
    /** Duration of TCP connect to port 2049 in milliseconds. */
    val port2049TimeMs: Long?,
    /** Whether TCP connect to port 111 succeeded. null if DNS failed. */
    val port111Reachable: Boolean?,
    /** Duration of TCP connect to port 111 in milliseconds. */
    val port111TimeMs: Long?
)

object NfsDiagnostics {

    /**
     * Run all pre-mount diagnostics checks for the given host.
     *
     * @param host The hostname or IP address of the NFS server.
     * @return A [NfsDiagnosticsResult] with per-check outcomes and timing.
     */
    suspend fun runDiagnostics(host: String): NfsDiagnosticsResult = withContext(Dispatchers.IO) {
        // 1. DNS resolution
        val dnsStart = System.currentTimeMillis()
        val (resolved, address) = try {
            val addr = InetAddress.getByName(host)
            true to addr.hostAddress
        } catch (e: UnknownHostException) {
            false to null
        }
        val dnsTime = System.currentTimeMillis() - dnsStart

        // 2. TCP connect to NFS port (2049)
        val (nfsOk, nfsTime) = if (resolved) {
            tcpConnect(host, 2049)
        } else {
            null to null
        }

        // 3. TCP connect to Portmapper port (111)
        val (pmOk, pmTime) = if (resolved) {
            tcpConnect(host, 111)
        } else {
            null to null
        }

        NfsDiagnosticsResult(
            hostResolved = resolved,
            resolvedAddress = address,
            resolveTimeMs = dnsTime,
            port2049Reachable = nfsOk,
            port2049TimeMs = nfsTime,
            port111Reachable = pmOk,
            port111TimeMs = pmTime
        )
    }

    /**
     * Attempt a TCP connection to the given port with a 2-second timeout.
     *
     * @return Pair of (success, durationMs). On failure, success is false and duration is null.
     */
    private fun tcpConnect(host: String, port: Int): Pair<Boolean?, Long?> {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), 2000)
                true to (System.currentTimeMillis() - start)
            }
        } catch (e: Exception) {
            false to null
        }
    }
}
