package za.kilowatch.ultimatefilemanager.onboarding

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LeftHandedFabPreferenceManager

/**
 * Onboarding Step 5: Left-Handed FAB Mode (Mobile only).
 * Pre-selects Right-Handed (Off) by default.
 */
class OnboardingLeftHandedFabActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 5
    override val stepTitleRes: Int = R.string.onboarding_left_handed_fab_title

    private var isLeftHanded: Boolean = false

    private lateinit var cardRight: MaterialCardView
    private lateinit var cardLeft: MaterialCardView

    private lateinit var radioRight: RadioButton
    private lateinit var radioLeft: RadioButton

    private lateinit var mockFab: View
    private lateinit var txtFabPositionHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_left_handed_fab_tv else R.layout.activity_onboarding_left_handed_fab)
        setupCommonViews(findViewById(R.id.main))

        cardRight = findViewById(R.id.cardOptionRight)
        cardLeft = findViewById(R.id.cardOptionLeft)

        radioRight = findViewById(R.id.radioOptionRight)
        radioLeft = findViewById(R.id.radioOptionLeft)

        mockFab = findViewById(R.id.mockFab)
        txtFabPositionHint = findViewById(R.id.txtFabPositionHint)

        if (isReconfigureMode) {
            isLeftHanded = LeftHandedFabPreferenceManager.isLeftHanded(this)
        } else {
            isLeftHanded = false
        }

        cardRight.setOnClickListener { setPlacement(false) }
        cardLeft.setOnClickListener { setPlacement(true) }

        updateUI()
    }

    private fun setPlacement(leftHanded: Boolean) {
        isLeftHanded = leftHanded
        updateUI()
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        radioRight.isChecked = !isLeftHanded
        radioLeft.isChecked = isLeftHanded

        cardRight.strokeWidth = if (!isLeftHanded) dpToPx(2) else dpToPx(1)
        cardRight.strokeColor = if (!isLeftHanded) activeColor else inactiveStroke

        cardLeft.strokeWidth = if (isLeftHanded) dpToPx(2) else dpToPx(1)
        cardLeft.strokeColor = if (isLeftHanded) activeColor else inactiveStroke

        val params = mockFab.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (isLeftHanded) (Gravity.BOTTOM or Gravity.START) else (Gravity.BOTTOM or Gravity.END)
        mockFab.layoutParams = params

        txtFabPositionHint.text = if (isLeftHanded) {
            "FAB positioned on the left for comfortable left-handed access"
        } else {
            "FAB positioned on the right for standard one-hand thumb access"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        LeftHandedFabPreferenceManager.setLeftHanded(this, isLeftHanded)
    }
}
