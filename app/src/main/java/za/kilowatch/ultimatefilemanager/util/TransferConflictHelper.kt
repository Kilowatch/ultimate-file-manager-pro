package za.kilowatch.ultimatefilemanager.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.TvShareClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Unified transfer conflict detection and resolution.
 *
 * Design goals:
 *  - Never truncate any existing file to 0 bytes under any code path.
 *  - All overwrites use write-to-temp-then-atomic-rename.
 *  - Source files are only deleted AFTER a verified successful copy+rename.
 *  - Folder collisions prompt conflict resolution; "Keep Both" generates a separate unique folder.
 */
object TransferConflictHelper {

    // ── Conflict action chosen by the user ────────────────────────────────────

    enum class ConflictAction {
        OVERWRITE,   // Replace the existing destination file (atomic temp-then-rename)
        SKIP,        // Leave the destination untouched; do not copy this file
        KEEP_BOTH,   // Auto-rename the incoming file (append " (1)", " (2)", …)
        CANCEL       // Abort the entire transfer operation
    }

    // ── Result returned for each processed file ───────────────────────────────

    data class FileResult(
        val success: Boolean,
        val skipped: Boolean = false,
        val cancelled: Boolean = false
    )

    // ── Partial-output cleanup (FR-17, FR-18, FR-20) ──────────────────────────

    private const val TAG = "TransferConflictHelper"

    /**
     * Removes the partial output of a failed or cancelled transfer (FR-17).
     *
     * The single cleanup routine for every write-to-temp path in this file, so the two cache
     * paths cannot drift apart in how they tidy up after themselves.
     *
     * Deletes **outright** via [File.delete] and is deliberately never routed to the recycle bin
     * (`recycle/RecycleBinManager.moveToTrash`). FR-20: the recycle bin governs user-initiated
     * deletion of user data, and a partial is neither user data nor deliberate — it is the debris
     * of a failed operation. Preserving it wastes the very space the transfer was trying to use,
     * and leaves behind an artifact indistinguishable from a complete file, which is precisely how
     * the reported 4,294,967,295-byte `.mkv` came to look valid.
     *
     * Satisfies FR-18 by construction: every caller passes the temp file *it* created, so no
     * pre-existing file is reachable through here.
     *
     * A deletion that fails is logged rather than swallowed (FR-26) — the one thing worse than a
     * partial on disk is a partial on disk that nothing admits to.
     */
    internal fun discardPartial(file: File) {
        try {
            if (file.exists() && !file.delete()) {
                android.util.Log.w(TAG, "Could not remove partial output: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not remove partial output: ${file.absolutePath}", e)
        }
    }

    // ── TV device check ───────────────────────────────────────────────────────

    private fun isTv(context: Context) = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(context)

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // PUBLIC API — show the conflict dialog
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private val conflictDialogMutex = Mutex()

    /**
     * Shows the conflict resolution dialog on the UI thread and suspends until the user
     * makes a choice.
     *
     * @param activity       Host activity (required for layout inflation and dialog display).
     * @param fileName       Name of the conflicting file.
     * @param isFolder       True when the item is a directory (shown in the message).
     * @param destSizeBytes  Size of the existing destination item in bytes.
     *                        When ≤ 0 the 0-byte warning is hidden.
     *                        When == 0 a special warning is shown.
     * @param applyToAllRef  Reference to the "apply to all" toggle state. Updated inside this
     *                        function when the user changes it.
     * @return The [ConflictAction] chosen by the user.
     */
    suspend fun showConflictDialog(
        activity: Activity,
        fileName: String,
        isFolder: Boolean,
        destSizeBytes: Long,
        applyToAllRef: BooleanArray   // single-element; [0] = current "apply to all" state
    ): ConflictAction = conflictDialogMutex.withLock {
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<ConflictAction>()

            val isTvMode = isTv(activity)
            val layoutRes = if (isTvMode) R.layout.dialog_conflict_tv else R.layout.dialog_conflict_mobile
            val view = LayoutInflater.from(activity).inflate(layoutRes, null, false)

            // Bind views
            val txtFileName    = view.findViewById<TextView>(R.id.txtConflictFileName)
            val txtInfo        = view.findViewById<TextView>(R.id.txtConflictInfo)
            val txtZeroByte    = view.findViewById<TextView>(R.id.txtZeroByteWarning)
            val btnOverwrite   = view.findViewById<MaterialButton>(R.id.btnConflictOverwrite)
            val btnSkip        = view.findViewById<MaterialButton>(R.id.btnConflictSkip)
            val btnKeepBoth    = view.findViewById<MaterialButton>(R.id.btnConflictKeepBoth)
            val btnCancel      = view.findViewById<MaterialButton>(R.id.btnConflictCancel)

            // "Apply to all" — CheckBox on mobile; toggle Button on TV
            val chkApplyToAll  = view.findViewById<CheckBox?>(R.id.chkApplyToAll)
            val btnApplyToAll  = view.findViewById<MaterialButton?>(R.id.btnApplyToAll)

            txtFileName.text = fileName
            txtInfo.text = if (isFolder)
                activity.getString(R.string.conflict_message_folder, fileName)
            else
                activity.getString(R.string.conflict_message_file, fileName)

            if (destSizeBytes == 0L) {
                txtZeroByte.visibility = View.VISIBLE
            }

            // Sync initial "apply to all" visual state
            chkApplyToAll?.isChecked = applyToAllRef[0]
            btnApplyToAll?.alpha = if (applyToAllRef[0]) 1f else 0.5f

            chkApplyToAll?.setOnCheckedChangeListener { _, checked ->
                applyToAllRef[0] = checked
            }
            btnApplyToAll?.setOnClickListener {
                applyToAllRef[0] = !applyToAllRef[0]
                btnApplyToAll.alpha = if (applyToAllRef[0]) 1f else 0.5f
            }

            // Apply TV yellow-focus styling
            if (isTvMode) {
                applyTvFocusStyle(activity, btnOverwrite, btnSkip, btnKeepBoth, btnCancel, btnApplyToAll)
            }

            fun pick(action: ConflictAction) {
                if (!deferred.isCompleted) deferred.complete(action)
            }

            btnOverwrite.setOnClickListener  { pick(ConflictAction.OVERWRITE)  }
            btnSkip.setOnClickListener       { pick(ConflictAction.SKIP)       }
            btnKeepBoth.setOnClickListener   { pick(ConflictAction.KEEP_BOTH)  }
            btnCancel.setOnClickListener     { pick(ConflictAction.CANCEL)     }

            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                .setView(view)
                .setCancelable(false)
                .create()

            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT
                )
            )

            dialog.show()

