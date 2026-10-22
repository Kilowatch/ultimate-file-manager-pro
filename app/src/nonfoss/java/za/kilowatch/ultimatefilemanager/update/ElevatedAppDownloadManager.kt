package za.kilowatch.ultimatefilemanager.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import za.kilowatch.ultimatefilemanager.R

/**
 * Google Play / Amazon stub for ElevatedAppDownloadManager.
 *
 * In-app APK download and installation from GitHub is prohibited on Google Play
 * (Device and Network Abuse policy). This stub replaces the full download flow
 * with a simple browser redirect to the app's GitHub releases page, letting the
 * user download and install it manually.
 *
 * The real download implementation lives in src/foss/ only.
 */
object ElevatedAppDownloadManager {

    enum class ElevatedApp(
        val displayName: String,
        val primaryRepo: String,
        val fallbackRepo: String?,
        val fallbackWebUrl: String,
        val iconRes: Int,
        val apkAssetPattern: Regex? = null
    ) {
        PORTER(
            displayName = "Porter",
            primaryRepo = "d4rken-org/porter",
            fallbackRepo = null,
            fallbackWebUrl = "https://github.com/d4rken-org/porter/releases",
            iconRes = R.drawable.ic_porter_logo
        ),
        SHIZUKU(
            displayName = "Shizuku",
            primaryRepo = "thedjchi/Shizuku",
            fallbackRepo = "RikkaApps/Shizuku",
            fallbackWebUrl = "https://github.com/thedjchi/Shizuku/releases/latest",
            iconRes = R.drawable.ic_shield_check
        ),
        SHEVERY(
            displayName = "Shevery",
            primaryRepo = "HmnDev-Tech/shevery",
            fallbackRepo = null,
            fallbackWebUrl = "https://github.com/HmnDev-Tech/shevery/releases/latest",
            iconRes = R.drawable.ic_lightning
        )
    }

    /**
     * Opens the GitHub releases page for [app] in the device browser.
     * No APK is downloaded or installed — this variant is for Google Play / Amazon builds.
     */
    fun startDownloadFlow(activity: Activity, app: ElevatedApp) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(app.fallbackWebUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, app.fallbackWebUrl, Toast.LENGTH_LONG).show()
        }
    }
}
