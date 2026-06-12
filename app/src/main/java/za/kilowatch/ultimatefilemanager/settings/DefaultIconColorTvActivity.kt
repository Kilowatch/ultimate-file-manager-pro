package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.TvColorPickerActivity

/**
 * Full-screen TV activity for customising the default icon tint colour per theme.
 *
 * Follows the same two-panel pattern as [za.kilowatch.ultimatefilemanager.storage.TileColorTvActivity]:
 * left panel has a RecyclerView of theme sections (Light / Dark / AMOLED),
 * right panel shows a live icon preview.
 *
 * Must satisfy the three Activity conventions:
 * 1. [attachBaseContext] for locale wrapping.
 * 2. Edge-to-edge via [enableEdgeToEdge].
 * 3. Single activity with one layout ([R.layout.activity_default_icon_color_tv]).
 */
class DefaultIconColorTvActivity : AppCompatActivity() {

    // ── State ───────────────────────────────────────────────────────────────

    private var lightColor: Int  = DefaultIconColorManager.DEFAULT_LIGHT
    private var darkColor: Int   = DefaultIconColorManager.DEFAULT_DARK
    private var amoledColor: Int = DefaultIconColorManager.DEFAULT_AMOLED

    private lateinit var tvPreviewIcon: ImageView
    private lateinit var tvPreviewHex: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ThemeSectionAdapter

    // Launcher for TvColorPickerActivity
    private var pendingSection: Int = ThemeHelper.THEME_DARK
    private val colorPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val color = result.data?.getIntExtra(TvColorPickerActivity.EXTRA_COLOR, -1) ?: -1
            if (color != -1) {
                setSectionColor(pendingSection, color)
                adapter.notifyDataSetChanged()
                updatePreview()
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_default_icon_color_tv)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Load current custom colours
        lightColor  = DefaultIconColorManager.getCustomColor(this, ThemeHelper.THEME_LIGHT)  ?: DefaultIconColorManager.DEFAULT_LIGHT
        darkColor   = DefaultIconColorManager.getCustomColor(this, ThemeHelper.THEME_DARK)   ?: DefaultIconColorManager.DEFAULT_DARK
        amoledColor = DefaultIconColorManager.getCustomColor(this, ThemeHelper.THEME_AMOLED) ?: DefaultIconColorManager.DEFAULT_AMOLED

        tvPreviewIcon = findViewById(R.id.tvPreviewIcon)
        tvPreviewHex  = findViewById(R.id.tvPreviewHex)

        // RecyclerView with 3 theme sections
        recycler = findViewById(R.id.recyclerThemeSections)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ThemeSectionAdapter()
        recycler.adapter = adapter

        // Done button
        findViewById<MaterialButton>(R.id.btnTvDone)?.setOnClickListener {
            saveAndFinish()
        }

        // Reset all
        findViewById<MaterialButton>(R.id.btnTvResetAll)?.setOnClickListener {
            lightColor  = DefaultIconColorManager.DEFAULT_LIGHT
            darkColor   = DefaultIconColorManager.DEFAULT_DARK
            amoledColor = DefaultIconColorManager.DEFAULT_AMOLED
            adapter.notifyDataSetChanged()
            updatePreview()
        }

