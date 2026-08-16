package za.kilowatch.ultimatefilemanager.storage

import android.net.Uri
import java.io.File

class SafFile(
    parentPath: String,
    val docName: String,
    private val isDir: Boolean = true,
    private val docLength: Long = 0L,
    private val docLastModified: Long = 0L,
    val documentUri: Uri? = null
) : File(parentPath, docName) {
    override fun isDirectory(): Boolean = isDir
    override fun isFile(): Boolean = !isDir
    override fun length(): Long = docLength
    override fun lastModified(): Long = docLastModified
    override fun exists(): Boolean = true
    override fun canRead(): Boolean = true
    override fun canWrite(): Boolean = true
}
