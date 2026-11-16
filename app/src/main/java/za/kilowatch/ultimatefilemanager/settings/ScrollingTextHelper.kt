package za.kilowatch.ultimatefilemanager.settings

import android.animation.Animator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Controls the "Scrolling text" behaviour and interactive manual scrubbing for long file names.
 *
 * Supports:
 * - One-shot or Continuous marquee animation loop
 * - Configurable scroll speed (Slow ~30 dp/s, Normal ~50 dp/s, Fast ~80 dp/s)
 * - Manual horizontal touch scrubbing that disambiguates taps, long-presses, and list flings
 * - Scope-based scroll control
 *
 * All public methods must be called from the main thread.
 */
object ScrollingTextHelper {

    private const val INITIAL_PAUSE_MS = 1500L
    private const val END_PAUSE_MS = 500L

    private val handler = Handler(Looper.getMainLooper())

    /** Tracks which views are actively managed by the marquee animator. */
    private val activeViews = WeakHashMap<TextView, Boolean>()
    /** Maps each TextView to its running Animator (for cancellation). */
    private val activeAnimators = WeakHashMap<TextView, Animator>()
    /** Maps each TextView to its pending delayed Runnable (for cancellation). */
    private val pendingActions = WeakHashMap<TextView, Runnable>()
    /** Maps each TextView to its pending long-press Runnable (for cancellation on recycle). */
    private val pendingLongPressRunnables = WeakHashMap<TextView, Runnable>()
    /** Tracks views that have been manually scrubbed by the user. */
    private val manuallyScrubbedViews = WeakHashMap<TextView, Boolean>()

    /**
     * Apply or remove scrolling text on [textView] based on [enabled].
     *
     * Call this from [RecyclerView.Adapter.onBindViewHolder] every time the view is bound.
     */
    @JvmStatic
    fun applyScrollingText(textView: TextView, enabled: Boolean) {
        cancelAnimation(textView)

        if (!enabled) {
            textView.ellipsize = TextUtils.TruncateAt.END
            textView.setHorizontallyScrolling(false)
            textView.scrollX = 0
            return
        }

        // If manually scrubbed while visible, don't restart marquee animation
        if (manuallyScrubbedViews.containsKey(textView)) return

        // Don't scroll when system animations are disabled (accessibility).
        if (areAnimationsDisabled(textView.context)) return

        // Grid uses maxLines=2 — horizontal scrolling doesn't make sense.
        if (textView.maxLines > 1) return

        // Remove ellipsize so the full text is drawn (no "..." truncation).
        textView.ellipsize = null
        textView.setHorizontallyScrolling(true)

        // Defer measurement to next layout pass so view has a width.
        textView.post {
            if (!textView.isAttachedToWindow || textView.width == 0) return@post
            if (manuallyScrubbedViews.containsKey(textView)) return@post

            val text = textView.text.toString()
            if (text.isEmpty()) return@post

            val textWidth = textView.paint.measureText(text)
            val viewWidth = textView.width - textView.paddingLeft - textView.paddingRight
            val density = textView.resources.displayMetrics.density

            // 8 dp breathing room so the last character is clearly visible.
            if (textWidth <= viewWidth + 8f * density) return@post

            val overflow = (textWidth - viewWidth).toInt()

            activeViews[textView] = true

            // Initial pause, then scroll forward.
            postDelayed(textView, INITIAL_PAUSE_MS) {
                scrollOneWay(textView, overflow, forward = true)
            }
        }
    }

    /**
     * Cancel and reset scrolling and scrubbing on [textView] immediately.
     */
    @JvmStatic
    fun cancelScrolling(textView: TextView) {
        cancelAnimation(textView)
        pendingLongPressRunnables.remove(textView)?.let { handler.removeCallbacks(it) }
        manuallyScrubbedViews.remove(textView)
    }

    /**
     * Returns true if [textView] has been manually scrubbed by the user.
     */
    @JvmStatic
    fun isManuallyScrubbed(textView: TextView): Boolean {
        return manuallyScrubbedViews.containsKey(textView)
    }

    /**
     * Clears manual scrub state for [textView].
     */
    @JvmStatic
    fun clearManualScrub(textView: TextView) {
        manuallyScrubbedViews.remove(textView)
    }

    /**
     * Attaches interactive horizontal touch scrubbing to [textView].
     *
     * Disambiguates between:
     * - Tap: invokes click on item row
     * - Long press: invokes long-click on item row
     * - Vertical scroll: yields touch handling to RecyclerView
     * - Horizontal drag: scrubs filename horizontally and halts marquee
     */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun attachTouchScrubbing(textView: TextView, fullFileName: String) {
        val context = textView.context
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        textView.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var downScrollX = 0
            private var isDragging = false
            private var longPressRunnable: Runnable? = null

            private fun findItemRow(view: View): View? {
                var p: Any? = view.parent
                while (p is View && p !is RecyclerView) {
                    if (p.isClickable || p.hasOnClickListeners()) return p
                    p = p.parent
                }
                return view.parent as? View
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downScrollX = textView.scrollX
                        isDragging = false

                        // Schedule long press
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        val lp = Runnable {
                            if (!isDragging && textView.isAttachedToWindow) {
                                findItemRow(textView)?.performLongClick()
                            }
                        }
                        longPressRunnable = lp
                        pendingLongPressRunnables[textView] = lp
                        handler.postDelayed(lp, longPressTimeout)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY

                        if (!isDragging) {
                            if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                                // Horizontal scrub started
                                isDragging = true
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                pendingLongPressRunnables.remove(textView)
                                longPressRunnable = null

                                textView.parent?.requestDisallowInterceptTouchEvent(true)
                                manuallyScrubbedViews[textView] = true
                                cancelAnimation(textView)

                                // Ensure layout can scroll horizontally across the full file name
                                if (textView.text.toString() != fullFileName) {
                                    textView.text = fullFileName
                                }
                                textView.ellipsize = null
                                textView.setHorizontallyScrolling(true)
                            } else if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                                // Vertical scroll - yield to RecyclerView
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                pendingLongPressRunnables.remove(textView)
                                longPressRunnable = null
                                return false
                            }
                        }

