package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import coil3.asImage
import coil3.load
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Language selection screen.
 * Displays System Default and supported languages in sleek grouped glass cards.
 */
class LanguageActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SAVED_SCROLL_Y = "extra_saved_scroll_y"
    }

    private var isTv = false
    private lateinit var contentLayout: LinearLayout
    private val optionViews = mutableListOf<Pair<String, View>>()
    private val tvCards = mutableListOf<MaterialCardView>()

    private data class LanguageItem(
        val code: String,
        val titleRes: Int,
        val nativeSubtitle: String,
        val flagAsset: String? = null,
        val iconRes: Int = R.drawable.ic_language
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
            setContentView(R.layout.activity_language_tv)
        } else {
            setContentView(R.layout.activity_language)
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

        val systemDefaultOption = LanguageItem(
            code = LocaleHelper.LOCALE_DEFAULT,
            titleRes = R.string.language_system_default,
            nativeSubtitle = getString(R.string.language_system_default_desc),
            flagAsset = null,
            iconRes = R.drawable.ic_language
        )

        val supportedLanguages = listOf(
            LanguageItem(LocaleHelper.LOCALE_EN, R.string.language_english, "English", "gb.svg"),
            LanguageItem(LocaleHelper.LOCALE_DE, R.string.language_german, "Deutsch", "de.svg"),
            LanguageItem(LocaleHelper.LOCALE_JA, R.string.language_japanese, "日本語", "jp.svg"),
            LanguageItem(LocaleHelper.LOCALE_AR, R.string.language_arabic, "العربية", "sa.svg"),
            LanguageItem(LocaleHelper.LOCALE_ES, R.string.language_spanish, "Español", "es.svg"),
            LanguageItem(LocaleHelper.LOCALE_FR, R.string.language_french, "Français", "fr.svg"),
            LanguageItem(LocaleHelper.LOCALE_IT, R.string.language_italian, "Italiano", "it.svg"),
            LanguageItem(LocaleHelper.LOCALE_NL, R.string.language_dutch, "Nederlands", "nl.svg"),
            LanguageItem(LocaleHelper.LOCALE_HI, R.string.language_hindi, "हिन्दी", "in.svg"),
            LanguageItem(LocaleHelper.LOCALE_ID, R.string.language_indonesian, "Bahasa Indonesia", "id.svg"),
            LanguageItem(LocaleHelper.LOCALE_KO, R.string.language_korean, "한국어", "kr.svg"),
            LanguageItem(LocaleHelper.LOCALE_ZH, R.string.language_chinese, "中文", "cn.svg"),
            LanguageItem(LocaleHelper.LOCALE_SV, R.string.language_swedish, "Svenska", "se.svg"),
            LanguageItem(LocaleHelper.LOCALE_PT, R.string.language_portuguese, "Português", "br.svg"),
            LanguageItem(LocaleHelper.LOCALE_RU, R.string.language_russian, "Русский", "ru.svg"),
            LanguageItem(LocaleHelper.LOCALE_TR, R.string.language_turkish, "Türkçe", "tr.svg"),
            LanguageItem(LocaleHelper.LOCALE_UK, R.string.language_ukrainian, "Українська", "ua.svg")
        )

        val currentLocale = LocaleHelper.getSavedLocale(this)

        optionViews.clear()
        tvCards.clear()

        if (isTv) {
            buildTvLayout(systemDefaultOption, supportedLanguages, currentLocale)
        } else {
            buildMobileLayout(systemDefaultOption, supportedLanguages, currentLocale)
        }

        updateSelection(currentLocale)

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
        systemDefault: LanguageItem,
        languages: List<LanguageItem>,
        currentLocale: String
    ) {
        val inflater = LayoutInflater.from(this)

        // Section 1: System Preference
        contentLayout.addView(createSectionHeader(R.string.language_section_system))
        val systemCard = createGlassCard()
        val systemContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val systemRow = inflater.inflate(R.layout.item_language_row, systemContainer, false)
        bindRow(systemRow, systemDefault, currentLocale)
        systemContainer.addView(systemRow)
        systemCard.addView(systemContainer)
        contentLayout.addView(systemCard)

        // Section 2: Available Languages
        contentLayout.addView(createSectionHeader(R.string.language_section_available))
        val languagesCard = createGlassCard()
        val languagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        languages.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_language_row, languagesContainer, false)
            bindRow(row, item, currentLocale)
            languagesContainer.addView(row)

            if (index < languages.size - 1) {
                languagesContainer.addView(createDivider())
            }
        }
        languagesCard.addView(languagesContainer)
        contentLayout.addView(languagesCard)
    }

    private fun bindRow(row: View, item: LanguageItem, currentLocale: String) {
        val imgFlag = row.findViewById<ImageView>(R.id.imgFlag)
        val imgIcon = row.findViewById<ImageView>(R.id.imgIcon)
        val txtTitle = row.findViewById<TextView>(R.id.txtTitle)
        val txtSubtitle = row.findViewById<TextView>(R.id.txtSubtitle)
        val checkContainer = row.findViewById<View>(R.id.checkContainer)

        txtTitle.setText(item.titleRes)
        txtSubtitle.text = item.nativeSubtitle
        checkContainer.visibility = if (item.code == currentLocale) View.VISIBLE else View.GONE

        if (item.flagAsset != null) {
            imgFlag.visibility = View.VISIBLE
            imgIcon.visibility = View.GONE
            val placeholder = ContextCompat.getDrawable(this, R.drawable.ic_photo_video)?.asImage()
            imgFlag.load("file:///android_asset/remote/flags/${item.flagAsset}") {
                placeholder(placeholder)
                error(placeholder)
            }
        } else {
            imgFlag.visibility = View.GONE
            imgIcon.visibility = View.VISIBLE
            imgIcon.setImageResource(item.iconRes)
        }

        row.tag = item.code
        row.setOnClickListener { selectLanguage(item.code) }
        optionViews.add(Pair(item.code, checkContainer))
    }

    private fun createSectionHeader(titleRes: Int): TextView {
        return TextView(this).apply {
            setText(titleRes)
            setTextColor(ThemeColors.primary(this@LanguageActivity))
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
        systemDefault: LanguageItem,
        languages: List<LanguageItem>,
        currentLocale: String
    ) {
        val inflater = LayoutInflater.from(this)
        val allOptions = listOf(systemDefault) + languages

        for (item in allOptions) {
            val card = inflater.inflate(R.layout.item_language_card_tv, contentLayout, false) as MaterialCardView
            val imgFlag = card.findViewById<ImageView>(R.id.imgFlag)
            val imgIcon = card.findViewById<ImageView>(R.id.imgIcon)
            val txtLabel = card.findViewById<TextView>(R.id.txtLabel)
            val txtSubtitle = card.findViewById<TextView>(R.id.txtSubtitle)
            val checkContainer = card.findViewById<View>(R.id.checkContainer)

            txtLabel.setText(item.titleRes)
            txtSubtitle.text = item.nativeSubtitle
            checkContainer.visibility = if (item.code == currentLocale) View.VISIBLE else View.GONE

            if (item.flagAsset != null) {
                imgFlag.visibility = View.VISIBLE
                imgIcon.visibility = View.GONE
                val placeholder = ContextCompat.getDrawable(this, R.drawable.ic_photo_video)?.asImage()
                imgFlag.load("file:///android_asset/remote/flags/${item.flagAsset}") {
                    placeholder(placeholder)
                    error(placeholder)
                }
            } else {
                imgFlag.visibility = View.GONE
                imgIcon.visibility = View.VISIBLE
                imgIcon.setImageResource(item.iconRes)
            }

            setupTvCardFocus(card)

            card.tag = item.code
            card.setOnClickListener { selectLanguage(item.code) }
            tvCards.add(card)
            optionViews.add(Pair(item.code, checkContainer))
            contentLayout.addView(card)
        }
    }

    private fun selectLanguage(locale: String) {
        val previous = LocaleHelper.getSavedLocale(this)
        LocaleHelper.save(this, locale)
        updateSelection(locale)

        if (locale == previous) return

        // Mark that we need a restart for the new language to apply globally
        LocaleHelper.restartPending = true

        // Capture scroll position and recreate immediately
        val scrollView = findViewById<NestedScrollView?>(R.id.scrollView)
        val scrollY = scrollView?.scrollY ?: 0
        intent.putExtra(EXTRA_SAVED_SCROLL_Y, scrollY)
        recreate()
    }

    private fun updateSelection(locale: String) {
        for ((code, check) in optionViews) {
            check.visibility = if (code == locale) View.VISIBLE else View.GONE
        }

        if (isTv) {
            val activeColor = getColor(R.color.tv_accent)
            val inactiveColor = getColor(R.color.tv_glass_border)

            for (card in tvCards) {
                val code = card.tag as String
                val isSelected = (code == locale)
                card.strokeColor = if (isSelected) activeColor else inactiveColor
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
            val imgCheck = card.findViewById<ImageView>(R.id.imgCheck)
            val imgIcon = card.findViewById<ImageView>(R.id.imgIcon)
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setCardTextColors(card, blackText, blackText)
                imgCheck?.imageTintList = ColorStateList.valueOf(blackText)
                imgIcon?.imageTintList = ColorStateList.valueOf(blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setCardTextColors(card, primaryText, secondaryText)
                imgCheck?.imageTintList = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                imgIcon?.imageTintList = ColorStateList.valueOf(primaryText)
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
