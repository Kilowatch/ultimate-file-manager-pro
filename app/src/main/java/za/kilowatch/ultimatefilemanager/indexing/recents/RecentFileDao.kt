package za.kilowatch.ultimatefilemanager.indexing.recents

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
@JvmSuppressWildcards
interface RecentFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RecentFileEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecentFileEntity): Long

    @Query("SELECT * FROM recent_files ORDER BY lastModified DESC LIMIT :limit")
    fun observeRecentFiles(limit: Int): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files ORDER BY lastModified DESC LIMIT :limit")
    suspend fun getRecentFiles(limit: Int): List<RecentFileEntity>

    /**
     * Capped LRU eviction: keep only the newest [maxCount] entries.
     */
    @Query("DELETE FROM recent_files WHERE uriOrPath NOT IN (SELECT uriOrPath FROM recent_files ORDER BY lastModified DESC LIMIT :maxCount)")
    suspend fun pruneLru(maxCount: Int): Int

    @Query("DELETE FROM recent_files WHERE volumeId = :volumeId")
    suspend fun deleteByVolumeId(volumeId: String): Int

    @Query("DELETE FROM recent_files WHERE source = :source")
    suspend fun deleteBySource(source: String): Int

    @Query("DELETE FROM recent_files WHERE uriOrPath = :uriOrPath")
    suspend fun deleteByUriOrPath(uriOrPath: String): Int

    @Query("DELETE FROM recent_files")
    suspend fun clearAll(): Int
}
