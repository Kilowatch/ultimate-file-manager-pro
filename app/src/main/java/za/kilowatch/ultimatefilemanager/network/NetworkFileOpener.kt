package za.kilowatch.ultimatefilemanager.network

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.NetworkOpenCachePreferenceManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.MimeTypeHelper
import za.kilowatch.ultimatefilemanager.util.ThemeColors
import za.kilowatch.ultimatefilemanager.util.TransferService
import za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge
import za.kilowatch.ultimatefilemanager.viewer.SlideShowActivity
import za.kilowatch.ultimatefilemanager.viewer.TextViewerActivity
import za.kilowatch.ultimatefilemanager.viewer.UFMPlayerActivity
import java.io.File
import java.io.FileInputStream
import java.util.ArrayList
import java.util.HashMap

/**
 * Handles downloading, streaming, and launching viewers or external apps for [NetworkFile] items
 * across fragments and activities (e.g. [NetworkBrowserFragment], [za.kilowatch.ultimatefilemanager.tabs.TabbedBrowserActivity]).
 */
object NetworkFileOpener {

    fun openFile(
        activity: Activity,
        scope: CoroutineScope,
        share: NetworkShare,
        file: NetworkFile,
        currentFiles: List<NetworkFile>,
        sortedFiles: List<NetworkFile> = currentFiles,
        snackAnchorView: View? = null,
        onShowSnackbar: ((String) -> Unit)? = null
    ) {
        val isStreamingSupported = share.type == ShareType.GOOGLE_DRIVE ||
                share.type == ShareType.ONEDRIVE ||
                share.type == ShareType.SMB ||
                share.type == ShareType.FTP ||
                share.type == ShareType.SFTP ||
                share.type == ShareType.SCP ||
                share.type == ShareType.NFS ||
                share.type == ShareType.DLNA ||
                share.type == ShareType.WEBDAV
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val isMedia = FileViewerRouter.isAudio(ext) || FileViewerRouter.isVideo(ext)

        if (isStreamingSupported && isMedia) {
            val defaultAction = DefaultOpenManager.getDefaultAction(activity, ext, isNetwork = true)
            if (defaultAction != DefaultOpenManager.Action.ASK) {
                when (defaultAction) {
                    DefaultOpenManager.Action.INTERNAL -> {
                        openNetworkFileDirectly(
                            activity = activity,
                            scope = scope,
                            share = share,
                            file = file,
                            currentFiles = currentFiles,
                            forceExternal = false,
                            snackAnchorView = snackAnchorView,
                            onShowSnackbar = onShowSnackbar
                        )
                        return
                    }
                    DefaultOpenManager.Action.SLIDESHOW -> {
                        val filesToConsider = currentFiles.filter { !it.isDirectory && !it.name.startsWith(".") }
                            .filter { f ->
                                val e = f.name.substringAfterLast('.', "").lowercase()
                                e in FileViewerRouter.IMAGE_EXTENSIONS || e in FileViewerRouter.VIDEO_EXTENSIONS
                            }
                        startSlideShow(activity, share, file, sortedFiles, filesToConsider)
                        return
                    }
                    DefaultOpenManager.Action.PLAYER -> {
                        startUfmPlayer(activity, share, file, sortedFiles, currentFiles)
                        return
                    }
                    DefaultOpenManager.Action.EXTERNAL -> {
                        val preferred = DefaultOpenManager.getPreferredPackage(activity, ext, isNetwork = true)
                        openNetworkFileDirectly(
                            activity = activity,
                            scope = scope,
                            share = share,
                            file = file,
                            currentFiles = currentFiles,
                            forceExternal = true,
                            preferredPackage = preferred,
                            remember = preferred == null,
                            extension = ext,
                            snackAnchorView = snackAnchorView,
                            onShowSnackbar = onShowSnackbar
                        )
                        return
                    }
                    else -> {}
                }
            }
            showNetworkMediaChoiceDialog(
                activity = activity,
                scope = scope,
                share = share,
                file = file,
                currentFiles = currentFiles,
                sortedFiles = sortedFiles,
                snackAnchorView = snackAnchorView,
                onShowSnackbar = onShowSnackbar
            )
            return
        }

        openNetworkFileDirectly(
            activity = activity,
            scope = scope,
            share = share,
            file = file,
            currentFiles = currentFiles,
            forceExternal = false,
            snackAnchorView = snackAnchorView,
            onShowSnackbar = onShowSnackbar
        )
    }

