package za.kilowatch.ultimatefilemanager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRenameConflictDetectorTest {

    @Test
    fun cleanNamesProduceNoConflicts() {
        val conflicts = BatchRenameConflictDetector.nameConflicts(listOf("a.txt", "b.txt", "c.txt"))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun duplicateNamesAreFlaggedCaseInsensitively() {
        val conflicts = BatchRenameConflictDetector.nameConflicts(listOf("photo.jpg", "PHOTO.JPG"))
        assertEquals(PreviewConflict.DUPLICATE, conflicts[0])
        assertEquals(PreviewConflict.DUPLICATE, conflicts[1])
    }

    @Test
    fun invalidCharactersAreFlagged() {
        val conflicts = BatchRenameConflictDetector.nameConflicts(listOf("a/b.txt"))
        assertEquals(PreviewConflict.INVALID_CHARS, conflicts[0])
    }

    @Test
    fun trailingDotIsFlagged() {
        val conflicts = BatchRenameConflictDetector.nameConflicts(listOf("file."))
        assertEquals(PreviewConflict.INVALID_CHARS, conflicts[0])
    }

    @Test
    fun reservedDeviceNameIsFlagged() {
        val conflicts = BatchRenameConflictDetector.nameConflicts(listOf("CON.txt"))
        assertEquals(PreviewConflict.INVALID_CHARS, conflicts[0])
    }

    @Test
    fun emptyNameIsNotFlaggedAsAConflict() {
        // Empty results are handled by the empty-pattern error, not a per-row flag.
        val conflicts = BatchRenameConflictDetector.nameConflicts(listOf(""))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun identicalNamesInDifferentSubfoldersProduceNoConflicts() {
        val item1 = BatchRenameItem.fromLocalFile(java.io.File("/storage/emulated/0/Video/A/1/Episode-1.mp4"))
        val item2 = BatchRenameItem.fromLocalFile(java.io.File("/storage/emulated/0/Video/A/2/Episode-2.mp4"))
        val item3 = BatchRenameItem.fromLocalFile(java.io.File("/storage/emulated/0/Video/A/3/Episode-3.mp4"))
        val items = listOf(item1, item2, item3)
        val resolvedNames = listOf("Episode.mp4", "Episode.mp4", "Episode.mp4")

        val conflicts = BatchRenameConflictDetector.nameConflicts(items, resolvedNames)
        assertTrue("Different subfolders with same target name should not conflict", conflicts.isEmpty())
    }

    @Test
    fun identicalNamesInSameSubfolderProduceDuplicateConflict() {
        val item1 = BatchRenameItem.fromLocalFile(java.io.File("/storage/emulated/0/Video/A/1/Episode-1.mp4"))
        val item2 = BatchRenameItem.fromLocalFile(java.io.File("/storage/emulated/0/Video/A/1/Episode-2.mp4"))
        val items = listOf(item1, item2)
        val resolvedNames = listOf("Episode.mp4", "Episode.mp4")

        val conflicts = BatchRenameConflictDetector.nameConflicts(items, resolvedNames)
        assertEquals(PreviewConflict.DUPLICATE, conflicts[0])
        assertEquals(PreviewConflict.DUPLICATE, conflicts[1])
    }
}
