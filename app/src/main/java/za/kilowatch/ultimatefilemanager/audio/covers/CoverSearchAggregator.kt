package za.kilowatch.ultimatefilemanager.audio.covers

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.audio.covers.model.CoverSearchResult
import za.kilowatch.ultimatefilemanager.audio.covers.model.CoverSource
import za.kilowatch.ultimatefilemanager.audio.covers.model.DeezerSearchResponse
import za.kilowatch.ultimatefilemanager.audio.covers.model.ItunesSearchResponse
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.CoverArtArchiveClient
import za.kilowatch.ultimatefilemanager.network.UfmHttpClient
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.net.URLEncoder

/**
 * High-performance aggregator searching across multiple keyless high-resolution album artwork sources:
 * 1. Apple Music / iTunes Store API (up to 3000x3000px master artwork)
 * 2. Deezer API (1000x1000px cover_xl artwork)
 * 3. Cover Art Archive (CAA) (1200px front covers)
 */
object CoverSearchAggregator {

    private const val TAG = "CoverSearchAggregator"
    private val gson = Gson()

    /**
     * Searches all artwork providers concurrently for candidates matching [album] and [artist].
     */
    suspend fun searchArtwork(
        album: String,
        artist: String = "",
        mbid: String? = null
    ): List<CoverSearchResult> = withContext(Dispatchers.IO) {
        val cleanAlbum = album.trim()
        val cleanArtist = artist.trim()
        if (cleanAlbum.isBlank() && cleanArtist.isBlank()) return@withContext emptyList()

        val results = mutableListOf<CoverSearchResult>()

        coroutineScope {
            val appleDeferred = async { searchAppleMusic(cleanAlbum, cleanArtist) }
            val deezerDeferred = async { searchDeezer(cleanAlbum, cleanArtist) }
            val caaDeferred = async {
                if (!mbid.isNullOrBlank()) {
                    searchCoverArtArchive(mbid)
                } else {
                    emptyList()
                }
            }

            results.addAll(appleDeferred.await())
            results.addAll(deezerDeferred.await())
            results.addAll(caaDeferred.await())
        }

        // Deduplicate and prioritize highest resolution
        results.distinctBy { it.fullImageUrl }
            .sortedByDescending { it.width * it.height }
    }

    /**
     * Searches Apple Music / iTunes Store for album artwork up to 3000x3000px.
     */
    private suspend fun searchAppleMusic(album: String, artist: String): List<CoverSearchResult> {
        val query = "$artist $album".trim()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&entity=album&limit=10"

        return try {
            val response = UfmHttpClient.get(url, timeoutSec = 15)
            if (response.isSuccessful) {
                val parsed = gson.fromJson(response.bodyString, ItunesSearchResponse::class.java)
                parsed?.results?.mapNotNull { item ->
                    val highRes = item.highResArtworkUrl() ?: return@mapNotNull null
                    val thumb = item.artworkUrl100 ?: item.artworkUrl60 ?: highRes
                    CoverSearchResult(
                        thumbUrl = thumb,
                        fullImageUrl = highRes,
                        source = CoverSource.APPLE_MUSIC,
                        width = 3000,
                        height = 3000,
                        albumTitle = item.collectionName,
                        artistName = item.artistName,
                        releaseYear = item.releaseYear()
                    )
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Apple Music artwork search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Searches Deezer for album artwork up to 1000x1000px.
     */
    private suspend fun searchDeezer(album: String, artist: String): List<CoverSearchResult> {
        val query = "$artist $album".trim()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.deezer.com/search/album?q=$encoded&limit=10"

        return try {
            val response = UfmHttpClient.get(url, timeoutSec = 15)
            if (response.isSuccessful) {
                val parsed = gson.fromJson(response.bodyString, DeezerSearchResponse::class.java)
                parsed?.data?.mapNotNull { item ->
                    val fullUrl = item.coverXl ?: item.coverBig ?: return@mapNotNull null
                    val thumbUrl = item.coverMedium ?: item.coverSmall ?: fullUrl
                    CoverSearchResult(
                        thumbUrl = thumbUrl,
                        fullImageUrl = fullUrl,
                        source = CoverSource.DEEZER,
                        width = if (item.coverXl != null) 1000 else 500,
                        height = if (item.coverXl != null) 1000 else 500,
                        albumTitle = item.title,
                        artistName = item.artist?.name ?: "",
                        releaseYear = ""
                    )
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Deezer artwork search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches front cover artwork from Cover Art Archive (CAA) for a known release MBID.
     */
    private suspend fun searchCoverArtArchive(mbid: String): List<CoverSearchResult> {
        return try {
            val manifest = CoverArtArchiveClient.getReleaseCoverManifest(mbid) ?: return emptyList()
            manifest.images.filter { it.isFront }.mapNotNull { img ->
                val fullUrl = img.thumbnails?.thumb1200 ?: img.originalImageUrl
                val thumbUrl = img.thumbnails?.thumb500 ?: img.thumbnails?.thumb250 ?: fullUrl
                CoverSearchResult(
                    thumbUrl = thumbUrl,
                    fullImageUrl = fullUrl,
                    source = CoverSource.COVER_ART_ARCHIVE,
                    width = 1200,
                    height = 1200,
                    albumTitle = "",
                    artistName = "",
                    releaseYear = ""
                )
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "CAA artwork search failed for $mbid: ${e.message}")
            emptyList()
        }
    }

    /**
     * Downloads image bytes and mime type from an image URL, following redirects if needed.
     */
    suspend fun downloadImageBytes(imageUrl: String, maxRedirects: Int = 3): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank() || maxRedirects < 0) return@withContext null
        try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Android; Mobile)")
            val response = UfmHttpClient.get(imageUrl, headers = headers, timeoutSec = 25)
            if (response.statusCode in listOf(301, 302, 303, 307, 308)) {
                val loc = response.header("Location") ?: response.header("location")
                if (!loc.isNullOrBlank()) {
                    val nextUrl = if (loc.startsWith("http")) loc else java.net.URI(imageUrl).resolve(loc).toString()
                    return@withContext downloadImageBytes(nextUrl, maxRedirects - 1)
                }
            }
            if (response.isSuccessful && response.bodyBytes.isNotEmpty()) {
                val contentType = response.header("Content-Type") ?: "image/jpeg"
                Pair(response.bodyBytes, contentType)
            } else {
                null
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to download image bytes from $imageUrl: ${e.message}")
            null
        }
    }
}
