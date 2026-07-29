package za.kilowatch.ultimatefilemanager.util

import android.app.WallpaperManager
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.io.FileInputStream

object WallpaperHelper {

    /**
     * Shows a confirmation dialog informing the user before applying wallpaper.
     */
    fun showConfirmDialog(
        context: Context,
        fileName: String,
        flag: Int,
        onConfirmed: () -> Unit
    ) {
        val isHome = flag == WallpaperManager.FLAG_SYSTEM
        val iconRes = if (isHome) R.drawable.ic_wallpaper_home else R.drawable.ic_wallpaper_lock
        val messageRes = if (isHome) R.string.wallpaper_confirm_home_message else R.string.wallpaper_confirm_lock_message

        MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setIcon(iconRes)
            .setTitle(R.string.wallpaper_confirm_title)
            .setMessage(context.getString(messageRes, fileName))
            .setPositiveButton(R.string.action_set_wallpaper) { _, _ ->
                onConfirmed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Sets the specified image file as wallpaper.
     * @param context Context
     * @param file Local image File
     * @param flag WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
     * @return true if wallpaper was successfully applied, false on failure
     */
    fun setWallpaper(context: Context, file: File, flag: Int): Boolean {
        if (!file.exists() || !file.isFile) return false
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            FileInputStream(file).use { inputStream ->
                wallpaperManager.setStream(inputStream, null, true, flag)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
