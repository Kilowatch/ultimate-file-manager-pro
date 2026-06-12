package za.kilowatch.ultimatefilemanager.settings

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.ui.HsvPaletteView
import za.kilowatch.ultimatefilemanager.ui.HueSliderView

/**
 * Bottom sheet dialog that lets users customise the default icon tint colour
 * for each theme (Light, Dark, AMOLED).
 *
 * Follows the same pattern as [TileColorBottomSheet] and reuses the same HSV
 * colour picker dialog ([dialog_color_picker.xml], [HsvPaletteView], [HueSliderView]).
 */
class DefaultIconColorBottomSheet : BottomSheetDialogFragment() {

    // ── Per-theme state ─────────────────────────────────────────────────────

    private var lightColor: Int = DefaultIconColorManager.DEFAULT_LIGHT
    private var darkColor: Int = DefaultIconColorManager.DEFAULT_DARK
    private var amoledColor: Int = DefaultIconColorManager.DEFAULT_AMOLED

    // ── Preset colour values ────────────────────────────────────────────────

    private val presets = intArrayOf(
        0xFF000000.toInt(),  // Black
        0xFFFFFFFF.toInt(),  // White
        0xFF7DAECC.toInt(),  // Muted blue-gray (Dark default)
        0xFFE8C98A.toInt(),  // Warm gold/tan (AMOLED default)
        0xFF1C2B3A.toInt(),  // Dark slate (Light default)
        0xFFE53935.toInt(),  // Red
        0xFF1E88E5.toInt(),  // Blue
        0xFF757575.toInt()   // Grey
    )

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_default_icon_colors, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load current custom colours from preferences
        val ctx = requireContext()
        lightColor  = DefaultIconColorManager.getCustomColor(ctx, ThemeHelper.THEME_LIGHT)  ?: DefaultIconColorManager.DEFAULT_LIGHT
        darkColor   = DefaultIconColorManager.getCustomColor(ctx, ThemeHelper.THEME_DARK)   ?: DefaultIconColorManager.DEFAULT_DARK
        amoledColor = DefaultIconColorManager.getCustomColor(ctx, ThemeHelper.THEME_AMOLED) ?: DefaultIconColorManager.DEFAULT_AMOLED

        // Render initial state
        updatePreview()
        setupSection(
            section = ThemeHelper.THEME_LIGHT,
            swatchCurrent = R.id.swatchLightCurrent,
            presetsContainer = R.id.presetsLight,
            btnCustom = R.id.btnCustomLight,
            btnReset = R.id.btnResetLight,
            presetBaseId = R.id.swatchLight0
        )
        setupSection(
            section = ThemeHelper.THEME_DARK,
            swatchCurrent = R.id.swatchDarkCurrent,
            presetsContainer = R.id.presetsDark,
            btnCustom = R.id.btnCustomDark,
            btnReset = R.id.btnResetDark,
            presetBaseId = R.id.swatchDark0
        )
        setupSection(
            section = ThemeHelper.THEME_AMOLED,
            swatchCurrent = R.id.swatchAmoledCurrent,
            presetsContainer = R.id.presetsAmoled,
            btnCustom = R.id.btnCustomAmoled,
            btnReset = R.id.btnResetAmoled,
            presetBaseId = R.id.swatchAmoled0
        )

        // Reset all
        view.findViewById<TextView>(R.id.btnResetAll)?.setOnClickListener {
            lightColor  = DefaultIconColorManager.DEFAULT_LIGHT
            darkColor   = DefaultIconColorManager.DEFAULT_DARK
            amoledColor = DefaultIconColorManager.DEFAULT_AMOLED
            refreshAllSwatches()
            updatePreview()
        }

