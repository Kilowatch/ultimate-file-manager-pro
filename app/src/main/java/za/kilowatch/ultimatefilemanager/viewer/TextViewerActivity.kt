package za.kilowatch.ultimatefilemanager.viewer

import android.app.AlertDialog
import android.graphics.Color
import android.content.Context
import android.os.Bundle
import android.text.style.BackgroundColorSpan
import android.text.Spannable
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.PrecomputedTextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import za.kilowatch.ultimatefilemanager.util.EditorInsets
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.GridIndicatorsPreferenceManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.viewer.syntax.LanguageDef
import za.kilowatch.ultimatefilemanager.viewer.syntax.LanguageRegistry
import za.kilowatch.ultimatefilemanager.viewer.syntax.SyntaxHighlightEngine
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TextViewerActivity : AppCompatActivity() {

    private lateinit var scrollView: ScrollView
    private lateinit var hScrollView: HorizontalScrollView
    private lateinit var txtContent: EditText
    private lateinit var txtLineNumbers: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtTitle: TextView
    private lateinit var paginationBar: LinearLayout
    private lateinit var btnPrevPage: ImageView
    private lateinit var btnNextPage: ImageView
    private lateinit var txtPageIndicator: TextView
    private lateinit var btnEdit: ImageView
    private lateinit var btnSave: View

    private var textSize = 13f
    private lateinit var scaleDetector: ScaleGestureDetector

    private var allChunks: List<String> = emptyList()
    private var allLineNumChunks: List<String> = emptyList()
    private var currentPage = 0
    private var originalFilePath = ""
    private var originalFileName = ""
    private var fileExtension = ""
    private var isEditMode = false
    private var isModified = false
    private var currentText = ""
    private var isOfficeFile = false
    private var isTv = false
    private var imeWasVisible = false

    // ── Syntax highlighting fields ─────────────────────────────────────
    private var currentLanguage: LanguageDef? = null
    private var isHighlightedFile = false
    private var highlightingTextWatcher: TextWatcher? = null
    private var highlightDebounceHandler: Handler? = null
    private var highlightDebounceRunnable: Runnable? = null

    // ── In-document search fields ──────────────────────────────────────
    private var searchHelper: DocumentSearchHelper<IntRange>? = null
    private var searchQuery: String = ""
    private var searchIsActive: Boolean = false

    // ── View-mode selection action mode (hides Cut/Paste) ────────────────
    private val viewModeActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(android.R.id.cut)?.isVisible = false
            menu.findItem(android.R.id.paste)?.isVisible = false
            menu.findItem(android.R.id.pasteAsPlainText)?.isVisible = false
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(android.R.id.cut)?.isVisible = false
            menu.findItem(android.R.id.paste)?.isVisible = false
            menu.findItem(android.R.id.pasteAsPlainText)?.isVisible = false
            return false
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_text_viewer_tv
            else R.layout.activity_text_viewer
        )

        // Restore search state after configuration change
        if (savedInstanceState != null) {
            searchIsActive = savedInstanceState.getBoolean("search_active", false)
            searchQuery = savedInstanceState.getString("search_query", "") ?: ""
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val imeVisible = EditorInsets.apply(
                v, insets,
                resources.getDimensionPixelSize(R.dimen.editor_keyboard_gap)
            )
            // When the soft keyboard first appears, scroll the cursor into view
            // so the active line never sits behind the keyboard.
            if (imeVisible && !imeWasVisible) {
                scrollToCursor()
            }
            imeWasVisible = imeVisible
            insets
        }

        scrollView = findViewById(R.id.scrollView)
        hScrollView = findViewById(R.id.hScrollView)
        txtContent = findViewById(R.id.txtContent)
        txtLineNumbers = findViewById(R.id.txtLineNumbers)
        progressBar = findViewById(R.id.progressBar)
        txtTitle = findViewById(R.id.txtTitle)
        paginationBar = findViewById(R.id.paginationBar)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        txtPageIndicator = findViewById(R.id.txtPageIndicator)
        btnEdit = findViewById(R.id.btnEdit)
        btnSave = findViewById(R.id.btnSave)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { onBackPressed() }

        findViewById<View>(R.id.btnConvertToPdf).setOnClickListener {
            val path = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: return@setOnClickListener
            val name = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME) ?: File(path).name
            val dialog = za.kilowatch.ultimatefilemanager.ui.ConvertToPdfDialog().apply {
                arguments = android.os.Bundle().apply {
                    putString("original_filename", File(name).nameWithoutExtension)
                    putString("document_path", path)
                }
            }
            dialog.show(supportFragmentManager, "ConvertToPdfDialog")
        }

        originalFilePath = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: run {
            finish(); return
        }
        originalFileName = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME) ?: "File"
        fileExtension = File(originalFileName).extension.lowercase()
        txtTitle.text = originalFileName

        isOfficeFile = fileExtension in OFFICE_WORD_EXTENSIONS + OFFICE_EXCEL_EXTENSIONS + OFFICE_PPT_EXTENSIONS

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                textSize = (textSize * detector.scaleFactor).coerceIn(8f, 40f)
                txtContent.textSize = textSize
                txtLineNumbers.textSize = textSize
                return true
            }
        })

        scrollView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) true else false
        }

        btnEdit.setOnClickListener { toggleEditMode() }
        btnSave.setOnClickListener { showSaveDialog() }

        // ── In-document search setup ──────────────────────────────────
        val btnSearch = findViewById<ImageView>(R.id.btnSearch)
        val searchBar = findViewById<View>(R.id.layoutSearchBar)
        val edtSearch = findViewById<EditText>(R.id.edtSearchInput)
        val txtCount = findViewById<TextView>(R.id.txtSearchCount)
        val btnSearchUp = findViewById<View>(R.id.btnSearchUp)
        val btnSearchDown = findViewById<View>(R.id.btnSearchDown)
        val btnSearchClose = findViewById<View>(R.id.btnSearchClose)

        searchHelper = DocumentSearchHelper(
            host = createSearchHost(),
            searchInput = edtSearch,
            searchBarLayout = searchBar,
            matchCountLabel = txtCount,
            btnUp = btnSearchUp,
            btnDown = btnSearchDown,
            btnClose = btnSearchClose,
            searchIconView = btnSearch,
            isTv = isTv
        )
        btnSearch.setOnClickListener { searchHelper?.toggle() }

        // Restore search state after configuration change
        if (searchIsActive && searchQuery.isNotEmpty()) {
            searchHelper?.let { helper ->
                // Post to allow content to load first
                btnSearch.post { helper.restoreState(searchQuery, 0) }
            }
        }

        val startEditing = intent.getBooleanExtra(FileViewerRouter.EXTRA_START_IN_EDIT_MODE, false)
        loadFile(File(originalFilePath), startEditing)

        btnPrevPage.setOnClickListener {
            if (currentPage > 0) showPage(currentPage - 1)
        }
        btnNextPage.setOnClickListener {
            if (currentPage < allChunks.lastIndex) showPage(currentPage + 1)
        }

        if (isTv) {
            val scrollContainer = findViewById<View>(R.id.scrollContainer)
            scrollContainer?.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val scrollAmount = (80 * resources.displayMetrics.density).toInt()
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            scrollView.smoothScrollBy(0, scrollAmount); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (scrollView.scrollY <= 0) false
                            else { scrollView.smoothScrollBy(0, -scrollAmount); true }
                        }
                        else -> false
                    }
                } else false
            }
            scrollContainer?.requestFocus()
        }
    }

    /**
     * Brings the cursor into view when the soft keyboard appears, so the line
     * being edited stays visible above the keyboard.
     */
    private fun scrollToCursor() {
        if (!::txtContent.isInitialized) return
        val sel = txtContent.selectionStart
        if (sel < 0) return
        txtContent.post {
            txtContent.bringPointIntoView(sel)
            // Belt-and-suspenders: bringPointIntoView can fail to scroll the
            // outer vertical ScrollView through the nested scroll containers, so
            // if the cursor line is still out of view, scroll the ScrollView
            // directly so the active line sits just above the keyboard.
            val layout = txtContent.layout ?: return@post
            val line = layout.getLineForOffset(sel)
            val cursorTop = txtContent.top + txtContent.paddingTop + layout.getLineTop(line)
            val cursorBottom = txtContent.top + txtContent.paddingTop + layout.getLineBottom(line)
            val visibleTop = scrollView.scrollY
            val visibleBottom = scrollView.scrollY + scrollView.height
            if (cursorBottom > visibleBottom || cursorTop < visibleTop) {
                val gap = resources.getDimensionPixelSize(R.dimen.editor_keyboard_gap)
                scrollView.smoothScrollTo(
                    scrollView.scrollX,
                    (cursorBottom - scrollView.height + gap).coerceAtLeast(0)
                )
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (isModified) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.unsaved_changes_title))
                .setMessage(getString(R.string.unsaved_changes_message))
                .setPositiveButton(R.string.save) { _, _ -> showSaveDialog() }
                .setNegativeButton(R.string.btn_discard) { _, _ -> finish() }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        // Reset the IME-open transition flag so a keyboard shown after returning
        // to the foreground still triggers the cursor scroll.
        imeWasVisible = false
    }

    private fun toggleEditMode() {
        // Refuse to enter edit mode for very large documents. A single wrap-content
        // EditText holding the whole file forces TextView.onMeasure to walk every glyph
        // on the main thread (Layout.getDesiredWidthWithLimit -> TextLine.metrics ->
        // Paint.getRunAdvance); on low-end Android TV boxes (e.g. ZTE OTT Xview+ AV1,
        // SDK 30) that exceeds the ANR watchdog's 5s budget and freezes the app. Viewing
        // stays available via pagination; only editing is capped.
        val fullText = if (!isEditMode) allChunks.joinToString("\n") else ""
        if (!isEditMode && fullText.toByteArray(Charsets.UTF_8).size > EDIT_MAX_BYTES) {
            Toast.makeText(
                this,
                getString(R.string.text_edit_file_too_large, EDIT_MAX_BYTES / 1024),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        isEditMode = !isEditMode
        if (isEditMode) {
            txtContent.setText(fullText)
            txtContent.textSize = textSize
            // ── Restore EditText editing capabilities (mobile and TV) ─────
            txtContent.setKeyListener(android.text.method.TextKeyListener.getInstance())
            txtContent.setCursorVisible(true)
            txtContent.setCustomSelectionActionModeCallback(null)
            txtContent.isEnabled = true
            txtContent.isFocusable = true
            txtContent.isFocusableInTouchMode = true
            txtContent.requestFocus()
            txtContent.post {
                txtContent.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(txtContent, InputMethodManager.SHOW_IMPLICIT)
            }
            btnSave.visibility = View.VISIBLE
            paginationBar.visibility = View.GONE
            txtLineNumbers.visibility = View.GONE
            txtContent.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setStroke(2, za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(this@TextViewerActivity))
                setColor(0x15FFFFFF.toInt())
                cornerRadius = 4f
            }
            // Only force white text for plain text files — syntax highlighting
            // provides its own per-token colours via ForegroundColorSpan
            if (!isHighlightedFile) {
                txtContent.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            }

            // ── Apply syntax highlighting in edit mode ────────────────
            if (isHighlightedFile && currentLanguage != null) {
                val lang = currentLanguage!!
                lifecycleScope.launch(Dispatchers.Default) {
                    val spannable = SyntaxHighlightEngine.highlight(fullText, lang, this@TextViewerActivity)
                    withContext(Dispatchers.Main) {
                        val pos = txtContent.selectionStart.coerceIn(0, spannable.length)
                        txtContent.setText(spannable, android.widget.TextView.BufferType.SPANNABLE)
                        txtContent.setSelection(pos)
                    }
                }
            }

            // ── Debounced re-highlight TextWatcher ────────────────────
            highlightDebounceHandler = Handler(Looper.getMainLooper())
            val debounce = Runnable {
                if (isEditMode && isHighlightedFile && currentLanguage != null) {
                    // In-place span update — no setText(), no cursor reset
                    SyntaxHighlightEngine.applyHighlight(
                        txtContent.text,
                        currentLanguage!!,
                        this@TextViewerActivity
                    )
                }
            }
            highlightDebounceRunnable = debounce

            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    highlightDebounceRunnable?.let { r ->
                        highlightDebounceHandler?.removeCallbacks(r)
                        highlightDebounceHandler?.postDelayed(r, 300)
                    }
                }
            }
            txtContent.addTextChangedListener(watcher)
            highlightingTextWatcher = watcher

            hScrollView.scrollTo(0, 0)
            scrollView.scrollTo(0, 0)
            if (isOfficeFile) {
                Toast.makeText(this, R.string.office_save_warning, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.edit_mode_enabled, Toast.LENGTH_SHORT).show()
            }
        } else {
            // ── Hide the soft keyboard so it doesn't linger over the viewer ──
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(txtContent.windowToken, 0)

            // ── Clean up TextWatcher when leaving edit mode ───────────
            highlightingTextWatcher?.let { txtContent.removeTextChangedListener(it) }
            highlightingTextWatcher = null
            highlightDebounceHandler?.removeCallbacksAndMessages(null)
            highlightDebounceHandler = null
            highlightDebounceRunnable = null

            // ── Restore view-mode text selection (mobile) or disable (TV) ──
            if (!isTv) {
                applyViewModeTextSelection()
            } else {
                txtContent.isEnabled = false
                txtContent.isFocusable = false
                txtContent.isFocusableInTouchMode = false
            }
            btnSave.visibility = View.GONE
            txtContent.background = null
            paginationBar.visibility = if (allChunks.size > 1) View.VISIBLE else View.GONE
            txtLineNumbers.visibility = if (!GridIndicatorsPreferenceManager.isHidden(this)) View.VISIBLE else View.GONE
            updateModifiedState()
            showPage(currentPage)
        }
    }

    private fun updateModifiedState() {
        val newText = txtContent.text.toString()
        val fullText = allChunks.joinToString("\n")
        isModified = newText != fullText
    }

    private fun showSaveDialog() {
        if (!isModified && !isEditMode) return
        val newText = if (isEditMode) txtContent.text.toString() else allChunks.joinToString("\n")
        if (newText.isBlank()) {
            Toast.makeText(this, R.string.cannot_save_empty_file, Toast.LENGTH_SHORT).show()
            return
        }

        val layoutRes = if (isTv) R.layout.dialog_save_file_tv else R.layout.dialog_save_file
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val edtInput = if (isTv) {
            dialogView.findViewById<android.widget.EditText>(R.id.edtFilename)
        } else {
            dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtFilename)
        }
        edtInput?.setText(originalFileName)

        val btnSave = dialogView.findViewById<android.view.View>(R.id.btnConfirmSave)
        val btnCancel = dialogView.findViewById<android.view.View>(R.id.btnCancelSave)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnSave?.setOnClickListener {
            val enteredName = edtInput?.text?.toString()?.trim() ?: originalFileName
            if (enteredName.isEmpty()) {
                Toast.makeText(this, R.string.filename_empty_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val finalName = if (enteredName.contains(".")) enteredName else "$enteredName.$fileExtension"
            dialog.dismiss()
            saveFile(newText, finalName)
        }
        btnCancel?.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        za.kilowatch.ultimatefilemanager.util.DialogInputHelper.setupDialogInput(dialog, edtInput) {
            btnSave?.performClick()
        }
    }

    private fun saveFile(content: String, fileName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val parentDir = File(originalFilePath).parentFile ?: cacheDir
                val targetFile = File(parentDir, fileName)

                if (targetFile.absolutePath == originalFilePath && targetFile.length() > 0L) {
                    withContext(Dispatchers.Main) {
                        val confirmView = layoutInflater.inflate(R.layout.dialog_support_message, null)
                        val imgIcon = confirmView.findViewById<ImageView>(R.id.imgDialogIcon)
                        val txtTitle = confirmView.findViewById<TextView>(R.id.txtDialogTitle)
                        val txtMessage = confirmView.findViewById<TextView>(R.id.txtDialogMessage)
                        val btnPositive = confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogPositive)
                        val btnNegative = confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogNegative)

                        imgIcon?.setImageResource(R.drawable.ic_warning)
                        txtTitle?.setText(R.string.overwrite_dialog_title)
                        txtMessage?.text = getString(R.string.overwrite_dialog_message, fileName)
                        btnPositive?.setText(R.string.conflict_overwrite)
                        btnNegative?.setText(R.string.cancel)
                        btnNegative?.visibility = View.VISIBLE

                        val confirmDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TextViewerActivity, R.style.UFM_Dialog)
                            .setView(confirmView)
                            .setCancelable(true)
                            .create()

                        btnPositive?.setOnClickListener {
                            confirmDialog.dismiss()
                            lifecycleScope.launch(Dispatchers.IO) {
                                doSave(content, targetFile)
                            }
                        }
                        btnNegative?.setOnClickListener {
                            confirmDialog.dismiss()
                        }

                        confirmDialog.show()
                        confirmDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    }
                } else {
                    doSave(content, targetFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TextViewerActivity,
                        getString(R.string.file_save_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun doSave(content: String, targetFile: File) {
        try {
            val contentUriStr = intent.getStringExtra(FileViewerRouter.EXTRA_CONTENT_URI)
            val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(this, targetFile)
            val safUri = if (contentUriStr != null && targetFile.absolutePath == originalFilePath) {
                android.net.Uri.parse(contentUriStr)
            } else if (isSaf) {
                (targetFile as? za.kilowatch.ultimatefilemanager.storage.SafFile)?.documentUri ?: za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getDocumentUriForPath(this, targetFile.absolutePath)
            } else null

            if (safUri != null) {
                contentResolver.openOutputStream(safUri, "wt")?.use { outStream ->
                    outStream.write(content.toByteArray(Charsets.UTF_8))
                }
            } else {
                when (fileExtension) {
                    "docx", "docm", "dotx", "dotm" -> saveAsDocx(content, targetFile)
                    "xlsx", "xlsm", "xltx", "xltm" -> saveAsXlsx(content, targetFile)
                    "pptx", "pptm", "ppsx", "potx", "potm" -> saveAsPptx(content, targetFile)
                    "doc", "dot" -> targetFile.writeText(content, Charsets.UTF_8)
                    "xls", "xlt", "xlsb" -> saveAsXls(content, targetFile)
                    "ppt", "pps", "pot" -> targetFile.writeText(content, Charsets.UTF_8)
                    else -> targetFile.writeText(content, Charsets.UTF_8)
                }
            }
            // If this file was opened from a network share, upload the saved content back
            // (the bridge callback handles its own threading — runs the upload on IO internally)
            runCatching { NetworkSaveBridge.onFileSaved?.invoke(targetFile) }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@TextViewerActivity,
                    getString(R.string.file_saved, targetFile.name), Toast.LENGTH_SHORT).show()
                isModified = false
                if (targetFile.absolutePath == originalFilePath) {
                    isEditMode = false
                    btnSave.visibility = View.GONE
                    txtContent.background = null
                    if (!isTv) {
                        txtContent.isEnabled = true
                        txtContent.setTextIsSelectable(true)
                        txtContent.setKeyListener(null)
                        txtContent.setCursorVisible(false)
                        txtContent.setHighlightColor(
                            ContextCompat.getColor(this@TextViewerActivity, R.color.text_selection_highlight)
                        )
                        txtContent.setCustomSelectionActionModeCallback(viewModeActionModeCallback)
                    } else {
                        txtContent.isEnabled = false
                        txtContent.isFocusable = false
                        txtContent.isFocusableInTouchMode = false
                    }
                    showPage(currentPage)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@TextViewerActivity,
                    getString(R.string.file_save_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAsDocx(content: String, file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("word/document.xml"))
            val docBody = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>""")
                content.lines().forEach { line ->
                    val escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    append("<w:p><w:r><w:t xml:space=\"preserve\">$escaped</w:t></w:r></w:p>")
                }
                append("</w:body></w:document>")
            }
            zos.write(docBody.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    private fun saveAsXlsx(content: String, file: File) {
        val lines = content.lines().filter { it.isNotBlank() }
        val sharedStrings = mutableListOf<String>()
        val sheetRows = mutableListOf<List<Int>>()
        for (line in lines) {
            val cells = line.split("\t")
            val cellIndices = cells.map { cell ->
                val idx = sharedStrings.indexOf(cell)
                if (idx >= 0) idx else {
                    sharedStrings.add(cell)
                    sharedStrings.size - 1
                }
            }
            sheetRows.add(cellIndices)
        }

        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
</Types>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/></sheets>
</workbook>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            val ssXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sharedStrings.size}" uniqueCount="${sharedStrings.size}">""")
                sharedStrings.forEach { s ->
                    val escaped = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                    append("<si><t>$escaped</t></si>")
                }
                append("</sst>")
            }
            zos.write(ssXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val cols = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val sheetXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>""")
                sheetRows.forEachIndexed { rowIdx, cells ->
                    append("<row r=\"${rowIdx + 1}\">")
                    cells.forEachIndexed { colIdx, cellIdx ->
                        val colRef = if (colIdx < 26) "${cols[colIdx]}${rowIdx + 1}" else "${cols[colIdx / 26 - 1]}${cols[colIdx % 26]}${rowIdx + 1}"
                        append("<c r=\"$colRef\" t=\"s\"><v>$cellIdx</v></c>")
                    }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
            }
            zos.write(sheetXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    private fun saveAsPptx(content: String, file: File) {
        val lines = content.lines().filter { it.isNotBlank() }
        val slides = if (lines.isEmpty()) listOf("") else lines

        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            val typesXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
                slides.forEachIndexed { i, _ ->
                    append("<Override PartName=\"/ppt/slides/slide${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
                }
                append("</Types>")
            }
            zos.write(typesXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/presentation.xml"))
            val presXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldIdLst>""")
                slides.forEachIndexed { i, _ ->
                    append("<p:sldId id=\"${256 + i}\" r:id=\"rId${i + 2}\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>")
                }
                append("""</p:sldIdLst>
  <p:sldSz cx="9144000" cy="6858000"/>
  <p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>""")
            }
            zos.write(presXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/_rels/presentation.xml.rels"))
            val relsXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                slides.forEachIndexed { i, _ ->
                    append("<Relationship Id=\"rId${i + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${i + 1}.xml\"/>")
                }
                append("</Relationships>")
            }
            zos.write(relsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            slides.forEachIndexed { i, slideText ->
                zos.putNextEntry(ZipEntry("ppt/slides/slide${i + 1}.xml"))
                val slideXml = buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr/>
      <p:sp>
        <p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr><p:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>
        <p:spPr/><p:txBody>
          <a:bodyPr xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"/>
          <a:lstStyle xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"/>""")
                    val escaped = slideText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    append("<a:p xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:r><a:rPr lang=\"en-US\"/><a:t>$escaped</a:t></a:r></a:p>")
                    append("""</p:txBody></p:sp></p:spTree></p:cSld></p:sld>""")
                }
                zos.write(slideXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    private fun saveAsXls(content: String, file: File) {
        try {
            val wb = org.apache.poi.hssf.usermodel.HSSFWorkbook()
            val sheet = wb.createSheet("Sheet1")
            val lines = content.lines().filter { it.isNotBlank() }
            lines.forEachIndexed { rowIdx, line ->
                val row = sheet.createRow(rowIdx)
                val cells = line.split("\t")
                cells.forEachIndexed { colIdx, cell ->
                    row.createCell(colIdx).setCellValue(cell)
                }
            }
            FileOutputStream(file).use { wb.write(it) }
            wb.close()
        } catch (e: Exception) {
            file.writeText(content, Charsets.UTF_8)
        }
    }

    private fun loadFile(file: File, startInEditMode: Boolean = false) {
        progressBar.visibility = View.VISIBLE
        txtContent.visibility = View.GONE
        txtLineNumbers.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ext = file.extension.lowercase()
                val text = when {
                    ext == "rtf" -> extractRtf(file)
                    ext == "dat" -> extractDat(file)
                    ext in OFFICE_WORD_EXTENSIONS -> extractWord(file, ext)
                    ext in OFFICE_EXCEL_EXTENSIONS -> extractExcel(file, ext)
                    ext in OFFICE_PPT_EXTENSIONS -> extractPowerPoint(file, ext)
                    ext == "vsdx" -> extractVisio(file)
                    else -> readPlainText(file)
                }

                currentText = text

                // ── Syntax highlighting: detect language ──────────────
                if (!isOfficeFile) {
                    currentLanguage = LanguageRegistry.detect(originalFileName)
                    isHighlightedFile = currentLanguage != null
                } else {
                    isHighlightedFile = false
                }

                val chunks = splitIntoChunks(text, PAGE_BYTE_SIZE)
                val indicatorsHidden = GridIndicatorsPreferenceManager.isHidden(this@TextViewerActivity)
                val lineNumChunks = if (!indicatorsHidden) {
                    buildLineNumberChunks(text, PAGE_BYTE_SIZE)
                } else {
                    emptyList()
                }

                withContext(Dispatchers.Main) {
                    allChunks = chunks
                    allLineNumChunks = lineNumChunks
                    currentPage = 0
                    progressBar.visibility = View.GONE
                    txtContent.visibility = View.VISIBLE
                    txtLineNumbers.visibility = if (!indicatorsHidden) View.VISIBLE else View.GONE

                    paginationBar.visibility =
                        if (allChunks.size > 1) View.VISIBLE else View.GONE

                    if (startInEditMode) {
                        toggleEditMode()
                    } else {
                        showPage(0)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    txtContent.visibility = View.VISIBLE
                    txtContent.setTextColor(android.graphics.Color.parseColor("#FF6B6B"))
                    txtContent.setText(getString(R.string.error_emessage, e.message ?: "Unknown error"), android.widget.TextView.BufferType.NORMAL)
                }
            }
        }
    }

    /**
     * Applies view-mode text selection properties on mobile (enabled, textIsSelectable,
     * null KeyListener, cursor hidden) while keeping the EditText non-editable.
     * On TV this is a no-op — the TV path disables the view outright in toggleEditMode().
     */
    private fun applyViewModeTextSelection() {
        if (isEditMode) return
        if (!isTv) {
            txtContent.isEnabled = true
            txtContent.setTextIsSelectable(true)
            txtContent.setKeyListener(null)
            txtContent.setCursorVisible(false)
            txtContent.setHighlightColor(
                ContextCompat.getColor(this, R.color.text_selection_highlight)
            )
            txtContent.setCustomSelectionActionModeCallback(viewModeActionModeCallback)
        }
    }

    private fun showPage(page: Int) {
        currentPage = page
        val chunk = allChunks.getOrElse(page) { "" }
        val lineNums = if (allLineNumChunks.isNotEmpty()) {
            allLineNumChunks.getOrElse(page) { "" }
        } else ""

        lifecycleScope.launch(Dispatchers.Default) {
            // ── Syntax highlighting for code files ────────────────
            if (isHighlightedFile && currentLanguage != null && chunk.isNotEmpty()) {
                val lang = currentLanguage!!
                val context = this@TextViewerActivity
                val spannable = SyntaxHighlightEngine.highlight(chunk, lang, context)
                withContext(Dispatchers.Main) {
                    if (isEditMode) return@withContext
                    txtContent.setText(spannable, android.widget.TextView.BufferType.SPANNABLE)
                    txtLineNumbers.text = lineNums
                    txtContent.textSize = textSize
                    txtLineNumbers.textSize = textSize
                    scrollView.scrollTo(0, 0)
                    updatePaginationUi()
                    searchHelper?.reRunSearch()
                    applyViewModeTextSelection()
                }
            } else {
                // ── Plain text path (existing behaviour) ──────────
                val params = withContext(Dispatchers.Main) {
                    TextViewCompat.getTextMetricsParams(txtContent)
                }
                val precomputed = PrecomputedTextCompat.create(chunk, params)
                withContext(Dispatchers.Main) {
                    if (isEditMode) return@withContext
                    txtContent.setText(precomputed, android.widget.TextView.BufferType.SPANNABLE)
                    txtLineNumbers.text = lineNums
                    txtContent.textSize = textSize
                    txtLineNumbers.textSize = textSize
                    scrollView.scrollTo(0, 0)
                    updatePaginationUi()
                    searchHelper?.reRunSearch()
                    applyViewModeTextSelection()
                }
            }
        }
    }

    private fun updatePaginationUi() {
        val total = allChunks.size
        txtPageIndicator.text = getString(R.string.page_firstvisible_1_total, currentPage + 1, total)
        btnPrevPage.alpha = if (currentPage > 0) 1f else 0.3f
        btnNextPage.alpha = if (currentPage < total - 1) 1f else 0.3f
    }

    private fun splitIntoChunks(text: String, chunkBytes: Int): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= chunkBytes) return listOf(text)
        val lines = text.lines()
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        var bufBytes = 0
        for (line in lines) {
            val lineWithNl = line + "\n"
            val lineBytes = lineWithNl.toByteArray(Charsets.UTF_8).size
            if (bufBytes + lineBytes > chunkBytes && buf.isNotEmpty()) {
                chunks.add(buf.toString().trimEnd('\n'))
                buf.clear()
                bufBytes = 0
            }
            buf.append(lineWithNl)
            bufBytes += lineBytes
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString().trimEnd('\n'))
        return chunks.ifEmpty { listOf("") }
    }

    private fun buildLineNumberChunks(text: String, chunkBytes: Int): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= chunkBytes) {
            val lineCount = text.lines().size
            return listOf((1..lineCount).joinToString("\n") { it.toString() })
        }
        val lines = text.lines()
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        var bufBytes = 0
        val lineNumBuf = StringBuilder()
        for ((idx, line) in lines.withIndex()) {
            val lineWithNl = line + "\n"
            val lineBytes = lineWithNl.toByteArray(Charsets.UTF_8).size
            if (bufBytes + lineBytes > chunkBytes && buf.isNotEmpty()) {
                result.add(lineNumBuf.toString().trimEnd('\n'))
                buf.clear(); lineNumBuf.clear(); bufBytes = 0
            }
            buf.append(lineWithNl)
            lineNumBuf.append("${idx + 1}\n")
            bufBytes += lineBytes
        }
        if (lineNumBuf.isNotEmpty()) result.add(lineNumBuf.toString().trimEnd('\n'))
        return result.ifEmpty { listOf("") }
    }

    private fun readPlainText(file: File, charset: java.nio.charset.Charset = Charsets.UTF_8): String {
        val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(this, file)
        if (isSaf) {
            val inStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(this, file.absolutePath)
                ?: throw java.io.FileNotFoundException("Cannot open stream for ${file.name}")
            return inStream.use { stream ->
                val maxBytes = 1 * 1024 * 1024
                val bytes = stream.readBytes()
                if (bytes.size > maxBytes) {
                    val truncated = bytes.copyOf(maxBytes)
                    String(truncated, charset) + "\n\n... [File too large, showing first 1MB]"
                } else {
                    String(bytes, charset)
                }
            }
        }

        val maxBytes = 1 * 1024 * 1024L
        if (file.length() > maxBytes) {
            val bytes = ByteArray(maxBytes.toInt())
            FileInputStream(file).use { it.read(bytes) }
            return String(bytes, charset) + "\n\n... [File too large, showing first 1MB]"
        }
        return file.readText(charset)
    }

    private fun stageSafFileIfNeeded(file: File): Pair<File, File?> {
        val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(this, file)
        if (!isSaf) return Pair(file, null)
        val temp = File(cacheDir, "temp_txt_view_${System.currentTimeMillis()}.${file.extension}")
        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(this, file.absolutePath)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        return Pair(temp, temp)
    }

    private fun extractRtf(file: File): String {
        val raw = readPlainText(file, Charsets.ISO_8859_1)
        var text = raw
            .replace(Regex("\\\\pard[^\\s]*\\s?"), "")
            .replace(Regex("\\\\[a-z]+\\d*\\s?"), "")
            .replace(Regex("\\{[^{}]*\\}"), "")
            .replace(Regex("[{}]"), "")
            .replace(Regex("\\\\['`][0-9a-fA-F]{2}"), "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        text = text.replace(Regex("\n{3,}"), "\n\n").trim()
        return text
    }

    private fun extractDat(file: File): String {
        val limit = minOf(file.length(), 64 * 1024L).toInt()
        val bytes = ByteArray(limit)
        FileInputStream(file).use { it.read(bytes) }
        val sampleSize = minOf(bytes.size, 8192)
        val isText = bytes.take(sampleSize).all { b ->
            b == 0x09.toByte() || b == 0x0A.toByte() || b == 0x0D.toByte() ||
            (b in 0x20..0x7E)
        }
        return if (isText) {
            String(bytes, Charsets.UTF_8)
        } else {
            hexDump(bytes)
        }
    }

    private fun hexDump(bytes: ByteArray): String {
        val sb = StringBuilder()
        val limit = minOf(bytes.size, 64 * 1024)
        for (offset in 0 until limit step 16) {
            sb.append(String.format("%08X  ", offset))
            val end = minOf(offset + 16, limit)
            for (i in offset until end) {
                sb.append(String.format("%02X ", bytes[i]))
                if (i == offset + 7) sb.append(" ")
            }
            for (i in end until offset + 16) {
                sb.append("   ")
                if (i == offset + 7) sb.append(" ")
            }
            sb.append(" |")
            for (i in offset until end) {
                val ch = bytes[i].toInt().toChar()
                sb.append(if (ch in ' '..'~') ch else '.')
            }
            sb.append("|\n")
        }
        if (bytes.size > limit) {
            sb.append("\n... (showing first ${limit / 1024}KB of ${bytes.size / 1024}KB)")
        }
        return sb.toString()
    }

    private fun extractWord(file: File, ext: String): String {
        val (effectiveFile, tempFile) = stageSafFileIfNeeded(file)
        return try {
            if (ext in OFFICE_WORD_LEGACY) {
                val fis = FileInputStream(effectiveFile)
                val doc = org.apache.poi.hwpf.HWPFDocument(fis)
                val extractor = org.apache.poi.hwpf.extractor.WordExtractor(doc)
                val text = extractor.text
                extractor.close()
                doc.close()
                fis.close()
                text
            } else {
                extractDocxViaZip(effectiveFile)
            }
        } catch (e: Exception) {
            "Error extracting Word content:\n${e.message}"
        } finally {
            tempFile?.delete()
        }
    }

    private fun extractDocxViaZip(file: File): String {
        val zip = java.util.zip.ZipFile(file)
        val sb = StringBuilder()
        val entry = zip.getEntry("word/document.xml")
        if (entry != null) {
            val xml = zip.getInputStream(entry).bufferedReader().readText()
            val textPattern = Regex("""<w:t[^>]*>([^<]*)</w:t>""")
            val paragraphs = xml.split(Regex("""<w:p[\s>]"""))
            for (para in paragraphs) {
                val texts = textPattern.findAll(para).map { it.groupValues[1] }.toList()
                if (texts.isNotEmpty()) {
                    sb.appendLine(texts.joinToString(""))
                }
            }
        }
        zip.close()
        return if (sb.isEmpty()) getString(R.string.no_text_content_found) else sb.toString()
    }

    private fun extractExcel(file: File, ext: String): String {
        val (effectiveFile, tempFile) = stageSafFileIfNeeded(file)
        return try {
            if (ext in OFFICE_EXCEL_LEGACY) {
                val fis = FileInputStream(effectiveFile)
                val wb = org.apache.poi.hssf.usermodel.HSSFWorkbook(fis)
                val sb = buildExcelText(wb)
                wb.close()
                fis.close()
                sb
            } else {
                extractXlsxViaZip(effectiveFile)
            }
        } catch (e: Exception) {
            "Error extracting Excel content:\n${e.message}"
        } finally {
            tempFile?.delete()
        }
    }

    private fun buildExcelText(wb: org.apache.poi.ss.usermodel.Workbook): String {
        val sb = StringBuilder()
        val formatter = org.apache.poi.ss.usermodel.DataFormatter()
        for (i in 0 until wb.numberOfSheets) {
            val sheet = wb.getSheetAt(i)
            sb.appendLine(getString(R.string.sheet_sheetsheetname))
            sb.appendLine()
            for (row in sheet) {
                val cells = mutableListOf<String>()
                for (cell in row) {
                    cells.add(formatter.formatCellValue(cell))
                }
                sb.appendLine(cells.joinToString("\t"))
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun extractXlsxViaZip(file: File): String {
        val zip = java.util.zip.ZipFile(file)
        val sb = StringBuilder()
        val sharedStrings = mutableListOf<String>()
        val ssEntry = zip.getEntry("xl/sharedStrings.xml")
        if (ssEntry != null) {
            val xml = zip.getInputStream(ssEntry).bufferedReader().readText()
            val siPattern = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
            val tPattern = Regex("""<t[^>]*>([^<]*)</t>""")
            siPattern.findAll(xml).forEach { siMatch ->
                val texts = tPattern.findAll(siMatch.groupValues[1]).map { it.groupValues[1] }.toList()
                sharedStrings.add(texts.joinToString(""))
            }
        }
        val sheetEntries = zip.entries().toList()
            .filter { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
            .sortedWith(NaturalSort.byName { it.name })
        for ((sheetIdx, sheetEntry) in sheetEntries.withIndex()) {
            sb.appendLine(getString(R.string.sheet_sheetidx_1))
            sb.appendLine()
            val xml = zip.getInputStream(sheetEntry).bufferedReader().readText()
            val rowPattern = Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
            val cellPattern = Regex("""<c\s+[^>]*?(?:t="([^"]*)")?[^>]*>(?:.*?<v>([^<]*)</v>)?.*?</c>""", RegexOption.DOT_MATCHES_ALL)
            rowPattern.findAll(xml).forEach { rowMatch ->
                val cells = mutableListOf<String>()
                cellPattern.findAll(rowMatch.groupValues[1]).forEach { cellMatch ->
                    val type = cellMatch.groupValues[1]
                    val value = cellMatch.groupValues[2]
                    val cellText = when (type) {
                        "s" -> {
                            val idx = value.toIntOrNull() ?: 0
                            sharedStrings.getOrElse(idx) { "" }
                        }
                        "inlineStr" -> value
                        else -> value
                    }
                    cells.add(cellText)
                }
                if (cells.isNotEmpty()) {
                    sb.appendLine(cells.joinToString("\t"))
                }
            }
            sb.appendLine()
        }
        zip.close()
        return if (sb.isEmpty()) getString(R.string.no_content_found) else sb.toString()
    }

    private fun extractPowerPoint(file: File, ext: String): String {
        val (effectiveFile, tempFile) = stageSafFileIfNeeded(file)
        return try {
            if (ext in OFFICE_PPT_LEGACY) {
                val fis = FileInputStream(effectiveFile)
                val ppt = org.apache.poi.hslf.usermodel.HSLFSlideShow(fis)
                val sb = StringBuilder()
                for ((idx, slide) in ppt.slides.withIndex()) {
                    sb.appendLine(getString(R.string.slide_idx_1))
                    for (shape in slide.shapes) {
                        if (shape is org.apache.poi.hslf.usermodel.HSLFTextShape) {
                            sb.appendLine(shape.text)
                        }
                    }
                    sb.appendLine()
                }
                ppt.close()
                fis.close()
                sb.toString()
            } else {
                extractPptxViaZip(effectiveFile)
            }
        } catch (e: Exception) {
            "Error extracting PowerPoint content:\n${e.message}"
        } finally {
            tempFile?.delete()
        }
    }

    private fun extractPptxViaZip(file: File): String {
        val zip = java.util.zip.ZipFile(file)
        val sb = StringBuilder()
        val slideEntries = zip.entries().toList()
            .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
            .sortedBy {
                Regex("""slide(\d+)\.xml""").find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        for ((idx, slideEntry) in slideEntries.withIndex()) {
            sb.appendLine(getString(R.string.slide_idx_1))
            val xml = zip.getInputStream(slideEntry).bufferedReader().readText()
            val textPattern = Regex("""<a:t>([^<]*)</a:t>""")
            val paraPattern = Regex("""<a:p>(.*?)</a:p>""", RegexOption.DOT_MATCHES_ALL)
            paraPattern.findAll(xml).forEach { paraMatch ->
                val texts = textPattern.findAll(paraMatch.groupValues[1])
                    .map { it.groupValues[1] }
                    .toList()
                if (texts.isNotEmpty()) {
                    sb.appendLine(texts.joinToString(""))
                }
            }
            sb.appendLine()
        }
        zip.close()
        return if (sb.isEmpty()) getString(R.string.no_text_content_found) else sb.toString()
    }

    private fun extractVisio(file: File): String {
        val (effectiveFile, tempFile) = stageSafFileIfNeeded(file)
        return try {
            val zip = java.util.zip.ZipFile(effectiveFile)
            val sb = StringBuilder()
            for (entry in zip.entries()) {
                if (entry.name.contains("page") && entry.name.endsWith(".xml")) {
                    val content = zip.getInputStream(entry).bufferedReader().readText()
                    val textPattern = Regex("<vt:Text[^>]*>([^<]+)</vt:Text>|<Text[^>]*>([^<]+)</Text>")
                    textPattern.findAll(content).forEach { match ->
                        val text = match.groupValues[1].ifEmpty { match.groupValues[2] }
                        if (text.isNotBlank()) sb.appendLine(text.trim())
                    }
                }
            }
            zip.close()
            if (sb.isEmpty()) getString(R.string.no_text_content_found_in_visio_file) else sb.toString()
        } catch (e: Exception) {
            "Error extracting Visio content:\n${e.message}"
        } finally {
            tempFile?.delete()
        }
    }

    override fun onDestroy() {
        highlightingTextWatcher?.let { txtContent.removeTextChangedListener(it) }
        highlightingTextWatcher = null
        highlightDebounceHandler?.removeCallbacksAndMessages(null)
        highlightDebounceHandler = null
        highlightDebounceRunnable = null
        searchHelper?.close()
        searchHelper = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (searchHelper?.isActive == true) {
            outState.putString("search_query", searchHelper?.currentQuery)
            // currentIndex can't be saved easily without the matches list;
            // we restore to 0 and let the user re-navigate.
            outState.putBoolean("search_active", true)
        }
    }

    // ── In-document search host implementation ────────────────────────────

    private fun createSearchHost(): SearchHost<IntRange> {
        return object : SearchHost<IntRange> {
            override fun findMatches(query: String): List<IntRange> {
                val text = txtContent.text?.toString() ?: return emptyList()
                if (query.isBlank()) return emptyList()
                val lowerQuery = query.lowercase()
                val lowerText = text.lowercase()
                val matches = mutableListOf<IntRange>()
                var start = 0
                while (true) {
                    val idx = lowerText.indexOf(lowerQuery, start)
                    if (idx < 0) break
                    matches.add(idx until (idx + query.length))
                    start = idx + 1
                }
                return matches
            }

            override fun highlightMatches(matches: List<IntRange>, currentIndex: Int) {
                val text = txtContent.text
                if (text !is Spannable) return

                // Remove any existing search highlight spans first
                val existing = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
                for (span in existing) {
                    text.removeSpan(span)
                }

                // Apply yellow background to all matches
                for ((i, range) in matches.withIndex()) {
                    val color = if (i == currentIndex) {
                        Color.parseColor("#8044B5F6") // Light blue for current match
                    } else {
                        Color.parseColor("#80FFEB3B") // Yellow for other matches
                    }
                    text.setSpan(
                        BackgroundColorSpan(color),
                        range.first,
                        range.last + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            override fun clearHighlights() {
                val text = txtContent.text
                if (text !is Spannable) return
                val spans = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
                for (span in spans) {
                    text.removeSpan(span)
                }
            }

            override fun scrollToMatch(matches: List<IntRange>, index: Int) {
                if (index !in matches.indices) return
                val range = matches[index]
                val text = txtContent.text?.toString() ?: return

                // ── Vertical scroll (line-based) ───────────────────────
                val textBefore = text.substring(0, range.first)
                val lineCount = textBefore.count { it == '\n' }
                val lineHeight = txtContent.lineHeight
                val targetY = lineCount * lineHeight
                scrollView.smoothScrollTo(
                    0,
                    (targetY - scrollView.height / 3).coerceAtLeast(0)
                )

                // ── Horizontal scroll (character column-based) ─────────
                // Text uses monospace font, so we measure from line start to match
                val lastNewline = textBefore.lastIndexOf('\n')
                val colInLine = range.first - lastNewline - 1
                val charWidth = txtContent.paint.measureText("A")
                val targetX = (colInLine * charWidth).toInt()
                hScrollView.smoothScrollTo(
                    (targetX - hScrollView.width / 3).coerceAtLeast(0),
                    0
                )
            }

            override fun getContext() = this@TextViewerActivity
        }
    }

    companion object {
        // Pages are deliberately small: the content EditText has wrap_content width, so
        // every layout pass re-measures the whole page's glyphs on the main thread
        // (TextView.onMeasure -> Layout.getDesiredWidthWithLimit -> TextLine.metrics).
        // 64 KB pages exceeded the ANR watchdog's 5s budget on low-end TV boxes
        // (ZTE OTT Xview+ AV1, SDK 30); 16 KB keeps a page measurement well under it.
        private const val PAGE_BYTE_SIZE = 16 * 1024

        // Documents larger than this (UTF-8 bytes) cannot be opened in edit mode:
        // setText() of the whole document on the main thread re-lays-out every glyph,
        // freezing the app for >5s on slow devices. Viewing stays paginated.
        private const val EDIT_MAX_BYTES = 128 * 1024

        private val OFFICE_WORD_LEGACY = setOf("doc", "dot")
        private val OFFICE_WORD_OOXML = setOf("docx", "docm", "dotx", "dotm")
        val OFFICE_WORD_EXTENSIONS = OFFICE_WORD_LEGACY + OFFICE_WORD_OOXML

        private val OFFICE_EXCEL_LEGACY = setOf("xls", "xlt", "xlsb")
        private val OFFICE_EXCEL_OOXML = setOf("xlsx", "xlsm", "xltx", "xltm")
        val OFFICE_EXCEL_EXTENSIONS = OFFICE_EXCEL_LEGACY + OFFICE_EXCEL_OOXML

        private val OFFICE_PPT_LEGACY = setOf("ppt", "pps", "pot")
        private val OFFICE_PPT_OOXML = setOf("pptx", "pptm", "ppsx", "potx", "potm")
        val OFFICE_PPT_EXTENSIONS = OFFICE_PPT_LEGACY + OFFICE_PPT_OOXML
    }
}
