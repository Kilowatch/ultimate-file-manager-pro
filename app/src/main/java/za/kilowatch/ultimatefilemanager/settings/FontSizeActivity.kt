package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Font Size selection screen.
 * Offers Small, Normal, and Large options.
 * Changes apply immediately via fontScale in attachBaseContext and persist across restarts.
 * On TV, cards turn yellow with black text when D-pad focused.
 */
class FontSizeActivity : AppCompatActivity() {

    private lateinit var cardSmall:  MaterialCardView
    private lateinit var cardNormal: MaterialCardView
    private lateinit var cardLarge:  MaterialCardView
    private lateinit var rbSmall:    RadioButton
    private lateinit var rbNormal:   RadioButton
    private lateinit var rbLarge:    RadioButton

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
            setContentView(R.layout.activity_font_size_tv)
        } else {
            setContentView(R.layout.activity_font_size)
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

        // Back button (shared ID in both layouts)
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

        cardSmall  = findViewById(R.id.cardSmall)
        cardNormal = findViewById(R.id.cardNormal)
        cardLarge  = findViewById(R.id.cardLarge)
        rbSmall    = findViewById(R.id.rbSmall)
        rbNormal   = findViewById(R.id.rbNormal)
        rbLarge    = findViewById(R.id.rbLarge)

        // Reflect current saved preference
        updateSelection(FontSizeHelper.getSavedSize(this))

        cardSmall.setOnClickListener  { selectSize(FontSizeHelper.FONT_SMALL)  }
        cardNormal.setOnClickListener { selectSize(FontSizeHelper.FONT_NORMAL) }
        cardLarge.setOnClickListener  { selectSize(FontSizeHelper.FONT_LARGE)  }

        if (isTv) {
            setupTvCardFocus(cardSmall)
            setupTvCardFocus(cardNormal)
            setupTvCardFocus(cardLarge)
        }
    }

    private fun selectSize(size: Int) {
        val previous = FontSizeHelper.getSavedSize(this)
        FontSizeHelper.save(this, size)
        updateSelection(size)

        if (size == previous) return   // Nothing changed

        // Recreate this activity immediately so the user sees the new font
        // right here on the picker. Also signal the home screen to do the same
        // when it eventually resumes.
        FontSizeHelper.restartPending = true
        recreate()   // Immediate visual feedback on this screen
    }

    private fun updateSelection(size: Int) {
        val activeColor   = if (isTv) getColor(R.color.tv_accent)        else getColor(R.color.ufm_primary)
        val inactiveColor = if (isTv) getColor(R.color.tv_glass_border)  else getColor(R.color.ufm_surface_variant)

        rbSmall.isChecked  = size == FontSizeHelper.FONT_SMALL
        rbNormal.isChecked = size == FontSizeHelper.FONT_NORMAL
        rbLarge.isChecked  = size == FontSizeHelper.FONT_LARGE

        cardSmall.strokeColor  = if (size == FontSizeHelper.FONT_SMALL)  activeColor else inactiveColor
        cardNormal.strokeColor = if (size == FontSizeHelper.FONT_NORMAL) activeColor else inactiveColor
        cardLarge.strokeColor  = if (size == FontSizeHelper.FONT_LARGE)  activeColor else inactiveColor
    }

    /**
     * On TV: when the card gains D-pad focus, fill it solid yellow and flip all
     * child text to near-black. On blur, restore the glass look.
     */
    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill   = getColor(R.color.tv_button_focused_yellow)
        val blackText    = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor   = getColor(R.color.tv_glass_white_10)
        val primaryText  = getColor(R.color.tv_text_primary)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setCardTextColors(card, blackText, blackText)
                setCardRadioTint(card, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setCardTextColors(card, primaryText, primaryText)
                setCardRadioTint(card, getColor(R.color.tv_accent))
            }
        }
    }

    private fun setCardTextColors(view: View, primaryColor: Int, secondaryColor: Int) {
        if (view is TextView) { view.setTextColor(primaryColor); return }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount)
                setCardTextColors(view.getChildAt(i), primaryColor, secondaryColor)
        }
    }

    private fun setCardRadioTint(view: View, color: Int) {
        if (view is RadioButton) { view.buttonTintList = android.content.res.ColorStateList.valueOf(color); return }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount)
                setCardRadioTint(view.getChildAt(i), color)
        }
    }
}
