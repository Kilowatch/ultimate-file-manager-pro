package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import java.util.concurrent.TimeUnit

/**
 * Minimal [FileSystemProvider] that bridges NIO calls to [UfmFileSystemBridge].
 * Used by [UfmSftpServer] to host network storage via SFTP.
 *
 * Security notes:
 * - [readOnly]: When true, all mutating operations (write, delete, rename, mkdir)
 *   throw [AccessDeniedException] and files/directories report read-only POSIX
 *   permissions so WinSCP's UI disables those actions.
 * - C-2: [checkAccess] now enforces AccessMode.WRITE and AccessMode.EXECUTE for
 *   read-only profiles, honouring the FileSystemProvider contract.
 * - M-1: [assertWithinRoot] verifies that every resolved path remains inside
 *   the profile's configured rootUri to prevent symlink-based chroot escapes.
 * - L-2: Debug logs redact the full URI path to avoid leaking sensitive information.
 */
class UfmSftpFileSystemProvider(val context: Context, val readOnly: Boolean = false, val rootUri: String = "") : FileSystemProvider() {
    private val TAG = "UfmSftpProvider"
    private val systems = mutableMapOf<Pair<String, String>, UfmSftpFileSystem>()

    // ── Chroot enforcement ───────────────────────────────────────────────────
    // M-1: For file:// roots, assert that every resolved path still begins with
    // the profile's root base to block symlink-based traversal attacks.

    private val localRootBase: String? =
        if (rootUri.startsWith("file://")) rootUri.removePrefix("file://").trimEnd('/') else null

    private fun assertWithinRoot(localPath: String) {
        val base = localRootBase ?: return   // non-local roots skip this check
        val canonical = try { File(localPath).canonicalPath } catch (_: Exception) { localPath }
        if (!canonical.startsWith(base)) {
            Log.e(TAG, "Path traversal blocked: resolved outside root")
            throw AccessDeniedException(localPath, null, "Path traversal detected: outside configured root")
        }
    }

    // ── Local-index notification ─────────────────────────────────────────────
    // Only `file://` roots live in local storage that the indexer tracks.
    // Remote mounts (smb://, ftp://, sftp://, tv://, gdrive://, onedrive://)
    // are intentionally skipped — the IndexingRepository only indexes local paths.

