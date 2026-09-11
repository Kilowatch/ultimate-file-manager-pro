package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.storage.FileClipboard
import za.kilowatch.ultimatefilemanager.storage.FileTagsManager
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper
import za.kilowatch.ultimatefilemanager.util.FileTransferGuard
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper
import za.kilowatch.ultimatefilemanager.util.TransferManager
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Paste-into-a-network-share engine, relocated verbatim from
 * `NetworkBrowserActivity.performPaste` (recursion over the FileClipboard slots). It is
 * activity-agnostic: it runs on a [TransferManager.TransferSession], which provides the
 * retry wrapper, progress fan-out, conflict resolution and cancellation, and it never
 * touches the UI or the foreground service.
 *
 * Byte-copy primitives (the expensive, resumable step) are wrapped in
 * [TransferManager.TransferSession.withFileRetry]
 * so transient connection drops are retried (≤5 / wait-for-network / ~5-min budget) and
 * result in aborting the WHOLE transfer on exhaustion (clarifications Q5). Post-copy MOVE
 * renames/deletes are intentionally NOT retried-abort — a failed delete fails that item and
 * the batch continues, so a transient post-copy failure can never abort the whole move.
 */
object NetworkPasteEngine {

    /** Everything the recursion reads off the launching Activity, snapshotted at start. */
    data class NetworkPasteContext(
        val appContext: Context,
        /** The destination share being pasted into (never a server-mode-less raw profile). */
        val share: NetworkShare,
        /** Destination folder path (currentPath of the Activity). */
        val destPath: String,
        /** Destination folder listing (currentFiles of the Activity) for conflict checks. */
        val destChildren: List<NetworkFile>,
        /** Clipboard slots selected for this paste (already filtered by targetSlotId). */
        val slots: List<FileClipboard.Slot>,
        val targetSlotId: Long?
    )

