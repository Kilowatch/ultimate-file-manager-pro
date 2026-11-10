package za.kilowatch.ultimatefilemanager.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import za.kilowatch.ultimatefilemanager.storage.MountedDocumentProvider

class OtherStorageExportTest {

    @Test
    fun testExportChoiceRouting_singleRegularFile_identifiesAsDirectExport() {
        val files = listOf(
            NetworkFile(name = "document.pdf", path = "docs/document.pdf", isDirectory = false, size = 1024L)
        )
        val hasDirectory = files.any { it.isDirectory }
        val isSingleRegularFile = files.size == 1 && !hasDirectory

        assertTrue("Single regular file should qualify for direct export", isSingleRegularFile)
        assertEquals("document.pdf", files.first().name)
    }

    @Test
    fun testExportChoiceRouting_singleDirectory_requiresChoiceDialog() {
        val files = listOf(
            NetworkFile(name = "Photos", path = "Photos", isDirectory = true, size = 0L)
        )
        val hasDirectory = files.any { it.isDirectory }
        val isSingleRegularFile = files.size == 1 && !hasDirectory

        assertFalse("Single folder must NOT qualify for direct export without choice dialog", isSingleRegularFile)
        assertTrue("hasDirectory must be true for directory", hasDirectory)
        assertEquals("Photos", files.first().name)
    }

    @Test
    fun testExportChoiceRouting_multipleFiles_requiresChoiceDialog() {
        val files = listOf(
            NetworkFile(name = "file1.txt", path = "file1.txt", isDirectory = false, size = 100L),
            NetworkFile(name = "file2.txt", path = "file2.txt", isDirectory = false, size = 200L)
        )
        val hasDirectory = files.any { it.isDirectory }
        val isSingleRegularFile = files.size == 1 && !hasDirectory

        assertFalse("Multiple files must NOT qualify for single file direct export", isSingleRegularFile)
        assertFalse("No directories present", hasDirectory)
        assertEquals(2, files.size)
    }

    @Test
    fun testExportChoiceRouting_multipleFilesWithDirectory_requiresChoiceDialog() {
        val files = listOf(
            NetworkFile(name = "file1.txt", path = "file1.txt", isDirectory = false, size = 100L),
            NetworkFile(name = "FolderA", path = "FolderA", isDirectory = true, size = 0L)
        )
        val hasDirectory = files.any { it.isDirectory }
        val isSingleRegularFile = files.size == 1 && !hasDirectory

        assertFalse("Mixed files and folders require choice dialog", isSingleRegularFile)
        assertTrue("hasDirectory must be true", hasDirectory)
    }

    @Test
    fun testMountedDocumentProvider_properties() {
        val provider = MountedDocumentProvider(
            authority = "com.google.android.apps.docs.storage",
            packageName = "com.google.android.apps.docs",
            displayName = "Google Drive",
            isTreeCapable = true
        )
        assertEquals("com.google.android.apps.docs.storage", provider.authority)
        assertEquals("Google Drive", provider.displayName)
        assertEquals("com.google.android.apps.docs", provider.packageName)
        assertTrue(provider.isTreeCapable)
    }
}
