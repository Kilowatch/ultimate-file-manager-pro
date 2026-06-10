package za.kilowatch.ultimatefilemanager.network

import android.util.Log
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Singleton SSH session pool — mirrors the design of [SmbSessionPool].
 *
 * Keeps one authenticated [ClientSession] alive per share credential key so
 * sequential operations (list → delete → rename …) reuse the same SSH session
 * instead of performing a fresh TCP connect + authentication on every call.
 *
 * ## Pool key
 * `host:port:username` — password / privateKeyPath intentionally excluded so that
 * a credential change always triggers a fresh session.
 *
 * ## Dedicated sessions
 * [borrow] with `dedicated = true` always creates a fresh [SshClient] + [ClientSession]
 * that is never returned to the pool. This is used by streaming I/O (openInputStream /
 * openOutputStream) so long-running transfers don't block the pooled session.
 *
 * ## Idle eviction
 * A background daemon thread closes sessions that have been idle for ≥ [IDLE_TIMEOUT_MS].
 */
object SshSessionPool {

    private const val TAG = "SshSessionPool"

    /** Sessions idle longer than 5 minutes are proactively closed. */
    private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

    private data class PoolKey(
        val host: String,
        val port: Int,
        val username: String
    )

    private class PoolEntry {
        var client: SshClient? = null
        var session: ClientSession? = null
        var useCount: Int = 0
        var lastReleasedMs: Long = 0L
    }

    private val pool = ConcurrentHashMap<PoolKey, PoolEntry>()

    private val evictor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ssh-pool-evictor").also { it.isDaemon = true }
    }

    init {
        evictor.scheduleAtFixedRate(::evictIdle, 60, 60, TimeUnit.SECONDS)
    }

    /**
     * Borrow an authenticated [PooledSession] for [share].
     *
     * @param share The network share to connect to.
     * @param dedicated If true, the returned session is never pooled and is
     *   closed when [PooledSession.release] is called.
     */
    fun borrow(share: NetworkShare, dedicated: Boolean = false): PooledSession {
        if (dedicated) {
            val (client, session) = freshSession(share)
            return PooledSession(
                session = session,
                onRelease = { closePair(client, session) },
                onInvalidate = { closePair(client, session) }
            )
        }

        val key = poolKey(share)
        val entry = pool.getOrPut(key) { PoolEntry() }

        synchronized(entry) {
            val existing = entry.session
            if (existing != null && isAlive(existing)) {
                entry.useCount++
                return PooledSession(
                    session = existing,
                    onRelease = {
                        synchronized(entry) {
                            entry.useCount = maxOf(0, entry.useCount - 1)
                            entry.lastReleasedMs = System.currentTimeMillis()
                        }
                    },
                    onInvalidate = {
                        synchronized(entry) {
                            closeEntry(entry)
                        }
                    }
                )
            }

            // Close any stale entry if not in use.
            if (entry.useCount == 0) closeEntry(entry)

            val (client, session) = freshSession(share)
            entry.client = client
            entry.session = session
            entry.useCount = 1
            entry.lastReleasedMs = 0L

            return PooledSession(
                session = session,
                onRelease = {
                    synchronized(entry) {
                        entry.useCount = maxOf(0, entry.useCount - 1)
                        entry.lastReleasedMs = System.currentTimeMillis()
                    }
                },
                onInvalidate = {
                    synchronized(entry) {
                        closeEntry(entry)
                    }
                }
            )
        }
    }

    /** Close every pooled session. Call from Application.onTrimMemory(COMPLETE). */
    fun closeAll() {
        pool.values.forEach { entry ->
            synchronized(entry) { closeEntry(entry) }
        }
        pool.clear()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun poolKey(share: NetworkShare) = PoolKey(
        host = share.host,
        port = share.effectivePort,
        username = share.username.trim()
    )

    /**
     * Open a fresh client + authenticated session for [share].
     * Auth is delegated to [SshShareClient.authenticate].
     */
    private fun freshSession(share: NetworkShare): Pair<SshClient, ClientSession> {
        val client = SshClient.setUpDefaultClient()
        SshShareClient.setupClientAuth(client, share)
        client.start()
        return try {
            val session = client.connect(share.username, share.host, share.effectivePort)
                .verify(SshShareClient.TIMEOUT_SECONDS, TimeUnit.SECONDS).session
            if (!SshShareClient.authenticate(session, share)) {
                client.stop()
                throw java.io.IOException("SSH authentication failed for ${share.host}")
            }
            Log.d(TAG, "New session opened to ${share.host}:${share.effectivePort}")
            Pair(client, session)
        } catch (e: Exception) {
            client.stop()
            throw e
        }
    }

    private fun isAlive(session: ClientSession): Boolean = try {
        !session.isClosed && !session.isClosing
    } catch (_: Exception) { false }

    private fun closeEntry(entry: PoolEntry) {
        closePair(entry.client, entry.session)
        entry.client = null
        entry.session = null
        entry.useCount = 0
        entry.lastReleasedMs = 0L
    }

    private fun closePair(client: SshClient?, session: ClientSession?) {
        runCatching { session?.close() }
        runCatching { client?.stop() }
    }

    private fun evictIdle() {
        val now = System.currentTimeMillis()
        pool.entries.forEach { (_, entry) ->
            synchronized(entry) {
                if (entry.session != null && entry.useCount == 0) {
                    val idleMs = now - entry.lastReleasedMs
                    if (idleMs >= IDLE_TIMEOUT_MS) {
                        Log.d(TAG, "Evicting idle SSH session (idle ${idleMs / 1000}s)")
                        closeEntry(entry)
                    }
                }
            }
        }
    }

    // ── Public types ──────────────────────────────────────────────────────────

    /**
     * A borrowed, authenticated SSH session from the pool (or a dedicated one).
     *
     * Always call [release] on normal completion or [invalidate] on error so the
     * pool entry's use-count is correctly maintained.
     */
    class PooledSession(
        val session: ClientSession,
        private val onRelease: () -> Unit,
        private val onInvalidate: () -> Unit
    ) {
        /** Return this session to the pool (or close it if dedicated). */
        fun release() = onRelease()

        /** Discard a broken session so the pool won't hand it out again. */
        fun invalidate() = onInvalidate()
    }
}
