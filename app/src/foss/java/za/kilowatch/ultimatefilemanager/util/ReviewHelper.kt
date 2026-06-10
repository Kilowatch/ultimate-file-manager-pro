package za.kilowatch.ultimatefilemanager.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/**
 * FOSS build override for ReviewHelper.
 *
 * Google Play In-App Review is not available in the FOSS build.
 * Redirects the user to the F-Droid store details page for the FOSS package.
 */
object ReviewHelper {

    /** Launches the F-Droid store page. */
    fun launchInAppReview(activity: Activity) {
        redirectToPlayStore(activity)
    }

    /** Opens the app's details page in the F-Droid app or browser. */
    fun redirectToPlayStore(activity: Activity) {
        val packageName = activity.packageName
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/$packageName")).apply {
                setPackage("org.fdroid.fdroid")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/$packageName")))
        }
    }

    /** No-op — Amazon Appstore is not used in the FOSS build. */
    fun launchAmazonReview(activity: Activity) { /* no-op */ }
}
