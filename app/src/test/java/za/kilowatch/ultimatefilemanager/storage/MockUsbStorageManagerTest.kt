package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MockUsbStorageManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Ensure clean state before each test
        MockUsbStorageManager.setMockUsbEnabled(context, true)
        MockUsbStorageManager.setMockUsbMounted(context, true)
    }

    @Test
    fun testMockUsbEnabledState() {
        assertTrue("Mock USB should be enabled by default when feature flag is on",
            MockUsbStorageManager.isMockUsbEnabled(context))

        MockUsbStorageManager.setMockUsbEnabled(context, false)
        assertFalse("Mock USB should be disabled after setting false",
            MockUsbStorageManager.isMockUsbEnabled(context))

        MockUsbStorageManager.setMockUsbEnabled(context, true)
        assertTrue("Mock USB should be re-enabled after setting true",
            MockUsbStorageManager.isMockUsbEnabled(context))
    }

    @Test
    fun testMockUsbMountAndUnmountState() {
        assertTrue("Mock USB should be mounted initially",
            MockUsbStorageManager.isMockUsbMounted(context))

        MockUsbStorageManager.unmountMockUsb(context)
        assertFalse("Mock USB should be unmounted after unmountMockUsb",
            MockUsbStorageManager.isMockUsbMounted(context))

        MockUsbStorageManager.remountMockUsb(context)
        assertTrue("Mock USB should be mounted after remountMockUsb",
            MockUsbStorageManager.isMockUsbMounted(context))
    }

    @Test
    fun testDisabledMockUsbReportsNotMounted() {
        MockUsbStorageManager.setMockUsbEnabled(context, false)
        assertFalse("Disabled mock USB should report not mounted",
            MockUsbStorageManager.isMockUsbMounted(context))
    }

    @Test
    fun testCreateMockStorageItem() {
        val item = MockUsbStorageManager.createMockStorageItem(context)
        assertNotNull(item)
        assertEquals(MockUsbStorageManager.MOCK_USB_ID, item.id)
        assertTrue(item.isRemovable)
        assertFalse(item.isUnmounted)
        assertTrue(item.mountPath.isNotEmpty())
        assertTrue(MockUsbStorageManager.isMockUsbItem(item))
        assertTrue(MockUsbStorageManager.isMockUsbStorageId(item.id))
        assertFalse(MockUsbStorageManager.isMockUsbStorageId("internal"))
    }

    @Test
    fun testCreateMockStorageItemWhenUnmounted() {
        MockUsbStorageManager.unmountMockUsb(context)
        val item = MockUsbStorageManager.createMockStorageItem(context)
        assertNotNull(item)
        assertEquals(MockUsbStorageManager.MOCK_USB_ID, item.id)
        assertTrue(item.isRemovable)
        assertTrue(item.isUnmounted)
        assertEquals("", item.mountPath)
        assertTrue(item.label.contains("Unmounted"))
    }

    @Test
    fun testMockUsbDirectoryAndSampleFiles() {
        val dir = MockUsbStorageManager.getMockUsbDirectory(context)
        assertTrue("Mock directory must exist", dir.exists())
        assertTrue("Mock directory must be a directory", dir.isDirectory)

        val readme = File(dir, "readme_otg.txt")
        assertTrue("Sample readme_otg.txt should be created", readme.exists())
        assertTrue("Readme should contain OTG text", readme.readText().contains("USB OTG"))

        val docsDir = File(dir, "Documents")
        assertTrue("Documents subdirectory should exist", docsDir.exists())

        val photosDir = File(dir, "Photos")
        assertTrue("Photos subdirectory should exist", photosDir.exists())

        val backupsDir = File(dir, "Backups")
        assertTrue("Backups subdirectory should exist", backupsDir.exists())
    }

    @Test
    fun testIsMockUsbPath() {
        val dir = MockUsbStorageManager.getMockUsbDirectory(context)
        val insidePath = File(dir, "readme_otg.txt").absolutePath
        val subFolderPath = File(dir, "Documents/Sample_Report.txt").absolutePath
        val outsidePath = "/storage/emulated/0/Download"

        assertTrue(MockUsbStorageManager.isMockUsbPath(context, dir.absolutePath))
        assertTrue(MockUsbStorageManager.isMockUsbPath(context, insidePath))
        assertTrue(MockUsbStorageManager.isMockUsbPath(context, subFolderPath))
        assertFalse(MockUsbStorageManager.isMockUsbPath(context, outsidePath))
    }
}
