package za.kilowatch.ultimatefilemanager.shortcuts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Renders 16:9 (640x360 px) TV preview channel poster art cards for Android TV homescreen,
 * featuring a centered icon, item label, and gold star badge at top-right.
 */
object FavoritePosterRenderer {

    private const val TAG = "FavoritePosterRenderer"
    private const val POSTER_WIDTH = 640
    private const val POSTER_HEIGHT = 360

    /**
     * Resolves or generates the cached poster image for the given favorite item.
     * Returns a content/file [Uri] suitable for [androidx.tvprovider.media.tv.PreviewProgram.Builder.setPosterArtUri].
     */
    fun getPosterUri(context: Context, item: FavoritesManager.FavoriteItem): Uri? {
        val posterDir = File(context.filesDir, "fav_posters").apply { if (!exists()) mkdirs() }
        val posterFile = File(posterDir, "${item.id}.png")

        if (posterFile.exists() && posterFile.length() > 0) {
            return try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", posterFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get content URI for existing poster: ${posterFile.path}", e)
                null
            }
        }

        val bitmap = renderPoster(context, item)
        return try {
            FileOutputStream(posterFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", posterFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save poster for ${item.id}", e)
            null
        }
    }

    /**
     * Removes the cached poster file when a favorite is deleted.
     */
    fun removePoster(context: Context, item: FavoritesManager.FavoriteItem) {
        val posterDir = File(context.filesDir, "fav_posters")
        val posterFile = File(posterDir, "${item.id}.png")
        if (posterFile.exists()) {
            posterFile.delete()
        }
    }

    fun renderPoster(context: Context, item: FavoritesManager.FavoriteItem): Bitmap {
        val bitmap = Bitmap.createBitmap(POSTER_WIDTH, POSTER_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Solid TV glass dark background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#151921")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, POSTER_WIDTH.toFloat(), POSTER_HEIGHT.toFloat(), bgPaint)

        // 2. Subtle gradient inner card
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E2430")
            style = Paint.Style.FILL
        }
        val cardRect = RectF(20f, 20f, (POSTER_WIDTH - 20).toFloat(), (POSTER_HEIGHT - 20).toFloat())
        canvas.drawRoundRect(cardRect, 24f, 24f, cardPaint)

        // 3. Draw base file/folder icon
        val iconRes = if (item.isFolder) {
            IconCustomizationManager.getEffectiveIconRes(context, "folder_default", R.drawable.ic_folder)
        } else {
            val ext = item.path.substringAfterLast('.', "")
            FileTypeIconProvider.iconForExtension(ext)
        }

        val baseDrawable = ContextCompat.getDrawable(context, iconRes)?.mutate()
        val iconSize = 130
        val iconLeft = (POSTER_WIDTH - iconSize) / 2
        val iconTop = 65
        if (baseDrawable != null) {
            val iconTint = ContextCompat.getColor(context, R.color.tv_accent)
            baseDrawable.setTint(iconTint)
            baseDrawable.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            baseDrawable.draw(canvas)
        }

        // 4. Draw label below icon
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F0F4F8")
            textSize = 28f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val maxTextWidth = (POSTER_WIDTH - 80).toFloat()
        val ellipsized = TextUtils.ellipsize(item.label, textPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
        val textY = (iconTop + iconSize + 55).toFloat()
        canvas.drawText(ellipsized, (POSTER_WIDTH / 2).toFloat(), textY, textPaint)

        // 5. Draw gold star badge at top-right
        val starBadge = ContextCompat.getDrawable(context, R.drawable.ic_star_badge)?.mutate()
        if (starBadge != null) {
            val badgeSize = 64
            val badgeRight = POSTER_WIDTH - 36
            val badgeTop = 36
            starBadge.setBounds(badgeRight - badgeSize, badgeTop, badgeRight, badgeTop + badgeSize)
            starBadge.draw(canvas)
        }

        return bitmap
    }
}
