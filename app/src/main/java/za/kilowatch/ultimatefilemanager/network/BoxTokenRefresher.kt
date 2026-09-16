package za.kilowatch.ultimatefilemanager.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * Refreshes a Box OAuth token via Box's token endpoint.
 *
 * This is a stateless helper used by [RCloneShareClient] to proactively refresh
 * expired Box tokens before rclone attempts to use them, avoiding 401 errors.
 *
 * Thread safety: a [Mutex] ensures only one concurrent refresh is in flight.
 * A second caller that arrives while a refresh is active will await the first
 * call's result and return it, preventing redundant API calls to Box.
 */
object BoxTokenRefresher {

    private const val TAG = "BoxTokenRefresher"
    private const val TOKEN_URL = "https://api.box.com/oauth2/token"

    private const val GRANT_TYPE_REFRESH_TOKEN = "refresh_token"

    /** Guards against concurrent refresh attempts. */
    private val refreshMutex = Mutex()

    /**
     * Refreshes a Box OAuth token using the given [refreshToken].
     *
     * POSTs to Box's token endpoint, computes an `expiry` timestamp from the
     * returned `expires_in` seconds, injects it into the response JSON, and
     * returns the enriched token JSON string.
     *
     * Because Box's raw response contains `expires_in` (seconds from now)
     * rather than an absolute `expiry` timestamp, this method calculates
     * `expiry = Instant.now() + expires_in` and adds it to the JSON so that
     * [RCloneConfig.isTokenExpired] can check it later without needing to
     * know when the token was issued.
     *
     * @param refreshToken The Box refresh token to exchange for new tokens.
     * @return The full token JSON string with a computed `expiry` field added.
     * @throws IOException if the network request fails or Box returns an error.
     */
    suspend fun refreshToken(refreshToken: String): String {
        return refreshMutex.withLock {
            performRefresh(refreshToken)
        }
    }

    /**
     * Synchronous variant of [refreshToken] for use from non-coroutine contexts
     * such as [RCloneShareClient.rcloneCall], which is a synchronous method.
     *
     * @see refreshToken
     */
    fun refreshTokenSync(refreshToken: String): String {
        return runBlocking {
            refreshMutex.withLock {
                performRefresh(refreshToken)
            }
        }
    }

    /**
     * Performs the actual HTTP POST to Box's OAuth token endpoint.
     * Not guarded — callers must hold [refreshMutex].
     */
    private fun performRefresh(refreshToken: String): String {
        GoRoLog.d(TAG, "Refreshing Box OAuth token...")

        val formFields = mapOf(
            "grant_type" to GRANT_TYPE_REFRESH_TOKEN,
            "refresh_token" to refreshToken,
            "client_id" to BoxOAuthConfig.CLIENT_ID,
            "client_secret" to BoxOAuthConfig.CLIENT_SECRET
        )

        val response = UfmHttpClient.postFormSync(TOKEN_URL, formFields = formFields, timeoutSec = 30)
        val body = response.bodyString
        GoRoLog.d(TAG, "Box refresh HTTP ${response.statusCode}: ${body.take(200)}")

        if (!response.isSuccessful) {
            val errorSummary = try {
                val err = JSONObject(body)
                err.optString("error_description", err.optString("error", body))
            } catch (_: Exception) {
                body
            }
            GoRoLog.e(TAG, "Box token refresh failed (${response.statusCode}): $errorSummary")
            throw IOException("Box token refresh failed (${response.statusCode}): $errorSummary")
        }

        val responseBody = body

        // Box returns "expires_in" (seconds), not an absolute "expiry" timestamp.
        // Compute the absolute expiry and inject it so isTokenExpired() can check it.
        val tokenJson = JSONObject(responseBody)
        val expiresIn = tokenJson.optInt("expires_in", -1)
        if (expiresIn > 0) {
            val expiry = Instant.now().plus(Duration.ofSeconds(expiresIn.toLong()))
            tokenJson.put("expiry", expiry.toString())
            GoRoLog.d(TAG, "Computed expiry: $expiry (expires_in=$expiresIn)")
        } else {
            GoRoLog.w(TAG, "Box refresh response has no expires_in field")
        }

        val enrichedJson = tokenJson.toString()
        GoRoLog.i(TAG, "Box token refreshed successfully")
        return enrichedJson
    }
}
