package za.kilowatch.ultimatefilemanager.recycle

import android.content.Context

object RecycleBinSettingsManager {
    private const val PREFS_NAME = "recycle_bin_prefs"
    private const val KEY_ENABLED = "recycle_bin_enabled"
    private const val KEY_AUTO_DELETE_DAYS = "recycle_bin_auto_delete_days"
    const val DEFAULT_AUTO_DELETE_DAYS = 30

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

    fun getAutoDeleteDays(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_AUTO_DELETE_DAYS, 30)
    }

    fun setAutoDeleteDays(context: Context, days: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_AUTO_DELETE_DAYS, days)
            .apply()
    }
}
