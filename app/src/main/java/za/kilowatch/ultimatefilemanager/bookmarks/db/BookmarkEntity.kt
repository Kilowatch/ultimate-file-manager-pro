package za.kilowatch.ultimatefilemanager.bookmarks.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Supported content types in the Smart Bookmark Hub.
 */
object BookmarkContentType {
    const val LINK = "LINK"
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
    const val AUDIO = "AUDIO"
    const val DOCUMENT = "DOCUMENT"
    const val TEXT_NOTE = "TEXT_NOTE"
}

@Entity(
    tableName = "bookmarks",
    indices = [
        Index("groupId"),
        Index("contentType"),
        Index("sourcePackage"),
        Index("createdAt"),
        Index("isStarred"),
        Index("url")
    ]
)
data class BookmarkEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val url: String? = null,
    val filePath: String? = null,
    val thumbnailPath: String? = null,
    val faviconPath: String? = null,
    val contentType: String = BookmarkContentType.LINK,
    val sourcePackage: String = "",
    val sourceAppName: String = "",
    val groupId: String,
    val groupName: String,
    val notes: String? = null,
    val cachedArticleText: String? = null,
    val fileSizeBytes: Long = 0L,
    val isStarred: Boolean = false,
    val isRead: Boolean = false,
    val isLocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
