package za.kilowatch.ultimatefilemanager.settings

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "network_thumbnails",
    primaryKeys = ["shareId", "networkPath"]
)
data class NetworkThumbnailEntity(
    val shareId: String,
    val networkPath: String,
    val localFileName: String,
    val sizeBytes: Long,
    val generatedDate: Long = System.currentTimeMillis(),
    val parentFolder: String // Added to enable easy cleanup by folder
)
