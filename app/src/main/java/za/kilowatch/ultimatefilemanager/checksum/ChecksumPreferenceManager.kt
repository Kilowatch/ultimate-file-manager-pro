package za.kilowatch.ultimatefilemanager.checksum

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user preferences for Checksum Tools (last-selected algorithms, large file prompts).
 */
object ChecksumPreferenceManager {

    const val PREFS_NAME = "checksum_prefs"
    private const val KEY_SELECTED_ALGORITHMS = "selected_algorithms"
    private const val KEY_WARN_LARGE_FILES = "warn_large_files"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedAlgorithms(context: Context): Set<HashAlgorithm> {
        val raw = getPrefs(context).getStringSet(KEY_SELECTED_ALGORITHMS, null)
        if (raw.isNullOrEmpty()) {
            return setOf(HashAlgorithm.SHA256)
        }
        val algos = raw.mapNotNull { name ->
            try {
                HashAlgorithm.valueOf(name)
            } catch (_: Exception) {
                null
            }
        }.toSet()
        return if (algos.isNotEmpty()) algos else setOf(HashAlgorithm.SHA256)
    }

    fun setSelectedAlgorithms(context: Context, algorithms: Set<HashAlgorithm>) {
        val set = algorithms.map { it.name }.toSet()
        getPrefs(context).edit().putStringSet(KEY_SELECTED_ALGORITHMS, set).apply()
    }

    fun shouldWarnLargeFiles(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_WARN_LARGE_FILES, true)
    }

    fun setWarnLargeFiles(context: Context, warn: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WARN_LARGE_FILES, warn).apply()
    }
}
