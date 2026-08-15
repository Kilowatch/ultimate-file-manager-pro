package za.kilowatch.ultimatefilemanager.storage

class ShizukuFile(
    parentPath: String,
    val docName: String,
    private val isDir: Boolean = true,
    private val docLength: Long = 0L,
    private val docLastModified: Long = 0L
) : java.io.File(parentPath, docName) {
    override fun isDirectory(): Boolean = isDir
    override fun isFile(): Boolean = !isDir
    override fun length(): Long = docLength
    override fun lastModified(): Long = docLastModified
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
