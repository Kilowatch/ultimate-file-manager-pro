package za.kilowatch.ultimatefilemanager.billing

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * SharedPreferences wrapper for all auto-backup settings and password storage.
 *
 * Settings are stored in plain SharedPreferences (`auto_backup_prefs`).
 * The backup password is stored in EncryptedSharedPreferences (`auto_backup_secure`)
 * backed by the Android Keystore (AES256-GCM), so it is not readable even with root.
 */
object AutoBackupPrefs {
    private const val TAG = "AutoBackupPrefs"
    private const val PREFS_NAME = "auto_backup_prefs"
    private const val SECURE_PREFS_NAME = "auto_backup_secure"

    // ── Plain SharedPreferences keys ──────────────────────────────────────────

    private const val KEY_ENABLED = "auto_backup_enabled"
    private const val KEY_BACKUP_SETTINGS = "auto_backup_settings"
    private const val KEY_BACKUP_THEME = "auto_backup_theme"
    private const val KEY_SCHEDULE = "auto_backup_schedule"
    private const val KEY_USE_PASSWORD = "auto_backup_use_password"
    private const val KEY_SETTINGS_DIRTY = "is_settings_dirty"
    private const val KEY_THEME_DIRTY = "is_theme_dirty"

    // ── Encrypted SharedPreferences key ──────────────────────────────────────

    private const val KEY_ENCRYPTED_PASSWORD = "backup_password"

    // ── First-boot / restore keys (stored in main prefs) ─────────────────────

    private const val KEY_FILES_ON_FIRST_BOOT = "backup_files_present_on_first_boot"
    private const val KEY_RESTORE_PROMPT_SHOWN = "auto_restore_prompt_shown"

    // ── Custom location keys ─────────────────────────────────────────────────

    private const val KEY_CUSTOM_LOCATION_TYPE = "custom_location_type"
    private const val KEY_CUSTOM_LOCAL_PATH = "custom_local_path"
    private const val KEY_CUSTOM_SHARE_ID = "custom_share_id"
    private const val KEY_CUSTOM_NET_PATH = "custom_net_path"