            // On TV: widen the dialog to ~48% of screen
            if (isTvMode) {
                dialog.window?.setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.48).toInt(),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
                )
            }

            val action = deferred.await()
            dialog.dismiss()
            action
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // LOCAL FILE OPERATIONS — atomic overwrite helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Copies [src] → [dest] (which may or may not exist) with full 0-byte safety.
     *
     * Strategy:
     *  - If [dest] does not exist: straightforward copy, followed by size verification.
     *  - If [dest] exists and [action] == [ConflictAction.OVERWRITE]:
     *      Write to `<dest>.ufm_tmp`, verify sizes match, then atomically rename to [dest].
     *      The existing file is never opened for writing until the temp file is ready.
     *  - If [action] == [ConflictAction.KEEP_BOTH]:
     *      Auto-generate a unique name in the same directory. No temp file needed in this case.
     *  - On any failure: delete the temp file; leave original untouched.
     *
     * @return The actual destination [File] that was written (may differ from [dest] for KEEP_BOTH).
     */
    suspend fun copyLocalToLocalAtomic(
        src: File,
        dest: File,
        action: ConflictAction,
        /**
         * The operation's mount table (FR-04). Batch callers pass the one they read when the
         * operation started, so it is read once per operation rather than once per file (NFR-01).
         * Null means "no table in hand" — the gate reads one, which costs a single procfs read and
         * is the right trade for the single-file callers.
         *
         * Declared before [onProgress] so the trailing-lambda call sites keep working unchanged.
         */
        mounts: List<MountEntry>? = null,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null
    ): File {
        val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
        val isSrcSaf = src is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(src.absolutePath) ||
                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, src.absolutePath)
        val isDestSaf = dest is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(dest.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, dest.absolutePath)
        val isSrcRoot = src is za.kilowatch.ultimatefilemanager.storage.RootFile ||
                        za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(src.absolutePath)
        val isDestRoot = dest is za.kilowatch.ultimatefilemanager.storage.RootFile ||
                         za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(dest.absolutePath)

        val actualDest = when (action) {
            ConflictAction.KEEP_BOTH -> uniqueLocalFile(dest.parentFile ?: File(dest.parent ?: ""), dest.name)
            else -> dest
        }

        // Safety: never copy a file onto itself — that would truncate it to 0 bytes.
        if (src.canonicalPath == actualDest.canonicalPath || src.absolutePath == actualDest.absolutePath) {
            android.util.Log.w("TransferConflictHelper", "copyLocalToLocalAtomic: src == actualDest, skipping self-copy: ${src.absolutePath}")
            return actualDest
        }

        // Hoisted above the gate: the gate needs the size to have an opinion, and this used to be
        // computed further down, past the two early returns below.
        val sourceSize = if (isSrcSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(ctx, src.absolutePath)
        } else if (isSrcRoot) {
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.getFileSize(src.absolutePath)
        } else {
            src.length()
        }

        // ── FR-04: the per-file gate ──────────────────────────────────────────────
        //
        // This sits deliberately *ahead* of the Root and Shizuku branches below, which return
        // unconditionally — a gate placed any later would simply never run for those two access
        // mechanisms, and Q2 requires the check to cover all three. That is also why the size
        // read above had to move up with it.
        //
        // A SKIP is not a write, so a file that will not be transferred cannot exceed anything.
        if (action != ConflictAction.SKIP) {
            val verdict = TransferPreflight.evaluate(
                context = ctx,
                fileName = src.name,
                fileSize = sourceSize,
                dest = actualDest,
                mounts = mounts ?: FilesystemCapabilities.readMountTable()
            )
            if (verdict is PreflightVerdict.Block) {
                // FR-24 — the local→local counterpart of the log in the download gate below.
                val block = PreflightBlockedException(verdict)
                android.util.Log.w(FileTransferGuard.TAG, block.message, block)
                throw block
            }
        }

        if (isSrcRoot && isDestRoot) {
            if (!actualDest.exists() || action == ConflictAction.OVERWRITE) {
                if (action == ConflictAction.OVERWRITE && actualDest.exists()) {
                    za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.delete(actualDest.absolutePath)
                }
                za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.copy(src.absolutePath, actualDest.absolutePath)
            }
            return actualDest
        }

        if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(src.absolutePath) ||
            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(dest.absolutePath)) {
            if (!actualDest.exists() || action == ConflictAction.OVERWRITE) {
                if (action == ConflictAction.OVERWRITE && actualDest.exists()) {
                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(actualDest.absolutePath)
                }
                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.copy(src.absolutePath, actualDest.absolutePath)
            }
            return actualDest
        }

        val destExists = if (isDestSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, actualDest.absolutePath)
        } else if (isDestRoot) {
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.exists(actualDest.absolutePath)
        } else {
            actualDest.exists()
        }

        if (!destExists || action == ConflictAction.OVERWRITE) {
            val openInStream: () -> java.io.InputStream = {
                if (isSrcSaf) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(ctx, src.absolutePath)
                        ?: throw java.io.FileNotFoundException("Cannot open SAF input stream for ${src.absolutePath}")
                } else if (isSrcRoot) {
                    za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.openInputStream(src.absolutePath)
                } else {
                    FileInputStream(src)
                }
            }

            if (isDestRoot) {
                // FR-27 (T033): stream to a sibling `*.ufm_tmp` through root, verify it, then `mv`
                // it into place. The destination is never deleted first — `mv` within a directory
                // is `rename(2)`, one operation that replaces atomically, and the temp is a sibling
                // so it is the same filesystem by construction. Previously the original was deleted
                // up front and the copy streamed straight into its name, so any failure mid-stream
                // left the user's file gone and a partial in its place.
                za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.remount(actualDest.absolutePath, rw = true)
                val tempPath = "${actualDest.absolutePath}.ufm_tmp"
                try {
                    val outStream = za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.openOutputStream(tempPath)
                    val bytesCopied = openInStream().use { inp ->
                        outStream.use { out ->
                            CopyHelper.copy(inp, out, sourceSize, onProgress)
                        }
                    }
                    if (sourceSize > 0 && bytesCopied != sourceSize) {
                        throw Exception("Copy integrity check failed: expected $sourceSize bytes, wrote $bytesCopied bytes to ${actualDest.name}")
                    }
                    if (sourceSize > 0 && bytesCopied <= 0L) {
                        throw Exception("Copy failed: 0 bytes written for ${src.name}")
                    }
                    if (!za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.move(tempPath, actualDest.absolutePath)) {
                        throw java.io.IOException(
                            "Could not replace ${actualDest.name}: moving the verified temporary file " +
                                "into place failed (destination left unchanged)"
                        )
                    }
                } catch (e: Exception) {
                    // Only ever the temp: the destination was never opened for writing.
                    za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.delete(tempPath)
                    throw e
                }
            } else if (isDestSaf) {
                // FR-27 (T033): same shape as the Root branch — write a sibling `*.ufm_tmp`,
                // verify it, then move it onto the destination.
                //
                // SAF has no atomic replace, and this is the one place the guarantee is weaker than
                // the other four mechanisms. `DocumentsContract.renameDocument` is not required to
                // overwrite an existing document — a provider may auto-uniquify the name instead —
                // so the verified temp cannot be renamed *over* the destination. The destination is
                // therefore deleted immediately before the rename: two metadata operations with no
                // data streamed through the gap, against the previous behaviour where the original
                // was deleted before a multi-gigabyte copy had written its first byte.
                val destParent = actualDest.parent ?: ""
                val destName = actualDest.name
                val tempName = "$destName.ufm_tmp"
                val tempPath = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getSafChildPath(destParent, tempName)
                var destinationRemoved = false
                try {
                    if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, tempPath)) {
                        // The MIME type is passed explicitly: `createFile` would otherwise derive it
                        // from the temp's own extension (`.ufm_tmp`), and the rename below carries
                        // that type onto the finished file — leaving a `.mkv` typed as
                        // `application/octet-stream`. The temp is created with the type the
                        // destination is meant to have.
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFile(
                            ctx, destParent, tempName,
                            mimeType = MimeTypeHelper.getOrFallback(destName.substringAfterLast('.', ""))
                        ) ?: throw java.io.IOException("Cannot create SAF temporary file at $tempPath")
                    }

                    val outStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(ctx, tempPath)
                        ?: throw java.io.IOException("Cannot open SAF output stream for $tempPath")

                    val bytesCopied = openInStream().use { inp ->
                        outStream.use { out ->
                            CopyHelper.copy(inp, out, sourceSize, onProgress)
                        }
                    }

                    if (sourceSize > 0 && bytesCopied != sourceSize) {
                        throw Exception("Copy integrity check failed: expected $sourceSize bytes, wrote $bytesCopied bytes to ${actualDest.name}")
                    }
                    if (sourceSize > 0 && bytesCopied <= 0L) {
                        throw Exception("Copy failed: 0 bytes written for ${src.name}")
                    }

                    if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, actualDest.absolutePath)) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(ctx, actualDest.absolutePath)
                    }
                    destinationRemoved = true

                    if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.rename(ctx, tempPath, destName)) {
                        throw java.io.IOException("SAF rename of the verified temporary file did not succeed")
                    }
                    // A provider that honoured the request by uniquifying the name would leave the
                    // new content under a different name and nothing at the destination. Better to
                    // fail loudly here than to report a success that did not happen.
                    if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, actualDest.absolutePath)) {
                        throw java.io.IOException("the SAF provider renamed the temporary file to a different name")
                    }
                } catch (e: Exception) {
                    if (destinationRemoved) {
                        // The destination is already gone and this temp is now the ONLY complete
                        // copy — it is verified output, not a partial, so discarding it (FR-17)
                        // would destroy the user's data to tidy up. Keep it and say where it is.
                        throw java.io.IOException(
                            "Could not replace ${actualDest.name}: ${e.message}. The complete copy was " +
                                "kept as $tempName, in the same folder.", e
                        )
                    }
                    // Nothing was taken from the destination, so the temp is disposable and the
                    // original is untouched (FR-27).
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(ctx, tempPath)
                    throw e
                }
            } else {
                // FR-27: this is the only route, whatever the "Cache Copying" preference says.
                //
                // The direct-write alternative this used to fall back on was never merely a
                // *faster* one — it was an unsafe one. `FileOutputStream(actualDest)` truncates the
                // destination before the first byte, so a copy that fails part-way (source read
                // error, destination full, cancellation) left the user's original file destroyed
                // and replaced by a partial that still looks like a real file. That trade — a
                // rewrite of an existing file either completes or leaves the original alone — is
                // not something a preference should be able to opt out of, so the temp file is
                // unconditional here. It costs one extra rename; it does mean an overwrite briefly
                // needs room for both copies, which the pre-flight free-space check accounts for.
                val tempFile = File(actualDest.parent, "${actualDest.name}.ufm_tmp")
                try {
                    val copySucceeded = FileTransferGuard.guardedCopy(
                        sourceName = src.name,
                        sourceSize = sourceSize,
                        verifyDestSize = { tempFile.length() },
                        doCopy = {
                            openInStream().use { inp ->
                                FileOutputStream(tempFile).use { out ->
                                    CopyHelper.copy(inp, out, sourceSize, onProgress)
                                }
                            }
                        }
                    )
                    if (!copySucceeded) {
                        throw Exception("Copy failed after retries: destination is 0 bytes for ${src.name}")
                    }
                    if (sourceSize > 0 && tempFile.length() != sourceSize) {
                        throw Exception("Copy integrity check failed: expected $sourceSize bytes, got ${tempFile.length()}")
                    }
                    // FR-27: the rename IS the atomic replace, so the destination is never
                    // deleted first. `rename(2)` swaps an existing destination in a single
                    // metadata operation — there is no instant at which the destination does not
                    // exist. Deleting first (what this used to do) opened exactly the window it
                    // was meant to close: a rename or fallback-copy that then failed left the
                    // user's file *gone*, with the temp deleted by the catch below.
                    //
                    // Verified on the target card (FAT32, via the `/storage/<volid>` FUSE mount)
                    // before removing it: `mv temp dest` over an existing `dest` replaced it and
                    // left no temp behind.
                    if (!tempFile.renameTo(actualDest)) {
                        // Deliberately no destructive fallback. A `copyTo(overwrite = true)` here
                        // would truncate the destination and re-create the same hazard on the one
                        // path where the rename has already proved the directory is not behaving
                        // normally. Temp and destination are same-directory, so a rename failing
                        // means the copy would fail too — better to leave the original in place
                        // and say so than to destroy it trying.
                        throw java.io.IOException(
                            "Could not replace ${actualDest.name}: rename of the verified temporary " +
                                "file failed (destination left unchanged)"
                        )
                    }
                } catch (e: Exception) {
                    // FR-24: file *and* destination. `guardedCopy` logs the file name for the
                    // failures it detects, but not where the bytes were going, and the engine catches
                    // further up only have the message — so this is the one place in the local copy
                    // path that can name both ends of the transfer that just failed.
                    android.util.Log.e(
                        FileTransferGuard.TAG,
                        "Copy failed: ${src.absolutePath} → ${actualDest.absolutePath}",
                        e
                    )
                    discardPartial(tempFile)
                    throw e
                }
            }
        }
        // Restore original modification date
        try { actualDest.setLastModified(src.lastModified()) } catch (_: Exception) {}
        MediaScannerNotifier.scanFile(file = actualDest)
        return actualDest
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // NETWORK FILE OPERATIONS — atomic overwrite on network destinations
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Uploads [src] (local file) to [destShare]/[destPath] atomically:
     *  1. Upload to `<destPath>.ufm_tmp`
     *  2. Rename `...ufm_tmp` → [destPath] via the share's rename API
     *  3. Delete `.ufm_tmp` on any failure
     */
    suspend fun uploadLocalToNetworkAtomic(
        src: File,
        destShare: NetworkShare,
        destPath: String,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null,
        onConnectionReady: ((AutoCloseable) -> Unit)? = null
    ) {
        val effectiveDestShare = if (destShare.type == ShareType.SMB && destShare.isServerMode && destShare.remotePath.isBlank()) {
            val clean = destPath.trimStart('/')
            val shareName = if (clean.contains('/')) clean.substringBefore('/') else ""
            if (shareName.isNotEmpty()) {
                destShare.copy(remotePath = "/$shareName")
            } else {
                destShare
            }
        } else {
            destShare
        }
        val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
        val isSrcSaf = src is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(src.absolutePath) ||
                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, src.absolutePath)
        val isSrcRoot = src is za.kilowatch.ultimatefilemanager.storage.RootFile ||
                        za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(src.absolutePath)

        val useTmp = (effectiveDestShare.type != ShareType.AWS_S3 && effectiveDestShare.type != ShareType.IDRIVE_E2 && effectiveDestShare.type != ShareType.WEBDAV && effectiveDestShare.type != ShareType.NFS)
            && za.kilowatch.ultimatefilemanager.settings.CacheCopyPreferenceManager.isEnabled(ctx)
        val tmpPath = if (useTmp) "$destPath.ufm_tmp" else destPath
        val sourceSize = if (isSrcSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(ctx, src.absolutePath)
        } else if (isSrcRoot) {
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.getFileSize(src.absolutePath)
        } else src.length()

        val actualSrc = if (isSrcSaf) {
            val cacheDir = ctx.externalCacheDir ?: ctx.cacheDir
            val tmp = File.createTempFile("ufm_saf_up_", ".tmp", cacheDir)
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(ctx, src.absolutePath)?.use { inp ->
                tmp.outputStream().use { out -> inp.copyTo(out) }
            } ?: throw java.io.FileNotFoundException("Cannot read SAF source for upload: ${src.absolutePath}")
            tmp
        } else if (isSrcRoot) {
            val cacheDir = ctx.externalCacheDir ?: ctx.cacheDir
            val tmp = File.createTempFile("ufm_root_up_", ".tmp", cacheDir)
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.openInputStream(src.absolutePath).use { inp ->
                tmp.outputStream().use { out -> inp.copyTo(out) }
            }
            tmp
        } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(src.absolutePath)) {
            val cacheDir = ctx.externalCacheDir ?: ctx.cacheDir
            val tmp = File.createTempFile("ufm_upload_", ".tmp", cacheDir)
            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.copy(src.absolutePath, tmp.absolutePath)
            tmp
        } else { src }

        val effectiveSourceSize = if (actualSrc != src) actualSrc.length() else {
            if (sourceSize > 0L) sourceSize else src.length()
        }

        try {
            // Zero-byte guard with auto-retry — wraps upload + rename into a single
            // doCopy lambda. verifyDestSize queries the final remote file size after
            // the rename has completed.
            val uploadSucceeded = FileTransferGuard.guardedCopy(
                sourceName = src.name,
                sourceSize = effectiveSourceSize,
                verifyDestSize = { getRemoteFileSize(effectiveDestShare, destPath) },
                doCopy = {
                    if (effectiveDestShare.type == ShareType.TV) {
                        FileInputStream(actualSrc).use { inp ->
                            TvShareClient.uploadStream(effectiveDestShare, tmpPath, inp, effectiveSourceSize)
                        }
                        TvShareClient.rename(effectiveDestShare, tmpPath, destPath)
                    } else {
                        // Cloud providers buffer the entire file locally in openOutputStream then upload
                        // silently on close(), causing progress to jump to 100% instantly and the dialog
                        // to freeze for the duration of the real upload. Bypass openOutputStream and call
                        // uploadStream directly so the onProgress callback fires during actual HTTP transfer.
                        if (effectiveDestShare.type == ShareType.ONEDRIVE ||
                            effectiveDestShare.type == ShareType.GOOGLE_DRIVE ||
                            effectiveDestShare.type == ShareType.DROPBOX ||
                            za.kilowatch.ultimatefilemanager.network.RCloneShareClient.isRCloneShare(effectiveDestShare)) {
                            if (za.kilowatch.ultimatefilemanager.network.RCloneShareClient.isRCloneShare(effectiveDestShare)) {
                                // rclone: stream via operations/copyfile with real-time core/stats progress
                                za.kilowatch.ultimatefilemanager.network.RCloneShareClient.uploadWithProgress(
                                    effectiveDestShare, actualSrc, tmpPath, effectiveSourceSize, onProgress
                                )
                            } else {
                                withContext(Dispatchers.IO) {
                                    FileInputStream(actualSrc).use { inp ->
                                        when (effectiveDestShare.type) {
                                             ShareType.ONEDRIVE ->
                                                za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.uploadStream(effectiveDestShare, tmpPath, inp, effectiveSourceSize, onProgress)
                                            ShareType.GOOGLE_DRIVE ->
                                                za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.uploadStream(effectiveDestShare, tmpPath, inp, effectiveSourceSize, onProgress)
                                            ShareType.DROPBOX ->
                                                za.kilowatch.ultimatefilemanager.network.DropboxShareClient.uploadStream(effectiveDestShare, tmpPath, inp, effectiveSourceSize) { copied ->
                                                    onProgress?.invoke(copied, effectiveSourceSize)
                                                }
                                            else -> {}
                                        }
                                    }
                                }
                            } // end else (non-rclone cloud)
                        } else {
                            val outStream = when (effectiveDestShare.type) {
                                ShareType.SMB -> SmbShareClient.openOutputStream(effectiveDestShare, tmpPath) { conn -> onConnectionReady?.invoke(conn) }
                                ShareType.FTP -> FtpShareClient.openOutputStream(effectiveDestShare, tmpPath)
                                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(effectiveDestShare, tmpPath)
                                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(effectiveDestShare, tmpPath)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openOutputStream(effectiveDestShare, tmpPath)
                                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(effectiveDestShare, tmpPath)
                                else -> throw Exception("Unsupported share type")
                            }
                            withContext(Dispatchers.IO) {
                                FileInputStream(actualSrc).use { inp ->
                                    outStream.use { out ->
                                        CopyHelper.copy(inp, out, effectiveSourceSize, onProgress)
                                    }
                                }
                            }
                        }
                        if (useTmp) {
                            when (effectiveDestShare.type) {
                                ShareType.SMB -> SmbShareClient.rename(effectiveDestShare, tmpPath, destPath)
                                ShareType.FTP -> FtpShareClient.rename(effectiveDestShare, tmpPath, destPath)
                                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.rename(effectiveDestShare, tmpPath, destPath)
                                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.rename(effectiveDestShare, tmpPath, destPath)
                                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.rename(effectiveDestShare, tmpPath, destPath)
                                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.rename(effectiveDestShare, tmpPath, destPath)
                                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.rename(effectiveDestShare, tmpPath, destPath)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.rename(effectiveDestShare, tmpPath, destPath)
                                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.rename(effectiveDestShare, tmpPath, destPath)
                                else -> {}
                            }
                        }
                    }
                }
            )
            if (!uploadSucceeded) {
                throw Exception("Upload failed after retries: destination is 0 bytes for ${src.name}")
            }
        } catch (e: Exception) {
            // Best-effort cleanup of the incomplete temp file
            runCatching {
                if (useTmp) {
                    when (effectiveDestShare.type) {
                        ShareType.SMB -> SmbShareClient.deleteFile(effectiveDestShare, tmpPath)
                        ShareType.FTP -> FtpShareClient.deleteFile(effectiveDestShare, tmpPath)
                        ShareType.TV  -> TvShareClient.deleteFile(effectiveDestShare, tmpPath)
                        ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(effectiveDestShare, tmpPath, false)
                        ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteFile(effectiveDestShare, tmpPath)
                        ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(effectiveDestShare, tmpPath)
                        ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(effectiveDestShare, tmpPath)
                        ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(effectiveDestShare, tmpPath)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(effectiveDestShare, tmpPath)
                        ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(effectiveDestShare, tmpPath)
                        ShareType.DLNA -> {}
                    }
                }
            }
            throw e
        } finally {
            if (actualSrc != src) actualSrc.delete()
        }
    }

    /**
     * Copies a network file to a local destination atomically.
     * Uses a `.ufm_tmp` local file; renames in place after verification.
     */
    suspend fun downloadNetworkToLocalAtomic(
        srcShare: NetworkShare,
        srcFile: NetworkFile,
        dest: File,
        action: ConflictAction,
        /**
         * The operation's mount table (FR-04); see [copyLocalToLocalAtomic]. Null reads one here.
         */
        mounts: List<MountEntry>? = null,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null,
        onConnectionReady: ((AutoCloseable) -> Unit)? = null
    ): File {
        val effectiveSrcShare = if (srcShare.type == ShareType.SMB && srcShare.isServerMode && srcShare.remotePath.isBlank()) {
            val clean = srcFile.path.trimStart('/')
            val shareName = if (clean.contains('/')) clean.substringBefore('/') else ""
            if (shareName.isNotEmpty() && shareName != srcFile.name) {
                srcShare.copy(remotePath = "/$shareName")
            } else {
                srcShare
            }
        } else {
            srcShare
        }
        val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
        val isDestSaf = dest is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(dest.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, dest.absolutePath)
        val isDestRoot = dest is za.kilowatch.ultimatefilemanager.storage.RootFile ||
                         za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(dest.absolutePath)

        val actualDest = when (action) {
            ConflictAction.KEEP_BOTH -> {
                val parentDir = dest.parentFile ?: (if (!dest.parent.isNullOrEmpty()) File(dest.parent!!) else File(ctx.filesDir.absolutePath))
                uniqueLocalFile(parentDir, srcFile.name, ctx)
            }
            else -> dest
        }

        // ── FR-04: the per-file gate, download path ───────────────────────────────
        //
        // Same placement rule as [copyLocalToLocalAtomic]: ahead of the Root and SAF branches
        // below, which return unconditionally, so a gate any later would never run for them.
        // The size is already in hand here — it comes from the network listing, so unlike the
        // local path there is nothing to hoist.
        if (action != ConflictAction.SKIP) {
            val verdict = TransferPreflight.evaluate(
                context = ctx,
                fileName = srcFile.name,
                fileSize = srcFile.size,
                dest = actualDest,
                mounts = mounts ?: FilesystemCapabilities.readMountTable()
            )
            if (verdict is PreflightVerdict.Block) {
                // FR-24. Logged at the throw rather than from the exception's constructor, so that
                // this type stays reachable from a JVM unit test — see the note on
                // `PreflightBlockedException`. The exception's own message already names the file,
                // its size, the limit and the filesystem, so nothing is re-derived here.
                val block = PreflightBlockedException(verdict)
                android.util.Log.w(FileTransferGuard.TAG, block.message, block)
                throw block
            }
        }

        if (isDestRoot) {
            // FR-27 (T033): sibling temp, verify, then `mv` into place — same shape as the
            // local→local Root branch. The destination is never deleted first.
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.remount(actualDest.absolutePath, rw = true)
            val tempPath = "${actualDest.absolutePath}.ufm_tmp"
            try {
                // Opened before the temp so a failed connection never leaves a stray temp behind.
                val inStream = when (effectiveSrcShare.type) {
                    ShareType.SMB -> SmbShareClient.openInputStream(effectiveSrcShare, srcFile.path) { conn -> onConnectionReady?.invoke(conn) }
                    ShareType.FTP -> FtpShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                    ShareType.TV  -> TvShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                    ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                    ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                    ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                    ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                    ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                    ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                }
                val outStream = za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.openOutputStream(tempPath)
                val bytesCopied = withContext(Dispatchers.IO) {
                    inStream.use { inp ->
                        outStream.use { out ->
                            CopyHelper.copy(inp, out, srcFile.size, onProgress)
                        }
                    }
                }
                if (srcFile.size > 0 && bytesCopied != srcFile.size) {
                    throw Exception("Download integrity check failed: expected ${srcFile.size} bytes, wrote $bytesCopied bytes to ${actualDest.name}")
                }
                if (srcFile.size > 0 && bytesCopied <= 0L) {
                    throw Exception("Download failed: 0 bytes written for ${srcFile.name}")
                }
                if (!za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.move(tempPath, actualDest.absolutePath)) {
                    throw java.io.IOException(
                        "Could not replace ${actualDest.name}: moving the verified temporary file " +
                            "into place failed (destination left unchanged)"
                    )
                }
            } catch (e: Exception) {
                za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.delete(tempPath)
                throw e
            }
            return actualDest
        }

        if (isDestSaf) {
            val destParent = actualDest.parent ?: ""
            val destName = actualDest.name

            // FR-27 (T033): sibling temp, verify, then rename over the destination — same shape as
            // the local→local SAF branch. SAF cannot replace atomically, so the destination is
            // deleted only once the temp is complete, and the `destinationRemoved` flag below
            // keeps the verified temp if the rename then fails.
            val tempName = "$destName.ufm_tmp"
            val tempPath = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getSafChildPath(destParent, tempName)
            var destinationRemoved = false
            try {
                if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, tempPath)) {
                    // Explicit MIME type — see the note on the local→local branch above: deriving it
                    // from `.ufm_tmp` would leave the finished file typed as octet-stream.
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFile(
                        ctx, destParent, tempName,
                        mimeType = MimeTypeHelper.getOrFallback(destName.substringAfterLast('.', ""))
                    ) ?: throw java.io.IOException("Cannot create SAF temporary file at $tempPath")
                }

                val inStream = when (effectiveSrcShare.type) {
                    ShareType.SMB -> SmbShareClient.openInputStream(effectiveSrcShare, srcFile.path) { conn -> onConnectionReady?.invoke(conn) }
                    ShareType.FTP -> FtpShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                    ShareType.TV  -> TvShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                    ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                    ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                    ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                    ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                    ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                    ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                }
                val outStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(ctx, tempPath)
                    ?: throw java.io.IOException("Cannot open SAF temporary output stream for $tempPath")

                val bytesCopied = withContext(Dispatchers.IO) {
                    inStream.use { inp ->
                        outStream.use { out ->
                            CopyHelper.copy(inp, out, srcFile.size, onProgress)
                        }
                    }
                }

                if (srcFile.size > 0 && bytesCopied != srcFile.size) {
                    throw Exception("Download integrity check failed: expected ${srcFile.size} bytes, wrote $bytesCopied bytes to ${actualDest.name}")
                }
                if (srcFile.size > 0 && bytesCopied <= 0L) {
                    throw Exception("Download failed: 0 bytes written for ${srcFile.name}")
                }

                // The temp is complete and verified — only now is the original given up.
                if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, actualDest.absolutePath)) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(ctx, actualDest.absolutePath)
                }
                destinationRemoved = true

                if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.rename(ctx, tempPath, destName)) {
                    throw java.io.IOException("SAF rename of the verified temporary file did not succeed")
                }
                if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, actualDest.absolutePath)) {
                    throw java.io.IOException("the SAF provider renamed the temporary file to a different name")
                }
            } catch (e: Exception) {
                if (destinationRemoved) {
                    // The destination is already gone and this temp is now the ONLY complete copy —
                    // it is verified output, not a partial, so discarding it (FR-17) would destroy
                    // the user's data to tidy up. Keep it and say where it is.
                    throw java.io.IOException(
                        "Could not replace ${actualDest.name}: ${e.message}. The complete copy was " +
                            "kept as $tempName, in the same folder.", e
                    )
                }
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(ctx, tempPath)
                throw e
            }
            return actualDest
        }


        // FR-27: a download never writes into the destination.
        //
        // `tempFile` used to be `actualDest` itself whenever the "Cache Copying" setting was off —
        // which is its default — so a failed or cancelled download had already destroyed whatever
        // was there, and the catch below could not clean up because it deliberately skipped the
        // delete when `tempFile == actualDest`. Network sources fail mid-transfer routinely
        // (timeout, dropped connection, cancellation), so the *default* path was the unsafe one.
        val isShizukuDest =
            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(actualDest.absolutePath)
        val tempFile = if (isShizukuDest) {
            // Root/Shizuku destination: the app cannot write alongside `actualDest`, so the temp
            // has to live where it *can* write and the shell moves it into place afterwards.
            val cacheDir = ctx.externalCacheDir ?: ctx.cacheDir
            File.createTempFile("ufm_dl_", ".tmp", cacheDir)
        } else {
            val parentDir = actualDest.parentFile ?: (if (!actualDest.parent.isNullOrEmpty()) File(actualDest.parent!!) else ctx.cacheDir)
            File(parentDir, "${actualDest.name}.ufm_tmp")
        }
        try {
            val downloadSucceeded = FileTransferGuard.guardedCopy(
                sourceName = srcFile.name,
                sourceSize = srcFile.size,
                verifyDestSize = { tempFile.length() },
                doCopy = {
                    if (za.kilowatch.ultimatefilemanager.network.RCloneShareClient.isRCloneShare(effectiveSrcShare)) {
                        za.kilowatch.ultimatefilemanager.network.RCloneShareClient.downloadWithProgress(
                            effectiveSrcShare, srcFile.path, tempFile, srcFile.size, onProgress
                        )
                    } else {
                        val threads = effectiveSrcShare.effectiveThreads(ctx)
                        val isParallelCandidate = (effectiveSrcShare.type == ShareType.FTP || effectiveSrcShare.type == ShareType.SFTP)
                            && threads > 1 && srcFile.size >= 5 * 1024 * 1024
                        var parallelHandled = false
                        if (isParallelCandidate) {
                            parallelHandled = when (effectiveSrcShare.type) {
                                ShareType.FTP -> FtpShareClient.downloadFileParallel(
                                    share = effectiveSrcShare,
                                    remotePath = srcFile.path,
                                    destFile = tempFile,
                                    totalSize = srcFile.size,
                                    threads = threads,
                                    onProgress = onProgress,
                                    onConnectionReady = onConnectionReady
                                )
                                ShareType.SFTP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.downloadFileParallel(
                                    share = effectiveSrcShare,
                                    remotePath = srcFile.path,
                                    destFile = tempFile,
                                    totalSize = srcFile.size,
                                    threads = threads,
                                    onProgress = onProgress,
                                    onConnectionReady = onConnectionReady
                                )
                                else -> false
                            }
                        }

                        if (!parallelHandled) {
                            val inStream = when (effectiveSrcShare.type) {
                                ShareType.SMB -> SmbShareClient.openInputStream(effectiveSrcShare, srcFile.path) { conn -> onConnectionReady?.invoke(conn) }
                                ShareType.FTP -> FtpShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                                ShareType.TV  -> TvShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(effectiveSrcShare, srcFile.path)
                                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(effectiveSrcShare, srcFile.path).first
                                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                            withContext(Dispatchers.IO) {
                                inStream.use { inp ->
                                    FileOutputStream(tempFile).use { out ->
                                        CopyHelper.copy(inp, out, srcFile.size, onProgress)
                                    }
                                }
                            }
                        }
                    }
                }
            )
            if (!downloadSucceeded) {
                throw Exception("Download failed after retries: destination is 0 bytes for ${srcFile.name}")
            }
            if (srcFile.size > 0 && tempFile.length() != srcFile.size) {
                throw Exception("Download integrity check failed: expected ${srcFile.size} bytes, got ${tempFile.length()}")
            }
            // The verified download is now placed. Neither branch deletes the destination first:
            // `mv` and `rename(2)` each replace it themselves, and the delete-first that used to
            // precede them meant a failed move left the destination *gone* — for the Shizuku
            // branch the result was discarded, so the user lost the file in silence.
            if (isShizukuDest) {
                if (!za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.move(tempFile.absolutePath, actualDest.absolutePath)) {
                    // Residual: a cache-dir temp and a destination on another volume make `mv`
                    // fall back to copy-then-unlink internally, so this one branch is not atomic —
                    // it is nonetheless strictly better than deleting first, which guaranteed loss
                    // on any failure. Device-verified in T031.
                    throw java.io.IOException(
                        "Could not place ${actualDest.name}: moving the verified download into " +
                            "place failed (destination left unchanged)"
                    )
                }
            } else if (!tempFile.renameTo(actualDest)) {
                throw java.io.IOException(
                    "Could not replace ${actualDest.name}: rename of the verified temporary file " +
                        "failed (destination left unchanged)"
                )
            }
        } catch (e: Exception) {
            // FR-24: the network→local counterpart of the log in `copyLocalToLocalAtomic`. Both
            // ends are named here too — the remote source path is what identifies which of several
            // similarly-named files on a share failed.
            android.util.Log.e(
                FileTransferGuard.TAG,
                "Download failed: ${srcFile.path} (${effectiveSrcShare.name}) → ${actualDest.absolutePath}",
                e
            )
            // `tempFile` is now always a real temp, so this always removes something — the old
            // `tempFile != actualDest` guard made it a no-op on the default path.
            discardPartial(tempFile)
            throw e
        }
        MediaScannerNotifier.scanFile(file = actualDest)
        return actualDest
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // EXISTENCE CHECKS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** Returns true if [name] already exists in [destDir] (local/SAF/Root). */
    fun localFileExists(destDir: File, name: String, context: Context? = null): Boolean {
        val ctx = context ?: za.kilowatch.ultimatefilemanager.UfmApplication.instance
        val isSaf = destDir is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(destDir.absolutePath) ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, destDir.absolutePath)
        if (isSaf) {
            val candidatePath = za.kilowatch.ultimatefilemanager.storage.SafFile.combineSafPath(destDir.absolutePath, name)
            return za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, candidatePath)
        }
        if (destDir is za.kilowatch.ultimatefilemanager.storage.RootFile || za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(destDir.absolutePath)) {
            val candidate = File(destDir, name)
            return za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.exists(candidate.absolutePath)
        }
        if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(destDir.absolutePath)) {
            return za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.exists(File(destDir, name).absolutePath)
        }
        return File(destDir, name).exists()
    }

    /** Returns the size of [File(destDir, name)], or -1 if it doesn't exist. */
    fun localFileSize(destDir: File, name: String, context: Context? = null): Long {
        val ctx = context ?: za.kilowatch.ultimatefilemanager.UfmApplication.instance
        val isSaf = destDir is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(destDir.absolutePath) ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, destDir.absolutePath)
        if (isSaf) {
            val candidatePath = za.kilowatch.ultimatefilemanager.storage.SafFile.combineSafPath(destDir.absolutePath, name)
            return za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(ctx, candidatePath)
        }
        if (destDir is za.kilowatch.ultimatefilemanager.storage.RootFile || za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(destDir.absolutePath)) {
            val candidate = File(destDir, name)
            return za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.getFileSize(candidate.absolutePath)
        }
        val f = File(destDir, name)
        return if (f.exists()) f.length() else -1L
    }

    /**
     * Returns true if [name] already exists in [destDir] within [knownFiles].
     * [knownFiles] is populated from the destination listing we already have in memory,
     * so no extra network round-trip is needed.
     */
    fun networkFileExists(name: String, knownFiles: List<NetworkFile>): Boolean =
        knownFiles.any { it.name.equals(name, ignoreCase = false) }

    /** Returns the size of the matching entry in [knownFiles], or -1 if not found. */
    fun networkFileSize(name: String, knownFiles: List<NetworkFile>): Long =
        knownFiles.firstOrNull { it.name == name }?.size ?: -1L

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // UTILITIES
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** Generates a unique local/SAF/Root file path by appending " (1)", " (2)" … */
    fun uniqueLocalFile(dir: File, name: String, context: Context? = null): File {
        val ctx = context ?: runCatching { za.kilowatch.ultimatefilemanager.UfmApplication.instance }.getOrNull()
        val isSaf = dir is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(dir.absolutePath) ||
                    (ctx != null && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, dir.absolutePath))
        val isRoot = dir is za.kilowatch.ultimatefilemanager.storage.RootFile ||
                     za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(dir.absolutePath)
        val ext  = if (name.contains('.')) ".${name.substringAfterLast('.')}" else ""
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        var counter = 1
        var candidateName = name
        fun checkExists(candName: String): Boolean {
            if (isSaf) {
                val candidatePath = za.kilowatch.ultimatefilemanager.storage.SafFile.combineSafPath(dir.absolutePath, candName)
                return ctx != null && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, candidatePath)
            }
            if (isRoot) {
                val candidate = File(dir, candName)
                return za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.exists(candidate.absolutePath)
            }
            val candidate = File(dir, candName)
            return if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(candidate.absolutePath))
                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.exists(candidate.absolutePath)
            else candidate.exists()
        }
        while (checkExists(candidateName)) {
            candidateName = "$base ($counter)$ext"
            counter++
        }
        return if (isSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafFile(dir.absolutePath, candidateName, false)
        } else if (isRoot) {
            za.kilowatch.ultimatefilemanager.storage.RootFile(dir.absolutePath, candidateName, false)
        } else {
            File(dir, candidateName)
        }
    }

    /** Generates a unique local/SAF/Root folder path by appending " (1)", " (2)" … */
    fun uniqueLocalFolder(dir: File, name: String, context: Context? = null): File {
        val ctx = context ?: runCatching { za.kilowatch.ultimatefilemanager.UfmApplication.instance }.getOrNull()
        val isSaf = dir is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(dir.absolutePath) ||
                    (ctx != null && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, dir.absolutePath))
        val isRoot = dir is za.kilowatch.ultimatefilemanager.storage.RootFile ||
                     za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(dir.absolutePath)
        var counter = 1
        var candidateName = name
        fun checkExists(candName: String): Boolean {
            if (isSaf) {
                val candidatePath = za.kilowatch.ultimatefilemanager.storage.SafFile.combineSafPath(dir.absolutePath, candName)
                return ctx != null && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, candidatePath)
            }
            if (isRoot) {
                val candidate = File(dir, candName)
                return za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.exists(candidate.absolutePath)
            }
            val candidate = File(dir, candName)
            return if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(candidate.absolutePath))
                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.exists(candidate.absolutePath)
            else candidate.exists()
        }
        while (checkExists(candidateName)) {
            candidateName = "$name ($counter)"
            counter++
        }
        return if (isSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafFile(dir.absolutePath, candidateName, true)
        } else if (isRoot) {
            za.kilowatch.ultimatefilemanager.storage.RootFile(dir.absolutePath, candidateName, true)
        } else {
            File(dir, candidateName)
        }
    }

    /** Generates a unique remote path by appending " (1)", " (2)" … */
    fun uniqueNetworkPath(parentPath: String, name: String, knownFiles: List<NetworkFile>, isFolder: Boolean = false): String {
        val ext  = if (!isFolder && name.contains('.')) ".${name.substringAfterLast('.')}" else ""
        val base = if (!isFolder && name.contains('.')) name.substringBeforeLast('.') else name
        var counter = if (isFolder) 2 else 1
        var candidate = if (isFolder) "$name (1)" else name
        while (networkFileExists(candidate, knownFiles)) {
            candidate = "$base ($counter)$ext"
            counter++
        }
        return if (parentPath.isEmpty() || parentPath == "/") candidate else "${parentPath.trimEnd('/')}/$candidate"
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // TV BUTTON STYLING
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun applyTvFocusStyle(
        activity: Activity,
        vararg buttons: MaterialButton?
    ) {
        val yellow    = ColorblindPalette.focusFill(activity)
        val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
        val glass     = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())
        val black     = ColorblindPalette.focusFillText(activity)
        val white     = activity.getColor(R.color.tv_text_primary)

        for (btn in buttons) {
            btn ?: continue
            val defaultBg = btn.backgroundTintList ?: glass
            btn.setOnFocusChangeListener { _, hasFocus ->
                btn.backgroundTintList = if (hasFocus) yellowCsl else defaultBg
                btn.setTextColor(if (hasFocus) black else white)
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // TRANSFER FLATTENING
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    data class TransferTask(
        val srcLocal: File?,
        val srcNet: NetworkFile?,
        val destPath: String,
        val isFolder: Boolean
    )

    /**
     * Traverses directories recursively and builds a flat list of items to transfer.
     * This allows the main loop to use atomic operations and single-file conflict checks uniformly.
     */
    suspend fun buildTransferTasks(
        sources: List<Any>,
        srcShare: NetworkShare?,
        destDir: String
    ): List<TransferTask> {
        val tasks = mutableListOf<TransferTask>()
        
        fun walkLocal(src: File, relPath: String) {
            val destPath = if (destDir == "/" || destDir.isEmpty()) "/$relPath${src.name}" else "$destDir/$relPath${src.name}"
            tasks.add(TransferTask(src, null, destPath, src.isDirectory))
            if (src.isDirectory) {
                val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
                val isSaf = src is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(src.absolutePath) ||
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, src.absolutePath)
                val isRoot = src is za.kilowatch.ultimatefilemanager.storage.RootFile ||
                             za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(src.absolutePath)
                val children = if (isSaf) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.listFiles(ctx, src.absolutePath)
                } else if (isRoot) {
                    za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.listFiles(src.absolutePath)
                } else {
                    src.listFiles()?.toList()
                }
                children?.forEach { walkLocal(it, "$relPath${src.name}/") }
            }
        }
        
        suspend fun walkNetwork(src: NetworkFile, relPath: String) {
            val basePath = if (destDir == "/" || destDir.isEmpty()) "" else "$destDir/"
            val destPath = "$basePath$relPath${src.name}"
            tasks.add(TransferTask(null, src, destPath, src.isDirectory))
            if (src.isDirectory && srcShare != null) {
                val children = when (srcShare.type) {
                    ShareType.SMB -> SmbShareClient.listFiles(srcShare, src.path)
                    ShareType.FTP -> FtpShareClient.listFiles(srcShare, src.path)
                    ShareType.TV  -> TvShareClient.listFiles(srcShare, src.path)
                    ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(srcShare, src.path)
                    ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(srcShare, src.path)
                    ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(srcShare, src.path)
                    ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(srcShare, src.path)
                    ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(srcShare, src.path)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(srcShare, src.path)
                    ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(srcShare, src.path)
                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                }
                for (child in children) {
                    walkNetwork(child, "$relPath${src.name}/")
                }
            }
        }

        for (src in sources) {
            if (src is File) walkLocal(src, "")
            else if (src is NetworkFile) walkNetwork(src, "")
        }
        return tasks
    }

    fun countLocalFiles(dir: File): Int {
        val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
        val isSaf = dir is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(dir.absolutePath) ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, dir.absolutePath)
        val isRoot = dir is za.kilowatch.ultimatefilemanager.storage.RootFile ||
                     za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(dir.absolutePath)
        var count = 0
        val children = if (isSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.listFiles(ctx, dir.absolutePath)
        } else if (isRoot) {
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.listFiles(dir.absolutePath)
        } else {
            dir.listFiles()?.toList()
        } ?: return 0
        for (child in children) {
            if (child.isDirectory) count += countLocalFiles(child)
            else count++
        }
        return count
    }

    suspend fun countNetworkFiles(share: NetworkShare, dirPath: String): Int {
        var count = 0
        val children = when (share.type) {
            ShareType.SMB -> SmbShareClient.listFiles(share, dirPath)
            ShareType.FTP -> FtpShareClient.listFiles(share, dirPath)
            ShareType.TV  -> TvShareClient.listFiles(share, dirPath)
            ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, dirPath)
            ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, dirPath)
            ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, dirPath)
            ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, dirPath)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, dirPath)
            ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, dirPath)
            ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(share, dirPath)
            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
        }
        for (child in children) {
            if (child.isDirectory) count += countNetworkFiles(share, child.path)
            else count++
        }
        return count
    }

    suspend fun deleteNetworkDirRecursively(share: NetworkShare, dirPath: String) {
        // All share types except FTP support a single native "delete directory recursively" call.
        // FTP has no recursive delete command and must traverse manually.
        // DLNA is read-only and should never reach here.
        if (share.type != ShareType.FTP && share.type != ShareType.DLNA) {
            try {
                GoRoLog.d("TransferConflictHelper", "deleteNetworkDirRecursively: using native recursive delete for ${share.type} at $dirPath")
                when (share.type) {
                    ShareType.SMB                        -> SmbShareClient.deleteDir(share, dirPath)
                    ShareType.TV                         -> TvShareClient.deleteDir(share, dirPath)
                    ShareType.SFTP, ShareType.SCP        -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(share, dirPath, true)
                    ShareType.NFS                        -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteDir(share, dirPath)
                    ShareType.WEBDAV                     -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteDir(share, dirPath)
                    ShareType.ONEDRIVE                   -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(share, dirPath)
                    ShareType.GOOGLE_DRIVE               -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(share, dirPath)
                    ShareType.DROPBOX                    -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(share, dirPath)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(share, "$dirPath/")
                    else -> {}
                }
                return
            } catch (e: Exception) {
                GoRoLog.w("TransferConflictHelper", "Native recursive delete failed for $dirPath: ${e.message}. Falling back to manual traversal.")
            }
        }

        val children = when (share.type) {
            ShareType.SMB -> SmbShareClient.listFiles(share, dirPath)
            ShareType.FTP -> FtpShareClient.listFiles(share, dirPath)
            ShareType.TV  -> TvShareClient.listFiles(share, dirPath)
            ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, dirPath)
            ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, dirPath)
            ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, dirPath)
            ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, dirPath)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, dirPath)
            ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, dirPath)
            ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(share, dirPath)
            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
        }
        GoRoLog.d("TransferConflictHelper", "deleteNetworkDirRecursively: found ${children.size} items in $dirPath")
        for (child in children) {
            coroutineContext.ensureActive() // Check for cancellation
            if (child.isDirectory) {
                deleteNetworkDirRecursively(share, child.path)
            } else {
                try {
                    GoRoLog.d("TransferConflictHelper", "deleteNetworkDirRecursively: deleting file ${child.path}")
                    when (share.type) {
                        ShareType.SMB -> SmbShareClient.deleteFile(share, child.path)
                        ShareType.FTP -> FtpShareClient.deleteFile(share, child.path)
                        ShareType.TV  -> TvShareClient.deleteFile(share, child.path)
                        ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(share, child.path, false)
                        ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteFile(share, child.path)
                        ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(share, child.path)
                        ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(share, child.path)
                        ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(share, child.path)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(share, child.path)
                        ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(share, child.path)
                        ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                    }
                } catch (e: Exception) {
                    GoRoLog.w("TransferConflictHelper", "deleteNetworkDirRecursively: failed to delete ${child.path}: ${e.message}")
                }
            }
        }
        try {
            GoRoLog.d("TransferConflictHelper", "deleteNetworkDirRecursively: removing dir $dirPath")
            when (share.type) {
                ShareType.SMB -> SmbShareClient.deleteDir(share, dirPath)
                ShareType.FTP -> FtpShareClient.deleteDir(share, dirPath)
                ShareType.TV  -> TvShareClient.deleteDir(share, dirPath)
                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(share, dirPath, true)
                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteDir(share, dirPath)
                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(share, dirPath)
                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(share, dirPath)
                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(share, dirPath)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(share, "$dirPath/")
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteDir(share, dirPath)
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (e: Exception) {
            GoRoLog.w("TransferConflictHelper", "deleteNetworkDirRecursively: failed to rmdir $dirPath: ${e.message}")
        }
    }

    suspend fun copyNetworkFileToNetwork(
        srcShare: NetworkShare,
        source: NetworkFile,
        dstShare: NetworkShare,
        dstPath: String,
        onProgress: (String, Long, Long, Int, Int) -> Unit,
        fileIndex: Int,
        totalFiles: Int,
        onConnectionReady: ((AutoCloseable) -> Unit)? = null,
        cacheDir: File? = null
    ) {
        // Zero-byte guard with auto-retry — this function previously had no
        // size verification at all (FR-04 gap).
        val copySucceeded = FileTransferGuard.guardedCopy(
            sourceName = source.name,
            sourceSize = source.size,
            verifyDestSize = { getRemoteFileSize(dstShare, dstPath) },
            doCopy = {
                withContext(Dispatchers.IO) {
                    val inStream = when (srcShare.type) {
                        ShareType.SMB -> SmbShareClient.openInputStream(srcShare, source.path)
                        ShareType.FTP -> FtpShareClient.openInputStream(srcShare, source.path)
                        ShareType.TV  -> TvShareClient.openInputStream(srcShare, source.path)
                        ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(srcShare, source.path)
                        ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(srcShare, source.path)
                        ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(srcShare, source.path).first
                        ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(srcShare, source.path).first
                        ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(srcShare, source.path).first
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(srcShare, source.path).first
                        ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(srcShare, source.path).first
                        ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                    }

                    if (dstShare.type == ShareType.TV) {
                        val tempFile = File.createTempFile("ufm_nettv_", ".tmp", cacheDir)
                        try {
                            inStream.use { inp -> tempFile.outputStream().use { out ->
                                CopyHelper.copy(inp, out, source.size) { copied, total ->
                                    onProgress(source.name, copied, total, fileIndex, totalFiles)
                                }
                            } }
                            tempFile.inputStream().use { inp ->
                                TvShareClient.uploadStream(dstShare, dstPath, inp, tempFile.length())
                            }
                        } finally { tempFile.delete() }
                    } else {
                        val outStream = when (dstShare.type) {
                            ShareType.SMB -> SmbShareClient.openOutputStream(dstShare, dstPath) { conn -> onConnectionReady?.invoke(conn) }
                            ShareType.FTP -> FtpShareClient.openOutputStream(dstShare, dstPath)
                            ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(dstShare, dstPath)
                    ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(dstShare, dstPath)
                    ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openOutputStream(dstShare, dstPath)
                    ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openOutputStream(dstShare, dstPath)
                    ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openOutputStream(dstShare, dstPath)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openOutputStream(dstShare, dstPath)
                    ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(dstShare, dstPath)
                            else -> throw Exception("Unhandled destination share type")
                        }
                        inStream.use { inp -> outStream.use { out ->
                            CopyHelper.copy(inp, out, source.size) { copied, total ->
                                onProgress(source.name, copied, total, fileIndex, totalFiles)
                            }
                        } }
                    }
                }
            }
        )
        if (!copySucceeded) {
            throw Exception("Network-to-network copy failed after retries: destination is 0 bytes for ${source.name}")
        }
    }

    /**
     * Local → local copy that bypasses the pre-flight gate and the conflict resolver.
     *
     * **No caller.** Verified by grep across `app/src/main` and `app/src/test`: the name appears
     * only at this declaration. Every local→local copy in the app goes through
     * [copyLocalToLocalAtomic], which is where FR-27's temp-verify-replace sequence lives — so
     * this is not an alternative entry point that happens to be unused, it is the *old* one, and
     * it writes straight to the destination. Two reasons to leave the marker rather than the
     * function's absence: deleting it is a change to a file this feature only needed to amend,
     * and a future caller reaching for the shorter name would silently reintroduce the overwrite
     * hazard FR-27 exists to close. If you are here to call it: don't — call
     * [copyLocalToLocalAtomic]. The reasoning is recorded in `.plans/tasks.md` under
     * "Deferred / Not In Scope", which is gitignored, which is why it is repeated here.
     */
    suspend fun copyLocalFileToLocal(
        src: File,
        dst: File,
        onProgress: (String, Long, Long, Int, Int) -> Unit,
        fileIndex: Int,
        totalFiles: Int
    ) {
        val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
        val isSrcSaf = src is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(src.absolutePath) ||
                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, src.absolutePath)
        val isDstSaf = dst is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(dst.absolutePath) ||
                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, dst.absolutePath)

        val sourceSize = if (isSrcSaf) za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(ctx, src.absolutePath) else src.length()
        onProgress(src.name, 0, sourceSize, fileIndex, totalFiles)

        if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(src.absolutePath) || 
            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(dst.absolutePath)) {
            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.copy(src.absolutePath, dst.absolutePath)
            onProgress(src.name, sourceSize, sourceSize, fileIndex, totalFiles)
            return
        }

        if (isDstSaf) {
            withContext(Dispatchers.IO) {
                val inStream = if (isSrcSaf) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(ctx, src.absolutePath)
                        ?: throw java.io.FileNotFoundException("Cannot open SAF src: ${src.absolutePath}")
                } else {
                    FileInputStream(src)
                }

                if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, dst.absolutePath)) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(ctx, dst.absolutePath)
                }
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFile(ctx, dst.parent ?: "", dst.name)
                val outStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(ctx, dst.absolutePath)
                    ?: throw java.io.IOException("Cannot open SAF dst: ${dst.absolutePath}")

                val bytesCopied = inStream.use { inp ->
                    outStream.use { out ->
                        CopyHelper.copy(inp, out, sourceSize) { copied, total ->
                            onProgress(src.name, copied, total, fileIndex, totalFiles)
                        }
                    }
                }

                if (sourceSize > 0 && bytesCopied != sourceSize) {
                    throw Exception("Copy integrity check failed: expected $sourceSize bytes, wrote $bytesCopied bytes to ${dst.name}")
                }
                onProgress(src.name, sourceSize, sourceSize, fileIndex, totalFiles)
            }
            return
        }

        val copySucceeded = FileTransferGuard.guardedCopy(
            sourceName = src.name,
            sourceSize = sourceSize,
            verifyDestSize = {
                if (isDstSaf) za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(ctx, dst.absolutePath)
                else dst.length()
            },
            doCopy = {
                withContext(Dispatchers.IO) {
                    val inStream = if (isSrcSaf) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(ctx, src.absolutePath)
                            ?: throw java.io.FileNotFoundException("Cannot open SAF src: ${src.absolutePath}")
                    } else {
                        FileInputStream(src)
                    }
                    val outStream = if (isDstSaf) {
                        if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, dst.absolutePath)) {
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(ctx, dst.absolutePath)
                        }
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFile(ctx, dst.parent ?: "", dst.name)
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(ctx, dst.absolutePath)
                            ?: throw java.io.IOException("Cannot open SAF dst: ${dst.absolutePath}")
                    } else {
                        FileOutputStream(dst)
                    }
                    inStream.use { inp ->
                        outStream.use { out ->
                            CopyHelper.copy(inp, out, sourceSize) { copied, total ->
                                onProgress(src.name, copied, total, fileIndex, totalFiles)
                            }
                        }
                    }
                }
            }
        )
        if (!copySucceeded) {
            throw Exception("Local-to-local copy failed after retries: destination is 0 bytes for ${src.name}")
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // FILE SIZE QUERY — remote file size retrieval for post-copy verification
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Queries the size of a file on a remote share.
     *
     * Supports all share types including RClone (via `operations/stat`),
     * SMB (via dedicated `getFileSize`), and standard listFile-based queries.
     *
     * For cloud storage types (OneDrive, Google Drive, Dropbox, S3, WebDAV,
     * rclone), an internal short retry (3 × 500 ms) is used to account for
     * delayed metadata propagation — some providers do not immediately reflect
     * the size of a newly uploaded file.
     *
     * @param share      The network share to query.
     * @param remotePath Full remote path to the file.
     * @return The file size in bytes, or -1 if the file does not exist or
     *         the size cannot be determined.
     */
    suspend fun getRemoteFileSize(share: NetworkShare, remotePath: String): Long {
        return try {
            // RClone: use dedicated operations/stat endpoint
            if (za.kilowatch.ultimatefilemanager.network.RCloneShareClient.isRCloneShare(share)) {
                val size = withContext(Dispatchers.IO) {
                    za.kilowatch.ultimatefilemanager.network.RCloneShareClient.getFileSizeSync(share, remotePath)
                }
                if (size <= 0L) {
                    // Brief retry for delayed metadata
                    var s = size
                    for (i in 1..3) {
                        delay(500)
                        s = withContext(Dispatchers.IO) {
                            za.kilowatch.ultimatefilemanager.network.RCloneShareClient.getFileSizeSync(share, remotePath)
                        }
                        if (s > 0L) return s
                    }
                    return s
                }
                return size
            }

            // Handle root-level files correctly: when '/' is absent,
            // parent is "" (not the entire path) and name is the whole path.
            val parent = remotePath.substringBeforeLast('/', "").ifEmpty { "/" }
            val name = remotePath.substringAfterLast('/')

            val size = when (share.type) {
                ShareType.SMB -> {
                    // SMB has dedicated getFileSize — use it when available
                    za.kilowatch.ultimatefilemanager.network.SmbShareClient.getFileSize(share, remotePath)
                        ?: za.kilowatch.ultimatefilemanager.network.SmbShareClient.listFiles(share, parent)
                            .firstOrNull { it.name == name }?.size ?: -1L
                }
                ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.getFileSize(share, remotePath)
                    ?: (za.kilowatch.ultimatefilemanager.network.FtpShareClient.listFiles(share, parent)
                        .firstOrNull { it.name == name }?.size ?: -1L)
                ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.listFiles(share, parent)
                    .firstOrNull { it.name == name }?.size ?: -1L
                ShareType.SFTP, ShareType.SCP ->
                    // Direct lstat on the file path — avoids stale directory-listing
                    // metadata that can lag after a write and cause false 0-byte results.
                    za.kilowatch.ultimatefilemanager.network.SshShareClient.getFileSize(share, remotePath)
                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(share, parent)
                    .firstOrNull { it.name == name }?.size ?: -1L
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, parent)
                    .firstOrNull { it.name == name }?.size ?: -1L
                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, parent)
                    .firstOrNull { it.name == name }?.size ?: -1L
                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, parent)
                    .firstOrNull { it.name == name }?.size ?: -1L
                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, parent)
                    .firstOrNull { it.name == name }?.size ?: -1L
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, parent)
                    .firstOrNull { it.name == name }?.size ?: -1L
                ShareType.DLNA -> -1L  // read-only
            }

            // Cloud storage delayed-propagation mitigation
            if (size <= 0L && isCloudStorageType(share.type)) {
                var s = size
                for (i in 1..3) {
                    delay(500)
                    s = when (share.type) {
                        ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, parent)
                            .firstOrNull { it.name == name }?.size ?: -1L
                        ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, parent)
                            .firstOrNull { it.name == name }?.size ?: -1L
                        ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, parent)
                            .firstOrNull { it.name == name }?.size ?: -1L
                        else -> s
                    }
                    if (s > 0L) return s
                }
            }

            size
        } catch (e: Exception) {
            GoRoLog.w("TransferConflictHelper", "getRemoteFileSize failed for $remotePath: ${e.message}")
            -1L
        }
    }

    /** Returns `true` for cloud/remote storage types where metadata
     *  propagation may be delayed after a write. */
    private fun isCloudStorageType(type: ShareType): Boolean = when (type) {
        ShareType.ONEDRIVE, ShareType.GOOGLE_DRIVE, ShareType.DROPBOX,
        ShareType.AWS_S3, ShareType.IDRIVE_E2, ShareType.WEBDAV -> true
        else -> false
    }
}
