package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R

/**
 * Manages preferences for hardware / Bluetooth keyboard shortcuts, Vim navigation mode,
 * dual-pane fast switching, and customizable keybind mappings.
 */
object KeyboardPreferenceManager {

    const val PREFS_NAME = "ufm_keyboard_shortcuts_prefs"

    private const val KEY_MASTER_ENABLED = "keyboard_master_enabled"
    private const val KEY_VIM_MODE_ENABLED = "keyboard_vim_mode_enabled"
    private const val KEY_DUAL_PANE_SWITCH_ENABLED = "keyboard_dual_pane_switch_enabled"
    private const val KEY_CUSTOM_BINDINGS_JSON = "keyboard_custom_bindings_json"

    // Action Identifiers
    const val ACTION_MOVE_DOWN = "action_move_down"
    const val ACTION_MOVE_UP = "action_move_up"
    const val ACTION_PARENT_DIR = "action_parent_dir"
    const val ACTION_OPEN = "action_open"
    const val ACTION_JUMP_TOP = "action_jump_top"
    const val ACTION_JUMP_BOTTOM = "action_jump_bottom"
    const val ACTION_GO_TO = "action_go_to"
    const val ACTION_TOGGLE_SELECT = "action_toggle_select"
    const val ACTION_SELECT_ALL = "action_select_all"
    const val ACTION_COPY = "action_copy"
    const val ACTION_CUT = "action_cut"
    const val ACTION_PASTE = "action_paste"
    const val ACTION_DELETE = "action_delete"
    const val ACTION_RENAME = "action_rename"
    const val ACTION_NEW_FOLDER = "action_new_folder"
    const val ACTION_SEARCH = "action_search"
    const val ACTION_TOGGLE_HIDDEN = "action_toggle_hidden"
    const val ACTION_REFRESH = "action_refresh"
    const val ACTION_SWITCH_PANE = "action_switch_pane"
    const val ACTION_FOCUS_PANE_1 = "action_focus_pane_1"
    const val ACTION_FOCUS_PANE_2 = "action_focus_pane_2"
    const val ACTION_CHEATSHEET = "action_cheatsheet"

    data class KeyBinding(
        val actionId: String,
        val titleResId: Int,
        val descResId: Int,
        val categoryResId: Int,
        val defaultDisplayKey: String,
        val isVimOnly: Boolean = false
    )

