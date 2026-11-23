package za.kilowatch.ultimatefilemanager.onboarding

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.recycle.RecycleBinSettingsManager

/**
 * Onboarding Step 7: Recycle Bin Protection.
 * Pre-selects Permanent Erase (Default - Recycle Bin Disabled) by default.
 */
class OnboardingRecycleBinActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 7
    override val stepTitleRes: Int = R.string.onboarding_recycle_bin_title

    private var isRecycleEnabled: Boolean = false

    private lateinit var cardPermanent: MaterialCardView
    private lateinit var cardSafe: MaterialCardView

    private lateinit var radioPermanent: RadioButton
    private lateinit var radioSafe: RadioButton

    private lateinit var imgPreviewShield: ImageView
    private lateinit var txtPreviewBadgeTitle: TextView
    private lateinit var txtPreviewBadgeDesc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_recycle_bin_tv else R.layout.activity_onboarding_recycle_bin)
        setupCommonViews(findViewById(R.id.main))

        cardPermanent = findViewById(R.id.cardOptionPermanent)
        cardSafe = findViewById(R.id.cardOptionSafe)

        radioPermanent = findViewById(R.id.radioOptionPermanent)
        radioSafe = findViewById(R.id.radioOptionSafe)

        imgPreviewShield = findViewById(R.id.imgPreviewShield)
        txtPreviewBadgeTitle = findViewById(R.id.txtPreviewBadgeTitle)
        txtPreviewBadgeDesc = findViewById(R.id.txtPreviewBadgeDesc)

        if (isReconfigureMode) {
            isRecycleEnabled = RecycleBinSettingsManager.isEnabled(this)
        } else {
            isRecycleEnabled = false
        }

        cardPermanent.setOnClickListener { setRecycleState(false) }
        cardSafe.setOnClickListener { setRecycleState(true) }

        updateUI()
    }

    private fun setRecycleState(enabled: Boolean) {
        isRecycleEnabled = enabled
        updateUI()
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        radioPermanent.isChecked = !isRecycleEnabled
        radioSafe.isChecked = isRecycleEnabled

        cardPermanent.strokeWidth = if (!isRecycleEnabled) dpToPx(2) else dpToPx(1)
        cardPermanent.strokeColor = if (!isRecycleEnabled) activeColor else inactiveStroke

        cardSafe.strokeWidth = if (isRecycleEnabled) dpToPx(2) else dpToPx(1)
        cardSafe.strokeColor = if (isRecycleEnabled) activeColor else inactiveStroke

        if (isRecycleEnabled) {
            imgPreviewShield.setImageResource(R.drawable.ic_shield_check)
            imgPreviewShield.imageTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            txtPreviewBadgeTitle.setText(R.string.onboarding_preview_safe_badge)
            txtPreviewBadgeDesc.text = "Deletions move to the Recycle Bin and can be restored for up to 30 days"
        } else {
            imgPreviewShield.setImageResource(R.drawable.ic_shield_alert)
            imgPreviewShield.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
            txtPreviewBadgeTitle.setText(R.string.onboarding_preview_warn_badge)
            txtPreviewBadgeDesc.text = "Bypasses the Recycle Bin. Files are permanently and immediately erased."
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        RecycleBinSettingsManager.setEnabled(this, isRecycleEnabled)
    }
}
