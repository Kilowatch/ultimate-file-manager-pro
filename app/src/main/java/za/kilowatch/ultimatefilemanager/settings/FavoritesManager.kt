package za.kilowatch.ultimatefilemanager.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R

/**
 * Manages user "Favorites" saved across the application.
 */
object FavoritesManager {
    private const val PREFS_NAME = "ufm_favorites_prefs"
    private const val KEY_FAVORITES = "favorites_array"

    data class FavoriteItem(
        val id: String,
        val path: String,
        val label: String,
        val isFolder: Boolean,
        val isNetwork: Boolean,
        val shareId: String? = null,
        val isPinnedMobile: Boolean = false,
        val isPinnedTv: Boolean = false,
        val tvProgramUri: String? = null,
        val isStorageTileFavorite: Boolean = false
    ) {
        fun toJson(): JSONObject {
            val json = JSONObject()
            json.put("id", id)
            json.put("path", path)
            json.put("label", label)
            json.put("isFolder", isFolder)
            json.put("isNetwork", isNetwork)
            json.put("shareId", shareId)
            json.put("isPinnedMobile", isPinnedMobile)
            json.put("isPinnedTv", isPinnedTv)
            json.put("tvProgramUri", tvProgramUri)
            json.put("isStorageTileFavorite", isStorageTileFavorite)
            return json
        }

        companion object {
            fun fromJson(json: JSONObject): FavoriteItem? {
                return try {
                    FavoriteItem(
                        id = json.getString("id"),
                        path = json.getString("path"),
                        label = json.getString("label"),
                        isFolder = json.getBoolean("isFolder"),
                        isNetwork = json.optBoolean("isNetwork", false),
                        shareId = if (json.has("shareId") && !json.isNull("shareId")) json.optString("shareId", null) else null,
                        isPinnedMobile = json.optBoolean("isPinnedMobile", false),
                        isPinnedTv = json.optBoolean("isPinnedTv", false),
                        tvProgramUri = if (json.has("tvProgramUri") && !json.isNull("tvProgramUri")) json.optString("tvProgramUri", null) else null,
                        isStorageTileFavorite = json.optBoolean("isStorageTileFavorite", false)
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getFavorites(context: Context): List<FavoriteItem> {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        val list = mutableListOf<FavoriteItem>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val item = FavoriteItem.fromJson(array.getJSONObject(i))
                if (item != null) {
                    list.add(item)
                }
            }
        } catch (e: Exception) {
            Log.e("FavoritesManager", "Failed to parse favorites JSON", e)
        }
        return list
    }

    fun addFavorite(context: Context, favorite: FavoriteItem) {
        val currentFavorites = getFavorites(context).toMutableList()
        val existingIndex = currentFavorites.indexOfFirst { it.path == favorite.path }
        if (existingIndex != -1) {
            val existing = currentFavorites[existingIndex]
            val merged = favorite.copy(
                id = existing.id,
                isPinnedMobile = favorite.isPinnedMobile || existing.isPinnedMobile,
                isPinnedTv = favorite.isPinnedTv || existing.isPinnedTv,
                isStorageTileFavorite = favorite.isStorageTileFavorite || existing.isStorageTileFavorite
            )
            currentFavorites[existingIndex] = merged
            saveFavorites(context, currentFavorites)
            notifyWidget(context)
        } else {
            currentFavorites.add(favorite)
            saveFavorites(context, currentFavorites)
            notifyWidget(context)
        }
    }

    fun updateItem(context: Context, updatedItem: FavoriteItem) {
        val currentFavorites = getFavorites(context).toMutableList()
        val index = currentFavorites.indexOfFirst { it.id == updatedItem.id }
        if (index != -1) {
            currentFavorites[index] = updatedItem
            saveFavorites(context, currentFavorites)
            notifyWidget(context)
        }
    }

    fun removeFavorite(context: Context, path: String) {
        val currentFavorites = getFavorites(context).toMutableList()
        val toRemove = currentFavorites.filter { it.path == path }
        val removed = currentFavorites.removeAll { it.path == path }
        if (removed) {
            saveFavorites(context, currentFavorites)
            notifyWidget(context)
            // Trigger cleanup for pinned shortcuts / TV programs
            toRemove.forEach { item ->
                try {
                    za.kilowatch.ultimatefilemanager.shortcuts.FavoriteHomeScreenManager.onFavoriteRemoved(context, item)
                } catch (e: Throwable) {
                    Log.w("FavoritesManager", "Failed to clean home presence: ${e.message}")
                }
            }
        }
    }

    fun clearAllFavorites(context: Context) {
        getPrefs(context).edit().remove(KEY_FAVORITES).apply()
        notifyWidget(context)
    }

    fun isFavorite(context: Context, path: String): Boolean {
        return getFavorites(context).any { it.path == path }
    }

    private fun saveFavorites(context: Context, favorites: List<FavoriteItem>) {
        val array = JSONArray()
        for (item in favorites) {
            array.put(item.toJson())
        }
        getPrefs(context).edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    /**
     * Notifies all active Bookmark Launcher widgets to refresh their list.
     * This is a lightweight IPC call to AppWidgetManager — safe to call from any thread.
     * Is a no-op when no widget is currently placed on the home screen, so TV builds
     * (which have no BookmarkWidgetProvider registered) are completely unaffected.
     */
    private fun notifyWidget(context: Context) {
        try {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, "za.kilowatch.ultimatefilemanager.widget.BookmarkWidgetProvider")
            val ids = manager.getAppWidgetIds(provider)
            if (ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_view)
            }
        } catch (e: Exception) {
            // Silently ignore — widget refresh is best-effort and must never crash the app
            Log.w("FavoritesManager", "Widget notify skipped: ${e.message}")
        }
    }

    fun getFavoriteProtocolLabel(
        context: Context,
        isNetwork: Boolean,
        shareId: String?,
        netShare: za.kilowatch.ultimatefilemanager.network.NetworkShare? = null
    ): String {
        if (!isNetwork) {
            return try {
                context.getString(R.string.fav_protocol_local)
            } catch (_: Exception) {
                "Local"
            }
        }
        val effShare = netShare ?: if (!shareId.isNullOrEmpty()) {
            if (shareId.startsWith("tv_")) null else {
                za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(context).getById(shareId)
                    ?: za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(context).getById(shareId)?.toNetworkShare()
            }
        } else null

        if (shareId?.startsWith("tv_") == true || effShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
            return "TV"
        }

        if (effShare != null) {
            return when (effShare.type) {
                za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> "WebDAV"
                za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> "SMB"
                za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> "NFS"
                za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> "FTP"
                za.kilowatch.ultimatefilemanager.network.ShareType.SFTP -> "SFTP"
                za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> "SCP"
                za.kilowatch.ultimatefilemanager.network.ShareType.TV -> "TV"
                za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> "DLNA"
                za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> "OneDrive"
                za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> "Google Drive"
                za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> "Dropbox"
                za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3 -> "Amazon S3"
                za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> "IDrive e2"
            }
        }
        return "Network"
    }

    fun getFavoriteProtocolLabel(context: Context, item: FavoriteItem): String {
        return getFavoriteProtocolLabel(context, item.isNetwork, item.shareId)
    }

    fun getFavoriteSubtitle(context: Context, item: FavoriteItem): String {
        return getFavoriteSubtitle(context, item.isNetwork, item.isFolder, item.shareId)
    }

    fun getFavoriteSubtitle(
        context: Context,
        isNetwork: Boolean,
        isFolder: Boolean,
        shareId: String?,
        netShare: za.kilowatch.ultimatefilemanager.network.NetworkShare? = null
    ): String {
        val proto = getFavoriteProtocolLabel(context, isNetwork, shareId, netShare)
        val type = if (isFolder) {
            try { context.getString(R.string.folder) } catch (_: Exception) { "Folder" }
        } else {
            try { context.getString(R.string.file) } catch (_: Exception) { "File" }
        }
        return try {
            context.getString(R.string.fav_protocol_type_format, proto, type)
        } catch (_: Exception) {
            "$proto \u2022 $type"
        }
    }
}
