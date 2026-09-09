package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor
import za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.PairingManager
import za.kilowatch.ultimatefilemanager.network.RCloneShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.TvShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.util.FileTransferGuard
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper
import za.kilowatch.ultimatefilemanager.util.TransferManager
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Paste-into-LOCAL-storage engine, relocated verbatim from `FileBrowserActivity.performPaste`
 * (local→local incl. SAF/Root/Shizuku, and network/cloud→local downloads). It is
 * activity-agnostic: it runs on a [TransferManager.TransferSession] and never touches UI,
 * the foreground service, or the Activity. Byte-copy primitives are wrapped in
 * [TransferManager.TransferSession.withFileRetry].
 */
object LocalPasteEngine {

    /** Everything the recursion reads off the launching Activity, snapshotted at start. */
    data class LocalPasteContext(
        val appContext: Context,
        /** The folder visible in the Activity — destination for network/cloud items. */
        val currentDir: File,
        /** Actual paste destination root (quickTransferDestDir ?: currentDir). */
        val effectiveDestDir: File,
        val storageId: String,
        val storageType: String,
        val slots: List<FileClipboard.Slot>,
        val targetSlotId: Long?
    )

    suspend fun pasteIntoLocal(session: TransferManager.TransferSession, ctx: LocalPasteContext) {
        val appContext = ctx.appContext
        val currentDir = ctx.currentDir
        val effectiveDestDir = ctx.effectiveDestDir
        val storageId = ctx.storageId
        val storageType = ctx.storageType

        val db = UfmIndexingDatabase.getInstance(appContext)
        val dao = db.fileIndexDao()
        val metadataExtractor = MetadataExtractor(appContext)
        val pendingIndices = mutableListOf<FileIndex>()

        suspend fun flushIndices() {
            if (pendingIndices.isNotEmpty()) {
                dao.insertAll(pendingIndices.toList())
                pendingIndices.clear()
            }
        }

        val applyToAllRef = booleanArrayOf(false)
        var globalAction: TransferConflictHelper.ConflictAction? = null

        suspend fun askConflict(
            name: String,
            isFolder: Boolean,
            destSize: Long
        ): TransferConflictHelper.ConflictAction {
            val existing = globalAction
            if (existing != null) return existing
            val action = session.resolveConflict(name, isFolder, destSize, applyToAllRef)
            if (applyToAllRef[0]) globalAction = action
            return action
        }

        fun isSaf(path: File): Boolean =
            path is SafFile || SafTreeManager.isSafPath(path.absolutePath) ||
                SafTreeManager.hasTreePermissionForPath(appContext, path.absolutePath)

        fun isRoot(path: File): Boolean =
            path is RootFile || RootShellWrapper.isRootPath(path.absolutePath)

        // ── Total file count ────────────────────────────────────────────────
        var totalFiles = 0
        for (slot in ctx.slots) {
            for (item in slot.items) {
                when (item) {
                    is FileClipboard.ClipItem.Local -> {
                        if (item.file.isDirectory) totalFiles += TransferConflictHelper.countLocalFiles(item.file)
                        else totalFiles++
                    }
                    is FileClipboard.ClipItem.Remote -> {
                        totalFiles++
                    }
                }
            }
        }
        var fileIndex = 0

        suspend fun processLocalItem(source: File, destBase: File, operation: FileClipboard.Operation) {
            session.checkCancelled()
            val isSrcSaf = isSaf(source)
            val isSrcRoot = isRoot(source)
            val isDestSaf = isSaf(destBase)
            val isDestRoot = isRoot(destBase)

            if (source.isDirectory) {
                val hasConflict = TransferConflictHelper.localFileExists(
                    destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                )

                var effectiveDest = destBase
                if (hasConflict) {
                    when (askConflict(source.name, true, -1L)) {
                        TransferConflictHelper.ConflictAction.CANCEL -> throw CancellationException()
                        TransferConflictHelper.ConflictAction.SKIP -> { session.noteSuccess(); return }
                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                            effectiveDest = TransferConflictHelper.uniqueLocalFolder(
                                destBase.parentFile ?: effectiveDestDir, source.name, appContext
                            )
                        }
                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                            effectiveDest = destBase
                        }
                    }
                }

                val isEffSaf = isSaf(effectiveDest)
                val isEffRoot = isRoot(effectiveDest)
                try {
                    if (isEffSaf) {
                        if (!SafTreeManager.exists(appContext, effectiveDest.absolutePath)) {
                            SafTreeManager.mkdir(appContext, effectiveDest.parent ?: effectiveDestDir.absolutePath, effectiveDest.name)
                        }
                    } else if (isEffRoot) {
                        if (!RootShellWrapper.exists(effectiveDest.absolutePath)) {
                            RootShellWrapper.mkdir(effectiveDest.absolutePath)
                        }
                    } else if (ShizukuShellWrapper.canUseShizukuForPath(effectiveDest.absolutePath)) {
                        if (!ShizukuShellWrapper.exists(effectiveDest.absolutePath)) {
                            ShizukuShellWrapper.mkdir(effectiveDest.absolutePath)
                        }
                    } else {
                        if (!effectiveDest.exists()) effectiveDest.mkdirs()
                    }
                    if (!isEffRoot && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                        pendingIndices.add(metadataExtractor.extractMetadata(effectiveDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                        if (pendingIndices.size >= 50) flushIndices()
                    }
                } catch (_: Exception) {}

                val children = if (isSrcSaf) {
                    SafTreeManager.listFiles(appContext, source.absolutePath)
                } else if (isSrcRoot) {
                    RootShellWrapper.listFiles(source.absolutePath)
                } else {
                    source.listFiles()?.toList()
                }
                if (children != null) {
                    for (child in children) {
                        session.checkCancelled()
                        try {
                            val childDest = if (isEffSaf) {
                                SafFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                            } else if (isEffRoot) {
                                RootFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                            } else {
                                File(effectiveDest, child.name)
                            }
                            processLocalItem(child, childDest, operation)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            session.checkCancelled()
                            session.noteFailure(e.message)
                        }
                    }
                }
                if (operation == FileClipboard.Operation.MOVE || operation == FileClipboard.Operation.EXTRACT) {
                    try {
                        if (isSrcSaf) {
                            SafTreeManager.deleteRecursively(appContext, source.absolutePath)
                        } else if (isSrcRoot) {
                            RootShellWrapper.delete(source.absolutePath)
                        } else if (ShizukuShellWrapper.canUseShizukuForPath(source.absolutePath)) {
                            ShizukuShellWrapper.delete(source.absolutePath)
                        } else {
                            source.delete()
                        }
                        if (!isSrcRoot) {
                            UfmApplication.indexingRepository.deleteTreeFromIndex(source.absolutePath)
                        }
                    } catch (_: Exception) {}
                    FileTagsManager.onPathMoved(appContext, source.absolutePath, effectiveDest.absolutePath)
                } else {
                    FileTagsManager.onPathCopied(appContext, source.absolutePath, effectiveDest.absolutePath)
                }
            } else {
                fileIndex++
                val hasConflict = TransferConflictHelper.localFileExists(
                    destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                )
                val resolvedAction = if (hasConflict) {
                    val destSize = TransferConflictHelper.localFileSize(
                        destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                    )
                    askConflict(source.name, false, destSize)
                } else TransferConflictHelper.ConflictAction.KEEP_BOTH

                if (resolvedAction == TransferConflictHelper.ConflictAction.CANCEL) throw CancellationException()
                if (resolvedAction == TransferConflictHelper.ConflictAction.SKIP) { session.noteSuccess(); return }

                val sourceSize = if (isSrcSaf) SafTreeManager.getFileSize(appContext, source.absolutePath)
                                 else if (isSrcRoot) RootShellWrapper.getFileSize(source.absolutePath)
                                 else source.length()
                session.reportProgress(source.name, 0, sourceSize, fileIndex, totalFiles)
                val finalDest = if (resolvedAction == TransferConflictHelper.ConflictAction.KEEP_BOTH)
                    TransferConflictHelper.uniqueLocalFile(destBase.parentFile ?: effectiveDestDir, destBase.name, appContext)
                else destBase
                val writtenDest = session.withFileRetry(source.name) {
                    TransferConflictHelper.copyLocalToLocalAtomic(source, finalDest, resolvedAction) { c, t ->
                        session.reportProgress(source.name, c, t, fileIndex, totalFiles)
                    }
                }

                val isEffDestRoot = isRoot(writtenDest)
                if (!isEffDestRoot && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                    pendingIndices.add(metadataExtractor.extractMetadata(writtenDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                    if (pendingIndices.size >= 50) flushIndices()
                }

                if (operation == FileClipboard.Operation.MOVE || operation == FileClipboard.Operation.EXTRACT) {
                    val isEffSafDest = isSaf(writtenDest)
                    val writtenSize = if (isEffSafDest) SafTreeManager.getFileSize(appContext, writtenDest.absolutePath)
                                      else if (isEffDestRoot) RootShellWrapper.getFileSize(writtenDest.absolutePath)
                                      else writtenDest.length()
                    val isSafeToDelete = if (isEffSafDest && writtenSize <= 0L) {
                        SafTreeManager.exists(appContext, writtenDest.absolutePath)
                    } else if (isEffDestRoot && writtenSize <= 0L) {
                        RootShellWrapper.exists(writtenDest.absolutePath)
                    } else {
                        FileTransferGuard.requireSourceSafeToDelete(writtenSize, sourceSize, source.name)
                    }
                    if (isSafeToDelete) {
                        if (isSrcSaf) {
                            SafTreeManager.delete(appContext, source.absolutePath)
                        } else if (isSrcRoot) {
                            RootShellWrapper.delete(source.absolutePath)
                        } else if (ShizukuShellWrapper.canUseShizukuForPath(source.absolutePath)) {
                            ShizukuShellWrapper.delete(source.absolutePath)
                        } else {
                            source.delete()
                        }
                        if (!isSrcRoot) {
                            UfmApplication.indexingRepository.deleteTreeFromIndex(source.absolutePath)
                        }
                    }
                    FileTagsManager.onPathMoved(appContext, source.absolutePath, writtenDest.absolutePath)
                } else {
                    FileTagsManager.onPathCopied(appContext, source.absolutePath, writtenDest.absolutePath)
                }
                session.noteSuccess()
            }
        }

        suspend fun processNetItem(source: NetworkFile, destBase: File, share: NetworkShare, operation: FileClipboard.Operation) {
            session.checkCancelled()
            val isDestSaf = isSaf(destBase)
            val isDestRoot = isRoot(destBase)

            if (source.isDirectory) {
                val hasConflict = TransferConflictHelper.localFileExists(
                    destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                )

                var effectiveDest = destBase
                if (hasConflict) {
                    when (askConflict(source.name, true, -1L)) {
                        TransferConflictHelper.ConflictAction.CANCEL -> throw CancellationException()
                        TransferConflictHelper.ConflictAction.SKIP -> { session.noteSuccess(); return }
                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                            effectiveDest = TransferConflictHelper.uniqueLocalFolder(
                                destBase.parentFile ?: effectiveDestDir, source.name, appContext
                            )
                        }
                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                            effectiveDest = destBase
                        }
                    }
                }

                val isEffSaf = isSaf(effectiveDest)
                val isEffRoot = isRoot(effectiveDest)
                try {
                    if (isEffSaf) {
                        if (!SafTreeManager.exists(appContext, effectiveDest.absolutePath)) {
                            SafTreeManager.mkdir(appContext, effectiveDest.parent ?: effectiveDestDir.absolutePath, effectiveDest.name)
                        }
                    } else if (isEffRoot) {
                        if (!RootShellWrapper.exists(effectiveDest.absolutePath)) {
                            RootShellWrapper.mkdir(effectiveDest.absolutePath)
                        }
                    } else if (ShizukuShellWrapper.canUseShizukuForPath(effectiveDest.absolutePath)) {
                        if (!ShizukuShellWrapper.exists(effectiveDest.absolutePath)) {
                            ShizukuShellWrapper.mkdir(effectiveDest.absolutePath)
                        }
                    } else {
                        if (!effectiveDest.exists()) effectiveDest.mkdirs()
                    }
                    if (!isEffRoot && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                        pendingIndices.add(metadataExtractor.extractMetadata(effectiveDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                        if (pendingIndices.size >= 50) flushIndices()
                    }
                } catch (_: Exception) {}

                val children = when (share.type) {
                    ShareType.SMB          -> SmbShareClient.listFiles(share, source.path)
                    ShareType.FTP          -> FtpShareClient.listFiles(share, source.path)
                    ShareType.TV           -> TvShareClient.listFiles(share, source.path)
                    ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, source.path)
                    ShareType.ONEDRIVE     -> OnedriveShareClient.listFiles(share, source.path)
                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, source.path)
                    ShareType.DROPBOX      -> DropboxShareClient.listFiles(share, source.path)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, source.path)
                    ShareType.WEBDAV       -> WebDavShareClient.listFiles(share, source.path)
                    ShareType.NFS          -> NfsShareClient.listFiles(share, source.path)
                    ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                }
                for (child in children) {
                    session.checkCancelled()
                    try {
                        val childDest = if (isEffSaf) {
                            SafFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                        } else if (isEffRoot) {
                            RootFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                        } else {
                            File(effectiveDest, child.name)
                        }
                        processNetItem(child, childDest, share, operation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        session.checkCancelled()
                        session.noteFailure(e.message)
                    }
                }
                if (operation == FileClipboard.Operation.MOVE) {
                    try {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.deleteDir(share, source.path)
                            ShareType.FTP          -> FtpShareClient.deleteDir(share, source.path)
                            ShareType.TV           -> TvShareClient.deleteDir(share, source.path)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, source.path, true)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.deleteFile(share, source.path)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, source.path)
                            ShareType.DROPBOX      -> DropboxShareClient.deleteFile(share, source.path)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, source.path)
                            ShareType.WEBDAV       -> WebDavShareClient.deleteFile(share, source.path)
                            ShareType.NFS          -> NfsShareClient.deleteDir(share, source.path)
                            ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                    } catch (_: Exception) {}
                    FileTagsManager.onPathMoved(appContext, source.path, effectiveDest.absolutePath)
                } else {
                    FileTagsManager.onPathCopied(appContext, source.path, effectiveDest.absolutePath)
                }
            } else {
                fileIndex++
                val hasConflict = TransferConflictHelper.localFileExists(
                    destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                )
                val resolvedAction = if (hasConflict) {
                    val destSize = TransferConflictHelper.localFileSize(
                        destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                    )
                    askConflict(source.name, false, destSize)
                } else TransferConflictHelper.ConflictAction.KEEP_BOTH

                if (resolvedAction == TransferConflictHelper.ConflictAction.CANCEL) throw CancellationException()
                if (resolvedAction == TransferConflictHelper.ConflictAction.SKIP) { session.noteSuccess(); return }

                session.reportProgress(source.name, 0, source.size, fileIndex, totalFiles)
                val writtenDest = session.withFileRetry(source.name) {
                    TransferConflictHelper.downloadNetworkToLocalAtomic(
                        share, source, destBase, resolvedAction,
                        onProgress = { c, t -> session.reportProgress(source.name, c, t, fileIndex, totalFiles) },
                        onConnectionReady = { conn -> session.registerConnection(conn) }
                    )
                }
                session.registerConnection(null)

                val isEffDestRoot = isRoot(writtenDest)
                if (!isEffDestRoot && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                    pendingIndices.add(metadataExtractor.extractMetadata(writtenDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                    if (pendingIndices.size >= 50) flushIndices()
                }

                if (operation == FileClipboard.Operation.MOVE) {
                    val isDestSafLocal = isSaf(writtenDest)
                    val writtenSize = if (isDestSafLocal) SafTreeManager.getFileSize(appContext, writtenDest.absolutePath) else writtenDest.length()
                    val isSafeToDelete = if (isDestSafLocal && writtenSize <= 0L) {
                        SafTreeManager.exists(appContext, writtenDest.absolutePath)
                    } else {
                        FileTransferGuard.requireSourceSafeToDelete(writtenSize, source.size, source.name)
                    }
                    if (isSafeToDelete) {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.deleteFile(share, source.path)
                            ShareType.FTP          -> FtpShareClient.deleteFile(share, source.path)
                            ShareType.TV           -> TvShareClient.deleteFile(share, source.path)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, source.path, false)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.deleteFile(share, source.path)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, source.path)
                            ShareType.DROPBOX      -> DropboxShareClient.deleteFile(share, source.path)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, source.path)
                            ShareType.WEBDAV       -> WebDavShareClient.deleteFile(share, source.path)
                            ShareType.NFS          -> NfsShareClient.deleteFile(share, source.path)
                            ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                    }
                    FileTagsManager.onPathMoved(appContext, source.path, writtenDest.absolutePath)
                } else {
                    FileTagsManager.onPathCopied(appContext, source.path, writtenDest.absolutePath)
                }
                session.noteSuccess()
            }
        }

        // ── Process target slots ──────────────────────────────────────────
        for (slot in ctx.slots) {
            for (item in slot.items) {
                session.checkCancelled()
                when (item) {
                    is FileClipboard.ClipItem.Local -> {
                        try {
                            processLocalItem(item.file, File(effectiveDestDir, item.file.name), item.operation)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            session.checkCancelled()
                            session.noteFailure(e.message)
                        }
                    }
                    is FileClipboard.ClipItem.Remote -> {
                        var share = NetworkShareRepository.getInstance(appContext).getById(item.sourceShareId)
                        if (share?.isServerMode == true && item.sourceRemotePath.isNotEmpty()) {
                            share = share.copy(remotePath = item.sourceRemotePath)
                        }
                        if (share == null) {
                            val pairedDevice = PairingManager.getInstance(appContext).getPairedDevice(item.sourceShareId)
                            if (pairedDevice != null && pairedDevice.isConnected) {
                                share = NetworkShare(
                                    id = pairedDevice.deviceId,
                                    name = pairedDevice.name,
                                    type = ShareType.TV,
                                    host = pairedDevice.lastIp,
                                    port = pairedDevice.lastPort
                                )
                            }
                        }
                        if (share == null) {
                            val onlineStorage = OnlineStorageRepository.getInstance(appContext).getById(item.sourceShareId)
                            if (onlineStorage != null) {
                                share = NetworkShare(
                                    id = onlineStorage.id,
                                    name = onlineStorage.displayName,
                                    type = when (onlineStorage.provider) {
                                        OnlineStorageProvider.ONEDRIVE     -> ShareType.ONEDRIVE
                                        OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                                        OnlineStorageProvider.DROPBOX      -> ShareType.DROPBOX
                                        OnlineStorageProvider.AWS_S3       -> ShareType.AWS_S3
                                        OnlineStorageProvider.IDRIVE_E2    -> ShareType.IDRIVE_E2
                                        OnlineStorageProvider.WEBDAV       -> ShareType.WEBDAV
                                        OnlineStorageProvider.RCLONE       -> ShareType.WEBDAV
                                    },
                                    host = when (onlineStorage.provider) {
                                        OnlineStorageProvider.RCLONE -> RCloneShareClient.RCLONE_HOST_MARKER
                                        else -> if (onlineStorage.isWebDavProvider) onlineStorage.webDavUrl ?: onlineStorage.email else onlineStorage.s3Endpoint ?: onlineStorage.email
                                    },
                                    username = when (onlineStorage.provider) {
                                        OnlineStorageProvider.RCLONE -> onlineStorage.id
                                        else -> if (onlineStorage.isWebDavProvider) onlineStorage.webDavUsername ?: "" else onlineStorage.s3AccessKey ?: ""
                                    },
                                    password = if (onlineStorage.isWebDavProvider) onlineStorage.webDavPassword ?: "" else onlineStorage.s3SecretKey ?: "",
                                    readOnly = false
                                )
                            }
                        }

                        if (share != null) {
                            try {
                                processNetItem(item.file, File(currentDir, item.file.name), share, item.operation)
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

        if (ctx.targetSlotId == null) {
            FileClipboard.clear()
        }
        flushIndices()
    }
}
