package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Represents a document provider mounted by the user as a persistent tile on the Main Storage Menu.
 */
data class MountedDocumentProvider(
    val authority: String,
    val packageName: String,
    val displayName: String,
    val isTreeCapable: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("authority", authority)
        put("packageName", packageName)
        put("displayName", displayName)
        put("isTreeCapable", isTreeCapable)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): MountedDocumentProvider {
            return MountedDocumentProvider(
                authority = json.getString("authority"),
                packageName = json.optString("packageName", ""),
                displayName = json.optString("displayName", "Document Provider"),
                isTreeCapable = json.optBoolean("isTreeCapable", false),
                addedAt = json.optLong("addedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Manages persistent storage and retrieval of user-mounted Document Providers
 * that appear as dedicated tiles on the Main Storage Menu.
 */
object DocumentProviderRepository {

    const val PREFS_NAME = "ufm_mounted_doc_providers"
    private const val KEY_PROVIDERS = "mounted_providers_json"

    @Volatile
    private var cachedProviders: List<MountedDocumentProvider>? = null

    fun clearCache() {
        cachedProviders = null
    }

    fun getMountedProviders(context: Context): List<MountedDocumentProvider> {
        val cached = cachedProviders
        if (cached != null) return cached

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PROVIDERS, null) ?: return emptyList()
        val list = mutableListOf<MountedDocumentProvider>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(MountedDocumentProvider.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            GoRoLog.e("DocProviderRepo", "Failed to parse mounted providers: ${e.message}")
        }
        cachedProviders = list
        return list
    }

    fun isProviderMounted(context: Context, authority: String): Boolean {
        return getMountedProviders(context).any { it.authority == authority }
    }

    fun addProvider(context: Context, provider: MountedDocumentProvider) {
        val current = getMountedProviders(context).toMutableList()
        current.removeAll { it.authority == provider.authority }
        current.add(provider)
        save(context, current)
    }

    fun removeProvider(context: Context, authority: String) {
        val current = getMountedProviders(context).toMutableList()
        current.removeAll { it.authority == authority }
        save(context, current)
    }

    private fun save(context: Context, list: List<MountedDocumentProvider>) {
        cachedProviders = list
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROVIDERS, arr.toString())
            .apply()
    }
}
