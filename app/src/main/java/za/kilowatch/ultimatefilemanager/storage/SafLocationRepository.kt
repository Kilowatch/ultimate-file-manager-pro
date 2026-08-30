package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Manages persistent storage, permission persistence, and lookup for user-mounted
 * custom SAF storage locations (Termux, DocumentProviders, USB, etc.).
 */
object SafLocationRepository {

    const val PREFS_NAME = "ufm_saf_locations_prefs"
    private const val KEY_LOCATIONS = "saf_custom_locations_json"

    @Volatile
    private var cachedLocations: List<SafLocation>? = null

    fun getLocations(context: Context): List<SafLocation> {
        val cached = cachedLocations
        if (cached != null) return cached

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_LOCATIONS, null) ?: run {
            cachedLocations = emptyList()
            return emptyList()
        }
        val list = mutableListOf<SafLocation>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(SafLocation.fromJson(obj))
            }
        } catch (e: Exception) {
            GoRoLog.e("SafLocationRepository", "Error parsing custom SAF locations", e)
        }
        val result = list.toList()
        cachedLocations = result
        return result
    }

    fun addLocation(context: Context, location: SafLocation): Boolean {
        try {
            val uri = Uri.parse(location.treeUriString)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                GoRoLog.w("SafLocationRepository", "Failed to take persistable permission: ${e.message}")
            }

            val current = getLocations(context).toMutableList()
            current.removeAll { it.id == location.id || it.treeUriString == location.treeUriString }
            current.add(location)
            saveLocations(context, current)
            return true
        } catch (e: Exception) {
            GoRoLog.e("SafLocationRepository", "Failed to add SAF location", e)
            return false
        }
    }

    fun updateLocation(context: Context, location: SafLocation) {
        val current = getLocations(context).toMutableList()
        val index = current.indexOfFirst { it.id == location.id }
        if (index != -1) {
            current[index] = location
            saveLocations(context, current)
        }
    }

    fun removeLocation(context: Context, locationId: String): Boolean {
        val current = getLocations(context).toMutableList()
        val location = current.firstOrNull { it.id == locationId } ?: return false

        try {
            val uri = Uri.parse(location.treeUriString)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        current.removeAll { it.id == locationId }
        saveLocations(context, current)
        return true
    }

    fun getLocationById(context: Context, id: String): SafLocation? {
        return getLocations(context).firstOrNull { it.id == id }
    }

    fun getLocationByUri(context: Context, uri: Uri): SafLocation? {
        val uriStr = uri.toString()
        return getLocations(context).firstOrNull { it.treeUriString == uriStr }
    }

    fun isUriAlreadyAdded(context: Context, uri: Uri): Boolean {
        val uriStr = uri.toString()
        return getLocations(context).any { it.treeUriString == uriStr }
    }

    fun getLocationsForStorage(context: Context, storageMountPath: String): List<SafLocation> {
        val cleanRoot = storageMountPath.trimEnd('/')
        return getLocations(context).filter { loc ->
            val displayPath = loc.getDisplayPath().trimEnd('/')
            displayPath == cleanRoot || displayPath.startsWith("$cleanRoot/")
        }
    }

    private fun saveLocations(context: Context, locations: List<SafLocation>) {
        val jsonArray = JSONArray()
        for (loc in locations) {
            jsonArray.put(loc.toJson())
        }
        val result = locations.toList()
        cachedLocations = result
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOCATIONS, jsonArray.toString()).apply()
    }
}
