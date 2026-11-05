package za.kilowatch.ultimatefilemanager.shortcuts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Composes home-screen launcher shortcut icons with the signature gold star badge at top-right.
 */
object FavoriteIconComposer {

    private const val BITMAP_SIZE = 192 // xxxhdpi shortcut icon size

    /**
     * Builds an [IconCompat] representing the favorite item with an overlaid gold star badge.
     */
    fun createShortcutIcon(context: Context, item: FavoritesManager.FavoriteItem): IconCompat {
        val bitmap = composeIconBitmap(context, item)
        return IconCompat.createWithBitmap(bitmap)
    }

    /**
     * Renders a 192x192 bitmap containing:
     * 1. A circular frosted dark background disk.
     * 2. The centered file/folder icon (tinted with theme accent).
     * 3. The gold star badge in the top-right quadrant.
     */
    fun composeIconBitmap(context: Context, item: FavoritesManager.FavoriteItem): Bitmap {
        val bitmap = Bitmap.createBitmap(BITMAP_SIZE, BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Draw rounded frosted background backing
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22272E")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#384252")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val bgRect = RectF(6f, 6f, (BITMAP_SIZE - 6).toFloat(), (BITMAP_SIZE - 6).toFloat())
        val cornerRadius = 42f
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, borderPaint)

        // 2. Draw base file/folder icon centered
        val iconRes = if (item.isFolder) {
            IconCustomizationManager.getEffectiveIconRes(context, "folder_default", R.drawable.ic_folder)
        } else {
            val ext = item.path.substringAfterLast('.', "")
            FileTypeIconProvider.iconForExtension(ext)
        }

        val baseDrawable = ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (baseDrawable != null) {
            val iconTint = ThemeColors.primary(context)
            baseDrawable.setTint(iconTint)
            val iconPadding = 44
            baseDrawable.setBounds(
                iconPadding,
                iconPadding,
                BITMAP_SIZE - iconPadding,
                BITMAP_SIZE - iconPadding
            )
            baseDrawable.draw(canvas)
        }

        // 3. Draw gold star badge at top-right
        val starBadge = ContextCompat.getDrawable(context, R.drawable.ic_star_badge)?.mutate()
        if (starBadge != null) {
            val badgeSize = 54
            val badgeRight = BITMAP_SIZE - 10
            val badgeTop = 10
            starBadge.setBounds(badgeRight - badgeSize, badgeTop, badgeRight, badgeTop + badgeSize)
            starBadge.draw(canvas)
        }

        return bitmap
    }
}
