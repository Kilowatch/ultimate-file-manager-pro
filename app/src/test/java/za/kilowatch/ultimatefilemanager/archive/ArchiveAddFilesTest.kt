package za.kilowatch.ultimatefilemanager.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveAddFilesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testIsWritableArchive() {
        assertTrue(ArchiveManager.isWritableArchive(File("test.zip")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.7z")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.tar")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.tar.gz")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.tgz")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.gz")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.tar.bz2")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.bz2")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.tar.xz")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.xz")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.tar.zst")))
        assertTrue(ArchiveManager.isWritableArchive(File("test.zst")))
    }

    @Test
    fun testAddFilesToZip() = runBlocking {
        val archive = tempFolder.newFile("test.zip")
        val file1 = tempFolder.newFile("hello.txt").apply { writeText("Hello") }
        val file2 = tempFolder.newFile("world.txt").apply { writeText("World") }

        // Create initial zip
        ArchiveManager.compress(null, listOf(file1), archive, format = ArchiveManager.Format.ZIP)

        // Add file2
        val res = ArchiveManager.addFilesToArchive(null, archive, listOf(file2), "", isMove = false)
        assertTrue(res.isSuccess)

        val entries = ArchiveManager.getArchiveEntries(archive)
        val names = entries.map { it.name }
        assertTrue(names.contains("hello.txt"))
        assertTrue(names.contains("world.txt"))
    }

    @Test
    fun testAddFilesTo7z() = runBlocking {
        val archive = tempFolder.newFile("test.7z")
        val file1 = tempFolder.newFile("hello.txt").apply { writeText("Hello") }
        val file2 = tempFolder.newFile("world.txt").apply { writeText("World") }

        // Create initial 7z
        ArchiveManager.compress(null, listOf(file1), archive, format = ArchiveManager.Format.SEVEN_Z)

        // Add file2
        val res = ArchiveManager.addFilesToArchive(null, archive, listOf(file2), "", isMove = false)
        assertTrue("Add to 7z failed: ${res.exceptionOrNull()?.message}", res.isSuccess)

        val entries = ArchiveManager.getArchiveEntries(archive)
        val names = entries.map { it.name }
        assertTrue(names.contains("hello.txt"))
        assertTrue(names.contains("world.txt"))
    }

    @Test
    fun testAddFilesToTarGz() = runBlocking {
        val archive = tempFolder.newFile("test.tar.gz")
        val file1 = tempFolder.newFile("hello.txt").apply { writeText("Hello") }
        val file2 = tempFolder.newFile("world.txt").apply { writeText("World") }

        ArchiveManager.compress(null, listOf(file1), archive, format = ArchiveManager.Format.TAR_GZ)

        val res = ArchiveManager.addFilesToArchive(null, archive, listOf(file2), "", isMove = false)
        assertTrue("Add to tar.gz failed: ${res.exceptionOrNull()?.message}", res.isSuccess)

        val entries = ArchiveManager.getArchiveEntries(archive)
        val names = entries.map { it.name }
        assertTrue(names.contains("hello.txt"))
        assertTrue(names.contains("world.txt"))
    }

    @Test
    fun testAddFilesToGz() = runBlocking {
        val archive = tempFolder.newFile("archive (1).gz")
        val file1 = tempFolder.newFile("hello.txt").apply { writeText("Hello") }
        val file2 = tempFolder.newFile("world.txt").apply { writeText("World") }

        ArchiveManager.compress(null, listOf(file1), archive, format = ArchiveManager.Format.GZ)

        val res = ArchiveManager.addFilesToArchive(null, archive, listOf(file2), "", isMove = false)
        assertTrue("Add to .gz failed: ${res.exceptionOrNull()?.message}", res.isSuccess)

        val entries = ArchiveManager.getArchiveEntries(archive)
        val names = entries.map { it.name }
        assertTrue(names.contains("hello.txt"))
        assertTrue(names.contains("world.txt"))
    }

    @Test
    fun testAddFilesToRawGz() = runBlocking {
        val archive = tempFolder.newFile("raw_archive.gz")
        val file1 = tempFolder.newFile("content.txt").apply { writeText("Original raw content") }
        val file2 = tempFolder.newFile("added.txt").apply { writeText("Added content") }

        // Create a raw GZIP file (NOT a TAR.GZ)
        org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(archive.outputStream()).use { out ->
            file1.inputStream().use { it.copyTo(out) }
        }

        // Add file2
        val res = ArchiveManager.addFilesToArchive(null, archive, listOf(file2), "", isMove = false)
        assertTrue("Add to raw .gz failed: ${res.exceptionOrNull()?.message}", res.isSuccess)

        val entries = ArchiveManager.getArchiveEntries(archive)
        val names = entries.map { it.name }
        assertTrue("Entries should contain added.txt: $names", names.contains("added.txt"))
        assertTrue("Entries should contain raw_archive: $names", names.contains("raw_archive"))
    }

    @Test
    fun testAddFilesToTar() = runBlocking {
        val archive = tempFolder.newFile("test.tar")
        val file1 = tempFolder.newFile("hello.txt").apply { writeText("Hello") }
        val file2 = tempFolder.newFile("world.txt").apply { writeText("World") }

        ArchiveManager.compress(null, listOf(file1), archive, format = ArchiveManager.Format.TAR)

        val res = ArchiveManager.addFilesToArchive(null, archive, listOf(file2), "", isMove = false)
        assertTrue("Add to .tar failed: ${res.exceptionOrNull()?.message}", res.isSuccess)

        val entries = ArchiveManager.getArchiveEntries(archive)
        val names = entries.map { it.name }
        assertTrue(names.contains("hello.txt"))
        assertTrue(names.contains("world.txt"))
    }

    @Test
    fun testAddFilesToBz2() = runBlocking {
        val archive = tempFolder.newFile("test.tar.bz2")
        val file1 = tempFolder.newFile("hello.txt").apply { writeText("Hello") }
        val file2 = tempFolder.newFile("world.txt").apply { writeText("World") }

        ArchiveManager.compress(null, listOf(file1), archive, format = ArchiveManager.Format.TAR_BZ2)

        val res = ArchiveManager.addFilesToArchive(null, archive, listOf(file2), "", isMove = false)
        assertTrue("Add to .tar.bz2 failed: ${res.exceptionOrNull()?.message}", res.isSuccess)

        val entries = ArchiveManager.getArchiveEntries(archive)
        val names = entries.map { it.name }
        assertTrue(names.contains("hello.txt"))
        assertTrue(names.contains("world.txt"))
    }

    @Test
    fun testAddFilesToXz() = runBlocking {
        val archive = tempFolder.newFile("test.tar.xz")
        val file1 = tempFolder.newFile("hello.txt").apply { writeText("Hello") }
        val file2 = tempFolder.newFile("world.txt").apply { writeText("World") }

        ArchiveManager.compress(null, listOf(file1), archive, format = ArchiveManager.Format.TAR_XZ)

        val res = ArchiveManager.addFilesToArchive(null, archive, listOf(file2), "", isMove = false)
        assertTrue("Add to .tar.xz failed: ${res.exceptionOrNull()?.message}", res.isSuccess)

        val entries = ArchiveManager.getArchiveEntries(archive)
        val names = entries.map { it.name }
        assertTrue(names.contains("hello.txt"))
        assertTrue(names.contains("world.txt"))
    }
}
