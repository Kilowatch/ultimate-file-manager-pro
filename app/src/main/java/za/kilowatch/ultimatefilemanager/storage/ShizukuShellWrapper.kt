package za.kilowatch.ultimatefilemanager.storage

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuShellWrapper {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHEVERY_PACKAGE = "com.hamondev.shevery"

    @Volatile
    private var cachedPrimaryPrefix: String? = null
    private val cachedSdPrefixes = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun isShizukuInstalled(context: android.content.Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isSheveryInstalled(context: android.content.Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHEVERY_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isElevatedManagerInstalled(context: android.content.Context): Boolean {
        return isShizukuInstalled(context) || isSheveryInstalled(context)
    }

    fun tryBindShevery(context: android.content.Context? = null): Boolean {
        if (Shizuku.pingBinder()) return true
        val ctx = context ?: try {
            za.kilowatch.ultimatefilemanager.UfmApplication.instance
        } catch (e: Exception) {
            null
        } ?: return false

        return try {
            val uri = android.net.Uri.parse("content://$SHEVERY_PACKAGE.shizukuprovider")
            val reply = ctx.contentResolver.call(uri, "sendBinder", null, null)
            val binder = reply?.getBinder("moe.shizuku.privileged.api.intent.extra.BINDER")
                ?: reply?.getBinder("binder")
            if (binder != null) {
                try {
                    val method = Shizuku::class.java.getDeclaredMethod("setProviderBinder", android.os.IBinder::class.java)
                    method.isAccessible = true
                    method.invoke(null, binder)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                Shizuku.pingBinder()
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isAuthorized(context: android.content.Context? = null): Boolean {
        return try {
            if (!Shizuku.pingBinder()) {
                tryBindShevery(context)
            }
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

    /**
     * Resolves the working path for shell commands.
     * On Android 11-14+, accessing `/storage/emulated/0/Android/data/<pkg>` via FUSE is blocked
     * by MediaProvider isolation. We route through pass-through mount points where UID 2000 has access.
     */
    fun getWorkingPath(path: String): String {
        if (!isProtectedPath(path)) return path

        // Primary emulated storage (/storage/emulated/0 or /sdcard)
        val primaryMatch = when {
            path.startsWith("/storage/emulated/0") -> path.removePrefix("/storage/emulated/0")
            path.startsWith("/sdcard") -> path.removePrefix("/sdcard")
            else -> null
        }

        if (primaryMatch != null) {
            val prefix = resolvePrimaryPrefix()
            return "$prefix$primaryMatch"
        }

        // Secondary SD card (/storage/XXXX-XXXX/...)
        val sdMatch = Regex("^/storage/([A-Fa-f0-9]{4}-[A-Fa-f0-9]{4})(.*)").find(path)
        if (sdMatch != null) {
            val uuid = sdMatch.groupValues[1]
            val sub = sdMatch.groupValues[2]
            val prefix = resolveSdPrefix(uuid)
            return "$prefix$sub"
        }

        return path
    }

    private fun resolvePrimaryPrefix(): String {
        cachedPrimaryPrefix?.let { return it }
        
        val candidates = listOf(
            "/mnt/pass_through/0/emulated/0",
            "/data/media/0",
            "/mnt/androidwritable/0/emulated/0",
            "/storage/emulated/0"
        )
        for (cand in candidates) {
            val (code, _) = runCommand("test -d '$cand/Android'")
            if (code == 0) {
                cachedPrimaryPrefix = cand
                return cand
            }
        }
        val fallback = "/storage/emulated/0"
        cachedPrimaryPrefix = fallback
        return fallback
    }

    private fun resolveSdPrefix(uuid: String): String {
        cachedSdPrefixes[uuid]?.let { return it }

        val candidates = listOf(
            "/mnt/media_rw/$uuid",
            "/mnt/pass_through/0/$uuid",
            "/storage/$uuid"
        )
        for (cand in candidates) {
            val (code, _) = runCommand("test -d '$cand/Android'")
            if (code == 0) {
                cachedSdPrefixes[uuid] = cand
                return cand
            }
        }
        val fallback = "/storage/$uuid"
        cachedSdPrefixes[uuid] = fallback
        return fallback
    }

    fun exists(path: String): Boolean {
        val workingPath = getWorkingPath(path)
        val safePath = workingPath.replace("'", "'\\''")
        val (code, _) = runCommand("test -e '$safePath'")
        return code == 0
    }

    fun getFileSize(path: String): Long {
        val workingPath = getWorkingPath(path)
        val safePath = workingPath.replace("'", "'\\''")
        val (code, output) = runCommand("stat -c \"%s\" '$safePath' 2>/dev/null")
        return if (code == 0 && output.isNotEmpty()) output.first().trim().toLongOrNull() ?: 0L else 0L
    }

    fun getLastModified(path: String): Long {
        val workingPath = getWorkingPath(path)
        val safePath = workingPath.replace("'", "'\\''")
        val (code, output) = runCommand("stat -c \"%Y\" '$safePath' 2>/dev/null")
        return if (code == 0 && output.isNotEmpty()) (output.first().trim().toLongOrNull() ?: 0L) * 1000L else 0L
    }

    /**
     * Executes a shell command via Shizuku or Shevery.
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
        val workingPath = getWorkingPath(path)
        val safeWorkingPath = workingPath.replace("'", "'\\''")
        
        // Iterates safely over direct children without shell glob expansion errors on empty folders
        val cmd = "for f in '$safeWorkingPath'/* '$safeWorkingPath'/.*; do if [ -e \"\$f\" ] && [ \"\$f\" != '$safeWorkingPath/.' ] && [ \"\$f\" != '$safeWorkingPath/..' ]; then stat -c \"%F|%s|%Y|%n\" \"\$f\" 2>/dev/null; fi; done"
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
                if (name.isEmpty() || name == "." || name == "..") continue
                
                val isDir = fType.contains("directory", ignoreCase = true)
                results.add(ShizukuFile(path, name, isDir, size, modified))
            }
        }
        return results
    }

    fun delete(path: String): Boolean {
        val workingPath = getWorkingPath(path)
        val safePath = workingPath.replace("'", "'\\''")
        val (code, _) = runCommand("rm -rf '$safePath'")
        return code == 0
    }

    fun copy(src: String, dest: String): Boolean {
        val workingSrc = getWorkingPath(src)
        val workingDest = getWorkingPath(dest)
        val safeSrc = workingSrc.replace("'", "'\\''")
        val safeDest = workingDest.replace("'", "'\\''")
        val (code, _) = runCommand("cp -r '$safeSrc' '$safeDest'")
        runCommand("chmod -R 777 '$safeDest'")
        return code == 0
    }

    fun move(src: String, dest: String): Boolean {
        val workingSrc = getWorkingPath(src)
        val workingDest = getWorkingPath(dest)
        val safeSrc = workingSrc.replace("'", "'\\''")
        val safeDest = workingDest.replace("'", "'\\''")
        val (code, _) = runCommand("mv '$safeSrc' '$safeDest'")
        return code == 0
    }

    fun mkdir(path: String): Boolean {
        val workingPath = getWorkingPath(path)
        val safePath = workingPath.replace("'", "'\\''")
        val (code, _) = runCommand("mkdir -p '$safePath'")
        return code == 0
    }
}
