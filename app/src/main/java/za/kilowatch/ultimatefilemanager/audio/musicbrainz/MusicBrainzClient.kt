package za.kilowatch.ultimatefilemanager.audio.musicbrainz

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbRecordingItem
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbRecordingSearchResponse
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbReleaseDetails
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbReleaseSearchResponse
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbReleaseSummary
import za.kilowatch.ultimatefilemanager.network.UfmHttpClient
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.net.URLEncoder

/**
 * Client for interacting with the MusicBrainz Web Service (v2).
 * Adheres strictly to the MetaBrainz API usage policies:
 * - Informative and unique User-Agent
 * - Enforced rate limiting via [MusicBrainzRateLimiter] (<= 1 request/sec)
 * - JSON responses requested via fmt=json and Accept: application/json
 */
object MusicBrainzClient {

    private const val TAG = "MusicBrainzClient"
    private const val BASE_URL = "https://musicbrainz.org/ws/2"

    const val USER_AGENT = "UltimateFileManager/1.0 ( za.kilowatch.ultimatefilemanager; support@kilowatch.co.za )"

    private val gson = Gson()

    /**
     * Searches for track recordings matching a track title and optional artist name.
     */
    suspend fun searchRecordings(
        title: String,
        artist: String = "",
        limit: Int = 15
    ): List<MbRecordingItem> = withContext(Dispatchers.IO) {
        if (title.isBlank() && artist.isBlank()) return@withContext emptyList()

        MusicBrainzRateLimiter.acquire()

        val queryParts = mutableListOf<String>()
        if (title.isNotBlank()) {
            queryParts.add("recording:\"${escapeLucene(title.trim())}\"")
        }
        if (artist.isNotBlank()) {
            queryParts.add("artist:\"${escapeLucene(artist.trim())}\"")
        }

        val rawQuery = queryParts.joinToString(" AND ")
        val encodedQuery = URLEncoder.encode(rawQuery, "UTF-8")
        val url = "$BASE_URL/recording?query=$encodedQuery&limit=$limit&fmt=json"

        try {
            val headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to USER_AGENT
            )
            val response = UfmHttpClient.get(url, headers = headers, timeoutSec = 20)
            if (response.isSuccessful) {
                val parsed = gson.fromJson(response.bodyString, MbRecordingSearchResponse::class.java)
                parsed?.recordings ?: emptyList()
            } else {
                GoRoLog.w(TAG, "Search recordings returned HTTP ${response.statusCode}")
                emptyList()
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to search recordings: ${e.message}")
            emptyList()
        }
    }

    /**
     * Searches for album releases matching an album title and optional artist name.
     */
    suspend fun searchReleases(
        album: String,
        artist: String = "",
        limit: Int = 15
    ): List<MbReleaseSummary> = withContext(Dispatchers.IO) {
        if (album.isBlank() && artist.isBlank()) return@withContext emptyList()

        MusicBrainzRateLimiter.acquire()

        val queryParts = mutableListOf<String>()
        if (album.isNotBlank()) {
            queryParts.add("release:\"${escapeLucene(album.trim())}\"")
        }
        if (artist.isNotBlank()) {
            queryParts.add("artist:\"${escapeLucene(artist.trim())}\"")
        }

        val rawQuery = queryParts.joinToString(" AND ")
        val encodedQuery = URLEncoder.encode(rawQuery, "UTF-8")
        val url = "$BASE_URL/release?query=$encodedQuery&limit=$limit&fmt=json"

        try {
            val headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to USER_AGENT
            )
            val response = UfmHttpClient.get(url, headers = headers, timeoutSec = 20)
            if (response.isSuccessful) {
                val parsed = gson.fromJson(response.bodyString, MbReleaseSearchResponse::class.java)
                parsed?.releases ?: emptyList()
            } else {
                GoRoLog.w(TAG, "Search releases returned HTTP ${response.statusCode}")
                emptyList()
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to search releases: ${e.message}")
            emptyList()
        }
    }

    /**
     * Looks up comprehensive release details, including all media discs and individual tracks.
     */
    suspend fun lookupRelease(mbid: String): MbReleaseDetails? = withContext(Dispatchers.IO) {
        if (mbid.isBlank()) return@withContext null

        MusicBrainzRateLimiter.acquire()

        val url = "$BASE_URL/release/$mbid?inc=recordings+artist-credits+labels+media+genres&fmt=json"

        try {
            val headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to USER_AGENT
            )
            val response = UfmHttpClient.get(url, headers = headers, timeoutSec = 25)
            if (response.isSuccessful) {
                gson.fromJson(response.bodyString, MbReleaseDetails::class.java)
            } else {
                GoRoLog.w(TAG, "Lookup release returned HTTP ${response.statusCode} for $mbid")
                null
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to lookup release $mbid: ${e.message}")
            null
        }
    }

    /**
     * Escapes characters that have special syntactic meaning in Lucene queries.
     */
    private fun escapeLucene(text: String): String {
        val specialChars = setOf('+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/')
        val sb = StringBuilder()
        for (c in text) {
            if (c in specialChars) {
                sb.append('\\')
            }
            sb.append(c)
        }
        return sb.toString()
    }
}
