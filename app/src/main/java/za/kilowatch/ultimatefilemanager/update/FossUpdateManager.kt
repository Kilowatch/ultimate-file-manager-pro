package za.kilowatch.ultimatefilemanager.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FossUpdatePreferenceManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.util.concurrent.TimeUnit

/**
 * FOSS Release Update Manager
 *
 * Coordinates checking GitHub releases via the Kilowatch server caching API,
 * parsing release assets (specifically resolving mobile-foss-release.apk vs tv-foss-release.apk),
 * posting update notifications, and presenting glassmorphic update dialogs.
 */
object FossUpdateManager {

    private const val TAG = "FossUpdateManager"
    private const val PRIMARY_API_URL = "https://www.kilowatch.co.za/UFM/api/update.php"
    private const val HTTP_API_URL = "http://kilowatch.co.za/UFM/api/update.php"
    private const val GITHUB_FALLBACK_URL = "https://api.github.com/repos/Kilowatch/ultimate-file-manager-pro/releases/latest"

    private const val NOTIFICATION_CHANNEL_ID = "ufm_app_updates"
    private const val NOTIFICATION_ID = 8842

    // Throttle automatic app-open checks to at most once per 4 hours
    private const val THROTTLE_INTERVAL_MS = 4 * 60 * 60 * 1000L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class ReleaseInfo(
        val version: String,
        val tagName: String,
        val name: String,
        val htmlUrl: String,
        val publishedAt: String,
        val body: String,
        val mobileApkUrl: String,
        val mobileApkSize: Long,
        val tvApkUrl: String,
        val tvApkSize: Long
    ) {
        fun getTargetApkUrl(context: Context): String {
            val isTv = DeviceUtils.isTvDevice(context)
            return if (isTv) {
                if (tvApkUrl.isNotBlank()) tvApkUrl else if (mobileApkUrl.isNotBlank()) mobileApkUrl else htmlUrl
            } else {
                if (mobileApkUrl.isNotBlank()) mobileApkUrl else if (tvApkUrl.isNotBlank()) tvApkUrl else htmlUrl
            }
        }

        fun getTargetApkName(context: Context): String {
            return if (DeviceUtils.isTvDevice(context)) "tv-foss-release.apk" else "mobile-foss-release.apk"
        }
    }

    /**
     * Checks if a remote version string is strictly newer than the current version.
     * Handles semver strings like "1.9.5" vs "1.9.4", "1.10.0" vs "1.9.4", "v1.9.5-beta".
     */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val cleanRemote = remoteVersion.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

        if (cleanRemote.isBlank() || cleanCurrent.isBlank()) return false
        if (cleanRemote.equals(cleanCurrent, ignoreCase = true)) return false

        val remoteParts = cleanRemote.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = if (i < remoteParts.size) remoteParts[i] else 0
            val c = if (i < currentParts.size) currentParts[i] else 0
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * Fetches latest release metadata with automatic fallback chain:
     * 1. Primary HTTPS endpoint
     * 2. HTTP endpoint
     * 3. Public GitHub API
     */
    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        // 1. Primary endpoint
        var release = fetchFromEndpoint(PRIMARY_API_URL)
        if (release != null) return@withContext release

        // 2. HTTP fallback
        release = fetchFromEndpoint(HTTP_API_URL)
        if (release != null) return@withContext release

