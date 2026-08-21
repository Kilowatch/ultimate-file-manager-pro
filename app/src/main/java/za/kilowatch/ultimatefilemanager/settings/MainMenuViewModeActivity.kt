package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.MainMenuViewModeManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Main Menu View Mode selection screen.
 * Allows toggling between Modern Categorized, Classic List, and Classic Grid,
 * and selecting column count or list row size.
 */
class MainMenuViewModeActivity : AppCompatActivity() {

    private var cardModern:    View? = null
    private lateinit var cardList:     View
    private lateinit var cardGrid:     View
    private lateinit var cardColumns4: View
    private lateinit var cardColumns3: View
    private var rbList:       RadioButton? = null
    private var rbGrid:       RadioButton? = null
    private var rbColumns4:   RadioButton? = null
    private var rbColumns3:   RadioButton? = null
    private lateinit var layoutColumns: View

    private lateinit var cardSizeLarge:  View
    private lateinit var cardSizeMedium: View
    private lateinit var cardSizeSmall:  View
    private var rbSizeLarge:    RadioButton? = null
    private var rbSizeMedium:   RadioButton? = null
    private var rbSizeSmall:    RadioButton? = null
    private lateinit var layoutListSize: View
    private var layoutResetCategories: View? = null
    private var cardResetCategories: View? = null

