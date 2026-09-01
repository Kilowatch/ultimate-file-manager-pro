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
import kotlin.coroutines.coroutineContext
import za.kilowatch.ultimatefilemanager.R
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

    // ── TV device check ───────────────────────────────────────────────────────

    private fun isTv(context: Context) = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(context)

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // PUBLIC API — show the conflict dialog
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
    ): ConflictAction = withContext(Dispatchers.Main) {
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

        val sourceSize = if (isSrcSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(ctx, src.absolutePath)
        } else if (isSrcRoot) {
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.getFileSize(src.absolutePath)
        } else {
            src.length()
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
                // Root Destination direct superuser streaming
                if (destExists && action == ConflictAction.OVERWRITE) {
                    za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.delete(actualDest.absolutePath)
                }
                za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.remount(actualDest.absolutePath, rw = true)
                val outStream = za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.openOutputStream(actualDest.absolutePath)
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
            } else if (isDestSaf) {
                // SAF Destination direct streaming
                val destParent = actualDest.parent ?: ""
                val destName = actualDest.name

                if (destExists && action == ConflictAction.OVERWRITE) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(ctx, actualDest.absolutePath)
                }

                if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, actualDest.absolutePath)) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFile(ctx, destParent, destName)
                }

                val outStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(ctx, actualDest.absolutePath)
                    ?: throw java.io.IOException("Cannot open SAF output stream for ${actualDest.absolutePath}")

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
            } else {
                val useCacheCopy = za.kilowatch.ultimatefilemanager.settings.CacheCopyPreferenceManager.isEnabled(ctx)
                if (useCacheCopy) {
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
                        if (actualDest.exists()) actualDest.delete()
                        if (!tempFile.renameTo(actualDest)) {
                            tempFile.copyTo(actualDest, overwrite = true)
                            tempFile.delete()
                        }
                    } catch (e: Exception) {
                        tempFile.delete()
                        throw e
                    }
                } else {
                    val copySucceeded = FileTransferGuard.guardedCopy(
                        sourceName = src.name,
                        sourceSize = sourceSize,
                        verifyDestSize = { actualDest.length() },
                        doCopy = {
                            openInStream().use { inp ->
                                FileOutputStream(actualDest).use { out ->
                                    CopyHelper.copy(inp, out, sourceSize, onProgress)
                                }
                            }
                        }
                    )
                    if (!copySucceeded) {
                        throw Exception("Copy failed after retries: destination is 0 bytes for ${src.name}")
                    }
                    if (sourceSize > 0 && actualDest.length() != sourceSize) {
                        throw Exception("Copy integrity check failed: expected $sourceSize bytes, got ${actualDest.length()}")
                    }
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
        val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
        val isSrcSaf = src is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(src.absolutePath) ||
                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, src.absolutePath)
        val isSrcRoot = src is za.kilowatch.ultimatefilemanager.storage.RootFile ||
                        za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(src.absolutePath)

        val useTmp = (destShare.type != ShareType.AWS_S3 && destShare.type != ShareType.IDRIVE_E2 && destShare.type != ShareType.WEBDAV && destShare.type != ShareType.NFS)
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
                verifyDestSize = { getRemoteFileSize(destShare, destPath) },
                doCopy = {
                    if (destShare.type == ShareType.TV) {
                        FileInputStream(actualSrc).use { inp ->
                            TvShareClient.uploadStream(destShare, tmpPath, inp, effectiveSourceSize)
                        }
                        TvShareClient.rename(destShare, tmpPath, destPath)
                    } else {
                        // Cloud providers buffer the entire file locally in openOutputStream then upload
                        // silently on close(), causing progress to jump to 100% instantly and the dialog
                        // to freeze for the duration of the real upload. Bypass openOutputStream and call
                        // uploadStream directly so the onProgress callback fires during actual HTTP transfer.
                        if (destShare.type == ShareType.ONEDRIVE ||
                            destShare.type == ShareType.GOOGLE_DRIVE ||
                            destShare.type == ShareType.DROPBOX ||
                            za.kilowatch.ultimatefilemanager.network.RCloneShareClient.isRCloneShare(destShare)) {
                            if (za.kilowatch.ultimatefilemanager.network.RCloneShareClient.isRCloneShare(destShare)) {
                                // rclone: stream via operations/copyfile with real-time core/stats progress
                                za.kilowatch.ultimatefilemanager.network.RCloneShareClient.uploadWithProgress(
                                    destShare, actualSrc, tmpPath, effectiveSourceSize, onProgress
                                )
                            } else {
                                withContext(Dispatchers.IO) {
                                    FileInputStream(actualSrc).use { inp ->
                                        when (destShare.type) {
                                             ShareType.ONEDRIVE ->
                                                za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.uploadStream(destShare, tmpPath, inp, effectiveSourceSize, onProgress)
                                            ShareType.GOOGLE_DRIVE ->
                                                za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.uploadStream(destShare, tmpPath, inp, effectiveSourceSize, onProgress)
                                            ShareType.DROPBOX ->
                                                za.kilowatch.ultimatefilemanager.network.DropboxShareClient.uploadStream(destShare, tmpPath, inp, effectiveSourceSize) { copied ->
                                                    onProgress?.invoke(copied, effectiveSourceSize)
                                                }
                                            else -> {}
                                        }
                                    }
                                }
                            } // end else (non-rclone cloud)
                        } else {
                            val outStream = when (destShare.type) {
                                ShareType.SMB -> SmbShareClient.openOutputStream(destShare, tmpPath) { conn -> onConnectionReady?.invoke(conn) }
                                ShareType.FTP -> FtpShareClient.openOutputStream(destShare, tmpPath)
                                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(destShare, tmpPath)
                                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(destShare, tmpPath)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openOutputStream(destShare, tmpPath)
                                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(destShare, tmpPath)
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
                            when (destShare.type) {
                                ShareType.SMB -> SmbShareClient.rename(destShare, tmpPath, destPath)
                                ShareType.FTP -> FtpShareClient.rename(destShare, tmpPath, destPath)
                                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.rename(destShare, tmpPath, destPath)
                                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.rename(destShare, tmpPath, destPath)
                                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.rename(destShare, tmpPath, destPath)
                                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.rename(destShare, tmpPath, destPath)
                                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.rename(destShare, tmpPath, destPath)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.rename(destShare, tmpPath, destPath)
                                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.rename(destShare, tmpPath, destPath)
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
                    when (destShare.type) {
                        ShareType.SMB -> SmbShareClient.deleteFile(destShare, tmpPath)
                        ShareType.FTP -> FtpShareClient.deleteFile(destShare, tmpPath)
                        ShareType.TV  -> TvShareClient.deleteFile(destShare, tmpPath)
                        ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(destShare, tmpPath, false)
                        ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteFile(destShare, tmpPath)
                        ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(destShare, tmpPath)
                        ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(destShare, tmpPath)
                        ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(destShare, tmpPath)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(destShare, tmpPath)
                        ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(destShare, tmpPath)
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
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null,
        onConnectionReady: ((AutoCloseable) -> Unit)? = null
    ): File {
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

        if (isDestRoot) {
            val destExists = za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.exists(actualDest.absolutePath)
            if (destExists && action == ConflictAction.OVERWRITE) {
                za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.delete(actualDest.absolutePath)
            }
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.remount(actualDest.absolutePath, rw = true)
            val outStream = za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.openOutputStream(actualDest.absolutePath)
            val inStream = when (srcShare.type) {
                ShareType.SMB -> SmbShareClient.openInputStream(srcShare, srcFile.path) { conn -> onConnectionReady?.invoke(conn) }
                ShareType.FTP -> FtpShareClient.openInputStream(srcShare, srcFile.path)
                ShareType.TV  -> TvShareClient.openInputStream(srcShare, srcFile.path)
                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(srcShare, srcFile.path)
                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(srcShare, srcFile.path)
                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(srcShare, srcFile.path).first
                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(srcShare, srcFile.path).first
                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(srcShare, srcFile.path).first
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(srcShare, srcFile.path).first
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(srcShare, srcFile.path).first
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
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
            return actualDest
        }

        if (isDestSaf) {
            val destParent = actualDest.parent ?: ""
            val destName = actualDest.name

            val destExists = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, actualDest.absolutePath)
            if (destExists && action == ConflictAction.OVERWRITE) {
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(ctx, actualDest.absolutePath)
            }

            if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(ctx, actualDest.absolutePath)) {
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFile(ctx, destParent, destName)
            }

            val outStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(ctx, actualDest.absolutePath)
                ?: throw java.io.IOException("Cannot open SAF destination: ${actualDest.absolutePath}")

            val inStream = when (srcShare.type) {
                ShareType.SMB -> SmbShareClient.openInputStream(srcShare, srcFile.path) { conn -> onConnectionReady?.invoke(conn) }
                ShareType.FTP -> FtpShareClient.openInputStream(srcShare, srcFile.path)
                ShareType.TV  -> TvShareClient.openInputStream(srcShare, srcFile.path)
                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(srcShare, srcFile.path)
                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(srcShare, srcFile.path)
                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(srcShare, srcFile.path).first
                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(srcShare, srcFile.path).first
                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(srcShare, srcFile.path).first
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(srcShare, srcFile.path).first
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(srcShare, srcFile.path).first
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
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
            return actualDest
        }


        val useCacheCopy = za.kilowatch.ultimatefilemanager.settings.CacheCopyPreferenceManager.isEnabled(ctx)
        val tempFile = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(actualDest.absolutePath)) {
            val cacheDir = ctx.externalCacheDir ?: ctx.cacheDir
            File.createTempFile("ufm_dl_", ".tmp", cacheDir)
        } else if (useCacheCopy) {
            val parentDir = actualDest.parentFile ?: (if (!actualDest.parent.isNullOrEmpty()) File(actualDest.parent!!) else ctx.cacheDir)
            File(parentDir, "${actualDest.name}.ufm_tmp")
        } else {
            actualDest
        }
        try {
            val downloadSucceeded = FileTransferGuard.guardedCopy(
                sourceName = srcFile.name,
                sourceSize = srcFile.size,
                verifyDestSize = { tempFile.length() },
                doCopy = {
                    if (za.kilowatch.ultimatefilemanager.network.RCloneShareClient.isRCloneShare(srcShare)) {
                        za.kilowatch.ultimatefilemanager.network.RCloneShareClient.downloadWithProgress(
                            srcShare, srcFile.path, tempFile, srcFile.size, onProgress
                        )
                    } else {
                        val inStream = when (srcShare.type) {
                            ShareType.SMB -> SmbShareClient.openInputStream(srcShare, srcFile.path) { conn -> onConnectionReady?.invoke(conn) }
                            ShareType.FTP -> FtpShareClient.openInputStream(srcShare, srcFile.path)
                            ShareType.TV  -> TvShareClient.openInputStream(srcShare, srcFile.path)
                            ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(srcShare, srcFile.path)
                            ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(srcShare, srcFile.path)
                            ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(srcShare, srcFile.path).first
                            ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(srcShare, srcFile.path).first
                            ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(srcShare, srcFile.path).first
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(srcShare, srcFile.path).first
                            ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(srcShare, srcFile.path).first
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
            )
            if (!downloadSucceeded) {
                throw Exception("Download failed after retries: destination is 0 bytes for ${srcFile.name}")
            }
            if (srcFile.size > 0 && tempFile.length() != srcFile.size) {
                throw Exception("Download integrity check failed: expected ${srcFile.size} bytes, got ${tempFile.length()}")
            }
            if (tempFile != actualDest) {
                if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(actualDest.absolutePath)) {
                    if (actualDest.exists()) za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(actualDest.absolutePath)
                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.move(tempFile.absolutePath, actualDest.absolutePath)
                } else {
                    if (actualDest.exists()) actualDest.delete()
                    if (!tempFile.renameTo(actualDest)) {
                        tempFile.copyTo(actualDest, overwrite = true)
                        tempFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            if (tempFile != actualDest) tempFile.delete()
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
        val yellow    = activity.getColor(R.color.tv_button_focused_yellow)
        val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
        val glass     = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())
        val black     = activity.getColor(R.color.tv_button_focused_yellow_text)
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
                ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.listFiles(share, parent)
                    .firstOrNull { it.name == name }?.size ?: -1L
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
