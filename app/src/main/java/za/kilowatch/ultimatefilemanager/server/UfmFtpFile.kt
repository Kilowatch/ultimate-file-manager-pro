package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Implementation of [FtpFile] that bridges to [UfmFileSystemBridge].
 *
 * ## Performance note
 * Apache FtpServer calls [isDirectory], [getSize], and [getLastModified] on every
 * child returned by [listFiles] to build the directory listing response. These all
 * funnel through [UfmFileSystemBridge.getFileMetadata], which is served from
 * the short-lived directory-listing cache populated by [UfmFileSystemBridge.listFiles].
 *
 * ## Security note
 * L-2: Log messages do not include full paths or URIs.
 */
class UfmFtpFile(
    private val context: Context,
    private val absolutePath: String,
    private val user: User,
    private val rootUri: String,
    /** Pre-fetched metadata from a parent [listFiles] call — avoids a redundant cache lookup. */
    private val cachedMeta: NetworkFile? = null,
    /** Mirror of [FtpServerProfile.readOnly] — gates write/delete operations. */
    private val readOnly: Boolean = false
) : FtpFile {

    private val fullUri: String = buildFullUri(rootUri, absolutePath)

    // ── Local-index notification ─────────────────────────────────────────────
    // Only `file://` roots live in local storage that the indexer tracks.

    private fun notifyLocalIndexing(
        ftpPath: String,
        isDelete: Boolean = false,
        isFolder: Boolean = false
    ) {
        if (!rootUri.startsWith("file://")) return
        indexScope.launch {
            try {
                val rootPath = rootUri.removePrefix("file://")
                val localPath = if (rootPath == "/" || rootPath.isEmpty()) ftpPath
                                else rootPath.trimEnd('/') + "/" + ftpPath.trimStart('/')

                val (storageId, storageType, _) = IndexingRepository.resolveStorageForPath(localPath)
                val repo = IndexingRepository.getInstance(context)

                if (isDelete) {
                    if (isFolder) repo.deleteTreeFromIndex(localPath)
                    else          repo.deleteFromIndex(localPath)
                } else {
                    if (isFolder) {
                        repo.indexFile(File(localPath), storageId, storageType)
                        repo.indexFolder(localPath, storageId, storageType)
                    } else {
                        repo.indexFile(File(localPath), storageId, storageType)
                    }
                }
            } catch (e: Exception) {
                Log.e("UfmFtpFile", "notifyLocalIndexing failed", e)
            }
        }
    }

    override fun getAbsolutePath(): String = absolutePath

    override fun getName(): String = absolutePath.substringAfterLast('/')

    override fun isDirectory(): Boolean =
        cachedMeta?.isDirectory ?: UfmFileSystemBridge.isDirectory(context, fullUri)

    override fun isFile(): Boolean = !isDirectory()

    override fun isReadable(): Boolean = true

    override fun isHidden(): Boolean = getName().startsWith(".")

    override fun isWritable(): Boolean = !readOnly

    override fun isRemovable(): Boolean = !readOnly

    override fun getLastModified(): Long =
        cachedMeta?.lastModified ?: UfmFileSystemBridge.getFileMetadata(context, fullUri)?.lastModified ?: 0L

    override fun setLastModified(time: Long): Boolean = false

    override fun getSize(): Long =
        cachedMeta?.size ?: UfmFileSystemBridge.getFileMetadata(context, fullUri)?.size ?: 0L

    override fun getOwnerName(): String = user.name

    override fun getGroupName(): String = "group"

    override fun getLinkCount(): Int = if (isDirectory()) 3 else 1

    override fun listFiles(): List<FtpFile> {
        val files = UfmFileSystemBridge.listFiles(context, fullUri)
        return files.map { f ->
            val childPath = if (absolutePath == "/") "/${f.name}" else "$absolutePath/${f.name}"
            UfmFtpFile(context, childPath, user, rootUri, cachedMeta = f, readOnly = readOnly)
        }
    }

    override fun mkdir(): Boolean {
        return try {
            UfmFileSystemBridge.mkdir(context, fullUri)
            notifyLocalIndexing(absolutePath, isDelete = false, isFolder = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun delete(): Boolean {
        return try {
            val wasDir = isDirectory()
            val deleted = UfmFileSystemBridge.delete(context, fullUri)
            if (deleted) {
                notifyLocalIndexing(absolutePath, isDelete = true, isFolder = wasDir)
                if (!wasDir) {
                    notifyLocalIndexing("$absolutePath.filepart", isDelete = true, isFolder = false)
                }
            }
            deleted
        } catch (e: Exception) {
            false
        }
    }

    override fun move(destination: FtpFile): Boolean {
        return try {
            val wasDir = isDirectory()
            UfmFileSystemBridge.rename(context, fullUri, destination.name)
            notifyLocalIndexing(absolutePath, isDelete = true, isFolder = wasDir)
            notifyLocalIndexing(destination.absolutePath, isDelete = false, isFolder = wasDir)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun createInputStream(offset: Long): InputStream {
        val raw = UfmFileSystemBridge.openInputStream(context, fullUri, offset)
        return java.io.BufferedInputStream(raw, 256 * 1024)
    }

    override fun createOutputStream(offset: Long): OutputStream {
        val raw = UfmFileSystemBridge.openOutputStream(context, fullUri)
        return object : java.io.BufferedOutputStream(raw, 256 * 1024) {
            override fun close() {
                super.close()
                notifyLocalIndexing(absolutePath, isDelete = false, isFolder = false)
            }
        }
    }

    override fun getPhysicalFile(): Any? = null

    override fun doesExist(): Boolean =
        cachedMeta != null || UfmFileSystemBridge.exists(context, fullUri)

    private fun buildFullUri(root: String, ftpPath: String): String {
        val scheme = root.substringBefore("://")
        val rest = root.substringAfter("://")
        val id = rest.substringBefore("/")
        val rootPath = "/" + rest.substringAfter("/").trimStart('/')
        val combinedPath = if (rootPath == "/") ftpPath else rootPath.trimEnd('/') + ftpPath
        return "$scheme://$id/${combinedPath.trimStart('/')}"
    }

    companion object {
        /** Shared coroutine scope for fire-and-forget index notifications. */
        private val indexScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
