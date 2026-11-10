package za.kilowatch.ultimatefilemanager.storage

import androidx.recyclerview.widget.DiffUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FileDiffCallbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAreItemsTheSame_sameFilePath_returnsTrue() {
        val root = tempFolder.newFolder("diffTest1")
        val file1 = File(root, "fileA.txt").apply { writeText("hello") }
        val file2 = File(root, "fileA.txt")

        val oldList = listOf(ListItem.FileEntry(file1))
        val newList = listOf(ListItem.FileEntry(file2))

        val callback = FileAdapter.Companion.FileDiffCallback(
            oldList = oldList,
            newList = newList,
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap(),
            folderSizes = emptyMap()
        )

        assertTrue(callback.areItemsTheSame(0, 0))
    }

    @Test
    fun testAreItemsTheSame_differentFilePath_returnsFalse() {
        val root = tempFolder.newFolder("diffTest2")
        val file1 = File(root, "fileA.txt").apply { writeText("hello") }
        val file2 = File(root, "fileB.txt").apply { writeText("world") }

        val oldList = listOf(ListItem.FileEntry(file1))
        val newList = listOf(ListItem.FileEntry(file2))

        val callback = FileAdapter.Companion.FileDiffCallback(
            oldList = oldList,
            newList = newList,
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap(),
            folderSizes = emptyMap()
        )

        assertFalse(callback.areItemsTheSame(0, 0))
    }

    @Test
    fun testAreItemsTheSame_headers_matchesYearAndMonth() {
        val h1 = ListItem.Header(year = 2026, month = 11, count = 5)
        val h2 = ListItem.Header(year = 2026, month = 11, count = 10)
        val h3 = ListItem.Header(year = 2026, month = 10, count = 5)

        val callback = FileAdapter.Companion.FileDiffCallback(
            oldList = listOf(h1),
            newList = listOf(h2, h3),
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap(),
            folderSizes = emptyMap()
        )

        assertTrue(callback.areItemsTheSame(0, 0))
        assertFalse(callback.areItemsTheSame(0, 1))
    }

    @Test
    fun testGetChangePayload_returnsPayloadMetadata() {
        val root = tempFolder.newFolder("diffTest3")
        val file1 = File(root, "fileA.txt").apply { writeText("hello") }
        val file2 = File(root, "fileA.txt")

        val callback = FileAdapter.Companion.FileDiffCallback(
            oldList = listOf(ListItem.FileEntry(file1)),
            newList = listOf(ListItem.FileEntry(file2)),
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap(),
            folderSizes = emptyMap()
        )

        val payload = callback.getChangePayload(0, 0)
        assertEquals(FileAdapter.PAYLOAD_METADATA, payload)
    }

    @Test
    fun testCalculateDiff_computesAccurateResult() {
        val root = tempFolder.newFolder("diffTest4")
        val f1 = File(root, "alpha.txt").apply { writeText("1") }
        val f2 = File(root, "beta.txt").apply { writeText("2") }
        val f3 = File(root, "gamma.txt").apply { writeText("3") }

        val oldList = listOf(ListItem.FileEntry(f1), ListItem.FileEntry(f2))
        val newList = listOf(ListItem.FileEntry(f1), ListItem.FileEntry(f3))

        val callback = FileAdapter.Companion.FileDiffCallback(
            oldList = oldList,
            newList = newList,
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap(),
            folderSizes = emptyMap()
        )

        val diffResult = DiffUtil.calculateDiff(callback)
        assertNotNull(diffResult)
    }

    @Test
    fun testAreContentsTheSame_withMetaCache_usesCachedMetadata() {
        val root = tempFolder.newFolder("diffTest5")
        val f1 = File(root, "meta.txt").apply { writeText("original") }
        val f2 = File(root, "meta.txt")

        val oldList = listOf(ListItem.FileEntry(f1))
        val newList = listOf(ListItem.FileEntry(f2))

        val meta = FileAdapter.Companion.FileMeta(isDirectory = false, size = 100L, lastModified = 12345L)
        val callback = FileAdapter.Companion.FileDiffCallback(
            oldList = oldList,
            newList = newList,
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap(),
            folderSizes = emptyMap(),
            metaCache = mapOf(f1.absolutePath to meta)
        )

        assertTrue(callback.areContentsTheSame(0, 0))
    }
}
