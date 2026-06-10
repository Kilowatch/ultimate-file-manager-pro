package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Google Play-specific Analytics implementation routing to Firebase Analytics.
 */
object Analytics {
    private const val TAG = "UfmAnalytics"

    fun init(context: Context) {
        try {
            FirebaseApp.initializeApp(context)
            Log.d(TAG, "Firebase initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization failed", e)
        }
    }

    fun setAnalyticsEnabled(context: Context, enabled: Boolean) {
        try {
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled)
            Log.d(TAG, "Firebase analytics collection set to $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set analytics collection enabled state", e)
        }
    }
}
