package za.kilowatch.ultimatefilemanager.settings

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hidden_files")
data class HiddenFileEntity(
    @PrimaryKey val absolutePath: String,
    val dateHidden: Long = System.currentTimeMillis()
)
