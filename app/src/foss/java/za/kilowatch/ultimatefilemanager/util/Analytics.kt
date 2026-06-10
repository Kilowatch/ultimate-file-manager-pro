package za.kilowatch.ultimatefilemanager.util

import android.content.Context

/**
 * FOSS build stub for Analytics.
 *
 * Firebase Analytics is not available in the FOSS build — no google-services.json,
 * no Firebase SDK dependency. All calls are silent no-ops.
 */
object Analytics {

    /** No-op — Firebase is not present in the FOSS build. */
    fun init(context: Context) { /* no-op */ }

    /** No-op — Firebase is not present in the FOSS build. */
    fun setAnalyticsEnabled(context: Context, enabled: Boolean) { /* no-op */ }
}
