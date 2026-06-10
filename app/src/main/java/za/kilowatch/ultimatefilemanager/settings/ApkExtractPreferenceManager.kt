package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object ApkExtractPreferenceManager {

    private const val PREFS_NAME = "apk_extract_prefs"
    private const val KEY_ENABLED = "apk_extract_enabled"
    private const val KEY_EXTRACT_ICON = "apk_extract_icon"
    private const val KEY_SELECTED_FIELDS = "apk_extract_fields"

    private const val DEFAULT_FIELDS = "package_name,version_name,version_code,label,extracted_date,install_time,last_update_time,target_sdk,min_sdk,has_obb,split_apks,source_dir,app_size,permissions"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isExtractIcon(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXTRACT_ICON, true)
    }

    fun setExtractIcon(context: Context, extract: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EXTRACT_ICON, extract)
            .apply()
    }

    fun getSelectedFields(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_FIELDS, DEFAULT_FIELDS) ?: DEFAULT_FIELDS
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setSelectedFields(context: Context, fields: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_FIELDS, fields.joinToString(","))
            .apply()
    }

    fun isFieldSelected(context: Context, field: String): Boolean {
        return field in getSelectedFields(context)
    }

    fun toggleField(context: Context, field: String) {
        val current = getSelectedFields(context).toMutableSet()
        if (field in current) current.remove(field) else current.add(field)
        setSelectedFields(context, current)
    }
}
