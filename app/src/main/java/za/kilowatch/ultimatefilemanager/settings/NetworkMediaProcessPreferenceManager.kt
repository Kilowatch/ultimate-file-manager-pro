package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the "Network Media Processing" preference.
 *
 * Default: false (Direct remote stream processing without copying the source video to local storage).
 * If true: Copies the entire source video to local cache before extraction or conversion,
 *          triggering a UFMStandard confirmation dialog prior to copying.
 */
object NetworkMediaProcessPreferenceManager {

    private const val PREFS_NAME = "network_media_process_prefs"
    private const val KEY_COPY_TO_LOCAL = "key_copy_to_local_before_process"

    /** Returns true when the user prefers downloading the full video to local storage before processing. */
    fun isCopyBeforeProcessing(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COPY_TO_LOCAL, false) // default OFF (Direct streaming)
    }

    /** Persists the copy-before-process preference. */
    fun setCopyBeforeProcessing(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COPY_TO_LOCAL, enabled)
            .apply()
    }
}
