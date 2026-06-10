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

    @Query("SELECT * FROM recycle_bin ORDER BY dateDeleted DESC")
    fun getAllFlow(): Flow<List<RecycleBinEntity>>

    @Query("SELECT * FROM recycle_bin ORDER BY dateDeleted DESC")
    fun getAll(): List<RecycleBinEntity>

    @Query("SELECT * FROM recycle_bin WHERE id = :id")
    fun getById(id: Long): RecycleBinEntity?
}