        // Done
        view.findViewById<TextView>(R.id.btnDone)?.setOnClickListener {
            val ctx = requireContext()
            DefaultIconColorManager.setCustomColor(ctx, ThemeHelper.THEME_LIGHT,  lightColor)
            DefaultIconColorManager.setCustomColor(ctx, ThemeHelper.THEME_DARK,   darkColor)
            DefaultIconColorManager.setCustomColor(ctx, ThemeHelper.THEME_AMOLED, amoledColor)
            DefaultIconColorManager.invalidateCache()
            dismiss()
        }
    }

    // ── Section setup ───────────────────────────────────────────────────────

    private fun setupSection(
        section: Int,
        swatchCurrent: Int,
        presetsContainer: Int,
        btnCustom: Int,
        btnReset: Int,
        presetBaseId: Int
    ) {
        val view = requireView()

        // Current swatch
        val swatch = view.findViewById<View>(swatchCurrent)
        swatch?.setBackgroundColor(currentColor(section))

        // Presets
        val container = view.findViewById<ViewGroup>(presetsContainer) ?: return
        for (i in 0 until container.childCount.coerceAtMost(presets.size)) {
            val presetView = container.getChildAt(i)
            presetView?.setBackgroundColor(presets[i])
            presetView?.setOnClickListener {
                setCurrentColor(section, presets[i])
                swatch?.setBackgroundColor(presets[i])
                updatePreview()
            }
        }

        // Custom button → HSV colour picker
        view.findViewById<TextView>(btnCustom)?.setOnClickListener {
            showColorPickerDialog(currentColor(section)) { picked ->
                setCurrentColor(section, picked)
                swatch?.setBackgroundColor(picked)
                updatePreview()
            }
        }

        // Reset button
        view.findViewById<TextView>(btnReset)?.setOnClickListener {
            val default = when (section) {
                ThemeHelper.THEME_LIGHT  -> DefaultIconColorManager.DEFAULT_LIGHT
                ThemeHelper.THEME_DARK   -> DefaultIconColorManager.DEFAULT_DARK
                ThemeHelper.THEME_AMOLED -> DefaultIconColorManager.DEFAULT_AMOLED
                else -> DefaultIconColorManager.DEFAULT_DARK
            }
            setCurrentColor(section, default)
            swatch?.setBackgroundColor(default)
            updatePreview()
        }
    }

    // ── Live preview ────────────────────────────────────────────────────────

    private fun updatePreview() {
        val view = view ?: return
        val previewIcon = view.findViewById<android.widget.ImageView>(R.id.imgPreviewIcon) ?: return
        val hexLabel = view.findViewById<TextView>(R.id.txtPreviewHex) ?: return

        val ctx = requireContext()
        val theme = ThemeHelper.getSavedTheme(ctx)
        val color = currentColor(theme)

        previewIcon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        hexLabel.text = "#%06X".format(0xFFFFFF and color)
    }

    private fun refreshAllSwatches() {
        val view = view ?: return
        view.findViewById<View>(R.id.swatchLightCurrent)?.setBackgroundColor(lightColor)
        view.findViewById<View>(R.id.swatchDarkCurrent)?.setBackgroundColor(darkColor)
        view.findViewById<View>(R.id.swatchAmoledCurrent)?.setBackgroundColor(amoledColor)
    }

    // ── Colour helpers ──────────────────────────────────────────────────────

    private fun currentColor(section: Int): Int = when (section) {
        ThemeHelper.THEME_LIGHT  -> lightColor
        ThemeHelper.THEME_DARK   -> darkColor
        ThemeHelper.THEME_AMOLED -> amoledColor
        else -> darkColor
    }

    private fun setCurrentColor(section: Int, color: Int) {
        when (section) {
            ThemeHelper.THEME_LIGHT  -> lightColor  = color
            ThemeHelper.THEME_DARK   -> darkColor   = color
            ThemeHelper.THEME_AMOLED -> amoledColor = color
        }
    }

    // ── HSV colour picker dialog (reused from TileColorBottomSheet pattern) ─

    private fun showColorPickerDialog(initialColor: Int, onColorSelected: (Int) -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)

        val palette      = dialogView.findViewById<HsvPaletteView>(R.id.hsvPalette)
        val hueSlider    = dialogView.findViewById<HueSliderView>(R.id.hueSlider)
        val colorPreview = dialogView.findViewById<View>(R.id.colorPreview)

        palette.setColor(initialColor)
        hueSlider.currentHue = palette.currentHue
        colorPreview.setBackgroundColor(initialColor)

        palette.onColorChanged = { color ->
            hueSlider.currentHue = palette.currentHue
            colorPreview.setBackgroundColor(color)
        }

        hueSlider.onHueChanged = { hue ->
            palette.setHue(hue)
            colorPreview.setBackgroundColor(palette.selectedColor)
        }

        val builder = android.app.AlertDialog.Builder(context)
            .setView(dialogView)
        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Preset swatches in the picker dialog
        dialogView.findViewById<View>(R.id.swatchBlack)?.setOnClickListener {
            dialog.dismiss(); onColorSelected(0xFF000000.toInt())
        }
        dialogView.findViewById<View>(R.id.swatchWhite)?.setOnClickListener {
            dialog.dismiss(); onColorSelected(0xFFFFFFFF.toInt())
        }
        dialogView.findViewById<View>(R.id.swatchRed)?.setOnClickListener {
            dialog.dismiss(); onColorSelected(0xFFE53935.toInt())
        }
        dialogView.findViewById<View>(R.id.swatchOrange)?.setOnClickListener {
            dialog.dismiss(); onColorSelected(0xFFFB8C00.toInt())
        }
        dialogView.findViewById<View>(R.id.swatchYellow)?.setOnClickListener {
            dialog.dismiss(); onColorSelected(0xFFFFEB3B.toInt())
        }
        dialogView.findViewById<View>(R.id.swatchGreen)?.setOnClickListener {
            dialog.dismiss(); onColorSelected(0xFF4CAF50.toInt())
        }
        dialogView.findViewById<View>(R.id.swatchBlue)?.setOnClickListener {
            dialog.dismiss(); onColorSelected(0xFF1E88E5.toInt())
        }
        dialogView.findViewById<View>(R.id.swatchPurple)?.setOnClickListener {
            dialog.dismiss(); onColorSelected(0xFF8E24AA.toInt())
        }

        dialogView.findViewById<TextView>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<TextView>(R.id.btnSelect)?.setOnClickListener {
            dialog.dismiss()
            onColorSelected(palette.selectedColor)
        }

        dialog.show()
    }
}
