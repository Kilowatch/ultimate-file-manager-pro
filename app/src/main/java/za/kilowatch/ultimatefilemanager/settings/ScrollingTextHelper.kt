package za.kilowatch.ultimatefilemanager.settings

import android.animation.Animator
import android.animation.ObjectAnimator
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.animation.LinearInterpolator
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Controls the "Scrolling text" behaviour for long file names.
 *
 * Uses [ObjectAnimator] on the [TextView]'s `scrollX` property — the native
 * way to shift content within a View's clip bounds.  The animation is a
 * **one-shot** cycle:
 *   1. Show the beginning of the name (~1.5 s)
 *   2. Scroll forward to reveal the end (linear, ~50 dp/s)
 *   3. Pause at the end (~0.5 s)
 *   4. Scroll back to the beginning (linear)
 *   5. **Stop** (one cycle done)
 *
 * The cycle re-plays when the view is re-bound (item scrolls off-screen and
 * back, triggering [applyScrollingText] again).
 *
 * All public methods must be called from the main thread.
 */
object ScrollingTextHelper {

    private const val SCROLL_SPEED_DP_PER_S = 50f
    private const val INITIAL_PAUSE_MS = 1500L
    private const val END_PAUSE_MS = 500L

    private val handler = Handler(Looper.getMainLooper())

    /** Tracks which views are actively managed (guard for delayed callbacks). */
    private val activeViews = WeakHashMap<TextView, Boolean>()
    /** Maps each TextView to its running Animator (for cancellation). */
    private val activeAnimators = WeakHashMap<TextView, Animator>()
    /** Maps each TextView to its pending delayed Runnable (for cancellation). */
    private val pendingActions = WeakHashMap<TextView, Runnable>()

    /**
     * Apply or remove scrolling text on [textView] based on [enabled].
     *
     * Call this from
     * [androidx.recyclerview.widget.RecyclerView.Adapter.onBindViewHolder]
     * every time the view is bound.  It is safe to call multiple times —
     * previous animations are cancelled before starting new ones.
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

        // Don't scroll when system animations are disabled (accessibility).
        if (areAnimationsDisabled(textView.context)) return

        // Grid uses maxLines=2 — horizontal scrolling doesn't make sense.
        if (textView.maxLines > 1) return

        // Remove ellipsize so the full text is drawn (no "..." truncation).
        // Enable horizontal scrolling so the text layout is not constrained to
        // the view width — otherwise scrollX scrolls within truncated content.
        textView.ellipsize = null
        textView.setHorizontallyScrolling(true)

        // Defer measurement to the next layout pass so the view has a width.
        textView.post {
            if (!textView.isAttachedToWindow || textView.width == 0) return@post

            val text = textView.text.toString()
            if (text.isEmpty()) return@post

            val textWidth = textView.paint.measureText(text)
            val viewWidth = textView.width - textView.paddingLeft - textView.paddingRight
            val density = textView.resources.displayMetrics.density

            // 8 dp breathing room so the last character is clearly visible.
            if (textWidth <= viewWidth + 8f * density) return@post

            val overflow = (textWidth - viewWidth).toInt()

            // Mark as active so delayed Runnables can check validity.
            activeViews[textView] = true

            // Initial pause, then scroll forward.
            postDelayed(textView, INITIAL_PAUSE_MS) {
                scrollOneWay(textView, overflow, forward = true)
            }
        }
    }

    /**
     * Cancel and reset scrolling on [textView] immediately.
     */
    @JvmStatic
    fun cancelScrolling(textView: TextView) {
        cancelAnimation(textView)
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /**
     * Animate [textView] in one direction, then schedule the opposite
     * direction or stop.
     */
    private fun scrollOneWay(textView: TextView, overflow: Int, forward: Boolean) {
        if (!activeViews.containsKey(textView) || !textView.isAttachedToWindow) return

        val from = if (forward) 0 else overflow
        val to = if (forward) overflow else 0

        val density = textView.resources.displayMetrics.density
        val speedPxPerS = SCROLL_SPEED_DP_PER_S * density
        val durationMs = ((overflow / speedPxPerS) * 1000).toLong().coerceAtLeast(200L)

        val animator = ObjectAnimator.ofInt(textView, "scrollX", from, to)
        animator.duration = durationMs
        animator.interpolator = LinearInterpolator()

        activeAnimators[textView] = animator

        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) = Unit
            override fun onAnimationRepeat(animation: Animator) = Unit
            override fun onAnimationCancel(animation: Animator) {
                textView.scrollX = 0
            }
            override fun onAnimationEnd(animation: Animator) {
                if (activeAnimators[textView] !== animation) return

                textView.post {
                    if (activeAnimators[textView] !== animation || !textView.isAttachedToWindow) return@post

                    if (forward) {
                        // Reached the end — pause briefly, then scroll back.
                        postDelayed(textView, END_PAUSE_MS) {
                            scrollOneWay(textView, overflow, forward = false)
                        }
                    } else {
                        // Scrolled back to start — one-shot complete.
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
        textView.scrollX = 0
    }

    private fun areAnimationsDisabled(context: android.content.Context): Boolean {
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
