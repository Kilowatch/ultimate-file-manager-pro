package za.kilowatch.ultimatefilemanager.checksum

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.network.DlnaShareClient
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.RCloneShareClient
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.TvShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import za.kilowatch.ultimatefilemanager.storage.RootShellWrapper
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Unified abstraction representing a readable file across Local, SAF, Root, Network,
 * Cloud, and Archive storage backends.
 */
interface UfmFileSource {
    val name: String
    val size: Long
    val storageLabel: String
    val displayPath: String

    suspend fun openStream(context: Context): InputStream
}

/**
 * Represents a standard file on local storage, external SD card via SAF, or root storage.
 */
class LocalFileSource(val file: File) : UfmFileSource {
    override val name: String = file.name
    override val size: Long = file.length()
    override val storageLabel: String
        get() = when {
            RootShellWrapper.isRootPath(file.absolutePath) -> "Root"
            SafTreeManager.isSafPath(file.absolutePath) -> "SAF"
            else -> "Local"
        }
    override val displayPath: String = file.absolutePath

    override suspend fun openStream(context: Context): InputStream = withContext(Dispatchers.IO) {
        if (SafTreeManager.isSafPath(file.absolutePath) ||
            SafTreeManager.hasTreePermissionForPath(context, file.absolutePath)
        ) {
            SafTreeManager.openInputStream(context, file.absolutePath)
                ?: throw FileNotFoundException("Could not open SAF stream for ${file.name}")
        } else if (RootShellWrapper.isRootPath(file.absolutePath)) {
            RootShellWrapper.openInputStream(file.absolutePath)
        } else {
            FileInputStream(file)
        }
    }
}

/**
 * Represents a file on a network share, cloud provider, or rclone backend.
 */
class NetworkFileSource(
    val share: NetworkShare,
    val networkFile: NetworkFile
) : UfmFileSource {
    override val name: String = networkFile.name
    override val size: Long = networkFile.size
    override val storageLabel: String
        get() = if (RCloneShareClient.isRCloneShare(share)) {
            "RClone"
        } else {
            share.type.name
        }
    override val displayPath: String = "${share.name}:${networkFile.path}"

    override suspend fun openStream(context: Context): InputStream = withContext(Dispatchers.IO) {
        when (share.type) {
            ShareType.SMB -> SmbShareClient.openInputStream(share, networkFile.path)
            ShareType.FTP -> FtpShareClient.openInputStream(share, networkFile.path)
            ShareType.TV -> TvShareClient.openInputStream(share, networkFile.path)
            ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, networkFile.path)
            ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, networkFile.path).first
            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, networkFile.path).first
            ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, networkFile.path).first
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, networkFile.path).first
            ShareType.WEBDAV -> WebDavShareClient.openInputStream(share, networkFile.path).first
            ShareType.NFS -> NfsShareClient.openInputStream(share, networkFile.path)
            ShareType.DLNA -> DlnaShareClient.openInputStream(share, networkFile.path)
        }
    }
}

/**
 * Represents an entry inside a ZIP or 7z archive file.
 */
class ArchiveFileSource(
    val archiveFile: File,
    val entryPath: String,
    val entrySize: Long,
    val password: String? = null
) : UfmFileSource {
    override val name: String = entryPath.substringAfterLast('/')
    override val size: Long = entrySize
    override val storageLabel: String = archiveFile.extension.uppercase(Locale.ROOT)
    override val displayPath: String = "${archiveFile.name}/$entryPath"

    override suspend fun openStream(context: Context): InputStream = withContext(Dispatchers.IO) {
        val ext = archiveFile.extension.lowercase(Locale.ROOT)
        if (ext == "7z") {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            val options = org.apache.commons.compress.archivers.sevenz.SevenZFileOptions.builder()
                .withMaxMemoryLimitInKb(maxMemoryKb)
                .build()
            val szf = if (password != null) {
                org.apache.commons.compress.archivers.sevenz.SevenZFile(archiveFile, password.toCharArray(), options)
            } else {
                org.apache.commons.compress.archivers.sevenz.SevenZFile(archiveFile, options)
            }
            var targetEntry: org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry? = szf.nextEntry
            while (targetEntry != null) {
                if (targetEntry.name == entryPath) {
                    return@withContext szf.getInputStream(targetEntry)
                }
                targetEntry = szf.nextEntry
            }
            szf.close()
            throw FileNotFoundException("Entry $entryPath not found in 7z archive")
        } else {
            val zip = net.lingala.zip4j.ZipFile(archiveFile)
            if (zip.isEncrypted && password != null) {
                zip.setPassword(password.toCharArray())
            }
            val header = zip.getFileHeader(entryPath)
                ?: throw FileNotFoundException("Entry $entryPath not found in archive")
            zip.getInputStream(header)
        }
    }
}

/**
 * Thread-safe in-memory session holder for passing [UfmFileSource] instances to Dialogs
 * without parcelization or transaction buffer limits.
 */
object ChecksumSessionHolder {
    private val sessions = ConcurrentHashMap<String, List<UfmFileSource>>()
    private val compareSessions = ConcurrentHashMap<String, Pair<UfmFileSource, UfmFileSource>>()

    fun put(sources: List<UfmFileSource>): String {
        val id = UUID.randomUUID().toString()
        sessions[id] = sources
        return id
    }

    fun get(id: String): List<UfmFileSource>? = sessions[id]

    fun remove(id: String) {
        sessions.remove(id)
    }

    fun putCompare(left: UfmFileSource, right: UfmFileSource): String {
        val id = UUID.randomUUID().toString()
        compareSessions[id] = Pair(left, right)
        return id
    }

    fun getCompare(id: String): Pair<UfmFileSource, UfmFileSource>? = compareSessions[id]

    fun removeCompare(id: String) {
        compareSessions.remove(id)
    }
}
