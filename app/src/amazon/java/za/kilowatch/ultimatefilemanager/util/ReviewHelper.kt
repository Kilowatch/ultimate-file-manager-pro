package za.kilowatch.ultimatefilemanager.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import za.kilowatch.ultimatefilemanager.BuildConfig

/**
 * Amazon Appstore-specific rating helper.
 * Completely excludes any Google Play In-App Review references or SDKs.
 */
object ReviewHelper {
    private const val TAG = "GoRoRating"

    /**
     * Launch Amazon rating flow on Amazon devices.
     */
    fun launchInAppReview(activity: Activity) {
        if (BuildConfig.AMAZON_RATING_ENABLED) {
            launchAmazonReview(activity)
        } else {
            Log.d(TAG, "launchInAppReview: Amazon rating disabled")
        }
    }

    fun redirectToPlayStore(activity: Activity) {
        launchInAppReview(activity)
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
