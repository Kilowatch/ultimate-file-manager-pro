package za.kilowatch.ultimatefilemanager.onboarding

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.recycle.RecycleBinSettingsManager
import za.kilowatch.ultimatefilemanager.security.AppSecurityManager
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager
import za.kilowatch.ultimatefilemanager.settings.LeftHandedFabPreferenceManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.LongPressDurationManager
import za.kilowatch.ultimatefilemanager.settings.ScrollingTextPreferenceManager
import za.kilowatch.ultimatefilemanager.storage.MainMenuViewModeManager
import za.kilowatch.ultimatefilemanager.storage.QuickAccessManager
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.support.CrashReportManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Base Activity coordinator for all 10 dedicated onboarding setup screens.
 *
 * Implements:
 * - Edge-to-edge system window insets
 * - Locale wrapping via [LocaleHelper]
 * - TV layout & D-pad detection
 * - Standardized navigation (Back, Next, Contextual Skip)
 * - Progressive skip logic: commits prior user choices + applies defaults to remaining unvisited screens
 * - Reconfigure mode support (returning cleanly to SettingsActivity)
 */
abstract class BaseOnboardingStepActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECONFIGURE_MODE = "extra_reconfigure_mode"
    }

    protected val isTv: Boolean by lazy { DeviceUtils.isTvDevice(this) }
    protected var isReconfigureMode: Boolean = false

    abstract val stepIndex: Int
    abstract val stepTitleRes: Int

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    protected fun getActiveAccentColor(): Int {
        if (isTv) return getColor(R.color.tv_accent)
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
        return typedValue.data
    }

    protected fun getInactiveStrokeColor(): Int {
        return if (isTv) getColor(R.color.tv_glass_border) else getColor(R.color.mobile_glass_stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isReconfigureMode = intent.getBooleanExtra(EXTRA_RECONFIGURE_MODE, false)
    }

    protected fun setupCommonViews(rootView: View) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Step indicator (e.g. "Step 1 of 10" on mobile, "Step 1 of 8" on TV)
        val maxSteps = if (isTv) 8 else 10
        val displayIndex = calculateDisplayStepIndex(stepIndex)
        val txtStep = findViewById<TextView>(R.id.txtOnboardingStep)
        txtStep?.text = getString(R.string.onboarding_step_format, displayIndex, maxSteps)

        val txtTitle = findViewById<TextView>(R.id.txtStepTitle)
        if (txtTitle != null && stepTitleRes != 0) {
            txtTitle.setText(stepTitleRes)
        }

        // Action buttons
        val btnBack = findViewById<View>(R.id.btnOnboardingBack)
        if (stepIndex == 1) {
            btnBack?.visibility = View.GONE
        } else {
            btnBack?.visibility = View.VISIBLE
            btnBack?.setOnClickListener { navigateBack() }
        }

        val btnNext = findViewById<MaterialButton>(R.id.btnOnboardingNext)
        if (isLastStep()) {
            btnNext?.text = getString(R.string.onboarding_btn_start_browsing)
        } else {
            btnNext?.text = getString(R.string.onboarding_btn_next)
        }
        btnNext?.setOnClickListener { onNextClicked() }

        val btnSkip = findViewById<MaterialButton>(R.id.btnOnboardingSkip)
        if (stepIndex == 1) {
            btnSkip?.text = getString(R.string.onboarding_btn_skip_defaults)
        } else {
            btnSkip?.text = getString(R.string.onboarding_btn_skip_remaining)
        }
        btnSkip?.setOnClickListener { skipRemainingAndFinish() }
    }

    protected abstract fun onSaveCurrentSelection()

    protected open fun onNextClicked() {
        onSaveCurrentSelection()
        if (isLastStep()) {
            finishOnboardingSuccess()
        } else {
            val nextIntent = getNextStepIntent()
            if (nextIntent != null) {
                startActivity(nextIntent)
                overridePendingTransition(R.anim.ufm_slide_in_right, R.anim.ufm_slide_out_left)
                finish()
            } else {
                finishOnboardingSuccess()
            }
        }
    }

    protected fun navigateBack() {
        val prevIntent = getPreviousStepIntent()
        if (prevIntent != null) {
            startActivity(prevIntent)
            overridePendingTransition(R.anim.ufm_slide_in_left, R.anim.ufm_slide_out_right)
            finish()
        } else {
            finish()
            overridePendingTransition(R.anim.ufm_slide_in_left, R.anim.ufm_slide_out_right)
        }
    }

    /**
     * Commits all remaining unvisited settings using their recommended defaults,
     * marks onboarding complete, and transitions to the main app or back to Settings.
     */
    protected fun skipRemainingAndFinish() {
        // Save current step selection first
        onSaveCurrentSelection()

        // Apply recommended defaults to all subsequent unvisited steps
        if (stepIndex < 1) {
            val defaultMode = if (isTv) MainMenuViewModeManager.ViewMode.LIST else MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED
            MainMenuViewModeManager.saveViewMode(this, defaultMode)
            MainMenuViewModeManager.saveItemSize(this, MainMenuViewModeManager.ItemSize.MEDIUM)
            MainMenuViewModeManager.saveColumnCount(this, 3)
        }
        if (stepIndex < 2) FontSizeHelper.save(this, FontSizeHelper.FONT_NORMAL)
        if (stepIndex < 3) ScrollingTextPreferenceManager.setMode(this, ScrollingTextPreferenceManager.MODE_MARQUEE)
        if (stepIndex < 4) LongPressDurationManager.saveStep(this, LongPressDurationManager.DEFAULT_STEP)
        if (stepIndex < 5 && !isTv) LeftHandedFabPreferenceManager.setLeftHanded(this, false)
        if (stepIndex < 6 && !isTv) QuickAccessManager.setMode(this, QuickAccessManager.MODE_EDGE_MENU)
        if (stepIndex < 7) RecycleBinSettingsManager.setEnabled(this, false)
        if (stepIndex < 8) {
            HiddenFilesManager.init(this)
            HiddenFilesManager.isShowHiddenFilesEnabled = false
        }
        if (stepIndex < 10) CrashReportManager.setEnabled(this, true)

        finishOnboardingSuccess()
    }

    protected fun finishOnboardingSuccess() {
        if (isReconfigureMode) {
            finish()
        } else {
            PolicyAcceptanceManager.setOnboardingComplete(this, true)
            za.kilowatch.ultimatefilemanager.storage.LastLocationManager.recordStorageBrowser(this)
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    protected fun isSecureLockSupported(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km != null && (km.isKeyguardSecure || km.isDeviceSecure)
    }

    private fun isLastStep(): Boolean {
        return stepIndex == 10
    }

    private fun calculateDisplayStepIndex(rawIndex: Int): Int {
        if (!isTv) return rawIndex
        // On TV, steps 5 & 6 (FAB, Edge Drawer) are skipped
        return when {
            rawIndex <= 4 -> rawIndex
            rawIndex == 7 -> 5
            rawIndex == 8 -> 6
            rawIndex == 9 -> 7
            rawIndex == 10 -> 8
            else -> rawIndex
        }
    }

    private fun getNextStepIntent(): Intent? {
        val targetClass = when (stepIndex) {
            1 -> OnboardingFontSizeActivity::class.java
            2 -> OnboardingScrollingTextActivity::class.java
            3 -> OnboardingHoldDurationActivity::class.java
            4 -> if (isTv) OnboardingRecycleBinActivity::class.java else OnboardingLeftHandedFabActivity::class.java
            5 -> OnboardingEdgeDrawerActivity::class.java
            6 -> OnboardingRecycleBinActivity::class.java
            7 -> OnboardingHiddenFilesActivity::class.java
            8 -> if (isSecureLockSupported()) OnboardingAppLockActivity::class.java else OnboardingDiagnosticsActivity::class.java
            9 -> OnboardingDiagnosticsActivity::class.java
            else -> null
        } ?: return null

        return Intent(this, targetClass).apply {
            putExtra(EXTRA_RECONFIGURE_MODE, isReconfigureMode)
        }
    }

    private fun getPreviousStepIntent(): Intent? {
        val targetClass = when (stepIndex) {
            2 -> OnboardingViewDensityActivity::class.java
            3 -> OnboardingFontSizeActivity::class.java
            4 -> OnboardingScrollingTextActivity::class.java
            5 -> OnboardingHoldDurationActivity::class.java
            6 -> OnboardingLeftHandedFabActivity::class.java
            7 -> if (isTv) OnboardingHoldDurationActivity::class.java else OnboardingEdgeDrawerActivity::class.java
            8 -> OnboardingRecycleBinActivity::class.java
            9 -> OnboardingHiddenFilesActivity::class.java
            10 -> if (isSecureLockSupported()) OnboardingAppLockActivity::class.java else OnboardingHiddenFilesActivity::class.java
            else -> null
        } ?: return null

        return Intent(this, targetClass).apply {
            putExtra(EXTRA_RECONFIGURE_MODE, isReconfigureMode)
        }
    }
}
