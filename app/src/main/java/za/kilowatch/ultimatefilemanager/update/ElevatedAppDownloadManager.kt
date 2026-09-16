package za.kilowatch.ultimatefilemanager.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.UfmHttpClient
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.PackageInstallerHelper
import java.io.File
import java.util.Locale

/**
 * Manages in-app downloading and auto-installation of elevated companion applications
 * (Shizuku and Shevery), matching the FOSS auto-update experience.
 */
object ElevatedAppDownloadManager {

    private const val TAG = "ElevatedAppDownload"
    private const val ELEVATED_CACHE_DIR = "elevated_apps"

    enum class ElevatedApp(
        val displayName: String,
        val primaryRepo: String,
        val fallbackRepo: String?,
        val fallbackWebUrl: String,
        val iconRes: Int
    ) {
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

    data class AppReleaseInfo(
        val app: ElevatedApp,
        val version: String,
        val tagName: String,
        val htmlUrl: String,
        val body: String,
        val apkUrl: String,
        val apkSize: Long,
        val apkName: String
    )

    @Volatile
    private var isDownloadCancelled = false

    /**
     * Entry point to start the download flow for Shizuku or Shevery.
     */
    fun startDownloadFlow(activity: Activity, app: ElevatedApp) {
        if (activity.isFinishing || activity.isDestroyed) return

        Toast.makeText(
            activity,
            activity.getString(R.string.elevated_fetching_toast, app.displayName),
            Toast.LENGTH_SHORT
        ).show()

        CoroutineScope(Dispatchers.Main).launch {
            val release = fetchRelease(app)

            if (activity.isFinishing || activity.isDestroyed) return@launch

            if (release == null || release.apkUrl.isBlank()) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.elevated_fetch_failed, app.displayName),
                    Toast.LENGTH_LONG
                ).show()
                openUrl(activity, app.fallbackWebUrl)
                return@launch
            }

