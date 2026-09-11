package za.kilowatch.ultimatefilemanager.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * THE shared "transfer holder" (FR-12): the single component every transfer path
 * (paste-to-network, paste-to-local, streaming) delegates to.
 *
 * It owns:
 *  - the transfer coroutine, launched on [UfmApplication.applicationScope] so it
 *    survives the Activity that started it (FR-09),
 *  - the [TransferService] foreground-service / wake-Wi-Fi-lock lifecycle (started when
 *    the first transfer begins, stopped when the last one ends — never by an Activity),
 *  - transient-failure retry pacing (per file, ≤5 attempts, ~5 s apart, wait for
 *    connectivity, ~5-minute budget → [TransferRetryPolicy.TransferAbortException]),
 *  - cancellation (from the in-app dialog or the notification Cancel action) incl.
 *    force-closing the active connection/streams so blocking sockets unblock,
 *  - progress fan-out to the currently-attached [TransferUi] (a live Activity dialog)
 *    AND to the foreground notification.
 *
 * Only one paste/interactive transfer runs at a time in this app (the paste dialog is
 * non-cancellable and there is a single transfer entry point), so the holder keeps a
 * ref-counted set but a single "interactive" UI attachment that a recreated Activity can
 * re-attach to via [observeActive].
 */
object TransferManager {

    private const val TAG = "TransferManager"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idGen = AtomicLong(0L)

    private data class Active(val handle: Long, val job: Job, val impl: SessionImpl)

    private val activeSet = CopyOnWriteArraySet<Active>()

    // Single "interactive" (paste) UI. Engines never talk to the Activity; the holder
    // forwards progress/retry/finished callbacks to whichever UI is currently attached.
    @Volatile private var interactiveUi: TransferUi? = null
    @Volatile private var interactiveOpLabel = ""
    @Volatile private var interactiveIsExtract = false

    @Volatile private var serviceUp = false

    // External-player streaming keep-alive: the foreground service/wake locks stay up for
    // as long as a stream is active OR a transfer is running, and stop only when neither is.
    @Volatile private var streamActive = false

    private val appContext get() = UfmApplication.instance.applicationContext

    // ── Public entry: the ONLY way a transfer is started ───────────────────────

