package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.util.Log

/**
 * Amazon Appstore-specific Analytics implementation (no-op).
 * Contains no references to Firebase or Google GMS packages to comply with Amazon policies.
 */
object Analytics {
    private const val TAG = "UfmAnalytics"

    fun init(context: Context) {
        Log.d(TAG, "Analytics initialized (no-op for Amazon version).")
    }

    fun setAnalyticsEnabled(context: Context, enabled: Boolean) {
        Log.d(TAG, "Analytics enabled state set to $enabled (no-op for Amazon version).")
    }
}
