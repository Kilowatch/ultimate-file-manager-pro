package za.kilowatch.ultimatefilemanager.storage

import android.os.ParcelFileDescriptor
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File

/**
 * What the release run left behind on a volume, and what it managed to close.
 *
 * Deliberately carries **no user-facing text**. Every string the FR-09 dialog shows is built by
 * the dialog from these counts and localized through `strings.xml`, so this class stays free of
 * hardcoded English.
 *
 * @param fileDescriptors descriptors **still open** on the volume — `"<fd> -> <path>"`.
 * @param memoryMaps `maps` lines referencing the volume. These cannot be released from Java
 *   (there is no `munmap`), so a non-empty list here is always a genuine surviving claim.
 * @param watcherPaths directories still registered for filesystem watching under the volume.
 *   Carried separately because an inotify watch is **invisible to both scans above** — its
 *   descriptor readlinks as `anon_inode:inotify`, never as a path symlink, and it produces no
 *   `maps` line. This is the one surviving-claim class the `/proc` inspection cannot see, so
 *   without it FR-09 would report clean while a watch kept the mount busy.
 * @param closedDescriptors descriptors the close stage **verified** closed, `"<fd> -> <path>"`.
 *   Recorded for diagnostics and deliberately **excluded** from [hasSurvivingClaims] — a close
 *   that worked must not itself trip the FR-09 warning.
 * @param failedStages names of release stages that threw, for the warning dialog and logcat.
 */
