package za.kilowatch.ultimatefilemanager.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * FOSS build override for AboutActivity.
 *
 * Extends the standard About screen with:
 *  - A "FOSS Build" badge on the version line
 *  - A Source Code link to the GitHub repository
 *  - A Report a Bug link to GitHub Issues
 *  - The GPL-3.0 notice (satisfies GPL-3.0 §4 user notification requirement)
 *
 * TODO: Replace the GitHub URL below with your real repository URL once published.
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        const val GITHUB_REPO_URL   = "https://github.com/Kilowatch/ultimate-file-manager-pro"
        const val GITHUB_ISSUES_URL = "https://github.com/Kilowatch/ultimate-file-manager-pro/issues"
    }

    private var isTv = false

    override fun attachBaseContext(newBase: android.content.Context) {
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
                if (hasFocus) btnBack.setBackgroundResource(R.drawable.bg_icon_circle_focused)
                else btnBack.setBackgroundResource(R.drawable.bg_icon_circle_accent)
            }
        }
        btnBack?.setOnClickListener { finish() }

        // Source Code button — links to GitHub repository
        val btnSourceCode = findViewById<android.view.View?>(R.id.btnSourceCode)
        btnSourceCode?.setOnClickListener { openUrl(GITHUB_REPO_URL) }

        // Report a Bug button — links to GitHub Issues
        val btnReportBug = findViewById<android.view.View?>(R.id.btnReportBug)
        btnReportBug?.setOnClickListener { openUrl(GITHUB_ISSUES_URL) }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
