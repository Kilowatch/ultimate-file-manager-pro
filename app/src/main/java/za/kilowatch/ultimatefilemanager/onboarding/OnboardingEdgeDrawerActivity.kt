package za.kilowatch.ultimatefilemanager.onboarding

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.QuickAccessManager

/**
 * Onboarding Step 6: Quick Access Edge Drawer (Mobile only).
 * Pre-selects Enabled (MODE_EDGE_MENU) by default.
 */
class OnboardingEdgeDrawerActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 6
    override val stepTitleRes: Int = R.string.onboarding_edge_drawer_title

    private var isDrawerEnabled: Boolean = true

    private lateinit var cardEnabled: MaterialCardView
    private lateinit var cardDisabled: MaterialCardView

    private lateinit var radioEnabled: RadioButton
    private lateinit var radioDisabled: RadioButton

    private lateinit var previewDrawerPanel: View
    private lateinit var previewEdgeHandle: View
    private lateinit var txtDrawerHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_edge_drawer_tv else R.layout.activity_onboarding_edge_drawer)
        setupCommonViews(findViewById(R.id.main))

        cardEnabled = findViewById(R.id.cardOptionEnabled)
        cardDisabled = findViewById(R.id.cardOptionDisabled)

        radioEnabled = findViewById(R.id.radioOptionEnabled)
        radioDisabled = findViewById(R.id.radioOptionDisabled)

        previewDrawerPanel = findViewById(R.id.previewDrawerPanel)
        previewEdgeHandle = findViewById(R.id.previewEdgeHandle)
        txtDrawerHint = findViewById(R.id.txtDrawerHint)

        if (isReconfigureMode) {
            isDrawerEnabled = (QuickAccessManager.getMode(this) == QuickAccessManager.MODE_EDGE_MENU)
        } else {
            isDrawerEnabled = true
        }

        cardEnabled.setOnClickListener { setEnabledState(true) }
        cardDisabled.setOnClickListener { setEnabledState(false) }

        updateUI()
    }

    private fun setEnabledState(enabled: Boolean) {
        isDrawerEnabled = enabled
        updateUI()
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        radioEnabled.isChecked = isDrawerEnabled
        radioDisabled.isChecked = !isDrawerEnabled

        cardEnabled.strokeWidth = if (isDrawerEnabled) dpToPx(2) else dpToPx(1)
        cardEnabled.strokeColor = if (isDrawerEnabled) activeColor else inactiveStroke

        cardDisabled.strokeWidth = if (!isDrawerEnabled) dpToPx(2) else dpToPx(1)
        cardDisabled.strokeColor = if (!isDrawerEnabled) activeColor else inactiveStroke

        if (isDrawerEnabled) {
            previewDrawerPanel.visibility = View.VISIBLE
            previewEdgeHandle.visibility = View.VISIBLE
            txtDrawerHint.text = "Edge handle is active: Swipe inward from the right to open quick drawer"
        } else {
            previewDrawerPanel.visibility = View.GONE
            previewEdgeHandle.visibility = View.GONE
            txtDrawerHint.text = "Edge drawer is turned off. Use standard navigation only"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        val mode = if (isDrawerEnabled) QuickAccessManager.MODE_EDGE_MENU else QuickAccessManager.MODE_DISABLED
        QuickAccessManager.setMode(this, mode)
    }
}
