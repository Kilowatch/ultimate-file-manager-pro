package za.kilowatch.ultimatefilemanager.audio.musicbrainz

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock

/**
 * Enforces MusicBrainz's mandatory 1 request per second rate limit across all threads and coroutines.
 */
object MusicBrainzRateLimiter {

    private const val MIN_INTERVAL_MS = 1050L // 1.05s buffer to prevent edge-case 503 throttling
    private val mutex = Mutex()
    private var lastRequestTimestamp = 0L

    /**
     * Suspends if necessary until at least [MIN_INTERVAL_MS] has elapsed since the last request.
     */
    suspend fun acquire() {
        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastRequestTimestamp
            if (elapsed < MIN_INTERVAL_MS) {
                val waitTime = MIN_INTERVAL_MS - elapsed
                delay(waitTime)
            }
            lastRequestTimestamp = SystemClock.elapsedRealtime()
        }
    }
}
