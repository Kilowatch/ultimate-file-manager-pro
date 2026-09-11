package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the "FTP & SFTP Multi-Threading / Transfer Threads" preference.
 *
 * Controls the number of parallel connections / worker threads used for FTP and SFTP
 * operations (both parallel multi-file batch transfers and segmented downloads of large files).
 *
 * Default: 4 threads (recommended standard).
 * Range: 1 (Single-threaded / Disabled) to 8 (Maximum throughput).
 */
object NetworkTransferPreferenceManager {

    const val PREFS_NAME = "ufm_network_transfer_prefs"
    const val KEY_TRANSFER_THREADS = "network_transfer_threads"

    const val DEFAULT_THREADS = 4
    const val MIN_THREADS = 1
    const val MAX_THREADS = 8

    val AVAILABLE_THREAD_OPTIONS = intArrayOf(1, 2, 4, 6, 8)

    /** Returns the configured thread count, clamped to valid range [MIN_THREADS..MAX_THREADS]. */
    fun getThreadCount(context: Context): Int {
        val threads = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_TRANSFER_THREADS, DEFAULT_THREADS)
        return threads.coerceIn(MIN_THREADS, MAX_THREADS)
    }

    /** Persists the configured thread count. */
    fun setThreadCount(context: Context, threads: Int) {
        val clamped = threads.coerceIn(MIN_THREADS, MAX_THREADS)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TRANSFER_THREADS, clamped)
            .apply()
    }

    /** Returns true when multi-threading is enabled (> 1 thread). */
    fun isMultiThreadingEnabled(context: Context): Boolean = getThreadCount(context) > 1
}