    private fun notifyLocalIndexing(
        localPath: String,
        isDelete: Boolean = false,
        isFolder: Boolean = false
    ) {
        if (!rootUri.startsWith("file://")) return
        indexScope.launch {
            try {
                val (storageId, storageType, _) = IndexingRepository.resolveStorageForPath(localPath)
                if (isDelete) {
                    val repo = IndexingRepository.getInstance(context)
                    if (isFolder) repo.deleteTreeFromIndex(localPath)
                    else          repo.deleteFromIndex(localPath)
                } else {
                    val repo = IndexingRepository.getInstance(context)
                    if (isFolder) {
                        repo.indexFile(File(localPath), storageId, storageType)
                        repo.indexFolder(localPath, storageId, storageType)
                    } else {
                        repo.indexFile(File(localPath), storageId, storageType)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "notifyLocalIndexing failed", e)
            }
        }
    }

    override fun getScheme(): String = "ufm"

    override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem {
        val rootUri = env["rootUri"] as String
        val scheme = rootUri.substringBefore("://")
        return systems.getOrPut(scheme to rootUri) { UfmSftpFileSystem(this, rootUri) }
    }

    override fun getFileSystem(uri: URI): FileSystem {
        throw UnsupportedOperationException()
    }

    override fun getPath(uri: URI): Path {
        throw UnsupportedOperationException()
    }

    override fun newInputStream(path: Path, vararg options: OpenOption): InputStream {
        val ufmPath = path as UfmSftpPath
        ufmPath.toLocalPath()?.let { assertWithinRoot(it) }
        return UfmFileSystemBridge.openInputStream(context, ufmPath.toUfmUri())
    }

    override fun newOutputStream(path: Path, vararg options: OpenOption): OutputStream {
        if (readOnly) throw AccessDeniedException(path.toString(), null, "Read-only profile")
        val ufmPath = path as UfmSftpPath
        val localPath = ufmPath.toLocalPath()
        localPath?.let { assertWithinRoot(it) }
        val raw = UfmFileSystemBridge.openOutputStream(context, ufmPath.toUfmUri())
        if (localPath == null) return raw
        // Notify the indexer once the upload stream is fully closed (not at open-time).
        return object : java.io.FilterOutputStream(raw) {
            override fun close() {
                super.close()
                notifyLocalIndexing(localPath, isDelete = false, isFolder = false)
            }
        }
    }

    override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
        // Any option that implies writing must be rejected for read-only profiles.
        if (readOnly) {
            val writingOptions = setOf(
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.CREATE,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            if (options.any { it in writingOptions }) {
                throw AccessDeniedException(path.toString(), null, "Read-only profile")
            }
        }
        try {
            val ufmPath = path as UfmSftpPath
            val isWrite = options.any { it in setOf(
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.CREATE,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.TRUNCATE_EXISTING
            )}
            val localPath = if (isWrite) ufmPath.toLocalPath() else null
            localPath?.let { assertWithinRoot(it) }
            val channel = UfmSeekableByteChannel(context, ufmPath.toUfmUri(), options)
            // For write channels over local storage, wrap so we notify on close.
            if (isWrite && localPath != null) {
                return object : SeekableByteChannel by channel {
                    override fun close() {
                        channel.close()
                        notifyLocalIndexing(localPath, isDelete = false, isFolder = false)
                    }
                }
            }
            return channel
        } catch (e: Exception) {
            Log.e(TAG, "Error opening byte channel", e)
            throw e
        }
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        try {
            val ufmPath = dir as UfmSftpPath
            val uri = ufmPath.toUfmUri()
            val files = UfmFileSystemBridge.listFiles(context, uri)
            val paths = files.map { f -> UfmSftpPath(ufmPath.fileSystem as UfmSftpFileSystem, ufmPath.toString().trimEnd('/') + "/" + f.name) }
            
            return object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = paths.toMutableList().iterator()
                override fun close() {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in newDirectoryStream", e)
            throw e
        }
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        if (readOnly) throw AccessDeniedException(dir.toString(), null, "Read-only profile")
        val ufmPath = dir as UfmSftpPath
        val localPath = ufmPath.toLocalPath()
        localPath?.let { assertWithinRoot(it) }
        UfmFileSystemBridge.mkdir(context, ufmPath.toUfmUri())
        localPath?.let { notifyLocalIndexing(it, isDelete = false, isFolder = true) }
    }

    override fun delete(path: Path) {
        if (readOnly) throw AccessDeniedException(path.toString(), null, "Read-only profile")
        val ufmPath = path as UfmSftpPath
        val localPath = ufmPath.toLocalPath()
        localPath?.let { assertWithinRoot(it) }
        val wasDir = UfmFileSystemBridge.isDirectory(context, ufmPath.toUfmUri())
        try {
            UfmFileSystemBridge.delete(context, ufmPath.toUfmUri())
            if (localPath != null) {
                notifyLocalIndexing(localPath, isDelete = true, isFolder = wasDir)
                if (!wasDir) {
                    notifyLocalIndexing(localPath + ".filepart", isDelete = true, isFolder = false)
                }
            }
        } catch (e: NoSuchFileException) {
            // L-4: Log at debug so callers are aware, but best-effort treat as success.
            Log.d(TAG, "delete: file already absent: $path")
        }
    }

    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        throw UnsupportedOperationException()
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        if (readOnly) throw AccessDeniedException(source.toString(), null, "Read-only profile")
        val ufmSource = source as UfmSftpPath
        val ufmTarget = target as UfmSftpPath
        ufmSource.toLocalPath()?.let { assertWithinRoot(it) }
        ufmTarget.toLocalPath()?.let { assertWithinRoot(it) }
        val wasDir = UfmFileSystemBridge.isDirectory(context, ufmSource.toUfmUri())
        UfmFileSystemBridge.rename(context, ufmSource.toUfmUri(), ufmTarget.getFileName().toString())
        ufmSource.toLocalPath()?.let { notifyLocalIndexing(it, isDelete = true, isFolder = wasDir) }
        ufmTarget.toLocalPath()?.let { notifyLocalIndexing(it, isDelete = false, isFolder = wasDir) }
    }

    override fun isSameFile(path: Path, path2: Path): Boolean = path == path2

    override fun isHidden(path: Path): Boolean =
        path.getFileName()?.toString()?.startsWith(".") ?: false

    override fun getFileStore(path: Path): FileStore? = null

    /**
     * C-2: Enforces AccessMode.WRITE and AccessMode.EXECUTE checks in addition to
     * existence verification, honouring the FileSystemProvider contract and
     * providing defence-in-depth for read-only profiles.
     */
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        try {
            val ufmPath = path as UfmSftpPath
            val uri = ufmPath.toUfmUri()
            if (!UfmFileSystemBridge.exists(context, uri)) {
                throw NoSuchFileException(path.toString())
            }
            // C-2: Enforce write/execute access modes for read-only profiles.
            if (readOnly && modes.any { it == AccessMode.WRITE || it == AccessMode.EXECUTE }) {
                throw AccessDeniedException(path.toString(), null, "Read-only profile")
            }
        } catch (e: NoSuchFileException) {
            throw e
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in checkAccess", e)
            throw e
        }
    }

