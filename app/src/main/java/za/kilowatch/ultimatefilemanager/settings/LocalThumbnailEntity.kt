package za.kilowatch.ultimatefilemanager.settings

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a cached thumbnail entry for a local file (internal storage, SD card, OTG, SAF).
 */
@Entity(
    tableName = "local_thumbnails",
    indices = [Index(value = ["parentFolder"])]
)
data class LocalThumbnailEntity(
    @PrimaryKey val filePath: String,
    val localFileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val fileSize: Long,
    val parentFolder: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