    // ── Prefs instances (lazily initialised) ──────────────────────────────────

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var securePrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return prefs ?: synchronized(this) {
            prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
        }
    }

    private fun getSecurePrefs(context: Context): SharedPreferences? {
        if (securePrefs != null) return securePrefs
        return synchronized(this) {
            if (securePrefs != null) return@synchronized securePrefs
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                val encrypted = EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                securePrefs = encrypted
                encrypted
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialise EncryptedSharedPreferences — password will not be persisted", e)
                null
            }
        }
    }

    // ── Configuration getters/setters ─────────────────────────────────────────

    fun isEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isBackupSettings(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_BACKUP_SETTINGS, true)

    fun setBackupSettings(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BACKUP_SETTINGS, enabled).apply()
    }

    fun isBackupTheme(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_BACKUP_THEME, true)

    fun setBackupTheme(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BACKUP_THEME, enabled).apply()
    }

    fun getScheduleType(context: Context): String =
        getPrefs(context).getString(KEY_SCHEDULE, "weekly") ?: "weekly"

    fun setScheduleType(context: Context, type: String) {
        getPrefs(context).edit().putString(KEY_SCHEDULE, type).apply()
    }

    fun isUsePassword(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_USE_PASSWORD, false)

    fun setUsePassword(context: Context, usePassword: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USE_PASSWORD, usePassword).apply()
    }

    // ── Password (EncryptedSharedPreferences) ─────────────────────────────────
    // Must only be called from Dispatchers.IO — see SecureTokenStore KDoc.

    fun getPassword(context: Context): String? {
        val sp = getSecurePrefs(context) ?: return null
        return sp.getString(KEY_ENCRYPTED_PASSWORD, null)
    }

    fun setPassword(context: Context, password: String?) {
        val sp = getSecurePrefs(context) ?: return
        sp.edit().putString(KEY_ENCRYPTED_PASSWORD, password).commit() // NOT apply() — see SecureTokenStore KDoc
    }

    // ── Dirty flags ───────────────────────────────────────────────────────────

    fun isSettingsDirty(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SETTINGS_DIRTY, false)

    fun setSettingsDirty(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_SETTINGS_DIRTY, true).apply()
    }

    fun clearSettingsDirty(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_SETTINGS_DIRTY, false).apply()
    }

    fun isThemeDirty(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_THEME_DIRTY, false)

    fun setThemeDirty(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_THEME_DIRTY, true).apply()
    }

    fun clearThemeDirty(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_THEME_DIRTY, false).apply()
    }

    // ── First-boot / restore flags ────────────────────────────────────────────

    fun isBackupFilesPresentOnFirstBoot(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_FILES_ON_FIRST_BOOT, false)

    fun setBackupFilesPresentOnFirstBoot(context: Context, present: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FILES_ON_FIRST_BOOT, present).commit()
    }

    fun isRestorePromptShown(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_RESTORE_PROMPT_SHOWN, false)

    fun setRestorePromptShown(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_RESTORE_PROMPT_SHOWN, true).commit()
    }

    // ── Custom location getters/setters ───────────────────────────────────────

    fun getCustomLocationType(context: Context): String =
        getPrefs(context).getString(KEY_CUSTOM_LOCATION_TYPE, "") ?: ""

    fun setCustomLocationType(context: Context, type: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_LOCATION_TYPE, type).apply()
    }

    fun getCustomLocalPath(context: Context): String =
        getPrefs(context).getString(KEY_CUSTOM_LOCAL_PATH, "") ?: ""

    fun setCustomLocalPath(context: Context, path: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_LOCAL_PATH, path).apply()
    }

    fun getCustomShareId(context: Context): String =
        getPrefs(context).getString(KEY_CUSTOM_SHARE_ID, "") ?: ""

    fun setCustomShareId(context: Context, shareId: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_SHARE_ID, shareId).apply()
    }

    fun getCustomNetPath(context: Context): String =
        getPrefs(context).getString(KEY_CUSTOM_NET_PATH, "") ?: ""

    fun setCustomNetPath(context: Context, netPath: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_NET_PATH, netPath).apply()
    }

    fun isCustomLocationSet(context: Context): Boolean {
        val type = getCustomLocationType(context)
        return when (type) {
            "local" -> getCustomLocalPath(context).isNotEmpty()
            "network" -> getCustomShareId(context).isNotEmpty()
            else -> false
        }
    }

    fun clearCustomLocation(context: Context) {
        getPrefs(context).edit()
            .putString(KEY_CUSTOM_LOCATION_TYPE, "")
            .putString(KEY_CUSTOM_LOCAL_PATH, "")
            .putString(KEY_CUSTOM_SHARE_ID, "")
            .putString(KEY_CUSTOM_NET_PATH, "")
            .apply()
    }

    /**
     * Returns the human-readable path for display in the settings UI.
     * For local: the absolute path. For network: "share_name: remote_path".
     */
    fun getBackupDirectoryDisplayPath(context: Context): String {
        return when (getCustomLocationType(context)) {
            "local" -> {
                val path = getCustomLocalPath(context)
                if (path.isNotEmpty()) path else "Documents/UFM/"
            }
            "network" -> {
                val shareId = getCustomShareId(context)
                val netPath = getCustomNetPath(context)
                if (shareId.isNotEmpty()) {
                    val shareName = try {
                        val repo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(context)
                        val share = repo.getById(shareId)
                        share?.name ?: shareId
                    } catch (e: Exception) {
                        shareId
                    }
                    "$shareName: $netPath"
                } else {
                    "Documents/UFM/"
                }
            }
            else -> "Documents/UFM/"
        }
    }

    /**
     * Checks whether the custom location is currently available.
     * For local: checks directory existence. For network: always returns true
     * (actual reachability is checked at backup time by the worker).
     */
    fun isCustomLocationAvailable(context: Context): Boolean {
        return when (getCustomLocationType(context)) {
            "local" -> {
                val f = File(getCustomLocalPath(context))
                f.exists() || f.mkdirs()
            }
            "network" -> true // checked at write time
            else -> true
        }
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

    /**
     * Returns the effective backup directory.
     * - If a local custom path is set and exists, returns that.
     * - For network custom paths, or if the local path doesn't exist,
     *   returns the default Documents/UFM/ as a fallback.
     */
    fun getBackupDirectory(context: Context): File {
        val type = getCustomLocationType(context)
        if (type == "local") {
            val customPath = getCustomLocalPath(context)
            if (customPath.isNotEmpty()) {
                val dir = File(customPath)
                if (dir.exists() || dir.mkdirs()) {
                    return dir
                }
                android.util.Log.w(TAG, "Custom local path $customPath does not exist — falling back to default")
            }
        }
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "UFM"
        )
    }

    fun getConfigFile(context: Context): File {
        return File(getBackupDirectory(context), "ufm_backup.UFMConfig")
    }

    fun getThemeFile(context: Context): File {
        return File(getBackupDirectory(context), "ufm_icons_theme.UFMTheme")
    }

    /**
     * Summary string for the status display.
     * Returns something like "ON — Settings and Theme — Weekly — Password protected"
     */
    fun getSummary(context: Context): String {
        val parts = mutableListOf<String>()
        val items = mutableListOf<String>()
        if (isBackupSettings(context)) items.add("Settings")
        if (isBackupTheme(context)) items.add("Theme")
        parts.add(if (items.isEmpty()) "Nothing selected" else items.joinToString(" and "))
        parts.add(getScheduleType(context).replaceFirstChar { it.uppercase() })
        parts.add(if (isUsePassword(context)) "Password protected" else "No password")
        return "ON — ${parts.joinToString(" — ")}"
    }
}
