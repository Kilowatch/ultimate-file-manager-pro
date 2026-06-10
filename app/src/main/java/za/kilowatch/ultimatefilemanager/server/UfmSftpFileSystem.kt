package za.kilowatch.ultimatefilemanager.server

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import java.io.File
import java.net.URI
import java.nio.file.*
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import android.util.Log

/**
 * Path implementation for the SFTP VFS.
 */
class UfmSftpFileSystem(private val provider: FileSystemProvider, val rootUri: String) : FileSystem() {
    override fun provider(): FileSystemProvider = provider
    override fun close() {}
    override fun isOpen(): Boolean = true
    override fun isReadOnly(): Boolean = false
    override fun getSeparator(): String = "/"
    override fun getRootDirectories(): Iterable<Path> = listOf(UfmSftpPath(this, "/"))
    override fun getFileStores(): Iterable<FileStore> = emptyList()
    override fun supportedFileAttributeViews(): Set<String> = setOf("basic", "posix", "owner", "unix")
    override fun getPath(first: String, vararg more: String): Path {
        val combined = if (more.isEmpty()) first else first + "/" + more.joinToString("/")
        return UfmSftpPath(this, combined)
    }
    override fun getPathMatcher(syntaxAndPattern: String?): PathMatcher = throw UnsupportedOperationException()
    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = throw UnsupportedOperationException()
    override fun newWatchService(): WatchService = throw UnsupportedOperationException()
}

/**
 * [Path] implementation for the SFTP VFS.
 */
class UfmSftpPath(private val fileSystem: UfmSftpFileSystem, private val path: String) : Path {
    override fun getFileSystem(): FileSystem = fileSystem
    override fun isAbsolute(): Boolean = path.startsWith("/")
    override fun getRoot(): Path? = if (isAbsolute) UfmSftpPath(fileSystem, "/") else null
    override fun getFileName(): Path? = if (path.isEmpty() || path == "/") null else UfmSftpPath(fileSystem, path.substringAfterLast('/'))
    override fun getParent(): Path? {
        val lastSlash = path.trimEnd('/').lastIndexOf('/')
        return if (lastSlash < 0) null else UfmSftpPath(fileSystem, path.substring(0, lastSlash + 1))
    }
    override fun getNameCount(): Int = path.trim('/').split("/").filter { it.isNotEmpty() }.size
    override fun getName(index: Int): Path = UfmSftpPath(fileSystem, path.trim('/').split("/").filter { it.isNotEmpty() }[index])
    override fun subpath(beginIndex: Int, endIndex: Int): Path {
        Log.d("UfmSftpPath", "subpath requested for $path")
        throw UnsupportedOperationException()
    }
    override fun startsWith(other: Path): Boolean = path.startsWith(other.toString())
    override fun startsWith(other: String): Boolean = path.startsWith(other)
    override fun endsWith(other: Path): Boolean = path.endsWith(other.toString())
    override fun endsWith(other: String): Boolean = path.endsWith(other)
    override fun normalize(): Path {
        val stack = mutableListOf<String>()
        val components = path.split("/").filter { it.isNotEmpty() && it != "." }
        for (comp in components) {
            if (comp == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            } else {
                stack.add(comp)
            }
        }
        val normalized = "/" + stack.joinToString("/")
        return UfmSftpPath(fileSystem, normalized)
    }
    override fun resolve(other: Path): Path = if (other.isAbsolute) other else {
        val resolved = path.trimEnd('/') + "/" + other.toString().trimStart('/')
        UfmSftpPath(fileSystem, resolved)
    }
    override fun resolve(other: String): Path = resolve(UfmSftpPath(fileSystem, other))
    override fun resolveSibling(other: Path): Path = getParent()?.resolve(other) ?: other
    override fun resolveSibling(other: String): Path = resolveSibling(UfmSftpPath(fileSystem, other))
    override fun relativize(other: Path): Path {
        Log.d("UfmSftpPath", "relativize requested for $path")
        throw UnsupportedOperationException()
    }
    override fun toUri(): URI = URI("ufm", null, path, null)
    override fun toAbsolutePath(): Path = if (isAbsolute) this else UfmSftpPath(fileSystem, "/" + path)
    override fun toRealPath(vararg options: LinkOption?): Path = toAbsolutePath()
    override fun register(watcher: WatchService, events: Array<out WatchEvent.Kind<*>>, vararg modifiers: WatchEvent.Modifier): WatchKey = throw UnsupportedOperationException()
    override fun register(watcher: WatchService, vararg events: WatchEvent.Kind<*>): WatchKey = register(watcher, events, *emptyArray())
    override fun compareTo(other: Path): Int = path.compareTo(other.toString())
    override fun toString(): String = path
    override fun iterator(): MutableIterator<Path> {
        val parts = path.trim('/').split("/").filter { it.isNotEmpty() }
        return parts.map { UfmSftpPath(fileSystem, it) as Path }.toMutableList().listIterator()
    }
    override fun toFile(): File {
        Log.d("UfmSftpPath", "toFile requested for $path")
        throw UnsupportedOperationException("Network path has no local File")
    }

    fun toUfmUri(): String {
        val root = fileSystem.rootUri
        val scheme = root.substringBefore("://")
        val rest = root.substringAfter("://")
        val id = rest.substringBefore("/")
        val rootPath = "/" + rest.substringAfter("/").trimStart('/')

        // Canonicalize the path before combining with the rootUri.
        val normalizedSelf = normalize().toString()
        val combinedPath = if (rootPath == "/") normalizedSelf else rootPath.trimEnd('/') + "/" + normalizedSelf.trimStart('/')
        return "$scheme://$id/${combinedPath.trimStart('/')}"
    }

    /**
     * Returns the physical local filesystem path when [fileSystem.rootUri] is a `file://` URI,
     * otherwise null.  Used by [UfmSftpFileSystemProvider] to notify the indexing system of
     * mutations (create, write, delete, rename) on local storage only.
     */
    fun toLocalPath(): String? {
        val root = fileSystem.rootUri
        if (!root.startsWith("file://")) return null
        // file:///storage/emulated/0/some/root  →  /storage/emulated/0/some/root
        val rootPath = root.removePrefix("file://")
        val normalizedSelf = normalize().toString()   // e.g. /subdir/file.txt
        return if (rootPath == "/" || rootPath.isEmpty()) normalizedSelf
               else rootPath.trimEnd('/') + normalizedSelf
    }
}
