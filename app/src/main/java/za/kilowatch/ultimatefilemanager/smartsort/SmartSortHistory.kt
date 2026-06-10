package za.kilowatch.ultimatefilemanager.smartsort

import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.UfmApplication
import java.io.File
import java.util.UUID

data class SmartSortHistoryEntry(
    val id: String,
    val folderPath: String,
    val sortDate: Long,
    val movedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val manifestFolderPath: String
)

object SmartSortHistoryManager {

    private const val FILE_NAME = "ufm_smart_sort_history.json"
    private val lock = Any()

    private fun getFile(): File {
        return File(UfmApplication.instance.filesDir, FILE_NAME)
    }

    fun loadAll(): List<SmartSortHistoryEntry> {
        synchronized(lock) {
            val file = getFile()
            if (!file.exists()) return emptyList()
            return try {
                val json = JSONArray(file.readText())
                (0 until json.length()).map { i ->
                    val obj = json.getJSONObject(i)
                    SmartSortHistoryEntry(
                        id = obj.getString("id"),
                        folderPath = obj.getString("folderPath"),
                        sortDate = obj.getLong("sortDate"),
                        movedCount = obj.getInt("movedCount"),
                        skippedCount = obj.getInt("skippedCount"),
                        failedCount = obj.getInt("failedCount"),
                        manifestFolderPath = obj.getString("manifestFolderPath")
                    )
                }.sortedByDescending { it.sortDate }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun addEntry(folderPath: String, movedCount: Int, skippedCount: Int, failedCount: Int) {
        synchronized(lock) {
            val entries = loadAll().toMutableList()
            entries.add(
                0,
                SmartSortHistoryEntry(
                    id = UUID.randomUUID().toString(),
                    folderPath = folderPath,
                    sortDate = System.currentTimeMillis(),
                    movedCount = movedCount,
                    skippedCount = skippedCount,
                    failedCount = failedCount,
                    manifestFolderPath = folderPath
                )
            )
            saveAll(entries)
        }
    }

    fun removeEntry(id: String) {
        synchronized(lock) {
            val entries = loadAll().filter { it.id != id }
            saveAll(entries)
        }
    }

    fun removeEntryForPath(folderPath: String) {
        synchronized(lock) {
            val entries = loadAll().filter { it.folderPath != folderPath }
            saveAll(entries)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            getFile().delete()
        }
    }

    private fun saveAll(entries: List<SmartSortHistoryEntry>) {
        val json = JSONArray(entries.map { entry ->
            JSONObject().apply {
                put("id", entry.id)
                put("folderPath", entry.folderPath)
                put("sortDate", entry.sortDate)
                put("movedCount", entry.movedCount)
                put("skippedCount", entry.skippedCount)
                put("failedCount", entry.failedCount)
                put("manifestFolderPath", entry.manifestFolderPath)
            }
        })
        getFile().writeText(json.toString(2))
    }
}
