package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.storage.RootShellWrapper
import za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper
import java.io.File

/**
 * What a transfer destination can physically accept.
 *
 * Produced before any bytes are written so that a transfer which is guaranteed to fail can be
 * refused up front, instead of after it has spent half an hour copying and then left a
 * truncated file that is indistinguishable from a complete one (see `.plans/spec.md` FR-01).
 *
 * **The filesystem type must be read from the mount table, never from `StatFs`.** The app's own
 * destination paths are FUSE mounts and report `FUSE_SUPER_MAGIC` (`0x65735546`), so a
 * `StatFs`-based type check concludes "not FAT" on exactly the paths this type exists to
 * protect. See [FilesystemCapabilities] for the resolution order.
 *
 * Every field falls back to a sentinel when the probe could not determine it, and an
 * undetermined destination **permits** the transfer — an unknown destination must never cause
 * a block (FR-03, fail open).
 */
data class DestinationCapabilities(
    /** The mount point this destination resolves to, or null if it could not be resolved. */
    val mountPath: String? = null,

    /** Filesystem type as reported by the mount table, e.g. `"vfat"`, `"f2fs"`, `"exfat"`. */
    val fsType: String? = null,

    /**
     * FAT variant, resolved only when [fsType] is `"vfat"`.
     * Refines the explanation shown to the user; never changes [maxFileSize]. Read through
     * [displayName] rather than directly, so the message names the variant instead of `vfat`.
     */
    val fatSubtype: FatSubtype? = null,

    /** Largest single file this destination can hold, in bytes. [UNCONSTRAINED] when none applies. */
    val maxFileSize: Long = UNCONSTRAINED,

    /** Bytes available at the destination, or [FREE_UNKNOWN] if it could not be read. */
    val freeBytes: Long = FREE_UNKNOWN,

    /** True only when the probe actually resolved this destination. */
    val determined: Boolean = false
) {

    /**
     * How this filesystem should be named to the user (FR-05).
     *
     * The detected FAT subtype where there is one, because *"FAT32"* is what a user recognises —
     * `vfat` is the kernel's name for the whole family and names none of its variants, so printing
     * it in an explanation of which limit applied to which card says less than nothing to the
     * person reading it. Every other filesystem falls through to the mount table's own name
     * (`f2fs`, `exfat`), which is already the name the app shows elsewhere.
     *
     * [fatSubtype] is resolved only for `vfat` (see [FilesystemCapabilities.probe]), so the
     * fallthrough is the normal case rather than an error path.
     */
    val displayName: String
        get() = fatSubtype?.name ?: fsType.orEmpty()

    /**
     * True when this destination provably cannot hold a file of [fileSize] bytes.
     *
     * Deliberately strict: requires [determined], so an unresolved destination can never
     * produce a block (FR-03). A file exactly [maxFileSize] bytes is allowed — the limit is
     * inclusive (FR-06).
     */
    fun cannotHold(fileSize: Long): Boolean = determined && fileSize > maxFileSize

    /**
     * True when free space is known and is smaller than [bytes].
     *
     * A warning, never a block — the estimate can be stale (FR-09, FR-10).
     */
    fun insufficientSpaceFor(bytes: Long): Boolean =
        freeBytes != FREE_UNKNOWN && bytes > freeBytes

    companion object {
        /** No per-file ceiling applies. Also the value used when the destination is undetermined. */
        const val UNCONSTRAINED = Long.MAX_VALUE

        /** Sentinel meaning free space could not be read. */
        const val FREE_UNKNOWN = -1L

        /**
         * A destination the probe could not resolve.
         *
         * Permits the transfer by construction: [determined] is false and [maxFileSize] is
         * [UNCONSTRAINED], so [cannotHold] is always false (FR-03).
         */
        fun unknown(): DestinationCapabilities = DestinationCapabilities()
    }
}

/**
 * FAT variant, used only to name the filesystem in a user-facing message (FR-05).
 *
 * The per-file ceiling is [FilesystemCapabilities.FAT_MAX] for all three variants: FAT12 and
 * FAT16 volumes cannot exceed it in practice, so applying it universally cannot block a
 * transfer that would otherwise have succeeded (FR-01a, NFR-02).
 */
enum class FatSubtype { FAT12, FAT16, FAT32 }

/**
 * A single entry parsed from `/proc/mounts`.
 *
 * The mount table is the authoritative source for a destination's filesystem type: it is
 * readable by the app process with no permission and no root, and unlike `StatFs` it names the
 * real filesystem even when the path the app writes through is a FUSE view of it.
 */
