package za.kilowatch.ultimatefilemanager.network

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a single stage in the NFS mount/connection process.
 * Used for structured debug logging and diagnostics display.
 */
data class RpcStage(
    /** Human-readable stage name, e.g. "DNS resolution", "TCP connect port 2049". */
    val name: String,
    /** Whether this stage completed successfully. */
    val success: Boolean,
    /** Optional detail message (error description, entry count, etc.). */
    val detail: String?,
    /** Duration of this stage in milliseconds. */
    val durationMs: Long
)

/**
 * Structured debug entry for a single NFS mount attempt.
 */
data class NfsDebugEntry(
    /** Timestamp of the mount attempt. */
    val timestamp: Long,
    /** Server hostname or IP. */
    val host: String,
    /** Remote export path. */
    val path: String,
    /** TCP port used. */
    val port: Int,
    /** NFS protocol version attempted (3 or 4). */
    val versionAttempted: Int,
    /** NFSv4 minor version attempted (0=v4.0, 1=v4.1, 2=v4.2). */
    val nfsV4MinorVersion: Int = 0,
    /** RPC auth flavor used (1 = AUTH_SYS). */
    val authFlavor: Int,
    /** Ordered list of RPC/connection stages. */
    val stages: List<RpcStage>,
    /** Final error sentinel or raw message; null if successful. */
    val finalError: String?,
    /** Total duration of the mount attempt in milliseconds. */
    val durationMs: Long
)

/**
 * In-memory ring buffer of recent NFS mount debug entries.
 *
 * Keeps the last [MAX_ENTRIES] entries for diagnostic export.
 * Used by [LibNfsClient] and surfaced in the UI via [exportSummary].
 */
object NfsDebugLogger {

    private val ring = mutableListOf<NfsDebugEntry>()
    private const val MAX_ENTRIES = 20

    /**
     * Record a new debug entry. Oldest entries are evicted when the ring
     * exceeds [MAX_ENTRIES].
     */
    @Synchronized
    fun record(entry: NfsDebugEntry) {
        ring.add(entry)
        if (ring.size > MAX_ENTRIES) {
            ring.removeAt(0)
        }
    }

    /**
     * Return the most recent debug entry, or null if none.
     */
    @Synchronized
    fun lastEntry(): NfsDebugEntry? = ring.lastOrNull()

    /**
     * Export the last entry as a human-readable summary string.
     *
     * Example output:
     * ```
     * NFS Mount Attempt — 2026-07-01 14:30:22
     * Server: nas.local (port 2049, v3)
     * Path: /srv/nfs/share
     * Stages:
     *   ✅ NFS init + mount (214ms): NFS server rejected authentication
     * Result: NFS_AUTH_REJECTED
     * Duration: 312ms
     * ```
     */
    @Synchronized
    fun exportSummary(): String {
        val entry = ring.lastOrNull() ?: return "No NFS debug log available."
        val sb = StringBuilder()
        sb.appendLine("NFS Mount Attempt — ${formatTimestamp(entry.timestamp)}")
        val versionStr = if (entry.versionAttempted == 4)
            "v4.${entry.nfsV4MinorVersion}" else "v${entry.versionAttempted}"
        sb.appendLine("Server: ${entry.host} (port ${entry.port}, $versionStr)")
        sb.appendLine("Path: ${entry.path}")
        sb.appendLine("Stages:")
        for (stage in entry.stages) {
            val icon = if (stage.success) "✅" else "❌"
            sb.append("  $icon ${stage.name} (${stage.durationMs}ms)")
            if (stage.detail != null) sb.append(": ${stage.detail}")
            sb.appendLine()
        }
        sb.appendLine("Result: ${entry.finalError ?: "SUCCESS"}")
        sb.appendLine("Duration: ${entry.durationMs}ms")
        return sb.toString()
    }

    private fun formatTimestamp(ts: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(ts))
    }
}
