package za.kilowatch.ultimatefilemanager.bookmarks.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.ui.ShareReceiverHelper
import java.util.regex.Pattern

/**
 * Share Target Activity that receives ACTION_SEND and ACTION_SEND_MULTIPLE intents
 * and presents the UFM Quick Save Bookmark Bottom Sheet.
 */
class BookmarkReceiverActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)

        // If recreated from saved state or launched from history, dismiss immediately
        if (savedInstanceState != null || (intent != null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0))) {
            finishAndRemoveTask()
            return
        }

        val incomingIntent = intent
        val action = incomingIntent?.action
        if (incomingIntent == null || (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE)) {
            finishAndRemoveTask()
            return
        }

        val callingPkg = resolveCallingPackage()
        val appName = resolveApplicationName(callingPkg)

        val rawText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
        val rawSubject = incomingIntent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotBlank() }
        val rawTitle = incomingIntent.getStringExtra(Intent.EXTRA_TITLE)?.trim()?.takeIf { it.isNotBlank() }
        val clipLabel = incomingIntent.clipData?.description?.label?.toString()?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("http") }
        val extractedUrl = extractUrlFromText(rawText)
        val plainText = if (extractedUrl != null) null else rawText
        val sharedUris = ShareReceiverHelper.extractUris(incomingIntent)

        val textWithoutUrl = if (extractedUrl != null && rawText != null) {
            rawText.replace(extractedUrl, "").trim().takeIf { it.isNotBlank() }
        } else null
        val initialTitle = rawSubject ?: rawTitle ?: clipLabel ?: textWithoutUrl

        if (extractedUrl == null && plainText == null && sharedUris.isEmpty()) {
            finishAndRemoveTask()
            return
        }

        val sheet = BookmarkQuickSaveBottomSheet.newInstance(
            sourcePackage = callingPkg,
            sourceAppName = appName,
            sharedUrl = extractedUrl,
            sharedText = plainText,
            sharedUris = sharedUris,
            initialTitle = initialTitle,
            onFinished = { finishAndRemoveTask() }
        )
        sheet.show(supportFragmentManager, "BookmarkQuickSaveBottomSheet")
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) {
            finishAndRemoveTask()
        }
    }

    private fun resolveCallingPackage(): String? {
        // 1. Direct calling package
        callingPackage?.let { return it }

        // 2. Activity referrer
        val referrer = referrer
        if (referrer != null && referrer.scheme == "android-app") {
            return referrer.authority
        }

        // 3. Extra referrer string
        val extraReferrer = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_REFERRER, Uri::class.java)
            ?: intent.getStringExtra(Intent.EXTRA_REFERRER)?.let { Uri.parse(it) }
        if (extraReferrer != null && extraReferrer.scheme == "android-app") {
            return extraReferrer.authority
        }

        return null
    }

    private fun resolveApplicationName(pkg: String?): String {
        if (pkg == null) return getString(R.string.share_target_bookmark)
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun extractUrlFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed.split(Regex("\\s+")).firstOrNull()
        }
        val urlPattern = Pattern.compile("https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+")
        val matcher = urlPattern.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }
}
