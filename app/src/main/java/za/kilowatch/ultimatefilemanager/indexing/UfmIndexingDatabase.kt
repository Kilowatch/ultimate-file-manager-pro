package za.kilowatch.ultimatefilemanager.indexing

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * UFM Indexing Database - SQLite-backed Room database for high-performance file indexing.
 *
 * This database stores comprehensive file metadata to enable:
 * - Instant full-text search across all storage devices
 * - Quick folder navigation without filesystem scans
 * - Duplicate detection via content hashing
 * - Storage analytics and visualization
 *
 * The database supports:
 * - Internal storage
 * - SD cards
 * - USB storage
 * - Network shares (SMB, FTP)
 * - Cloud storage (future expansion)
 *
 * Version 1: Initial schema with FileIndex table
 */
@Database(
    entities = [FileIndex::class, FileSearchFts::class],
    version = 3,       // v3: added FTS4 virtual table for instant filename search
    exportSchema = false
)
abstract class UfmIndexingDatabase : RoomDatabase() {

    /**
     * Get the FileIndex DAO for database operations.
     */
    abstract fun fileIndexDao(): FileIndexDao

    companion object {
        private const val DATABASE_NAME = "ufm_indexing.db"
        
        @Volatile
        private var INSTANCE: UfmIndexingDatabase? = null

        /**
         * Get or create the database instance (singleton pattern).
         */
        fun getInstance(context: Context): UfmIndexingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UfmIndexingDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()  // For development; adjust for production
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Close the database (for testing/cleanup).
         */
        fun closeInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
