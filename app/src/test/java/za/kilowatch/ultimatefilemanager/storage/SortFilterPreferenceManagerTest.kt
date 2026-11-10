package za.kilowatch.ultimatefilemanager.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SortFilterPreferenceManagerTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SortFilterPreferenceManager.resetForTests()
    }

    @After
    fun tearDown() {
        SortFilterPreferenceManager.resetForTests()
    }

    @Test
    fun inMemoryCacheAndPersistenceLifecycle() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val path = "/storage/emulated/0/Movies"
        val key = SortFilterPreferenceManager.folderKey(path)

        // Initially no override
        assertFalse(SortFilterPreferenceManager.hasFolderSpecific(context, key))
        assertNull(SortFilterPreferenceManager.loadForFolder(context, key))
        assertFalse(SortFilterPreferenceManager.hasFolderOverride(context, path))

        val state = SortFilterPreferenceManager.SortFilterState(
            sortMode = SortFilterSheet.SortMode.DATE,
            sortOrder = SortFilterSheet.SortOrder.DESC,
            filterType = SortFilterSheet.FilterType.VIDEOS,
            showHidden = true,
            groupByDate = true
        )

        // Save folder specific
        SortFilterPreferenceManager.saveFolderSpecific(context, key, path, state, isNetwork = false)

        // Should be found immediately via in-memory cache
        assertTrue(SortFilterPreferenceManager.hasFolderSpecific(context, key))
        assertTrue(SortFilterPreferenceManager.hasFolderOverride(context, path))
        val loaded = SortFilterPreferenceManager.loadForFolder(context, key)
        assertNotNull(loaded)
        assertEquals(SortFilterSheet.SortMode.DATE, loaded?.sortMode)
        assertEquals(SortFilterSheet.SortOrder.DESC, loaded?.sortOrder)
        assertEquals(SortFilterSheet.FilterType.VIDEOS, loaded?.filterType)
        assertTrue(loaded?.showHidden == true)
        assertTrue(loaded?.groupByDate == true)

        // Verify getAllFolderEntries
        val entries = SortFilterPreferenceManager.getAllFolderEntries(context)
        assertEquals(1, entries.size)
        assertEquals(key, entries[0].key)
        assertEquals(path, entries[0].displayPath)

        // Clear specific
        SortFilterPreferenceManager.clearFolderSpecific(context, key)
        assertFalse(SortFilterPreferenceManager.hasFolderSpecific(context, key))
        assertFalse(SortFilterPreferenceManager.hasFolderOverride(context, path))
        assertNull(SortFilterPreferenceManager.loadForFolder(context, key))
    }

    @Test
    fun initPreWarmsCacheFromPrefs() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val path = "/storage/emulated/0/Download"
        val key = SortFilterPreferenceManager.folderKey(path)

        val state = SortFilterPreferenceManager.SortFilterState(
            sortMode = SortFilterSheet.SortMode.SIZE,
            sortOrder = SortFilterSheet.SortOrder.ASC
        )
        SortFilterPreferenceManager.saveFolderSpecific(context, key, path, state, isNetwork = false)

        // Reset in-memory cache
        SortFilterPreferenceManager.resetForTests()

        // Call initSync() to pre-warm synchronously in test
        SortFilterPreferenceManager.initSync(context)

        // Should now be cached
        assertTrue(SortFilterPreferenceManager.hasFolderOverride(context, path))
        val loaded = SortFilterPreferenceManager.loadForPath(context, path)
        assertNotNull(loaded)
        assertEquals(SortFilterSheet.SortMode.SIZE, loaded?.sortMode)
    }

    @Test
    fun networkFolderKeyNormalizesSlashes() {
        val shareId = "smb_server_1"

        val key1 = SortFilterPreferenceManager.folderKey(shareId, "docker")
        val key2 = SortFilterPreferenceManager.folderKey(shareId, "/docker")
        val key3 = SortFilterPreferenceManager.folderKey(shareId, "docker/")
        val key4 = SortFilterPreferenceManager.folderKey(shareId, "/docker/")
        val key5 = SortFilterPreferenceManager.folderKey(shareId, "  /docker/  ")

        assertEquals(key1, key2)
        assertEquals(key1, key3)
        assertEquals(key1, key4)
        assertEquals(key1, key5)
        assertTrue(key1.startsWith("net_"))
        assertEquals(44, key1.length) // "net_" (4) + 40 hex chars
    }

    @Test
    fun networkFolderKeyNormalizesRootPath() {
        val shareId = "smb_server_1"

        val emptyRoot = SortFilterPreferenceManager.folderKey(shareId, "")
        val slashRoot = SortFilterPreferenceManager.folderKey(shareId, "/")
        val multiSlashRoot = SortFilterPreferenceManager.folderKey(shareId, "///")
        val whitespaceRoot = SortFilterPreferenceManager.folderKey(shareId, "   ")

        assertEquals(emptyRoot, slashRoot)
        assertEquals(emptyRoot, multiSlashRoot)
        assertEquals(emptyRoot, whitespaceRoot)
    }

    @Test
    fun networkFolderKeyServerModeSubpathsAreConsistent() {
        val shareId = "smb_server_1"

        val subpath1 = SortFilterPreferenceManager.folderKey(shareId, "docker/projects")
        val subpath2 = SortFilterPreferenceManager.folderKey(shareId, "/docker/projects")
        val subpath3 = SortFilterPreferenceManager.folderKey(shareId, "docker/projects/")
        val subpath4 = SortFilterPreferenceManager.folderKey(shareId, "/docker/projects/")

        assertEquals(subpath1, subpath2)
        assertEquals(subpath1, subpath3)
        assertEquals(subpath1, subpath4)
    }

    @Test
    fun localFolderKeyNormalizesTrailingSlashes() {
        val local1 = SortFilterPreferenceManager.folderKey("/storage/emulated/0/Download")
        val local2 = SortFilterPreferenceManager.folderKey("/storage/emulated/0/Download/")

        assertEquals(local1, local2)
        assertTrue(local1.startsWith("local_"))
        assertEquals(46, local1.length) // "local_" (6) + 40 hex chars
    }

    @Test
    fun localFolderKeyRootPreserved() {
        val rootKey = SortFilterPreferenceManager.folderKey("/")
        assertTrue(rootKey.startsWith("local_"))
    }

    @Test
    fun legacyKeyHelpersPreserveUnnormalizedBehaviorForBackwardCompat() {
        val shareId = "smb_1"
        val rawPath = "/docker"

        val canonicalKey = SortFilterPreferenceManager.folderKey(shareId, rawPath)
        val legacyKey = SortFilterPreferenceManager.legacyFolderKey(shareId, rawPath)

        // Legacy key hashes raw "/docker", whereas canonical key normalizes to "docker"
        assertNotEquals(canonicalKey, legacyKey)

        // Legacy key with clean path matches canonical key
        assertEquals(canonicalKey, SortFilterPreferenceManager.legacyFolderKey(shareId, "docker"))
    }
}
