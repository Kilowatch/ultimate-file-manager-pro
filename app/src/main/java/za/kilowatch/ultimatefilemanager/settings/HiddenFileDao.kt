package za.kilowatch.ultimatefilemanager.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HiddenFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(file: HiddenFileEntity)

    @Query("DELETE FROM hidden_files WHERE absolutePath = :path")
    fun delete(path: String)

    @Query("SELECT absolutePath FROM hidden_files")
    fun getAllPaths(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM hidden_files WHERE absolutePath = :path LIMIT 1)")
    fun exists(path: String): Boolean
}
