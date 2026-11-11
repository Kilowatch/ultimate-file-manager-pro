package za.kilowatch.ultimatefilemanager.viewer.openwith

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.viewer.MediaIntentHelper
import java.io.File

/**
 * Handles explicit intent creation, URI permission grants, media metadata & subtitle extras injection,
 * preference persistence, and safe handoff to external applications.
 */
object OpenWithLauncher {

    private const val TAG = "OpenWithLauncher"

    private fun isOwnedAuthority(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val authority = uri.authority ?: return false
        val pkg = context.packageName
        return authority == "$pkg.fileprovider" || authority == "$pkg.documents"
    }

    /**
     * Launches the chosen target application with full permission grants and extras.
     */
    fun launch(
        context: Context,
        target: OpenWithTarget,
        uri: Uri,
        mimeType: String,
        fileName: String,
        file: File? = null,
        isNetwork: Boolean = false,
        remember: Boolean = false,
        extension: String = ""
    ) {
        val cleanExt = if (extension.isNotBlank()) {
            extension.lowercase().trimStart('.')
        } else {
            fileName.substringAfterLast('.', "").lowercase()
        }

        // 1. Fallback: User explicitly chose "More options (System chooser)"
        if (target.isSystemChooserFallback) {
            launchSystemChooser(context, uri, mimeType, fileName, file)
            return
        }

        // Build base intent flags. Persistable permission is only valid for URIs owned by UFM
        // (FileProvider/DocumentsProvider). MediaStore and external URIs reject persistable grants with SecurityException.
        val isOwned = isOwnedAuthority(context, uri)
        val baseFlags = if (isOwned) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }

        // 2. Build explicit ACTION_VIEW intent targeting ComponentName
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(target.packageName, target.activityName)
            setDataAndType(uri, mimeType)
            addFlags(baseFlags)

            // Set ClipData to ensure content URI permissions pass through
            clipData = ClipData.newRawUri(fileName, uri)

            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        // 3. Grant URI permission explicitly to the target package for content URIs
        if (uri.scheme == "content") {
            try {
                context.grantUriPermission(target.packageName, uri, baseFlags)
            } catch (_: Exception) {
                try {
                    context.grantUriPermission(
                        target.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Non-fatal if grantUriPermission is unsupported for this URI authority
                }
            }
        }

        // 4. Attach media metadata and local/network subtitle extras
        val isVideo = FileViewerRouter.isVideo(cleanExt)
        val isAudio = FileViewerRouter.isAudio(cleanExt)
        if (isVideo || isAudio) {
            val fileSize = file?.length() ?: -1L
            MediaIntentHelper.attachMediaExtras(intent, fileName, fileSize, mimeType)

            if (file != null) {
                MediaIntentHelper.attachLocalSubtitles(intent, context, file)
            }
        }

        // 5. Pluggable player-specific extras
        if (target.packageName.startsWith("com.mxtech.videoplayer")) {
            // MX Player Free & Pro API extras
            intent.putExtra("return_result", false)
            intent.putExtra("title", fileName)
        }

        // 6. Persist preferred default package if user requested "Always"
        if (remember && cleanExt.isNotBlank()) {
            DefaultOpenManager.setDefaultAction(context, cleanExt, isNetwork, DefaultOpenManager.Action.EXTERNAL)
            DefaultOpenManager.setPreferredPackage(context, cleanExt, isNetwork, target.packageName)
        }

        // 7. Execute launch with graceful exception fallback
        try {
            context.startActivity(intent)
        } catch (e: SecurityException) {
            // If persistable permission flag was rejected by the platform, retry without it
            try {
                intent.flags = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION.inv()
                context.startActivity(intent)
            } catch (e2: Exception) {
                val errorMsg = context.getString(R.string.open_with_launch_failed, target.label)
                Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                launchSystemChooser(context, uri, mimeType, fileName, file)
            }
        } catch (e: Exception) {
            val errorMsg = context.getString(R.string.open_with_launch_failed, target.label)
            Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
            launchSystemChooser(context, uri, mimeType, fileName, file)
        }
    }

    /**
     * Fallback dispatcher that opens the Android system chooser.
     */
    fun launchSystemChooser(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String,
        file: File? = null
    ) {
        val isOwned = isOwnedAuthority(context, uri)
        val baseFlags = if (isOwned) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(baseFlags)
            clipData = ClipData.newRawUri(fileName, uri)

            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val fileSize = file?.length() ?: -1L
        MediaIntentHelper.attachMediaExtras(viewIntent, fileName, fileSize, mimeType)
        if (file != null) {
            MediaIntentHelper.attachLocalSubtitles(viewIntent, context, file)
        }

        val chooser = Intent.createChooser(viewIntent, context.getString(R.string.open_with))
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(chooser)
        } catch (e: SecurityException) {
            try {
                viewIntent.flags = viewIntent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION.inv()
                val safeChooser = Intent.createChooser(viewIntent, context.getString(R.string.open_with)).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(safeChooser)
            } catch (_: Exception) {
                Toast.makeText(context, R.string.error_cannot_open_file, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(context, R.string.error_cannot_open_file, Toast.LENGTH_SHORT).show()
        }
    }
}
