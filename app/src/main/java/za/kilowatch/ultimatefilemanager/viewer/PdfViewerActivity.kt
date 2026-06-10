package za.kilowatch.ultimatefilemanager.viewer

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.HorizontalScrollView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Built-in PDF viewer using Android's PdfRenderer API.
 * Renders pages as bitmaps in a RecyclerView for vertical scrolling.
 * Supports pinch-to-zoom, page indicator, and password-protected PDFs.
 */
class PdfViewerActivity : AppCompatActivity() {

    private lateinit var recyclerPages: RecyclerView
    private lateinit var pdfHScrollView: HorizontalScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtTitle: TextView
    private lateinit var txtPageInfo: TextView
    private var txtZoomInfo: TextView? = null

    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var scaleFactor = 1.0f  // Will be adjusted on first page load
    private var gestureScale = 1.0f
    private lateinit var scaleDetector: ScaleGestureDetector
    private var pageAdapter: PdfPageAdapter? = null
    private val renderMutex = Mutex()
    @Volatile private var rendererClosed = false
    private val renderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Temp file created when decrypting a password-protected PDF; deleted in onDestroy. */
    private var decryptedTempFile: File? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_pdf_viewer_tv
            else R.layout.activity_pdf_viewer
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        recyclerPages = findViewById(R.id.recyclerPages)
        pdfHScrollView = findViewById(R.id.pdfHScrollView)
        progressBar = findViewById(R.id.progressBar)
        txtTitle = findViewById(R.id.txtTitle)
        txtPageInfo = findViewById(R.id.txtPageInfo)
        txtZoomInfo = findViewById<TextView?>(R.id.txtZoomInfo)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val filePath = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: run {
            finish(); return
        }
        val fileName = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME) ?: "PDF"
        txtTitle.text = fileName

        // Pinch-to-zoom for the whole view
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var childPosition = RecyclerView.NO_POSITION
            private var oldOffset = 0f
            private var pivotX = 0f
            private var pivotY = 0f
            private var startFocusX = 0f
            private var startFocusY = 0f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pdfHScrollView.requestDisallowInterceptTouchEvent(true)
                recyclerPages.requestDisallowInterceptTouchEvent(true)

                val focusX = detector.focusX
                val focusY = detector.focusY

                val childView = recyclerPages.findChildViewUnder(focusX, focusY)
                    ?: (recyclerPages.layoutManager as? LinearLayoutManager)?.let { lm ->
                        val firstPos = lm.findFirstVisibleItemPosition()
                        if (firstPos >= 0) lm.findViewByPosition(firstPos) else null
                    }

                if (childView != null) {
                    childPosition = recyclerPages.getChildAdapterPosition(childView)
                    if (childPosition != RecyclerView.NO_POSITION) {
                        oldOffset = focusY - childView.top
                    }
                } else {
                    childPosition = RecyclerView.NO_POSITION
                }

                val loc = IntArray(2)
                recyclerPages.getLocationInWindow(loc)
                pivotX = focusX - loc[0]
                pivotY = focusY - loc[1]
                recyclerPages.pivotX = pivotX
                recyclerPages.pivotY = pivotY
                startFocusX = focusX
                startFocusY = focusY
                gestureScale = 1.0f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                gestureScale = (gestureScale * detector.scaleFactor).coerceIn(0.5f / scaleFactor, 3.0f / scaleFactor)
                recyclerPages.scaleX = gestureScale
                recyclerPages.scaleY = gestureScale
                recyclerPages.translationX = detector.focusX - startFocusX
                recyclerPages.translationY = detector.focusY - startFocusY
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val targetScale = (scaleFactor * gestureScale).coerceIn(0.25f, 8.0f)
                gestureScale = targetScale / scaleFactor
                scaleFactor = targetScale

                recyclerPages.scaleX = 1.0f
                recyclerPages.scaleY = 1.0f
                recyclerPages.translationX = 0f
                recyclerPages.translationY = 0f

                pageAdapter?.updateScale(scaleFactor)

                val focusX = detector.focusX
                val focusY = detector.focusY

                val scrollLoc = IntArray(2)
                pdfHScrollView.getLocationInWindow(scrollLoc)

                recyclerPages.post {
                    // 1. Adjust horizontal scroll
                    val newScrollX = (pivotX * gestureScale - (focusX - scrollLoc[0])).toInt()
                    pdfHScrollView.scrollTo(maxOf(0, newScrollX), 0)

                    // 2. Adjust vertical scroll via scrollToPositionWithOffset
                    if (childPosition != RecyclerView.NO_POSITION) {
                        val newOffset = oldOffset * gestureScale
                        val newTop = focusY - newOffset
                        (recyclerPages.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(childPosition, newTop.toInt())
                    }
                }
                updateZoomIndicator()
            }
        })

        // Track scroll for page indicator
        recyclerPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                val total = pageAdapter?.itemCount ?: 0
                if (total > 0 && firstVisible >= 0) {
                    txtPageInfo.text = getString(R.string.page_firstvisible_1_total, firstVisible + 1, total)
                }
            }
        })

        loadPdf(File(filePath))

        // TV D-pad scroll support
        if (isTv) {
            val scrollContainer = findViewById<View>(R.id.scrollContainer)
            scrollContainer?.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val scrollAmount = (80 * resources.displayMetrics.density).toInt()
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            recyclerPages.smoothScrollBy(0, scrollAmount); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (!recyclerPages.canScrollVertically(-1)) false
                            else { recyclerPages.smoothScrollBy(0, -scrollAmount); true }
                        }
                        else -> false
                    }
                } else false
            }
            scrollContainer?.requestFocus()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun loadPdf(file: File, password: String? = null) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val renderFile = if (password != null) {
                    // Decrypt to a temp file so PdfRenderer can open it
                    decryptAndSaveTemp(file, password) ?: run {
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = View.GONE
                            showPasswordDialog(file, incorrect = true)
                        }
                        return@launch
                    }
                } else {
                    file
                }

                fileDescriptor = ParcelFileDescriptor.open(renderFile, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(fileDescriptor!!)
                val pageCount = pdfRenderer!!.pageCount

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    txtPageInfo.text = getString(R.string.page_1_pagecount, pageCount)
                    // Calculate initial scale to fit page width to screen
                    val paddingDp = 8f // 4dp left + 4dp right from RecyclerView padding
                    val density = resources.displayMetrics.density
                    val screenWidth = resources.displayMetrics.widthPixels
                    val availableWidth = screenWidth - (paddingDp * density).toInt()
                    val firstPage = pdfRenderer?.openPage(0)
                    if (firstPage != null) {
                        scaleFactor = (availableWidth.toFloat() / firstPage.width).coerceIn(0.25f, 8.0f)
                        firstPage.close()
                    }
                    pageAdapter = PdfPageAdapter(pageCount, scaleFactor)
                    recyclerPages.layoutManager = LinearLayoutManager(this@PdfViewerActivity)
                    recyclerPages.adapter = pageAdapter
                    updateZoomIndicator()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (isPdfEncrypted(file)) {
                        showPasswordDialog(file, incorrect = false)
                    } else {
                        txtPageInfo.text = getString(R.string.error_emessage, e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    /** Returns true if PDFBox detects the file is password-protected. */
    private fun isPdfEncrypted(file: File): Boolean {
        return try {
            PDFBoxResourceLoader.init(this)
            PDDocument.load(file).use { false }  // opened without password → not encrypted
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Uses PDFBox to decrypt [file] with [password] and saves the result to a
     * temp file in cacheDir. Returns the temp file, or null if the password is wrong.
     */
    private fun decryptAndSaveTemp(file: File, password: String): File? {
        return try {
            PDFBoxResourceLoader.init(this)
            val doc = PDDocument.load(file, password)
            doc.isAllSecurityToBeRemoved = true
            val temp = File(cacheDir, "pdf_decrypted_${System.currentTimeMillis()}.pdf")
            doc.save(temp)
            doc.close()
            decryptedTempFile?.delete()   // clean up previous temp if any
            decryptedTempFile = temp
            temp
        } catch (_: Exception) {
            null  // wrong password or other error
        }
    }

    /**
     * Shows a non-cancellable password prompt dialog.
     * If [incorrect] is true, shows the "incorrect password" error immediately.
     */
    private fun showPasswordDialog(file: File, incorrect: Boolean) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_password_prompt, null)
        val tilPassword = dialogView.findViewById<TextInputLayout>(R.id.tilPassword)
        val edtPassword = dialogView.findViewById<TextInputEditText>(R.id.edtPassword)

        // Hide the layout's own buttons — AlertDialog supplies OK / Cancel
        dialogView.findViewById<View>(R.id.btnUnlock)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.btnCancel)?.visibility = View.GONE

        if (incorrect) {
            tilPassword?.error = getString(R.string.pdf_password_incorrect)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pdf_password_prompt_title))
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = edtPassword?.text?.toString() ?: ""
                if (entered.isNotEmpty()) {
                    closePdfRenderer()   // serialised close before re-opening
                    rendererClosed = false  // reset so the next render can proceed
                    loadPdf(file, entered)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun updateZoomIndicator() {
        txtZoomInfo?.let {
            it.text = getString(R.string.zoom_level, (scaleFactor * 100).toInt())
            it.visibility = View.VISIBLE
            it.postDelayed({ it.visibility = View.GONE }, 2000)
        }
    }

    /**
     * Closes the PdfRenderer and file descriptor under [renderMutex] so that
     * no in-flight [page.render] JNI call can overlap with the native teardown.
     * Safe to call from any thread.
     */
    private fun closePdfRenderer() = runBlocking {
        renderMutex.withLock {
            rendererClosed = true
            try { pdfRenderer?.close() } catch (_: Exception) {}
            try { fileDescriptor?.close() } catch (_: Exception) {}
            pdfRenderer = null
            fileDescriptor = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closePdfRenderer()      // waits for any in-flight render to finish first
        renderScope.cancel()    // then cancel the scope so no new renders start
        decryptedTempFile?.delete()
    }

    /**
     * RecyclerView adapter that renders PDF pages on demand as bitmaps.
     * Only renders visible pages for memory efficiency.
     */
    inner class PdfPageAdapter(
        private val pageCount: Int,
        private var currentScale: Float
    ) : RecyclerView.Adapter<PdfPageAdapter.PageVH>() {

        inner class PageVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgPage: ImageView = itemView.findViewById(R.id.imgPdfPage)
        }

        fun updateScale(newScale: Float) {
            currentScale = newScale
            notifyItemRangeChanged(0, pageCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pdf_page, parent, false)
            return PageVH(view)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            renderScope.launch {
                try {
                    renderMutex.withLock {
                        if (rendererClosed) return@withLock
                        val renderer = pdfRenderer ?: return@withLock
                        val page = renderer.openPage(position)
                        
                        val targetWidth = (page.width * currentScale).toInt()
                        val targetHeight = (page.height * currentScale).toInt()

                        // Limit maximum bitmap memory size to avoid "trying to draw too large bitmap" Canvas crash (100MB limit)
                        val maxBytes = 90 * 1024 * 1024 // 90 MB
                        var renderWidth = targetWidth
                        var renderHeight = targetHeight
                        val byteCount = renderWidth.toLong() * renderHeight.toLong() * 4
                        if (byteCount > maxBytes) {
                            val scale = Math.sqrt(maxBytes.toDouble() / (renderWidth.toLong() * renderHeight.toLong()))
                            renderWidth = maxOf(1, (renderWidth * scale).toInt())
                            renderHeight = maxOf(1, (renderHeight * scale).toInt())
                        }

                        val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        withContext(Dispatchers.Main) {
                            holder.imgPage.setImageBitmap(bitmap)
                            holder.imgPage.scaleType = ImageView.ScaleType.FIT_XY
                            val imgLp = holder.imgPage.layoutParams
                            imgLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                            imgLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                            holder.imgPage.layoutParams = imgLp

                            // Resize item to match original target size so scrolling/zoom coordinates align correctly
                            val lp = holder.itemView.layoutParams
                            lp.width = targetWidth
                            lp.height = targetHeight
                            holder.itemView.layoutParams = lp
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        override fun getItemCount() = pageCount
    }
}
