package za.kilowatch.ultimatefilemanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FilesystemCapabilities].
 *
 * The fixture is the real `/proc/mounts` content captured from the device, filtered to the lines
 * that matter. The FUSE/vfat pair is what motivates the whole feature: the app writes through
 * `/storage/7DE2-1219`, which reports itself as `fuse`, while the filesystem that actually backs
 * it lives at `/mnt/pass_through/0/7DE2-1219` and is `vfat` (FR-01c).
 */
class FilesystemCapabilitiesTest {

    private companion object {
        /** Captured verbatim; factored out only to keep the fixture readable. */
        const val F2FS_OPTS =
            "rw,lazytime,seclabel,nosuid,nodev,noatime,background_gc=on,nogc_merge,discard," +
                "discard_unit=block,user_xattr,inline_xattr,acl,inline_data,inline_dentry," +
                "flush_merge,barrier,extent_cache,mode=adaptive,active_logs=6,reserve_root=32768," +
                "resuid=0,resgid=5678,usrquota,grpquota,inlinecrypt,alloc_mode=default," +
                "checkpoint_merge,fsync_mode=nobarrier,compress_algorithm=lz4," +
                "compress_log_size=5,compress_mode=user,memory=normal,errors=continue"

        const val VFAT_OPTS =
            "rw,dirsync,nosuid,nodev,noexec,noatime,gid=1023,fmask=0007,dmask=0007," +
                "allow_utime=0020,codepage=437,iocharset=iso8859-1,shortname=mixed,utf8," +
                "time_offset=120,errors=remount-ro"

        const val FUSE_OPTS = "rw,lazytime,nosuid,nodev,noexec,noatime,user_id=0,group_id=0,allow_other"
    }

    /**
     * Verbatim from the device, in the order `/proc/mounts` reports them.
     *
     * Note the shape the resolution has to work with: every path the app can hand to a user
     * (`/storage/<volid>`) is a FUSE view, and each volume also appears once as a direct,
     * non-FUSE mount under `/mnt/pass_through/0/<volid>` — including internal storage.
     */
    private val deviceTable = """
        /dev/block/dm-67 /data f2fs $F2FS_OPTS 0 0
        /dev/fuse /storage/emulated fuse $FUSE_OPTS 0 0
        /dev/block/dm-67 /mnt/pass_through/0/emulated f2fs $F2FS_OPTS 0 0
        /dev/block/vold/public:179,1 /mnt/media_rw/7DE2-1219 vfat $VFAT_OPTS 0 0
        /dev/block/vold/public:179,1 /mnt/secure/asec vfat $VFAT_OPTS 0 0
        /dev/fuse /mnt/user/0/emulated fuse $FUSE_OPTS 0 0
        /dev/fuse /storage/7DE2-1219 fuse $FUSE_OPTS 0 0
        /dev/fuse /mnt/user/0/7DE2-1219 fuse $FUSE_OPTS 0 0
        /dev/block/vold/public:179,1 /mnt/pass_through/0/7DE2-1219 vfat $VFAT_OPTS 0 0
    """.trimIndent()

    private fun entries() = FilesystemCapabilities.parseMountTable(deviceTable)

    // ---------------------------------------------------------------- parsing (T002)

    @Test
    fun `parses every entry in the captured device table`() {
        assertEquals(9, entries().size)
    }

    @Test
    fun `destination volume parses as vfat`() {
        val entry = entries().first { it.mountPoint == "/mnt/media_rw/7DE2-1219" }

        assertEquals("/dev/block/vold/public:179,1", entry.device)
        assertEquals("vfat", entry.fsType)
    }

    @Test
    fun `the path the app writes through parses as fuse, not vfat`() {
        // The trap the feature exists to work around: a StatFs-based check reads
        // FUSE_SUPER_MAGIC here and concludes "not FAT" on the exact path that failed.
        val entry = entries().first { it.mountPoint == "/storage/7DE2-1219" }

        assertEquals("/dev/fuse", entry.device)
        assertEquals("fuse", entry.fsType)
    }

    @Test
    fun `options are captured whole and exclude the trailing dump and pass fields`() {
        val entry = entries().first { it.mountPoint == "/storage/7DE2-1219" }

        // The trailing "0 0" (dump and pass number) must not leak into the options field.
        assertEquals(FUSE_OPTS, entry.options)
    }

