package za.kilowatch.ultimatefilemanager.network

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Secure in-memory holder for a pending settings transfer payload.
 *
 * Security design (H-1):
 *  - Plain-text passwords must NOT travel via Intent.putExtra (readable by privileged apps).
 *  - Instead, the [PairingServer] stores the payload bytes here and places only a
 *    random [token] UUID in the Intent.
 *  - [TransferApprovalActivity] retrieves the payload using that token.
 *  - Entries auto-expire after [TTL_MS] (60 seconds) regardless of user action.
 */
object PendingTransferHolder {

    private const val TAG = "PendingTransferHolder"
    private const val TTL_MS = 60_000L

    private data class Entry(
        val payloadBytes: ByteArray,
        val expiresAt: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Stores a transfer payload and returns a short-lived token UUID.
     * Old expired entries are purged on every store call.
     */
    fun store(payloadBytes: ByteArray): String {
        purgeExpired()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(payloadBytes.copyOf(), System.currentTimeMillis() + TTL_MS)
        Log.d(TAG, "Stored pending transfer payload (token=${token.take(8)}…, ${payloadBytes.size} bytes)")
        return token
    }

    /**
     * Retrieves the payload for [token] if it exists and has not expired.
     * Returns null otherwise.
     */
    fun retrieve(token: String): ByteArray? {
        val entry = entries[token] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            entries.remove(token)
            Log.w(TAG, "Transfer payload expired (token=${token.take(8)}…)")
            return null
        }
        return entry.payloadBytes.copyOf()
    }

    /**
     * Removes the entry for [token] and zeros its payload bytes.
     * Call after the payload has been applied or rejected.
     */
    fun clear(token: String) {
        val removed = entries.remove(token)
        if (removed != null) {
            removed.payloadBytes.fill(0)
            Log.d(TAG, "Cleared pending transfer payload (token=${token.take(8)}…)")
        }
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val expired = entries.entries.filter { now > it.value.expiresAt }.map { it.key }
        expired.forEach { token ->
            entries.remove(token)?.payloadBytes?.fill(0)
        }
        if (expired.isNotEmpty()) Log.d(TAG, "Purged ${expired.size} expired transfer entry(ies)")
    }
}
