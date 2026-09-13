package za.kilowatch.ultimatefilemanager.indexing.recents

import android.content.Context
import android.os.Build
import android.os.Environment

enum class AccessTier {
    FULL,
    LIMITED
}

/**
 * Detects current storage access tier (Full Access vs Limited SAF Access).
 * Never cached across the app lifecycle — re-evaluated dynamically on demand.
 */
object AccessTierDetector {

    /**
     * Checks current permission tier.
     * On Android 11+ (API 30+), checks Environment.isExternalStorageManager().
     * On older Android versions (API < 30), legacy storage permission gives FULL access.
     */
    fun currentTier(context: Context): AccessTier {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) AccessTier.FULL else AccessTier.LIMITED
        } else {
            // Android 9/10: If READ_EXTERNAL_STORAGE is granted, it is FULL access
            val hasLegacy = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasLegacy) AccessTier.FULL else AccessTier.LIMITED
        }
    }
}
