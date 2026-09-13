package za.kilowatch.ultimatefilemanager.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalThumbnailDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: LocalThumbnailEntity)

    @Query("SELECT * FROM local_thumbnails WHERE filePath = :filePath LIMIT 1")
    fun get(filePath: String): LocalThumbnailEntity?

    @Query("SELECT * FROM local_thumbnails WHERE parentFolder = :folder OR parentFolder = :folder || '/'")
    fun getByParentFolder(folder: String): List<LocalThumbnailEntity>

    @Query("SELECT * FROM local_thumbnails WHERE filePath = :folderPath OR filePath LIKE :folderPathPrefix")
    fun getUnderFolder(folderPath: String, folderPathPrefix: String): List<LocalThumbnailEntity>

    @Query("DELETE FROM local_thumbnails WHERE filePath = :filePath")
    fun delete(filePath: String): Int

    @Query("DELETE FROM local_thumbnails WHERE filePath = :folderPath OR filePath LIKE :folderPathPrefix")
    fun deleteUnderFolder(folderPath: String, folderPathPrefix: String): Int

    @Query("DELETE FROM local_thumbnails")
    fun deleteAll(): Int

    @Query("SELECT SUM(sizeBytes) FROM local_thumbnails")
    fun getTotalSizeBytes(): Long?

    @Query("SELECT COUNT(*) FROM local_thumbnails")
    fun getCount(): Int
}
