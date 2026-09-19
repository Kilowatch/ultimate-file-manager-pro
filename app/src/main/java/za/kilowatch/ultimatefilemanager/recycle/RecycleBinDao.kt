package za.kilowatch.ultimatefilemanager.recycle

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecycleBinDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: RecycleBinEntity)

    @Query("DELETE FROM recycle_bin WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM recycle_bin")
    fun deleteAll()

    @Query("SELECT * FROM recycle_bin ORDER BY dateDeleted DESC LIMIT :limit")
    fun getAllFlow(limit: Int): Flow<List<RecycleBinEntity>>

    @Query("SELECT * FROM recycle_bin ORDER BY dateDeleted DESC LIMIT 500")
    fun getAllFlow(): Flow<List<RecycleBinEntity>>

    @Query("SELECT * FROM recycle_bin ORDER BY dateDeleted DESC LIMIT :limit OFFSET :offset")
    fun getPaged(limit: Int, offset: Int): List<RecycleBinEntity>

    @Query("SELECT * FROM recycle_bin ORDER BY dateDeleted DESC LIMIT :limit")
    fun getAll(limit: Int): List<RecycleBinEntity>

    @Query("SELECT * FROM recycle_bin ORDER BY dateDeleted DESC LIMIT 500")
    fun getAll(): List<RecycleBinEntity>

    @Query("SELECT * FROM recycle_bin WHERE id = :id")
    fun getById(id: Long): RecycleBinEntity?

    @Query("SELECT COUNT(*) FROM recycle_bin")
    fun getCount(): Int

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM recycle_bin")
    fun getTotalFileSize(): Long

    @Query("SELECT COUNT(*) FROM recycle_bin WHERE isDirectory = 0")
    fun getFileCount(): Int

    @Query("SELECT COUNT(*) FROM recycle_bin WHERE isDirectory = 1")
    fun getFolderCount(): Int

    @Query("SELECT * FROM recycle_bin WHERE isDirectory = 1")
    fun getDirectoryEntries(): List<RecycleBinEntity>

    @Query("SELECT * FROM recycle_bin WHERE dateDeleted < :cutoffTimestamp")
    fun getExpiredEntries(cutoffTimestamp: Long): List<RecycleBinEntity>
}
