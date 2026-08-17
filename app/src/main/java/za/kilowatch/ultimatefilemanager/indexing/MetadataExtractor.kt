package za.kilowatch.ultimatefilemanager.indexing

import android.content.Context
import android.webkit.MimeTypeMap
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Metadata Extractor - Extracts comprehensive file metadata including:
 * - File attributes (name, size, modification times)
 * - MIME type detection
 * - Content hashing for duplicate detection
 * - Symbolic link detection
 * - Hidden file detection
 */
class MetadataExtractor(private val context: Context) {

    private val TAG = "MetadataExtractor"

    /**
     * Extract all available metadata from a file.
     *
     * @param file The file to extract metadata from
     * @param storageId Storage device identifier
     * @param storageType Type of storage (internal, sdcard, usb, smb, ftp, etc.)
     * @param hashAlgorithm Hash algorithm for duplicate detection (MD5, SHA256)
     * @return FileIndex containing extracted metadata
     */
    fun extractMetadata(
        file: File,
        storageId: String,
        storageType: String,
        hashAlgorithm: HashAlgorithm = HashAlgorithm.MD5_QUICK
    ): FileIndex {
        val id = UUID.randomUUID().toString()
        val path = file.absolutePath
        val filename = file.name
        val extension = getFileExtension(filename)
        val folderPath = file.parent ?: ""
        val size = file.length()
        val lastModified = file.lastModified()
        val mimeType = getMimeType(file)
        val isDirectory = file.isDirectory
        val isHidden = file.isHidden
        val isSymlink = isSymbolicLink(file)

        // Compute hash only for non-directories and files (controlled by hashAlgorithm parameter)
        val hash = when {
            isDirectory || size == 0L -> ""
            hashAlgorithm == HashAlgorithm.NONE -> ""
            hashAlgorithm == HashAlgorithm.MD5_QUICK -> {
                try {
                    // Very small quick MD5 hash (64KB) to minimize I/O and battery impact
                    val quickHashSize = 64L * 1024L // 64KB
                    hashFile(file, maxBytes = quickHashSize, algorithm = "MD5")
                } catch (e: Exception) {
                    GoRoLog.w(TAG, "Failed to compute quick hash for ${file.name}: ${e.message}")
                    ""
                }
            }
            hashAlgorithm == HashAlgorithm.MD5_FULL -> {
                try {
                    hashFile(file, algorithm = "MD5")
                } catch (e: Exception) {
                    GoRoLog.w(TAG, "Failed to compute full MD5 hash for ${file.name}: ${e.message}")
                    ""
                }
            }
            hashAlgorithm == HashAlgorithm.SHA256 -> {
                try {
                    hashFile(file, algorithm = "SHA-256")
                } catch (e: Exception) {
                    GoRoLog.w(TAG, "Failed to compute SHA256 hash for ${file.name}: ${e.message}")
                    ""
                }
            }
            else -> ""
        }

        val now = System.currentTimeMillis()

        return FileIndex(
            id = id,
            path = path,
            filename = filename,
            extension = extension,
            folderPath = folderPath,
            size = size,
            lastModified = lastModified,
            createdDate = now, // Android doesn't provide reliable creation time, use indexing time
            mimeType = mimeType,
            storageId = storageId,
            storageType = storageType,
            isDirectory = isDirectory,
            hash = hash,
            isHidden = isHidden,
            isSymlink = isSymlink,
            indexedAt = now,
            lastScannedAt = now
        )
    }

    /**
     * Update metadata for an existing file (for change detection).
     */
    fun updateMetadata(
        existingIndex: FileIndex,
        file: File,
        hashAlgorithm: HashAlgorithm = HashAlgorithm.MD5_QUICK
    ): FileIndex {
        val now = System.currentTimeMillis()
        val size = file.length()
        val lastModified = file.lastModified()

        // Only recompute hash if file size changed (likely file content changed)
        val newHash = if (size != existingIndex.size && hashAlgorithm != HashAlgorithm.NONE) {
            try {
                when (hashAlgorithm) {
                    HashAlgorithm.MD5_QUICK -> {
                        // Use the same smaller quick-hash size for updates to keep behavior consistent
                        val quickHashSize = 64L * 1024L
                        hashFile(file, maxBytes = quickHashSize, algorithm = "MD5")
                    }
                    HashAlgorithm.MD5_FULL -> hashFile(file, algorithm = "MD5")
                    HashAlgorithm.SHA256 -> hashFile(file, algorithm = "SHA-256")
                    else -> ""
                }
            } catch (e: Exception) {
                existingIndex.hash
            }
        } else {
            existingIndex.hash
        }

        return existingIndex.copy(
            size = size,
            lastModified = lastModified,
            hash = newHash,
            mimeType = getMimeType(file),
            lastScannedAt = now,
            indexedAt = now
        )
    }

    /**
     * Compute cryptographic hash of file content.
     *
     * @param file File to hash
     * @param algorithm Hash algorithm (MD5, SHA-256, etc.)
     * @param maxBytes Maximum bytes to read (null = entire file)
     * @return Hex string of hash
     */
    private fun hashFile(
        file: File,
        algorithm: String = "MD5",
        maxBytes: Long? = null
    ): String {
        val messageDigest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(8192)
        var bytesRead = 0L

        file.inputStream().use { input ->
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                val toWrite = if (maxBytes != null && bytesRead + len > maxBytes) {
                    (maxBytes - bytesRead).toInt()
                } else {
                    len
                }
                messageDigest.update(buffer, 0, toWrite)
                bytesRead += toWrite
                if (maxBytes != null && bytesRead >= maxBytes) break
            }
        }

        return messageDigest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Detect MIME type of a file.
     */
    private fun getMimeType(file: File): String {
        return when {
            file.isDirectory -> "application/x-directory"
            else -> {
                val ext = getFileExtension(file.name)
                za.kilowatch.ultimatefilemanager.util.MimeTypeHelper.getOrFallback(ext)
            }
        }
    }

    /**
     * Extract file extension (without dot).
     */
    private fun getFileExtension(filename: String): String {
        val lastDot = filename.lastIndexOf('.')
        return if (lastDot > 0 && lastDot < filename.length - 1) {
            filename.substring(lastDot + 1).lowercase()
        } else {
            ""
        }
    }

    /**
     * Check if a file is a symbolic link.
     */
    private fun isSymbolicLink(file: File): Boolean {
        return try {
            val canonical = file.canonicalPath
            val absolute = file.absolutePath
            canonical != absolute
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Hash algorithm options for duplicate detection.
     */
    enum class HashAlgorithm {
        NONE,           // No hashing
        MD5_QUICK,      // MD5 of first 1MB (fast, suitable for quick duplicate detection)
        MD5_FULL,       // Full file MD5 (slower, comprehensive)
        SHA256          // SHA-256 (slowest, most secure)
    }
}
