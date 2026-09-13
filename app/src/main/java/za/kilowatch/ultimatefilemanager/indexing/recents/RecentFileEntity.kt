package za.kilowatch.ultimatefilemanager.indexing.recents

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecentFileSource {
    FULL_FS,
    SAF
}

/**
 * Normalized entity stored in SQLite for recently modified local files.
 * Capped to top 200 entries via LRU eviction in [RecentFileDao].
 */
@Entity(
    tableName = "recent_files",
    indices = [
        Index("lastModified", name = "idx_recents_mtime"),
        Index("source", name = "idx_recents_source"),
        Index("volumeId", name = "idx_recents_vol_id")
    ]
)
data class RecentFileEntity(
    @PrimaryKey
    val uriOrPath: String,              // Raw path for FULL tier, content Uri string for SAF
    val displayName: String,
    val lastModified: Long,             // Epoch millis UTC
    val volumeLabel: String,            // Human-readable: "Internal Storage", "SD Card", or SAF tree name
    val volumeId: String,               // "internal", UUID, or tree identifier for unmount pruning
    val source: String,                 // FULL_FS or SAF
    val sizeBytes: Long,
    val mimeType: String? = null,
    val accessedAt: Long = System.currentTimeMillis()
)

/**
 * Unified data model for presentation across all UI tiers.
 */
data class RecentFileItem(
    val displayName: String,
    val uriOrPath: String,
    val lastModified: Long,
    val volumeLabel: String,
    val volumeId: String,
    val source: RecentFileSource,
    val sizeBytes: Long,
    val mimeType: String? = null
)