data class MountEntry(
    /** Backing device, e.g. `/dev/block/vold/public:179,1`. */
    val device: String,
    /** Mount point, e.g. `/mnt/media_rw/7DE2-1219`. */
    val mountPoint: String,
    /** Filesystem type as reported by the kernel, e.g. `vfat`, `f2fs`, `fuse`. */
    val fsType: String,
    /** Comma-separated mount options. */
    val options: String
)

/**
 * Destination capability probing for the pre-flight transfer check.
 *
 * Resolution order, and the reason for each step:
 * 1. Resolve the destination to a **mount path** — SAF trees via their root docId, root/Shizuku
 *    paths via the working-path mapping, plain files via their absolute path.
 * 2. Read `/proc/mounts` (readable by the app process; no root, no permission) and match the
 *    longest prefix. When the match is `fuse`, follow the volume id to its backing
 *    `/mnt/media_rw/<volid>` entry — that is where the real type lives.
 * 3. For `vfat`, the ceiling is [FAT_MAX]; `StatFs` block count classifies the variant for the
 *    message only.
 * 4. Free space via `StatFs.availableBytes` — **not** `File.freeSpace`, which is meaningless on
 *    SAF and root destinations because those `File` subclasses override no free-space method.
 *
 * Any failure at any step yields [DestinationCapabilities.unknown] (fail open).
 */
object FilesystemCapabilities {

    const val TAG = "FilesystemCapabilities"

    /** The kernel's mount table — readable by the app process with no permission and no root. */
    private const val PROC_MOUNTS = "/proc/mounts"

    /**
     * Maximum size of a single file on any FAT-family filesystem: 4 GiB − 1 byte
     * (`0xFFFFFFFF`), the FAT32 ceiling. Applied to FAT12/FAT16 as well — see [FatSubtype].
     */
    const val FAT_MAX = 4_294_967_295L

    /**
     * Cluster-count thresholds that separate the FAT variants (FAT spec): a volume with fewer
     * than 4085 clusters is FAT12, fewer than 65525 is FAT16, anything more is FAT32.
     */
    const val FAT12_MAX_CLUSTERS = 4085L
    const val FAT16_MAX_CLUSTERS = 65525L

    /**
     * The largest single file [fsType] can hold (FR-01, FR-01a).
     *
     * Only `vfat` is constrained. FAT is the only common filesystem with a per-file ceiling a
     * user can realistically reach — exFAT and NTFS have none, and ext4's is unreachable — so
     * everything else is [DestinationCapabilities.UNCONSTRAINED] (Q4). Note `vfat` covers FAT12,
     * FAT16 and FAT32, and all three take the same [FAT_MAX].
     */
    fun maxFileSizeFor(fsType: String?): Long =
        if (fsType == "vfat") FAT_MAX else DestinationCapabilities.UNCONSTRAINED

    /**
     * Classifies the FAT variant from a volume's cluster count.
     *
     * `fat_statfs()` sets `f_bsize = cluster_size` and `f_blocks = max_cluster − FAT_START_ENT`,
     * so `StatFs.blockCount` is the cluster count and needs no further arithmetic. Verified on the
     * target card: `Block Size 32768`, `Blocks 976624` → 976624 clusters ⇒ FAT32, and
     * 976624 × 32768 = 32.0 GB, consistent with the physical card.
     *
     * **This refines the message only — it must never change the ceiling** (FR-01a). Returns null
     * for a non-positive count, which is not a real volume.
     */
    fun fatSubtypeForClusters(clusters: Long): FatSubtype? = when {
        clusters <= 0L -> null
        clusters < FAT12_MAX_CLUSTERS -> FatSubtype.FAT12
        clusters < FAT16_MAX_CLUSTERS -> FatSubtype.FAT16
        else -> FatSubtype.FAT32
    }

