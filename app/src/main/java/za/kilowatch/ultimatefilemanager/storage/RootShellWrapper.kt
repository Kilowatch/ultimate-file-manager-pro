package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import za.kilowatch.ultimatefilemanager.settings.RootPreferenceManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Superuser shell engine for root partition inspection, traversal, and I/O.
 * Backed by libsu ([Shell]) and elevated shell execution.
 */
object RootShellWrapper {

    private const val TAG = "RootShellWrapper"

    /**
     * Checks whether superuser shell is authorized and active.
     */
    fun isAuthorized(context: Context? = null): Boolean {
        if (context != null && !RootPreferenceManager.isRootEnabled(context)) {
            return false
        }
        return try {
            Shell.isAppGrantedRoot() == true || (Shell.isAppGrantedRoot() == null && Shell.getShell().isRoot)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks whether [path] is within the root system filesystem hierarchy.
     */
    fun isRootPath(path: String): Boolean {
        if (path.isEmpty()) return false
        if (path.startsWith("saf://") || path.startsWith("smb://") || path.startsWith("sftp://") ||
            path.startsWith("ftp://") || path.startsWith("nfs://") || path.startsWith("content://") ||
            path.startsWith("http://") || path.startsWith("https://")) {
            return false
        }
        if (path.startsWith("/storage/") || path.startsWith("/sdcard") || path.startsWith("/mnt/media_rw") ||
            path.startsWith("/mnt/user/") || path.startsWith("/mnt/runtime/") || path.startsWith("/mnt/expand/") ||
            path.startsWith("/mnt/sdcard")) {
            return false
        }
        // App's own private sandbox storage (cache, files, data dirs) is never part of the root hierarchy
        val app = try { za.kilowatch.ultimatefilemanager.UfmApplication.instance } catch (_: Throwable) { null }
        val pkg = app?.packageName ?: "za.kilowatch.ultimatefilemanager"
        if (path.contains("/$pkg/") || path.endsWith("/$pkg") || path.contains("za.kilowatch.ultimatefilemanager")) {
            return false
        }
        if (app != null) {
            try {
                if (path.startsWith(app.cacheDir.absolutePath) || path.startsWith(app.filesDir.absolutePath)) {
                    return false
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
                    path.startsWith(app.dataDir.absolutePath)) {
                    return false
                }
            } catch (_: Throwable) {}
        }
        return path == "/" || path.startsWith("/")
    }

    fun escapeShellPath(path: String): String {
        return path.replace("'", "'\\''")
    }

    fun runCommand(cmd: String): Pair<Int, List<String>> {
        return try {
            val result = Shell.cmd(cmd).exec()
            Pair(result.code, result.out)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing root cmd: $cmd", e)
            Pair(-1, emptyList())
        }
    }

    /**
     * Checks if a file or directory exists in root filesystem.
     */
    fun exists(path: String): Boolean {
        return try {
            val safePath = escapeShellPath(path)
            val (code, _) = runCommand("test -e '$safePath' || test -L '$safePath'")
            code == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Gets file size in bytes for a root path.
     */
    fun getFileSize(path: String): Long {
        return try {
            val safePath = escapeShellPath(path)
            val (code, out) = runCommand("stat -c \"%s\" '$safePath' 2>/dev/null")
            if (code == 0 && out.isNotEmpty()) out.first().trim().toLongOrNull() ?: 0L else 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Gets last modified timestamp in ms for a root path.
     */
    fun getLastModified(path: String): Long {
        return try {
            val safePath = escapeShellPath(path)
            val (code, out) = runCommand("stat -c \"%Y\" '$safePath' 2>/dev/null")
            if (code == 0 && out.isNotEmpty()) (out.first().trim().toLongOrNull() ?: 0L) * 1000L else 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Lists children of [path] as [RootFile] objects with detailed POSIX metadata.
     */
    fun listFiles(path: String): List<File> {
        val normPath = if (path.endsWith("/") && path.length > 1) path.trimEnd('/') else path
        val safePath = escapeShellPath(normPath)

        val cmd = "for f in '$safePath'/* '$safePath'/.*; do " +
                "if [ -e \"\$f\" ] || [ -L \"\$f\" ]; then " +
                "if [ \"\$f\" != '$safePath/.' ] && [ \"\$f\" != '$safePath/..' ]; then " +
                "stat -c \"%F|%s|%Y|%A (%a)|%U:%G|%N\" \"\$f\" 2>/dev/null || ls -ld \"\$f\" 2>/dev/null; " +
                "fi; " +
                "fi; " +
                "done"

        val (code, output) = runCommand(cmd)
        val results = mutableListOf<File>()

        if (code == 0 && output.isNotEmpty()) {
            for (line in output) {
                val parts = line.split("|", limit = 6)
                if (parts.size >= 6) {
                    val fType = parts[0]
                    val size = parts[1].toLongOrNull() ?: 0L
                    val modified = (parts[2].toLongOrNull() ?: 0L) * 1000L
                    val perms = parts[3]
                    val ownerGroup = parts[4]
                    val nameRaw = parts[5]

                    var fileName = nameRaw.trim().trim('\'', '`', '"')
                    var isSymlink = false
                    var symlinkTarget = ""

                    if (fileName.contains(" -> ")) {
                        val symParts = fileName.split(" -> ", limit = 2)
                        fileName = symParts[0].trim().trim('\'', '`', '"')
                        symlinkTarget = symParts[1].trim().trim('\'', '`', '"')
                        isSymlink = true
                    }

                    fileName = fileName.substringAfterLast('/')
                    if (fileName.isEmpty() || fileName == "." || fileName == "..") continue

                    val isDir = fType.contains("directory", ignoreCase = true)

                    results.add(
                        RootFile(
                            parentPath = normPath,
                            docName = fileName,
                            isDir = isDir,
                            docLength = size,
                            docLastModified = modified,
                            posixPermissions = perms,
                            ownerGroup = ownerGroup,
                            isSymlink = isSymlink,
                            symlinkTarget = symlinkTarget
                        )
                    )
                }
            }
        }

        // Fallback to ls -la if stat loop output was empty
        if (results.isEmpty()) {
            val (lsCode, lsOut) = runCommand("ls -la '$safePath' 2>/dev/null")
            if (lsCode == 0) {
                for (line in lsOut) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("total") || trimmed.isEmpty()) continue
                    val tokens = trimmed.split(Regex("\\s+"), limit = 9)
                    if (tokens.size < 9) continue
                    val perms = tokens[0]
                    val owner = tokens[2]
                    val group = tokens[3]
                    val size = tokens[4].toLongOrNull() ?: 0L
                    val rawEntry = tokens[8]
                    val isSymlink = perms.startsWith("l") || rawEntry.contains("->")
                    val isDir = perms.startsWith("d")

                    val (name, target) = if (rawEntry.contains("->")) {
                        val s = rawEntry.split("->", limit = 2)
                        s[0].trim() to s[1].trim()
                    } else {
                        rawEntry.trim() to ""
                    }
                    val docName = name.substringAfterLast('/')
                    if (docName.isEmpty() || docName == "." || docName == "..") continue

                    results.add(
                        RootFile(
                            parentPath = normPath,
                            docName = docName,
                            isDir = isDir,
                            docLength = size,
                            docLastModified = System.currentTimeMillis(),
                            posixPermissions = perms,
                            ownerGroup = "$owner:$group",
                            isSymlink = isSymlink,
                            symlinkTarget = target
                        )
                    )
                }
            }
        }

        return results
    }

    /**
     * Creates a directory in root filesystem.
     */
    fun mkdir(path: String): Boolean {
        remount(path, rw = true)
        val safePath = escapeShellPath(path)
        val (code, _) = runCommand("mkdir -p '$safePath'")
        return code == 0
    }

    /**
     * Creates an empty file in root filesystem.
     */
    fun createFile(path: String): Boolean {
        remount(path, rw = true)
        val safePath = escapeShellPath(path)
        val (code, _) = runCommand("touch '$safePath'")
        return code == 0
    }

    /**
     * Deletes a file or directory recursively from root filesystem.
     */
    fun delete(path: String): Boolean {
        remount(path, rw = true)
        val safePath = escapeShellPath(path)
        val (code, _) = runCommand("rm -rf '$safePath'")
        return code == 0
    }

    /**
     * Copies files or directories in root partitions.
     */
    fun copy(src: String, dest: String): Boolean {
        return try {
            remount(dest, rw = true)
            val safeSrc = escapeShellPath(src)
            val safeDest = escapeShellPath(dest)
            val (code, _) = runCommand("cp -rf '$safeSrc' '$safeDest'")
            code == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Moves / renames files or directories in root partitions.
     */
    fun move(src: String, dest: String): Boolean {
        return try {
            remount(dest, rw = true)
            remount(src, rw = true)
            val safeSrc = escapeShellPath(src)
            val safeDest = escapeShellPath(dest)
            val (code, _) = runCommand("mv '$safeSrc' '$safeDest'")
            code == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Changes POSIX permissions (e.g. "0644" or "0755") on a root path.
     */
    fun chmod(path: String, mode: String): Boolean {
        remount(path, rw = true)
        val safePath = escapeShellPath(path)
        val (code, _) = runCommand("chmod $mode '$safePath'")
        return code == 0
    }

    /**
     * Changes owner and group on a root path.
     */
    fun chown(path: String, owner: String, group: String): Boolean {
        remount(path, rw = true)
        val safePath = escapeShellPath(path)
        val (code, _) = runCommand("chown $owner:$group '$safePath'")
        return code == 0
    }

    /**
     * Remounts a partition as read-write (rw) or read-only (ro).
     */
    fun remount(path: String, rw: Boolean): Boolean {
        val mode = if (rw) "rw" else "ro"
        val safePath = escapeShellPath(path)
        val (code, _) = runCommand("mount -o $mode,remount '$safePath' 2>/dev/null || mount -o $mode,remount / 2>/dev/null || mount -o $mode,remount /system 2>/dev/null")
        return code == 0
    }

    /**
     * Retrieves POSIX permissions string (e.g., "-rw-r--r--" or "rwxr-xr-x (0755)")
     */
    fun getPosixPermissions(path: String): String? {
        val safePath = escapeShellPath(path)
        val (code, out) = runCommand("stat -c \"%A (%a)\" '$safePath' 2>/dev/null || ls -ld '$safePath' 2>/dev/null")
        if (code == 0 && out.isNotEmpty()) {
            val line = out.first().trim()
            return if (line.isNotEmpty()) line else null
        }
        return null
    }

    /**
     * Retrieves owner and group (e.g. "root : root" or "system : system")
     */
    fun getOwnerGroup(path: String): String? {
        val safePath = escapeShellPath(path)
        val (code, out) = runCommand("stat -c \"%U : %G\" '$safePath' 2>/dev/null")
        if (code == 0 && out.isNotEmpty()) {
            val line = out.first().trim()
            return if (line.isNotEmpty()) line else null
        }
        return null
    }

    /**
     * Retrieves SELinux security context (e.g. "u:object_r:system_file:s0")
     */
    fun getSelinuxContext(path: String): String? {
        val safePath = escapeShellPath(path)
        val (code, out) = runCommand("stat -c \"%C\" '$safePath' 2>/dev/null || ls -Zd '$safePath' 2>/dev/null")
        if (code == 0 && out.isNotEmpty()) {
            val line = out.first().trim()
            if (line.isNotEmpty() && line != "?") {
                return line.split(" ").firstOrNull { it.contains(":") } ?: line
            }
        }
        return null
    }

    /**
     * Opens an [InputStream] to read a root-protected file.
     */
    fun openInputStream(path: String): InputStream {
        if (!isAuthorized()) {
            throw java.io.IOException("Root access is not available or not authorized")
        }
        val safePath = escapeShellPath(path)
        val process = ProcessBuilder("su", "-c", "cat '$safePath'").start()
        return process.inputStream
    }

    /**
     * Opens an [OutputStream] to write to a root-protected file.
     */
    fun openOutputStream(path: String): OutputStream {
        if (!isAuthorized()) {
            throw java.io.IOException("Root access is not available or not authorized")
        }
        remount(path, rw = true)
        val safePath = escapeShellPath(path)
        val process = ProcessBuilder("su", "-c", "cat > '$safePath'").start()
        return process.outputStream
    }

    /**
     * Reads entire text content from a root file.
     */
    fun readText(path: String): String {
        val safePath = escapeShellPath(path)
        val (code, out) = runCommand("cat '$safePath'")
        return if (code == 0) out.joinToString("\n") else ""
    }

    /**
     * Writes text content to a root file with automatic remount.
     */
    fun writeText(path: String, text: String): Boolean {
        return try {
            remount(path, rw = true)
            openOutputStream(path).bufferedWriter(Charsets.UTF_8).use {
                it.write(text)
                it.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write root text to $path", e)
            false
        }
    }
}
