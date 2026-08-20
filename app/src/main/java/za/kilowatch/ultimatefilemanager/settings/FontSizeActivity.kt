package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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
 * Font Size selection screen.
 * Displays Live Preview and Font Size options in sleek grouped glass cards,
 * following the exact standard established in [LanguageActivity].
 */
class FontSizeActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SAVED_SCROLL_Y = "extra_saved_scroll_y"
    }

    private var isTv = false
    private lateinit var contentLayout: LinearLayout
    private val optionViews = mutableListOf<Pair<Int, RadioButton>>()
    private val tvCards = mutableListOf<MaterialCardView>()

    private var txtPreviewBadge: TextView? = null
    private var txtPreviewHeading: TextView? = null
    private var txtPreviewBody: TextView? = null

    private data class FontSizeItem(
        val size: Int,
        val titleRes: Int,
        val subtitleRes: Int,
        val letterSizeSp: Float,
        val badgeLabel: String
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_font_size_tv else R.layout.activity_font_size)

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

        val fontOptions = listOf(
            FontSizeItem(
                size = FontSizeHelper.FONT_SMALL,
                titleRes = R.string.font_size_small,
                subtitleRes = R.string.font_size_small_desc,
                letterSizeSp = 14f,
                badgeLabel = "85% • " + getString(R.string.font_size_small)
            ),
            FontSizeItem(
                size = FontSizeHelper.FONT_NORMAL,
                titleRes = R.string.font_size_normal,
                subtitleRes = R.string.font_size_normal_desc,
                letterSizeSp = 18f,
                badgeLabel = "100% • " + getString(R.string.font_size_normal)
            ),
            FontSizeItem(
                size = FontSizeHelper.FONT_LARGE,
                titleRes = R.string.font_size_large,
                subtitleRes = R.string.font_size_large_desc,
                letterSizeSp = 22f,
                badgeLabel = "115% • " + getString(R.string.font_size_large)
            )
        )

        val currentSize = FontSizeHelper.getSavedSize(this)

        if (isTv) {
            buildTvLayout(fontOptions, currentSize)
        } else {
            buildMobileLayout(fontOptions, currentSize)
        }

        updateSelection(currentSize)

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
        options: List<FontSizeItem>,
        currentSize: Int
    ) {
        val inflater = LayoutInflater.from(this)
        val density = resources.displayMetrics.density

        // Section 1: Live Preview Card
        contentLayout.addView(createSectionHeader(R.string.font_size_section_preview))
        val previewCard = createGlassCard()
        val previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        // Preview Header: Icon badge + title + percentage pill
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }

        val iconBadge = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams((34 * density).toInt(), (34 * density).toInt()).apply {
                marginEnd = (10 * density).toInt()
            }
            setBackgroundResource(R.drawable.bg_btn_icon_frosted)
            addView(ImageView(this@FontSizeActivity).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt(), android.view.Gravity.CENTER)
                setImageResource(R.drawable.ic_font_size)
                imageTintList = ColorStateList.valueOf(ThemeColors.primary(this@FontSizeActivity))
            })
        }
        headerRow.addView(iconBadge)

        val txtPreviewTitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(R.string.font_size_preview_title)
            setTextColor(getColor(R.color.mobile_text_primary))
            textSize = 14f
            typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
        }
        headerRow.addView(txtPreviewTitle)

        txtPreviewBadge = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTextColor(ThemeColors.primary(this@FontSizeActivity))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setBackgroundResource(R.drawable.bg_btn_icon_frosted)
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
        }
        headerRow.addView(txtPreviewBadge)
        previewContainer.addView(headerRow)

        // Preview Headline
        txtPreviewHeading = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (4 * density).toInt()
            }
            setText(R.string.font_size_preview_sample_heading)
            setTextColor(getColor(R.color.mobile_text_primary))
            textSize = 16f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        }
        previewContainer.addView(txtPreviewHeading)

        // Preview Paragraph
        txtPreviewBody = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setText(R.string.font_size_preview_sample_body)
            setTextColor(getColor(R.color.mobile_text_secondary))
            textSize = 13f
            setLineSpacing(2 * density, 1f)
        }
        previewContainer.addView(txtPreviewBody)

        previewCard.addView(previewContainer)
        contentLayout.addView(previewCard)

        // Section 2: Text Size Options Grouped Card
        contentLayout.addView(createSectionHeader(R.string.font_size_section_options))
        val optionsCard = createGlassCard()
        val optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        options.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_font_size_row, optionsContainer, false)
            bindRow(row, item, currentSize)
            optionsContainer.addView(row)

            if (index < options.size - 1) {
                optionsContainer.addView(createDivider())
            }
        }

        optionsCard.addView(optionsContainer)
        contentLayout.addView(optionsCard)
    }

    private fun bindRow(row: View, item: FontSizeItem, currentSize: Int) {
        val txtLetterBadge = row.findViewById<TextView>(R.id.txtLetterBadge)
        val txtTitle = row.findViewById<TextView>(R.id.txtTitle)
        val txtSubtitle = row.findViewById<TextView>(R.id.txtSubtitle)
        val rbSelect = row.findViewById<RadioButton>(R.id.rbSelect)

        txtLetterBadge.textSize = item.letterSizeSp
        txtTitle.setText(item.titleRes)
        txtSubtitle.setText(item.subtitleRes)
        rbSelect.isChecked = (item.size == currentSize)

        row.tag = item.size
        row.setOnClickListener { selectSize(item.size) }
        optionViews.add(Pair(item.size, rbSelect))
    }

    private fun createSectionHeader(titleRes: Int): TextView {
        return TextView(this).apply {
            setText(titleRes)
            setTextColor(ThemeColors.primary(this@FontSizeActivity))
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
        options: List<FontSizeItem>,
        currentSize: Int
    ) {
        val inflater = LayoutInflater.from(this)

        for (item in options) {
            val card = inflater.inflate(R.layout.item_font_size_card_tv, contentLayout, false) as MaterialCardView
            val txtLetterBadge = card.findViewById<TextView>(R.id.txtLetterBadge)
            val txtLabel = card.findViewById<TextView>(R.id.txtLabel)
            val txtSubtitle = card.findViewById<TextView>(R.id.txtSubtitle)
            val rbSelect = card.findViewById<RadioButton>(R.id.rbSelect)

            txtLetterBadge.textSize = item.letterSizeSp + 4f
            txtLabel.setText(item.titleRes)
            txtSubtitle.setText(item.subtitleRes)
            rbSelect.isChecked = (item.size == currentSize)

            setupTvCardFocus(card)

            card.tag = item.size
            card.setOnClickListener { selectSize(item.size) }
            tvCards.add(card)
            optionViews.add(Pair(item.size, rbSelect))
            contentLayout.addView(card)
        }
    }

    private fun selectSize(size: Int) {
        val previous = FontSizeHelper.getSavedSize(this)
        FontSizeHelper.save(this, size)
        updateSelection(size)

        if (size == previous) return

        // Mark that restart is pending across app
        FontSizeHelper.restartPending = true

        // Capture scroll position and recreate immediately
        val scrollView = findViewById<NestedScrollView?>(R.id.scrollView)
        val scrollY = scrollView?.scrollY ?: 0
        intent.putExtra(EXTRA_SAVED_SCROLL_Y, scrollY)
        recreate()
    }

    private fun updateSelection(size: Int) {
        for ((itemSize, rb) in optionViews) {
            rb.isChecked = (itemSize == size)
        }

        txtPreviewBadge?.text = when (size) {
            FontSizeHelper.FONT_SMALL -> "85% • " + getString(R.string.font_size_small)
            FontSizeHelper.FONT_LARGE -> "115% • " + getString(R.string.font_size_large)
            else                      -> "100% • " + getString(R.string.font_size_normal)
        }

        if (isTv) {
            val activeColor = getColor(R.color.tv_accent)
            val inactiveColor = getColor(R.color.tv_glass_border)

            for (card in tvCards) {
                val itemSize = card.tag as Int
                val isSelected = (itemSize == size)
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

        card.setOnFocusChangeListener { _, hasFocus ->
            val isSelected = (card.tag as Int) == FontSizeHelper.getSavedSize(this)
            val rb = card.findViewById<RadioButton>(R.id.rbSelect)
            val txtLetterBadge = card.findViewById<TextView>(R.id.txtLetterBadge)

            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setCardTextColors(card, blackText, blackText)
                rb.buttonTintList = ColorStateList.valueOf(blackText)
                txtLetterBadge.setTextColor(blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setCardTextColors(card, primaryText, secondaryText)
                rb.buttonTintList = ColorStateList.valueOf(if (isSelected) getColor(R.color.tv_accent) else secondaryText)
                txtLetterBadge.setTextColor(primaryText)
            }
        }
    }

    private fun setCardTextColors(view: View, primaryColor: Int, secondaryColor: Int) {
        if (view is TextView) {
            if (view.id == R.id.txtSubtitle) {
                view.setTextColor(secondaryColor)
            } else {
                view.setTextColor(primaryColor)
            }
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setCardTextColors(view.getChildAt(i), primaryColor, secondaryColor)
            }
        }
    }
}
