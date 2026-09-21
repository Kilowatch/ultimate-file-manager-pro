package za.kilowatch.ultimatefilemanager.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.storage.RootFile
import za.kilowatch.ultimatefilemanager.storage.ShizukuFile
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SyncStorageHelperTest {

    @Test
    fun testRootFileSecondaryConstructor() {
        val rootFile = RootFile("/data/media/0/Android/data/com.example/files", true)
        assertEquals("/data/media/0/Android/data/com.example/files", rootFile.absolutePath)
        assertEquals("files", rootFile.name)
        assertEquals("/data/media/0/Android/data/com.example", rootFile.parent)
        assertTrue(rootFile.isDirectory)
        assertFalse(rootFile.isFile)

        val rootChildFile = RootFile("/data/media/0/Android/data/com.example/files/backup.db", false)
        assertEquals("/data/media/0/Android/data/com.example/files/backup.db", rootChildFile.absolutePath)
        assertEquals("backup.db", rootChildFile.name)
        assertEquals("/data/media/0/Android/data/com.example/files", rootChildFile.parent)
        assertFalse(rootChildFile.isDirectory)
        assertTrue(rootChildFile.isFile)
    }

    @Test
    fun testShizukuFileSecondaryConstructor() {
        val shizukuDir = ShizukuFile("/storage/emulated/0/Android/data/com.example", true)
        assertEquals("/storage/emulated/0/Android/data/com.example", shizukuDir.absolutePath)
        assertEquals("com.example", shizukuDir.name)
        assertEquals("/storage/emulated/0/Android/data", shizukuDir.parent)
        assertTrue(shizukuDir.isDirectory)
        assertFalse(shizukuDir.isFile)

        val shizukuFile = ShizukuFile("/storage/emulated/0/Android/data/com.example/test.txt", false)
        assertEquals("/storage/emulated/0/Android/data/com.example/test.txt", shizukuFile.absolutePath)
        assertEquals("test.txt", shizukuFile.name)
        assertEquals("/storage/emulated/0/Android/data/com.example", shizukuFile.parent)
        assertFalse(shizukuFile.isDirectory)
        assertTrue(shizukuFile.isFile)
    }

    @Test
    fun testResolveChildFileDirect() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val tempDir = File(context.cacheDir, "test_sync_folder")
        tempDir.mkdirs()

        val child = SyncStorageHelper.resolveChildFile(context, tempDir.absolutePath, "subfile.txt")
        assertEquals(File(tempDir, "subfile.txt").absolutePath, child.absolutePath)
        assertEquals("subfile.txt", child.name)

        val emptyChild = SyncStorageHelper.resolveChildFile(context, tempDir.absolutePath, "")
        assertEquals(tempDir.absolutePath, emptyChild.absolutePath)

        tempDir.deleteRecursively()
    }
}
