package za.kilowatch.ultimatefilemanager.storage

import android.net.Uri
import java.io.File

class SafFile : File {
    val rawPath: String
    val docName: String
    private val isDir: Boolean
    private val docLength: Long
    private val docLastModified: Long
    val documentUri: Uri?

    constructor(
        pathname: String,
        isDir: Boolean = true,
        docLength: Long = 0L,
        docLastModified: Long = 0L,
        documentUri: Uri? = null
    ) : super(cleanSafPath(pathname)) {
        this.rawPath = cleanSafPath(pathname)
        this.docName = rawPath.substringAfterLast('/')
        this.isDir = isDir
        this.docLength = docLength
        this.docLastModified = docLastModified
        this.documentUri = documentUri
    }

    constructor(
        parentPath: String,
        docName: String,
        isDir: Boolean = true,
        docLength: Long = 0L,
        docLastModified: Long = 0L,
        documentUri: Uri? = null
    ) : super(combineSafPath(parentPath, docName)) {
        this.rawPath = combineSafPath(parentPath, docName)
        this.docName = docName
        this.isDir = isDir
        this.docLength = docLength
        this.docLastModified = docLastModified
        this.documentUri = documentUri
    }

    override fun getPath(): String = rawPath
    override fun getAbsolutePath(): String = rawPath
    override fun getCanonicalPath(): String = rawPath
    override fun getName(): String = if (docName.isNotEmpty()) docName else super.getName()

    override fun getParent(): String? {
        val clean = cleanSafPath(rawPath)
        if (!clean.startsWith("saf://")) return super.getParent()
        val withoutScheme = clean.removePrefix("saf://")
        if (!withoutScheme.contains('/')) return null // Root saf://<id> has no parent
        return "saf://" + withoutScheme.substringBeforeLast('/')
    }

    override fun getParentFile(): File? {
        val parent = getParent() ?: return null
        return SafFile(parent, true)
    }

    override fun isDirectory(): Boolean = isDir
    override fun isFile(): Boolean = !isDir
    override fun length(): Long = docLength
    override fun lastModified(): Long = docLastModified
    override fun exists(): Boolean = true
    override fun canRead(): Boolean = true
    override fun canWrite(): Boolean = true

    override fun delete(): Boolean {
        val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
        return if (isDir) SafTreeManager.deleteRecursively(ctx, rawPath) else SafTreeManager.delete(ctx, rawPath)
    }

    fun deleteRecursively(): Boolean {
        val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
        return if (isDir) SafTreeManager.deleteRecursively(ctx, rawPath) else SafTreeManager.delete(ctx, rawPath)
    }

    override fun mkdir(): Boolean {
        val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
        val p = parent ?: ""
        return SafTreeManager.mkdir(ctx, p, docName)
    }

    override fun createNewFile(): Boolean {
        val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
        val p = parent ?: ""
        return SafTreeManager.createFile(ctx, p, docName) != null
    }

    companion object {
        fun cleanSafPath(path: String): String {
            var p = path.trim()
            if (p.startsWith("/saf:/")) {
                p = "saf://" + p.removePrefix("/saf:/")
            } else if (p.startsWith("/saf:")) {
                p = "saf://" + p.removePrefix("/saf:")
            } else if (p.startsWith("saf:/") && !p.startsWith("saf://")) {
                p = "saf://" + p.removePrefix("saf:/")
            }
            return p.trimEnd('/')
        }

        fun combineSafPath(parent: String, name: String): String {
            val cleanParent = cleanSafPath(parent)
            val cleanName = name.trimStart('/')
            if (cleanParent.isEmpty()) return cleanSafPath(cleanName)
            return "$cleanParent/$cleanName"
        }
    }
}
