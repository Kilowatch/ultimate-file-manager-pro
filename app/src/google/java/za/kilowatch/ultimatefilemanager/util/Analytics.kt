package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.util.Log

/**
 * Google Play-specific Analytics implementation.
 *
 * Firebase Analytics initialization is intentionally disabled. UFM's own
 * CrashReportManager handles crash/ANR reporting, and the app never logs custom
 * analytics events, so the Firebase Measurement component provided no in-app value
 * while its internal runnable (R8-merged `com.google.android.gms.internal.play_billing.zzcw`
 * / `com.google.android.gms.measurement.internal.*`) blocked the main looper for
 * >5000 ms on low-end Android TV devices (e.g. onn 4K Pro), producing a genuine
 * "Main thread blocked" ANR. Without `FirebaseApp.initializeApp()` the Measurement
 * component never starts (the manifest already removes FirebaseInitProvider), so no
 * Firebase work runs on the main thread at all.
 *
 * The Settings "Usage Analytics" toggle still saves its preference but has no effect;
 * the firebase-analytics dependency is retained only so this can be re-enabled without
 * a build-system change if a real analytics pipeline is added later.
 */
object Analytics {
    private const val TAG = "UfmAnalytics"

    fun init(context: Context) {
        Log.d(TAG, "Analytics init is a no-op — Firebase Measurement disabled to avoid main-thread ANR on low-end devices.")
    }

    fun setAnalyticsEnabled(context: Context, enabled: Boolean) {
        Log.d(TAG, "Analytics enabled state set to $enabled (no-op — Firebase Measurement disabled).")
    }
}
