package za.kilowatch.ultimatefilemanager.billing

import android.content.Context
import android.content.SharedPreferences

object LoyaltyPrefs {
    private const val PREFS_NAME = "loyalty_prefs"

    // Local lifetime tip total
    private const val KEY_TOTAL_TIPPED = "total_tipped"

    // Whether the tip jar progress popup is enabled (default true)
    private const val KEY_TIP_JAR_POPUP_ENABLED = "tip_jar_popup_enabled"

    // Whether the device has ever been online (persistent flag, never reset)
    private const val KEY_HAS_EVER_BEEN_ONLINE = "has_ever_been_online"

    // Server-fetched monthly progress cache
    private const val KEY_CACHED_PERCENT = "cached_percent"
    private const val KEY_CACHED_MONTH = "cached_month"
    private const val KEY_CACHED_TIMESTAMP = "cached_timestamp"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ─── Local tip total ──────────────────────────────────────

    fun getTotalTipped(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOTAL_TIPPED, 0)
    }

    fun addTip(context: Context, amount: Int) {
        val current = getTotalTipped(context)
        getPrefs(context).edit().putInt(KEY_TOTAL_TIPPED, current + amount).apply()
    }

    // ─── Tip jar popup toggle ────────────────────────────────

    /**
     * Whether the tip jar progress popup (mobile card / TV bar) is enabled.
     * Defaults to true. Can be toggled in Settings.
     */
    fun isTipJarPopupEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_TIP_JAR_POPUP_ENABLED, true)
    }

    fun setTipJarPopupEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TIP_JAR_POPUP_ENABLED, enabled).apply()
    }

    // ─── "Ever been online" flag ─────────────────────────────

    /**
     * Returns whether the device has ever been online.
     * Once set to true this flag is never reset.
     */
    fun getHasEverBeenOnline(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAS_EVER_BEEN_ONLINE, false)
    }

    /**
     * Persistently record that the device has been online.
     * Only writes if the flag is not already true to avoid redundant I/O.
     */
    fun setHasEverBeenOnline(context: Context, value: Boolean) {
        if (value && getHasEverBeenOnline(context)) return // already set
        getPrefs(context).edit().putBoolean(KEY_HAS_EVER_BEEN_ONLINE, value).apply()
    }

    // ─── Server progress cache ───────────────────────────────-

    /**
     * Returns the last successfully fetched monthly percentage.
     * Defaults to 0 if no cache exists.
     */
    fun getCachedPercent(context: Context): Int {
        return getPrefs(context).getInt(KEY_CACHED_PERCENT, 0)
    }

    /**
     * Returns the last successfully fetched month label.
     * Returns an empty string if no cache exists — caller should fall back
     * to the default [R.string.tip_jar_progress_label].
     */
    fun getCachedMonth(context: Context): String {
        return getPrefs(context).getString(KEY_CACHED_MONTH, "") ?: ""
    }

    /**
     * Save the latest server-fetched progress values.
     * Also stores the current timestamp so we could show "last updated" info.
     *
     * @param percent The monthly percentage (0-100).
     * @param month   The month label from the server (e.g. "July 2025").
     */
    fun saveCachedProgress(context: Context, percent: Int, month: String) {
        getPrefs(context).edit()
            .putInt(KEY_CACHED_PERCENT, percent)
            .putString(KEY_CACHED_MONTH, month)
            .putLong(KEY_CACHED_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
}