    @Test
    fun `blank lines and comments are skipped`() {
        val text = """
            # This is a comment

            /dev/fuse /storage/7DE2-1219 fuse rw 0 0

            # /dev/block/vold/public:179,1 /mnt/media_rw/7DE2-1219 vfat rw 0 0

        """.trimIndent()

        val parsed = FilesystemCapabilities.parseMountTable(text)

        assertEquals(1, parsed.size)
        assertEquals("/storage/7DE2-1219", parsed[0].mountPoint)
    }

    @Test
    fun `lines with fewer than four fields are ignored rather than throwing`() {
        val text = """
            /dev/fuse
            /dev/fuse /storage/7DE2-1219
            /dev/fuse /storage/7DE2-1219 fuse
            /dev/fuse /storage/7DE2-1219 fuse rw 0 0
        """.trimIndent()

        val parsed = FilesystemCapabilities.parseMountTable(text)

        assertEquals(1, parsed.size)
        assertEquals("fuse", parsed[0].fsType)
    }

    @Test
    fun `fields are split on runs of whitespace, not single spaces`() {
        val text = "/dev/fuse\t/storage/7DE2-1219   fuse \t rw 0 0"

        val parsed = FilesystemCapabilities.parseMountTable(text)

        assertEquals(1, parsed.size)
        assertEquals("/storage/7DE2-1219", parsed[0].mountPoint)
        assertEquals("fuse", parsed[0].fsType)
        assertEquals("rw", parsed[0].options)
    }

    @Test
    fun `octal-escaped spaces in paths are decoded`() {
        // The kernel escapes spaces, tabs, newlines and backslashes in seq_file output.
        val parsed = FilesystemCapabilities.parseMountTable(
            "/dev/block/vold/public:179,2 /mnt/my\\040card vfat rw 0 0"
        )

        assertEquals(1, parsed.size)
        assertEquals("/mnt/my card", parsed[0].mountPoint)
    }

    @Test
    fun `octal decoding covers tab, newline and backslash`() {
        val text = "" +
            "/dev/block/vold/public:179,2 /mnt/a\\011b vfat rw 0 0\n" +
            "/dev/block/vold/public:179,2 /mnt/a\\012b vfat rw 0 0\n" +
            "/dev/block/vold/public:179,2 /mnt/a\\134b vfat rw 0 0\n"

        val parsed = FilesystemCapabilities.parseMountTable(text)

        assertEquals("/mnt/a\tb", parsed[0].mountPoint)
        assertEquals("/mnt/a\nb", parsed[1].mountPoint)
        assertEquals("/mnt/a\\b", parsed[2].mountPoint)
    }

    @Test
    fun `a backslash that is not a valid octal escape is left alone`() {
        val parsed = FilesystemCapabilities.parseMountTable(
            "/dev/block/vold/public:179,2 /mnt/a\\zzz vfat rw 0 0"
        )

        assertEquals("/mnt/a\\zzz", parsed[0].mountPoint)
    }

    @Test
    fun `a trailing backslash at the end of a field is left alone`() {
        val parsed = FilesystemCapabilities.parseMountTable(
            "/dev/block/vold/public:179,2 /mnt/card\\ vfat rw 0 0"
        )

        assertEquals("/mnt/card\\", parsed[0].mountPoint)
    }

    @Test
    fun `an empty table yields no entries`() {
        assertTrue(FilesystemCapabilities.parseMountTable("").isEmpty())
    }

    @Test
    fun `a table of only comments and blanks yields no entries`() {
        assertTrue(FilesystemCapabilities.parseMountTable("\n# nothing here\n\n").isEmpty())
    }

    // ------------------------------------------------- FUSE resolution (T003)

    @Test
    fun `the sdcard fuse path resolves to its vfat backing volume`() {
        // The done criterion for T003, and the case the reported failure hit.
        val resolved = FilesystemCapabilities.resolveMount(entries(), "/storage/7DE2-1219")

        assertNotNull(resolved)
        assertEquals("vfat", resolved!!.fsType)
        assertEquals("/mnt/pass_through/0/7DE2-1219", resolved.mountPoint)
    }

