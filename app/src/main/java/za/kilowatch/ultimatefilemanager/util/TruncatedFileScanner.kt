package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import za.kilowatch.ultimatefilemanager.storage.RootShellWrapper
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper
import java.io.File

/**
 * A destination file that sits exactly at its filesystem's per-file size ceiling, and so is
 * almost certainly the debris of a transfer that was cut off by that ceiling (FR-21).
 *
 * "Almost certainly" is doing real work in that sentence. A file can legitimately be exactly
 * 4,294,967,295 bytes, so this is a *candidate*, never a verdict — which is why removal is offered
 * and confirmed rather than performed (FR-23). The record keeps [ceiling] and [capabilities] so
 * the confirmation can explain what the number it was matched against actually was.
 */
data class TruncatedFile(
    val file: File,
    val size: Long,
    val ceiling: Long,
    val capabilities: DestinationCapabilities
)

/**
 * Finds — and, only on explicit request, removes — files left truncated by a filesystem's per-file
 * size limit (FR-21, FR-22, FR-23).
 *
 * The failure this exists for leaves an artifact that is *indistinguishable from a complete file*
 * by every test the app applies: the truncated `.mkv` was exactly 4,294,967,295 bytes, a plausible
 * size for a video, and it plays up to the point where it stops. Nothing about it announces that
 * it is damaged. The only thing that gives it away is the number itself, and the only place that
 * number means anything is against the ceiling of the filesystem it is sitting on.
 *
 * ## Removal is never automatic, and never a side effect of scanning
 *
 * [scan] does not delete anything, and cannot — it has no delete call in it. Removal is a separate
 * entry point ([remove]) that takes the list the user confirmed. That is FR-23 held structurally
 * rather than by remembering to ask: there is no code path from a scan to a deletion.
 *
 * Files are deleted outright, never through the recycle bin (FR-20). The recycle bin governs
 * user-initiated deletion of the user's data; this is the debris of a failed operation, and
 * preserving it would waste the very space the original transfer was trying to use.
 */
object TruncatedFileScanner {

    private const val TAG = FileTransferGuard.TAG

    /**
     * Returns the files under [root] whose size is exactly the per-file ceiling of the filesystem
     * they are on, sorted by path.
     *
     * [root] may be a directory (walked recursively) or a single file. Recursion is bounded by the
     * caller's choice of root, which is deliberate: the useful root after a failed transfer is the
     * folder it failed into, not the whole volume, so the walk stays small in the case this is
     * actually reached from.
     *
     * [mounts] is the operation's mount table, already read by whatever prompted this scan; when it
     * is null the table is read once here. Either way it is read **once**, not per file.
     */
    suspend fun scan(
        context: Context,
        root: File,
        mounts: List<MountEntry>? = null
    ): List<TruncatedFile> {
        val table = mounts ?: FilesystemCapabilities.readMountTable()
        return scanWith(
            root = root,
            capabilitiesOf = { dir -> FilesystemCapabilities.probe(context, dir, table) },
            sizeOf = { file ->
                // The destination directory is passed because `localFileSize` dispatches on it to
                // read a SAF or root file, neither of which reports a meaningful `length()`.
                val parent = file.parentFile
                if (parent != null) {
                    TransferConflictHelper.localFileSize(parent, file.name, context)
                } else {
                    file.length()
                }
            }
        )
    }

    /**
     * The walk and the match, with the two environment reads supplied by the caller.
     *
     * Split out so the matching rule and the traversal are testable without a device, a mount
     * table or a filesystem capable of a 4 GiB ceiling — neither of which a host JVM has. The
     * capability cache is keyed by directory, so a folder of a thousand files costs one probe
     * rather than a thousand, while a tree that crosses a mount boundary still gets each
     * directory's own answer.
     */
    internal suspend fun scanWith(
        root: File,
        capabilitiesOf: suspend (File) -> DestinationCapabilities,
        sizeOf: (File) -> Long
    ): List<TruncatedFile> {
        val found = mutableListOf<TruncatedFile>()
        val byDirectory = HashMap<String, DestinationCapabilities>()

        suspend fun walk(dir: File) {
            val children = try {
                dir.listFiles()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Cannot list ${dir.absolutePath} while scanning for truncated files", e)
                null
            } ?: return

            for (child in children) {
                if (child.isDirectory) {
                    walk(child)
                    continue
                }
                val parent = child.parentFile ?: dir
                val capabilities = byDirectory.getOrPut(parent.absolutePath) { capabilitiesOf(parent) }
                val size = sizeOf(child)
                if (isTruncated(size, capabilities)) {
                    found += TruncatedFile(child, size, capabilities.maxFileSize, capabilities)
                }
            }
        }

        if (root.isDirectory) {
            walk(root)
        } else {
            val parent = root.parentFile
            if (parent != null) {
                val capabilities = capabilitiesOf(parent)
                val size = sizeOf(root)
                if (isTruncated(size, capabilities)) {
                    found += TruncatedFile(root, size, capabilities.maxFileSize, capabilities)
                }
            }
        }

        return found.sortedBy { it.file.absolutePath }
    }

