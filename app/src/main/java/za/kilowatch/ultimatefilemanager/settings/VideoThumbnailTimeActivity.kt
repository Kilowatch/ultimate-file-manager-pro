package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Video Thumbnail Time settings screen.
 * Configures the timestamp frame used to generate video thumbnails throughout UFM.
 * Follows the Language & Grouped Glass Card design standard.
 */
class VideoThumbnailTimeActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SAVED_SCROLL_Y = "extra_saved_scroll_y"
    }

    private var isTv = false
    private lateinit var contentLayout: LinearLayout
    private val optionViews = mutableListOf<Pair<Int, RadioButton>>()
    private val tvCards = mutableListOf<MaterialCardView>()

    private data class VideoThumbnailOption(
        val percent: Int,
        val titleRes: Int,
        val subtitleRes: Int,
        val badgeText: String
    )

    private val thumbnailOptions = listOf(
        VideoThumbnailOption(0, R.string.vtt_percent_0, R.string.vtt_percent_0_subtitle, "0%"),
        VideoThumbnailOption(10, R.string.vtt_percent_10, R.string.vtt_percent_10_subtitle, "10%"),
        VideoThumbnailOption(25, R.string.vtt_percent_25, R.string.vtt_percent_25_subtitle, "25%"),
        VideoThumbnailOption(50, R.string.vtt_percent_50, R.string.vtt_percent_50_subtitle, "50%"),
        VideoThumbnailOption(75, R.string.vtt_percent_75, R.string.vtt_percent_75_subtitle, "75%")
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_video_thumbnail_time_tv)
        } else {
            setContentView(R.layout.activity_video_thumbnail_time)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        contentLayout = findViewById(R.id.contentLayout)

        val currentPercent = VideoThumbnailTimePreferenceManager.getPercent(this)

        optionViews.clear()
        tvCards.clear()

        if (isTv) {
            buildTvLayout(thumbnailOptions, currentPercent)
        } else {
            buildMobileLayout(thumbnailOptions, currentPercent)
        }

        updateSelection(currentPercent)

        // Restore scroll position after recreation
        val savedScrollY = intent.getIntExtra(EXTRA_SAVED_SCROLL_Y, -1).takeIf { it >= 0 }
            ?: savedInstanceState?.getInt(EXTRA_SAVED_SCROLL_Y, 0) ?: 0

        if (savedScrollY > 0) {
            val scrollView = findViewById<NestedScrollView?>(R.id.scrollView)
            scrollView?.post {
                scrollView.scrollTo(0, savedScrollY)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val scrollView = findViewById<NestedScrollView?>(R.id.scrollView)
        scrollView?.let {
            outState.putInt(EXTRA_SAVED_SCROLL_Y, it.scrollY)
        }
    }

    private fun buildMobileLayout(
        options: List<VideoThumbnailOption>,
        currentPercent: Int
    ) {
        val inflater = LayoutInflater.from(this)

        // Section: Options
        contentLayout.addView(createSectionHeader(R.string.vtt_section_options))

        val glassCard = createGlassCard()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        options.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_video_thumbnail_time_row, container, false)
            bindMobileRow(row, item, currentPercent)
            container.addView(row)

            if (index < options.size - 1) {
                container.addView(createDivider())
            }
        }

        glassCard.addView(container)
        contentLayout.addView(glassCard)
    }

    private fun bindMobileRow(row: View, item: VideoThumbnailOption, currentPercent: Int) {
        val txtBadge = row.findViewById<TextView>(R.id.txtBadge)
        val txtTitle = row.findViewById<TextView>(R.id.txtTitle)
        val txtSubtitle = row.findViewById<TextView>(R.id.txtSubtitle)
        val rbSelect = row.findViewById<RadioButton>(R.id.rbSelect)

        txtBadge.text = item.badgeText
        txtTitle.setText(item.titleRes)
        txtSubtitle.setText(item.subtitleRes)
        rbSelect.isChecked = (item.percent == currentPercent)

        row.setOnClickListener { selectPercent(item.percent) }
        optionViews.add(Pair(item.percent, rbSelect))
    }

    private fun createSectionHeader(titleRes: Int): TextView {
        return TextView(this).apply {
            setText(titleRes)
            setTextColor(ThemeColors.primary(this@VideoThumbnailTimeActivity))
            textSize = 13f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            isAllCaps = true
            letterSpacing = 0.05f
            val density = resources.displayMetrics.density
            setPadding(
                (4 * density).toInt(),
                (14 * density).toInt(),
                (4 * density).toInt(),
                (8 * density).toInt()
            )
        }
    }

    private fun createGlassCard(): MaterialCardView {
        val density = resources.displayMetrics.density
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            radius = 16 * density
            strokeWidth = (1 * density).toInt()
            strokeColor = getColor(R.color.mobile_glass_stroke)
            setCardBackgroundColor(getColor(R.color.mobile_glass_card))
            cardElevation = 0f
        }
    }

    private fun createDivider(): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                marginStart = (14 * density).toInt()
                marginEnd = (14 * density).toInt()
            }
            setBackgroundColor(getColor(R.color.mobile_glass_stroke))
        }
    }

    private fun buildTvLayout(
        options: List<VideoThumbnailOption>,
        currentPercent: Int
    ) {
        val inflater = LayoutInflater.from(this)

        for (item in options) {
            val card = inflater.inflate(R.layout.item_video_thumbnail_time_card_tv, contentLayout, false) as MaterialCardView
            val txtBadge = card.findViewById<TextView>(R.id.txtBadge)
            val txtTitle = card.findViewById<TextView>(R.id.txtTitle)
            val txtSubtitle = card.findViewById<TextView>(R.id.txtSubtitle)
            val rbSelect = card.findViewById<RadioButton>(R.id.rbSelect)

            txtBadge.text = item.badgeText
            txtTitle.setText(item.titleRes)
            txtSubtitle.setText(item.subtitleRes)
            rbSelect.isChecked = (item.percent == currentPercent)

            setupTvCardFocus(card)

            card.tag = item.percent
            card.setOnClickListener { selectPercent(item.percent) }
            tvCards.add(card)
            optionViews.add(Pair(item.percent, rbSelect))
            contentLayout.addView(card)
        }
    }

    private fun selectPercent(percent: Int) {
        VideoThumbnailTimePreferenceManager.setPercent(this, percent)
        updateSelection(percent)
    }

    private fun updateSelection(selectedPercent: Int) {
        for ((pct, rb) in optionViews) {
            rb.isChecked = (pct == selectedPercent)
        }

        if (isTv) {
            val activeColor = getColor(R.color.tv_accent)
            val inactiveColor = getColor(R.color.tv_glass_border)

            for (card in tvCards) {
                val pct = card.tag as? Int ?: continue
                val isSelected = (pct == selectedPercent)
                val rb = card.findViewById<RadioButton>(R.id.rbSelect)
                rb.isChecked = isSelected

                card.strokeColor = if (isSelected) activeColor else inactiveColor
                if (!card.hasFocus()) {
                    rb.buttonTintList = ColorStateList.valueOf(if (isSelected) activeColor else getColor(R.color.tv_text_secondary))
                }
            }
        }
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondaryText = getColor(R.color.tv_text_secondary)
        val white = getColor(R.color.white)

        card.setOnFocusChangeListener { _, hasFocus ->
            val cardPercent = card.tag as? Int ?: -1
            val isSelected = (cardPercent == VideoThumbnailTimePreferenceManager.getPercent(this))
            val rb = card.findViewById<RadioButton>(R.id.rbSelect)
            val txtBadge = card.findViewById<TextView>(R.id.txtBadge)

            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setCardTextColors(card, blackText, blackText)
                txtBadge?.setTextColor(blackText)
                rb.buttonTintList = ColorStateList.valueOf(blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setCardTextColors(card, primaryText, secondaryText)
                txtBadge?.setTextColor(white)
                rb.buttonTintList = ColorStateList.valueOf(if (isSelected) getColor(R.color.tv_accent) else secondaryText)
            }
        }
    }

    private fun setCardTextColors(view: View, primaryColor: Int, secondaryColor: Int) {
        if (view is TextView) {
            if (view.id == R.id.txtSubtitle) {
                view.setTextColor(secondaryColor)
            } else if (view.id == R.id.txtTitle) {
                view.setTextColor(primaryColor)
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setCardTextColors(view.getChildAt(i), primaryColor, secondaryColor)
            }
        }
    }
}
