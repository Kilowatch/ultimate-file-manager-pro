package za.kilowatch.ultimatefilemanager.network

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Singleton SMB session pool.
 *
 * Keeps one [Session] alive per share credential key so sequential operations
 * (list → delete → rename …) reuse the same authenticated session instead of
 * performing a fresh NTLM handshake on every call. This is the primary fix for
 * the session flood and improves performance significantly.
 *
 * ## Pool key
 * `host:port:username:domain` — password is intentionally excluded from the key;
 * a changed password triggers a fresh [Session].
 *
 * ## Dedicated operations
 * [borrow] with `dedicated = true` always creates a fresh [Connection] + [Session]
 * that is never returned to the pool. This is used by streaming operations so
 * long-running data transfers do not block the pooled session.
 *
 * ## Idle eviction
 * A background daemon thread closes sessions idle for ≥ [IDLE_TIMEOUT_MS].
 */
object SmbSessionPool {

    /** Idle sessions are closed after 5 minutes of inactivity. */
    private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

    private class PoolEntry {
        var client:         SMBClient?  = null
        var connection:     Connection? = null
        var session:        Session?    = null
        var useCount:       Int         = 0
        var lastReleasedMs: Long        = 0L
    }

    private data class PoolKey(
        val host:     String,
        val port:     Int,
        val username: String,
        val domain:   String,
        val protocol: String
    )

    private val pool = ConcurrentHashMap<PoolKey, PoolEntry>()