    /**
     * Free bytes available at [path], or [DestinationCapabilities.FREE_UNKNOWN] if they cannot be
     * read (FR-08).
     *
     * **`StatFs`, never `File.freeSpace`.** `SafFile`, `RootFile` and `ShizukuFile` are `File`
     * subclasses that override no free-space method, so on exactly the destinations this feature
     * protects `File.freeSpace` silently answers for the wrong path — or for nothing at all.
     *
     * Where `StatFs` cannot reach the path — an elevated destination such as `/data/adb/…`, which
     * the app process is denied — the value is read with `stat -f` through the root or Shizuku
     * shell (`storage/RootShellWrapper.kt:72`, `storage/ShizukuShellWrapper.kt:261`). Both are
     * blocking, hence [Dispatchers.IO], and both are gated on the shell actually being authorized
     * so an ordinary device never pays for a shell spawn it cannot use.
     *
     * Blocking; call from a coroutine. Never throws — free space is a warning, and a warning that
     * cannot be computed must stay silent rather than fail a transfer (FR-10, NFR-02).
     */
    suspend fun freeSpaceAt(path: String, context: Context? = null): Long {
        if (path.isBlank()) return DestinationCapabilities.FREE_UNKNOWN

        val direct = withContext(Dispatchers.IO) { directFreeSpace(path) }
        if (direct != DestinationCapabilities.FREE_UNKNOWN) return direct

        return withContext(Dispatchers.IO) { shellFreeSpace(path, context) }
    }

    /** `StatFs.availableBytes`, the bytes actually writable by this process. */
    private fun directFreeSpace(path: String): Long = try {
        // StatFs(String) is deprecated in favour of StatFs(File) only for the API-18 framework
        // move; it takes the same path string and is what the rest of the app uses.
        @Suppress("DEPRECATION")
        val stat = StatFs(path)
        val available = stat.availableBytes
        if (available >= 0L) available else DestinationCapabilities.FREE_UNKNOWN
    } catch (_: Throwable) {
        // Does not exist, is not readable, or SELinux blocks statfs on this path.
        DestinationCapabilities.FREE_UNKNOWN
    }

    /** `stat -f` through root or Shizuku, for destinations the app process cannot statfs. */
    private fun shellFreeSpace(path: String, context: Context?): Long {
        val command = "stat -f -c '%b %a %S' '${RootShellWrapper.escapeShellPath(path)}'"

        if (RootShellWrapper.isAuthorized(context)) {
            val (code, output) = RootShellWrapper.runCommand(command)
            if (code == 0) {
                val free = parseStatFreeBytes(output)
                if (free != DestinationCapabilities.FREE_UNKNOWN) return free
            }
        }

        if (ShizukuShellWrapper.isAuthorized()) {
            val (code, output) = ShizukuShellWrapper.runCommand(command)
            if (code == 0) {
                val free = parseStatFreeBytes(output)
                if (free != DestinationCapabilities.FREE_UNKNOWN) return free
            }
        }

        return DestinationCapabilities.FREE_UNKNOWN
    }

    /**
     * Parses `stat -f -c '%b %a %S'` output — `total_blocks available_blocks block_size` — into
     * available bytes.
     *
     * The machine-readable form is used rather than the human `stat -f` layout so the parse does
     * not depend on column widths or on how many lines the device's `stat` chooses to print.
     * Verified on the target card, where the app user gets `976624 478502 32768`, i.e. the same
     * 478502 free clusters × 32768 that `StatFs` reports.
     *
     * Returns [DestinationCapabilities.FREE_UNKNOWN] for anything it cannot read; never throws.
     */
    fun parseStatFreeBytes(output: List<String>): Long {
        for (line in output) {
            val fields = line.trim().split(WHITESPACE)
            if (fields.size < 3) continue
            val available = fields[1].toLongOrNull() ?: continue
            val blockSize = fields[2].toLongOrNull() ?: continue
            if (available < 0L || blockSize <= 0L) continue
            if (available > Long.MAX_VALUE / blockSize) return DestinationCapabilities.FREE_UNKNOWN
            return available * blockSize
        }
        return DestinationCapabilities.FREE_UNKNOWN
    }

    /**
     * Reads and parses `/proc/mounts`.
     *
     * **Call once per transfer operation and pass the result to every [probe].** Mounts change
     * when a volume is ejected, so the table must not be cached globally — but it must not be
     * re-read per file either, or a large batch turns into thousands of reads of a 15 KB file
     * (NFR-01).
     *
     * Returns an empty list when the table cannot be read. Every destination then resolves to
     * "undetermined", which permits the transfer — the correct outcome (FR-03).
     */
    suspend fun readMountTable(): List<MountEntry> = withContext(Dispatchers.IO) {
        try {
            parseMountTable(File(PROC_MOUNTS).readText())
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read $PROC_MOUNTS — destinations will be treated as unconstrained", e)
            emptyList()
        }
    }

