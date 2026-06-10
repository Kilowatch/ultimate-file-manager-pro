package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
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
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class StartScreenPreferenceActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var contentLayout: LinearLayout
    private val cards = mutableListOf<MaterialCardView>()

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
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        contentLayout = findViewById(R.id.contentLayout)

        val currentScreenId = DefaultStartScreenPreferenceManager.getStartScreenId(this)

        // Generate options dynamically
        val options = mutableListOf<Pair<String, String>>()
        
        // Hardcoded generic options
        options.add(Pair(DefaultStartScreenPreferenceManager.ID_STORAGE_BROWSER, getString(R.string.start_screen_storage_browser)))
        options.add(Pair(DefaultStartScreenPreferenceManager.ID_TWIN_WINDOW, getString(R.string.start_screen_twin_window)))
        options.add(Pair(DefaultStartScreenPreferenceManager.ID_FILE_SERVER, getString(R.string.start_screen_file_server)))

        // Dynamic Storage Options
        val connectedStorages = za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity.getConnectedStorages(this, localOnly = false)
        for (item in connectedStorages) {
            val id = "${DefaultStartScreenPreferenceManager.PREFIX_STORAGE}${item.id}"
            options.add(Pair(id, item.label))
        }

        val inflater = LayoutInflater.from(this)
        for ((id, label) in options) {
            val card = inflater.inflate(R.layout.item_start_screen_card, contentLayout, false) as MaterialCardView
            val txtLabel = card.findViewById<TextView>(R.id.txtLabel)
            txtLabel.text = label

            if (isTv) {
                setupTvCardFocus(card)
            }

            card.tag = id
            card.setOnClickListener { selectScreen(id) }
            cards.add(card)
            contentLayout.addView(card)
        }

        updateSelection(currentScreenId)
    }

    private fun selectScreen(screenId: String) {
        DefaultStartScreenPreferenceManager.setStartScreenId(this, screenId)
        updateSelection(screenId)
    }

    private fun updateSelection(selectedScreenId: String) {
        val activeColor = if (isTv) getColor(R.color.tv_accent) else getColor(R.color.ufm_primary)
        val inactiveColor = if (isTv) getColor(R.color.tv_glass_border) else getColor(R.color.mobile_glass_stroke)

        for (card in cards) {
            val screenId = card.tag as String
            val isSelected = screenId == selectedScreenId
            val rb = card.findViewById<RadioButton>(R.id.rbSelect)
            rb.isChecked = isSelected

            if (isTv) {
                card.strokeColor = if (isSelected) activeColor else inactiveColor
                if (!card.hasFocus()) {
                    rb.buttonTintList = android.content.res.ColorStateList.valueOf(if (isSelected) activeColor else getColor(R.color.tv_text_secondary))
                }
            } else {
                card.strokeColor = if (isSelected) activeColor else inactiveColor
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
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setCardTextColors(card, blackText, blackText)
                rb.buttonTintList = android.content.res.ColorStateList.valueOf(blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setCardTextColors(card, primaryText, secondaryText)
                rb.buttonTintList = android.content.res.ColorStateList.valueOf(if (isSelected) getColor(R.color.tv_accent) else secondaryText)
            }
        }
    }

    private fun setCardTextColors(view: View, primaryColor: Int, secondaryColor: Int) {
        if (view is TextView) {
            view.setTextColor(primaryColor)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setCardTextColors(view.getChildAt(i), primaryColor, secondaryColor)
            }
        }
    }
}
