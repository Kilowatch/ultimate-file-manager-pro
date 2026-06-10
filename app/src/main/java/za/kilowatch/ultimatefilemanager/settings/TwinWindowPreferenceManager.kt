package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the Twin Window layout preference on mobile devices.
 * 
 * Default: false (horizontal split)
 * Enabled: true (vertical split)
 */
object TwinWindowPreferenceManager {

    private const val PREFS_NAME = "twin_window_prefs"
    private const val KEY_VERTICAL_SPLIT = "is_vertical_split"

    /** Returns true when the user has enabled the vertical layout for Twin Window. Defaults to vertical on TV. */
    fun isVerticalSplit(context: Context): Boolean {
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(context)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VERTICAL_SPLIT, isTv) // default horizontal on mobile, vertical on TV
    }

    /** Persists the vertical split state. */
    fun setVerticalSplit(context: Context, isVertical: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VERTICAL_SPLIT, isVertical)
            .apply()
    }

    private const val KEY_DEFAULT_STARTUP = "is_default_startup"

    /** Returns true when the user has enabled Twin Window as the default startup screen. */
    fun isDefaultStartup(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEFAULT_STARTUP, false)
    }

    /** Persists the default startup state. */
    fun setDefaultStartup(context: Context, isDefault: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEFAULT_STARTUP, isDefault)
            .apply()
    }

    // ── Pane 2 remembered storage ─────────────────────────────────────────────

    private const val KEY_PANE2_TYPE        = "pane2_type"
    private const val KEY_PANE2_LOCAL_PATH   = "pane2_local_path"
    private const val KEY_PANE2_LOCAL_LABEL  = "pane2_local_label"
    private const val KEY_PANE2_SHARE_ID     = "pane2_share_id"
    private const val KEY_PANE2_INITIAL_PATH = "pane2_initial_path"

    /**
     * Persists the user's pane 2 storage choice so Twin Window can restore it on next launch.
     *
     * @param type      One of "local", "network", or "apps".
     * @param path      Mount-root path for local storage (e.g. "/storage/xxxx-xxxx" for SD card).
     * @param label     Human-readable label shown in the pane header.
     * @param shareId   Network share ID when type == "network".
     */
    fun savePane2Selection(
        context: Context,
        type: String,
        path: String? = null,
        label: String? = null,
        shareId: String? = null
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PANE2_TYPE, type)
            .putString(KEY_PANE2_LOCAL_PATH, path)
            .putString(KEY_PANE2_LOCAL_LABEL, label)
            .putString(KEY_PANE2_SHARE_ID, shareId)
            .apply()
    }

    /** Returns the remembered pane 2 type ("local", "network", "apps"). Defaults to "local". */
    fun getPane2Type(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PANE2_TYPE, "local") ?: "local"

    /** Returns the remembered pane 2 local mount path, or null if none saved. */
    fun getPane2LocalPath(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PANE2_LOCAL_PATH, null)

    /** Returns the remembered pane 2 local label, or null if none saved. */
    fun getPane2LocalLabel(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PANE2_LOCAL_LABEL, null)

    /** Returns the remembered pane 2 network share ID, or null if none saved. */
    fun getPane2ShareId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PANE2_SHARE_ID, null)

    /**
     * Returns the remembered pane 2 subfolder path (relative to the drive root for network,
     * or absolute for local storage), or null if none saved.
     */
    fun getPane2InitialPath(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PANE2_INITIAL_PATH, null)

    /** Persists the current subfolder shown in pane 2 so it can be restored on next launch. */
    fun savePane2InitialPath(context: Context, path: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PANE2_INITIAL_PATH, path)
            .apply()
    }

    /** Clears the remembered pane 2 selection (resets to default Internal Storage on next launch). */
    fun clearPane2Selection(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PANE2_TYPE)
            .remove(KEY_PANE2_LOCAL_PATH)
            .remove(KEY_PANE2_LOCAL_LABEL)
            .remove(KEY_PANE2_SHARE_ID)
            .remove(KEY_PANE2_INITIAL_PATH)
            .apply()
    }
}
