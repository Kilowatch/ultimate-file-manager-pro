package za.kilowatch.ultimatefilemanager.storage

import java.io.File

/**
 * File abstraction for files and directories residing on root/system partitions.
 * Seamlessly integrates with [FileAdapter], [FileBrowserActivity], [TwinWindowActivity],
 * and built-in viewers by extending [java.io.File] and routing I/O via [RootShellWrapper].
 */
class RootFile(
    parentPath: String,
    val docName: String,
    private val isDir: Boolean = true,
    private val docLength: Long = 0L,
    private val docLastModified: Long = 0L,
    val posixPermissions: String = "",
    val owner: String = "",
    val group: String = "",
    val ownerGroup: String = if (owner.isNotEmpty() && group.isNotEmpty()) "$owner:$group" else owner.ifEmpty { group },
    val selinuxContext: String = "",
    val isSymlink: Boolean = false,
    val symlinkTarget: String = ""
) : File(if (parentPath == "/" || parentPath.isEmpty()) (if (docName.isEmpty()) "/" else "/$docName") else "$parentPath/$docName") {

    val posixPath: String = if (parentPath == "/" || parentPath.isEmpty()) {
        if (docName.isEmpty()) "/" else "/$docName"
    } else {
        "$parentPath/$docName"
    }

    override fun getPath(): String = posixPath
    override fun getAbsolutePath(): String = posixPath
    override fun getCanonicalPath(): String = posixPath
    override fun getName(): String = docName
    override fun getParent(): String? = if (posixPath == "/" || posixPath.isEmpty()) null else posixPath.substringBeforeLast('/', "").ifEmpty { "/" }
    override fun getParentFile(): File? = parent?.let { RootFile(it.substringBeforeLast('/', ""), it.substringAfterLast('/'), true) }

    override fun isDirectory(): Boolean = isDir
    override fun isFile(): Boolean = !isDir

    override fun length(): Long {
        return if (docLength > 0L) docLength else RootShellWrapper.getFileSize(posixPath)
    }

    override fun lastModified(): Long {
        return if (docLastModified > 0L) docLastModified else RootShellWrapper.getLastModified(posixPath)
    }

    override fun exists(): Boolean {
        return RootShellWrapper.exists(posixPath)
    }

    override fun canRead(): Boolean = true
    override fun canWrite(): Boolean = true

    override fun list(): Array<String>? {
        return listFiles()?.map { it.name }?.toTypedArray()
    }

    override fun listFiles(): Array<File>? {
        return RootShellWrapper.listFiles(posixPath).toTypedArray()
    }

    override fun renameTo(dest: File): Boolean {
        return RootShellWrapper.move(posixPath, dest.absolutePath)
    }

    override fun mkdir(): Boolean {
        return RootShellWrapper.mkdir(posixPath)
    }

    override fun mkdirs(): Boolean {
        return RootShellWrapper.mkdir(posixPath)
    }

    override fun delete(): Boolean {
        return RootShellWrapper.delete(posixPath)
    }
}
