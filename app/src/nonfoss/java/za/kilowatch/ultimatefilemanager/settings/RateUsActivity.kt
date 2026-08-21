package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.billing.SupporterLoyaltyActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ReviewHelper
import za.kilowatch.ultimatefilemanager.util.ReviewPrefs

/**
 * Screen that prompts the user to rate the app on Google Play / Amazon Appstore.
 */
class RateUsActivity : AppCompatActivity() {

    private var isTv = false

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, RateUsActivity::class.java))
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)
        
        if (isTv) {
            setContentView(R.layout.activity_rate_us_tv)
        } else {
            setContentView(R.layout.activity_rate_us)
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
        // Back Navigation
        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        
        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val yellowCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) yellowCsl else whiteCsl
            }
        }
        
        btnBack?.setOnClickListener { finish() }

        // Body text & Rate Us Button
        val txtBody = findViewById<TextView?>(R.id.txtRateUsBody)
        val btnRateUs = findViewById<MaterialButton>(R.id.btnRateUs)
        val isAmazon = DeviceUtils.isAmazonDevice(this)

        if (isAmazon) {
            txtBody?.text = getString(R.string.rate_us_screen_body_amazon)
            btnRateUs.text = getString(R.string.rate_us_button_amazon)
        } else {
            txtBody?.text = getString(R.string.rate_us_screen_body)
            btnRateUs.text = getString(R.string.rate_us_button_play)
        }

        btnRateUs.setOnClickListener {
            if (isAmazon && !BuildConfig.AMAZON_RATING_ENABLED) {
                // Flag is off — inform the user and do nothing else
                android.widget.Toast.makeText(
                    this,
                    R.string.rating_unavailable_amazon_msg,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                ReviewPrefs.onRateUsTapped(this)
                ReviewHelper.launchInAppReview(this)
            }
        }

        // Tip Jar Card / Button Click Handling
        val cardTipJar = findViewById<View?>(R.id.cardTipJar)
        val btnTipJar = findViewById<View?>(R.id.btnTipJar)

        val tipClickListener = View.OnClickListener {
            startActivity(Intent(this, SupporterLoyaltyActivity::class.java))
        }

        cardTipJar?.setOnClickListener(tipClickListener)
        btnTipJar?.setOnClickListener(tipClickListener)
    }
}
