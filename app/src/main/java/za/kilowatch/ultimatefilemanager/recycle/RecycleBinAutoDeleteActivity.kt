package za.kilowatch.ultimatefilemanager.recycle

import android.content.Context
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
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class RecycleBinAutoDeleteActivity : AppCompatActivity() {

    companion object {
        private const val MAX_DAYS = 30
        private const val DOT_COUNT = MAX_DAYS + 1 // 31 dots for 0-30
    }

    private var isTv = false
    private var currentDays = RecycleBinSettingsManager.DEFAULT_AUTO_DELETE_DAYS

    private var seekBar: SeekBar? = null
    private var btnDecrease: MaterialCardView? = null
    private var btnIncrease: MaterialCardView? = null
    private var btnSave: MaterialCardView? = null
    private var layoutStepDots: LinearLayout? = null
    private lateinit var txtDurationValue: TextView
    private lateinit var txtDurationLabel: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_recycle_bin_auto_delete_tv)
        } else {
            setContentView(R.layout.activity_recycle_bin_auto_delete)
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

        currentDays = RecycleBinSettingsManager.getAutoDeleteDays(this).coerceIn(0, MAX_DAYS)
        txtDurationValue = findViewById(R.id.txtDurationValue)
        txtDurationLabel = findViewById(R.id.txtDurationLabel)

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

        updateValueDisplay()

        if (isTv) setupTv() else setupMobile()
    }

    private fun setupMobile() {
        seekBar = findViewById(R.id.seekBarDays)
        seekBar?.max = MAX_DAYS
        seekBar?.progress = currentDays

        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                currentDays = progress
                updateValueDisplay()
                RecycleBinSettingsManager.setAutoDeleteDays(this@RecycleBinAutoDeleteActivity, currentDays)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun setupTv() {
        btnDecrease = findViewById(R.id.btnDecrease)
        btnIncrease = findViewById(R.id.btnIncrease)
        btnSave = findViewById(R.id.btnSave)
        layoutStepDots = findViewById(R.id.layoutStepDots)

        buildStepDots()
        updateValueDisplay()

        btnDecrease?.setOnClickListener {
            if (currentDays > 0) { currentDays--; onTvStepChanged() }
        }
        btnIncrease?.setOnClickListener {
            if (currentDays < MAX_DAYS) { currentDays++; onTvStepChanged() }
        }
        btnSave?.setOnClickListener {
            RecycleBinSettingsManager.setAutoDeleteDays(this, currentDays)
            finish()
        }

        listOfNotNull(btnDecrease, btnIncrease).forEach { card ->
            setupTvCardFocus(card, isAccentButton = false)
        }
        btnSave?.let { setupTvCardFocus(it, isAccentButton = true) }

        btnDecrease?.requestFocus()
    }

    private fun onTvStepChanged() {
        updateValueDisplay()
        updateStepDots()
    }

    private fun buildStepDots() {
        val dots = layoutStepDots ?: return
        dots.removeAllViews()
        val sizePx = (8 * resources.displayMetrics.density).toInt()
        val marginPx = (3 * resources.displayMetrics.density).toInt()
        for (i in 0 until DOT_COUNT) {
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
        val activeColor = ContextCompat.getColor(this, R.color.ufm_primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.tv_glass_border)
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).background.setTint(
                if (i <= currentDays) activeColor else inactiveColor
            )
        }
    }

    private fun updateValueDisplay() {
        txtDurationValue.text = currentDays.toString()
        txtDurationLabel.text = if (currentDays == 0) {
            getString(R.string.recycle_bin_auto_delete_disabled)
        } else {
            getString(R.string.recycle_bin_auto_delete_days, currentDays)
        }
    }

    private fun setupTvCardFocus(card: MaterialCardView, isAccentButton: Boolean = false) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val accentColor = getColor(R.color.tv_accent)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondText = getColor(R.color.tv_text_secondary)
        val whiteText = android.graphics.Color.WHITE

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