            showDownloadDialog(activity, release)
        }
    }

    /**
     * Fetches release metadata from GitHub releases API.
     */
    suspend fun fetchRelease(app: ElevatedApp): AppReleaseInfo? = withContext(Dispatchers.IO) {
        // 1. Try primary repo
        var info = fetchFromGitHubRepo(app, app.primaryRepo)
        if (info != null && info.apkUrl.isNotBlank()) return@withContext info

        // 2. Try fallback repo if available
        if (app.fallbackRepo != null) {
            info = fetchFromGitHubRepo(app, app.fallbackRepo)
            if (info != null && info.apkUrl.isNotBlank()) return@withContext info
        }

        return@withContext null
    }

    private fun fetchFromGitHubRepo(app: ElevatedApp, repo: String): AppReleaseInfo? {
        val url = "https://api.github.com/repos/$repo/releases/latest"
        return try {
            val headers = mapOf(
                "User-Agent" to "UltimateFileManager-Android/${BuildConfig.VERSION_NAME}",
                "Accept" to "application/vnd.github.v3+json"
            )
            val response = UfmHttpClient.getSync(url, headers = headers, timeoutSec = 15)
            if (!response.isSuccessful) return null
            val body = response.bodyString

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            if (tagName.isBlank()) return null

            val version = tagName.removePrefix("v").removePrefix("V")
            val htmlUrl = json.optString("html_url", app.fallbackWebUrl)
            val releaseNotes = json.optString("body", "")

            var apkUrl = ""
            var apkSize = 0L
            var apkName = ""

            val assetsArray = json.optJSONArray("assets")
            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val downloadUrl = asset.optString("browser_download_url", "")
                    val size = asset.optLong("size", 0L)

                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = downloadUrl
                        apkSize = size
                        apkName = name
                        break
                    }
                }
            }

            if (apkUrl.isBlank()) return null
            if (apkName.isBlank()) apkName = "${app.displayName.lowercase()}-$tagName.apk"

            AppReleaseInfo(
                app = app,
                version = version,
                tagName = tagName,
                htmlUrl = htmlUrl,
                body = releaseNotes,
                apkUrl = apkUrl,
                apkSize = apkSize,
                apkName = apkName
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun formatFileSize(size: Long): String {
        return if (size > 0) {
            String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
        } else ""
    }

    /**
     * Displays a glassmorphic update/download dialog adhering to UFM standards.
     */
    fun showDownloadDialog(activity: Activity, release: AppReleaseInfo) {
        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.dialog_update_available_tv else R.layout.dialog_update_available
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val imgIcon = dialogView.findViewById<ImageView>(R.id.imgUpdateIcon)
        imgIcon?.setImageResource(release.app.iconRes)

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtUpdateTitle)
        txtTitle?.text = "${release.app.displayName} ${release.version}"

        val txtVersionDiff = dialogView.findViewById<TextView>(R.id.txtVersionDiff)
        txtVersionDiff?.text = release.tagName

        val txtChangelog = dialogView.findViewById<TextView>(R.id.txtChangelog)
        if (release.body.isNotBlank()) {
            txtChangelog?.visibility = View.VISIBLE
            txtChangelog?.text = FossUpdateManager.renderMarkdown(activity, release.body)
        } else {
            val sizeStr = formatFileSize(release.apkSize)
            txtChangelog?.text = "${release.app.displayName} ($sizeStr)"
        }

        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progressDownload)
        progressBar?.visibility = View.GONE

        val btnDownload = dialogView.findViewById<MaterialButton>(R.id.btnDownloadUpdate)
        btnDownload?.text = activity.getString(R.string.btn_download)

        val btnViewRelease = dialogView.findViewById<MaterialButton>(R.id.btnViewRelease)
        btnViewRelease?.text = activity.getString(R.string.update_btn_view_release)
        btnViewRelease?.setOnClickListener {
            openUrl(activity, release.htmlUrl)
        }

        val btnRemindLater = dialogView.findViewById<MaterialButton>(R.id.btnRemindLater)
        btnRemindLater?.text = activity.getString(R.string.btn_cancel)
        btnRemindLater?.setOnClickListener {
            cancelDownload()
            dialog.dismiss()
        }

        btnDownload?.setOnClickListener {
            startInAppDownload(activity, release, it as MaterialButton, progressBar, btnViewRelease, btnRemindLater, dialog)
        }

        dialog.setOnDismissListener {
            cancelDownload()
        }

        dialog.show()
    }

    /**
     * Starts in-app download and triggers installation upon completion.
     */
    private fun startInAppDownload(
        activity: Activity,
        release: AppReleaseInfo,
        btnDownload: MaterialButton,
        progressBar: LinearProgressIndicator?,
        btnViewRelease: MaterialButton?,
        btnRemindLater: MaterialButton?,
        dialog: android.app.Dialog
    ) {
        // Gate: check unknown sources install permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.update_install_permission_required, Toast.LENGTH_LONG).show()
            val permIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(permIntent)
            return
        }

        btnDownload.isEnabled = false
        btnDownload.text = activity.getString(R.string.update_downloading, 0)
        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        btnViewRelease?.isEnabled = false
        btnRemindLater?.isEnabled = false

        isDownloadCancelled = false

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
        scope.launch {
            val result = downloadApk(activity, release.apkUrl, release.apkName) { progress ->
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        btnDownload.text = activity.getString(R.string.update_downloading, progress)
                        progressBar?.setProgressCompat(progress, true)
                    }
                }
            }

            if (activity.isFinishing || activity.isDestroyed) return@launch

            if (result != null) {
                btnDownload.text = activity.getString(R.string.update_preparing_install)
                progressBar?.setProgressCompat(100, true)

                dialog.setOnDismissListener(null)
                dialog.dismiss()

                installDownloadedApk(activity, result, release.app.displayName)
            } else {
                btnDownload.isEnabled = true
                btnDownload.text = activity.getString(R.string.update_download_failed)
                progressBar?.visibility = View.GONE
                btnViewRelease?.isEnabled = true
                btnRemindLater?.isEnabled = true

                btnDownload.setOnClickListener {
                    startInAppDownload(activity, release, btnDownload, progressBar, btnViewRelease, btnRemindLater, dialog)
                }
            }
        }
    }

    private suspend fun downloadApk(
        context: Context,
        url: String,
        fileName: String,
        progressCallback: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, ELEVATED_CACHE_DIR)
        cacheDir.mkdirs()

        val targetFile = File(cacheDir, fileName)
        val tempFile = File(cacheDir, "$fileName.tmp")
        tempFile.delete()

        try {
            val headers = mapOf("User-Agent" to "UltimateFileManager-Android/${BuildConfig.VERSION_NAME}")
            val response = UfmHttpClient.openStream(url, headers = headers, timeoutSec = 60)
            if (!response.isSuccessful) {
                Log.w(TAG, "Download failed: HTTP ${response.statusCode}")
                response.close()
                return@withContext null
            }

            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            var bytesRead = 0L
            var lastReportedProgress = -1

            tempFile.outputStream().use { output ->
                response.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (isDownloadCancelled) {
                            throw java.io.IOException("Download cancelled by user")
                        }
                        output.write(buffer, 0, read)
                        bytesRead += read

                        if (contentLength > 0) {
                            val progress = ((bytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                            if (progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                progressCallback(progress)
                            }
                        }
                    }
                }
            }
            response.close()

            targetFile.delete()
            if (tempFile.renameTo(targetFile)) {
                Log.d(TAG, "APK downloaded successfully: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                return@withContext targetFile
            } else {
                Log.w(TAG, "Failed to rename temp file to target")
                tempFile.delete()
                return@withContext null
            }
        } catch (e: Exception) {
            tempFile.delete()
            if (isDownloadCancelled || (e is java.io.IOException && e.message?.contains("cancelled", ignoreCase = true) == true)) {
                Log.d(TAG, "Download cancelled by user")
            } else {
                Log.w(TAG, "Download failed: ${e.message}", e)
            }
            return@withContext null
        }
    }

    private fun installDownloadedApk(context: Context, apkFile: File, appName: String) {
        try {
            PackageInstallerHelper.installApk(context, apkFile)
            Log.d(TAG, "Install session committed for $appName")
        } catch (e: SecurityException) {
            Toast.makeText(context, R.string.update_install_permission_required, Toast.LENGTH_LONG).show()
            Log.w(TAG, "Install permission denied: ${e.message}")
        } catch (e: Exception) {
            Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            Log.e(TAG, "Install failed: ${e.message}", e)
        }
    }

    private fun cancelDownload() {
        isDownloadCancelled = true
        Log.d(TAG, "Download cancelled")
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
}