    suspend fun pasteIntoNetwork(session: TransferManager.TransferSession, ctx: NetworkPasteContext) {
        val share = ctx.share
        val currentPath = ctx.destPath
        val currentFiles = ctx.destChildren
        val targetSlots = ctx.slots
        val appContext = ctx.appContext

        val applyToAllRef = booleanArrayOf(false)
        var globalAction: TransferConflictHelper.ConflictAction? = null

        // ── resolve a source share id → NetworkShare (repo / paired device / online) ──
        val shareCache = mutableMapOf<String, NetworkShare?>()
        fun resolveShare(shareId: String, remotePath: String = ""): NetworkShare? = shareCache.getOrPut(shareId) {
            if (shareId == share.id) {
                if (share.isServerMode && remotePath.isNotEmpty()) share.copy(remotePath = remotePath)
                else share
            } else {
                var fromRepo = NetworkShareRepository.getInstance(appContext).getById(shareId)
                if (fromRepo?.isServerMode == true && remotePath.isNotEmpty()) {
                    fromRepo = fromRepo.copy(remotePath = remotePath)
                }
                if (fromRepo != null) fromRepo
                else {
                    val dev = PairingManager.getInstance(appContext).getPairedDevice(shareId)
                    if (dev != null) NetworkShare(id = dev.deviceId, name = dev.name,
                        type = ShareType.TV, host = dev.lastIp, port = dev.lastPort, readOnly = false)
                    else {
                        val online = OnlineStorageRepository.getInstance(appContext).getById(shareId)
                        if (online != null) NetworkShare(
                            id = online.id, name = online.displayName,
                            type = when (online.provider) {
                                OnlineStorageProvider.ONEDRIVE     -> ShareType.ONEDRIVE
                                OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                                OnlineStorageProvider.DROPBOX      -> ShareType.DROPBOX
                                OnlineStorageProvider.AWS_S3       -> ShareType.AWS_S3
                                OnlineStorageProvider.IDRIVE_E2    -> ShareType.IDRIVE_E2
                                OnlineStorageProvider.WEBDAV       -> ShareType.WEBDAV
                                OnlineStorageProvider.RCLONE       -> ShareType.WEBDAV
                            },
                            host = when (online.provider) {
                                OnlineStorageProvider.RCLONE -> RCloneShareClient.RCLONE_HOST_MARKER
                                else -> if (online.isWebDavProvider) online.webDavUrl ?: "" else online.s3Endpoint ?: online.email
                            },
                            domain = online.s3Bucket ?: "",
                            remotePath = online.s3Region ?: "",
                            username = when (online.provider) {
                                OnlineStorageProvider.RCLONE -> online.id
                                else -> if (online.isWebDavProvider) online.webDavUsername ?: "" else online.s3AccessKey ?: online.email
                            },
                            password = if (online.isWebDavProvider) online.webDavPassword ?: "" else online.s3SecretKey ?: "",
                            readOnly = false
                        )
                        else null
                    }
                }
            }
        }

        fun stripSharePrefix(path: String): String {
            val clean = path.trimStart('/')
            if (!share.isServerMode || share.remotePath.isEmpty()) return clean
            val prefix = share.remotePath.trimStart('/')
            return when {
                clean.startsWith("$prefix/", ignoreCase = true) -> clean.substring(prefix.length + 1)
                clean.equals(prefix, ignoreCase = true)         -> ""
                else                                           -> clean
            }
        }

        val conflictMutex = Mutex()
        suspend fun askConflict(
            name: String,
            isFolder: Boolean,
            remoteSize: Long
        ): TransferConflictHelper.ConflictAction = conflictMutex.withLock {
            val existing = globalAction
            if (existing != null) return existing
            val action = session.resolveConflict(name, isFolder, remoteSize, applyToAllRef)
            if (applyToAllRef[0]) globalAction = action
            return action
        }

            // 1. Calculate total files
            var totalFiles = 0
            for (slot in targetSlots) {
                for (item in slot.items) {
                    when (item) {
                        is FileClipboard.ClipItem.Local -> {
                            totalFiles += if (item.file.isDirectory) TransferConflictHelper.countLocalFiles(item.file) else 1
                        }
                        is FileClipboard.ClipItem.Remote -> {
                            val srcShare = resolveShare(item.sourceShareId, item.sourceRemotePath)
                            if (srcShare != null) {
                                totalFiles += if (item.file.isDirectory) TransferConflictHelper.countNetworkFiles(srcShare, item.file.path) else 1
                            } else {
                                totalFiles++
                            }
                        }
                    }
                }
            }

            val fileIndexCounter = AtomicInteger(0)
            val concurrency = if (share.type == ShareType.FTP || share.type == ShareType.SFTP) share.effectiveThreads(appContext).coerceIn(1, 8) else 1
            val semaphore = Semaphore(concurrency)

            suspend fun processNetItem(
                srcShare: NetworkShare,
                source: NetworkFile,
                op: FileClipboard.Operation,
                currentDest: String,
                destChildren: List<NetworkFile>
            ) {
                session.checkCancelled()
                val itemName = source.name
                val cleanDest = stripSharePrefix(currentDest)
                val targetPath = if (cleanDest.isEmpty() || cleanDest == "/") itemName else "${cleanDest.trimEnd('/')}/$itemName"

                if (source.isDirectory) {
                    val hasConflict = TransferConflictHelper.networkFileExists(itemName, destChildren)
                    var effectiveDest = targetPath
                    if (hasConflict) {
                        val resolvedAction = askConflict(itemName, true, -1L)
                        when (resolvedAction) {
                            TransferConflictHelper.ConflictAction.CANCEL -> throw CancellationException()
                            TransferConflictHelper.ConflictAction.SKIP -> { session.noteSuccess(); return }
                            TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                effectiveDest = TransferConflictHelper.uniqueNetworkPath(cleanDest, itemName, destChildren, isFolder = true)
                            }
                            TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                effectiveDest = targetPath
                            }
                        }
                    }

                    if (srcShare.id == share.id && op == FileClipboard.Operation.MOVE && (!hasConflict || effectiveDest != targetPath)) {
                        session.reportProgress(itemName, 0, 0, fileIndexCounter.get(), totalFiles)
                            when (share.type) {
                                ShareType.SMB          -> SmbShareClient.rename(share, source.path, effectiveDest)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(share, source.path, effectiveDest)
                                ShareType.FTP          -> FtpShareClient.rename(share, source.path, effectiveDest)
                                ShareType.NFS          -> NfsShareClient.rename(share, source.path, effectiveDest)
                                ShareType.TV           -> TvShareClient.rename(share, source.path, effectiveDest)
                                ShareType.ONEDRIVE     -> OnedriveShareClient.rename(share, source.path, effectiveDest)
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, source.path, effectiveDest)
                                ShareType.DROPBOX      -> DropboxShareClient.rename(share, source.path, effectiveDest)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, source.path, effectiveDest)
                                ShareType.WEBDAV       -> WebDavShareClient.rename(share, source.path, effectiveDest)
                                ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                        FileTagsManager.onPathMoved(appContext, source.path, effectiveDest)
                        session.noteSuccess()
                        return
                    }

