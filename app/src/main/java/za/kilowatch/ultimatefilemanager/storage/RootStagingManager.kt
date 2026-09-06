package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.sync.advanced.InstantSyncWatcher
import za.kilowatch.ultimatefilemanager.util.MediaScannerNotifier
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages staging of root-protected files into an application-accessible cache directory,
 * enabling seamless editing and viewing in external applications (e.g. QuickEdit Pro)
 * without Linux kernel EACCES (Permission denied) errors.
 *
 * Automatically tracks file modifications and syncs changes back to the root partition
 * using elevated privileges while preserving original POSIX permissions, ownership,
 * and SELinux security contexts.
 */
object RootStagingManager {

    private const val TAG = "RootStagingManager"
    private const val STAGING_DIR_NAME = "root_staging"
    private const val META_FILE_NAME = ".meta.json"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class StagedMetadata(
        val originalPath: String,
        val stagedPath: String,
        val octalMode: String,
        val owner: String,
        val group: String,
        val selinuxContext: String,
        val originalLastModified: Long,
        val originalSize: Long,
        var stagedInitialHash: String,
        var stagedInitialMtime: Long,
        var stagedInitialSize: Long
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("originalPath", originalPath)
                put("stagedPath", stagedPath)
                put("octalMode", octalMode)
                put("owner", owner)
                put("group", group)
                put("selinuxContext", selinuxContext)
                put("originalLastModified", originalLastModified)
                put("originalSize", originalSize)
                put("stagedInitialHash", stagedInitialHash)
                put("stagedInitialMtime", stagedInitialMtime)
                put("stagedInitialSize", stagedInitialSize)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): StagedMetadata? {
                return try {
                    StagedMetadata(
                        originalPath = json.getString("originalPath"),
                        stagedPath = json.getString("stagedPath"),
                        octalMode = json.optString("octalMode", "644"),
                        owner = json.optString("owner", "0"),
                        group = json.optString("group", "0"),
                        selinuxContext = json.optString("selinuxContext", ""),
                        originalLastModified = json.optLong("originalLastModified", 0L),
                        originalSize = json.optLong("originalSize", 0L),
                        stagedInitialHash = json.optString("stagedInitialHash", ""),
                        stagedInitialMtime = json.optLong("stagedInitialMtime", 0L),
                        stagedInitialSize = json.optLong("stagedInitialSize", 0L)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing staged metadata: ${e.message}")
                    null
                }
            }
        }
    }

    /** Cache of active metadata mapped by original root path */
    private val activeByOriginal = ConcurrentHashMap<String, StagedMetadata>()
    /** Cache of active metadata mapped by staged local path */
    private val activeByStaged = ConcurrentHashMap<String, StagedMetadata>()

    /**
     * Determines whether [path] resides on a root partition and requires root staging
     * for external apps to open or edit it.
     */
    fun isRootFile(context: Context, path: String): Boolean {
        if (!RootShellWrapper.isRootPath(path)) return false
        if (!RootShellWrapper.isAuthorized(context)) return false

        // Paths in /data, /system, /vendor, /apex, /sbin, /etc or any path where standard java File cannot read
        val file = File(path)
        val canRead = try { file.canRead() } catch (_: Exception) { false }
        val canWrite = try { file.canWrite() } catch (_: Exception) { false }

        val isSystemOrData = path.startsWith("/data") || path.startsWith("/system") ||
                path.startsWith("/vendor") || path.startsWith("/apex") || path.startsWith("/sbin") ||
                path.startsWith("/product") || path.startsWith("/system_ext")

        return !canRead || !canWrite || isSystemOrData
    }

    /**
     * Stages a root file into the app cache directory with full metadata preservation.
     * The staged file is owned by UFM's application UID, making it directly accessible
     * via [UfmFileProvider] to external editors without permission issues.
     */
    @Synchronized
    fun stageFile(context: Context, originalPath: String, force: Boolean = false): File? {
        if (!RootShellWrapper.isAuthorized(context)) {
            Log.w(TAG, "Root not authorized, cannot stage $originalPath")
            return null
        }

        try {
            val hashKey = sha256Hex(originalPath).take(16)
            val stagingDir = File(context.cacheDir, "$STAGING_DIR_NAME/$hashKey").apply { mkdirs() }
            val fileName = File(originalPath).name
            val stagedFile = File(stagingDir, fileName)
            val metaFile = File(stagingDir, META_FILE_NAME)

            // If already staged and not forced, check if root file was modified since
            val rootMtime = RootShellWrapper.getLastModified(originalPath)
            val rootSize = RootShellWrapper.getFileSize(originalPath)

            if (!force && stagedFile.exists() && metaFile.exists()) {
                val existingMeta = loadMetadataFromFile(metaFile)
                if (existingMeta != null) {
                    // If root file timestamp hasn't changed, reuse staged file
                    if (existingMeta.originalLastModified == rootMtime && existingMeta.originalSize == rootSize) {
                        activeByOriginal[originalPath] = existingMeta
                        activeByStaged[stagedFile.absolutePath] = existingMeta
                        return stagedFile
                    }
                }
            }

            // Read root file content into staged file owned by UFM UID
            RootShellWrapper.openInputStream(originalPath).use { input ->
                stagedFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Capture original root POSIX mode (e.g. "644", "755")
            val safePath = RootShellWrapper.escapeShellPath(originalPath)
            val (codeMode, outMode) = RootShellWrapper.runCommand("stat -c \"%a\" '$safePath' 2>/dev/null")
            val octalMode = if (codeMode == 0 && outMode.isNotEmpty()) outMode.first().trim() else "644"

            // Capture owner and group IDs
            val (codeOwner, outOwner) = RootShellWrapper.runCommand("stat -c \"%u\" '$safePath' 2>/dev/null")
            val owner = if (codeOwner == 0 && outOwner.isNotEmpty()) outOwner.first().trim() else "0"

            val (codeGroup, outGroup) = RootShellWrapper.runCommand("stat -c \"%g\" '$safePath' 2>/dev/null")
            val group = if (codeGroup == 0 && outGroup.isNotEmpty()) outGroup.first().trim() else "0"

            // Capture SELinux context (e.g. "u:object_r:system_file:s0")
            val selinux = RootShellWrapper.getSelinuxContext(originalPath) ?: ""

            val stagedHash = computeFileSha256(stagedFile)
            val stagedMtime = stagedFile.lastModified()
            val stagedSize = stagedFile.length()

            val metadata = StagedMetadata(
                originalPath = originalPath,
                stagedPath = stagedFile.absolutePath,
                octalMode = octalMode,
                owner = owner,
                group = group,
                selinuxContext = selinux,
                originalLastModified = rootMtime,
                originalSize = rootSize,
                stagedInitialHash = stagedHash,
                stagedInitialMtime = stagedMtime,
                stagedInitialSize = stagedSize
            )

            // Persist metadata to disk
            metaFile.writeText(metadata.toJson().toString())

            activeByOriginal[originalPath] = metadata
            activeByStaged[stagedFile.absolutePath] = metadata

            Log.d(TAG, "Staged root file $originalPath -> ${stagedFile.absolutePath} (mode=$octalMode, owner=$owner:$group, selinux=$selinux)")
            return stagedFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage root file: $originalPath", e)
            return null
        }
    }

    /**
     * Gets an existing staged file or stages it if not already present.
     */
    fun getOrStageFile(context: Context, originalPath: String): File? {
        val existingMeta = activeByOriginal[originalPath] ?: findMetadataOnDisk(context, originalPath)
        if (existingMeta != null) {
            val stagedFile = File(existingMeta.stagedPath)
            if (stagedFile.exists()) {
                activeByOriginal[originalPath] = existingMeta
                activeByStaged[stagedFile.absolutePath] = existingMeta
                return stagedFile
            }
        }
        return stageFile(context, originalPath)
    }

    /**
     * Resolves the original root path for a given staged local file path.
     */
    fun getOriginalPathForStagedPath(stagedPath: String): String? {
        activeByStaged[stagedPath]?.let { return it.originalPath }
        val stagedFile = File(stagedPath)
        val metaFile = File(stagedFile.parentFile, META_FILE_NAME)
        if (metaFile.exists()) {
            val meta = loadMetadataFromFile(metaFile)
            if (meta != null) {
                activeByOriginal[meta.originalPath] = meta
                activeByStaged[stagedPath] = meta
                return meta.originalPath
            }
        }
        return null
    }

    /**
     * Gets file size for a root path, using the staged file size if available or querying root stat.
     */
    fun getStagedOrRootFileSize(context: Context, originalPath: String): Long {
        val meta = activeByOriginal[originalPath] ?: findMetadataOnDisk(context, originalPath)
        if (meta != null) {
            val f = File(meta.stagedPath)
            if (f.exists()) return f.length()
        }
        return RootShellWrapper.getFileSize(originalPath)
    }

    /**
     * Checks whether the staged copy has been modified by an external editor.
     */
    fun isModified(metadata: StagedMetadata): Boolean {
        val stagedFile = File(metadata.stagedPath)
        if (!stagedFile.exists()) return false

        // Fast check: length or lastModified changed
        if (stagedFile.length() != metadata.stagedInitialSize) return true
        if (stagedFile.lastModified() != metadata.stagedInitialMtime) {
            // Confirm with content hash check
            val currentHash = computeFileSha256(stagedFile)
            return currentHash != metadata.stagedInitialHash
        }
        return false
    }

    /**
     * Syncs any modifications made to the staged file back to the root destination.
     * Preserves original POSIX permissions, ownership, and SELinux contexts.
     */
    @Synchronized
    fun syncBackToRoot(context: Context, originalPath: String, force: Boolean = false): Boolean {
        val metadata = activeByOriginal[originalPath] ?: findMetadataOnDisk(context, originalPath)
        if (metadata == null) {
            Log.w(TAG, "No metadata found to sync back for $originalPath")
            return false
        }

        val stagedFile = File(metadata.stagedPath)
        if (!stagedFile.exists()) {
            Log.w(TAG, "Staged file missing: ${metadata.stagedPath}")
            return false
        }

        if (!force && !isModified(metadata)) {
            Log.d(TAG, "File $originalPath not modified, skipping sync-back")
            return true
        }

        if (!RootShellWrapper.isAuthorized(context)) {
            Log.e(TAG, "Root not authorized, cannot sync back to $originalPath")
            return false
        }

        Log.i(TAG, "Writing modified staged file back to root: $originalPath")
        try {
            // Remount partition as read-write
            RootShellWrapper.remount(originalPath, rw = true)

            // Safe write via temporary file next to destination to prevent truncation on interruption
            val safeOriginal = RootShellWrapper.escapeShellPath(originalPath)
            val tempPath = "${originalPath}.ufm_tmp_${System.currentTimeMillis()}"
            val safeTemp = RootShellWrapper.escapeShellPath(tempPath)

            var writeSuccess = false
            try {
                RootShellWrapper.openOutputStream(tempPath).use { out ->
                    stagedFile.inputStream().use { input ->
                        input.copyTo(out)
                        out.flush()
                    }
                }
                // Atomic replace
                val (mvCode, _) = RootShellWrapper.runCommand("mv -f '$safeTemp' '$safeOriginal'")
                writeSuccess = (mvCode == 0)
            } catch (e: Exception) {
                Log.w(TAG, "Atomic temp write failed for $originalPath, attempting direct stream: ${e.message}")
            } finally {
                // Ensure temp file is cleaned up if mv didn't consume it
                RootShellWrapper.runCommand("rm -f '$safeTemp'")
            }

            // Fallback to direct stream write if atomic mv failed
            if (!writeSuccess) {
                RootShellWrapper.openOutputStream(originalPath).use { out ->
                    stagedFile.inputStream().use { input ->
                        input.copyTo(out)
                        out.flush()
                    }
                }
                writeSuccess = true
            }

            // Restore original POSIX mode
            if (metadata.octalMode.isNotEmpty()) {
                RootShellWrapper.chmod(originalPath, metadata.octalMode)
            }

            // Restore original owner & group
            if (metadata.owner.isNotEmpty() && metadata.group.isNotEmpty()) {
                RootShellWrapper.chown(originalPath, metadata.owner, metadata.group)
            }

            // Restore original SELinux security context
            if (metadata.selinuxContext.isNotEmpty()) {
                RootShellWrapper.setSelinuxContext(originalPath, metadata.selinuxContext)
            }

            // Update metadata with new baseline
            metadata.stagedInitialHash = computeFileSha256(stagedFile)
            metadata.stagedInitialMtime = stagedFile.lastModified()
            metadata.stagedInitialSize = stagedFile.length()

            val metaFile = File(stagedFile.parentFile, META_FILE_NAME)
            metaFile.writeText(metadata.toJson().toString())

            // Notify directory change
            val parentPath = File(originalPath).parent ?: "/"
            InstantSyncWatcher.notifyDirectoryChanged(context, parentPath)
            MediaScannerNotifier.scanFiles(context, listOf(originalPath))

            Log.i(TAG, "Successfully synced changes back to root: $originalPath")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing back to root: $originalPath", e)
            return false
        }
    }

    /**
     * Asynchronously syncs staged file changes back to root on an IO thread.
     */
    fun syncBackToRootAsync(context: Context, originalPath: String, onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch {
            val success = syncBackToRoot(context, originalPath)
            onComplete?.invoke(success)
        }
    }

    /**
     * Scans all staged root files and syncs any that were modified back to their root destinations.
     * Called during Activity/Fragment [onResume] to ensure changes made in external editors
     * are persisted reliably.
     *
     * @return count of successfully synced files.
     */
    fun syncAllPending(context: Context): Int {
        var syncedCount = 0
        val stagingBase = File(context.cacheDir, STAGING_DIR_NAME)
        if (!stagingBase.exists() || !stagingBase.isDirectory) return 0

        val subDirs = stagingBase.listFiles { f -> f.isDirectory } ?: return 0
        for (dir in subDirs) {
            val metaFile = File(dir, META_FILE_NAME)
            if (metaFile.exists()) {
                val meta = loadMetadataFromFile(metaFile) ?: continue
                if (isModified(meta)) {
                    val ok = syncBackToRoot(context, meta.originalPath)
                    if (ok) syncedCount++
                }
            }
        }
        return syncedCount
    }

    /**
     * Cleans up staged directories older than [olderThanMs] whose content is not pending sync.
     */
    fun cleanupOldStaging(context: Context, olderThanMs: Long = 24 * 60 * 60 * 1000L) {
        scope.launch {
            try {
                val stagingBase = File(context.cacheDir, STAGING_DIR_NAME)
                if (!stagingBase.exists() || !stagingBase.isDirectory) return@launch

                val now = System.currentTimeMillis()
                val subDirs = stagingBase.listFiles { f -> f.isDirectory } ?: return@launch
                for (dir in subDirs) {
                    val metaFile = File(dir, META_FILE_NAME)
                    if (metaFile.exists()) {
                        val meta = loadMetadataFromFile(metaFile)
                        if (meta != null && isModified(meta)) {
                            // Don't delete modified un-synced files!
                            continue
                        }
                    }
                    if (now - dir.lastModified() > olderThanMs) {
                        dir.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up old staging files: ${e.message}")
            }
        }
    }

    private fun findMetadataOnDisk(context: Context, originalPath: String): StagedMetadata? {
        val hashKey = sha256Hex(originalPath).take(16)
        val stagingDir = File(context.cacheDir, "$STAGING_DIR_NAME/$hashKey")
        val metaFile = File(stagingDir, META_FILE_NAME)
        return if (metaFile.exists()) loadMetadataFromFile(metaFile) else null
    }

    private fun loadMetadataFromFile(file: File): StagedMetadata? {
        return try {
            val json = JSONObject(file.readText())
            StagedMetadata.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun computeFileSha256(file: File): String {
        if (!file.exists() || file.length() > 50 * 1024 * 1024L) {
            // For very large files, use size + mtime as quick fingerprint
            return "${file.length()}_${file.lastModified()}"
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "${file.length()}_${file.lastModified()}"
        }
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
