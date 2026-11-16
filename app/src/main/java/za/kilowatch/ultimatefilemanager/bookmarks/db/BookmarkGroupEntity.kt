package za.kilowatch.ultimatefilemanager.bookmarks.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmark_groups",
    indices = [
        Index("associatedPackage"),
        Index("sortOrder")
    ]
)
data class BookmarkGroupEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val iconResName: String = "ic_folder",
    val colorHex: String = "#3B82F6",
    val isAutoGroup: Boolean = false,
    val associatedPackage: String? = null,
    val isLocked: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
