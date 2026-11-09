package za.kilowatch.ultimatefilemanager.download

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Validates storage destinations for downloads before writing data.
 * Checks:
 * 1. Filesystem limits: FAT32 4 GB single-file ceiling (4,294,967,295 bytes).
 * 2. Usable capacity: Target drive free space against payload size + 100 MB margin.
 * 3. Security & Sandboxing: Zip-Slip traversal prevention and restricted folder isolation
 *    (preventing writes to the Encrypted Vault, app private storage, or system directories).
 */
object StoragePreflightValidator {

    private const val TAG = "StoragePreflight"

    /** Maximum file size supported by FAT32 / VFAT (4 GiB minus 1 byte) */
    const val FAT32_MAX_FILE_BYTES: Long = 4_294_967_295L

    /** Safety buffer required over total payload size (100 MB) */
    const val SAFETY_MARGIN_BYTES: Long = 100L * 1024L * 1024L

    enum class ErrorType {
        NONE,
        FAT32_LIMIT_EXCEEDED,
        INSUFFICIENT_SPACE,
        RESTRICTED_DIRECTORY,
        PATH_TRAVERSAL,
        DIRECTORY_NOT_WRITABLE
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errorType: ErrorType = ErrorType.NONE,
        val requiredBytes: Long = 0L,
        val availableBytes: Long = 0L,
        val offendingFileName: String? = null,
        val offendingFileSize: Long = 0L
    ) {
        val isSuccessful: Boolean get() = isValid && errorType == ErrorType.NONE
    }

    /**
     * Validates whether a download payload can be safely written to [destinationDir].
     *
     * @param context Application context.
     * @param destinationDir Target directory on device or external drive.
     * @param totalSizeBytes Sum of all files in this transfer.
     * @param fileSizes Optional map of individual filename to file size (for FAT32 single-file check).
     */
    fun validate(
        context: Context,
        destinationDir: File,
        totalSizeBytes: Long,
        fileSizes: Map<String, Long> = emptyMap()
    ): ValidationResult {
        // 1. Path Traversal & Restricted Paths Security Check
        val securityResult = validateDirectorySecurity(context, destinationDir)
        if (!securityResult.isValid) return securityResult

        // 2. Directory writability check
        if (!destinationDir.exists()) {
            if (!destinationDir.mkdirs()) {
                return ValidationResult(
                    isValid = false,
                    errorType = ErrorType.DIRECTORY_NOT_WRITABLE
                )
            }
        }
        if (!destinationDir.canWrite()) {
            return ValidationResult(
                isValid = false,
                errorType = ErrorType.DIRECTORY_NOT_WRITABLE
            )
        }

        // 3. FAT32 4 GB Single-File Limit Check
        val isFat32Fs = isFat32Filesystem(context, destinationDir)
        if (isFat32Fs) {
            // Check individual files if provided
            for ((fileName, size) in fileSizes) {
                if (size > FAT32_MAX_FILE_BYTES) {
                    return ValidationResult(
                        isValid = false,
                        errorType = ErrorType.FAT32_LIMIT_EXCEEDED,
                        requiredBytes = size,
                        offendingFileName = fileName,
                        offendingFileSize = size
                    )
                }
            }
            // If individual files not specified but total size exceeds 4GB for a single download
            if (fileSizes.isEmpty() && totalSizeBytes > FAT32_MAX_FILE_BYTES) {
                return ValidationResult(
                    isValid = false,
                    errorType = ErrorType.FAT32_LIMIT_EXCEEDED,
                    requiredBytes = totalSizeBytes,
                    offendingFileName = destinationDir.name,
                    offendingFileSize = totalSizeBytes
                )
            }
        }

        // 4. Free Usable Space Check
        val availableBytes = getAvailableBytes(destinationDir)
        val neededWithBuffer = totalSizeBytes + SAFETY_MARGIN_BYTES

        if (totalSizeBytes > 0 && availableBytes < neededWithBuffer) {
            return ValidationResult(
                isValid = false,
                errorType = ErrorType.INSUFFICIENT_SPACE,
                requiredBytes = neededWithBuffer,
                availableBytes = availableBytes
            )
        }

        return ValidationResult(
            isValid = true,
            errorType = ErrorType.NONE,
            requiredBytes = totalSizeBytes,
            availableBytes = availableBytes
        )
    }