    val ALL_BINDINGS = listOf(
        // Navigation
        KeyBinding(ACTION_MOVE_DOWN, R.string.keyboard_action_move_down, R.string.keyboard_action_move_down_desc, R.string.keyboard_shortcuts_section_nav, "j / ↓"),
        KeyBinding(ACTION_MOVE_UP, R.string.keyboard_action_move_up, R.string.keyboard_action_move_up_desc, R.string.keyboard_shortcuts_section_nav, "k / ↑"),
        KeyBinding(ACTION_PARENT_DIR, R.string.keyboard_action_parent_dir, R.string.keyboard_action_parent_dir_desc, R.string.keyboard_shortcuts_section_nav, "h / ← / Backspace"),
        KeyBinding(ACTION_OPEN, R.string.keyboard_action_open, R.string.keyboard_action_open_desc, R.string.keyboard_shortcuts_section_nav, "l / → / Enter"),
        KeyBinding(ACTION_JUMP_TOP, R.string.keyboard_action_jump_top, R.string.keyboard_action_jump_top_desc, R.string.keyboard_shortcuts_section_nav, "gg / Home"),
        KeyBinding(ACTION_JUMP_BOTTOM, R.string.keyboard_action_jump_bottom, R.string.keyboard_action_jump_bottom_desc, R.string.keyboard_shortcuts_section_nav, "G / End"),
        KeyBinding(ACTION_GO_TO, R.string.keyboard_action_go_to, R.string.keyboard_action_go_to_desc, R.string.keyboard_shortcuts_section_nav, "g / Ctrl+G"),

        // Selection
        KeyBinding(ACTION_TOGGLE_SELECT, R.string.keyboard_action_toggle_select, R.string.keyboard_action_toggle_select_desc, R.string.keyboard_shortcuts_section_selection, "Space / v"),
        KeyBinding(ACTION_SELECT_ALL, R.string.keyboard_action_select_all, R.string.keyboard_action_select_all_desc, R.string.keyboard_shortcuts_section_selection, "a / Ctrl+A"),

        // File Operations
        KeyBinding(ACTION_COPY, R.string.keyboard_action_copy, R.string.keyboard_action_copy_desc, R.string.keyboard_shortcuts_section_file_ops, "y / Ctrl+C"),
        KeyBinding(ACTION_CUT, R.string.keyboard_action_cut, R.string.keyboard_action_cut_desc, R.string.keyboard_shortcuts_section_file_ops, "x / Ctrl+X"),
        KeyBinding(ACTION_PASTE, R.string.keyboard_action_paste, R.string.keyboard_action_paste_desc, R.string.keyboard_shortcuts_section_file_ops, "p / Ctrl+V"),
        KeyBinding(ACTION_DELETE, R.string.keyboard_action_delete, R.string.keyboard_action_delete_desc, R.string.keyboard_shortcuts_section_file_ops, "d / Delete"),
        KeyBinding(ACTION_RENAME, R.string.keyboard_action_rename, R.string.keyboard_action_rename_desc, R.string.keyboard_shortcuts_section_file_ops, "r / F2"),
        KeyBinding(ACTION_NEW_FOLDER, R.string.keyboard_action_new_folder, R.string.keyboard_action_new_folder_desc, R.string.keyboard_shortcuts_section_file_ops, "n / Ctrl+Shift+N"),

        // Dual Pane
        KeyBinding(ACTION_SWITCH_PANE, R.string.keyboard_action_switch_pane, R.string.keyboard_action_switch_pane_desc, R.string.keyboard_shortcuts_section_dual_pane, "Tab / Ctrl+W"),
        KeyBinding(ACTION_FOCUS_PANE_1, R.string.keyboard_action_focus_pane_1, R.string.keyboard_action_focus_pane_1_desc, R.string.keyboard_shortcuts_section_dual_pane, "1"),
        KeyBinding(ACTION_FOCUS_PANE_2, R.string.keyboard_action_focus_pane_2, R.string.keyboard_action_focus_pane_2_desc, R.string.keyboard_shortcuts_section_dual_pane, "2"),

        // General
        KeyBinding(ACTION_SEARCH, R.string.keyboard_action_search, R.string.keyboard_action_search_desc, R.string.keyboard_shortcuts_section_general, "/ / Ctrl+F"),
        KeyBinding(ACTION_TOGGLE_HIDDEN, R.string.keyboard_action_toggle_hidden, R.string.keyboard_action_toggle_hidden_desc, R.string.keyboard_shortcuts_section_general, ". / Ctrl+H"),
        KeyBinding(ACTION_REFRESH, R.string.keyboard_action_refresh, R.string.keyboard_action_refresh_desc, R.string.keyboard_shortcuts_section_general, "F5 / Ctrl+R"),
        KeyBinding(ACTION_CHEATSHEET, R.string.keyboard_action_cheatsheet, R.string.keyboard_action_cheatsheet_desc, R.string.keyboard_shortcuts_section_general, "? / F1")
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isMasterEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MASTER_ENABLED, true)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun isVimModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VIM_MODE_ENABLED, true)
    }

    fun setVimModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VIM_MODE_ENABLED, enabled).apply()
    }

    fun isDualPaneSwitchEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DUAL_PANE_SWITCH_ENABLED, true)
    }

    fun setDualPaneSwitchEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DUAL_PANE_SWITCH_ENABLED, enabled).apply()
    }

    fun getCustomBindingDisplay(context: Context, actionId: String): String {
        val jsonStr = getPrefs(context).getString(KEY_CUSTOM_BINDINGS_JSON, null) ?: return getDefaultDisplay(actionId)
        return try {
            val json = JSONObject(jsonStr)
            json.optString(actionId).ifEmpty { getDefaultDisplay(actionId) }
        } catch (_: Exception) {
            getDefaultDisplay(actionId)
        }
    }

    fun setCustomBindingDisplay(context: Context, actionId: String, displayKey: String) {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_CUSTOM_BINDINGS_JSON, null)
        val json = try {
            if (jsonStr != null) JSONObject(jsonStr) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        json.put(actionId, displayKey)
        prefs.edit().putString(KEY_CUSTOM_BINDINGS_JSON, json.toString()).apply()
    }

    fun resetToDefaults(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_MASTER_ENABLED, true)
            .putBoolean(KEY_VIM_MODE_ENABLED, true)
            .putBoolean(KEY_DUAL_PANE_SWITCH_ENABLED, true)
            .remove(KEY_CUSTOM_BINDINGS_JSON)
            .apply()
    }

    private fun getDefaultDisplay(actionId: String): String {
        return ALL_BINDINGS.firstOrNull { it.actionId == actionId }?.defaultDisplayKey ?: ""
    }
}