    private var checkContainerModern: View? = null
    private var checkContainerList: View? = null
    private var checkContainerGrid: View? = null
    private var checkContainerSizeLarge: View? = null
    private var checkContainerSizeMedium: View? = null
    private var checkContainerSizeSmall: View? = null
    private var checkContainerColumns4: View? = null
    private var checkContainerColumns3: View? = null

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
            setContentView(R.layout.activity_main_menu_view_mode_tv)
        } else {
            setContentView(R.layout.activity_main_menu_view_mode)
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

        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        cardModern   = findViewById(R.id.cardModern)
        cardList     = findViewById(R.id.cardList)
        cardGrid     = findViewById(R.id.cardGrid)
        cardColumns4 = findViewById(R.id.cardColumns4)
        cardColumns3 = findViewById(R.id.cardColumns3)
        rbList       = findViewById(R.id.rbList)
        rbGrid       = findViewById(R.id.rbGrid)
        rbColumns4   = findViewById(R.id.rbColumns4)
        rbColumns3   = findViewById(R.id.rbColumns3)
        layoutColumns = findViewById(R.id.layoutColumns)

        cardSizeLarge  = findViewById(R.id.cardSizeLarge)
        cardSizeMedium = findViewById(R.id.cardSizeMedium)
        cardSizeSmall  = findViewById(R.id.cardSizeSmall)
        rbSizeLarge    = findViewById(R.id.rbSizeLarge)
        rbSizeMedium   = findViewById(R.id.rbSizeMedium)
        rbSizeSmall    = findViewById(R.id.rbSizeSmall)
        layoutListSize = findViewById(R.id.layoutListSize)
        layoutResetCategories = findViewById(R.id.layoutResetCategories)
        cardResetCategories = findViewById(R.id.cardResetCategories)

        checkContainerModern = findViewById(R.id.checkContainerModern)
        checkContainerList = findViewById(R.id.checkContainerList)
        checkContainerGrid = findViewById(R.id.checkContainerGrid)
        checkContainerSizeLarge = findViewById(R.id.checkContainerSizeLarge)
        checkContainerSizeMedium = findViewById(R.id.checkContainerSizeMedium)
        checkContainerSizeSmall = findViewById(R.id.checkContainerSizeSmall)
        checkContainerColumns4 = findViewById(R.id.checkContainerColumns4)
        checkContainerColumns3 = findViewById(R.id.checkContainerColumns3)

        val currentMode     = MainMenuViewModeManager.loadViewMode(this)
        val currentColCount = MainMenuViewModeManager.loadColumnCount(this)
        val currentItemSize = MainMenuViewModeManager.loadItemSize(this)

        updateViewModeSelection(currentMode)
        updateColumnCountSelection(currentColCount)
        updateItemSizeSelection(currentItemSize)
        updateColumnsVisibility(currentMode)

        cardModern?.setOnClickListener { selectViewMode(MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED) }
        cardList.setOnClickListener { selectViewMode(MainMenuViewModeManager.ViewMode.LIST) }
        cardGrid.setOnClickListener { selectViewMode(MainMenuViewModeManager.ViewMode.GRID) }
        cardColumns4.setOnClickListener { selectColumnCount(4) }
        cardColumns3.setOnClickListener { selectColumnCount(3) }

        cardSizeLarge.setOnClickListener { selectItemSize(MainMenuViewModeManager.ItemSize.LARGE) }
        cardSizeMedium.setOnClickListener { selectItemSize(MainMenuViewModeManager.ItemSize.MEDIUM) }
        cardSizeSmall.setOnClickListener { selectItemSize(MainMenuViewModeManager.ItemSize.SMALL) }
        cardResetCategories?.setOnClickListener { showResetConfirmationDialog() }

        if (isTv) {
            setupTvCardFocus(cardList)
            setupTvCardFocus(cardGrid)
            setupTvCardFocus(cardColumns4)
            setupTvCardFocus(cardColumns3)
            setupTvCardFocus(cardSizeLarge)
            setupTvCardFocus(cardSizeMedium)
            setupTvCardFocus(cardSizeSmall)
        }
    }

    private fun selectViewMode(mode: MainMenuViewModeManager.ViewMode) {
        MainMenuViewModeManager.saveViewMode(this, mode)
        updateViewModeSelection(mode)
        updateColumnsVisibility(mode)
    }

    private fun selectColumnCount(count: Int) {
        MainMenuViewModeManager.saveColumnCount(this, count)
        updateColumnCountSelection(count)
    }

    private fun selectItemSize(size: MainMenuViewModeManager.ItemSize) {
        MainMenuViewModeManager.saveItemSize(this, size)
        updateItemSizeSelection(size)
    }

    private fun updateViewModeSelection(mode: MainMenuViewModeManager.ViewMode) {
        val activeColor   = if (isTv) getColor(R.color.tv_accent)        else za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(this)
        val inactiveColor = if (isTv) getColor(R.color.tv_glass_border)  else getColor(R.color.mobile_glass_stroke)

        rbList?.isChecked = mode == MainMenuViewModeManager.ViewMode.LIST
        rbGrid?.isChecked = mode == MainMenuViewModeManager.ViewMode.GRID

        (cardList as? MaterialCardView)?.strokeColor = if (mode == MainMenuViewModeManager.ViewMode.LIST) activeColor else inactiveColor
        (cardGrid as? MaterialCardView)?.strokeColor = if (mode == MainMenuViewModeManager.ViewMode.GRID) activeColor else inactiveColor
        (cardModern as? MaterialCardView)?.strokeColor = if (mode == MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED) activeColor else inactiveColor

        checkContainerModern?.visibility = if (mode == MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED) View.VISIBLE else View.GONE
        checkContainerList?.visibility = if (mode == MainMenuViewModeManager.ViewMode.LIST) View.VISIBLE else View.GONE
        checkContainerGrid?.visibility = if (mode == MainMenuViewModeManager.ViewMode.GRID) View.VISIBLE else View.GONE
    }

    private fun updateColumnCountSelection(count: Int) {
        val activeColor   = if (isTv) getColor(R.color.tv_accent)        else za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(this)
        val inactiveColor = if (isTv) getColor(R.color.tv_glass_border)  else getColor(R.color.mobile_glass_stroke)

        rbColumns4?.isChecked = count == 4
        rbColumns3?.isChecked = count == 3

        (cardColumns4 as? MaterialCardView)?.strokeColor = if (count == 4) activeColor else inactiveColor
        (cardColumns3 as? MaterialCardView)?.strokeColor = if (count == 3) activeColor else inactiveColor

        checkContainerColumns4?.visibility = if (count == 4) View.VISIBLE else View.GONE
        checkContainerColumns3?.visibility = if (count == 3) View.VISIBLE else View.GONE
    }

    private fun updateItemSizeSelection(size: MainMenuViewModeManager.ItemSize) {
        val activeColor   = if (isTv) getColor(R.color.tv_accent)        else za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(this)
        val inactiveColor = if (isTv) getColor(R.color.tv_glass_border)  else getColor(R.color.mobile_glass_stroke)

        rbSizeLarge?.isChecked  = size == MainMenuViewModeManager.ItemSize.LARGE
        rbSizeMedium?.isChecked = size == MainMenuViewModeManager.ItemSize.MEDIUM
        rbSizeSmall?.isChecked  = size == MainMenuViewModeManager.ItemSize.SMALL

        (cardSizeLarge as? MaterialCardView)?.strokeColor  = if (size == MainMenuViewModeManager.ItemSize.LARGE) activeColor else inactiveColor
        (cardSizeMedium as? MaterialCardView)?.strokeColor = if (size == MainMenuViewModeManager.ItemSize.MEDIUM) activeColor else inactiveColor
        (cardSizeSmall as? MaterialCardView)?.strokeColor  = if (size == MainMenuViewModeManager.ItemSize.SMALL) activeColor else inactiveColor

        checkContainerSizeLarge?.visibility = if (size == MainMenuViewModeManager.ItemSize.LARGE) View.VISIBLE else View.GONE
        checkContainerSizeMedium?.visibility = if (size == MainMenuViewModeManager.ItemSize.MEDIUM) View.VISIBLE else View.GONE
        checkContainerSizeSmall?.visibility = if (size == MainMenuViewModeManager.ItemSize.SMALL) View.VISIBLE else View.GONE
    }

    private fun updateColumnsVisibility(mode: MainMenuViewModeManager.ViewMode) {
        when (mode) {
            MainMenuViewModeManager.ViewMode.GRID -> {
                layoutColumns.visibility = View.VISIBLE
                layoutListSize.visibility = View.GONE
                layoutResetCategories?.visibility = View.GONE
            }
            MainMenuViewModeManager.ViewMode.LIST -> {
                layoutColumns.visibility = View.GONE
                layoutListSize.visibility = View.VISIBLE
                layoutResetCategories?.visibility = View.GONE
            }
            MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED -> {
                layoutColumns.visibility = View.GONE
                layoutListSize.visibility = View.VISIBLE
                layoutResetCategories?.visibility = if (isTv) View.GONE else View.VISIBLE
            }
        }
    }

    private fun showResetConfirmationDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_reset_category_layout_confirm, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnResetConfirm).setOnClickListener {
            MainMenuViewModeManager.resetCategoryLayout(this)
            dialog.dismiss()
            val rootView = findViewById<View>(R.id.main)
            if (rootView != null) {
                Snackbar.make(rootView, R.string.reset_categorized_success, Snackbar.LENGTH_SHORT).show()
            }
        }
        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun setupTvCardFocus(card: View) {
        if (card !is MaterialCardView) return
        val yellowFill   = getColor(R.color.tv_button_focused_yellow)
        val blackText    = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor   = getColor(R.color.tv_glass_white_10)
        val primaryText  = getColor(R.color.tv_text_primary)
        val secondaryText = getColor(R.color.tv_text_secondary)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setCardTextColors(card, blackText, blackText)
                setCardRadioTint(card, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setCardTextColors(card, primaryText, secondaryText)
                setCardRadioTint(card, getColor(R.color.tv_accent))
            }
        }
    }

    private fun setCardTextColors(view: View, primaryColor: Int, secondaryColor: Int) {
        if (view is TextView) {
            val isSubtitle = view.textSize < resources.displayMetrics.density * 16
            view.setTextColor(if (isSubtitle) secondaryColor else primaryColor)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount)
                setCardTextColors(view.getChildAt(i), primaryColor, secondaryColor)
        }
    }

    private fun setCardRadioTint(view: View, color: Int) {
        if (view is RadioButton) { view.buttonTintList = ColorStateList.valueOf(color); return }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount)
                setCardRadioTint(view.getChildAt(i), color)
        }
    }
}
