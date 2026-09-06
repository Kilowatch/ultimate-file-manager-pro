package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Theme selection screen (Appearance).
 * Offers Light, Dark, AMOLED Black, and System Default options in grouped glass cards,
 * plus Material You dynamic color on supported Android 12+ devices.
 */
class ThemeActivity : AppCompatActivity() {

    private lateinit var cardLight:  View
    private lateinit var cardDark:   View
    private lateinit var cardAmoled: View
    private lateinit var cardSystem: View
    private lateinit var rbLight:    RadioButton
    private lateinit var rbDark:     RadioButton
    private lateinit var rbAmoled:   RadioButton
    private lateinit var rbSystem:   RadioButton

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
            setContentView(R.layout.activity_theme_tv)
        } else {
            setContentView(R.layout.activity_theme)
        }

        // Replace root gradient with pure black when AMOLED is active.
        ThemeHelper.applyAmoledBackground(this, findViewById(R.id.main))

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

        cardLight  = findViewById(R.id.cardLight)
        cardDark   = findViewById(R.id.cardDark)
        cardAmoled = findViewById(R.id.cardAmoled)
        cardSystem = findViewById(R.id.cardSystem)
        rbLight    = findViewById(R.id.rbLight)
        rbDark     = findViewById(R.id.rbDark)
        rbAmoled   = findViewById(R.id.rbAmoled)
        rbSystem   = findViewById(R.id.rbSystem)

        // Set current selection
        updateSelection(ThemeHelper.getSavedTheme(this))

        cardLight.setOnClickListener  { selectTheme(ThemeHelper.THEME_LIGHT)  }
        cardDark.setOnClickListener   { selectTheme(ThemeHelper.THEME_DARK)   }
        cardAmoled.setOnClickListener { selectTheme(ThemeHelper.THEME_AMOLED) }
        cardSystem.setOnClickListener { selectTheme(ThemeHelper.THEME_SYSTEM) }

        // TV D-pad focus
        if (isTv) {
            setupTvCardFocus(cardLight)
            setupTvCardFocus(cardDark)
            setupTvCardFocus(cardAmoled)
            setupTvCardFocus(cardSystem)
        }

        // Material You (dynamic color) toggle — mobile only and Android 12+ only.
        if (!isTv) {
            val sectionMy = findViewById<View?>(R.id.layoutMaterialYouSection)
            val cardMy = findViewById<MaterialCardView?>(R.id.cardMaterialYou)
            val switchMy = findViewById<MaterialSwitch?>(R.id.switchMaterialYou)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                sectionMy?.visibility = View.GONE
            } else if (cardMy != null && switchMy != null) {
                switchMy.isChecked = MaterialYouPrefs.isEnabled(this)
                cardMy.setOnClickListener { switchMy.toggle() }
                switchMy.setOnCheckedChangeListener { _, isChecked ->
                    MaterialYouPrefs.setEnabled(this, isChecked)
                    DefaultIconColorManager.invalidateCache()
                    za.kilowatch.ultimatefilemanager.UfmApplication.instance.recreateAllActivities()
                }
            }
        }
    }

    private fun setupTvCardFocus(card: View) {
        if (card !is MaterialCardView) return
        val yellowFill    = getColor(R.color.tv_button_focused_yellow)
        val blackText     = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor    = getColor(R.color.tv_glass_white_10)
        val primaryText   = getColor(R.color.tv_text_primary)
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
            for (i in 0 until view.childCount) {
                setCardTextColors(view.getChildAt(i), primaryColor, secondaryColor)
            }
        }
    }

    private fun setCardRadioTint(view: View, color: Int) {
        if (view is RadioButton) {
            view.buttonTintList = ColorStateList.valueOf(color)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setCardRadioTint(view.getChildAt(i), color)
            }
        }
    }

    private fun selectTheme(theme: Int) {
        val oldTheme = ThemeHelper.getSavedTheme(this)
        if (oldTheme == theme) return

        updateSelection(theme)
        ThemeHelper.saveAndApply(this, theme)
        DefaultIconColorManager.invalidateCache()

        val wasDarkOrAmoled = oldTheme == ThemeHelper.THEME_DARK || oldTheme == ThemeHelper.THEME_AMOLED
        val isDarkOrAmoled  = theme == ThemeHelper.THEME_DARK || theme == ThemeHelper.THEME_AMOLED

        if (wasDarkOrAmoled && isDarkOrAmoled) {
            za.kilowatch.ultimatefilemanager.UfmApplication.instance.recreateAllActivities()
        } else {
            Snackbar.make(findViewById(R.id.main), getString(R.string.theme_applied), Snackbar.LENGTH_SHORT)
                .setBackgroundTint(getColor(R.color.ufm_surface_variant))
                .setTextColor(getColor(R.color.ufm_text_primary))
                .show()
        }
    }

    private fun updateSelection(theme: Int) {
        val activeColor   = if (isTv) getColor(R.color.tv_accent) else ThemeColors.primary(this)
        val inactiveColor = if (isTv) getColor(R.color.tv_glass_border) else getColor(R.color.mobile_glass_stroke)

        rbLight.isChecked  = theme == ThemeHelper.THEME_LIGHT
        rbDark.isChecked   = theme == ThemeHelper.THEME_DARK
        rbAmoled.isChecked = theme == ThemeHelper.THEME_AMOLED
        rbSystem.isChecked = theme == ThemeHelper.THEME_SYSTEM

        (cardLight as? MaterialCardView)?.strokeColor  = if (theme == ThemeHelper.THEME_LIGHT)  activeColor else inactiveColor
        (cardDark as? MaterialCardView)?.strokeColor   = if (theme == ThemeHelper.THEME_DARK)   activeColor else inactiveColor
        (cardAmoled as? MaterialCardView)?.strokeColor = if (theme == ThemeHelper.THEME_AMOLED) activeColor else inactiveColor
        (cardSystem as? MaterialCardView)?.strokeColor = if (theme == ThemeHelper.THEME_SYSTEM) activeColor else inactiveColor
    }
}
