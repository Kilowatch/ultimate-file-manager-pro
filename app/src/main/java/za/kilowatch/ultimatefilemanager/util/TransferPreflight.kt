package za.kilowatch.ultimatefilemanager.util

import android.app.Activity
import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The outcome of checking one file against one destination, before any byte is written.
 *
 * Three states, and the distinction between the last two is the whole point (`.plans/spec.md`):
 * a [Block] is a transfer that can never succeed and is refused outright (FR-04), while a [Warn]
 * is an estimate that may be stale and is the user's to override (FR-09, FR-10). They are
 * separate types so they cannot be confused at a call site.
 */
sealed class PreflightVerdict {

    /** The destination can hold this file. Nothing to tell the user. */
    data object Allow : PreflightVerdict()

    /**
     * Hard block — the destination can never hold a file this size (FR-04).
     *
     * Carries everything the explanatory message needs (FR-05): the file, its size, the limit and
     * the filesystem that imposes it. No text is built here — all user-facing strings are
     * resources (NFR-06).
     */
    data class Block(
        val reason: BlockReason,
        val fileName: String,
        val fileSize: Long,
        val capabilities: DestinationCapabilities
    ) : PreflightVerdict()

    /**
     * Soft warning — free space looks insufficient (FR-09).
     *
     * [shortfallBytes] is how much the copy is expected to fall short by. The user may proceed
     * anyway; this must never become a block (FR-10).
     */
    data class Warn(
        val shortfallBytes: Long,
        val capabilities: DestinationCapabilities
    ) : PreflightVerdict()
}

/**
 * Raised by the per-file gate when the destination provably cannot hold the file (FR-04).
 *
 * Carries the whole [PreflightVerdict.Block] so the caller has the file, its size, the limit and
 * the filesystem without re-deriving any of it (FR-05).
 *
 * **A block is not a failure.** Nothing was attempted and no byte was written, so callers must
 * record it as a skip — `TransferSession.noteSkipped` — and never as a failure. Treating it as a
 * failure would both mis-report the outcome and, for a batch, train the user to ignore a failure
 * count that mixes refused files in with genuinely broken ones (FR-15).
 *
 * Deliberately **not** a [kotlinx.coroutines.CancellationException], unlike
 * [TransferRetryPolicy.TransferAbortException]: one blocked file is skipped and the batch carries
 * on (FR-11). Aborting the entire operation because *everything* was blocked is FR-14, and that
 * remains the caller's decision to make once it has seen the whole list.
 *
 * The message avoids the retry policy's transient-failure vocabulary on purpose, so a block can
 * never be mistaken for a reconnectable error — `classify` defaults unknown exceptions to
 * TERMINAL, and this stays clear of the markers that would override that.
 */
class PreflightBlockedException(val block: PreflightVerdict.Block) : Exception(
    "Blocked before transfer: ${block.fileName} is ${block.fileSize} bytes, which exceeds the " +
        "${block.capabilities.maxFileSize} byte limit of the ${block.capabilities.fsType} " +
        "destination at ${block.capabilities.mountPath}"
)

// NOTE (FR-24): the block is logged by the *callers* that throw this — `copyLocalToLocalAtomic`
// and `downloadNetworkToLocalAtomic` — not from a constructor here.
//
// Logging in the constructor is the tidier-looking option and was tried first, but it makes this
// type reach `android.util.Log`, which is not mocked in JVM unit tests: `TransferPreflightTest`
// constructs this directly to assert what it carries, and every such test then died with
// "Method w in android.util.Log not mocked". A value type that a unit test can build is worth more
// than saving one line at each of two throw sites, and the coupling is the actual defect — the
// logging belongs at the boundary, not in the model.

/**
 * Why a file was blocked. Only one cause exists today, and the block message is selected from it —
 * keeping it explicit means a future limit (a filesystem with a different ceiling, say) adds a
 * constant here rather than an `if` in the UI.
 */
enum class BlockReason {
    /** The file is larger than the destination filesystem's maximum per-file size (FR-01a). */
    EXCEEDS_DESTINATION_FILE_SIZE
}

/**
 * One file excluded from a transfer, with everything the user needs to understand why.
 *
 * [destPath] is the file's destination, and is the identity used for de-duplication — see
 * [ExclusionLog.record].
 */
