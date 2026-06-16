package za.kilowatch.ultimatefilemanager.server

import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Central security enforcement layer for the DLNA feature.
 *
 * Thread-safe singleton that validates paths, URLs, rate-limits requests,
 * caps request sizes, and limits concurrent connections.
 */
object DlnaSecurityFilter {

    private const val TAG = "DlnaSecurityFilter"

    // ---- Request Size Caps ----

    private const val MAX_HTTP_HEADER_SIZE = 8192
    private const val MAX_HTTP_BODY_SIZE = 65536
    private const val MAX_URI_LENGTH = 2048

    // ---- Connection Limiter ----

    private const val MAX_CONCURRENT_CONNECTIONS = 20
    private val activeConnections = AtomicInteger(0)

    // ---- Rate Limiter ----

    private val tokenBuckets = ConcurrentHashMap<String, TokenBucketEntry>()

    private const val RATE_LIMITER_CLEANUP_MILLIS = 5 * 60 * 1000L // 5 minutes

    enum class EndpointType(val maxTokens: Int, val refillRate: Double) {
        SSDP(5, 10.0 / 60.0),           // 10 requests/min, burst of 5
        HTTP_BROWSE(60, 60.0 / 60.0),    // 60 requests/min
        HTTP_STREAM(120, 120.0 / 60.0)   // 120 requests/min
    }

    private class TokenBucketEntry(val bucket: TokenBucket, var lastAccess: Long)

    private class TokenBucket(
        private val maxTokens: Int,
        private val refillRate: Double // tokens per second
    ) {
        private val currentTokens = AtomicInteger(maxTokens)
        private val lastRefill = AtomicLong(System.currentTimeMillis())

        fun tryConsume(): Boolean {
            refill()
            return currentTokens.updateAndGet { if (it > 0) it - 1 else 0 } > 0
        }

        private fun refill() {
            val now = System.currentTimeMillis()
            val last = lastRefill.get()
            val elapsed = now - last
            if (elapsed <= 0) return
            val tokensToAdd = (elapsed / 1000.0) * refillRate
            if (tokensToAdd > 0) {
                // Only one thread wins the CAS to update lastRefill
                if (lastRefill.compareAndSet(last, now)) {
                    currentTokens.updateAndGet { current ->
                        val added = current + tokensToAdd.toInt()
                        if (added > maxTokens) maxTokens else added
                    }
                }
            }
        }
    }

    // =========================================================================
    // 1. Path Validation
    // =========================================================================

    /**
     * Validates that [requestedPath] resolves within at least one of the
     * [allowedRoots] directories.
     *
     * @return The canonical path if valid, or `null` if path traversal is
     *         detected (path escapes outside all allowed roots).
     *         Never confirms file existence.
     */
    fun validatePath(requestedPath: String, allowedRoots: List<String>): String? {
        if (requestedPath.isBlank() || allowedRoots.isEmpty()) return null

        val canonicalRequested: String = try {
            File(requestedPath).canonicalPath
        } catch (e: Exception) {
            Log.w(TAG, "validatePath: failed to resolve requested path", e)
            return null
        }

        for (root in allowedRoots) {
            if (root.isBlank()) continue
            val canonicalRoot: String = try {
                File(root).canonicalPath
            } catch (e: Exception) {
                Log.w(TAG, "validatePath: failed to resolve root path '$root'", e)
                continue
            }

            // Ensure root ends with separator so we don't match partial names
            val rootPrefix = if (canonicalRoot.endsWith(File.separator)) {
                canonicalRoot
            } else {
                canonicalRoot + File.separator
            }

            if (canonicalRequested.startsWith(rootPrefix) || canonicalRequested == canonicalRoot) {
                return canonicalRequested
            }
        }

        Log.w(TAG, "validatePath: path traversal blocked — requested='$requestedPath' " +
                "resolved='$canonicalRequested' not within allowed roots")
        return null
    }

    // =========================================================================
    // 2. URL Validation
    // =========================================================================

    /**
     * Validates that [url] resolves to a permitted private/link-local IP range.
     *
     * Allowed:
     * - RFC 1918: 192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12
     * - Link-local: 169.254.0.0/16
     * - IPv6 link-local (fe80::/10) and ULA (fc00::/7)
     *
     * Blocked:
     * - Public IPs, localhost (127.0.0.0/8), 0.0.0.0, ::1, global unicast IPv6
     */
    fun validateUrl(url: String): Boolean {
        if (url.isBlank()) return false

        val host: String = try {
            URI(url).host ?: return false
        } catch (e: Exception) {
            Log.w(TAG, "validateUrl: failed to parse URL", e)
            return false
        }

        if (host.isBlank()) return false

        val addresses: Array<InetAddress> = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            Log.w(TAG, "validateUrl: DNS resolution failed for host '$host'", e)
            return false
        }

        if (addresses.isEmpty()) return false

        for (addr in addresses) {
            if (!isAddressAllowed(addr)) {
                Log.w(TAG, "validateUrl: blocked address ${addr.hostAddress} for host '$host'")
                return false
            }
        }

