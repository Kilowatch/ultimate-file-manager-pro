package za.kilowatch.ultimatefilemanager.audio.musicbrainz

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.CaaReleaseResponse
import za.kilowatch.ultimatefilemanager.network.UfmHttpClient
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Client for retrieving high-resolution album cover art from the Cover Art Archive (CAA).
 * CAA is operated jointly by MetaBrainz and the Internet Archive.
 */
object CoverArtArchiveClient {

    private const val TAG = "CoverArtArchiveClient"
    private const val BASE_URL = "https://coverartarchive.org/release"
    private val gson = Gson()

    /**
     * Retrieves the image manifest for a MusicBrainz Release MBID.
     * Note: Does NOT require MusicBrainz rate-limiting as CAA is hosted on Internet Archive.
     */
    suspend fun getReleaseCoverManifest(mbid: String): CaaReleaseResponse? = withContext(Dispatchers.IO) {
        if (mbid.isBlank()) return@withContext null
        val url = "$BASE_URL/$mbid"
        val headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to MusicBrainzClient.USER_AGENT
        )

        try {
            val response = UfmHttpClient.get(url, headers = headers, timeoutSec = 15)
            if (response.statusCode in 200..299) {
                gson.fromJson(response.bodyString, CaaReleaseResponse::class.java)
            } else {
                GoRoLog.d(TAG, "CAA manifest returned status ${response.statusCode} for $mbid")
                null
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to fetch CAA manifest for $mbid: ${e.message}")
            null
        }
    }

    /**
     * Resolves the primary front cover art URL for a given release MBID.
     * Prefers 1200px or full resolution.
     */
    suspend fun getFrontCoverUrl(mbid: String): String? {
        val manifest = getReleaseCoverManifest(mbid) ?: return null
        val frontImage = manifest.images.firstOrNull { it.isFront } ?: manifest.images.firstOrNull() ?: return null
        return frontImage.thumbnails?.thumb1200
            ?: frontImage.originalImageUrl
            ?: frontImage.thumbnails?.large
            ?: frontImage.thumbnails?.thumb500
    }

    /**
     * Downloads cover art bytes from an image URL.
     */
    suspend fun downloadArtwork(imageUrl: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null
        try {
            val headers = mapOf("User-Agent" to MusicBrainzClient.USER_AGENT)
            val response = UfmHttpClient.get(imageUrl, headers = headers, timeoutSec = 30)
            if (response.isSuccessful && response.bodyBytes.isNotEmpty()) {
                val contentType = response.header("Content-Type") ?: "image/jpeg"
                Pair(response.bodyBytes, contentType)
            } else {
                null
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to download artwork from $imageUrl: ${e.message}")
            null
        }
    }
}
