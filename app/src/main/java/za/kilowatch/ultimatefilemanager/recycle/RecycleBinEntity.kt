package za.kilowatch.ultimatefilemanager.recycle

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recycle_bin")
data class RecycleBinEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalPath: String = "",
    val trashPath: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = "*/*",
    val extension: String = "",
    val dateDeleted: Long = 0L,
    val storageType: String = "",
    val storageId: String = "",
    val storageLabel: String = "",
    val shareConfigJson: String? = null,
    val originalFolder: String = "",
    val isDirectory: Boolean = false
)
