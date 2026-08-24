package za.kilowatch.ultimatefilemanager.viewer

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.DataFormatter
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import za.kilowatch.ultimatefilemanager.settings.GridIndicatorsPreferenceManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import java.io.File
import java.io.FileInputStream
import java.lang.StringBuilder
import java.util.zip.ZipFile
import android.view.ScaleGestureDetector
import android.view.MotionEvent

class SpreadsheetViewerActivity : AppCompatActivity() {

    private lateinit var tableLayout: TableLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var txtTitle: TextView
    private lateinit var sheetTabsScroll: HorizontalScrollView
    private lateinit var sheetTabsContainer: LinearLayout
    private lateinit var btnRotate: ImageView

    private var sheets: List<Spreadsheet> = emptyList()
    private var currentSheetIndex = 0
    private var isTv = false
    private var scaleFactor = 1.0f
    private var gestureScale = 1.0f
    private var scaleDetector: ScaleGestureDetector? = null
    private lateinit var tableScrollView: ScrollView
    private lateinit var tableHScrollView: HorizontalScrollView

    // ── In-document search fields ──────────────────────────────────────
    private var searchHelper: DocumentSearchHelper<Pair<Int, Int>>? = null
    private val allCellViews = mutableListOf<Pair<Pair<Int, Int>, TextView>>()
    private var searchQuery: String = ""
    private var searchIsActive: Boolean = false

    // Performant ceiling to keep grid rendering exceptionally fast and memory-safe
    private val MAX_ROWS_PER_SHEET = 1000

