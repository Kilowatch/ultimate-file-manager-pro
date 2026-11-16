package za.kilowatch.ultimatefilemanager.bookmarks.db

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.bookmarks.util.BookmarkMetadataScraper
import java.io.File
import java.net.URI
import java.util.UUID

class BookmarkRepository private constructor(private val context: Context) {

    private val db = BookmarkDatabase.getInstance(context)
    private val dao = db.bookmarkDao()
    val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        repositoryScope.launch {
            dao.deleteEmptyAutoGroups()
        }
    }

    suspend fun cleanupEmptyAutoGroups() = withContext(Dispatchers.IO) {
        dao.deleteEmptyAutoGroups()
    }

    suspend fun resolveOrCreateGroup(url: String?, pkg: String?, appName: String): BookmarkGroupEntity = withContext(Dispatchers.IO) {
        // 1. YouTube check: URL or package
        if ((url != null && isYouTubeUrl(url)) || pkg == "com.google.android.youtube") {
            val existing = dao.getGroupByName("YouTube")
            if (existing != null) return@withContext existing
            val ytGroup = BookmarkGroupEntity(
                id = "group_youtube",
                name = "YouTube",
                iconResName = "ic_file_video",
                colorHex = "#FF0000",
                isAutoGroup = true,
                associatedPackage = "com.google.android.youtube",
                sortOrder = 10
            )
            dao.insertGroup(ytGroup)
            return@withContext ytGroup
        }

        // 2. Reddit check
        if ((url != null && (url.contains("reddit.com", ignoreCase = true) || url.contains("redd.it", ignoreCase = true))) || pkg == "com.reddit.frontpage") {
            val existing = dao.getGroupByName("Reddit")
            if (existing != null) return@withContext existing
            val redditGroup = BookmarkGroupEntity(
                id = "group_reddit",
                name = "Reddit",
                iconResName = "ic_folder",
                colorHex = "#FF4500",
                isAutoGroup = true,
                associatedPackage = "com.reddit.frontpage",
                sortOrder = 10
            )
            dao.insertGroup(redditGroup)
            return@withContext redditGroup
        }

        // 3. WhatsApp check
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            val existing = dao.getGroupByName("WhatsApp")
            if (existing != null) return@withContext existing
            val waGroup = BookmarkGroupEntity(
                id = "group_whatsapp",
                name = "WhatsApp",
                iconResName = "ic_folder",
                colorHex = "#25D366",
                isAutoGroup = true,
                associatedPackage = pkg,
                sortOrder = 10
            )
            dao.insertGroup(waGroup)
            return@withContext waGroup
        }

        // 4. X / Twitter check
        if ((url != null && (url.contains("twitter.com", ignoreCase = true) || url.contains("x.com", ignoreCase = true))) || pkg == "com.twitter.android") {
            val existing = dao.getGroupByName("X")
            if (existing != null) return@withContext existing
            val xGroup = BookmarkGroupEntity(
                id = "group_x",
                name = "X",
                iconResName = "ic_folder",
                colorHex = "#1DA1F2",
                isAutoGroup = true,
                associatedPackage = "com.twitter.android",
                sortOrder = 10
            )
            dao.insertGroup(xGroup)
            return@withContext xGroup
        }

        // 5. Specific third-party app (not browser)
        if (!pkg.isNullOrBlank() && !isBrowserPackage(pkg)) {
            val groupName = appName.ifBlank { "App" }
            val existing = dao.getGroupByName(groupName)
            if (existing != null) return@withContext existing
            val appGroup = BookmarkGroupEntity(
                id = "group_${UUID.randomUUID()}",
                name = groupName,
                iconResName = "ic_folder",
                colorHex = pickAccentColor(pkg),
                isAutoGroup = true,
                associatedPackage = pkg,
                sortOrder = 20
            )
            dao.insertGroup(appGroup)
            return@withContext appGroup
        }

        // 6. Web link
        if (!url.isNullOrBlank()) {
            val domain = extractDomain(url)
            val siteName = domain.substringBeforeLast('.').substringAfterLast('.').replaceFirstChar { it.uppercase() }
            val groupName = siteName.ifBlank { "Web" }
            val existing = dao.getGroupByName(groupName)
            if (existing != null) return@withContext existing
            val webGroup = BookmarkGroupEntity(
                id = "group_${UUID.randomUUID()}",
                name = groupName,
                iconResName = "ic_network",
                colorHex = pickAccentColor(domain),
                isAutoGroup = true,
                associatedPackage = pkg,
                sortOrder = 20
            )
            dao.insertGroup(webGroup)
            return@withContext webGroup
        }

        // 7. Fallback
        val defaultName = appName.ifBlank { "Collection" }
        val existing = dao.getGroupByName(defaultName)
        if (existing != null) return@withContext existing
        val fallbackGroup = BookmarkGroupEntity(
            id = "group_${UUID.randomUUID()}",
            name = defaultName,
            iconResName = "ic_folder",
            colorHex = "#3B82F6",
            isAutoGroup = true,
            sortOrder = 30
        )
        dao.insertGroup(fallbackGroup)
        return@withContext fallbackGroup
    }

    fun isYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true)
    }

    private fun extractDomain(urlStr: String): String {
        return try {
            val uri = URI(urlStr)
            uri.host?.removePrefix("www.") ?: urlStr
        } catch (_: Exception) {
            urlStr
        }
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

    fun enrichBookmarkAsync(bookmark: BookmarkEntity) {
        repositoryScope.launch {
            try {
                val url = bookmark.url ?: return@launch
                val meta = BookmarkMetadataScraper.scrape(context, url)
                val enriched = bookmark.copy(
                    title = if ((bookmark.title == url || bookmark.title.startsWith("http")) && !meta.title.isNullOrBlank()) meta.title else bookmark.title,
                    thumbnailPath = meta.thumbnailPath ?: bookmark.thumbnailPath,
                    faviconPath = meta.faviconPath ?: bookmark.faviconPath,
                    cachedArticleText = meta.articleText
                )
                dao.updateBookmark(enriched)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun updateBookmark(item: BookmarkEntity) = withContext(Dispatchers.IO) {
        dao.updateBookmark(item)
        dao.deleteEmptyAutoGroups()
    }

    suspend fun deleteBookmark(item: BookmarkEntity, purgeFiles: Boolean = true) = withContext(Dispatchers.IO) {
        dao.deleteBookmark(item)
        if (purgeFiles) {
            item.filePath?.let { runCatching { File(it).delete() } }
            item.thumbnailPath?.let { runCatching { File(it).delete() } }
            item.faviconPath?.let { runCatching { File(it).delete() } }
        }
        dao.deleteEmptyAutoGroups()
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
        dao.deleteEmptyAutoGroups()
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
