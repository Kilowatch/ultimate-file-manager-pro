package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences wrapper for DLNA server/renderer configuration.
 * Stored in the same preferences file as FTP/SFTP server settings.
 *
 * Security: Both server and renderer are OFF by default on fresh install (SR-03, SR-04).
 * Shared folder URIs are stored as JSON — no plain-text credential leakage risk
 * since DLNA has no authentication.
 */
object DlnaServerPrefs {

    private const val TAG = "DlnaServerPrefs"
    private const val PREFS_NAME = "file_server_prefs"

    // Keys
    private const val KEY_DLNA_SERVER_ENABLED = "dlna_server_enabled"
    private const val KEY_DLNA_RENDERER_ENABLED = "dlna_renderer_enabled"
    private const val KEY_DLNA_SERVER_NAME = "dlna_server_name"
    private const val KEY_DLNA_RENDERER_NAME = "dlna_renderer_name"
    private const val KEY_DLNA_SHARED_FOLDERS = "dlna_shared_folders"
    private const val KEY_DLNA_SERVER_PORT = "dlna_server_port"

    // Defaults
    private const val DEFAULT_SERVER_NAME = "UFM Media Server"
    private const val DEFAULT_RENDERER_NAME = "UFM Player"
    const val DEFAULT_SERVER_PORT = 8200

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Server enabled ---

    fun isDlnaServerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DLNA_SERVER_ENABLED, false)
    }

    fun setDlnaServerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DLNA_SERVER_ENABLED, enabled).apply()
    }

    // --- Renderer enabled ---

    fun isDlnaRendererEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DLNA_RENDERER_ENABLED, false)
    }

    fun setDlnaRendererEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DLNA_RENDERER_ENABLED, enabled).apply()
    }

    // --- Server name ---

    fun getDlnaServerName(context: Context): String {
        return getPrefs(context).getString(KEY_DLNA_SERVER_NAME, DEFAULT_SERVER_NAME) ?: DEFAULT_SERVER_NAME
    }

    fun setDlnaServerName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_DLNA_SERVER_NAME, name).apply()
    }

    // --- Renderer name ---

    fun getDlnaRendererName(context: Context): String {
        return getPrefs(context).getString(KEY_DLNA_RENDERER_NAME, DEFAULT_RENDERER_NAME) ?: DEFAULT_RENDERER_NAME
    }

    fun setDlnaRendererName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_DLNA_RENDERER_NAME, name).apply()
    }

    // --- Server port ---

    fun getDlnaServerPort(context: Context): Int {
        return getPrefs(context).getInt(KEY_DLNA_SERVER_PORT, DEFAULT_SERVER_PORT)
    }

    fun setDlnaServerPort(context: Context, port: Int) {
        getPrefs(context).edit().putInt(KEY_DLNA_SERVER_PORT, port).apply()
    }

    // --- Shared folders ---

    fun getSharedFolders(context: Context): List<SharedFolder> {
        val json = getPrefs(context).getString(KEY_DLNA_SHARED_FOLDERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SharedFolder(
                    uri = obj.getString("uri"),
                    label = obj.getString("label")
                )
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to parse shared folders JSON", e)
            emptyList()
        }
    }

    fun setSharedFolders(context: Context, folders: List<SharedFolder>) {
        val arr = JSONArray()
        for (f in folders) {
            val obj = JSONObject()
            obj.put("uri", f.uri)
            obj.put("label", f.label)
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_DLNA_SHARED_FOLDERS, arr.toString()).apply()
    }

    // --- Validation ---

    /**
     * Validates that folder URIs don't point to sensitive system paths.
     * Rejects: "/", "/system", "/proc", "/sys", "/dev", "/data/data", "/root"
     */
    fun validateFolderUri(uri: String): Boolean {
        if (uri.isBlank()) return false
        val lower = uri.lowercase().trimEnd('/')
        val dangerous = listOf(
            "/", "/system", "/proc", "/sys", "/dev", "/root",
            "/data/data", "/data/user", "/storage/emulated"
        )
        // For file:// URIs, extract the path portion
        val path = if (lower.startsWith("file://")) {
            lower.removePrefix("file://").substringBefore("?")
        } else {
            lower.substringBefore("?")
        }
        val normalizedPath = if (path.isEmpty()) "/" else path
        return dangerous.none { normalizedPath == it || normalizedPath.startsWith("$it/") }
    }

    /**
     * Data class representing a configured shared folder.
     */
    data class SharedFolder(
        val uri: String,    // e.g. "file:///sdcard/Movies" or "smb://shareId/movies"
        val label: String   // e.g. "Movies"
    )
}
