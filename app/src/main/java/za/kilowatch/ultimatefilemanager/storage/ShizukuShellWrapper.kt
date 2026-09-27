package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterBackend
import eu.darken.porter.sdk.extras.exec
import kotlinx.coroutines.runBlocking
import za.kilowatch.ultimatefilemanager.settings.ElevatedAccessPreferenceManager
import za.kilowatch.ultimatefilemanager.ui.elevated.ElevatedManager

object ShizukuShellWrapper {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHEVERY_PACKAGE = "com.hamondev.shevery"

    /**
     * The Porter **manager** app.
     *
     * Not Porter's Compatibility companion, which deliberately reuses [SHIZUKU_PACKAGE] and is out of
     * scope. Kept here with the other two so the three manager package ids have exactly one definition
     * each — a package string that drifts by one character fails silently.
     */
    const val PORTER_PACKAGE = "eu.darken.porter"


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

    fun isPorterInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PORTER_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isElevatedManagerInstalled(context: Context): Boolean {
        return isPorterInstalled(context) || isShizukuInstalled(context) || isSheveryInstalled(context)
    }

    /**
     * Manually binds to Shevery's provider and hands the resulting binder to the Shizuku client.
     *
     * FALLBACK ONLY — the SDK is the primary path, and this must never pre-empt or race it. The
     * pingBinder() guard below enforces that: when the SDK already holds a live binder we return
     * immediately and never call into Shevery. Callers must likewise reach this only after
     * confirming no binder is held. This path stays live rather than being dead code, which is why
     * the authority below has to be correct.
     *
     * Shevery publishes `com.hamondev.shevery.shizuku`. The previous authority here,
     * `com.hamondev.shevery.shizukuprovider`, does not exist — ContentResolver.call() returned
     * null and the bind fell through silently, so this path never actually worked on device.
     */
    /**
     * Manually requests Shevery to send its binder to our .shizuku provider.
     */
    fun tryBindShevery(context: Context? = null): Boolean {
        if (Porter.connection.value != null) return true
        val ctx = context ?: try {
            za.kilowatch.ultimatefilemanager.UfmApplication.instance
        } catch (_: Exception) { null } ?: return false

        return try {
            val uri = android.net.Uri.parse("content://$SHEVERY_PACKAGE.shizuku")
            ctx.contentResolver.call(uri, "sendBinder", null, null)
            Porter.connection.value != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Safely checks permission on the active Porter / Shizuku / Shevery connection.
     */
    fun checkPermissionSafely(context: Context? = null): Int {
        return if (isAuthorized(context)) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    /**
     * Safely requests permission via the active Porter connection.
     */
    fun requestPermissionSafely(requestCode: Int = 0, context: Context? = null): Boolean {
        val connection = Porter.connection.value ?: return false
        return try {
            val state = runBlocking { connection.requestPermission() }
            state is PermissionState.Granted
        } catch (e: Throwable) {
            Log.w("ShizukuShellWrapper", "requestPermissionSafely failed: ${e.message}")
            false
        }
    }

    /**
     * Suspending version of requestPermission directly returning PermissionState.
     */
    suspend fun requestPermission(): PermissionState {
        val connection = Porter.connection.value ?: return PermissionState.Denied(false)
        return connection.requestPermission()
    }

    fun isAuthorized(context: Context? = null): Boolean {
        val connection = Porter.connection.value ?: return false
        val isGranted = connection.permission.value is PermissionState.Granted
        if (!isGranted) return false

        val ctx = context ?: try {
            za.kilowatch.ultimatefilemanager.UfmApplication.instance
        } catch (_: Exception) { null }

        if (ctx != null) {
            val activeManager = when (connection.backend) {
                PorterBackend.PORTER -> ElevatedManager.PORTER
                PorterBackend.SHIZUKU -> {
                    if (isShizukuInstalled(ctx)) ElevatedManager.SHIZUKU else ElevatedManager.SHEVERY
                }
            }
            if (!ElevatedAccessPreferenceManager.isManagerEnabled(ctx, activeManager)) {
                return false
            }
        }
        return true
    }

    private val cachedPrimaryPrefixes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val cachedSdPrefixes = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun isProtectedPath(path: String): Boolean {
        return path.contains("/Android/data") || path.contains("/Android/obb")
    }

    fun canUseShizukuForPath(path: String): Boolean {
        return isProtectedPath(path) && isAuthorized()
    }

    /**
     * Extracts the Android multi-user ID from a given path (e.g. /storage/emulated/10 -> "10").
     * Defaults to primary user "0" if unparseable.
     */
    fun extractUserId(path: String): String {
        val emulatedMatch = Regex("^/storage/emulated/(\\d+)").find(path)
        if (emulatedMatch != null) return emulatedMatch.groupValues[1]
        val userMatch = Regex("^/mnt/user/(\\d+)").find(path)
        if (userMatch != null) return userMatch.groupValues[1]
        val passThroughMatch = Regex("^/mnt/pass_through/(\\d+)").find(path)
        if (passThroughMatch != null) return passThroughMatch.groupValues[1]
        val runtimeMatch = Regex("^/mnt/runtime/[^/]+/emulated/(\\d+)").find(path)
        if (runtimeMatch != null) return runtimeMatch.groupValues[1]
        return "0"
    }

    /**
     * Resolves the working path for shell commands.
     * On Android 11-14+, accessing `/storage/emulated/0/Android/data/<pkg>` via FUSE is blocked
     * by MediaProvider isolation. We route through pass-through or runtime mount points where UID 2000 has access.
     */
    fun getWorkingPath(path: String): String {
        if (!isProtectedPath(path)) return path

        val userId = extractUserId(path)

        // Primary emulated storage (/storage/emulated/<userId>, /sdcard, etc.)
        val emulatedPrefix = "/storage/emulated/$userId"
        val primaryMatch = when {
            path.startsWith(emulatedPrefix) -> path.removePrefix(emulatedPrefix)
            path.startsWith("/storage/emulated/0") -> path.removePrefix("/storage/emulated/0")
            path.startsWith("/sdcard") -> path.removePrefix("/sdcard")
            path.startsWith("/storage/self/primary") -> path.removePrefix("/storage/self/primary")
            path.startsWith("/mnt/sdcard") -> path.removePrefix("/mnt/sdcard")
            else -> null
        }

        if (primaryMatch != null) {
            val prefix = resolvePrimaryPrefix(userId)
            return "$prefix$primaryMatch"
        }

        // Secondary SD card / USB drive (/storage/<uuid>/...)
        val sdMatch = Regex("^/storage/([^/]+)(/.*)?").find(path)
        if (sdMatch != null) {
            val uuid = sdMatch.groupValues[1]
            val sub = sdMatch.groupValues[2]
            if (uuid != "emulated" && uuid != "self") {
                val prefix = resolveSdPrefix(uuid, userId)
                return "$prefix$sub"
            }
        }

        return path
    }

    private fun resolvePrimaryPrefix(userId: String = "0"): String {
        cachedPrimaryPrefixes[userId]?.let { return it }

        // Ordered by accessibility for UID 2000 / shell across Mobile and Android TV:
        // 1. /mnt/pass_through — Mobile FUSE passthrough (preserves exact proven mobile behavior)
        // 2. /mnt/runtime/full — Standard Android Vold mount granted to privileged/shell users (works on Android TV!)
        // 3. /mnt/user — User-specific storage mount
        // 4. Fallbacks for specific vendor ROMs
        val candidates = listOf(
            "/mnt/pass_through/$userId/emulated/$userId",
            "/mnt/runtime/full/emulated/$userId",
            "/mnt/user/$userId/emulated/$userId",
            "/mnt/user/$userId/primary",
            "/mnt/runtime/write/emulated/$userId",
            "/mnt/runtime/default/emulated/$userId",
            "/mnt/androidwritable/$userId/emulated/$userId",
            "/data/media/$userId"
        )
        for (cand in candidates) {
            val (code, _) = runCommand("test -d '$cand/Android'")
            if (code == 0) {
                cachedPrimaryPrefixes[userId] = cand
                Log.d("ShizukuShellWrapper", "Resolved primary prefix for user $userId: $cand")
                return cand
            }
        }

        // If standard candidates failed, try inspecting /proc/mounts dynamically
        val dynamicMount = discoverMountFromProc(userId)
        if (dynamicMount != null) {
            cachedPrimaryPrefixes[userId] = dynamicMount
            Log.d("ShizukuShellWrapper", "Resolved dynamic primary prefix for user $userId: $dynamicMount")
            return dynamicMount
        }

        val fallback = "/storage/emulated/$userId"
        cachedPrimaryPrefixes[userId] = fallback
        Log.w("ShizukuShellWrapper", "Falling back to default prefix for user $userId: $fallback")
        return fallback
    }

    private fun discoverMountFromProc(userId: String): String? {
        val (code, lines) = runCommand("cat /proc/mounts 2>/dev/null")
        if (code != 0 || lines.isEmpty()) return null
        for (line in lines) {
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val mountPoint = parts[1]
                if (mountPoint.contains("emulated/$userId") && !mountPoint.startsWith("/storage/emulated")) {
                    val (testCode, _) = runCommand("test -d '$mountPoint/Android'")
                    if (testCode == 0) return mountPoint
                }
            }
        }
        return null
    }

    private fun resolveSdPrefix(uuid: String, userId: String = "0"): String {
        cachedSdPrefixes[uuid]?.let { return it }

        val candidates = listOf(
            "/mnt/media_rw/$uuid",
            "/mnt/pass_through/$userId/$uuid",
            "/mnt/runtime/full/$uuid",
            "/mnt/user/$userId/$uuid",
            "/storage/$uuid"
        )
        for (cand in candidates) {
            val (code, _) = runCommand("test -d '$cand/Android' || test -d '$cand'")
            if (code == 0) {
                cachedSdPrefixes[uuid] = cand
                Log.d("ShizukuShellWrapper", "Resolved SD prefix for $uuid: $cand")
                return cand
            }
        }
        val fallback = "/storage/$uuid"
        cachedSdPrefixes[uuid] = fallback
        return fallback
    }

    fun exists(path: String): Boolean {
        val workingPath = getWorkingPath(path).trimEnd('/')
        val safePath = workingPath.replace("'", "'\\''")
        val (code, _) = runCommand("test -e '$safePath'")
        return code == 0
    }

    fun getFileSize(path: String): Long {
        val workingPath = getWorkingPath(path).trimEnd('/')
        val safePath = workingPath.replace("'", "'\\''")
        val (code, output) = runCommand("stat -c \"%s\" '$safePath' 2>/dev/null")
        return if (code == 0 && output.isNotEmpty()) output.first().trim().toLongOrNull() ?: 0L else 0L
    }

    fun getLastModified(path: String): Long {
        val workingPath = getWorkingPath(path).trimEnd('/')
        val safePath = workingPath.replace("'", "'\\''")
        val (code, output) = runCommand("stat -c \"%Y\" '$safePath' 2>/dev/null")
        return if (code == 0 && output.isNotEmpty()) (output.first().trim().toLongOrNull() ?: 0L) * 1000L else 0L
    }

    /**
     * Executes a shell command via Porter, Shizuku or Shevery.
     * Returns a pair of (exitCode, stdout_lines).
     */
    fun runCommand(cmd: String): Pair<Int, List<String>> {
        if (!isAuthorized()) return Pair(-1, emptyList())
        val connection = Porter.connection.value ?: return Pair(-1, emptyList())
        return try {
            val result = runBlocking {
                connection.exec("sh", "-c", cmd)
            }
            val lines = if (result.output.isEmpty()) emptyList() else result.output.trimEnd('\r', '\n').lines()
            Pair(result.exitCode, lines)
        } catch (e: Exception) {
            Log.w("ShizukuShellWrapper", "runCommand failed: ${e.message}")
            Pair(-1, emptyList())
        }
    }

    fun listFiles(path: String): List<java.io.File> {
        val workingPath = getWorkingPath(path).trimEnd('/')
        val safeWorkingPath = workingPath.replace("'", "'\\''")

        // 1. Primary listing attempt: stat -c with file type, size, mtime, name
        // Iterates safely over direct children including hidden dot-files
        val cmd = "for f in '$safeWorkingPath'/* '$safeWorkingPath'/.*; do " +
                "if [ -e \"\$f\" ] || [ -L \"\$f\" ]; then " +
                "if [ \"\$f\" != '$safeWorkingPath/.' ] && [ \"\$f\" != '$safeWorkingPath/..' ]; then " +
                "stat -c \"%F|%s|%Y|%n\" \"\$f\" 2>/dev/null; " +
                "fi; fi; done"
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

        // 2. Fallback listing attempt: if stat produced no entries (unsupported stat -c or restricted Toybox on Android TV)
        if (results.isEmpty()) {
            val fbCmd = "ls -1Ap '$safeWorkingPath' 2>/dev/null"
            val (fbCode, fbOutput) = runCommand(fbCmd)
            if (fbCode == 0 && fbOutput.isNotEmpty()) {
                for (rawLine in fbOutput) {
                    val entry = rawLine.trim()
                    if (entry.isEmpty() || entry == "." || entry == ".." || entry == "./" || entry == "../") continue
                    val isDir = entry.endsWith("/")
                    val name = entry.trimEnd('/')
                    if (name.isEmpty()) continue
                    results.add(ShizukuFile(path, name, isDir, 0L, 0L))
                }
            }
        }

        Log.d("ShizukuShellWrapper", "listFiles: path=$path -> workingPath=$workingPath, count=${results.size}")
        return results
    }

    fun delete(path: String): Boolean {
        val workingPath = getWorkingPath(path).trimEnd('/')
        val safePath = workingPath.replace("'", "'\\''")
        val (code, _) = runCommand("rm -rf '$safePath'")
        return code == 0
    }

    fun copy(src: String, dest: String): Boolean {
        val workingSrc = getWorkingPath(src).trimEnd('/')
        val workingDest = getWorkingPath(dest).trimEnd('/')
        val safeSrc = workingSrc.replace("'", "'\\''")
        val safeDest = workingDest.replace("'", "'\\''")
        val (code, _) = runCommand("cp -r '$safeSrc' '$safeDest'")
        runCommand("chmod -R 777 '$safeDest'")
        return code == 0
    }

    fun move(src: String, dest: String): Boolean {
        val workingSrc = getWorkingPath(src).trimEnd('/')
        val workingDest = getWorkingPath(dest).trimEnd('/')
        val safeSrc = workingSrc.replace("'", "'\\''")
        val safeDest = workingDest.replace("'", "'\\''")
        val (code, _) = runCommand("mv '$safeSrc' '$safeDest'")
        return code == 0
    }

    fun mkdir(path: String): Boolean {
        val workingPath = getWorkingPath(path).trimEnd('/')
        val safePath = workingPath.replace("'", "'\\''")
        val (code, _) = runCommand("mkdir -p '$safePath'")
        return code == 0
    }
}
