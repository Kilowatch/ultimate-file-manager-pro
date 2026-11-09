package za.kilowatch.ultimatefilemanager.download

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Manages user preferences for the Download Manager:
 * - Download destination directory
 * - Network policy (Wi-Fi only vs all networks)
 * - Legal & Non-Piracy acceptance state
 */
object DownloadPreferencesManager {

    private const val PREFS_NAME = "ufm_download_prefs"

    private const val KEY_DOWNLOAD_DIR = "key_download_dir"
    private const val KEY_NETWORK_POLICY = "key_network_policy"
    private const val KEY_LEGAL_ACCEPTED_TIME = "key_legal_accepted_time"

    enum class NetworkPolicy {
        WIFI_ONLY,
        ALL_NETWORKS
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the configured download target directory.
     * Defaults to the standard public Downloads folder (/storage/emulated/0/Download).
     */
    fun getDownloadDir(context: Context): File {
        val saved = prefs(context).getString(KEY_DOWNLOAD_DIR, null)
        if (!saved.isNullOrBlank()) {
            val file = File(saved)
            if (file.exists() && file.isDirectory && file.canWrite()) {
                return file
            }
        }

        val defaultDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!defaultDir.exists()) {
            defaultDir.mkdirs()
        }
        return defaultDir
    }

    fun setDownloadDir(context: Context, path: String) {
        prefs(context).edit().putString(KEY_DOWNLOAD_DIR, path).apply()
    }

    /**
     * Gets the current network policy. Defaults to [NetworkPolicy.WIFI_ONLY].
     */
    fun getNetworkPolicy(context: Context): NetworkPolicy {
        val raw = prefs(context).getString(KEY_NETWORK_POLICY, NetworkPolicy.WIFI_ONLY.name)
        return try {
            NetworkPolicy.valueOf(raw ?: NetworkPolicy.WIFI_ONLY.name)
        } catch (_: Exception) {
            NetworkPolicy.WIFI_ONLY
        }
    }

    fun setNetworkPolicy(context: Context, policy: NetworkPolicy) {
        prefs(context).edit().putString(KEY_NETWORK_POLICY, policy.name).apply()
    }

    fun isWifiOnly(context: Context): Boolean =
        getNetworkPolicy(context) == NetworkPolicy.WIFI_ONLY

    /**
     * Returns true if the user has completed the mandatory first-use Legal Gate.
     */
    fun isLegalAccepted(context: Context): Boolean {
        return prefs(context).getLong(KEY_LEGAL_ACCEPTED_TIME, 0L) > 0L
    }

    /**
     * Records acceptance timestamp for the Legal Gate.
     */
    fun setLegalAccepted(context: Context, accepted: Boolean = true) {
        val time = if (accepted) System.currentTimeMillis() else 0L
        prefs(context).edit().putLong(KEY_LEGAL_ACCEPTED_TIME, time).apply()
    }
}
