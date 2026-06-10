package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom view that draws a horizontal segmented bar chart.
 * Each segment represents a file category with a different color and size.
 */
class StorageBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Segment(
        val label: String,
        val bytes: Long,
        val color: Int
    )

    private val segments = mutableListOf<Segment>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0E0E0.toInt()
    }
    private val rect = RectF()
    private var cornerRadius = 12f

    fun setSegments(newSegments: List<Segment>) {
        segments.clear()
        segments.addAll(newSegments)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        cornerRadius = h / 2f

        // Draw background
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        val total = segments.sumOf { it.bytes }
        if (total <= 0) return

        var startX = 0f
        for ((index, segment) in segments.withIndex()) {
            val segmentWidth = (segment.bytes.toFloat() / total.toFloat()) * w
            if (segmentWidth < 1f) continue

            paint.color = segment.color
            val endX = (startX + segmentWidth).coerceAtMost(w)

            // First segment gets left corners, last gets right corners
            rect.set(startX, 0f, endX, h)
            if (index == 0 && segments.size == 1) {
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            } else if (index == 0) {
                // Left rounded
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
                // Fill right corners
                canvas.drawRect(endX - cornerRadius, 0f, endX, h, paint)
            } else if (index == segments.lastIndex) {
                // Right rounded
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
                // Fill left corners
                canvas.drawRect(startX, 0f, startX + cornerRadius, h, paint)
            } else {
                canvas.drawRect(rect, paint)
            }

            startX = endX
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (16 * resources.displayMetrics.density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
