package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.PairingManager
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.TvShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import za.kilowatch.ultimatefilemanager.settings.SafFileActionPreferenceManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper
import za.kilowatch.ultimatefilemanager.util.TransferProgressDialogHelper
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Manages live transfer telemetry, progress tracking, and conflict resolution for SAF file copy
 * and export operations, rendering UFMStandard transfer progress via [dialog_transfer_progress].
 */
object SafFileTransferManager {

    private const val TAG = "SafTransfer"

    data class SafUriMetadata(
        val uri: Uri,
        val displayName: String,
        val size: Long
    )

    fun queryUriMetadata(context: Context, uri: Uri): SafUriMetadata {
        var name = "Document"
        var size = -1L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx != -1) {
                        name = cursor.getString(nameIdx) ?: name
                    }
                    if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to query URI metadata: ${e.message}")
        }
        return SafUriMetadata(uri, name, size)
    }

    /**
     * Copies a list of picked SAF URIs to the configured destination (local directory or network/cloud URI).
     */
    fun copyUrisToDestination(
        activity: AppCompatActivity,
        uris: List<Uri>,
        destPath: String,
        onComplete: ((Int) -> Unit)? = null
    ) {
        if (uris.isEmpty()) return
        if (SafFileActionPreferenceManager.isNetworkOrRemoteUri(destPath)) {
            copyUrisToRemote(activity, uris, destPath, onComplete)
        } else {
            val destDir = File(destPath)
            copyUrisToDirectory(activity, uris, destDir, onComplete)
        }
    }

    /**
     * Copies a list of picked SAF URIs to the specified local destination directory,
     * resolving file conflicts using [TransferConflictHelper] and displaying real-time
     * telemetry with smooth updates and completion pause.
     */
    fun copyUrisToDirectory(
        activity: AppCompatActivity,
        uris: List<Uri>,
        destDir: File,
        onComplete: ((Int) -> Unit)? = null
    ) {
        if (uris.isEmpty()) return
        if (!destDir.exists()) destDir.mkdirs()

        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.dialog_transfer_progress_tv else R.layout.dialog_transfer_progress
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtProgressTitle)
        val txtFiles = dialogView.findViewById<TextView>(R.id.txtProgressFiles)
        val txtCurrentFile = dialogView.findViewById<TextView>(R.id.txtProgressCurrentFile)
        val txtSize = dialogView.findViewById<TextView>(R.id.txtProgressSize)
        val txtSpeed = dialogView.findViewById<TextView>(R.id.txtProgressSpeed)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progressFile)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtTitle.setText(R.string.copying_files)
        txtSpeed.text = ""
        txtSize.text = activity.getString(R.string.preparing)
        progressBar.isIndeterminate = true

        val helper = TransferProgressDialogHelper(activity, dialogView, isTv)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        var transferJob: Job? = null
        var isCancelled = false

        btnCancel.setOnClickListener {
            isCancelled = true
            transferJob?.cancel()
            dialog.dismiss()
        }

        transferJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            val metadataList = uris.map { queryUriMetadata(activity, it) }
            val totalBytes = metadataList.sumOf { if (it.size > 0) it.size else 0L }
            val totalFiles = metadataList.size

            var overallBytesCopied = 0L
            var speedLastBytes = 0L
            var speedLastMs = SystemClock.elapsedRealtime()
            var copiedCount = 0

            var applyToAllAction: TransferConflictHelper.ConflictAction? = null
            val applyToAllRef = booleanArrayOf(false)
            val buffer = ByteArray(64 * 1024)

            for ((index, item) in metadataList.withIndex()) {
                if (isCancelled) break

                val originalName = item.displayName
                var targetFile = File(destDir, originalName)

                // ── Conflict resolution ──
                if (targetFile.exists()) {
                    val action = applyToAllAction ?: run {
                        TransferConflictHelper.showConflictDialog(
                            activity,
                            originalName,
                            false,
                            targetFile.length(),
                            applyToAllRef
                        )
                    }
                    if (applyToAllRef[0]) {
                        applyToAllAction = action
                    }

                    when (action) {
                        TransferConflictHelper.ConflictAction.CANCEL -> {
                            isCancelled = true
                            break
                        }
                        TransferConflictHelper.ConflictAction.SKIP -> {
                            continue
                        }
                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                            targetFile = TransferConflictHelper.uniqueLocalFile(destDir, originalName)
                        }
                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                            // Proceed to overwrite targetFile
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (totalFiles > 1) {
                        txtFiles.text = "Item ${index + 1} of $totalFiles"
                        txtFiles.visibility = View.VISIBLE
                    } else {
                        txtFiles.visibility = View.GONE
                    }
                    txtCurrentFile.text = originalName
                    if (totalBytes > 0) {
                        progressBar.isIndeterminate = false
                        val pct = ((overallBytesCopied * 100) / totalBytes).toInt().coerceIn(0, 100)
                        progressBar.progress = pct
                        val copiedStr = Formatter.formatFileSize(activity, overallBytesCopied)
                        val totalStr = Formatter.formatFileSize(activity, totalBytes)
                        txtSize.text = "$copiedStr / $totalStr ($pct%)"
                    } else {
                        progressBar.isIndeterminate = true
                        txtSize.text = activity.getString(R.string.preparing)
                    }
                }

                try {
                    activity.contentResolver.openInputStream(item.uri)?.use { inStream ->
                        targetFile.outputStream().use { outStream ->
                            var bytesRead: Int
                            while (inStream.read(buffer).also { bytesRead = it } != -1) {
                                if (isCancelled) {
                                    throw CancellationException("Transfer cancelled by user")
                                }
                                outStream.write(buffer, 0, bytesRead)
                                overallBytesCopied += bytesRead

                                val nowMs = SystemClock.elapsedRealtime()
                                val deltaMs = nowMs - speedLastMs
                                if (deltaMs >= 100L) {
                                    val deltaBytes = overallBytesCopied - speedLastBytes
                                    val mbps = if (deltaBytes > 0 && deltaMs > 0) {
                                        (deltaBytes.toDouble() / (1024.0 * 1024.0)) / (deltaMs.toDouble() / 1000.0)
                                    } else 0.0

                                    speedLastBytes = overallBytesCopied
                                    speedLastMs = nowMs

                                    val copiedStr = Formatter.formatFileSize(activity, overallBytesCopied)
                                    val totalStr = if (totalBytes > 0) Formatter.formatFileSize(activity, totalBytes) else null
                                    val pct = if (totalBytes > 0) ((overallBytesCopied * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

                                    withContext(Dispatchers.Main) {
                                        if (totalStr != null) {
                                            progressBar.isIndeterminate = false
                                            progressBar.progress = pct
                                            txtSize.text = "$copiedStr / $totalStr ($pct%)"
                                        } else {
                                            progressBar.isIndeterminate = true
                                            txtSize.text = copiedStr
                                        }
                                        txtSpeed.text = String.format(Locale.US, "%.1f MB/s", mbps)
                                        helper.updateSpeed(mbps)
                                    }
                                }
                            }
                        }
                    }
                    copiedCount++
                } catch (e: Exception) {
                    if (e is CancellationException || isCancelled) {
                        if (targetFile.exists()) targetFile.delete()
                        break
                    } else {
                        GoRoLog.e(TAG, "Error copying file ${item.displayName}: ${e.message}")
                    }
                }
            }

            // Show 100% completion state and pause briefly before dismissing
            if (!isCancelled && copiedCount > 0) {
                withContext(Dispatchers.Main) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 100
                    val totalStr = Formatter.formatFileSize(activity, overallBytesCopied)
                    txtSize.text = "$totalStr / $totalStr (100%)"
                    txtSpeed.text = activity.getString(R.string.action_done)
                    helper.updateSpeed(0.0)
                }
                delay(700L)
            }

            withContext(Dispatchers.Main) {
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
                if (!isCancelled) {
                    val destName = SafFileActionPreferenceManager.getDestinationDisplayNameForPath(activity, destDir.absolutePath)
                    val msg = if (destName == "Downloads") {
                        activity.getString(R.string.saf_files_copied_to_downloads, copiedCount)
                    } else {
                        activity.getString(R.string.saf_files_copied_to_destination, copiedCount, destName)
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                    onComplete?.invoke(copiedCount)
                }
            }
        }
    }

    /**
     * Copies a list of picked SAF URIs to the specified network/online destination URI.
     */
    private fun copyUrisToRemote(
        activity: AppCompatActivity,
        uris: List<Uri>,
        destPath: String,
        onComplete: ((Int) -> Unit)? = null
    ) {
        val schemeEnd = destPath.indexOf("://")
        if (schemeEnd == -1) return
        val afterScheme = destPath.substring(schemeEnd + 3)
        val shareId = afterScheme.substringBefore('/')
        val remoteDir = afterScheme.substringAfter('/', "").trimStart('/')

        val share = NetworkShareRepository.getInstance(activity).getById(shareId)
            ?: OnlineStorageRepository.getInstance(activity).getById(shareId)?.toNetworkShare()
            ?: PairingManager.getInstance(activity).getPairedDevice(shareId)?.let { dev ->
                NetworkShare(
                    id = dev.deviceId, name = dev.name,
                    type = ShareType.TV,
                    host = dev.lastIp, port = dev.lastPort, readOnly = false
                )
            }

        if (share == null) {
            Toast.makeText(activity, "Destination network storage not found", Toast.LENGTH_SHORT).show()
            return
        }

        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.dialog_transfer_progress_tv else R.layout.dialog_transfer_progress
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtProgressTitle)
        val txtFiles = dialogView.findViewById<TextView>(R.id.txtProgressFiles)
        val txtCurrentFile = dialogView.findViewById<TextView>(R.id.txtProgressCurrentFile)
        val txtSize = dialogView.findViewById<TextView>(R.id.txtProgressSize)
        val txtSpeed = dialogView.findViewById<TextView>(R.id.txtProgressSpeed)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progressFile)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtTitle.text = "Uploading to ${share.name}"
        txtSpeed.text = ""
        txtSize.text = activity.getString(R.string.preparing)
        progressBar.isIndeterminate = true

        val helper = TransferProgressDialogHelper(activity, dialogView, isTv)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        var transferJob: Job? = null
        var isCancelled = false

        btnCancel.setOnClickListener {
            isCancelled = true
            transferJob?.cancel()
            dialog.dismiss()
        }

        transferJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            val metadataList = uris.map { queryUriMetadata(activity, it) }
            val totalBytes = metadataList.sumOf { if (it.size > 0) it.size else 0L }
            val totalFiles = metadataList.size

            var overallBytesCopied = 0L
            var speedLastBytes = 0L
            var speedLastMs = SystemClock.elapsedRealtime()
            var copiedCount = 0

            val buffer = ByteArray(64 * 1024)

            for ((index, item) in metadataList.withIndex()) {
                if (isCancelled) break

                val originalName = item.displayName
                val targetRemotePath = if (remoteDir.isEmpty()) originalName else "$remoteDir/$originalName"

                withContext(Dispatchers.Main) {
                    if (totalFiles > 1) {
                        txtFiles.text = "Item ${index + 1} of $totalFiles"
                        txtFiles.visibility = View.VISIBLE
                    } else {
                        txtFiles.visibility = View.GONE
                    }
                    txtCurrentFile.text = originalName
                    if (totalBytes > 0) {
                        progressBar.isIndeterminate = false
                        val pct = ((overallBytesCopied * 100) / totalBytes).toInt().coerceIn(0, 100)
                        progressBar.progress = pct
                        val copiedStr = Formatter.formatFileSize(activity, overallBytesCopied)
                        val totalStr = Formatter.formatFileSize(activity, totalBytes)
                        txtSize.text = "$copiedStr / $totalStr ($pct%)"
                    } else {
                        progressBar.isIndeterminate = true
                        txtSize.text = activity.getString(R.string.preparing)
                    }
                }

                try {
                    activity.contentResolver.openInputStream(item.uri)?.use { inStream ->
                        if (share.type == ShareType.TV) {
                            TvShareClient.uploadStream(share, targetRemotePath, inStream, item.size)
                        } else {
                            openRemoteOutputStream(share, targetRemotePath)?.use { outStream ->
                                var bytesRead: Int
                                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                                    if (isCancelled) {
                                        throw CancellationException("Upload cancelled by user")
                                    }
                                    outStream.write(buffer, 0, bytesRead)
                                    overallBytesCopied += bytesRead

                                    val nowMs = SystemClock.elapsedRealtime()
                                    val deltaMs = nowMs - speedLastMs
                                    if (deltaMs >= 100L) {
                                        val deltaBytes = overallBytesCopied - speedLastBytes
                                        val mbps = if (deltaBytes > 0 && deltaMs > 0) {
                                            (deltaBytes.toDouble() / (1024.0 * 1024.0)) / (deltaMs.toDouble() / 1000.0)
                                        } else 0.0

                                        speedLastBytes = overallBytesCopied
                                        speedLastMs = nowMs

                                        val copiedStr = Formatter.formatFileSize(activity, overallBytesCopied)
                                        val totalStr = if (totalBytes > 0) Formatter.formatFileSize(activity, totalBytes) else null
                                        val pct = if (totalBytes > 0) ((overallBytesCopied * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

                                        withContext(Dispatchers.Main) {
                                            if (totalStr != null) {
                                                progressBar.isIndeterminate = false
                                                progressBar.progress = pct
                                                txtSize.text = "$copiedStr / $totalStr ($pct%)"
                                            } else {
                                                progressBar.isIndeterminate = true
                                                txtSize.text = copiedStr
                                            }
                                            txtSpeed.text = String.format(Locale.US, "%.1f MB/s", mbps)
                                            helper.updateSpeed(mbps)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    copiedCount++
                } catch (e: Exception) {
                    if (e is CancellationException || isCancelled) {
                        break
                    } else {
                        GoRoLog.e(TAG, "Error uploading to remote ${item.displayName}: ${e.message}")
                    }
                }
            }

            // Show 100% completion state and pause briefly before dismissing
            if (!isCancelled && copiedCount > 0) {
                withContext(Dispatchers.Main) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 100
                    val totalStr = Formatter.formatFileSize(activity, overallBytesCopied)
                    txtSize.text = "$totalStr / $totalStr (100%)"
                    txtSpeed.text = activity.getString(R.string.action_done)
                    helper.updateSpeed(0.0)
                }
                delay(700L)
            }

            withContext(Dispatchers.Main) {
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
                if (!isCancelled) {
                    val destName = SafFileActionPreferenceManager.getDestinationDisplayNameForPath(activity, destPath)
                    val msg = if (destName == "Downloads") {
                        activity.getString(R.string.saf_files_copied_to_downloads, copiedCount)
                    } else {
                        activity.getString(R.string.saf_files_copied_to_destination, copiedCount, destName)
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                    onComplete?.invoke(copiedCount)
                }
            }
        }
    }

    private suspend fun openRemoteOutputStream(share: NetworkShare, remotePath: String): OutputStream? {
        return when (share.type) {
            ShareType.SMB -> SmbShareClient.openOutputStream(share, remotePath)
            ShareType.FTP -> FtpShareClient.openOutputStream(share, remotePath)
            ShareType.SFTP, ShareType.SCP -> SshShareClient.openOutputStream(share, remotePath)
            ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(share, remotePath)
            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(share, remotePath)
            ShareType.DROPBOX -> DropboxShareClient.openOutputStream(share, remotePath)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(share, remotePath)
            ShareType.WEBDAV -> WebDavShareClient.openOutputStream(share, remotePath)
            ShareType.NFS -> NfsShareClient.openOutputStream(share, remotePath)
            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            ShareType.TV -> null
        }
    }

    /**
     * Exports a single local file to a chosen SAF target URI, displaying real-time telemetry.
     */
    fun exportLocalFileToSaf(
        activity: AppCompatActivity,
        localFile: File,
        targetUri: Uri,
        providerLabel: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.dialog_transfer_progress_tv else R.layout.dialog_transfer_progress
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtProgressTitle)
        val txtFiles = dialogView.findViewById<TextView>(R.id.txtProgressFiles)
        val txtCurrentFile = dialogView.findViewById<TextView>(R.id.txtProgressCurrentFile)
        val txtSize = dialogView.findViewById<TextView>(R.id.txtProgressSize)
        val txtSpeed = dialogView.findViewById<TextView>(R.id.txtProgressSpeed)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progressFile)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtTitle.text = "Uploading to $providerLabel"
        txtFiles.visibility = View.GONE
        txtCurrentFile.text = localFile.name
        txtSpeed.text = ""
        txtSize.text = activity.getString(R.string.preparing)
        progressBar.isIndeterminate = false
        progressBar.progress = 0

        val helper = TransferProgressDialogHelper(activity, dialogView, isTv)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        var transferJob: Job? = null
        var isCancelled = false

        btnCancel.setOnClickListener {
            isCancelled = true
            transferJob?.cancel()
            dialog.dismiss()
        }

        transferJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            val fileName = localFile.name
            val totalBytes = localFile.length()
            var bytesWritten = 0L
            var speedLastBytes = 0L
            var speedLastMs = SystemClock.elapsedRealtime()
            var success = false

            val buffer = ByteArray(64 * 1024)

            try {
                localFile.inputStream().use { inStream ->
                    activity.contentResolver.openOutputStream(targetUri)?.use { outStream ->
                        var bytesRead: Int
                        while (inStream.read(buffer).also { bytesRead = it } != -1) {
                            if (isCancelled) {
                                throw CancellationException("Upload cancelled by user")
                            }
                            outStream.write(buffer, 0, bytesRead)
                            bytesWritten += bytesRead

                            val nowMs = SystemClock.elapsedRealtime()
                            val deltaMs = nowMs - speedLastMs
                            if (deltaMs >= 100L) {
                                val deltaBytes = bytesWritten - speedLastBytes
                                val mbps = if (deltaBytes > 0 && deltaMs > 0) {
                                    (deltaBytes.toDouble() / (1024.0 * 1024.0)) / (deltaMs.toDouble() / 1000.0)
                                } else 0.0

                                speedLastBytes = bytesWritten
                                speedLastMs = nowMs

                                val copiedStr = Formatter.formatFileSize(activity, bytesWritten)
                                val totalStr = Formatter.formatFileSize(activity, totalBytes)
                                val pct = if (totalBytes > 0) ((bytesWritten * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

                                withContext(Dispatchers.Main) {
                                    txtSize.text = "$copiedStr / $totalStr ($pct%)"
                                    txtSpeed.text = String.format(Locale.US, "%.1f MB/s", mbps)
                                    progressBar.progress = pct
                                    helper.updateSpeed(mbps)
                                }
                            }
                        }
                    }
                }
                success = true
            } catch (e: Exception) {
                if (e !is CancellationException && !isCancelled) {
                    GoRoLog.e(TAG, "Upload failed: ${e.message}")
                }
            } finally {
                // Strictly clean up staged temporary export files or directories in cacheDir
                DocumentProviderExportHelper.cleanupTempFile(activity, localFile)
            }

            if (success && !isCancelled) {
                withContext(Dispatchers.Main) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 100
                    val totalStr = Formatter.formatFileSize(activity, bytesWritten)
                    txtSize.text = "$totalStr / $totalStr (100%)"
                    txtSpeed.text = activity.getString(R.string.action_done)
                    helper.updateSpeed(0.0)
                }
                delay(700L)
            }

            withContext(Dispatchers.Main) {
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
                if (success && !isCancelled) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.saf_upload_success, fileName, providerLabel),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                onComplete?.invoke(success && !isCancelled)
            }
        }
    }
}
