package za.kilowatch.ultimatefilemanager.onboarding

import android.os.Bundle
import android.util.TypedValue
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper

/**
 * Onboarding Step 2: Font Size Scale.
 * Pre-selects Medium (100%) by default.
 */
class OnboardingFontSizeActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 2
    override val stepTitleRes: Int = R.string.onboarding_font_size_title

    private var selectedSize: Int = FontSizeHelper.FONT_NORMAL

    private lateinit var cardSmall: MaterialCardView
    private lateinit var cardMedium: MaterialCardView
    private lateinit var cardLarge: MaterialCardView

    private lateinit var radioSmall: RadioButton
    private lateinit var radioMedium: RadioButton
    private lateinit var radioLarge: RadioButton

    private lateinit var previewFileName: TextView
    private lateinit var previewFileMeta: TextView
    private lateinit var previewFilePath: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_font_size_tv else R.layout.activity_onboarding_font_size)
        setupCommonViews(findViewById(R.id.main))

        cardSmall = findViewById(R.id.cardOptionSmall)
        cardMedium = findViewById(R.id.cardOptionMedium)
        cardLarge = findViewById(R.id.cardOptionLarge)

        radioSmall = findViewById(R.id.radioOptionSmall)
        radioMedium = findViewById(R.id.radioOptionMedium)
        radioLarge = findViewById(R.id.radioOptionLarge)

        previewFileName = findViewById(R.id.previewFileName)
        previewFileMeta = findViewById(R.id.previewFileMeta)
        previewFilePath = findViewById(R.id.previewFilePath)

        if (isReconfigureMode) {
            selectedSize = FontSizeHelper.getSavedSize(this)
        } else {
            selectedSize = FontSizeHelper.FONT_NORMAL
        }

        cardSmall.setOnClickListener { selectSize(FontSizeHelper.FONT_SMALL) }
        cardMedium.setOnClickListener { selectSize(FontSizeHelper.FONT_NORMAL) }
        cardLarge.setOnClickListener { selectSize(FontSizeHelper.FONT_LARGE) }

        if (isTv) {
            setupTvCardFocus(cardSmall) { selectedSize == FontSizeHelper.FONT_SMALL }
            setupTvCardFocus(cardMedium) { selectedSize == FontSizeHelper.FONT_NORMAL }
            setupTvCardFocus(cardLarge) { selectedSize == FontSizeHelper.FONT_LARGE }
        }

        updateUI()

        if (isTv) {
            val initialCard = when (selectedSize) {
                FontSizeHelper.FONT_SMALL -> cardSmall
                FontSizeHelper.FONT_LARGE -> cardLarge
                else -> cardMedium
            }
            requestInitialTvFocus(initialCard)
        }
    }

    private fun selectSize(size: Int) {
        selectedSize = size
        updateUI()
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        radioSmall.isChecked = (selectedSize == FontSizeHelper.FONT_SMALL)
        radioMedium.isChecked = (selectedSize == FontSizeHelper.FONT_NORMAL)
        radioLarge.isChecked = (selectedSize == FontSizeHelper.FONT_LARGE)

        if (isTv) {
            applyCardSelectionStroke(cardSmall, radioSmall.isChecked)
            applyCardSelectionStroke(cardMedium, radioMedium.isChecked)
            applyCardSelectionStroke(cardLarge, radioLarge.isChecked)
        } else {
            cardSmall.strokeWidth = if (radioSmall.isChecked) dpToPx(2) else dpToPx(1)
            cardSmall.strokeColor = if (radioSmall.isChecked) activeColor else inactiveStroke

            cardMedium.strokeWidth = if (radioMedium.isChecked) dpToPx(2) else dpToPx(1)
            cardMedium.strokeColor = if (radioMedium.isChecked) activeColor else inactiveStroke

            cardLarge.strokeWidth = if (radioLarge.isChecked) dpToPx(2) else dpToPx(1)
            cardLarge.strokeColor = if (radioLarge.isChecked) activeColor else inactiveStroke
        }

        val (nameSp, metaSp, pathSp) = when (selectedSize) {
            FontSizeHelper.FONT_SMALL -> Triple(14f, 11f, 10f)
            FontSizeHelper.FONT_LARGE -> Triple(19f, 14f, 12.5f)
            else -> Triple(16f, 12f, 11f)
        }

        previewFileName.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp)
        previewFileMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp)
        previewFilePath.setTextSize(TypedValue.COMPLEX_UNIT_SP, pathSp)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        FontSizeHelper.save(this, selectedSize)
    }
}
