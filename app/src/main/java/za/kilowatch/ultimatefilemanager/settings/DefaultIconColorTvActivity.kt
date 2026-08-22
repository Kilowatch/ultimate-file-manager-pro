package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.TvColorPickerActivity
import za.kilowatch.ultimatefilemanager.ui.HexColorHelper

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

    private var selectedThemeForPreview: Int = ThemeHelper.THEME_DARK

    private val tvPresetColors = intArrayOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF7DAECC.toInt(), 0xFFE8C98A.toInt(),
        0xFF1C2B3A.toInt(), 0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF757575.toInt()
    )

    // Dialog state variables
    private var activeDialog: android.app.AlertDialog? = null
    private var activeDialogData: SectionData? = null
    private var activeDialogTempColor: Int = 0
    private var activeDialogPreview: View? = null
    private var activeDialogHexInput: EditText? = null

    // Launcher for TvColorPickerActivity
    private var pendingSection: Int = ThemeHelper.THEME_DARK
    private val colorPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data != null && data.hasExtra(TvColorPickerActivity.EXTRA_COLOR)) {
                val color = data.getIntExtra(TvColorPickerActivity.EXTRA_COLOR, Color.WHITE)
                val dialog = activeDialog
                if (dialog != null && dialog.isShowing) {
                    activeDialogTempColor = color
                    val d = GradientDrawable()
                    d.shape = GradientDrawable.OVAL
                    d.setColor(color)
                    activeDialogPreview?.background = d
                    activeDialogHexInput?.setText(HexColorHelper.formatHex(color).removePrefix("#"))
                    (dialog.findViewById<RecyclerView>(R.id.dialogPresetsGrid)?.adapter as? DialogPresetsAdapter)?.updateSelected(color)
                } else {
                    setSectionColor(pendingSection, color)
                    adapter.notifyDataSetChanged()
                    updatePreview()
                }
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
        val defaultColor = DefaultIconColorManager.getDefaultColor(this)
        lightColor  = DefaultIconColorManager.getCustomColor(this, ThemeHelper.THEME_LIGHT)  ?: defaultColor
        darkColor   = DefaultIconColorManager.getCustomColor(this, ThemeHelper.THEME_DARK)   ?: defaultColor
        amoledColor = DefaultIconColorManager.getCustomColor(this, ThemeHelper.THEME_AMOLED) ?: defaultColor

        tvPreviewIcon = findViewById(R.id.tvPreviewIcon)
        tvPreviewHex  = findViewById(R.id.tvPreviewHex)

        // Set default preview theme to match current active app theme
        selectedThemeForPreview = ThemeHelper.getSavedTheme(this)

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
            val default = DefaultIconColorManager.getDefaultColor(this)
            lightColor  = default
            darkColor   = default
            amoledColor = default
            adapter.notifyDataSetChanged()
            updatePreview()
        }

        updatePreview()
    }

    // ── Save ────────────────────────────────────────────────────────────────

    private fun saveAndFinish() {
        val default = DefaultIconColorManager.getDefaultColor(this)

        if (lightColor == default) {
            DefaultIconColorManager.resetCustomColor(this, ThemeHelper.THEME_LIGHT)
        } else {
            DefaultIconColorManager.setCustomColor(this, ThemeHelper.THEME_LIGHT,  lightColor)
        }

        if (darkColor == default) {
            DefaultIconColorManager.resetCustomColor(this, ThemeHelper.THEME_DARK)
        } else {
            DefaultIconColorManager.setCustomColor(this, ThemeHelper.THEME_DARK,   darkColor)
        }

        if (amoledColor == default) {
            DefaultIconColorManager.resetCustomColor(this, ThemeHelper.THEME_AMOLED)
        } else {
            DefaultIconColorManager.setCustomColor(this, ThemeHelper.THEME_AMOLED, amoledColor)
        }

        DefaultIconColorManager.invalidateCache()
        finish()
    }

    // ── Preview ─────────────────────────────────────────────────────────────

    private fun updatePreview() {
        val color = sectionColor(selectedThemeForPreview)
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

    // ── Dialog Creation ─────────────────────────────────────────────────────

    private fun showColorDialog(data: SectionData) {
        activeDialogData = data
        activeDialogTempColor = sectionColor(data.section)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_default_icon_color_tv, null)
        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        activeDialogPreview = dialogView.findViewById(R.id.dialogSelectedColorPreview)
        activeDialogHexInput = dialogView.findViewById(R.id.dialogSelectedColorHex)
        val recyclerPresets = dialogView.findViewById<RecyclerView>(R.id.dialogPresetsGrid)
        val btnCustom = dialogView.findViewById<View>(R.id.dialogBtnCustom)
        val btnReset = dialogView.findViewById<View>(R.id.dialogBtnReset)
        val btnCancel = dialogView.findViewById<View>(R.id.dialogBtnCancel)
        val btnApply = dialogView.findViewById<View>(R.id.dialogBtnApply)

        dialogTitle.text = getString(data.titleRes) + " Theme Icon Color"

        // ── Hex-only InputFilter ────────────────────────────────────────────
        activeDialogHexInput?.filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
            source.filter { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
        })

        // ── Re-entrancy guard ───────────────────────────────────────────────
        var isUpdatingHex = false

        // ── Shared preview updater ──────────────────────────────────────────
        fun updateDialogPreview() {
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(activeDialogTempColor)
            activeDialogPreview?.background = d
            if (!isUpdatingHex) {
                isUpdatingHex = true
                activeDialogHexInput?.setText(HexColorHelper.formatHex(activeDialogTempColor).removePrefix("#"))
                activeDialogHexInput?.setSelection(activeDialogHexInput?.text?.length ?: 0)
                isUpdatingHex = false
            }
        }

        // ── Presets grid ──────────────────────────────────────────────────
        recyclerPresets.layoutManager = GridLayoutManager(this, 4)
        val presetsAdapter = DialogPresetsAdapter(
            presets = tvPresetColors.toList(),
            initialSelected = activeDialogTempColor,
            onSelected = { color ->
                activeDialogTempColor = color
                updateDialogPreview()
            }
        )
        recyclerPresets.adapter = presetsAdapter

        // ── Hex TextWatcher (hex → preview) ─────────────────────────────────
        activeDialogHexInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingHex) return
                val hex = s?.toString() ?: ""
                val parsed = HexColorHelper.parseHex(hex)
                if (parsed != null) {
                    activeDialogTempColor = parsed
                    val d = GradientDrawable()
                    d.shape = GradientDrawable.OVAL
                    d.setColor(parsed)
                    activeDialogPreview?.background = d
                    presetsAdapter.updateSelected(parsed)
                }
            }
        })

        // Prefill hex field from current colour
        if (activeDialogTempColor != Color.TRANSPARENT) {
            isUpdatingHex = true
            activeDialogHexInput?.setText(HexColorHelper.formatHex(activeDialogTempColor).removePrefix("#"))
            activeDialogHexInput?.setSelection(activeDialogHexInput?.text?.length ?: 0)
            isUpdatingHex = false
        }

        updateDialogPreview()

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Translucent_NoTitleBar)
            .setView(dialogView)
            .create()

        dialog.setOnDismissListener {
            activeDialog = null
            activeDialogData = null
        }

        activeDialog = dialog

        // Custom button clicks to open TvColorPickerActivity
        btnCustom.setOnClickListener {
            pendingSection = data.section
            val intent = TvColorPickerActivity.createIntent(this, activeDialogTempColor)
            colorPickerLauncher.launch(intent)
        }

        // Reset button
        btnReset.setOnClickListener {
            activeDialogTempColor = data.defaultColor
            updateDialogPreview()
            presetsAdapter.updateSelected(activeDialogTempColor)
        }

        // Cancel button
        btnCancel.setOnClickListener {
            dialog.dismiss()
            activeDialog = null
            activeDialogData = null
        }

        // Apply button
        btnApply.setOnClickListener {
            // Priority: valid hex → use hex; otherwise → use visual selection
            val hexText = activeDialogHexInput?.text?.toString() ?: ""
            val color = HexColorHelper.parseHex(hexText) ?: activeDialogTempColor
            setSectionColor(data.section, color)
            adapter.notifyDataSetChanged()
            updatePreview()
            dialog.dismiss()
            activeDialog = null
            activeDialogData = null
        }

        dialog.show()
        btnCancel.post {
            btnCancel.requestFocus()
        }
    }

    // ── Theme Section Adapter ───────────────────────────────────────────────

    private inner class ThemeSectionAdapter : RecyclerView.Adapter<ThemeSectionAdapter.ViewHolder>() {

        private val sections = listOf(
            SectionData(ThemeHelper.THEME_LIGHT,  R.string.default_icon_color_section_light,  DefaultIconColorManager.getDefaultColor(this@DefaultIconColorTvActivity)),
            SectionData(ThemeHelper.THEME_DARK,   R.string.default_icon_color_section_dark,   DefaultIconColorManager.getDefaultColor(this@DefaultIconColorTvActivity)),
            SectionData(ThemeHelper.THEME_AMOLED, R.string.default_icon_color_section_amoled, DefaultIconColorManager.getDefaultColor(this@DefaultIconColorTvActivity))
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
            private val txtSubtitle: TextView = itemView.findViewById(R.id.txtSectionSubtitle)

            fun bind(data: SectionData) {
                val ctx = itemView.context
                val currentColor = sectionColor(data.section)

                txtTitle.setText(data.titleRes)

                // Current swatch
                val d = GradientDrawable()
                d.shape = GradientDrawable.OVAL
                d.setColor(currentColor)
                swatchCurrent.background = d

                // Subtitle showing state
                val isDefault = currentColor == data.defaultColor
                txtSubtitle.text = if (isDefault) {
                    ctx.getString(R.string.default_icon_color_uses_default)
                } else {
                    "#%06X".format(0xFFFFFF and currentColor)
                }

                // Focus updates the preview on the right
                card.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        selectedThemeForPreview = data.section
                        updatePreview()
                    }
                }

                // Click opens dialog customization popup
                card.setOnClickListener {
                    showColorDialog(data)
                }
            }
        }
    }

    // ── Dialog Presets Adapter ──────────────────────────────────────────────

    private class DialogPresetsAdapter(
        private val presets: List<Int>,
        initialSelected: Int,
        private val onSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<DialogPresetsAdapter.VH>() {

        private var selectedColor: Int = initialSelected

        fun updateSelected(newColor: Int) {
            val oldPos = presets.indexOf(selectedColor)
            val newPos = presets.indexOf(newColor)
            selectedColor = newColor
            if (oldPos >= 0) notifyItemChanged(oldPos)
            if (newPos >= 0) notifyItemChanged(newPos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_color_swatch_tv, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(presets[position])
        }

        override fun getItemCount() = presets.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val swatch: View = itemView.findViewById(R.id.swatchColor)
            private val check: View = itemView.findViewById(R.id.swatchCheck)

            fun bind(color: Int) {
                val ctx     = swatch.context
                val density = ctx.resources.displayMetrics.density

                val fill = GradientDrawable()
                fill.shape = GradientDrawable.RECTANGLE
                fill.cornerRadius = 6f
                fill.setColor(color)
                swatch.background = fill

                check.visibility = if (color == selectedColor) View.VISIBLE else View.GONE

                fun applyFocusRing(hasFocus: Boolean) {
                    if (hasFocus) {
                        val ring = GradientDrawable()
                        ring.shape = GradientDrawable.RECTANGLE
                        ring.cornerRadius = 10f
                        ring.setColor(Color.TRANSPARENT)
                        ring.setStroke((3 * density).toInt(), ContextCompat.getColor(ctx, R.color.tv_accent))
                        itemView.foreground = ring
                    } else {
                        itemView.foreground = null
                    }
                }
                itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> applyFocusRing(hasFocus) }
                applyFocusRing(itemView.hasFocus())

                itemView.setOnClickListener {
                    updateSelected(color)
                    onSelected(color)
                }
            }
        }
    }

    private data class SectionData(val section: Int, val titleRes: Int, val defaultColor: Int)

    companion object {
        private const val TAG = "DefaultIconColorTv"
    }
}
