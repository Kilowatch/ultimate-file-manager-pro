package za.kilowatch.ultimatefilemanager.update

import android.app.Activity
import android.content.Context
import android.text.Spanned
import androidx.core.text.HtmlCompat

/**
 * No-op stub for Google Play / Amazon builds.
 * The real implementation lives in src/foss/ only and is not compiled into
 * any non-FOSS variant, keeping APK-download/install code off Google Play.
 */
object FossUpdateManager {
    fun checkOnAppOpen(activity: Activity) = Unit
    fun checkManually(activity: Activity) = Unit
    fun onInstallSessionFinished(context: Context, isSuccess: Boolean) = Unit
    fun renderMarkdown(context: Context, markdown: String): Spanned =
        HtmlCompat.fromHtml("", HtmlCompat.FROM_HTML_MODE_LEGACY)
}
