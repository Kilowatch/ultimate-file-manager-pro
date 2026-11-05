package za.kilowatch.ultimatefilemanager.viewer.subtitles

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import za.kilowatch.ultimatefilemanager.R

/**
 * Custom live preview view that displays sample subtitle cues against a simulated
 * cinema viewport to immediately visualize style, scale, color, and edge changes.
 */
class SubtitlePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val subtitleView: SubtitleView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_subtitle_preview, this, true)
        subtitleView = findViewById(R.id.previewSubtitleView)

        val line1 = context.getString(R.string.subtitle_style_preview_line1)
        val line2 = context.getString(R.string.subtitle_style_preview_line2)

        val sampleCues = listOf(
            Cue.Builder()
                .setText("$line1\n$line2")
                .build()
        )
        subtitleView.setCues(sampleCues)
    }

    /**
     * Updates the preview with the provided [SubtitleStyleConfig].
     */
    fun updateStyle(config: SubtitleStyleConfig) {
        SubtitleStyleApplier.applyConfig(subtitleView, config, context)
    }
}
