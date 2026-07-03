package za.kilowatch.ultimatefilemanager.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Pre-mount network diagnostics for NFS servers.
 *
 * Performs a DNS resolution check only. TCP port probes are intentionally
 * omitted — the real RPC handshake serves as the definitive connectivity
 * check (see FR-01).
 */
data class NfsDiagnosticsResult(
    /** Whether hostname resolution succeeded. */
    val hostResolved: Boolean,
    /** The resolved IP address string, if resolution succeeded. */
    val resolvedAddress: String?,
    /** Duration of DNS resolution in milliseconds. */
    val resolveTimeMs: Long
)

object NfsDiagnostics {

    /**
     * Run DNS resolution check for the given host.
     *
     * @param host The hostname or IP address of the NFS server.
     * @return A [NfsDiagnosticsResult] with DNS outcome and timing.
     */
    suspend fun runDiagnostics(host: String): NfsDiagnosticsResult = withContext(Dispatchers.IO) {
        val dnsStart = System.currentTimeMillis()
        val (resolved, address) = try {
            val addr = InetAddress.getByName(host)
            true to addr.hostAddress
        } catch (e: UnknownHostException) {
            false to null
        }
        val dnsTime = System.currentTimeMillis() - dnsStart

        NfsDiagnosticsResult(
            hostResolved = resolved,
            resolvedAddress = address,
            resolveTimeMs = dnsTime
        )
    }
}
