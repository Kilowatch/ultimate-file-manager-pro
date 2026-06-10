package za.kilowatch.ultimatefilemanager.ui.policy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Policy Selection Screen.
 * Shows three options: Terms & Conditions, Privacy Policy, and Open Source Licenses.
 * Navigates to PolicyActivity or OssLicensesMenuActivity as appropriate.
 */
class PolicySelectionActivity : AppCompatActivity() {

    private lateinit var cardTerms: View
    private lateinit var cardPrivacy: View
    private lateinit var cardLicenses: View

    private var isTv = false

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PolicySelectionActivity::class.java))
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_policy_selection_tv)
        } else {
            setContentView(R.layout.activity_policy_selection)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        // Back navigation
        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        // Card clicks
        cardTerms = findViewById(R.id.cardTerms)
        cardPrivacy = findViewById(R.id.cardPrivacy)
        cardLicenses = findViewById(R.id.cardLicenses)

        cardTerms.setOnClickListener {
            PolicyActivity.startTerms(this)
        }

        cardPrivacy.setOnClickListener {
            PolicyActivity.startPrivacyPolicy(this)
        }

        cardLicenses.setOnClickListener {
            LicensesActivity.start(this)
        }

        // TV D-pad yellow focus is handled by XML state selectors.
    }
}