    class Spreadsheet(val name: String, val rows: List<List<String>>, val isTruncated: Boolean = false)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_spreadsheet_viewer_tv
            else R.layout.activity_spreadsheet_viewer
        )

        // Prevent navigation bar overlaps
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        // Restore search state after configuration change
        if (savedInstanceState != null) {
            searchIsActive = savedInstanceState.getBoolean("search_active", false)
            searchQuery = savedInstanceState.getString("search_query", "") ?: ""
        }

        tableLayout = findViewById(R.id.tableLayout)
        progressBar = findViewById(R.id.progressBar)
        txtTitle = findViewById(R.id.txtTitle)
        sheetTabsScroll = findViewById(R.id.sheetTabsScroll)
        sheetTabsContainer = findViewById(R.id.sheetTabsContainer)
        btnRotate = findViewById(R.id.btnRotate)
        tableScrollView = findViewById(R.id.tableScrollView)
        tableHScrollView = findViewById(R.id.tableHScrollView)

        if (!isTv) {
            scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var startFocusX = 0f
                private var startFocusY = 0f
                private var pivotX = 0f
                private var pivotY = 0f

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    tableScrollView.requestDisallowInterceptTouchEvent(true)
                    tableHScrollView.requestDisallowInterceptTouchEvent(true)
                    val loc = IntArray(2)
                    tableLayout.getLocationInWindow(loc)
                    pivotX = detector.focusX - loc[0]
                    pivotY = detector.focusY - loc[1]
                    tableLayout.pivotX = pivotX
                    tableLayout.pivotY = pivotY
                    startFocusX = detector.focusX
                    startFocusY = detector.focusY
                    gestureScale = 1.0f
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    gestureScale = (gestureScale * detector.scaleFactor).coerceIn(0.5f / scaleFactor, 3.0f / scaleFactor)
                    tableLayout.scaleX = gestureScale
                    tableLayout.scaleY = gestureScale
                    tableLayout.translationX = detector.focusX - startFocusX
                    tableLayout.translationY = detector.focusY - startFocusY
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    val targetScale = (scaleFactor * gestureScale).coerceIn(0.5f, 3.0f)
                    gestureScale = targetScale / scaleFactor

                    tableLayout.scaleX = 1.0f
                    tableLayout.scaleY = 1.0f
                    tableLayout.translationX = 0f
                    tableLayout.translationY = 0f

                    val scrollLoc = IntArray(2)
                    tableScrollView.getLocationInWindow(scrollLoc)

                    val focusX = detector.focusX
                    val focusY = detector.focusY

                    scaleFactor = targetScale
                    updateCellTextSizes()

                    tableLayout.post {
                        val newScrollX = (pivotX * gestureScale - (focusX - scrollLoc[0])).toInt()
                        val newScrollY = (pivotY * gestureScale - (focusY - scrollLoc[1])).toInt()
                        tableHScrollView.scrollTo(maxOf(0, newScrollX), 0)
                        tableScrollView.scrollTo(0, maxOf(0, newScrollY))
                    }
                }
            })
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Setup Screen Rotation Toggle
        btnRotate.setOnClickListener {
            toggleScreenOrientation()
        }

        // Setup Convert to PDF Click Listener
        findViewById<View>(R.id.btnConvertToPdf).setOnClickListener {
            val path = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: return@setOnClickListener
            val name = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME) ?: File(path).name
            val dialog = za.kilowatch.ultimatefilemanager.ui.ConvertToPdfDialog().apply {
                arguments = Bundle().apply {
                    putString("original_filename", File(name).nameWithoutExtension)
                    putString("document_path", path)
                }
            }
            dialog.show(supportFragmentManager, "ConvertToPdfDialog")
        }

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

        // Restore search query after config change
        if (searchIsActive && searchQuery.isNotEmpty()) {
            btnSearch.post { searchHelper?.restoreState(searchQuery, 0) }
        }

        val filePath = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: run {
            finish(); return
        }
        val fileName = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME) ?: getString(R.string.spreadsheet_viewer_fallback_title)
        txtTitle.text = fileName

        loadSpreadsheet(File(filePath))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isTv) {
            scaleDetector?.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun updateCellTextSizes() {
        val dp = { px: Int -> (px * resources.displayMetrics.density).toInt() }
        val padH = dp((12 * scaleFactor).toInt())
        val padV = dp((8 * scaleFactor).toInt())
        val currentTextSize = 14f * scaleFactor

        for (i in 0 until tableLayout.childCount) {
            val row = tableLayout.getChildAt(i) as? TableRow ?: continue
            for (j in 0 until row.childCount) {
                val cell = row.getChildAt(j) as? TextView ?: continue
                cell.textSize = currentTextSize
                cell.setPadding(padH, padV, padH, padV)
            }
        }
    }

    private fun toggleScreenOrientation() {
        val currentOrientation = requestedOrientation
        if (currentOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Toast.makeText(this, R.string.spreadsheet_portrait_toast, Toast.LENGTH_SHORT).show()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Toast.makeText(this, R.string.spreadsheet_landscape_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSpreadsheet(file: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ext = file.extension.lowercase()
                val parsedSheets = when (ext) {
                    "csv" -> loadCsv(file)
                    "xls", "xlt", "xlsb" -> loadXls(file)
                    else -> loadXlsx(file) // xlsx, xlsm, xltx, xltm
                }

                withContext(Dispatchers.Main) {
                    sheets = parsedSheets
                    progressBar.visibility = View.GONE
                    setupSheetTabs()
                    if (sheets.isNotEmpty()) {
                        displaySheet(0)
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@SpreadsheetViewerActivity, getString(R.string.spreadsheet_error_parsing, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupSheetTabs() {
        sheetTabsContainer.removeAllViews()
        if (sheets.size <= 1) {
            sheetTabsScroll.visibility = View.GONE
            return
        }
        sheetTabsScroll.visibility = View.VISIBLE

        val dp = { px: Int -> (px * resources.displayMetrics.density).toInt() }

        sheets.forEachIndexed { index, sheet ->
            val tabView = TextView(this).apply {
                text = sheet.name
                textSize = if (isTv) 16f else 14f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                
                // Beautiful capsule background styling
                val normalBg = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_glass_card))
                    setStroke(dp(1), ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_glass_stroke))
                }
                val selectedBg = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    val color = if (isTv) {
                        ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.tv_button_focused_yellow)
                    } else {
                        ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.ufm_accent)
                    }
                    setColor(color)
                }

                background = normalBg
                setTextColor(if (isTv) Color.WHITE else ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_text_secondary))

                setOnFocusChangeListener { _, hasFocus ->
                    if (isTv) {
                        if (hasFocus) {
                            background = selectedBg
                            setTextColor(Color.BLACK)
                        } else if (index != currentSheetIndex) {
                            background = normalBg
                            setTextColor(Color.WHITE)
                        }
                    }
                }

                setOnClickListener {
                    displaySheet(index)
                    // Update highlighting indicators for selected tabs
                    for (i in 0 until sheetTabsContainer.childCount) {
                        val child = sheetTabsContainer.getChildAt(i) as TextView
                        if (i == index) {
                            child.background = selectedBg
                            child.setTextColor(if (isTv) Color.BLACK else Color.WHITE)
                        } else {
                            child.background = normalBg
                            child.setTextColor(if (isTv) Color.WHITE else ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_text_secondary))
                        }
                    }
                }
            }
            
            if (index == currentSheetIndex) {
                val selectedBg = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    val color = if (isTv) {
                        ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.tv_button_focused_yellow)
                    } else {
                        ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.ufm_accent)
                    }
                    setColor(color)
                }
                tabView.background = selectedBg
                tabView.setTextColor(if (isTv) Color.BLACK else Color.WHITE)
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(8)
            }
            sheetTabsContainer.addView(tabView, params)
        }
    }

    private fun displaySheet(index: Int) {
        if (index !in sheets.indices) return
        currentSheetIndex = index
        val sheet = sheets[index]

        tableLayout.removeAllViews()
        allCellViews.clear()

        val rows = sheet.rows
        if (rows.isEmpty()) return

        val dp = { px: Int -> (px * resources.displayMetrics.density).toInt() }
        val padH = dp((12 * scaleFactor).toInt())
        val padV = dp((8 * scaleFactor).toInt())
        val currentTextSize = if (isTv) 15f * scaleFactor else 13f * scaleFactor

        // Determine actual maximum columns populated in this sheet
        val maxCols = rows.maxOfOrNull { it.size } ?: 0
        val indicatorsHidden = GridIndicatorsPreferenceManager.isHidden(this)

        // Create Excel Style Column Headers (e.g. A, B, C...) — only when not hidden
        if (!indicatorsHidden) {
            val headerRow = TableRow(this)

            // Empty Top-Left Cell representing the row header origin
            val topLeftCell = TextView(this).apply {
                text = " "
                textSize = currentTextSize
                setPadding(padH, padV, padH, padV)
                gravity = Gravity.CENTER
                val headerBg = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_glass_card))
                    setStroke(1, ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_glass_stroke))
                }
                background = headerBg
                typeface = Typeface.DEFAULT_BOLD
            }
            headerRow.addView(topLeftCell)

            for (colIdx in 0 until maxCols) {
                val headerCell = TextView(this).apply {
                    text = getColumnLabel(colIdx)
                    textSize = currentTextSize
                    setPadding(padH, padV, padH, padV)
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_text_primary))
                    val headerBg = GradientDrawable().apply {
                        setColor(ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_glass_card))
                        setStroke(1, ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_glass_stroke))
                    }
                    background = headerBg
                    typeface = Typeface.DEFAULT_BOLD
                }
                headerRow.addView(headerCell)
            }
            tableLayout.addView(headerRow)
        }

        // Populate Table Grid Data rows
        rows.forEachIndexed { rowIdx, rowCells ->
            val tableRow = TableRow(this)

            // Add row label (number) — only when not hidden
            if (!indicatorsHidden) {
                val rowLabelCell = TextView(this).apply {
                    text = (rowIdx + 1).toString()
                    textSize = currentTextSize
                    setPadding(padH, padV, padH, padV)
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_text_primary))
                    val headerBg = GradientDrawable().apply {
                        setColor(ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_glass_card))
                        setStroke(1, ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_glass_stroke))
                    }
                    background = headerBg
                    typeface = Typeface.DEFAULT_BOLD
                }
                tableRow.addView(rowLabelCell)
            }

            // Add individual cells
            for (colIdx in 0 until maxCols) {
                val value = rowCells.getOrNull(colIdx) ?: ""
                val dataCell = TextView(this).apply {
                    text = value
                    textSize = currentTextSize
                    setPadding(padH, padV, padH, padV)
                    setTextColor(ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_text_primary))
                    isClickable = true
                    isFocusable = true

                    // Glass grid divider borders
                    val borderDrawable = GradientDrawable().apply {
                        setColor(Color.parseColor("#08FFFFFF"))
                        setStroke(1, ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_glass_stroke))
                    }
                    background = borderDrawable

                    // Store original drawable for search highlight restoration
                    setTag(R.id.tag_original_drawable, borderDrawable)

                    setOnFocusChangeListener { view, hasFocus ->
                        if (hasFocus) {
                            val focusedBorder = GradientDrawable().apply {
                                setColor(ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.tv_button_focused_yellow))
                                setStroke(2, Color.WHITE)
                            }
                            view.background = focusedBorder
                            (view as TextView).setTextColor(Color.BLACK)
                        } else {
                            view.background = borderDrawable
                            (view as TextView).setTextColor(ContextCompat.getColor(this@SpreadsheetViewerActivity, R.color.mobile_text_primary))
                        }
                    }
                }
                tableRow.addView(dataCell)
                // Store cell reference for search
                allCellViews.add(Pair(rowIdx, colIdx) to dataCell)
            }
            tableLayout.addView(tableRow)
        }

        // Re-run search on the new sheet if search is active
        if (searchHelper?.isActive == true) {
            searchHelper?.reRunSearch()
        }

        // Show a helpful warning footer if the sheet content was truncated due to performance limits
        if (sheet.isTruncated) {
            val warningRow = TableRow(this)
            val warningCell = TextView(this).apply {
                text = getString(R.string.spreadsheet_performance_warning, MAX_ROWS_PER_SHEET)
                setTextColor(Color.parseColor("#FFFBBF24")) // Yellow warning color
                textSize = currentTextSize
                setPadding(dp((16 * scaleFactor).toInt()), dp((12 * scaleFactor).toInt()), dp((16 * scaleFactor).toInt()), dp((12 * scaleFactor).toInt()))
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            // Span across all columns plus row indicator
            val params = TableRow.LayoutParams().apply {
                span = if (!indicatorsHidden) maxCols + 1 else maxCols
            }
            warningRow.addView(warningCell, params)
            tableLayout.addView(warningRow)
        }
    }

    private fun getColumnLabel(index: Int): String {
        var temp = index
        val sb = StringBuilder()
        while (temp >= 0) {
            sb.insert(0, ('A'.toInt() + (temp % 26)).toChar())
            temp = temp / 26 - 1
        }
        return sb.toString()
    }

    private fun openInputStream(file: File): java.io.InputStream? {
        val isSaf = file is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, file.absolutePath)
        return if (isSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(this, file.absolutePath)
        } else {
            try { FileInputStream(file) } catch (_: Exception) { null }
        }
    }

    private fun loadCsv(file: File): List<Spreadsheet> {
        val rows = mutableListOf<List<String>>()
        var truncated = false
        var rowCount = 0

        val inStream = openInputStream(file) ?: throw java.io.FileNotFoundException("Cannot open ${file.name}")
        inStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (rowCount >= MAX_ROWS_PER_SHEET) {
                    truncated = true
                    break
                }
                val cells = splitCsvLine(line)
                rows.add(cells)
                rowCount++
            }
        }
        return listOf(Spreadsheet(getString(R.string.spreadsheet_viewer_fallback_title) + " 1", rows, truncated))
    }

    private fun splitCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        val delimiter = if (line.contains(";")) ';' else ','
        while (i < line.length) {
            val c = line[i]
            if (c == '\"') {
                inQuotes = !inQuotes
            } else if (c == delimiter && !inQuotes) {
                cells.add(sb.toString().trim())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        cells.add(sb.toString().trim())
        return cells
    }

    private fun loadXls(file: File): List<Spreadsheet> {
        val result = mutableListOf<Spreadsheet>()
        val inStream = openInputStream(file) ?: throw java.io.FileNotFoundException("Cannot open ${file.name}")
        inStream.use { fis ->
            HSSFWorkbook(fis).use { wb ->
                val formatter = DataFormatter()
                for (i in 0 until wb.numberOfSheets) {
                    val poiSheet = wb.getSheetAt(i)
                    val rows = mutableListOf<List<String>>()
                    var truncated = false
                    var rowCount = 0

                    for (row in poiSheet) {
                        if (rowCount >= MAX_ROWS_PER_SHEET) {
                            truncated = true
                            break
                        }
                        val cells = mutableListOf<String>()
                        val lastCellNum = row.lastCellNum.toInt()
                        for (colIdx in 0 until lastCellNum) {
                            val cell = row.getCell(colIdx)
                            cells.add(formatter.formatCellValue(cell) ?: "")
                        }
                        rows.add(cells)
                        rowCount++
                    }
                    result.add(Spreadsheet(wb.getSheetName(i) ?: (getString(R.string.spreadsheet_viewer_fallback_title) + " ${i + 1}"), rows, truncated))
                }
            }
        }
        return result
    }

    private fun loadXlsx(file: File): List<Spreadsheet> {
        val isSaf = file is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, file.absolutePath)
        var tempFile: File? = null
        val targetFile = if (isSaf) {
            tempFile = File(cacheDir, "temp_sheet_${System.currentTimeMillis()}.xlsx")
            openInputStream(file)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            tempFile
        } else {
            file
        }

        try {
            val result = mutableListOf<Spreadsheet>()
            ZipFile(targetFile).use { zip ->
            // 1. Read shared strings definitions
            val sharedStrings = mutableListOf<String>()
            val ssEntry = zip.getEntry("xl/sharedStrings.xml")
            if (ssEntry != null) {
                zip.getInputStream(ssEntry).bufferedReader().use { reader ->
                    val xml = reader.readText()
                    val siPattern = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
                    val tPattern = Regex("""<t[^>]*>([^<]*)</t>""")
                    siPattern.findAll(xml).forEach { siMatch ->
                        val texts = tPattern.findAll(siMatch.groupValues[1]).map { it.groupValues[1] }.toList()
                        sharedStrings.add(texts.joinToString(""))
                    }
                }
            }

            // 2. Parse workbook names
            val sheetNames = mutableListOf<String>()
            val wbEntry = zip.getEntry("xl/workbook.xml")
            if (wbEntry != null) {
                zip.getInputStream(wbEntry).bufferedReader().use { reader ->
                    val xml = reader.readText()
                    val sheetPattern = Regex("""<sheet\s+[^>]*?name="([^"]*)"[^>]*>""")
                    sheetPattern.findAll(xml).forEach { match ->
                        sheetNames.add(match.groupValues[1])
                    }
                }
            }

            // 3. Extract worksheet sheets
            val sheetEntries = zip.entries().toList()
                .filter { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
                .sortedWith(NaturalSort.byName { it.name })

            val rowPattern = Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
            val cellPattern = Regex("""<c\s+[^>]*?(?:t="([^"]*)")?[^>]*>(?:.*?<v>([^<]*)</v>)?.*?</c>""", RegexOption.DOT_MATCHES_ALL)
            val cellRefPattern = Regex("""r="([A-Z]+)(\d+)"""")

            for ((sheetIdx, sheetEntry) in sheetEntries.withIndex()) {
                val rows = mutableListOf<List<String>>()
                var truncated = false
                var rowCount = 0

                zip.getInputStream(sheetEntry).bufferedReader().use { reader ->
                    val xml = reader.readText()
                    rowPattern.findAll(xml).forEach { rowMatch ->
                        if (rowCount >= MAX_ROWS_PER_SHEET) {
                            truncated = true
                            return@forEach
                        }
                        val cells = mutableMapOf<Int, String>()
                        var maxColIdx = -1
                        cellPattern.findAll(rowMatch.groupValues[1]).forEach { cellMatch ->
                            val cellXml = cellMatch.value
                            val type = cellMatch.groupValues[1]
                            val value = cellMatch.groupValues[2]

                            val refMatch = cellRefPattern.find(cellXml)
                            val colIdx = if (refMatch != null) {
                                excelColToIndex(refMatch.groupValues[1])
                            } else {
                                maxColIdx + 1
                            }
                            maxColIdx = maxOf(maxColIdx, colIdx)

                            val cellText = when (type) {
                                "s" -> {
                                    val idx = value.toIntOrNull() ?: 0
                                    sharedStrings.getOrElse(idx) { "" }
                                }
                                "inlineStr" -> value
                                else -> value
                            }
                            cells[colIdx] = cellText
                        }

                        val rowList = mutableListOf<String>()
                        for (colIdx in 0..maxColIdx) {
                            rowList.add(cells[colIdx] ?: "")
                        }
                        rows.add(rowList)
                        rowCount++
                    }
                }

                val sheetName = sheetNames.getOrNull(sheetIdx) ?: (getString(R.string.spreadsheet_viewer_fallback_title) + " ${sheetIdx + 1}")
                result.add(Spreadsheet(sheetName, rows, truncated))
            }
            }
            return result
        } finally {
            tempFile?.delete()
        }
    }

    private fun excelColToIndex(colStr: String): Int {
        var index = 0
        for (c in colStr) {
            index = index * 26 + (c - 'A' + 1)
        }
        return index - 1
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (searchHelper?.isActive == true) {
            outState.putString("search_query", searchHelper?.currentQuery)
            outState.putBoolean("search_active", true)
        }
    }

    override fun onDestroy() {
        searchHelper?.close()
        searchHelper = null
        super.onDestroy()
    }

    // ── In-document search host implementation ────────────────────────────

    private fun createSearchHost(): SearchHost<Pair<Int, Int>> {
        return object : SearchHost<Pair<Int, Int>> {
            override fun findMatches(query: String): List<Pair<Int, Int>> {
                if (query.isBlank()) return emptyList()
                val lowerQuery = query.lowercase()
                val matches = mutableListOf<Pair<Int, Int>>()
                for ((pair, cell) in allCellViews) {
                    val cellText = cell.text?.toString()?.lowercase() ?: continue
                    if (cellText.contains(lowerQuery)) {
                        matches.add(pair)
                    }
                }
                return matches
            }

            override fun highlightMatches(matches: List<Pair<Int, Int>>, currentIndex: Int) {
                // First pass: reset all cells to their original border background
                for ((_, cell) in allCellViews) {
                    val original = cell.getTag(R.id.tag_original_drawable) as? GradientDrawable
                    if (original != null) {
                        cell.background = original
                    }
                    cell.setTextColor(Color.WHITE)
                }

                // Second pass: apply highlight colors
                for ((i, pair) in matches.withIndex()) {
                    val cell = allCellViews.find { it.first == pair }?.second ?: continue
                    val color = if (i == currentIndex) {
                        Color.parseColor("#8044B5F6") // Light blue for current match
                    } else {
                        Color.parseColor("#80FFEB3B") // Yellow for other matches
                    }
                    cell.setBackgroundColor(color)
                    cell.setTextColor(Color.BLACK)
                }
            }

            override fun clearHighlights() {
                for ((_, cell) in allCellViews) {
                    val original = cell.getTag(R.id.tag_original_drawable) as? GradientDrawable
                    if (original != null) {
                        cell.background = original
                    }
                    cell.setTextColor(Color.WHITE)
                }
            }

            override fun scrollToMatch(matches: List<Pair<Int, Int>>, index: Int) {
                if (index !in matches.indices) return
                val (row, _) = matches[index]
                val estimatedCellHeight = 48 * scaleFactor
                val targetY = (row * estimatedCellHeight).toInt()
                tableScrollView.smoothScrollTo(0, (targetY - tableScrollView.height / 3).coerceAtLeast(0))
            }

            override fun getContext() = this@SpreadsheetViewerActivity
        }
    }
}
