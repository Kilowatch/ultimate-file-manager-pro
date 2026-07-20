package za.kilowatch.ultimatefilemanager.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R

class SettingsListSizeBottomSheet : BottomSheetDialogFragment() {

    var onSettingsChanged: (() -> Unit)? = null

    companion object {
        const val TAG = "SettingsListSizeBottomSheet"

        fun newInstance(): SettingsListSizeBottomSheet {
            return SettingsListSizeBottomSheet()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_view_mode_options, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imgIcon = view.findViewById<ImageView>(R.id.imgSheetIcon)
        val txtTitle = view.findViewById<TextView>(R.id.txtSheetTitle)
        val layoutColumns = view.findViewById<View>(R.id.layoutColumns)
        val layoutListSize = view.findViewById<View>(R.id.layoutListSize)

        imgIcon.setImageResource(R.drawable.ic_list_view_custom)
        txtTitle.text = getString(R.string.settings_list_size_title)
        layoutListSize.visibility = View.VISIBLE
        layoutColumns.visibility = View.GONE

        setupListSizeOptions(view)
    }

    private fun setupListSizeOptions(view: View) {
        val context = requireContext()
        val currentSize = SettingsListSizeManager.loadItemSize(context)

        val cardLarge = view.findViewById<MaterialCardView>(R.id.cardSizeLarge)
        val cardMedium = view.findViewById<MaterialCardView>(R.id.cardSizeMedium)
        val cardSmall = view.findViewById<MaterialCardView>(R.id.cardSizeSmall)

        val rbLarge = view.findViewById<RadioButton>(R.id.rbSizeLarge)
        val rbMedium = view.findViewById<RadioButton>(R.id.rbSizeMedium)
        val rbSmall = view.findViewById<RadioButton>(R.id.rbSizeSmall)

        rbLarge.isChecked = currentSize == SettingsListSizeManager.ItemSize.LARGE
        rbMedium.isChecked = currentSize == SettingsListSizeManager.ItemSize.MEDIUM
        rbSmall.isChecked = currentSize == SettingsListSizeManager.ItemSize.SMALL

        val activeColor = context.getColor(R.color.ufm_primary)
        val inactiveColor = context.getColor(R.color.mobile_glass_stroke)

        cardLarge.strokeColor = if (currentSize == SettingsListSizeManager.ItemSize.LARGE) activeColor else inactiveColor
        cardMedium.strokeColor = if (currentSize == SettingsListSizeManager.ItemSize.MEDIUM) activeColor else inactiveColor
        cardSmall.strokeColor = if (currentSize == SettingsListSizeManager.ItemSize.SMALL) activeColor else inactiveColor

        cardLarge.setOnClickListener {
            SettingsListSizeManager.saveItemSize(context, SettingsListSizeManager.ItemSize.LARGE)
            onSettingsChanged?.invoke()
            dismiss()
        }
        cardMedium.setOnClickListener {
            SettingsListSizeManager.saveItemSize(context, SettingsListSizeManager.ItemSize.MEDIUM)
            onSettingsChanged?.invoke()
            dismiss()
        }
        cardSmall.setOnClickListener {
            SettingsListSizeManager.saveItemSize(context, SettingsListSizeManager.ItemSize.SMALL)
            onSettingsChanged?.invoke()
            dismiss()
        }
    }
}
