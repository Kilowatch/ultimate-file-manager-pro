package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.os.Process
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.FirebaseAnalytics.ConsentStatus
import com.google.firebase.analytics.FirebaseAnalytics.ConsentType
import java.util.concurrent.Executors

/**
 * Google Play-specific Analytics implementation routing to Firebase Analytics.
 *
 * Firebase is initialised on a dedicated background thread so the Firebase Measurement
 * component can never block the main looper (the >5000 ms "Main thread blocked" ANR seen
 * on low-end Android TV devices such as the onn 4K Pro, where the Measurement bootstrap
 * stalled the main thread at startup). The manifest removes FirebaseInitProvider, so
 * Firebase does not auto-start during the ContentProvider phase; all initialisation
 * happens here, off the main thread, after UfmApplication has registered BouncyCastle
 * synchronously on the main thread (so the security-provider list is stable before any
 * Firebase HTTPS thread starts).
 *
 * All Firebase calls are routed through a single-thread executor, so:
 *  - `FirebaseApp.initializeApp()` and `FirebaseAnalytics.getInstance()` never run on the
 *    caller's thread — including the Settings "Usage Analytics" toggle, which runs on the
 *    UI thread;
 *  - initialisation and enable-state changes are serialised, so a user toggling
 *    "Usage Analytics" before background init completes still gets their choice applied
 *    (`collectionEnabled` is read by the init task after init succeeds).
 *
 * The Settings "Usage Analytics" toggle genuinely controls collection via
 * `setAnalyticsCollectionEnabled(...)`; the state is persisted through [AnalyticsPrefs].
 */
object Analytics {
    private const val TAG = "UfmAnalytics"

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            },
            "ufm-firebase"
        ).apply { isDaemon = true }
    }

    /** Latest user intent for collection; applied by the init task even if set mid-init. */
    @Volatile
    private var collectionEnabled: Boolean? = null

    /** True once FirebaseApp and the Measurement component have been started. */
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        executor.execute {
            try {
                if (collectionEnabled == null) {
                    collectionEnabled = AnalyticsPrefs.isEnabled(context)
                }
                FirebaseApp.initializeApp(context)
                val analytics = FirebaseAnalytics.getInstance(context)
                analytics.setAnalyticsCollectionEnabled(collectionEnabled ?: true)
                applyConsent(analytics, collectionEnabled ?: true)
                initialized = true
                Log.d(TAG, "Firebase initialized on ${Thread.currentThread().name} (collection=$collectionEnabled).")
            } catch (e: Exception) {
                Log.e(TAG, "Firebase initialization failed", e)
            }
        }
    }

    fun setAnalyticsEnabled(context: Context, enabled: Boolean) {
        collectionEnabled = enabled
        executor.execute {
            try {
                AnalyticsPrefs.setEnabled(context, enabled)
                if (initialized) {
                    val analytics = FirebaseAnalytics.getInstance(context)
                    analytics.setAnalyticsCollectionEnabled(enabled)
                    applyConsent(analytics, enabled)
                }
                Log.d(TAG, "Analytics collection set to $enabled (initialized=$initialized).")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set analytics collection state", e)
            }
        }
    }

    /**
     * Signals the user's consent state to Google's systems via the Firebase Consent API.
     *
     * UFM has no ads, so only [ConsentType.ANALYTICS_STORAGE] is signalled — `ad_storage` /
     * `ad_user_data` / `ad_personalization` are intentionally left unset to avoid implying
     * advertising consent. This mirrors [setAnalyticsCollectionEnabled]: the toggle ON maps to
     * GRANTED, OFF maps to DENIED. Per Google's EU User Consent Policy, this consent state is
     * what Google's measurement stack records for EEA/UK/Swiss users.
     */
    private fun applyConsent(analytics: FirebaseAnalytics, enabled: Boolean) {
        try {
            val status = if (enabled) ConsentStatus.GRANTED else ConsentStatus.DENIED
            analytics.setConsent(mapOf(ConsentType.ANALYTICS_STORAGE to status))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set consent state", e)
        }
    }
}
