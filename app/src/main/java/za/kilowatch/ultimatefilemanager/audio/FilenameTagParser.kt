package za.kilowatch.ultimatefilemanager.audio

import java.io.File

/**
 * Utility for parsing audio tags from filenames and formatting filenames from audio tags.
 */
object FilenameTagParser {

    data class ParsedTags(
        val title: String? = null,
        val artist: String? = null,
        val trackNumber: String? = null
    )

    enum class PresetPattern(val label: String, val pattern: String) {
        TRACK_ARTIST_TITLE("01 - Artist - Title", "%track% - %artist% - %title%"),
        ARTIST_TITLE("Artist - Title", "%artist% - %title%"),
        TRACK_TITLE("01 - Title", "%track% - %title%"),
        TITLE_ONLY("Title Only", "%title%")
    }

    /**
     * Auto-detects and extracts artist, title, and track number from a filename.
     */
    fun parseFilename(filename: String): ParsedTags {
        val baseName = filename.substringBeforeLast('.').trim()
        if (baseName.isEmpty()) return ParsedTags()

        // Pattern 1: Track - Artist - Title (e.g., "01 - Queen - Bohemian Rhapsody" or "1. Queen - Bohemian Rhapsody")
        val regexTrackArtistTitle = Regex("""^(\d{1,3})[\s.\-_]+(.+?)[\s\-_]+-(.+?)$""")
        regexTrackArtistTitle.find(baseName)?.let { match ->
            val track = match.groupValues[1].trim()
            val artist = match.groupValues[2].trim()
            val title = match.groupValues[3].trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) {
                return ParsedTags(title = title, artist = artist, trackNumber = track)
            }
        }

        // Pattern 2: Artist - Title (e.g., "Queen - Bohemian Rhapsody")
        if (baseName.contains(" - ")) {
            val parts = baseName.split(" - ")
            if (parts.size == 2) {
                val first = parts[0].trim()
                val second = parts[1].trim()
                // Check if first part is just a track number
                val isFirstTrack = first.matches(Regex("""^\d{1,3}$"""))
                return if (isFirstTrack) {
                    ParsedTags(title = second, trackNumber = first)
                } else {
                    ParsedTags(title = second, artist = first)
                }
            } else if (parts.size >= 3) {
                // e.g., 01 - Queen - Bohemian Rhapsody
                val track = if (parts[0].trim().matches(Regex("""^\d{1,3}$"""))) parts[0].trim() else null
                val artist = if (track != null) parts[1].trim() else parts[0].trim()
                val title = if (track != null) parts.subList(2, parts.size).joinToString(" - ").trim()
                            else parts.subList(1, parts.size).joinToString(" - ").trim()
                return ParsedTags(title = title, artist = artist, trackNumber = track)
            }
        }

        // Pattern 3: Track. Title or Track Title (e.g. "01. Bohemian Rhapsody" or "01 Bohemian Rhapsody")
        val regexTrackTitle = Regex("""^(\d{1,3})[\s.\-_]+(.+)$""")
        regexTrackTitle.find(baseName)?.let { match ->
            val track = match.groupValues[1].trim()
            val title = match.groupValues[2].trim()
            if (title.isNotEmpty()) {
                return ParsedTags(title = title, trackNumber = track)
            }
        }

        // Default: use the entire base name as title
        return ParsedTags(title = baseName)
    }

    /**
     * Builds a clean filename from audio tags given a pattern.
     * Tokens: %artist%, %title%, %album%, %track%, %year%
     */
    fun formatFilename(tags: AudioTagData, pattern: String, extension: String): String {
        var result = pattern
        val formattedTrack = tags.trackNumber.padStart(2, '0')

        result = result.replace("%track%", if (tags.trackNumber.isNotBlank()) formattedTrack else "")
        result = result.replace("%artist%", tags.artist.trim())
        result = result.replace("%title%", tags.title.trim())
        result = result.replace("%album%", tags.album.trim())
        result = result.replace("%year%", tags.year.trim())

        // Clean up redundant separators caused by blank fields
        result = result.replace(Regex("""\s+-\s+-\s+"""), " - ")
        result = result.replace(Regex("""^[\s\-_.]+"""), "")
        result = result.replace(Regex("""[\s\-_.]+$"""), "")
        result = result.trim()

        if (result.isEmpty()) {
            result = if (tags.title.isNotBlank()) tags.title.trim() else "Track_${tags.trackNumber.ifBlank { "01" }}"
        }

        // Sanitize invalid filename characters on Android / FAT32 / NTFS
        result = sanitizeFilename(result)

        val cleanExt = extension.removePrefix(".").lowercase()
        return "$result.$cleanExt"
    }

    /**
     * Sanitizes filename removing restricted characters: \ / : * ? " < > |
     */
    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }
}
