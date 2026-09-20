package za.kilowatch.ultimatefilemanager.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipMethod
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.checksum.ArchiveFileSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
class ZipZstandardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun createZstandardZip(destZip: File, entries: Map<String, ByteArray>) {
        ZipArchiveOutputStream(destZip.outputStream().buffered()).use { zout ->
            for ((name, data) in entries) {
                val compressedBytes = ByteArrayOutputStream().also { bos ->
                    ZstdCompressorOutputStream(bos).use { zos ->
                        zos.write(data)
                    }
                }.toByteArray()

                val entry = ZipArchiveEntry(name).apply {
                    method = ZipMethod.ZSTD.code // 93
                    size = data.size.toLong()
                    compressedSize = compressedBytes.size.toLong()
                    val crcCalc = CRC32().apply { update(data) }
                    crc = crcCalc.value
                }
                zout.addRawArchiveEntry(entry, ByteArrayInputStream(compressedBytes))
            }
        }
    }

    @Test
    fun testReadZipEntries_Zstandard() = runBlocking {
        val archive = tempFolder.newFile("zstd_archive.zip")
        val content1 = "Hello, Zstandard compressed ZIP!".toByteArray(Charsets.UTF_8)
        val content2 = "Nested file compressed with method 93.".toByteArray(Charsets.UTF_8)

        createZstandardZip(archive, mapOf(
            "hello.txt" to content1,
            "subfolder/nested.txt" to content2
        ))

        val entries = ArchiveManager.getArchiveEntries(archive)
        assertEquals(2, entries.size)

        val entryNames = entries.map { it.name }
        assertTrue(entryNames.contains("hello.txt"))
        assertTrue(entryNames.contains("subfolder/nested.txt"))

        val helloEntry = entries.first { it.name == "hello.txt" }
        assertEquals(content1.size.toLong(), helloEntry.uncompressedSize)
        assertFalse(helloEntry.isDirectory)
    }

    @Test
    fun testExtractZip_Zstandard() = runBlocking {
        val archive = tempFolder.newFile("zstd_extract.zip")
        val content1 = "Content of first file in Zstd ZIP".toByteArray(Charsets.UTF_8)
        val content2 = "Content of second file nested in directory".toByteArray(Charsets.UTF_8)

        createZstandardZip(archive, mapOf(
            "file1.txt" to content1,
            "dir/file2.txt" to content2
        ))

        val destDir = tempFolder.newFolder("extracted_zstd")
        val result = ArchiveManager.extract(context, archive, destDir)
        assertTrue("Extraction failed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        val extracted1 = File(destDir, "file1.txt")
        assertTrue("file1.txt does not exist", extracted1.exists())
        assertEquals(String(content1, Charsets.UTF_8), extracted1.readText(Charsets.UTF_8))

        val extracted2 = File(destDir, "dir/file2.txt")
        assertTrue("dir/file2.txt does not exist", extracted2.exists())
        assertEquals(String(content2, Charsets.UTF_8), extracted2.readText(Charsets.UTF_8))
    }

    @Test
    fun testExtractZipEntry_Zstandard() = runBlocking {
        val archive = tempFolder.newFile("zstd_entry.zip")
        val content = "Single entry extraction payload".toByteArray(Charsets.UTF_8)

        createZstandardZip(archive, mapOf(
            "data/sample.txt" to content
        ))

        val destDir = tempFolder.newFolder("extracted_entry")
        val result = ArchiveManager.extractZipEntry(archive, "data/sample.txt", destDir, context = context)
        assertTrue("extractZipEntry failed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        val extracted = File(destDir, "sample.txt")
        assertTrue("sample.txt does not exist", extracted.exists())
        assertEquals(String(content, Charsets.UTF_8), extracted.readText(Charsets.UTF_8))
    }

    @Test
    fun testArchiveFileSource_Zstandard() = runBlocking {
        val archive = tempFolder.newFile("zstd_source.zip")
        val expectedText = "Checksum calculation over Zstd ZIP stream"
        val content = expectedText.toByteArray(Charsets.UTF_8)

        createZstandardZip(archive, mapOf(
            "test_checksum.txt" to content
        ))

        val source = ArchiveFileSource(
            archiveFile = archive,
            entryPath = "test_checksum.txt",
            entrySize = content.size.toLong()
        )

        val stream = source.openStream(context)
        val readBytes = stream.use { it.readBytes() }
        assertEquals(expectedText, String(readBytes, Charsets.UTF_8))
    }

    @Test
    fun testDeleteZipEntry_Zstandard() = runBlocking {
        val archive = tempFolder.newFile("zstd_delete.zip")
        val content1 = "File to keep".toByteArray(Charsets.UTF_8)
        val content2 = "File to delete".toByteArray(Charsets.UTF_8)

        createZstandardZip(archive, mapOf(
            "keep.txt" to content1,
            "delete.txt" to content2
        ))

        val res = ArchiveManager.deleteZipEntry(archive, "delete.txt")
        assertTrue("deleteZipEntry failed: ${res.exceptionOrNull()?.message}", res.isSuccess)

        val entries = ArchiveManager.getArchiveEntries(archive)
        val names = entries.map { it.name }
        assertTrue("keep.txt should still exist", names.contains("keep.txt"))
        assertFalse("delete.txt should be gone", names.contains("delete.txt"))

        val destDir = tempFolder.newFolder("extracted_after_delete")
        val extractRes = ArchiveManager.extract(context, archive, destDir)
        assertTrue("Extract after delete failed: ${extractRes.exceptionOrNull()?.message}", extractRes.isSuccess)
        assertEquals(String(content1, Charsets.UTF_8), File(destDir, "keep.txt").readText(Charsets.UTF_8))
    }

    @Test
    fun testAddFilesToZip_Zstandard() = runBlocking {
        val archive = tempFolder.newFile("zstd_add.zip")
        val initialContent = "Initial zstd file".toByteArray(Charsets.UTF_8)
        createZstandardZip(archive, mapOf("initial.txt" to initialContent))

        val newFile = tempFolder.newFile("added.txt").apply {
            writeText("Added file content", Charsets.UTF_8)
        }

        val addRes = ArchiveManager.addFilesToArchive(context, archive, listOf(newFile), targetDirInArchive = "")
        assertTrue("addFilesToArchive failed: ${addRes.exceptionOrNull()?.message}", addRes.isSuccess)

        val entries = ArchiveManager.getArchiveEntries(archive)
        val names = entries.map { it.name }
        assertTrue(names.contains("initial.txt"))
        assertTrue(names.contains("added.txt"))

        val destDir = tempFolder.newFolder("extracted_after_add")
        val extractRes = ArchiveManager.extract(context, archive, destDir)
        assertTrue("Extract after add failed: ${extractRes.exceptionOrNull()?.message}", extractRes.isSuccess)
        assertEquals("Initial zstd file", File(destDir, "initial.txt").readText(Charsets.UTF_8))
        assertEquals("Added file content", File(destDir, "added.txt").readText(Charsets.UTF_8))
    }
}
