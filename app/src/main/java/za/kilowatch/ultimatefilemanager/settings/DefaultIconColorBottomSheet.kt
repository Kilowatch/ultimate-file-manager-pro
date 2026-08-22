package za.kilowatch.ultimatefilemanager.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.ui.HexColorHelper
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
        val defaultColor = DefaultIconColorManager.getDefaultColor(ctx)
        lightColor  = DefaultIconColorManager.getCustomColor(ctx, ThemeHelper.THEME_LIGHT)  ?: defaultColor
        darkColor   = DefaultIconColorManager.getCustomColor(ctx, ThemeHelper.THEME_DARK)   ?: defaultColor
        amoledColor = DefaultIconColorManager.getCustomColor(ctx, ThemeHelper.THEME_AMOLED) ?: defaultColor

        // Render initial state
        updatePreview()
        setupSection(
            section = ThemeHelper.THEME_LIGHT,
            swatchCurrent = R.id.swatchLightCurrent,
            presetsContainer = R.id.presetsLight,
            btnCustom = R.id.btnCustomLight,
            btnReset = R.id.btnResetLight
        )
        setupSection(
            section = ThemeHelper.THEME_DARK,
            swatchCurrent = R.id.swatchDarkCurrent,
            presetsContainer = R.id.presetsDark,
            btnCustom = R.id.btnCustomDark,
            btnReset = R.id.btnResetDark
        )
        setupSection(
            section = ThemeHelper.THEME_AMOLED,
            swatchCurrent = R.id.swatchAmoledCurrent,
            presetsContainer = R.id.presetsAmoled,
            btnCustom = R.id.btnCustomAmoled,
            btnReset = R.id.btnResetAmoled
        )

        // Reset all
        view.findViewById<View>(R.id.btnResetAll)?.setOnClickListener {
            val default = DefaultIconColorManager.getDefaultColor(requireContext())
            lightColor  = default
            darkColor   = default
            amoledColor = default
            refreshAllSwatches()
            updatePreview()
        }

        // Done
        view.findViewById<View>(R.id.btnDone)?.setOnClickListener {
            val context = requireContext()
            val default = DefaultIconColorManager.getDefaultColor(context)

            if (lightColor == default) {
                DefaultIconColorManager.resetCustomColor(context, ThemeHelper.THEME_LIGHT)
            } else {
                DefaultIconColorManager.setCustomColor(context, ThemeHelper.THEME_LIGHT, lightColor)
            }

            if (darkColor == default) {
                DefaultIconColorManager.resetCustomColor(context, ThemeHelper.THEME_DARK)
            } else {
                DefaultIconColorManager.setCustomColor(context, ThemeHelper.THEME_DARK, darkColor)
            }

            if (amoledColor == default) {
                DefaultIconColorManager.resetCustomColor(context, ThemeHelper.THEME_AMOLED)
            } else {
                DefaultIconColorManager.setCustomColor(context, ThemeHelper.THEME_AMOLED, amoledColor)
            }

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
        btnReset: Int
    ) {
        val view = requireView()

        // Current swatch
        val swatch = view.findViewById<View>(swatchCurrent)
        swatch?.backgroundTintList = ColorStateList.valueOf(currentColor(section))

        // Presets
        val container = view.findViewById<ViewGroup>(presetsContainer) ?: return
        for (i in 0 until container.childCount.coerceAtMost(presets.size)) {
            val presetView = container.getChildAt(i)
            presetView?.backgroundTintList = ColorStateList.valueOf(presets[i])
            presetView?.setOnClickListener {
                setCurrentColor(section, presets[i])
                swatch?.backgroundTintList = ColorStateList.valueOf(presets[i])
                updatePreview()
            }
        }

        // Custom button → HSV colour picker
        view.findViewById<View>(btnCustom)?.setOnClickListener {
            showColorPickerDialog(currentColor(section)) { picked ->
                setCurrentColor(section, picked)
                swatch?.backgroundTintList = ColorStateList.valueOf(picked)
                updatePreview()
            }
        }

        // Reset button
        view.findViewById<View>(btnReset)?.setOnClickListener {
            val default = DefaultIconColorManager.getDefaultColor(requireContext())
            setCurrentColor(section, default)
            swatch?.backgroundTintList = ColorStateList.valueOf(default)
            updatePreview()
        }
    }

    // ── Live preview ────────────────────────────────────────────────────────

    private fun updatePreview() {
        val view = view ?: return
        val previewIcon1 = view.findViewById<ImageView>(R.id.imgPreviewIcon)
        val previewIcon2 = view.findViewById<ImageView>(R.id.imgPreviewIcon2)
        val previewIcon3 = view.findViewById<ImageView>(R.id.imgPreviewIcon3)
        val previewIcon4 = view.findViewById<ImageView>(R.id.imgPreviewIcon4)
        val hexLabel = view.findViewById<TextView>(R.id.txtPreviewHex) ?: return

        val ctx = requireContext()
        val theme = ThemeHelper.getSavedTheme(ctx)
        val color = currentColor(theme)

        val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        previewIcon1?.colorFilter = filter
        previewIcon2?.colorFilter = filter
        previewIcon3?.colorFilter = filter
        previewIcon4?.colorFilter = filter

        hexLabel.text = "#%06X".format(0xFFFFFF and color)
    }

    private fun refreshAllSwatches() {
        val view = view ?: return
        view.findViewById<View>(R.id.swatchLightCurrent)?.backgroundTintList = ColorStateList.valueOf(lightColor)
        view.findViewById<View>(R.id.swatchDarkCurrent)?.backgroundTintList = ColorStateList.valueOf(darkColor)
        view.findViewById<View>(R.id.swatchAmoledCurrent)?.backgroundTintList = ColorStateList.valueOf(amoledColor)
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
        val ctx = context ?: return
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_color_picker, null)

        val palette      = dialogView.findViewById<HsvPaletteView>(R.id.hsvPalette)
        val hueSlider    = dialogView.findViewById<HueSliderView>(R.id.hueSlider)
        val colorPreview = dialogView.findViewById<View>(R.id.colorPreview)
        val hexInput     = dialogView.findViewById<EditText>(R.id.hexInput)

        // ── Hex-only InputFilter ────────────────────────────────────────────
        hexInput.filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
            source.filter { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
        })

        // ── Re-entrancy guard ───────────────────────────────────────────────
        var isUpdatingHex = false

        // ── Helper: push colour from visual controls into hex field ─────────
        fun syncHexFromColor(color: Int) {
            if (isUpdatingHex) return
            isUpdatingHex = true
            hexInput.setText(HexColorHelper.formatHex(color).removePrefix("#"))
            hexInput.setSelection(hexInput.text.length)
            isUpdatingHex = false
        }

        // ── Hex TextWatcher (hex → visual controls) ─────────────────────────
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingHex) return
                val hex = s?.toString() ?: ""
                HexColorHelper.parseHex(hex)?.let { color ->
                    palette.setColor(color)
                    hueSlider.currentHue = palette.currentHue
                    colorPreview.backgroundTintList = ColorStateList.valueOf(color)
                }
            }
        })

        palette.setColor(initialColor)
        hueSlider.currentHue = palette.currentHue
        colorPreview.backgroundTintList = ColorStateList.valueOf(initialColor)

        // Prefill hex field from saved colour
        if (initialColor != Color.TRANSPARENT) {
            hexInput.setText(HexColorHelper.formatHex(initialColor).removePrefix("#"))
            hexInput.setSelection(hexInput.text.length)
        }

        palette.onColorChanged = { color ->
            hueSlider.currentHue = palette.currentHue
            colorPreview.backgroundTintList = ColorStateList.valueOf(color)
            syncHexFromColor(color)
        }

        hueSlider.onHueChanged = { hue ->
            palette.setHue(hue)
            colorPreview.backgroundTintList = ColorStateList.valueOf(palette.selectedColor)
            syncHexFromColor(palette.selectedColor)
        }

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

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

        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.btnSelect)?.setOnClickListener {
            dialog.dismiss()
            // Priority: valid hex → use hex; otherwise → use visual selection
            val hexText = hexInput.text?.toString() ?: ""
            val color = HexColorHelper.parseHex(hexText) ?: palette.selectedColor
            onColorSelected(color)
        }

        dialog.show()
    }
}