data class Exclusion(
    val destPath: String,
    val fileName: String,
    val fileSize: Long,
    val reason: BlockReason,
    val capabilities: DestinationCapabilities
) {
    companion object {
        /** Builds an exclusion from the verdict that produced it. */
        fun from(destPath: String, block: PreflightVerdict.Block): Exclusion = Exclusion(
            destPath = destPath,
            fileName = block.fileName,
            fileSize = block.fileSize,
            reason = block.reason,
            capabilities = block.capabilities
        )
    }
}

/**
 * Collects the files a batch had to exclude, so they can be shown once before the transfer starts
 * (FR-12) and reported again once it finishes (FR-15).
 *
 * **Thread-safe by construction.** The engines fan per-file work out onto `Dispatchers.IO` under a
 * `Semaphore` (`storage/LocalPasteEngine.kt:556`, `network/NetworkPasteEngine.kt:156-157`), so
 * several files are assessed concurrently and a plain `MutableList` would silently lose entries —
 * which for this feature means silently dropping a file the user was entitled to be told about.
 *
 * Insertion order is preserved, so the list the user sees matches the order they were assessed in
 * rather than an arbitrary thread interleaving.
 */
class ExclusionLog {

    private val entries = ConcurrentLinkedQueue<Exclusion>()
    private val recordedPaths = ConcurrentHashMap.newKeySet<String>()
    private val counter = AtomicInteger(0)

    /**
     * Records [block] for [destPath].
     *
     * Returns true if this was a new exclusion. A path is recorded **at most once**: a directory
     * walk can reach the same file through more than one route, and double-counting would both
     * show the user a duplicated row and corrupt the progress arithmetic that T016 derives from
     * [count].
     */
    fun record(destPath: String, block: PreflightVerdict.Block): Boolean {
        if (!recordedPaths.add(destPath)) return false
        entries.add(Exclusion.from(destPath, block))
        counter.incrementAndGet()
        // FR-24's per-file exclusion line is logged by the caller, guarded on this method's return
        // value so the de-duplication above is honoured — the same file reached twice is logged
        // once, exactly as it is shown once. Not logged here: see the FR-24 note below
        // `PreflightBlockedException`.
        return true
    }

    /** Number of distinct excluded files. Safe to read from any thread at any time. */
    val count: Int get() = counter.get()

    val isEmpty: Boolean get() = counter.get() == 0

    /** The exclusions in the order they were recorded. */
    fun snapshot(): List<Exclusion> = entries.toList()

    /** Forgets everything. Used when an operation finishes and its log is no longer needed. */
    fun clear() {
        entries.clear()
        recordedPaths.clear()
        counter.set(0)
    }
}

/**
 * Layer 2 of the pre-flight feature: turns [DestinationCapabilities] into a verdict the transfer
 * paths can act on.
 *
 * **This layer never writes, never deletes, and never throws.** A blocked file is reported, not
 * enforced here — enforcement belongs to the caller, because only the caller knows whether the
 * block excludes one file from a batch (FR-11) or must abort the operation entirely (FR-14).
 */
object TransferPreflight {

    /**
     * Decides the fate of a file of [fileSize] bytes destined for [capabilities].
     *
     * Pure — no I/O, no Android dependency — so every branch is unit-testable against a
     * hand-built [DestinationCapabilities] rather than a real device.
     *
     * A size of zero or less means the source size could not be determined, and validation is
     * impossible; the file is allowed through and the existing post-transfer integrity check
     * remains the safety net. An undetermined *destination* likewise allows: `cannotHold` and
     * `insufficientSpaceFor` both require the capability to have actually been resolved, so an
     * unknown destination cannot produce either verdict (FR-03, NFR-02).
     *
     * The size block is evaluated before free space, so an oversized file reports the reason that
     * actually matters rather than a space shortfall it would also have triggered.
     */
    fun evaluate(
        fileName: String,
        fileSize: Long,
        capabilities: DestinationCapabilities
    ): PreflightVerdict {
        if (fileSize <= 0L) return PreflightVerdict.Allow

        if (capabilities.cannotHold(fileSize)) {
            return PreflightVerdict.Block(
                reason = BlockReason.EXCEEDS_DESTINATION_FILE_SIZE,
                fileName = fileName,
                fileSize = fileSize,
                capabilities = capabilities
            )
        }

        if (capabilities.insufficientSpaceFor(fileSize)) {
            return PreflightVerdict.Warn(
                shortfallBytes = fileSize - capabilities.freeBytes,
                capabilities = capabilities
            )
        }

        return PreflightVerdict.Allow
    }

