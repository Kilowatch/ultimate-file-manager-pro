package za.kilowatch.ultimatefilemanager.onboarding

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FileNameDisplayHelper
import za.kilowatch.ultimatefilemanager.settings.ScrollingTextHelper
import za.kilowatch.ultimatefilemanager.settings.ScrollingTextPreferenceManager

/**
 * Onboarding Step 3: Scrolling Text / File Name Display.
 * Fully replicates the options and sub-options found in Settings:
 * - 6 Modes: Marquee Auto-Scroll, 2 Lines, 3 Lines, Unlimited Lines, Single Line (Truncate End), Single Line (Truncate Middle)
 * - Marquee Sub-options: Scroll Speed (Slow, Normal, Fast), Loop Mode (One-Shot, Continuous), Scroll Scope (All Visible, Focused Only)
 * - Authentic live preview reflecting the exact text behavior and animations in real-time.
 */
class OnboardingScrollingTextActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 3
    override val stepTitleRes: Int = R.string.onboarding_scrolling_text_title

    private var selectedMode: String = ScrollingTextPreferenceManager.MODE_MARQUEE
    private var selectedSpeed: String = ScrollingTextPreferenceManager.SPEED_NORMAL
    private var selectedLoop: String = ScrollingTextPreferenceManager.LOOP_ONE_SHOT
    private var selectedScope: String = ScrollingTextPreferenceManager.SCOPE_ALL

    // Mode Cards & Check Badges
    private lateinit var cardMarquee: MaterialCardView
    private lateinit var checkMarquee: ImageView

    private lateinit var card2Lines: MaterialCardView
    private lateinit var check2Lines: ImageView

    private lateinit var card3Lines: MaterialCardView
    private lateinit var check3Lines: ImageView

    private lateinit var cardUnlimited: MaterialCardView
    private lateinit var checkUnlimited: ImageView

    private lateinit var cardTruncate: MaterialCardView
    private lateinit var checkTruncate: ImageView

    private lateinit var cardTruncateMiddle: MaterialCardView
    private lateinit var checkTruncateMiddle: ImageView

    // Marquee Sub-options
    private lateinit var layoutMarqueeSubOptions: View
    private lateinit var cgSpeed: ChipGroup
    private lateinit var chipSpeedSlow: Chip
    private lateinit var chipSpeedNormal: Chip
    private lateinit var chipSpeedFast: Chip

    private lateinit var cgLoop: ChipGroup
    private lateinit var chipLoopOneShot: Chip
    private lateinit var chipLoopContinuous: Chip

    private lateinit var cgScope: ChipGroup
    private lateinit var chipScopeAll: Chip
    private lateinit var chipScopeFocused: Chip

    // Live Preview
    private lateinit var txtPreviewFileName: TextView
    private lateinit var sampleText: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_scrolling_text_tv else R.layout.activity_onboarding_scrolling_text)
        setupCommonViews(findViewById(R.id.main))

        // Cards & Checks
        cardMarquee = findViewById(R.id.cardOptionMarquee)
        checkMarquee = findViewById(R.id.checkModeMarquee)

        card2Lines = findViewById(R.id.cardOption2Lines)
        check2Lines = findViewById(R.id.checkMode2Lines)

        card3Lines = findViewById(R.id.cardOption3Lines)
        check3Lines = findViewById(R.id.checkMode3Lines)

        cardUnlimited = findViewById(R.id.cardOptionUnlimited)
        checkUnlimited = findViewById(R.id.checkModeUnlimited)

        cardTruncate = findViewById(R.id.cardOptionTruncate)
        checkTruncate = findViewById(R.id.checkModeTruncate)

        cardTruncateMiddle = findViewById(R.id.cardOptionTruncateMiddle)
        checkTruncateMiddle = findViewById(R.id.checkModeTruncateMiddle)

        // Sub-options
        layoutMarqueeSubOptions = findViewById(R.id.layoutMarqueeSubOptions)
        cgSpeed = findViewById(R.id.cgSpeed)
        chipSpeedSlow = findViewById(R.id.chipSpeedSlow)
        chipSpeedNormal = findViewById(R.id.chipSpeedNormal)
        chipSpeedFast = findViewById(R.id.chipSpeedFast)

        cgLoop = findViewById(R.id.cgLoop)
        chipLoopOneShot = findViewById(R.id.chipLoopOneShot)
        chipLoopContinuous = findViewById(R.id.chipLoopContinuous)

        cgScope = findViewById(R.id.cgScope)
        chipScopeAll = findViewById(R.id.chipScopeAll)
        chipScopeFocused = findViewById(R.id.chipScopeFocused)

        // Preview
        txtPreviewFileName = findViewById(R.id.txtPreviewFileName)
        sampleText = getString(R.string.settings_file_name_preview_sample)

        // Load initial values
        if (isReconfigureMode) {
            selectedMode = ScrollingTextPreferenceManager.getMode(this)
            selectedSpeed = ScrollingTextPreferenceManager.getScrollSpeed(this)
            selectedLoop = ScrollingTextPreferenceManager.getLoopMode(this)
            selectedScope = ScrollingTextPreferenceManager.getScrollScope(this)
        } else {
            selectedMode = ScrollingTextPreferenceManager.MODE_MARQUEE
            selectedSpeed = ScrollingTextPreferenceManager.SPEED_NORMAL
            selectedLoop = ScrollingTextPreferenceManager.LOOP_ONE_SHOT
            selectedScope = ScrollingTextPreferenceManager.SCOPE_ALL
        }

        // Initialize chip selections
        when (selectedSpeed) {
            ScrollingTextPreferenceManager.SPEED_SLOW -> chipSpeedSlow.isChecked = true
            ScrollingTextPreferenceManager.SPEED_FAST -> chipSpeedFast.isChecked = true
            else -> chipSpeedNormal.isChecked = true
        }

        when (selectedLoop) {
            ScrollingTextPreferenceManager.LOOP_CONTINUOUS -> chipLoopContinuous.isChecked = true
            else -> chipLoopOneShot.isChecked = true
        }

        when (selectedScope) {
            ScrollingTextPreferenceManager.SCOPE_FOCUSED -> chipScopeFocused.isChecked = true
            else -> chipScopeAll.isChecked = true
        }

        // Mode Card Click Listeners
        cardMarquee.setOnClickListener { selectMode(ScrollingTextPreferenceManager.MODE_MARQUEE) }
        card2Lines.setOnClickListener { selectMode(ScrollingTextPreferenceManager.MODE_MULTILINE_2) }
        card3Lines.setOnClickListener { selectMode(ScrollingTextPreferenceManager.MODE_MULTILINE_3) }
        cardUnlimited.setOnClickListener { selectMode(ScrollingTextPreferenceManager.MODE_MULTILINE_UNLIMITED) }
        cardTruncate.setOnClickListener { selectMode(ScrollingTextPreferenceManager.MODE_TRUNCATE) }
        cardTruncateMiddle.setOnClickListener { selectMode(ScrollingTextPreferenceManager.MODE_TRUNCATE_MIDDLE) }

        // Chip Listeners
        cgSpeed.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedSpeed = when {
                checkedIds.contains(R.id.chipSpeedSlow) -> ScrollingTextPreferenceManager.SPEED_SLOW
                checkedIds.contains(R.id.chipSpeedFast) -> ScrollingTextPreferenceManager.SPEED_FAST
                else -> ScrollingTextPreferenceManager.SPEED_NORMAL
            }
            ScrollingTextPreferenceManager.setScrollSpeed(this, selectedSpeed)
            if (selectedMode == ScrollingTextPreferenceManager.MODE_MARQUEE) {
                updatePreview(selectedMode)
            }
        }

        cgLoop.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedLoop = when {
                checkedIds.contains(R.id.chipLoopContinuous) -> ScrollingTextPreferenceManager.LOOP_CONTINUOUS
                else -> ScrollingTextPreferenceManager.LOOP_ONE_SHOT
            }
            ScrollingTextPreferenceManager.setLoopMode(this, selectedLoop)
            if (selectedMode == ScrollingTextPreferenceManager.MODE_MARQUEE) {
                updatePreview(selectedMode)
            }
        }

        cgScope.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedScope = when {
                checkedIds.contains(R.id.chipScopeFocused) -> ScrollingTextPreferenceManager.SCOPE_FOCUSED
                else -> ScrollingTextPreferenceManager.SCOPE_ALL
            }
            ScrollingTextPreferenceManager.setScrollScope(this, selectedScope)
        }

        if (isTv) {
            setupTvOptionFocus(cardMarquee)
            setupTvOptionFocus(card2Lines)
            setupTvOptionFocus(card3Lines)
            setupTvOptionFocus(cardUnlimited)
            setupTvOptionFocus(cardTruncate)
            setupTvOptionFocus(cardTruncateMiddle)
        }

        updateUI()
        updatePreview(selectedMode)
    }

    override fun onResume() {
        super.onResume()
        updatePreview(selectedMode)
    }

    override fun onPause() {
        super.onPause()
        ScrollingTextHelper.cancelScrolling(txtPreviewFileName)
    }

    override fun onDestroy() {
        super.onDestroy()
        ScrollingTextHelper.cancelScrolling(txtPreviewFileName)
    }

    private fun selectMode(mode: String) {
        selectedMode = mode
        updateUI()
        updatePreview(mode)
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        val isMarquee = (selectedMode == ScrollingTextPreferenceManager.MODE_MARQUEE)
        val is2Lines = (selectedMode == ScrollingTextPreferenceManager.MODE_MULTILINE_2)
        val is3Lines = (selectedMode == ScrollingTextPreferenceManager.MODE_MULTILINE_3)
        val isUnlimited = (selectedMode == ScrollingTextPreferenceManager.MODE_MULTILINE_UNLIMITED)
        val isTruncate = (selectedMode == ScrollingTextPreferenceManager.MODE_TRUNCATE)
        val isTruncateMiddle = (selectedMode == ScrollingTextPreferenceManager.MODE_TRUNCATE_MIDDLE)

        updateCardState(cardMarquee, checkMarquee, isMarquee, activeColor, inactiveStroke)
        updateCardState(card2Lines, check2Lines, is2Lines, activeColor, inactiveStroke)
        updateCardState(card3Lines, check3Lines, is3Lines, activeColor, inactiveStroke)
        updateCardState(cardUnlimited, checkUnlimited, isUnlimited, activeColor, inactiveStroke)
        updateCardState(cardTruncate, checkTruncate, isTruncate, activeColor, inactiveStroke)
        updateCardState(cardTruncateMiddle, checkTruncateMiddle, isTruncateMiddle, activeColor, inactiveStroke)

        layoutMarqueeSubOptions.visibility = if (isMarquee) View.VISIBLE else View.GONE
    }

    private fun updateCardState(
        card: MaterialCardView,
        check: ImageView,
        isSelected: Boolean,
        activeColor: Int,
        inactiveStroke: Int
    ) {
        card.strokeWidth = if (isSelected) dpToPx(2) else dpToPx(1)
        card.strokeColor = if (isSelected) activeColor else inactiveStroke
        check.visibility = if (isSelected) View.VISIBLE else View.GONE
    }

    private fun updatePreview(mode: String) {
        ScrollingTextHelper.cancelScrolling(txtPreviewFileName)
        txtPreviewFileName.setHorizontallyScrolling(false)
        txtPreviewFileName.scrollX = 0

        when (mode) {
            ScrollingTextPreferenceManager.MODE_MARQUEE -> {
                txtPreviewFileName.maxLines = 1
                txtPreviewFileName.ellipsize = null
                txtPreviewFileName.text = sampleText
                ScrollingTextHelper.applyScrollingText(txtPreviewFileName, enabled = true)
            }
            ScrollingTextPreferenceManager.MODE_TRUNCATE_MIDDLE -> {
                txtPreviewFileName.maxLines = 1
                txtPreviewFileName.ellipsize = TextUtils.TruncateAt.MIDDLE
                val avail = (txtPreviewFileName.width - txtPreviewFileName.paddingLeft - txtPreviewFileName.paddingRight).toFloat()
                if (avail > 0) {
                    txtPreviewFileName.text = FileNameDisplayHelper.truncateMiddle(sampleText, txtPreviewFileName.paint, avail)
                } else {
                    txtPreviewFileName.text = sampleText
                    txtPreviewFileName.post {
                        if (txtPreviewFileName.isAttachedToWindow && txtPreviewFileName.width > 0) {
                            val w = (txtPreviewFileName.width - txtPreviewFileName.paddingLeft - txtPreviewFileName.paddingRight).toFloat()
                            if (w > 0) {
                                txtPreviewFileName.text = FileNameDisplayHelper.truncateMiddle(sampleText, txtPreviewFileName.paint, w)
                            }
                        }
                    }
                }
            }
            ScrollingTextPreferenceManager.MODE_TRUNCATE -> {
                txtPreviewFileName.maxLines = 1
                txtPreviewFileName.ellipsize = TextUtils.TruncateAt.END
                txtPreviewFileName.text = sampleText
            }
            else -> {
                txtPreviewFileName.maxLines = when (mode) {
                    ScrollingTextPreferenceManager.MODE_MULTILINE_2 -> 2
                    ScrollingTextPreferenceManager.MODE_MULTILINE_3 -> 3
                    else -> 10
                }
                txtPreviewFileName.ellipsize = TextUtils.TruncateAt.END
                txtPreviewFileName.text = FileNameDisplayHelper.formatForWrapping(sampleText)
            }
        }
    }

    private fun setupTvOptionFocus(card: MaterialCardView) {
        val yellowFill = za.kilowatch.ultimatefilemanager.settings.ColorblindPalette.focusFill(this)
        val glassColor = getColor(R.color.tv_glass_white_10)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
            } else {
                card.setCardBackgroundColor(glassColor)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        ScrollingTextPreferenceManager.setMode(this, selectedMode)
        ScrollingTextPreferenceManager.setScrollSpeed(this, selectedSpeed)
        ScrollingTextPreferenceManager.setLoopMode(this, selectedLoop)
        ScrollingTextPreferenceManager.setScrollScope(this, selectedScope)
    }
}
