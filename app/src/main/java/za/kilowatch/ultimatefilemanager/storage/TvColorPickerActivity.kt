package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * D-Pad friendly full-screen colour picker for TV.
 * Left panel: extended preset colour swatch grid (8 columns).
 * Right panel: three SeekBar sliders for Hue / Saturation / Value.
 * Each D-Pad press on a focused slider changes the value by STEP (5).
 *
 * Returns the selected colour via [EXTRA_COLOR] in the result Intent.
 */
class TvColorPickerActivity : AppCompatActivity() {

    private var currentColor = Color.WHITE
    private val hsv = floatArrayOf(0f, 1f, 1f)

    private lateinit var previewSwatch: View
    private lateinit var hexLabel: TextView
    private lateinit var sliderHue: SeekBar
    private lateinit var sliderSat: SeekBar
    private lateinit var sliderVal: SeekBar
    private lateinit var swatchAdapter: TvSwatchAdapter

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_color_picker)

        val initial = intent.getIntExtra(EXTRA_INITIAL_COLOR, Color.WHITE)
        Color.colorToHSV(initial, hsv)
        currentColor = initial

        previewSwatch = findViewById(R.id.tvPickerPreviewSwatch)
        hexLabel      = findViewById(R.id.tvPickerHexLabel)
        sliderHue     = findViewById(R.id.sliderHue)
        sliderSat     = findViewById(R.id.sliderSat)
        sliderVal     = findViewById(R.id.sliderVal)

        // ── Preset grid ───────────────────────────────────────────────────
        val recycler = findViewById<RecyclerView>(R.id.recyclerColorGrid)
        swatchAdapter = TvSwatchAdapter(PRESET_COLORS) { color ->
            currentColor = color
            Color.colorToHSV(color, hsv)
            syncSlidersFromHsv()
            updatePreview()
        }
        recycler.layoutManager = GridLayoutManager(this, 8)
        recycler.adapter = swatchAdapter

        // ── Sliders ───────────────────────────────────────────────────────
        sliderHue.max = 360
        sliderSat.max = 255
        sliderVal.max = 255
        syncSlidersFromHsv()

        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?)  {}
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                hsv[0] = sliderHue.progress.toFloat()
                hsv[1] = sliderSat.progress / 255f
                hsv[2] = sliderVal.progress / 255f
                currentColor = Color.HSVToColor(hsv)
                swatchAdapter.clearSelection()
                updatePreview()
            }
        }
        sliderHue.setOnSeekBarChangeListener(sliderListener)
        sliderSat.setOnSeekBarChangeListener(sliderListener)
        sliderVal.setOnSeekBarChangeListener(sliderListener)

        // D-Pad key handler for sliders (step = 5)
        val sliderKeyListener = View.OnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            val seekBar = v as? SeekBar ?: return@OnKeyListener false
            val delta = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> SLIDER_STEP
                KeyEvent.KEYCODE_DPAD_LEFT  -> -SLIDER_STEP
                else                        -> return@OnKeyListener false
            }
            seekBar.progress = (seekBar.progress + delta).coerceIn(0, seekBar.max)
            true
        }
        sliderHue.setOnKeyListener(sliderKeyListener)
        sliderSat.setOnKeyListener(sliderKeyListener)
        sliderVal.setOnKeyListener(sliderKeyListener)

        updatePreview()

        // ── Buttons ───────────────────────────────────────────────────────
        findViewById<View>(R.id.btnPickerCancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        findViewById<View>(R.id.btnPickerOk).setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_COLOR, currentColor))
            finish()
        }
    }

    private fun syncSlidersFromHsv() {
        sliderHue.progress = hsv[0].toInt()
        sliderSat.progress = (hsv[1] * 255).toInt()
        sliderVal.progress = (hsv[2] * 255).toInt()
    }

    private fun updatePreview() {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(currentColor)
        previewSwatch.background = d
        hexLabel.text = String.format("#%06X", 0xFFFFFF and currentColor)
    }

    companion object {
        const val EXTRA_COLOR         = "extra_color"
        const val EXTRA_INITIAL_COLOR = "extra_initial_color"
        private const val SLIDER_STEP = 5

        fun createIntent(context: Context, initialColor: Int): Intent =
            Intent(context, TvColorPickerActivity::class.java)
                .putExtra(EXTRA_INITIAL_COLOR, initialColor)

        /** Extended preset palette — 120 colours, 8 per row. */
        val PRESET_COLORS = listOf(
            // Reds
            0xFFB71C1C.toInt(), 0xFFC62828.toInt(), 0xFFD32F2F.toInt(), 0xFFE53935.toInt(),
            0xFFEF5350.toInt(), 0xFFE57373.toInt(), 0xFFEF9A9A.toInt(), 0xFFFFCDD2.toInt(),
            // Pinks
            0xFF880E4F.toInt(), 0xFFAD1457.toInt(), 0xFFC2185B.toInt(), 0xFFD81B60.toInt(),
            0xFFE91E63.toInt(), 0xFFEC407A.toInt(), 0xFFF48FB1.toInt(), 0xFFFCE4EC.toInt(),
            // Purples
            0xFF4A148C.toInt(), 0xFF6A1B9A.toInt(), 0xFF7B1FA2.toInt(), 0xFF8E24AA.toInt(),
            0xFF9C27B0.toInt(), 0xFFAB47BC.toInt(), 0xFFCE93D8.toInt(), 0xFFF3E5F5.toInt(),
            // Deep purple
            0xFF311B92.toInt(), 0xFF4527A0.toInt(), 0xFF512DA8.toInt(), 0xFF5E35B1.toInt(),
            0xFF673AB7.toInt(), 0xFF7E57C2.toInt(), 0xFFB39DDB.toInt(), 0xFFEDE7F6.toInt(),
            // Blues
            0xFF0D47A1.toInt(), 0xFF1565C0.toInt(), 0xFF1976D2.toInt(), 0xFF1E88E5.toInt(),
            0xFF2196F3.toInt(), 0xFF42A5F5.toInt(), 0xFF90CAF9.toInt(), 0xFFE3F2FD.toInt(),
            // Cyan
            0xFF006064.toInt(), 0xFF00838F.toInt(), 0xFF0097A7.toInt(), 0xFF00ACC1.toInt(),
            0xFF00BCD4.toInt(), 0xFF26C6DA.toInt(), 0xFF80DEEA.toInt(), 0xFFE0F7FA.toInt(),
            // Teal
            0xFF004D40.toInt(), 0xFF00695C.toInt(), 0xFF00796B.toInt(), 0xFF00897B.toInt(),
            0xFF009688.toInt(), 0xFF26A69A.toInt(), 0xFF80CBC4.toInt(), 0xFFE0F2F1.toInt(),
            // Greens
            0xFF1B5E20.toInt(), 0xFF2E7D32.toInt(), 0xFF388E3C.toInt(), 0xFF43A047.toInt(),
            0xFF4CAF50.toInt(), 0xFF66BB6A.toInt(), 0xFFA5D6A7.toInt(), 0xFFE8F5E9.toInt(),
            // Lime
            0xFF827717.toInt(), 0xFF9E9D24.toInt(), 0xFFC6CA10.toInt(), 0xFFD4E157.toInt(),
            0xFFEEFF41.toInt(), 0xFFF0F4C3.toInt(), 0xFFF9FBE7.toInt(), 0xFFFFFFFF.toInt(),
            // Amber / Orange
            0xFFE65100.toInt(), 0xFFF57C00.toInt(), 0xFFFB8C00.toInt(), 0xFFFF9800.toInt(),
            0xFFFFA726.toInt(), 0xFFFFB74D.toInt(), 0xFFFFCC80.toInt(), 0xFFFFF8E1.toInt(),
            // Deep orange
            0xFFBF360C.toInt(), 0xFFD84315.toInt(), 0xFFE64A19.toInt(), 0xFFF4511E.toInt(),
            0xFFFF5722.toInt(), 0xFFFF7043.toInt(), 0xFFFFAB91.toInt(), 0xFFFBE9E7.toInt(),
            // Browns
            0xFF3E2723.toInt(), 0xFF4E342E.toInt(), 0xFF5D4037.toInt(), 0xFF6D4C41.toInt(),
            0xFF795548.toInt(), 0xFF8D6E63.toInt(), 0xFFBCAAA4.toInt(), 0xFFEFEBE9.toInt(),
            // Greys / Black
            0xFF000000.toInt(), 0xFF212121.toInt(), 0xFF424242.toInt(), 0xFF616161.toInt(),
            0xFF757575.toInt(), 0xFF9E9E9E.toInt(), 0xFFBDBDBD.toInt(), 0xFFE0E0E0.toInt(),
            // Blue grey
            0xFF263238.toInt(), 0xFF37474F.toInt(), 0xFF455A64.toInt(), 0xFF546E7A.toInt(),
            0xFF607D8B.toInt(), 0xFF78909C.toInt(), 0xFFB0BEC5.toInt(), 0xFFECEFF1.toInt()
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Swatch adapter
// ─────────────────────────────────────────────────────────────────────────────

class TvSwatchAdapter(
    private val colors: List<Int>,
    private val onSelected: (Int) -> Unit
) : RecyclerView.Adapter<TvSwatchAdapter.VH>() {

    private var selectedPos = -1

    fun clearSelection() {
        val old = selectedPos
        selectedPos = -1
        if (old >= 0) notifyItemChanged(old)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_swatch_tv, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)
    override fun getItemCount() = colors.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val swatch: View = itemView.findViewById(R.id.swatchColor)
        private val check:  View = itemView.findViewById(R.id.swatchCheck)

        fun bind(position: Int) {
            val color   = colors[position]
            val ctx     = swatch.context
            val density = ctx.resources.displayMetrics.density

            val d = GradientDrawable()
            d.shape = GradientDrawable.RECTANGLE
            d.cornerRadius = 6f
            d.setColor(color)
            swatch.background = d
            check.visibility = if (position == selectedPos) View.VISIBLE else View.GONE

            // Focus ring drawn as foreground so it appears OVER swatchColor (match_parent child)
            fun applyFocusRing(hasFocus: Boolean) {
                if (hasFocus) {
                    val ring = GradientDrawable()
                    ring.shape = GradientDrawable.RECTANGLE
                    ring.cornerRadius = 10f
                    ring.setColor(Color.TRANSPARENT)
                    ring.setStroke(
                        (3 * density).toInt(),
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.tv_accent)
                    )
                    itemView.foreground = ring
                } else {
                    itemView.foreground = null
                }
            }
            itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                applyFocusRing(hasFocus)
            }
            applyFocusRing(itemView.hasFocus())

            itemView.setOnClickListener {
                val old = selectedPos
                selectedPos = position
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(position)
                onSelected(color)
            }
        }
    }
}