    /**
     * Determines what [dest] can physically accept, before any byte is written (FR-01).
     *
     * Never throws. Any failure at any step — an unreadable mount table, a path that matches no
     * mount, a `StatFs` that is denied — yields [DestinationCapabilities.unknown], which permits
     * the transfer. **A wrongly blocked transfer is worse than a late failure** (FR-03, NFR-02).
     *
     * [mounts] comes from [readMountTable], captured once per operation by the caller. It is a
     * required parameter rather than a defaulted one so that a batch loop cannot accidentally
     * re-read the table per file.
     */
    suspend fun probe(
        context: Context?,
        dest: File,
        mounts: List<MountEntry>
    ): DestinationCapabilities {
        return try {
            probeOrThrow(context, dest, mounts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pre-flight probe failed for ${dest.absolutePath}; permitting the transfer", e)
            DestinationCapabilities.unknown()
        }
    }

    private suspend fun probeOrThrow(
        context: Context?,
        dest: File,
        mounts: List<MountEntry>
    ): DestinationCapabilities {
        val path = dest.absolutePath
        if (path.isBlank()) return DestinationCapabilities.unknown()

        // No mount matched: report undetermined rather than guessing a filesystem.
        val mount = resolveMount(mounts, path) ?: return DestinationCapabilities.unknown()
        val fsType = mount.fsType

        return DestinationCapabilities(
            mountPath = mount.mountPoint,
            fsType = fsType,
            // The subtype only ever refines the message; the ceiling is maxFileSizeFor(fsType).
            fatSubtype = if (fsType == "vfat") fatSubtypeForClusters(clusterCountAt(path)) else null,
            maxFileSize = maxFileSizeFor(fsType),
            freeBytes = freeSpaceAt(path, context),
            determined = true
        )
    }

    /**
     * Cluster count for FAT classification, or 0 when it cannot be read.
     *
     * `StatFs` runs against [path] — the destination as the app sees it — and **not** against the
     * resolved mount point. The backing `/mnt/pass_through/0/<volid>` is `Permission denied` to
     * the app process, while the FUSE path it writes through answers with the real `fat_statfs()`
     * numbers. Verified on the target card: 976624 clusters, 32768 bytes each, 32.0 GB.
     */
    private fun clusterCountAt(path: String): Long = try {
        // blockCountLong, not the deprecated Int-returning blockCount: a large volume's cluster
        // count is exactly the kind of number that would silently wrap.
        StatFs(path).blockCountLong
    } catch (_: Throwable) {
        0L
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Parses `/proc/mounts` content into a list of [MountEntry].
     *
     * Pure — takes the table text, returns entries, no I/O and no Android dependency — so it
     * can be unit-tested against a table captured verbatim from a real device.
     *
     * Blank lines and `#` comments are skipped, and lines carrying fewer than the four required
     * fields are ignored rather than throwing: an unparseable table must degrade to
     * "destination undetermined" and fail open, never abort a transfer.
     *
     * Mount points containing spaces arrive octal-escaped by the kernel's `seq_file` escaping
     * (`\040` for space) and are decoded here.
     */
    fun parseMountTable(text: String): List<MountEntry> {
        val entries = ArrayList<MountEntry>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = line.split(WHITESPACE)
            if (fields.size < 4) continue
            entries.add(
                MountEntry(
                    device = unescapeOctal(fields[0]),
                    mountPoint = unescapeOctal(fields[1]),
                    fsType = fields[2],
                    options = fields[3]
                )
            )
        }
        return entries
    }

    /**
     * Decodes the kernel's octal escaping for path fields (`\040` space, `\011` tab,
     * `\012` newline, `\134` backslash).
     *
     * Returns the input unchanged when it holds no backslash, which is the overwhelmingly
     * common case, so the scan costs nothing on ordinary paths.
     */
    /** Filesystem type reported by the FUSE mounts the app's own destination paths live behind. */
    const val FUSE_FS_TYPE = "fuse"

    /** The volume id Android uses for internal storage. */
    private const val EMULATED_VOLUME = "emulated"

    private const val STORAGE_ROOT = "/storage/"

    /**
     * Mount-point prefixes that lead with a volume id.
     *
     * `/storage/<volid>` is the FUSE view users and the app navigate; the `/mnt/<scope>/0/<volid>`
     * forms are the same volumes mounted for other user ids.
     */
    private val VOLUME_ROOTS = listOf(
        STORAGE_ROOT,
        "/mnt/user/0/",
        "/mnt/installer/0/",
        "/mnt/androidwritable/0/"
    )

    /**
     * Resolves [path] to the mount entry for the filesystem that actually backs it.
     *
     * The destination paths the app writes through are **FUSE** mounts — `/storage/<volid>` — and
     * a FUSE mount reports its *own* type, not the type of the volume beneath it. So a direct
     * match on `fuse` is not an answer: it is a redirect. The volume is located by its id and
     * re-resolved against the same table, where it appears a second time as a direct mount.
     *
     * Verified against the device's table. `7DE2-1219` (a FAT32 card) appears as:
     * ```
     * /dev/fuse                     /storage/7DE2-1219                    fuse
     * /dev/block/vold/public:179,1  /mnt/pass_through/0/7DE2-1219         vfat
     * ```
     * and internal storage as:
     * ```
     * /dev/fuse                     /storage/emulated                     fuse
     * /dev/block/dm-67              /mnt/pass_through/0/emulated          f2fs
     * ```
     * `/mnt/pass_through/0/<volid>` is the general route — it exists for internal *and*
     * removable volumes. `/mnt/media_rw/<volid>` only exists for removable ones, and is kept as
     * a second candidate for devices that do not publish the pass-through view. For emulated
     * storage `/data` is the last resort, since that is always the filesystem behind it.
     *
     * Returns null when nothing matches — the caller must treat that as "undetermined" and permit
     * the transfer (FR-03). When a FUSE mount has no resolvable backing, the FUSE entry itself is
     * returned, which likewise imposes no ceiling.
     *
     * Pure: takes the already-parsed table, performs no I/O.
     */
    fun resolveMount(entries: List<MountEntry>, path: String): MountEntry? {
        val direct = longestPrefixMatch(entries, canonicalizeVolumeAlias(path)) ?: return null
        if (direct.fsType != FUSE_FS_TYPE) return direct

        val volid = volumeIdOf(direct.mountPoint) ?: return direct

        return entries.firstOrNull {
            it.fsType != FUSE_FS_TYPE && it.mountPoint == "/mnt/pass_through/0/$volid"
        } ?: entries.firstOrNull {
            it.fsType != FUSE_FS_TYPE && it.mountPoint == "/mnt/media_rw/$volid"
        } ?: if (volid == EMULATED_VOLUME) {
            entries.firstOrNull { it.fsType != FUSE_FS_TYPE && it.mountPoint == "/data" } ?: direct
        } else {
            direct
        }
    }

    /**
     * Longest mount point that contains [path], compared on path-segment boundaries so
     * `/storage/7DE2-121` never matches `/storage/7DE2-1219`.
     */
    private fun longestPrefixMatch(entries: List<MountEntry>, path: String): MountEntry? {
        var best: MountEntry? = null
        for (entry in entries) {
            val mountPoint = entry.mountPoint
            if (mountPoint.isEmpty()) continue
            val matches = path == mountPoint ||
                path.startsWith(if (mountPoint.endsWith("/")) mountPoint else "$mountPoint/")
            if (!matches) continue
            if (best == null || mountPoint.length > best.mountPoint.length) best = entry
        }
        return best
    }

    /**
     * Rewrites `/storage/self/…` and `/storage/0/…` to `/storage/emulated/…`.
     *
     * Both are aliases Android has used for internal storage, and neither is a mount point in its
     * own right, so without this they would match nothing and read as "undetermined".
     */
    private fun canonicalizeVolumeAlias(path: String): String {
        val volid = firstSegmentAfter(path, STORAGE_ROOT) ?: return path
        if (volid != "self" && volid != "0") return path
        return STORAGE_ROOT + EMULATED_VOLUME + path.substring(STORAGE_ROOT.length + volid.length)
    }

    /** The volume id in a mount point such as `/storage/7DE2-1219`, or null if there is none. */
    private fun volumeIdOf(mountPoint: String): String? {
        for (root in VOLUME_ROOTS) {
            val volid = firstSegmentAfter(mountPoint, root) ?: continue
            return if (volid == "self" || volid == "0") EMULATED_VOLUME else volid
        }
        return null
    }

    /** The path segment following [root], or null when [value] is not under it or has none. */
    private fun firstSegmentAfter(value: String, root: String): String? {
        if (!value.startsWith(root)) return null
        val rest = value.substring(root.length)
        if (rest.isEmpty()) return null
        val end = rest.indexOf('/')
        return if (end < 0) rest else rest.substring(0, end).ifEmpty { null }
    }

    private fun unescapeOctal(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 3 < value.length) {
                val code = value.substring(i + 1, i + 4).toIntOrNull(8)
                if (code != null && code in 0..255) {
                    sb.append(code.toChar())
                    i += 4
                    continue
                }
            }
            sb.append(value[i])
            i++
        }
        return sb.toString()
    }
}
