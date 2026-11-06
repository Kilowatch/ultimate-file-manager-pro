package za.kilowatch.ultimatefilemanager.viewer

import android.app.Activity
import android.media.MediaScannerConnection
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.network.*
import za.kilowatch.ultimatefilemanager.storage.SafFile
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction
import java.io.File

/**
 * Universal transfer engine for UFM Viewer and Slide Show triage actions.
 * Executes atomic Copy and Move operations across Local, SAF, Network, and Online storage
 * with full pre-flight collision detection and [TransferConflictHelper] conflict resolution.
 */
object ViewerTransferExecutor {

    private const val TAG = "ViewerTransferExecutor"

    suspend fun executeTransfer(
        activity: Activity,
        fileName: String,
        srcLocalFile: File?,
        srcShare: NetworkShare?,
        srcNetPath: String?,
        dest: ViewerTransferPreferenceManager.ViewerDestination,
        isMove: Boolean,
        onComplete: (success: Boolean, wasSkippedOrCancelled: Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val isSourceNetwork = srcShare != null && !srcNetPath.isNullOrEmpty()
            val isDestNetwork = dest.isNetwork && !dest.shareId.isNullOrEmpty()

            // Guard against self-copy/move
            if (!isSourceNetwork && !isDestNetwork) {
                val srcParent = srcLocalFile?.parentFile?.absolutePath
                val destDir = dest.localPath
                if (srcParent != null && destDir != null && File(srcParent).canonicalPath == File(destDir).canonicalPath) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, R.string.viewer_transfer_same_folder, Toast.LENGTH_SHORT).show()
                        onComplete(false, true)
                    }
                    return@withContext
                }
            } else if (isSourceNetwork && isDestNetwork) {
                if (srcShare?.id == dest.shareId) {
                    val srcDir = srcNetPath?.substringBeforeLast('/', "")?.trim('/') ?: ""
                    val targetDir = dest.netPath?.trim('/') ?: ""
                    if (srcDir == targetDir) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, R.string.viewer_transfer_same_folder, Toast.LENGTH_SHORT).show()
                            onComplete(false, true)
                        }
                        return@withContext
                    }
                }
            }

            var actualTargetName = fileName
            var chosenAction = ConflictAction.OVERWRITE

            // ── Pre-flight Conflict Check ─────────────────────────────────────
            if (!isDestNetwork) {
                val destFolder = File(dest.localPath ?: "")
                val isDestSaf = SafTreeManager.isSafPath(destFolder.absolutePath) ||
                        SafTreeManager.hasTreePermissionForPath(activity, destFolder.absolutePath)
                val targetFile = if (isDestSaf) SafFile(destFolder.absolutePath, fileName) else File(destFolder, fileName)

                val exists = if (isDestSaf) {
                    SafTreeManager.exists(activity, targetFile.absolutePath)
                } else {
                    targetFile.exists()
                }

                if (exists) {
                    val targetSize = if (isDestSaf) {
                        SafTreeManager.getFileSize(activity, targetFile.absolutePath)
                    } else {
                        targetFile.length()
                    }

                    chosenAction = TransferConflictHelper.showConflictDialog(
                        activity = activity,
                        fileName = fileName,
                        isFolder = false,
                        destSizeBytes = targetSize,
                        applyToAllRef = BooleanArray(1)
                    )

                    when (chosenAction) {
                        ConflictAction.CANCEL -> {
                            withContext(Dispatchers.Main) { onComplete(false, true) }
                            return@withContext
                        }
                        ConflictAction.SKIP -> {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(activity, activity.getString(R.string.conflict_skipped_n, 1), Toast.LENGTH_SHORT).show()
                                onComplete(false, true)
                            }
                            return@withContext
                        }
                        ConflictAction.KEEP_BOTH -> {
                            actualTargetName = findUniqueLocalFileName(destFolder, fileName, isDestSaf, activity)
                        }
                        ConflictAction.OVERWRITE -> { /* Handled atomically below */ }
                    }
                }
            } else {
                val destShare = resolveNetworkShare(activity, dest.shareId!!)
                if (destShare == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
                        onComplete(false, false)
                    }
                    return@withContext
                }

                val remoteDir = dest.netPath?.trimEnd('/') ?: ""
                val targetRemotePath = if (remoteDir.isEmpty()) "/$fileName" else "$remoteDir/$fileName"

                val remoteSize = TransferConflictHelper.getRemoteFileSize(destShare, targetRemotePath)
                if (remoteSize >= 0L) {
                    chosenAction = TransferConflictHelper.showConflictDialog(
                        activity = activity,
                        fileName = fileName,
                        isFolder = false,
                        destSizeBytes = remoteSize,
                        applyToAllRef = BooleanArray(1)
                    )

                    when (chosenAction) {
                        ConflictAction.CANCEL -> {
                            withContext(Dispatchers.Main) { onComplete(false, true) }
                            return@withContext
                        }
                        ConflictAction.SKIP -> {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(activity, activity.getString(R.string.conflict_skipped_n, 1), Toast.LENGTH_SHORT).show()
                                onComplete(false, true)
                            }
                            return@withContext
                        }
                        ConflictAction.KEEP_BOTH -> {
                            actualTargetName = findUniqueRemoteFileName(destShare, remoteDir, fileName)
                        }
                        ConflictAction.OVERWRITE -> { /* Handled atomically below */ }
                    }
                }
            }

            // ── Transfer Execution ────────────────────────────────────────────
            var transferSuccess = false

            if (!isSourceNetwork && !isDestNetwork) {
                // Local/SAF -> Local/SAF
                val src = srcLocalFile ?: return@withContext
                val destFolder = File(dest.localPath ?: "")
                val isDestSaf = SafTreeManager.isSafPath(destFolder.absolutePath) ||
                        SafTreeManager.hasTreePermissionForPath(activity, destFolder.absolutePath)
                val destTarget = if (isDestSaf) SafFile(destFolder.absolutePath, actualTargetName) else File(destFolder, actualTargetName)

                val resultFile = TransferConflictHelper.copyLocalToLocalAtomic(
                    src = src,
                    dest = destTarget,
                    action = chosenAction
                )
                transferSuccess = resultFile.exists() && resultFile.length() > 0L

                if (transferSuccess && isMove) {
                    val isSrcSaf = SafTreeManager.isSafPath(src.absolutePath) ||
                            SafTreeManager.hasTreePermissionForPath(activity, src.absolutePath)
                    if (isSrcSaf) {
                        SafTreeManager.delete(activity, src.absolutePath)
                    } else {
                        src.delete()
                    }
                    UfmApplication.indexingRepository.deleteTreeFromIndex(src.absolutePath)
                    MediaScannerConnection.scanFile(activity, arrayOf(src.absolutePath, resultFile.absolutePath), null, null)
                }

            } else if (!isSourceNetwork && isDestNetwork) {
                // Local/SAF -> Network/Online
                val src = srcLocalFile ?: return@withContext
                val destShare = resolveNetworkShare(activity, dest.shareId!!) ?: return@withContext
                val remoteDir = dest.netPath?.trimEnd('/') ?: ""
                val targetRemotePath = if (remoteDir.isEmpty()) "/$actualTargetName" else "$remoteDir/$actualTargetName"

                TransferConflictHelper.uploadLocalToNetworkAtomic(
                    src = src,
                    destShare = destShare,
                    destPath = targetRemotePath
                )
                transferSuccess = true

                if (isMove) {
                    val isSrcSaf = SafTreeManager.isSafPath(src.absolutePath) ||
                            SafTreeManager.hasTreePermissionForPath(activity, src.absolutePath)
                    if (isSrcSaf) {
                        SafTreeManager.delete(activity, src.absolutePath)
                    } else {
                        src.delete()
                    }
                    UfmApplication.indexingRepository.deleteTreeFromIndex(src.absolutePath)
                    MediaScannerConnection.scanFile(activity, arrayOf(src.absolutePath), null, null)
                }

            } else if (isSourceNetwork && !isDestNetwork) {
                // Network/Online -> Local/SAF
                val share = srcShare ?: return@withContext
                val netPath = srcNetPath ?: return@withContext
                val destFolder = File(dest.localPath ?: "")
                val isDestSaf = SafTreeManager.isSafPath(destFolder.absolutePath) ||
                        SafTreeManager.hasTreePermissionForPath(activity, destFolder.absolutePath)
                val destTarget = if (isDestSaf) SafFile(destFolder.absolutePath, actualTargetName) else File(destFolder, actualTargetName)

                val srcNetFile = NetworkFile(
                    name = fileName,
                    path = netPath,
                    size = TransferConflictHelper.getRemoteFileSize(share, netPath).coerceAtLeast(0L),
                    isDirectory = false
                )

                val resultFile = TransferConflictHelper.downloadNetworkToLocalAtomic(
                    srcShare = share,
                    srcFile = srcNetFile,
                    dest = destTarget,
                    action = chosenAction
                )
                transferSuccess = resultFile.exists() && resultFile.length() > 0L

                if (transferSuccess && isMove) {
                    deleteRemoteFile(share, netPath)
                }

            } else if (isSourceNetwork && isDestNetwork) {
                // Network/Online -> Network/Online
                val share = srcShare ?: return@withContext
                val netPath = srcNetPath ?: return@withContext
                val destShare = resolveNetworkShare(activity, dest.shareId!!) ?: return@withContext
                val remoteDir = dest.netPath?.trimEnd('/') ?: ""
                val targetRemotePath = if (remoteDir.isEmpty()) "/$actualTargetName" else "$remoteDir/$actualTargetName"

                val srcNetFile = NetworkFile(
                    name = fileName,
                    path = netPath,
                    size = TransferConflictHelper.getRemoteFileSize(share, netPath).coerceAtLeast(0L),
                    isDirectory = false
                )

                TransferConflictHelper.copyNetworkFileToNetwork(
                    srcShare = share,
                    source = srcNetFile,
                    dstShare = destShare,
                    dstPath = targetRemotePath,
                    onProgress = { _, _, _, _, _ -> },
                    fileIndex = 1,
                    totalFiles = 1,
                    cacheDir = activity.cacheDir
                )
                transferSuccess = true

                if (isMove) {
                    deleteRemoteFile(share, netPath)
                }
            }

            withContext(Dispatchers.Main) {
                if (transferSuccess) {
                    val msgRes = if (isMove) R.string.viewer_transfer_moved_success else R.string.viewer_transfer_copied_success
                    Toast.makeText(activity, activity.getString(msgRes, dest.displayName), Toast.LENGTH_SHORT).show()
                    onComplete(true, false)
                } else {
                    Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
                    onComplete(false, false)
                }
            }

        } catch (e: Exception) {
            GoRoLog.e(TAG, "Transfer failed for $fileName", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, activity.getString(R.string.error) + ": " + (e.message ?: ""), Toast.LENGTH_LONG).show()
                onComplete(false, false)
            }
        }
    }

    private fun resolveNetworkShare(activity: Activity, shareId: String): NetworkShare? {
        val repo = NetworkShareRepository.getInstance(activity)
        val share = repo.getById(shareId)
        if (share != null) return share

        // Check OnlineStorageRepository (RClone/Cloud)
        val online = OnlineStorageRepository.getInstance(activity).getById(shareId)
        return online?.toNetworkShare()
    }

    private suspend fun deleteRemoteFile(share: NetworkShare, path: String) {
        when (share.type) {
            ShareType.SMB -> SmbShareClient.deleteFile(share, path)
            ShareType.FTP -> FtpShareClient.deleteFile(share, path)
            ShareType.TV  -> TvShareClient.deleteFile(share, path)
            ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, path, false)
            ShareType.ONEDRIVE -> OnedriveShareClient.deleteFile(share, path)
            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, path)
            ShareType.DROPBOX -> DropboxShareClient.deleteFile(share, path)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, path)
            ShareType.WEBDAV -> WebDavShareClient.deleteFile(share, path)
            ShareType.NFS -> NfsShareClient.deleteFile(share, path)
            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
        }
    }

    private fun findUniqueLocalFileName(destFolder: File, name: String, isSaf: Boolean, activity: Activity): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""

        var counter = 1
        while (counter < 1000) {
            val candidate = "$base ($counter)$ext"
            val file = if (isSaf) SafFile(destFolder.absolutePath, candidate) else File(destFolder, candidate)
            val exists = if (isSaf) SafTreeManager.exists(activity, file.absolutePath) else file.exists()
            if (!exists) return candidate
            counter++
        }
        return "${base}_${System.currentTimeMillis()}$ext"
    }

    private suspend fun findUniqueRemoteFileName(share: NetworkShare, dir: String, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""

        var counter = 1
        while (counter < 1000) {
            val candidate = "$base ($counter)$ext"
            val targetRemotePath = if (dir.isEmpty()) "/$candidate" else "$dir/$candidate"
            if (TransferConflictHelper.getRemoteFileSize(share, targetRemotePath) < 0L) {
                return candidate
            }
            counter++
        }
        return "${base}_${System.currentTimeMillis()}$ext"
    }
}
