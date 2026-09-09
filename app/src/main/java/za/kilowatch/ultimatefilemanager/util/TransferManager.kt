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

    fun isActiveTransfers(): Boolean = activeSet.isNotEmpty()

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

    private fun summaryFrom(
        impl: SessionImpl,
        cancelled: Boolean = false,
        aborted: Boolean = false,
        message: String? = null
    ): TransferSummary = TransferSummary(
        successCount = impl.successCount,
        failCount = if (aborted) impl.failCount + 1 else impl.failCount,
        message = message ?: impl.lastError,
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

        /** Records a completed/skipped item. */
        fun noteSuccess()

        /** Records a failed item (keeps the first non-blank error for the summary). */
        fun noteFailure(message: String?)

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

        fun reportProgress(fileName: String, bytesCopied: Long, totalBytes: Long, fileIndex: Int, totalFiles: Int)

        /** Register the raw connection/streams currently in use so [cancelAll] can force them closed. */
        fun registerConnection(connection: AutoCloseable?)
        fun registerStreams(input: java.io.InputStream?, output: java.io.OutputStream?)
    }

    // ── Internal session implementation ────────────────────────────────────────

    private class SessionImpl(val id: Long, val opLabel: String, val isExtract: Boolean) : TransferSession {
        @Volatile override var cancelled: Boolean = false
        @Volatile var successCount: Int = 0
            private set
        @Volatile var failCount: Int = 0
            private set
        @Volatile var lastError: String? = null
            private set

        private val lock = Any()
        private var connection: AutoCloseable? = null
        private var input: java.io.InputStream? = null
        private var output: java.io.OutputStream? = null

        // notification throttle
        @Volatile private var lastNotifMs = 0L
        @Volatile private var lastNotifPercent = -1

        override fun checkCancelled() {
            if (cancelled) throw CancellationException("Transfer cancelled by user")
        }

        override fun noteSuccess() {
            successCount++
        }

        override fun noteFailure(message: String?) {
            failCount++
            if (lastError == null && !message.isNullOrBlank()) lastError = message
        }

        fun requestCancel() {
            cancelled = true
            closeActiveIo()
        }

        fun closeActiveIo() {
            synchronized(lock) {
                connection?.let { runCatching { it.close() } }
                connection = null
                input?.let { runCatching { it.close() } }
                input = null
                output?.let { runCatching { it.close() } }
                output = null
            }
        }

        override fun registerConnection(connection: AutoCloseable?) {
            synchronized(lock) { this.connection = connection }
        }

        override fun registerStreams(input: java.io.InputStream?, output: java.io.OutputStream?) {
            synchronized(lock) {
                this.input = input
                this.output = output
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