data class ClaimReport(
    val fileDescriptors: List<String>,
    val memoryMaps: List<String>,
    val watcherPaths: List<String>,
    val closedDescriptors: List<String>,
    val failedStages: List<String>
) {
    val fdCount: Int get() = fileDescriptors.size
    val mapCount: Int get() = memoryMaps.size
    val watcherCount: Int get() = watcherPaths.size
    val closedCount: Int get() = closedDescriptors.size
    val failedCount: Int get() = failedStages.size

    /** True when something still references the volume and the unmount is therefore unsafe. */
    val hasSurvivingClaims: Boolean
        get() = fileDescriptors.isNotEmpty() || memoryMaps.isNotEmpty() ||
            watcherPaths.isNotEmpty() || failedStages.isNotEmpty()

    /**
     * True when the surviving claims are ones the release phase cannot address at all: a
     * memory mapping has no Java `munmap`, whereas a lingering watcher is releasable in
     * principle and a failed stage may be transient.
     */
    val hasUnreleasableClaims: Boolean
        get() = memoryMaps.isNotEmpty()

    companion object {
        fun empty(): ClaimReport =
            ClaimReport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * Reads this process's own reference tables to find what still points into a volume, and
 * closes the descriptors among them.
 *
 * Both `/proc/self/fd` and `/proc/self/maps` are self-referential and readable without root,
 * which is what keeps the whole release phase available to non-elevated users (NFR-04). The
 * process is inspecting itself, so no permission is involved and no other app's state is
 * touched.
 *
 * This is the measurement that replaces the planning assumption that releasing *known* holders
 * would be sufficient. A live diagnostic showed a directory descriptor surviving on every
 * single run, opened by something the codebase cannot name (see plan R8) — so the release also
 * has to close by target rather than by owner, which is [closeClaimsUnder].
 */
object VolumeClaimInspector {

    private const val TAG = "VolumeClaimInspector"

    /**
     * Index of the pathname column in `/proc/self/maps`
     * (`address perms offset dev inode pathname`).
     */
    private const val MAPS_PATH_FIELD = 5

    /**
     * Snapshot of everything still referencing [volume]. Read-only — closes nothing.
     *
     * Call this **after** the close stage to gate the unmount (FR-09), or on its own to
     * diagnose. [failedStages] and [closedDescriptors] are threaded through so one report
     * carries the whole outcome.
     */
    suspend fun inspect(
        volume: VolumeIdentity,
        watcherPaths: List<String> = emptyList(),
        failedStages: List<String> = emptyList(),
        closedDescriptors: List<String> = emptyList()
    ): ClaimReport = withContext(Dispatchers.IO) {
        val root = volume.normalizedMountPath
        if (root.isEmpty()) return@withContext ClaimReport.empty()

        val fds = try {
            openDescriptorsUnder(root)
        } catch (e: Exception) {
            GoRoLog.w(TAG, "inspect: fd scan failed: ${e.message}")
            emptyList()
        }

        val maps = try {
            mappedFilesUnder(root)
        } catch (e: Exception) {
            GoRoLog.w(TAG, "inspect: maps scan failed: ${e.message}")
            emptyList()
        }

        val report = ClaimReport(
            fileDescriptors = fds,
            memoryMaps = maps,
            watcherPaths = watcherPaths,
            closedDescriptors = closedDescriptors,
            failedStages = failedStages
        )

        if (report.hasSurvivingClaims) {
            GoRoLog.w(
                TAG,
                "Surviving claims on ${volume.uuid}: ${fds.size} fd(s), ${maps.size} map(s), " +
                    "${watcherPaths.size} watcher(s), ${failedStages.size} failed stage(s)"
            )
            fds.forEach { GoRoLog.w(TAG, "  fd  : $it") }
            maps.forEach { GoRoLog.w(TAG, "  map : $it") }
            watcherPaths.forEach { GoRoLog.w(TAG, "  watch: $it") }
        } else {
            GoRoLog.i(TAG, "No surviving claims on ${volume.uuid}")
        }

        report
    }

    /**
     * Closes every descriptor whose target lies under [volume] and returns the ones **verified**
     * closed, formatted `"<fd> -> <path>"`. The source-agnostic half of the fix: it needs no
     * knowledge of who opened the descriptor, only of where it points.
     *
     * Three hazards drive the implementation, and all three matter:
     *
     *  - **Only matching descriptors may be closed.** Closing an unrelated fd — a socket, the
     *    log pipe, a database handle — would corrupt the process, turning a survivable kill into
     *    arbitrary breakage. [isUnderVolume] is the only guard, so it is a segment-boundary check
     *    and nothing looser.
     *  - **The listing is materialised before anything is closed.** Closing a descriptor while
     *    iterating `/proc/self/fd` invalidates the very directory stream being read; the
     *    `/proc/self/fd` directory handle itself must never be closed either. Both are handled
     *    by reading the whole listing into a `List<String>` first, then closing.
     *  - **A closed descriptor number can be reused.** Between the close and any later `open(2)`,
     *    the kernel may hand the same integer to something unrelated, so a stale reference to
     *    the original object would read or write the *wrong* file. This is why the close stage
     *    runs as late as possible — after the caches are dropped and the viewers are gone (T005,
     *    T006, T009) — and why the release proceeds straight to the unmount rather than leaving
     *    the process running against a torn-down volume.
     *
     * **Success is measured, never assumed.** `ParcelFileDescriptor.close()` funnels through
     * `IoUtils.closeQuietly`, which swallows every error, so no exception can report whether the
     * close landed. Instead the volume is scanned again afterwards and an entry counts as closed
     * only if it is gone. A descriptor that someone re-opened in the meantime therefore appears
     * as surviving, which is the truthful answer.
     *
     * Closing is best-effort and never throws: one stubborn descriptor must not prevent the
     * others from being closed (NFR-03).
     */
    suspend fun closeClaimsUnder(volume: VolumeIdentity): List<String> = withContext(Dispatchers.IO) {
        val root = volume.normalizedMountPath
        if (root.isEmpty()) return@withContext emptyList()

        val before = try {
            openDescriptorsUnder(root)
        } catch (e: Exception) {
            GoRoLog.w(TAG, "closeClaimsUnder: fd scan failed: ${e.message}")
            return@withContext emptyList()
        }

        if (before.isEmpty()) return@withContext emptyList()

        for (entry in before) {
            val fd = entry.substringBefore(" -> ").toIntOrNull() ?: continue
            try {
                // wraps the raw number and marks itself closed, so its finalizer cannot issue a
                // second close(2) against a number the kernel has since handed to someone else.
                ParcelFileDescriptor.adoptFd(fd).close()
            } catch (e: Exception) {
                GoRoLog.w(TAG, "closeClaimsUnder: close of fd $fd threw: ${e.message}")
            }
        }

        val after = try {
            openDescriptorsUnder(root).toSet()
        } catch (e: Exception) {
            GoRoLog.w(TAG, "closeClaimsUnder: re-scan failed, reporting nothing closed: ${e.message}")
            return@withContext emptyList()
        }

        val closed = before.filter { it !in after }
        val survived = before.filter { it in after }

        if (closed.isNotEmpty()) {
            GoRoLog.i(TAG, "Closed ${closed.size} descriptor(s) under ${volume.uuid}")
            closed.forEach { GoRoLog.d(TAG, "  closed: $it") }
        }
        survived.forEach { GoRoLog.w(TAG, "  still open after close: $it") }

        closed
    }

    /**
     * Every open descriptor of this process whose target is under [root], as `"<fd> -> <path>"`.
     *
     * `Os.readlink` is used rather than `File.canonicalPath` because it reports the target
     * exactly as the kernel holds it — including for descriptors whose file has been unlinked
     * or whose path no longer resolves — and it never opens anything, so the scan cannot
     * itself create the claim it is looking for.
     */
    private fun openDescriptorsUnder(root: String): List<String> {
        val result = mutableListOf<String>()
        val fdDir = File("/proc/self/fd")
        val names = fdDir.list() ?: return result

        for (name in names) {
            val fd = name.toIntOrNull() ?: continue
            val target = try {
                Os.readlink("/proc/self/fd/$fd")
            } catch (_: Exception) {
                // Racy by nature: descriptors come and go while we iterate.
                continue
            } ?: continue

            // Never treat a /proc handle as a claim on the volume — this also skips the
            // /proc/<pid>/fd directory descriptor that `list()` itself opens, which readlinks
            // to exactly that path.
            if (target.startsWith("/proc/")) continue

            if (isUnderVolume(target, root)) result.add("$fd -> $target")
        }
        return result
    }

    /**
     * Lines from `/proc/self/maps` referencing [root].
     *
     * These are reported but never released: Java exposes no `munmap`, so dropping the objects
     * that created the mapping is the only lever, and that happens earlier in the release
     * sequence. A non-empty result here means the unmount is genuinely unsafe.
     */
    private fun mappedFilesUnder(root: String): List<String> {
        val result = mutableListOf<String>()
        val maps = File("/proc/self/maps")
        if (!maps.canRead()) return result

        maps.forEachLine { line ->
            // limit = pathname index + 1, so Kotlin keeps everything after the inode column in
            // the final element. A plain split would truncate the path at its first space and
            // silently miss every file inside a directory whose name contains one.
            val fields = line.split(' ', limit = MAPS_PATH_FIELD + 1)
            if (fields.size <= MAPS_PATH_FIELD) return@forEachLine
            val path = fields[MAPS_PATH_FIELD].trim()
            if (path.isEmpty()) return@forEachLine
            if (isUnderVolume(path, root)) result.add(line.trim())
        }
        return result
    }

    /**
     * Segment-boundary containment test: true when [path] is [root] itself or lies beneath it.
     *
     * A plain `startsWith(root)` would match `/storage/7DE2-12190` against
     * `/storage/7DE2-1219`, which for the close stage means closing a descriptor belonging to a
     * *different* volume. The boundary is therefore mandatory rather than cosmetic.
     *
     * Comparison is textual on purpose. The targets come from the kernel as absolute paths, and
     * canonicalising them would resolve symlinks and cost a syscall per descriptor — and could
     * fail outright for a descriptor whose file has already been unlinked, which is exactly the
     * case this needs to catch.
     */
    fun isUnderVolume(path: String, root: String): Boolean {
        if (root.isEmpty()) return false
        return path == root || path.startsWith("$root/")
    }
}