    /**
     * Runs [block] (the engine's byte-moving work) under this holder: application-scope
     * coroutine + foreground service + retry + cancellation + progress.
     *
     * @param ui        Activity-backed UI observer, or null for background-only work.
     * @param opLabel   Human label for the operation (e.g. current folder label).
     * @param isExtract Whether the batch is an archive-extraction operation.
     */
    fun submit(
        ui: TransferUi?,
        opLabel: String,
        isExtract: Boolean,
        block: suspend (session: TransferSession) -> Unit
    ): TransferHandle {
        val handle = TransferHandle(idGen.incrementAndGet())
        val impl = SessionImpl(handle.id, opLabel, isExtract)
        val actRef = arrayOfNulls<Active>(1)

        val job: Job = UfmApplication.applicationScope.launch {
            var summary: TransferSummary? = null
            try {
                block(impl)
            } catch (e: TransferRetryPolicy.TransferAbortException) {
                // Destination unreachable after retries → whole transfer stopped (Q5).
                Log.w(TAG, "Transfer aborted: ${e.message}")
                summary = summaryFrom(impl, message = e.message ?: appContext.getString(R.string.transfer_error_unreachable), aborted = true)
            } catch (e: CancellationException) {
                if (impl.cancelled) {
                    summary = summaryFrom(impl, cancelled = true)
                } else {
                    // Unrelated cancellation (e.g. application-scope teardown): still release
                    // the active entry + service before propagating.
                    finish(actRef[0], summaryFrom(impl), impl)
                    throw e
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Transfer failed unexpectedly", e)
                summary = summaryFrom(impl, message = e.message)
            }
            finish(actRef[0], summary ?: summaryFrom(impl), impl)
        }

        val act = Active(handle.id, job, impl)
        actRef[0] = act
        activeSet.add(act)

        if (ui != null) {
            interactiveUi = ui
            interactiveOpLabel = opLabel
            interactiveIsExtract = isExtract
            postOnMain { runCatching { ui.onStarted(opLabel, isExtract) } }
        }
        ensureServiceStarted()
        return handle
    }

    // ── Lifecycle helpers ──────────────────────────────────────────────────────

    /** Attach (or detach with null) a UI observer to the running transfer — used when the
     * launching Activity is recreated so a fresh screen can keep showing progress (FR-09). */
    fun observeActive(ui: TransferUi?) {
        interactiveUi = ui
        if (ui != null && activeSet.isNotEmpty()) {
            val label = interactiveOpLabel
            val isExtract = interactiveIsExtract
            postOnMain { runCatching { ui.onStarted(label, isExtract) } }
        }
    }

    fun isActiveTransfers(): Boolean = activeSet.isNotEmpty() || streamActive

    fun cancel(handle: TransferHandle) {
        activeSet.forEach { if (it.handle == handle.id) cancelActive(it) }
    }

    fun cancelAll() {
        activeSet.forEach { cancelActive(it) }
    }

    private fun cancelActive(active: Active) {
        active.impl.requestCancel()
        active.job.cancel()
    }

    /**
     * External keep-alive (streaming a network file to an external player): starts the
     * foreground service/wake locks with the given notification content. Call [endStream]
     * when the player session finishes (typically the host Activity's onResume after the
     * external player returns). Idempotent — only the first call opens the service.
     */
    fun startStream(title: String? = null, text: String? = null) {
        if (streamActive) return
        streamActive = true
        if (!serviceUp) {
            serviceUp = true
            TransferService.start(appContext, title, text)
        }
    }

    /** Release the stream keep-alive. Safe to call even when no stream is active. */
    fun endStream() {
        if (!streamActive) return
        streamActive = false
        if (activeSet.isEmpty() && serviceUp) {
            serviceUp = false
            TransferService.stop(appContext)
        }
    }

    private fun ensureServiceStarted() {
        if (!serviceUp && activeSet.isNotEmpty()) {
            serviceUp = true
            TransferService.start(appContext)
        }
    }

    private fun stopServiceIfIdle() {
        // Don't tear the service down while a stream is still holding it open.
        if (activeSet.isEmpty() && !streamActive && serviceUp) {
            serviceUp = false
            TransferService.stop(appContext)
        }
    }

    internal fun summaryFrom(
        impl: SessionImpl,
        cancelled: Boolean = false,
        aborted: Boolean = false,
        message: String? = null
    ): TransferSummary = TransferSummary(
        successCount = impl.successCount,
        failCount = if (aborted) impl.failCount + 1 else impl.failCount,
        skippedCount = impl.skippedCount,
        // A real failure outranks an exclusion: the error is the more urgent thing to report.
        message = message ?: impl.lastError ?: impl.skipReason,
        cancelled = cancelled,
        aborted = aborted,
        opLabel = impl.opLabel,
        isExtract = impl.isExtract
    )

    private fun finish(active: Active?, summary: TransferSummary, impl: SessionImpl) {
        if (active != null) {
            activeSet.remove(active)
            stopServiceIfIdle()
        }
        // FR-24: one line per completed transfer, deliberately *after* the per-item lines rather
        // than instead of them. logcat's buffer is finite, so a batch that failed item-by-item can
        // roll its own earlier detail out of the window — this line is the outcome of record
        // whichever of the per-item lines survived, and it is the one line that answers "what
        // happened to that transfer?" without reading the whole run.
        android.util.Log.i(
            FileTransferGuard.TAG,
            "Transfer finished (${summary.opLabel}): ${summary.successCount} ok, " +
                "${summary.failCount} failed, ${summary.skippedCount} skipped" +
                (if (summary.cancelled) ", cancelled" else "") +
                (if (summary.aborted) ", aborted" else "") +
                (summary.message?.let { " — $it" } ?: "")
        )
        // Release the singleton UI reference now the transfer is done — prevents retaining a
        // destroyed Activity indefinitely. A recreated Activity re-attaches via observeActive.
        val ui = interactiveUi
        interactiveUi = null
        if (ui != null) {
            postOnMain { runCatching { ui.onFinished(summary) } }
        }
    }

    private fun postOnMain(action: () -> Unit) {
        mainHandler.post { action() }
    }

    /** True when the observer still has a live Activity to draw into (or is UI-less). */
    private fun uiIsAlive(ui: TransferUi): Boolean =
        ui.activity?.let { !it.isFinishing && !it.isDestroyed } ?: true

    // ── Public types ───────────────────────────────────────────────────────────

    /** Opaque handle returned by [submit], used for [cancel]. */
    class TransferHandle internal constructor(val id: Long) {
        override fun equals(other: Any?) = other is TransferHandle && other.id == id
        override fun hashCode() = id.hashCode()
    }

    /** Result handed to [TransferUi.onFinished]. Engines accumulate the counts. */
    data class TransferSummary(
        val successCount: Int = 0,
        val failCount: Int = 0,
        /** Files the pre-flight check excluded (FR-15). Neither transferred nor failed. */
        val skippedCount: Int = 0,
        val message: String? = null,
        val cancelled: Boolean = false,
        val aborted: Boolean = false,
        val opLabel: String = "",
        val isExtract: Boolean = false
    )

    /** Activity-backed observer. All callbacks arrive on the main thread. */
    interface TransferUi {
        val activity: android.app.Activity?
        fun onStarted(opLabel: String, isExtract: Boolean)
        fun onProgress(fileName: String, bytesCopied: Long, totalBytes: Long, fileIndex: Int, totalFiles: Int)
        fun onRetry(fileName: String, attempt: Int, maxAttempts: Int)
        fun onFinished(summary: TransferSummary)
    }

    /** Surface engines call. Engines never touch the Activity, [TransferService] or scope. */
    interface TransferSession {
        val cancelled: Boolean
        fun checkCancelled()

        /** Records a completed item. Excluded items are recorded with [noteSkipped], not here. */
        fun noteSuccess()

        /** Records a failed item (keeps the first non-blank error for the summary). */
        fun noteFailure(message: String?)

        /**
         * Records an item the pre-flight check excluded, so it is reported rather than silently
         * dropped (FR-15). Keeps the first non-blank reason for the summary.
         *
         * An exclusion is neither a success nor a failure: the file was never attempted.
         */
        fun noteSkipped(reason: String?)

        /** Runs [block]; retries transient connection/session failures per policy. */
        suspend fun <T> withFileRetry(fileName: String, block: suspend () -> T): T

        /** Asks the user how to resolve a destination name conflict, or defaults to
         * KEEP_BOTH (auto-unique-rename) when no UI is available (decision #2). */
        suspend fun resolveConflict(
            name: String,
            isFolder: Boolean,
            destSizeBytes: Long,
            applyToAllRef: BooleanArray
        ): TransferConflictHelper.ConflictAction

        /**
         * Shows what the pre-flight check excluded and returns whether to go ahead (FR-12, FR-13).
         *
         * Routed through the session for the same reason [resolveConflict] is: the engines hold
         * only an application context, and a dialog needs a live Activity. The session is the one
         * place that knows whether there is a UI to ask at all.
         *
         * Returns **true** when there is no UI to ask — the exclusions have already been applied
         * to the file list by the time this is called, so the only question left is whether to
         * cancel, and a cancelled-looking default would abandon a transfer the user never refused.
         */
        suspend fun showPreflightExclusions(
            exclusions: List<Exclusion>,
            remainingFiles: Int,
            spaceWarning: PreflightVerdict.Warn?,
            scanRoot: java.io.File?
        ): Boolean

        fun reportProgress(fileName: String, bytesCopied: Long, totalBytes: Long, fileIndex: Int, totalFiles: Int)

        /** Register the raw connection/streams currently in use so [cancelAll] can force them closed. */
        fun registerConnection(connection: AutoCloseable?)
        fun unregisterConnection(connection: AutoCloseable?) {}
        fun registerStreams(input: java.io.InputStream?, output: java.io.OutputStream?)
        fun unregisterStreams(input: java.io.InputStream?, output: java.io.OutputStream?) {}
    }

    // ── Internal session implementation ────────────────────────────────────────

    // `internal` rather than private so the unit tests can drive the counters directly; the
    // engines only ever see this through the TransferSession interface.
    internal class SessionImpl(val id: Long, val opLabel: String, val isExtract: Boolean) : TransferSession {
        @Volatile override var cancelled: Boolean = false
        private val _successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val successCount: Int get() = _successCount.get()

        private val _failCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount: Int get() = _failCount.get()

        private val _skippedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val skippedCount: Int get() = _skippedCount.get()

        @Volatile var lastError: String? = null
            private set

        /**
         * First non-blank reason an item was excluded, if any. Used for the summary message when
         * nothing actually failed but files were left behind (FR-15).
         */
        @Volatile var skipReason: String? = null
            private set

        private val lock = Any()
        private val activeConnections = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<AutoCloseable, Boolean>())
        private val activeInputs = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<java.io.InputStream, Boolean>())
        private val activeOutputs = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<java.io.OutputStream, Boolean>())

        // notification throttle
        @Volatile private var lastNotifMs = 0L
        @Volatile private var lastNotifPercent = -1

        override fun checkCancelled() {
            if (cancelled) throw CancellationException("Transfer cancelled by user")
        }

        override fun noteSuccess() {
            _successCount.incrementAndGet()
        }

        override fun noteFailure(message: String?) {
            val n = _failCount.incrementAndGet()
            synchronized(lock) {
                if (lastError == null && !message.isNullOrBlank()) lastError = message
            }
            // FR-24: every failure any route reports passes through here, so this is the one line
            // that makes a failed transfer visible in logcat even when the failing code is one that
            // logs nothing itself — a network upload, a SAF or Shizuku branch, a post-copy step.
            // The caller's own messages are sometimes terse (`e.message` can be a bare errno
            // string), so the running count is included: three failures carrying identical text
            // read very differently from one.
            android.util.Log.e(
                FileTransferGuard.TAG,
                "Transfer item failed (#$n): ${message ?: "no cause reported"}"
            )
        }

        override fun noteSkipped(reason: String?) {
            val n = _skippedCount.incrementAndGet()
            synchronized(lock) {
                if (skipReason == null && !reason.isNullOrBlank()) skipReason = reason
            }
            // Deliberately Log.i, not Log.e — a skip is not a failure, and the whole point of the
            // separate channel is that it must not read like one (FR-15). The per-file detail is
            // logged by whoever refused the file; this line is the running tally.
            android.util.Log.i(
                FileTransferGuard.TAG,
                "Transfer item skipped (#$n): ${reason ?: "no reason reported"}"
            )
        }

        fun requestCancel() {
            cancelled = true
            closeActiveIo()
        }

        fun closeActiveIo() {
            synchronized(lock) {
                activeConnections.forEach { runCatching { it.close() } }
                activeConnections.clear()
                activeInputs.forEach { runCatching { it.close() } }
                activeInputs.clear()
                activeOutputs.forEach { runCatching { it.close() } }
                activeOutputs.clear()
            }
        }

        override fun registerConnection(connection: AutoCloseable?) {
            if (connection != null) {
                synchronized(lock) {
                    activeConnections.add(connection)
                }
            }
        }

        override fun unregisterConnection(connection: AutoCloseable?) {
            if (connection != null) {
                synchronized(lock) {
                    activeConnections.remove(connection)
                }
            }
        }

        override fun registerStreams(input: java.io.InputStream?, output: java.io.OutputStream?) {
            synchronized(lock) {
                if (input != null) activeInputs.add(input)
                else activeInputs.clear()
                if (output != null) activeOutputs.add(output)
                else activeOutputs.clear()
            }
        }

        override fun unregisterStreams(input: java.io.InputStream?, output: java.io.OutputStream?) {
            synchronized(lock) {
                if (input != null) activeInputs.remove(input)
                if (output != null) activeOutputs.remove(output)
            }
        }

        override suspend fun <T> withFileRetry(fileName: String, block: suspend () -> T): T {
            checkCancelled()
            val startedAt = SystemClock.elapsedRealtime()
            var attempt = 1
            while (true) {
                try {
                    return block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (cancelled) throw CancellationException("Transfer cancelled by user")
                    if (TransferRetryPolicy.classify(e) != TransferRetryPolicy.RetryClass.TRANSIENT) throw e

                    attempt++
                    if (attempt > TransferRetryPolicy.MAX_ATTEMPTS) {
                        throw TransferRetryPolicy.TransferAbortException(
                            appContext.getString(R.string.transfer_error_unreachable)
                        )
                    }

                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val remainingBudget = TransferRetryPolicy.BUDGET_MS - elapsed
                    val shortGap = minOf(TransferRetryPolicy.RETRY_DELAY_MS, remainingBudget.coerceAtLeast(0L))
                    if (remainingBudget <= 0L) {
                        throw TransferRetryPolicy.TransferAbortException(
                            appContext.getString(R.string.transfer_error_unreachable)
                        )
                    }

                    // Tell the UI + notification a retry is happening.
                    val waitingText = appContext.getString(
                        R.string.transfer_retrying_notif, fileName, attempt, TransferRetryPolicy.MAX_ATTEMPTS
                    )
                    runCatching {
                        TransferService.update(appContext, appContext.getString(R.string.ufm_file_transfer), waitingText, true, null)
                    }
                    val ui = interactiveUi
                    if (ui != null && uiIsAlive(ui)) {
                        postOnMain { runCatching { ui.onRetry(fileName, attempt, TransferRetryPolicy.MAX_ATTEMPTS) } }
                    }

                    // Drop dead sockets/streams from the failed attempt before retrying.
                    closeActiveIo()
                    delay(shortGap)

                    // Wait for connectivity within the remaining budget before the next attempt.
                    val connected = TransferRetryPolicy.waitForConnectivity(
                        appContext,
                        (remainingBudget - shortGap).coerceAtLeast(1L)
                    )
                    if (!connected) {
                        throw TransferRetryPolicy.TransferAbortException(
                            appContext.getString(R.string.transfer_error_unreachable)
                        )
                    }
                    checkCancelled()
                }
            }
        }

        override suspend fun resolveConflict(
            name: String,
            isFolder: Boolean,
            destSizeBytes: Long,
            applyToAllRef: BooleanArray
        ): TransferConflictHelper.ConflictAction {
            val ui = interactiveUi
            val activity = ui?.activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                return TransferConflictHelper.showConflictDialog(activity, name, isFolder, destSizeBytes, applyToAllRef)
            }
            // No live UI (screen destroyed / app backgrounded) → safe default: keep both
            // by auto-unique-renaming the incoming file (decision #2).
            return TransferConflictHelper.ConflictAction.KEEP_BOTH
        }

        override suspend fun showPreflightExclusions(
            exclusions: List<Exclusion>,
            remainingFiles: Int,
            spaceWarning: PreflightVerdict.Warn?,
            scanRoot: java.io.File?
        ): Boolean {
            val ui = interactiveUi
            val activity = ui?.activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                return TransferPreflight.showExclusions(
                    activity, exclusions, remainingFiles, spaceWarning, scanRoot
                )
            }
            // No live UI (screen destroyed / app backgrounded) → proceed. The files the
            // destination refuses have already been excluded from the list by the caller, so
            // nothing here can write a byte the destination cannot hold — the only outcome left
            // to decide is whether to cancel, and there is nobody to ask. Defaulting to cancel
            // would abandon a transfer the user never refused, which FR-10 forbids outright for
            // the free-space case and which is no better for the exclusion case.
            return true
        }

        override fun reportProgress(
            fileName: String,
            bytesCopied: Long,
            totalBytes: Long,
            fileIndex: Int,
            totalFiles: Int
        ) {
            val ui = interactiveUi
            if (ui != null && uiIsAlive(ui)) {
                postOnMain {
                    runCatching { ui.onProgress(fileName, bytesCopied, totalBytes, fileIndex, totalFiles) }
                }
            }

            // Throttled notification update (~every 400 ms or when % jumps ≥ 5).
            val now = SystemClock.elapsedRealtime()
            val percent = if (totalBytes > 0) ((bytesCopied * 100L) / totalBytes).toInt() else -1
            if (now - lastNotifMs < 400L &&
                (percent < 0 || abs(percent - lastNotifPercent) < 5)
            ) return
            lastNotifMs = now
            lastNotifPercent = percent

            val context = appContext
            val title = context.getString(R.string.ufm_file_transfer)
            val text: String = try {
                if (totalFiles > 1) {
                    context.getString(R.string.file_fileindex_of_totalfiles_filename, fileIndex, totalFiles, fileName)
                } else if (totalBytes > 0) {
                    val copied = android.text.format.Formatter.formatFileSize(context, bytesCopied)
                    val total = android.text.format.Formatter.formatFileSize(context, totalBytes)
                    context.getString(R.string.copiedstr_totalstr, copied, total)
                } else {
                    fileName
                }
            } catch (_: Exception) {
                fileName
            }
            runCatching { TransferService.update(context, title, text, totalBytes <= 0L, if (totalBytes > 0) percent else null) }
        }
    }
}
