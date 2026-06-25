package za.kilowatch.ultimatefilemanager.smartsort

import za.kilowatch.ultimatefilemanager.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class NetworkSmartSortStorage(
    private val share: NetworkShare
) : SmartSortStorage {

    private val isUnsupported: Boolean = share.isServerMode

    override suspend fun listFiles(path: String): List<SmartSortFileEntry> = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext emptyList()
        val netFiles = try {
            when (share.type) {
                ShareType.SMB -> SmbShareClient.listFiles(share, path)
                ShareType.FTP -> FtpShareClient.listFiles(share, path)
                ShareType.TV  -> TvShareClient.listFiles(share, path)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, path)
                ShareType.NFS -> NfsShareClient.listFiles(share, path)
                ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(share, path)
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, path)
                ShareType.DROPBOX -> DropboxShareClient.listFiles(share, path)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, path)
                ShareType.WEBDAV -> WebDavShareClient.listFiles(share, path)
                ShareType.WEBDAV -> WebDavShareClient.listFiles(share, path)
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (e: Exception) {
            emptyList()
        }
        netFiles
            .filter { !it.name.startsWith(".UFM_") }
            .map { it.toSmartSortEntry() }
    }

    override suspend fun mkdirs(path: String): Boolean = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext false
        try {
            when (share.type) {
                ShareType.SMB -> { SmbShareClient.mkdir(share, path); true }
                ShareType.FTP -> { FtpShareClient.mkdir(share, path); true }
                ShareType.TV  -> { TvShareClient.mkdir(share, path); true }
                ShareType.SFTP, ShareType.SCP -> { SshShareClient.mkdir(share, path); true }
                ShareType.NFS -> { NfsShareClient.mkdir(share, path); true }
                ShareType.ONEDRIVE -> { OnedriveShareClient.mkdir(share, path); true }
                ShareType.GOOGLE_DRIVE -> { GoogleDriveShareClient.mkdir(share, path); true }
                ShareType.DROPBOX -> { DropboxShareClient.mkdir(share, path); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.mkdir(share, path); true }
                ShareType.WEBDAV -> { WebDavShareClient.mkdir(share, path); true }
                ShareType.WEBDAV -> { WebDavShareClient.mkdir(share, path); true }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext false
        try {
            when (share.type) {
                ShareType.SMB -> { SmbShareClient.rename(share, from, to); true }
                ShareType.FTP -> { FtpShareClient.rename(share, from, to); true }
                ShareType.TV  -> { TvShareClient.rename(share, from, to); true }
                ShareType.SFTP, ShareType.SCP -> { SshShareClient.rename(share, from, to); true }
                ShareType.NFS -> { NfsShareClient.rename(share, from, to); true }
                ShareType.ONEDRIVE -> { OnedriveShareClient.rename(share, from, to); true }
                ShareType.GOOGLE_DRIVE -> { GoogleDriveShareClient.rename(share, from, to); true }
                ShareType.DROPBOX -> { DropboxShareClient.rename(share, from, to); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.rename(share, from, to); true }
                ShareType.WEBDAV -> { WebDavShareClient.rename(share, from, to); true }
                ShareType.WEBDAV -> { WebDavShareClient.rename(share, from, to); true }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun writeBytes(path: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext false
        val noProgress: (Long) -> Unit = {}
        try {
            when (share.type) {
                ShareType.FTP -> { FtpShareClient.uploadStream(share, path, ByteArrayInputStream(data), data.size.toLong()); true }
                ShareType.ONEDRIVE -> { OnedriveShareClient.uploadStream(share, path, ByteArrayInputStream(data), data.size.toLong()); true }
                ShareType.TV -> { TvShareClient.uploadStream(share, path, ByteArrayInputStream(data), data.size.toLong()); true }
                ShareType.GOOGLE_DRIVE -> { GoogleDriveShareClient.uploadStream(share, path, ByteArrayInputStream(data), data.size.toLong()); true }
                ShareType.DROPBOX -> { DropboxShareClient.uploadStream(share, path, ByteArrayInputStream(data), data.size.toLong(), noProgress); true }
                ShareType.WEBDAV -> { WebDavShareClient.uploadStream(share, path, ByteArrayInputStream(data), data.size.toLong(), noProgress); true }
                ShareType.WEBDAV -> { WebDavShareClient.uploadStream(share, path, ByteArrayInputStream(data), data.size.toLong(), noProgress); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.uploadStream(share, path, ByteArrayInputStream(data), data.size.toLong(), noProgress); true }
                ShareType.SMB -> {
                    SmbShareClient.openOutputStream(share, path).use { out -> out.write(data) }
                    true
                }
                ShareType.SFTP, ShareType.SCP -> {
                    SshShareClient.openOutputStream(share, path).use { out -> out.write(data) }
                    true
                }
                ShareType.NFS -> {
                    NfsShareClient.openOutputStream(share, path).use { out -> out.write(data) }
                    true
                }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (_: Exception) { false }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext false
        try {
            when (share.type) {
                ShareType.SMB -> { SmbShareClient.deleteFile(share, path); true }
                ShareType.FTP -> { FtpShareClient.deleteFile(share, path); true }
                ShareType.TV -> { TvShareClient.deleteFile(share, path); true }
                ShareType.SFTP, ShareType.SCP -> { SshShareClient.delete(share, path, false); true }
                ShareType.NFS -> { NfsShareClient.deleteFile(share, path); true }
                ShareType.ONEDRIVE -> { OnedriveShareClient.deleteFile(share, path); true }
                ShareType.GOOGLE_DRIVE -> { GoogleDriveShareClient.deleteFile(share, path); true }
                ShareType.DROPBOX -> { DropboxShareClient.deleteFile(share, path); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.deleteFile(share, path); true }
                ShareType.WEBDAV -> { WebDavShareClient.deleteFile(share, path); true }
                ShareType.WEBDAV -> { WebDavShareClient.deleteFile(share, path); true }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } catch (_: Exception) { false }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        if (isUnsupported) return@withContext false
        try {
            val files = when (share.type) {
                ShareType.SMB -> SmbShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.FTP -> FtpShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.TV  -> TvShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.NFS -> NfsShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.DROPBOX -> DropboxShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.WEBDAV -> WebDavShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.WEBDAV -> WebDavShareClient.listFiles(share, path.substringBeforeLast("/"))
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
            files.any { it.path == path || it.path == "/${path.trimStart('/').replace('\\', '/')}" }
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
