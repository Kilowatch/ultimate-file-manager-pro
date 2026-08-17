package za.kilowatch.ultimatefilemanager.indexing

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [FileIndex].
 * DB v2: isDeleted removed — all deletes are hard deletes.
 */
@Dao
@JvmSuppressWildcards
interface FileIndexDao {

    // ============ INSERT ============

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fileIndex: FileIndex): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(fileIndices: List<FileIndex>): List<Long>

    // ============ UPDATE ============

    @Update
    suspend fun update(fileIndex: FileIndex): Int

    @Update
    suspend fun updateAll(fileIndices: List<FileIndex>): Int

    // ============ DELETE ============

    /** Hard-delete a single entry by path. */
    @Query("DELETE FROM file_index WHERE path = :path")
    suspend fun deleteByPath(path: String): Int

    /** Hard-delete all entries whose path starts with [pathPrefix]. Used for folder deletes. */
    @Query("DELETE FROM file_index WHERE path = :pathPrefix OR path LIKE :pathPrefix || '/%'")
    suspend fun deleteByPathPrefix(pathPrefix: String): Int

    /** Hard-delete all entries for a storage device. */
    @Query("DELETE FROM file_index WHERE storageId = :storageId")
    suspend fun deleteByStorageId(storageId: String): Int

    /** Hard-delete everything. */
    @Query("DELETE FROM file_index")
    suspend fun deleteAll(): Int

    @Delete
    suspend fun delete(fileIndex: FileIndex): Int

    // ============ SEARCH & QUERY ============

    @Query("SELECT * FROM file_index WHERE path = :path")
    suspend fun getByPath(path: String): FileIndex?

    /** All paths for a storage — used by deletion reconcile to find stale entries. */
    @Query("SELECT path FROM file_index WHERE storageId = :storageId")
    suspend fun getAllPathsForStorage(storageId: String): List<String>

    @Query("SELECT path FROM file_index WHERE path = :pathPrefix OR path LIKE :pathPrefix || '/%'")
    suspend fun getPathsByPrefix(pathPrefix: String): List<String>

    /** All files (debug viewer). */
    @Query("SELECT * FROM file_index ORDER BY id DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 1000): List<FileIndex>

    @Query("SELECT * FROM file_index WHERE hash = :hash AND hash != ''")
    suspend fun getByHash(hash: String): List<FileIndex>

    @Query("""
        SELECT * FROM file_index
        WHERE filename LIKE :pattern
        AND storageId LIKE :storageId
        ORDER BY lastModified DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchByFilename(
        pattern: String,
        storageId: String,
        limit: Int,
        offset: Int
    ): List<FileIndex>

    @Query("""
        SELECT * FROM file_index
        WHERE extension = :extension
        AND storageId LIKE :storageId
        ORDER BY lastModified DESC
        LIMIT :limit
    """)
    suspend fun searchByExtension(
        extension: String,
        storageId: String,
        limit: Int
    ): List<FileIndex>

    @Query("""
        SELECT * FROM file_index
        WHERE mimeType LIKE :mimePattern
        AND storageId LIKE :storageId
        ORDER BY lastModified DESC
        LIMIT :limit
    """)
    suspend fun searchByMimeType(
        mimePattern: String,
        storageId: String,
        limit: Int
    ): List<FileIndex>

    @Query("""
        SELECT * FROM file_index
        WHERE folderPath = :folderPath
        ORDER BY isDirectory DESC, filename ASC
    """)
    suspend fun getFilesInFolder(folderPath: String): List<FileIndex>

    @Query("SELECT COUNT(*) FROM file_index WHERE folderPath = :folderPath")
    suspend fun getFileCountInFolder(folderPath: String): Int

    @Query("""
        SELECT SUM(size)
        FROM file_index
        WHERE isDirectory = 0
          AND (:storageId = '' OR storageId = :storageId)
          AND isHidden = 0
          AND filename NOT LIKE '.%'
          AND (
              folderPath = :folderPath COLLATE NOCASE
              OR folderPath = :folderPath || '/' COLLATE NOCASE
              OR folderPath LIKE :folderPath || '/%' COLLATE NOCASE
          )
    """)
    suspend fun getFolderTotalSize(storageId: String, folderPath: String): Long?

    @Query("SELECT path FROM file_index WHERE folderPath = :folderPath")
    suspend fun getIndexedPathsInFolder(folderPath: String): List<String>

    @Query("SELECT path FROM file_index WHERE path IN (:paths)")
    suspend fun getIndexedPaths(paths: List<String>): List<String>

    @Query("""
        SELECT * FROM file_index
        WHERE folderPath = :folderPath
        ORDER BY isDirectory DESC, filename ASC
    """)
    fun getFilesInFolderFlow(folderPath: String): Flow<List<FileIndex>>

    @Query("""
        SELECT * FROM file_index
        WHERE size >= :minSize AND size <= :maxSize
        AND storageId = :storageId
        ORDER BY size DESC
        LIMIT :limit
    """)
    suspend fun getFilesBySize(
        minSize: Long,
        maxSize: Long,
        storageId: String,
        limit: Int
    ): List<FileIndex>

    @Query("""
        SELECT * FROM file_index
        WHERE hash = :hash AND size = :size AND hash != ''
        ORDER BY storageId, path
    """)
    suspend fun getDuplicates(hash: String, size: Long): List<FileIndex>

    @Query("""
        SELECT * FROM file_index
        WHERE lastModified >= :sinceTimestamp
        AND storageId = :storageId
        ORDER BY lastModified DESC
        LIMIT :limit
    """)
    suspend fun getRecentlyModified(
        sinceTimestamp: Long,
        storageId: String,
        limit: Int
    ): List<FileIndex>

    @Query("""
        SELECT * FROM file_index
        WHERE indexedAt >= :sinceTimestamp
        AND storageId = :storageId
        ORDER BY indexedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentlyIndexed(
        sinceTimestamp: Long,
        storageId: String,
        limit: Int
    ): List<FileIndex>

    // ============ FTS SEARCH ============

    /**
     * Fast FTS4-backed filename search.
     * Results are ranked: exact filename match first, then by recency.
     * Only works when storage is indexed.
     */
    @Query("""
        SELECT fi.* FROM file_index fi
        INNER JOIN file_index_fts fts ON fi.rowid = fts.rowid
        WHERE file_index_fts MATCH :ftsQuery
        AND fi.storageId LIKE :storageId
        ORDER BY
            CASE WHEN fi.filename = :exactName THEN 0 ELSE 1 END,
            fi.lastModified DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchFts(
        ftsQuery: String,
        storageId: String,
        exactName: String,
        limit: Int,
        offset: Int
    ): List<FileIndex>

    /**
     * Folder-scoped FTS search — limits results to a subtree.
     */
    @Query("""
        SELECT fi.* FROM file_index fi
        INNER JOIN file_index_fts fts ON fi.rowid = fts.rowid
        WHERE file_index_fts MATCH :ftsQuery
        AND fi.folderPath LIKE :folderPrefix
        AND fi.storageId LIKE :storageId
        ORDER BY fi.lastModified DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchFtsInFolder(
        ftsQuery: String,
        folderPrefix: String,
        storageId: String,
        limit: Int,
        offset: Int
    ): List<FileIndex>

    /**
     * Fully-filtered search driven by [SearchQueryParser].
     * Any null parameter is ignored (acts as no filter for that column).
     * Falls back to regular indexed columns — does NOT require FTS.
     */
    @Query("""
        SELECT * FROM file_index
        WHERE (:namePattern IS NULL OR filename LIKE :namePattern ESCAPE '\')
        AND (:extension IS NULL OR extension = :extension)
        AND (:mimePrefix IS NULL OR mimeType LIKE :mimePrefix)
        AND (:minSize IS NULL OR size >= :minSize)
        AND (:maxSize IS NULL OR size <= :maxSize)
        AND (:sinceDate IS NULL OR lastModified >= :sinceDate)
        AND (:folderPrefix IS NULL OR folderPath LIKE :folderPrefix)
        AND storageId LIKE :storageId
        ORDER BY
            CASE WHEN filename = :exactName THEN 0 ELSE 1 END,
            lastModified DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchFiltered(
        namePattern: String?,
        extension: String?,
        mimePrefix: String?,
        minSize: Long?,
        maxSize: Long?,
        sinceDate: Long?,
        folderPrefix: String?,
        storageId: String,
        exactName: String,
        limit: Int,
        offset: Int
    ): List<FileIndex>

    /**
     * Literal filename substring search for special-character terms.
     * [pattern] is an already-escaped `%...%` LIKE pattern (the wildcards
     * `%`, `_` and the escape char `\` are escaped), matched case-insensitively
     * (ASCII) via `ESCAPE '\'`. Used by FileSearchEngine.searchSmart when the
     * plain-text term is not FTS-safe.
     */
    @Query("""
        SELECT * FROM file_index
        WHERE filename LIKE :pattern ESCAPE '\'
        AND storageId LIKE :storageId
        AND (:folderPrefix IS NULL OR folderPath LIKE :folderPrefix)
        ORDER BY
            CASE WHEN filename = :exactName THEN 0 ELSE 1 END,
            lastModified DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchByFilenameLiteral(
        pattern: String,
        storageId: String,
        folderPrefix: String?,
        exactName: String,
        limit: Int,
        offset: Int
    ): List<FileIndex>

    // ============ ANALYTICS ============

    @Query("""
        SELECT storageId, SUM(size) as totalSize
        FROM file_index
        WHERE isDirectory = 0
        GROUP BY storageId
    """)
    suspend fun getStorageUsageByDevice(): List<StorageUsage>

    @Query("""
        SELECT extension, COUNT(*) as fileCount, SUM(size) as totalSize
        FROM file_index
        WHERE isDirectory = 0 AND storageId = :storageId
        GROUP BY extension
        ORDER BY totalSize DESC
    """)
    suspend fun getStorageUsageByType(storageId: String): List<FileTypeUsage>

    @Query("""
        SELECT mimeType as extension, COUNT(*) as fileCount, SUM(size) as totalSize
        FROM file_index
        WHERE isDirectory = 0 AND storageId = :storageId
        GROUP BY mimeType
        ORDER BY totalSize DESC
    """)
    suspend fun getStorageUsageByMimeType(storageId: String): List<FileTypeUsage>

    @Query("SELECT COUNT(*) FROM file_index WHERE storageId = :storageId")
    suspend fun getFileCountByStorage(storageId: String): Long

    // ============ CHANGE DETECTION ============

    @Query("""
        SELECT * FROM file_index
        WHERE lastScannedAt < :beforeTimestamp
        AND storageId = :storageId
        LIMIT :limit
    """)
    suspend fun getFilesNeedingRescan(
        beforeTimestamp: Long,
        storageId: String,
        limit: Int
    ): List<FileIndex>

    // ============ STATISTICS ============

    @Query("""
        SELECT COUNT(*) as totalFiles,
               SUM(size) as totalSize,
               COUNT(DISTINCT storageId) as deviceCount
        FROM file_index
    """)
    suspend fun getIndexStats(): IndexStats?

    @Query("SELECT COUNT(*) FROM file_index")
    suspend fun getTotalIndexEntries(): Long

    @Query("""
        SELECT * FROM file_index
        WHERE storageId = :storageId AND isDirectory = 0
        ORDER BY size DESC
        LIMIT :limit
    """)
    suspend fun getLargestFiles(storageId: String, limit: Int): List<FileIndex>

    @Query("""
        SELECT * FROM file_index
        WHERE isDirectory = 0 
          AND (:storageId = '' OR storageId = :storageId)
          AND size >= :minSize
          AND (
              folderPath = :folderPath COLLATE NOCASE
              OR folderPath = :folderPath || '/' COLLATE NOCASE
              OR folderPath LIKE :folderPath || '/%' COLLATE NOCASE
          )
        ORDER BY size DESC
        LIMIT :limit
    """)
    suspend fun getLargestFilesForFolder(
        storageId: String,
        folderPath: String,
        minSize: Long = 10L * 1024 * 1024,
        limit: Int = 500
    ): List<FileIndex>

    // ============ ANALYZER — FOLDER USAGE ============

    @Query("""
        SELECT folderPath, SUM(size) as totalSize, COUNT(*) as fileCount
        FROM file_index
        WHERE isDirectory = 0 AND storageId = :storageId
        GROUP BY folderPath
        ORDER BY totalSize DESC
        LIMIT :limit
    """)
    suspend fun getTopFoldersBySize(storageId: String, limit: Int = 50): List<FolderUsage>

    // ============ ANALYZER — DUPLICATES ============

    /**
     * Returns all hash groups that have more than one file (potential duplicates).
     * Only considers files with a non-empty hash.
     */
    @Query("""
        SELECT hash, COUNT(*) as fileCount, SUM(size) as totalSize
        FROM file_index
        WHERE hash != '' AND isDirectory = 0 AND storageId = :storageId
        GROUP BY hash
        HAVING fileCount > 1
        ORDER BY totalSize DESC
        LIMIT :limit
    """)
    suspend fun getDuplicateGroups(storageId: String, limit: Int = 200): List<DuplicateGroupSummary>

    @Query("""
        SELECT * FROM file_index
        WHERE hash = :hash AND isDirectory = 0 AND storageId = :storageId
        ORDER BY path ASC
    """)
    suspend fun getFilesForHash(hash: String, storageId: String): List<FileIndex>

    /**
     * Batch variant: fetches all candidate files for a list of quick-hashes in one query.
     * Used by the two-phase duplicate-detection pipeline to avoid N+1 calls.
     */
    @Query("""
        SELECT * FROM file_index
        WHERE hash IN (:hashes) AND hash != '' AND isDirectory = 0 AND (:storageId = '' OR storageId = :storageId)
        ORDER BY hash, path ASC
    """)
    suspend fun getFilesForHashes(hashes: List<String>, storageId: String): List<FileIndex>

    /**
     * Folder-scoped variant: returns hash groups that have at least one file in [folderPath] or its subfolders,
     * and have more than one file total across the storage.
     */
    @Query("""
        SELECT hash, COUNT(*) as fileCount, SUM(size) as totalSize
        FROM file_index
        WHERE hash != '' AND isDirectory = 0 
          AND (:storageId = '' OR storageId = :storageId)
          AND hash IN (
              SELECT DISTINCT hash FROM file_index
              WHERE (:storageId = '' OR storageId = :storageId) 
                AND isDirectory = 0 AND hash != ''
                AND (
                    folderPath = :folderPath COLLATE NOCASE
                    OR folderPath = :folderPath || '/' COLLATE NOCASE
                    OR folderPath LIKE :folderPath || '/%' COLLATE NOCASE
                )
          )
        GROUP BY hash
        HAVING fileCount > 1
        ORDER BY totalSize DESC
        LIMIT :limit
    """)
    suspend fun getDuplicateGroupsForFolder(storageId: String, folderPath: String, limit: Int = 500): List<DuplicateGroupSummary>




    /**
     * Folder-scoped batch variant: fetches candidate files for hashes inside [folderPath] or its subfolders.
     */
    @Query("""
        SELECT * FROM file_index
        WHERE hash IN (:hashes) AND hash != '' AND isDirectory = 0 AND storageId = :storageId
          AND (folderPath = :folderPath OR folderPath LIKE :folderPath || '/%')
        ORDER BY hash, path ASC
    """)
    suspend fun getFilesForHashesInFolder(hashes: List<String>, storageId: String, folderPath: String): List<FileIndex>



    // ============ ANALYZER — OLD FILES ============

    /**
     * Files not modified since [beforeTimestamp].
     * Excludes directories and hidden system paths.
     */
    @Query("""
        SELECT * FROM file_index
        WHERE isDirectory = 0
          AND storageId = :storageId
          AND lastModified < :beforeTimestamp
          AND path NOT LIKE '%/Android/data/%'
          AND path NOT LIKE '%/Android/obb/%'
        ORDER BY lastModified ASC
        LIMIT :limit
    """)
    suspend fun getOldFiles(storageId: String, beforeTimestamp: Long, limit: Int = 200): List<FileIndex>

    // ============ ANALYZER — JUNK / CACHE FILES ============

    /**
     * Files living in well-known cache/temp directories.
     */
    @Query("""
        SELECT * FROM file_index
        WHERE isDirectory = 0
          AND storageId = :storageId
          AND (
              path LIKE '%/cache/%'
           OR path LIKE '%/.cache/%'
           OR path LIKE '%/tmp/%'
           OR path LIKE '%/.tmp/%'
           OR path LIKE '%/.temp/%'
           OR path LIKE '%/logs/%'
           OR path LIKE '%/.crash/%'
           OR path LIKE '%/Thumbs.db'
           OR path LIKE '%/.DS_Store'
           OR path LIKE '%/thumbdata%'
          )
        ORDER BY size DESC
        LIMIT :limit
    """)
    suspend fun getJunkFiles(storageId: String, limit: Int = 500): List<FileIndex>

    /** Aggregate size + count of all junk files for the banner summary. */
    @Query("""
        SELECT SUM(size) as totalSize, COUNT(*) as fileCount
        FROM file_index
        WHERE isDirectory = 0
          AND storageId = :storageId
          AND (
              path LIKE '%/cache/%'
           OR path LIKE '%/.cache/%'
           OR path LIKE '%/tmp/%'
           OR path LIKE '%/.tmp/%'
           OR path LIKE '%/.temp/%'
           OR path LIKE '%/logs/%'
           OR path LIKE '%/.crash/%'
           OR path LIKE '%/Thumbs.db'
           OR path LIKE '%/.DS_Store'
           OR path LIKE '%/thumbdata%'
          )
    """)
    suspend fun getJunkFilesAggregated(storageId: String): JunkAggregate?

    // ============ ANALYZER — DOWNLOAD FOLDER ============

    @Query("""
        SELECT extension, COUNT(*) as fileCount, SUM(size) as totalSize
        FROM file_index
        WHERE isDirectory = 0
          AND storageId = :storageId
          AND (folderPath LIKE '%/Download%' OR folderPath LIKE '%/Downloads%')
        GROUP BY extension
        ORDER BY totalSize DESC
    """)
    suspend fun getDownloadStatsByExtension(storageId: String): List<FileTypeUsage>

    @Query("""
        SELECT * FROM file_index
        WHERE isDirectory = 0
          AND storageId = :storageId
          AND (folderPath LIKE '%/Download%' OR folderPath LIKE '%/Downloads%')
        ORDER BY size DESC
        LIMIT :limit
    """)
    suspend fun getLargestDownloads(storageId: String, limit: Int = 50): List<FileIndex>

    // ============ ANALYZER — APP STORAGE ============

    @Query("""
        SELECT * FROM file_index
        WHERE isDirectory = 0
          AND storageId = :storageId
          AND (
              (:filterType = 1 AND (extension IN ('jpg','jpeg','png','gif','bmp','webp','svg','heic','heif','avif','jxl'))) OR
              (:filterType = 2 AND (extension IN ('mp4','mkv','avi','mov','wmv','flv','webm','3gp','m4v'))) OR
              (:filterType = 3 AND (extension IN ('mp3','wav','aac','flac','ogg','wma','m4a','opus'))) OR
              (:filterType = 4 AND (extension IN ('pdf','doc','docx','docm','dot','dotx','dotm','xls','xlsx','xlsm','xlt','xltx','xltm','xlsb','ppt','pptx','pptm','pps','ppsx','pot','potx','potm','txt','csv','rtf','odt','dat','vsd','vsdx','pub','accdb','mdb'))) OR
              (:filterType = 5 AND (extension IN ('apk','xapk','apks'))) OR
              (:filterType = 6 AND (extension NOT IN ('jpg','jpeg','png','gif','bmp','webp','svg','heic','heif','avif','jxl','mp4','mkv','avi','mov','wmv','flv','webm','3gp','m4v','mp3','wav','aac','flac','ogg','wma','m4a','opus','pdf','doc','docx','docm','dot','dotx','dotm','xls','xlsx','xlsm','xlt','xltx','xltm','xlsb','ppt','pptx','pptm','pps','ppsx','pot','potx','potm','txt','csv','rtf','odt','dat','vsd','vsdx','pub','accdb','mdb','apk','xapk','apks')))
          )
        ORDER BY lastModified DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFilesByCategory(storageId: String, filterType: Int, limit: Int = 1000, offset: Int = 0): List<FileIndex>

    @Query("""
        SELECT SUM(size) as totalSize, COUNT(*) as fileCount
        FROM file_index
        WHERE isDirectory = 0
          AND storageId = :storageId
          AND path LIKE :pathPrefix || '%'
    """)
    suspend fun getAppStorageByPrefix(storageId: String, pathPrefix: String): AppStorageAggregate?
}

data class StorageUsage(val storageId: String, val totalSize: Long)

data class FileTypeUsage(val extension: String, val fileCount: Long, val totalSize: Long)

data class IndexStats(val totalFiles: Long, val totalSize: Long, val deviceCount: Long)

data class FolderUsage(val folderPath: String, val totalSize: Long, val fileCount: Long)

data class DuplicateGroupSummary(val hash: String, val fileCount: Long, val totalSize: Long)

data class JunkAggregate(val totalSize: Long, val fileCount: Long)

data class AppStorageAggregate(val totalSize: Long, val fileCount: Long)
