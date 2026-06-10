package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import java.nio.file.AccessDeniedException

/**
 * Implementation of [FileSystemView] for the File Server VFS.
 *
 * Security notes:
 * - M-1: [resolvePath] clamps `..` at the virtual root and [assertWithinRoot]
 *   validates that no path component escapes the virtual `/` root. Since all
 *   paths are relative to the profile's rootUri (resolved in [UfmFileSystemBridge]),
 *   no absolute filesystem path is exposed here.
 */
class UfmFileSystemView(
    private val context: Context,
    private val user: User,
    private val rootUri: String,
    private val readOnly: Boolean = false
) : FileSystemView {

    private var currentDir: String = "/"

    override fun getHomeDirectory(): FtpFile = UfmFtpFile(context, "/", user, rootUri, readOnly = readOnly)

    override fun getWorkingDirectory(): FtpFile = UfmFtpFile(context, currentDir, user, rootUri, readOnly = readOnly)

    override fun changeWorkingDirectory(dir: String): Boolean {
        val target = resolvePath(currentDir, dir)
        val file = UfmFtpFile(context, target, user, rootUri, readOnly = readOnly)
        if (file.isDirectory) {
            currentDir = target
            return true
        }
        return false
    }

    override fun getFile(file: String): FtpFile {
        val target = resolvePath(currentDir, file)
        return UfmFtpFile(context, target, user, rootUri, readOnly = readOnly)
    }

    override fun isRandomAccessible(): Boolean = false

    override fun dispose() {}

    /**
     * Resolves [sub] relative to [base], normalising `.` and `..` components.
     * M-1: `..` above root is clamped so the result always begins with `/`.
     */
    private fun resolvePath(base: String, sub: String): String {
        val combined = if (sub.startsWith("/")) sub else {
            if (base == "/") "/$sub" else "$base/$sub"
        }
        val parts = combined.split("/").filter { it.isNotEmpty() && it != "." }
        val resolved = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
                // Empty stack: .. at root is silently clamped — cannot escape to parent.
            } else {
                resolved.add(part)
            }
        }
        return "/" + resolved.joinToString("/")
    }
}
