package za.kilowatch.ultimatefilemanager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortFilterPreferenceManagerTest {

    @Test
    fun networkFolderKeyNormalizesSlashes() {
        val shareId = "smb_server_1"

        val key1 = SortFilterPreferenceManager.folderKey(shareId, "docker")
        val key2 = SortFilterPreferenceManager.folderKey(shareId, "/docker")
        val key3 = SortFilterPreferenceManager.folderKey(shareId, "docker/")
        val key4 = SortFilterPreferenceManager.folderKey(shareId, "/docker/")
        val key5 = SortFilterPreferenceManager.folderKey(shareId, "  /docker/  ")

        assertEquals(key1, key2)
        assertEquals(key1, key3)
        assertEquals(key1, key4)
        assertEquals(key1, key5)
        assertTrue(key1.startsWith("net_"))
        assertEquals(44, key1.length) // "net_" (4) + 40 hex chars
    }

    @Test
    fun networkFolderKeyNormalizesRootPath() {
        val shareId = "smb_server_1"

        val emptyRoot = SortFilterPreferenceManager.folderKey(shareId, "")
        val slashRoot = SortFilterPreferenceManager.folderKey(shareId, "/")
        val multiSlashRoot = SortFilterPreferenceManager.folderKey(shareId, "///")
        val whitespaceRoot = SortFilterPreferenceManager.folderKey(shareId, "   ")

        assertEquals(emptyRoot, slashRoot)
        assertEquals(emptyRoot, multiSlashRoot)
        assertEquals(emptyRoot, whitespaceRoot)
    }

    @Test
    fun networkFolderKeyServerModeSubpathsAreConsistent() {
        val shareId = "smb_server_1"

        val subpath1 = SortFilterPreferenceManager.folderKey(shareId, "docker/projects")
        val subpath2 = SortFilterPreferenceManager.folderKey(shareId, "/docker/projects")
        val subpath3 = SortFilterPreferenceManager.folderKey(shareId, "docker/projects/")
        val subpath4 = SortFilterPreferenceManager.folderKey(shareId, "/docker/projects/")

        assertEquals(subpath1, subpath2)
        assertEquals(subpath1, subpath3)
        assertEquals(subpath1, subpath4)
    }

    @Test
    fun localFolderKeyNormalizesTrailingSlashes() {
        val local1 = SortFilterPreferenceManager.folderKey("/storage/emulated/0/Download")
        val local2 = SortFilterPreferenceManager.folderKey("/storage/emulated/0/Download/")

        assertEquals(local1, local2)
        assertTrue(local1.startsWith("local_"))
        assertEquals(46, local1.length) // "local_" (6) + 40 hex chars
    }

    @Test
    fun localFolderKeyRootPreserved() {
        val rootKey = SortFilterPreferenceManager.folderKey("/")
        assertTrue(rootKey.startsWith("local_"))
    }

    @Test
    fun legacyKeyHelpersPreserveUnnormalizedBehaviorForBackwardCompat() {
        val shareId = "smb_1"
        val rawPath = "/docker"

        val canonicalKey = SortFilterPreferenceManager.folderKey(shareId, rawPath)
        val legacyKey = SortFilterPreferenceManager.legacyFolderKey(shareId, rawPath)

        // Legacy key hashes raw "/docker", whereas canonical key normalizes to "docker"
        assertNotEquals(canonicalKey, legacyKey)

        // Legacy key with clean path matches canonical key
        assertEquals(canonicalKey, SortFilterPreferenceManager.legacyFolderKey(shareId, "docker"))
    }
}
