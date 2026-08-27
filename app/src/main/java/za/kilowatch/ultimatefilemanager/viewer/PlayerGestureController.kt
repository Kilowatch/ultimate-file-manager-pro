package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.app.AppCompatActivity
import za.kilowatch.ultimatefilemanager.settings.PlayerPreferencesManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Controller for video player touch gestures:
 * - Left vertical swipe: Brightness
 * - Right vertical swipe: Volume
 * - Horizontal swipe: Scrub / seek
 * - Double-tap left: Rewind (skip length)
 * - Double-tap right: Fast-forward (skip length)
 * - Single-tap: Toggle controls visibility
 * - Long-press: Show playback speed bottom sheet
 */
class PlayerGestureController(
    private val activity: AppCompatActivity,
    private val overlayView: PlayerGestureOverlayView,
    private val topBar: View,
    private val controlsLayout: View,
    private val isGesturesEnabled: () -> Boolean,
    private val isVideoPlaying: () -> Boolean,
    private val getCurrentPosition: () -> Long,
    private val getDuration: () -> Long,
    private val onSeekTo: (positionMs: Long) -> Unit,
    private val onSingleTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val hideControls: () -> Unit,
    private val resetHideTimer: () -> Unit
) {

    private enum class GestureType {
        NONE,
        BRIGHTNESS,
        VOLUME,
        SEEK
    }

    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop

    private var currentGesture = GestureType.NONE
    private var isTouchInsideControls = false

    private var downX = 0f
    private var downY = 0f

    private var initialVolume = 0
    private var maxVolume = 15
    private var initialBrightness = 0.5f
    private var initialPosition = 0L
    private var targetSeekPosition = 0L
    private var seekDeltaMs = 0L

    // Cumulative double-tap tracking
    private var lastDoubleTapTime = 0L
    private var lastDoubleTapSideIsRight: Boolean? = null
    private var consecutiveDoubleTapCount = 0

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isTouchInsideControls) return false
            onSingleTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isTouchInsideControls || !canHandleGestures()) return false

            val width = activity.window.decorView.width
            val isRight = e.x >= (width / 2f)

            val baseSkipSeconds = PlayerPreferencesManager.getSkipLengthSeconds(activity).let {
                if (it <= 0) PlayerPreferencesManager.DEFAULT_SKIP_SECONDS else it
            }

            val now = SystemClock.uptimeMillis()
            if (lastDoubleTapSideIsRight == isRight && (now - lastDoubleTapTime) < 700) {
                consecutiveDoubleTapCount++
            } else {
                consecutiveDoubleTapCount = 1
                lastDoubleTapSideIsRight = isRight
            }
            lastDoubleTapTime = now

            val totalSkipSeconds = baseSkipSeconds * consecutiveDoubleTapCount
            val skipMs = totalSkipSeconds * 1000L
            val current = getCurrentPosition()
            val duration = getDuration()

            val target = if (isRight) {
                (current + (baseSkipSeconds * 1000L)).coerceAtMost(duration)
            } else {
                (current - (baseSkipSeconds * 1000L)).coerceAtLeast(0L)
            }

            onSeekTo(target)
            overlayView.triggerDoubleTapAnimation(isRight, totalSkipSeconds)
            resetHideTimer()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!isTouchInsideControls) {
                onLongPress()
            }
        }
    }

    private val gestureDetector = GestureDetector(activity, gestureListener)

    fun handleTouchEvent(event: MotionEvent): Boolean {
        // Double-check TV or disabled
        if (DeviceUtils.isTvDevice(activity)) return false

        // Check if touch starts on topBar or controlsLayout
        if (event.action == MotionEvent.ACTION_DOWN) {
            isTouchInsideControls = isEventInsideVisibleView(topBar, event) ||
                                   isEventInsideVisibleView(controlsLayout, event)
            if (isTouchInsideControls) {
                return false
            }
        }

        if (isTouchInsideControls) {
            return false
        }

        // Pass to gesture detector for single-tap, double-tap, and long-press
        val detectorHandled = gestureDetector.onTouchEvent(event)

        if (!canHandleGestures()) {
            return detectorHandled
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                currentGesture = GestureType.NONE

                maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                val lp = activity.window.attributes
                initialBrightness = if (lp.screenBrightness < 0f) {
                    try {
                        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                    } catch (_: Exception) {
                        0.5f
                    }
                } else {
                    lp.screenBrightness
                }.coerceIn(0.01f, 1.0f)

                initialPosition = getCurrentPosition()
                targetSeekPosition = initialPosition
                seekDeltaMs = 0L
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - downX
                val deltaY = event.y - downY

                if (currentGesture == GestureType.NONE) {
                    val absX = Math.abs(deltaX)
                    val absY = Math.abs(deltaY)

                    if (absX > touchSlop || absY > touchSlop) {
                        if (absX > absY) {
                            currentGesture = GestureType.SEEK
                            hideControls()
                        } else {
                            val width = activity.window.decorView.width
                            currentGesture = if (downX < (width / 2f)) {
                                GestureType.BRIGHTNESS
                            } else {
                                GestureType.VOLUME
                            }
                            hideControls()
                        }
                    }
                }

                when (currentGesture) {
                    GestureType.BRIGHTNESS -> {
                        val height = activity.window.decorView.height.toFloat()
                        val percentDelta = -deltaY / (height * 0.70f)
                        val targetBrightness = (initialBrightness + percentDelta).coerceIn(0.01f, 1.0f)

                        val lp = activity.window.attributes
                        lp.screenBrightness = targetBrightness
                        activity.window.attributes = lp

                        overlayView.showBrightness((targetBrightness * 100).toInt())
                        return true
                    }

                    GestureType.VOLUME -> {
                        val height = activity.window.decorView.height.toFloat()
                        val percentDelta = -deltaY / (height * 0.70f)
                        val volumeStep = (percentDelta * maxVolume).toInt()
                        val targetVolume = (initialVolume + volumeStep).coerceIn(0, maxVolume)

                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)

                        val isMuted = targetVolume == 0
                        val percent = if (maxVolume > 0) (targetVolume * 100 / maxVolume) else 0
                        overlayView.showVolume(percent, isMuted)
                        return true
                    }

                    GestureType.SEEK -> {
                        val width = activity.window.decorView.width.toFloat()
                        val duration = getDuration()
                        // 90 seconds max delta across screen width
                        val scrubScaleMs = 90_000L
                        seekDeltaMs = ((deltaX / width) * scrubScaleMs).toLong()
                        targetSeekPosition = (initialPosition + seekDeltaMs).coerceIn(0L, duration)

                        overlayView.showSeek(seekDeltaMs, targetSeekPosition, duration)
                        return true
                    }

                    GestureType.NONE -> { /* waiting for slop */ }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (currentGesture) {
                    GestureType.SEEK -> {
                        onSeekTo(targetSeekPosition)
                        overlayView.releaseSeek()
                        resetHideTimer()
                        currentGesture = GestureType.NONE
                        return true
                    }

                    GestureType.BRIGHTNESS -> {
                        overlayView.releaseBrightness()
                        resetHideTimer()
                        currentGesture = GestureType.NONE
                        return true
                    }

                    GestureType.VOLUME -> {
                        overlayView.releaseVolume()
                        resetHideTimer()
                        currentGesture = GestureType.NONE
                        return true
                    }

                    GestureType.NONE -> {
                        currentGesture = GestureType.NONE
                    }
                }
                isTouchInsideControls = false
            }
        }

        return detectorHandled
    }

    private fun canHandleGestures(): Boolean {
        return !DeviceUtils.isTvDevice(activity) &&
               isGesturesEnabled() &&
               isVideoPlaying()
    }

    private fun isEventInsideVisibleView(view: View, event: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE || view.alpha < 0.1f) return false
        val rect = Rect()
        view.getGlobalVisibleRect(rect)
        return rect.contains(event.rawX.toInt(), event.rawY.toInt())
    }
}
