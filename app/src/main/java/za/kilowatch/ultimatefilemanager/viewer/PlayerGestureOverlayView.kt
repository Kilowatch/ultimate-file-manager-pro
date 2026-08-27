package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import za.kilowatch.ultimatefilemanager.R

/**
 * Visual feedback overlay for video player touch gestures (Brightness, Volume,
 * Horizontal Seek scrub, and Left/Right Double-Tap indicators).
 */
class PlayerGestureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())

    private val brightnessOverlay: View
    private val imgBrightnessIcon: ImageView
    private val progressBrightness: ProgressBar
    private val txtBrightness: TextView

    private val volumeOverlay: View
    private val imgVolumeIcon: ImageView
    private val progressVolume: ProgressBar
    private val txtVolume: TextView

    private val seekOverlay: View
    private val imgSeekIcon: ImageView
    private val txtSeekDelta: TextView
    private val txtSeekTargetTime: TextView
    private val progressSeek: ProgressBar

    private val doubleTapLeftOverlay: View
    private val txtDoubleTapLeft: TextView
    private val doubleTapRightOverlay: View
    private val txtDoubleTapRight: TextView

    private val hideBrightnessRunnable = Runnable { fadeOut(brightnessOverlay) }
    private val hideVolumeRunnable = Runnable { fadeOut(volumeOverlay) }
    private val hideSeekRunnable = Runnable { fadeOut(seekOverlay) }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_player_gesture_overlay, this, true)

        brightnessOverlay = findViewById(R.id.brightnessOverlay)
        imgBrightnessIcon = findViewById(R.id.imgBrightnessIcon)
        progressBrightness = findViewById(R.id.progressBrightness)
        txtBrightness = findViewById(R.id.txtBrightness)

        volumeOverlay = findViewById(R.id.volumeOverlay)
        imgVolumeIcon = findViewById(R.id.imgVolumeIcon)
        progressVolume = findViewById(R.id.progressVolume)
        txtVolume = findViewById(R.id.txtVolume)

        seekOverlay = findViewById(R.id.seekOverlay)
        imgSeekIcon = findViewById(R.id.imgSeekIcon)
        txtSeekDelta = findViewById(R.id.txtSeekDelta)
        txtSeekTargetTime = findViewById(R.id.txtSeekTargetTime)
        progressSeek = findViewById(R.id.progressSeek)

        doubleTapLeftOverlay = findViewById(R.id.doubleTapLeftOverlay)
        txtDoubleTapLeft = findViewById(R.id.txtDoubleTapLeft)
        doubleTapRightOverlay = findViewById(R.id.doubleTapRightOverlay)
        txtDoubleTapRight = findViewById(R.id.txtDoubleTapRight)
    }

    fun showBrightness(percent: Int) {
        handler.removeCallbacks(hideBrightnessRunnable)
        hideImmediately(volumeOverlay)
        hideImmediately(seekOverlay)

        txtBrightness.text = "$percent%"
        progressBrightness.progress = percent.coerceIn(0, 100)

        fadeIn(brightnessOverlay)
    }

    fun releaseBrightness() {
        handler.removeCallbacks(hideBrightnessRunnable)
        handler.postDelayed(hideBrightnessRunnable, 500)
    }

    fun showVolume(percent: Int, isMuted: Boolean) {
        handler.removeCallbacks(hideVolumeRunnable)
        hideImmediately(brightnessOverlay)
        hideImmediately(seekOverlay)

        val iconRes = when {
            isMuted || percent == 0 -> R.drawable.ic_volume_off
            percent < 40            -> R.drawable.ic_volume_down
            else                    -> R.drawable.ic_volume_up
        }
        imgVolumeIcon.setImageResource(iconRes)
        txtVolume.text = if (isMuted) "MUTED" else "$percent%"
        progressVolume.progress = if (isMuted) 0 else percent.coerceIn(0, 100)

        fadeIn(volumeOverlay)
    }

    fun releaseVolume() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, 500)
    }

    fun showSeek(deltaMs: Long, targetMs: Long, totalMs: Long) {
        handler.removeCallbacks(hideSeekRunnable)
        hideImmediately(brightnessOverlay)
        hideImmediately(volumeOverlay)

        val isForward = deltaMs >= 0
        imgSeekIcon.setImageResource(if (isForward) R.drawable.ic_skip_forward else R.drawable.ic_skip_back)

        val absSec = Math.abs(deltaMs) / 1000
        val sign = if (isForward) "+" else "-"
        txtSeekDelta.text = String.format("%s%02d:%02d", sign, absSec / 60, absSec % 60)
        txtSeekDelta.setTextColor(if (isForward) Color.parseColor("#4CAF50") else Color.parseColor("#FF7043"))

        txtSeekTargetTime.text = "${formatTime(targetMs)} / ${formatTime(totalMs)}"

        if (totalMs > 0) {
            val progress = ((targetMs.toDouble() / totalMs) * 1000).toInt().coerceIn(0, 1000)
            progressSeek.progress = progress
        } else {
            progressSeek.progress = 0
        }

        fadeIn(seekOverlay)
    }

    fun releaseSeek() {
        handler.removeCallbacks(hideSeekRunnable)
        handler.postDelayed(hideSeekRunnable, 500)
    }

    fun triggerDoubleTapAnimation(isForward: Boolean, seconds: Int) {
        val overlay = if (isForward) doubleTapRightOverlay else doubleTapLeftOverlay
        val textView = if (isForward) txtDoubleTapRight else txtDoubleTapLeft
        textView.text = if (isForward) "+${seconds}s" else "-${seconds}s"

        overlay.animate().cancel()
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.scaleX = 0.75f
        overlay.scaleY = 0.75f

        overlay.animate()
            .alpha(1f)
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(140)
            .withEndAction {
                overlay.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(80)
                    .withEndAction {
                        overlay.animate()
                            .alpha(0f)
                            .setStartDelay(300)
                            .setDuration(220)
                            .withEndAction { overlay.visibility = View.GONE }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    fun hideAll(immediate: Boolean = false) {
        handler.removeCallbacks(hideBrightnessRunnable)
        handler.removeCallbacks(hideVolumeRunnable)
        handler.removeCallbacks(hideSeekRunnable)
        if (immediate) {
            hideImmediately(brightnessOverlay)
            hideImmediately(volumeOverlay)
            hideImmediately(seekOverlay)
            hideImmediately(doubleTapLeftOverlay)
            hideImmediately(doubleTapRightOverlay)
        } else {
            fadeOut(brightnessOverlay)
            fadeOut(volumeOverlay)
            fadeOut(seekOverlay)
            fadeOut(doubleTapLeftOverlay)
            fadeOut(doubleTapRightOverlay)
        }
    }

    private fun fadeIn(view: View) {
        if (view.visibility != View.VISIBLE || view.alpha < 1f) {
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .setDuration(120)
                .start()
        }
    }

    private fun fadeOut(view: View) {
        if (view.visibility == View.VISIBLE) {
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { view.visibility = View.GONE }
                .start()
        }
    }

    private fun hideImmediately(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.GONE
    }

    private fun formatTime(ms: Long): String {
        var s = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = s / 3600
        s %= 3600
        val m = s / 60
        s %= 60
        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }
}
