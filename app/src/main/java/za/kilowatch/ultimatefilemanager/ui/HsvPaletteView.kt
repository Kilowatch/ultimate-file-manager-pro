package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class HsvPaletteView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val huePaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE
    }
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = 0x66000000.toInt()
    }

    var currentHue = 0f; private set
    var currentSat = 1f; private set
    var currentVal = 1f; private set
    var selectedColor: Int = Color.RED; private set
    var onColorChanged: ((Int) -> Unit)? = null

    private var thumbX = 0f
    private var thumbY = 0f

    // Force square
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        buildGradients(w, h)
        updateThumb()
    }

    private fun buildGradients(w: Int, h: Int) {
        val hues = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN,
            Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        huePaint.shader  = LinearGradient(0f, 0f, w.toFloat(), 0f, hues, null, Shader.TileMode.CLAMP)
        whitePaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.WHITE, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        blackPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x00000000, Color.BLACK), null, Shader.TileMode.CLAMP)
    }

    private fun updateThumb() {
        if (width <= 0) return
        thumbX = (currentHue / 360f) * width
        thumbY = (1f - currentVal) * height
    }

    override fun onDraw(canvas: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRect(r, huePaint)
        canvas.drawRect(r, whitePaint)
        canvas.drawRect(r, blackPaint)
        val dp = resources.displayMetrics.density
        val rad = 10f * dp
        thumbFill.color = selectedColor
        canvas.drawCircle(thumbX, thumbY, rad, thumbFill)
        canvas.drawCircle(thumbX, thumbY, rad, thumbRing)
        canvas.drawCircle(thumbX, thumbY, rad + 1.5f, thumbShadow)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            val x = e.x.coerceIn(0f, width.toFloat())
            val y = e.y.coerceIn(0f, height.toFloat())
            currentHue = (x / width) * 360f
            currentSat = x / width
            currentVal = 1f - (y / height)
            thumbX = x; thumbY = y
            selectedColor = Color.HSVToColor(floatArrayOf(currentHue, currentSat, currentVal))
            onColorChanged?.invoke(selectedColor)
            invalidate()
            return true
        }
        return super.onTouchEvent(e)
    }

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        currentHue = hsv[0]; currentSat = hsv[1]; currentVal = hsv[2]
        selectedColor = color
        updateThumb()
        invalidate()
    }

    /** Called by the hue slider — keeps sat/val, only changes hue + thumb X */
    fun setHue(hue: Float) {
        currentHue = hue
        selectedColor = Color.HSVToColor(floatArrayOf(currentHue, currentSat, currentVal))
        if (width > 0) thumbX = (hue / 360f) * width
        onColorChanged?.invoke(selectedColor)
        invalidate()
    }
}
