package za.kilowatch.ultimatefilemanager.onboarding

import android.os.Bundle
import android.text.TextUtils
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ScrollingTextPreferenceManager

/**
 * Onboarding Step 3: Scrolling Text.
 * Pre-selects Marquee Auto-Scroll by default, actively animating on screen open.
 */
class OnboardingScrollingTextActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 3
    override val stepTitleRes: Int = R.string.onboarding_scrolling_text_title

    private var selectedMode: String = ScrollingTextPreferenceManager.MODE_MARQUEE

    private lateinit var cardMarquee: MaterialCardView
    private lateinit var cardTruncate: MaterialCardView

    private lateinit var radioMarquee: RadioButton
    private lateinit var radioTruncate: RadioButton

    private lateinit var previewFileName: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_scrolling_text_tv else R.layout.activity_onboarding_scrolling_text)
        setupCommonViews(findViewById(R.id.main))

        cardMarquee = findViewById(R.id.cardOptionMarquee)
        cardTruncate = findViewById(R.id.cardOptionTruncate)

        radioMarquee = findViewById(R.id.radioOptionMarquee)
        radioTruncate = findViewById(R.id.radioOptionTruncate)

        previewFileName = findViewById(R.id.previewFileName)

        if (isReconfigureMode) {
            selectedMode = ScrollingTextPreferenceManager.getMode(this)
        } else {
            selectedMode = ScrollingTextPreferenceManager.MODE_MARQUEE
        }

        cardMarquee.setOnClickListener { selectMode(ScrollingTextPreferenceManager.MODE_MARQUEE) }
        cardTruncate.setOnClickListener { selectMode(ScrollingTextPreferenceManager.MODE_TRUNCATE) }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        // Ensure marquee restarts smoothly on resume if active
        if (selectedMode == ScrollingTextPreferenceManager.MODE_MARQUEE) {
            previewFileName.isSelected = true
        }
    }

    private fun selectMode(mode: String) {
        selectedMode = mode
        updateUI()
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        val isMarquee = (selectedMode == ScrollingTextPreferenceManager.MODE_MARQUEE)

        radioMarquee.isChecked = isMarquee
        radioTruncate.isChecked = !isMarquee

        cardMarquee.strokeWidth = if (isMarquee) dpToPx(2) else dpToPx(1)
        cardMarquee.strokeColor = if (isMarquee) activeColor else inactiveStroke

        cardTruncate.strokeWidth = if (!isMarquee) dpToPx(2) else dpToPx(1)
        cardTruncate.strokeColor = if (!isMarquee) activeColor else inactiveStroke

        if (isMarquee) {
            previewFileName.ellipsize = TextUtils.TruncateAt.MARQUEE
            previewFileName.isSelected = true
        } else {
            previewFileName.isSelected = false
            previewFileName.ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        ScrollingTextPreferenceManager.setMode(this, selectedMode)
    }
}
