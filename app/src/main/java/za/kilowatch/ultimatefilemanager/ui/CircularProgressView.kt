package za.kilowatch.ultimatefilemanager.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import za.kilowatch.ultimatefilemanager.R

/**
 * A premium donut-style circular progress indicator.
 *
 * Draws a background arc (track) and a foreground arc (progress)
 * with optional percentage text in the centre.
 * Progress animates smoothly on first draw.
 */
class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.ufm_progress_bg)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.ufm_progress_fill)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.mobile_card_text_primary)
        textSize = 32f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.mobile_card_text_secondary)
        textSize = 20f
    }

    private val arcRect = RectF()

    /** Target progress 0-100 */
    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            animateProgress()
        }

    /** Currently animated sweep value */
    private var animatedProgress: Float = 0f

    /** Whether to show the percentage text */
    var showPercentText: Boolean = true

    /** Label shown below the percentage (e.g. getString(R.string.free)) */
    var percentLabel: String = context.getString(R.string.used)

    private var animator: ValueAnimator? = null

    fun setProgressColor(color: Int) {
        progressPaint.color = color
        invalidate()
    }

    fun setStrokeWidth(widthDp: Float) {
        val px = widthDp * resources.displayMetrics.density
        trackPaint.strokeWidth = px
        progressPaint.strokeWidth = px
        invalidate()
    }

    private fun animateProgress() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animatedProgress, progress.toFloat()).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                animatedProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val halfStroke = trackPaint.strokeWidth / 2f
        val size = minOf(width, height).toFloat()
        arcRect.set(halfStroke, halfStroke, size - halfStroke, size - halfStroke)

        // Background track (full circle)
        canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)

        // Foreground progress arc
        val sweepAngle = (animatedProgress / 100f) * 360f
        canvas.drawArc(arcRect, -90f, sweepAngle, false, progressPaint)

        // Percentage text
        if (showPercentText) {
            val centerX = size / 2f
            val centerY = size / 2f

            val percentText = "${animatedProgress.toInt()}%"
            textPaint.textSize = size * 0.22f
            labelPaint.textSize = size * 0.13f

            // Draw percentage
            val textY = centerY - 2f
            canvas.drawText(percentText, centerX, textY, textPaint)

            // Draw label below
            val labelY = centerY + labelPaint.textSize + 4f
            canvas.drawText(percentLabel, centerX, labelY, labelPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = (80 * resources.displayMetrics.density).toInt()
        val width = resolveSize(desiredSize, widthMeasureSpec)
        val height = resolveSize(desiredSize, heightMeasureSpec)
        val size = minOf(width, height)
        setMeasuredDimension(size, size)
    }
}
