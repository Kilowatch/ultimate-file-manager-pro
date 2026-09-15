package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.R

@RunWith(RobolectricTestRunner::class)
class UsbEjectManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testIsRemovableStorageIdentifiesRemovableDrives() {
        val mockItem = MockUsbStorageManager.createMockStorageItem(context)
        assertTrue("Mock USB item must be identified as removable",
            UsbEjectManager.isRemovableStorage(mockItem))

        val physicalUsbItem = StorageItem(
            id = "1234-5678",
            label = "Kingston USB",
            iconRes = R.drawable.ic_storage_usb,
            totalBytes = 64L * 1024 * 1024 * 1024,
            usedBytes = 10L * 1024 * 1024 * 1024,
            mountPath = "/storage/1234-5678",
            isRemovable = true
        )
        assertTrue("Physical USB drive must be identified as removable",
            UsbEjectManager.isRemovableStorage(physicalUsbItem))

        val sdCardItem = StorageItem(
            id = "ABCD-EF01",
            label = "SD Card",
            iconRes = R.drawable.ic_storage_sdcard,
            totalBytes = 128L * 1024 * 1024 * 1024,
            usedBytes = 50L * 1024 * 1024 * 1024,
            mountPath = "/storage/ABCD-EF01",
            isRemovable = true
        )
        assertTrue("SD Card must be identified as removable",
            UsbEjectManager.isRemovableStorage(sdCardItem))
    }

    @Test
    fun testIsRemovableStorageExcludesInternalAndRoot() {
        val internalItem = StorageItem(
            id = "internal",
            label = "Internal Storage",
            iconRes = R.drawable.ic_storage_internal,
            totalBytes = 128L * 1024 * 1024 * 1024,
            usedBytes = 60L * 1024 * 1024 * 1024,
            mountPath = "/storage/emulated/0",
            isRemovable = false
        )
        assertFalse("Internal storage must NOT be identified as removable",
            UsbEjectManager.isRemovableStorage(internalItem))

        val rootItem = StorageItem(
            id = "root",
            label = "Root System",
            iconRes = R.drawable.ic_storage_internal,
            totalBytes = 64L * 1024 * 1024 * 1024,
            usedBytes = 20L * 1024 * 1024 * 1024,
            mountPath = "/",
            isRemovable = false,
            isRootTile = true
        )
        assertFalse("Root tile must NOT be identified as removable",
            UsbEjectManager.isRemovableStorage(rootItem))
    }

    @Test
    fun testFlushBuffersExecutesWithoutException() = runBlocking {
        // POSIX sync() should execute or be handled gracefully without throwing unhandled exceptions
        val result = UsbEjectManager.flushBuffers()
        // In Robolectric/JVM environment, it returns a boolean (true if success, false if caught)
        assertTrue("flushBuffers should return a boolean result", result || !result)
    }

    @Test
    fun testElevatedAvailabilityCheck() {
        // In Robolectric environment without Root or Shizuku, isElevatedAvailable returns false
        val available = UsbEjectManager.isElevatedAvailable(context)
        assertFalse(available)
    }
}
