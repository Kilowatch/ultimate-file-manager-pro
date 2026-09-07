package za.kilowatch.ultimatefilemanager.tabs

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import org.json.JSONArray
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.storage.SafLocationRepository
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import java.io.File

object TabSessionManager {

    private const val PREFS_NAME = "tabs_preferences"
    private const val KEY_TABS_JSON = "tabs_json"
    private const val KEY_ACTIVE_TAB_ID = "active_tab_id"

    fun saveSession(context: Context, tabs: List<TabModel>, activeTabId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (tab in tabs) {
            jsonArray.put(tab.toJson())
        }
        prefs.edit()
            .putString(KEY_TABS_JSON, jsonArray.toString())
            .putString(KEY_ACTIVE_TAB_ID, activeTabId)
            .apply()
    }

    fun loadSession(context: Context): Pair<List<TabModel>, String?> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_TABS_JSON, null) ?: return Pair(emptyList(), null)
        val activeTabId = prefs.getString(KEY_ACTIVE_TAB_ID, null)

        val result = mutableListOf<TabModel>()
        return try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val tab = TabModel.fromJson(obj) ?: continue
                result.add(tab)
            }
            Pair(result, activeTabId)
        } catch (_: Exception) {
            Pair(emptyList(), null)
        }
    }

    fun hasSavedTabs(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_TABS_JSON, null) ?: return false
        return jsonStr.length > 2 // more than "[]"
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * Validates that the storage location for each tab is currently accessible/connected.
     * If a location is disconnected (e.g. unmounted SD card, unplugged USB, deleted share),
     * the tab is automatically closed and pruned from the session.
     *
     * Returns a Pair of:
     * - Valid active tabs list
     * - List of closed tab titles (for user notification)
     */
    fun validateAndPrune(context: Context, tabs: List<TabModel>): Pair<List<TabModel>, List<String>> {
        val validTabs = mutableListOf<TabModel>()
        val closedTabNames = mutableListOf<String>()

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val mountedVolumes = storageManager?.storageVolumes ?: emptyList()
        val mountedPaths = mutableSetOf<String>()
        for (vol in mountedVolumes) {
            try {
                val state = vol.state
                if (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY) {
                    val getPathMethod = vol.javaClass.getMethod("getPath")
                    val path = getPathMethod.invoke(vol) as? String
                    if (!path.isNullOrEmpty()) {
                        mountedPaths.add(path)
                    }
                }
            } catch (_: Exception) {}
        }

        val networkRepo = NetworkShareRepository.getInstance(context)

        for (tab in tabs) {
            val isAvailable = when (tab.storageType) {
                StorageType.LOCAL -> {
                    val root = File(tab.rootPath)
                    if (tab.rootPath.startsWith("/storage/emulated") || tab.rootPath.startsWith("/sdcard")) {
                        root.exists() && root.canRead()
                    } else if (mountedPaths.isNotEmpty() && mountedPaths.any { tab.rootPath.startsWith(it) }) {
                        root.exists()
                    } else {
                        root.exists() && root.canRead()
                    }
                }
                StorageType.SAF -> {
                    if (SafTreeManager.isSafPath(tab.rootPath)) {
                        val locId = tab.rootPath.removePrefix("saf://").substringBefore('/')
                        val loc = SafLocationRepository.getLocationById(context, locId)
                        loc != null && SafTreeManager.hasTreePermissionForPath(context, tab.rootPath)
                    } else {
                        val root = File(tab.rootPath)
                        (root.exists() && root.canRead()) || (mountedPaths.isNotEmpty() && mountedPaths.any { tab.rootPath.startsWith(it) })
                    }
                }
                StorageType.NETWORK, StorageType.CLOUD -> {
                    if (tab.shareId.isNullOrEmpty()) {
                        false
                    } else {
                        networkRepo.getById(tab.shareId) != null ||
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(context).getById(tab.shareId) != null
                    }
                }
            }

            if (isAvailable) {
                // Also verify that currentPath exists, otherwise fallback to rootPath
                if (tab.storageType == StorageType.LOCAL) {
                    val cur = File(tab.currentPath)
                    if (!cur.exists()) {
                        tab.currentPath = tab.rootPath
                    }
                }
                validTabs.add(tab)
            } else {
                closedTabNames.add(tab.title)
            }
        }

        if (validTabs.isEmpty()) {
            validTabs.add(createDefaultTab(context))
        }

        return Pair(validTabs, closedTabNames)
    }

    fun createDefaultTab(context: Context): TabModel {
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        val label = context.getString(R.string.storage_internal)
        return TabModel(
            title = label,
            isCustomName = false,
            storageType = StorageType.LOCAL,
            rootPath = internalPath,
            currentPath = internalPath,
            storageLabel = label
        )
    }
}
