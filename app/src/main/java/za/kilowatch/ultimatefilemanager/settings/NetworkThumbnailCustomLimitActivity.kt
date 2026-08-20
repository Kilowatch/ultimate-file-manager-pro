package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import za.kilowatch.ultimatefilemanager.R
import java.util.Locale

class NetworkThumbnailCustomLimitActivity : AppCompatActivity() {

    // Persisted through recreate() to prevent looping when restartPending is still true.
    private var handledFontChange = false
    private var handledLocaleChange = false

    private var currentMb: Int = 500
    private var useGb: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        handledFontChange = savedInstanceState?.getBoolean("font_handled", false) ?: false
        handledLocaleChange = savedInstanceState?.getBoolean("locale_handled", false) ?: false

        enableEdgeToEdge()

        setContentView(R.layout.activity_network_thumbnail_custom_limit)

        val btnBack = findViewById<View?>(R.id.btnBack)
        btnBack?.setOnClickListener { finish() }
        if (btnBack is ImageView) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack.imageTintList = whiteCsl
            btnBack.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }

        val txtValue = findViewById<TextView>(R.id.txtValue)
        val btnMinus = findViewById<Button>(R.id.btnMinus)
        val btnPlus = findViewById<Button>(R.id.btnPlus)
        val btnUnitMb = findViewById<Button>(R.id.btnUnitMb)
        val btnUnitGb = findViewById<Button>(R.id.btnUnitGb)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnCancel = findViewById<Button>(R.id.btnCancel)

        currentMb = NetworkThumbnailPreferenceManager.getCacheLimitMb(this)
        useGb = currentMb >= 1024

        fun refreshDisplay() {
            if (useGb) {
                val gb = currentMb.toDouble() / 1024.0
                txtValue.text = String.format(Locale.getDefault(), "%.1f GB", gb)
            } else {
                txtValue.text = "$currentMb MB"
            }
        }

        refreshDisplay()

        val focusText = getColor(R.color.tv_button_focused_yellow_text)

        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (v is Button) {
                v.setTextColor(if (hasFocus) focusText else getColor(R.color.tv_text_primary))
            }
        }

        btnMinus.onFocusChangeListener = focusListener
        btnPlus.onFocusChangeListener = focusListener
        btnUnitMb.onFocusChangeListener = focusListener
        btnUnitGb.onFocusChangeListener = focusListener
        btnSave.onFocusChangeListener = focusListener
        btnCancel.onFocusChangeListener = focusListener

        btnMinus.setOnClickListener {
            if (useGb) {
                currentMb = (currentMb - 102).coerceAtLeast(1)
            } else {
                currentMb = (currentMb - 50).coerceAtLeast(1)
            }
            refreshDisplay()
        }

        btnPlus.setOnClickListener {
            if (useGb) {
                currentMb = currentMb + 102
            } else {
                currentMb = currentMb + 50
            }
            refreshDisplay()
        }

        btnUnitMb.setOnClickListener {
            useGb = false
            refreshDisplay()
        }

        btnUnitGb.setOnClickListener {
            useGb = true
            refreshDisplay()
        }

        btnSave.setOnClickListener {
            NetworkThumbnailPreferenceManager.setCacheLimitMb(this, currentMb)
            finish()
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (LocaleHelper.restartPending && !handledLocaleChange) {
            handledLocaleChange = true
            recreate()
            return
        }
        if (FontSizeHelper.restartPending && !handledFontChange) {
            handledFontChange = true
            recreate()
            return
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("font_handled", handledFontChange)
        outState.putBoolean("locale_handled", handledLocaleChange)
    }
}
