package za.kilowatch.ultimatefilemanager.audio.musicbrainz.model

import com.google.gson.annotations.SerializedName

/**
 * MusicBrainz Recording Search Response.
 */
data class MbRecordingSearchResponse(
    @SerializedName("count") val count: Int = 0,
    @SerializedName("offset") val offset: Int = 0,
    @SerializedName("recordings") val recordings: List<MbRecordingItem> = emptyList()
)

data class MbRecordingItem(
    @SerializedName("id") val id: String = "",
    @SerializedName("score") val score: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("length") val lengthMs: Long? = null,
    @SerializedName("disambiguation") val disambiguation: String? = null,
    @SerializedName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    @SerializedName("releases") val releases: List<MbReleaseSummary> = emptyList(),
    @SerializedName("tags") val tags: List<MbTagItem> = emptyList()
) {
    fun displayArtist(): String = artistCredit.joinToString(", ") { it.name }
}

data class MbArtistCredit(
    @SerializedName("name") val name: String = "",
    @SerializedName("artist") val artist: MbArtistRef? = null
)

data class MbArtistRef(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("sort-name") val sortName: String? = null
)

data class MbReleaseSummary(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("status") val status: String? = null,
    @SerializedName("date") val date: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("track-count") val trackCount: Int = 0,
    @SerializedName("media") val media: List<MbMediaSummary> = emptyList(),
    @SerializedName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList()
) {
    fun displayArtist(): String = artistCredit.joinToString(", ") { it.name }
    fun releaseYear(): String = date?.take(4) ?: ""
}

data class MbMediaSummary(
    @SerializedName("position") val position: Int = 1,
    @SerializedName("format") val format: String? = null,
    @SerializedName("track-count") val trackCount: Int = 0,
    @SerializedName("track-offset") val trackOffset: Int = 0
)

data class MbTagItem(
    @SerializedName("count") val count: Int = 0,
    @SerializedName("name") val name: String = ""
)

/**
 * MusicBrainz Release Search Response.
 */
data class MbReleaseSearchResponse(
    @SerializedName("count") val count: Int = 0,
    @SerializedName("offset") val offset: Int = 0,
    @SerializedName("releases") val releases: List<MbReleaseSummary> = emptyList()
)

/**
 * Detailed Release lookup response including complete media and tracklists.
 */
data class MbReleaseDetails(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("status") val status: String? = null,
    @SerializedName("date") val date: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("barcode") val barcode: String? = null,
    @SerializedName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    @SerializedName("media") val media: List<MbMediaDetails> = emptyList(),
    @SerializedName("genres") val genres: List<MbGenreItem> = emptyList(),
    @SerializedName("label-info") val labelInfo: List<MbLabelInfo> = emptyList()
) {
    fun displayArtist(): String = artistCredit.joinToString(", ") { it.name }
    fun releaseYear(): String = date?.take(4) ?: ""
    fun primaryGenre(): String = genres.firstOrNull()?.name ?: ""
    fun labelName(): String = labelInfo.firstOrNull()?.label?.name ?: ""
}

data class MbMediaDetails(
    @SerializedName("position") val position: Int = 1,
    @SerializedName("format") val format: String? = null,
    @SerializedName("track-count") val trackCount: Int = 0,
    @SerializedName("tracks") val tracks: List<MbTrackDetails> = emptyList()
)

data class MbTrackDetails(
    @SerializedName("id") val id: String = "",
    @SerializedName("position") val position: Int = 0,
    @SerializedName("number") val number: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("length") val lengthMs: Long? = null,
    @SerializedName("recording") val recording: MbTrackRecording? = null
)

data class MbTrackRecording(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("length") val lengthMs: Long? = null,
    @SerializedName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList()
) {
    fun displayArtist(): String = artistCredit.joinToString(", ") { it.name }
}

data class MbGenreItem(
    @SerializedName("name") val name: String = "",
    @SerializedName("count") val count: Int = 0
)

data class MbLabelInfo(
    @SerializedName("label") val label: MbLabelItem? = null
)

data class MbLabelItem(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = ""
)

/**
 * Cover Art Archive response model.
 */
data class CaaReleaseResponse(
    @SerializedName("images") val images: List<CaaImageItem> = emptyList(),
    @SerializedName("release") val releaseUrl: String = ""
)

data class CaaImageItem(
    @SerializedName("id") val id: Long = 0L,
    @SerializedName("image") val originalImageUrl: String = "",
    @SerializedName("front") val isFront: Boolean = false,
    @SerializedName("back") val isBack: Boolean = false,
    @SerializedName("types") val types: List<String> = emptyList(),
    @SerializedName("thumbnails") val thumbnails: CaaThumbnails? = null
)

data class CaaThumbnails(
    @SerializedName("250") val thumb250: String? = null,
    @SerializedName("500") val thumb500: String? = null,
    @SerializedName("1200") val thumb1200: String? = null,
    @SerializedName("small") val small: String? = null,
    @SerializedName("large") val large: String? = null
)
