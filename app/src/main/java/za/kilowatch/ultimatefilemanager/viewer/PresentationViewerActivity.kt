package za.kilowatch.ultimatefilemanager.viewer

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.MimeTypeHelper
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/**
 * Visual Presentation Viewer for Mobile and Android TV.
 *
 * Supports modern OOXML (.pptx, .pptm, .ppsx, .potx, .potm) via direct ZIP+XML parsing
 * with embedded media and speaker notes extraction, as well as legacy (.ppt, .pps, .pot)
 * via Apache POI HSLF.
 *
 * Provides instant slide-deck navigation, TV D-Pad controls, "Open in PowerPoint/Google Slides"
 * external delegation, and "View Raw Text" fallback.
 */
class PresentationViewerActivity : AppCompatActivity() {

    private lateinit var txtTitle: TextView
    private lateinit var txtSlideCounter: TextView
    private lateinit var btnBack: View
    private lateinit var btnOpenExternal: View
    private lateinit var btnViewText: View
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var scrollViewSlide: ScrollView
    private lateinit var cardSlide: MaterialCardView
    private lateinit var txtSlideBadge: TextView
    private lateinit var txtSlideTitle: TextView
    private lateinit var imgSlideMedia: ImageView
    private lateinit var txtSlideContent: TextView
    private lateinit var layoutSpeakerNotes: LinearLayout
    private lateinit var txtSpeakerNotes: TextView
    private lateinit var btnPrevSlide: MaterialButton
    private lateinit var txtBottomIndicator: TextView
    private lateinit var btnNextSlide: MaterialButton

    private var isTv = false
    private var presentationFile: File? = null
    private var slides: List<PresentationSlide> = emptyList()
    private var currentSlideIndex = 0

