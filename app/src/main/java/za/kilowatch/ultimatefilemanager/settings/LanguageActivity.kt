package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import coil3.load
import coil3.asImage
import androidx.core.content.ContextCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper

/**
 * Language selection screen.
 * Offers System Default, English, and German options.
 */
class LanguageActivity : AppCompatActivity() {

    private lateinit var cardSystemDefault: MaterialCardView
    private lateinit var cardEnglish:       MaterialCardView
    private lateinit var cardGerman:        MaterialCardView
    private lateinit var cardJapanese:      MaterialCardView
    private lateinit var cardArabic:        MaterialCardView
    private lateinit var cardSpanish:       MaterialCardView
    private lateinit var cardFrench:        MaterialCardView
    private lateinit var cardHindi:         MaterialCardView
    private lateinit var cardIndonesian:    MaterialCardView
    private lateinit var cardKorean:        MaterialCardView
    private lateinit var cardPortuguese:    MaterialCardView
    private lateinit var cardRussian:       MaterialCardView
    private lateinit var cardTurkish:       MaterialCardView
    private lateinit var cardUkrainian:     MaterialCardView

    private lateinit var rbSystemDefault:   RadioButton
    private lateinit var rbEnglish:         RadioButton
    private lateinit var rbGerman:          RadioButton
    private lateinit var rbJapanese:        RadioButton
    private lateinit var rbArabic:          RadioButton
    private lateinit var rbSpanish:         RadioButton
    private lateinit var rbFrench:          RadioButton
    private lateinit var rbHindi:           RadioButton
    private lateinit var rbIndonesian:      RadioButton
    private lateinit var rbKorean:          RadioButton
    private lateinit var rbPortuguese:      RadioButton
    private lateinit var rbRussian:         RadioButton
    private lateinit var rbTurkish:         RadioButton
    private lateinit var rbUkrainian:       RadioButton

    private var isTv = false

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
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        cardSystemDefault = findViewById(R.id.cardSystemDefault)
        cardEnglish       = findViewById(R.id.cardEnglish)
        cardGerman        = findViewById(R.id.cardGerman)
        cardJapanese      = findViewById(R.id.cardJapanese)
        cardArabic        = findViewById(R.id.cardArabic)
        cardSpanish       = findViewById(R.id.cardSpanish)
        cardFrench        = findViewById(R.id.cardFrench)
        cardHindi         = findViewById(R.id.cardHindi)
        cardIndonesian    = findViewById(R.id.cardIndonesian)
        cardKorean        = findViewById(R.id.cardKorean)
        cardPortuguese    = findViewById(R.id.cardPortuguese)
        cardRussian       = findViewById(R.id.cardRussian)
        cardTurkish       = findViewById(R.id.cardTurkish)
        cardUkrainian     = findViewById(R.id.cardUkrainian)

        rbSystemDefault   = findViewById(R.id.rbSystemDefault)
        rbEnglish         = findViewById(R.id.rbEnglish)
        rbGerman          = findViewById(R.id.rbGerman)
        rbJapanese        = findViewById(R.id.rbJapanese)
        rbArabic          = findViewById(R.id.rbArabic)
        rbSpanish         = findViewById(R.id.rbSpanish)
        rbFrench          = findViewById(R.id.rbFrench)
        rbHindi           = findViewById(R.id.rbHindi)
        rbIndonesian      = findViewById(R.id.rbIndonesian)
        rbKorean          = findViewById(R.id.rbKorean)
        rbPortuguese      = findViewById(R.id.rbPortuguese)
        rbRussian         = findViewById(R.id.rbRussian)
        rbTurkish         = findViewById(R.id.rbTurkish)
        rbUkrainian       = findViewById(R.id.rbUkrainian)

