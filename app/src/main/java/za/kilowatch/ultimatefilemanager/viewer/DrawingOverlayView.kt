package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val drawPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val cropPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val cropFillPaint = Paint().apply {
        color = 0x88000000.toInt()
        style = Paint.Style.FILL
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#FF0284C7")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private var paths = mutableListOf<DrawPath>()
    private var currentPath: DrawPath? = null
    private var drawColor = Color.RED
    private var strokeWidth = 6f
    var isDrawingMode = false
    var isCropMode = false

    var cropRect = RectF(0.1f, 0.1f, 0.9f, 0.9f)
    private var draggingCorner: Int = -1
    private val cornerRadius = 24f
    private val dragThreshold = 36f

    private var viewWidth = 1f
    private var viewHeight = 1f

    fun setDrawColor(color: Int) {
        drawColor = color
    }

    fun clearDrawing() {
        paths.clear()
        currentPath = null
        invalidate()
    }

    fun getDrawingBitmap(): Bitmap? {
        if (paths.isEmpty() && !isCropMode) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (path in paths) {
            val p = Paint().apply {
                color = path.color
                strokeWidth = path.strokeWidth
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                isAntiAlias = true
            }
            canvas.drawPath(path.path, p)
        }
        return bmp
    }

    fun computeCropRect(): RectF? {
        if (!isCropMode) return null
        return RectF(
            cropRect.left * viewWidth,
            cropRect.top * viewHeight,
            cropRect.right * viewWidth,
            cropRect.bottom * viewHeight
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w.toFloat()
        viewHeight = h.toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingMode && !isCropMode) return false

        if (isCropMode) return handleCropTouch(event)
        return handleDrawTouch(event)
    }

    private fun handleDrawTouch(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = DrawPath(android.graphics.Path(), drawColor, strokeWidth)
                currentPath?.path?.moveTo(x, y)
                paths.add(currentPath!!)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.path?.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath = null
                invalidate()
                return true
            }
        }
        return false
    }

    private fun handleCropTouch(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val r = RectF(
            cropRect.left * viewWidth,
            cropRect.top * viewHeight,
            cropRect.right * viewWidth,
            cropRect.bottom * viewHeight
        )

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingCorner = getCornerAt(x, y, r)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingCorner >= 0) {
                    val nx = (x / viewWidth).coerceIn(0f, 1f)
                    val ny = (y / viewHeight).coerceIn(0f, 1f)
                    when (draggingCorner) {
                        0 -> { cropRect.left = nx; cropRect.top = ny }
                        1 -> { cropRect.right = nx; cropRect.top = ny }
                        2 -> { cropRect.left = nx; cropRect.bottom = ny }
                        3 -> { cropRect.right = nx; cropRect.bottom = ny }
                    }
                    cropRect.sort()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingCorner = -1
                invalidate()
                return true
            }
        }
        return false
    }

    private fun getCornerAt(x: Float, y: Float, r: RectF): Int {
        val corners = listOf(
            PointF(r.left, r.top),
            PointF(r.right, r.top),
            PointF(r.left, r.bottom),
            PointF(r.right, r.bottom)
        )
        for ((i, corner) in corners.withIndex()) {
            val dx = x - corner.x
            val dy = y - corner.y
            if (dx * dx + dy * dy <= dragThreshold * dragThreshold) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (path in paths) {
            val p = Paint(drawPaint).apply {
                color = path.color
                strokeWidth = path.strokeWidth
            }
            canvas.drawPath(path.path, p)
        }
        if (isCropMode) {
            val w = viewWidth
            val h = viewHeight
            val r = RectF(
                cropRect.left * w, cropRect.top * h,
                cropRect.right * w, cropRect.bottom * h
            )
            canvas.drawRect(0f, 0f, w, r.top, cropFillPaint)
            canvas.drawRect(0f, r.bottom, w, h, cropFillPaint)
            canvas.drawRect(0f, r.top, r.left, r.bottom, cropFillPaint)
            canvas.drawRect(r.right, r.top, w, r.bottom, cropFillPaint)
            canvas.drawRect(r, cropPaint)
            val corners = listOf(
                PointF(r.left, r.top),
                PointF(r.right, r.top),
                PointF(r.left, r.bottom),
                PointF(r.right, r.bottom)
            )
            for (corner in corners) {
                canvas.drawCircle(corner.x, corner.y, cornerRadius, cornerPaint)
            }
        }
    }

    private class DrawPath(
        val path: android.graphics.Path,
        val color: Int,
        val strokeWidth: Float
    )
}
