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
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class VideoThumbnailTimeActivity : AppCompatActivity() {

    private lateinit var cardPercent0: MaterialCardView
    private lateinit var cardPercent10: MaterialCardView
    private lateinit var cardPercent25: MaterialCardView
    private lateinit var cardPercent50: MaterialCardView
    private lateinit var cardPercent75: MaterialCardView
    private lateinit var rbPercent0: RadioButton
    private lateinit var rbPercent10: RadioButton
    private lateinit var rbPercent25: RadioButton
    private lateinit var rbPercent50: RadioButton
    private lateinit var rbPercent75: RadioButton

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
            setContentView(R.layout.activity_video_thumbnail_time_tv)
        } else {
            setContentView(R.layout.activity_video_thumbnail_time)
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

        cardPercent0 = findViewById(R.id.cardPercent0)
        cardPercent10 = findViewById(R.id.cardPercent10)
        cardPercent25 = findViewById(R.id.cardPercent25)
        cardPercent50 = findViewById(R.id.cardPercent50)
        cardPercent75 = findViewById(R.id.cardPercent75)
        rbPercent0 = findViewById(R.id.rbPercent0)
        rbPercent10 = findViewById(R.id.rbPercent10)
        rbPercent25 = findViewById(R.id.rbPercent25)
        rbPercent50 = findViewById(R.id.rbPercent50)
        rbPercent75 = findViewById(R.id.rbPercent75)

        updateSelection(VideoThumbnailTimePreferenceManager.getPercent(this))

        cardPercent0.setOnClickListener { selectPercent(0) }
        cardPercent10.setOnClickListener { selectPercent(10) }
        cardPercent25.setOnClickListener { selectPercent(25) }
        cardPercent50.setOnClickListener { selectPercent(50) }
        cardPercent75.setOnClickListener { selectPercent(75) }

        if (isTv) {
            setupTvCardFocus(cardPercent0)
            setupTvCardFocus(cardPercent10)
            setupTvCardFocus(cardPercent25)
            setupTvCardFocus(cardPercent50)
            setupTvCardFocus(cardPercent75)
        }
    }

    private fun selectPercent(percent: Int) {
        val previous = VideoThumbnailTimePreferenceManager.getPercent(this)
        VideoThumbnailTimePreferenceManager.setPercent(this, percent)
        updateSelection(percent)
    }

    private fun updateSelection(percent: Int) {
        val activeColor = if (isTv) getColor(R.color.tv_accent) else getColor(R.color.ufm_primary)
        val inactiveColor = if (isTv) getColor(R.color.tv_glass_border) else getColor(R.color.ufm_surface_variant)

        rbPercent0.isChecked = percent == 0
        rbPercent10.isChecked = percent == 10
        rbPercent25.isChecked = percent == 25
        rbPercent50.isChecked = percent == 50
        rbPercent75.isChecked = percent == 75

        cardPercent0.strokeColor = if (percent == 0) activeColor else inactiveColor
        cardPercent10.strokeColor = if (percent == 10) activeColor else inactiveColor
        cardPercent25.strokeColor = if (percent == 25) activeColor else inactiveColor
        cardPercent50.strokeColor = if (percent == 50) activeColor else inactiveColor
        cardPercent75.strokeColor = if (percent == 75) activeColor else inactiveColor
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)

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
