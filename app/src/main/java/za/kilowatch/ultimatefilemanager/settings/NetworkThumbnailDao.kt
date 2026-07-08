package za.kilowatch.ultimatefilemanager.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NetworkThumbnailDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: NetworkThumbnailEntity)

    @Query("SELECT * FROM network_thumbnails WHERE shareId = :shareId AND networkPath = :path LIMIT 1")
    fun get(shareId: String, path: String): NetworkThumbnailEntity?

    @Query("SELECT * FROM network_thumbnails WHERE shareId = :shareId AND parentFolder = :folder")
    fun getByParentFolder(shareId: String, folder: String): List<NetworkThumbnailEntity>

    @Query("SELECT * FROM network_thumbnails WHERE shareId = :shareId AND (networkPath = :folderPath OR networkPath LIKE :folderPathPrefix)")
    fun getUnderFolder(shareId: String, folderPath: String, folderPathPrefix: String): List<NetworkThumbnailEntity>

    @Query("DELETE FROM network_thumbnails WHERE shareId = :shareId AND networkPath = :path")
    fun delete(shareId: String, path: String)

    @Query("DELETE FROM network_thumbnails WHERE shareId = :shareId AND (networkPath = :folderPath OR networkPath LIKE :folderPathPrefix)")
    fun deleteUnderFolder(shareId: String, folderPath: String, folderPathPrefix: String)

    @Query("DELETE FROM network_thumbnails")
    fun deleteAll()

    @Query("SELECT SUM(sizeBytes) FROM network_thumbnails")
    fun getTotalSizeBytes(): Long?
}
