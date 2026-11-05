package za.kilowatch.ultimatefilemanager.viewer.subtitles

import android.content.Context
import za.kilowatch.ultimatefilemanager.settings.PlayerPreferencesManager

/**
 * Data model encapsulating the user's complete subtitle styling configuration.
 */
data class SubtitleStyleConfig(
    val mode: Int = PlayerPreferencesManager.SUBTITLE_MODE_SYSTEM,
    val textSizeMultiplier: Float = PlayerPreferencesManager.DEFAULT_SUBTITLE_TEXT_SIZE_SCALE,
    val textColor: Int = PlayerPreferencesManager.DEFAULT_SUBTITLE_TEXT_COLOR,
    val backgroundColor: Int = PlayerPreferencesManager.DEFAULT_SUBTITLE_BG_COLOR,
    val edgeType: Int = PlayerPreferencesManager.DEFAULT_SUBTITLE_EDGE_TYPE,
    val edgeColor: Int = PlayerPreferencesManager.DEFAULT_SUBTITLE_EDGE_COLOR,
    val bottomPaddingDp: Int = PlayerPreferencesManager.DEFAULT_SUBTITLE_BOTTOM_PADDING_DP_MOBILE,
    val applyEmbeddedStyles: Boolean = PlayerPreferencesManager.DEFAULT_SUBTITLE_APPLY_EMBEDDED_STYLES
) {
    companion object {
        /**
         * Loads the current configuration from [PlayerPreferencesManager].
         */
        fun fromPreferences(context: Context, isTv: Boolean = false): SubtitleStyleConfig {
            return SubtitleStyleConfig(
                mode = PlayerPreferencesManager.getSubtitleStyleMode(context),
                textSizeMultiplier = PlayerPreferencesManager.getSubtitleCustomTextSize(context),
                textColor = PlayerPreferencesManager.getSubtitleCustomTextColor(context),
                backgroundColor = PlayerPreferencesManager.getSubtitleCustomBgColor(context),
                edgeType = PlayerPreferencesManager.getSubtitleCustomEdgeType(context),
                edgeColor = PlayerPreferencesManager.getSubtitleCustomEdgeColor(context),
                bottomPaddingDp = PlayerPreferencesManager.getSubtitleBottomPaddingDp(context, isTv),
                applyEmbeddedStyles = PlayerPreferencesManager.isSubtitleApplyEmbeddedStyles(context)
            )
        }
    }

    /**
     * Persists this configuration back to [PlayerPreferencesManager].
     */
    fun saveToPreferences(context: Context) {
        PlayerPreferencesManager.setSubtitleStyleMode(context, mode)
        PlayerPreferencesManager.setSubtitleCustomTextSize(context, textSizeMultiplier)
        PlayerPreferencesManager.setSubtitleCustomTextColor(context, textColor)
        PlayerPreferencesManager.setSubtitleCustomBgColor(context, backgroundColor)
        PlayerPreferencesManager.setSubtitleCustomEdgeType(context, edgeType)
        PlayerPreferencesManager.setSubtitleCustomEdgeColor(context, edgeColor)
        PlayerPreferencesManager.setSubtitleBottomPaddingDp(context, bottomPaddingDp)
        PlayerPreferencesManager.setSubtitleApplyEmbeddedStyles(context, applyEmbeddedStyles)
    }
}
