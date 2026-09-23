package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.tabs.StorageType
import za.kilowatch.ultimatefilemanager.tabs.TabModel
import za.kilowatch.ultimatefilemanager.tabs.TabSessionManager
import za.kilowatch.ultimatefilemanager.tabs.TabbedBrowserActivity
import za.kilowatch.ultimatefilemanager.settings.SettingsActivity
import za.kilowatch.ultimatefilemanager.MainActivity
import za.kilowatch.ultimatefilemanager.onboarding.WelcomeActivity
import za.kilowatch.ultimatefilemanager.security.SecurityUnlockActivity
import za.kilowatch.ultimatefilemanager.viewer.ImageViewerActivity
import org.robolectric.Robolectric
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LastLocationManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LastLocationManager.clear(context)
        TabSessionManager.clearSession(context)
    }

    @Test
    fun testDefaultResolvesToStorageBrowser() {
        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(StorageBrowserActivity::class.java.name, intent.component?.className)
        assertFalse(intent.getBooleanExtra(LastLocationManager.EXTRA_STORAGE_UNAVAILABLE_REDIRECT, false))
    }

    @Test
    fun testFileBrowserAvailableStorageResolvesCorrectly() {
        val testDir = File(context.filesDir, "test_mount").apply { mkdirs() }
        val testSubDir = File(testDir, "sub_folder").apply { mkdirs() }

        LastLocationManager.recordFileBrowser(
            context = context,
            mountPath = testDir.absolutePath,
            currentPath = testSubDir.absolutePath,
            storageLabel = "Test Storage",
            storageId = "test_storage_id",
            storageType = "LOCAL",
            isRemovable = false,
            isRoot = false
        )

        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(FileBrowserActivity::class.java.name, intent.component?.className)
        assertEquals(testDir.absolutePath, intent.getStringExtra(FileBrowserActivity.EXTRA_MOUNT_PATH))
        assertEquals(testSubDir.absolutePath, intent.getStringExtra(FileBrowserActivity.EXTRA_INITIAL_PATH))
        assertEquals("Test Storage", intent.getStringExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL))
        assertEquals("test_storage_id", intent.getStringExtra(FileBrowserActivity.EXTRA_STORAGE_ID))
        assertFalse(intent.getBooleanExtra(FileBrowserActivity.EXTRA_IS_REMOVABLE, true))
    }

    @Test
    fun testFileBrowserMissingStorageRedirectsToStorageBrowser() {
        LastLocationManager.recordFileBrowser(
            context = context,
            mountPath = "/storage/9999-9999/non_existent_drive",
            currentPath = "/storage/9999-9999/non_existent_drive/DCIM",
            storageLabel = "Missing Drive",
            storageId = "9999-9999",
            storageType = "REMOVABLE",
            isRemovable = true,
            isRoot = false
        )

        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(StorageBrowserActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(LastLocationManager.EXTRA_STORAGE_UNAVAILABLE_REDIRECT, false))
    }

    @Test
    fun testNetworkBrowserMissingShareRedirectsToStorageBrowser() {
        LastLocationManager.recordNetworkBrowser(
            context = context,
            shareId = "non_existent_share_uuid",
            currentPath = "/shared/folder",
            storageLabel = "SMB Share",
            isOnlineStorage = false
        )

        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(StorageBrowserActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(LastLocationManager.EXTRA_STORAGE_UNAVAILABLE_REDIRECT, false))
    }

    @Test
    fun testTabbedBrowserWithoutSavedTabsFallsBackToStorageBrowser() {
        LastLocationManager.recordTabbedBrowser(context)
        // With no saved tabs, it gracefully falls back to StorageBrowserActivity
        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(StorageBrowserActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun testTabbedBrowserWithMultipleValidSavedTabsResolvesToTabbedBrowser() {
        val testDir = File(context.filesDir, "valid_tab_storage").apply { mkdirs() }
        val tab1 = TabModel(
            title = "Internal",
            storageType = StorageType.LOCAL,
            rootPath = testDir.absolutePath,
            currentPath = testDir.absolutePath,
            storageLabel = "Internal Storage"
        )
        val tab2 = TabModel(
            title = "Tab 2",
            storageType = StorageType.LOCAL,
            rootPath = testDir.absolutePath,
            currentPath = testDir.absolutePath,
            storageLabel = "Internal Storage"
        )
        TabSessionManager.saveSession(context, listOf(tab1, tab2), tab1.id)
        LastLocationManager.recordTabbedBrowser(context)

        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(TabbedBrowserActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun testTabbedBrowserWithSingleTabResolvesToNormalFileBrowser() {
        val testDir = File(context.filesDir, "single_tab_storage").apply { mkdirs() }
        val tab = TabModel(
            title = "Internal",
            storageType = StorageType.LOCAL,
            rootPath = testDir.absolutePath,
            currentPath = testDir.absolutePath,
            storageLabel = "Internal Storage"
        )
        TabSessionManager.saveSession(context, listOf(tab), tab.id)
        LastLocationManager.recordTabbedBrowser(context)

        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(FileBrowserActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun testTabbedBrowserWithDisconnectedStorageRedirectsToStorageBrowser() {
        val tab = TabModel(
            title = "Missing USB",
            storageType = StorageType.SAF,
            rootPath = "/storage/1234-5678",
            currentPath = "/storage/1234-5678/Docs",
            storageLabel = "USB Drive"
        )
        TabSessionManager.saveSession(context, listOf(tab), tab.id)
        LastLocationManager.recordTabbedBrowser(context)

        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(StorageBrowserActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(LastLocationManager.EXTRA_STORAGE_UNAVAILABLE_REDIRECT, false))
    }

    @Test
    fun testTwinWindowResolution() {
        LastLocationManager.recordTwinWindow(context)
        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(TwinWindowActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun testClearResetsToStorageBrowser() {
        LastLocationManager.recordTwinWindow(context)
        LastLocationManager.clear(context)

        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(StorageBrowserActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun testRecordGenericActivityResolvesCorrectly() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).get()
        LastLocationManager.recordGenericActivity(activity)

        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(SettingsActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun testIsRecordableGenericActivityFiltersExcludedActivities() {
        val settingsActivity = Robolectric.buildActivity(SettingsActivity::class.java).get()
        assertTrue(LastLocationManager.isRecordableGenericActivity(settingsActivity))

        val mainActivity = Robolectric.buildActivity(MainActivity::class.java).get()
        assertFalse(LastLocationManager.isRecordableGenericActivity(mainActivity))

        val welcomeActivity = Robolectric.buildActivity(WelcomeActivity::class.java).get()
        assertFalse(LastLocationManager.isRecordableGenericActivity(welcomeActivity))

        val securityActivity = Robolectric.buildActivity(SecurityUnlockActivity::class.java).get()
        assertFalse(LastLocationManager.isRecordableGenericActivity(securityActivity))

        val imageViewer = Robolectric.buildActivity(ImageViewerActivity::class.java).get()
        assertFalse(LastLocationManager.isRecordableGenericActivity(imageViewer))
    }

    @Test
    fun testGenericActivityInvalidClassFallsBackToStorageBrowser() {
        val prefs = context.getSharedPreferences("last_location_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("container_type", "GENERIC_ACTIVITY")
            .putString("generic_class_name", "za.kilowatch.nonexistent.FakeActivity")
            .apply()

        val intent = LastLocationManager.resolveStartIntent(context)
        assertEquals(StorageBrowserActivity::class.java.name, intent.component?.className)
    }
}
