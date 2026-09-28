package za.kilowatch.ultimatefilemanager.audio

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import za.kilowatch.ultimatefilemanager.audio.covers.model.CoverSearchResult
import za.kilowatch.ultimatefilemanager.audio.covers.model.CoverSource
import za.kilowatch.ultimatefilemanager.audio.covers.model.DeezerSearchResponse
import za.kilowatch.ultimatefilemanager.audio.covers.model.ItunesSearchResponse
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.CaaReleaseResponse
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbRecordingSearchResponse
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbReleaseDetails
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbReleaseSearchResponse

class MusicBrainzModelsTest {

    private val gson = Gson()

    @Test
    fun testParseRecordingSearchResponse() {
        val json = """
            {
              "count": 1,
              "offset": 0,
              "recordings": [
                {
                  "id": "rec-12345",
                  "score": 100,
                  "title": "Bohemian Rhapsody",
                  "length": 354000,
                  "artist-credit": [
                    { "name": "Queen" }
                  ],
                  "releases": [
                    {
                      "id": "rel-9999",
                      "title": "A Night at the Opera",
                      "date": "1975-11-21",
                      "country": "GB",
                      "track-count": 12
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, MbRecordingSearchResponse::class.java)
        assertEquals(1, response.count)
        val rec = response.recordings.first()
        assertEquals("rec-12345", rec.id)
        assertEquals("Bohemian Rhapsody", rec.title)
        assertEquals("Queen", rec.displayArtist())
        assertEquals(1, rec.releases.size)
        assertEquals("1975", rec.releases.first().releaseYear())
    }

    @Test
    fun testParseReleaseSearchResponse() {
        val json = """
            {
              "count": 1,
              "releases": [
                {
                  "id": "rel-abc",
                  "title": "Abbey Road",
                  "date": "1969-09-26",
                  "country": "GB",
                  "track-count": 17,
                  "artist-credit": [
                    { "name": "The Beatles" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, MbReleaseSearchResponse::class.java)
        assertEquals(1, response.releases.size)
        val rel = response.releases.first()
        assertEquals("The Beatles", rel.displayArtist())
        assertEquals("1969", rel.releaseYear())
    }

    @Test
    fun testParseReleaseDetailsWithTracks() {
        val json = """
            {
              "id": "rel-detail-1",
              "title": "Dark Side of the Moon",
              "date": "1973-03-01",
              "artist-credit": [{ "name": "Pink Floyd" }],
              "genres": [{ "name": "Progressive Rock" }],
              "media": [
                {
                  "position": 1,
                  "format": "Vinyl",
                  "track-count": 10,
                  "tracks": [
                    {
                      "id": "track-1",
                      "position": 1,
                      "number": "A1",
                      "title": "Speak to Me"
                    },
                    {
                      "id": "track-2",
                      "position": 2,
                      "number": "A2",
                      "title": "Breathe"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val details = gson.fromJson(json, MbReleaseDetails::class.java)
        assertEquals("Dark Side of the Moon", details.title)
        assertEquals("Pink Floyd", details.displayArtist())
        assertEquals("Progressive Rock", details.primaryGenre())
        assertEquals("1973", details.releaseYear())
        assertEquals(1, details.media.size)
        assertEquals(2, details.media.first().tracks.size)
        assertEquals("Speak to Me", details.media.first().tracks[0].title)
    }

    @Test
    fun testParseCoverArtArchiveResponse() {
        val json = """
            {
              "images": [
                {
                  "id": 123456,
                  "image": "http://coverartarchive.org/release/rel-1/123456.jpg",
                  "front": true,
                  "back": false,
                  "thumbnails": {
                    "250": "http://coverartarchive.org/release/rel-1/123456-250.jpg",
                    "500": "http://coverartarchive.org/release/rel-1/123456-500.jpg",
                    "1200": "http://coverartarchive.org/release/rel-1/123456-1200.jpg"
                  }
                }
              ]
            }
        """.trimIndent()

        val caa = gson.fromJson(json, CaaReleaseResponse::class.java)
        assertEquals(1, caa.images.size)
        val img = caa.images.first()
        assertTrue(img.isFront)
        assertEquals("http://coverartarchive.org/release/rel-1/123456-1200.jpg", img.thumbnails?.thumb1200)
    }

    @Test
    fun testItunesHighResReplacement() {
        val json = """
            {
              "resultCount": 1,
              "results": [
                {
                  "collectionId": 1234,
                  "artistName": "Coldplay",
                  "collectionName": "Parachutes",
                  "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music123/v4/source/100x100bb.jpg",
                  "releaseDate": "2000-07-10T07:00:00Z"
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, ItunesSearchResponse::class.java)
        val album = response.results.first()
        val highRes = album.highResArtworkUrl()
        assertNotNull(highRes)
        assertTrue(highRes!!.contains("3000x3000bb.jpg"))
        assertEquals("2000", album.releaseYear())
    }

    @Test
    fun testDeezerModelParsing() {
        val json = """
            {
              "total": 1,
              "data": [
                {
                  "id": 4321,
                  "title": "Discovery",
                  "cover_xl": "https://e-cdns-images.dzcdn.net/images/cover/1000x1000-000000-80-0-0.jpg",
                  "artist": {
                    "id": 27,
                    "name": "Daft Punk"
                  }
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, DeezerSearchResponse::class.java)
        val item = response.data.first()
        assertEquals("Discovery", item.title)
        assertEquals("Daft Punk", item.artist?.name)
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/1000x1000-000000-80-0-0.jpg", item.coverXl)
    }

    @Test
    fun testCoverSearchResultResolutionLabel() {
        val result1 = CoverSearchResult(
            thumbUrl = "thumb",
            fullImageUrl = "full",
            source = CoverSource.APPLE_MUSIC,
            width = 3000,
            height = 3000
        )
        assertEquals("3000×3000", result1.resolutionLabel())

        val result2 = CoverSearchResult(
            thumbUrl = "thumb",
            fullImageUrl = "full",
            source = CoverSource.DEEZER,
            width = 0,
            height = 0
        )
        assertEquals("High Res", result2.resolutionLabel())
    }
}
