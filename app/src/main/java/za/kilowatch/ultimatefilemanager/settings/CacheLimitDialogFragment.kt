package za.kilowatch.ultimatefilemanager.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.ThemeColors
import java.util.Locale

/**
 * Modern Custom Cache Limit Bottom Sheet Dialog.
 * Supports quick preset selection (500 MB, 1 GB, 2 GB, 5 GB) with single-selection toggle group,
 * decimal custom value input, and MB/GB unit selection strictly styled with the active theme's primary colors.
 */
class CacheLimitDialogFragment : BottomSheetDialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val themedInflater = inflater.cloneInContext(ContextThemeWrapper(requireActivity(), requireActivity().theme))
        return themedInflater.inflate(R.layout.dialog_cache_limit_custom, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val act = requireActivity()
        val primaryColor = ThemeColors.primary(act)
        val onPrimaryColor = ThemeColors.onPrimary(act)
        val glassBgColor = act.getColor(R.color.mobile_glass_white_10)
        val textPrimaryColor = act.getColor(R.color.mobile_card_text_primary)

        val imgHeroIcon = view.findViewById<ImageView>(R.id.imgHeroIcon)
        val labelPresetsHeader = view.findViewById<TextView>(R.id.labelPresetsHeader)
        val labelCustomSizeHeader = view.findViewById<TextView>(R.id.labelCustomSizeHeader)

        imgHeroIcon?.imageTintList = ColorStateList.valueOf(primaryColor)
        labelPresetsHeader?.setTextColor(primaryColor)
        labelCustomSizeHeader?.setTextColor(primaryColor)

        val editValue = view.findViewById<EditText>(R.id.editCacheValue)
        val togglePresetsGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.togglePresetsGroup)
        val chipPreset500 = view.findViewById<MaterialButton>(R.id.chipPreset500)
        val chipPreset1gb = view.findViewById<MaterialButton>(R.id.chipPreset1gb)
        val chipPreset2gb = view.findViewById<MaterialButton>(R.id.chipPreset2gb)
        val chipPreset5gb = view.findViewById<MaterialButton>(R.id.chipPreset5gb)

        val toggleUnitGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleUnitGroup)
        val chipMb = view.findViewById<MaterialButton>(R.id.chipMb)
        val chipGb = view.findViewById<MaterialButton>(R.id.chipGb)

        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)

        // Dynamically apply theme color selectors to all toggle buttons
        val toggleBgCsl = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                primaryColor,
                glassBgColor
            )
        )
        val toggleTextCsl = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                onPrimaryColor,
                textPrimaryColor
            )
        )

        val toggleButtons = listOf(chipPreset500, chipPreset1gb, chipPreset2gb, chipPreset5gb, chipMb, chipGb)
        for (btn in toggleButtons) {
            btn?.backgroundTintList = toggleBgCsl
            btn?.setTextColor(toggleTextCsl)
        }

        btnSave.backgroundTintList = ColorStateList.valueOf(primaryColor)
        btnSave.setTextColor(onPrimaryColor)
        btnSave.iconTint = ColorStateList.valueOf(onPrimaryColor)

        var isProgrammaticUpdate = false

        fun syncPresetSelection() {
            if (isProgrammaticUpdate) return
            val raw = editValue.text.toString().trim()
            val value = raw.toDoubleOrNull()
            val isGb = (toggleUnitGroup.checkedButtonId == R.id.chipGb)

            if (value == null) {
                togglePresetsGroup.clearChecked()
                return
            }

            val totalMb = if (isGb) (value * 1024.0).toInt() else value.toInt()
            when (totalMb) {
                500 -> togglePresetsGroup.check(R.id.chipPreset500)
                1024 -> togglePresetsGroup.check(R.id.chipPreset1gb)
                2048 -> togglePresetsGroup.check(R.id.chipPreset2gb)
                5120 -> togglePresetsGroup.check(R.id.chipPreset5gb)
                else -> togglePresetsGroup.clearChecked()
            }
        }

        fun applyPreset(mb: Int) {
            isProgrammaticUpdate = true
            when (mb) {
                500 -> {
                    editValue.setText("500")
                    toggleUnitGroup.check(R.id.chipMb)
                    togglePresetsGroup.check(R.id.chipPreset500)
                }
                1024 -> {
                    editValue.setText("1.0")
                    toggleUnitGroup.check(R.id.chipGb)
                    togglePresetsGroup.check(R.id.chipPreset1gb)
                }
                2048 -> {
                    editValue.setText("2.0")
                    toggleUnitGroup.check(R.id.chipGb)
                    togglePresetsGroup.check(R.id.chipPreset2gb)
                }
                5120 -> {
                    editValue.setText("5.0")
                    toggleUnitGroup.check(R.id.chipGb)
                    togglePresetsGroup.check(R.id.chipPreset5gb)
                }
            }
            editValue.setSelection(editValue.text.length)
            isProgrammaticUpdate = false
        }

        // Initialize with current preference
        val currentMb = NetworkThumbnailPreferenceManager.getCacheLimitMb(requireContext())
        isProgrammaticUpdate = true
        when (currentMb) {
            500 -> {
                editValue.setText("500")
                toggleUnitGroup.check(R.id.chipMb)
                togglePresetsGroup.check(R.id.chipPreset500)
            }
            1024 -> {
                editValue.setText("1.0")
                toggleUnitGroup.check(R.id.chipGb)
                togglePresetsGroup.check(R.id.chipPreset1gb)
            }
            2048 -> {
                editValue.setText("2.0")
                toggleUnitGroup.check(R.id.chipGb)
                togglePresetsGroup.check(R.id.chipPreset2gb)
            }
            5120 -> {
                editValue.setText("5.0")
                toggleUnitGroup.check(R.id.chipGb)
                togglePresetsGroup.check(R.id.chipPreset5gb)
            }
            else -> {
                if (currentMb >= 1024) {
                    val gb = currentMb.toDouble() / 1024.0
                    editValue.setText(String.format(Locale.getDefault(), "%.1f", gb))
                    toggleUnitGroup.check(R.id.chipGb)
                } else {
                    editValue.setText(currentMb.toString())
                    toggleUnitGroup.check(R.id.chipMb)
                }
                togglePresetsGroup.clearChecked()
            }
        }
        editValue.setSelection(editValue.text.length)
        isProgrammaticUpdate = false

        // Preset button click listeners
        chipPreset500.setOnClickListener { applyPreset(500) }
        chipPreset1gb.setOnClickListener { applyPreset(1024) }
        chipPreset2gb.setOnClickListener { applyPreset(2048) }
        chipPreset5gb.setOnClickListener { applyPreset(5120) }

        // Unit selection change listener
        toggleUnitGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isProgrammaticUpdate && isChecked) {
                syncPresetSelection()
            }
        }

        // Text change listener for custom values
        editValue.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                syncPresetSelection()
            }
        })

        btnSave.setOnClickListener {
            val raw = editValue.text.toString().trim()
            if (raw.isEmpty()) {
                editValue.error = getString(R.string.nt_custom_value_hint)
                return@setOnClickListener
            }
            val isGb = (toggleUnitGroup.checkedButtonId == R.id.chipGb)
            val valueDouble = try {
                raw.toDouble()
            } catch (e: Exception) {
                editValue.error = getString(R.string.nt_custom_value_hint)
                return@setOnClickListener
            }
            val resultMb = if (isGb) (valueDouble * 1024.0).toInt() else valueDouble.toInt()
            NetworkThumbnailPreferenceManager.setCacheLimitMb(requireContext(), resultMb)
            (activity as? NetworkThumbnailSettingsActivity)?.updateUI()
            dismiss()
        }

        btnCancel.setOnClickListener { dismiss() }
    }

    companion object {
        const val TAG = "CacheLimitDialog"
    }
}
