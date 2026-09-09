package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import kotlinx.coroutines.delay

/**
 * Retry policy for user-initiated file transfers (FR-11 and .plans/clarifications.md).
 *
 * Only *transient* connection/session/stream failures are retried. Permanent errors
 * (auth, permission, disk full, source missing, name conflict) fail immediately so a
 * doomed file is never ground through 5 pointless attempts.
 *
 * Classification deliberately avoids importing third-party exception classes (jcifs,
 * sshj, commons-net, cloud SDKs…). Instead it walks the whole cause chain and matches
 * on stable type names and message fragments, so adding/upgrading a client library can
 * never break compilation of this policy.
 *
 * Unknown exceptions default to [RetryClass.TERMINAL] (fail the file, continue the
 * batch) — we prefer a single honest per-file failure over blind retrying.
 */
object TransferRetryPolicy {

    /** Maximum copy attempts per file, including the first. */
    const val MAX_ATTEMPTS = 5

    /** Pause between retry attempts. */
    const val RETRY_DELAY_MS = 5_000L

    /** Whole retry budget for a single file. After this the file is given up. */
    const val BUDGET_MS = 5 * 60_000L

    enum class RetryClass { TRANSIENT, TERMINAL }

    /**
     * Thrown by the retry wrapper once a file exhausts its [MAX_ATTEMPTS] / [BUDGET_MS].
     * Extends [kotlinx.coroutines.CancellationException] so it propagates out of the
     * engine's per-item try/catch and aborts the WHOLE transfer (clarifications Q5) —
     * remaining files are not attempted against an unreachable destination.
     */
    class TransferAbortException(message: String) : kotlinx.coroutines.CancellationException(message)

    // Message fragments that indicate a NON-transient, permanent failure. Checked first
    // so e.g. "connection … permission denied" style wrapped messages never get retried.
    private val TERMINAL_MARKERS = listOf(
        // space
        "no space left on device", "disk full", "insufficient storage", "quota exceeded", "out of space",
        // permission
        "permission denied", "access denied", "operation not permitted", "not permitted", "read-only",
        // auth
        "authentication", "unauthorized", "login failed", "bad password", "wrong password",
        "credentials", "logon failure", "access is denied", "401", "403", "session is not authenticated",
        // source / path missing
        "no such file", "not found", "filenotfound", "does not exist", "path not found", "unknown host",
        // conflict / state
        "file exists", "already exists", "readonly",
        // unsupported / corrupt
        "unsupported", "read-only file system", "invalid argument", "protocol error", "corrupt"
    )

    // Type (simple class) names that reliably indicate a transient network failure.
    private val TRANSIENT_TYPE_NAMES = listOf(
        "SocketTimeoutException", "ConnectException", "SocketException",
        "NoRouteToHostException", "PortUnreachableException", "InterruptedIOException",
        "FTPConnectionClosedException", "TransportException", "ConnectTimeoutException",
        "SslException", "SSLException", "EOFException", "ReadTimeoutException"
    )

    // Message fragments that indicate a transient connection/session/stream failure.
    private val TRANSIENT_MARKERS = listOf(
        "connection reset", "connection refused", "connection timed out", "connect timed out",
        "connection lost", "reset by peer", "broken pipe", "socket", "timed out", "timeout",
        "read timed", "write timed", "econnreset", "econnrefused", "network is unreachable",
        "no route to host", "host unreachable", "unable to connect", "failed to connect",
        "transport", "end of stream", "eof", "peer", "stream closed", "i/o error",
        "io exception", "server error", "bad gateway", "service unavailable",
        "too many requests", "rate limit", "502", "503", "504", "closed by remote", "disconnected"
    )

    /**
     * Returns whether [t] (or any cause in its chain) should be retried.
     * Cancellation exceptions are never classified as retryable.
     */
    fun classify(t: Throwable?): RetryClass {
        if (t is kotlinx.coroutines.CancellationException) return RetryClass.TERMINAL
        if (t is TransferAbortException) return RetryClass.TERMINAL

        val allMessages = mutableListOf<String>()
        val allTypeNames = mutableListOf<String>()
        var cause: Throwable? = t
        while (cause != null) {
            cause.message?.let { allMessages.add(it.lowercase()) }
            allTypeNames.add(cause.javaClass.simpleName)
            cause = cause.cause
        }
        if (allMessages.isEmpty() && allTypeNames.isEmpty()) return RetryClass.TERMINAL

        // 1. Permanent errors win regardless of any transient text higher up the chain.
        for (marker in TERMINAL_MARKERS) {
            if (allMessages.any { it.contains(marker) }) return RetryClass.TERMINAL
        }

        // 2. Transient type/message signatures.
        for (name in TRANSIENT_TYPE_NAMES) {
            if (allTypeNames.any { it.contains(name) }) return RetryClass.TRANSIENT
        }
        for (marker in TRANSIENT_MARKERS) {
            if (allMessages.any { it.contains(marker) }) return RetryClass.TRANSIENT
        }

        // 3. Unknown → terminal (single-file failure, never blind retry).
        return RetryClass.TERMINAL
    }

    /**
     * Blocks until the device reports connectivity (default network present / active),
     * or until [budgetMs] elapses. Returns true when connected, false on timeout.
     * Idempotent per attempt; the retry pacing loop in TransferManager owns the budget.
     */
    suspend fun waitForConnectivity(context: Context, budgetMs: Long = BUDGET_MS): Boolean {
        if (hasConnectivity(context)) return true
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            delay(500L)
            if (hasConnectivity(context)) return true
        }
        return hasConnectivity(context)
    }

    /** One-shot connectivity probe, safe on all supported API levels. */
    fun hasConnectivity(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true // no connectivity service → assume reachable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.activeNetwork != null
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Exception) {
            true // probing failed → let the attempt run; the copy itself will surface the truth
        }
    }
}
