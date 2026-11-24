package za.kilowatch.ultimatefilemanager.billing

import android.content.Context
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import java.util.concurrent.TimeUnit

class SupporterLoyaltyActivity : AppCompatActivity() {

    // Google Play Billing manager — used on all Google Play/non-Amazon devices.
    private lateinit var billingManager: BillingManager

    private lateinit var btnRistretto: MaterialButton
    private lateinit var btnEspresso: MaterialButton
    private lateinit var btnCappuccino: MaterialButton
    private lateinit var btnLatte: MaterialButton
    private lateinit var btnBeans: MaterialButton
    
    private lateinit var txtRistrettoPrice: TextView
    private lateinit var txtEspressoPrice: TextView
    private lateinit var txtCappuccinoPrice: TextView
    private lateinit var txtLattePrice: TextView
    private lateinit var txtBeansPrice: TextView
    
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
    
    private lateinit var progressGlobal: android.widget.ProgressBar
    private lateinit var txtGlobalPercent: TextView
    private lateinit var txtGlobalTitle: TextView
    private var cardGlobalProgress: View? = null



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

        if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(this)) {
            findViewById<View>(R.id.rowRistretto)?.visibility = View.GONE
            findViewById<View>(R.id.dividerRistretto)?.visibility = View.GONE
            findViewById<View>(R.id.rowCappuccino)?.visibility = View.GONE
            findViewById<View>(R.id.dividerCappuccino)?.visibility = View.GONE
            findViewById<View>(R.id.cardGlobalProgress)?.visibility = View.GONE

            setButtonsEnabled(false)
            txtThankYou.visibility = View.VISIBLE
            txtThankYou.text = getString(R.string.billing_unavailable_amazon_msg)
            return
        }

        // Google Play path
        initBilling()
        refreshLoyaltyUI()
        fetchGlobalProgress()

        if (!isTv && !za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(this)) {
            setupBadgesSection()
        }
    }

    private fun setupViews(isTv: Boolean) {
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val yellowCsl = ColorStateList.valueOf(ColorblindPalette.focusFillText(this))
            
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

        btnRistretto = findViewById(R.id.btnRistretto)
        btnEspresso  = findViewById(R.id.btnEspresso)
        btnCappuccino = findViewById(R.id.btnCappuccino)
        btnLatte     = findViewById(R.id.btnLatte)
        btnBeans     = findViewById(R.id.btnBeans)
        
        txtRistrettoPrice = findViewById(R.id.txtRistrettoPrice)
        txtEspressoPrice  = findViewById(R.id.txtEspressoPrice)
        txtCappuccinoPrice = findViewById(R.id.txtCappuccinoPrice)
        txtLattePrice     = findViewById(R.id.txtLattePrice)
        txtBeansPrice     = findViewById(R.id.txtBeansPrice)
        
        txtThankYou  = findViewById(R.id.txtThankYou)
        txtLoading   = findViewById(R.id.txtTipLoading)
        txtLoading.text = getString(R.string.tip_jar_loading_play)
        coffeeIcon   = findViewById(R.id.imgCoffeeTv)
        if (coffeeIcon == null) {
            coffeeIcon = window.decorView.findViewWithTag("coffeeIcon")
        }

        progressBar      = findViewById(R.id.progressBar)
        progressGlobal   = findViewById(R.id.progressGlobal)
        txtGlobalPercent = findViewById(R.id.txtGlobalPercent)
        txtGlobalTitle   = findViewById(R.id.txtGlobalTitle)
        cardGlobalProgress = findViewById(R.id.cardGlobalProgress)
        cardGlobalProgress?.visibility = View.GONE
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
            applyTvFocusLogic(btnRistretto)
            applyTvFocusLogic(btnEspresso)
            applyTvFocusLogic(btnCappuccino)
            applyTvFocusLogic(btnLatte)
            applyTvFocusLogic(btnBeans)
        }

        btnRistretto.setOnClickListener {
            billingManager.launchPurchaseFlow(this, BillingManager.SKU_RISTRETTO)
        }
        btnEspresso.setOnClickListener {
            billingManager.launchPurchaseFlow(this, BillingManager.SKU_ESPRESSO)
        }
        btnCappuccino.setOnClickListener {
            billingManager.launchPurchaseFlow(this, BillingManager.SKU_CAPPUCCINO)
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
        // TV: tiny stamps in a single horizontal row; mobile: standard size
        val stampSizeDp = if (isTv) 16 else 40
        val marginDp    = if (isTv) 1  else 4
        gridStamps.columnCount = if (isTv) 15 else 5

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
        val focusTint    = ColorStateList.valueOf(ColorblindPalette.focusFill(this))
        val focusText    = ColorblindPalette.focusFillText(this)

        button.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                button.backgroundTintList = focusTint
                button.setTextColor(focusText)
            } else {
                button.backgroundTintList = null
                button.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }

    private fun initBilling() {
        billingManager = BillingManager(
            context = this,
            onPurchaseSuccess = { sku ->
                handlePurchaseSuccess(sku)
                showThankYou(sku) { fetchGlobalProgress() }
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

        val ristrettoPrice = priceFor(BillingManager.SKU_RISTRETTO)
        val espressoPrice  = priceFor(BillingManager.SKU_ESPRESSO)
        val cappuccinoPrice = priceFor(BillingManager.SKU_CAPPUCCINO)
        val lattePrice     = priceFor(BillingManager.SKU_LATTE)
        val beansPrice     = priceFor(BillingManager.SKU_BEANS)

        txtRistrettoPrice.text = ristrettoPrice.ifEmpty { "—" }
        txtEspressoPrice.text  = espressoPrice.ifEmpty { "—" }
        txtCappuccinoPrice.text = cappuccinoPrice.ifEmpty { "—" }
        txtLattePrice.text     = lattePrice.ifEmpty { "—" }
        txtBeansPrice.text     = beansPrice.ifEmpty { "—" }
    }

    private fun handlePurchaseSuccess(sku: String) {
        val tipAmount = when (sku) {
            BillingManager.SKU_RISTRETTO  -> 1
            BillingManager.SKU_ESPRESSO   -> 3
            BillingManager.SKU_CAPPUCCINO -> 5
            BillingManager.SKU_LATTE      -> 10
            BillingManager.SKU_BEANS      -> 25
            else -> 0
        }
        
        if (tipAmount > 0) {
            LoyaltyPrefs.addTip(this, tipAmount)
            refreshLoyaltyUI()
            if (!isTv && !za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(this)) {
                setupBadgesSection()
            }
            // Trigger auto-backup if enabled
            AutoBackupScheduler.runOnceNow(this)
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

    // ─── Internet check ───────────────────────────────────────────────────────

    /**
     * Returns true if the device has an active internet connection.
     */
    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ─── Global progress fetch ────────────────────────────────────────────────

    /**
     * Fetch the global community progress from the server.
     * On success: updates the global progress card UI and caches the values.
     * On failure: card stays hidden (cardGlobalProgress is GONE).
     */
    private fun fetchGlobalProgress() {
        if (!isInternetAvailable()) {
            cardGlobalProgress?.visibility = View.GONE
            return
        }

        // Show cached values immediately if available, then fetch fresh
        val cachedPercent = LoyaltyPrefs.getCachedPercent(this)
        val cachedMonth = LoyaltyPrefs.getCachedMonth(this)
        if (cachedMonth.isNotEmpty()) {
            updateGlobalProgressBar(cachedPercent, cachedMonth)
            cardGlobalProgress?.visibility = View.VISIBLE
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val response = za.kilowatch.ultimatefilemanager.network.UfmHttpClient.getSync(
                    "https://www.kilowatch.co.za/UFM/api/progress.php",
                    timeoutSec = 10
                )
                val body = response.bodyString

                if (!response.isSuccessful || body.isBlank()) {
                    return@launch
                }

                val json = JSONObject(body)
                val percent = json.optInt("percent", 0).coerceIn(0, 100)
                val month = json.optString("month", "")

                if (month.isNotEmpty()) {
                    LoyaltyPrefs.saveCachedProgress(this@SupporterLoyaltyActivity, percent, month)
                    withContext(Dispatchers.Main) {
                        updateGlobalProgressBar(percent, month)
                        cardGlobalProgress?.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) {
                // Network error — card stays hidden or shows cached values already set
            }
        }
    }

    /**
     * Update the global progress card UI with the given values.
     * Called on the main thread.
     */
    private fun updateGlobalProgressBar(percent: Int, month: String) {
        progressGlobal.progress = percent
        txtGlobalPercent.text = getString(R.string.loyalty_value_format, percent) + "%"
        val raw = getString(R.string.tip_jar_progress_title_format, month)
        val spannable = android.text.SpannableString(raw)
        val accentColor = androidx.core.content.ContextCompat.getColor(this, R.color.tile_tip_jar_accent)
        val start = raw.indexOf(month)
        if (start >= 0) {
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(accentColor),
                start,
                start + month.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        txtGlobalTitle.text = spannable
    }

    private fun showThankYou(sku: String, onDismissed: (() -> Unit)? = null) {
        val message = when (sku) {
            BillingManager.SKU_RISTRETTO  -> getString(R.string.tip_jar_thanks_ristretto)
            BillingManager.SKU_ESPRESSO   -> getString(R.string.tip_jar_thanks_espresso)
            BillingManager.SKU_CAPPUCCINO -> getString(R.string.tip_jar_thanks_cappuccino)
            BillingManager.SKU_LATTE      -> getString(R.string.tip_jar_thanks_latte)
            BillingManager.SKU_BEANS      -> getString(R.string.tip_jar_thanks_beans)
            else                          -> getString(R.string.tip_jar_thanks)
        }
        txtThankYou.text = message
        txtThankYou.visibility = View.VISIBLE

        val root = findViewById<ViewGroup>(R.id.tipJarRoot) ?: return
        TipCelebrationHelper.celebrate(
            activity   = this,
            sku        = sku,
            rootView   = root,
            coffeeIcon = coffeeIcon,
            onDismissed = onDismissed
        )
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnRistretto.isEnabled  = enabled
        btnEspresso.isEnabled   = enabled
        btnCappuccino.isEnabled = enabled
        btnLatte.isEnabled      = enabled
        btnBeans.isEnabled      = enabled
    }

    private fun setupBadgesSection() {
        val cardBadges = findViewById<View>(R.id.cardBadgesSection) ?: return
        val container = findViewById<ViewGroup>(R.id.containerBadgesList) ?: return
        val imgHeader = findViewById<ImageView>(R.id.imgActiveBadgeHeader)
        cardBadges.visibility = View.VISIBLE

        populateBadgesList(container, imgHeader)
    }

    private fun populateBadgesList(container: ViewGroup, imgHeader: ImageView?) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val selectedPoints = BadgeIconManager.getSelectedBadgePoints(this)
        val totalPoints = BadgeIconManager.getTotalTippedPoints(this)

        if (imgHeader != null) {
            imgHeader.setImageResource(R.drawable.ic_ribbon_medal)
        }

        // 1. Default App Icon Option (Restores original launcher icon)
        val defaultView = inflater.inflate(R.layout.item_supporter_badge, container, false)
        val cardDefault = defaultView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardBadgeItem)
        val layoutDefaultContainer = defaultView.findViewById<View>(R.id.layoutDefaultIconContainer)
        val imgDefaultSquare = defaultView.findViewById<ImageView>(R.id.imgBadgeSquare)
        val imgDefaultCutout = defaultView.findViewById<ImageView>(R.id.imgBadgeCutout)
        val txtDefaultTitle = defaultView.findViewById<TextView>(R.id.txtBadgeTitle)
        val txtDefaultDesc = defaultView.findViewById<TextView>(R.id.txtBadgeDesc)
        val txtDefaultPoints = defaultView.findViewById<TextView>(R.id.txtBadgePoints)
        val btnDefaultAction = defaultView.findViewById<MaterialButton>(R.id.btnBadgeAction)
        val dividerDefault = defaultView.findViewById<View>(R.id.dividerRow)

        dividerDefault.visibility = View.GONE
        layoutDefaultContainer.visibility = View.VISIBLE
        imgDefaultCutout.visibility = View.GONE
        imgDefaultSquare.setImageResource(R.mipmap.ic_launcher)
        txtDefaultTitle.setText(R.string.badge_default_icon_title)
        txtDefaultDesc.setText(R.string.badge_default_icon_desc)
        txtDefaultPoints.text = getString(R.string.badge_btn_apply)

        val isDefaultActive = (selectedPoints == null || selectedPoints == 0)
        if (isDefaultActive) {
            btnDefaultAction.text = getString(R.string.badge_btn_active)
            btnDefaultAction.setIconResource(R.drawable.ic_check_bold)
            btnDefaultAction.iconTint = ColorStateList.valueOf(android.graphics.Color.WHITE)
            btnDefaultAction.setTextColor(android.graphics.Color.WHITE)
            btnDefaultAction.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#10B981"))
            btnDefaultAction.strokeWidth = 0
            cardDefault.setCardBackgroundColor(android.graphics.Color.parseColor("#1410B981"))
        } else {
            btnDefaultAction.text = getString(R.string.badge_btn_apply)
            btnDefaultAction.setIconResource(R.drawable.ic_check_bold)
            btnDefaultAction.iconTint = ColorStateList.valueOf(android.graphics.Color.WHITE)
            btnDefaultAction.setTextColor(android.graphics.Color.WHITE)
            btnDefaultAction.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#00B4D8"))
            btnDefaultAction.strokeWidth = 0
            cardDefault.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        btnDefaultAction.setOnClickListener { defaultView.performClick() }

        defaultView.setOnClickListener {
            if (!isDefaultActive) {
                showDefaultIconConfirmationDialog()
            }
        }
        container.addView(defaultView)

        // 2. 10 Milestone Badges in ascending order (5 points first, 1000 points last)
        for (badge in BadgeIconManager.BADGES) {
            val itemView = inflater.inflate(R.layout.item_supporter_badge, container, false)
            val cardItem = itemView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardBadgeItem)
            val layoutContainer = itemView.findViewById<View>(R.id.layoutDefaultIconContainer)
            val imgCutout = itemView.findViewById<ImageView>(R.id.imgBadgeCutout)
            val txtTitle = itemView.findViewById<TextView>(R.id.txtBadgeTitle)
            val txtDesc = itemView.findViewById<TextView>(R.id.txtBadgeDesc)
            val txtPoints = itemView.findViewById<TextView>(R.id.txtBadgePoints)
            val btnAction = itemView.findViewById<MaterialButton>(R.id.btnBadgeAction)
            val dividerRow = itemView.findViewById<View>(R.id.dividerRow)

            dividerRow.visibility = View.VISIBLE

            val isUnlocked = BadgeIconManager.isBadgeUnlocked(this, badge.points)
            val isActive = (selectedPoints == badge.points)

            // Setup View for Cutout Badge
            layoutContainer.visibility = View.GONE
            imgCutout.visibility = View.VISIBLE
            imgCutout.setImageResource(badge.cutoutRes)
            txtTitle.setText(badge.titleRes)
            txtDesc.setText(badge.descRes)
            txtPoints.text = getString(R.string.badge_points_format, badge.points)

            if (isActive) {
                btnAction.text = getString(R.string.badge_btn_active)
                btnAction.setIconResource(R.drawable.ic_check_bold)
                btnAction.iconTint = ColorStateList.valueOf(android.graphics.Color.WHITE)
                btnAction.setTextColor(android.graphics.Color.WHITE)
                btnAction.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#10B981"))
                btnAction.strokeWidth = 0
                cardItem.setCardBackgroundColor(android.graphics.Color.parseColor("#1410B981"))
            } else if (isUnlocked) {
                btnAction.text = getString(R.string.badge_btn_apply)
                btnAction.setIconResource(R.drawable.ic_check_bold)
                btnAction.iconTint = ColorStateList.valueOf(android.graphics.Color.WHITE)
                btnAction.setTextColor(android.graphics.Color.WHITE)
                btnAction.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#00B4D8"))
                btnAction.strokeWidth = 0
                cardItem.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            } else {
                btnAction.text = getString(R.string.badge_btn_locked)
                btnAction.setIconResource(R.drawable.ic_lock)
                val lockColor = android.graphics.Color.parseColor("#94A3B8")
                btnAction.iconTint = ColorStateList.valueOf(lockColor)
                btnAction.setTextColor(lockColor)
                btnAction.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#182234"))
                btnAction.strokeColor = ColorStateList.valueOf(android.graphics.Color.parseColor("#334155"))
                btnAction.strokeWidth = (1 * resources.displayMetrics.density).toInt()
                cardItem.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            btnAction.setOnClickListener { itemView.performClick() }

            if (isUnlocked) {
                itemView.alpha = 1.0f
                itemView.setOnClickListener {
                    if (BadgeIconManager.getSelectedBadgePoints(this) != badge.points) {
                        showBadgeApplyConfirmationDialog(badge)
                    }
                }
            } else {
                itemView.alpha = 0.85f
                itemView.setOnClickListener {
                    showBadgeLockedDialog(badge)
                }
            }

            container.addView(itemView)
        }
    }

    private fun showBadgeLockedDialog(badge: BadgeItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_badge_locked, null)
        val imgPreview = dialogView.findViewById<ImageView>(R.id.imgDialogBadgeLockedSquare)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogBadgeLockedTitle)
        val txtMsg = dialogView.findViewById<TextView>(R.id.txtDialogBadgeLockedMsg)
        val btnFuel = dialogView.findViewById<MaterialButton>(R.id.btnDialogLockedFuel)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnDialogLockedCancel)

        val totalPoints = BadgeIconManager.getTotalTippedPoints(this)
        val needed = (badge.points - totalPoints).coerceAtLeast(0)

        imgPreview.setImageResource(badge.iconRes)
        txtTitle.setText(badge.titleRes)
        txtMsg.text = getString(R.string.badge_locked_dialog_msg, badge.points, totalPoints, needed)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        btnFuel.setOnClickListener {
            dialog.dismiss()
            findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollTipJar)?.smoothScrollTo(0, 0)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showBadgeApplyConfirmationDialog(badge: BadgeItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_badge_icon_updated, null)
        val imgPreview = dialogView.findViewById<ImageView>(R.id.imgDialogBadgePreview)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtDialogSubtitle)
        val txtName = dialogView.findViewById<TextView>(R.id.txtDialogBadgeName)
        val txtNotice = dialogView.findViewById<TextView>(R.id.txtDialogApplyNotice)
        val btnApply = dialogView.findViewById<MaterialButton>(R.id.btnDialogApply)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnDialogCancel)

        imgPreview.setImageResource(badge.iconRes)
        txtTitle.setText(R.string.badge_apply_dialog_title)
        txtSubtitle.setText(R.string.badge_apply_dialog_subtitle)
        txtName.setText(badge.titleRes)
        txtNotice.setText(R.string.badge_apply_dialog_notice)
        btnApply.setText(R.string.badge_apply_dialog_btn_apply)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        btnApply.setOnClickListener {
            dialog.dismiss()
            BadgeIconManager.setSelectedBadge(this, badge.points)
            Toast.makeText(
                this,
                getString(R.string.badge_applied_toast, getString(badge.titleRes)),
                Toast.LENGTH_LONG
            ).show()
            finishAffinity()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDefaultIconConfirmationDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_badge_icon_updated, null)
        val imgPreview = dialogView.findViewById<ImageView>(R.id.imgDialogBadgePreview)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtDialogSubtitle)
        val txtName = dialogView.findViewById<TextView>(R.id.txtDialogBadgeName)
        val txtNotice = dialogView.findViewById<TextView>(R.id.txtDialogApplyNotice)
        val btnApply = dialogView.findViewById<MaterialButton>(R.id.btnDialogApply)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnDialogCancel)

        imgPreview.setImageResource(R.mipmap.ic_launcher)
        txtTitle.setText(R.string.badge_default_dialog_title)
        txtSubtitle.setText(R.string.badge_default_dialog_subtitle)
        txtName.setText(R.string.badge_default_icon_title)
        txtNotice.setText(R.string.badge_default_dialog_notice)
        btnApply.setText(R.string.badge_default_dialog_btn_restore)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        btnApply.setOnClickListener {
            dialog.dismiss()
            BadgeIconManager.setSelectedBadge(this, null)
            Toast.makeText(this, R.string.badge_reset_toast, Toast.LENGTH_LONG).show()
            finishAffinity()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::billingManager.isInitialized) {
            billingManager.destroy()
        }
    }
}