        loadFlag(R.id.imgEnglish, "gb.svg")
        loadFlag(R.id.imgGerman, "de.svg")
        loadFlag(R.id.imgJapanese, "jp.svg")
        loadFlag(R.id.imgArabic, "sa.svg")
        loadFlag(R.id.imgSpanish, "es.svg")
        loadFlag(R.id.imgFrench, "fr.svg")
        loadFlag(R.id.imgHindi, "in.svg")
        loadFlag(R.id.imgIndonesian, "id.svg")
        loadFlag(R.id.imgKorean, "kr.svg")
        loadFlag(R.id.imgPortuguese, "br.svg")
        loadFlag(R.id.imgRussian, "ru.svg")
        loadFlag(R.id.imgTurkish, "tr.svg")
        loadFlag(R.id.imgUkrainian, "ua.svg")

        updateSelection(LocaleHelper.getSavedLocale(this))

        cardSystemDefault.setOnClickListener { selectLanguage(LocaleHelper.LOCALE_DEFAULT) }
        cardEnglish.setOnClickListener       { selectLanguage(LocaleHelper.LOCALE_EN) }
        cardGerman.setOnClickListener        { selectLanguage(LocaleHelper.LOCALE_DE) }
        cardJapanese.setOnClickListener      { selectLanguage(LocaleHelper.LOCALE_JA) }
        cardArabic.setOnClickListener        { selectLanguage(LocaleHelper.LOCALE_AR) }
        cardSpanish.setOnClickListener       { selectLanguage(LocaleHelper.LOCALE_ES) }
        cardFrench.setOnClickListener        { selectLanguage(LocaleHelper.LOCALE_FR) }
        cardHindi.setOnClickListener         { selectLanguage(LocaleHelper.LOCALE_HI) }
        cardIndonesian.setOnClickListener    { selectLanguage(LocaleHelper.LOCALE_ID) }
        cardKorean.setOnClickListener        { selectLanguage(LocaleHelper.LOCALE_KO) }
        cardPortuguese.setOnClickListener    { selectLanguage(LocaleHelper.LOCALE_PT) }
        cardRussian.setOnClickListener       { selectLanguage(LocaleHelper.LOCALE_RU) }
        cardTurkish.setOnClickListener       { selectLanguage(LocaleHelper.LOCALE_TR) }

        cardUkrainian.setOnClickListener     { selectLanguage(LocaleHelper.LOCALE_UK) }

