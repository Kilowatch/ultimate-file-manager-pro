package za.kilowatch.ultimatefilemanager.billing

import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView

object ActiveBadgeIconHelper {

    private const val PREFS_NAME = "ufm_badge_prefs"
    private const val KEY_SELECTED_POINTS = "selected_badge_points"

    /**
     * Returns the active badge app icon resource ID, or null if custom icon or default icon is active.
     */
    fun getActiveAppIcon(context: Context): Int? {
        if (CustomIconManager.isCustomIconActive(context)) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val points = prefs.getInt(KEY_SELECTED_POINTS, 0)
        if (points <= 0) return null
        val id = context.resources.getIdentifier("app_icon_points_$points", "drawable", context.packageName)
        return if (id != 0) id else null
    }

    /**
     * Applies the current active logo to the given ImageView:
     * 1. If custom app icon is active, applies its internal saved Bitmap.
     * 2. Else if a milestone badge is active, applies its drawable resource.
     * 3. Else applies defaultRes.
     */
    fun applyToImageView(imageView: ImageView, defaultRes: Int) {
        val context = imageView.context
        if (CustomIconManager.isCustomIconActive(context)) {
            val bitmap = CustomIconManager.getCustomIconBitmap(context)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                return
            }
        }
        val activeBadgeRes = getActiveAppIcon(context)
        if (activeBadgeRes != null) {
            imageView.setImageResource(activeBadgeRes)
        } else {
            imageView.setImageResource(defaultRes)
        }
    }
}
