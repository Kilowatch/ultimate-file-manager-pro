package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LocalThumbnailEntity::class], version = 1, exportSchema = false)
abstract class LocalThumbnailDatabase : RoomDatabase() {

    abstract fun dao(): LocalThumbnailDao

    companion object {
        private const val DATABASE_NAME = "ufm_local_thumbnails.db"

        @Volatile
        private var INSTANCE: LocalThumbnailDatabase? = null

        fun getInstance(context: Context): LocalThumbnailDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocalThumbnailDatabase::class.java,
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
