package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.os.SystemClock
import coil3.SingletonImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.indexing.FileIndexingService
import za.kilowatch.ultimatefilemanager.indexing.recents.RecentsChangeWatcher
import za.kilowatch.ultimatefilemanager.sync.advanced.InstantSyncWatcher
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Runs the staged release that must complete before a volume is unmounted.
 *
 * The stages exist because `vold` scans `/proc/<pid>/fd` and `/proc/<pid>/maps` of every
 * process — including the one that asked for the unmount — and `SIGINT`s anything still
 * holding a reference. UFM requests the unmount through Shizuku, so without this it kills
 * itself: no exception, no stack trace, just `signal 2 (Interrupt)` in the Zygote log.
 *
 * | # | Stage | Releases | Bound |
 * |---|-------|----------|-------|
 * | 1 | `close-viewers` | Every viewer's fd, player and Coil cache | [CLOSE_VIEWERS_TIMEOUT_MS] |
 * | 2 | `quiesce` | Indexing jobs, adapter jobs and both filesystem watchers | 2 × [INDEX_IDLE_TIMEOUT_MS] |
 * | 3 | `drop-caches` | Bitmaps, thumbnail and audio-art caches | — |
 * | 4 | `verify-watchers`, inspect | *(read-only)* proves 1-3 worked, and reports what survives | — |
 * | 5 | `flush-buffers` | Dirty page cache | — |
 *
 * Worst case before the unmount is issued is therefore ~9 s: 3 s in stage 1, 6 s in stage 2,
 * plus [NAVIGATE_TIMEOUT_MS] for the host to leave the volume. Only stages 1 and 2 can block;
 * 3-5 are unbounded in principle but do bounded work over this process's own tables.
 *
 * **Leaving the volume is not one of these stages.** It is [navigateOut], called by the eject
 * flow *after* the FR-09 gate and immediately before the unmount command. The plan originally
 * placed it first, and that cannot be made to work: for the standalone browser "leaving" means
 * finishing the Activity, which would destroy the Activity that has to show the FR-09 dialog
 * and the non-root Storage Settings fallback. FR-05 only requires that the app is not browsing
 * inside the volume *at the moment the unmount is issued*, so running it last satisfies the
 * requirement exactly while keeping those dialogs possible. The cost is that stages 1 and 3
 * find slightly more to clean up than they would have; they are designed to do that cleanup
 * anyway, and anything they miss is reported by stage 4 rather than closed.
 *
 * **Ordering within the release is load-bearing, not stylistic:**
 *  - Stages 1 and 3 must precede stage 4: the caches hold bitmaps that map files on the
 *    volume, and recycling a bitmap that is still displayed throws. Closing viewers first is
 *    what makes the cache sweep safe — and it is also what makes stage 4's measurement
 *    meaningful, since a cache dropped after the inspection would leave the report claiming a
 *    claim survived when it had simply not been released yet.
 *  - Stage 5 runs last so every write the earlier stages touched reaches the physical media
 *    before the unmount is issued. `flushBuffers()` forks a `sync` process and a fork
 *    duplicates the parent's descriptor table; running the flush last keeps what that child
 *    inherits at its minimum, because stages 1-3 have already released their descriptors and
 *    nothing after stage 5 opens a file.
 *
 * Stages 1 and 3 are the *targeted* release: each knows which component owns which claim, so it
 * can release that claim properly and cheaply. A live diagnostic showed why enumerating known
 * holders is incomplete by construction — a directory descriptor survived every single run,
 * opened by something the codebase cannot name. There is deliberately **no** stage that closes
 * it. Closing a descriptor this process never opened trips fdsan's ownership check and aborts
 * the process outright, so a claim surviving stages 1-3 is **reported, never closed** (FR-14),
 * and the user decides whether to unmount regardless.
 *
 * Nothing here throws: every stage is isolated so one failure cannot abort the others, and the
 * failures are reported in [ClaimReport.failedStages] so the user is warned rather than
 * unmounted out from under (NFR-03).
 */
object VolumeClaimReleaser {

    private const val TAG = "VolumeClaimReleaser"

    /** How long a container gets to navigate out before the stage is treated as failed. */
    private const val NAVIGATE_TIMEOUT_MS = 3_000L

    /** Outer bound on stage 1 (`close-viewers`). Inner call gets this minus [CALLBACK_MARGIN_MS]. */
    private const val CLOSE_VIEWERS_TIMEOUT_MS = 3_000L

