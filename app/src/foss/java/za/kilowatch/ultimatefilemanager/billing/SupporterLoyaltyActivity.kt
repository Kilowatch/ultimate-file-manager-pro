package za.kilowatch.ultimatefilemanager.billing

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper

/**
 * FOSS build replacement for SupporterLoyaltyActivity.
 *
 * Google Play Billing and Amazon IAP are not available in the FOSS build.
 * This activity shows a simple donation screen linking to external platforms instead.
 *
 * TODO: Replace the placeholder URLs below with your real donation page URLs.
 */
class SupporterLoyaltyActivity : AppCompatActivity() {

    // TODO: Replace these with your real donation URLs once set up.
    companion object {
        const val KOFI_URL             = "https://ko-fi.com/kilowatch"
        const val GITHUB_SPONSORS_URL  = "https://github.com/sponsors/Kilowatch"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_supporter_loyalty_foss)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        // Version label
        val txtVersion = findViewById<TextView?>(R.id.txtFossVersion)
        txtVersion?.text = getString(R.string.foss_donation_version_format, android.os.Build.VERSION.SDK_INT)

        // Back button
        findViewById<android.widget.ImageView?>(R.id.btnBack)?.setOnClickListener { finish() }

        // Ko-fi button
        findViewById<MaterialButton?>(R.id.btnKofi)?.setOnClickListener {
            openUrl(KOFI_URL)
        }

        // GitHub Sponsors button
        findViewById<MaterialButton?>(R.id.btnGithubSponsors)?.setOnClickListener {
            openUrl(GITHUB_SPONSORS_URL)
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
