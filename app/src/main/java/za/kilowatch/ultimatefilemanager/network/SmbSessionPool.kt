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

    /** Idle sessions are closed after 10 minutes of inactivity. */
    private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

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
            val isStale  = idleMs >= IDLE_TIMEOUT_MS

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
                            closeEntryQuietly(entry, forceDisconnect = true)
                            entry.useCount       = 0
                            entry.lastReleasedMs = 0L
                        }
                    }
                )
            }

            // Fresh connection needed. If the slot is empty or the existing connection
            // is dead/stale, we open a new one. Stale entries with useCount=0 are closed.
            if (entry.useCount == 0) {
                closeEntryQuietly(entry, forceDisconnect = true)
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
                    closeQuietly(newClient, newConn, newSession, forceDisconnect = true)
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
                                closeEntryQuietly(entry, forceDisconnect = true)
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
                            closeEntryQuietly(entry, forceDisconnect = true)
                            entry.useCount       = 0
                            entry.lastReleasedMs = 0L
                        }
                    }
                )
            }
        } catch (e: Exception) {
            closeQuietly(newClient, newConn, newSession, forceDisconnect = true)
            throw e
        }
    }

    /** Close every pooled connection immediately. Call from Application.onTerminate(). */
    fun closeAll() {
        pool.values.forEach { entry ->
            synchronized(entry) { closeEntryQuietly(entry, forceDisconnect = true) }
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
        // sessions fail over fast.
        //
        // Dedicated connections (video playback via the HTTP proxy, long-lived reads)
        // get NO socket timeout (0 = infinite). This is critical: smbj's PacketReader
        // thread blocks in a socket read waiting for the next response packet. When an
        // external player (VLC) buffers and pauses SMB reads for a while, that blocked
        // read would hit a finite SO_TIMEOUT and smbj would tear down the whole SMB
        // session — which is exactly why "net session" was dropping mid-playback. The
        // internal player never idles long enough to trigger it (ExoPlayer reads
        // continuously), which is why it worked. The proxy's own HTTP socket still has
        // a 30 s soTimeout that bounds each request, and TCP keepalive detects a truly
        // dead server. Write streams keep a 60 s timeout so a hung upload fails fast.
        val soTimeoutSec = when {
            forWrite  -> 60L
            dedicated -> 0L // infinite — buffering/pausing must never kill the stream
            else      -> 30L // pooled metadata — 30s timeout prevents idle packet reader teardowns
        }

        // Transport timeout for dedicated streaming connections: use a large value
        // (2 min) rather than 0 — smbj's internal request timeout may treat 0 as
        // "already expired". The blocking socket read is what matters for the idle
        // buffering case, and that is governed by SO_TIMEOUT above (0 = infinite),
        // not this value. A paused/buffering player issues no outstanding request,
        // so a 2-min request timeout never fires while idle. Pooled metadata
        // connections keep a generous 30 s timeout.
        val transportTimeoutSec = when {
            forWrite  -> 60L
            dedicated -> 120L // generous — never fires during idle buffering
            else      -> 30L  // pooled — generous request timeout
        }

        val builder = SmbConfig.builder()
            .withTimeout(transportTimeoutSec, TimeUnit.SECONDS)
            .withSoTimeout(soTimeoutSec, TimeUnit.SECONDS)
            .withSocketFactory(KeepAliveSocketFactory(15000))
            // smbj 0.14.0 may require signing by default, which causes
            // STATUS_ACCESS_DENIED on NAS devices / servers that don't support
            // SMB signing.  Disable signing to match the 0.13.0 behaviour.
            .withSigningRequired(false)
            // Disable SMB3 transport encryption — the LAN is trusted and encryption
            // adds heavy CPU overhead on Android, especially when BouncyCastle handles
            // the AES-CCM/GCM operations instead of hardware-accelerated Conscrypt.
            .withEncryptData(false)
            // Read buffer size. The HTTP proxy reads in 256KB chunks, so a 1MB buffer
            // buys nothing but forces a Large-MTU SMB negotiation that some Windows
            // servers reject — causing a mid-stream "Software caused connection abort"
            // (Kodi issue #26791 documents the exact same failure). 256KB is the safe
            // sweet spot: full throughput on LAN, no server-side reset.
            .withReadBufferSize(256 * 1024)
            .withWriteBufferSize(256 * 1024)

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
        if (conn == null || !conn.isConnected) {
            false
        } else {
            // conn.isConnected is a software flag — it only flips false when smbj's
            // own PacketReader detects a failure.  If the TCP connection was silently
            // dropped (NAT timeout, Wi-Fi roaming, server closed idle session while
            // the app was in background), the flag stays true until the next actual
            // read.  Probe the underlying socket to catch OS-level disconnects.
            val transport = conn.javaClass.getDeclaredField("transport")
            transport.isAccessible = true
            val transportObj = transport.get(conn)
            if (transportObj != null) {
                val socketField = transportObj.javaClass.getDeclaredField("socket")
                socketField.isAccessible = true
                val socket = socketField.get(transportObj) as? java.net.Socket
                socket != null && !socket.isClosed && socket.isConnected
            } else {
                false
            }
        }
    } catch (_: Exception) { false }

    private fun closeQuietly(
        client: SMBClient?,
        conn: Connection?,
        session: Session?,
        forceDisconnect: Boolean = false
    ) {
        if (forceDisconnect) {
            // Force-close the TCP connection socket FIRST to abort blocked socket reads/writes immediately
            // without waiting for session.close() to attempt an SMB LOGOFF packet over a dead/stale pipe.
            runCatching { conn?.close() }
            runCatching { session?.close() }
            runCatching { client?.close() }
        } else {
            runCatching { session?.close() }
            runCatching { conn?.close() }
            runCatching { client?.close() }
        }
    }

    private fun closeEntryQuietly(entry: PoolEntry, forceDisconnect: Boolean = true) {
        closeQuietly(entry.client, entry.connection, entry.session, forceDisconnect = forceDisconnect)
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