        // 3. GitHub API direct fallback
        return@withContext fetchFromGitHubApi(GITHUB_FALLBACK_URL)
    }

    private fun fetchFromEndpoint(url: String): ReleaseInfo? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "UltimateFileManager-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) return null

            val json = JSONObject(body)
            val success = json.optBoolean("success", true)
            val version = json.optString("version", "")
            if (!success || version.isBlank()) return null

            val tagName = json.optString("tag_name", "v$version")
            val name = json.optString("name", tagName)
            val htmlUrl = json.optString("html_url", "https://github.com/Kilowatch/ultimate-file-manager-pro/releases/latest")
            val publishedAt = json.optString("published_at", "")
            val releaseNotes = json.optString("body", "")

            val assetsObj = json.optJSONObject("assets")
            val mobileObj = assetsObj?.optJSONObject("mobile_apk")
            val tvObj = assetsObj?.optJSONObject("tv_apk")

            val mobileUrl = mobileObj?.optString("download_url", "") ?: ""
            val mobileSize = mobileObj?.optLong("size", 0L) ?: 0L
            val tvUrl = tvObj?.optString("download_url", "") ?: ""
            val tvSize = tvObj?.optLong("size", 0L) ?: 0L

            ReleaseInfo(
                version = version,
                tagName = tagName,
                name = name,
                htmlUrl = htmlUrl,
                publishedAt = publishedAt,
                body = releaseNotes,
                mobileApkUrl = mobileUrl,
                mobileApkSize = mobileSize,
                tvApkUrl = tvUrl,
                tvApkSize = tvSize
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromGitHubApi(url: String): ReleaseInfo? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "UltimateFileManager-Android/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github.v3+json")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) return null

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            if (tagName.isBlank()) return null

            val version = tagName.removePrefix("v").removePrefix("V")
            val name = json.optString("name", tagName)
            val htmlUrl = json.optString("html_url", "https://github.com/Kilowatch/ultimate-file-manager-pro/releases/latest")
            val publishedAt = json.optString("published_at", "")
            val releaseNotes = json.optString("body", "")

            var mobileUrl = ""
            var mobileSize = 0L
            var tvUrl = ""
            var tvSize = 0L

            val assetsArray = json.optJSONArray("assets")
            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.optJSONObject(i) ?: continue
                    val aName = asset.optString("name", "")
                    val aUrl = asset.optString("browser_download_url", "")
                    val aSize = asset.optLong("size", 0L)

                    if (aName.contains("mobile", ignoreCase = true) || aName == "mobile-foss-release.apk") {
                        mobileUrl = aUrl
                        mobileSize = aSize
                    } else if (aName.contains("tv", ignoreCase = true) || aName == "tv-foss-release.apk") {
                        tvUrl = aUrl
                        tvSize = aSize
                    }
                }
            }

            if (mobileUrl.isBlank()) {
                mobileUrl = "https://github.com/Kilowatch/ultimate-file-manager-pro/releases/download/$tagName/mobile-foss-release.apk"
            }
            if (tvUrl.isBlank()) {
                tvUrl = "https://github.com/Kilowatch/ultimate-file-manager-pro/releases/download/$tagName/tv-foss-release.apk"
            }

            ReleaseInfo(
                version = version,
                tagName = tagName,
                name = name,
                htmlUrl = htmlUrl,
                publishedAt = publishedAt,
                body = releaseNotes,
                mobileApkUrl = mobileUrl,
                mobileApkSize = mobileSize,
                tvApkUrl = tvUrl,
                tvApkSize = tvSize
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Non-blocking check triggered when the app is launched or re-opened.
     * Only runs if BuildConfig.IS_FOSS is true and auto check is enabled in settings.
     */
    fun checkOnAppOpen(activity: Activity) {
        if (!BuildConfig.IS_FOSS) return
        if (!FossUpdatePreferenceManager.isAutoCheckEnabled(activity)) return

        val lastCheck = FossUpdatePreferenceManager.getLastCheckTime(activity)
        val now = System.currentTimeMillis()
        if (now - lastCheck < THROTTLE_INTERVAL_MS) {
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val release = fetchLatestRelease() ?: return@launch
            FossUpdatePreferenceManager.setLastCheckTime(activity, System.currentTimeMillis())

            if (isNewerVersion(release.version, BuildConfig.VERSION_NAME)) {
                val lastNotified = FossUpdatePreferenceManager.getLastNotifiedVersion(activity)
                val isDismissed = FossUpdatePreferenceManager.isVersionDismissed(activity, release.version)

                // Show notification if not notified yet or if not dismissed
                if (!isDismissed && !lastNotified.equals(release.version, ignoreCase = true)) {
                    FossUpdatePreferenceManager.setLastNotifiedVersion(activity, release.version)
                    showUpdateNotification(activity, release)
                }

                // If activity is still foreground and not dismissed, show dialog
                if (!activity.isFinishing && !activity.isDestroyed && !isDismissed) {
                    showUpdateDialog(activity, release)
                }
            }
        }
    }

    /**
     * Manual check triggered by user action in Settings or About screen.
     */
    fun checkManually(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        Toast.makeText(activity, R.string.update_checking_toast, Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val release = fetchLatestRelease()

            if (activity.isFinishing || activity.isDestroyed) return@launch

            if (release == null) {
                Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }

            FossUpdatePreferenceManager.setLastCheckTime(activity, System.currentTimeMillis())

            if (isNewerVersion(release.version, BuildConfig.VERSION_NAME)) {
                showUpdateDialog(activity, release)
            } else {
                showAlreadyLatestDialog(activity, BuildConfig.VERSION_NAME)
            }
        }
    }

    /**
     * Displays a glassmorphic update dialog adhering to UFMStandard.
     */
    fun showUpdateDialog(activity: Activity, release: ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.dialog_update_available_tv else R.layout.dialog_update_available
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtUpdateTitle)
        val txtVersionDiff = dialogView.findViewById<TextView>(R.id.txtVersionDiff)
        val txtChangelog = dialogView.findViewById<TextView>(R.id.txtChangelog)
        val btnDownload = dialogView.findViewById<MaterialButton>(R.id.btnDownloadUpdate)
        val btnViewRelease = dialogView.findViewById<MaterialButton>(R.id.btnViewRelease)
        val btnRemindLater = dialogView.findViewById<MaterialButton>(R.id.btnRemindLater)

        txtTitle?.text = activity.getString(R.string.update_available_title, release.version)
        txtVersionDiff?.text = activity.getString(R.string.update_version_comparison, BuildConfig.VERSION_NAME, release.version)

        val changelogBody = if (release.body.isNotBlank()) {
            renderMarkdown(activity, release.body)
        } else {
            activity.getString(R.string.update_available_msg)
        }
        txtChangelog?.text = changelogBody

        val targetApkName = release.getTargetApkName(activity)
        btnDownload?.text = activity.getString(R.string.update_btn_download, targetApkName)

        btnDownload?.setOnClickListener {
            dialog.dismiss()
            val downloadUrl = release.getTargetApkUrl(activity)
            openUrl(activity, downloadUrl)
        }

        btnViewRelease?.setOnClickListener {
            dialog.dismiss()
            openUrl(activity, release.htmlUrl)
        }

        btnRemindLater?.setOnClickListener {
            dialog.dismiss()
            FossUpdatePreferenceManager.dismissVersion(activity, release.version)
        }

        dialog.show()

        val displayMetrics = activity.resources.displayMetrics
        val targetWidth = if (isTv) {
            (displayMetrics.widthPixels * 0.55).toInt()
        } else {
            (displayMetrics.widthPixels * 0.90).toInt()
        }
        dialog.window?.setLayout(targetWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Displays a dialog confirming the user is on the latest release.
     */
    fun showAlreadyLatestDialog(activity: Activity, currentVersion: String) {
        if (activity.isFinishing || activity.isDestroyed) return

        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val imgIcon = dialogView.findViewById<ImageView>(R.id.imgDialogIcon)
        imgIcon?.setImageResource(R.drawable.ic_check_circle)

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        txtTitle?.text = activity.getString(R.string.update_already_latest_title)

        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDialogMessage)
        txtMessage?.text = activity.getString(R.string.update_already_latest_msg, currentVersion)

        val btnOk = dialogView.findViewById<MaterialButton>(R.id.btnDialogPositive)
        btnOk?.text = activity.getString(R.string.btn_ok)
        btnOk?.setOnClickListener { dialog.dismiss() }

        val btnNegative = dialogView.findViewById<View>(R.id.btnDialogNegative)
        btnNegative?.visibility = View.GONE

        dialog.show()
    }

    /**
     * Posts a system notification with platform-specific download action.
     */
    private fun showUpdateNotification(context: Context, release: ReleaseInfo) {
        ensureNotificationChannel(context)

        val targetApkUrl = release.getTargetApkUrl(context)
        val targetApkName = release.getTargetApkName(context)

        // Intent to download APK
        val downloadIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetApkUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pDownloadIntent = PendingIntent.getActivity(
            context,
            1,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to view release on GitHub
        val releaseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pReleaseIntent = PendingIntent.getActivity(
            context,
            2,
            releaseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_install)
            .setContentTitle(context.getString(R.string.update_notification_title, release.version))
            .setContentText(context.getString(R.string.update_notification_desc, release.version))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.update_notification_desc, release.version) + "\n\n" + release.body.take(200)
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pDownloadIntent)
            .addAction(R.drawable.ic_install, context.getString(R.string.btn_download) + " ($targetApkName)", pDownloadIntent)
            .addAction(R.drawable.ic_about, context.getString(R.string.update_btn_view_release), pReleaseIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Handled if notification permission is denied
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notif_channel_app_updates)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = name
                enableLights(true)
                lightColor = Color.BLUE
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, url, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Parses GitHub release markdown notes (headers, bullets, bold, code snippets)
     * and returns formatted Spannable/HTML CharSequence.
     */
    fun renderMarkdown(context: Context, markdown: String): CharSequence {
        if (markdown.isBlank()) return ""
        val lines = markdown.lines()
        val sb = StringBuilder()

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val trimmed = line.trimStart()

            if (trimmed.isEmpty()) {
                sb.append("<br>")
                continue
            }

            // Headers (### Added, ### Fixed)
            if (trimmed.startsWith("### ")) {
                val title = trimmed.removePrefix("### ").trim()
                sb.append("<br><font color=\"#4FC3F7\"><b>").append(escapeHtml(title)).append("</b></font><br>")
                continue
            } else if (trimmed.startsWith("## ")) {
                val title = trimmed.removePrefix("## ").trim()
                sb.append("<br><font color=\"#81D4FA\"><b><big>").append(escapeHtml(title)).append("</big></b></font><br>")
                continue
            } else if (trimmed.startsWith("# ")) {
                val title = trimmed.removePrefix("# ").trim()
                sb.append("<br><b><big>").append(escapeHtml(title)).append("</big></b><br>")
                continue
            }

            // Bullet items
            val indentSpaces = line.length - trimmed.length
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                val content = trimmed.substring(2)
                val bulletIcon = if (indentSpaces >= 2) "&nbsp;&nbsp;&nbsp;&nbsp;&#9642; " else "&#8226; "
                sb.append(bulletIcon).append(formatInlineMarkdown(content)).append("<br>")
                continue
            }

            sb.append(formatInlineMarkdown(trimmed)).append("<br>")
        }

        return androidx.core.text.HtmlCompat.fromHtml(sb.toString(), androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    private fun formatInlineMarkdown(text: String): String {
        var result = escapeHtml(text)
        // Inline code `code`
        result = result.replace(Regex("`([^`]+)`"), "<font color=\"#80DEEA\"><code>$1</code></font>")
        // Bold **bold**
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
        // Italic *italic*
        result = result.replace(Regex("\\*([^*]+)\\*"), "<i>$1</i>")
        return result
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
