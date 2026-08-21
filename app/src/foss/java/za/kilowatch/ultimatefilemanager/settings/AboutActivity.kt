package za.kilowatch.ultimatefilemanager.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.support.SupportActivity
import za.kilowatch.ultimatefilemanager.ui.policy.PolicySelectionActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * FOSS build override for AboutActivity.
 *
 * Extends the standard About screen with:
 *  - A "FOSS Build" badge on the version line
 *  - A Source Code link to the GitHub repository
 *  - A Report a Bug link to GitHub Issues
 *  - The GPL-3.0 notice (satisfies GPL-3.0 §4 user notification requirement)
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        const val GITHUB_REPO_URL   = "https://github.com/Kilowatch/ultimate-file-manager-pro"
        const val GITHUB_ISSUES_URL = "https://github.com/Kilowatch/ultimate-file-manager-pro/issues"
    }

    private var isTv = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            setContentView(R.layout.activity_about_tv)
        } else {
            setContentView(R.layout.activity_about)
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

        setupViews()
    }

    private fun setupViews() {
        // Version string — shows "1.4.7-FOSS" instead of "1.4.7-GOOGLE"
        val flavorStr   = getString(if (isTv) R.string.flavor_tv else R.string.flavor_mobile)
        val versionStr  = getString(R.string.about_version_format, BuildConfig.VERSION_NAME, flavorStr)
        val fossBadge   = " [FOSS]"

        val txtVersion  = findViewById<TextView>(R.id.txtVersion)
        txtVersion.text = versionStr + fossBadge

        // GPL-3.0 notice — satisfies GPL-3.0 §4 user notification requirement
        val txtLicense = findViewById<TextView?>(R.id.txtFossLicense)
        txtLicense?.text = getString(R.string.about_foss_gpl_notice, GITHUB_REPO_URL)

        // Back button
        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl  = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) yellowCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        // Support Email row
        findViewById<View?>(R.id.cardSupportEmail)?.setOnClickListener {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:support@kilowatch.co.za")
                putExtra(Intent.EXTRA_SUBJECT, "[UFM FOSS] General Inquiry")
            }
            try {
                startActivity(Intent.createChooser(emailIntent, getString(R.string.about_title)))
            } catch (e: Exception) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Support Email", "support@kilowatch.co.za")
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, R.string.about_email_copied, Toast.LENGTH_SHORT).show()
            }
        }

        // Help & Support row
        findViewById<View?>(R.id.cardHelpSupport)?.setOnClickListener {
            startActivity(Intent(this, SupportActivity::class.java))
        }

        // Rate & Review row
        findViewById<View?>(R.id.cardRateUs)?.setOnClickListener {
            startActivity(Intent(this, RateUsActivity::class.java))
        }

        // Legal & Policies row
        findViewById<View?>(R.id.cardLegal)?.setOnClickListener {
            startActivity(Intent(this, PolicySelectionActivity::class.java))
        }

        // Source Code button — links to GitHub repository
        val btnSourceCode = findViewById<View?>(R.id.btnSourceCode)
        btnSourceCode?.setOnClickListener { openUrl(GITHUB_REPO_URL) }

        // Report a Bug button — links to GitHub Issues
        val btnReportBug = findViewById<View?>(R.id.btnReportBug)
        btnReportBug?.setOnClickListener { openUrl(GITHUB_ISSUES_URL) }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
