package za.kilowatch.ultimatefilemanager.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilenameTagParserTest {

    @Test
    fun testParseFilename_trackArtistTitle() {
        val parsed = FilenameTagParser.parseFilename("01 - Queen - Bohemian Rhapsody.mp3")
        assertEquals("01", parsed.trackNumber)
        assertEquals("Queen", parsed.artist)
        assertEquals("Bohemian Rhapsody", parsed.title)
    }

    @Test
    fun testParseFilename_trackDotArtistTitle() {
        val parsed = FilenameTagParser.parseFilename("05. Pink Floyd - Time.flac")
        assertEquals("05", parsed.trackNumber)
        assertEquals("Pink Floyd", parsed.artist)
        assertEquals("Time", parsed.title)
    }

    @Test
    fun testParseFilename_artistTitle() {
        val parsed = FilenameTagParser.parseFilename("Led Zeppelin - Stairway to Heaven.opus")
        assertNull(parsed.trackNumber)
        assertEquals("Led Zeppelin", parsed.artist)
        assertEquals("Stairway to Heaven", parsed.title)
    }

    @Test
    fun testParseFilename_trackTitle() {
        val parsed = FilenameTagParser.parseFilename("03 - Imagine.m4a")
        assertEquals("03", parsed.trackNumber)
        assertNull(parsed.artist)
        assertEquals("Imagine", parsed.title)
    }

    @Test
    fun testParseFilename_trackDotTitle() {
        val parsed = FilenameTagParser.parseFilename("04. Yesterday.mp3")
        assertEquals("04", parsed.trackNumber)
        assertNull(parsed.artist)
        assertEquals("Yesterday", parsed.title)
    }

    @Test
    fun testParseFilename_titleOnly() {
        val parsed = FilenameTagParser.parseFilename("Hotel California.wav")
        assertNull(parsed.trackNumber)
        assertNull(parsed.artist)
        assertEquals("Hotel California", parsed.title)
    }

    @Test
    fun testFormatFilename_standardPattern() {
        val data = AudioTagData(
            title = "Comfortably Numb",
            artist = "Pink Floyd",
            album = "The Wall",
            trackNumber = "6",
            year = "1979"
        )
        val formatted = FilenameTagParser.formatFilename(
            tags = data,
            pattern = "%track% - %artist% - %title%",
            extension = "flac"
        )
        assertEquals("06 - Pink Floyd - Comfortably Numb.flac", formatted)
    }

    @Test
    fun testFormatFilename_sanitization() {
        val data = AudioTagData(
            title = "AC/DC - Highway to Hell: Remaster?",
            artist = "AC/DC",
            trackNumber = "1"
        )
        val formatted = FilenameTagParser.formatFilename(
            tags = data,
            pattern = "%artist% - %title%",
            extension = "mp3"
        )
        // Slashes and colons/question marks should be replaced with underscores
        assertEquals("AC_DC - AC_DC - Highway to Hell_ Remaster_.mp3", formatted)
    }
}
