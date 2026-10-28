package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.PairingManager
import za.kilowatch.ultimatefilemanager.network.ShareType

/**
 * Manages the user preference for how files selected from SAF Document Providers
 * (like Proton Drive, Google Drive, DS file) should be handled.
 *
 * Options:
 * - [ACTION_ALWAYS_ASK]: Prompt user every time (with a "Remember" checkbox)
 * - [ACTION_OPEN_VIEWER]: Directly open in UFM's built-in viewer/editor
 * - [ACTION_COPY_LOCAL]: Directly copy/download into device local Downloads storage or configured destination
 */
object SafFileActionPreferenceManager {

    private const val PREFS_NAME = "saf_file_action_prefs"
    private const val KEY_ACTION = "saf_file_action"
    private const val KEY_COPY_DESTINATION_PATH = "saf_copy_destination_path"

    const val ACTION_ALWAYS_ASK = "always_ask"
    const val ACTION_OPEN_VIEWER = "open_viewer"
    const val ACTION_COPY_LOCAL = "copy_local"

    fun isNetworkOrRemoteUri(path: String): Boolean {
        return path.startsWith("smb://") || path.startsWith("ftp://") ||
               path.startsWith("sftp://") || path.startsWith("gdrive://") ||
               path.startsWith("onedrive://") || path.startsWith("dropbox://") ||
               path.startsWith("webdav://") || path.startsWith("s3://") ||
               path.startsWith("idrive-e2://") || path.startsWith("dlna://") ||
               path.startsWith("nfs://") || path.startsWith("tv://")
    }

    fun getAction(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTION, ACTION_ALWAYS_ASK) ?: ACTION_ALWAYS_ASK
    }

    fun setAction(context: Context, action: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTION, action)
            .apply()
    }

    fun getSubtitleRes(action: String): Int {
        return when (action) {
            ACTION_OPEN_VIEWER -> R.string.settings_saf_file_action_subtitle_open_viewer
            ACTION_COPY_LOCAL  -> R.string.settings_saf_file_action_subtitle_copy_local
            else               -> R.string.settings_saf_file_action_subtitle_always_ask
        }
    }

    fun getDefaultCopyDestinationPath(): String {
        return android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    fun getCopyDestinationPath(context: Context): String {
        val defaultPath = getDefaultCopyDestinationPath()
        val path = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COPY_DESTINATION_PATH, defaultPath) ?: defaultPath
        if (isNetworkOrRemoteUri(path)) return path
        val file = java.io.File(path)
        return if (file.exists() && file.isDirectory) path else defaultPath
    }

    fun setCopyDestinationPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COPY_DESTINATION_PATH, path)
            .apply()
    }

    fun resetCopyDestinationPath(context: Context) {
        setCopyDestinationPath(context, getDefaultCopyDestinationPath())
    }

    fun getCopyDestinationDisplayName(context: Context): String {
        return getDestinationDisplayNameForPath(context, getCopyDestinationPath(context))
    }

    fun getDestinationDisplayNameForPath(context: Context, path: String): String {
        val defaultPath = getDefaultCopyDestinationPath()
        if (path == defaultPath || path == java.io.File(defaultPath).absolutePath) {
            return "Downloads"
        }
        if (isNetworkOrRemoteUri(path)) {
            val schemeEnd = path.indexOf("://")
            val scheme = path.substring(0, schemeEnd).uppercase()
            val afterScheme = path.substring(schemeEnd + 3)
            val shareId = afterScheme.substringBefore('/')
            val subPath = afterScheme.substringAfter('/', "")

            val share: NetworkShare? = NetworkShareRepository.getInstance(context).getById(shareId)
                ?: OnlineStorageRepository.getInstance(context).getById(shareId)?.toNetworkShare()
                ?: PairingManager.getInstance(context).getPairedDevice(shareId)?.let { dev ->
                    NetworkShare(
                        id = dev.deviceId, name = dev.name,
                        type = ShareType.TV,
                        host = dev.lastIp, port = dev.lastPort, readOnly = false
                    )
                }

            val baseName = share?.name ?: shareId
            return if (subPath.isNotEmpty()) "[$scheme] $baseName/$subPath" else "[$scheme] $baseName"
        }
        val file = java.io.File(path)
        return file.name.ifEmpty { path }
    }
}
