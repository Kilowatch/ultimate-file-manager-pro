package za.kilowatch.ultimatefilemanager.viewer

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VideoInfoExtractorTest {

    @Test
    fun testVideoCodecNames() {
        assertEquals("HEVC / H.265", VideoInfoExtractor.getVideoCodecName("video/hevc", null))
        assertEquals("HEVC / H.265 (hev1.1.6.L120.90)", VideoInfoExtractor.getVideoCodecName("video/hevc", "hev1.1.6.L120.90"))
        assertEquals("AVC / H.264", VideoInfoExtractor.getVideoCodecName("video/avc", null))
        assertEquals("AVC / H.264 (avc1.640028)", VideoInfoExtractor.getVideoCodecName("video/avc", "avc1.640028"))
        assertEquals("AV1", VideoInfoExtractor.getVideoCodecName("video/av01", null))
        assertEquals("VP9", VideoInfoExtractor.getVideoCodecName("video/x-vnd.on2.vp9", null))
        assertEquals("Dolby Vision", VideoInfoExtractor.getVideoCodecName("video/dolby-vision", null))
    }

    @Test
    fun testAudioCodecNames() {
        assertEquals("AAC", VideoInfoExtractor.getAudioCodecName("audio/mp4a-latm", null))
        assertEquals("Dolby Digital (AC-3)", VideoInfoExtractor.getAudioCodecName("audio/ac3", null))
        assertEquals("Dolby Digital Plus (E-AC-3)", VideoInfoExtractor.getAudioCodecName("audio/eac3", null))
        assertEquals("Dolby Digital Plus with Atmos", VideoInfoExtractor.getAudioCodecName("audio/eac3-joc", null))
        assertEquals("Dolby TrueHD", VideoInfoExtractor.getAudioCodecName("audio/true-hd", null))
        assertEquals("DTS Digital Surround", VideoInfoExtractor.getAudioCodecName("audio/vnd.dts", null))
        assertEquals("FLAC Lossless", VideoInfoExtractor.getAudioCodecName("audio/flac", null))
        assertEquals("Opus", VideoInfoExtractor.getAudioCodecName("audio/opus", null))
    }

    @Test
    fun testResolutionLabels() {
        val fhd = VideoInfoExtractor.getResolutionLabel(1920, 1080)
        assertTrue(fhd.contains("1080p FHD"))
        assertTrue(fhd.contains("16:9"))

        val uhd = VideoInfoExtractor.getResolutionLabel(3840, 2160)
        assertTrue(uhd.contains("4K UHD"))
        assertTrue(uhd.contains("16:9"))

        val cinemascope = VideoInfoExtractor.getResolutionLabel(1920, 804)
        assertTrue(cinemascope.contains("Cinemascope") || cinemascope.contains("2.39:1"))
    }

    @Test
    fun testChannelLayouts() {
        assertEquals("1.0 (Mono)", VideoInfoExtractor.getChannelLayout(1))
        assertEquals("2.0 (Stereo)", VideoInfoExtractor.getChannelLayout(2))
        assertEquals("5.1 Surround (6 ch)", VideoInfoExtractor.getChannelLayout(6))
        assertEquals("7.1 Surround (8 ch)", VideoInfoExtractor.getChannelLayout(8))
    }

    @Test
    fun testSubtitleFormatNames() {
        assertEquals("WebVTT", VideoInfoExtractor.getSubtitleFormatName("text/vtt"))
        assertEquals("SRT (SubRip)", VideoInfoExtractor.getSubtitleFormatName("application/x-subrip"))
        assertEquals("ASS / SSA", VideoInfoExtractor.getSubtitleFormatName("text/x-ssa"))
        assertEquals("PGS (Blu-ray)", VideoInfoExtractor.getSubtitleFormatName("application/pgs"))
    }

    @Test
    fun testVideoInfoExtractor_fullExtraction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Construct 4K HDR10 Video Format
        val colorInfo = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .build()

        val videoFormat = Format.Builder()
            .setSampleMimeType("video/hevc")
            .setCodecs("hev1.1.6.L120.90")
            .setWidth(3840)
            .setHeight(2160)
            .setFrameRate(23.976f)
            .setAverageBitrate(18_500_000)
            .setColorInfo(colorInfo)
            .build()

        // Construct 5.1 AC-3 Audio Format
        val audioFormat = Format.Builder()
            .setSampleMimeType("audio/ac3")
            .setChannelCount(6)
            .setSampleRate(48000)
            .setAverageBitrate(640_000)
            .setLanguage("eng")
            .setLabel("English 5.1")
            .build()

        // Construct Subtitle Format
        val subFormat = Format.Builder()
            .setSampleMimeType("application/x-subrip")
            .setLanguage("eng")
            .setLabel("English Full")
            .build()

        val videoGroup = TrackGroup("video", videoFormat)
        val audioGroup = TrackGroup("audio", audioFormat)
        val subGroup = TrackGroup("sub", subFormat)

        val tracks = Tracks(
            listOf(
                Tracks.Group(videoGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                Tracks.Group(audioGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                Tracks.Group(subGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
            )
        )

        val queueItem = QueueItem(
            path = "/storage/emulated/0/Movies/sample.mkv",
            title = "Sample 4K Movie",
            duration = 3600_000L, // 1 hour
            isVideo = true,
            fileSize = 8_500_000_000L
        )

        val details = VideoInfoExtractor.extract(
            context = context,
            player = null,
            currentTrackInfo = queueItem,
            tracks = tracks,
            externalSubtitles = emptyList()
        )

        assertEquals("Sample 4K Movie", details.fileName)
        assertEquals("MKV (Matroska)", details.containerFormat)
        assertTrue(details.hasVideo)
        assertNotNull(details.videoCodec)
        assertTrue(details.videoCodec!!.contains("HEVC"))
        assertEquals("3840 × 2160", details.resolution)
        assertTrue(details.resolutionLabel!!.contains("4K UHD"))
        assertEquals("23.976 fps", details.frameRate)
        assertEquals("18.5 Mbps", details.videoBitrate)
        assertTrue(details.hdrFormat!!.contains("HDR10"))
        assertTrue(details.colorSpace!!.contains("BT.2020"))
        assertEquals("10-bit", details.bitDepth)

        // Verify Audio
        assertEquals(1, details.audioTracks.size)
        val audio = details.audioTracks[0]
        assertEquals("English 5.1", audio.title)
        assertTrue(audio.codec!!.contains("Dolby Digital"))
        assertEquals("5.1 Surround (6 ch)", audio.channels)
        assertEquals("48.0 kHz", audio.sampleRate)
        assertEquals("640 kbps", audio.bitrate)
        assertTrue(audio.isSelected)

        // Verify Subtitle
        assertEquals(1, details.subtitleTracks.size)
        val sub = details.subtitleTracks[0]
        assertEquals("English Full", sub.title)
        assertEquals("SRT (SubRip)", sub.format)
        assertEquals("Embedded", sub.source)
        assertTrue(sub.isSelected)

        // Verify summary text generation
        val summary = details.toFormattedSummary()
        assertTrue(summary.contains("=== GENERAL ==="))
        assertTrue(summary.contains("=== VIDEO ==="))
        assertTrue(summary.contains("=== AUDIO (1 tracks) ==="))
        assertTrue(summary.contains("=== SUBTITLES (1 tracks) ==="))
        assertTrue(summary.contains("HDR10"))
    }

    @Test
    fun testVideoInfoExtractor_nullCurrentTrackInfo_usesFallbackPathAndSize() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val details = VideoInfoExtractor.extract(
            context = context,
            player = null,
            currentTrackInfo = null,
            tracks = null,
            externalSubtitles = emptyList(),
            fallbackPath = "/storage/emulated/0/Movies/sample_clip.mp4",
            fallbackFileSize = 15_728_640L
        )

        assertEquals("sample_clip.mp4", details.fileName)
        assertEquals("/storage/emulated/0/Movies/sample_clip.mp4", details.filePath)
        assertEquals("MP4 (MPEG-4 Part 14)", details.containerFormat)
        assertFalse(details.fileSizeFormatted == "N/A")
        assertTrue(details.fileSizeFormatted.contains("15,728,640 bytes"))
    }

    @Test
    fun testVideoInfoExtractor_localFileResolutionWhenFileSizeIsZero() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tempFile = java.io.File.createTempFile("test_video", ".mkv")
        try {
            tempFile.writeBytes(ByteArray(2048) { 1.toByte() })

            val queueItem = QueueItem(
                path = tempFile.absolutePath,
                fileSize = 0L
            )

            val details = VideoInfoExtractor.extract(
                context = context,
                player = null,
                currentTrackInfo = queueItem,
                tracks = null
            )

            assertEquals(tempFile.name, details.fileName)
            assertEquals(tempFile.absolutePath, details.filePath)
            assertEquals("MKV (Matroska)", details.containerFormat)
            assertFalse(details.fileSizeFormatted == "N/A")
            assertTrue(details.fileSizeFormatted.contains("2.00 kB") || details.fileSizeFormatted.contains("2 kB") || details.fileSizeFormatted.contains("2,048 bytes"))
        } finally {
            tempFile.delete()
        }
    }
}