    private fun showNetworkMediaChoiceDialog(
        activity: Activity,
        scope: CoroutineScope,
        share: NetworkShare,
        file: NetworkFile,
        currentFiles: List<NetworkFile>,
        sortedFiles: List<NetworkFile>,
        snackAnchorView: View?,
        onShowSnackbar: ((String) -> Unit)?
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dp = { px: Int -> (px * activity.resources.displayMetrics.density).toInt() }
        val isTv = DeviceUtils.isTvDevice(activity)
        val bgColor = activity.getColor(R.color.tv_dialog_background)
        val textPrimary = activity.getColor(R.color.tv_text_primary)
        val textSecondary = activity.getColor(R.color.tv_text_secondary)

        val dialogBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(bgColor)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = dialogBg
            setPadding(dp(24), dp(28), dp(24), dp(20))
        }

        val title = TextView(activity).apply {
            text = activity.getString(R.string.open_with_1)
            textSize = 22f
            setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })

        val subtitle = TextView(activity).apply {
            text = file.name
            textSize = 13f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        root.addView(subtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })

        val checkbox = CheckBox(activity).apply {
            text = activity.getString(R.string.remember_my_choice)
            setTextColor(textSecondary)
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(textSecondary)
        }
        root.addView(checkbox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })

        val dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(true)
            .create()

        fun createChoiceButton(
            label: String,
            desc: String,
            icon: String,
            gradientColors: IntArray,
            isFocusedYellow: Boolean,
            onClick: () -> Unit
        ): LinearLayout {
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                colors = gradientColors
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            val focusedBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(if (isFocusedYellow) Color.parseColor("#FBBF24") else activity.getColor(R.color.ufm_surface_variant))
            }
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                background = btnBg
                setPadding(dp(16), dp(16), dp(16), dp(16))
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            val iconView = TextView(activity).apply {
                text = icon
                textSize = 24f
            }
            val textContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
            }
            val labelView = TextView(activity).apply {
                text = label
                textSize = 16f
                setTextColor(textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val descView = TextView(activity).apply {
                text = desc
                textSize = 12f
                setTextColor(textSecondary)
            }
            textContainer.addView(labelView)
            textContainer.addView(descView)
            container.addView(iconView)
            container.addView(textContainer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            container.setOnFocusChangeListener { _, hasFocus ->
                container.background = if (hasFocus) focusedBg else btnBg
                if (hasFocus && isFocusedYellow) {
                    labelView.setTextColor(Color.BLACK)
                    descView.setTextColor(Color.DKGRAY)
                } else {
                    labelView.setTextColor(textPrimary)
                    descView.setTextColor(textSecondary)
                }
            }
            return container
        }

        val ext = file.name.substringAfterLast('.', "").lowercase()
        val isVideo = FileViewerRouter.isVideo(ext)
        val isAudio = FileViewerRouter.isAudio(ext)

        // UFM Viewer
        if (!isVideo && !isAudio) {
            root.addView(createChoiceButton(
                label = activity.getString(R.string.ufm_viewer),
                desc = activity.getString(R.string.open_with_builtin_viewer),
                icon = "📂",
                gradientColors = intArrayOf(ThemeColors.primary(activity), Color.parseColor("#0369A1")),
                isFocusedYellow = isTv
            ) {
                if (checkbox.isChecked) {
                    DefaultOpenManager.setDefaultAction(activity, ext, true, DefaultOpenManager.Action.INTERNAL)
                }
                dialog.dismiss()
                openNetworkFileDirectly(
                    activity = activity,
                    scope = scope,
                    share = share,
                    file = file,
                    currentFiles = currentFiles,
                    forceExternal = false,
                    snackAnchorView = snackAnchorView,
                    onShowSnackbar = onShowSnackbar
                )
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }

        // UFM Media Player (STREAMING)
        if (isVideo || isAudio) {
            root.addView(createChoiceButton(
                label = activity.getString(R.string.ufm_media_player),
                desc = activity.getString(R.string.ufm_media_player_stream_desc),
                icon = "▶️",
                gradientColors = intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#059669")),
                isFocusedYellow = isTv
            ) {
                if (checkbox.isChecked) {
                    DefaultOpenManager.setDefaultAction(activity, ext, true, DefaultOpenManager.Action.PLAYER)
                }
                dialog.dismiss()
                startUfmPlayer(activity, share, file, sortedFiles, currentFiles)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }

        // External App
        root.addView(createChoiceButton(
            label = activity.getString(R.string.external_app),
            desc = activity.getString(R.string.choose_another_app_to_open),
            icon = "🔗",
            gradientColors = intArrayOf(bgColor, bgColor),
            isFocusedYellow = isTv
        ) {
            val remember = checkbox.isChecked
            if (remember) {
                DefaultOpenManager.setDefaultAction(activity, ext, true, DefaultOpenManager.Action.EXTERNAL)
            }
            dialog.dismiss()
            openNetworkFileDirectly(
                activity = activity,
                scope = scope,
                share = share,
                file = file,
                currentFiles = currentFiles,
                forceExternal = true,
                preferredPackage = null,
                remember = remember,
                extension = ext,
                snackAnchorView = snackAnchorView,
                onShowSnackbar = onShowSnackbar
            )
        })

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun startUfmPlayer(
        activity: Activity,
        share: NetworkShare,
        file: NetworkFile,
        sortedFiles: List<NetworkFile>,
        currentFiles: List<NetworkFile>
    ) {
        val playlist = sortedFiles.filter {
            val x = it.name.substringAfterLast('.', "").lowercase()
            FileViewerRouter.isAudio(x) || FileViewerRouter.isVideo(x)
        }.map { it.path }

        val sizesMap = currentFiles.associate { it.path to it.size }

        val intent = Intent(activity, UFMPlayerActivity::class.java).apply {
            putExtra("shareId", share.id)
            putExtra(NetworkBrowserActivity.EXTRA_REMOTE_PATH, share.remotePath)
            putExtra("shareHost", share.host)
            putExtra("shareUsername", share.username)
            putExtra("shareName", share.name)
            putExtra("provider", share.type.name)
            putExtra("isServerMode", share.isServerMode)
            putExtra("initialPath", file.path)
            putExtra("initialSize", file.size)
            putExtra("sizesMap", HashMap(sizesMap))
            putStringArrayListExtra("playlist", ArrayList(playlist))
        }
        activity.startActivity(intent)
    }

    private fun startSlideShow(
        activity: Activity,
        share: NetworkShare,
        file: NetworkFile,
        sortedFiles: List<NetworkFile>,
        filesToConsider: List<NetworkFile>
    ) {
        val toConsiderSet = filesToConsider.map { it.path }.toSet()
        val playlist = sortedFiles.filter { it.path in toConsiderSet }.map { it.path }
        val sizesMap = filesToConsider.associate { it.path to it.size }
        val intent = Intent(activity, SlideShowActivity::class.java).apply {
            putExtra("shareId", share.id)
            putExtra(NetworkBrowserActivity.EXTRA_REMOTE_PATH, share.remotePath)
            putExtra("shareHost", share.host)
            putExtra("shareName", share.name)
            putExtra("provider", share.type.name)
            putExtra("initialPath", file.path)
            putExtra("initialSize", file.size)
            putExtra("sizesMap", HashMap(sizesMap))
            putStringArrayListExtra("playlist", ArrayList(playlist))
        }
        activity.startActivity(intent)
    }

    private fun openNetworkFileDirectly(
        activity: Activity,
        scope: CoroutineScope,
        share: NetworkShare,
        file: NetworkFile,
        currentFiles: List<NetworkFile>,
        forceExternal: Boolean,
        preferredPackage: String? = null,
        remember: Boolean = false,
        extension: String = file.name.substringAfterLast('.', "").lowercase(),
        snackAnchorView: View? = null,
        onShowSnackbar: ((String) -> Unit)? = null
    ) {
        val capturedUploadShare = share
        val capturedUploadPath = file.path
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val isDotConfig = FileViewerRouter.isDotConfigFile(file.name)
        val saveTarget = File(activity.cacheDir, file.name)
        if (saveTarget.exists()) saveTarget.delete()

        if (!forceExternal && (ext in FileViewerRouter.TEXT_EXTENSIONS || ext in FileViewerRouter.DAT_EXTENSIONS || isDotConfig)) {
            NetworkSaveBridge.onFileSaved = { savedFile ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val fis = FileInputStream(savedFile)
                        fis.use { inp ->
                            when (capturedUploadShare.type) {
                                ShareType.SMB -> SmbShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.FTP -> FtpShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.SFTP, ShareType.SCP -> withContext(Dispatchers.IO) { SshShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) } }
                                ShareType.TV -> TvShareClient.uploadStream(capturedUploadShare, capturedUploadPath, inp, savedFile.length())
                                ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.DROPBOX -> DropboxShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.WEBDAV -> WebDavShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.NFS -> withContext(Dispatchers.IO) { NfsShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) } }
                                ShareType.DLNA -> throw UnsupportedOperationException()
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        } else {
            NetworkSaveBridge.onFileSaved = null
        }

        val anchor = snackAnchorView ?: activity.findViewById(android.R.id.content)
        val snack = anchor?.let {
            Snackbar.make(it, activity.getString(R.string.opening_filename, file.name), Snackbar.LENGTH_INDEFINITE).apply { show() }
        }

        scope.launch(Dispatchers.IO) {
            try {
                val isNetworkOpenCacheEnabled = NetworkOpenCachePreferenceManager.isEnabled(activity)
                val isInternalViewer = (FileViewerRouter.canOpenInternally(ext) || isDotConfig) && !forceExternal

                if (isNetworkOpenCacheEnabled || isInternalViewer) {
                    val cutoff = System.currentTimeMillis() - 30 * 60 * 1000L
                    activity.cacheDir.listFiles { f -> f.name.startsWith("ufm_open_") && f.lastModified() < cutoff }
                        ?.forEach { it.delete() }

                    val safeName = file.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                    val cacheFile = File(activity.cacheDir, "ufm_open_$safeName")

                    val inStream = when (share.type) {
                        ShareType.SMB -> SmbShareClient.openInputStream(share, file.path)
                        ShareType.FTP -> FtpShareClient.openInputStream(share, file.path)
                        ShareType.TV  -> TvShareClient.openInputStream(share, file.path)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, file.path)
                        ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, file.path).first
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, file.path).first
                        ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, file.path).first
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, file.path).first
                        ShareType.WEBDAV                      -> WebDavShareClient.openInputStream(share, file.path).first
                        ShareType.NFS                         -> NfsShareClient.openInputStream(share, file.path)
                        ShareType.DLNA                        -> DlnaShareClient.openInputStream(share, file.path)
                    }
                    inStream.use { inp -> cacheFile.outputStream().use { out -> inp.copyTo(out) } }

                    var mime = MimeTypeHelper.getOrFallback(ext)
                    if (mime == "application/octet-stream" || mime == "*/*") {
                        mime = when {
                            FileViewerRouter.isVideo(ext) -> "video/*"
                            FileViewerRouter.isAudio(ext) -> "audio/*"
                            ext in FileViewerRouter.IMAGE_EXTENSIONS -> "image/*"
                            ext == "pdf" -> "application/pdf"
                            else -> "*/*"
                        }
                    }

                    val uri = FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        cacheFile
                    )

                    withContext(Dispatchers.Main) {
                        snack?.dismiss()
                        if (activity.isFinishing || activity.isDestroyed) return@withContext

                        if (!forceExternal) {
                            val isTextViewable = ext in FileViewerRouter.TEXT_EXTENSIONS ||
                                    ext in FileViewerRouter.DAT_EXTENSIONS ||
                                    isDotConfig
                            if (isTextViewable) {
                                val intent = Intent(activity, TextViewerActivity::class.java).apply {
                                    putExtra(FileViewerRouter.EXTRA_FILE_PATH, cacheFile.absolutePath)
                                    putExtra(FileViewerRouter.EXTRA_FILE_NAME, file.name)
                                }
                                activity.startActivity(intent)
                                return@withContext
                            }
                            if (FileViewerRouter.openFile(activity, cacheFile, isNetwork = true)) return@withContext
                        }

                        val isExternalVideo = FileViewerRouter.isVideo(ext)
                        if (isExternalVideo && forceExternal) {
                            val subFiles = SubtitleIntentHelper.findNetworkSubtitles(file.name, currentFiles)
                            if (subFiles.isNotEmpty()) {
                                val cachedSubs = SubtitleIntentHelper.downloadSubtitlesToCache(
                                    activity.cacheDir, subFiles
                                ) { subPath ->
                                    when (share.type) {
                                        ShareType.SMB -> SmbShareClient.openInputStream(share, subPath)
                                        ShareType.FTP -> FtpShareClient.openInputStream(share, subPath)
                                        ShareType.TV  -> TvShareClient.openInputStream(share, subPath)
                                        ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, subPath)
                                        ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, subPath).first
                                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, subPath).first
                                        ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, subPath).first
                                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, subPath).first
                                        ShareType.WEBDAV -> WebDavShareClient.openInputStream(share, subPath).first
                                        ShareType.NFS -> NfsShareClient.openInputStream(share, subPath)
                                        ShareType.DLNA -> DlnaShareClient.openInputStream(share, subPath)
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    val externalIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        SubtitleIntentHelper.attachCachedSubtitleExtras(this, activity.packageName, activity, cachedSubs)
                                    }
                                    if (preferredPackage != null) {
                                        val pm = activity.packageManager
                                        val isInstalled = try { pm.getPackageInfo(preferredPackage, 0); true }
                                        catch (_: PackageManager.NameNotFoundException) { false }
                                        if (isInstalled) {
                                            val directIntent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, mime)
                                                setPackage(preferredPackage)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                SubtitleIntentHelper.attachCachedSubtitleExtras(this, activity.packageName, activity, cachedSubs)
                                            }
                                            val resolves = pm.queryIntentActivities(directIntent, PackageManager.MATCH_DEFAULT_ONLY)
                                            if (resolves.isNotEmpty()) {
                                                activity.startActivity(directIntent)
                                                return@withContext
                                            }
                                        }
                                        DefaultOpenManager.clearPreferredPackage(activity, extension, isNetwork = true)
                                    }
                                    try {
                                        val chooser = Intent.createChooser(externalIntent, activity.getString(R.string.open_with))
                                        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        activity.startActivity(chooser)
                                    } catch (_: Exception) {
                                        showError(activity, activity.getString(R.string.no_app_found_to_open_this_file_type), snackAnchorView, onShowSnackbar)
                                    }
                                }
                                return@withContext
                            }
                        }

                        // No subtitles (or not a video)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        if (preferredPackage != null) {
                            val pm = activity.packageManager
                            val isInstalled = try { pm.getPackageInfo(preferredPackage, 0); true }
                            catch (_: PackageManager.NameNotFoundException) { false }
                            if (isInstalled) {
                                val directIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mime)
                                    setPackage(preferredPackage)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                val resolves = pm.queryIntentActivities(directIntent, PackageManager.MATCH_DEFAULT_ONLY)
                                if (resolves.isNotEmpty()) {
                                    activity.startActivity(directIntent)
                                    return@withContext
                                }
                            }
                            DefaultOpenManager.clearPreferredPackage(activity, extension, isNetwork = true)
                        }

                        try {
                            val chooser = Intent.createChooser(intent, activity.getString(R.string.open_with))
                            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (remember) {
                                registerChosenComponentReceiver(activity, extension, chooser)
                            }
                            activity.startActivity(chooser)
                        } catch (_: Exception) {
                            showError(activity, activity.getString(R.string.no_app_found_to_open_this_file_type), snackAnchorView, onShowSnackbar)
                        }
                    }
                } else {
                    // Direct Stream — no local cache
                    var mime = MimeTypeHelper.getOrFallback(ext)
                    if (mime == "application/octet-stream" || mime == "*/*") {
                        mime = when {
                            FileViewerRouter.isVideo(ext) -> "video/*"
                            FileViewerRouter.isAudio(ext) -> "audio/*"
                            ext in FileViewerRouter.IMAGE_EXTENSIONS -> "image/*"
                            ext == "pdf" -> "application/pdf"
                            else -> "*/*"
                        }
                    }

                    val isMedia = FileViewerRouter.isVideo(ext) || FileViewerRouter.isAudio(ext)
                    val supportsProxy = isMedia && (share.type == ShareType.SMB ||
                            share.type == ShareType.FTP ||
                            share.type == ShareType.SFTP ||
                            share.type == ShareType.SCP ||
                            share.type == ShareType.GOOGLE_DRIVE ||
                            share.type == ShareType.ONEDRIVE ||
                            share.type == ShareType.DROPBOX ||
                            share.type == ShareType.AWS_S3 ||
                            share.type == ShareType.IDRIVE_E2 ||
                            share.type == ShareType.WEBDAV ||
                            share.type == ShareType.NFS)

                    if (supportsProxy) {
                        val proxyUrl = NetworkHttpProxyServer.register(share, file.path, mime, file.size)
                        val mediaTypeLabel = if (FileViewerRouter.isVideo(ext)) "video" else "audio"
                        // Released by the host Activity's onResume (TransferManager.endStream) when the player returns.
                        za.kilowatch.ultimatefilemanager.util.TransferManager.startStream(
                            "Streaming $mediaTypeLabel file", "Streaming ${file.name} to external player"
                        )

                        val isExternalVideo = FileViewerRouter.isVideo(ext)
                        val proxySubtitleUris: List<Uri> = if (isExternalVideo && forceExternal) {
                            SubtitleIntentHelper.findNetworkSubtitles(file.name, currentFiles).map { subFile ->
                                val subExt = subFile.name.substringAfterLast('.', "").lowercase()
                                val subMime = when (subExt) {
                                    "vtt" -> "text/vtt"
                                    "ass", "ssa" -> "text/x-ass"
                                    else -> "application/x-subrip"
                                }
                                val subProxyUrl = NetworkHttpProxyServer.register(share, subFile.path, subMime, subFile.size)
                                Uri.parse(subProxyUrl)
                            }
                        } else emptyList()

                        withContext(Dispatchers.Main) {
                            snack?.dismiss()
                            if (activity.isFinishing || activity.isDestroyed) return@withContext

                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(proxyUrl), mime)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (proxySubtitleUris.isNotEmpty()) {
                                    val subNames = currentFiles.filter { f ->
                                        val e = f.name.substringAfterLast('.', "").lowercase()
                                        e in SubtitleIntentHelper.SUBTITLE_EXTENSIONS &&
                                                f.name.substringBeforeLast('.').let { b ->
                                                    val vb = file.name.substringBeforeLast('.')
                                                    b.equals(vb, ignoreCase = true) || b.startsWith("$vb.", ignoreCase = true)
                                                }
                                    }.sortedBy { it.name.lowercase() }
                                    val names = subNames.map { it.name.substringBeforeLast('.') }
                                    val fnames = subNames.map { it.name }
                                    SubtitleIntentHelper.attachSubtitleExtras(this, proxySubtitleUris, names, fnames)
                                }
                            }

                            if (preferredPackage != null) {
                                val pm = activity.packageManager
                                val isInstalled = try { pm.getPackageInfo(preferredPackage, 0); true }
                                catch (_: PackageManager.NameNotFoundException) { false }
                                if (isInstalled) {
                                    val directIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(Uri.parse(proxyUrl), mime)
                                        setPackage(preferredPackage)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        if (proxySubtitleUris.isNotEmpty()) {
                                            val subNames = currentFiles.filter { f ->
                                                val e = f.name.substringAfterLast('.', "").lowercase()
                                                e in SubtitleIntentHelper.SUBTITLE_EXTENSIONS &&
                                                        f.name.substringBeforeLast('.').let { b ->
                                                            val vb = file.name.substringBeforeLast('.')
                                                            b.equals(vb, ignoreCase = true) || b.startsWith("$vb.", ignoreCase = true)
                                                        }
                                            }.sortedBy { it.name.lowercase() }
                                            val names = subNames.map { it.name.substringBeforeLast('.') }
                                            val fnames = subNames.map { it.name }
                                            SubtitleIntentHelper.attachSubtitleExtras(this, proxySubtitleUris, names, fnames)
                                        }
                                    }
                                    val resolves = pm.queryIntentActivities(directIntent, PackageManager.MATCH_DEFAULT_ONLY)
                                    if (resolves.isNotEmpty()) { activity.startActivity(directIntent); return@withContext }
                                }
                                DefaultOpenManager.clearPreferredPackage(activity, extension, isNetwork = true)
                            }

                            try {
                                val chooser = Intent.createChooser(intent, activity.getString(R.string.open_with))
                                if (remember) {
                                    registerChosenComponentReceiver(activity, extension, chooser)
                                }
                                activity.startActivity(chooser)
                            } catch (_: Exception) {
                                showError(activity, activity.getString(R.string.no_app_found_to_open_this_file_type), snackAnchorView, onShowSnackbar)
                            }
                        }
                    } else {
                        // Sequential pipe via DocumentsProvider
                        val isExternalVideo = FileViewerRouter.isVideo(ext)
                        val cachedSubsFtpTv: List<File> = if (isExternalVideo && forceExternal) {
                            val subFiles = SubtitleIntentHelper.findNetworkSubtitles(file.name, currentFiles)
                            SubtitleIntentHelper.downloadSubtitlesToCache(activity.cacheDir, subFiles) { subPath ->
                                when (share.type) {
                                    ShareType.FTP  -> FtpShareClient.openInputStream(share, subPath)
                                    ShareType.TV   -> TvShareClient.openInputStream(share, subPath)
                                    ShareType.DLNA -> DlnaShareClient.openInputStream(share, subPath)
                                    else -> throw UnsupportedOperationException("Subtitle download not supported for ${share.type}")
                                }
                            }
                        } else emptyList()

                        val cleanPath = file.path.removePrefix("/")
                        val docId = "${share.docIdPrefix}${cleanPath}"
                        val uri = DocumentsContract.buildDocumentUri(
                            "${activity.packageName}.documents",
                            docId
                        )

                        withContext(Dispatchers.Main) {
                            snack?.dismiss()
                            if (activity.isFinishing || activity.isDestroyed) return@withContext

                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (cachedSubsFtpTv.isNotEmpty()) {
                                    SubtitleIntentHelper.attachCachedSubtitleExtras(this, activity.packageName, activity, cachedSubsFtpTv)
                                }
                            }

                            if (preferredPackage != null) {
                                val pm = activity.packageManager
                                val isInstalled = try { pm.getPackageInfo(preferredPackage, 0); true }
                                catch (_: PackageManager.NameNotFoundException) { false }
                                if (isInstalled) {
                                    val directIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        setPackage(preferredPackage)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    val resolves = pm.queryIntentActivities(directIntent, PackageManager.MATCH_DEFAULT_ONLY)
                                    if (resolves.isNotEmpty()) { activity.startActivity(directIntent); return@withContext }
                                }
                                DefaultOpenManager.clearPreferredPackage(activity, extension, isNetwork = true)
                            }

                            try {
                                val chooser = Intent.createChooser(intent, activity.getString(R.string.open_with))
                                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                if (remember) {
                                    registerChosenComponentReceiver(activity, extension, chooser)
                                }
                                activity.startActivity(chooser)
                            } catch (_: Exception) {
                                showError(activity, activity.getString(R.string.no_app_found_to_open_this_file_type), snackAnchorView, onShowSnackbar)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkFileOpener", "openNetworkFile failed: ", e)
                withContext(Dispatchers.Main) {
                    snack?.dismiss()
                    val msg = activity.getString(R.string.failed_to_open_file_emessage, e.message ?: "")
                    showError(activity, msg, snackAnchorView, onShowSnackbar)
                }
            }
        }
    }

    private fun registerChosenComponentReceiver(
        activity: Activity,
        extension: String,
        chooser: Intent
    ) {
        val callbackAction = "${activity.packageName}.CHOSEN_NET_APP_$extension"
        val callbackIntent = Intent(callbackAction).apply { setPackage(activity.packageName) }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val pi = android.app.PendingIntent.getBroadcast(activity, extension.hashCode(), callbackIntent, piFlags)
        val recv = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                @Suppress("DEPRECATION")
                val component = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)
                if (component != null) {
                    DefaultOpenManager.setPreferredPackage(
                        ctx, extension, isNetwork = true, component.packageName
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(recv, IntentFilter(callbackAction), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(recv, IntentFilter(callbackAction))
        }
        chooser.putExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, pi.intentSender)
    }

    private fun showError(
        activity: Activity,
        message: String,
        snackAnchorView: View?,
        onShowSnackbar: ((String) -> Unit)?
    ) {
        if (onShowSnackbar != null) {
            onShowSnackbar(message)
        } else if (snackAnchorView != null) {
            Snackbar.make(snackAnchorView, message, Snackbar.LENGTH_LONG).show()
        } else {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
