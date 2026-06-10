package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

private fun hsvToColor(alpha: Int, hsv: FloatArray): Int {
    return android.graphics.Color.HSVToColor(alpha, hsv)
}

class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val hueSatRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    
    private val valueRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    
    private val selectorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.BLACK
    }

    private var currentHue = 0f
    private var currentSat = 1f
    private var currentValue = 1f
    
    var selectedColor: Int = Color.RED
        private set
    
    var onColorChanged: ((Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = (minOf(w, h) / 2f) - 40f
        val centerX = w / 2f
        val centerY = h / 2f
        
        hueSatRing.strokeWidth = radius * 0.15f
        
        val hueColors = IntArray(361)
        for (i in 0..360) {
            hueColors[i] = hsvToColor(255, floatArrayOf(i.toFloat(), 1f, 1f))
        }
        
        val huePositions = FloatArray(361) { it / 360f }
        
        val hueGradient = SweepGradient(centerX, centerY, hueColors, huePositions)
        hueSatRing.shader = hueGradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (minOf(width, height) / 2f) - 40f
        
        val hueRect = RectF(
            centerX - radius + hueSatRing.strokeWidth / 2,
            centerY - radius + hueSatRing.strokeWidth / 2,
            centerX + radius - hueSatRing.strokeWidth / 2,
            centerY + radius - hueSatRing.strokeWidth / 2
        )
        canvas.drawOval(hueRect, hueSatRing)
        
        valueRing.strokeWidth = radius * 0.3f
        val innerRadius = radius * 0.35f
        val valueRect = RectF(
            centerX - innerRadius - valueRing.strokeWidth / 2,
            centerY - innerRadius - valueRing.strokeWidth / 2,
            centerX + innerRadius + valueRing.strokeWidth / 2,
            centerY + innerRadius + valueRing.strokeWidth / 2
        )
        
        val valueColors = intArrayOf(Color.BLACK, hsvToColor(255, floatArrayOf(currentHue, 0f, 1f)))
        valueRing.shader = SweepGradient(centerX, centerY, valueColors, floatArrayOf(0f, 1f))
        canvas.drawOval(valueRect, valueRing)
        
        val angleRadians = currentHue * Math.PI / 180.0
        val selectorDistance = innerRadius * currentSat
        val selectorX = (centerX + selectorDistance * Math.cos(angleRadians)).toFloat()
        val selectorY = (centerY + selectorDistance * Math.sin(angleRadians)).toFloat()
        
        val selectorRadius = 20f
        selectorPaint.color = hsvToColor(255, floatArrayOf(currentHue, currentSat, currentValue))
        canvas.drawCircle(selectorX, selectorY, selectorRadius, selectorPaint)
        canvas.drawCircle(selectorX, selectorY, selectorRadius, selectorStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val centerX = width / 2f
                val centerY = height / 2f
                val radius = (minOf(width, height) / 2f) - 40f
                val innerRadius = radius * 0.35f
                
                val dx = event.x - centerX
                val dy = event.y - centerY
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                
                if (distance >= innerRadius - hueSatRing.strokeWidth / 2 && distance <= radius + hueSatRing.strokeWidth / 2) {
                    var angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
                    if (angle < 0) angle += 360
                    currentHue = angle.toFloat()
                    
                    currentSat = (distance / radius).coerceIn(0f, 1f)
                    
                    currentValue = (1f - (distance / (radius * 1.5f))).coerceIn(0f, 1f)
                    
                    selectedColor = hsvToColor(255, floatArrayOf(currentHue, currentSat, currentValue))
                    onColorChanged?.invoke(selectedColor)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        currentHue = hsv[0]
        currentSat = hsv[1]
        currentValue = hsv[2]
        selectedColor = color
        invalidate()
    }
}