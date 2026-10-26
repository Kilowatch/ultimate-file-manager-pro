package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.storage.DuplicateRetentionManager.DuplicateRetentionPolicy

@RunWith(RobolectricTestRunner::class)
class DuplicateRetentionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DuplicateRetentionManager.resetPreference(context)
    }

    private fun makeFile(path: String, lastModified: Long, size: Long = 1024L): FileIndex {
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "")
        val folder = path.substringBeforeLast('/')
        return FileIndex(
            id = java.util.UUID.randomUUID().toString(),
            path = path,
            filename = name,
            extension = ext,
            folderPath = folder,
            size = size,
            lastModified = lastModified,
            createdDate = lastModified,
            mimeType = "image/jpeg",
            storageId = "internal",
            storageType = "internal",
            isDirectory = false,
            hash = "sample_hash_123"
        )
    }

    @Test
    fun policyFromKeyMapping() {
        assertEquals(DuplicateRetentionPolicy.KEEP_OLDEST, DuplicateRetentionPolicy.fromKey("oldest"))
        assertEquals(DuplicateRetentionPolicy.KEEP_NEWEST, DuplicateRetentionPolicy.fromKey("newest"))
        assertNull(DuplicateRetentionPolicy.fromKey("ask"))
        assertNull(DuplicateRetentionPolicy.fromKey(null))
        assertNull(DuplicateRetentionPolicy.fromKey("random_invalid"))
    }

    @Test
    fun preferenceDefaultsToAskMode() {
        assertNull(DuplicateRetentionManager.getStrategy(context))
        assertEquals(DuplicateRetentionManager.MODE_ASK, DuplicateRetentionManager.getPreferenceMode(context))
        assertFalse(DuplicateRetentionManager.isRemembered(context))
    }

    @Test
    fun saveStrategyWithRememberChoice() {
        DuplicateRetentionManager.saveStrategy(context, DuplicateRetentionPolicy.KEEP_OLDEST, remember = true)
        assertEquals(DuplicateRetentionPolicy.KEEP_OLDEST, DuplicateRetentionManager.getStrategy(context))
        assertEquals(DuplicateRetentionManager.MODE_OLDEST, DuplicateRetentionManager.getPreferenceMode(context))
        assertTrue(DuplicateRetentionManager.isRemembered(context))

        DuplicateRetentionManager.saveStrategy(context, DuplicateRetentionPolicy.KEEP_NEWEST, remember = false)
        assertNull(DuplicateRetentionManager.getStrategy(context))
        assertEquals(DuplicateRetentionManager.MODE_ASK, DuplicateRetentionManager.getPreferenceMode(context))
        assertFalse(DuplicateRetentionManager.isRemembered(context))
    }

    @Test
    fun setPreferenceModeFromSettings() {
        DuplicateRetentionManager.setPreferenceMode(context, DuplicateRetentionManager.MODE_NEWEST)
        assertEquals(DuplicateRetentionPolicy.KEEP_NEWEST, DuplicateRetentionManager.getStrategy(context))
        assertEquals(DuplicateRetentionManager.MODE_NEWEST, DuplicateRetentionManager.getPreferenceMode(context))
        assertTrue(DuplicateRetentionManager.isRemembered(context))

        DuplicateRetentionManager.setPreferenceMode(context, DuplicateRetentionManager.MODE_ASK)
        assertNull(DuplicateRetentionManager.getStrategy(context))
        assertEquals(DuplicateRetentionManager.MODE_ASK, DuplicateRetentionManager.getPreferenceMode(context))
        assertFalse(DuplicateRetentionManager.isRemembered(context))
    }

    @Test
    fun selectAllDuplicatesKeepsOldestOriginal() {
        val f1 = makeFile("/storage/emulated/0/DCIM/photo1.jpg", lastModified = 1000L)
        val f2 = makeFile("/storage/emulated/0/Downloads/photo2.jpg", lastModified = 2000L)
        val f3 = makeFile("/storage/emulated/0/Pictures/photo3.jpg", lastModified = 3000L)

        val group = DuplicateGroup(
            hash = "hash1",
            files = listOf(f2, f3, f1),
            wastedBytes = 2048L
        )

        val adapter = AnalyzerDuplicateAdapter(listOf(group))
        val selectedCount = adapter.selectAllDuplicates(DuplicateRetentionPolicy.KEEP_OLDEST)

        assertEquals(2, selectedCount)
        assertEquals(1, adapter.preservedPaths.size)
        assertTrue(adapter.preservedPaths.contains(f1.path))
        assertTrue(adapter.checkedFiles.contains(f2))
        assertTrue(adapter.checkedFiles.contains(f3))
        assertFalse(adapter.checkedFiles.contains(f1))
    }

    @Test
    fun selectAllDuplicatesKeepsNewestOriginal() {
        val f1 = makeFile("/storage/emulated/0/DCIM/photo1.jpg", lastModified = 1000L)
        val f2 = makeFile("/storage/emulated/0/Downloads/photo2.jpg", lastModified = 2000L)
        val f3 = makeFile("/storage/emulated/0/Pictures/photo3.jpg", lastModified = 3000L)

        val group = DuplicateGroup(
            hash = "hash1",
            files = listOf(f1, f2, f3),
            wastedBytes = 2048L
        )

        val adapter = AnalyzerDuplicateAdapter(listOf(group))
        val selectedCount = adapter.selectAllDuplicates(DuplicateRetentionPolicy.KEEP_NEWEST)

        assertEquals(2, selectedCount)
        assertEquals(1, adapter.preservedPaths.size)
        assertTrue(adapter.preservedPaths.contains(f3.path))
        assertTrue(adapter.checkedFiles.contains(f1))
        assertTrue(adapter.checkedFiles.contains(f2))
        assertFalse(adapter.checkedFiles.contains(f3))
    }

    @Test
    fun tieBreakingPrefersShorterPathThenAlphabetical() {
        // Equal timestamps, different path lengths
        val fShort = makeFile("/storage/photo.jpg", lastModified = 1000L)
        val fLong = makeFile("/storage/nested/folder/photo.jpg", lastModified = 1000L)

        val group1 = DuplicateGroup(
            hash = "hash1",
            files = listOf(fLong, fShort),
            wastedBytes = 1024L
        )

        val adapter1 = AnalyzerDuplicateAdapter(listOf(group1))
        adapter1.selectAllDuplicates(DuplicateRetentionPolicy.KEEP_OLDEST)
        assertTrue(adapter1.preservedPaths.contains(fShort.path))

        // Equal timestamps, equal path lengths, different alphabetical order
        val fAlphaA = makeFile("/storage/a.jpg", lastModified = 2000L)
        val fAlphaB = makeFile("/storage/b.jpg", lastModified = 2000L)

        val group2 = DuplicateGroup(
            hash = "hash2",
            files = listOf(fAlphaB, fAlphaA),
            wastedBytes = 1024L
        )

        val adapter2 = AnalyzerDuplicateAdapter(listOf(group2))
        adapter2.selectAllDuplicates(DuplicateRetentionPolicy.KEEP_OLDEST)
        assertTrue(adapter2.preservedPaths.contains(fAlphaA.path))
    }

    @Test
    fun clearSelectionResetsAllState() {
        val f1 = makeFile("/storage/1.jpg", 1000L)
        val f2 = makeFile("/storage/2.jpg", 2000L)
        val group = DuplicateGroup("h", listOf(f1, f2), 1024L)

        var selectionCountNotified = -1
        val adapter = AnalyzerDuplicateAdapter(listOf(group)) { count ->
            selectionCountNotified = count
        }

        adapter.selectAllDuplicates(DuplicateRetentionPolicy.KEEP_OLDEST)
        assertEquals(1, adapter.checkedFiles.size)
        assertEquals(1, adapter.preservedPaths.size)
        assertEquals(1, selectionCountNotified)

        adapter.clearSelection()
        assertEquals(0, adapter.checkedFiles.size)
        assertEquals(0, adapter.preservedPaths.size)
        assertEquals(0, selectionCountNotified)
    }
}
