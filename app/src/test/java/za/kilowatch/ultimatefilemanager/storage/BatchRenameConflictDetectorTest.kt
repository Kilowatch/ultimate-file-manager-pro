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
}
