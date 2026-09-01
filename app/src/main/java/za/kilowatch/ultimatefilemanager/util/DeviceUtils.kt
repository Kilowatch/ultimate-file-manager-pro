package za.kilowatch.ultimatefilemanager.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import za.kilowatch.ultimatefilemanager.R

/**
 * Utility for detecting device form factor (Mobile vs Android TV).
 */
object DeviceUtils {

    /**
     * Returns true if the current device is an Android TV or Fire TV.
     * Checks UiModeManager first; falls back to Amazon manufacturer check
     * for Fire TV Sticks that may not report UI_MODE_TYPE_TELEVISION correctly.
     */
    fun isTvDevice(context: Context?): Boolean {
        if (context == null) return false
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        // Fallback: some Amazon Fire TV Sticks report UI_MODE_TYPE_NORMAL
        val isAmazonTv = isAmazonDevice(context) &&
            (context.packageManager?.hasSystemFeature("android.hardware.touchscreen") == false)
        return isTvMode || isAmazonTv
    }

    /**
     * Returns true if running on an Amazon (Fire OS) device.
     * Used to disable Google Play–dependent features (Billing, Play Store URLs)
     * which do not work on Fire OS.
     */
    /**
     * Returns true if Picture-in-Picture mode is supported on this device.
     * PiP requires Android 8.0 (API 26) or higher.
     */
    fun isPiPSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    fun isAmazonDevice(context: Context?): Boolean {
        if (context == null) return false
        return Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ||
               (context.packageManager?.hasSystemFeature("amazon.hardware.fire_tv") == true)
    }

    /**
     * Resolves the Online Storages subtitle dynamically.
     * On FOSS builds, it loads 'online_storages_subtitle_foss' (if present) to bypass
     * localized translations in src/main. Falls back to the standard subtitle otherwise.
     */
    fun getOnlineStoragesSubtitle(context: Context): String {
        val id = context.resources.getIdentifier("online_storages_subtitle_foss", "string", context.packageName)
        return if (id != 0) context.getString(id) else context.getString(R.string.online_storages_subtitle)
    }
}
