package za.kilowatch.ultimatefilemanager.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R

class ViewModeBottomSheet : BottomSheetDialogFragment() {

    var onSettingsChanged: (() -> Unit)? = null

    companion object {
        const val TAG = "ViewModeBottomSheet"
        private const val ARG_IS_LIST_VIEW = "is_list_view"

        fun newInstance(isListView: Boolean = true): ViewModeBottomSheet {
            return ViewModeBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_LIST_VIEW, isListView)
                }
            }
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

        setupViewModeOptions(view)
        setupListSizeOptions(view)
        setupColumnCountOptions(view)
        setupResetCategoryOption(view)
        updateSubSectionVisibilities(view)
    }

    private fun setupViewModeOptions(view: View) {
        val context = requireContext()
        val cardModern = view.findViewById<View>(R.id.cardModeModern)
        val cardList = view.findViewById<View>(R.id.cardModeList)
        val cardGrid = view.findViewById<View>(R.id.cardModeGrid)

        fun updateUI() {
            val currentMode = MainMenuViewModeManager.loadViewMode(context)
            view.findViewById<View>(R.id.checkContainerModeModern)?.visibility =
                if (currentMode == MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.checkContainerModeList)?.visibility =
                if (currentMode == MainMenuViewModeManager.ViewMode.LIST) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.checkContainerModeGrid)?.visibility =
                if (currentMode == MainMenuViewModeManager.ViewMode.GRID) View.VISIBLE else View.GONE

            val imgIcon = view.findViewById<ImageView>(R.id.imgSheetIcon)
            when (currentMode) {
                MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED -> imgIcon?.setImageResource(R.drawable.ic_view_list)
                MainMenuViewModeManager.ViewMode.LIST -> imgIcon?.setImageResource(R.drawable.ic_list_view_custom)
                MainMenuViewModeManager.ViewMode.GRID -> imgIcon?.setImageResource(R.drawable.ic_grid_view_custom)
            }
            updateSubSectionVisibilities(view)
        }

        updateUI()

        cardModern.setOnClickListener {
            MainMenuViewModeManager.saveViewMode(context, MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED)
            updateUI()
            onSettingsChanged?.invoke()
        }

        cardList.setOnClickListener {
            MainMenuViewModeManager.saveViewMode(context, MainMenuViewModeManager.ViewMode.LIST)
            updateUI()
            onSettingsChanged?.invoke()
        }

        cardGrid.setOnClickListener {
            MainMenuViewModeManager.saveViewMode(context, MainMenuViewModeManager.ViewMode.GRID)
            updateUI()
            onSettingsChanged?.invoke()
        }
    }

    private fun updateSubSectionVisibilities(view: View) {
        val context = requireContext()
        val currentMode = MainMenuViewModeManager.loadViewMode(context)
        val layoutColumns = view.findViewById<View>(R.id.layoutColumns)
        val layoutListSize = view.findViewById<View>(R.id.layoutListSize)
        val layoutResetCategories = view.findViewById<View>(R.id.layoutResetCategories)

        when (currentMode) {
            MainMenuViewModeManager.ViewMode.GRID -> {
                layoutColumns?.visibility = View.VISIBLE
                layoutListSize?.visibility = View.GONE
                layoutResetCategories?.visibility = View.GONE
            }
            MainMenuViewModeManager.ViewMode.LIST -> {
                layoutColumns?.visibility = View.GONE
                layoutListSize?.visibility = View.VISIBLE
                layoutResetCategories?.visibility = View.GONE
            }
            MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED -> {
                layoutColumns?.visibility = View.GONE
                layoutListSize?.visibility = View.VISIBLE
                layoutResetCategories?.visibility = View.VISIBLE
            }
        }
    }

    private fun setupResetCategoryOption(view: View) {
        val cardReset = view.findViewById<View>(R.id.cardResetCategories) ?: return
        cardReset.setOnClickListener {
            val context = requireContext()
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_reset_category_layout_confirm, null)
            val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
                .setView(dialogView)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            dialogView.findViewById<View>(R.id.btnResetConfirm).setOnClickListener {
                MainMenuViewModeManager.resetCategoryLayout(context)
                onSettingsChanged?.invoke()
                dialog.dismiss()
                dismiss()
            }
            dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    private fun setupListSizeOptions(view: View) {
        val context = requireContext()
        val cardLarge = view.findViewById<View>(R.id.cardSizeLarge)
        val cardMedium = view.findViewById<View>(R.id.cardSizeMedium)
        val cardSmall = view.findViewById<View>(R.id.cardSizeSmall)

        fun updateUI() {
            val currentSize = MainMenuViewModeManager.loadItemSize(context)
            view.findViewById<View>(R.id.checkContainerSizeLarge)?.visibility =
                if (currentSize == MainMenuViewModeManager.ItemSize.LARGE) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.checkContainerSizeMedium)?.visibility =
                if (currentSize == MainMenuViewModeManager.ItemSize.MEDIUM) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.checkContainerSizeSmall)?.visibility =
                if (currentSize == MainMenuViewModeManager.ItemSize.SMALL) View.VISIBLE else View.GONE
        }

        updateUI()

        cardLarge.setOnClickListener {
            MainMenuViewModeManager.saveItemSize(context, MainMenuViewModeManager.ItemSize.LARGE)
            updateUI()
            onSettingsChanged?.invoke()
        }
        cardMedium.setOnClickListener {
            MainMenuViewModeManager.saveItemSize(context, MainMenuViewModeManager.ItemSize.MEDIUM)
            updateUI()
            onSettingsChanged?.invoke()
        }
        cardSmall.setOnClickListener {
            MainMenuViewModeManager.saveItemSize(context, MainMenuViewModeManager.ItemSize.SMALL)
            updateUI()
            onSettingsChanged?.invoke()
        }
    }

    private fun setupColumnCountOptions(view: View) {
        val context = requireContext()
        val cardColumns4 = view.findViewById<View>(R.id.cardColumns4)
        val cardColumns3 = view.findViewById<View>(R.id.cardColumns3)

        fun updateUI() {
            val currentColCount = MainMenuViewModeManager.loadColumnCount(context)
            view.findViewById<View>(R.id.checkContainerColumns4)?.visibility =
                if (currentColCount == 4) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.checkContainerColumns3)?.visibility =
                if (currentColCount == 3) View.VISIBLE else View.GONE
        }

        updateUI()

        cardColumns4.setOnClickListener {
            MainMenuViewModeManager.saveColumnCount(context, 4)
            updateUI()
            onSettingsChanged?.invoke()
        }
        cardColumns3.setOnClickListener {
            MainMenuViewModeManager.saveColumnCount(context, 3)
            updateUI()
            onSettingsChanged?.invoke()
        }
    }
}
