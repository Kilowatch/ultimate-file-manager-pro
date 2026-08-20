package za.kilowatch.ultimatefilemanager.storage

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Bottom sheet dialog for sorting files in the App Manager.
 * Supports sort by name/size/date with ascending/descending order.
 */
class SortFilterAppSheet : BottomSheetDialogFragment() {

    enum class AppSortMode { NAME, SIZE, DATE }
    enum class AppSortOrder { ASC, DESC }

    var currentSortMode = AppSortMode.NAME
    var currentSortOrder = AppSortOrder.ASC
    var onApply: ((AppSortMode, AppSortOrder) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = if (isTv) R.layout.sheet_sort_app_tv else R.layout.sheet_sort_app_filter
        val view = inflater.inflate(layoutRes, container, false)

        val txtTitle = view.findViewById<TextView>(R.id.txtSortTitle)
        txtTitle.text = getString(R.string.sort_title) // "Sort & Filter"

        // Sort mode chips
        val cgSort = view.findViewById<ChipGroup>(R.id.cgSortMode)
        val chipName = view.findViewById<Chip>(R.id.chipSortName)
        val chipSize = view.findViewById<Chip>(R.id.chipSortSize)
        val chipDate = view.findViewById<Chip>(R.id.chipSortDate)

        when (currentSortMode) {
            AppSortMode.NAME -> chipName.isChecked = true
            AppSortMode.SIZE -> chipSize.isChecked = true
            AppSortMode.DATE -> chipDate.isChecked = true
        }

        // Sort order chips
        val cgOrder = view.findViewById<ChipGroup>(R.id.cgSortOrder)
        val chipAsc = view.findViewById<Chip>(R.id.chipAscending)
        val chipDesc = view.findViewById<Chip>(R.id.chipDescending)

        when (currentSortOrder) {
            AppSortOrder.ASC -> chipAsc.isChecked = true
            AppSortOrder.DESC -> chipDesc.isChecked = true
        }

        // Apply button
        view.findViewById<View>(R.id.btnApplySort).setOnClickListener {
            val sortMode = when (cgSort.checkedChipId) {
                R.id.chipSortName -> AppSortMode.NAME
                R.id.chipSortSize -> AppSortMode.SIZE
                R.id.chipSortDate -> AppSortMode.DATE
                else -> AppSortMode.NAME
            }
            val sortOrder = when (cgOrder.checkedChipId) {
                R.id.chipAscending -> AppSortOrder.ASC
                R.id.chipDescending -> AppSortOrder.DESC
                else -> AppSortOrder.ASC
            }
            onApply?.invoke(sortMode, sortOrder)
            dismiss()
        }

        // Cancel button
        view.findViewById<View?>(R.id.btnCancelSort)?.setOnClickListener {
            dismiss()
        }

        // Setup TV focus states
        if (isTv) {
            setupTvFocus(view)
        }

        return view
    }

    private fun setupTvFocus(view: View) {
        val yellowBg = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_button_focused_yellow))
        val yellowText = requireContext().getColor(R.color.tv_button_focused_yellow_text)
        val whiteText = requireContext().getColor(R.color.tv_text_primary)
        val tvAccent = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_accent))
        val tvAccentTextWhite = requireContext().getColor(R.color.tv_text_primary)

        val defaultChipBg = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)

        fun applyFocusListener(v: View, isButton: Boolean = false) {
            v.setOnFocusChangeListener { _, hasFocus ->
                if (v is Chip) {
                    if (hasFocus) {
                        v.chipBackgroundColor = yellowBg
                        v.setTextColor(yellowText)
                    } else {
                        v.chipBackgroundColor = defaultChipBg
                        v.setTextColor(whiteText)
                    }
                } else if (isButton && v is MaterialButton) {
                    if (hasFocus) {
                        v.backgroundTintList = yellowBg
                        v.setTextColor(yellowText)
                    } else {
                        v.backgroundTintList = tvAccent
                        v.setTextColor(tvAccentTextWhite)
                    }
                }
            }
        }

        // Apply to Chips
        view.findViewById<Chip>(R.id.chipSortName)?.let { applyFocusListener(it) }
        view.findViewById<Chip>(R.id.chipSortSize)?.let { applyFocusListener(it) }
        view.findViewById<Chip>(R.id.chipSortDate)?.let { applyFocusListener(it) }
        view.findViewById<Chip>(R.id.chipAscending)?.let { applyFocusListener(it) }
        view.findViewById<Chip>(R.id.chipDescending)?.let { applyFocusListener(it) }

        // Apply to Apply Button
        view.findViewById<MaterialButton>(R.id.btnApplySort)?.let { applyFocusListener(it, isButton = true) }
    }
}