                    try {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.mkdir(share, effectiveDest)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, effectiveDest)
                            ShareType.FTP          -> FtpShareClient.mkdir(share, effectiveDest)
                            ShareType.NFS          -> NfsShareClient.mkdir(share, effectiveDest)
                            ShareType.TV           -> TvShareClient.mkdir(share, effectiveDest)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.mkdir(share, effectiveDest)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, effectiveDest)
                            ShareType.DROPBOX      -> DropboxShareClient.mkdir(share, effectiveDest)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, effectiveDest)
                            ShareType.WEBDAV       -> WebDavShareClient.mkdir(share, effectiveDest)
                            ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                    } catch (_: Exception) {}

                    val children = when (srcShare.type) {
                        ShareType.SMB          -> SmbShareClient.listFiles(srcShare, source.path)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(srcShare, source.path)
                        ShareType.FTP          -> FtpShareClient.listFiles(srcShare, source.path)
                        ShareType.NFS          -> NfsShareClient.listFiles(srcShare, source.path)
                        ShareType.TV           -> TvShareClient.listFiles(srcShare, source.path)
                        ShareType.ONEDRIVE     -> OnedriveShareClient.listFiles(srcShare, source.path)
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(srcShare, source.path)
                        ShareType.DROPBOX      -> DropboxShareClient.listFiles(srcShare, source.path)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(srcShare, source.path)
                        ShareType.WEBDAV       -> WebDavShareClient.listFiles(srcShare, source.path)
                        ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                    }

                    val effectiveDestChildren = try {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.listFiles(share, effectiveDest)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, effectiveDest)
                            ShareType.FTP          -> FtpShareClient.listFiles(share, effectiveDest)
                            ShareType.NFS          -> NfsShareClient.listFiles(share, effectiveDest)
                            ShareType.TV           -> TvShareClient.listFiles(share, effectiveDest)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.listFiles(share, effectiveDest)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, effectiveDest)
                            ShareType.DROPBOX      -> DropboxShareClient.listFiles(share, effectiveDest)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, effectiveDest)
                            ShareType.WEBDAV       -> WebDavShareClient.listFiles(share, effectiveDest)
                            ShareType.DLNA         -> emptyList()
                        }
                    } catch (_: Exception) { emptyList<NetworkFile>() }

                    if (concurrency > 1) {
                        coroutineScope {
                            for (child in children) {
                                session.checkCancelled()
                                if (child.isDirectory) {
                                    processNetItem(srcShare, child, op, effectiveDest, effectiveDestChildren)
                                } else {
                                    launch(Dispatchers.IO) {
                                        semaphore.withPermit {
                                            session.checkCancelled()
                                            try {
                                                processNetItem(srcShare, child, op, effectiveDest, effectiveDestChildren)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                session.checkCancelled()
                                                session.noteFailure(e.message)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        for (child in children) {
                            session.checkCancelled()
                            try {
                                processNetItem(srcShare, child, op, effectiveDest, effectiveDestChildren)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                session.checkCancelled()
                                session.noteFailure(e.message)
                            }
                        }
                    }

                    if (op == FileClipboard.Operation.MOVE) {
                        try {
                                when (srcShare.type) {
                                    ShareType.SMB          -> SmbShareClient.deleteDir(srcShare, source.path)
                                    ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(srcShare, source.path, isDirectory = true)
                                    ShareType.FTP          -> FtpShareClient.deleteDir(srcShare, source.path)
                                    ShareType.NFS          -> NfsShareClient.deleteDir(srcShare, source.path)
                                    ShareType.TV           -> TvShareClient.deleteDir(srcShare, source.path)
                                    ShareType.ONEDRIVE     -> OnedriveShareClient.deleteFile(srcShare, source.path)
                                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(srcShare, source.path)
                                    ShareType.DROPBOX      -> DropboxShareClient.deleteFile(srcShare, source.path)
                                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(srcShare, source.path)
                                    ShareType.WEBDAV       -> WebDavShareClient.deleteFile(srcShare, source.path)
                                    ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                                }
                        } catch (_: Exception) {}
                        FileTagsManager.onPathMoved(appContext, source.path, effectiveDest)
                    } else {
                        FileTagsManager.onPathCopied(appContext, source.path, effectiveDest)
                    }
                } else {
                    val currentIndex = fileIndexCounter.incrementAndGet()
                    val hasConflict = TransferConflictHelper.networkFileExists(itemName, destChildren)
                    val resolvedAction = if (hasConflict) {
                        val remoteSize = TransferConflictHelper.getRemoteFileSize(share, targetPath)
                        askConflict(itemName, false, remoteSize)
                    } else TransferConflictHelper.ConflictAction.KEEP_BOTH

                    if (resolvedAction == TransferConflictHelper.ConflictAction.CANCEL) throw CancellationException()
                    if (resolvedAction == TransferConflictHelper.ConflictAction.SKIP) { session.noteSuccess(); return }

                    val finalPath = if (resolvedAction == TransferConflictHelper.ConflictAction.KEEP_BOTH)
                        TransferConflictHelper.uniqueNetworkPath(cleanDest, itemName, destChildren)
                    else targetPath

                    session.reportProgress(itemName, 0, source.size, currentIndex, totalFiles)

                    if (srcShare.id == share.id && op == FileClipboard.Operation.MOVE && (!hasConflict || finalPath != targetPath)) {
                            when (share.type) {
                                ShareType.SMB          -> SmbShareClient.rename(share, source.path, finalPath)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(share, source.path, finalPath)
                                ShareType.FTP          -> FtpShareClient.rename(share, source.path, finalPath)
                                ShareType.NFS          -> NfsShareClient.rename(share, source.path, finalPath)
                                ShareType.TV           -> TvShareClient.rename(share, source.path, finalPath)
                                ShareType.ONEDRIVE     -> OnedriveShareClient.rename(share, source.path, finalPath)
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, source.path, finalPath)
                                ShareType.DROPBOX      -> DropboxShareClient.rename(share, source.path, finalPath)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, source.path, finalPath)
                                ShareType.WEBDAV       -> WebDavShareClient.rename(share, source.path, finalPath)
                                ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                        FileTagsManager.onPathMoved(appContext, source.path, finalPath)
                        session.noteSuccess()
                        return
                    }

                    var connToUnregister: AutoCloseable? = null
                    try {
                        session.withFileRetry(itemName) {
                            TransferConflictHelper.copyNetworkFileToNetwork(
                                srcShare, source, share, finalPath,
                                { _, c, t, _, _ -> session.reportProgress(itemName, c, t, currentIndex, totalFiles) },
                                currentIndex, totalFiles,
                                { conn ->
                                    connToUnregister = conn
                                    session.registerConnection(conn)
                                },
                                appContext.cacheDir
                            )
                        }
                    } finally {
                        connToUnregister?.let { session.unregisterConnection(it) }
                    }

                    if (op == FileClipboard.Operation.MOVE) {
                        val remoteDestSize = TransferConflictHelper.getRemoteFileSize(share, finalPath)
                        if (FileTransferGuard.requireSourceSafeToDelete(remoteDestSize, source.size, source.name)) {
                                when (srcShare.type) {
                                    ShareType.SMB          -> SmbShareClient.deleteFile(srcShare, source.path)
                                    ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(srcShare, source.path, isDirectory = false)
                                    ShareType.FTP          -> FtpShareClient.deleteFile(srcShare, source.path)
                                    ShareType.NFS          -> NfsShareClient.deleteFile(srcShare, source.path)
                                    ShareType.TV           -> TvShareClient.deleteFile(srcShare, source.path)
                                    ShareType.ONEDRIVE     -> OnedriveShareClient.deleteFile(srcShare, source.path)
                                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(srcShare, source.path)
                                    ShareType.DROPBOX      -> DropboxShareClient.deleteFile(srcShare, source.path)
                                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(srcShare, source.path)
                                    ShareType.WEBDAV       -> WebDavShareClient.deleteFile(srcShare, source.path)
                                    ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                                }
                        }
                        FileTagsManager.onPathMoved(appContext, source.path, finalPath)
                    } else {
                        FileTagsManager.onPathCopied(appContext, source.path, finalPath)
                    }
                    session.noteSuccess()
                }
            }

            suspend fun processLocalItem(
                source: File,
                op: FileClipboard.Operation,
                currentDest: String,
                destChildren: List<NetworkFile>
            ) {
                session.checkCancelled()
                val isSrcSaf = source is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                    SafTreeManager.isSafPath(source.absolutePath) ||
                    SafTreeManager.hasTreePermissionForPath(appContext, source.absolutePath)
                val itemName = source.name
                val cleanDest = stripSharePrefix(currentDest)
                val targetPath = if (cleanDest.isEmpty() || cleanDest == "/") itemName else "${cleanDest.trimEnd('/')}/$itemName"

                if (source.isDirectory) {
                    val hasConflict = TransferConflictHelper.networkFileExists(itemName, destChildren)
                    var effectiveDest = targetPath
                    if (hasConflict) {
                        val resolvedAction = askConflict(itemName, true, -1L)
                        when (resolvedAction) {
                            TransferConflictHelper.ConflictAction.CANCEL -> throw CancellationException()
                            TransferConflictHelper.ConflictAction.SKIP -> { session.noteSuccess(); return }
                            TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                effectiveDest = TransferConflictHelper.uniqueNetworkPath(cleanDest, itemName, destChildren, isFolder = true)
                            }
                            TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                effectiveDest = targetPath
                            }
                        }
                    }

                    try {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.mkdir(share, effectiveDest)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, effectiveDest)
                            ShareType.FTP          -> FtpShareClient.mkdir(share, effectiveDest)
                            ShareType.NFS          -> NfsShareClient.mkdir(share, effectiveDest)
                            ShareType.TV           -> TvShareClient.mkdir(share, effectiveDest)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.mkdir(share, effectiveDest)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, effectiveDest)
                            ShareType.DROPBOX      -> DropboxShareClient.mkdir(share, effectiveDest)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, effectiveDest)
                            ShareType.WEBDAV       -> WebDavShareClient.mkdir(share, effectiveDest)
                            ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                    } catch (_: Exception) {}

                    val effectiveDestChildren = try {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.listFiles(share, effectiveDest)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, effectiveDest)
                            ShareType.FTP          -> FtpShareClient.listFiles(share, effectiveDest)
                            ShareType.NFS          -> NfsShareClient.listFiles(share, effectiveDest)
                            ShareType.TV           -> TvShareClient.listFiles(share, effectiveDest)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.listFiles(share, effectiveDest)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, effectiveDest)
                            ShareType.DROPBOX      -> DropboxShareClient.listFiles(share, effectiveDest)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, effectiveDest)
                            ShareType.WEBDAV       -> WebDavShareClient.listFiles(share, effectiveDest)
                            ShareType.DLNA         -> emptyList()
                        }
                    } catch (_: Exception) { emptyList<NetworkFile>() }

                    val children = if (isSrcSaf) {
                        SafTreeManager.listFiles(appContext, source.absolutePath)
                    } else {
                        source.listFiles()?.toList()
                    }
                    if (children != null) {
                        if (concurrency > 1) {
                            coroutineScope {
                                for (child in children) {
                                    session.checkCancelled()
                                    if (child.isDirectory) {
                                        processLocalItem(child, op, effectiveDest, effectiveDestChildren)
                                    } else {
                                        launch(Dispatchers.IO) {
                                            semaphore.withPermit {
                                                session.checkCancelled()
                                                try {
                                                    processLocalItem(child, op, effectiveDest, effectiveDestChildren)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    session.checkCancelled()
                                                    session.noteFailure(e.message)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            for (child in children) {
                                session.checkCancelled()
                                try {
                                    processLocalItem(child, op, effectiveDest, effectiveDestChildren)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    session.checkCancelled()
                                    session.noteFailure(e.message)
                                }
                            }
                        }
                    }
                    if (op == FileClipboard.Operation.MOVE || op == FileClipboard.Operation.EXTRACT) {
                        try {
                            if (isSrcSaf) {
                                SafTreeManager.deleteRecursively(appContext, source.absolutePath)
                            } else if (ShizukuShellWrapper.canUseShizukuForPath(source.absolutePath)) {
                                ShizukuShellWrapper.delete(source.absolutePath)
                            } else {
                                source.delete()
                            }
                            UfmApplication.indexingRepository.deleteTreeFromIndex(source.absolutePath)
                        } catch (_: Exception) {}
                        FileTagsManager.onPathMoved(appContext, source.absolutePath, effectiveDest)
                    } else {
                        FileTagsManager.onPathCopied(appContext, source.absolutePath, effectiveDest)
                    }
                } else {
                    val hasConflict = TransferConflictHelper.networkFileExists(itemName, destChildren)
                    val resolvedAction = if (hasConflict) {
                        val remoteSize = TransferConflictHelper.getRemoteFileSize(share, targetPath)
                        askConflict(itemName, false, remoteSize)
                    } else TransferConflictHelper.ConflictAction.KEEP_BOTH

                    if (resolvedAction == TransferConflictHelper.ConflictAction.CANCEL) throw CancellationException()
                    if (resolvedAction == TransferConflictHelper.ConflictAction.SKIP) { session.noteSuccess(); return }

                    val finalPath = if (resolvedAction == TransferConflictHelper.ConflictAction.KEEP_BOTH)
                        TransferConflictHelper.uniqueNetworkPath(cleanDest, itemName, destChildren)
                    else targetPath

                    val currentIndex = fileIndexCounter.incrementAndGet()
                    val sourceSize = if (isSrcSaf) {
                        SafTreeManager.getFileSize(appContext, source.absolutePath)
                    } else {
                        source.length()
                    }
                    try {
                        session.reportProgress(itemName, 0, sourceSize, currentIndex, totalFiles)
                        var connToUnregister: AutoCloseable? = null
                        try {
                            session.withFileRetry(itemName) {
                                TransferConflictHelper.uploadLocalToNetworkAtomic(
                                    source, share, finalPath,
                                    { c, t -> session.reportProgress(itemName, c, t, currentIndex, totalFiles) },
                                    { conn ->
                                        connToUnregister = conn
                                        session.registerConnection(conn)
                                    }
                                )
                            }
                        } finally {
                            connToUnregister?.let { session.unregisterConnection(it) }
                        }
                        if (op == FileClipboard.Operation.MOVE || op == FileClipboard.Operation.EXTRACT) {
                            val remoteSize = TransferConflictHelper.getRemoteFileSize(share, finalPath)
                            val isSafeToDelete = if (remoteSize <= 0L && sourceSize > 0L) {
                                true
                            } else {
                                FileTransferGuard.requireSourceSafeToDelete(remoteSize, sourceSize, source.name)
                            }
                            if (isSafeToDelete) {
                                try {
                                    if (isSrcSaf) {
                                        val deleted = SafTreeManager.deleteRecursively(appContext, source.absolutePath)
                                        if (!deleted) {
                                            SafTreeManager.delete(appContext, source.absolutePath)
                                        }
                                    } else if (ShizukuShellWrapper.canUseShizukuForPath(source.absolutePath)) {
                                        ShizukuShellWrapper.delete(source.absolutePath)
                                    } else {
                                        source.delete()
                                    }
                                    UfmApplication.indexingRepository.deleteTreeFromIndex(source.absolutePath)
                                } catch (_: Exception) {}
                            }
                            FileTagsManager.onPathMoved(appContext, source.absolutePath, finalPath)
                        } else {
                            FileTagsManager.onPathCopied(appContext, source.absolutePath, finalPath)
                        }
                        session.noteSuccess()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        session.checkCancelled()
                        session.noteFailure(e.message)
                    }
                }
            }

            // Process target slots
            if (concurrency > 1) {
                coroutineScope {
                    for (slot in targetSlots) {
                        for (item in slot.items) {
                            session.checkCancelled()
                            when (item) {
                                is FileClipboard.ClipItem.Local -> {
                                    if (item.file.isDirectory) {
                                        processLocalItem(item.file, item.operation, currentPath, currentFiles)
                                    } else {
                                        launch(Dispatchers.IO) {
                                            semaphore.withPermit {
                                                session.checkCancelled()
                                                try {
                                                    processLocalItem(item.file, item.operation, currentPath, currentFiles)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    session.checkCancelled()
                                                    session.noteFailure(e.message)
                                                }
                                            }
                                        }
                                    }
                                }
                                is FileClipboard.ClipItem.Remote -> {
                                    val srcShare = resolveShare(item.sourceShareId, item.sourceRemotePath)
                                    if (srcShare != null) {
                                        if (item.file.isDirectory) {
                                            processNetItem(srcShare, item.file, item.operation, currentPath, currentFiles)
                                        } else {
                                            launch(Dispatchers.IO) {
                                                semaphore.withPermit {
                                                    session.checkCancelled()
                                                    try {
                                                        processNetItem(srcShare, item.file, item.operation, currentPath, currentFiles)
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        session.checkCancelled()
                                                        session.noteFailure(e.message)
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        session.noteFailure(null)
                                    }
                                }
                            }
                        }
                        FileClipboard.removeSlot(slot.id)
                    }
                }
            } else {
                for (slot in targetSlots) {
                    for (item in slot.items) {
                        session.checkCancelled()
                        when (item) {
                            is FileClipboard.ClipItem.Local -> {
                                try {
                                    processLocalItem(item.file, item.operation, currentPath, currentFiles)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    session.checkCancelled()
                                    session.noteFailure(e.message)
                                }
                            }
                            is FileClipboard.ClipItem.Remote -> {
                                val srcShare = resolveShare(item.sourceShareId, item.sourceRemotePath)
                                if (srcShare != null) {
                                    try {
                                        processNetItem(srcShare, item.file, item.operation, currentPath, currentFiles)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        session.checkCancelled()
                                        session.noteFailure(e.message)
                                    }
                                } else {
                                    session.noteFailure(null)
                                }
                            }
                        }
                    }
                    FileClipboard.removeSlot(slot.id)
                }
            }

            if (ctx.targetSlotId == null) {
                FileClipboard.clear()
            }
    }
}