    private val evictor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "smb-pool-evictor").also { it.isDaemon = true }
    }

    init {
        evictor.scheduleAtFixedRate(::evictIdle, 60, 60, TimeUnit.SECONDS)
    }

    /**
     * Borrow an authenticated [PooledConnection] (wrapping a [Session]).
     */
    fun borrow(
        share: NetworkShare,
        auth: AuthenticationContext,
        dedicated: Boolean = false,
        forWrite: Boolean = false
    ): PooledConnection {
        if (dedicated) {
            val client  = SMBClient(buildConfig(share.smbProtocol, forWrite, dedicated = true))
            val conn    = client.connect(share.host, share.effectivePort)
            val session = conn.authenticate(auth)
            return PooledConnection(
                session     = session,
                onRelease   = { closeQuietly(client, conn, session) },
                onInvalidate = { closeQuietly(client, conn, session) }
            )
        }

        val key   = poolKey(share)
        val entry = pool.getOrPut(key) { PoolEntry() }

        synchronized(entry) {
            val existing = entry.session
            val idleMs   = if (entry.lastReleasedMs > 0) System.currentTimeMillis() - entry.lastReleasedMs else 0L
            val isStale  = idleMs > 2_000L // SMB servers often drop idle sessions after 3-5s

            if (existing != null && !isStale && isAlive(existing)) {
                entry.useCount++
                return PooledConnection(
                    session      = existing,
                    onRelease    = {
                        synchronized(entry) {
                            entry.useCount = maxOf(0, entry.useCount - 1)
                            entry.lastReleasedMs = System.currentTimeMillis()
                        }
                    },
                    onInvalidate = {
                        synchronized(entry) {
                            closeEntryQuietly(entry)
                            entry.useCount       = 0
                            entry.lastReleasedMs = 0L
                        }
                    }
                )
            }

            // Fresh connection needed. If the slot is empty or the existing connection
            // is dead/stale, we open a new one. Stale entries with useCount=0 are closed.
            if (entry.useCount == 0) {
                closeEntryQuietly(entry)
            }
        }

        // Perform blocking network TCP socket connect and authentication OUTSIDE synchronized block
        // to prevent holding monitor locks during socket connection & NTLM handshake timeouts.
        val newClient  = SMBClient(buildConfig(share.smbProtocol, forWrite, dedicated = false))
        var newConn: Connection? = null
        var newSession: Session? = null
        try {
            val conn = newClient.connect(share.host, share.effectivePort)
            newConn = conn
            val session = conn.authenticate(auth)
            newSession = session

            synchronized(entry) {
                val existing = entry.session
                if (existing != null && isAlive(existing)) {
                    // Another thread established a session while we were connecting; reuse it
                    closeQuietly(newClient, newConn, newSession)
                    entry.useCount++
                    return PooledConnection(
                        session      = existing,
                        onRelease    = {
                            synchronized(entry) {
                                entry.useCount = maxOf(0, entry.useCount - 1)
                                entry.lastReleasedMs = System.currentTimeMillis()
                            }
                        },
                        onInvalidate = {
                            synchronized(entry) {
                                closeEntryQuietly(entry)
                                entry.useCount       = 0
                                entry.lastReleasedMs = 0L
                            }
                        }
                    )
                }

                entry.client     = newClient
                entry.connection = newConn
                entry.session    = newSession
                entry.useCount   = 1
                entry.lastReleasedMs = System.currentTimeMillis()

                return PooledConnection(
                    session      = session,
                    onRelease    = {
                        synchronized(entry) {
                            entry.useCount = maxOf(0, entry.useCount - 1)
                            entry.lastReleasedMs = System.currentTimeMillis()
                        }
                    },
                    onInvalidate = {
                        synchronized(entry) {
                            closeEntryQuietly(entry)
                            entry.useCount       = 0
                            entry.lastReleasedMs = 0L
                        }
                    }
                )
            }
        } catch (e: Exception) {
            closeQuietly(newClient, newConn, newSession)
            throw e
        }
    }

    /** Close every pooled connection immediately. Call from Application.onTerminate(). */
    fun closeAll() {
        pool.values.forEach { entry ->
            synchronized(entry) { closeEntryQuietly(entry) }
        }
        pool.clear()
    }

    /**
     * A borrowed authenticated context from the pool (or a dedicated private one).
     *
     * Call [release] on success, [invalidate] on error.
     */
    class PooledConnection(
        val session:      Session,
        private val onRelease:    () -> Unit,
        private val onInvalidate: () -> Unit
    ) {
        /** The underlying TCP connection — used for immediate socket close on cancel. */
        val connection: Connection get() = session.connection

        /** Return this session to the pool. */
        fun release()    = onRelease()

        /** Discard this session — broken or transport disconnected. */
        fun invalidate() = onInvalidate()
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun poolKey(share: NetworkShare) = PoolKey(
        host     = share.host,
        port     = share.effectivePort,
        username = share.username.trim(),
        domain   = share.domain.trim(),
        protocol = share.smbProtocol
    )

    /**
     * Short-lived config for use **only** in [SmbShareClient.testConnection].
     *
     * 10 s connect + 10 s read — tight enough to fail fast if the server is
     * genuinely unreachable, but still long enough for a NAS that briefly spins
     * up its disk. The connection created with this config is dedicated (never
     * returned to the pool) so a slow probe never contaminates a pooled session.
     */
    internal fun buildTestConfig(): SmbConfig =
        SmbConfig.builder()
            .withTimeout(10L, TimeUnit.SECONDS)
            .withSoTimeout(10L, TimeUnit.SECONDS)
            .withSocketFactory(KeepAliveSocketFactory(8000))
            .withSigningRequired(false)
            .withEncryptData(false)
            .build()

    private fun buildConfig(protocol: String, forWrite: Boolean, dedicated: Boolean = false): SmbConfig {
        // Pooled metadata connections use a short socket timeout (2 s) so stale
        // sessions fail over fast. Dedicated connections (video playback, large
        // file transfers, thumbnail extraction) get a generous but FINITE timeout
        // (30 s): SO_TIMEOUT only fires during an active socket read, so long idle
        // periods (pausing, buffering) are unaffected, while a genuinely hung
        // server read is bounded instead of blocking its thread forever. Transfer
        // cancellation is still handled via the UI cancel button, which closes the
        // underlying TCP socket directly.
        val soTimeoutSec = when {
            forWrite  -> 60L
            dedicated -> 30L // streaming / long-lived reads — bounded so a hung server can't block forever
            else      -> 2L // pooled metadata — fast failover on stale connections
        }

        val builder = SmbConfig.builder()
            .withTimeout(if (forWrite) 60L else 2L, TimeUnit.SECONDS)
            .withSoTimeout(soTimeoutSec, TimeUnit.SECONDS)
            .withSocketFactory(KeepAliveSocketFactory(5000))
            // smbj 0.14.0 may require signing by default, which causes
            // STATUS_ACCESS_DENIED on NAS devices / servers that don't support
            // SMB signing.  Disable signing to match the 0.13.0 behaviour.
            .withSigningRequired(false)
            // Disable SMB3 transport encryption — the LAN is trusted and encryption
            // adds heavy CPU overhead on Android, especially when BouncyCastle handles
            // the AES-CCM/GCM operations instead of hardware-accelerated Conscrypt.
            .withEncryptData(false)
            // Larger read/write buffer sizes improve throughput for all operations
            // (default is 64KB; 1MB significantly reduces round-trips on Wi-Fi).
            .withReadBufferSize(1024 * 1024)
            .withWriteBufferSize(1024 * 1024)

        when (protocol) {
            "SMB2" -> builder.withDialects(
                com.hierynomus.mssmb2.SMB2Dialect.SMB_2_0_2,
                com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1
            )
            "SMB3" -> builder.withDialects(
                com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0,
                com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2,
                com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1
            )
            // AUTO handles both by default
        }
        return builder.build()
    }

    private fun isAlive(session: Session): Boolean = try {
        val conn = session.connection
        conn != null && conn.isConnected
    } catch (_: Exception) { false }

    private fun closeQuietly(client: SMBClient?, conn: Connection?, session: Session?) {
        runCatching { session?.close() }
        runCatching { conn?.close() }
        runCatching { client?.close() }
    }

    private fun closeEntryQuietly(entry: PoolEntry) {
        closeQuietly(entry.client, entry.connection, entry.session)
        entry.client     = null
        entry.connection = null
        entry.session    = null
    }

    private fun evictIdle() {
        val now = System.currentTimeMillis()
        pool.values.forEach { entry ->
            synchronized(entry) {
                if (entry.session != null && entry.useCount == 0) {
                    val idleMs = now - entry.lastReleasedMs
                    if (idleMs >= IDLE_TIMEOUT_MS) {
                        android.util.Log.d("SmbSessionPool",
                            "Evicting idle session (idle ${idleMs / 1000}s)")
                        closeEntryQuietly(entry)
                    }
                }
            }
        }
    }

    // ── Socket factory with TCP keepalive ────────────────────────────────────

    /**
     * Wraps smbj's ProxySocketFactory and enables TCP keepalive on every created
     * socket so the OS detects dead peers (Wi-Fi drop, server crash) without
     * waiting for the application-level soTimeout to fire.
     *
     * Extends [javax.net.SocketFactory] which is what
     * [com.hierynomus.smbj.SmbConfig.Builder.withSocketFactory] accepts.
     */
    private class KeepAliveSocketFactory(connectTimeoutMs: Int) : javax.net.SocketFactory() {

        private val delegate = com.hierynomus.protocol.commons.socket.ProxySocketFactory(
            java.net.Proxy.NO_PROXY, connectTimeoutMs
        )

        override fun createSocket(): java.net.Socket =
            delegate.createSocket().also { it.keepAlive = true }

        override fun createSocket(host: String, port: Int): java.net.Socket =
            delegate.createSocket(host, port).also { it.keepAlive = true }

        override fun createSocket(
            host: String, port: Int,
            localHost: java.net.InetAddress, localPort: Int
        ): java.net.Socket =
            delegate.createSocket(host, port, localHost, localPort).also { it.keepAlive = true }

        override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket =
            delegate.createSocket(host, port).also { it.keepAlive = true }

        override fun createSocket(
            address: java.net.InetAddress, port: Int,
            localAddress: java.net.InetAddress, localPort: Int
        ): java.net.Socket =
            delegate.createSocket(address, port, localAddress, localPort).also { it.keepAlive = true }
    }
}
