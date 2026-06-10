package za.kilowatch.ultimatefilemanager.settings.renamer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StorageRenameDao {

    @Query("SELECT * FROM storage_renames")
    fun getAllRenames(): List<StorageRenameEntity>

    @Query("SELECT * FROM storage_renames WHERE deviceId = :deviceId LIMIT 1")
    fun getRenameById(deviceId: String): StorageRenameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(entity: StorageRenameEntity)

    @Query("DELETE FROM storage_renames WHERE deviceId = :deviceId")
    fun deleteById(deviceId: String)

    @Query("DELETE FROM storage_renames")
    fun deleteAll()
}
