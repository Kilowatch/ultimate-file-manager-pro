package za.kilowatch.ultimatefilemanager.audio

/**
 * Encapsulates audio metadata tags and technical properties for an audio file.
 */
data class AudioTagData(
    var title: String = "",
    var artist: String = "",
    var album: String = "",
    var albumArtist: String = "",
    var year: String = "",
    var genre: String = "",
    var trackNumber: String = "",
    var trackTotal: String = "",
    var discNumber: String = "",
    var discTotal: String = "",
    var composer: String = "",
    var comment: String = "",
    var lyrics: String = "",
    var artworkBytes: ByteArray? = null,
    var artworkMime: String? = null,

    // Read-only technical audio information
    val format: String = "",
    val bitrate: String = "",
    val sampleRate: String = "",
    val channels: String = "",
    val durationSeconds: Long = 0L,
    val fileSizeBytes: Long = 0L
) {
    /**
     * Formats duration into mm:ss or hh:mm:ss.
     */
    fun formattedDuration(): String {
        if (durationSeconds <= 0) return "--:--"
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioTagData

        if (title != other.title) return false
        if (artist != other.artist) return false
        if (album != other.album) return false
        if (albumArtist != other.albumArtist) return false
        if (year != other.year) return false
        if (genre != other.genre) return false
        if (trackNumber != other.trackNumber) return false
        if (trackTotal != other.trackTotal) return false
        if (discNumber != other.discNumber) return false
        if (discTotal != other.discTotal) return false
        if (composer != other.composer) return false
        if (comment != other.comment) return false
        if (lyrics != other.lyrics) return false
        if (artworkBytes != null) {
            if (other.artworkBytes == null) return false
            if (!artworkBytes.contentEquals(other.artworkBytes)) return false
        } else if (other.artworkBytes != null) return false
        if (artworkMime != other.artworkMime) return false

        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + albumArtist.hashCode()
        result = 31 * result + year.hashCode()
        result = 31 * result + genre.hashCode()
        result = 31 * result + trackNumber.hashCode()
        result = 31 * result + trackTotal.hashCode()
        result = 31 * result + discNumber.hashCode()
        result = 31 * result + discTotal.hashCode()
        result = 31 * result + composer.hashCode()
        result = 31 * result + comment.hashCode()
        result = 31 * result + lyrics.hashCode()
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        result = 31 * result + (artworkMime?.hashCode() ?: 0)
        return result
    }
}
