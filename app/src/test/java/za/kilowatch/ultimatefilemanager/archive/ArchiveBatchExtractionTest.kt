package za.kilowatch.ultimatefilemanager.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class ArchiveBatchExtractionTest {

    @Test
    fun testGetArchiveBaseName_singleExtensions() {
        assertEquals("archive1", ArchiveManager.getArchiveBaseName("archive1.zip"))
        assertEquals("backup_2026", ArchiveManager.getArchiveBaseName("backup_2026.7z"))
        assertEquals("photos", ArchiveManager.getArchiveBaseName("photos.rar"))
        assertEquals("data", ArchiveManager.getArchiveBaseName("data.tar"))
        assertEquals("source", ArchiveManager.getArchiveBaseName("source.gz"))
        assertEquals("archive", ArchiveManager.getArchiveBaseName("archive.bz2"))
        assertEquals("archive", ArchiveManager.getArchiveBaseName("archive.xz"))
        assertEquals("archive", ArchiveManager.getArchiveBaseName("archive.zst"))
        assertEquals("archive", ArchiveManager.getArchiveBaseName("archive.tgz"))
        assertEquals("archive", ArchiveManager.getArchiveBaseName("archive.tbz2"))
    }

    @Test
    fun testGetArchiveBaseName_compoundExtensions() {
        assertEquals("archive1", ArchiveManager.getArchiveBaseName("archive1.tar.gz"))
        assertEquals("archive2", ArchiveManager.getArchiveBaseName("archive2.tar.bz2"))
        assertEquals("archive3", ArchiveManager.getArchiveBaseName("archive3.tar.xz"))
        assertEquals("archive4", ArchiveManager.getArchiveBaseName("archive4.tar.zst"))
    }

    @Test
    fun testGetArchiveBaseName_withDotsInName() {
        assertEquals("my.project.v1.0.0", ArchiveManager.getArchiveBaseName("my.project.v1.0.0.tar.gz"))
        assertEquals("release-1.2.3", ArchiveManager.getArchiveBaseName("release-1.2.3.zip"))
        assertEquals(".hidden_config", ArchiveManager.getArchiveBaseName(".hidden_config.zip"))
    }

    @Test
    fun testGetArchiveBaseName_mixedCase() {
        assertEquals("ARCHIVE", ArchiveManager.getArchiveBaseName("ARCHIVE.TAR.GZ"))
        assertEquals("BackupFile", ArchiveManager.getArchiveBaseName("BackupFile.ZIP"))
        assertEquals("MixedName", ArchiveManager.getArchiveBaseName("MixedName.Tar.Xz"))
    }

    @Test
    fun testGetArchiveBaseName_withFileObject() {
        val file = File("/sdcard/Download/bundle.tar.gz")
        assertEquals("bundle", ArchiveManager.getArchiveBaseName(file))
    }

    @Test
    fun testBatchExtractionSubfolderResolution() {
        val parentDestFolder = File("/sdcard/Download/Extracted")
        val archives = listOf(
            File("/sdcard/Download/archive1.zip"),
            File("/sdcard/Download/archive2.tar.gz"),
            File("/sdcard/Download/archive3.7z")
        )

        // For multiple archives (batch extraction), each archive receives a distinct subfolder named after the archive
        val targetDests = archives.map { archive ->
            if (archives.size > 1) {
                File(parentDestFolder, ArchiveManager.getArchiveBaseName(archive.name))
            } else {
                parentDestFolder
            }
        }

        assertEquals(File(parentDestFolder, "archive1"), targetDests[0])
        assertEquals(File(parentDestFolder, "archive2"), targetDests[1])
        assertEquals(File(parentDestFolder, "archive3"), targetDests[2])

        // Verify distinct target paths prevent conflict between archives
        assertNotEquals(targetDests[0], targetDests[1])
        assertNotEquals(targetDests[1], targetDests[2])
    }

    @Test
    fun testSingleExtractionSubfolderResolution() {
        val parentDestFolder = File("/sdcard/Download/MyArchive")
        val archives = listOf(
            File("/sdcard/Download/MyArchive.zip")
        )

        // For a single archive, extraction goes directly into the custom destination folder
        val targetDest = if (archives.size > 1) {
            File(parentDestFolder, ArchiveManager.getArchiveBaseName(archives.first().name))
        } else {
            parentDestFolder
        }

        assertEquals(parentDestFolder, targetDest)
    }

    @Test
    fun testExtractToCurrentFolder_batchCreatesSubfolders() {
        val currentDir = File("/sdcard/Download")
        val archives = listOf(
            File("/sdcard/Download/archive1.zip"),
            File("/sdcard/Download/archive2.tar.gz")
        )

        // Extract to current folder for multiple archives: creates separate subfolders in currentDir
        val targetDests = archives.map { archive ->
            val baseParent = archive.parentFile ?: currentDir
            if (archives.size > 1) {
                File(baseParent, ArchiveManager.getArchiveBaseName(archive.name))
            } else {
                baseParent
            }
        }

        assertEquals(File("/sdcard/Download/archive1"), targetDests[0])
        assertEquals(File("/sdcard/Download/archive2"), targetDests[1])
    }

    @Test
    fun testExtractToCurrentFolder_singleExtractsToRoot() {
        val currentDir = File("/sdcard/Download")
        val archives = listOf(
            File("/sdcard/Download/archive1.zip")
        )

        // Extract to current folder for a single archive: extracts directly to currentDir root
        val targetDests = archives.map { archive ->
            val baseParent = archive.parentFile ?: currentDir
            if (archives.size > 1) {
                File(baseParent, ArchiveManager.getArchiveBaseName(archive.name))
            } else {
                baseParent
            }
        }

        assertEquals(currentDir, targetDests[0])
    }
}
