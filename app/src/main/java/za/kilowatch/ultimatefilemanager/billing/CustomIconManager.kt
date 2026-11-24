package za.kilowatch.ultimatefilemanager.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.io.FileOutputStream

object CustomIconManager {

    private const val PREFS_NAME = "ufm_badge_prefs"
    private const val KEY_IS_CUSTOM_ACTIVE = "is_custom_icon_active"
    const val CUSTOM_ICON_REQUIRED_POINTS = 10

    private const val FILE_NAME = "custom_app_icon.png"

    fun getCustomIconFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    fun hasCustomIcon(context: Context): Boolean {
        val file = getCustomIconFile(context)
        return file.exists() && file.length() > 0
    }

    fun isCustomIconActive(context: Context): Boolean {
        if (!hasCustomIcon(context)) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_CUSTOM_ACTIVE, false)
    }

    fun setCustomIconActive(context: Context, active: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_CUSTOM_ACTIVE, active).apply()
        if (active) {
            // Unset selected milestone badge points so custom icon is uniquely active
            prefs.edit().putInt("selected_badge_points", 0).apply()
            try {
                val clazz = Class.forName("za.kilowatch.ultimatefilemanager.billing.BadgeIconManager")
                val method = clazz.getMethod("setSelectedBadge", Context::class.java, java.lang.Integer::class.java)
                method.invoke(null, context, null)
            } catch (_: Throwable) {}
        }
    }

    fun isUnlocked(context: Context): Boolean {
        return try {
            val clazz = Class.forName("za.kilowatch.ultimatefilemanager.billing.BadgeIconManager")
            val forceUnlockedField = clazz.getField("DEBUG_FORCE_ALL_UNLOCKED")
            if (forceUnlockedField.getBoolean(null)) return true
            val instance = clazz.getField("INSTANCE").get(null)
            val method = clazz.getMethod("getTotalTippedPoints", Context::class.java)
            val points = method.invoke(instance, context) as? Int ?: 0
            points >= CUSTOM_ICON_REQUIRED_POINTS
        } catch (_: Throwable) {
            (LoyaltyPrefs.getTotalTipped(context) * 10) >= CUSTOM_ICON_REQUIRED_POINTS
        }
    }

    fun getCustomIconBitmap(context: Context): Bitmap? {
        val file = getCustomIconFile(context)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resizes and crops the source image to a 1:1 square, scales it to 512x512,
     * and saves it to internal app storage so it remains permanently available.
     */
    fun processAndSaveCustomIcon(context: Context, sourcePath: String): Boolean {
        val file = File(sourcePath)
        if (!file.exists() || !file.canRead()) return false

        val ext = file.extension.lowercase()
        val supported = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif")
        if (ext !in supported) return false

        return try {
            // 1. Decode bounds to compute subsampling
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourcePath, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return false

            var sampleSize = 1
            while (boundsOptions.outWidth / (sampleSize * 2) >= 512 && boundsOptions.outHeight / (sampleSize * 2) >= 512) {
                sampleSize *= 2
            }

            // 2. Decode sampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(sourcePath, decodeOptions) ?: return false

            // 3. Center crop to 1:1 square
            val minDim = Math.min(decoded.width, decoded.height)
            val xOffset = (decoded.width - minDim) / 2
            val yOffset = (decoded.height - minDim) / 2
            val cropped = Bitmap.createBitmap(decoded, xOffset, yOffset, minDim, minDim)
            if (cropped != decoded) decoded.recycle()

            // 4. Scale to 512x512 standard
            val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
            if (scaled != cropped) cropped.recycle()

            // 5. Save to internal folder
            val destFile = getCustomIconFile(context)
            val tempFile = File(context.filesDir, "custom_app_icon_tmp.png")
            FileOutputStream(tempFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            scaled.recycle()

            if (destFile.exists()) destFile.delete()
            tempFile.renameTo(destFile)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pins a launcher shortcut with the custom bitmap on the user's home screen.
     */
    fun pinHomeScreenShortcut(context: Context, bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
                    launchIntent.action = Intent.ACTION_MAIN
                    launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)

                    val shortcut = ShortcutInfo.Builder(context, "custom_app_icon_shortcut")
                        .setShortLabel(context.getString(R.string.app_name))
                        .setIcon(Icon.createWithBitmap(bitmap))
                        .setIntent(launchIntent)
                        .build()

                    shortcutManager.requestPinShortcut(shortcut, null)
                }
            } catch (_: Exception) {
                // Ignore if pinning is not permitted by launcher
            }
        }
    }
}
