package za.kilowatch.ultimatefilemanager.billing

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class SupporterLoyaltyActivity : AppCompatActivity() {

    // Google Play Billing manager — used on all Google Play/non-Amazon devices.
    private lateinit var billingManager: BillingManager

    private lateinit var btnEspresso: MaterialButton
    private lateinit var btnLatte: MaterialButton
    private lateinit var btnBeans: MaterialButton
    
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var gridStamps: GridLayout
    private lateinit var txtTippedTitle: TextView
    private lateinit var txtToGoTitle: TextView
    private lateinit var txtMemberTier: TextView
    private lateinit var txtTierBadge: TextView
    private lateinit var txtStatTotal: TextView
    private lateinit var txtStatCoffees: TextView
    private lateinit var txtStatGoalLabel: TextView
    private lateinit var txtStatGoal: TextView
    
    private lateinit var txtThankYou: TextView
    private lateinit var txtLoading: TextView
    private var coffeeIcon: ImageView? = null
    
    private var isTv = false
    private val GOAL_TARGET = 15

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
            // Handle mobile insets — pad the scroll container so content clears nav bar
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

        setupViews(isTv)

        // Google Play path
        initBilling()
        refreshLoyaltyUI()
    }

    private fun setupViews(isTv: Boolean) {
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

        btnEspresso  = findViewById(R.id.btnEspresso)
        btnLatte     = findViewById(R.id.btnLatte)
        btnBeans     = findViewById(R.id.btnBeans)
        
        txtThankYou  = findViewById(R.id.txtThankYou)
        txtLoading   = findViewById(R.id.txtTipLoading)
        coffeeIcon   = findViewById(R.id.imgCoffeeTv)
        if (coffeeIcon == null) {
            coffeeIcon = window.decorView.findViewWithTag("coffeeIcon")
        }

        progressBar      = findViewById(R.id.progressBar)
        gridStamps       = findViewById(R.id.gridStamps)
        txtTippedTitle   = findViewById(R.id.txtTippedTitle)
        txtToGoTitle     = findViewById(R.id.txtToGoTitle)
        txtMemberTier    = findViewById(R.id.txtMemberTier)
        txtTierBadge     = findViewById(R.id.txtTierBadge)
        txtStatTotal     = findViewById(R.id.txtStatTotal)
        txtStatCoffees   = findViewById(R.id.txtStatCoffees)
        txtStatGoalLabel = findViewById(R.id.txtStatGoalLabel)
        txtStatGoal      = findViewById(R.id.txtStatGoal)

        // Setup the 15 stamp image views in the grid
        setupStampsGrid()

        setButtonsEnabled(false)
        if (isTv) {
            applyTvFocusLogic(btnEspresso)
            applyTvFocusLogic(btnLatte)
            applyTvFocusLogic(btnBeans)
        }

        btnEspresso.setOnClickListener {
            billingManager.launchPurchaseFlow(this, BillingManager.SKU_ESPRESSO)
        }
        btnLatte.setOnClickListener {
            billingManager.launchPurchaseFlow(this, BillingManager.SKU_LATTE)
        }
        btnBeans.setOnClickListener {
            billingManager.launchPurchaseFlow(this, BillingManager.SKU_BEANS)
        }
    }
    
    private fun setupStampsGrid() {
        gridStamps.removeAllViews()
        // TV: tiny stamps so 3 rows fits neatly; mobile: standard size
        val stampSizeDp = if (isTv) 22 else 40
        val marginDp    = if (isTv) 2  else 4
        gridStamps.columnCount = 5

        val stampSize = (stampSizeDp * resources.displayMetrics.density).toInt()
        val margin    = (marginDp    * resources.displayMetrics.density).toInt()

        for (i in 0 until 15) {
            val iv = ImageView(this)
            val params = GridLayout.LayoutParams().apply {
                width  = stampSize
                height = stampSize
                setMargins(margin, margin, margin, margin)
            }
            iv.layoutParams = params
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setImageResource(R.drawable.ic_stamp_empty)
            gridStamps.addView(iv)
        }
    }

    private fun applyTvFocusLogic(button: MaterialButton) {
        val defaultTint  = ColorStateList.valueOf(getColor(R.color.ufm_primary))
        val focusTint    = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
        val defaultText  = getColor(R.color.tv_text_primary)
        val focusText    = getColor(R.color.tv_button_focused_yellow_text)

        button.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                button.backgroundTintList = focusTint
                button.setTextColor(focusText)
            } else {
                button.backgroundTintList = defaultTint
                button.setTextColor(defaultText)
            }
        }
    }

    private fun initBilling() {
        billingManager = BillingManager(
            context = this,
            onPurchaseSuccess = { sku -> 
                handlePurchaseSuccess(sku) 
                showThankYou(sku) 
            },
            onError = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        )

        billingManager.connect {
            txtLoading.visibility = View.GONE
            updateButtonLabels()
            setButtonsEnabled(true)
        }
    }

    private fun updateButtonLabels() {
        fun priceFor(sku: String): String {
            return billingManager.productDetails[sku]
                ?.oneTimePurchaseOfferDetails
                ?.formattedPrice
                ?: ""
        }

        val espressoSku   = BillingManager.SKU_ESPRESSO
        val latteSku      = BillingManager.SKU_LATTE
        val beansSku      = BillingManager.SKU_BEANS
        val espressoPrice = priceFor(espressoSku)
        val lattePrice    = priceFor(latteSku)
        val beansPrice    = priceFor(beansSku)

        btnEspresso.text = if (espressoPrice.isNotEmpty())
            getString(R.string.tip_jar_espresso_title) + getString(R.string.loyalty_price_separator_format, espressoPrice)
        else
            getString(R.string.tip_jar_espresso_title)

        btnLatte.text = if (lattePrice.isNotEmpty())
            getString(R.string.tip_jar_latte_title) + getString(R.string.loyalty_price_separator_format, lattePrice)
        else
            getString(R.string.tip_jar_latte_title)

        btnBeans.text = if (beansPrice.isNotEmpty())
            getString(R.string.tip_jar_beans_title) + getString(R.string.loyalty_price_separator_format, beansPrice)
        else
            getString(R.string.tip_jar_beans_title)
    }

    private fun handlePurchaseSuccess(sku: String) {
        val tipAmount = when (sku) {
            BillingManager.SKU_ESPRESSO -> 1
            BillingManager.SKU_LATTE    -> 5
            BillingManager.SKU_BEANS    -> 15
            else -> 0
        }
        
        if (tipAmount > 0) {
            LoyaltyPrefs.addTip(this, tipAmount)
            refreshLoyaltyUI()
        }
    }

    private fun refreshLoyaltyUI() {
        val totalTipped = LoyaltyPrefs.getTotalTipped(this)
        
        // Progress bar
        progressBar.progress = Math.min(totalTipped, GOAL_TARGET)
        if (totalTipped >= GOAL_TARGET) {
            progressBar.progressTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#FFD700")) // Gold
        }

        // Stamps
        for (i in 0 until 15) {
            val iv = gridStamps.getChildAt(i) as ImageView
            iv.setImageResource(
                if (i < totalTipped) R.drawable.ic_stamp_filled
                else R.drawable.ic_stamp_empty
            )
        }

        // Titles
        txtTippedTitle.text = getString(R.string.loyalty_tipped_format, totalTipped)
        
        if (totalTipped < GOAL_TARGET) {
            val toGo = GOAL_TARGET - totalTipped
            txtToGoTitle.text = getString(R.string.loyalty_to_go_format, toGo)
            txtStatGoalLabel.text = getString(R.string.loyalty_stat_to_goal_label)
            txtStatGoal.text = getString(R.string.loyalty_currency_format, toGo)
        } else {
            val beyond = totalTipped - GOAL_TARGET
            txtToGoTitle.text = getString(R.string.loyalty_beyond_goal_format, beyond)
            txtStatGoalLabel.text = getString(R.string.loyalty_stat_beyond_goal_label)
            txtStatGoal.text = getString(R.string.loyalty_currency_format, beyond)
        }
        
        txtStatTotal.text = getString(R.string.loyalty_currency_format, totalTipped)
        txtStatCoffees.text = getString(R.string.loyalty_value_format, totalTipped)

        // Tier Logic
        val tierName: String
        val badgeText: String
        when {
            totalTipped >= 15 -> {
                tierName = getString(R.string.loyalty_tier_legend)
                badgeText = getString(R.string.loyalty_tier_badge_format, 3)
            }
            totalTipped >= 5 -> {
                tierName = getString(R.string.loyalty_tier_champion)
                badgeText = getString(R.string.loyalty_tier_badge_format, 2)
            }
            totalTipped >= 1 -> {
                tierName = getString(R.string.loyalty_tier_supporter)
                badgeText = getString(R.string.loyalty_tier_badge_format, 1)
            }
            else -> {
                tierName = getString(R.string.loyalty_tier_free)
                badgeText = getString(R.string.loyalty_tier_badge_format, 0)
            }
        }
        txtMemberTier.text = tierName
        txtTierBadge.text = badgeText
    }

    private fun showThankYou(sku: String) {
        val message = when (sku) {
            BillingManager.SKU_ESPRESSO -> getString(R.string.tip_jar_thanks_espresso)
            BillingManager.SKU_LATTE    -> getString(R.string.tip_jar_thanks_latte)
            BillingManager.SKU_BEANS    -> getString(R.string.tip_jar_thanks_beans)
            else                        -> getString(R.string.tip_jar_thanks)
        }
        txtThankYou.text = message
        txtThankYou.visibility = View.VISIBLE

        val root = findViewById<ViewGroup>(R.id.tipJarRoot) ?: return
        TipCelebrationHelper.celebrate(
            activity   = this,
            sku        = sku,
            rootView   = root,
            coffeeIcon = coffeeIcon
        )
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnEspresso.isEnabled = enabled
        btnLatte.isEnabled    = enabled
        btnBeans.isEnabled    = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::billingManager.isInitialized) {
            billingManager.destroy()
        }
    }
}
