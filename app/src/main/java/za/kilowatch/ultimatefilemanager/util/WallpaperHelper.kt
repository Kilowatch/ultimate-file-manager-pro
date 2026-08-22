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
        val isTv = DeviceUtils.isTvDevice(context)

        val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
        val dialogView = android.view.LayoutInflater.from(context).inflate(layoutRes, null)
        val imgIcon = dialogView.findViewById<android.widget.ImageView>(R.id.imgDialogIcon)
        val txtTitle = dialogView.findViewById<android.widget.TextView>(R.id.txtDialogTitle)
        val txtMessage = dialogView.findViewById<android.widget.TextView>(R.id.txtDialogMessage)
        val btnPositive = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogNegative)

        imgIcon?.setImageResource(iconRes)
        txtTitle?.setText(R.string.wallpaper_confirm_title)
        txtMessage?.text = context.getString(messageRes, fileName)
        btnPositive?.setText(R.string.action_set_wallpaper)
        btnNegative?.visibility = android.view.View.VISIBLE
        btnNegative?.setText(android.R.string.cancel)

        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnPositive?.setOnClickListener {
            dialog.dismiss()
            onConfirmed()
        }

        btnNegative?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
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