    /**
     * Slack between a callback stage's own deadline and ours, so a host that honours its own
     * timeout still calls back before we give up on it and record a failure.
     */
    private const val CALLBACK_MARGIN_MS = 500L

    /**
     * Per-storage-id bound on waiting for indexing to stop. Two ids are awaited in sequence
     * (the bare uuid and the `sdcard_`-prefixed form), and normally one of them has no jobs at
     * all, so the second returns immediately.
     */
    private const val INDEX_IDLE_TIMEOUT_MS = 3_000L

    /**
     * Releases every claim this process holds on [volume] and reports what is left.
     *
     * This does *not* move the UI off the volume — see [navigateOut], which the caller runs
     * after the FR-09 gate and immediately before issuing the unmount.
     *
     * The returned report is the FR-09 gate: `hasSurvivingClaims` tells the caller to warn
     * instead of unmounting. It is a measurement of this process's own state, taken after the
     * release ran, not a prediction of whether the unmount will succeed.
     */
    suspend fun releaseAll(
        context: Context,
        volume: VolumeIdentity
    ): ClaimReport {
        val failed = mutableListOf<String>()
        val root = volume.normalizedMountPath
        val appContext = context.applicationContext

        if (root.isEmpty()) {
            GoRoLog.w(TAG, "releaseAll: volume ${volume.uuid} has a blank mount path")
        }

        // 1 — every viewer, unconditionally, so their fds and players are gone.
        stage(failed, "close-viewers", Unit) {
            // Throwing rather than returning quietly: a stage that silently reports "ok" when
            // it did nothing is worse than one that reports failure, because FR-09 would then
            // treat a volume full of live viewer descriptors as safe to unmount.
            val app = appContext as? UfmApplication
                ?: throw IllegalStateException("applicationContext is not a UfmApplication")
            val completed = awaitCallback(CLOSE_VIEWERS_TIMEOUT_MS) { done ->
                app.closeAllViewers(done, CLOSE_VIEWERS_TIMEOUT_MS - CALLBACK_MARGIN_MS)
            }
            if (!completed) {
                throw IllegalStateException("closeAllViewers did not finish in time")
            }
        }

        // 2 — cancel background work and wait for it to actually stop (FR-04). Cancelling
        //     without awaiting would leave the job free to open a descriptor after stage 4 has
        //     already reported the volume clear — a claim the user was told did not exist.
        stage(failed, "quiesce", Unit) {
            // Adapters the releaser does not own — Tab and Twin hosts keep theirs in a
            // fragment it cannot reach. Cancelled here rather than in stage 3 because these
            // are *jobs*, not caches: stage 3's cache drop cannot stop a job that has not
            // opened its file yet, and a job that opens one after stage 4 leaves a descriptor
            // (or, worse, a mapping) the unmount will find. The standalone browser cancels
            // its own adapter before the release begins; cancelling again here is a no-op.
            //
            // Deliberately *not* moved inside the Dispatchers.IO block below. It walks every
            // live adapter's backing file list, which is an unsynchronised mutableListOf owned
            // by the main thread — reading it from an IO thread would be a data race, and the
            // walk is bounded by what is currently on screen. Correctness over the micro-win.
            FileAdapter.cancelPendingJobsUnder(root)
            withContext(Dispatchers.IO) {
                val indexer = FileIndexingService.getInstance(appContext)
                indexer.cancelIndexing(volume.uuid)
                indexer.cancelIndexing("sdcard_${volume.uuid}")
                indexer.awaitIdle(volume.uuid, INDEX_IDLE_TIMEOUT_MS)
                indexer.awaitIdle("sdcard_${volume.uuid}", INDEX_IDLE_TIMEOUT_MS)
            }
            RecentsChangeWatcher.stopWatchingVolume(root)
            InstantSyncWatcher.stopWatchingVolume(root)
        }

        // 3 — drop in-memory caches. Safe only because stage 1 already closed the viewers that
        //     were displaying these bitmaps.
        stage(failed, "drop-caches", Unit) {
            withContext(Dispatchers.IO) {
                FileAdapter.clearVolumeCaches(root)
                // Stage 1 also clears this, but it can time out; a retained Coil bitmap is a
                // file mapping, and only the mapping scan can still see one afterwards.
                SingletonImageLoader.get(appContext).memoryCache?.clear()
            }
        }

        // 4 — prove it worked. Watchers are queried here, after stage 2 stopped them, because
        //     an inotify registration is invisible to both scans below.
        val survivingWatchers = stage(failed, "verify-watchers", emptyList<String>()) {
            RecentsChangeWatcher.activeWatchesUnder(root) +
                InstantSyncWatcher.activeWatchesUnder(root)
        }

        val inspected = try {
            VolumeClaimInspector.inspect(
                volume = volume,
                watcherPaths = survivingWatchers,
                failedStages = failed.toList()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GoRoLog.w(TAG, "inspection failed: ${e.message}", e)
            failed.add("inspect")
            // Named, not positional: every field is a List<String>, so a transposition here
            // would compile and be completely invisible. An empty claim
            // list is the honest answer when the inspection itself failed — we do not know
            // what survived, and the "inspect" failure is what warns the user.
            ClaimReport(
                fileDescriptors = emptyList(),
                memoryMaps = emptyList(),
                watcherPaths = survivingWatchers,
                failedStages = failed.toList()
            )
        }

        // 5 — flush dirty page cache to the physical media.
        stage(failed, "flush-buffers", Unit) {
            if (!UsbEjectManager.flushBuffers()) {
                throw IllegalStateException("sync did not report success")
            }
        }

        // Rebuilt after the last stage so a stage-5 failure is reported too.
        val report = inspected.copy(failedStages = failed.toList())
        GoRoLog.i(
            TAG,
            "Release of ${volume.uuid} complete: " +
                "${report.fdCount} fd / ${report.mapCount} map / ${report.watcherCount} watcher " +
                "surviving, ${report.failedCount} stage(s) failed"
        )
        return report
    }

    /**
     * Moves the UI off [volume] and waits for the host to confirm it is done.
     *
     * Called *after* the FR-09 gate and immediately before the unmount command, because for the
     * standalone browser leaving the volume means finishing the Activity — doing it earlier
     * would destroy the Activity that still has to show the FR-09 dialog and the non-root
     * Storage Settings fallback. FR-05 asks only that the app is not browsing inside the volume
     * *at the moment the unmount is issued*, which running it here satisfies exactly.
     *
     * [host] is null for the Main Menu, which has no in-volume UI to leave; that is a no-op
     * rather than a failure. Returns false if the host did not confirm within
     * [NAVIGATE_TIMEOUT_MS], and the caller proceeds anyway: the user has already answered the
     * FR-09 warning, so abandoning the unmount at this point would silently override an explicit
     * "Unmount Anyway" for no data-safety gain.
     */
    suspend fun navigateOut(host: VolumeEjectHost?): Boolean {
        if (host == null) return true
        return try {
            awaitCallback(NAVIGATE_TIMEOUT_MS) { done -> host.navigateOutOfVolume(done) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GoRoLog.w(TAG, "navigateOutOfVolume threw: ${e.message}", e)
            false
        }
    }

    /**
     * Runs one stage in isolation, returning [fallback] if it throws.
     *
     * `CancellationException` is rethrown rather than recorded: it is an `Exception`, so a
     * blanket catch here would swallow a cancelled eject and report the stage as a failure —
     * turning a legitimate cancellation into a spurious FR-09 warning.
     */
    private suspend fun <T> stage(
        failedStages: MutableList<String>,
        name: String,
        fallback: T,
        block: suspend () -> T
    ): T {
        val started = SystemClock.elapsedRealtime()
        return try {
            val result = block()
            GoRoLog.i(TAG, "stage '$name' ok in ${SystemClock.elapsedRealtime() - started}ms")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedStages.add(name)
            GoRoLog.w(
                TAG,
                "stage '$name' failed after ${SystemClock.elapsedRealtime() - started}ms: ${e.message}",
                e
            )
            fallback
        }
    }

    /**
     * Invokes [register] on the main thread with a callback and waits for it, up to [timeoutMs].
     *
     * Returns false on timeout rather than throwing, so the caller can decide whether a missing
     * callback is a failure. The callback is delivered at most once even if the host calls it
     * twice, and a late callback arriving after the timeout is dropped rather than resuming a
     * cancelled continuation.
     */
    private suspend fun awaitCallback(
        timeoutMs: Long,
        register: (() -> Unit) -> Unit
    ): Boolean {
        val finished = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val delivered = AtomicBoolean(false)
                    register {
                        if (delivered.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }
            }
        }
        return finished != null
    }
}
