package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

class StartScreenPreferenceActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var contentLayout: LinearLayout
    private val optionViews = mutableListOf<Pair<String, RadioButton>>()
    private val tvCards = mutableListOf<MaterialCardView>()

    private data class StartScreenOption(
        val id: String,
        val title: String,
        val subtitle: String,
        val iconRes: Int
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_start_screen_preference_tv)
        } else {
            setContentView(R.layout.activity_start_screen_preference)
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

        contentLayout = findViewById(R.id.contentLayout)

        // 1. Build Main Screen Options
        val mainScreenOptions = listOf(
            StartScreenOption(
                id = DefaultStartScreenPreferenceManager.ID_STORAGE_BROWSER,
                title = getString(R.string.start_screen_storage_browser),
                subtitle = getString(R.string.start_screen_storage_browser_desc),
                iconRes = R.drawable.ic_home
            ),
            StartScreenOption(
                id = DefaultStartScreenPreferenceManager.ID_TWIN_WINDOW,
                title = getString(R.string.start_screen_twin_window),
                subtitle = getString(R.string.start_screen_twin_window_desc),
                iconRes = R.drawable.ic_twin_window
            ),
            StartScreenOption(
                id = DefaultStartScreenPreferenceManager.ID_FILE_SERVER,
                title = getString(R.string.start_screen_file_server),
                subtitle = getString(R.string.start_screen_file_server_desc),
                iconRes = R.drawable.ic_file_server
            )
        )

        // 2. Build Storage Drives Options
        val storageOptions = mutableListOf<StartScreenOption>()
        val connectedStorages = StorageBrowserActivity.getConnectedStorages(this, localOnly = false)
        for (item in connectedStorages) {
            val id = "${DefaultStartScreenPreferenceManager.PREFIX_STORAGE}${item.id}"
            val subtitle = item.subtitle?.takeIf { it.isNotEmpty() }
                ?: item.mountPath.takeIf { it.isNotEmpty() }
                ?: getString(R.string.start_screen_storage_desc)
            val iconRes = if (item.iconRes != 0) item.iconRes else R.drawable.ic_storage_internal
            storageOptions.add(StartScreenOption(id, item.label, subtitle, iconRes))
        }

        val currentScreenId = DefaultStartScreenPreferenceManager.getStartScreenId(this)

        if (isTv) {
            buildTvLayout(mainScreenOptions, storageOptions, currentScreenId)
        } else {
            buildMobileLayout(mainScreenOptions, storageOptions, currentScreenId)
        }

        updateSelection(currentScreenId)
    }

    private fun buildMobileLayout(
        mainOptions: List<StartScreenOption>,
        storageOptions: List<StartScreenOption>,
        currentScreenId: String
    ) {
        val inflater = LayoutInflater.from(this)

        // Section 1: Main Screens
        contentLayout.addView(createSectionHeader(R.string.start_screen_section_main))
        val mainCard = createGlassCard()
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        mainOptions.forEachIndexed { index, option ->
            val row = inflater.inflate(R.layout.item_start_screen_row, mainContainer, false)
            bindRow(row, option)
            mainContainer.addView(row)

            if (index < mainOptions.size - 1) {
                mainContainer.addView(createDivider())
            }
        }
        mainCard.addView(mainContainer)
        contentLayout.addView(mainCard)

        // Section 2: Direct Storage Drives (if any)
        if (storageOptions.isNotEmpty()) {
            contentLayout.addView(createSectionHeader(R.string.start_screen_section_storages))
            val storageCard = createGlassCard()
            val storageContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            storageOptions.forEachIndexed { index, option ->
                val row = inflater.inflate(R.layout.item_start_screen_row, storageContainer, false)
                bindRow(row, option)
                storageContainer.addView(row)

                if (index < storageOptions.size - 1) {
                    storageContainer.addView(createDivider())
                }
            }
            storageCard.addView(storageContainer)
            contentLayout.addView(storageCard)
        }
    }

    private fun bindRow(row: View, option: StartScreenOption) {
        val imgIcon = row.findViewById<ImageView>(R.id.imgIcon)
        val txtTitle = row.findViewById<TextView>(R.id.txtTitle)
        val txtSubtitle = row.findViewById<TextView>(R.id.txtSubtitle)
        val rbSelect = row.findViewById<RadioButton>(R.id.rbSelect)

        imgIcon.setImageResource(option.iconRes)
        txtTitle.text = option.title
        txtSubtitle.text = option.subtitle

        row.tag = option.id
        row.setOnClickListener { selectScreen(option.id) }
        optionViews.add(Pair(option.id, rbSelect))
    }

    private fun createSectionHeader(titleRes: Int): TextView {
        return TextView(this).apply {
            setText(titleRes)
            setTextColor(ThemeColors.primary(this@StartScreenPreferenceActivity))
            textSize = 13f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            isAllCaps = true
            letterSpacing = 0.05f
            val density = resources.displayMetrics.density
            setPadding(
                (4 * density).toInt(),
                (14 * density).toInt(),
                (4 * density).toInt(),
                (8 * density).toInt()
            )
        }
    }

    private fun createGlassCard(): MaterialCardView {
        val density = resources.displayMetrics.density
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            radius = 16 * density
            strokeWidth = (1 * density).toInt()
            strokeColor = getColor(R.color.mobile_glass_stroke)
            setCardBackgroundColor(getColor(R.color.mobile_glass_card))
            cardElevation = 0f
        }
    }

    private fun createDivider(): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                marginStart = (14 * density).toInt()
                marginEnd = (14 * density).toInt()
            }
            setBackgroundColor(getColor(R.color.mobile_glass_stroke))
        }
    }

    private fun buildTvLayout(
        mainOptions: List<StartScreenOption>,
        storageOptions: List<StartScreenOption>,
        currentScreenId: String
    ) {
        val inflater = LayoutInflater.from(this)
        val allOptions = mainOptions + storageOptions

        for (option in allOptions) {
            val card = inflater.inflate(R.layout.item_start_screen_card, contentLayout, false) as MaterialCardView
            val imgIcon = card.findViewById<ImageView>(R.id.imgIcon)
            val txtLabel = card.findViewById<TextView>(R.id.txtLabel)
            val txtSubtitle = card.findViewById<TextView>(R.id.txtSubtitle)
            val rbSelect = card.findViewById<RadioButton>(R.id.rbSelect)

            imgIcon.setImageResource(option.iconRes)
            txtLabel.text = option.title
            txtSubtitle.text = option.subtitle

            setupTvCardFocus(card)

            card.tag = option.id
            card.setOnClickListener { selectScreen(option.id) }
            tvCards.add(card)
            optionViews.add(Pair(option.id, rbSelect))
            contentLayout.addView(card)
        }
    }

    private fun selectScreen(screenId: String) {
        DefaultStartScreenPreferenceManager.setStartScreenId(this, screenId)
        updateSelection(screenId)
    }

    private fun updateSelection(selectedScreenId: String) {
        for ((id, rb) in optionViews) {
            rb.isChecked = (id == selectedScreenId)
        }

        if (isTv) {
            val activeColor = getColor(R.color.tv_accent)
            val inactiveColor = getColor(R.color.tv_glass_border)

            for (card in tvCards) {
                val screenId = card.tag as String
                val isSelected = screenId == selectedScreenId
                val rb = card.findViewById<RadioButton>(R.id.rbSelect)
                rb.isChecked = isSelected

                card.strokeColor = if (isSelected) activeColor else inactiveColor
                if (!card.hasFocus()) {
                    rb.buttonTintList = ColorStateList.valueOf(if (isSelected) activeColor else getColor(R.color.tv_text_secondary))
                }
            }
        }
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondaryText = getColor(R.color.tv_text_secondary)

        card.setOnFocusChangeListener { _, hasFocus ->
            val isSelected = (card.tag as String) == DefaultStartScreenPreferenceManager.getStartScreenId(this)
            val rb = card.findViewById<RadioButton>(R.id.rbSelect)
            val imgIcon = card.findViewById<ImageView>(R.id.imgIcon)
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setCardTextColors(card, blackText, blackText)
                rb.buttonTintList = ColorStateList.valueOf(blackText)
                imgIcon.imageTintList = ColorStateList.valueOf(blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setCardTextColors(card, primaryText, secondaryText)
                rb.buttonTintList = ColorStateList.valueOf(if (isSelected) getColor(R.color.tv_accent) else secondaryText)
                imgIcon.imageTintList = ColorStateList.valueOf(primaryText)
            }
        }
    }

    private fun setCardTextColors(view: View, primaryColor: Int, secondaryColor: Int) {
        if (view is TextView) {
            if (view.id == R.id.txtSubtitle) {
                view.setTextColor(secondaryColor)
            } else {
                view.setTextColor(primaryColor)
            }
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setCardTextColors(view.getChildAt(i), primaryColor, secondaryColor)
            }
        }
    }
}
