package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputLayout
import za.kilowatch.ultimatefilemanager.R

class CacheLimitDialogFragment : BottomSheetDialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_cache_limit_custom, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val editValue = view.findViewById<EditText>(R.id.editCacheValue)
        val chip500 = view.findViewById<Chip>(R.id.chipPreset500)
        val chip1gb = view.findViewById<Chip>(R.id.chipPreset1gb)
        val chip2gb = view.findViewById<Chip>(R.id.chipPreset2gb)
        val chip5gb = view.findViewById<Chip>(R.id.chipPreset5gb)
        val chipMb = view.findViewById<Chip>(R.id.chipMb)
        val chipGb = view.findViewById<Chip>(R.id.chipGb)
        val chipUnitGroup = view.findViewById<ChipGroup>(R.id.chipUnitGroup)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)

        var isUpdatingDisplay = false

        fun getUnit(): String = if (chipGb.isChecked) "GB" else "MB"

        fun updateDisplayWithUnit() {
            val raw = editValue.text.toString().trim()
            if (raw.isNotEmpty()) {
                // Remove old unit if present before appending new one
                val valueOnly = raw.replace(Regex("\\s*(MB|GB)$"), "")
                val unit = getUnit()
                isUpdatingDisplay = true
                editValue.setText("$valueOnly $unit")
                editValue.setSelection(editValue.text.length)
                isUpdatingDisplay = false
            }
        }

        fun setPresetValue(mb: Int) {
            if (mb >= 1024) {
                val gb = mb.toDouble() / 1024.0
                val unit = "GB"
                isUpdatingDisplay = true
                editValue.setText(String.format(java.util.Locale.getDefault(), "%.1f %s", gb, unit))
                editValue.setSelection(editValue.text.length)
                isUpdatingDisplay = false
                chipGb.isChecked = true
            } else {
                val unit = "MB"
                isUpdatingDisplay = true
                editValue.setText("$mb $unit")
                editValue.setSelection(editValue.text.length)
                isUpdatingDisplay = false
                chipMb.isChecked = true
            }
        }

        val currentMb = NetworkThumbnailPreferenceManager.getCacheLimitMb(requireContext())
        if (currentMb >= 1024) {
            val gbVal = currentMb.toDouble() / 1024.0
            editValue.setText(String.format(java.util.Locale.getDefault(), "%.1f GB", gbVal))
            chipGb.isChecked = true
        } else {
            editValue.setText("$currentMb MB")
            chipMb.isChecked = true
        }

        // Unit selection listener
        chipUnitGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (!isUpdatingDisplay && checkedIds.isNotEmpty()) {
                updateDisplayWithUnit()
            }
        }

        // Text watcher to update unit when user types and prevent editing the unit suffix
        editValue.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingDisplay && s != null) {
                    val text = s.toString()
                    val unit = getUnit()
                    val unitSuffix = " $unit"
                    
                    // Extract numeric part (everything before the unit)
                    val valueOnly = text.replace(Regex("\\s*(MB|GB)$"), "").trim()
                    
                    // Rebuild with proper unit
                    val expectedText = if (valueOnly.isEmpty()) "" else "$valueOnly $unit"
                    
                    if (text != expectedText) {
                        isUpdatingDisplay = true
                        val cursorPos = editValue.selectionStart
                        s.clear()
                        s.append(expectedText)
                        
                        // Prevent cursor from being in the unit suffix
                        val maxCursorPos = if (valueOnly.isEmpty()) 0 else valueOnly.length
                        val newCursorPos = when {
                            valueOnly.isEmpty() -> 0
                            cursorPos > maxCursorPos -> maxCursorPos
                            else -> minOf(cursorPos, maxCursorPos)
                        }
                        try {
                            editValue.setSelection(minOf(newCursorPos, expectedText.length))
                        } catch (e: Exception) {
                            // Silently ignore cursor positioning errors
                        }
                        isUpdatingDisplay = false
                    } else {
                        // Prevent cursor from moving into the unit suffix area
                        val maxCursorPos = if (valueOnly.isEmpty()) 0 else valueOnly.length
                        val currentSelection = editValue.selectionStart
                        if (currentSelection > maxCursorPos && currentSelection <= text.length) {
                            try {
                                editValue.setSelection(maxCursorPos)
                            } catch (e: Exception) {
                                // Silently ignore cursor positioning errors
                            }
                        }
                    }
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        chip500.setOnClickListener { setPresetValue(500) }
        chip1gb.setOnClickListener { setPresetValue(1024) }
        chip2gb.setOnClickListener { setPresetValue(2048) }
        chip5gb.setOnClickListener { setPresetValue(5120) }

        btnSave.setOnClickListener {
            val raw = editValue.text.toString().trim()
            if (raw.isEmpty()) {
                editValue.error = getString(R.string.nt_custom_value_hint)
                return@setOnClickListener
            }
            // Extract numeric value only (remove unit suffix)
            val valueOnly = raw.replace(Regex("\\s*(MB|GB)$"), "").trim()
            val isGb = chipGb.isChecked
            val valueDouble = try { valueOnly.toDouble() } catch (e: Exception) {
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
