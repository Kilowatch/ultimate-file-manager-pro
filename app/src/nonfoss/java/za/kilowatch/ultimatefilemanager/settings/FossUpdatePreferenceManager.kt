package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * No-op stub for Google Play / Amazon builds.
 * The real implementation lives in src/foss/ only.
 */
object FossUpdatePreferenceManager {
    fun isAutoCheckEnabled(context: Context) = false
    fun setAutoCheckEnabled(context: Context, enabled: Boolean) = Unit
    fun getLastCheckTime(context: Context) = 0L
    fun setLastCheckTime(context: Context, time: Long) = Unit
    fun getLastNotifiedVersion(context: Context) = ""
    fun setLastNotifiedVersion(context: Context, version: String) = Unit
    fun getPendingUpdateVersion(context: Context): String? = null
    fun getPendingUpdatePath(context: Context): String? = null
    fun setPendingUpdate(context: Context, version: String, path: String) = Unit
    fun clearPendingUpdate(context: Context) = Unit
    fun isVersionDismissed(context: Context, version: String) = false
    fun dismissVersion(context: Context, version: String) = Unit
}