    /**
     * Operation-level free-space check (FR-08).
     *
     * Deliberately separate from [evaluate] rather than a call to it with [totalBytes] as the
     * "file size": FR-08 compares the **total** bytes to be written, and a total must never be run
     * through [DestinationCapabilities.cannotHold] — a per-file ceiling does not apply to a sum, so
     * doing that would turn a legitimate 20-file batch of 3 GiB files into a hard block on a FAT32
     * card that could hold each of them, and would hold some of them.
     *
     * Never returns [PreflightVerdict.Block]. Free space is an estimate that may be stale by the
     * time the copy reaches the destination, so insufficient space is the user's to override
     * (FR-09, FR-10) — the only reason this is a warning and not a refusal.
     *
     * A [totalBytes] of zero or less means the batch could not be measured, and unknown free space
     * means the destination could not be read; neither warns (FR-03, NFR-02).
     */
    fun evaluateSpace(totalBytes: Long, capabilities: DestinationCapabilities): PreflightVerdict {
        if (totalBytes <= 0L) return PreflightVerdict.Allow

        if (capabilities.insufficientSpaceFor(totalBytes)) {
            return PreflightVerdict.Warn(
                shortfallBytes = totalBytes - capabilities.freeBytes,
                capabilities = capabilities
            )
        }

        return PreflightVerdict.Allow
    }

    /**
     * Probes [dest] and evaluates [fileName] against it.
     *
     * [mounts] comes from [FilesystemCapabilities.readMountTable], captured once per operation —
     * a batch must not re-read the table per file (NFR-01).
     */
    suspend fun evaluate(
        context: Context?,
        fileName: String,
        fileSize: Long,
        dest: File,
        mounts: List<MountEntry>
    ): PreflightVerdict = evaluate(
        fileName = fileName,
        fileSize = fileSize,
        capabilities = FilesystemCapabilities.probe(context, dest, mounts)
    )

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // THE EXCLUSION DIALOG (FR-05, FR-09, FR-12, FR-13, FR-14)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private val dialogMutex = Mutex()

    /** Rows that fit the list box without it reading as an empty container. */
    private const val SHORT_LIST_ROWS = 3

