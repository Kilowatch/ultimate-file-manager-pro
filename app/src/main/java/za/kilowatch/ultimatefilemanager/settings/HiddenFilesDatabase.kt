package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HiddenFileEntity::class], version = 1, exportSchema = false)
abstract class HiddenFilesDatabase : RoomDatabase() {

    abstract fun hiddenFileDao(): HiddenFileDao

    companion object {
        private const val DATABASE_NAME = "ufm_hidden_files.db"

        @Volatile
        private var INSTANCE: HiddenFilesDatabase? = null

        fun getInstance(context: Context): HiddenFilesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HiddenFilesDatabase::class.java,
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
