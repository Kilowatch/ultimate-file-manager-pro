package za.kilowatch.ultimatefilemanager.indexing

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 virtual table that shadows [FileIndex].
 *
 * Room keeps this table in sync with [FileIndex] automatically via content-entity triggers.
 * Indexed columns: filename, folderPath — enough for instant prefix / substring search.
 *
 * DB version 3.
 */
@Fts4(contentEntity = FileIndex::class)
@Entity(tableName = "file_index_fts")
data class FileSearchFts(
    val filename: String,
    val folderPath: String
)
