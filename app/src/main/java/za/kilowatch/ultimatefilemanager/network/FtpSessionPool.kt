package za.kilowatch.ultimatefilemanager.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Singleton FTP connection pool — mirrors the design of [SshSessionPool].
 *
 * Keeps authenticated [FTPClient] connections alive per share credential key so
 * sequential and concurrent operations (list -> transfer -> delete ...) reuse
 * existing FTP connections instead of performing a fresh TCP connect + login on every call.
 *
 * ## Pool key
 * `host:port:username`
 *
 * ## Dedicated connections
 * [borrow] with `dedicated = true` creates a fresh [FTPClient] that is never returned
 * to the pool and is closed when [PooledFtpClient.release] is called.
 *
 * ## Idle eviction
 * A background daemon thread closes connections that have been idle for >= [IDLE_TIMEOUT_MS].
 */
object FtpSessionPool {

    private const val TAG = "FtpSessionPool"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val DATA_TIMEOUT_MS    = 0
    private const val IDLE_TIMEOUT_MS    = 60_000L // 1 minute idle timeout
    private const val MAX_IDLE_PER_SHARE = 8

    private data class PoolKey(
        val host: String,
        val port: Int,
        val username: String
    )

    private class IdleEntry(
        val client: FTPClient,
        val lastUsedMs: Long = System.currentTimeMillis()
    )

    private val pool = ConcurrentHashMap<PoolKey, ConcurrentLinkedQueue<IdleEntry>>()

    private val evictor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ftp-pool-evictor").also { it.isDaemon = true }
    }

    init {
        evictor.scheduleAtFixedRate(::evictIdle, 30, 30, TimeUnit.SECONDS)
    }

    /**
     * Borrow an authenticated [PooledFtpClient] for [share].
     *
     * @param share The network share to connect to.
     * @param dedicated If true, the returned client is never pooled and is
     *   disconnected when [PooledFtpClient.release] is called.
     */
    suspend fun borrow(share: NetworkShare, dedicated: Boolean = false): PooledFtpClient {
        if (dedicated) {
            val fresh = createClientOnIo(share)
            return PooledFtpClient(
                client = fresh,
                onRelease = { closeClient(fresh) },
                onInvalidate = { closeClient(fresh) }
            )
        }

        val key = poolKey(share)
        val queue = pool.getOrPut(key) { ConcurrentLinkedQueue() }

        // Try borrowing a healthy existing client from the pool
        while (true) {
            val entry = queue.poll() ?: break
            val candidate = entry.client
            if (isAlive(candidate)) {
                return PooledFtpClient(
                    client = candidate,
                    onRelease = { returnToPool(key, candidate) },
                    onInvalidate = { closeClient(candidate) }
                )
            } else {
                closeClient(candidate)
            }
        }

        // No healthy idle client found: open a fresh one
        val fresh = createClientOnIo(share)
        return PooledFtpClient(
            client = fresh,
            onRelease = { returnToPool(key, fresh) },
            onInvalidate = { closeClient(fresh) }
        )
    }

    private fun returnToPool(key: PoolKey, client: FTPClient) {
        if (!isAlive(client)) {
            closeClient(client)
            return
        }
        val queue = pool.getOrPut(key) { ConcurrentLinkedQueue() }
        if (queue.size < MAX_IDLE_PER_SHARE) {
            queue.offer(IdleEntry(client, System.currentTimeMillis()))
        } else {
            closeClient(client)
        }
    }

    /** Close every pooled connection. Call on memory trim or app backgrounding. */
    fun closeAll() {
        pool.values.forEach { queue ->
            while (true) {
                val entry = queue.poll() ?: break
                closeClient(entry.client)
            }
        }
        pool.clear()
    }

    private fun poolKey(share: NetworkShare) = PoolKey(
        host = share.host,
        port = share.effectivePort,
        username = share.username.trim()
    )

    private fun isAlive(ftp: FTPClient): Boolean = try {
        ftp.isConnected && ftp.isAvailable && ftp.sendNoOp()
    } catch (_: Exception) {
        false
    }

    private fun closeClient(ftp: FTPClient?) {
        runCatching { ftp?.logout() }
        runCatching { ftp?.disconnect() }
    }

    private fun evictIdle() {
        val now = System.currentTimeMillis()
        pool.entries.forEach { (_, queue) ->
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.lastUsedMs >= IDLE_TIMEOUT_MS) {
                    iterator.remove()
                    closeClient(entry.client)
                }
            }
        }
    }

    private suspend fun createClientOnIo(share: NetworkShare): FTPClient = withContext(Dispatchers.IO) {
        val ftp = FTPClient()
        ftp.connectTimeout = CONNECT_TIMEOUT_MS
        @Suppress("DEPRECATION")
        ftp.setDataTimeout(DATA_TIMEOUT_MS)
        ftp.setBufferSize(1024 * 1024)
        ftp.setControlKeepAliveTimeout(30)

        try {
            ftp.connect(share.host, share.effectivePort)
            val ok = if (share.username.isBlank()) {
                ftp.login("anonymous", "UFM@android")
            } else {
                ftp.login(share.username, share.password)
            }
            if (!ok) throw IOException("FTP login failed: ${ftp.replyString}")
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftp.setListHiddenFiles(true)
            ftp
        } catch (e: Exception) {
            closeClient(ftp)
            throw if (e is FtpConnectionException) e else FtpConnectionException(share.host, share.effectivePort, e)
        }
    }

    // ── Public types ──────────────────────────────────────────────────────────

    /**
     * A borrowed, authenticated FTP connection from the pool (or a dedicated one).
     */
    class PooledFtpClient(
        val client: FTPClient,
        private val onRelease: () -> Unit,
        private val onInvalidate: () -> Unit
    ) : AutoCloseable {
        fun release() = onRelease()
        fun invalidate() = onInvalidate()
        override fun close() = release()
    }
}
