package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Settings screen for configuring the long-press hold duration used to enter
 * tile edit mode on the StorageBrowser main menu.
 *
 * **Mobile**: a SeekBar (0–9) maps to 0.5 s – 5.0 s; changes persist immediately.
 * **TV**: left/right cards decrement/increment the step; a Save card persists.
 */
class LongPressDurationActivity : AppCompatActivity() {

    private var isTv = false
    private var currentStep = LongPressDurationManager.DEFAULT_STEP

    // Mobile views
    private var seekBar: SeekBar? = null

    // TV views
    private var btnDecrease: MaterialCardView? = null
    private var btnIncrease: MaterialCardView? = null
    private var btnSave: MaterialCardView? = null
    private var layoutStepDots: LinearLayout? = null
    private var txtSave: TextView? = null

    // Shared
    private lateinit var txtDurationValue: TextView

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, LongPressDurationActivity::class.java))
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_long_press_duration_tv)
        } else {
            setContentView(R.layout.activity_long_press_duration)
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

        currentStep = LongPressDurationManager.loadStep(this)
        txtDurationValue = findViewById(R.id.txtDurationValue)

        // Back button
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

        if (isTv) setupTv() else setupMobile()
    }

    // ── Mobile ────────────────────────────────────────────────────────────

    private fun setupMobile() {
        seekBar = findViewById(R.id.seekBarDuration)
        seekBar?.max = LongPressDurationManager.STEP_COUNT - 1
        seekBar?.progress = currentStep
        updateValueDisplay()

        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                currentStep = progress
                updateValueDisplay()
                // Persist immediately on mobile (no explicit save button)
                LongPressDurationManager.saveStep(this@LongPressDurationActivity, currentStep)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── TV ────────────────────────────────────────────────────────────────

    private fun setupTv() {
        btnDecrease    = findViewById(R.id.btnDecrease)
        btnIncrease    = findViewById(R.id.btnIncrease)
        btnSave        = findViewById(R.id.btnSave)
        layoutStepDots = findViewById(R.id.layoutStepDots)
        txtSave        = findViewById(R.id.txtSave)

        buildStepDots()
        updateValueDisplay()

        btnDecrease?.setOnClickListener {
            if (currentStep > 0) { currentStep--; onTvStepChanged() }
        }
        btnIncrease?.setOnClickListener {
            if (currentStep < LongPressDurationManager.STEP_COUNT - 1) { currentStep++; onTvStepChanged() }
        }
        btnSave?.setOnClickListener {
            LongPressDurationManager.saveStep(this, currentStep)
            finish()
        }

        // TV focus yellow highlight
        // Arrow buttons restore to glass; Save button restores to solid accent.
        listOfNotNull(btnDecrease, btnIncrease).forEach { card ->
            setupTvCardFocus(card, isAccentButton = false)
        }
        btnSave?.let { setupTvCardFocus(it, isAccentButton = true) }

        // Initial focus on Decrease so users can navigate easily
        btnDecrease?.requestFocus()
    }

    private fun onTvStepChanged() {
        updateValueDisplay()
        updateStepDots()
    }

    /**
     * Inflates 10 small dot views (filled = steps up to current, hollow = remaining).
     */
    private fun buildStepDots() {
        val dots = layoutStepDots ?: return
        dots.removeAllViews()
        val sizePx = (14 * resources.displayMetrics.density).toInt()   // slightly larger for 1080p
        val marginPx = (5 * resources.displayMetrics.density).toInt()
        for (i in 0 until LongPressDurationManager.STEP_COUNT) {
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(sizePx, sizePx)
            lp.setMargins(marginPx, 0, marginPx, 0)
            dot.layoutParams = lp
            dot.setBackgroundResource(R.drawable.bg_icon_circle_accent)
            dots.addView(dot)
        }
        updateStepDots()
    }

    private fun updateStepDots() {
        val dots = layoutStepDots ?: return
        val activeColor  = ContextCompat.getColor(this, R.color.ufm_primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.tv_glass_border)
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).background.setTint(
                if (i <= currentStep) activeColor else inactiveColor
            )
        }
    }

    // ── Shared ────────────────────────────────────────────────────────────

    private fun updateValueDisplay() {
        txtDurationValue.text = LongPressDurationManager.formatStep(currentStep)
    }

    // ── TV card focus highlight ───────────────────────────────────────────

    private fun setupTvCardFocus(card: MaterialCardView, isAccentButton: Boolean = false) {
        val yellowFill   = getColor(R.color.tv_button_focused_yellow)
        val blackText    = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor   = getColor(R.color.tv_glass_white_10)
        val accentColor  = getColor(R.color.tv_accent)
        val primaryText  = getColor(R.color.tv_text_primary)
        val secondText   = getColor(R.color.tv_text_secondary)
        val whiteText    = android.graphics.Color.WHITE

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setChildTextColors(card, blackText)
            } else {
                if (isAccentButton) {
                    card.setCardBackgroundColor(accentColor)
                    setChildTextColors(card, whiteText)
                } else {
                    card.setCardBackgroundColor(glassColor)
                    setChildTextColorsTwo(card, primaryText, secondText)
                }
            }
        }
    }

    private fun setChildTextColors(view: View, color: Int) {
        if (view is TextView) { view.setTextColor(color); return }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setChildTextColors(view.getChildAt(i), color)
        }
    }

    private fun setChildTextColorsTwo(view: View, primary: Int, secondary: Int) {
        if (view is TextView) {
            view.setTextColor(
                if (view.textSize > resources.displayMetrics.density * 14) primary else secondary
            )
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setChildTextColorsTwo(view.getChildAt(i), primary, secondary)
        }
    }
}
