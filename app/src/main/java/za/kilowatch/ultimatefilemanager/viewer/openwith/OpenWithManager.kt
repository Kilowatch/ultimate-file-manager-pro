package za.kilowatch.ultimatefilemanager.viewer.openwith

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import za.kilowatch.ultimatefilemanager.storage.SafFile
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.util.MimeTypeHelper
import za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager
import za.kilowatch.ultimatefilemanager.viewer.MediaIntentHelper
import java.io.File

/**
 * Central manager and single entry point for all "Open With" and "External App" actions in UFM.
 * Checks for remembered defaults for direct launching, or displays the custom OpenWithBottomSheet.
 */
object OpenWithManager {

    private const val TAG = "OpenWithManager"

    /**
     * Shows the custom Open With experience or launches the preferred default app directly.
     *
     * @param context Host Context or Activity
     * @param file Local or SAF File (optional if [uri] is provided)
     * @param uri Direct content:// URI (optional if [file] is provided)
     * @param mimeType MIME type (optional, resolved from extension if omitted)
     * @param fileName Display file name
     * @param isNetwork Whether the file represents a network / cloud item
     * @param forceChooser If true, bypasses any remembered default and always shows the chooser
     * @param fragmentManager Optional explicit FragmentManager when called from a Fragment
     */
    fun show(
        context: Context,
        file: File? = null,
        uri: Uri? = null,
        mimeType: String? = null,
        fileName: String? = null,
        isNetwork: Boolean = false,
        forceChooser: Boolean = false,
        fragmentManager: FragmentManager? = null
    ) {
        val resolvedFileName = fileName ?: file?.name ?: uri?.lastPathSegment ?: "file"
        val ext = resolvedFileName.substringAfterLast('.', "").lowercase()

        // 1. Resolve canonical MIME type
        val resolvedMime = MediaIntentHelper.normalizeMediaMimeType(
            ext,
            mimeType ?: MimeTypeHelper.getOrFallback(ext)
        )

        // 2. Resolve content:// URI
        val resolvedUri: Uri = uri ?: run {
            if (file == null) return
            try {
                if (SafTreeManager.isSaf(context, file)) {
                    (file as? SafFile)?.documentUri
                        ?: SafTreeManager.getDocumentUriForPath(context, file.absolutePath)
                        ?: Uri.fromFile(file)
                } else {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
            } catch (_: Exception) {
                Uri.fromFile(file)
            }
        }

        // 3. Check for remembered default package (if not explicitly forced to show chooser)
        if (!forceChooser && ext.isNotBlank()) {
            val preferredPkg = DefaultOpenManager.getPreferredPackage(context, ext, isNetwork)
            if (!preferredPkg.isNullOrBlank()) {
                val pm = context.packageManager
                val isInstalled = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(preferredPkg, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(preferredPkg, 0)
                    }
                    true
                } catch (_: Exception) {
                    false
                }

                if (isInstalled) {
                    val directIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(resolvedUri, resolvedMime)
                        setPackage(preferredPkg)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }

                    val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.queryIntentActivities(directIntent, PackageManager.ResolveInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(directIntent, 0)
                    }

                    val validTarget = resolves.firstOrNull { it.activityInfo != null }
                    if (validTarget != null) {
                        val ai = validTarget.activityInfo
                        val target = OpenWithTarget(
                            id = "${ai.packageName}/${ai.name}",
                            label = runCatching { validTarget.loadLabel(pm).toString() }.getOrNull() ?: ai.packageName,
                            packageName = ai.packageName,
                            activityName = ai.name,
                            isPreferred = true
                        )

                        OpenWithLauncher.launch(
                            context = context,
                            target = target,
                            uri = resolvedUri,
                            mimeType = resolvedMime,
                            fileName = resolvedFileName,
                            file = file,
                            isNetwork = isNetwork,
                            remember = false,
                            extension = ext
                        )
                        return
                    }
                } else {
                    // App was uninstalled; clear stale preference
                    DefaultOpenManager.clearPreferredPackage(context, ext, isNetwork)
                }
            }
        }

        // 4. Resolve FragmentManager to show OpenWithBottomSheet
        val fm = fragmentManager ?: findFragmentManager(context)
        if (fm != null && !fm.isStateSaved) {
            val sheet = OpenWithBottomSheet.newInstance(
                uri = resolvedUri,
                mimeType = resolvedMime,
                fileName = resolvedFileName,
                file = file,
                isNetwork = isNetwork,
                extension = ext
            )
            sheet.show(fm, OpenWithBottomSheet.TAG)
        } else {
            // Fallback if no FragmentManager available (e.g. non-Activity Context)
            OpenWithLauncher.launchSystemChooser(context, resolvedUri, resolvedMime, resolvedFileName, file)
        }
    }

    private fun findFragmentManager(context: Context): FragmentManager? {
        var current = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) {
                return current.supportFragmentManager
            }
            current = current.baseContext
        }
        return null
    }
}
