package za.kilowatch.ultimatefilemanager.flatview

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.indexing.FileIndexDao
import java.lang.reflect.Proxy

class FlatViewViewModelTest {

    private fun createSampleFile(path: String, folderPath: String, filename: String): FileIndex {
        return FileIndex(
            id = path.hashCode().toString(),
            path = path,
            filename = filename,
            extension = filename.substringAfterLast('.', ""),
            folderPath = folderPath,
            size = 1024L,
            lastModified = 1700000000L,
            createdDate = 1700000000L,
            mimeType = "application/pdf",
            storageId = "internal",
            storageType = "internal",
            isDirectory = false
        )
    }

    private fun createFakeDao(files: List<FileIndex>): FileIndexDao {
        return Proxy.newProxyInstance(
            FileIndexDao::class.java.classLoader,
            arrayOf(FileIndexDao::class.java)
        ) { _, method, _ ->
            if (method.name.startsWith("getFlatViewFiles")) {
                files
            } else {
                null
            }
        } as FileIndexDao
    }

    @Test
    fun testGroupingAndSubfolderRelativeNames() = runBlocking {
        val root = "/storage/emulated/0"
        val files = listOf(
            createSampleFile("$root/invoice.pdf", root, "invoice.pdf"),
            createSampleFile("$root/A Google Review/Hello/review.png", "$root/A Google Review/Hello", "review.png"),
            createSampleFile("$root/Documents/Work/report.pdf", "$root/Documents/Work", "report.pdf")
        )

        val fakeDao = createFakeDao(files)
        val viewModel = FlatViewViewModel("internal", root, fakeDao)
        viewModel.loadData(excludeSystem = true, defaultExpanded = true)

        var attempts = 0
        while (viewModel.state.value.isLoading && attempts < 20) {
            delay(50)
            attempts++
        }

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(3, state.totalFiles)

        // Categories: (Root), A Google Review, Documents
        val categories = state.items.filterIsInstance<FlatViewItem.RootCategoryHeader>()
        assertEquals(3, categories.size)
        assertTrue(categories.any { it.categoryName == "(Root)" })
        assertTrue(categories.any { it.categoryName == "A Google Review" })
        assertTrue(categories.any { it.categoryName == "Documents" })

        // Subfolders: Hello, Work (stripped of category prefix)
        val subfolders = state.items.filterIsInstance<FlatViewItem.SubfolderHeader>()
        assertEquals(2, subfolders.size)
        assertTrue(subfolders.any { it.subfolderDisplayName == "Hello" })
        assertFalse(subfolders.any { it.subfolderDisplayName == "A Google Review/Hello" })
        assertTrue(subfolders.any { it.subfolderDisplayName == "Work" })
    }

    @Test
    fun testCollapseAllAndExpandAll() = runBlocking {
        val root = "/storage/emulated/0"
        val files = listOf(
            createSampleFile("$root/A Google Review/Hello/file1.pdf", "$root/A Google Review/Hello", "file1.pdf"),
            createSampleFile("$root/Documents/Work/file2.pdf", "$root/Documents/Work", "file2.pdf")
        )

        val fakeDao = createFakeDao(files)
        val viewModel = FlatViewViewModel("internal", root, fakeDao)
        viewModel.loadData(excludeSystem = true, defaultExpanded = true)

        var attempts = 0
        while (viewModel.state.value.isLoading && attempts < 20) {
            delay(50)
            attempts++
        }

        // Expanded: 2 categories + 2 subfolders + 2 files = 6 items
        assertEquals(6, viewModel.state.value.items.size)

        // Collapse All
        viewModel.collapseAll()
        // When collapsed, only the 2 top-level category cards are visible!
        val collapsedItems = viewModel.state.value.items
        assertEquals(2, collapsedItems.size)
        assertTrue(collapsedItems.all { it is FlatViewItem.RootCategoryHeader })
        assertFalse((collapsedItems[0] as FlatViewItem.RootCategoryHeader).isExpanded)

        // Expand All again
        viewModel.expandAll()
        assertEquals(6, viewModel.state.value.items.size)
        assertTrue((viewModel.state.value.items[0] as FlatViewItem.RootCategoryHeader).isExpanded)
    }

    @Test
    fun testSelectionAndAutoExitEditMode() = runBlocking {
        val root = "/storage/emulated/0"
        val files = listOf(
            createSampleFile("$root/Documents/file1.pdf", "$root/Documents", "file1.pdf"),
            createSampleFile("$root/Documents/file2.pdf", "$root/Documents", "file2.pdf")
        )

        val fakeDao = createFakeDao(files)
        val viewModel = FlatViewViewModel("internal", root, fakeDao)
        viewModel.loadData(excludeSystem = true, defaultExpanded = true)

        var attempts = 0
        while (viewModel.state.value.isLoading && attempts < 20) {
            delay(50)
            attempts++
        }

        // Select file1
        viewModel.toggleFileSelection("$root/Documents/file1.pdf")
        assertTrue(viewModel.state.value.isEditMode)
        assertEquals(1, viewModel.state.value.selectedPaths.size)
        assertFalse(viewModel.state.value.areAllSelected)

        // Select All
        viewModel.selectAll()
        assertTrue(viewModel.state.value.isEditMode)
        assertEquals(2, viewModel.state.value.selectedPaths.size)
        assertTrue(viewModel.state.value.areAllSelected)

        // Deselect All -> must automatically exit edit mode!
        viewModel.deselectAll()
        assertFalse(viewModel.state.value.isEditMode)
        assertEquals(0, viewModel.state.value.selectedPaths.size)
        assertFalse(viewModel.state.value.areAllSelected)
    }

    @Test
    fun testFilterQuery() = runBlocking {
        val root = "/storage/emulated/0"
        val files = listOf(
            createSampleFile("$root/Documents/report.pdf", "$root/Documents", "report.pdf"),
            createSampleFile("$root/Documents/invoice.pdf", "$root/Documents", "invoice.pdf")
        )

        val fakeDao = createFakeDao(files)
        val viewModel = FlatViewViewModel("internal", root, fakeDao)
        viewModel.loadData(excludeSystem = true, defaultExpanded = true)

        var attempts = 0
        while (viewModel.state.value.isLoading && attempts < 20) {
            delay(50)
            attempts++
        }

        viewModel.setFilterQuery("invoice")
        val filtered = viewModel.state.value.items
        // 1 category + 1 file = 2 items
        assertEquals(2, filtered.size)
        val fileEntry = filtered.filterIsInstance<FlatViewItem.FileEntry>().first()
        assertEquals("invoice.pdf", fileEntry.fileIndex.filename)
    }
}
