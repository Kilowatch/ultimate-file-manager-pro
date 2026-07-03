package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import za.kilowatch.ultimatefilemanager.R

/**
 * Overlay that appears at 5 seconds remaining to show the next track.
 * Provides Cancel (skip autoplay this once) and Skip (play now) actions.
 */
class NextTrackOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var txtNextFileName: TextView
    private var txtNextDuration: TextView
    private var btnCancelNext: Button
    private var btnSkipNext: View

    private var onCancelListener: (() -> Unit)? = null
    private var onSkipListener: (() -> Unit)? = null

    private val hiddenOffset: Float

    init {
        inflate(context, R.layout.overlay_next_track, this)

        txtNextFileName = findViewById(R.id.txtNextFileName)
        txtNextDuration = findViewById(R.id.txtNextDuration)
        btnCancelNext = findViewById(R.id.btnCancelNext)
        btnSkipNext = findViewById(R.id.btnSkipNext)

        btnCancelNext.setOnClickListener { onCancelListener?.invoke() }
        btnSkipNext.setOnClickListener { onSkipListener?.invoke() }

        hiddenOffset = 200 * context.resources.displayMetrics.density

        // Initially hidden
        visibility = GONE
        translationY = hiddenOffset
    }

    /** Set the next-track info and show. */
    fun showNextTrack(
        fileName: String,
        durationText: String,
        onCancel: () -> Unit,
        onSkip: () -> Unit
    ) {
        txtNextFileName.text = fileName
        txtNextDuration.text = durationText
        onCancelListener = onCancel
        onSkipListener = onSkip

        if (visibility != VISIBLE) {
            visibility = VISIBLE
            translationY = hiddenOffset
            animate().translationY(0f).setDuration(300).start()
        }
    }

    /** Hide the overlay with a slide-down animation. */
    fun hideOverlay() {
        animate().translationY(hiddenOffset)
            .setDuration(200)
            .withEndAction { visibility = GONE }
            .start()
    }

    /** Immediately hide without animation. */
    fun hideImmediately() {
        clearAnimation()
        visibility = GONE
        translationY = hiddenOffset
    }
}