    @Test
    fun `internal storage fuse path resolves to f2fs`() {
        val resolved = FilesystemCapabilities.resolveMount(entries(), "/storage/emulated")

        assertNotNull(resolved)
        assertEquals("f2fs", resolved!!.fsType)
        assertEquals("/mnt/pass_through/0/emulated", resolved.mountPoint)
    }

    @Test
    fun `resolution uses the longest matching prefix`() {
        // /mnt/media_rw/... is a real mount; /mnt is not, so no accidental short match.
        val resolved = FilesystemCapabilities.resolveMount(
            entries(),
            "/mnt/media_rw/7DE2-1219/Movies/Behind Enemy Lines (2001).mkv"
        )

        assertEquals("vfat", resolved!!.fsType)
        assertEquals("/mnt/media_rw/7DE2-1219", resolved.mountPoint)
    }

    @Test
    fun `a path under a fuse volume resolves through the volume, not by string prefix on the file`() {
        val resolved = FilesystemCapabilities.resolveMount(
            entries(),
            "/storage/7DE2-1219/Movies/Behind Enemy Lines (2001).mkv"
        )

        assertEquals("vfat", resolved!!.fsType)
    }

    @Test
    fun `a sibling volume whose name shares a prefix is not matched`() {
        // /storage/7DE2-121 is not /storage/7DE2-1219 — matching must respect segment boundaries.
        val resolved = FilesystemCapabilities.resolveMount(entries(), "/storage/7DE2-121")

        assertNull(resolved)
    }

    @Test
    fun `the mount point itself matches exactly`() {
        val resolved = FilesystemCapabilities.resolveMount(entries(), "/storage/7DE2-1219")

        assertNotNull(resolved)
    }

    @Test
    fun `a prefix that is not a path segment boundary is rejected`() {
        // /storage/7DE2-1219X must not match /storage/7DE2-1219.
        assertNull(FilesystemCapabilities.resolveMount(entries(), "/storage/7DE2-1219X"))
    }

    @Test
    fun `a non-fuse path is returned directly without a backing lookup`() {
        val resolved = FilesystemCapabilities.resolveMount(entries(), "/mnt/media_rw/7DE2-1219")

        assertEquals("vfat", resolved!!.fsType)
    }

    @Test
    fun `an unmounted path resolves to nothing rather than guessing`() {
        // Fail open lives in probe(); resolution itself must not invent an answer.
        assertNull(FilesystemCapabilities.resolveMount(entries(), "/storage/NOT-A-VOLUME/file.mkv"))
        assertNull(FilesystemCapabilities.resolveMount(entries(), "/nonexistent/path"))
    }

    @Test
    fun `a fuse volume with no non-fuse backing entry keeps the fuse entry`() {
        // Fail open: an unresolvable fuse mount stays fuse, which imposes no ceiling.
        val fuseOnly = FilesystemCapabilities.parseMountTable(
            "/dev/fuse /storage/7DE2-1219 fuse rw 0 0"
        )

        val resolved = FilesystemCapabilities.resolveMount(fuseOnly, "/storage/7DE2-1219")

        assertEquals("fuse", resolved!!.fsType)
    }

    @Test
    fun `the user-scoped fuse mount also resolves to its backing volume`() {
        val resolved = FilesystemCapabilities.resolveMount(entries(), "/mnt/user/0/7DE2-1219")

        assertEquals("vfat", resolved!!.fsType)
    }

    @Test
    fun `emulated storage falls back to data when no pass-through mount exists`() {
        // Older devices have no /mnt/pass_through; /data is always where emulated storage lives.
        val legacy = FilesystemCapabilities.parseMountTable(
            "/dev/block/dm-67 /data f2fs rw 0 0\n" +
                "/dev/fuse /storage/emulated fuse rw 0 0"
        )

        val resolved = FilesystemCapabilities.resolveMount(legacy, "/storage/emulated")

        assertEquals("f2fs", resolved!!.fsType)
        assertEquals("/data", resolved.mountPoint)
    }

    @Test
    fun `the self and zero volume aliases resolve as emulated storage`() {
        val resolvedSelf = FilesystemCapabilities.resolveMount(entries(), "/storage/self")
        val resolvedZero = FilesystemCapabilities.resolveMount(entries(), "/storage/0")

        // Neither is mounted under those names here, so both fall through to emulated's backing.
        assertEquals("f2fs", resolvedSelf!!.fsType)
        assertEquals("f2fs", resolvedZero!!.fsType)
    }

