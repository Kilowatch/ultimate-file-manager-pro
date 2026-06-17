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

        val backupDir = AutoBackupPrefs.getBackupDirectory()
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
}
