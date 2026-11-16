package za.kilowatch.ultimatefilemanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libtorrent4j.Priority

class TorrentFileNodeTest {

    @Test
    fun testManualHierarchyAndRecursiveSelection() {
        // Construct a hierarchy:
        // root/
        //   folderA/
        //     file1.mp4 (1000 bytes, index 0)
        //     file2.srt (100 bytes, index 1)
        //   file3.nfo (50 bytes, index 2)

        val file1 = TorrentFileNode(id = 1, path = "folderA/file1.mp4", name = "file1.mp4", sizeBytes = 1000L, isDirectory = false, fileIndex = 0)
        val file2 = TorrentFileNode(id = 2, path = "folderA/file2.srt", name = "file2.srt", sizeBytes = 100L, isDirectory = false, fileIndex = 1)
        val folderA = TorrentFileNode(id = 3, path = "folderA", name = "folderA", sizeBytes = 1100L, isDirectory = true, children = mutableListOf(file1, file2))

        val file3 = TorrentFileNode(id = 4, path = "file3.nfo", name = "file3.nfo", sizeBytes = 50L, isDirectory = false, fileIndex = 2)
        val root = TorrentFileNode(id = 5, path = "", name = "root", sizeBytes = 1150L, isDirectory = true, children = mutableListOf(folderA, file3))

        // Initial state: all selected
        assertTrue(root.isAllSelected())
        assertFalse(root.isPartiallySelected())
        assertFalse(root.isNoneSelected())
        assertEquals(1150L, root.getSelectedBytes())
        assertEquals(3, root.getLeafFileCount())
        assertEquals(3, root.getSelectedLeafFileCount())
        assertEquals(setOf(0, 1, 2), root.getSelectedFileIndices())

        val prioritiesAll = root.buildPriorityArray(3)
        assertEquals(Priority.DEFAULT, prioritiesAll[0])
        assertEquals(Priority.DEFAULT, prioritiesAll[1])
        assertEquals(Priority.DEFAULT, prioritiesAll[2])

        // Deselect folderA
        folderA.setSelectionRecursive(false)
        assertFalse(file1.isSelected)
        assertFalse(file2.isSelected)
        assertTrue(file3.isSelected)

        assertTrue(folderA.isNoneSelected())
        assertFalse(folderA.isAllSelected())
        assertTrue(root.isPartiallySelected())
        assertEquals(50L, root.getSelectedBytes())
        assertEquals(1, root.getSelectedLeafFileCount())
        assertEquals(setOf(2), root.getSelectedFileIndices())

        val prioritiesPartial = root.buildPriorityArray(3)
        assertEquals(Priority.IGNORE, prioritiesPartial[0])
        assertEquals(Priority.IGNORE, prioritiesPartial[1])
        assertEquals(Priority.DEFAULT, prioritiesPartial[2])

        // Deselect all
        root.setSelectionRecursive(false)
        assertTrue(root.isNoneSelected())
        assertEquals(0L, root.getSelectedBytes())
        assertEquals(0, root.getSelectedLeafFileCount())
        assertTrue(root.getSelectedFileIndices().isEmpty())

        val prioritiesNone = root.buildPriorityArray(3)
        assertEquals(Priority.IGNORE, prioritiesNone[0])
        assertEquals(Priority.IGNORE, prioritiesNone[1])
        assertEquals(Priority.IGNORE, prioritiesNone[2])

        // Select all
        root.setSelectionRecursive(true)
        assertTrue(root.isAllSelected())
        assertEquals(1150L, root.getSelectedBytes())
        assertEquals(3, root.getSelectedLeafFileCount())
    }
}
