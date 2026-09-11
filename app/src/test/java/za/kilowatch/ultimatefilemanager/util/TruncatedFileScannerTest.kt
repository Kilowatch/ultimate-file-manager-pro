package za.kilowatch.ultimatefilemanager.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [TruncatedFileScanner].
 *
 * The matching rule and the traversal are tested here; the delete path is not, because it
 * dispatches to SAF/root/Shizuku wiring that only exists on a device (`/proc/mounts`, the shell
 * wrappers). Device verification of the whole feature is T031 — specifically FR-21–23 against the
 * 4,294,967,295-byte file the reported failure left on the card.
 *
 * Every test here works on a real temporary directory tree, because the property that matters most
 * is that a scan **does not delete anything** (FR-23), and that cannot be shown against a stub.
 */
class TruncatedFileScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fatMax = FilesystemCapabilities.FAT_MAX

    /** The card from the incident: a real 4 GiB−1 per-file ceiling. */
    private val fat32 = DestinationCapabilities(
        mountPath = "/mnt/pass_through/0/7DE2-1219",
        fsType = "vfat",
        fatSubtype = FatSubtype.FAT32,
        maxFileSize = fatMax,
        freeBytes = 478502L * 32768L,
        determined = true
    )

    /** Internal storage: no per-file ceiling. */
    private val f2fs = DestinationCapabilities(
        mountPath = "/mnt/pass_through/0/emulated",
        fsType = "f2fs",
        fatSubtype = null,
        maxFileSize = DestinationCapabilities.UNCONSTRAINED,
        freeBytes = 10001535L * 4096L,
        determined = true
    )

    // ------------------------------------------------------------- the match (FR-21)

    @Test
    fun `a file exactly at the ceiling is truncated`() {
        assertTrue(TruncatedFileScanner.isTruncated(fatMax, fat32))
    }

    @Test
    fun `one byte under the ceiling is a file that fits`() {
        assertFalse(TruncatedFileScanner.isTruncated(fatMax - 1, fat32))
    }

    @Test
    fun `one byte over the ceiling cannot happen but must not match either`() {
        // A file cannot be written past the ceiling, so this is unreachable in practice — but the
        // rule is equality, and an off-by-one in the other direction would match every large file.
        assertFalse(TruncatedFileScanner.isTruncated(fatMax + 1, fat32))
    }

    @Test
    fun `nothing on a filesystem with no ceiling is ever truncated`() {
        // The clause that stops the feature reporting every file on internal storage as damaged.
        assertFalse(TruncatedFileScanner.isTruncated(fatMax, f2fs))
        assertFalse(TruncatedFileScanner.isTruncated(8L * 1024 * 1024 * 1024, f2fs))
        assertFalse(TruncatedFileScanner.isTruncated(Long.MAX_VALUE, f2fs))
    }

    @Test
    fun `an undetermined destination yields no candidates`() {
        // FR-03, NFR-02: a destination whose filesystem could not be read has no ceiling, so
        // nothing can be shown to sit at one.
        assertFalse(TruncatedFileScanner.isTruncated(fatMax, DestinationCapabilities.unknown()))
    }

    @Test
    fun `an empty file is not truncated`() {
        assertFalse(TruncatedFileScanner.isTruncated(0L, fat32))
        assertFalse(TruncatedFileScanner.isTruncated(-1L, fat32))
        // Even a zero ceiling must not match an empty file — the same guard, seen directly.
        assertFalse(TruncatedFileScanner.isTruncated(0L, fat32.copy(maxFileSize = 0L)))
    }

    // -------------------------------------------------------------- the walk (FR-21)

    /** A scan that reports everything as being on [capabilities], whatever its real directory. */
    private fun scanAs(root: File, capabilities: DestinationCapabilities) = runBlocking {
        TruncatedFileScanner.scanWith(
            root = root,
            capabilitiesOf = { capabilities },
            sizeOf = { it.length() }
        )
    }

    private fun file(name: String, size: Long): File {
        val f = tmp.newFile(name)
        f.writeBytes(ByteArray(size.toInt()))
        return f
    }

    @Test
    fun `the truncated file is found and the complete one is not`() {
        // The real shape of the incident: a folder holding a truncated movie beside intact files.
        val dir = tmp.newFolder("Movies")
        file("Movies/Behind Enemy Lines (2001).mkv", 4096L)

        val found = runBlocking {
            TruncatedFileScanner.scanWith(
                root = dir,
                capabilitiesOf = { fat32 },
                sizeOf = { if (it.name.endsWith(".mkv")) fatMax else 4096L }
            )
        }

        assertEquals(1, found.size)
        assertEquals("Behind Enemy Lines (2001).mkv", found.single().file.name)
        assertEquals(fatMax, found.single().size)
        assertEquals(fatMax, found.single().ceiling)
        assertEquals(FatSubtype.FAT32, found.single().capabilities.fatSubtype)
    }

    @Test
    fun `the walk is recursive`() {
        val root = tmp.newFolder("card")
        File(root, "DCIM/Camera").mkdirs()
        File(root, "Download").mkdirs()
        file("card/DCIM/Camera/truncated.mkv", 2048L)
        file("card/Download/also-truncated.mkv", 2048L)

        val found = runBlocking {
            TruncatedFileScanner.scanWith(
                root = root,
                capabilitiesOf = { fat32 },
                sizeOf = { if (it.name.endsWith(".mkv")) fatMax else 2048L }
            )
        }

        assertEquals(listOf("also-truncated.mkv", "truncated.mkv"), found.map { it.file.name }.sorted())
    }

    @Test
    fun `an empty directory yields nothing rather than failing`() {
        assertTrue(scanAs(tmp.newFolder("empty"), fat32).isEmpty())
    }

    @Test
    fun `a single file is accepted as the root`() {
        val f = file("one.mkv", 1024L)

        val found = runBlocking {
            TruncatedFileScanner.scanWith(root = f, capabilitiesOf = { fat32 }, sizeOf = { fatMax })
        }

        assertEquals(1, found.size)
        assertEquals("one.mkv", found.single().file.name)
    }

    @Test
    fun `results are ordered by path so the list is stable between scans`() {
        val root = tmp.newFolder("card")
        listOf("c", "a", "b").forEach { name -> file("card/$name.mkv", 512L) }

        val found = runBlocking {
            TruncatedFileScanner.scanWith(
                root = root,
                capabilitiesOf = { fat32 },
                sizeOf = { fatMax }
            )
        }

        assertEquals(listOf("a.mkv", "b.mkv", "c.mkv"), found.map { it.file.name })
    }

    @Test
    fun `the capability is probed once per directory, not once per file`() {
        // NFR-01: a folder of many files must not cost a probe each. The real probe is at least a
        // StatFs syscall, and for a root destination a shell subprocess.
        val root = tmp.newFolder("card")
        repeat(25) { i -> file("card/file-$i.mkv", 128L) }
        var probes = 0

        runBlocking {
            TruncatedFileScanner.scanWith(
                root = root,
                capabilitiesOf = { probes++; fat32 },
                sizeOf = { fatMax }
            )
        }

        assertEquals(1, probes)
    }

    @Test
    fun `a tree crossing a mount boundary gets each directory's own ceiling`() {
        // The reason the cache is per-directory rather than per-scan: /storage/<card> and
        // /storage/emulated are different filesystems with different answers, and one of them has
        // no ceiling at all.
        val root = tmp.newFolder("storage")
        File(root, "card").mkdirs()
        File(root, "emulated").mkdirs()
        file("storage/card/on-the-card.mkv", 64L)
        file("storage/emulated/on-internal.mkv", 64L)

        val found = runBlocking {
            TruncatedFileScanner.scanWith(
                root = root,
                capabilitiesOf = { if (it.absolutePath.contains("emulated")) f2fs else fat32 },
                sizeOf = { fatMax }
            )
        }

        assertEquals(listOf("on-the-card.mkv"), found.map { it.file.name })
    }

    // ---------------------------------------------------- nothing is automatic (FR-23)

    @Test
    fun `scanning does not delete anything`() {
        // The structural half of FR-23. Everything here matches, so a scan with any deletion in it
        // would empty the folder.
        val root = tmp.newFolder("card")
        repeat(3) { i -> file("card/truncated-$i.mkv", 256L) }
        val before = root.listFiles()!!.map { it.name }.sorted()

        val found = runBlocking {
            TruncatedFileScanner.scanWith(root = root, capabilitiesOf = { fat32 }, sizeOf = { fatMax })
        }

        assertEquals(3, found.size)
        assertEquals(before, root.listFiles()!!.map { it.name }.sorted())
        assertTrue(found.all { it.file.exists() })
    }

    @Test
    fun `scanning a directory the app cannot list yields nothing rather than throwing`() {
        val missing = File(tmp.root, "not-there")

        assertTrue(scanAs(missing, fat32).isEmpty())
    }
}
