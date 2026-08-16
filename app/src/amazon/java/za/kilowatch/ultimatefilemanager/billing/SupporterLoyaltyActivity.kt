package za.kilowatch.ultimatefilemanager.billing

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Amazon flavor SupporterLoyaltyActivity utilizing standard XML layouts.
 * Hides billing tiers unsupported on the Amazon Appstore.
 */
class SupporterLoyaltyActivity : AppCompatActivity() {

    private lateinit var amazonBillingManager: AmazonBillingManager

    private lateinit var btnEspresso: MaterialButton
    private lateinit var btnLatte: MaterialButton
    private lateinit var btnBeans: MaterialButton

    private lateinit var txtEspressoPrice: TextView
    private lateinit var txtLattePrice: TextView
    private lateinit var txtBeansPrice: TextView

    private lateinit var txtThankYou: TextView
    private var txtLoading: TextView? = null
    private var isTv = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            enableEdgeToEdge()
            setContentView(R.layout.activity_supporter_loyalty_tv)
            // Handle TV insets
            val root = findViewById<View>(R.id.tipJarRoot)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val tvPad = (27 * resources.displayMetrics.density).toInt()
                v.setPadding(
                    systemBars.left + tvPad, systemBars.top + tvPad,
                    systemBars.right + tvPad, systemBars.bottom + tvPad
                )
                insets
            }
        } else {
            enableEdgeToEdge()
            setContentView(R.layout.activity_supporter_loyalty)
            // Handle mobile insets
            val root = findViewById<View>(R.id.tipJarRoot)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(
                    systemBars.left, systemBars.top,
                    systemBars.right, systemBars.bottom
                )
                insets
            }
        }

        setupViews()

        if (BuildConfig.AMAZON_IAP_ENABLED) {
            txtLoading?.text = getString(R.string.tip_jar_loading_amazon)
            txtLoading?.visibility = View.VISIBLE
            // Keep buttons enabled & focusable by default so Fire TV D-pad navigation works immediately
            setButtonsEnabled(true)

            amazonBillingManager = AmazonBillingManager(
                context = this,
                onPurchaseSuccess = { sku ->
                    if (!isFinishing && !isDestroyed) {
                        handlePurchaseSuccess(sku)
                        showThankYou(sku)
                    }
                },
                onProductsLoaded = {
                    if (!isFinishing && !isDestroyed) {
                        txtLoading?.visibility = View.GONE
                        updateButtonPrices()
                        setButtonsEnabled(true)
                    }
                },
                onError = { msg ->
                    if (!isFinishing && !isDestroyed) {
                        txtLoading?.visibility = View.GONE
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            )
            amazonBillingManager.register(this)
            if (amazonBillingManager.hasCachedProducts()) {
                txtLoading?.visibility = View.GONE
                updateButtonPrices()
            }
        } else {
            setButtonsEnabled(false)
            txtLoading?.visibility = View.GONE
            txtThankYou.visibility = View.VISIBLE
            txtThankYou.text = getString(R.string.billing_unavailable_amazon_msg)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::amazonBillingManager.isInitialized && BuildConfig.AMAZON_IAP_ENABLED) {
            amazonBillingManager.onResume()
        }
    }

    private fun setupViews() {
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val yellowCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack.imageTintList = whiteCsl
            btnBack.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) yellowCsl else whiteCsl
                if (hasFocus) {
                    btnBack.setBackgroundResource(R.drawable.bg_icon_circle_focused)
                } else {
                    btnBack.setBackgroundResource(R.drawable.bg_icon_circle_accent)
                }
            }
        }

        // Hide rows not supported by Amazon (Ristretto and Cappuccino)
        findViewById<View>(R.id.rowRistretto)?.visibility = View.GONE
        findViewById<View>(R.id.dividerRistretto)?.visibility = View.GONE
        findViewById<View>(R.id.rowCappuccino)?.visibility = View.GONE
        findViewById<View>(R.id.dividerCappuccino)?.visibility = View.GONE

        // Hide global progress card (Amazon build does not use global progress)
        findViewById<View>(R.id.cardGlobalProgress)?.visibility = View.GONE

        // Bind Amazon billing buttons and text views
        btnEspresso = findViewById(R.id.btnEspresso)
        btnLatte = findViewById(R.id.btnLatte)
        btnBeans = findViewById(R.id.btnBeans)

        txtEspressoPrice = findViewById(R.id.txtEspressoPrice)
        txtLattePrice = findViewById(R.id.txtLattePrice)
        txtBeansPrice = findViewById(R.id.txtBeansPrice)

        txtThankYou = findViewById(R.id.txtThankYou)
        txtLoading = findViewById(R.id.txtTipLoading)

        // Setup click listeners
        btnEspresso.setOnClickListener { triggerPurchase(AmazonBillingManager.SKU_ESPRESSO) }
        btnLatte.setOnClickListener { triggerPurchase(AmazonBillingManager.SKU_LATTE) }
        btnBeans.setOnClickListener { triggerPurchase(AmazonBillingManager.SKU_BEANS) }

        // Setup TV focus listeners & navigation chain
        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(android.graphics.Color.WHITE)
            val yellowCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))

            btnBack.nextFocusDownId = R.id.btnEspresso
            btnEspresso.nextFocusUpId = R.id.btnBack
            btnEspresso.nextFocusDownId = R.id.btnLatte
            btnLatte.nextFocusUpId = R.id.btnEspresso
            btnLatte.nextFocusDownId = R.id.btnBeans
            btnBeans.nextFocusUpId = R.id.btnLatte

            listOf(btnEspresso, btnLatte, btnBeans).forEach { button ->
                button.isFocusable = true
                button.isClickable = true
                button.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                        button.setTextColor(yellowCsl)
                    } else {
                        button.backgroundTintList = null
                        button.setTextColor(whiteCsl)
                    }
                }
            }
        }
    }

    private fun triggerPurchase(sku: String) {
        if (::amazonBillingManager.isInitialized) {
            amazonBillingManager.purchase(this, sku)
        }
    }

    private fun updateButtonPrices() {
        fun priceFor(sku: String): String {
            return if (::amazonBillingManager.isInitialized) {
                amazonBillingManager.priceFor(sku) ?: ""
            } else {
                ""
            }
        }
        val espressoPrice = priceFor(AmazonBillingManager.SKU_ESPRESSO)
        val lattePrice = priceFor(AmazonBillingManager.SKU_LATTE)
        val beansPrice = priceFor(AmazonBillingManager.SKU_BEANS)
        if (espressoPrice.isNotEmpty()) txtEspressoPrice.text = espressoPrice
        if (lattePrice.isNotEmpty()) txtLattePrice.text = lattePrice
        if (beansPrice.isNotEmpty()) txtBeansPrice.text = beansPrice
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnEspresso.isEnabled = enabled
        btnLatte.isEnabled = enabled
        btnBeans.isEnabled = enabled
    }

    private fun handlePurchaseSuccess(sku: String) {
        val tipAmount = when (sku) {
            AmazonBillingManager.SKU_ESPRESSO -> 1
            AmazonBillingManager.SKU_LATTE    -> 5
            AmazonBillingManager.SKU_BEANS    -> 15
            else -> 0
        }
        if (tipAmount > 0) {
            LoyaltyPrefs.addTip(this, tipAmount)
            // Trigger auto-backup if enabled
            AutoBackupScheduler.runOnceNow(this)
        }
    }

    private fun showThankYou(sku: String) {
        val message = when (sku) {
            AmazonBillingManager.SKU_ESPRESSO -> getString(R.string.tip_jar_thanks_espresso)
            AmazonBillingManager.SKU_LATTE    -> getString(R.string.tip_jar_thanks_latte)
            AmazonBillingManager.SKU_BEANS    -> getString(R.string.tip_jar_thanks_beans)
            else                              -> getString(R.string.tip_jar_thanks)
        }
        txtThankYou.text = message
        txtThankYou.visibility = View.VISIBLE

        TipCelebrationHelper.celebrate(
            activity = this,
            sku = sku,
            rootView = window.decorView as android.view.ViewGroup,
            coffeeIcon = null
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::amazonBillingManager.isInitialized) {
            amazonBillingManager.destroy()
        }
    }
}