    /**
     * True when [size] is exactly the ceiling [capabilities] imposes.
     *
     * Every clause is a false-positive guard, and NFR-02 makes those the priority — a scanner that
     * offers to delete a complete file is far worse than one that misses a truncated file:
     *
     *  - `size > 0` — an empty file is not truncated by a ceiling, and a zero ceiling must not
     *    match it.
     *  - `determined` — an unreadable destination has no ceiling to compare against (FR-03).
     *  - `maxFileSize != UNCONSTRAINED` — f2fs, ext4 and every other filesystem here have no
     *    per-file limit, so nothing on internal storage can ever match. This is the clause that
     *    keeps the feature from reporting every file on the internal volume as damaged.
     *  - `size == maxFileSize` — *exactly* at. One byte under is a file that fits, and it is the
     *    fat32 boundary condition from FR-06 seen from the other side.
     */
    internal fun isTruncated(size: Long, capabilities: DestinationCapabilities): Boolean =
        size > 0L &&
            capabilities.determined &&
            capabilities.maxFileSize != DestinationCapabilities.UNCONSTRAINED &&
            size == capabilities.maxFileSize

    /**
     * Deletes [files], returning how many were actually removed.
     *
     * **Call this only with a list the user has confirmed** (FR-23). It exists as its own entry
     * point so that no code path leads here from a scan.
     *
     * Each file's size is re-read and it is removed only if it is *still* exactly at its ceiling.
     * A file that no longer matches is skipped and reported rather than deleted: between the scan
     * and the confirmation the user may have replaced it, completed it, or edited it, and by then
     * the reason it was ever a candidate is gone. Deleting it anyway would destroy a file that is
     * no longer the artifact they agreed to remove.
     */
    fun remove(context: Context, files: List<TruncatedFile>): Int {
        var removed = 0
        for (truncated in files) {
            val file = truncated.file
            val parent = file.parentFile
            val current = if (parent != null) {
                TransferConflictHelper.localFileSize(parent, file.name, context)
            } else {
                file.length()
            }

            if (current != truncated.ceiling) {
                android.util.Log.w(
                    TAG,
                    "Not removing ${file.absolutePath}: it was $current bytes at " +
                        "confirmation, not the ${truncated.ceiling} bytes it was found at — " +
                        "leaving it alone rather than deleting a file that no longer matches"
                )
                continue
            }

            if (deletePath(context, file)) {
                removed++
                // FR-26: what was removed, and why. A cleanup the user cannot audit afterwards is
                // indistinguishable from data loss.
                android.util.Log.i(
                    TAG,
                    "Removed truncated file ${file.absolutePath} ($current bytes, at the " +
                        "${truncated.ceiling}-byte limit of ${truncated.capabilities.fsType} " +
                        "at ${truncated.capabilities.mountPath})"
                )
            }
        }
        return removed
    }

    /**
     * Deletes one path through whichever mechanism actually owns it.
     *
     * The same four-way dispatch the transfer engines use, because the same path can be a plain
     * file, a SAF document, a root path or a Shizuku path, and only one of those answers to
     * [File.delete]. A failed deletion returns false and is logged by the caller's absence from
     * the removed count — it is never retried here, since a second attempt with the same mechanism
     * fails the same way.
     */
    private fun deletePath(context: Context, file: File): Boolean {
        val path = file.absolutePath
        return try {
            val deleted = when {
                SafTreeManager.isSafPath(path) ||
                    SafTreeManager.hasTreePermissionForPath(context, path) -> SafTreeManager.delete(context, path)
                RootShellWrapper.isRootPath(path) -> RootShellWrapper.delete(path)
                ShizukuShellWrapper.canUseShizukuForPath(path) -> ShizukuShellWrapper.delete(path)
                else -> file.delete()
            }
            if (!deleted) {
                android.util.Log.w(TAG, "Could not remove truncated file $path")
            }
            deleted
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not remove truncated file $path", e)
            false
        }
    }
}
