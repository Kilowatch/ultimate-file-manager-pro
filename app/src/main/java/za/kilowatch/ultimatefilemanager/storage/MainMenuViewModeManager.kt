package za.kilowatch.ultimatefilemanager.storage

import android.content.Context

/**
 * Manages view preferences for the Main Menu (Storage Browser).
 */
object MainMenuViewModeManager {

    private const val PREFS_NAME = "ufm_main_menu_prefs"
    private const val KEY_VIEW_MODE = "main_menu_view_mode"
    private const val KEY_COLUMN_COUNT = "main_menu_column_count"
    private const val KEY_ITEM_SIZE = "main_menu_item_size"
    private const val PREF_PREFIX_EXPANDED = "category_expanded_"

    const val CATEGORY_STORAGE   = "category_storage"
    const val CATEGORY_CONNECT   = "category_connect"
    const val CATEGORY_SYNC      = "category_sync"
    const val CATEGORY_ORGANIZE  = "category_organize"
    const val CATEGORY_UTILITIES = "category_utilities"
    const val CATEGORY_SECURITY  = "category_security"
    const val CATEGORY_SETTINGS  = "category_settings"

    enum class ViewMode {
        LIST,               // Classic single-column list (Default)
        GRID,               // Classic multicolumn grid
        MODERN_CATEGORIZED  // Modern grouped/collapsible sections
    }

    fun saveViewMode(context: Context, mode: ViewMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VIEW_MODE, mode.ordinal)
            .apply()
    }

    fun loadViewMode(context: Context): ViewMode {
        val ordinal = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_VIEW_MODE, ViewMode.LIST.ordinal)
        return ViewMode.entries.getOrElse(ordinal) { ViewMode.LIST }
    }

    fun saveColumnCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_COLUMN_COUNT, count)
            .apply()
    }

    fun loadColumnCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COLUMN_COUNT, 3)
        
        // Migrate legacy 2-column or other unsupported values to 3
        if (count != 3 && count != 4) {
            prefs.edit().putInt(KEY_COLUMN_COUNT, 3).apply()
            return 3
        }
        return count
    }

    enum class ItemSize { LARGE, MEDIUM, SMALL }

    fun saveItemSize(context: Context, size: ItemSize) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ITEM_SIZE, size.ordinal)
            .apply()
    }

    fun loadItemSize(context: Context): ItemSize {
        val ordinal = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ITEM_SIZE, ItemSize.MEDIUM.ordinal)
        return ItemSize.entries.getOrElse(ordinal) { ItemSize.MEDIUM }
    }

    private const val KEY_CUSTOM_CATEGORIES = "main_menu_tile_categories"

    fun isCategoryExpanded(context: Context, categoryId: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_PREFIX_EXPANDED + categoryId, true)
    }

    fun setCategoryExpanded(context: Context, categoryId: String, expanded: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_PREFIX_EXPANDED + categoryId, expanded)
            .apply()
    }

    fun saveTileCategory(context: Context, tileId: String, categoryId: String) {
        val map = loadAllTileCategories(context).toMutableMap()
        map[tileId] = categoryId
        saveAllTileCategories(context, map)
    }

    fun loadTileCategory(context: Context, tileId: String): String? {
        return loadAllTileCategories(context)[tileId]
    }

    fun loadAllTileCategories(context: Context): Map<String, String> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_CATEGORIES, null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.getString(k)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveAllTileCategories(context: Context, map: Map<String, String>) {
        val obj = org.json.JSONObject()
        for ((k, v) in map) {
            obj.put(k, v)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_CATEGORIES, obj.toString())
            .apply()
    }

    private const val KEY_CATEGORY_ORDER = "main_menu_category_order"

    val DEFAULT_CATEGORY_ORDER: List<String> = listOf(
        CATEGORY_STORAGE,
        CATEGORY_CONNECT,
        CATEGORY_SYNC,
        CATEGORY_ORGANIZE,
        CATEGORY_UTILITIES,
        CATEGORY_SECURITY,
        CATEGORY_SETTINGS
    )

    fun loadCategoryOrder(context: Context): List<String> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CATEGORY_ORDER, null) ?: return DEFAULT_CATEGORY_ORDER
        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            for (def in DEFAULT_CATEGORY_ORDER) {
                if (def !in list) list.add(def)
            }
            list
        } catch (_: Exception) {
            DEFAULT_CATEGORY_ORDER
        }
    }

    fun saveCategoryOrder(context: Context, order: List<String>) {
        val arr = org.json.JSONArray()
        for (cat in order) {
            arr.put(cat)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CATEGORY_ORDER, arr.toString())
            .apply()
    }

    private const val KEY_CUSTOM_HEADER_DEFS = "main_menu_custom_header_defs"

    fun loadCustomCategories(context: Context): Map<String, String> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_HEADER_DEFS, null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.getString(k)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveCustomCategory(context: Context, categoryId: String, title: String) {
        val map = loadCustomCategories(context).toMutableMap()
        map[categoryId] = title
        val obj = org.json.JSONObject()
        for ((k, v) in map) {
            obj.put(k, v)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_HEADER_DEFS, obj.toString())
            .apply()
    }

    fun deleteCustomCategory(context: Context, categoryId: String) {
        val map = loadCustomCategories(context).toMutableMap()
        map.remove(categoryId)
        val obj = org.json.JSONObject()
        for ((k, v) in map) {
            obj.put(k, v)
        }
        val order = loadCategoryOrder(context).toMutableList()
        order.remove(categoryId)
        saveCategoryOrder(context, order)

        val tileCatMap = loadAllTileCategories(context).toMutableMap()
        val toRemove = tileCatMap.filter { it.value == categoryId }.keys
        for (k in toRemove) {
            tileCatMap.remove(k)
        }
        saveAllTileCategories(context, tileCatMap)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_HEADER_DEFS, obj.toString())
            .remove(PREF_PREFIX_EXPANDED + categoryId)
            .apply()
    }

    /**
     * Resets all custom categorized layout state to factory defaults:
     * - Restores category order to DEFAULT_CATEGORY_ORDER
     * - Clears all custom tile-to-category assignments
     * - Clears all custom category header definitions
     * - Clears all category collapse/expanded state overrides (defaults all to expanded)
     * - Resets overall tile ordering so items return to natural order
     */
    fun resetCategoryLayout(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.remove(KEY_CATEGORY_ORDER)
        editor.remove(KEY_CUSTOM_CATEGORIES)
        editor.remove(KEY_CUSTOM_HEADER_DEFS)
        for (key in prefs.all.keys) {
            if (key.startsWith(PREF_PREFIX_EXPANDED)) {
                editor.remove(key)
            }
        }
        editor.apply()
        TileOrderManager.save(context, emptyList())
    }
}