    /**
     * Checks if [relativeFilePath] would escape [baseDownloadDir] via path traversal (Zip-Slip).
     */
    fun isPathTraversalSafe(baseDownloadDir: File, relativeFilePath: String): Boolean {
        return try {
            val baseCanonical = baseDownloadDir.canonicalFile
            val targetFile = File(baseDownloadDir, relativeFilePath)
            val targetCanonical = targetFile.canonicalFile
            targetCanonical.path.startsWith(baseCanonical.path)
        } catch (e: Exception) {
            Log.w(TAG, "Path resolution failed for traversal check: $relativeFilePath", e)
            false
        }
    }

    /**
     * Validates that destination directory is not a sensitive or isolated area of the OS/App.
     */
    fun validateDirectorySecurity(context: Context, destinationDir: File): ValidationResult {
        return try {
            val canonicalPath = destinationDir.canonicalPath

            // Disallow root system directories
            val systemPrefixes = listOf("/system", "/vendor", "/product", "/system_ext", "/apex", "/bin", "/sbin")
            for (sys in systemPrefixes) {
                if (canonicalPath == sys || canonicalPath.startsWith("$sys/")) {
                    return ValidationResult(isValid = false, errorType = ErrorType.RESTRICTED_DIRECTORY)
                }
            }

            // Disallow app private internal files/cache dirs
            val appInternal = context.filesDir.parentFile?.canonicalPath
            if (appInternal != null && (canonicalPath == appInternal || canonicalPath.startsWith("$appInternal/"))) {
                return ValidationResult(isValid = false, errorType = ErrorType.RESTRICTED_DIRECTORY)
            }

            // Disallow UFM Encrypted Vault directory
            val vaultDir = File(context.filesDir, "vault").canonicalPath
            if (canonicalPath == vaultDir || canonicalPath.startsWith("$vaultDir/")) {
                return ValidationResult(isValid = false, errorType = ErrorType.RESTRICTED_DIRECTORY)
            }

            ValidationResult(isValid = true, errorType = ErrorType.NONE)
        } catch (e: Exception) {
            Log.e(TAG, "Security validation failed for path: ${destinationDir.path}", e)
            ValidationResult(isValid = false, errorType = ErrorType.RESTRICTED_DIRECTORY)
        }
    }

    /**
     * Returns usable free bytes for [dir] safely using StatFs.
     */
    fun getAvailableBytes(dir: File): Long {
        return try {
            val usable = dir.usableSpace
            if (usable > 0) return usable
            val stat = StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get usable space for ${dir.path}", e)
            0L
        }
    }

    /**
     * Determines whether the volume containing [file] is formatted with FAT32/VFAT.
     */
    fun isFat32Filesystem(context: Context, file: File): Boolean {
        val targetPath = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }

        // 1. Inspect /proc/mounts (standard Linux / Android mount table)
        try {
            val mountsFile = File("/proc/mounts")
            if (mountsFile.exists() && mountsFile.canRead()) {
                val lines = mountsFile.readLines()
                var longestMatchMount = ""
                var matchedFsType = ""

                for (line in lines) {
                    val tokens = line.split("\\s+".toRegex())
                    if (tokens.size >= 3) {
                        val mountPoint = tokens[1]
                        val fsType = tokens[2].lowercase()
                        if (targetPath.startsWith(mountPoint) && mountPoint.length > longestMatchMount.length) {
                            longestMatchMount = mountPoint
                            matchedFsType = fsType
                        }
                    }
                }

                if (matchedFsType.contains("vfat") || matchedFsType.contains("fat32") || matchedFsType == "msdos") {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not inspect /proc/mounts: ${e.message}")
        }

        // 2. Query Android StorageManager on API 24+
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null) {
                val volume = sm.getStorageVolume(file)
                if (volume != null) {
                    val desc = volume.getDescription(context).lowercase()
                    if (desc.contains("fat") || desc.contains("vfat")) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not inspect StorageManager volume: ${e.message}")
        }

        return false
    }
}