    @Test
    fun `an empty table resolves nothing`() {
        assertNull(FilesystemCapabilities.resolveMount(emptyList(), "/storage/7DE2-1219"))
    }

    // ------------------------------------------- FAT classification (T004)

    @Test
    fun `the target card classifies as FAT32 from its cluster count`() {
        // Real device reading: `stat -f /storage/7DE2-1219` → Block Size 32768, Blocks 976624.
        val subtype = FilesystemCapabilities.fatSubtypeForClusters(976624L)

        assertEquals(FatSubtype.FAT32, subtype)
    }

    @Test
    fun `cluster counts are classified at the FAT spec boundaries`() {
        assertEquals(FatSubtype.FAT12, FilesystemCapabilities.fatSubtypeForClusters(1L))
        assertEquals(FatSubtype.FAT12, FilesystemCapabilities.fatSubtypeForClusters(4084L))
        assertEquals(FatSubtype.FAT16, FilesystemCapabilities.fatSubtypeForClusters(4085L))
        assertEquals(FatSubtype.FAT16, FilesystemCapabilities.fatSubtypeForClusters(65524L))
        assertEquals(FatSubtype.FAT32, FilesystemCapabilities.fatSubtypeForClusters(65525L))
    }

    @Test
    fun `a non-positive cluster count is not a volume`() {
        assertNull(FilesystemCapabilities.fatSubtypeForClusters(0L))
        assertNull(FilesystemCapabilities.fatSubtypeForClusters(-1L))
    }

    @Test
    fun `only vfat is size-constrained`() {
        assertEquals(
            FilesystemCapabilities.FAT_MAX,
            FilesystemCapabilities.maxFileSizeFor("vfat")
        )
        assertEquals(
            DestinationCapabilities.UNCONSTRAINED,
            FilesystemCapabilities.maxFileSizeFor("f2fs")
        )
        assertEquals(
            DestinationCapabilities.UNCONSTRAINED,
            FilesystemCapabilities.maxFileSizeFor("exfat")
        )
        assertEquals(
            DestinationCapabilities.UNCONSTRAINED,
            FilesystemCapabilities.maxFileSizeFor("fuse")
        )
        assertEquals(
            DestinationCapabilities.UNCONSTRAINED,
            FilesystemCapabilities.maxFileSizeFor(null)
        )
    }

    @Test
    fun `the detected subtype never moves the ceiling`() {
        // FR-01a: the subtype names the filesystem in the message; the limit is FAT_MAX either
        // way. A FAT16 volume cannot hold a file over 4 GiB−1 regardless, so applying the FAT32
        // ceiling to it cannot block a transfer that would have succeeded (NFR-02).
        val fat16 = DestinationCapabilities(
            fsType = "vfat",
            fatSubtype = FilesystemCapabilities.fatSubtypeForClusters(10_000L),
            maxFileSize = FilesystemCapabilities.maxFileSizeFor("vfat"),
            determined = true
        )

        assertEquals(FatSubtype.FAT16, fat16.fatSubtype)
        assertTrue(!fat16.cannotHold(FilesystemCapabilities.FAT_MAX))
        assertTrue(fat16.cannotHold(FilesystemCapabilities.FAT_MAX + 1))
    }

    // ----------------------------------------------------- free space (T005)

    @Test
    fun `stat output from the target card parses to the same free space df reports`() {
        // `stat -f -c '%b %a %S' /storage/7DE2-1219` as the app user → "976624 478502 32768".
        val free = FilesystemCapabilities.parseStatFreeBytes(listOf("976624 478502 32768"))

        assertEquals(478502L * 32768L, free)
        assertEquals(15_679_553_536L, free)
    }

    @Test
    fun `internal storage stat output parses`() {
        // `stat -f -c '%b %a %S' /data` → "27699708 10001535 4096".
        val free = FilesystemCapabilities.parseStatFreeBytes(listOf("27699708 10001535 4096"))

        assertEquals(10001535L * 4096L, free)
    }

