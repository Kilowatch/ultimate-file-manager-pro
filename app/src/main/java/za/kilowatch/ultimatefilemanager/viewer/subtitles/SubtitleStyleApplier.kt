package za.kilowatch.ultimatefilemanager.viewer.subtitles

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import za.kilowatch.ultimatefilemanager.settings.PlayerPreferencesManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Reusable styling engine for Media3 [SubtitleView] and [PlayerView] surfaces.
 *
 * Resolves system accessibility caption styles from [CaptioningManager] regardless of whether
 * the global OS-level CC switch is enabled, and applies custom in-app styling preferences
 * and dynamic bottom padding offsets.
 */
object SubtitleStyleApplier {

    private const val TAG = "SubtitleStyleApplier"

    /**
     * Applies the current subtitle preferences to a [PlayerView]'s internal [SubtitleView].
     */
    fun apply(playerView: PlayerView?, context: Context) {
        val subtitleView = playerView?.subtitleView ?: return
        apply(subtitleView, context)
    }

    /**
     * Applies the current subtitle preferences to any target [SubtitleView].
     */
    fun apply(subtitleView: SubtitleView?, context: Context) {
        if (subtitleView == null) return
        val isTv = DeviceUtils.isTvDevice(context)
        val config = SubtitleStyleConfig.fromPreferences(context, isTv)
        applyConfig(subtitleView, config, context)
    }

    /**
     * Applies an explicit [SubtitleStyleConfig] to a target [SubtitleView].
     * Used by the real player surfaces as well as the live preview sheet/dialog.
     */
    fun applyConfig(subtitleView: SubtitleView?, config: SubtitleStyleConfig, context: Context) {
        if (subtitleView == null) return

        try {
            subtitleView.setApplyEmbeddedStyles(config.applyEmbeddedStyles)
            subtitleView.setApplyEmbeddedFontSizes(config.applyEmbeddedStyles)

            if (config.mode == PlayerPreferencesManager.SUBTITLE_MODE_SYSTEM) {
                val (systemStyle, fontScale) = resolveSystemStyle(context)
                subtitleView.setStyle(systemStyle)
                subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * fontScale)
                GoRoLog.d(TAG, "Applied system subtitle style with fontScale=$fontScale")
            } else {
                val customStyle = CaptionStyleCompat(
                    config.textColor,
                    config.backgroundColor,
                    Color.TRANSPARENT,
                    config.edgeType,
                    config.edgeColor,
                    Typeface.DEFAULT
                )
                subtitleView.setStyle(customStyle)
                subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * config.textSizeMultiplier)
                GoRoLog.d(TAG, "Applied custom subtitle style: color=${config.textColor}, sizeMultiplier=${config.textSizeMultiplier}")
            }

            setBottomPadding(subtitleView, context, config.bottomPaddingDp, extraOffsetPx = 0)
        } catch (t: Throwable) {
            GoRoLog.e(TAG, "Error applying subtitle style: ${t.message}", t)
            subtitleView.setStyle(CaptionStyleCompat.DEFAULT)
            subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
        }
    }

    /**
     * Reads the user's custom caption style and font scale directly from [CaptioningManager],
     * decoupling the styling from [CaptioningManager.isEnabled].
     */
    fun resolveSystemStyle(context: Context): Pair<CaptionStyleCompat, Float> {
        val cm = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        if (cm != null) {
            try {
                val userStyle = cm.userStyle
                val styleCompat = CaptionStyleCompat.createFromCaptionStyle(userStyle)
                val fontScale = cm.fontScale.coerceIn(0.5f, 2.0f)
                return Pair(styleCompat, fontScale)
            } catch (t: Throwable) {
                GoRoLog.w(TAG, "Failed to read system caption style: ${t.message}")
            }
        }
        return Pair(CaptionStyleCompat.DEFAULT, 1.0f)
    }

    /**
     * Dynamically shifts subtitle position upwards when player controls appear so that
     * the seek bar and controls never obscure subtitles.
     *
     * @param extraOffsetPx Additional vertical bottom offset in pixels (e.g. 84dp when controls are visible, 0 when hidden).
     */
    fun updateBottomPadding(subtitleView: SubtitleView?, context: Context, extraOffsetPx: Int = 0) {
        if (subtitleView == null) return
        val isTv = DeviceUtils.isTvDevice(context)
        val baseDp = PlayerPreferencesManager.getSubtitleBottomPaddingDp(context, isTv)
        setBottomPadding(subtitleView, context, baseDp, extraOffsetPx)
    }

    private fun setBottomPadding(subtitleView: SubtitleView?, context: Context, bottomPaddingDp: Int, extraOffsetPx: Int) {
        if (subtitleView == null) return
        val density = context.resources.displayMetrics.density
        val basePx = (bottomPaddingDp * density).toInt()
        val totalPx = (basePx + extraOffsetPx).coerceAtLeast(0)
        subtitleView.setPadding(0, 0, 0, totalPx)
    }
}
