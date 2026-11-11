package za.kilowatch.ultimatefilemanager.viewer.openwith

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreUriResolverTest {

    private lateinit var context: android.content.Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        tempDir = File(context.filesDir, "test_media_dir").apply { mkdirs() }
        MediaStoreUriResolver.queryOverride = null
    }

    @After
    fun tearDown() {
        MediaStoreUriResolver.queryOverride = null
        tempDir.deleteRecursively()
    }

    @Test
    fun testResolve_nullOrNonExistentFile_returnsNull() {
        assertNull(MediaStoreUriResolver.resolve(context, null))

        val nonExistent = File("/storage/emulated/0/Movies/ghost.mp4")
        assertNull(MediaStoreUriResolver.resolve(context, nonExistent))
    }

    @Test
    fun testResolve_nonMediaExtension_returnsNull() {
        val textFile = File(tempDir, "document.txt").apply { writeText("Hello") }
        assertNull(MediaStoreUriResolver.resolve(context, textFile))

        val pdfFile = File(tempDir, "manual.pdf").apply { writeText("%PDF-1.4") }
        assertNull(MediaStoreUriResolver.resolve(context, pdfFile))

        val zipFile = File(tempDir, "archive.zip").apply { writeText("PK") }
        assertNull(MediaStoreUriResolver.resolve(context, zipFile))
    }

    @Test
    fun testResolve_privateCacheOrFilesDir_returnsNull() {
        // Files located inside context.cacheDir or context.filesDir are excluded from MediaStore
        val cacheFile = File(context.cacheDir, "sample.mp4").apply { writeText("dummy") }
        assertNull(MediaStoreUriResolver.resolve(context, cacheFile))

        val internalFile = File(context.filesDir, "sample.mp3").apply { writeText("dummy") }
        assertNull(MediaStoreUriResolver.resolve(context, internalFile))
    }

    @Test
    fun testResolve_indexedVideoFile_returnsMediaStoreUri() {
        val testVideo = File(tempDir, "video.mp4").apply { writeText("video content") }
        val simulatedUri = Uri.parse("content://media/external/video/media/98765")

        MediaStoreUriResolver.queryOverride = { _, file, _ ->
            if (file.name == "video.mp4") simulatedUri else null
        }

        val resolved = MediaStoreUriResolver.resolve(context, testVideo, "video/mp4")
        assertNotNull(resolved)
        assertEquals(simulatedUri, resolved)
    }

    @Test
    fun testResolve_indexedAudioFile_returnsMediaStoreUri() {
        val testAudio = File(tempDir, "song.mp3").apply { writeText("audio content") }
        val simulatedUri = Uri.parse("content://media/external/audio/media/54321")

        MediaStoreUriResolver.queryOverride = { _, file, _ ->
            if (file.name == "song.mp3") simulatedUri else null
        }

        val resolved = MediaStoreUriResolver.resolve(context, testAudio, "audio/mpeg")
        assertNotNull(resolved)
        assertEquals(simulatedUri, resolved)
    }

    @Test
    fun testResolve_unindexedMediaWithQueryFallback_returnsNullWhenNotFound() {
        // Dummy external-style path that exists
        val video = File(context.getExternalFilesDir(null), "unindexed.mkv").apply { writeText("data") }
        val resolved = MediaStoreUriResolver.resolve(context, video, "video/x-matroska", timeoutMs = 10L)
        // Without Robolectric's MediaScanner registering it, it cleanly returns null for FileProvider fallback
        assertNull(resolved)
        video.delete()
    }
}
