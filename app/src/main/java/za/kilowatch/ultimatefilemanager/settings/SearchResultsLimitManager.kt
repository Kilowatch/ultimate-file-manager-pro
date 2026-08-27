package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.SharedPreferences
import za.kilowatch.ultimatefilemanager.R

/**
 * Manages the user's preferred search results batch limit.
 * Configured in Settings -> File Management, and respected across
 * SearchActivity (global search) and FileBrowserActivity / FileBrowserFragment (in-folder search).
 */
object SearchResultsLimitManager {

    private const val PREFS_NAME = "ufm_prefs"
    const val KEY_SEARCH_RESULTS_LIMIT = "pref_search_results_limit"

    const val LIMIT_200 = 200
    const val LIMIT_500 = 500
    const val LIMIT_1000 = 1000
    const val LIMIT_2000 = 2000

    const val DEFAULT_LIMIT = LIMIT_500

    val AVAILABLE_LIMITS = listOf(LIMIT_200, LIMIT_500, LIMIT_1000, LIMIT_2000)

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets the configured search results limit (default 500).
     */
    fun getSearchLimit(context: Context): Int {
        val value = getPrefs(context).getInt(KEY_SEARCH_RESULTS_LIMIT, DEFAULT_LIMIT)
        return if (value in AVAILABLE_LIMITS) value else DEFAULT_LIMIT
    }

    /**
     * Persists the chosen search results limit.
     */
    fun setSearchLimit(context: Context, limit: Int) {
        val safeLimit = if (limit in AVAILABLE_LIMITS) limit else DEFAULT_LIMIT
        getPrefs(context).edit().putInt(KEY_SEARCH_RESULTS_LIMIT, safeLimit).apply()
    }

    /**
     * Formats a user-friendly subtitle describing the current limit.
     */
    fun getLimitSubtitle(context: Context, limit: Int = getSearchLimit(context)): String {
        return when (limit) {
            LIMIT_200 -> "${context.getString(R.string.settings_search_limit_200)} · ${context.getString(R.string.settings_search_limit_200_desc)}"
            LIMIT_500 -> "${context.getString(R.string.settings_search_limit_500)} · ${context.getString(R.string.settings_search_limit_500_desc)}"
            LIMIT_1000 -> "${context.getString(R.string.settings_search_limit_1000)} · ${context.getString(R.string.settings_search_limit_1000_desc)}"
            LIMIT_2000 -> "${context.getString(R.string.settings_search_limit_2000)} · ${context.getString(R.string.settings_search_limit_2000_desc)}"
            else -> context.getString(R.string.settings_search_limit_subtitle, limit)
        }
    }
}
