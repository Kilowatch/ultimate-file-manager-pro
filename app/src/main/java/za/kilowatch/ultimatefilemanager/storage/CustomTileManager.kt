package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists all custom tile data: metadata, parent-child relationships,
 * and per-custom-tile tile ordering.
 *
 * Storage: SharedPreferences file "custom_tiles_prefs".
 * Keys:
 *   - "custom_tiles_meta"  → JSON array of custom tile metadata objects
 *   - "tile_parents"       → JSON object { tileId: parentCustomTileId }
 *   - "tile_order_<id>"    → JSON array of ordered child tile IDs per custom tile
 */
object CustomTileManager {

    private const val PREFS_NAME = "custom_tiles_prefs"
    private const val KEY_META   = "custom_tiles_meta"
    private const val KEY_PARENTS = "tile_parents"
    private const val KEY_ORDER_PREFIX = "tile_order_"

    // ------------------------------------------------------------------ //
    //  Data classes                                                        //
    // ------------------------------------------------------------------ //

    data class CustomTileData(
        val id: String,
        val title: String,
        val subtitle: String,
        val iconRes: Int
    )

    // ------------------------------------------------------------------ //
    //  Custom tile CRUD                                                    //
    // ------------------------------------------------------------------ //

    /** Generates a unique custom tile ID. */
    fun generateId(): String = "custom_${UUID.randomUUID().toString().take(8)}"

    /** Save (create or update) a custom tile. */
    fun saveCustomTile(context: Context, data: CustomTileData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = loadCustomTiles(context).toMutableList()
        val idx = existing.indexOfFirst { it.id == data.id }
        if (idx >= 0) {
            existing[idx] = data
        } else {
            existing.add(data)
        }
        prefs.edit().putString(KEY_META, serializeMeta(existing)).apply()
    }

    /** Load all custom tiles. */
    fun loadCustomTiles(context: Context): List<CustomTileData> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_META, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CustomTileData(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    subtitle = obj.optString("subtitle", ""),
                    iconRes = obj.optInt("iconRes", 0)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Delete a custom tile and all its associated data. */
    fun deleteCustomTile(context: Context, tileId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Remove metadata
        val existing = loadCustomTiles(context).toMutableList()
        existing.removeAll { it.id == tileId }
        editor.putString(KEY_META, serializeMeta(existing))

        // Clear parent mapping for all children
        val parents = getTileParentMap(context).toMutableMap()
        parents.entries.removeAll { it.value == tileId }
        editor.putString(KEY_PARENTS, serializeParents(parents))

        // Remove per-tile order
        editor.remove(KEY_ORDER_PREFIX + tileId)

        editor.apply()
    }

    // ------------------------------------------------------------------ //
    //  Parent-child relationships                                          //
    // ------------------------------------------------------------------ //

    /**
     * Set which custom tile a regular tile belongs to.
     * @param parentCustomTileId null = main screen (not inside any custom tile).
     */
    fun setTileParent(context: Context, tileId: String, parentCustomTileId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val parents = getTileParentMap(context).toMutableMap()
        if (parentCustomTileId != null) {
            parents[tileId] = parentCustomTileId
        } else {
            parents.remove(tileId)
        }
        prefs.edit().putString(KEY_PARENTS, serializeParents(parents)).apply()
    }

    /** Returns tileId → parentCustomTileId map for ALL tiles that have a parent. */
    fun getTileParentMap(context: Context): Map<String, String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PARENTS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.getString(key)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Returns IDs of all tiles belonging to a specific custom tile. */
    fun getChildTiles(context: Context, customTileId: String): List<String> {
        val parentMap = getTileParentMap(context)
        return parentMap.filter { it.value == customTileId }.keys.toList()
    }

    // ------------------------------------------------------------------ //
    //  Per-custom-tile order                                               //
    // ------------------------------------------------------------------ //

    /** Save the internal tile order for a specific custom tile. */
    fun saveTileOrder(context: Context, customTileId: String, orderedIds: List<String>) {
        val arr = JSONArray()
        orderedIds.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ORDER_PREFIX + customTileId, arr.toString())
            .apply()
    }

    /** Load the internal tile order for a specific custom tile. */
    fun loadTileOrder(context: Context, customTileId: String): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ORDER_PREFIX + customTileId, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------ //
    //  Export / Import                                                     //
    // ------------------------------------------------------------------ //

