package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the user's selected destination folder for UFM Viewer and Slide Show
 * rapid Copy and Move triage operations.
 *
 * Registered in [za.kilowatch.ultimatefilemanager.settings.SettingsBackupManager]
 * for seamless configuration backup and restore.
 */
object ViewerTransferPreferenceManager {

    const val PREFS_NAME = "viewer_transfer_prefs"

    private const val KEY_IS_NETWORK = "is_network"
    private const val KEY_LOCAL_PATH = "local_path"
    private const val KEY_SHARE_ID = "share_id"
    private const val KEY_NET_PATH = "net_path"
    private const val KEY_DISPLAY_NAME = "display_name"

    data class ViewerDestination(
        val isNetwork: Boolean,
        val localPath: String? = null,
        val shareId: String? = null,
        val netPath: String? = null,
        val displayName: String
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDestination(context: Context): ViewerDestination? {
        val prefs = getPrefs(context)
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null) ?: return null
        val isNetwork = prefs.getBoolean(KEY_IS_NETWORK, false)
        val localPath = prefs.getString(KEY_LOCAL_PATH, null)
        val shareId = prefs.getString(KEY_SHARE_ID, null)
        val netPath = prefs.getString(KEY_NET_PATH, null)

        if (isNetwork && shareId.isNullOrEmpty()) return null
        if (!isNetwork && localPath.isNullOrEmpty()) return null

        return ViewerDestination(
            isNetwork = isNetwork,
            localPath = localPath,
            shareId = shareId,
            netPath = netPath,
            displayName = displayName
        )
    }

    fun setDestination(context: Context, destination: ViewerDestination) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_NETWORK, destination.isNetwork)
            .putString(KEY_LOCAL_PATH, destination.localPath)
            .putString(KEY_SHARE_ID, destination.shareId)
            .putString(KEY_NET_PATH, destination.netPath)
            .putString(KEY_DISPLAY_NAME, destination.displayName)
            .apply()
    }

    fun clearDestination(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
