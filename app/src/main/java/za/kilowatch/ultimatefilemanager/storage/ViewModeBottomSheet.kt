package za.kilowatch.ultimatefilemanager.storage

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

class ViewModeBottomSheet : BottomSheetDialogFragment() {

    var onSettingsChanged: (() -> Unit)? = null

    private var isListView = true

    companion object {
        const val TAG = "ViewModeBottomSheet"
        private const val ARG_IS_LIST_VIEW = "is_list_view"

        fun newInstance(isListView: Boolean): ViewModeBottomSheet {
            return ViewModeBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_LIST_VIEW, isListView)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isListView = arguments?.getBoolean(ARG_IS_LIST_VIEW, true) ?: true
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

        val context = requireContext()
        val imgIcon = view.findViewById<ImageView>(R.id.imgSheetIcon)
        val txtTitle = view.findViewById<TextView>(R.id.txtSheetTitle)
        val layoutColumns = view.findViewById<View>(R.id.layoutColumns)
        val layoutListSize = view.findViewById<View>(R.id.layoutListSize)

        if (isListView) {
            imgIcon.setImageResource(R.drawable.ic_list_view_custom)
            txtTitle.text = getString(R.string.layout_list)
            layoutListSize.visibility = View.VISIBLE
            layoutColumns.visibility = View.GONE
            setupListSizeOptions(view)
        } else {
            imgIcon.setImageResource(R.drawable.ic_grid_view_custom)
            txtTitle.text = getString(R.string.layout_grid)
            layoutColumns.visibility = View.VISIBLE
            layoutListSize.visibility = View.GONE
            setupColumnCountOptions(view)
        }
    }

    private fun setupListSizeOptions(view: View) {
        val context = requireContext()
        val currentSize = MainMenuViewModeManager.loadItemSize(context)

        val cardLarge = view.findViewById<MaterialCardView>(R.id.cardSizeLarge)
        val cardMedium = view.findViewById<MaterialCardView>(R.id.cardSizeMedium)
        val cardSmall = view.findViewById<MaterialCardView>(R.id.cardSizeSmall)

        val rbLarge = view.findViewById<RadioButton>(R.id.rbSizeLarge)
        val rbMedium = view.findViewById<RadioButton>(R.id.rbSizeMedium)
        val rbSmall = view.findViewById<RadioButton>(R.id.rbSizeSmall)

        rbLarge.isChecked = currentSize == MainMenuViewModeManager.ItemSize.LARGE
        rbMedium.isChecked = currentSize == MainMenuViewModeManager.ItemSize.MEDIUM
        rbSmall.isChecked = currentSize == MainMenuViewModeManager.ItemSize.SMALL

        val activeColor = context.getColor(R.color.ufm_primary)
        val inactiveColor = context.getColor(R.color.mobile_glass_stroke)

        cardLarge.strokeColor = if (currentSize == MainMenuViewModeManager.ItemSize.LARGE) activeColor else inactiveColor
        cardMedium.strokeColor = if (currentSize == MainMenuViewModeManager.ItemSize.MEDIUM) activeColor else inactiveColor
        cardSmall.strokeColor = if (currentSize == MainMenuViewModeManager.ItemSize.SMALL) activeColor else inactiveColor

        cardLarge.setOnClickListener {
            MainMenuViewModeManager.saveItemSize(context, MainMenuViewModeManager.ItemSize.LARGE)
            onSettingsChanged?.invoke()
            dismiss()
        }
        cardMedium.setOnClickListener {
            MainMenuViewModeManager.saveItemSize(context, MainMenuViewModeManager.ItemSize.MEDIUM)
            onSettingsChanged?.invoke()
            dismiss()
        }
        cardSmall.setOnClickListener {
            MainMenuViewModeManager.saveItemSize(context, MainMenuViewModeManager.ItemSize.SMALL)
            onSettingsChanged?.invoke()
            dismiss()
        }
    }

    private fun setupColumnCountOptions(view: View) {
        val context = requireContext()
        val currentColCount = MainMenuViewModeManager.loadColumnCount(context)

        val cardColumns4 = view.findViewById<MaterialCardView>(R.id.cardColumns4)
        val cardColumns3 = view.findViewById<MaterialCardView>(R.id.cardColumns3)

        val rbColumns4 = view.findViewById<RadioButton>(R.id.rbColumns4)
        val rbColumns3 = view.findViewById<RadioButton>(R.id.rbColumns3)

        rbColumns4.isChecked = currentColCount == 4
        rbColumns3.isChecked = currentColCount == 3

        val activeColor = context.getColor(R.color.ufm_primary)
        val inactiveColor = context.getColor(R.color.mobile_glass_stroke)

        cardColumns4.strokeColor = if (currentColCount == 4) activeColor else inactiveColor
        cardColumns3.strokeColor = if (currentColCount == 3) activeColor else inactiveColor

        cardColumns4.setOnClickListener {
            MainMenuViewModeManager.saveColumnCount(context, 4)
            onSettingsChanged?.invoke()
            dismiss()
        }
        cardColumns3.setOnClickListener {
            MainMenuViewModeManager.saveColumnCount(context, 3)
            onSettingsChanged?.invoke()
            dismiss()
        }
    }
}
