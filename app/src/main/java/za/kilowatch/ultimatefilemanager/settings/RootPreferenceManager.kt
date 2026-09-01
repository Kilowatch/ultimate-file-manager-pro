package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Manages root access preferences, shell authorization state, and lifecycle.
 *
 * SEC-POLICY:
 * 1. Root access features are strictly isolated to Mobile devices (!isTvDevice).
 * 2. Active su shell requests are never executed automatically at app startup;
 *    they only occur upon explicit user interaction (e.g. enabling root toggle in Settings).
 * 3. Preference flags are stored in private SharedPreferences (MODE_PRIVATE).
 */
object RootPreferenceManager {

    const val PREFS_NAME = "root_preferences"
    private const val KEY_ROOT_ENABLED = "root_access_enabled"
    private const val KEY_ROOT_LAST_GRANTED = "root_last_granted_time"

    /**
     * Checks whether Root Access is enabled by the user.
     * Always returns false on TV devices.
     */
    fun isRootEnabled(context: Context): Boolean {
        if (DeviceUtils.isTvDevice(context)) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ROOT_ENABLED, false)
    }

    /**
     * Updates the Root Access enabled state in private SharedPreferences.
     */
    fun setRootEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ROOT_ENABLED, enabled)
            .apply()

        if (!enabled) {
            closeShell()
        }
    }

    /**
     * Gets the timestamp when root access was last successfully granted.
     */
    fun getLastGrantedTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_ROOT_LAST_GRANTED, 0L)
    }

    /**
     * Updates the timestamp of the last successful root grant.
     */
    fun setLastGrantedTime(context: Context, timestamp: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ROOT_LAST_GRANTED, timestamp)
            .apply()
    }

    /**
     * Requests root access asynchronously via libsu.
     * Invokes [callback] on Dispatchers.Main with the result.
     */
    fun requestRootAccess(context: Context, callback: (granted: Boolean, message: String?) -> Unit) {
        if (DeviceUtils.isTvDevice(context)) {
            callback(false, "Root access is not supported on TV devices.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Shell.getShell() connects to su daemon and prompts user if not already granted
                val shell = Shell.getShell()
                val isRoot = shell.isRoot

                if (isRoot) {
                    setLastGrantedTime(context, System.currentTimeMillis())
                    withContext(Dispatchers.Main) {
                        callback(true, null)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        callback(false, "Superuser permission was denied or unavailable.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(false, e.localizedMessage ?: "Failed to establish root shell.")
                }
            }
        }
    }

    /**
     * Gracefully closes any cached active root shell.
     */
    fun closeShell() {
        try {
            Shell.getCachedShell()?.close()
        } catch (_: Exception) {
        }
    }
}
