package za.kilowatch.ultimatefilemanager.onboarding

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.security.AppSecurityManager
import za.kilowatch.ultimatefilemanager.security.SecurityDialogHelper
import za.kilowatch.ultimatefilemanager.security.SecurityMode

/**
 * Onboarding Step 9: App Lock & Security.
 * Fully replicates the options and sub-options found in Settings when Protected Lock is active:
 * - Open Access vs Protected Lock
 * - Protected Sub-Options:
 *   - Security Method: PIN, Password, or Biometric (Fingerprint / Face)
 *   - Change Credential: Change PIN or Change Password
 *   - Lock Frequency: Fresh open only, Immediately, 1 minute, 5 minutes
 * - Live preview reflecting current lock and frequency state.
 */
class OnboardingAppLockActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 9
    override val stepTitleRes: Int = R.string.onboarding_app_lock_title

    private var isProtected: Boolean = false

    // Main Selection Cards
    private lateinit var cardOpen: MaterialCardView
    private lateinit var checkOptionOpen: ImageView

    private lateinit var cardProtected: MaterialCardView
    private lateinit var checkOptionProtected: ImageView

    // Protected Sub-Options Container & Rows
    private lateinit var layoutProtectedSettings: View
    private lateinit var cardSecurityMethod: View
    private lateinit var imgSecurityMethodIcon: ImageView
    private lateinit var txtSecurityMethodSubtitle: TextView

    private lateinit var dividerChangeCredential: View
    private lateinit var cardChangeCredential: View
    private lateinit var imgChangeCredentialIcon: ImageView
    private lateinit var txtChangeCredentialTitle: TextView
    private lateinit var txtChangeCredentialSubtitle: TextView

    private lateinit var dividerAutoLockTimeout: View
    private lateinit var cardAutoLockTimeout: View
    private lateinit var txtAutoLockTimeoutSubtitle: TextView

    // Live Preview
    private lateinit var imgLockStatus: ImageView
    private lateinit var txtLockStatusTitle: TextView
    private lateinit var txtLockStatusDesc: TextView
    private lateinit var txtLockStatusPill: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_app_lock_tv else R.layout.activity_onboarding_app_lock)
        setupCommonViews(findViewById(R.id.main))

        // Main Cards
        cardOpen = findViewById(R.id.cardOptionOpen)
        checkOptionOpen = findViewById(R.id.checkOptionOpen)

        cardProtected = findViewById(R.id.cardOptionProtected)
        checkOptionProtected = findViewById(R.id.checkOptionProtected)

        // Protected Sub-Options
        layoutProtectedSettings = findViewById(R.id.layoutProtectedSettings)
        cardSecurityMethod = findViewById(R.id.cardSecurityMethod)
        imgSecurityMethodIcon = findViewById(R.id.imgSecurityMethodIcon)
        txtSecurityMethodSubtitle = findViewById(R.id.txtSecurityMethodSubtitle)

        dividerChangeCredential = findViewById(R.id.dividerChangeCredential)
        cardChangeCredential = findViewById(R.id.cardChangeCredential)
        imgChangeCredentialIcon = findViewById(R.id.imgChangeCredentialIcon)
        txtChangeCredentialTitle = findViewById(R.id.txtChangeCredentialTitle)
        txtChangeCredentialSubtitle = findViewById(R.id.txtChangeCredentialSubtitle)

        dividerAutoLockTimeout = findViewById(R.id.dividerAutoLockTimeout)
        cardAutoLockTimeout = findViewById(R.id.cardAutoLockTimeout)
        txtAutoLockTimeoutSubtitle = findViewById(R.id.txtAutoLockTimeoutSubtitle)

        // Live Preview Views
        imgLockStatus = findViewById(R.id.imgLockStatus)
        txtLockStatusTitle = findViewById(R.id.txtLockStatusTitle)
        txtLockStatusDesc = findViewById(R.id.txtLockStatusDesc)
        txtLockStatusPill = findViewById(R.id.txtLockStatusPill)

        val secManager = AppSecurityManager.getInstance(this)
        isProtected = if (isReconfigureMode) {
            secManager.isSecurityEnabled()
        } else {
            false
        }

        // Open Access Click Listener
        cardOpen.setOnClickListener {
            if (isProtected) {
                if (secManager.isSecurityEnabled()) {
                    SecurityDialogHelper.showDisableConfirmDialog(
                        activity = this,
                        scope = lifecycleScope,
                        onConfirmed = {
                            isProtected = false
                            updateUI()
                        },
                        onCancel = {
                            isProtected = secManager.isSecurityEnabled()
                            updateUI()
                        }
                    )
                } else {
                    isProtected = false
                    updateUI()
                }
            } else {
                isProtected = false
                updateUI()
            }
        }

        // Protected Lock Click Listener
        cardProtected.setOnClickListener {
            if (!isProtected) {
                if (secManager.isSecurityEnabled()) {
                    isProtected = true
                    updateUI()
                } else {
                    // Open Method Picker matching Settings flow
                    SecurityDialogHelper.showMethodPicker(
                        context = this,
                        onModeSelected = { selectedMode ->
                            when (selectedMode) {
                                SecurityMode.PIN -> {
                                    SecurityDialogHelper.showSetPinFlow(
                                        context = this,
                                        scope = lifecycleScope,
                                        onConfirmed = {
                                            isProtected = true
                                            updateUI()
                                        },
                                        onCancel = {
                                            isProtected = secManager.isSecurityEnabled()
                                            updateUI()
                                        }
                                    )
                                }
                                SecurityMode.PASSWORD -> {
                                    SecurityDialogHelper.showSetPasswordFlow(
                                        context = this,
                                        scope = lifecycleScope,
                                        onConfirmed = {
                                            isProtected = true
                                            updateUI()
                                        },
                                        onCancel = {
                                            isProtected = secManager.isSecurityEnabled()
                                            updateUI()
                                        }
                                    )
                                }
                                SecurityMode.BIOMETRIC -> {
                                    lifecycleScope.launch {
                                        secManager.saveBiometricMode()
                                        isProtected = true
                                        updateUI()
                                    }
                                }
                                SecurityMode.NONE -> {
                                    isProtected = false
                                    updateUI()
                                }
                            }
                        },
                        onCancel = {
                            isProtected = secManager.isSecurityEnabled()
                            updateUI()
                        }
                    )
                }
            }
        }

        // Security Method Click Listener (Change Method)
        cardSecurityMethod.setOnClickListener {
            SecurityDialogHelper.showMethodPicker(
                context = this,
                onModeSelected = { newMode ->
                    when (newMode) {
                        SecurityMode.PIN -> {
                            SecurityDialogHelper.showSetPinFlow(
                                context = this,
                                scope = lifecycleScope,
                                onConfirmed = { updateUI() },
                                onCancel = { updateUI() }
                            )
                        }
                        SecurityMode.PASSWORD -> {
                            SecurityDialogHelper.showSetPasswordFlow(
                                context = this,
                                scope = lifecycleScope,
                                onConfirmed = { updateUI() },
                                onCancel = { updateUI() }
                            )
                        }
                        SecurityMode.BIOMETRIC -> {
                            lifecycleScope.launch {
                                secManager.saveBiometricMode()
                                updateUI()
                            }
                        }
                        SecurityMode.NONE -> {
                            updateUI()
                        }
                    }
                },
                onCancel = { updateUI() }
            )
        }

        // Change Credential Click Listener
        cardChangeCredential.setOnClickListener {
            val mode = secManager.getSecurityMode()
            if (mode == SecurityMode.PIN) {
                SecurityDialogHelper.showModifyPinFlow(
                    context = this,
                    scope = lifecycleScope,
                    onConfirmed = {
                        Toast.makeText(this, R.string.settings_security_change_pin, Toast.LENGTH_SHORT).show()
                    },
                    onCancel = {}
                )
            } else if (mode == SecurityMode.PASSWORD) {
                SecurityDialogHelper.showModifyPasswordFlow(
                    context = this,
                    scope = lifecycleScope,
                    onConfirmed = {
                        Toast.makeText(this, R.string.settings_security_change_password, Toast.LENGTH_SHORT).show()
                    },
                    onCancel = {}
                )
            }
        }

        // Lock Frequency / Auto-Lock Timeout Click Listener
        cardAutoLockTimeout.setOnClickListener {
            showLockTimeoutDialog()
        }

        if (isTv) {
            setupTvOptionFocus(cardOpen)
            setupTvOptionFocus(cardProtected)
            setupTvViewFocus(cardSecurityMethod)
            setupTvViewFocus(cardChangeCredential)
            setupTvViewFocus(cardAutoLockTimeout)
        }

        updateUI()
    }

    private fun showLockTimeoutDialog() {
        val layoutRes = if (isTv) R.layout.dialog_lock_frequency_tv else R.layout.dialog_lock_frequency
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)

        val secManager = AppSecurityManager.getInstance(this)
        val current = secManager.getLockTimeout()

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val options = listOf(
            Triple(R.id.btnTimeoutFreshOpen, R.id.checkTimeoutFreshOpen, AppSecurityManager.TIMEOUT_FRESH_OPEN_ONLY),
            Triple(R.id.btnTimeoutImmediately, R.id.checkTimeoutImmediately, AppSecurityManager.TIMEOUT_IMMEDIATELY),
            Triple(R.id.btnTimeout1Min, R.id.checkTimeout1Min, AppSecurityManager.TIMEOUT_ONE_MINUTE),
            Triple(R.id.btnTimeout5Min, R.id.checkTimeout5Min, AppSecurityManager.TIMEOUT_FIVE_MINUTES)
        )

        var defaultFocusView: View? = null

        options.forEach { (btnId, checkId, timeoutVal) ->
            val btn = dialogView.findViewById<View>(btnId)
            val check = dialogView.findViewById<ImageView>(checkId)
            val isSelected = current == timeoutVal
            check?.visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) {
                defaultFocusView = btn
            }

            btn?.setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    secManager.setLockTimeout(timeoutVal)
                    updateUI()
                }
            }

            if (isTv && btn is MaterialCardView) {
                setupTvOptionFocus(btn)
            }
        }

        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            (defaultFocusView ?: dialogView.findViewById(R.id.btnTimeoutFreshOpen))?.requestFocus()
        }
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()
        val secManager = AppSecurityManager.getInstance(this)

        // 1. Update Main Cards
        cardOpen.strokeWidth = if (!isProtected) dpToPx(2) else dpToPx(1)
        cardOpen.strokeColor = if (!isProtected) activeColor else inactiveStroke
        checkOptionOpen.visibility = if (!isProtected) View.VISIBLE else View.GONE

        cardProtected.strokeWidth = if (isProtected) dpToPx(2) else dpToPx(1)
        cardProtected.strokeColor = if (isProtected) activeColor else inactiveStroke
        checkOptionProtected.visibility = if (isProtected) View.VISIBLE else View.GONE

        // 2. Update Protected Sub-Options Visibility & Content
        if (isProtected) {
            layoutProtectedSettings.visibility = View.VISIBLE
            val mode = secManager.getSecurityMode()

            when (mode) {
                SecurityMode.PIN -> {
                    txtSecurityMethodSubtitle.text = getString(R.string.settings_security_method_pin)
                    imgSecurityMethodIcon.setImageResource(R.drawable.ic_dialpad)

                    dividerChangeCredential.visibility = View.VISIBLE
                    cardChangeCredential.visibility = View.VISIBLE
                    txtChangeCredentialTitle.setText(R.string.settings_security_change_pin)
                    txtChangeCredentialSubtitle.setText(R.string.settings_security_change_pin_subtitle)
                    imgChangeCredentialIcon.setImageResource(R.drawable.ic_dialpad)
                }
                SecurityMode.PASSWORD -> {
                    txtSecurityMethodSubtitle.text = getString(R.string.settings_security_method_password)
                    imgSecurityMethodIcon.setImageResource(R.drawable.ic_edit)

                    dividerChangeCredential.visibility = View.VISIBLE
                    cardChangeCredential.visibility = View.VISIBLE
                    txtChangeCredentialTitle.setText(R.string.settings_security_change_password)
                    txtChangeCredentialSubtitle.setText(R.string.settings_security_change_password_subtitle)
                    imgChangeCredentialIcon.setImageResource(R.drawable.ic_edit)
                }
                SecurityMode.BIOMETRIC -> {
                    txtSecurityMethodSubtitle.text = getString(R.string.settings_security_method_biometric)
                    imgSecurityMethodIcon.setImageResource(R.drawable.ic_fingerprint)

                    dividerChangeCredential.visibility = View.GONE
                    cardChangeCredential.visibility = View.GONE
                }
                SecurityMode.NONE -> {
                    txtSecurityMethodSubtitle.text = getString(R.string.settings_security_method_none)
                    imgSecurityMethodIcon.setImageResource(R.drawable.ic_security_lock)

                    dividerChangeCredential.visibility = View.GONE
                    cardChangeCredential.visibility = View.GONE
                }
            }

            // Lock Frequency Subtitle
            val timeout = secManager.getLockTimeout()
            val timeoutText = when (timeout) {
                AppSecurityManager.TIMEOUT_IMMEDIATELY -> getString(R.string.settings_security_lock_immediately)
                AppSecurityManager.TIMEOUT_ONE_MINUTE -> getString(R.string.settings_security_lock_1min)
                AppSecurityManager.TIMEOUT_FIVE_MINUTES -> getString(R.string.settings_security_lock_5min)
                else -> getString(R.string.settings_security_lock_fresh_open)
            }
            txtAutoLockTimeoutSubtitle.text = timeoutText

            // 3. Update Live Preview for Protected
            val methodLabel = when (mode) {
                SecurityMode.PIN -> "PIN Required"
                SecurityMode.PASSWORD -> "Password Required"
                SecurityMode.BIOMETRIC -> "Biometrics Required"
                else -> "Authentication Enforced"
            }
            imgLockStatus.setImageResource(
                when (mode) {
                    SecurityMode.PIN -> R.drawable.ic_dialpad
                    SecurityMode.PASSWORD -> R.drawable.ic_edit
                    SecurityMode.BIOMETRIC -> R.drawable.ic_fingerprint
                    else -> R.drawable.ic_security_lock
                }
            )
            txtLockStatusTitle.text = "Protected ($methodLabel)"
            txtLockStatusDesc.text = "Locked $timeoutText • Authentication enforced"
            txtLockStatusPill.text = "SECURE"
        } else {
            layoutProtectedSettings.visibility = View.GONE

            // 3. Update Live Preview for Open Access
            imgLockStatus.setImageResource(R.drawable.ic_lock)
            txtLockStatusTitle.text = "Open Access (Instant Launch)"
            txtLockStatusDesc.text = "No authentication required when opening UFM"
            txtLockStatusPill.text = "OPEN"
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

    private fun setupTvViewFocus(view: View) {
        val yellowFill = za.kilowatch.ultimatefilemanager.settings.ColorblindPalette.focusFill(this)

        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                view.setBackgroundColor(yellowFill)
            } else {
                view.setBackgroundResource(android.R.color.transparent)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        val secManager = AppSecurityManager.getInstance(this)
        if (!isProtected && secManager.isSecurityEnabled()) {
            lifecycleScope.launch {
                secManager.clearAllCredentials()
            }
        }
    }
}
