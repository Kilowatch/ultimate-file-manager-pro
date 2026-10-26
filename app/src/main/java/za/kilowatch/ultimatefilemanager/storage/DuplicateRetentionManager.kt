package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages preferences and retention strategies for bulk duplicate file deletion.
 */
object DuplicateRetentionManager {

    const val PREFS_NAME = "prefs_duplicate_retention"

    private const val KEY_STRATEGY = "duplicate_retention_strategy"
    private const val KEY_REMEMBER = "duplicate_retention_remember"

    const val MODE_ASK = "ask"
    const val MODE_OLDEST = "oldest"
    const val MODE_NEWEST = "newest"

    enum class DuplicateRetentionPolicy(val key: String) {
        KEEP_OLDEST(MODE_OLDEST),
        KEEP_NEWEST(MODE_NEWEST);

        companion object {
            fun fromKey(key: String?): DuplicateRetentionPolicy? = when (key) {
                MODE_OLDEST -> KEEP_OLDEST
                MODE_NEWEST -> KEEP_NEWEST
                else -> null
            }
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns the remembered retention policy if remember is true and a strategy is set,
     * or null if the user should be prompted ("Always Ask" mode).
     */
    fun getStrategy(context: Context): DuplicateRetentionPolicy? {
        val prefs = getPrefs(context)
        val remember = prefs.getBoolean(KEY_REMEMBER, false)
        if (!remember) return null
        val mode = prefs.getString(KEY_STRATEGY, MODE_ASK)
        return DuplicateRetentionPolicy.fromKey(mode)
    }

    /**
     * Returns the configured mode: [MODE_ASK], [MODE_OLDEST], or [MODE_NEWEST].
     */
    fun getPreferenceMode(context: Context): String {
        val prefs = getPrefs(context)
        val remember = prefs.getBoolean(KEY_REMEMBER, false)
        if (!remember) return MODE_ASK
        return prefs.getString(KEY_STRATEGY, MODE_ASK) ?: MODE_ASK
    }

    /**
     * Checks if the user explicitly opted to remember their choice.
     */
    fun isRemembered(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REMEMBER, false)
    }

    /**
     * Saves the chosen retention policy and remember flag from the in-dialog prompt.
     */
    fun saveStrategy(context: Context, policy: DuplicateRetentionPolicy, remember: Boolean) {
        getPrefs(context).edit()
            .putString(KEY_STRATEGY, policy.key)
            .putBoolean(KEY_REMEMBER, remember)
            .apply()
    }

    /**
     * Updates the mode directly from Settings: "oldest", "newest", or "ask".
     */
    fun setPreferenceMode(context: Context, mode: String) {
        val remember = mode != MODE_ASK
        getPrefs(context).edit()
            .putString(KEY_STRATEGY, mode)
            .putBoolean(KEY_REMEMBER, remember)
            .apply()
    }

    /**
     * Resets retention strategy back to default "Always Ask".
     */
    fun resetPreference(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_STRATEGY)
            .putBoolean(KEY_REMEMBER, false)
            .apply()
    }
}
