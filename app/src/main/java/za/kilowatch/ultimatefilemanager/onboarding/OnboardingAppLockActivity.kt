package za.kilowatch.ultimatefilemanager.onboarding

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.security.AppSecurityManager

/**
 * Onboarding Step 9: App Lock & Security.
 * Pre-selects Open Access (Off) by default. Automatically skipped if hardware lacks secure lock.
 */
class OnboardingAppLockActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 9
    override val stepTitleRes: Int = R.string.onboarding_app_lock_title

    private var isProtected: Boolean = false

    private lateinit var cardOpen: MaterialCardView
    private lateinit var cardProtected: MaterialCardView

    private lateinit var radioOpen: RadioButton
    private lateinit var radioProtected: RadioButton

    private lateinit var imgLockStatus: ImageView
    private lateinit var txtLockStatusTitle: TextView
    private lateinit var txtLockStatusDesc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_app_lock_tv else R.layout.activity_onboarding_app_lock)
        setupCommonViews(findViewById(R.id.main))

        cardOpen = findViewById(R.id.cardOptionOpen)
        cardProtected = findViewById(R.id.cardOptionProtected)

        radioOpen = findViewById(R.id.radioOptionOpen)
        radioProtected = findViewById(R.id.radioOptionProtected)

        imgLockStatus = findViewById(R.id.imgLockStatus)
        txtLockStatusTitle = findViewById(R.id.txtLockStatusTitle)
        txtLockStatusDesc = findViewById(R.id.txtLockStatusDesc)

        if (isReconfigureMode) {
            isProtected = AppSecurityManager.getInstance(this).isSecurityEnabled()
        } else {
            isProtected = false
        }

        cardOpen.setOnClickListener { setProtectionState(false) }
        cardProtected.setOnClickListener { setProtectionState(true) }

        updateUI()
    }

    private fun setProtectionState(protectedMode: Boolean) {
        isProtected = protectedMode
        updateUI()
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        radioOpen.isChecked = !isProtected
        radioProtected.isChecked = isProtected

        cardOpen.strokeWidth = if (!isProtected) dpToPx(2) else dpToPx(1)
        cardOpen.strokeColor = if (!isProtected) activeColor else inactiveStroke

        cardProtected.strokeWidth = if (isProtected) dpToPx(2) else dpToPx(1)
        cardProtected.strokeColor = if (isProtected) activeColor else inactiveStroke

        if (isProtected) {
            imgLockStatus.imageTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))
            txtLockStatusTitle.text = "Protected (Biometric / PIN Required)"
            txtLockStatusDesc.text = "Authentication enforced each time UFM opens"
        } else {
            imgLockStatus.imageTintList = ColorStateList.valueOf(activeColor)
            txtLockStatusTitle.text = "Open Access (Instant Launch)"
            txtLockStatusDesc.text = "No authentication required when opening UFM"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        // AppSecurityManager default is already false (unlocked/no credentials).
        // If protected is toggled, it will take effect or remain as chosen.
    }
}
