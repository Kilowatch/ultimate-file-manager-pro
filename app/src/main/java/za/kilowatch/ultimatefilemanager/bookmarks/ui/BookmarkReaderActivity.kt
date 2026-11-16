package za.kilowatch.ultimatefilemanager.bookmarks.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkEntity
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkRepository
import za.kilowatch.ultimatefilemanager.bookmarks.util.BookmarkMetadataScraper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarkReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var btnBack: ImageView
    private lateinit var txtToolbarTitle: TextView
    private lateinit var txtToolbarDomain: TextView
    private lateinit var btnOpenInBrowser: ImageView
    private lateinit var btnStar: ImageView

    private lateinit var btnFontSmaller: TextView
    private lateinit var btnFontBigger: TextView
    private lateinit var btnThemeLight: View
    private lateinit var btnThemeSepia: View
    private lateinit var btnThemeDark: View

    private lateinit var layoutReadingContainer: LinearLayout
    private lateinit var txtArticleTitle: TextView
    private lateinit var txtArticleByline: TextView
    private lateinit var txtArticleBody: TextView

    private lateinit var btnTtsPlayPause: ImageView
    private lateinit var btnTtsStop: ImageView
    private lateinit var txtTtsStatus: TextView
    private lateinit var btnTtsSpeed: MaterialButton

    private var currentBookmark: BookmarkEntity? = null
    private var bookmarkId: String? = null
    private var articleUrl: String? = null
    private var articleTitle: String? = null
    private var articleText: String? = null

    private var currentFontSizeSp: Float = 16f
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isTtsPlaying = false
    private var currentTtsSpeed = 1.0f

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_bookmark_reader)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupViews()
        initTts()
        loadData()
    }

    private fun setupViews() {
        btnBack = findViewById(R.id.btnBack)
        txtToolbarTitle = findViewById(R.id.txtToolbarTitle)
        txtToolbarDomain = findViewById(R.id.txtToolbarDomain)
        btnOpenInBrowser = findViewById(R.id.btnOpenInBrowser)
        btnStar = findViewById(R.id.btnStar)

        btnFontSmaller = findViewById(R.id.btnFontSmaller)
        btnFontBigger = findViewById(R.id.btnFontBigger)
        btnThemeLight = findViewById(R.id.btnThemeLight)
        btnThemeSepia = findViewById(R.id.btnThemeSepia)
        btnThemeDark = findViewById(R.id.btnThemeDark)

        layoutReadingContainer = findViewById(R.id.layoutReadingContainer)
        txtArticleTitle = findViewById(R.id.txtArticleTitle)
        txtArticleByline = findViewById(R.id.txtArticleByline)
        txtArticleBody = findViewById(R.id.txtArticleBody)

        btnTtsPlayPause = findViewById(R.id.btnTtsPlayPause)
        btnTtsStop = findViewById(R.id.btnTtsStop)
        txtTtsStatus = findViewById(R.id.txtTtsStatus)
        btnTtsSpeed = findViewById(R.id.btnTtsSpeed)

        btnBack.setOnClickListener { finish() }

        btnOpenInBrowser.setOnClickListener {
            articleUrl?.let { url ->
                runCatching {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(Intent.createChooser(browserIntent, getString(R.string.bookmark_action_open_browser)))
                }
            }
        }

        btnFontSmaller.setOnClickListener {
            if (currentFontSizeSp > 12f) {
                currentFontSizeSp -= 1.5f
                txtArticleBody.textSize = currentFontSizeSp
            }
        }

        btnFontBigger.setOnClickListener {
            if (currentFontSizeSp < 28f) {
                currentFontSizeSp += 1.5f
                txtArticleBody.textSize = currentFontSizeSp
            }
        }

        btnThemeLight.setOnClickListener { applyThemePalette("#F8F9FA", "#111827", "#4B5563") }
        btnThemeSepia.setOnClickListener { applyThemePalette("#F4ECD8", "#3B2E1E", "#6D5943") }
        btnThemeDark.setOnClickListener { applyThemePalette("#121212", "#E5E7EB", "#9CA3AF") }

        btnTtsPlayPause.setOnClickListener { toggleTtsPlayback() }
        btnTtsStop.setOnClickListener { stopTts() }
        btnTtsSpeed.setOnClickListener { cycleTtsSpeed() }
    }

    private fun applyThemePalette(bgHex: String, textPrimaryHex: String, textSecondaryHex: String) {
        val bgColor = Color.parseColor(bgHex)
        val textPrimaryColor = Color.parseColor(textPrimaryHex)
        val textSecondaryColor = Color.parseColor(textSecondaryHex)

        layoutReadingContainer.setBackgroundColor(bgColor)
        txtArticleTitle.setTextColor(textPrimaryColor)
        txtArticleBody.setTextColor(textPrimaryColor)
        txtArticleByline.setTextColor(textSecondaryColor)
    }

    private fun loadData() {
        bookmarkId = intent.getStringExtra(EXTRA_BOOKMARK_ID)
        articleUrl = intent.getStringExtra(EXTRA_URL)
        articleTitle = intent.getStringExtra(EXTRA_TITLE)
        articleText = intent.getStringExtra(EXTRA_ARTICLE_TEXT)

        if (bookmarkId != null) {
            val repo = BookmarkRepository.getInstance(this)
            lifecycleScope.launch {
                val db = za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkDatabase.getInstance(this@BookmarkReaderActivity)
                val item = db.bookmarkDao().getBookmarkById(bookmarkId!!)
                if (item != null) {
                    currentBookmark = item
                    articleUrl = item.url
                    articleTitle = item.title
                    articleText = item.cachedArticleText ?: item.notes ?: item.title
                    bindBookmark(item)

                    // If article text is missing or just the title, fetch it live
                    val url = item.url
                    if ((item.cachedArticleText.isNullOrBlank() || item.cachedArticleText == item.title) && !url.isNullOrBlank()) {
                        txtArticleBody.text = "Loading article content…"
                        launch(Dispatchers.IO) {
                            val meta = BookmarkMetadataScraper.scrape(this@BookmarkReaderActivity, url)
                            val text = meta.articleText ?: meta.description ?: item.notes ?: item.title
                            withContext(Dispatchers.Main) {
                                if (!meta.title.isNullOrBlank() && (item.title == url || item.title.startsWith("http"))) {
                                    txtArticleTitle.text = meta.title
                                    articleTitle = meta.title
                                }
                                txtArticleBody.text = text
                                articleText = text
                            }
                            if (!meta.articleText.isNullOrBlank() || !meta.title.isNullOrBlank()) {
                                val updated = item.copy(
                                    title = if (!meta.title.isNullOrBlank() && (item.title == url || item.title.startsWith("http"))) meta.title else item.title,
                                    cachedArticleText = meta.articleText ?: item.cachedArticleText
                                )
                                currentBookmark = updated
                                BookmarkRepository.getInstance(this@BookmarkReaderActivity).updateBookmark(updated)
                            }
                        }
                    }
                } else {
                    bindDirectExtras()
                }
            }
        } else {
            bindDirectExtras()
        }
    }

    private fun bindBookmark(item: BookmarkEntity) {
        val domain = extractDomain(item.url)
        txtToolbarDomain.text = domain
        txtArticleTitle.text = item.title
        val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(item.createdAt))
        txtArticleByline.text = if (domain.isNotEmpty()) "$domain • $dateStr" else dateStr
        txtArticleBody.text = item.cachedArticleText ?: item.notes ?: item.title

        updateStarUi(item.isStarred)
        btnStar.setOnClickListener {
            val current = currentBookmark ?: item
            val updated = current.copy(isStarred = !current.isStarred)
            currentBookmark = updated
            updateStarUi(updated.isStarred)
            lifecycleScope.launch(Dispatchers.IO) {
                BookmarkRepository.getInstance(this@BookmarkReaderActivity).updateBookmark(updated)
            }
        }
    }

    private fun bindDirectExtras() {
        val domain = extractDomain(articleUrl)
        txtToolbarDomain.text = domain
        txtArticleTitle.text = articleTitle ?: "Article"
        txtArticleByline.text = domain
        txtArticleBody.text = articleText ?: ""
    }

    private fun updateStarUi(isStarred: Boolean) {
        if (isStarred) {
            btnStar.setImageResource(R.drawable.ic_star)
            btnStar.setColorFilter(Color.parseColor("#FFD700"))
        } else {
            btnStar.setImageResource(R.drawable.ic_star)
            btnStar.setColorFilter(getColor(R.color.mobile_icon_tint))
        }
    }

    private fun extractDomain(urlStr: String?): String {
        if (urlStr.isNullOrBlank()) return ""
        return try {
            URI(urlStr).host?.removePrefix("www.") ?: urlStr
        } catch (_: Exception) {
            urlStr
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread {
                        isTtsPlaying = true
                        btnTtsPlayPause.setImageResource(R.drawable.ic_pause)
                        txtTtsStatus.text = getString(R.string.bookmark_tts_pause)
                    }
                }

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        isTtsPlaying = false
                        btnTtsPlayPause.setImageResource(R.drawable.ic_play)
                        txtTtsStatus.text = getString(R.string.bookmark_tts_play)
                    }
                }

                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        isTtsPlaying = false
                        btnTtsPlayPause.setImageResource(R.drawable.ic_play)
                        txtTtsStatus.text = getString(R.string.bookmark_tts_play)
                    }
                }
            })
        } else {
            isTtsReady = false
        }
    }

    private fun toggleTtsPlayback() {
        if (!isTtsReady) {
            Toast.makeText(this, R.string.bookmark_tts_unsupported, Toast.LENGTH_SHORT).show()
            return
        }

        if (isTtsPlaying) {
            tts?.stop()
            isTtsPlaying = false
            btnTtsPlayPause.setImageResource(R.drawable.ic_play)
            txtTtsStatus.text = getString(R.string.bookmark_tts_play)
        } else {
            val textToRead = txtArticleBody.text.toString()
            if (textToRead.isBlank()) return

            tts?.setSpeechRate(currentTtsSpeed)
            tts?.speak(textToRead, TextToSpeech.QUEUE_FLUSH, null, "reader_article")
            isTtsPlaying = true
            btnTtsPlayPause.setImageResource(R.drawable.ic_pause)
            txtTtsStatus.text = getString(R.string.bookmark_tts_pause)
        }
    }

    private fun stopTts() {
        if (isTtsPlaying) {
            tts?.stop()
            isTtsPlaying = false
            btnTtsPlayPause.setImageResource(R.drawable.ic_play)
            txtTtsStatus.text = getString(R.string.bookmark_tts_play)
        }
    }

    private fun cycleTtsSpeed() {
        currentTtsSpeed = when (currentTtsSpeed) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            2.0f -> 0.75f
            else -> 1.0f
        }
        btnTtsSpeed.text = "${currentTtsSpeed}x"
        if (isTtsPlaying) {
            tts?.setSpeechRate(currentTtsSpeed)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BOOKMARK_ID = "extra_bookmark_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTICLE_TEXT = "extra_article_text"
    }
}
