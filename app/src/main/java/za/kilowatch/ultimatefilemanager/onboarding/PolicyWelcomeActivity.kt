package za.kilowatch.ultimatefilemanager.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.ui.policy.PolicyActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class PolicyWelcomeActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var btnContinue: MaterialButton

    private val policyObserver = {
        if (!isFinishing && !isDestroyed) {
            if (PolicyAcceptanceManager.arePoliciesAccepted(this)) {
                startActivity(Intent(this, WelcomeActivity::class.java))
                finish()
            } else {
                refreshCardStates()
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        PolicyAcceptanceManager.addObserver(policyObserver)

        // Bypass directly to the regular onboarding/permissions if policies already accepted or running on FOSS
        if (BuildConfig.IS_FOSS) {
            PolicyAcceptanceManager.recordBothAccepted(this)
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        if (PolicyAcceptanceManager.arePoliciesAccepted(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_policy_welcome_tv)
        } else {
            setContentView(R.layout.activity_policy_welcome)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupViews()
    }

    override fun onResume() {
        super.onResume()
        refreshCardStates()
    }

    private fun setupViews() {
        btnContinue = findViewById(R.id.btnContinue)
        val cardTerms = findViewById<android.view.View>(R.id.cardTerms)
        val cardPrivacy = findViewById<android.view.View>(R.id.cardPrivacy)

        cardTerms.setOnClickListener {
            PolicyActivity.startTerms(this)
        }

        cardPrivacy.setOnClickListener {
            PolicyActivity.startPrivacyPolicy(this)
        }

        btnContinue.setOnClickListener {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
        }

        if (isTv) {
            val focusColors = za.kilowatch.ultimatefilemanager.util.TvFocusHelper.colors(this)
            val density = resources.displayMetrics.density
            val focusStrokePx = maxOf((2.5f * density).toInt(), focusColors.strokeWidthPx.toInt())
            val whiteText = getColor(R.color.tv_text_primary)
            val focusCsl = android.content.res.ColorStateList.valueOf(focusColors.fill)
            val glassCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_glass_white_10))

            btnContinue.backgroundTintList = glassCsl
            btnContinue.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    btnContinue.backgroundTintList = focusCsl
                    btnContinue.setTextColor(focusColors.onFill)
                    btnContinue.strokeColor = android.content.res.ColorStateList.valueOf(focusColors.stroke)
                    btnContinue.strokeWidth = focusStrokePx
                    btnContinue.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
                } else {
                    btnContinue.backgroundTintList = glassCsl
                    btnContinue.setTextColor(whiteText)
                    btnContinue.strokeWidth = 0
                    btnContinue.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }

            listOf(cardTerms, cardPrivacy).forEach { card ->
                card.setOnFocusChangeListener { v, hasFocus ->
                    za.kilowatch.ultimatefilemanager.util.TvFocusHelper.applyMarker(v, hasFocus)
                    v.animate().scaleX(if (hasFocus) 1.03f else 1.0f)
                        .scaleY(if (hasFocus) 1.03f else 1.0f)
                        .setDuration(120).start()
                }
            }

            cardTerms.post { cardTerms.requestFocus() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PolicyAcceptanceManager.removeObserver(policyObserver)
    }

    private fun refreshCardStates() {
        val termsTime = PolicyAcceptanceManager.getTermsAcceptedTime(this)
        val privacyTime = PolicyAcceptanceManager.getPrivacyAcceptedTime(this)

        val tvTermsStatus = findViewById<TextView>(R.id.tvTermsStatus)
        val tvPrivacyStatus = findViewById<TextView>(R.id.tvPrivacyStatus)

        val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        if (termsTime > 0) {
            val dateString = formatter.format(Date(termsTime))
            tvTermsStatus.text = getString(R.string.accepted_on_formatterformatdatetermstime, dateString)
            tvTermsStatus.setTextColor(ColorblindPalette.policyGreen(this))
        } else {
            tvTermsStatus.setText(R.string.required)
            if (isTv) {
                tvTermsStatus.setTextColor(ContextCompat.getColorStateList(this, R.color.selector_tv_policy_subtitle_required))
            } else {
                tvTermsStatus.setTextColor(ContextCompat.getColor(this, R.color.ufm_accent_light))
            }
        }

        if (privacyTime > 0) {
            val dateString = formatter.format(Date(privacyTime))
            tvPrivacyStatus.text = getString(R.string.accepted_on_formatterformatdateprivacytime, dateString)
            tvPrivacyStatus.setTextColor(ColorblindPalette.policyGreen(this))
        } else {
            tvPrivacyStatus.setText(R.string.required)
            if (isTv) {
                tvPrivacyStatus.setTextColor(ContextCompat.getColorStateList(this, R.color.selector_tv_policy_subtitle_required))
            } else {
                tvPrivacyStatus.setTextColor(ContextCompat.getColor(this, R.color.ufm_accent_light))
            }
        }

        val bothAccepted = (termsTime > 0 && privacyTime > 0)
        btnContinue.isEnabled = bothAccepted
        btnContinue.alpha = if (bothAccepted) 1f else 0.4f
    }
}
