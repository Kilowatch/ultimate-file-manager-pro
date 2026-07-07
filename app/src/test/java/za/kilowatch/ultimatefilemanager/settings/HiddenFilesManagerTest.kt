package za.kilowatch.ultimatefilemanager.settings

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HiddenFilesManagerTest {

    @Test
    fun testDotPrefixedFiles() {
        assertTrue(HiddenFilesManager.isJunkOrHidden(".DS_Store"))
        assertTrue(HiddenFilesManager.isJunkOrHidden(".git"))
        assertTrue(HiddenFilesManager.isJunkOrHidden(".directory"))
        assertTrue(HiddenFilesManager.isJunkOrHidden("._photo.jpg"))
    }

    @Test
    fun testExactJunkFilesCaseInsensitive() {
        assertTrue(HiddenFilesManager.isJunkOrHidden("Thumbs.db"))
        assertTrue(HiddenFilesManager.isJunkOrHidden("thumbs.db"))
        assertTrue(HiddenFilesManager.isJunkOrHidden("THUMBS.DB"))
        assertTrue(HiddenFilesManager.isJunkOrHidden("desktop.ini"))
        assertTrue(HiddenFilesManager.isJunkOrHidden("DESKTOP.INI"))
        assertTrue(HiddenFilesManager.isJunkOrHidden("lost+found"))
        
        assertFalse(HiddenFilesManager.isJunkOrHidden("my_thumbs.db"))
        assertFalse(HiddenFilesManager.isJunkOrHidden("desktop.ini.txt"))
    }

    @Test
    fun testExactJunkFoldersCaseInsensitive() {
        assertTrue(HiddenFilesManager.isJunkOrHidden("\$RECYCLE.BIN"))
        assertTrue(HiddenFilesManager.isJunkOrHidden("\$recycle.bin"))
        assertTrue(HiddenFilesManager.isJunkOrHidden("@eaDir"))
        assertTrue(HiddenFilesManager.isJunkOrHidden("#recycle"))
        assertTrue(HiddenFilesManager.isJunkOrHidden("#snapshot"))
        
        assertFalse(HiddenFilesManager.isJunkOrHidden("recycle"))
        assertFalse(HiddenFilesManager.isJunkOrHidden("snapshot1"))
    }

    @Test
    fun testPathFiltering() {
        // Path contains a junk directory
        assertTrue(HiddenFilesManager.isPathJunkOrHidden("/sdcard/Pictures/@eaDir/photo.jpg"))
        assertTrue(HiddenFilesManager.isPathJunkOrHidden("C:\\\$RECYCLE.BIN\\file.txt"))
        
        // Path contains a junk file at the end
        assertTrue(HiddenFilesManager.isPathJunkOrHidden("/sdcard/Documents/Thumbs.db"))
        assertTrue(HiddenFilesManager.isPathJunkOrHidden("/sdcard/Documents/.DS_Store"))
        
        // Normal paths
        assertFalse(HiddenFilesManager.isPathJunkOrHidden("/sdcard/Documents/photo.jpg"))
        assertFalse(HiddenFilesManager.isPathJunkOrHidden("/sdcard/Movies/my_thumbs.db"))
    }
}
