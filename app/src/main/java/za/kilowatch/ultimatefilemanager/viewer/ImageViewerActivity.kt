package za.kilowatch.ultimatefilemanager.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import coil3.ImageLoader
import coil3.asDrawable
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.svg.SvgDecoder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import java.io.File
import java.io.FileOutputStream

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtTitle: TextView
    private lateinit var txtInfo: TextView
    private lateinit var drawingOverlay: DrawingOverlayView
    private lateinit var btnDrawToggle: View
    private lateinit var btnCropToggle: View
    private lateinit var btnImageSave: View

    private val matrix = Matrix()
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var imageWidth = 0
    private var imageHeight = 0
    private var rotationDegrees = 0f
    private var isTv = false

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var currentImageFile: File? = null
    private var isDrawMode = false

    private val coilLoader by lazy {
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(AnimatedPngDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .build()
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_image_viewer_tv
            else R.layout.activity_image_viewer
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Mobile toolbar — absorbs status-bar top + landscape side insets
            findViewById<android.view.View>(R.id.toolbar)
                ?.setPadding(sb.left, sb.top, sb.right, 0)
            // TV header — same treatment for consistency
            findViewById<android.view.View>(R.id.layoutTvHeader)
                ?.setPadding(sb.left, sb.top, sb.right, 0)
            // Mobile image container
            val imageContainer = findViewById<android.view.View>(R.id.imageContainer)
            if (imageContainer != null) {
                val topPadding = (8 * resources.displayMetrics.density).toInt()
                imageContainer.setPadding(sb.left, topPadding, sb.right, sb.bottom)
            }
            // TV image container
            val scrollContainer = findViewById<android.view.View>(R.id.scrollContainer)
            if (scrollContainer != null) {
                scrollContainer.setPadding(sb.left, 0, sb.right, 0)
            }
            // TV control bar — absorb bottom inset (navigation bar)
            findViewById<android.view.View>(R.id.tvControlBar)
                ?.setPadding(sb.left, 0, sb.right, sb.bottom)
            WindowInsetsCompat.CONSUMED
        }

        imageView   = findViewById(R.id.imageView)
        progressBar = findViewById(R.id.progressBar)
        txtTitle    = findViewById(R.id.txtTitle)
        txtInfo     = findViewById(R.id.txtInfo)
        drawingOverlay = findViewById(R.id.drawingOverlay)

        intent.getStringExtra(FileViewerRouter.EXTRA_TRANSITION_NAME)?.let { name ->
            imageView.transitionName = name
        }

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        val pdfBtn = findViewById<View>(R.id.btnConvertToPdf)
        if (pdfBtn != null) {
            pdfBtn.setOnClickListener { showConvertToPdfDialog() }
        }

        imageView.scaleType = ImageView.ScaleType.MATRIX

        initGestures()
        setupTouchHandling()

        if (isTv) {
            btnDrawToggle = findViewById(R.id.btnDrawToggleTv)
            btnCropToggle = findViewById(R.id.btnCropToggleTv)
            btnImageSave = findViewById(R.id.btnImageSaveTv)
            setupTvControls()
        } else {
            btnDrawToggle = findViewById(R.id.btnDrawToggle)
            btnCropToggle = findViewById(R.id.btnCropToggle)
            btnImageSave = findViewById(R.id.btnImageSave)
            setupMobileControls()
        }

        setupViewer()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // The Activity is not recreated on rotation (configChanges declared in manifest).
        // Re-fit the image to the new view dimensions after layout has been updated.
        imageView.post { fitImageToView() }
    }

    private fun setupMobileControls() {
        btnDrawToggle.setOnClickListener { toggleDrawMode() }
        btnCropToggle.setOnClickListener { toggleCropMode() }
        btnImageSave.setOnClickListener { saveImage() }
    }

    private fun showConvertToPdfDialog() {
        val filePath = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: return
        val fileName = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME)
            ?.let { File(it).nameWithoutExtension }
            ?: File(filePath).nameWithoutExtension
        val dialog = za.kilowatch.ultimatefilemanager.ui.ConvertToPdfDialog().apply {
            arguments = android.os.Bundle().apply {
                putString("original_filename", fileName)
                putString("image_path", filePath)
            }
        }
        dialog.show(supportFragmentManager, "ConvertToPdfDialog")
    }

    private fun toggleDrawMode() {
        isDrawMode = !isDrawMode
        if (isDrawMode) {
            drawingOverlay.visibility = View.VISIBLE
            drawingOverlay.isDrawingMode = true
            drawingOverlay.isCropMode = false
            btnCropToggle.visibility = View.VISIBLE
            btnImageSave.visibility = View.VISIBLE
            Toast.makeText(this, R.string.drawing_mode, Toast.LENGTH_SHORT).show()
        } else {
            drawingOverlay.isDrawingMode = false
            drawingOverlay.isCropMode = false
            drawingOverlay.visibility = View.GONE
            btnCropToggle.visibility = View.GONE
            btnImageSave.visibility = View.GONE
        }
    }

    private fun toggleCropMode() {
        drawingOverlay.isCropMode = !drawingOverlay.isCropMode
        if (drawingOverlay.isCropMode) {
            drawingOverlay.isDrawingMode = false
            Toast.makeText(this, R.string.crop_mode_active, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.crop_mode_off, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImage() {
        val file = currentImageFile ?: return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val drawingBmp = drawingOverlay.getDrawingBitmap()
                val cropRect = drawingOverlay.computeCropRect()
                val srcBmp = coilLoader.execute(
                    ImageRequest.Builder(this@ImageViewerActivity)
                        .data(file)
                        .allowHardware(false)
                        .build()
                ).image?.asDrawable(resources)?.let {
                    val bmp = Bitmap.createBitmap(it.intrinsicWidth, it.intrinsicHeight, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    it.setBounds(0, 0, c.width, c.height)
                    it.draw(c)
                    bmp
                } ?: return@launch

                val resultBmp = if (drawingBmp != null) {
                    val composite = srcBmp.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(composite)
                    val scaleX = srcBmp.width.toFloat() / drawingOverlay.width
                    val scaleY = srcBmp.height.toFloat() / drawingOverlay.height
                    canvas.drawBitmap(drawingBmp, Matrix().apply { postScale(scaleX, scaleY) }, null)
                    composite
                } else srcBmp

                val finalBmp = if (cropRect != null && drawingOverlay.isCropMode) {
                    val scaleX = resultBmp.width.toFloat() / drawingOverlay.width
                    val scaleY = resultBmp.height.toFloat() / drawingOverlay.height
                    val srcRect = android.graphics.Rect(
                        (cropRect.left * scaleX).toInt(),
                        (cropRect.top * scaleY).toInt(),
                        (cropRect.right * scaleX).toInt(),
                        (cropRect.bottom * scaleY).toInt()
                    )
                    Bitmap.createBitmap(resultBmp,
                        maxOf(0, srcRect.left), maxOf(0, srcRect.top),
                        minOf(srcRect.width(), resultBmp.width - maxOf(0, srcRect.left)),
                        minOf(srcRect.height(), resultBmp.height - maxOf(0, srcRect.top)))
                } else resultBmp

                val ext = file.extension.ifEmpty { "png" }
                val saveFile = File(file.parentFile, "${file.nameWithoutExtension}_edited.$ext")
                FileOutputStream(saveFile).use { out ->
                    when (ext.lowercase()) {
                        "jpg", "jpeg" -> finalBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        "png" -> finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        "webp" -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                finalBmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                            } else {
                                @Suppress("DEPRECATION")
                                finalBmp.compress(Bitmap.CompressFormat.WEBP, 90, out)
                            }
                        }
                        else -> finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                if (drawingBmp != null) drawingBmp.recycle()
                if (resultBmp !== srcBmp) resultBmp.recycle()
                if (finalBmp !== resultBmp) finalBmp.recycle()
                srcBmp.recycle()

                runOnUiThread {
                    Toast.makeText(this@ImageViewerActivity,
                        getString(R.string.image_saved, saveFile.name), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@ImageViewerActivity,
                        getString(R.string.image_save_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun initGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isDrawMode) return false
                val focusX = detector.focusX
                val focusY = detector.focusY
                val oldScale = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.25f, 10f)
                val r = scaleFactor / oldScale
                translateX = focusX - (focusX - translateX) * r
                translateY = focusY - (focusY - translateY) * r
                updateMatrix()
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isDrawMode) return false
                scaleFactor = 1f
                translateX  = 0f
                translateY  = 0f
                fitImageToView()
                return true
            }
        })
    }

    private fun setupTouchHandling() {
        imageView.setOnTouchListener { _, event ->
            if (isDrawMode && drawingOverlay.visibility == View.VISIBLE) {
                drawingOverlay.onTouchEvent(event)
                return@setOnTouchListener true
            }
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0) {
                            val x = event.getX(pointerIndex)
                            val y = event.getY(pointerIndex)
                            translateX += x - lastTouchX
                            translateY += y - lastTouchY
                            lastTouchX = x
                            lastTouchY = y
                            updateMatrix()
                        }
                    } else {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0) {
                            lastTouchX = event.getX(pointerIndex)
                            lastTouchY = event.getY(pointerIndex)
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == activePointerId) {
                        val newPointerIndex = if (pointerIndex == 0) 1 else 0
                        if (newPointerIndex < event.pointerCount) {
                            lastTouchX = event.getX(newPointerIndex)
                            lastTouchY = event.getY(newPointerIndex)
                            activePointerId = event.getPointerId(newPointerIndex)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
            true
        }
    }

    private fun setupViewer() {
        findViewById<View>(R.id.btnRotateLeft)?.setOnClickListener  { rotateImage(-90f) }
        findViewById<View>(R.id.btnRotateRight)?.setOnClickListener { rotateImage(90f)  }

        val filePath = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: run {
            finish(); return
        }
        val fileName = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME)
            ?: getString(R.string.image_3)
        txtTitle.text = fileName
        currentImageFile = File(filePath)

        loadImage(currentImageFile!!)

        if (isTv) setupTvControls()

        postponeEnterTransition()
    }

    private fun setupTvControls() {
        val panStep = 80f
        val zoomIn  = 1.20f
        val zoomOut = 1f / zoomIn

        val iconTint  = androidx.core.content.ContextCompat.getColor(this, R.color.tv_icon_tint)
        val blackIcon = android.graphics.Color.parseColor("#FF0F0F0F")

        fun wire(btnId: Int, iconIdx: Int = 0, action: () -> Unit) {
            val container = findViewById<android.view.ViewGroup>(btnId) ?: return
            val icon = container.getChildAt(iconIdx) as? android.widget.ImageView
            container.setOnClickListener { action() }
            container.setOnFocusChangeListener { _, hasFocus ->
                icon?.setColorFilter(if (hasFocus) blackIcon else iconTint)
            }
        }

        wire(R.id.btnPanLeft)     { if (!isDrawMode) { translateX += panStep;  updateMatrix() } }
        wire(R.id.btnPanRight)    { if (!isDrawMode) { translateX -= panStep;  updateMatrix() } }
        wire(R.id.btnPanUp)       { if (!isDrawMode) { translateY += panStep;  updateMatrix() } }
        wire(R.id.btnPanDown)     { if (!isDrawMode) { translateY -= panStep;  updateMatrix() } }
        wire(R.id.btnZoomIn)      { if (!isDrawMode) { scaleFactor = (scaleFactor * zoomIn).coerceIn(0.25f, 10f);  updateMatrix() } }
        wire(R.id.btnZoomOut)     { if (!isDrawMode) { scaleFactor = (scaleFactor * zoomOut).coerceIn(0.25f, 10f); updateMatrix() } }
        wire(R.id.btnRotateLeft)  { if (!isDrawMode) { rotateImage(-90f) } }
        wire(R.id.btnRotateRight) { if (!isDrawMode) { rotateImage(90f)  } }
        wire(R.id.btnFitReset)    { rotationDegrees = 0f; fitImageToView() }
        wire(R.id.btnDrawToggleTv) { toggleDrawMode() }
        wire(R.id.btnCropToggleTv) { toggleCropMode() }
        wire(R.id.btnImageSaveTv)  { saveImage() }

        val btnBack = findViewById<android.widget.ImageView>(R.id.btnBack)
        btnBack?.setOnFocusChangeListener { _, hasFocus ->
            btnBack.setColorFilter(if (hasFocus) blackIcon else iconTint)
        }
        val btnConvertToPdf = findViewById<android.widget.ImageView>(R.id.btnConvertToPdf)
        btnConvertToPdf?.setOnFocusChangeListener { _, hasFocus ->
            btnConvertToPdf.setColorFilter(if (hasFocus) blackIcon else iconTint)
        }

        findViewById<android.view.View>(R.id.btnPanLeft)?.requestFocus()
    }

    private fun loadImage(file: File) {
        progressBar.visibility = View.VISIBLE

        val request = ImageRequest.Builder(this)
            .data(file)
            .allowHardware(false)
            .target(
                onStart = {
                    progressBar.visibility = View.VISIBLE
                },
                onSuccess = { image ->
                    val drawable = image.asDrawable(resources)

                    imageWidth  = drawable.intrinsicWidth.takeIf  { it > 0 } ?: imageView.width
                    imageHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: imageView.height

                    imageView.setImageDrawable(drawable)

                    (drawable as? Animatable)?.start()

                    val fileSize = formatFileSize(file.length())
                    txtInfo.text = "${imageWidth} \u00d7 ${imageHeight}  \u2022  $fileSize"
                    txtInfo.visibility = View.VISIBLE

                    progressBar.visibility = View.GONE

                    imageView.post {
                        fitImageToView()
                        startPostponedEnterTransition()
                    }
                },
                onError = { _ ->
                    progressBar.visibility = View.GONE
                    txtInfo.setText(R.string.unable_to_decode_image)
                    txtInfo.visibility = View.VISIBLE
                    startPostponedEnterTransition()
                }
            )
            .build()

        coilLoader.enqueue(request)
    }

    private fun fitImageToView() {
        if (imageWidth == 0 || imageHeight == 0) return
        val viewWidth  = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return

        val isSwapped = (rotationDegrees / 90f).toInt() % 2 != 0
        val w = if (isSwapped) imageHeight.toFloat() else imageWidth.toFloat()
        val h = if (isSwapped) imageWidth.toFloat()  else imageHeight.toFloat()

        val scaleX = viewWidth  / w
        val scaleY = viewHeight / h
        scaleFactor = minOf(scaleX, scaleY)

        translateX = (viewWidth  - w * scaleFactor) / 2f
        translateY = (viewHeight - h * scaleFactor) / 2f

        updateMatrix()
    }

    private fun rotateImage(delta: Float) {
        rotationDegrees = (rotationDegrees + delta) % 360f
        if (rotationDegrees < 0) rotationDegrees += 360f
        fitImageToView()
    }

    private fun updateMatrix() {
        matrix.reset()
        matrix.postRotate(rotationDegrees, imageWidth / 2f, imageHeight / 2f)
        matrix.postScale(scaleFactor, scaleFactor)

        val pts = floatArrayOf(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        matrix.mapPoints(pts)

        matrix.reset()
        matrix.postRotate(rotationDegrees, imageWidth / 2f, imageHeight / 2f)

        val rect = android.graphics.RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        matrix.mapRect(rect)
        matrix.postTranslate(-rect.left, -rect.top)

        matrix.postScale(scaleFactor, scaleFactor)
        matrix.postTranslate(translateX, translateY)

        imageView.imageMatrix = matrix
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> getString(R.string.bytes_b)
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(getString(R.string.q1f_mb), bytes / (1024.0 * 1024.0))
        }
    }
}
