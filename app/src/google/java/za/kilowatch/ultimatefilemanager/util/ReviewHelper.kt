package za.kilowatch.ultimatefilemanager.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import za.kilowatch.ultimatefilemanager.BuildConfig

/**
 * Facilitates the Google Play In-App Review API.
 */
object ReviewHelper {
    private const val TAG = "GoRoRating"

    /**
     * Attempts to launch the In-App Review flow.
     * If it fails (e.g., no Play Services), it falls back to a Play Store redirect.
     */
    fun launchInAppReview(activity: Activity) {
        if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(activity)) {
            if (BuildConfig.AMAZON_RATING_ENABLED) {
                launchAmazonReview(activity)
            } else {
                Log.d(TAG, "launchInAppReview: Amazon rating disabled")
            }
            return
        }
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "launchInAppReview: requesting review flow")
        val manager = ReviewManagerFactory.create(activity)
        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "launchInAppReview: request successful, launching flow")
                val reviewInfo = task.result
                val flow = manager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCompleteListener { _ ->
                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "launchInAppReview: flow completed in ${duration}ms")
                    
                    // If the flow completed too quickly (e.g. < 2 seconds), it was likely suppressed
                    // by Google Play (quota, already rated, etc.). Fallback to Store in this case.
                    if (duration < 2000) {
                        Log.w(TAG, "launchInAppReview: suppressed by Google Play, falling back to Store")
                        redirectToPlayStore(activity)
                    }
                }
            } else {
                Log.w(TAG, "launchInAppReview: request failed, redirecting to store")
                redirectToPlayStore(activity)
            }
        }
    }

    /**
     * Opens the app's details page in the Play Store app or browser.
     */
    fun redirectToPlayStore(activity: Activity) {
        if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(activity)) {
            if (BuildConfig.AMAZON_RATING_ENABLED) {
                launchAmazonReview(activity)
            } else {
                Log.d(TAG, "redirectToPlayStore: Amazon rating disabled")
            }
            return
        }
        Log.d(TAG, "redirectToPlayStore: opening Play Store link")
        val packageName = activity.packageName
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
    }

    fun launchAmazonReview(activity: Activity) {
        Log.d(TAG, "launchAmazonReview: opening Amazon Appstore link")
        val packageName = activity.packageName
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?p=$packageName")))
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.amazon.com/dp/$packageName")))
        }
    }
}