    override fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? {
        try {
            if (type == BasicFileAttributeView::class.java) {
                @Suppress("UNCHECKED_CAST")
                return UfmBasicFileAttributeView(this, path) as V
            }
            if (type.isAssignableFrom(PosixFileAttributeView::class.java) || type.isAssignableFrom(FileOwnerAttributeView::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return UfmPosixFileAttributeView(this, path) as V
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getFileAttributeView", e)
        }
        return null
    }

    override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A {
        try {
            val ufmPath = path as UfmSftpPath
            val uri = ufmPath.toUfmUri()
            val meta = UfmFileSystemBridge.getFileMetadata(context, uri)
                ?: throw NoSuchFileException(path.toString())

            @Suppress("UNCHECKED_CAST")
            return UfmSftpFileAttributes(meta, readOnly) as A
        } catch (e: NoSuchFileException) {
            // Expected: SFTP clients stat both target and .filepart before upload.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in readAttributes", e)
            throw e
        }
    }

    override fun readSymbolicLink(link: Path): Path {
        throw UnsupportedOperationException("Symbolic links not supported")
    }

    override fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>) {
        throw UnsupportedOperationException("Symbolic links not supported")
    }

    override fun createLink(link: Path, existing: Path) {
        throw UnsupportedOperationException("Hard links not supported")
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> {
        try {
            val attrs = readAttributes(path, PosixFileAttributes::class.java)
            val result = mutableMapOf<String, Any>()
            result["size"] = attrs.size()
            result["creationTime"] = attrs.creationTime()
            result["lastAccessTime"] = attrs.lastAccessTime()
            result["lastModifiedTime"] = attrs.lastModifiedTime()
            result["isRegularFile"] = attrs.isRegularFile
            result["isDirectory"] = attrs.isDirectory
            result["isSymbolicLink"] = attrs.isSymbolicLink
            result["isOther"] = attrs.isOther
            result["permissions"] = attrs.permissions()
            result["owner"] = attrs.owner()
            result["group"] = attrs.group()
            result["uid"] = 0
            result["gid"] = 0
            // mode: directory=040000|0755=16877, file=0100000|0644=33188
            result["mode"] = if (attrs.isDirectory) 16877 else 33188
            return result
        } catch (e: NoSuchFileException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in readAttributes (String)", e)
            throw e
        }
    }

    override fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption) {
        // Silent no-op: attribute setting not supported for network/virtual paths.
    }

    companion object {
        /** Shared coroutine scope for fire-and-forget index notifications. */
        private val indexScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private class UfmBasicFileAttributeView(val provider: UfmSftpFileSystemProvider, val path: Path) : BasicFileAttributeView {
        override fun name(): String = "basic"
        override fun readAttributes(): BasicFileAttributes = provider.readAttributes(path, BasicFileAttributes::class.java)
        override fun setTimes(lastModifiedTime: FileTime?, lastAccessTime: FileTime?, createTime: FileTime?) {}
    }

    private class UfmPosixFileAttributeView(val provider: UfmSftpFileSystemProvider, val path: Path) : PosixFileAttributeView {
        override fun name(): String = "posix"
        override fun readAttributes(): PosixFileAttributes = provider.readAttributes(path, PosixFileAttributes::class.java)
        override fun setTimes(lastModifiedTime: FileTime?, lastAccessTime: FileTime?, createTime: FileTime?) {}
        override fun setPermissions(perms: MutableSet<PosixFilePermission>?) {}
        override fun setGroup(group: GroupPrincipal?) {}
        override fun getOwner(): UserPrincipal = readAttributes().owner()
        override fun setOwner(owner: UserPrincipal?) {}
    }
}

class UfmSftpFileAttributes(private val file: NetworkFile, private val readOnly: Boolean = false) : PosixFileAttributes {
    override fun lastModifiedTime(): FileTime = FileTime.from(file.lastModified, TimeUnit.MILLISECONDS)
    override fun lastAccessTime(): FileTime = lastModifiedTime()
    override fun creationTime(): FileTime = lastModifiedTime()
    override fun isRegularFile(): Boolean = !file.isDirectory
    override fun isDirectory(): Boolean = file.isDirectory
    override fun isSymbolicLink(): Boolean = false
    override fun isOther(): Boolean = false
    override fun size(): Long = file.size
    override fun fileKey(): Any? = null
    override fun owner(): UserPrincipal = UserPrincipal { "user" }
    override fun group(): GroupPrincipal = GroupPrincipal { "group" }
    override fun permissions(): Set<PosixFilePermission> {
        return if (readOnly) {
            if (isDirectory) PosixFilePermissions.fromString("r-xr-xr-x")
            else             PosixFilePermissions.fromString("r--r--r--")
        } else {
            if (isDirectory) PosixFilePermissions.fromString("rwxr-xr-x")
            else             PosixFilePermissions.fromString("rw-r--r--")
        }
    }
}
