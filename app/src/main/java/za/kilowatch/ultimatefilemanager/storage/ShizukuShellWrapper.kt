package za.kilowatch.ultimatefilemanager.storage

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuShellWrapper {

    fun isAuthorized(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun isProtectedPath(path: String): Boolean {
        return path.contains("/Android/data") || path.contains("/Android/obb")
    }

    fun canUseShizukuForPath(path: String): Boolean {
        return isProtectedPath(path) && isAuthorized()
    }

    fun exists(path: String): Boolean {
        val safePath = path.replace("'", "'\\''")
        val (code, _) = runCommand("test -e '$safePath'")
        return code == 0
    }

    /**
     * Executes a shell command via Shizuku.
     * Returns a pair of (exitCode, stdout_lines)
     */
    fun runCommand(cmd: String): Pair<Int, List<String>> {
        if (!isAuthorized()) return Pair(-1, emptyList())
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { lines.add(it) }
            }
            val exitCode = process.waitFor()
            return Pair(exitCode, lines)
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(-1, emptyList())
        }
    }

    fun listFiles(path: String): List<java.io.File> {
        val safePath = path.replace("'", "'\\''")
        val cmd = "stat -c \"%F|%s|%Y|%n\" '$safePath'/* '$safePath'/.* 2>/dev/null"
        val (code, output) = runCommand(cmd)
        
        val results = mutableListOf<java.io.File>()
        for (line in output) {
            val parts = line.split("|", limit = 4)
            if (parts.size == 4) {
                val fType = parts[0]
                val size = parts[1].toLongOrNull() ?: 0L
                val modified = (parts[2].toLongOrNull() ?: 0L) * 1000L
                val fullPath = parts[3]
                
                val name = fullPath.substringAfterLast("/")
                if (name == "." || name == "..") continue
                
                val isDir = fType.contains("directory", ignoreCase = true)
                results.add(ShizukuFile(path, name, isDir, size, modified))
            }
        }
        return results
    }

    fun delete(path: String): Boolean {
        val safePath = path.replace("'", "'\\''")
        val (code, _) = runCommand("rm -rf '$safePath'")
        return code == 0
    }

    fun copy(src: String, dest: String): Boolean {
        val safeSrc = src.replace("'", "'\\''")
        val safeDest = dest.replace("'", "'\\''")
        val (code, _) = runCommand("cp -r '$safeSrc' '$safeDest'")
        runCommand("chmod -R 777 '$safeDest'")
        return code == 0
    }

    fun move(src: String, dest: String): Boolean {
        val safeSrc = src.replace("'", "'\\''")
        val safeDest = dest.replace("'", "'\\''")
        val (code, _) = runCommand("mv '$safeSrc' '$safeDest'")
        return code == 0
    }

    fun mkdir(path: String): Boolean {
        val safePath = path.replace("'", "'\\''")
        val (code, _) = runCommand("mkdir -p '$safePath'")
        return code == 0
    }
}