                        if (isDragging) {
                            val textWidth = textView.paint.measureText(fullFileName)
                            val viewWidth = (textView.width - textView.paddingLeft - textView.paddingRight).coerceAtLeast(1)
                            val maxOverflow = (textWidth - viewWidth).toInt().coerceAtLeast(0)

                            val targetScrollX = (downScrollX - dx).toInt().coerceIn(0, maxOverflow)
                            textView.scrollX = targetScrollX
                            return true
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        pendingLongPressRunnables.remove(textView)
                        longPressRunnable = null

                        if (isDragging) {
                            textView.parent?.requestDisallowInterceptTouchEvent(false)
                            // Retain current scrubbed position until recycled off-screen
                            return true
                        } else {
                            // Tap: delegate to row click
                            val row = findItemRow(textView)
                            if (row != null) {
                                row.performClick()
                            } else {
                                textView.performClick()
                            }
                            return true
                        }
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        pendingLongPressRunnables.remove(textView)
                        longPressRunnable = null
                        if (isDragging) {
                            textView.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        return false
                    }
                }
                return false
            }
        })
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    private fun getScrollSpeedPxPerS(context: Context, density: Float): Float {
        val speedPref = ScrollingTextPreferenceManager.getScrollSpeed(context)
        val dpPerS = when (speedPref) {
            ScrollingTextPreferenceManager.SPEED_SLOW -> 30f
            ScrollingTextPreferenceManager.SPEED_FAST -> 80f
            else -> 50f
        }
        return dpPerS * density
    }

    /**
     * Animate [textView] in one direction, then schedule the opposite direction or repeat.
     */
    private fun scrollOneWay(textView: TextView, overflow: Int, forward: Boolean) {
        if (!activeViews.containsKey(textView) || !textView.isAttachedToWindow) return
        if (manuallyScrubbedViews.containsKey(textView)) return

        val from = if (forward) 0 else overflow
        val to = if (forward) overflow else 0

        val density = textView.resources.displayMetrics.density
        val speedPxPerS = getScrollSpeedPxPerS(textView.context, density)
        val durationMs = ((overflow / speedPxPerS) * 1000).toLong().coerceAtLeast(200L)

        val animator = ObjectAnimator.ofInt(textView, "scrollX", from, to)
        animator.duration = durationMs
        animator.interpolator = LinearInterpolator()

        activeAnimators[textView] = animator

        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) = Unit
            override fun onAnimationRepeat(animation: Animator) = Unit
            override fun onAnimationCancel(animation: Animator) {
                if (!manuallyScrubbedViews.containsKey(textView)) {
                    textView.scrollX = 0
                }
            }
            override fun onAnimationEnd(animation: Animator) {
                if (activeAnimators[textView] !== animation) return

                textView.post {
                    if (activeAnimators[textView] !== animation || !textView.isAttachedToWindow) return@post
                    if (manuallyScrubbedViews.containsKey(textView)) return@post

                    if (forward) {
                        // Reached the end — pause briefly, then scroll back.
                        postDelayed(textView, END_PAUSE_MS) {
                            scrollOneWay(textView, overflow, forward = false)
                        }
                    } else {
                        // Scrolled back to start
                        val loopMode = ScrollingTextPreferenceManager.getLoopMode(textView.context)
                        if (loopMode == ScrollingTextPreferenceManager.LOOP_CONTINUOUS) {
                            postDelayed(textView, INITIAL_PAUSE_MS) {
                                scrollOneWay(textView, overflow, forward = true)
                            }
                        }
                    }
                }
            }
        })

        animator.start()
    }

    /** Post a [Runnable] that automatically checks [activeViews] validity. */
    private fun postDelayed(textView: TextView, delayMs: Long, action: () -> Unit) {
        val runnable = Runnable {
            if (!activeViews.containsKey(textView) || !textView.isAttachedToWindow) return@Runnable
            if (manuallyScrubbedViews.containsKey(textView)) return@Runnable
            action()
        }
        pendingActions[textView] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelAnimation(textView: TextView) {
        activeViews.remove(textView)
        pendingActions.remove(textView)?.let { handler.removeCallbacks(it) }
        activeAnimators.remove(textView)?.let { anim ->
            anim.removeAllListeners()
            anim.cancel()
        }
        if (!manuallyScrubbedViews.containsKey(textView)) {
            textView.scrollX = 0
        }
    }

    private fun areAnimationsDisabled(context: Context): Boolean {
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }
}