    /** Serialize all custom tile data for backup export.
     *  @param selectedIds if non-empty only the custom tiles whose IDs are in this set are
     *                     included; an empty set means "export everything". */
    fun getAllCustomTileDataForExport(context: Context, selectedIds: Set<String> = emptySet()): JSONArray {
        val tiles = loadCustomTiles(context)
        val arr = JSONArray()
        for (tile in tiles) {
            // Skip tiles that were not selected by the user (if a selection was made)
            if (selectedIds.isNotEmpty() && !selectedIds.contains(tile.id)) continue

            val obj = JSONObject()
            obj.put("id", tile.id)
            obj.put("title", tile.title)
            obj.put("subtitle", tile.subtitle)
            obj.put("iconRes", tile.iconRes)

            // Child tiles in order
            val childIds = loadTileOrder(context, tile.id)
            val childrenArr = JSONArray()
            for (childId in childIds) {
                val childObj = JSONObject()
                childObj.put("tileId", childId)

                // Export color config if present
                val colors = TileColorManager.loadTileColors(context)
                colors[childId]?.let { childObj.put("colorConfig", it.toJson()) }

                // Export icon config if present
                val icons = TileIconManager.getAllTileIcons(context)
                val iconResMap = TileIconManager.getAllTileIconRes(context)
                val iconPath = icons[childId]
                val iconRes = iconResMap[childId]
                if (iconPath != null || (iconRes != null && iconRes != 0)) {
                    val iconObj = JSONObject()
                    if (iconPath != null) iconObj.put("customIconPath", iconPath)
                    if (iconRes != null && iconRes != 0) iconObj.put("selectedIconRes", iconRes)
                    childObj.put("iconConfig", iconObj)
                }

                childrenArr.put(childObj)
            }
            obj.put("children", childrenArr)
            arr.put(obj)
        }
        return arr
    }

    /** Restore all custom tile data from a backup import. */
    fun restoreFromExport(context: Context, jsonArray: JSONArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Clear existing data
        editor.clear()

        val metaList = mutableListOf<CustomTileData>()
        val parents = mutableMapOf<String, String>()
        val restoredTileIds = mutableSetOf<String>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val tileId = obj.getString("id")
            val title = obj.getString("title")
            val subtitle = obj.optString("subtitle", "")
            val iconRes = obj.optInt("iconRes", 0)

            metaList.add(CustomTileData(tileId, title, subtitle, iconRes))
            restoredTileIds.add(tileId)

            // Restore children and their order
            val childrenArr = obj.optJSONArray("children")
            if (childrenArr != null) {
                val orderedIds = mutableListOf<String>()
                for (j in 0 until childrenArr.length()) {
                    val childObj = childrenArr.getJSONObject(j)
                    val childId = childObj.getString("tileId")
                    parents[childId] = tileId
                    orderedIds.add(childId)

                    // Restore color config
                    val colorObj = childObj.optJSONObject("colorConfig")
                    if (colorObj != null) {
                        TileColorManager.saveTileColor(
                            context, childId, TileColorConfig.fromJson(colorObj)
                        )
                    }

                    // Restore icon config
                    val iconObj = childObj.optJSONObject("iconConfig")
                    if (iconObj != null) {
                        val iconPath = iconObj.optString("customIconPath", null)
                        val iconResVal = iconObj.optInt("selectedIconRes", 0)
                        if (iconPath != null) {
                            TileIconManager.saveTileIcon(context, childId, iconPath)
                        }
                        if (iconResVal != 0) {
                            TileIconManager.saveTileIconRes(context, childId, iconResVal)
                        }
                    }
                }
                editor.putString(KEY_ORDER_PREFIX + tileId, JSONArray(orderedIds).toString())
            }
        }

        editor.putString(KEY_META, serializeMeta(metaList))
        editor.putString(KEY_PARENTS, serializeParents(parents))
        editor.commit()

        // Ensure the restored custom-tile container IDs are not in the hidden set
        // so they appear on-screen immediately after import, even if the target
        // device previously had them in its hidden list from a prior state.
        if (restoredTileIds.isNotEmpty()) {
            val tileOrderPrefs = context.getSharedPreferences("tile_order_prefs", Context.MODE_PRIVATE)
            val hiddenRaw = tileOrderPrefs.getString("tile_hidden", null)
            if (!hiddenRaw.isNullOrEmpty()) {
                val hiddenArr = try { org.json.JSONArray(hiddenRaw) } catch (_: Exception) { null }
                if (hiddenArr != null) {
                    val updatedHidden = org.json.JSONArray()
                    for (k in 0 until hiddenArr.length()) {
                        val id = hiddenArr.getString(k)
                        if (!restoredTileIds.contains(id)) updatedHidden.put(id)
                    }
                    if (updatedHidden.length() != hiddenArr.length()) {
                        tileOrderPrefs.edit().putString("tile_hidden", updatedHidden.toString()).commit()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Internal helpers                                                    //
    // ------------------------------------------------------------------ //

    private fun serializeMeta(tiles: List<CustomTileData>): String {
        val arr = JSONArray()
        for (tile in tiles) {
            val obj = JSONObject()
            obj.put("id", tile.id)
            obj.put("title", tile.title)
            obj.put("subtitle", tile.subtitle)
            obj.put("iconRes", tile.iconRes)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun serializeParents(parents: Map<String, String>): String {
        val obj = JSONObject()
        for ((tileId, parentId) in parents) {
            obj.put(tileId, parentId)
        }
        return obj.toString()
    }
}
