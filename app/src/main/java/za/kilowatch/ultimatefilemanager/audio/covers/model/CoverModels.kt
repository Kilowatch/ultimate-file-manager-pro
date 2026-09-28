package za.kilowatch.ultimatefilemanager.audio.covers.model

import com.google.gson.annotations.SerializedName

/**
 * Common normalized model representing an album cover candidate found from online providers.
 */
data class CoverSearchResult(
    val thumbUrl: String,
    val fullImageUrl: String,
    val source: CoverSource,
    val width: Int,
    val height: Int,
    val albumTitle: String = "",
    val artistName: String = "",
    val releaseYear: String = ""
) {
    fun resolutionLabel(): String {
        return if (width > 0 && height > 0) "${width}×${height}" else "High Res"
    }
}

enum class CoverSource(val displayName: String) {
    APPLE_MUSIC("Apple Music"),
    COVER_ART_ARCHIVE("Cover Art Archive"),
    DEEZER("Deezer")
}

/**
 * iTunes / Apple Music Store Search API Models.
 */
data class ItunesSearchResponse(
    @SerializedName("resultCount") val resultCount: Int = 0,
    @SerializedName("results") val results: List<ItunesAlbumItem> = emptyList()
)

data class ItunesAlbumItem(
    @SerializedName("collectionId") val collectionId: Long = 0L,
    @SerializedName("artistName") val artistName: String = "",
    @SerializedName("collectionName") val collectionName: String = "",
    @SerializedName("artworkUrl60") val artworkUrl60: String? = null,
    @SerializedName("artworkUrl100") val artworkUrl100: String? = null,
    @SerializedName("releaseDate") val releaseDate: String? = null,
    @SerializedName("primaryGenreName") val primaryGenreName: String? = null
) {
    /**
     * Replaces standard 100x100 resolution in the Apple CDN URL with 3000x3000 for master artwork.
     */
    fun highResArtworkUrl(): String? {
        val base = artworkUrl100 ?: artworkUrl60 ?: return null
        return base.replace("/100x100bb.jpg", "/3000x3000bb.jpg")
            .replace("/100x100bb.png", "/3000x3000bb.png")
            .replace("/60x60bb.jpg", "/3000x3000bb.jpg")
    }

    fun releaseYear(): String = releaseDate?.take(4) ?: ""
}

/**
 * Deezer Album Search API Models.
 */
data class DeezerSearchResponse(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("data") val data: List<DeezerAlbumItem> = emptyList()
)

data class DeezerAlbumItem(
    @SerializedName("id") val id: Long = 0L,
    @SerializedName("title") val title: String = "",
    @SerializedName("cover_small") val coverSmall: String? = null,
    @SerializedName("cover_medium") val coverMedium: String? = null,
    @SerializedName("cover_big") val coverBig: String? = null,
    @SerializedName("cover_xl") val coverXl: String? = null,
    @SerializedName("artist") val artist: DeezerArtistItem? = null
)

data class DeezerArtistItem(
    @SerializedName("id") val id: Long = 0L,
    @SerializedName("name") val name: String = ""
)
