package za.kilowatch.ultimatefilemanager.security

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper

/**
 * Full-screen, security-gated unlock screen for Mobile devices.
 *
 * Enforces FLAG_SECURE against screenshots and recent-apps previews.
 * Intercepts back navigation to minimize the task instead of bypassing authentication.
 */
class SecurityUnlockActivity : AppCompatActivity() {

    private lateinit var secManager: AppSecurityManager

    // UI containers
    private lateinit var layoutHeroHeader: LinearLayout
    private lateinit var txtUnlockSubtitle: TextView
    private lateinit var txtUnlockError: TextView
    private lateinit var layoutPinContainer: LinearLayout
    private lateinit var layoutPasswordContainer: LinearLayout
    private lateinit var layoutBiometricContainer: LinearLayout
    private lateinit var layoutLockoutContainer: LinearLayout
    private lateinit var txtLockoutTimer: TextView

    // PIN UI
    private val pinDigits = StringBuilder()
    private val pinDots = ArrayList<View>()

    // Password UI
    private lateinit var tilUnlockPassword: TextInputLayout
    private lateinit var etUnlockPassword: TextInputEditText
    private lateinit var btnUnlockPassword: MaterialButton

    // Lockout timer
    private var countDownTimer: CountDownTimer? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // SEC: Prevent credential entry from being recorded or previewed in recents
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_security_unlock)

        secManager = AppSecurityManager.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Intercept back button: Minimize app, never allow bypass
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        initViews()
        setupModeUi()
    }

    override fun onResume() {
        super.onResume()
        if (secManager.isLockedOut()) {
            startLockoutCountdown(secManager.getRemainingLockoutSeconds())
        } else if (secManager.getSecurityMode() == SecurityMode.BIOMETRIC) {
            triggerBiometricPrompt()
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        countDownTimer = null
        super.onDestroy()
    }

    private fun initViews() {
        layoutHeroHeader = findViewById(R.id.layoutHeroHeader)
        txtUnlockSubtitle = findViewById(R.id.txtUnlockSubtitle)
        txtUnlockError = findViewById(R.id.txtUnlockError)
        layoutPinContainer = findViewById(R.id.layoutPinContainer)
        layoutPasswordContainer = findViewById(R.id.layoutPasswordContainer)
        layoutBiometricContainer = findViewById(R.id.layoutBiometricContainer)
        layoutLockoutContainer = findViewById(R.id.layoutLockoutContainer)
        txtLockoutTimer = findViewById(R.id.txtLockoutTimer)

        // PIN dots
        pinDots.add(findViewById(R.id.pinDot1))
        pinDots.add(findViewById(R.id.pinDot2))
        pinDots.add(findViewById(R.id.pinDot3))
        pinDots.add(findViewById(R.id.pinDot4))

        // Password
        tilUnlockPassword = findViewById(R.id.tilUnlockPassword)
        etUnlockPassword = findViewById(R.id.etUnlockPassword)
        btnUnlockPassword = findViewById(R.id.btnUnlockPassword)

        setupPinKeypad()
        setupPasswordActions()
        setupBiometricActions()
    }

    private fun setupModeUi() {
        val mode = secManager.getSecurityMode()
        layoutPinContainer.visibility = View.GONE
        layoutPasswordContainer.visibility = View.GONE
        layoutBiometricContainer.visibility = View.GONE
        layoutLockoutContainer.visibility = View.GONE

        if (secManager.isLockedOut()) {
            startLockoutCountdown(secManager.getRemainingLockoutSeconds())
            return
        }

        when (mode) {
            SecurityMode.PIN -> {
                txtUnlockSubtitle.setText(R.string.security_unlock_enter_pin)
                layoutPinContainer.visibility = View.VISIBLE
                updatePinDots()
            }
            SecurityMode.PASSWORD -> {
                txtUnlockSubtitle.setText(R.string.security_unlock_enter_password)
                layoutPasswordContainer.visibility = View.VISIBLE
            }
            SecurityMode.BIOMETRIC -> {
                txtUnlockSubtitle.setText(R.string.security_unlock_biometric_prompt)
                layoutBiometricContainer.visibility = View.VISIBLE
            }
            SecurityMode.NONE -> {
                unlockSuccess()
            }
        }
    }

    // ── PIN Handling ─────────────────────────────────────────────────────────

    private fun setupPinKeypad() {
        val keyIds = intArrayOf(
            R.id.btnKey0, R.id.btnKey1, R.id.btnKey2, R.id.btnKey3, R.id.btnKey4,
            R.id.btnKey5, R.id.btnKey6, R.id.btnKey7, R.id.btnKey8, R.id.btnKey9
        )

        for (id in keyIds) {
            val btn = findViewById<MaterialButton>(id)
            btn.setOnClickListener {
                if (pinDigits.length < 4 && !secManager.isLockedOut()) {
                    pinDigits.append(btn.text)
                    updatePinDots()
                    if (pinDigits.length == 4) {
                        verifyEnteredPin(pinDigits.toString())
                    }
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnKeyBackspace).setOnClickListener {
            if (pinDigits.isNotEmpty()) {
                pinDigits.deleteCharAt(pinDigits.length - 1)
                updatePinDots()
                txtUnlockError.visibility = View.GONE
            }
        }

        findViewById<MaterialButton>(R.id.btnPinForgot).setOnClickListener {
            openRecoveryFlow()
        }
    }

    private fun updatePinDots() {
        for (i in pinDots.indices) {
            pinDots[i].setBackgroundResource(
                if (i < pinDigits.length) R.drawable.bg_pin_dot_filled
                else R.drawable.bg_pin_dot_empty
            )
        }
    }

    private fun verifyEnteredPin(pin: String) {
        lifecycleScope.launch {
            val isValid = secManager.verifyStoredCredential(pin)
            if (isValid) {
                unlockSuccess()
            } else {
                handleAuthFailure(isPin = true)
            }
        }
    }

    // ── Password Handling ────────────────────────────────────────────────────

    private fun setupPasswordActions() {
        btnUnlockPassword.setOnClickListener {
            val pw = etUnlockPassword.text?.toString() ?: ""
            if (pw.isNotEmpty()) {
                verifyEnteredPassword(pw)
            }
        }

        etUnlockPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                btnUnlockPassword.performClick()
                true
            } else false
        }

        findViewById<MaterialButton>(R.id.btnPasswordForgot).setOnClickListener {
            openRecoveryFlow()
        }
    }

    private fun verifyEnteredPassword(password: String) {
        lifecycleScope.launch {
            val isValid = secManager.verifyStoredCredential(password)
            if (isValid) {
                unlockSuccess()
            } else {
                handleAuthFailure(isPin = false)
            }
        }
    }

    // ── Biometric Handling ───────────────────────────────────────────────────

    private fun setupBiometricActions() {
        findViewById<MaterialButton>(R.id.btnTriggerBiometric).setOnClickListener {
            triggerBiometricPrompt()
        }
        findViewById<MaterialButton>(R.id.btnDeviceLockFallback).setOnClickListener {
            triggerBiometricPrompt()
        }
    }

    private fun triggerBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_name))
            .setSubtitle(getString(R.string.security_unlock_biometric_prompt))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()

        BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlockSuccess()
            }
            override fun onAuthenticationError(code: Int, errString: CharSequence) {
                if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    txtUnlockError.text = errString
                    txtUnlockError.visibility = View.VISIBLE
                }
            }
        }).authenticate(promptInfo)
    }

    // ── Recovery Flow ────────────────────────────────────────────────────────

    private fun openRecoveryFlow() {
        SecurityDialogHelper.showRecoveryEntryDialog(
            activity = this,
            scope = lifecycleScope,
            onKeyVerified = {
                // Key verified: Reset security mode so user can set a new credential or enter UFM
                Toast.makeText(this, R.string.security_recovery_success, Toast.LENGTH_LONG).show()
                lifecycleScope.launch {
                    val currentMode = secManager.getSecurityMode()
                    if (currentMode == SecurityMode.PIN) {
                        SecurityDialogHelper.showSetPinFlow(
                            context = this@SecurityUnlockActivity,
                            scope = lifecycleScope,
                            onConfirmed = { unlockSuccess() },
                            onCancel = { unlockSuccess() }
                        )
                    } else {
                        SecurityDialogHelper.showSetPasswordFlow(
                            context = this@SecurityUnlockActivity,
                            scope = lifecycleScope,
                            onConfirmed = { unlockSuccess() },
                            onCancel = { unlockSuccess() }
                        )
                    }
                }
            },
            onCancel = {}
        )
    }

    // ── Failure & Lockout Handling ───────────────────────────────────────────

    private fun handleAuthFailure(isPin: Boolean) {
        val attempts = secManager.registerFailedAttempt()
        triggerHapticError()

        if (secManager.isLockedOut()) {
            startLockoutCountdown(secManager.getRemainingLockoutSeconds())
            return
        }

        val remaining = AppSecurityManager.MAX_FAILED_ATTEMPTS - attempts
        txtUnlockError.text = getString(
            if (isPin) R.string.security_unlock_invalid_pin else R.string.security_unlock_invalid_password,
            remaining
        )
        txtUnlockError.visibility = View.VISIBLE

        if (isPin) {
            pinDigits.clear()
            updatePinDots()
            val shake = AnimationUtils.loadAnimation(this, R.anim.shake_horizontal)
            findViewById<LinearLayout>(R.id.layoutPinDots).startAnimation(shake)
        } else {
            val shake = AnimationUtils.loadAnimation(this, R.anim.shake_horizontal)
            tilUnlockPassword.startAnimation(shake)
        }
    }

    private fun startLockoutCountdown(seconds: Long) {
        layoutPinContainer.visibility = View.GONE
        layoutPasswordContainer.visibility = View.GONE
        layoutBiometricContainer.visibility = View.GONE
        layoutLockoutContainer.visibility = View.VISIBLE
        txtUnlockError.visibility = View.GONE

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000L)
                val m = sec / 60
                val s = sec % 60
                txtLockoutTimer.text = String.format("%02d:%02d", m, s)
            }

            override fun onFinish() {
                secManager.resetFailedAttempts()
                setupModeUi()
            }
        }.start()
    }

    private fun triggerHapticError() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(120L)
                }
            }
        } catch (_: Exception) {}
    }

    private fun unlockSuccess() {
        secManager.setSessionUnlocked(true)
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            val view = currentFocus ?: findViewById(R.id.main)
            imm?.hideSoftInputFromWindow(view?.windowToken, 0)
        } catch (_: Exception) {}
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}