        if (isTv) {
            setupTvCardFocus(cardSystemDefault)
            setupTvCardFocus(cardEnglish)
            setupTvCardFocus(cardGerman)
            setupTvCardFocus(cardJapanese)
            setupTvCardFocus(cardArabic)
            setupTvCardFocus(cardSpanish)
            setupTvCardFocus(cardFrench)
            setupTvCardFocus(cardHindi)
            setupTvCardFocus(cardIndonesian)
            setupTvCardFocus(cardKorean)
            setupTvCardFocus(cardPortuguese)
            setupTvCardFocus(cardRussian)
            setupTvCardFocus(cardTurkish)
            setupTvCardFocus(cardUkrainian)
        }
    }

    private fun selectLanguage(locale: String) {
        val previous = LocaleHelper.getSavedLocale(this)
        LocaleHelper.save(this, locale)
        updateSelection(locale)

        if (locale == previous) return

        // Mark that we need a restart for the new language to apply globally
        LocaleHelper.restartPending = true
        recreate() // Update this screen immediately
    }

    private fun updateSelection(locale: String) {
        val activeColor   = if (isTv) getColor(R.color.tv_accent)        else getColor(R.color.ufm_primary)
        val inactiveColor = if (isTv) getColor(R.color.tv_glass_border)  else getColor(R.color.ufm_surface_variant)

        rbSystemDefault.isChecked = locale == LocaleHelper.LOCALE_DEFAULT
        rbEnglish.isChecked       = locale == LocaleHelper.LOCALE_EN
        rbGerman.isChecked        = locale == LocaleHelper.LOCALE_DE
        rbJapanese.isChecked      = locale == LocaleHelper.LOCALE_JA
        rbArabic.isChecked        = locale == LocaleHelper.LOCALE_AR
        rbSpanish.isChecked       = locale == LocaleHelper.LOCALE_ES
        rbFrench.isChecked        = locale == LocaleHelper.LOCALE_FR
        rbHindi.isChecked         = locale == LocaleHelper.LOCALE_HI
        rbIndonesian.isChecked    = locale == LocaleHelper.LOCALE_ID
        rbKorean.isChecked        = locale == LocaleHelper.LOCALE_KO
        rbPortuguese.isChecked    = locale == LocaleHelper.LOCALE_PT
        rbRussian.isChecked       = locale == LocaleHelper.LOCALE_RU
        rbTurkish.isChecked       = locale == LocaleHelper.LOCALE_TR
        rbUkrainian.isChecked     = locale == LocaleHelper.LOCALE_UK

        cardSystemDefault.strokeColor = if (locale == LocaleHelper.LOCALE_DEFAULT) activeColor else inactiveColor
        cardEnglish.strokeColor       = if (locale == LocaleHelper.LOCALE_EN)       activeColor else inactiveColor
        cardGerman.strokeColor        = if (locale == LocaleHelper.LOCALE_DE)       activeColor else inactiveColor
        cardJapanese.strokeColor      = if (locale == LocaleHelper.LOCALE_JA)       activeColor else inactiveColor
        cardArabic.strokeColor        = if (locale == LocaleHelper.LOCALE_AR)       activeColor else inactiveColor
        cardSpanish.strokeColor       = if (locale == LocaleHelper.LOCALE_ES)       activeColor else inactiveColor
        cardFrench.strokeColor        = if (locale == LocaleHelper.LOCALE_FR)       activeColor else inactiveColor
        cardHindi.strokeColor         = if (locale == LocaleHelper.LOCALE_HI)       activeColor else inactiveColor
        cardIndonesian.strokeColor    = if (locale == LocaleHelper.LOCALE_ID)       activeColor else inactiveColor
        cardKorean.strokeColor        = if (locale == LocaleHelper.LOCALE_KO)       activeColor else inactiveColor
        cardPortuguese.strokeColor    = if (locale == LocaleHelper.LOCALE_PT)       activeColor else inactiveColor
        cardRussian.strokeColor       = if (locale == LocaleHelper.LOCALE_RU)       activeColor else inactiveColor
        cardTurkish.strokeColor       = if (locale == LocaleHelper.LOCALE_TR)       activeColor else inactiveColor
        cardUkrainian.strokeColor     = if (locale == LocaleHelper.LOCALE_UK)       activeColor else inactiveColor
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill   = getColor(R.color.tv_button_focused_yellow)
        val blackText    = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor   = getColor(R.color.tv_glass_white_10)
        val primaryText  = getColor(R.color.tv_text_primary)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setCardTextColors(card, blackText)
                setCardRadioTint(card, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setCardTextColors(card, primaryText)
                setCardRadioTint(card, getColor(R.color.tv_accent))
            }
        }
    }

    private fun setCardTextColors(view: View, color: Int) {
        if (view is TextView) { view.setTextColor(color); return }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setCardTextColors(view.getChildAt(i), color)
        }
    }

    private fun setCardRadioTint(view: View, color: Int) {
        if (view is RadioButton) { view.buttonTintList = android.content.res.ColorStateList.valueOf(color); return }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setCardRadioTint(view.getChildAt(i), color)
        }
    }

    private fun loadFlag(imageViewId: Int, flagAsset: String) {
        val imageView = findViewById<ImageView>(imageViewId) ?: return
        // Load flag SVG from assets using Coil 3
        val placeholderImage = ContextCompat.getDrawable(this, R.drawable.ic_photo_video)?.asImage()
        imageView.load("file:///android_asset/remote/flags/$flagAsset") {
            placeholder(placeholderImage)
            error(placeholderImage)
        }
    }
}
