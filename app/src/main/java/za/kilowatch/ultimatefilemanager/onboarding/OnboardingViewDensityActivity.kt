package za.kilowatch.ultimatefilemanager.onboarding

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.MainMenuViewModeManager
import za.kilowatch.ultimatefilemanager.storage.MainMenuViewModeManager.ItemSize
import za.kilowatch.ultimatefilemanager.storage.MainMenuViewModeManager.ViewMode

/**
 * Onboarding Step 1: Main Menu Layout.
 * Styled after template.jpeg:
 * - Top Live Preview with LIVE badge
 * - "View Mode" section with 3 horizontal cards (Categorized, List, Grid) + "✓ Selected" pill badges
 * - Contextual settings sub-section with Compact / Comfortable spacing cards and Expand Categories toggle
 */
class OnboardingViewDensityActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 1
    override val stepTitleRes: Int = R.string.onboarding_view_density_title

    private var selectedMode: ViewMode = ViewMode.MODERN_CATEGORIZED
    private var selectedItemSize: ItemSize = ItemSize.MEDIUM
    private var selectedColumnCount: Int = 3
    private var isExpanded: Boolean = true

    // View Mode Cards & Badges
    private var cardModern: MaterialCardView? = null
    private var badgeModernSelected: TextView? = null
    private var txtModernSub: TextView? = null

    private lateinit var cardList: MaterialCardView
    private lateinit var badgeListSelected: TextView
    private lateinit var txtListSub: TextView

    private lateinit var cardGrid: MaterialCardView
    private lateinit var badgeGridSelected: TextView
    private lateinit var txtGridSub: TextView

    // Sub-options sections
    private var layoutCategorizedSettings: View? = null
    private var cardSpacingCompact: MaterialCardView? = null
    private var cardSpacingComfortable: MaterialCardView? = null
    private var cardExpandCategories: MaterialCardView? = null
    private var switchExpandCategories: MaterialSwitch? = null

    private lateinit var layoutListSettings: View
    private lateinit var cardListCompact: MaterialCardView
    private lateinit var cardListComfortable: MaterialCardView

    private lateinit var layoutGridSettings: View
    private lateinit var cardColumns3: MaterialCardView
    private lateinit var cardColumns4: MaterialCardView

    // Preview containers
    private var previewModernContainer: View? = null
    private lateinit var previewListContainer: View
    private lateinit var previewGridContainer: View
    private lateinit var previewGridRow3: View
    private lateinit var previewGridRow4: View

    // Modern preview inner elements (Mobile)
    private var previewModernChild1: View? = null
    private var previewModernChild2: View? = null
    private var previewModernDivider: View? = null
    private var iconFrameModern1: View? = null
    private var iconFrameModern2: View? = null
    private var imgStorageChevron: ImageView? = null

    // List preview inner elements
    private lateinit var previewListRow1: View
    private lateinit var previewListRow2: View
    private lateinit var previewIconFrameList1: View
    private lateinit var previewIconFrameList2: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_view_density_tv else R.layout.activity_onboarding_view_density)
        setupCommonViews(findViewById(R.id.main))

        // View Mode Cards
        cardModern = findViewById(R.id.cardOptionModern)
        badgeModernSelected = findViewById(R.id.badgeModernSelected)
        txtModernSub = findViewById(R.id.txtModernSub)

        cardList = findViewById(R.id.cardOptionList)
        badgeListSelected = findViewById(R.id.badgeListSelected)
        txtListSub = findViewById(R.id.txtListSub)

        cardGrid = findViewById(R.id.cardOptionGrid)
        badgeGridSelected = findViewById(R.id.badgeGridSelected)
        txtGridSub = findViewById(R.id.txtGridSub)

        // Categorized Settings (Mobile only)
        layoutCategorizedSettings = findViewById(R.id.layoutCategorizedSettings)
        cardSpacingCompact = findViewById(R.id.cardSpacingCompact)
        cardSpacingComfortable = findViewById(R.id.cardSpacingComfortable)
        cardExpandCategories = findViewById(R.id.cardExpandCategories)
        switchExpandCategories = findViewById(R.id.switchExpandCategories)

        // List Settings
        layoutListSettings = findViewById(R.id.layoutListSettings)
        cardListCompact = findViewById(R.id.cardListCompact)
        cardListComfortable = findViewById(R.id.cardListComfortable)

        // Grid Settings
        layoutGridSettings = findViewById(R.id.layoutGridSettings)
        cardColumns3 = findViewById(R.id.cardColumns3)
        cardColumns4 = findViewById(R.id.cardColumns4)

        // Preview containers
        previewModernContainer = findViewById(R.id.previewModernContainer)
        previewListContainer = findViewById(R.id.previewListContainer)
        previewGridContainer = findViewById(R.id.previewGridContainer)
        previewGridRow3 = findViewById(R.id.previewGridRow3)
        previewGridRow4 = findViewById(R.id.previewGridRow4)

        // Modern preview inner elements (Mobile)
        previewModernChild1 = findViewById(R.id.previewModernChild1)
        previewModernChild2 = findViewById(R.id.previewModernChild2)
        previewModernDivider = findViewById(R.id.previewModernDivider)
        iconFrameModern1 = findViewById(R.id.iconFrameModern1)
        iconFrameModern2 = findViewById(R.id.iconFrameModern2)
        imgStorageChevron = findViewById(R.id.imgStorageChevron)

        // List preview inner elements
        previewListRow1 = findViewById(R.id.previewListRow1)
        previewListRow2 = findViewById(R.id.previewListRow2)
        previewIconFrameList1 = findViewById(R.id.previewIconFrameList1)
        previewIconFrameList2 = findViewById(R.id.previewIconFrameList2)

        // Read or set defaults
        val defaultMode = if (isTv) ViewMode.LIST else ViewMode.MODERN_CATEGORIZED
        if (isReconfigureMode) {
            val loadedMode = MainMenuViewModeManager.loadViewMode(this)
            selectedMode = if (isTv && loadedMode == ViewMode.MODERN_CATEGORIZED) ViewMode.LIST else loadedMode
            selectedItemSize = MainMenuViewModeManager.loadItemSize(this)
            selectedColumnCount = MainMenuViewModeManager.loadColumnCount(this)
            isExpanded = MainMenuViewModeManager.isCategoryExpanded(this, MainMenuViewModeManager.CATEGORY_STORAGE)
        } else {
            selectedMode = defaultMode
            selectedItemSize = ItemSize.MEDIUM
            selectedColumnCount = 3
            isExpanded = true
        }

        switchExpandCategories?.isChecked = isExpanded

        // Listeners for View Mode Cards
        cardModern?.setOnClickListener { selectMode(ViewMode.MODERN_CATEGORIZED) }
        cardList.setOnClickListener { selectMode(ViewMode.LIST) }
        cardGrid.setOnClickListener { selectMode(ViewMode.GRID) }

        // Listeners for Categorized Spacing
        cardSpacingCompact?.setOnClickListener { selectItemSize(ItemSize.SMALL) }
        cardSpacingComfortable?.setOnClickListener { selectItemSize(ItemSize.MEDIUM) }

        // Expand Categories toggle
        cardExpandCategories?.setOnClickListener {
            switchExpandCategories?.let { sw ->
                sw.isChecked = !sw.isChecked
            }
        }
        switchExpandCategories?.setOnCheckedChangeListener { _, isChecked ->
            isExpanded = isChecked
            updatePreview()
        }

        // Listeners for List Spacing
        cardListCompact.setOnClickListener { selectItemSize(ItemSize.SMALL) }
        cardListComfortable.setOnClickListener { selectItemSize(ItemSize.MEDIUM) }

        // Listeners for Grid Columns
        cardColumns3.setOnClickListener { selectColumnCount(3) }
        cardColumns4.setOnClickListener { selectColumnCount(4) }

        if (isTv) {
            setupTvOptionFocus(cardList)
            setupTvOptionFocus(cardGrid)
            setupTvOptionFocus(cardListCompact)
            setupTvOptionFocus(cardListComfortable)
            setupTvOptionFocus(cardColumns3)
            setupTvOptionFocus(cardColumns4)
        }

        updateUI()
    }

    private fun selectMode(mode: ViewMode) {
        selectedMode = mode
        updateUI()
    }

    private fun selectItemSize(size: ItemSize) {
        selectedItemSize = size
        updateUI()
    }

    private fun selectColumnCount(count: Int) {
        selectedColumnCount = count
        updateUI()
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        // 1. Update View Mode cards
        val isModern = (selectedMode == ViewMode.MODERN_CATEGORIZED)
        val isList = (selectedMode == ViewMode.LIST)
        val isGrid = (selectedMode == ViewMode.GRID)

        cardModern?.strokeWidth = if (isModern) dpToPx(2) else dpToPx(1)
        cardModern?.strokeColor = if (isModern) activeColor else inactiveStroke
        badgeModernSelected?.visibility = if (isModern) View.VISIBLE else View.GONE
        txtModernSub?.visibility = if (isModern) View.GONE else View.VISIBLE

        cardList.strokeWidth = if (isList) dpToPx(2) else dpToPx(1)
        cardList.strokeColor = if (isList) activeColor else inactiveStroke
        badgeListSelected.visibility = if (isList) View.VISIBLE else View.GONE
        txtListSub.visibility = if (isList) View.GONE else View.VISIBLE

        cardGrid.strokeWidth = if (isGrid) dpToPx(2) else dpToPx(1)
        cardGrid.strokeColor = if (isGrid) activeColor else inactiveStroke
        badgeGridSelected.visibility = if (isGrid) View.VISIBLE else View.GONE
        txtGridSub.visibility = if (isGrid) View.GONE else View.VISIBLE

        // 2. Update sub-options visibility
        layoutCategorizedSettings?.visibility = if (isModern) View.VISIBLE else View.GONE
        layoutListSettings.visibility = if (isList) View.VISIBLE else View.GONE
        layoutGridSettings.visibility = if (isGrid) View.VISIBLE else View.GONE

        // 3. Update Spacing cards (Compact vs Comfortable)
        val isCompact = (selectedItemSize == ItemSize.SMALL)
        val isComfortable = (selectedItemSize == ItemSize.MEDIUM || selectedItemSize == ItemSize.LARGE)

        cardSpacingCompact?.strokeWidth = if (isCompact) dpToPx(2) else dpToPx(1)
        cardSpacingCompact?.strokeColor = if (isCompact) activeColor else inactiveStroke

        cardSpacingComfortable?.strokeWidth = if (isComfortable) dpToPx(2) else dpToPx(1)
        cardSpacingComfortable?.strokeColor = if (isComfortable) activeColor else inactiveStroke

        cardListCompact.strokeWidth = if (isCompact) dpToPx(2) else dpToPx(1)
        cardListCompact.strokeColor = if (isCompact) activeColor else inactiveStroke

        cardListComfortable.strokeWidth = if (isComfortable) dpToPx(2) else dpToPx(1)
        cardListComfortable.strokeColor = if (isComfortable) activeColor else inactiveStroke

        // 4. Update Grid Column cards (3 vs 4)
        val isCol3 = (selectedColumnCount == 3)
        val isCol4 = (selectedColumnCount == 4)

        cardColumns3.strokeWidth = if (isCol3) dpToPx(2) else dpToPx(1)
        cardColumns3.strokeColor = if (isCol3) activeColor else inactiveStroke

        cardColumns4.strokeWidth = if (isCol4) dpToPx(2) else dpToPx(1)
        cardColumns4.strokeColor = if (isCol4) activeColor else inactiveStroke

        // 5. Update Live Preview
        updatePreview()
    }

    private fun updatePreview() {
        val isModern = (selectedMode == ViewMode.MODERN_CATEGORIZED)
        val isList = (selectedMode == ViewMode.LIST)
        val isGrid = (selectedMode == ViewMode.GRID)

        // 1. Container visibility
        previewModernContainer?.visibility = if (isModern) View.VISIBLE else View.GONE
        previewListContainer.visibility = if (isList) View.VISIBLE else View.GONE
        previewGridContainer.visibility = if (isGrid) View.VISIBLE else View.GONE

        // 2. Grid columns toggle
        if (isGrid) {
            val is3Col = (selectedColumnCount == 3)
            previewGridRow3.visibility = if (is3Col) View.VISIBLE else View.GONE
            previewGridRow4.visibility = if (is3Col) View.GONE else View.VISIBLE
        }

        // 3. Spacing / sizing
        val isCompact = (selectedItemSize == ItemSize.SMALL)
        val horizPad = dpToPx(16)

        val listVertPad = if (isTv) {
            if (isCompact) dpToPx(9) else dpToPx(14)
        } else {
            if (isCompact) dpToPx(8) else dpToPx(13)
        }
        val listIconDim = if (isTv) {
            if (isCompact) dpToPx(40) else dpToPx(48)
        } else {
            if (isCompact) dpToPx(36) else dpToPx(40)
        }

        previewListRow1.setPadding(horizPad, listVertPad, horizPad, listVertPad)
        previewListRow2.setPadding(horizPad, listVertPad, horizPad, listVertPad)
        previewIconFrameList1.layoutParams = previewIconFrameList1.layoutParams.apply {
            width = listIconDim
            height = listIconDim
        }
        previewIconFrameList2.layoutParams = previewIconFrameList2.layoutParams.apply {
            width = listIconDim
            height = listIconDim
        }

        // Categorized spacing & expand state (Mobile)
        val modernVertPad = if (isCompact) dpToPx(8) else dpToPx(12)
        val modernIconDim = if (isCompact) dpToPx(36) else dpToPx(40)
        previewModernChild1?.setPadding(horizPad, modernVertPad, horizPad, modernVertPad)
        previewModernChild2?.setPadding(horizPad, modernVertPad, horizPad, modernVertPad)
        iconFrameModern1?.layoutParams = iconFrameModern1?.layoutParams?.apply {
            width = modernIconDim
            height = modernIconDim
        }
        iconFrameModern2?.layoutParams = iconFrameModern2?.layoutParams?.apply {
            width = modernIconDim
            height = modernIconDim
        }

        if (isExpanded) {
            previewModernChild1?.visibility = View.VISIBLE
            previewModernDivider?.visibility = View.VISIBLE
            previewModernChild2?.visibility = View.VISIBLE
            imgStorageChevron?.setImageResource(R.drawable.ic_expand_less)
        } else {
            previewModernChild1?.visibility = View.GONE
            previewModernDivider?.visibility = View.GONE
            previewModernChild2?.visibility = View.GONE
            imgStorageChevron?.setImageResource(R.drawable.ic_expand_more)
        }
    }

    private fun setupTvOptionFocus(card: MaterialCardView) {
        val yellowFill = za.kilowatch.ultimatefilemanager.settings.ColorblindPalette.focusFill(this)
        val glassColor = getColor(R.color.tv_glass_white_10)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
            } else {
                card.setCardBackgroundColor(glassColor)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSaveCurrentSelection() {
        MainMenuViewModeManager.saveViewMode(this, selectedMode)
        MainMenuViewModeManager.saveItemSize(this, selectedItemSize)
        MainMenuViewModeManager.saveColumnCount(this, selectedColumnCount)
        MainMenuViewModeManager.setCategoryExpanded(this, MainMenuViewModeManager.CATEGORY_STORAGE, isExpanded)
    }
}
