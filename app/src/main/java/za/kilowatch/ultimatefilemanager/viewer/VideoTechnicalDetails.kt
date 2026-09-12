package za.kilowatch.ultimatefilemanager.viewer

import java.io.Serializable

/**
 * Data models representing rich technical metadata of a media item in UFM Player.
 */
data class VideoTechnicalDetails(
    // Container & File Properties
    val fileName: String,
    val filePath: String,
    val containerFormat: String,
    val fileSizeFormatted: String,
    val durationFormatted: String,
    val overallBitrateFormatted: String? = null,

    // Video Stream
    val hasVideo: Boolean = false,
    val videoCodec: String? = null,
    val resolution: String? = null,
    val resolutionLabel: String? = null,
    val frameRate: String? = null,
    val videoBitrate: String? = null,
    val hdrFormat: String? = null,
    val colorSpace: String? = null,
    val colorRange: String? = null,
    val bitDepth: String? = null,
    val pixelFormat: String? = null,

    // Audio & Subtitle Streams
    val audioTracks: List<AudioTrackTechnicalDetails> = emptyList(),
    val subtitleTracks: List<SubtitleTrackTechnicalDetails> = emptyList()
) : Serializable {

    /**
     * Generates a clean, MediaInfo-style plain-text summary suitable for copying to clipboard.
     */
    fun toFormattedSummary(): String {
        val sb = StringBuilder()
        sb.append("=== GENERAL ===\n")
        sb.append("Filename: ").append(fileName).append("\n")
        if (filePath.isNotEmpty()) sb.append("Location: ").append(filePath).append("\n")
        sb.append("Container: ").append(containerFormat).append("\n")
        sb.append("File Size: ").append(fileSizeFormatted).append("\n")
        sb.append("Duration: ").append(durationFormatted).append("\n")
        if (!overallBitrateFormatted.isNullOrEmpty()) {
            sb.append("Overall Bitrate: ").append(overallBitrateFormatted).append("\n")
        }

        if (hasVideo) {
            sb.append("\n=== VIDEO ===\n")
            sb.append("Codec: ").append(videoCodec ?: "N/A").append("\n")
            sb.append("Resolution: ").append(resolution ?: "N/A")
            if (!resolutionLabel.isNullOrEmpty()) sb.append(" (").append(resolutionLabel).append(")")
            sb.append("\n")
            if (!frameRate.isNullOrEmpty()) sb.append("Frame Rate: ").append(frameRate).append("\n")
            if (!videoBitrate.isNullOrEmpty()) sb.append("Bitrate: ").append(videoBitrate).append("\n")
            if (!hdrFormat.isNullOrEmpty()) sb.append("Dynamic Range: ").append(hdrFormat).append("\n")
            if (!colorSpace.isNullOrEmpty()) sb.append("Color Space: ").append(colorSpace).append("\n")
            if (!colorRange.isNullOrEmpty()) sb.append("Color Range: ").append(colorRange).append("\n")
            if (!bitDepth.isNullOrEmpty()) sb.append("Bit Depth: ").append(bitDepth).append("\n")
            if (!pixelFormat.isNullOrEmpty()) sb.append("Pixel Format: ").append(pixelFormat).append("\n")
        }

        if (audioTracks.isNotEmpty()) {
            sb.append("\n=== AUDIO (").append(audioTracks.size).append(" tracks) ===\n")
            audioTracks.forEachIndexed { i, track ->
                sb.append("#").append(i + 1).append(" - ").append(track.title)
                if (track.isSelected) sb.append(" [ACTIVE]")
                sb.append("\n")
                if (!track.codec.isNullOrEmpty()) sb.append("  Codec: ").append(track.codec).append("\n")
                if (!track.channels.isNullOrEmpty()) sb.append("  Channels: ").append(track.channels).append("\n")
                if (!track.sampleRate.isNullOrEmpty()) sb.append("  Sample Rate: ").append(track.sampleRate).append("\n")
                if (!track.bitrate.isNullOrEmpty()) sb.append("  Bitrate: ").append(track.bitrate).append("\n")
                if (!track.language.isNullOrEmpty()) sb.append("  Language: ").append(track.language).append("\n")
            }
        }

        if (subtitleTracks.isNotEmpty()) {
            sb.append("\n=== SUBTITLES (").append(subtitleTracks.size).append(" tracks) ===\n")
            subtitleTracks.forEachIndexed { i, track ->
                sb.append("#").append(i + 1).append(" - ").append(track.title)
                if (track.isSelected) sb.append(" [ACTIVE]")
                sb.append("\n")
                if (!track.format.isNullOrEmpty()) sb.append("  Format: ").append(track.format).append("\n")
                if (!track.language.isNullOrEmpty()) sb.append("  Language: ").append(track.language).append("\n")
                sb.append("  Source: ").append(track.source).append("\n")
            }
        }

        return sb.toString().trimEnd()
    }
}

data class AudioTrackTechnicalDetails(
    val index: Int,
    val title: String,
    val codec: String?,
    val channels: String?,
    val sampleRate: String?,
    val bitrate: String?,
    val language: String?,
    val isSelected: Boolean
) : Serializable

data class SubtitleTrackTechnicalDetails(
    val index: Int,
    val title: String,
    val format: String?,
    val language: String?,
    val source: String,
    val isSelected: Boolean
) : Serializable
