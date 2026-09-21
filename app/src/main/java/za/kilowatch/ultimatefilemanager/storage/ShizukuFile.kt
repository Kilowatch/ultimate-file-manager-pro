package za.kilowatch.ultimatefilemanager.storage

class ShizukuFile(
    parentPath: String,
    val docName: String,
    private val isDir: Boolean = true,
    private val docLength: Long = 0L,
    private val docLastModified: Long = 0L
) : java.io.File(parentPath, docName) {

    constructor(fullPath: String, isDir: Boolean = true) : this(
        if (fullPath == "/" || fullPath.isEmpty()) "/" else fullPath.substringBeforeLast('/', "").ifEmpty { "/" },
        if (fullPath == "/" || fullPath.isEmpty()) "" else fullPath.substringAfterLast('/'),
        isDir
    )

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
    override fun getParentFile(): java.io.File? = parent?.let { ShizukuFile(it, true) }

    override fun isDirectory(): Boolean = isDir
    override fun isFile(): Boolean = !isDir
    override fun length(): Long = if (docLength > 0L) docLength else ShizukuShellWrapper.getFileSize(absolutePath)
    override fun lastModified(): Long = if (docLastModified > 0L) docLastModified else ShizukuShellWrapper.getLastModified(absolutePath)
    override fun exists(): Boolean {
        return ShizukuShellWrapper.exists(absolutePath)
    }
    override fun canRead(): Boolean = true
    override fun canWrite(): Boolean = true
    override fun list(): Array<String>? {
        return listFiles()?.map { it.name }?.toTypedArray()
    }
    override fun listFiles(): Array<java.io.File>? {
        return ShizukuShellWrapper.listFiles(absolutePath).toTypedArray()
    }
    override fun renameTo(dest: java.io.File): Boolean {
        return ShizukuShellWrapper.move(absolutePath, dest.absolutePath)
    }
    override fun mkdir(): Boolean {
        return ShizukuShellWrapper.mkdir(absolutePath)
    }
    override fun mkdirs(): Boolean {
        return ShizukuShellWrapper.mkdir(absolutePath)
    }
    override fun delete(): Boolean {
        return ShizukuShellWrapper.delete(absolutePath)
    }
}
