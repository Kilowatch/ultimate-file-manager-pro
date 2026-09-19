package za.kilowatch.ultimatefilemanager.recycle

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RecycleBinEntity::class], version = 1, exportSchema = false)
abstract class RecycleBinDatabase : RoomDatabase() {
    abstract fun recycleBinDao(): RecycleBinDao

    companion object {
        private const val DATABASE_NAME = "ufm_recycle_bin.db"
        @Volatile private var INSTANCE: RecycleBinDatabase? = null

        fun getInstance(context: Context): RecycleBinDatabase {
            return INSTANCE ?: synchronized(this) {
                try {
                    val field = android.database.CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
                    field.isAccessible = true
                    val current = (field.get(null) as? Number)?.toLong() ?: 0L
                    if (current < 50L * 1024 * 1024) {
                        field.set(null, 50 * 1024 * 1024) // 50MB window
                    }
                } catch (_: Throwable) {
                    // Ignored on Android versions with strict non-SDK reflection policies
                }
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RecycleBinDatabase::class.java,
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