    @Test
    fun `a leading or trailing blank line does not defeat the parse`() {
        val free = FilesystemCapabilities.parseStatFreeBytes(
            listOf("", "  976624 478502 32768  ", "")
        )

        assertEquals(478502L * 32768L, free)
    }

    @Test
    fun `stat output that cannot be read yields unknown, never zero`() {
        val unknown = DestinationCapabilities.FREE_UNKNOWN

        assertEquals(unknown, FilesystemCapabilities.parseStatFreeBytes(emptyList()))
        assertEquals(unknown, FilesystemCapabilities.parseStatFreeBytes(listOf("stat: Permission denied")))
        assertEquals(unknown, FilesystemCapabilities.parseStatFreeBytes(listOf("976624 478502")))
        assertEquals(unknown, FilesystemCapabilities.parseStatFreeBytes(listOf("976624 abc 32768")))
        assertEquals(unknown, FilesystemCapabilities.parseStatFreeBytes(listOf("976624 -1 32768")))
        assertEquals(unknown, FilesystemCapabilities.parseStatFreeBytes(listOf("976624 478502 0")))
    }

    @Test
    fun `a block count that would overflow is reported as unknown`() {
        val unknown = DestinationCapabilities.FREE_UNKNOWN

        assertEquals(
            unknown,
            FilesystemCapabilities.parseStatFreeBytes(listOf("${Long.MAX_VALUE} ${Long.MAX_VALUE} 2"))
        )
    }

    @Test
    fun `zero free space is a real reading, not an unknown`() {
        // A full card must produce a warning; only an unreadable one stays silent.
        val free = FilesystemCapabilities.parseStatFreeBytes(listOf("976624 0 32768"))

        assertEquals(0L, free)
        assertTrue(free != DestinationCapabilities.FREE_UNKNOWN)
    }

    // ------------------------------------------------- capability semantics (T001)

    @Test
    fun `unknown returns a destination that permits the transfer`() {
        val caps = DestinationCapabilities.unknown()

        assertEquals(DestinationCapabilities.UNCONSTRAINED, caps.maxFileSize)
        assertEquals(DestinationCapabilities.FREE_UNKNOWN, caps.freeBytes)
        assertNull(caps.mountPath)
        assertNull(caps.fsType)
        assertTrue(!caps.determined)
        assertTrue(!caps.cannotHold(Long.MAX_VALUE))
    }

    @Test
    fun `an undetermined destination never blocks even for an absurd file size`() {
        val caps = DestinationCapabilities.unknown()

        // Fail open (FR-03): an unresolved destination must permit, not refuse.
        assertTrue(!caps.cannotHold(FilesystemCapabilities.FAT_MAX + 1))
    }

    @Test
    fun `the fat ceiling is inclusive`() {
        val fat32 = DestinationCapabilities(
            mountPath = "/mnt/pass_through/0/7DE2-1219",
            fsType = "vfat",
            fatSubtype = FatSubtype.FAT32,
            maxFileSize = FilesystemCapabilities.FAT_MAX,
            freeBytes = 15_000_000_000L,
            determined = true
        )

        // FR-06: a file exactly at the limit is allowed.
        assertTrue(!fat32.cannotHold(FilesystemCapabilities.FAT_MAX))
        assertTrue(fat32.cannotHold(FilesystemCapabilities.FAT_MAX + 1))
    }

    @Test
    fun `the reported failure size is over the limit by exactly one byte`() {
        // 4,294,967,295 bytes = 2^32 - 1, the size the truncated .mkv was left at.
        val fat32 = DestinationCapabilities(
            fsType = "vfat",
            maxFileSize = FilesystemCapabilities.FAT_MAX,
            determined = true
        )

        assertEquals(4_294_967_295L, FilesystemCapabilities.FAT_MAX)
        assertTrue(fat32.cannotHold(FilesystemCapabilities.FAT_MAX + 1))
    }

    @Test
    fun `insufficient space is a warning and is false when free space is unknown`() {
        val known = DestinationCapabilities(freeBytes = 1000L, determined = true)
        assertTrue(known.insufficientSpaceFor(1001L))
        assertTrue(!known.insufficientSpaceFor(1000L))

        val unknown = DestinationCapabilities.unknown()
        assertTrue(!unknown.insufficientSpaceFor(Long.MAX_VALUE))
    }
}
