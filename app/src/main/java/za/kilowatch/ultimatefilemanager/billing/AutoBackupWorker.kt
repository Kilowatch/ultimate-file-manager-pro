package za.kilowatch.ultimatefilemanager.billing

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.settings.SettingsBackupManager
import za.kilowatch.ultimatefilemanager.settings.ThemePackManager
import za.kilowatch.ultimatefilemanager.storage.TileIconManager
import java.io.File

/**
 * [CoroutineWorker] that performs an auto-backup of settings configuration
 * and/or icon theme to [Documents/UFM/][AutoBackupPrefs.getBackupDirectory].
 *
 * Respects the user's password/plain preference stored in [AutoBackupPrefs].
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AutoBackupWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!AutoBackupPrefs.isEnabled(ctx)) {
            Log.d(TAG, "Auto-backup is disabled — skipping")
            return@withContext Result.success()
        }

        val password = if (AutoBackupPrefs.isUsePassword(ctx)) {
            AutoBackupPrefs.getPassword(ctx)
        } else {
            null
        }

        val backupDir = AutoBackupPrefs.getBackupDirectory(ctx)
        backupDir.mkdirs()

        var anyFailure = false

        // ── 1. Settings backup ─────────────────────────────────────────────
        if (AutoBackupPrefs.isBackupSettings(ctx)) {
            anyFailure = !exportSettings(ctx, backupDir, password) || anyFailure
            AutoBackupPrefs.clearSettingsDirty(ctx)
        }

        // ── 2. Theme backup ────────────────────────────────────────────────
        if (AutoBackupPrefs.isBackupTheme(ctx)) {
            anyFailure = !exportTheme(ctx, backupDir, password) || anyFailure
            AutoBackupPrefs.clearThemeDirty(ctx)
        }

        // ── 3. Network custom location upload ───────────────────────────
        if (!anyFailure && AutoBackupPrefs.getCustomLocationType(ctx) == "network") {
            anyFailure = !uploadToNetworkShare(ctx, backupDir, password) || anyFailure
        }

        if (anyFailure) Result.retry() else Result.success()
    }

    private fun exportSettings(
        context: Context,
        backupDir: File,
        password: String?
    ): Boolean {
        return try {
            val configFile = File(backupDir, "ufm_backup.UFMConfig")
            val allItems = SettingsBackupManager.getAvailableBackupItems(context)
            // Mark all items as selected
            val selected = allItems.map { it.copy(isSelected = true) }

            val success = SettingsBackupManager.performExport(context, selected, configFile, password)
            if (success) {
                Log.d(TAG, "Settings backup written to ${configFile.absolutePath}")
            } else {
                Log.e(TAG, "Settings backup failed")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Settings backup error", e)
            false
        }
    }

    private fun exportTheme(
        context: Context,
        backupDir: File,
        password: String?
    ): Boolean {
        return try {
            val themeFile = File(backupDir, "ufm_icons_theme.UFMTheme")

            // Collect all icon override IDs
            val iconIds = mutableSetOf<String>()

            // Icon customization overrides
            iconIds.addAll(IconCustomizationManager.getAll(context).keys)

            // Tile icon overrides (custom paths)
            iconIds.addAll(TileIconManager.getAllTileIcons(context).keys.map { "tile_$it" })

            // Tile icon overrides (builtin resources)
            iconIds.addAll(TileIconManager.getAllTileIconRes(context).keys.map { "tile_$it" })

            val success = ThemePackManager.performExport(context, iconIds, themeFile, password)
            if (success) {
                Log.d(TAG, "Theme backup written to ${themeFile.absolutePath}")
            } else {
                Log.e(TAG, "Theme backup failed")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Theme backup error", e)
            false
        }
    }

    /**
     * Uploads the local backup files to the configured network share.
     * Falls back gracefully if the share is unreachable.
     */
    private suspend fun uploadToNetworkShare(
        context: Context,
        localDir: File,
        password: String?
    ): Boolean {
        val shareId = AutoBackupPrefs.getCustomShareId(context)
        val netPath = AutoBackupPrefs.getCustomNetPath(context)
        if (shareId.isEmpty()) return false

        return try {
            val repo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(context)
            var share = repo.getById(shareId) ?: return false
            val innerPath = if (share.isServerMode && netPath.isNotEmpty()) {
                val segments = netPath.trimStart('/').split("/", limit = 2)
                share = share.copy(remotePath = "/${segments[0]}")
                segments.getOrElse(1) { "" }
            } else {
                netPath
            }

            val configFile = File(localDir, "ufm_backup.UFMConfig")
            val themeFile = File(localDir, "ufm_icons_theme.UFMTheme")

            val filesToUpload = mutableListOf<File>()
            if (configFile.exists()) filesToUpload.add(configFile)
            if (themeFile.exists()) filesToUpload.add(themeFile)

            for (file in filesToUpload) {
                val remoteName = file.name
                val remotePath = if (innerPath.isEmpty()) remoteName else "$innerPath/$remoteName"
                val inp = file.inputStream()
                try {
                    when (share.type) {
                        za.kilowatch.ultimatefilemanager.network.ShareType.TV ->
                            za.kilowatch.ultimatefilemanager.network.TvShareClient.uploadStream(share, remotePath, inp, file.length())
                        za.kilowatch.ultimatefilemanager.network.ShareType.SMB ->
                            za.kilowatch.ultimatefilemanager.network.SmbShareClient.openOutputStream(share, remotePath)
                                .use { out -> za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inp, out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.FTP ->
                            za.kilowatch.ultimatefilemanager.network.FtpShareClient.openOutputStream(share, remotePath)
                                .use { out -> za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inp, out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.SFTP,
                        za.kilowatch.ultimatefilemanager.network.ShareType.SCP ->
                            za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(share, remotePath)
                                .use { out -> za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inp, out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.NFS ->
                            za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(share, remotePath)
                                .use { out -> za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inp, out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV ->
                            za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(share, remotePath)
                                .use { out -> za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inp, out) }
                        else -> {
                            // Non-local share types (OneDrive, Google Drive, Dropbox, S3)
                            // are handled by separate cloud sync flows — skip for auto-backup
                            Log.d(TAG, "Skipping upload for ${share.type} — not supported for auto-backup")
                        }
                    }
                    Log.d(TAG, "Uploaded ${file.name} to network share: $remotePath")
                } finally {
                    inp.close()
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Network share upload failed — falling back to local backup", e)
            false // non-fatal — local files remain in Documents/UFM/
        }
    }
}
