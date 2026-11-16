package za.kilowatch.ultimatefilemanager.bookmarks.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
@JvmSuppressWildcards
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY updatedAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY updatedAt DESC")
    suspend fun getAllBookmarksSync(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE groupId = :groupId ORDER BY updatedAt DESC")
    fun getBookmarksByGroup(groupId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getBookmarkById(id: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun getBookmarkByUrl(url: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE filePath = :filePath LIMIT 1")
    suspend fun getBookmarkByFilePath(filePath: String): BookmarkEntity?

    @Query("""
        SELECT * FROM bookmarks 
        WHERE title LIKE '%' || :query || '%' 
           OR notes LIKE '%' || :query || '%' 
           OR url LIKE '%' || :query || '%'
           OR sourceAppName LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
    """)
    fun searchBookmarks(query: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(item: BookmarkEntity): Long

    @Update
    suspend fun updateBookmark(item: BookmarkEntity): Int

    @Delete
    suspend fun deleteBookmark(item: BookmarkEntity): Int

    @Delete
    suspend fun deleteBookmarks(items: List<BookmarkEntity>): Int

    @Query("SELECT * FROM bookmark_groups ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllGroups(): Flow<List<BookmarkGroupEntity>>

    @Query("SELECT * FROM bookmark_groups ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllGroupsSync(): List<BookmarkGroupEntity>

    @Query("SELECT * FROM bookmark_groups WHERE id = :id LIMIT 1")
    suspend fun getGroupById(id: String): BookmarkGroupEntity?

    @Query("SELECT * FROM bookmark_groups WHERE name = :name LIMIT 1")
    suspend fun getGroupByName(name: String): BookmarkGroupEntity?

    @Query("SELECT * FROM bookmark_groups WHERE associatedPackage = :pkg LIMIT 1")
    suspend fun getGroupByPackage(pkg: String): BookmarkGroupEntity?

    @Query("DELETE FROM bookmark_groups WHERE isAutoGroup = 1 AND id NOT IN (SELECT DISTINCT groupId FROM bookmarks)")
    suspend fun deleteEmptyAutoGroups(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: BookmarkGroupEntity): Long

    @Update
    suspend fun updateGroup(group: BookmarkGroupEntity): Int

    @Delete
    suspend fun deleteGroup(group: BookmarkGroupEntity): Int

    @Query("SELECT COUNT(*) FROM bookmarks")
    fun getBookmarkCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun getBookmarkCountSync(): Int

    @Query("SELECT COUNT(*) FROM bookmark_groups")
    fun getGroupCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM bookmark_groups")
    suspend fun getGroupCountSync(): Int

    @Query("SELECT COUNT(*) FROM bookmarks WHERE groupId = :groupId")
    fun getBookmarksCountForGroup(groupId: String): Flow<Int>
}
