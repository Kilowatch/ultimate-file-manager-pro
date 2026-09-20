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
import org.json.JSONArray
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
        val iconRes: Int,
        /**
         * Selects THIS manager's own APK from a release's assets. `null` keeps the legacy
         * "first .apk asset" behaviour, which is correct for repos publishing a single APK.
         */
        val apkAssetPattern: Regex? = null
    ) {
        PORTER(
            displayName = "Porter",
            primaryRepo = "d4rken-org/porter",
            fallbackRepo = null,
            fallbackWebUrl = "https://github.com/d4rken-org/porter/releases",
            iconRes = R.drawable.ic_porter_logo,
            // The same release ships porter-compat-*.apk — the Compatibility companion, which is
            // NOT a supported artifact for UFM and is not the manager app. Assets come back
            // compat-first, so a plain "first .apk" match downloads the wrong application.
            apkAssetPattern = Regex("""^porter-v.*\.apk$""", RegexOption.IGNORE_CASE)
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

    data class AppReleaseInfo(
        val app: ElevatedApp,
        val version: String,
        val tagName: String,
        val htmlUrl: String,
        val body: String,
        val apkUrl: String,
        val apkSize: Long,
        val apkName: String,
        /** The resolved release is a pre-release — the UI must say so (FR-30). */
        val isPrerelease: Boolean = false
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

    /**
     * Resolves the newest usable release for [repo].
     *
     * Queries the releases LIST, not `/releases/latest`. GitHub's `latest` endpoint skips
     * pre-releases entirely, so it 404s for any repository whose releases are all pre-releases —
     * which is Porter's permanent situation (CR-03) and would make its download fail outright.
     *
     * The URL is the only thing this function decides — *which* of the returned releases is
     * offered, and which of its assets is this app's APK, is [parseRelease]'s job, so that the part
     * the user actually sees can be tested without a network.
     */
    private fun fetchFromGitHubRepo(app: ElevatedApp, repo: String): AppReleaseInfo? {
        val url = "https://api.github.com/repos/$repo/releases?per_page=10"
        return try {
            val headers = mapOf(
                "User-Agent" to "UltimateFileManager-Android/${BuildConfig.VERSION_NAME}",
                "Accept" to "application/vnd.github.v3+json"
            )
            val response = UfmHttpClient.getSync(url, headers = headers, timeoutSec = 15)
            if (!response.isSuccessful) return null
            parseRelease(app, response.bodyString)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Picks the release to offer out of a GitHub `/releases` LIST payload, and this app's own APK
     * out of that release's assets.
     *
     * Split out of [fetchFromGitHubRepo] so every branch is reachable from a unit test without a
     * network: the HTTP concerns (status code, timeout, headers) stay in the caller, and the part
     * that actually decides *what the user is offered* is a pure function of the response body.
     *
     * Selection order: newest stable (non-prerelease, non-draft), else newest pre-release, else
     * nothing. Drafts are never offered — they are unpublished and their asset URLs 404 for anyone
     * who is not the maintainer.
     *
     * The result carries [AppReleaseInfo.isPrerelease] so the caller can label a pre-release rather
     * than passing it off as stable (FR-29/FR-30) — reaching a pre-release at all is the point,
     * since Porter has yet to cut a stable one (CR-03), and a fallback that silently dropped them
     * would leave Porter permanently undownloadable.
     */
    internal fun parseRelease(app: ElevatedApp, body: String): AppReleaseInfo? {
        // GitHub answers a 404/403 with a JSON *object* ({"message":"Not Found"}), not an array, and
        // an empty or truncated body throws as well. Both mean "no release to offer", not "crash".
        val releases = try {
            JSONArray(body)
        } catch (e: Exception) {
            return null
        }

        val candidates = (0 until releases.length())
            .mapNotNull { releases.optJSONObject(it) }
            .filter { !it.optBoolean("draft", false) }
        val json = candidates.firstOrNull { !it.optBoolean("prerelease", false) }
            ?: candidates.firstOrNull()
            ?: return null

        val isPrerelease = json.optBoolean("prerelease", false)
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

                // Porter's releases carry BOTH porter-compat-*.apk and porter-*.apk, and come
                // back compat-first — a plain "first .apk" match takes the wrong app.
                val matches = app.apkAssetPattern?.matches(name)
                    ?: name.endsWith(".apk", ignoreCase = true)
                if (matches) {
                    apkUrl = downloadUrl
                    apkSize = size
                    apkName = name
                    break
                }
            }
        }

        if (apkUrl.isBlank()) return null
        if (apkName.isBlank()) apkName = "${app.displayName.lowercase()}-$tagName.apk"

        return AppReleaseInfo(
            app = app,
            version = version,
            tagName = tagName,
            htmlUrl = htmlUrl,
            body = releaseNotes,
            apkUrl = apkUrl,
            apkSize = apkSize,
            apkName = apkName,
            isPrerelease = isPrerelease
        )
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
        // FR-30: a pre-release has to say so. `isPrerelease` is resolved in parseRelease() and this
        // is the only place that consumes it — without this the flag is inert and the dialog offers
        // a beta as though it were a stable build. That is not an edge case here: Porter has no
        // stable release at all (CR-03), so every Porter download through this screen is one.
        // The badge is an existing translated string; only the separator is literal.
        txtTitle?.text = if (release.isPrerelease) {
            "${release.app.displayName} ${release.version} · " +
                activity.getString(R.string.elevated_prerelease_badge)
        } else {
            "${release.app.displayName} ${release.version}"
        }

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
            PackageInstallerHelper.openInstallPermissionSettings(activity)
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
