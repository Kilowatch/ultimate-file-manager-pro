package za.kilowatch.ultimatefilemanager.billing

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A full-overlay View that rains themed confetti particles (circles, stars, coffee-bean ovals).
 * Attach to a ViewGroup root, then call [start]. Automatically removes itself when done.
 *
 * @param colors       Integer color values for the particles (per-tier palette).
 * @param durationMs   Total animation duration in milliseconds.
 */
class ConfettiView(
    context: Context,
    private val colors: IntArray,
    private val durationMs: Long = 3_200L
) : View(context) {

    private data class Particle(
        var x: Float,
        var y: Float,
        val vx: Float,      // horizontal drift
        val vy: Float,      // fall speed
        val rotation: Float, // spin speed (degrees/frame)
        var angle: Float,    // current rotation angle
        val size: Float,
        val color: Int,
        val shape: Shape
    )

    private enum class Shape { CIRCLE, OVAL, STAR }

    private val particles = mutableListOf<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPath = Path()
    private val ovalRect = RectF()

    private var progress = 0f  // 0..1
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable  = false
    }

    /** Populate particles and start the animation. */
    fun start(count: Int = 80) {
        val shapes = Shape.entries.toTypedArray()
        repeat(count) {
            val size = dpToPx(Random.nextFloat() * 6f + 5f)
            particles += Particle(
                x         = Random.nextFloat(),          // 0..1 of width
                y         = -(Random.nextFloat() * 0.3f), // start slightly above view
                vx        = (Random.nextFloat() - 0.5f) * 0.004f,
                vy        = Random.nextFloat() * 0.006f + 0.004f,
                rotation  = (Random.nextFloat() - 0.5f) * 8f,
                angle     = Random.nextFloat() * 360f,
                size      = size,
                color     = colors[Random.nextInt(colors.size)],
                shape     = shapes[Random.nextInt(shapes.size)]
            )
        }

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration    = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                progress = anim.animatedFraction
                particles.forEach { p ->
                    p.x    += p.vx
                    p.y    += p.vy
                    p.angle += p.rotation
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Fade out in the last 30% of the animation
        val alpha = if (progress > 0.7f) ((1f - progress) / 0.3f * 255).toInt() else 255

        particles.forEach { p ->
            if (p.y > 1.1f) return@forEach  // off-screen bottom
            val cx = p.x * w
            val cy = p.y * h

            paint.color = p.color
            paint.alpha = alpha

            canvas.save()
            canvas.rotate(p.angle, cx, cy)

            when (p.shape) {
                Shape.CIRCLE -> canvas.drawCircle(cx, cy, p.size / 2f, paint)
                Shape.OVAL   -> {
                    ovalRect.set(cx - p.size / 2f, cy - p.size / 3f,
                                 cx + p.size / 2f, cy + p.size / 3f)
                    canvas.drawOval(ovalRect, paint)
                }
                Shape.STAR   -> {
                    canvas.drawPath(buildStarPath(cx, cy, p.size / 2f, p.size / 4f, 5), paint)
                }
            }

            canvas.restore()
        }
    }

    /** Cleanly stop and remove the view from its parent. */
    fun stop() {
        animator?.cancel()
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    private fun buildStarPath(cx: Float, cy: Float, outerR: Float, innerR: Float, points: Int): Path {
        starPath.reset()
        val step = Math.PI / points
        for (i in 0 until points * 2) {
            val r   = if (i % 2 == 0) outerR else innerR
            val ang = i * step - Math.PI / 2
            val x   = (cx + r * cos(ang)).toFloat()
            val y   = (cy + r * sin(ang)).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
        return starPath
    }

    private fun dpToPx(dp: Float): Float =
        dp * context.resources.displayMetrics.density
}