    /**
     * Shrinks the list box to its content when the list is short.
     *
     * The height belongs to a different view on each form factor, and that is the whole reason
     * this is a function rather than two lines at each call site. Mobile has no focus wrapper, so
     * the scroll view is the dialog's direct child and carries its own fixed height. TV needs a
     * focusable container for the D-pad (see `dialog_preflight_excluded_tv.xml`), and the height
     * lives on **that container** — the scroll view inside it is already `wrap_content`. Relaxing
     * the scroll view there sets the value the XML already gives it and leaves the box it sits in
     * untouched, so a one-row list on TV would still render the full-height empty frame.
     *
     * @param dialogView the inflated dialog, searched for the TV wrapper.
     * @param scrollView the list's scroll view, which is the height owner on mobile.
     * @param rows how many rows were added.
     */
    private fun relaxListHeight(dialogView: View, scrollView: View, rows: Int) {
        if (rows > SHORT_LIST_ROWS) return

        // Null on mobile, where there is no wrapper and the scroll view owns its own height.
        val owner = dialogView.findViewById<View>(R.id.scrollContainer) ?: scrollView
        owner.layoutParams = owner.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            // The weight goes with the height. A weighted child is given the parent's *leftover*
            // space rather than its content's, so on a `wrap_content` dialog a WRAP_CONTENT height
            // on a still-weighted view would be overridden in the second measure pass and the box
            // would not shrink at all. The TV layouts ship the container weighted to 0, so this is
            // belt-and-braces rather than load-bearing — it keeps the two from drifting apart.
            if (this is LinearLayout.LayoutParams) weight = 0f
        }
    }

    /**
     * Shows what the pre-flight check excluded, and returns whether the transfer should go ahead.
     *
     * One dialog for both cases the feature produces, because they are the same question asked
     * about a different number of files:
     *
     *  - a batch where some files were excluded (FR-12, FR-13) — the list, the reasons, and a
     *    confirm that transfers the remainder;
     *  - a single file the destination cannot hold (FR-04, FR-05) — the same list with one entry
     *    in it, and no confirm at all.
     *
     * A free-space shortfall (FR-09) is presented by the same dialog rather than one of its own,
     * because it can arrive alongside exclusions and the user should be told both things at once.
     * It only ever changes the wording and the button label: FR-10 makes it the user's to
     * override, so it can never be the reason a transfer does not start.
     *
     * @param exclusions the files that were refused, in the order they were assessed.
     * @param remainingFiles how many files are still going to be transferred. Zero means every
     *   file was excluded, which is FR-14 — the confirm action is **absent**, not merely
     *   discouraged, because there is no reduced transfer to agree to.
     * @param spaceWarning the FR-08 outcome when the total exceeded the free space, or null.
     * @param scanRoot destination folder the Q5 cleanup scan searches when the user asks for it.
     *   Null suppresses the offer rather than searching somewhere arbitrary — a scan rooted at a
     *   guess would report files from a destination the user was not looking at.
     * @return true to proceed, false when the user cancelled the whole operation.
     */
    suspend fun showExclusions(
        activity: Activity,
        exclusions: List<Exclusion>,
        remainingFiles: Int,
        spaceWarning: PreflightVerdict.Warn? = null,
        scanRoot: File? = null
    ): Boolean = dialogMutex.withLock {
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<Boolean>()
            val isTv = DeviceUtils.isTvDevice(activity)
            val layoutRes = if (isTv) R.layout.dialog_preflight_excluded_tv else R.layout.dialog_preflight_excluded
            val rowLayoutRes = if (isTv) R.layout.item_preflight_excluded_tv else R.layout.item_preflight_excluded
            val view = LayoutInflater.from(activity).inflate(layoutRes, null, false)

            val txtTitle       = view.findViewById<TextView>(R.id.txtPreflightTitle)
            val txtSubtitle    = view.findViewById<TextView>(R.id.txtPreflightSubtitle)
            val txtWarning     = view.findViewById<TextView>(R.id.txtPreflightWarning)
            val scrollExcluded = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollExcluded)
            val rows           = view.findViewById<ViewGroup>(R.id.layoutExcludedFiles)
            val btnConfirm     = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPreflightConfirm)
            val btnFindTrunc   = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPreflightFindTruncated)
            val btnCancel      = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPreflightCancel)

            // FR-14: with nothing left to transfer there is no reduced transfer to confirm, so
            // the action is removed rather than disabled. A greyed-out button would still be a
            // thing to try, and would imply the transfer might yet happen.
            val nothingLeft = remainingFiles <= 0
            if (nothingLeft) btnConfirm.visibility = View.GONE

            // Title and subtitle are chosen once, from what the dialog actually has to say. They
            // are not patched afterwards by the warning branch below, because a subtitle written
            // and then hidden is a line of text that is wrong for a frame and that no reader of
            // this function can tell is unreachable.
            when {
                // Nothing was excluded — this dialog is only here for the free-space warning.
                exclusions.isEmpty() ->
                    txtTitle.text = activity.getString(R.string.preflight_space_title)

                // The single-file block (FR-04/05). The row already carries the size, the limit
                // and the filesystem, so a count of "1 of 1" would be noise.
                exclusions.size == 1 && nothingLeft ->
                    txtTitle.text = activity.getString(R.string.preflight_blocked_title)

                else -> {
                    txtTitle.text = activity.getString(R.string.preflight_excluded_title)
                    // FR-14 with more than one file. The batch wording promises "the rest will be
                    // transferred", which is a promise this branch cannot keep: nothingLeft means
                    // there is no rest. Same state, different sentence.
                    txtSubtitle.text = if (nothingLeft) {
                        activity.getString(R.string.preflight_subtitle_batch_all, exclusions.size)
                    } else {
                        activity.getString(
                            R.string.preflight_subtitle_batch,
                            exclusions.size,
                            remainingFiles + exclusions.size
                        )
                    }
                    txtSubtitle.visibility = View.VISIBLE
                }
            }

            // FR-09: the shortfall, stated in the same words the confirm button offers to
            // override. Only the shortfall is named — the free space can be stale by the time
            // the copy arrives, and quoting it invites the user to check a number that moves.
            if (spaceWarning != null) {
                txtWarning.text = activity.getString(
                    R.string.preflight_space_warning,
                    Formatter.formatFileSize(activity, spaceWarning.shortfallBytes.coerceAtLeast(0L))
                )
                txtWarning.visibility = View.VISIBLE
                if (!nothingLeft) {
                    // FR-09's "proceed anyway" is the same button as FR-13's "transfer the rest",
                    // because when both apply it is the same act.
                    btnConfirm.setText(R.string.preflight_proceed_anyway)
                }
            }

            // The rows. A plain addView() loop — no adapter — matching how the compress-results
            // dialog and the checksum manifest build their lists.
            //
            // The reason text is hardcoded to `preflight_reason_too_large` because
            // `BlockReason.EXCEEDS_DESTINATION_FILE_SIZE` is currently the only member of the enum.
            // The layout and its sibling `preflight_reason_truncated` were built to carry a reason
            // per row, so if a second `BlockReason` is ever added this is the site that will
            // silently mislabel every row — it must switch on the reason, not default to this one.
            for (exclusion in exclusions) {
                val row = LayoutInflater.from(activity)
                    .inflate(rowLayoutRes, rows, false)
                row.findViewById<TextView>(R.id.txtExcludedFileName).text = exclusion.fileName
                row.findViewById<TextView>(R.id.txtExcludedReason).text =
                    activity.getString(R.string.preflight_reason_too_large)
                row.findViewById<TextView>(R.id.txtExcludedDetail).apply {
                    text = activity.getString(
                        R.string.preflight_detail_file_size,
                        Formatter.formatFileSize(activity, exclusion.fileSize),
                        exclusion.capabilities.displayName,
                        Formatter.formatFileSize(activity, exclusion.capabilities.maxFileSize)
                    )
                    visibility = View.VISIBLE
                }
                rows.addView(row)
            }

            // A short list in a fixed-height box is mostly empty box. One row of glass card is
            // roughly 60dp, so three fit inside the padding the dialog already has.
            relaxListHeight(view, scrollExcluded, exclusions.size)
            // The free-space warning can arrive with nothing excluded, and an empty list area
            // above the buttons would read as a rendering fault rather than as "no files were
            // excluded" — the one case where the absence of a list is the good news.
            if (exclusions.isEmpty()) scrollExcluded.visibility = View.GONE

            // The TV list is driven by the D-pad, and a key listener is only ever consulted for
            // the view that holds focus — which is why the container is focusable and why this
            // is attached to it rather than to the scroll view inside it. Without this a batch
            // longer than the dialog is tall cannot be read by a remote at all, and FR-12 asks
            // the user to agree to a reduced transfer they would not be able to see.
            if (isTv) {
                view.findViewById<View>(R.id.scrollContainer)?.setOnKeyListener { _, keyCode, event ->
                    if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val step = (80 * activity.resources.displayMetrics.density).toInt()
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            scrollExcluded.smoothScrollBy(0, step); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (scrollExcluded.scrollY <= 0) false
                            else { scrollExcluded.smoothScrollBy(0, -step); true }
                        }
                        else -> false
                    }
                }
            }

            var truncatedScanRequested = false

            fun pick(proceed: Boolean) {
                if (!deferred.isCompleted) deferred.complete(proceed)
            }

            btnConfirm.setOnClickListener { pick(true) }
            btnCancel.setOnClickListener { pick(false) }
            btnFindTrunc.setOnClickListener {
                // Q5: the offer to find files already truncated by this limit lives here, in the
                // dialog that just explained the limit. Asking for it is not a way to proceed —
                // it is a way to deal with the damage first — so it cancels this transfer, and
                // the scan opens once this dialog has gone.
                truncatedScanRequested = true
                pick(false)
            }

            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                .setView(view)
                .setCancelable(false)
                .create()

            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            dialog.show()

            val proceed = deferred.await()
            dialog.dismiss()

            // Handed off inline rather than through a flag read by the caller. The engine that
            // asked for this dialog unwinds on a cancel, so there is no later moment at which it
            // would be in a position to act on a "yes, and also…" — and a process-wide flag that
            // outlives the dialog is a request that can be answered by the wrong caller, or by
            // nobody. Showing it here keeps the whole interaction in one place, on the main
            // thread it needs, with the dialog that started it already gone.
            if (truncatedScanRequested && scanRoot != null) {
                showTruncatedCleanup(activity, scanRoot)
            }
            proceed
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // THE CLEANUP OFFER (FR-21, FR-22, FR-23)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private val cleanupMutex = Mutex()

    /**
     * Finds files already truncated by the destination's per-file limit and offers to remove
     * them (FR-22).
     *
     * Reached from the exclusion dialog, which is the one place the user has just been told what
     * the limit is and what it does (Q5). The dialog reports what it found and the removal is a
     * separate, explicitly confirmed act — nothing here deletes anything until the user says so
     * (FR-23), and [TruncatedFileScanner.scan] has no delete call in it at all.
     *
     * Returns the files that were removed, for the caller's log; empty when nothing matched, the
     * user declined, or the scan could not read [root].
     *
     * A scan that finds nothing still reports. "No damaged files" is the answer the user asked
     * for, and a dialog that silently does not appear is indistinguishable from a button that
     * does not work.
     */
    suspend fun showTruncatedCleanup(activity: Activity, root: File): List<TruncatedFile> =
        cleanupMutex.withLock {
            // The walk is I/O — a StatFs probe per directory and a size read per file — so it
            // runs off the main thread the caller is on. The candidate set can be large: the
            // whole point is to search a volume for files of one specific size.
            val found = withContext(Dispatchers.IO) {
                TruncatedFileScanner.scan(activity.applicationContext, root)
            }

            withContext(Dispatchers.Main) {
                val isTv = DeviceUtils.isTvDevice(activity)
                val layoutRes = if (isTv) R.layout.dialog_truncated_files_tv else R.layout.dialog_truncated_files
                val rowLayoutRes = if (isTv) R.layout.item_preflight_excluded_tv else R.layout.item_preflight_excluded
                val view = LayoutInflater.from(activity).inflate(layoutRes, null, false)

                val txtTitle    = view.findViewById<TextView>(R.id.txtTruncatedTitle)
                val txtSubtitle = view.findViewById<TextView>(R.id.txtTruncatedSubtitle)
                val scrollArea  = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollTruncated)
                val rows        = view.findViewById<ViewGroup>(R.id.layoutTruncatedFiles)
                val btnRemove   = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTruncatedRemove)
                val btnKeep     = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTruncatedKeep)

                val bytes = found.sumOf { it.size }
                txtTitle.text = activity.getString(R.string.preflight_truncated_title, found.size)
                txtSubtitle.text = activity.getString(
                    R.string.preflight_truncated_subtitle,
                    Formatter.formatFileSize(activity, bytes)
                )

                // Nothing found: the offer to remove is absent, exactly as the exclusion dialog
                // removes its confirm when there is nothing left to transfer. There is no action
                // to take, so there is no action to show.
                btnRemove.setText(activity.getString(R.string.preflight_truncated_remove, found.size))
                if (found.isEmpty()) btnRemove.visibility = View.GONE

                for (truncated in found) {
                    val row = LayoutInflater.from(activity).inflate(rowLayoutRes, rows, false)
                    row.findViewById<TextView>(R.id.txtExcludedFileName).text = truncated.file.name
                    row.findViewById<TextView>(R.id.txtExcludedReason).text =
                        activity.getString(R.string.preflight_reason_truncated)
                    row.findViewById<TextView>(R.id.txtExcludedDetail).apply {
                        text = activity.getString(
                            R.string.preflight_detail_truncated,
                            Formatter.formatFileSize(activity, truncated.size),
                            truncated.capabilities.displayName
                        )
                        visibility = View.VISIBLE
                    }
                    rows.addView(row)
                }
                relaxListHeight(view, scrollArea, found.size)
                if (found.isEmpty()) scrollArea.visibility = View.GONE

                if (isTv) {
                    view.findViewById<View>(R.id.scrollContainer)?.setOnKeyListener { _, keyCode, event ->
                        if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val step = (80 * activity.resources.displayMetrics.density).toInt()
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                scrollArea.smoothScrollBy(0, step); true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                if (scrollArea.scrollY <= 0) false
                                else { scrollArea.smoothScrollBy(0, -step); true }
                            }
                            else -> false
                        }
                    }
                }

                val deferred = CompletableDeferred<Boolean>()
                fun pick(remove: Boolean) { if (!deferred.isCompleted) deferred.complete(remove) }
                btnRemove.setOnClickListener { pick(true) }
                btnKeep.setOnClickListener { pick(false) }

                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                    .setView(view)
                    .setCancelable(false)
                    .create()
                dialog.window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                )
                dialog.show()

                val remove = deferred.await()
                dialog.dismiss()

                if (!remove || found.isEmpty()) return@withContext emptyList()

                // Off the main thread again: each deletion re-reads the file's size and dispatches
                // to SAF/root/Shizuku, all of which are I/O.
                withContext(Dispatchers.IO) {
                    TruncatedFileScanner.remove(activity.applicationContext, found)
                    // Only report what is actually gone, so the caller's log matches the removed
                    // count `remove()` returns rather than the list it was offered.
                    found.filterNot { it.file.exists() }
                }
            }
        }
}
