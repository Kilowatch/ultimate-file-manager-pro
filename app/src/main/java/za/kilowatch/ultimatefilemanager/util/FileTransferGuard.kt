package za.kilowatch.ultimatefilemanager.util

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Utility for zero-byte file detection, auto-retry on copy, and
 * pre-deletion safety checks during cut/move operations.
 *
 * All functions are designed to be called from suspend contexts
 * (coroutines) and use non-throwing failure signalling (return values
 * rather than exceptions) so the caller controls error handling.
 */
object FileTransferGuard {

    const val MAX_RETRIES = 3
    const val RETRY_DELAY_MS = 1000L
    const val TAG = "FileTransferGuard"

    /**
     * Performs a file copy with zero-byte detection and auto-retry.
     *
     * After each invocation of [doCopy] the [verifyDestSize] lambda is called.
     * If the destination size is ≤ 0 the copy is retried up to [MAX_RETRIES]
     * additional times with [RETRY_DELAY_MS] between attempts.
     *
     * Edge case: if [sourceSize] is ≤ 0 (the source file itself is empty),
     * no retry is performed — the copy runs once and succeeds immediately.
     * This prevents pointless retries on genuinely empty files.
     *
     * @param sourceName   Human-readable name for logging (file name or path).
     * @param sourceSize   Size of the source file in bytes.
     *                     When ≤ 0 the retry loop is skipped entirely.
     * @param verifyDestSize Suspended function returning the destination file size
     *                       in bytes, or ≤ 0 if the destination does not exist.
     * @param doCopy       The actual copy operation. May be called multiple times.
     *                     Each invocation must overwrite the destination completely.
     * @return `true` if the destination exists and is > 0 bytes after the operation.
     *         `false` if all retries were exhausted and the destination is still 0 bytes.
     */
    suspend fun guardedCopy(
        sourceName: String,
        sourceSize: Long,
        verifyDestSize: suspend () -> Long,
        doCopy: suspend () -> Unit
    ): Boolean {
        // Edge case: source is also 0 bytes → skip retry, accept empty file as-is
        if (sourceSize <= 0) {
            doCopy()
            return true
        }

        var attempt = 0
        while (true) {
            attempt++
            doCopy()
            val destSize = try {
                verifyDestSize()
            } catch (e: Exception) {
                Log.w(TAG, "verifyDestSize threw for '$sourceName' (attempt $attempt): ${e.message}")
                -1L
            }

            if (destSize > 0) return true   // Success

            Log.w(TAG, "Zero-byte copy detected for '$sourceName' " +
                       "(attempt $attempt/$MAX_RETRIES, destSize=$destSize)")

            if (attempt > MAX_RETRIES) {
                Log.e(TAG, "All $MAX_RETRIES retries exhausted for '$sourceName', " +
                           "file remains 0 bytes")
                return false  // All retries exhausted
            }

            delay(RETRY_DELAY_MS)
        }
    }

    /**
     * Verifies it is safe to delete the source file in a move/cut operation.
     *
     * The source file MUST NOT be deleted if the destination is 0 bytes — doing
     * so would permanently lose the file's data.
     *
     * The only exception is when the source file itself is also 0 bytes: deleting
     * an empty source file does not lose any data.
     *
     * @param destSize   Size of the destination file in bytes.
     * @param sourceSize Size of the source file in bytes (default -1 = unknown).
     * @param sourceName Human-readable name for logging.
     * @return `true` if the source can be safely deleted (dest > 0 or source is empty).
     *         `false` if deletion should be blocked (dest is 0 bytes, source has data).
     */
    fun requireSourceSafeToDelete(
        destSize: Long,
        sourceSize: Long = -1L,
        sourceName: String = ""
    ): Boolean {
        if (destSize > 0) return true
        if (sourceSize == 0L) {
            // Source file is empty too — deleting it doesn't lose data
            Log.w(TAG, "Source '$sourceName' is also 0 bytes, allowing deletion")
            return true
        }
        Log.e(TAG, "BLOCKED deletion of source '$sourceName' — destination is 0 bytes " +
                    "(destSize=$destSize, sourceSize=$sourceSize)")
        return false
    }
}
