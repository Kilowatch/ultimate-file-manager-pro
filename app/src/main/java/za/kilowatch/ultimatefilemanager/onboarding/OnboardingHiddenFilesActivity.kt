package za.kilowatch.ultimatefilemanager.onboarding

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager

/**
 * Onboarding Step 8: Show Hidden Files.
 * Pre-selects Hide Dotfiles (Off) by default.
 */
class OnboardingHiddenFilesActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 8
    override val stepTitleRes: Int = R.string.onboarding_hidden_files_title

    private var showHidden: Boolean = false

    private lateinit var cardHide: MaterialCardView
    private lateinit var cardShow: MaterialCardView

    private lateinit var radioHide: RadioButton
    private lateinit var radioShow: RadioButton

    private lateinit var previewHiddenItem: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_hidden_files_tv else R.layout.activity_onboarding_hidden_files)
        setupCommonViews(findViewById(R.id.main))

        HiddenFilesManager.init(this)

        cardHide = findViewById(R.id.cardOptionHide)
        cardShow = findViewById(R.id.cardOptionShow)

        radioHide = findViewById(R.id.radioOptionHide)
        radioShow = findViewById(R.id.radioOptionShow)

        previewHiddenItem = findViewById(R.id.previewHiddenItem)

        if (isReconfigureMode) {
            showHidden = HiddenFilesManager.isShowHiddenFilesEnabled
        } else {
            showHidden = false
        }

        cardHide.setOnClickListener { setShowHiddenState(false) }
        cardShow.setOnClickListener { setShowHiddenState(true) }

        updateUI()
    }

    private fun setShowHiddenState(show: Boolean) {
        showHidden = show
        updateUI()
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        radioHide.isChecked = !showHidden
        radioShow.isChecked = showHidden

        cardHide.strokeWidth = if (!showHidden) dpToPx(2) else dpToPx(1)
        cardHide.strokeColor = if (!showHidden) activeColor else inactiveStroke

        cardShow.strokeWidth = if (showHidden) dpToPx(2) else dpToPx(1)
        cardShow.strokeColor = if (showHidden) activeColor else inactiveStroke

        previewHiddenItem.visibility = if (showHidden) View.VISIBLE else View.GONE
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        HiddenFilesManager.isShowHiddenFilesEnabled = showHidden
    }
}
