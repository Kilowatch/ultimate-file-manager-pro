package za.kilowatch.ultimatefilemanager.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.smartsort.SmartSortCategory
import za.kilowatch.ultimatefilemanager.storage.SortFilterSheet
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import za.kilowatch.ultimatefilemanager.util.MimeTypeHelper

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MediaFormatSupportTest {

    private val newVideoExtensions = listOf(
        "mjpeg", "mjpg", "mjp",
        "m4v", "qt", "3g2", "3gp2",
        "m2ts", "mts", "m2t", "tp", "trp",
        "vob", "evo", "mpg", "mpeg", "mpe", "m1v", "m2v", "mpv",
        "f4v", "ogv", "ogm", "rm", "rmvb", "asf", "wm",
        "mxf", "dv", "divx", "xvid", "mk3d"
    )

    private val newImageExtensions = listOf(
        // Camera RAW
        "cr3", "crw", "raf", "rw2", "orf", "pef", "ptx", "nrw", "srw", "srf", "sr2",
        "x3f", "erf", "kdc", "dcr", "k25", "mrw", "mos",
        // Modern & HDR Stills
        "hif", "avifs", "hdr", "exr", "mpo", "jps", "pns",
        // Design, Textures & Bitmaps
        "psd", "psb", "ai", "xcf", "kra", "clip",
        "tga", "targa", "dds", "wbmp", "cur", "ani", "pbm", "pgm", "ppm", "pnm", "pcx", "wmf", "emf"
    )

    @Test
    fun testVideoExtensions_areRecognizedAcrossAllComponents() {
        for (ext in newVideoExtensions) {
            assertTrue("Video extension '$ext' should be openable internally", FileViewerRouter.canOpenInternally(ext))
            assertTrue("Video extension '$ext' should be recognized as video by FileViewerRouter", FileViewerRouter.isVideo(ext))
            assertTrue("Video extension '$ext' should be in SortFilterSheet.VIDEO_EXTENSIONS", ext in SortFilterSheet.VIDEO_EXTENSIONS)
            assertTrue("Video extension '$ext' should be in SmartSortCategory.VIDEOS", ext in SmartSortCategory.VIDEOS.extensions)
            assertEquals("Video extension '$ext' should map to ic_file_video", R.drawable.ic_file_video, FileTypeIconProvider.iconForExtension(ext))

            val mime = MimeTypeHelper.getOrFallback(ext)
            assertTrue("Video extension '$ext' MIME '$mime' should not be generic octet-stream", mime != "application/octet-stream")
            assertTrue("Video extension '$ext' MIME '$mime' should be video or mxf", mime.startsWith("video/") || mime == "application/mxf")
        }
    }

    @Test
    fun testImageExtensions_areRecognizedAcrossAllComponents() {
        for (ext in newImageExtensions) {
            assertTrue("Image extension '$ext' should be openable internally", FileViewerRouter.canOpenInternally(ext))
            assertTrue("Image extension '$ext' should be in FileViewerRouter.IMAGE_EXTENSIONS", ext in FileViewerRouter.IMAGE_EXTENSIONS)
            assertTrue("Image extension '$ext' should be in SortFilterSheet.IMAGE_EXTENSIONS", ext in SortFilterSheet.IMAGE_EXTENSIONS)
            assertTrue("Image extension '$ext' should be in SmartSortCategory.PHOTOS", ext in SmartSortCategory.PHOTOS.extensions)
            assertEquals("Image extension '$ext' should map to ic_file_image", R.drawable.ic_file_image, FileTypeIconProvider.iconForExtension(ext))

            val mime = MimeTypeHelper.getOrFallback(ext)
            assertTrue("Image extension '$ext' MIME '$mime' should not be generic octet-stream", mime != "application/octet-stream")
        }
    }

    @Test
    fun testMjpegExtractor_recognizesAndExtractsFrames() {
        val extractor = za.kilowatch.ultimatefilemanager.media.MjpegExtractor()
        // Build synthetic JPEG SOI + SOF0 (1920x1080) + EOI
        val frame1 = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), // SOI
            0xFF.toByte(), 0xC0.toByte(), 0x00.toByte(), 0x11.toByte(), 0x08.toByte(), // SOF0 length 17, precision 8
            0x04.toByte(), 0x38.toByte(), // height 1080 (0x0438)
            0x07.toByte(), 0x80.toByte(), // width 1920 (0x0780)
            0x03.toByte(), 0x01.toByte(), 0x11.toByte(), 0x00.toByte(), 0x02.toByte(), 0x11.toByte(), 0x01.toByte(), 0x03.toByte(), 0x11.toByte(), 0x01.toByte(),
            0xFF.toByte(), 0xD9.toByte()  // EOI
        )
        val streamBytes = frame1 + frame1
        val dataSource = androidx.media3.datasource.ByteArrayDataSource(streamBytes)
        val dataSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse("file://test.mjpeg"))
        dataSource.open(dataSpec)
        val extractorInput = androidx.media3.extractor.DefaultExtractorInput(dataSource, 0, streamBytes.size.toLong())

        assertTrue("MjpegExtractor should sniff synthetic JPEG stream", extractor.sniff(extractorInput))
    }
}
