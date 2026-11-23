package za.kilowatch.ultimatefilemanager.onboarding

import android.os.Bundle
import android.widget.RadioButton
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.support.CrashReportManager

/**
 * Onboarding Step 10: Crash & Diagnostic Reporting.
 * Pre-selects Enabled (Recommended) by default.
 */
class OnboardingDiagnosticsActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 10
    override val stepTitleRes: Int = R.string.onboarding_diagnostics_title

    private var isDiagnosticsEnabled: Boolean = true

    private lateinit var cardEnabled: MaterialCardView
    private lateinit var cardDisabled: MaterialCardView

    private lateinit var radioEnabled: RadioButton
    private lateinit var radioDisabled: RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_diagnostics_tv else R.layout.activity_onboarding_diagnostics)
        setupCommonViews(findViewById(R.id.main))

        cardEnabled = findViewById(R.id.cardOptionEnabled)
        cardDisabled = findViewById(R.id.cardOptionDisabled)

        radioEnabled = findViewById(R.id.radioOptionEnabled)
        radioDisabled = findViewById(R.id.radioOptionDisabled)

        if (isReconfigureMode) {
            isDiagnosticsEnabled = CrashReportManager.isEnabled(this)
        } else {
            isDiagnosticsEnabled = true
        }

        cardEnabled.setOnClickListener { setEnabledState(true) }
        cardDisabled.setOnClickListener { setEnabledState(false) }

        updateUI()
    }

    private fun setEnabledState(enabled: Boolean) {
        isDiagnosticsEnabled = enabled
        updateUI()
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        radioEnabled.isChecked = isDiagnosticsEnabled
        radioDisabled.isChecked = !isDiagnosticsEnabled

        cardEnabled.strokeWidth = if (isDiagnosticsEnabled) dpToPx(2) else dpToPx(1)
        cardEnabled.strokeColor = if (isDiagnosticsEnabled) activeColor else inactiveStroke

        cardDisabled.strokeWidth = if (!isDiagnosticsEnabled) dpToPx(2) else dpToPx(1)
        cardDisabled.strokeColor = if (!isDiagnosticsEnabled) activeColor else inactiveStroke
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        CrashReportManager.setEnabled(this, isDiagnosticsEnabled)
    }
}
