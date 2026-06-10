package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NetworkThumbnailEntity::class], version = 2, exportSchema = false)
abstract class NetworkThumbnailDatabase : RoomDatabase() {

    abstract fun dao(): NetworkThumbnailDao

    companion object {
        private const val DATABASE_NAME = "ufm_network_thumbnails.db"

        @Volatile
        private var INSTANCE: NetworkThumbnailDatabase? = null

        fun getInstance(context: Context): NetworkThumbnailDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NetworkThumbnailDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
