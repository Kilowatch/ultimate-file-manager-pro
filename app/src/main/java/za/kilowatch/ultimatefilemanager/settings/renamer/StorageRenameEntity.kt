package za.kilowatch.ultimatefilemanager.settings.renamer

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "storage_renames")
data class StorageRenameEntity(
    @PrimaryKey
    @ColumnInfo(name = "deviceId")
    val deviceId: String,
    
    @ColumnInfo(name = "customName")
    val customName: String,
    
    @ColumnInfo(name = "originalName")
    val originalName: String,
    
    @ColumnInfo(name = "totalBytes")
    val totalBytes: Long
)
