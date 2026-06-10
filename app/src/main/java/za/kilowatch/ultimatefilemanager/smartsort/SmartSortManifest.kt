package za.kilowatch.ultimatefilemanager.smartsort

import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.UfmApplication
import java.io.File

data class SortManifestEntry(
    val originalPath: String,
    val newPath: String,
    val fileSize: Long,
    val timestamp: Long,
    val categoryKey: String? = null
)

data class SmartSortManifest(
    val sourcePath: String,
    val entries: List<SortManifestEntry>,
    val createdAt: Long,
    val customCategoryPaths: Map<String, String> = emptyMap(),
    val customCategoryShareIds: Map<String, String> = emptyMap(),
    val sourceShareId: String? = null
) {
    companion object {
        private const val MANIFEST_DIR = "ufm_smart_sort_manifests"

        private fun manifestDir(): File {
            return File(UfmApplication.instance.filesDir, MANIFEST_DIR).also { it.mkdirs() }
        }

        fun manifestFile(rootPath: String): File {
            val safeName = rootPath.replace("/", "_").replace(":", "_").trimStart('_')
                .take(100) + ".json"
            return File(manifestDir(), safeName)
        }

        suspend fun save(rootPath: String, config: SmartSortConfig, entries: List<SortManifestEntry>) {
            val json = JSONObject().apply {
                put("sourcePath", rootPath)
                put("config", JSONObject().apply {
                    put("mode", config.mode.name)
                    put("recursive", config.recursive)
                    put("flattenSubfolders", config.flattenSubfolders)
                    put("maxDepth", config.maxDepth)
                    put("prefix", config.prefix)
                    put("enabledCategories", JSONArray(config.enabledCategories.map { it.name }))
                    put("duplicateStrategy", config.duplicateStrategy.name)
                    put("existingFolderStrategy", config.existingFolderStrategy.name)
                    if (config.customCategoryPaths.isNotEmpty()) {
                        put("customCategoryPaths", JSONObject(config.customCategoryPaths))
                    }
                    if (config.customCategoryShareIds.isNotEmpty()) {
                        put("customCategoryShareIds", JSONObject(config.customCategoryShareIds))
                    }
                })
                if (config.shareInfo != null) {
                    put("sourceShareId", config.shareInfo!!.id)
                }
                put("createdAt", System.currentTimeMillis())
                put("entries", JSONArray(entries.map { entry ->
                    JSONObject().apply {
                        put("originalPath", entry.originalPath)
                        put("newPath", entry.newPath)
                        put("fileSize", entry.fileSize)
                        put("timestamp", entry.timestamp)
                        if (entry.categoryKey != null) {
                            put("categoryKey", entry.categoryKey)
                        }
                    }
                }))
            }
            val file = manifestFile(rootPath)
            file.parentFile?.mkdirs()
            file.writeText(json.toString(2))
        }

        suspend fun load(manifestKeyOrRootPath: String): SmartSortManifest? {
            return try {
                val file = manifestFile(manifestKeyOrRootPath)
                if (!file.exists()) return null
                val json = JSONObject(file.readText())
                val entriesArray = json.getJSONArray("entries")
                val entries = (0 until entriesArray.length()).map { i ->
                    val entry = entriesArray.getJSONObject(i)
                    SortManifestEntry(
                        originalPath = entry.getString("originalPath"),
                        newPath = entry.getString("newPath"),
                        fileSize = entry.optLong("fileSize", 0L),
                        timestamp = entry.optLong("timestamp", 0L),
                        categoryKey = entry.optString("categoryKey", null)
                    )
                }
                val customPaths = mutableMapOf<String, String>()
                val customShareIds = mutableMapOf<String, String>()
                val configObj = json.optJSONObject("config")
                if (configObj != null) {
                    val pathsObj = configObj.optJSONObject("customCategoryPaths")
                    if (pathsObj != null) {
                        for (key in pathsObj.keys()) {
                            customPaths[key] = pathsObj.getString(key)
                        }
                    }
                    val shareIdsObj = configObj.optJSONObject("customCategoryShareIds")
                    if (shareIdsObj != null) {
                        for (key in shareIdsObj.keys()) {
                            customShareIds[key] = shareIdsObj.getString(key)
                        }
                    }
                }
                SmartSortManifest(
                    sourcePath = json.getString("sourcePath"),
                    entries = entries,
                    createdAt = json.getLong("createdAt"),
                    customCategoryPaths = customPaths,
                    customCategoryShareIds = customShareIds,
                    sourceShareId = json.optString("sourceShareId", null)
                )
            } catch (e: Exception) {
                null
            }
        }

        fun delete(manifestKeyOrRootPath: String) {
            manifestFile(manifestKeyOrRootPath).delete()
        }
    }
}
