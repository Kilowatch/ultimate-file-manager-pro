package za.kilowatch.ultimatefilemanager.storage

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShizukuShellWrapperTest {

    @Test
    fun testIsProtectedPath() {
        assertTrue(ShizukuShellWrapper.isProtectedPath("/storage/emulated/0/Android/data"))
        assertTrue(ShizukuShellWrapper.isProtectedPath("/storage/emulated/0/Android/data/com.example.app"))
        assertTrue(ShizukuShellWrapper.isProtectedPath("/storage/emulated/0/Android/obb"))
        assertTrue(ShizukuShellWrapper.isProtectedPath("/storage/emulated/0/Android/obb/com.example.game"))
        assertTrue(ShizukuShellWrapper.isProtectedPath("/Android/data"))
        assertTrue(ShizukuShellWrapper.isProtectedPath("\\storage\\emulated\\0\\Android\\data"))

        assertFalse(ShizukuShellWrapper.isProtectedPath("/storage/emulated/0/Download"))
        assertFalse(ShizukuShellWrapper.isProtectedPath("/storage/emulated/0/DCIM/Camera"))
        assertFalse(ShizukuShellWrapper.isProtectedPath("/storage/emulated/0/Documents"))
        assertFalse(ShizukuShellWrapper.isProtectedPath("/"))
        assertFalse(ShizukuShellWrapper.isProtectedPath(""))
    }

    @Test
    fun testExtractUserId() {
        assertEquals("0", ShizukuShellWrapper.extractUserId("/storage/emulated/0/Download"))
        assertEquals("10", ShizukuShellWrapper.extractUserId("/storage/emulated/10/Android/data"))
        assertEquals("0", ShizukuShellWrapper.extractUserId("/mnt/user/0/primary"))
        assertEquals("11", ShizukuShellWrapper.extractUserId("/mnt/pass_through/11/emulated/11"))
        assertEquals("0", ShizukuShellWrapper.extractUserId("/sdcard"))
    }

    @Test
    fun testMainThreadRunCommandReturnsImmediatelyWithoutBlocking() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        // On the main UI thread, runCommand must NEVER call runBlocking on remote IPC
        val (code, output) = ShizukuShellWrapper.runCommand("echo test")
        assertEquals(-1, code)
        assertTrue(output.isEmpty())
    }

    @Test
    fun testMainThreadExistsDoesNotBlock() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        // Main-thread exists check should safely evaluate without executing shell IPC
        val result = ShizukuShellWrapper.exists("/storage/emulated/0/Android/data")
        // Not authorized in Robolectric, so canUseShizuku is false; should fall back to non-blocking File.exists()
        assertFalse(result)
    }

    @Test
    fun testMainThreadGetFileSizeAndLastModifiedReturnZero() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        // On the main thread, getFileSize and getLastModified return 0L without IPC
        assertEquals(0L, ShizukuShellWrapper.getFileSize("/storage/emulated/0/Android/data/file.txt"))
        assertEquals(0L, ShizukuShellWrapper.getLastModified("/storage/emulated/0/Android/data/file.txt"))
    }

    @Test
    fun testShizukuFileMainThreadExistsReturnsTrue() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val file = ShizukuFile("/storage/emulated/0/Android/data", "com.example.app", true)
        // On the main thread, ShizukuFile.exists() must immediately return true to avoid UI freeze
        assertTrue(file.exists())
    }
}