    data class PresentationSlide(
        val slideNumber: Int,
        val title: String,
        val bullets: List<String>,
        val notes: String? = null,
        val imageBytes: ByteArray? = null
    )

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_presentation_viewer_tv
            else R.layout.activity_presentation_viewer
        )

        // Prevent navigation bar overlaps on mobile
        findViewById<View>(R.id.main)?.let { mainView ->
            ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
                val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
                insets
            }
        }

        initViews()

        val filePath = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH)
            ?: intent.data?.path
            ?: run {
                Toast.makeText(this, R.string.failed_to_open_file, Toast.LENGTH_SHORT).show()
                finish()
                return
            }

        val file = File(filePath)
        presentationFile = file
        val displayName = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME) ?: file.name
        txtTitle.text = displayName

        loadPresentation(file)
    }

    private fun initViews() {
        txtTitle = findViewById(R.id.txtTitle)
        txtSlideCounter = findViewById(R.id.txtSlideCounter)
        btnBack = findViewById(R.id.btnBack)
        btnOpenExternal = findViewById(R.id.btnOpenExternal)
        btnViewText = findViewById(R.id.btnViewText)
        progressBar = findViewById(R.id.progressBar)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        scrollViewSlide = findViewById(R.id.scrollViewSlide)
        cardSlide = findViewById(R.id.cardSlide)
        txtSlideBadge = findViewById(R.id.txtSlideBadge)
        txtSlideTitle = findViewById(R.id.txtSlideTitle)
        imgSlideMedia = findViewById(R.id.imgSlideMedia)
        txtSlideContent = findViewById(R.id.txtSlideContent)
        layoutSpeakerNotes = findViewById(R.id.layoutSpeakerNotes)
        txtSpeakerNotes = findViewById(R.id.txtSpeakerNotes)
        btnPrevSlide = findViewById(R.id.btnPrevSlide)
        txtBottomIndicator = findViewById(R.id.txtBottomIndicator)
        btnNextSlide = findViewById(R.id.btnNextSlide)

        btnBack.setOnClickListener { finish() }

        btnOpenExternal.setOnClickListener {
            openExternalApp()
        }

        btnViewText.setOnClickListener {
            openRawText()
        }

        btnPrevSlide.setOnClickListener {
            if (currentSlideIndex > 0) {
                currentSlideIndex--
                displaySlide(currentSlideIndex)
            }
        }

        btnNextSlide.setOnClickListener {
            if (currentSlideIndex < slides.size - 1) {
                currentSlideIndex++
                displaySlide(currentSlideIndex)
            }
        }
    }

    private fun loadPresentation(file: File) {
        progressBar.visibility = View.VISIBLE
        scrollViewSlide.visibility = View.GONE
        layoutEmpty.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val (effectiveFile, tempFile) = stageFileIfNeeded(file)
            val ext = file.extension.lowercase()
            val parsedSlides = try {
                if (ext in LEGACY_PPT_EXTENSIONS) {
                    parseLegacyPpt(effectiveFile)
                } else {
                    parseOoxmlPptx(effectiveFile)
                }
            } catch (e: Exception) {
                android.util.Log.e("PresentationViewer", "Error parsing presentation: ${e.message}", e)
                emptyList()
            } finally {
                tempFile?.delete()
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (parsedSlides.isEmpty()) {
                    layoutEmpty.visibility = View.VISIBLE
                    txtSlideCounter.text = getString(R.string.no_slides_found)
                    btnPrevSlide.isEnabled = false
                    btnNextSlide.isEnabled = false
                } else {
                    slides = parsedSlides
                    currentSlideIndex = 0
                    scrollViewSlide.visibility = View.VISIBLE
                    displaySlide(0)
                }
            }
        }
    }

    private fun displaySlide(index: Int) {
        if (index !in slides.indices) return
        val slide = slides[index]

        txtSlideBadge.text = getString(R.string.slide_badge_format, slide.slideNumber)
        txtSlideTitle.text = slide.title.ifBlank { getString(R.string.slide_badge_format, slide.slideNumber) }

        if (slide.bullets.isNotEmpty()) {
            val bulletText = slide.bullets.joinToString("\n\n") { "•  $it" }
            txtSlideContent.text = bulletText
            txtSlideContent.visibility = View.VISIBLE
        } else {
            txtSlideContent.visibility = View.GONE
        }

        if (slide.imageBytes != null && slide.imageBytes.isNotEmpty()) {
            val bitmap = BitmapFactory.decodeByteArray(slide.imageBytes, 0, slide.imageBytes.size)
            if (bitmap != null) {
                imgSlideMedia.setImageBitmap(bitmap)
                imgSlideMedia.visibility = View.VISIBLE
            } else {
                imgSlideMedia.visibility = View.GONE
            }
        } else {
            imgSlideMedia.visibility = View.GONE
        }

        if (!slide.notes.isNullOrBlank()) {
            txtSpeakerNotes.text = slide.notes
            layoutSpeakerNotes.visibility = View.VISIBLE
        } else {
            layoutSpeakerNotes.visibility = View.GONE
        }

        val counterText = getString(R.string.slide_counter_format, index + 1, slides.size)
        txtSlideCounter.text = counterText
        txtBottomIndicator.text = "${index + 1} / ${slides.size}"

        btnPrevSlide.isEnabled = index > 0
        btnNextSlide.isEnabled = index < slides.size - 1

        // Scroll back to top of card on slide transition
        scrollViewSlide.scrollTo(0, 0)
    }

    private fun parseOoxmlPptx(file: File): List<PresentationSlide> {
        val result = mutableListOf<PresentationSlide>()
        ZipFile(file).use { zip ->
            // Extract cover thumbnail if available
            val coverThumbnail: ByteArray? = zip.getEntry("docProps/thumbnail.jpeg")?.let { entry ->
                zip.getInputStream(entry).use { it.readBytes() }
            } ?: zip.getEntry("docProps/thumbnail.png")?.let { entry ->
                zip.getInputStream(entry).use { it.readBytes() }
            }

            // Find all slide XML files and sort them by slide index
            val slideEntries = zip.entries().toList()
                .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                .sortedBy { entry ->
                    Regex("""slide(\d+)\.xml""").find(entry.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }

            // Also find all slide media images
            val mediaEntries = zip.entries().toList()
                .filter { it.name.startsWith("ppt/media/image") }
                .sortedBy { entry ->
                    Regex("""image(\d+)\.""").find(entry.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }

            val textPattern = Regex("""<a:t>([^<]*)</a:t>""")
            val paraPattern = Regex("""<a:p>(.*?)</a:p>""", RegexOption.DOT_MATCHES_ALL)

            for ((idx, slideEntry) in slideEntries.withIndex()) {
                val slideXml = zip.getInputStream(slideEntry).bufferedReader().readText()
                val paragraphs = mutableListOf<String>()

                paraPattern.findAll(slideXml).forEach { paraMatch ->
                    val text = textPattern.findAll(paraMatch.groupValues[1])
                        .map { unescapeXml(it.groupValues[1]) }
                        .joinToString("")
                        .trim()
                    if (text.isNotEmpty()) {
                        paragraphs.add(text)
                    }
                }

                val title = if (paragraphs.isNotEmpty()) paragraphs.removeAt(0) else ""
                val bullets = paragraphs

                // Try reading speaker notes for this slide
                val slideNum = idx + 1
                val notesEntry = zip.getEntry("ppt/notesSlides/notesSlide$slideNum.xml")
                val notes = notesEntry?.let { entry ->
                    val notesXml = zip.getInputStream(entry).bufferedReader().readText()
                    val noteTexts = mutableListOf<String>()
                    paraPattern.findAll(notesXml).forEach { paraMatch ->
                        val text = textPattern.findAll(paraMatch.groupValues[1])
                            .map { unescapeXml(it.groupValues[1]) }
                            .joinToString("")
                            .trim()
                        if (text.isNotEmpty()) noteTexts.add(text)
                    }
                    if (noteTexts.isNotEmpty()) noteTexts.joinToString("\n") else null
                }

                // Associate image: cover thumbnail for slide 1, or media image matching index
                val slideImage: ByteArray? = if (idx == 0 && coverThumbnail != null) {
                    coverThumbnail
                } else if (idx < mediaEntries.size) {
                    zip.getInputStream(mediaEntries[idx]).use { it.readBytes() }
                } else {
                    null
                }

                result.add(
                    PresentationSlide(
                        slideNumber = slideNum,
                        title = title,
                        bullets = bullets,
                        notes = notes,
                        imageBytes = slideImage
                    )
                )
            }
        }
        return result
    }

    private fun parseLegacyPpt(file: File): List<PresentationSlide> {
        val result = mutableListOf<PresentationSlide>()
        FileInputStream(file).use { fis ->
            val ppt = org.apache.poi.hslf.usermodel.HSLFSlideShow(fis)
            for ((idx, slide) in ppt.slides.withIndex()) {
                val slideNum = idx + 1
                var title = slide.title ?: ""
                val bullets = mutableListOf<String>()

                for (shape in slide.shapes) {
                    if (shape is org.apache.poi.hslf.usermodel.HSLFTextShape) {
                        val text = shape.text?.trim() ?: continue
                        if (text.isEmpty()) continue
                        if (title.isEmpty() && (text == slide.title || shape.shapeName.contains("title", ignoreCase = true))) {
                            title = text
                        } else if (text != title) {
                            bullets.add(text)
                        }
                    }
                }

                // Extract pictures if available
                val pictureBytes: ByteArray? = try {
                    var bytes: ByteArray? = null
                    for (shape in slide.shapes) {
                        if (shape is org.apache.poi.hslf.usermodel.HSLFPictureShape) {
                            val picData = shape.pictureData
                            if (picData != null) {
                                bytes = picData.data
                                break
                            }
                        }
                    }
                    bytes
                } catch (_: Exception) {
                    null
                }

                // Speaker notes
                val notes = try {
                    val notesList = mutableListOf<String>()
                    slide.notes?.shapes?.forEach { shape ->
                        if (shape is org.apache.poi.hslf.usermodel.HSLFTextShape) {
                            val t = shape.text?.trim()
                            if (!t.isNullOrBlank()) notesList.add(t)
                        }
                    }
                    notesList.joinToString("\n\n").takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }

                result.add(
                    PresentationSlide(
                        slideNumber = slideNum,
                        title = title.ifBlank { getString(R.string.slide_badge_format, slideNum) },
                        bullets = bullets,
                        notes = notes,
                        imageBytes = pictureBytes
                    )
                )
            }
            ppt.close()
        }
        return result
    }

    private fun unescapeXml(text: String): String {
        return text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun stageFileIfNeeded(file: File): Pair<File, File?> {
        if (file !is za.kilowatch.ultimatefilemanager.storage.RootFile && file.exists() && file.canRead()) {
            return Pair(file, null)
        }
        val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(this, file)
        if (isSaf) {
            val temp = File(cacheDir, "temp_ppt_view_${System.currentTimeMillis()}.${file.extension}")
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(this, file.absolutePath)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            return Pair(temp, temp)
        }
        val isRoot = (file is za.kilowatch.ultimatefilemanager.storage.RootFile ||
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isRootPath(file.absolutePath)) &&
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.isAuthorized(this)
        if (isRoot) {
            val temp = File(cacheDir, "temp_ppt_view_${System.currentTimeMillis()}.${file.extension}")
            za.kilowatch.ultimatefilemanager.storage.RootShellWrapper.openInputStream(file.absolutePath)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            return Pair(temp, temp)
        }
        return Pair(file, null)
    }

    private fun openExternalApp() {
        val file = presentationFile ?: return
        val ext = file.extension.lowercase()
        val mimeType = MimeTypeHelper.getOrFallback(ext)
        try {
            val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(this, file)
            val uri: Uri = if (isSaf) {
                (file as? za.kilowatch.ultimatefilemanager.storage.SafFile)?.documentUri
                    ?: za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getDocumentUriForPath(this, file.absolutePath)
                    ?: Uri.parse(file.absolutePath)
            } else {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.open_in_powerpoint)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_app_to_open_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openRawText() {
        val file = presentationFile ?: return
        val textIntent = Intent(this, TextViewerActivity::class.java).apply {
            putExtra(FileViewerRouter.EXTRA_FILE_PATH, file.absolutePath)
            putExtra(FileViewerRouter.EXTRA_FILE_NAME, file.name)
        }
        startActivity(textIntent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isTv) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (currentSlideIndex > 0) {
                        currentSlideIndex--
                        displaySlide(currentSlideIndex)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (currentSlideIndex < slides.size - 1) {
                        currentSlideIndex++
                        displaySlide(currentSlideIndex)
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private val LEGACY_PPT_EXTENSIONS = setOf("ppt", "pps", "pot")
    }
}
