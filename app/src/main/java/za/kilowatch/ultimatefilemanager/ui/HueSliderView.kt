package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class HueSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rainbowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumbStroke  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x66000000.toInt()
    }

    var currentHue: Float = 0f
        set(value) { field = value; updateThumb(); invalidate() }

    var onHueChanged: ((Float) -> Unit)? = null

    private var thumbY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val hues = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN,
            Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        rainbowPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), hues, null, Shader.TileMode.CLAMP)
        updateThumb()
    }

    private fun updateThumb() {
        if (height > 0) thumbY = (currentHue / 360f) * height
    }

    override fun onDraw(canvas: Canvas) {
        val dp = resources.displayMetrics.density
        val rx = width / 2f
        // Draw the rainbow strip
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), rx, rx, rainbowPaint)

        // Draw pill-shaped thumb (wider than strip, centred)
        val tw = width + 8 * dp
        val th = 14 * dp
        val tx = (width - tw) / 2f
        val ty = thumbY - th / 2f
        val pill = RectF(tx, ty.coerceIn(0f, height - th), tx + tw, (ty + th).coerceIn(th, height.toFloat()))
        canvas.drawRoundRect(pill, th / 2f, th / 2f, thumbFill)
        canvas.drawRoundRect(pill, th / 2f, th / 2f, thumbStroke)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            val y = e.y.coerceIn(0f, height.toFloat())
            currentHue = (y / height) * 360f
            thumbY = y
            onHueChanged?.invoke(currentHue)
            invalidate()
            return true
        }
        return super.onTouchEvent(e)
    }
}
