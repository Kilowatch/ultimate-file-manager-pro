package za.kilowatch.ultimatefilemanager.bookmarks.db

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.util.UUID

class BookmarkRepository private constructor(private val context: Context) {

    private val db = BookmarkDatabase.getInstance(context)
    private val dao = db.bookmarkDao()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            ensureDefaultGroups()
        }
    }

    suspend fun ensureDefaultGroups() = withContext(Dispatchers.IO) {
        val existing = dao.getAllGroupsSync()
        if (existing.isEmpty()) {
            val defaults = listOf(
                BookmarkGroupEntity(
                    id = "group_web",
                    name = context.getString(R.string.bookmark_group_web),
                    iconResName = "ic_network",
                    colorHex = "#3B82F6",
                    isAutoGroup = true,
                    associatedPackage = null,
                    sortOrder = 1
                ),
                BookmarkGroupEntity(
                    id = "group_whatsapp",
                    name = context.getString(R.string.bookmark_group_whatsapp),
                    iconResName = "ic_folder",
                    colorHex = "#25D366",
                    isAutoGroup = true,
                    associatedPackage = "com.whatsapp",
                    sortOrder = 2
                ),
                BookmarkGroupEntity(
                    id = "group_reddit",
                    name = context.getString(R.string.bookmark_group_reddit),
                    iconResName = "ic_folder",
                    colorHex = "#FF4500",
                    isAutoGroup = true,
                    associatedPackage = "com.reddit.frontpage",
                    sortOrder = 3
                ),
                BookmarkGroupEntity(
                    id = "group_youtube",
                    name = context.getString(R.string.bookmark_group_youtube),
                    iconResName = "ic_file_video",
                    colorHex = "#FF0000",
                    isAutoGroup = true,
                    associatedPackage = "com.google.android.youtube",
                    sortOrder = 4
                ),
                BookmarkGroupEntity(
                    id = "group_twitter",
                    name = context.getString(R.string.bookmark_group_twitter),
                    iconResName = "ic_folder",
                    colorHex = "#1DA1F2",
                    isAutoGroup = true,
                    associatedPackage = "com.twitter.android",
                    sortOrder = 5
                ),
                BookmarkGroupEntity(
                    id = "group_telegram",
                    name = context.getString(R.string.bookmark_group_telegram),
                    iconResName = "ic_folder",
                    colorHex = "#0088CC",
                    isAutoGroup = true,
                    associatedPackage = "org.telegram.messenger",
                    sortOrder = 6
                ),
                BookmarkGroupEntity(
                    id = "group_files",
                    name = context.getString(R.string.bookmark_group_files),
                    iconResName = "ic_file_generic",
                    colorHex = "#8B5CF6",
                    isAutoGroup = true,
                    associatedPackage = null,
                    sortOrder = 7
                )
            )
            defaults.forEach { dao.insertGroup(it) }
        }
    }

    suspend fun getOrCreateGroupForPackage(pkg: String?, appName: String): BookmarkGroupEntity = withContext(Dispatchers.IO) {
        if (!pkg.isNullOrBlank()) {
            val existing = dao.getGroupByPackage(pkg)
            if (existing != null) return@withContext existing

            // Match known web browsers
            if (isBrowserPackage(pkg)) {
                val webGroup = dao.getGroupById("group_web")
                if (webGroup != null) return@withContext webGroup
            }

            // Create dynamic smart auto-group for unrecognized third-party app
            val newGroup = BookmarkGroupEntity(
                id = "group_${UUID.randomUUID()}",
                name = appName.ifBlank { "App" },
                iconResName = "ic_folder",
                colorHex = pickAccentColor(pkg),
                isAutoGroup = true,
                associatedPackage = pkg,
                sortOrder = 100
            )
            dao.insertGroup(newGroup)
            return@withContext newGroup
        }

        return@withContext dao.getGroupById("group_web")
            ?: dao.getAllGroupsSync().firstOrNull()
            ?: BookmarkGroupEntity(
                id = "group_default",
                name = context.getString(R.string.bookmark_group_web),
                isAutoGroup = true
            ).also { dao.insertGroup(it) }
    }

    private fun isBrowserPackage(pkg: String): Boolean {
        return pkg in setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.opera.browser"
        )
    }

    private fun pickAccentColor(pkg: String): String {
        val colors = listOf("#3B82F6", "#10B981", "#8B5CF6", "#F59E0B", "#EC4899", "#06B6D4", "#6366F1")
        val index = (pkg.hashCode() and 0x7FFFFFFF) % colors.size
        return colors[index]
    }

    fun getAllBookmarks(): Flow<List<BookmarkEntity>> = dao.getAllBookmarks()
    fun getBookmarksByGroup(groupId: String): Flow<List<BookmarkEntity>> = dao.getBookmarksByGroup(groupId)
    fun searchBookmarks(query: String): Flow<List<BookmarkEntity>> = dao.searchBookmarks(query)
    fun getAllGroups(): Flow<List<BookmarkGroupEntity>> = dao.getAllGroups()
    suspend fun getAllGroupsSync(): List<BookmarkGroupEntity> = dao.getAllGroupsSync()

    fun getBookmarkCount(): Flow<Int> = dao.getBookmarkCount()
    suspend fun getBookmarkCountSync(): Int = dao.getBookmarkCountSync()
    fun getGroupCount(): Flow<Int> = dao.getGroupCount()
    suspend fun getGroupCountSync(): Int = dao.getGroupCountSync()

    suspend fun saveBookmark(item: BookmarkEntity): Pair<BookmarkEntity, Boolean> = withContext(Dispatchers.IO) {
        // Duplicate check on URL
        if (!item.url.isNullOrBlank()) {
            val existing = dao.getBookmarkByUrl(item.url)
            if (existing != null) {
                val combinedNotes = when {
                    item.notes.isNullOrBlank() -> existing.notes
                    existing.notes.isNullOrBlank() -> item.notes
                    else -> "${existing.notes}\n${item.notes}"
                }
                val updated = existing.copy(
                    updatedAt = System.currentTimeMillis(),
                    notes = combinedNotes
                )
                dao.updateBookmark(updated)
                return@withContext Pair(updated, true)
            }
        }

        // Duplicate check on FilePath
        if (!item.filePath.isNullOrBlank()) {
            val existing = dao.getBookmarkByFilePath(item.filePath)
            if (existing != null) {
                val updated = existing.copy(
                    updatedAt = System.currentTimeMillis()
                )
                dao.updateBookmark(updated)
                return@withContext Pair(updated, true)
            }
        }

        dao.insertBookmark(item)
        return@withContext Pair(item, false)
    }

    suspend fun updateBookmark(item: BookmarkEntity) = withContext(Dispatchers.IO) {
        dao.updateBookmark(item)
    }

    suspend fun deleteBookmark(item: BookmarkEntity, purgeFiles: Boolean = true) = withContext(Dispatchers.IO) {
        dao.deleteBookmark(item)
        if (purgeFiles) {
            item.filePath?.let { runCatching { File(it).delete() } }
            item.thumbnailPath?.let { runCatching { File(it).delete() } }
            item.faviconPath?.let { runCatching { File(it).delete() } }
        }
    }

    suspend fun deleteBookmarks(items: List<BookmarkEntity>, purgeFiles: Boolean = true) = withContext(Dispatchers.IO) {
        dao.deleteBookmarks(items)
        if (purgeFiles) {
            items.forEach { item ->
                item.filePath?.let { runCatching { File(it).delete() } }
                item.thumbnailPath?.let { runCatching { File(it).delete() } }
                item.faviconPath?.let { runCatching { File(it).delete() } }
            }
        }
    }

    suspend fun insertGroup(group: BookmarkGroupEntity) = withContext(Dispatchers.IO) {
        dao.insertGroup(group)
    }

    suspend fun updateGroup(group: BookmarkGroupEntity) = withContext(Dispatchers.IO) {
        dao.updateGroup(group)
    }

    suspend fun deleteGroup(group: BookmarkGroupEntity) = withContext(Dispatchers.IO) {
        dao.deleteGroup(group)
    }

    companion object {
        @Volatile private var INSTANCE: BookmarkRepository? = null

        fun getInstance(context: Context): BookmarkRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = BookmarkRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
