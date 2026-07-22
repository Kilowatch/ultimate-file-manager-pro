package za.kilowatch.ultimatefilemanager.recycle

import android.content.Context
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.ShareType
import java.io.File

object RecycleBinManager {
    private const val TRASH_FOLDER_NAME = ".UFM_Recyclebin"

    private lateinit var dao: RecycleBinDao
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            dao = RecycleBinDatabase.getInstance(context.applicationContext).recycleBinDao()
        } catch (e: Exception) {
            android.util.Log.e("RecycleBinManager", "Failed to initialize RecycleBin DAO", e)
        }
    }

    private fun ensureInitialized(context: Context? = null) {
        if (!::appContext.isInitialized || !::dao.isInitialized) {
            val ctx = context?.applicationContext ?: try { za.kilowatch.ultimatefilemanager.UfmApplication.instance } catch (_: Exception) { null }
            if (ctx != null) {
                if (!::appContext.isInitialized) appContext = ctx
                if (!::dao.isInitialized) {
                    try {
                        dao = RecycleBinDatabase.getInstance(ctx).recycleBinDao()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    val isEnabled: Boolean
        get() {
            ensureInitialized()
            return if (::appContext.isInitialized) RecycleBinSettingsManager.isEnabled(appContext) else false
        }

    // ── Local file trash ─────────────────────────────────────────────────────

    suspend fun moveToTrash(
        context: Context,
        file: File,
        storageType: String,
        storageId: String,
        storageLabel: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val trashDir = getTrashDir(file)
            if (!trashDir.exists()) trashDir.mkdirs()

            val uniqueName = uniqueTrashName(file.name)
            val trashFile = File(trashDir, uniqueName)
            val fileSize = if (file.isDirectory) 0L else file.length()
            val ext = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val origPath = file.absolutePath
            val parentFolder = file.parent ?: ""
            val fileName = file.name
            val isDir = file.isDirectory

            val success = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(file.absolutePath)) {
                if (isDir) {
                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.move(file.absolutePath, trashFile.absolutePath) &&
                            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.runCommand("chmod -R 777 '${trashFile.absolutePath.replace("'", "'\\''")}'").first == 0
                } else {
                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.copy(file.absolutePath, trashFile.absolutePath) &&
                            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(file.absolutePath)
                }
            } else {
                if (isDir) {
                    file.renameTo(trashFile)
                } else {
                    file.copyTo(trashFile, overwrite = true)
                    file.delete()
                }
            }

            if (success) {
                dao.insert(RecycleBinEntity(
                    originalPath = origPath,
                    trashPath = trashFile.absolutePath,
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mime,
                    extension = ext,
                    dateDeleted = System.currentTimeMillis(),
                    storageType = storageType,
                    storageId = storageId,
                    storageLabel = storageLabel,
                    originalFolder = parentFolder,
                    isDirectory = isDir
                ))
            }
            success
        } catch (e: Exception) {
            false
        }
    }

    // ── Network/cloud file trash ─────────────────────────────────────────────

    suspend fun moveNetworkToTrash(
        context: Context,
        share: NetworkShare,
        filePath: String,
        fileName: String,
        isDirectory: Boolean,
        fileSize: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val trashFolder = TRASH_FOLDER_NAME
            val uniqueName = uniqueTrashName(fileName)
            val trashPath = "$trashFolder/$uniqueName"

            createNetworkDir(share, trashFolder)

            var actualTrashPath = trashPath
            var moved = try {
                renameNetwork(share, filePath, trashPath)
                true
            } catch (e: Exception) {
                false
            }

            if (!moved) {
                // Root-level mkdir/rename failed. Try same-directory .UFM_Recyclebin/
                val parentDir = filePath.substringBeforeLast("/", "")
                val localTrashPath = "$parentDir/$trashFolder/$uniqueName"
                createNetworkDir(share, "$parentDir/$trashFolder")
                try {
                    renameNetwork(share, filePath, localTrashPath)
                    actualTrashPath = localTrashPath
                    moved = true
                } catch (e2: Exception) {
                    // Last resort: copy+delete to root-level trash
                    copyToTrashNetwork(share, filePath, trashPath, fileSize)
                    deleteNetworkFile(share, filePath, isDirectory)
                    moved = true
                }
            }

            if (moved) {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                dao.insert(RecycleBinEntity(
                    originalPath = filePath,
                    trashPath = actualTrashPath,
                    fileName = fileName,
                    fileSize = if (isDirectory) 0L else fileSize,
                    mimeType = mime,
                    extension = ext,
                    dateDeleted = System.currentTimeMillis(),
                    storageType = share.type.name,
                    storageId = share.id,
                    storageLabel = share.name,
                    originalFolder = filePath.substringBeforeLast("/", ""),
                    isDirectory = isDirectory
                ))
            }
            moved
        } catch (e: Exception) {
            false
        }
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    suspend fun restore(entity: RecycleBinEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            if (entity.trashPath.startsWith("/")) {
                restoreLocal(entity)
            } else {
                restoreNetwork(entity)
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun restoreLocal(entity: RecycleBinEntity): Boolean {
        val trashFile = File(entity.trashPath)
        if (!trashFile.exists()) {
            dao.delete(entity.id)
            return false
        }
        val originalFile = File(entity.originalPath)
        originalFile.parentFile?.mkdirs()
        val success = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(entity.originalPath)) {
            if (entity.isDirectory) {
                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.move(trashFile.absolutePath, entity.originalPath)
            } else {
                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.copy(trashFile.absolutePath, entity.originalPath) &&
                        za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(trashFile.absolutePath)
            }
        } else {
            if (entity.isDirectory) {
                trashFile.renameTo(originalFile)
            } else {
                trashFile.copyTo(originalFile, overwrite = true)
                trashFile.delete()
            }
        }
        if (success) dao.delete(entity.id)
        return success
    }

    private suspend fun restoreNetwork(entity: RecycleBinEntity): Boolean {
        val share = resolveShareFromRepo(entity.storageId, entity.storageType) ?: return false
        try {
            renameNetwork(share, entity.trashPath, entity.originalPath)
            dao.delete(entity.id)
            return true
        } catch (e: Exception) {
            // If rename fails, try copy+delete as fallback
            return try {
                copyToTrashNetwork(share, entity.trashPath, entity.originalPath, entity.fileSize)
                deleteNetworkFile(share, entity.trashPath, entity.isDirectory)
                dao.delete(entity.id)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    // ── Permanent delete ─────────────────────────────────────────────────────

    suspend fun permanentDelete(entity: RecycleBinEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            if (entity.trashPath.startsWith("/")) {
                permanentDeleteLocal(entity)
            } else {
                permanentDeleteNetwork(entity)
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun permanentDeleteLocal(entity: RecycleBinEntity): Boolean {
        val trashFile = File(entity.trashPath)
        if (trashFile.exists()) {
            val deleted = if (entity.isDirectory) trashFile.deleteRecursively() else trashFile.delete()
            if (deleted) dao.delete(entity.id)
            return deleted
        }
        dao.delete(entity.id)
        return true
    }

    private suspend fun permanentDeleteNetwork(entity: RecycleBinEntity): Boolean {
        val share = resolveShareFromRepo(entity.storageId, entity.storageType) ?: return false
        try {
            deleteNetworkFile(share, entity.trashPath, entity.isDirectory)
            dao.delete(entity.id)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ── Bulk operations ──────────────────────────────────────────────────────

    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        try {
            val entries = getAllEntries()
            var count = 0
            for (entry in entries) {
                if (permanentDelete(entry)) count++
            }
            dao.deleteAll()
            count
        } catch (e: Exception) {
            0
        }
    }

    suspend fun permanentDeleteByIds(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        try {
            var count = 0
            for (id in ids) {
                val entry = getById(id)
                if (entry != null && permanentDelete(entry)) count++
            }
            count
        } catch (e: Exception) {
            0
        }
    }

    suspend fun restoreByIds(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        try {
            var count = 0
            for (id in ids) {
                val entry = getById(id)
                if (entry != null && restore(entry)) count++
            }
            count
        } catch (e: Exception) {
            0
        }
    }

    suspend fun validateEntries(): Int = withContext(Dispatchers.IO) {
        try {
            val entries = getAllEntries()
            var removed = 0
            for (entry in entries) {
                if (entry.trashPath.startsWith("/")) {
                    val trashFile = File(entry.trashPath)
                    if (!trashFile.exists()) {
                        dao.delete(entry.id)
                        removed++
                    }
                }
            }
            removed
        } catch (e: Exception) {
            0
        }
    }

    suspend fun isShareOnline(entity: RecycleBinEntity): Boolean = withContext(Dispatchers.IO) {
        if (entity.trashPath.startsWith("/")) return@withContext true
        try {
            val share = resolveShareFromRepo(entity.storageId, entity.storageType) ?: return@withContext false
            isNetworkShareReachable(share)
        } catch (e: Exception) {
            false
        }
    }

    fun getAllFlow() = dao.getAllFlow()

    suspend fun getAllEntries(): List<RecycleBinEntity> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    suspend fun getById(id: Long): RecycleBinEntity? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun createNetworkDir(share: NetworkShare, dirPath: String) {
        try {
            when (share.type) {
                ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.mkdir(share, dirPath)
                ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.mkdir(share, dirPath)
                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.mkdir(share, dirPath)
                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.mkdir(share, dirPath)
                ShareType.TV  -> za.kilowatch.ultimatefilemanager.network.TvShareClient.mkdir(share, dirPath)
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.mkdir(share, dirPath)
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.mkdir(share, dirPath)
                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.mkdir(share, dirPath)
                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.mkdir(share, dirPath)
                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.mkdir(share, dirPath)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.mkdir(share, dirPath)
                ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.mkdir(share, dirPath)
            }
        } catch (_: Exception) {}
    }

    private suspend fun copyToTrashNetwork(share: NetworkShare, fromPath: String, toPath: String, fileSize: Long = -1L) {
        val input = openNetworkStream(share, fromPath)
        input?.use { stream ->
            when (share.type) {
                ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.openOutputStream(share, toPath).use { out -> stream.copyTo(out) }
                ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.openOutputStream(share, toPath).use { out -> stream.copyTo(out) }
                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(share, toPath).use { out -> stream.copyTo(out) }
                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(share, toPath).use { out -> stream.copyTo(out) }
                ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.uploadStream(share, toPath, stream, fileSize)
                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.uploadStream(share, toPath, stream, fileSize)
                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.uploadStream(share, toPath, stream, fileSize)
                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.uploadStream(share, toPath, stream, fileSize) {}
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.uploadStream(share, toPath, stream, fileSize) {}
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.uploadStream(share, toPath, stream, fileSize) {}
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.uploadStream(share, toPath, stream, fileSize) {}
                ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.openOutputStream(share, toPath).use { out -> stream.copyTo(out) }
            }
        }
    }

    private suspend fun openNetworkStream(share: NetworkShare, path: String): java.io.InputStream? {
        return try {
            when (share.type) {
                ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.openInputStream(share, path)
                ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.openInputStream(share, path)
                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(share, path)
                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(share, path)
                ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.openInputStream(share, path)
                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(share, path).first
                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(share, path).first
                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(share, path).first
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(share, path).first
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(share, path).first
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(share, path).first
                ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.openInputStream(share, path)
            }
        } catch (_: Exception) { null }
    }

    private suspend fun renameNetwork(share: NetworkShare, from: String, to: String) {
        when (share.type) {
            ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.rename(share, from, to)
            ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.rename(share, from, to)
            ShareType.TV  -> za.kilowatch.ultimatefilemanager.network.TvShareClient.rename(share, from, to)
            ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.rename(share, from, to)
            ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.rename(share, from, to)
            ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.rename(share, from, to)
            ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.rename(share, from, to)
            ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.rename(share, from, to)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.rename(share, from, to)
            ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.rename(share, from, to)
            ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.rename(share, from, to)
            ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.rename(share, from, to)
        }
    }

    private suspend fun deleteNetworkFile(share: NetworkShare, path: String, isDirectory: Boolean) {
        if (isDirectory) {
            when (share.type) {
                ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteDir(share, path)
                ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteDir(share, path)
                ShareType.TV  -> za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteDir(share, path)
                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteDir(share, path)
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteDir(share, path)
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteDir(share, path)
                ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.deleteDir(share, path)
                else -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteFile(share, path)
            }
        } else {
            when (share.type) {
                ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteFile(share, path)
                ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteFile(share, path)
                ShareType.TV  -> za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteFile(share, path)
                ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(share, path, false)
                ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(share, path)
                ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(share, path)
                ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(share, path)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(share, path)
                ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteFile(share, path)
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(share, path)
                ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(share, path)
                ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.deleteFile(share, path)
            }
        }
    }

    private suspend fun resolveShareFromRepo(storageId: String, storageType: String): NetworkShare? {
        return try {
            // Check NetworkShareRepository (SMB, FTP, SFTP, SCP, WebDAV)
            val fromRepo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(appContext).getById(storageId)
            if (fromRepo != null) {
                if (fromRepo.isServerMode) {
                    android.util.Log.w("RecycleBin", "Server-mode SMB shares not supported in recycle bin")
                    return null
                }
                return fromRepo
            }

            // Check OnlineStorageRepository (S3, OneDrive, Google Drive, Dropbox, WebDAV)
            val onlineRepo = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(appContext)
            val fromOnline = onlineRepo.getById(storageId)
            if (fromOnline != null) {
                val providerType = when (fromOnline.provider) {
                    za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.ONEDRIVE -> ShareType.ONEDRIVE
                    za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                    za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.DROPBOX -> ShareType.DROPBOX
                    za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.AWS_S3 -> ShareType.AWS_S3
                    za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.IDRIVE_E2 -> ShareType.IDRIVE_E2
                    za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.WEBDAV -> ShareType.WEBDAV
                    za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> ShareType.WEBDAV
                }
                return NetworkShare(
                    id = fromOnline.id,
                    name = fromOnline.displayName,
                    type = providerType,
                    host = if (fromOnline.isWebDavProvider) (fromOnline.webDavUrl ?: fromOnline.email) else (fromOnline.s3Endpoint ?: fromOnline.email),
                    port = 0,
                    username = if (fromOnline.isWebDavProvider) (fromOnline.webDavUsername ?: fromOnline.email) else (fromOnline.s3AccessKey ?: fromOnline.email),
                    password = if (fromOnline.isWebDavProvider) (fromOnline.webDavPassword ?: "") else (fromOnline.s3SecretKey ?: ""),
                    domain = fromOnline.s3Bucket ?: "",
                    remotePath = fromOnline.s3Region ?: "/",
                    readOnly = false
                )
            }

            // Check PairingManager (TV)
            val device = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(appContext).getPairedDevice(storageId)
            if (device != null) {
                return NetworkShare(
                    id = device.deviceId,
                    name = device.name,
                    type = ShareType.TV,
                    host = device.lastIp,
                    port = device.lastPort,
                    readOnly = false
                )
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun isNetworkShareReachable(share: NetworkShare): Boolean {
        return try {
            when (share.type) {
                ShareType.SMB -> { za.kilowatch.ultimatefilemanager.network.SmbShareClient.listFiles(share, ""); true }
                ShareType.FTP -> { za.kilowatch.ultimatefilemanager.network.FtpShareClient.listFiles(share, ""); true }
                ShareType.SFTP, ShareType.SCP -> { za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, ""); true }
                ShareType.NFS -> { za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(share, ""); true }
                ShareType.TV -> { za.kilowatch.ultimatefilemanager.network.TvShareClient.listFiles(share, ""); true }
                ShareType.ONEDRIVE -> { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, ""); true }
                ShareType.GOOGLE_DRIVE -> { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, ""); true }
                ShareType.DROPBOX -> { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, ""); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, ""); true }
                ShareType.WEBDAV -> { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, ""); true }
                ShareType.WEBDAV -> { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, ""); true }
                ShareType.DLNA -> { za.kilowatch.ultimatefilemanager.network.DlnaShareClient.listFiles(share, ""); true }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getTrashDir(file: File): File {
        val path = file.absolutePath
        val volumeRoot = when {
            path.startsWith("/storage/emulated/") -> {
                // Internal storage: /storage/emulated/0
                val idx = path.indexOf('/', "/storage/emulated/".length)
                if (idx > 0) path.substring(0, idx) else "/storage/emulated/0"
            }
            path.startsWith("/storage/") -> {
                // External SD/USB: /storage/<UUID>
                val idx = path.indexOf('/', "/storage/".length)
                if (idx > 0) path.substring(0, idx) else path
            }
            path.startsWith("/mnt/media_rw/") -> {
                // USB OTG on some devices
                val idx = path.indexOf('/', "/mnt/media_rw/".length)
                if (idx > 0) path.substring(0, idx) else path
            }
            else -> {
                // Fallback: use external storage directory
                android.os.Environment.getExternalStorageDirectory().absolutePath
            }
        }
        return File(volumeRoot, TRASH_FOLDER_NAME)
    }

    private fun uniqueTrashName(fileName: String): String {
        val baseName = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        val timestamp = System.currentTimeMillis()
        return if (ext.isNotEmpty() && ext != fileName) {
            "${baseName}_$timestamp.$ext"
        } else {
            "${fileName}_$timestamp"
        }
    }
}
