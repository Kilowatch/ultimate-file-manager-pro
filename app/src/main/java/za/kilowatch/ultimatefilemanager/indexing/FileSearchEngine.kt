package za.kilowatch.ultimatefilemanager.indexing

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.flow.Flow
import za.kilowatch.ultimatefilemanager.indexing.SearchQueryParser.hasFilters
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager

/**
 * File Search Engine - Provides fast searching across indexed files.
 *
 * Search capabilities:
 * - [searchSmart] — master entry point: FTS → filtered → LIKE fallback
 * - Full filename search with wildcard support
 * - Search by file type (extension)
 * - Search by MIME type
 * - Search by file size range
 * - Duplicate file detection
 * - Recently modified files
 * - Folder browser
 * - Recent searches (persisted in SharedPreferences)
 */
class FileSearchEngine(
    private val context: Context,
    private val database: UfmIndexingDatabase = UfmIndexingDatabase.getInstance(context)
) {

    private val TAG = "FileSearchEngine"
    private val dao = database.fileIndexDao()

    /** In-memory search result cache. Key = "$query|$storageId|$offset". TTL = 60 s. */
    private val cache = LruCache<String, Pair<List<FileIndex>, Long>>(50)
    private val CACHE_TTL_MS = 60_000L

    private val prefs by lazy {
        context.getSharedPreferences("ufm_recent_searches", Context.MODE_PRIVATE)
    }
    private val PREFS_KEY = "recent"
    private val MAX_RECENT = 10

    // ============ SMART SEARCH (master entry point) ============

    /**
     * Smart search — routes to the best available strategy:
     *
     * 1. If the parsed query contains filters → [FileIndexDao.searchFiltered]
     * 2. Else if plain FTS term is present → [FileIndexDao.searchFts] / [FileIndexDao.searchFtsInFolder]
     * 3. Fallback → [FileIndexDao.searchByFilename] (LIKE)
     *
     * Results are cached in-memory for [CACHE_TTL_MS] ms.
     *
     * **Only callable for indexed storages.** Unindexed storages use walkTopDown in [SearchActivity].
     *
     * @param query     Raw query string (may include filter tokens)
     * @param storageId Storage to search in (empty / "%" = all)
     * @param folderScope Optional folder path prefix for folder-scoped search
     * @param limit     Page size
     * @param offset    Pagination offset
     */
    suspend fun searchSmart(
        query: String,
        storageId: String = "",
        folderScope: String? = null,
        limit: Int = 200,
        offset: Int = 0
    ): List<FileIndex> {
        val rawKey = "$query|$storageId|$folderScope|$offset"

        // --- cache read ---
        cache.get(rawKey)?.let { (results, ts) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                GoRoLog.d(TAG, "Cache hit for '$rawKey'")
                return results
            }
        }

        val parsed  = SearchQueryParser.parse(query)
        val sid     = if (storageId.isEmpty()) "%" else storageId
        val exact   = parsed.ftsTerm.trim()

        val results = try {
            val rawResults = when {
                // Has structured filters → use parameterised query per ext/type and OR-merge
                parsed.hasFilters() -> {
                    val namePat = if (exact.isNotEmpty()) "%${escapeLike(exact)}%" else null

                    // Build all combinations of (extension?, mimePrefix?) to query
                    // If neither list has values, pass null for both (covers size/date-only filters)
                    val extList  = parsed.extensions.ifEmpty { listOf(null) }
                    val typeList = parsed.mimePrefixes.ifEmpty { listOf(null) }

                    val combined = mutableListOf<FileIndex>()
                    val seen = mutableSetOf<String>()

                    // One query per (ext, type) pair — OR logic via union
                    for (ext in extList) {
                        for (mime in typeList) {
                            val rows = dao.searchFiltered(
                                namePattern  = namePat,
                                extension    = ext,
                                mimePrefix   = mime,
                                minSize      = parsed.minSize,
                                maxSize      = parsed.maxSize,
                                sinceDate    = parsed.sinceDate,
                                folderPrefix = parsed.folderPrefix ?: folderScope?.let { "$it%" },
                                storageId    = sid,
                                exactName    = exact,
                                limit        = limit,
                                offset       = offset
                            )
                            for (r in rows) {
                                if (seen.add(r.path)) combined.add(r)
                            }
                        }
                    }
                    combined.sortedWith(
                        compareBy<FileIndex> { if (it.filename == exact) 0 else 1 }
                            .thenByDescending { it.lastModified }
                    ).take(limit)
                }


                // Plain text → FTS (safe terms) or literal LIKE (special chars)
                exact.isNotEmpty() -> {
                    if (isFtsSafe(exact)) {
                        // Escape special FTS chars and append * for prefix search
                        val ftsTerm = exact.replace("\"", "\"\"") + "*"
                        if (folderScope != null) {
                            dao.searchFtsInFolder(
                                ftsQuery     = ftsTerm,
                                folderPrefix = "$folderScope%",
                                storageId    = sid,
                                limit        = limit,
                                offset       = offset
                            )
                        } else {
                            dao.searchFts(
                                ftsQuery  = ftsTerm,
                                storageId = sid,
                                exactName = exact,
                                limit     = limit,
                                offset    = offset
                            )
                        }
                    } else {
                        // Special characters: literal case-insensitive substring match
                        dao.searchByFilenameLiteral(
                            pattern      = "%${escapeLike(exact)}%",
                            storageId    = sid,
                            folderPrefix = folderScope?.let { "$it%" },
                            exactName    = exact,
                            limit        = limit,
                            offset       = offset
                        )
                    }
                }

                // Empty query (e.g. category chip with no filename)
                else -> emptyList()
            }
            
            // Filter out hidden files if the toggle is off
            val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
            if (showHidden) {
                rawResults
            } else {
                val hiddenDao = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase.getInstance(context).hiddenFileDao()
                val hiddenPaths = hiddenDao.getAllPaths().toSet()
                rawResults.filter { it.path !in hiddenPaths && !HiddenFilesManager.isPathJunkOrHidden(it.path) }
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "searchSmart FTS/filter failed, falling back to LIKE: ${e.message}")
            // Graceful fallback to LIKE
            val likePattern = convertWildcardToLike(query)
            val rawResults = dao.searchByFilename(likePattern, sid, limit, offset)
            
            val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
            if (showHidden) {
                rawResults
            } else {
                val hiddenDao = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase.getInstance(context).hiddenFileDao()
                val hiddenPaths = hiddenDao.getAllPaths().toSet()
                rawResults.filter { it.path !in hiddenPaths && !HiddenFilesManager.isPathJunkOrHidden(it.path) }
            }
        }

        // --- cache write ---
        cache.put(rawKey, Pair(results, System.currentTimeMillis()))
        return results
    }

    /** Invalidate cache entries matching a storage (call after indexing completes). */
    fun invalidateCache(storageId: String = "") {
        // LruCache has no iteration — simplest safe approach is to evict all
        cache.evictAll()
        GoRoLog.d(TAG, "Search cache invalidated (storage: $storageId)")
    }

    // ============ RECENT SEARCHES ============

    /**
     * Persist [query] to the recent searches list (max [MAX_RECENT]).
     * Duplicates are moved to the top.
     */
    fun saveRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        val current = getRecentSearches().toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        prefs.edit()
            .putString(PREFS_KEY, current.take(MAX_RECENT).joinToString("\n"))
            .apply()
    }

    /** Returns up to [MAX_RECENT] recent searches, newest first. */
    fun getRecentSearches(): List<String> {
        val raw = prefs.getString(PREFS_KEY, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotEmpty() }
    }

    /**
     * Returns search suggestions for the given [prefix].
     * Combines recent searches matching the prefix + static category shortcuts.
     */
    fun getSearchSuggestions(prefix: String): List<SearchSuggestion> {
        val result = mutableListOf<SearchSuggestion>()

        // Recent searches first
        val lower = prefix.lowercase()
        getRecentSearches()
            .filter { it.lowercase().startsWith(lower) || lower.isEmpty() }
            .take(1)
            .mapTo(result) { SearchSuggestion(it, SearchSuggestion.Type.RECENT) }

        return result
    }

    // ============ BASIC SEARCH OPERATIONS ============

    /**
     * Search files by filename with pattern matching.
     *
     * @param query Search query (supports * and ? wildcards)
     * @param storageId Storage to search in (empty = all)
     * @param limit Maximum results to return
     * @param offset Pagination offset
     * @return List of matching FileIndex entries
     */
    suspend fun searchByFilename(
        query: String,
        storageId: String = "",
        limit: Int = 50,
        offset: Int = 0
    ): List<FileIndex> {
        return try {
            val searchPattern = convertWildcardToLike(query)
            val targetStorage = if (storageId.isEmpty()) "%" else storageId
            dao.searchByFilename(searchPattern, targetStorage, limit, offset)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error searching by filename: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search files by extension.
     *
     * @param extension File extension (without dot, e.g., "pdf", "jpg")
     * @param storageId Storage to search in
     * @param limit Maximum results
     * @return List of files with matching extension
     */
    suspend fun searchByExtension(
        extension: String,
        storageId: String = "",
        limit: Int = 100
    ): List<FileIndex> {
        return try {
            val ext = extension.lowercase().removePrefix(".")
            dao.searchByExtension(ext, storageId, limit)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error searching by extension: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search files by MIME type with pattern matching.
     *
     * @param mimePattern MIME type pattern (e.g., "image/any", "application/any")
     * @param storageId Storage to search in
     * @param limit Maximum results
     * @return List of files with matching MIME type
     */
    suspend fun searchByMimeType(
        mimePattern: String,
        storageId: String = "",
        limit: Int = 100
    ): List<FileIndex> {
        return try {
            val pattern = if (mimePattern.endsWith("*")) {
                mimePattern.replace("*", "%")
            } else {
                "%$mimePattern%"
            }
            dao.searchByMimeType(pattern, storageId, limit)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error searching by MIME type: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search files by size range.
     *
     * @param minSize Minimum file size in bytes
     * @param maxSize Maximum file size in bytes
     * @param storageId Storage to search in
     * @param limit Maximum results
     * @return List of files in size range
     */
    suspend fun searchBySize(
        minSize: Long,
        maxSize: Long,
        storageId: String = "",
        limit: Int = 1000
    ): List<FileIndex> {
        return try {
            dao.getFilesBySize(minSize, maxSize, storageId, limit)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error searching by size: ${e.message}")
            emptyList()
        }
    }

    // ============ DUPLICATE DETECTION ============

    /**
     * Find duplicate files (same hash and size).
     *
     * @param hash File content hash
     * @param size File size
     * @return List of duplicate files
     */
    suspend fun findDuplicates(hash: String, size: Long): List<FileIndex> {
        return try {
            if (hash.isEmpty()) emptyList()
            else dao.getDuplicates(hash, size)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error finding duplicates: ${e.message}")
            emptyList()
        }
    }

    /**
     * Find all duplicate groups (files with same content).
     * Returns a map of hash -> list of files with that hash.
     */
    suspend fun findAllDuplicateGroups(storageId: String = ""): Map<String, List<FileIndex>> {
        return try {
            // This would require a more complex query
            // For now, return empty map (can be enhanced with custom query)
            emptyMap()
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error finding duplicate groups: ${e.message}")
            emptyMap()
        }
    }

    // ============ FOLDER NAVIGATION ============

    /**
     * Get contents of a folder.
     *
     * @param folderPath Path to folder
     * @return List of files/directories in folder
     */
    suspend fun getFolderContents(folderPath: String): List<FileIndex> {
        return try {
            dao.getFilesInFolder(folderPath)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting folder contents: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get folder contents as reactive Flow.
     */
    fun getFolderContentsFlow(folderPath: String): Flow<List<FileIndex>> {
        return dao.getFilesInFolderFlow(folderPath)
    }

    /**
     * Navigate to parent folder.
     */
    suspend fun getParentFolder(folderPath: String): FileIndex? {
        return try {
            val parentPath = if (folderPath.endsWith("/")) {
                folderPath.dropLast(1)
            } else {
                folderPath
            }
            val parentPathOnly = parentPath.substringBeforeLast("/")
            if (parentPathOnly.isEmpty()) null else dao.getByPath(parentPathOnly)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting parent folder: ${e.message}")
            null
        }
    }

    // ============ FILE INFORMATION ============

    /**
     * Get file by path.
     */
    suspend fun getFile(path: String): FileIndex? {
        return try {
            dao.getByPath(path)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting file: ${e.message}")
            null
        }
    }

    /**
     * Get recently modified files.
     *
     * @param sinceMinutesAgo Get files modified in last N minutes
     * @param storageId Storage to search in
     * @param limit Maximum results
     */
    suspend fun getRecentlyModified(
        sinceMinutesAgo: Int = 60,
        storageId: String = "",
        limit: Int = 100
    ): List<FileIndex> {
        return try {
            val timestamp = System.currentTimeMillis() - (sinceMinutesAgo * 60 * 1000)
            dao.getRecentlyModified(timestamp, storageId, limit)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting recently modified files: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get recently indexed files.
     */
    suspend fun getRecentlyIndexed(
        sinceMinutesAgo: Int = 60,
        storageId: String = "",
        limit: Int = 100
    ): List<FileIndex> {
        return try {
            val timestamp = System.currentTimeMillis() - (sinceMinutesAgo * 60 * 1000)
            dao.getRecentlyIndexed(timestamp, storageId, limit)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting recently indexed files: ${e.message}")
            emptyList()
        }
    }

    // ============ STORAGE ANALYTICS ============

    /**
     * Get storage usage statistics by device.
     */
    suspend fun getStorageUsageByDevice(): List<StorageUsage> {
        return try {
            dao.getStorageUsageByDevice()
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting storage usage: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get storage usage by file type (extension).
     */
    suspend fun getStorageUsageByType(storageId: String): List<FileTypeUsage> {
        return try {
            dao.getStorageUsageByType(storageId)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting usage by type: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get storage usage by MIME type.
     */
    suspend fun getStorageUsageByMimeType(storageId: String): List<FileTypeUsage> {
        return try {
            dao.getStorageUsageByMimeType(storageId)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting usage by MIME type: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get overall index statistics.
     */
    suspend fun getIndexStats(): IndexStats? {
        return try {
            dao.getIndexStats()
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting index stats: ${e.message}")
            null
        }
    }

    /**
     * Get largest files in storage.
     */
    suspend fun getLargestFiles(storageId: String, limit: Int = 100): List<FileIndex> {
        return try {
            dao.getLargestFiles(storageId, limit)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting largest files: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get file count for storage.
     */
    suspend fun getFileCount(storageId: String): Long {
        return try {
            dao.getFileCountByStorage(storageId)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting file count: ${e.message}")
            0L
        }
    }

    // ============ UTILITIES ============

    /**
     * True if [term] is safe to pass to the FTS4 MATCH parser — i.e. it contains
     * only ASCII letters, ASCII digits, and whitespace. Any other character
     * (punctuation, symbols, non-ASCII letters) would be treated as an FTS
     * operator or stripped by the `simple` tokenizer, so such terms must go
     * through the literal LIKE path instead.
     */
    private fun isFtsSafe(term: String): Boolean =
        term.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it.isWhitespace() }

    /**
     * Escape a literal string for use inside a `LIKE ... ESCAPE '\'` pattern:
     * escape the escape character first, then the wildcards `%` and `_`.
     */
    private fun escapeLike(term: String): String =
        term.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    /**
     * Convert wildcard pattern to SQL LIKE pattern.
     *
     * @param query Wildcard query (* matches any sequence, ? matches single char)
     * @return SQL LIKE pattern
     */
    private fun convertWildcardToLike(query: String): String {
        return query
            .replace(".", "\\.")
            .replace("?", "_")
            .replace("*", "%")
            .let { "%$it%" }
    }

    companion object {
        @Volatile
        private var INSTANCE: FileSearchEngine? = null

        /**
         * Get or create search engine singleton.
         */
        fun getInstance(context: Context): FileSearchEngine {
            return INSTANCE ?: synchronized(this) {
                val instance = FileSearchEngine(context)
                INSTANCE = instance
                instance
            }
        }
    }
}

/** A single item in the search suggestions dropdown. */
data class SearchSuggestion(
    val text: String,
    val type: Type
) {
    enum class Type { RECENT, CATEGORY }
}
