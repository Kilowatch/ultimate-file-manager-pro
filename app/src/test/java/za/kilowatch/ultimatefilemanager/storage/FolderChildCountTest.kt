package za.kilowatch.ultimatefilemanager.storage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FolderChildCountTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testLocalFolderChildCount_showHiddenFalse_excludesDotAndJunkFiles() {
        val root = tempFolder.newFolder("testDir")
        File(root, "document.pdf").createNewFile()
        File(root, "photo.jpg").createNewFile()
        File(root, ".thumbnail").mkdir()
        File(root, ".nomedia").createNewFile()
        File(root, "Thumbs.db").createNewFile()

        val children = root.list()?.toList().orEmpty()
        val count = children.count { subName ->
            !HiddenFilesManager.isJunkOrHidden(subName)
        }

        assertEquals(2, count)
    }

    @Test
    fun testLocalFolderChildCount_showHiddenTrue_includesDotAndAllFiles() {
        val root = tempFolder.newFolder("testDirHidden")
        File(root, "document.pdf").createNewFile()
        File(root, "photo.jpg").createNewFile()
        File(root, ".thumbnail").mkdir()
        File(root, ".nomedia").createNewFile()
        File(root, "Thumbs.db").createNewFile()

        val children = root.list()?.toList().orEmpty()
        val showHidden = true
        val count = if (showHidden) {
            children.size
        } else {
            children.count { subName ->
                !HiddenFilesManager.isJunkOrHidden(subName)
            }
        }

        assertEquals(5, count)
    }

    @Test
    fun testLocalFolderChildCount_withHiddenPaths() {
        val root = tempFolder.newFolder("testDirPaths")
        val visibleFile = File(root, "visible.txt").apply { createNewFile() }
        val hiddenByUser = File(root, "user_hidden.txt").apply { createNewFile() }
        val dotFolder = File(root, ".thumbnail").apply { mkdir() }

        val hiddenPaths = setOf(hiddenByUser.absolutePath)

        val children = root.list()?.toList().orEmpty()
        val countShowHiddenFalse = children.count { subName ->
            !HiddenFilesManager.isJunkOrHidden(subName) &&
            File(root, subName).absolutePath !in hiddenPaths
        }
        assertEquals(1, countShowHiddenFalse)

        val countShowHiddenTrue = children.size
        assertEquals(3, countShowHiddenTrue)
    }
}
