package za.kilowatch.ultimatefilemanager.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import za.kilowatch.ultimatefilemanager.R

object SelectionAnimationHelper {
    private const val ANIM_VIEW_TAG = "UfmSelectionScannerAnimView"

    /**
     * Injects and starts the drifting neon file animation inside the container.
     */
    fun startAnimation(container: ViewGroup) {
        // Prevent duplicate animation views
        var animView = container.findViewWithTag<ImageView>(ANIM_VIEW_TAG)
        if (animView != null) {
            return
        }

        val context = container.context
        animView = ImageView(context).apply {
            tag = ANIM_VIEW_TAG
            setImageResource(R.drawable.ic_drifting_scanner)
            scaleType = ImageView.ScaleType.FIT_CENTER
            
            // Set layout params with center-vertical alignment
            val density = context.resources.displayMetrics.density
            val lp = when (container) {
                is LinearLayout -> LinearLayout.LayoutParams(
                    (120 * density).toInt(),
                    (48 * density).toInt()
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                is FrameLayout -> FrameLayout.LayoutParams(
                    (120 * density).toInt(),
                    (48 * density).toInt()
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                else -> ViewGroup.LayoutParams(
                    (120 * density).toInt(),
                    (48 * density).toInt()
                )
            }
            layoutParams = lp
        }

        if (container is LinearLayout) {
            container.addView(animView, 1.coerceAtMost(container.childCount))
        } else {
            container.addView(animView)
        }

        // Wait for container measurement to get exact screen/layout width
        container.post {
            if (container.indexOfChild(animView) == -1) return@post
            
            val containerWidth = if (container.width > 0) container.width.toFloat() else 1200f
            val startX = -150f * context.resources.displayMetrics.density
            val endX = containerWidth + (50f * context.resources.displayMetrics.density)

            val animator = ObjectAnimator.ofFloat(animView, "translationX", startX, endX).apply {
                duration = 3800 // smooth, elegant pace
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
            }
            
            // Store animator reference on the view tag to prevent memory leaks and cancel cleanly
            animView.setTag(R.id.lottieEmptyFolder, animator)
            animator.start()
        }
    }

    /**
     * Stops the animation and safely detaches the animated view from the container.
     */
    fun stopAnimation(container: ViewGroup) {
        val animView = container.findViewWithTag<ImageView>(ANIM_VIEW_TAG) ?: return
        
        val animator = animView.getTag(R.id.lottieEmptyFolder) as? ObjectAnimator
        animator?.cancel()
        
        container.removeView(animView)
    }
}
