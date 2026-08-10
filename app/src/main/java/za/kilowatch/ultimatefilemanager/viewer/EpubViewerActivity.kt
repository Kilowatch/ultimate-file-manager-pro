package za.kilowatch.ultimatefilemanager.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File

/**
 * Built-in EPUB viewer.
 *
 * Parses the EPUB using the zero-dependency [EpubParser] (custom ZIP + XML parser),
 * extracts chapter content to a temporary cache directory, and renders each chapter
 * in a [WebView] using a file:// URI so that relative CSS/image assets resolve correctly.
 *
 * Supports:
 *  - Chapter navigation (previous / next)
 *  - Table of Contents bottom sheet
 *  - Dark mode (CSS injection when system dark mode is active)
 *  - Font size preference via [FontSizeHelper]
 *  - Mobile and TV dual-layout pattern (CLAUDE.md convention)
 */
class EpubViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtTitle: TextView
    private lateinit var txtChapterInfo: TextView    // present on both layouts
    private lateinit var btnBack: ImageView
    private lateinit var btnPrevChapter: View
    private lateinit var btnNextChapter: View
    private lateinit var btnToc: ImageView

    private var book: EpubParser.EpubBook? = null
    private var currentChapterIndex = 0

    /** Temp directory where the EPUB is extracted; cleaned up in [onDestroy]. */
    private var tempDir: File? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_epub_viewer_tv
            else       R.layout.activity_epub_viewer
        )

        // ── Insets (mobile only; TV uses safe margins in XML) ─────────────────
        if (!isTv) {
            val root = findViewById<View>(R.id.main)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        // ── Bind views ────────────────────────────────────────────────────────
        webView        = findViewById(R.id.webView)
        progressBar    = findViewById(R.id.progressBar)
        txtTitle       = findViewById(R.id.txtTitle)
        txtChapterInfo = findViewById(R.id.txtChapterInfo)
        btnBack        = findViewById(R.id.btnBack)
        btnPrevChapter = findViewById(R.id.btnPrevChapter)
        btnNextChapter = findViewById(R.id.btnNextChapter)
        btnToc         = findViewById(R.id.btnToc)

        // ── WebView configuration ─────────────────────────────────────────────
        with(webView.settings) {
            javaScriptEnabled  = false           // no scripts needed for reflowable text
            allowFileAccess    = true            // required for file:// URIs (local assets)
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true   // let chapter HTML load sibling CSS/images
            defaultFontSize    = webViewFontSize()
            defaultTextEncodingName = "UTF-8"
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Prevent navigation away from local file:// content
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectStyles(view)
            }
        }

        // ── Navigation buttons ────────────────────────────────────────────────
        btnBack.setOnClickListener { finish() }
        btnPrevChapter.setOnClickListener { navigateChapter(-1) }
        btnNextChapter.setOnClickListener { navigateChapter(+1) }
        btnToc.setOnClickListener { showTocSheet() }

        // ── Load the EPUB ─────────────────────────────────────────────────────
        val filePath = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: run {
            Toast.makeText(this, R.string.epub_error_open, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        loadEpub(File(filePath))
    }

    // ── EPUB loading ─────────────────────────────────────────────────────────

    private fun loadEpub(epubFile: File) {
        progressBar.visibility = View.VISIBLE
        txtTitle.text = epubFile.nameWithoutExtension

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "epub_${epubFile.nameWithoutExtension.hashCode()}")
                    // Always re-extract to keep temp dir fresh
                    dir.deleteRecursively()
                    tempDir = dir
                    EpubParser.parse(epubFile, dir)
                }
            }

            progressBar.visibility = View.GONE
            result.fold(
                onSuccess = { parsedBook ->
                    book = parsedBook
                    txtTitle.text = parsedBook.title
                    currentChapterIndex = 0
                    showChapter(0)
                },
                onFailure = { e ->
                    android.util.Log.e("EpubViewer", "Failed to parse EPUB", e)
                    Toast.makeText(this@EpubViewerActivity, R.string.epub_error_open, Toast.LENGTH_LONG).show()
                    finish()
                }
            )
        }
    }

    // ── Chapter display ───────────────────────────────────────────────────────

    private fun showChapter(index: Int) {
        val chapters = book?.chapters ?: return
        if (index < 0 || index >= chapters.size) return
        currentChapterIndex = index
        val chapter = chapters[index]

        // Update chapter indicator
        txtChapterInfo.text = getString(
            R.string.epub_chapter_of,
            index + 1,
            chapters.size
        )

        // Navigation button state
        btnPrevChapter.alpha = if (index > 0) 1f else 0.35f
        btnNextChapter.alpha = if (index < chapters.size - 1) 1f else 0.35f

        // Load chapter HTML from extracted cache dir via file:// URI
        val htmlFile = File(chapter.absolutePath)
        if (htmlFile.exists()) {
            webView.loadUrl("file://${htmlFile.absolutePath}")
        } else {
            Toast.makeText(this, R.string.epub_error_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateChapter(delta: Int) {
        showChapter(currentChapterIndex + delta)
    }

    // ── Style injection ───────────────────────────────────────────────────────

    /**
     * Injects a CSS override via JavaScript after page load to apply dark mode
     * and font size preference. Uses document.body style injection — no external
     * scripts, no JS code execution beyond basic DOM property writes.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun injectStyles(view: WebView) {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val bgColor   = if (isDark) "#1A1A2E" else "#FFFFFF"
        val textColor = if (isDark) "#E0E0E0" else "#1A1A2E"
        val linkColor = if (isDark) "#7EC8E3" else "#0369A1"
        val fontSize  = webViewFontSize()

        // We temporarily enable JS just for the style injection, then disable again
        view.settings.javaScriptEnabled = true
        view.evaluateJavascript(
            """
            (function() {
                var style = document.createElement('style');
                style.type = 'text/css';
                style.innerHTML = 'body { background-color: $bgColor !important; color: $textColor !important; font-size: ${fontSize}px !important; line-height: 1.6 !important; padding: 16px !important; max-width: 100% !important; word-wrap: break-word !important; } a { color: $linkColor !important; } img { max-width: 100% !important; height: auto !important; }';
                document.head.appendChild(style);
            })();
            """.trimIndent(),
            null
        )
        view.settings.javaScriptEnabled = false
    }

    private fun webViewFontSize(): Int = when (FontSizeHelper.getSavedSize(this)) {
        FontSizeHelper.FONT_SMALL  -> 14
        FontSizeHelper.FONT_LARGE  -> 20
        else                        -> 17  // FONT_NORMAL
    }

    // ── Table of Contents bottom sheet ────────────────────────────────────────

    private fun showTocSheet() {
        val chapters = book?.chapters ?: return
        val isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            // TV: simple AlertDialog list
            val titles = chapters.map { it.title }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.epub_table_of_contents)
                .setItems(titles) { _, which -> showChapter(which) }
                .show()
            return
        }

        // Mobile: material bottom sheet
        val sheet = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(
            android.R.layout.simple_list_item_1,
            null,
            false
        )
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EpubViewerActivity)
            adapter = TocAdapter(chapters, currentChapterIndex) { index ->
                sheet.dismiss()
                showChapter(index)
            }
        }
        sheet.setContentView(recycler)
        sheet.show()
    }

    // ── TOC RecyclerView Adapter ──────────────────────────────────────────────

    private inner class TocAdapter(
        private val chapters: List<EpubParser.EpubChapter>,
        private val activeIndex: Int,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<TocAdapter.TocVh>() {

        inner class TocVh(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val txtChapterTitle: TextView = itemView as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TocVh {
            val tv = TextView(parent.context).apply {
                setPadding(48, 32, 48, 32)
                textSize = 16f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
            }
            return TocVh(tv)
        }

        override fun onBindViewHolder(holder: TocVh, position: Int) {
            val chapter = chapters[position]
            holder.txtChapterTitle.text = chapter.title
            holder.txtChapterTitle.setTextColor(
                if (position == activeIndex) getColor(R.color.ufm_primary)
                else getColor(android.R.color.primary_text_dark)
            )
            holder.itemView.setOnClickListener { onSelect(position) }
        }

        override fun getItemCount() = chapters.size
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
        // Clean up extracted EPUB files from cache on a background thread
        val dir = tempDir
        if (dir != null) {
            lifecycleScope.launch(Dispatchers.IO) { dir.deleteRecursively() }
        }
    }
}
