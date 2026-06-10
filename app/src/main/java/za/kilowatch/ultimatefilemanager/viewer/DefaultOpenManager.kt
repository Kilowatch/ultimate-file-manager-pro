package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user preferences for default file opening actions.
 * Distinguishes between local and network defaults to allow different settings
 * (e.g., UFM Player for cloud media, but External App for local media).
 */
object DefaultOpenManager {
    private const val PREFS_NAME = "ufm_default_apps"
    
    enum class Action {
        ASK,         // Show choice dialog
        INTERNAL,    // UFM built-in viewer
        EXTERNAL,    // Choose external app (System Intent)
        PLAYER,      // UFM Player (Special case for cloud media streaming)
        SLIDESHOW    // UFM Slide Show
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getPrefKey(extension: String, isNetwork: Boolean): String {
        val suffix = if (isNetwork) "_net" else "_local"
        return extension.lowercase() + suffix
    }

    private fun getPkgKey(extension: String, isNetwork: Boolean): String {
        val suffix = if (isNetwork) "_net" else "_local"
        return extension.lowercase() + suffix + "_pkg"
    }

    fun getDefaultAction(context: Context, extension: String, isNetwork: Boolean): Action {
        val key = getPrefKey(extension, isNetwork)
        val name = getPrefs(context).getString(key, Action.ASK.name)
        return try {
            Action.valueOf(name ?: Action.ASK.name)
        } catch (e: Exception) {
            Action.ASK
        }
    }

    fun setDefaultAction(context: Context, extension: String, isNetwork: Boolean, action: Action) {
        val key = getPrefKey(extension, isNetwork)
        getPrefs(context).edit().putString(key, action.name).apply()
    }

    fun clearDefaultAction(context: Context, extension: String, isNetwork: Boolean) {
        val key    = getPrefKey(extension, isNetwork)
        val pkgKey = getPkgKey(extension, isNetwork)
        getPrefs(context).edit().remove(key).remove(pkgKey).apply()
    }

    fun clearAllDefaults(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    // ── Preferred external-app package ──────────────────────────────────────

    /**
     * Returns the package name of the external app the user last chose for
     * [extension] in the given context (local vs network), or null if none saved.
     */
    fun getPreferredPackage(context: Context, extension: String, isNetwork: Boolean): String? =
        getPrefs(context).getString(getPkgKey(extension, isNetwork), null)

    /**
     * Persists the preferred external app package for [extension].
     */
    fun setPreferredPackage(context: Context, extension: String, isNetwork: Boolean, packageName: String) {
        getPrefs(context).edit().putString(getPkgKey(extension, isNetwork), packageName).apply()
    }

    /**
     * Clears only the preferred package (leaves the Action pref intact).
     */
    fun clearPreferredPackage(context: Context, extension: String, isNetwork: Boolean) {
        getPrefs(context).edit().remove(getPkgKey(extension, isNetwork)).apply()
    }

    /**
     * Data class for representing a default entry in the settings UI.
     */
    data class DefaultEntry(
        val extension: String,
        val isNetwork: Boolean,
        val action: Action
    )

    fun getAllDefaults(context: Context): List<DefaultEntry> {
        val all = getPrefs(context).all
        val entries = mutableListOf<DefaultEntry>()
        
        for ((key, value) in all) {
            if (value !is String) continue
            
            val isNetwork = key.endsWith("_net")
            val extension = if (isNetwork) key.removeSuffix("_net") else key.removeSuffix("_local")
            val action = try { Action.valueOf(value) } catch (e: Exception) { Action.ASK }
            
            if (action != Action.ASK) {
                entries.add(DefaultEntry(extension, isNetwork, action))
            }
        }
        return entries.sortedWith(compareBy({ it.extension }, { !it.isNetwork }))
    }
}