        return true
    }

    /**
     * Returns true if [addr] is in a permitted private/link-local range.
     */
    private fun isAddressAllowed(addr: InetAddress): Boolean {
        when (addr) {
            is Inet4Address -> {
                if (addr.isLoopbackAddress) return false
                val raw = addr.address
                if (raw.size != 4) return false
                val firstByte = raw[0].toInt() and 0xFF

                // 10.0.0.0/8
                if (firstByte == 10) return true
                // 192.168.0.0/16
                if (firstByte == 192 && (raw[1].toInt() and 0xFF) == 168) return true
                // 172.16.0.0/12
                if (firstByte == 172 && (raw[1].toInt() and 0xFF) in 16..31) return true
                // 169.254.0.0/16 (link-local)
                if (firstByte == 169 && (raw[1].toInt() and 0xFF) == 254) return true
                return false
            }
            is Inet6Address -> {
                if (addr.isLoopbackAddress) return false
                val raw = addr.address
                if (raw.size != 16) return false

                // fe80::/10 (link-local unicast)
                if ((raw[0].toInt() and 0xFF) == 0xFE && (raw[1].toInt() and 0xC0) == 0x80) {
                    return true
                }
                // fc00::/7 (Unique Local Address)
                if ((raw[0].toInt() and 0xFE) == 0xFC) {
                    return true
                }
                // Block global unicast (everything else for IPv6)
                return false
            }
            else -> return false
        }
    }

    // =========================================================================
    // 3. Rate Limiter (Token Bucket per source IP)
    // =========================================================================

    /**
     * Checks whether a request from [sourceIp] for [endpoint] is allowed
     * according to the token bucket rate limit.
     *
     * Thread-safe. Automatically cleans up stale entries.
     */
    fun allowRequest(sourceIp: String, endpoint: EndpointType): Boolean {
        if (sourceIp.isBlank()) return false

        val now = System.currentTimeMillis()
        val key = "$sourceIp:${endpoint.name}"
        val entry = tokenBuckets.computeIfAbsent(key) {
            TokenBucketEntry(TokenBucket(endpoint.maxTokens, endpoint.refillRate), now)
        }

        entry.lastAccess = now

        // Periodically scavenge stale entries (check-lock-check pattern not needed;
        // a rare missed cleanup is harmless)
        if (tokenBuckets.size > 100 && now % 1000 == 0L) {
            cleanupStaleBuckets()
        }

        return entry.bucket.tryConsume()
    }

    /**
     * Removes bucket entries that have seen no activity for [RATE_LIMITER_CLEANUP_MILLIS].
     */
    private fun cleanupStaleBuckets() {
        val now = System.currentTimeMillis()
        val cutoff = now - RATE_LIMITER_CLEANUP_MILLIS
        tokenBuckets.entries.removeIf { it.value.lastAccess < cutoff }
    }

    // =========================================================================
    // 4. Request Size Caps
    // =========================================================================

    /**
     * Validates that [headers] and [body] do not exceed maximum size limits.
     *
     * @return `true` if all size checks pass, `false` if any limit is exceeded.
     */
    fun validateRequestSize(headers: Map<String, String>, body: ByteArray?): Boolean {
        if (headers.isEmpty().not()) {
            var totalHeaderSize = 0
            for ((key, value) in headers) {
                totalHeaderSize += key.length + value.length
                if (totalHeaderSize > MAX_HTTP_HEADER_SIZE) {
                    Log.w(TAG, "validateRequestSize: header size exceeded ($totalHeaderSize > $MAX_HTTP_HEADER_SIZE)")
                    return false
                }
            }
        }

        if (body != null && body.size > MAX_HTTP_BODY_SIZE) {
            Log.w(TAG, "validateRequestSize: body size exceeded (${body.size} > $MAX_HTTP_BODY_SIZE)")
            return false
        }

        return true
    }

    /**
     * Validates that a URI string does not exceed the maximum allowed length.
     */
    fun validateUriLength(uri: String): Boolean {
        if (uri.length > MAX_URI_LENGTH) {
            Log.w(TAG, "validateUriLength: URI too long (${uri.length} > $MAX_URI_LENGTH)")
            return false
        }
        return true
    }

    // =========================================================================
    // 5. Connection Limiter
    // =========================================================================

    /**
     * Attempts to acquire a connection slot.
     *
     * @return `true` if a slot was acquired, `false` if at maximum connections.
     */
    fun tryAcquireConnection(): Boolean {
        val current = activeConnections.get()
        if (current >= MAX_CONCURRENT_CONNECTIONS) {
            Log.w(TAG, "tryAcquireConnection: at max connections ($MAX_CONCURRENT_CONNECTIONS)")
            return false
        }
        if (activeConnections.compareAndSet(current, current + 1)) {
            return true
        }
        // CAS failed — retry once
        val updated = activeConnections.get()
        if (updated >= MAX_CONCURRENT_CONNECTIONS) {
            Log.w(TAG, "tryAcquireConnection: at max connections on retry ($MAX_CONCURRENT_CONNECTIONS)")
            return false
        }
        activeConnections.incrementAndGet()
        return true
    }

    /**
     * Releases a previously acquired connection slot.
     * The counter is floored at 0 to prevent underflow.
     */
    fun releaseConnection() {
        activeConnections.updateAndGet { current ->
            if (current > 0) current - 1 else 0
        }
    }

    /**
     * Returns the number of currently active connections.
     */
    fun activeConnectionCount(): Int = activeConnections.get()
}
