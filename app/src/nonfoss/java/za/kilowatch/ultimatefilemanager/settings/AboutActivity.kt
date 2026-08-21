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
 * Screen that displays app information, contact details, and acknowledgments.
 */
class AboutActivity : AppCompatActivity() {

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
        val flavorStr = getString(if (isTv) R.string.flavor_tv else R.string.flavor_mobile)
        val versionStr = getString(R.string.about_version_format, BuildConfig.VERSION_NAME, flavorStr)

        val txtVersion = findViewById<TextView>(R.id.txtVersion)
        txtVersion.text = versionStr

        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
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
                putExtra(Intent.EXTRA_SUBJECT, "[UFM] General Inquiry")
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
    }
}
