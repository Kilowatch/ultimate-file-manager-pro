package za.kilowatch.ultimatefilemanager.smartsort

import za.kilowatch.ultimatefilemanager.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class NetworkSmartSortStorage(
    private val share: NetworkShare
) : SmartSortStorage {

    private val isUnsupported: Boolean = share.isServerMode && share.type != ShareType.SMB

    fun getEffectiveShareAndPath(path: String): Pair<NetworkShare, String> {
        if (!share.isServerMode) {
            return Pair(share, path)
        }
        val cleanPath = path.trimStart('/')
        val parts = cleanPath.split('/', limit = 2)
        val shareName = parts.getOrElse(0) { "" }
        val subPath = parts.getOrElse(1) { "" }
        
        val effectiveShare = if (share.remotePath.trimStart('/').substringBefore('/') != shareName) {
            share.copy(remotePath = "/$shareName")
        } else {
            share
        }
        return Pair(effectiveShare, subPath)
    }

    override suspend fun listFiles(path: String): List<SmartSortFileEntry> = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext emptyList()
        val (effectiveShare, cleanPath) = getEffectiveShareAndPath(path)
        val netFiles = try {
            when (share.type) {
                ShareType.SMB -> SmbShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.FTP -> FtpShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.TV  -> TvShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.NFS -> NfsShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.DROPBOX -> DropboxShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.WEBDAV -> WebDavShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.WEBDAV -> WebDavShareClient.listFiles(effectiveShare, cleanPath)
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (e: Exception) {
            emptyList()
        }
        val shareName = if (share.isServerMode) path.trimStart('/').substringBefore('/') else ""
        netFiles
            .filter { !it.name.startsWith(".UFM_") }
            .map {
                val entry = it.toSmartSortEntry()
                if (share.isServerMode && shareName.isNotEmpty()) {
                    entry.copy(path = "/$shareName${entry.path}")
                } else {
                    entry
                }
            }
    }

    override suspend fun mkdirs(path: String): Boolean = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext false
        val (effectiveShare, cleanPath) = getEffectiveShareAndPath(path)
        try {
            when (share.type) {
                ShareType.SMB -> { SmbShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.FTP -> { FtpShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.TV  -> { TvShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.SFTP, ShareType.SCP -> { SshShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.NFS -> { NfsShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.ONEDRIVE -> { OnedriveShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.GOOGLE_DRIVE -> { GoogleDriveShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.DROPBOX -> { DropboxShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.WEBDAV -> { WebDavShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.WEBDAV -> { WebDavShareClient.mkdir(effectiveShare, cleanPath); true }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext false
        val (effectiveShareFrom, cleanFrom) = getEffectiveShareAndPath(from)
        val (_, cleanTo) = getEffectiveShareAndPath(to)
        try {
            when (share.type) {
                ShareType.SMB -> { SmbShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.FTP -> { FtpShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.TV  -> { TvShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.SFTP, ShareType.SCP -> { SshShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.NFS -> { NfsShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.ONEDRIVE -> { OnedriveShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.GOOGLE_DRIVE -> { GoogleDriveShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.DROPBOX -> { DropboxShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.WEBDAV -> { WebDavShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.WEBDAV -> { WebDavShareClient.rename(effectiveShareFrom, cleanFrom, cleanTo); true }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun writeBytes(path: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext false
        val (effectiveShare, cleanPath) = getEffectiveShareAndPath(path)
        val noProgress: (Long) -> Unit = {}
        try {
            when (share.type) {
                ShareType.FTP -> { FtpShareClient.uploadStream(effectiveShare, cleanPath, ByteArrayInputStream(data), data.size.toLong()); true }
                ShareType.ONEDRIVE -> { OnedriveShareClient.uploadStream(effectiveShare, cleanPath, ByteArrayInputStream(data), data.size.toLong()); true }
                ShareType.TV -> { TvShareClient.uploadStream(effectiveShare, cleanPath, ByteArrayInputStream(data), data.size.toLong()); true }
                ShareType.GOOGLE_DRIVE -> { GoogleDriveShareClient.uploadStream(effectiveShare, cleanPath, ByteArrayInputStream(data), data.size.toLong()); true }
                ShareType.DROPBOX -> { DropboxShareClient.uploadStream(effectiveShare, cleanPath, ByteArrayInputStream(data), data.size.toLong(), noProgress); true }
                ShareType.WEBDAV -> { WebDavShareClient.uploadStream(effectiveShare, cleanPath, ByteArrayInputStream(data), data.size.toLong(), noProgress); true }
                ShareType.WEBDAV -> { WebDavShareClient.uploadStream(effectiveShare, cleanPath, ByteArrayInputStream(data), data.size.toLong(), noProgress); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.uploadStream(effectiveShare, cleanPath, ByteArrayInputStream(data), data.size.toLong(), noProgress); true }
                ShareType.SMB -> {
                    SmbShareClient.openOutputStream(effectiveShare, cleanPath).use { out -> out.write(data) }
                    true
                }
                ShareType.SFTP, ShareType.SCP -> {
                    SshShareClient.openOutputStream(effectiveShare, cleanPath).use { out -> out.write(data) }
                    true
                }
                ShareType.NFS -> {
                    NfsShareClient.openOutputStream(effectiveShare, cleanPath).use { out -> out.write(data) }
                    true
                }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (_: Exception) { false }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext false
        val (effectiveShare, cleanPath) = getEffectiveShareAndPath(path)
        try {
            when (share.type) {
                ShareType.SMB -> { SmbShareClient.deleteFile(effectiveShare, cleanPath); true }
                ShareType.FTP -> { FtpShareClient.deleteFile(effectiveShare, cleanPath); true }
                ShareType.TV -> { TvShareClient.deleteFile(effectiveShare, cleanPath); true }
                ShareType.SFTP, ShareType.SCP -> { SshShareClient.delete(effectiveShare, cleanPath, false); true }
                ShareType.NFS -> { NfsShareClient.deleteFile(effectiveShare, cleanPath); true }
                ShareType.ONEDRIVE -> { OnedriveShareClient.deleteFile(effectiveShare, cleanPath); true }
                ShareType.GOOGLE_DRIVE -> { GoogleDriveShareClient.deleteFile(effectiveShare, cleanPath); true }
                ShareType.DROPBOX -> { DropboxShareClient.deleteFile(effectiveShare, cleanPath); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.deleteFile(effectiveShare, cleanPath); true }
                ShareType.WEBDAV -> { WebDavShareClient.deleteFile(effectiveShare, cleanPath); true }
                ShareType.WEBDAV -> { WebDavShareClient.deleteFile(effectiveShare, cleanPath); true }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (_: Exception) { false }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext false
        val (effectiveShare, cleanPath) = getEffectiveShareAndPath(path)
        try {
            val parentPath = if (cleanPath.contains("/")) cleanPath.substringBeforeLast("/") else ""
            val files = when (share.type) {
                ShareType.SMB -> SmbShareClient.listFiles(effectiveShare, parentPath)
                ShareType.FTP -> FtpShareClient.listFiles(effectiveShare, parentPath)
                ShareType.TV  -> TvShareClient.listFiles(effectiveShare, parentPath)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(effectiveShare, parentPath)
                ShareType.NFS -> NfsShareClient.listFiles(effectiveShare, parentPath)
                ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(effectiveShare, parentPath)
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(effectiveShare, parentPath)
                ShareType.DROPBOX -> DropboxShareClient.listFiles(effectiveShare, parentPath)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(effectiveShare, parentPath)
                ShareType.WEBDAV -> WebDavShareClient.listFiles(effectiveShare, parentPath)
                ShareType.WEBDAV -> WebDavShareClient.listFiles(effectiveShare, parentPath)
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
            files.any {
                val normalizedItem = it.path.trimStart('/').replace('\\', '/')
                val normalizedClean = cleanPath.trimStart('/').replace('\\', '/')
                normalizedItem == normalizedClean
            }
        } catch (e: Exception) {
            false
        }
    }

    fun sharesType(): ShareType = share.type
    fun getShare(): NetworkShare = share

    private fun NetworkFile.toSmartSortEntry() = SmartSortFileEntry(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size,
        lastModified = lastModified
    )
}
