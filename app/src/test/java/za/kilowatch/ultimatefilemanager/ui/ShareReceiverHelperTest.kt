package za.kilowatch.ultimatefilemanager.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareReceiverHelperTest {

    @Test
    fun testExtractUris_actionSend_extraStream() {
        val testUri = Uri.parse("content://media/external/images/media/101")
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, testUri)
        }

        val uris = ShareReceiverHelper.extractUris(intent)
        assertEquals(1, uris.size)
        assertEquals(testUri, uris[0])
    }

    @Test
    fun testExtractUris_actionSendMultiple_extraStream() {
        val uri1 = Uri.parse("content://media/external/images/media/101")
        val uri2 = Uri.parse("content://media/external/images/media/102")
        val uri3 = Uri.parse("content://media/external/images/media/103")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri1, uri2, uri3))
        }

        val uris = ShareReceiverHelper.extractUris(intent)
        assertEquals(3, uris.size)
        assertEquals(listOf(uri1, uri2, uri3), uris)
    }

    @Test
    fun testExtractUris_clipDataFallback() {
        val uri1 = Uri.parse("content://media/external/images/media/201")
        val uri2 = Uri.parse("content://media/external/images/media/202")

        val clipData = ClipData(
            ClipDescription("shared files", arrayOf("image/*")),
            ClipData.Item(uri1)
        ).apply {
            addItem(ClipData.Item(uri2))
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.clipData = clipData
        }

        val uris = ShareReceiverHelper.extractUris(intent)
        assertEquals(2, uris.size)
        assertEquals(listOf(uri1, uri2), uris)
    }

    @Test
    fun testExtractUris_nullOrEmptyIntent() {
        assertTrue(ShareReceiverHelper.extractUris(null).isEmpty())
        assertTrue(ShareReceiverHelper.extractUris(Intent()).isEmpty())
    }

    @Test
    fun testSanitizeFileName() {
        assertEquals("valid_name.jpg", ShareReceiverHelper.sanitizeFileName("valid_name.jpg"))
        assertEquals("illegal_chars___test.pdf", ShareReceiverHelper.sanitizeFileName("illegal/chars:?*test.pdf"))
        assertEquals("trimmed.png", ShareReceiverHelper.sanitizeFileName("   trimmed.png   "))
        assertEquals("shared_file", ShareReceiverHelper.sanitizeFileName("   "))
    }

    @Test
    fun testDeduplicateItemNames() {
        val uri1 = Uri.parse("content://media/1")
        val uri2 = Uri.parse("content://media/2")
        val uri3 = Uri.parse("content://media/3")

        val items = listOf(
            SharedItem(uri1, "photo.jpg", 100L, "image/jpeg"),
            SharedItem(uri2, "photo.jpg", 200L, "image/jpeg"),
            SharedItem(uri3, "photo.jpg", 300L, "image/jpeg")
        )

        val deduplicated = ShareReceiverHelper.deduplicateItemNames(items)
        assertEquals(3, deduplicated.size)
        assertEquals("photo.jpg", deduplicated[0].fileName)
        assertEquals("photo_(1).jpg", deduplicated[1].fileName)
        assertEquals("photo_(2).jpg", deduplicated[2].fileName)
    }

    @Test
    fun testDeduplicateItemNames_noExtension() {
        val uri1 = Uri.parse("content://media/1")
        val uri2 = Uri.parse("content://media/2")

        val items = listOf(
            SharedItem(uri1, "document", 100L, "application/octet-stream"),
            SharedItem(uri2, "document", 200L, "application/octet-stream")
        )

        val deduplicated = ShareReceiverHelper.deduplicateItemNames(items)
        assertEquals(2, deduplicated.size)
        assertEquals("document", deduplicated[0].fileName)
        assertEquals("document_(1)", deduplicated[1].fileName)
    }
}