        updatePreview()
    }

    // ── Save ────────────────────────────────────────────────────────────────

    private fun saveAndFinish() {
        DefaultIconColorManager.setCustomColor(this, ThemeHelper.THEME_LIGHT,  lightColor)
        DefaultIconColorManager.setCustomColor(this, ThemeHelper.THEME_DARK,   darkColor)
        DefaultIconColorManager.setCustomColor(this, ThemeHelper.THEME_AMOLED, amoledColor)
        DefaultIconColorManager.invalidateCache()
        finish()
    }

    // ── Preview ─────────────────────────────────────────────────────────────

    private fun updatePreview() {
        val theme = ThemeHelper.getSavedTheme(this)
        val color = sectionColor(theme)
        tvPreviewIcon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        tvPreviewHex.text = "#%06X".format(0xFFFFFF and color)
    }

    // ── Colour helpers ──────────────────────────────────────────────────────

    private fun sectionColor(section: Int): Int = when (section) {
        ThemeHelper.THEME_LIGHT  -> lightColor
        ThemeHelper.THEME_DARK   -> darkColor
        ThemeHelper.THEME_AMOLED -> amoledColor
        else -> darkColor
    }

    private fun setSectionColor(section: Int, color: Int) {
        when (section) {
            ThemeHelper.THEME_LIGHT  -> lightColor  = color
            ThemeHelper.THEME_DARK   -> darkColor   = color
            ThemeHelper.THEME_AMOLED -> amoledColor = color
        }
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    private inner class ThemeSectionAdapter : RecyclerView.Adapter<ThemeSectionAdapter.ViewHolder>() {

        private val sections = listOf(
            SectionData(ThemeHelper.THEME_LIGHT,  R.string.default_icon_color_section_light,  DefaultIconColorManager.DEFAULT_LIGHT),
            SectionData(ThemeHelper.THEME_DARK,   R.string.default_icon_color_section_dark,   DefaultIconColorManager.DEFAULT_DARK),
            SectionData(ThemeHelper.THEME_AMOLED, R.string.default_icon_color_section_amoled, DefaultIconColorManager.DEFAULT_AMOLED)
        )

        private val tvPresetColors = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF7DAECC.toInt(), 0xFFE8C98A.toInt(),
            0xFF1C2B3A.toInt(), 0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF757575.toInt()
        )

        override fun getItemCount() = sections.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tv_theme_section, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val data = sections[position]
            holder.bind(data)
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val card: MaterialCardView = itemView as MaterialCardView
            private val txtTitle: TextView = itemView.findViewById(R.id.txtSectionTitle)
            private val swatchCurrent: View = itemView.findViewById(R.id.swatchSectionCurrent)
            private val presetsContainer: ViewGroup = itemView.findViewById(R.id.presetsSection)
            private val btnCustom: TextView = itemView.findViewById(R.id.btnSectionCustom)
            private val btnReset: TextView = itemView.findViewById(R.id.btnSectionReset)

            fun bind(data: SectionData) {
                val ctx = itemView.context
                val currentColor = sectionColor(data.section)

                txtTitle.setText(data.titleRes)
                swatchCurrent.setBackgroundColor(currentColor)

                // Presets
                for (i in 0 until presetsContainer.childCount.coerceAtMost(tvPresetColors.size)) {
                    val presetView = presetsContainer.getChildAt(i)
                    val color = tvPresetColors[i]
                    presetView?.setBackgroundColor(color)
                    presetView?.setOnClickListener {
                        setSectionColor(data.section, color)
                        swatchCurrent.setBackgroundColor(color)
                        updatePreview()
                    }
                }

                // Custom → TvColorPickerActivity
                btnCustom.setOnClickListener {
                    pendingSection = data.section
                    val intent = Intent(ctx, TvColorPickerActivity::class.java)
                    intent.putExtra(TvColorPickerActivity.EXTRA_COLOR, currentColor)
                    colorPickerLauncher.launch(intent)
                }

                // Reset
                btnReset.setOnClickListener {
                    setSectionColor(data.section, data.defaultColor)
                    swatchCurrent.setBackgroundColor(data.defaultColor)
                    updatePreview()
                }

                // TV D-pad focus
                val yellowFill  = ctx.getColor(R.color.tv_button_focused_yellow)
                val blackText   = ctx.getColor(R.color.tv_button_focused_yellow_text)
                val glassColor  = ctx.getColor(R.color.tv_glass_white_10)
                val primaryText = ctx.getColor(R.color.tv_text_primary)
                val secondText  = ctx.getColor(R.color.tv_text_secondary)

                card.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        card.setCardBackgroundColor(yellowFill)
                        txtTitle.setTextColor(blackText)
                        btnCustom.setTextColor(blackText)
                        btnReset.setTextColor(blackText)
                    } else {
                        card.setCardBackgroundColor(glassColor)
                        txtTitle.setTextColor(primaryText)
                        btnCustom.setTextColor(ctx.getColor(R.color.tv_accent))
                        btnReset.setTextColor(secondText)
                    }
                }
            }
        }
    }

    private data class SectionData(val section: Int, val titleRes: Int, val defaultColor: Int)

    companion object {
        private const val TAG = "DefaultIconColorTv"
    }
}
