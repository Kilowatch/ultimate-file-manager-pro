package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import org.json.JSONArray

/**
 * Persists and restores the user-defined ordering of all tiles
 * on the main StorageBrowser screen.
 *
 * Category-aware merge: when new tiles appear (e.g. a freshly plugged USB
 * drive or a newly added SMB share) they are inserted after the last tile
 * of the same category rather than appended at the end, so the visual
 * grouping the user sees by default is preserved.
 */
object TileOrderManager {

    private const val PREFS_NAME         = "tile_order_prefs"
    private const val KEY_ORDER          = "tile_order"
    private const val KEY_HIDDEN         = "tile_hidden"
    private const val KEY_HIDDEN_PARENTS = "tile_hidden_parents"

    // ------------------------------------------------------------------ //
    //  Category helpers                                                    //
    // ------------------------------------------------------------------ //

    /** Broad category used only for grouping during merge. */
    private enum class TileCategory {
        STORAGE_VOLUME,   // Internal / SD / USB drives
        NETWORK_ROOT,     // SMB/FTP mounts + connected TV/Phone entries
        FEATURE           // All hard-coded feature tiles + custom tiles
    }

    private fun categoryOf(item: StorageItem): TileCategory = when {
        item.isNetworkRoot  -> TileCategory.NETWORK_ROOT
        isFeatureTile(item) -> TileCategory.FEATURE
        else                -> TileCategory.STORAGE_VOLUME  // physical drive
    }

    private fun isFeatureTile(item: StorageItem) =
        item.isAppsTile || item.isRemoteTile || item.isSearchTile ||
        item.isAnalyzerTile || item.isVaultTile ||
        item.isLegalTile || item.isRateUsTile || item.isSafTile ||
        item.isNetworkTile || item.isPairedDevicesTile || item.isExtractsTile ||
        item.isTipJarTile || item.isSyncTile || item.isAdvancedSyncTile || item.isTwinWindowTile || item.isShizukuTile || item.isTerminalTile || item.isFileServerTile || item.isSupportTile || item.isAboutTile ||
        item.isNotepadTile || item.isRecycleBinTile || item.isScannerTile ||
        item.isSmartSortTile || item.isOnlineStoragesTile || item.isFavoriteTile ||
        item.isCustomTile

    // ------------------------------------------------------------------ //
    //  Public API                                                          //
    // ------------------------------------------------------------------ //

    /** Persist the current tile order by ID list (all tiles included). */
    fun save(context: Context, orderedIds: List<String>) {
        val arr = JSONArray()
        orderedIds.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ORDER, arr.toString())
            .apply()
    }

    /** Load the saved order. Returns an empty list if nothing was saved yet. */
    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------ //
    //  Hidden-tile persistence                                            //
    // ------------------------------------------------------------------ //

    /**
     * Persist the set of tile IDs the user has chosen to hide,
     * along with their parent custom tile associations.
     *
     * @param hiddenIds set of tile IDs to hide
     * @param parentMap map of tileId -> parentCustomTileId (null or empty string = main screen).
     *                  Pass null to preserve existing parent data unchanged.
     */
    fun saveHidden(context: Context, hiddenIds: Set<String>, parentMap: Map<String, String?>? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        val arr = JSONArray()
        hiddenIds.forEach { arr.put(it) }
        editor.putString(KEY_HIDDEN, arr.toString())

        if (parentMap != null) {
            val parentObj = org.json.JSONObject()
            for ((tileId, parentId) in parentMap) {
                parentObj.put(tileId, parentId ?: "")
            }
            editor.putString(KEY_HIDDEN_PARENTS, parentObj.toString())
        }

        editor.apply()
    }

    /** Load the set of hidden tile IDs. Returns an empty set if nothing was saved. */
    fun loadHidden(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HIDDEN, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Load the hidden tile parent associations.
     * @return Map of tileId -> parentCustomTileId (null = main screen).
     *         Returns empty map if no parent data exists (backwards compatible with old data).
     */
    fun loadHiddenParents(context: Context): Map<String, String?> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HIDDEN_PARENTS, null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw)
            val result = mutableMapOf<String, String?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key, "")
                result[key] = value.ifEmpty { null }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Merge [saved] order with the [naturalItems] list built for this launch.
     *
     * Rules:
     * 1. IDs in [saved] that no longer exist in [naturalItems] are silently dropped.
     * 2. IDs that are present in [naturalItems] but absent in [saved] (new tiles) are
     *    inserted **after the last tile of the same category** already placed in the
     *    result — so a new USB drive appears after other drives, a new share after
     *    other shares, etc.
     *
     * @return Ordered ID list of all tiles ready to deliver to the adapter.
     */
    fun mergeWithNatural(
        saved: List<String>,
        naturalItems: List<StorageItem>
    ): List<String> {
        val naturalIds  = naturalItems.map { it.id }
        val naturalMap  = naturalItems.associateBy { it.id }
        val savedSet    = saved.toSet()

        // Start with saved IDs that still exist
        val merged = saved.filter { it in naturalIds }.toMutableList()

        // Find new tiles (present in natural but not in saved)
        val newIds = naturalIds.filter { it !in savedSet }

        for (newId in newIds) {
            val newItem = naturalMap[newId] ?: continue
            val newCat  = categoryOf(newItem)

            // Find the insertion point: after the last tile of the same category
            var insertAfter = -1
            for (i in merged.indices) {
                val existingItem = naturalMap[merged[i]] ?: continue
                if (categoryOf(existingItem) == newCat) {
                    insertAfter = i
                }
            }

            if (insertAfter >= 0) {
                merged.add(insertAfter + 1, newId)
            } else {
                // No same-category tile in merged yet — place in natural order
                val naturalPos = naturalIds.indexOf(newId)
                var insertIdx  = merged.size // default: append
                for (i in merged.indices) {
                    val existingNatPos = naturalIds.indexOf(merged[i])
                    if (existingNatPos > naturalPos) {
                        insertIdx = i
                        break
                    }
                }
                merged.add(insertIdx, newId)
            }
        }

        return merged
    }
}
