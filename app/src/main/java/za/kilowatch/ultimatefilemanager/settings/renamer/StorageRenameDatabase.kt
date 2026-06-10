package za.kilowatch.ultimatefilemanager.settings.renamer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [StorageRenameEntity::class], version = 1, exportSchema = false)
abstract class StorageRenameDatabase : RoomDatabase() {

    abstract fun storageRenameDao(): StorageRenameDao

    companion object {
        private const val DATABASE_NAME = "ufm_storage_renames.db"

        @Volatile
        private var INSTANCE: StorageRenameDatabase? = null

        fun getInstance(context: Context): StorageRenameDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StorageRenameDatabase::class.java,
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
