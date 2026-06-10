package za.kilowatch.ultimatefilemanager.indexing

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * FileIndex Entity - Represents a file/directory entry in the indexing database.
 * Stores comprehensive metadata for fast search, navigation and duplicate detection.
 *
 * DB version 2: removed soft-delete flag (isDeleted). Deletions are now hard-deletes.
 *
 * Indexed columns for optimal query performance:
 * - path          unique — fast path lookups
 * - filename      — search functionality
 * - storageId     — filter by storage device
 * - lastModified  — change detection and sorting
 * - folderPath    — folder contents queries
 * - mimeType      — type-based search
 * - hash          — duplicate detection
 */
@Entity(
    tableName = "file_index",
    indices = [
        Index("path", unique = true, name = "idx_path"),
        Index("filename", name = "idx_filename"),
        Index("storageId", name = "idx_storageId"),
        Index("lastModified", name = "idx_lastModified"),
        Index("folderPath", name = "idx_folderPath"),
        Index("mimeType", name = "idx_mimeType"),
        Index("hash", name = "idx_hash")
    ]
)
data class FileIndex(
    @PrimaryKey
    val id: String,               // UUID — unique row identifier

    // File identification
    val path: String,             // Full absolute path  e.g.  /storage/emulated/0/DCIM/photo.jpg
    val filename: String,         // Filename only        e.g.  photo.jpg
    val extension: String,        // Extension            e.g.  jpg
    val folderPath: String,       // Parent directory path

    // File metadata
    val size: Long,               // Size in bytes
    val lastModified: Long,       // Last modification timestamp (ms)
    val createdDate: Long,        // Creation timestamp (ms)
    val mimeType: String,         // MIME type            e.g.  image/jpeg

    // Storage location
    val storageId: String,        // "internal" | "sdcard_<UUID>" | share id
    val storageType: String,      // internal | sdcard | usb | smb | ftp
    val isDirectory: Boolean,

    // Analysis
    val hash: String = "",
    val isHidden: Boolean = false,
    val isSymlink: Boolean = false,

    // Indexing timestamps
    val indexedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = System.currentTimeMillis()
    // isDeleted removed — hard deletes only (DB v2)
)
