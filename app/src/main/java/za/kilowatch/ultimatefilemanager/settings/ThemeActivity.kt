package za.kilowatch.ultimatefilemanager.settings

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
import com.google.android.material.snackbar.Snackbar
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.ui.policy.PolicySelectionActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Theme selection screen.
 * Offers Light, Dark, AMOLED Black, and System Default options.
 * Changes apply immediately and persist across restarts.
 * On TV, cards turn yellow with black text when D-pad focused.
 */
class ThemeActivity : AppCompatActivity() {

    private lateinit var cardLight: MaterialCardView
    private lateinit var cardDark: MaterialCardView
    private lateinit var cardAmoled: MaterialCardView
    private lateinit var cardSystem: MaterialCardView
    private lateinit var rbLight: RadioButton
    private lateinit var rbDark: RadioButton
    private lateinit var rbAmoled: RadioButton
    private lateinit var rbSystem: RadioButton

    private var isTv = false

    override fun attachBaseContext(newBase: android.content.Context) {
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
        // Note: UfmApplication's ActivityLifecycleCallbacks also does this globally;
        // the call here ensures correctness if the activity is recreated (e.g. theme change).
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

        // Back navigation via glass header btnBack (for both mobile and TV layouts)
        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            // TV: apply focus-based tint changes
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        // Mobile: tint is set via app:tint in XML, no override needed
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

        // TV D-pad yellow focus for each card
        if (isTv) {
            setupTvCardFocus(cardLight)
            setupTvCardFocus(cardDark)
            setupTvCardFocus(cardAmoled)
            setupTvCardFocus(cardSystem)
        }

        // Material You (dynamic color) toggle — mobile only and Android 12+ only.
        // Hidden entirely on TV (fixed brand palette) and on pre-Android-12 devices.
        if (!isTv) {
            val cardMy = findViewById<com.google.android.material.card.MaterialCardView?>(R.id.cardMaterialYou)
            val switchMy = findViewById<com.google.android.material.materialswitch.MaterialSwitch?>(R.id.switchMaterialYou)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                cardMy?.visibility = View.GONE
            } else if (cardMy != null && switchMy != null) {
                switchMy.isChecked = MaterialYouPrefs.isEnabled(this)
                // Tapping anywhere on the row flips the switch (the switch itself is
                // also directly tappable).
                cardMy.setOnClickListener { switchMy.toggle() }
                switchMy.setOnCheckedChangeListener { _, isChecked ->
                    MaterialYouPrefs.setEnabled(this, isChecked)
                    // Icon tints depend on whether dynamic color is active.
                    DefaultIconColorManager.invalidateCache()
                    // Recreate EVERY live activity so the wallpaper palette (or brand
                    // palette) applies app-wide, not just to this screen.
                    za.kilowatch.ultimatefilemanager.UfmApplication.instance.recreateAllActivities()
                }
            }
        }
    }

    /**
     * On TV: when the card gains D-pad focus, fill it solid yellow and flip all
     * child text to near-black. On blur, restore the glass look.
     */
    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill    = getColor(R.color.tv_button_focused_yellow)
        val blackText     = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor    = getColor(R.color.tv_glass_white_10)
        val primaryText   = getColor(R.color.tv_text_primary)
        val secondaryText = getColor(R.color.tv_text_secondary)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                // Flip all TextViews inside to black
                setCardTextColors(card, blackText, blackText)
                // Also tint the radio button
                setCardRadioTint(card, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setCardTextColors(card, primaryText, secondaryText)
                setCardRadioTint(card, getColor(R.color.tv_accent))
            }
        }
    }

    /** Recursively set text colour on all TextViews inside a ViewGroup. */
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

    /** Recursively set tint on all RadioButtons inside a ViewGroup. */
    private fun setCardRadioTint(view: View, color: Int) {
        if (view is RadioButton) {
            view.buttonTintList = android.content.res.ColorStateList.valueOf(color)
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
        // Icon tint resolution is cached per theme mode — refresh it on mode change.
        DefaultIconColorManager.invalidateCache()

        // Dark (1) and AMOLED (3) both use MODE_NIGHT_YES.
        // Switching between them won't trigger recreation by AppCompatDelegate,
        // so we must force it to apply/remove the AMOLED overlay and background.
        val wasDarkOrAmoled = oldTheme == ThemeHelper.THEME_DARK || oldTheme == ThemeHelper.THEME_AMOLED
        val isDarkOrAmoled  = theme == ThemeHelper.THEME_DARK || theme == ThemeHelper.THEME_AMOLED

        if (wasDarkOrAmoled && isDarkOrAmoled) {
            recreate()
        } else {
            Snackbar.make(findViewById(R.id.main), getString(R.string.theme_applied), Snackbar.LENGTH_SHORT)
                .setBackgroundTint(getColor(R.color.ufm_surface_variant))
                .setTextColor(getColor(R.color.ufm_text_primary))
                .show()
        }
    }

    private fun updateSelection(theme: Int) {
        val activeColor   = if (isTv) getColor(R.color.tv_accent) else getColor(R.color.ufm_primary)
        val inactiveColor = if (isTv) getColor(R.color.tv_glass_border) else getColor(R.color.ufm_surface_variant)

        rbLight.isChecked  = theme == ThemeHelper.THEME_LIGHT
        rbDark.isChecked   = theme == ThemeHelper.THEME_DARK
        rbAmoled.isChecked = theme == ThemeHelper.THEME_AMOLED
        rbSystem.isChecked = theme == ThemeHelper.THEME_SYSTEM

        cardLight.strokeColor  = if (theme == ThemeHelper.THEME_LIGHT)  activeColor else inactiveColor
        cardDark.strokeColor   = if (theme == ThemeHelper.THEME_DARK)   activeColor else inactiveColor
        cardAmoled.strokeColor = if (theme == ThemeHelper.THEME_AMOLED) activeColor else inactiveColor
        cardSystem.strokeColor = if (theme == ThemeHelper.THEME_SYSTEM) activeColor else inactiveColor
    }
}
