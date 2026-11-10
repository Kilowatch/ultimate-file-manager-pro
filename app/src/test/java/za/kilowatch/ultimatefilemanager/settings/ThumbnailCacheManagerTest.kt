package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.storage.FileAdapter
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ThumbnailCacheManagerTest {

    @Test
    fun localThumbnailCacheManager_getExistingDiskThumbnail_returnsNullOnMainThread() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LocalThumbnailCacheManager.getInstance(context)

        // Robolectric runs tests on the main looper by default
        assertTrue("Test thread must be main looper", Looper.myLooper() == Looper.getMainLooper())

        val testFile = File("/storage/emulated/0/DCIM/Camera/IMG_20261110_0001.jpg")
        val result = manager.getExistingDiskThumbnail(testFile)

        assertNull("getExistingDiskThumbnail must immediately return null on main thread without blocking", result)
    }

    @Test
    fun networkThumbnailCacheManager_getExistingDiskThumbnail_returnsNullOnMainThread() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = NetworkThumbnailCacheManager(context)

        assertTrue("Test thread must be main looper", Looper.myLooper() == Looper.getMainLooper())

        val testShare = NetworkShare(
            id = "test_share_1",
            type = ShareType.SMB,
            name = "NAS",
            host = "192.168.1.100"
        )
        val testFile = NetworkFile(
            name = "movie.mp4",
            path = "/share/Videos/movie.mp4",
            isDirectory = false,
            size = 1024L * 1024L
        )

        val result = manager.getExistingDiskThumbnail(testShare, testFile)

        assertNull("getExistingDiskThumbnail must immediately return null on main thread without blocking", result)
    }

    @Test
    fun fileAdapter_thumbnailPathCache_inMemoryOperations() {
        val path = "/storage/emulated/0/Download/sample.png"
        val cachedDisk = "/data/user/0/za.kilowatch.ultimatefilemanager/cache/thumb_sample.webp"

        FileAdapter.putCachedPath(path, cachedDisk)
        assertTrue(FileAdapter.getCachedPath(path) == cachedDisk)

        FileAdapter.clearCacheForPath(path)
        assertNull(FileAdapter.getCachedPath(path))
    }
}
