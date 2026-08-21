package za.kilowatch.ultimatefilemanager.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
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
        val layoutViewModes = view.findViewById<View>(R.id.layoutViewModes)
        val layoutColumns = view.findViewById<View>(R.id.layoutColumns)
        val layoutListSize = view.findViewById<View>(R.id.layoutListSize)
        val layoutResetCategories = view.findViewById<View>(R.id.layoutResetCategories)

        imgIcon?.setImageResource(R.drawable.ic_list_view_custom)
        txtTitle?.text = getString(R.string.settings_list_size_title)
        layoutListSize?.visibility = View.VISIBLE
        layoutViewModes?.visibility = View.GONE
        layoutColumns?.visibility = View.GONE
        layoutResetCategories?.visibility = View.GONE

        setupListSizeOptions(view)
    }

    private fun setupListSizeOptions(view: View) {
        val context = requireContext()
        val cardLarge = view.findViewById<View>(R.id.cardSizeLarge)
        val cardMedium = view.findViewById<View>(R.id.cardSizeMedium)
        val cardSmall = view.findViewById<View>(R.id.cardSizeSmall)

        fun updateUI() {
            val currentSize = SettingsListSizeManager.loadItemSize(context)
            view.findViewById<View>(R.id.checkContainerSizeLarge)?.visibility =
                if (currentSize == SettingsListSizeManager.ItemSize.LARGE) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.checkContainerSizeMedium)?.visibility =
                if (currentSize == SettingsListSizeManager.ItemSize.MEDIUM) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.checkContainerSizeSmall)?.visibility =
                if (currentSize == SettingsListSizeManager.ItemSize.SMALL) View.VISIBLE else View.GONE
        }

        updateUI()

        cardLarge?.setOnClickListener {
            SettingsListSizeManager.saveItemSize(context, SettingsListSizeManager.ItemSize.LARGE)
            updateUI()
            onSettingsChanged?.invoke()
            dismiss()
        }
        cardMedium?.setOnClickListener {
            SettingsListSizeManager.saveItemSize(context, SettingsListSizeManager.ItemSize.MEDIUM)
            updateUI()
            onSettingsChanged?.invoke()
            dismiss()
        }
        cardSmall?.setOnClickListener {
            SettingsListSizeManager.saveItemSize(context, SettingsListSizeManager.ItemSize.SMALL)
            updateUI()
            onSettingsChanged?.invoke()
            dismiss()
        }
    }
}
