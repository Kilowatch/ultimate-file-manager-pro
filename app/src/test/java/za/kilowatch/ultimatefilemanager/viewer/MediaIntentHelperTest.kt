package za.kilowatch.ultimatefilemanager.viewer

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaIntentHelperTest {

    @Test
    fun testAttachMediaExtras_withValidTitleAndSize() {
        val intent = Intent(Intent.ACTION_VIEW)
        val title = "Big_Buck_Bunny.mp4"
        val size = 157286400L

        MediaIntentHelper.attachMediaExtras(intent, title, size, "video/mp4")

        assertEquals("Big_Buck_Bunny.mp4", intent.getStringExtra("title"))
        assertEquals("Big_Buck_Bunny.mp4", intent.getStringExtra(MediaIntentHelper.VLC_EXTRA_ITEM_TITLE))
        assertEquals("Big_Buck_Bunny.mp4", intent.getStringExtra(MediaIntentHelper.VLC_EXTRA_MEDIA_TITLE))
        assertEquals("Big_Buck_Bunny.mp4", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("Big_Buck_Bunny.mp4", intent.getStringExtra("filename"))
        assertEquals("Big_Buck_Bunny.mp4", intent.getStringExtra("displayName"))
        assertEquals(157286400L, intent.getLongExtra("size", -1L))
    }

    @Test
    fun testAttachMediaExtras_withBlankTitleAndNegativeSize() {
        val intent = Intent(Intent.ACTION_VIEW)
        MediaIntentHelper.attachMediaExtras(intent, "", -1L, null)

        assertNull(intent.getStringExtra("title"))
        assertNull(intent.getStringExtra(MediaIntentHelper.VLC_EXTRA_ITEM_TITLE))
        assertNull(intent.getStringExtra(MediaIntentHelper.VLC_EXTRA_MEDIA_TITLE))
        assertNull(intent.getStringExtra(Intent.EXTRA_TITLE))
        assertFalse(intent.hasExtra("size"))
    }

    @Test
    fun testNormalizeMediaMimeType_videoExtensions() {
        assertEquals("video/*", MediaIntentHelper.normalizeMediaMimeType("mkv", null))
        assertEquals("video/*", MediaIntentHelper.normalizeMediaMimeType("mp4", "*/*"))
        assertEquals("video/*", MediaIntentHelper.normalizeMediaMimeType("avi", "application/octet-stream"))
        assertEquals("video/x-matroska", MediaIntentHelper.normalizeMediaMimeType("mkv", "video/x-matroska"))
        assertEquals("video/mp4", MediaIntentHelper.normalizeMediaMimeType("mp4", "video/mp4"))
    }

    @Test
    fun testNormalizeMediaMimeType_audioExtensions() {
        assertEquals("audio/*", MediaIntentHelper.normalizeMediaMimeType("flac", null))
        assertEquals("audio/*", MediaIntentHelper.normalizeMediaMimeType("mp3", "*/*"))
        assertEquals("audio/*", MediaIntentHelper.normalizeMediaMimeType("wav", "application/octet-stream"))
        assertEquals("audio/flac", MediaIntentHelper.normalizeMediaMimeType("flac", "audio/flac"))
    }

    @Test
    fun testFindLocalSubtitles_discoversMatchingSiblings() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ufm_test_subs_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val video = File(tempDir, "Episode_01.mkv").apply { writeText("dummy video") }
            File(tempDir, "Episode_01.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHello") }
            File(tempDir, "Episode_01.en.ass").apply { writeText("[Script Info]") }
            File(tempDir, "Episode_02.srt").apply { writeText("unrelated") }
            File(tempDir, "Episode_01.txt").apply { writeText("not a subtitle") }

            val found = MediaIntentHelper.findLocalSubtitles(video)
            assertEquals(2, found.size)
            val names = found.map { it.name }
            assertTrue(names.contains("Episode_01.srt"))
            assertTrue(names.contains("Episode_01.en.ass"))
            assertFalse(names.contains("Episode_02.srt"))
            assertFalse(names.contains("Episode_01.txt"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testAttachLocalSubtitles_noSubtitlesDoesNotAttach() {
        val context = RuntimeEnvironment.getApplication()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ufm_test_nosubs_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val video = File(tempDir, "SoloMovie.mp4").apply { writeText("dummy video") }
            val intent = Intent(Intent.ACTION_VIEW)

            MediaIntentHelper.attachLocalSubtitles(intent, context, video)
            assertFalse(intent.hasExtra("subs"))
            assertFalse(intent.hasExtra("subtitles_location"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
