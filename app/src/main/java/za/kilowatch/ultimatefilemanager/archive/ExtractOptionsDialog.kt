package za.kilowatch.ultimatefilemanager.archive

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Dialog to choose extraction mode:
 * 1. Extract Here (Extract directly into current folder)
 * 2. Extract and Select Folder (Extract to temp folder, stage in clipboard/FAB, allow user to navigate anywhere and tap FAB)
 * 3. Cancel
 *
 * - Mobile: BottomSheetDialog expanding from bottom.
 * - TV:     Centered glassmorphic dialog with D-pad navigation.
 */
class ExtractOptionsDialog : DialogFragment() {

    private var onExtractHere: (() -> Unit)? = null
    private var onExtractToNewFolder: (() -> Unit)? = null
    private var onExtractAndSelectFolder: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var archiveNames: List<String> = emptyList()

    fun setOnExtractHere(listener: () -> Unit) { onExtractHere = listener }
    fun setOnExtractToNewFolder(listener: () -> Unit) { onExtractToNewFolder = listener }
    fun setOnExtractAndSelectFolder(listener: () -> Unit) { onExtractAndSelectFolder = listener }
    fun setOnCancel(listener: () -> Unit) { onCancel = listener }
    fun setArchiveNames(names: List<String>) { archiveNames = names }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        return if (isTv) {
            super.onCreateDialog(savedInstanceState)
        } else {
            com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), theme)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = if (isTv) R.layout.dialog_extract_options_tv
                        else      R.layout.dialog_extract_options
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onStart() {
        super.onStart()
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val window = dialog?.window ?: return
        if (isTv) {
            val screenWidth = requireContext().resources.displayMetrics.widthPixels
            window.setLayout(
                (screenWidth * 0.75f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(android.view.Gravity.CENTER)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        } else {
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(android.view.Gravity.BOTTOM)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val txtSubtitle = view.findViewById<TextView>(R.id.txtSubtitle)
        if (archiveNames.isNotEmpty()) {
            txtSubtitle.text = if (archiveNames.size == 1) {
                archiveNames.first()
            } else {
                "${archiveNames.size} archives selected"
            }
            txtSubtitle.visibility = View.VISIBLE
        } else {
            txtSubtitle.visibility = View.GONE
        }

        view.findViewById<View>(R.id.btnExtractHere).setOnClickListener {
            dismiss()
            onExtractHere?.invoke()
        }

        view.findViewById<View>(R.id.btnExtractToNewFolder).setOnClickListener {
            dismiss()
            onExtractToNewFolder?.invoke()
        }

        view.findViewById<View>(R.id.btnExtractAndSelectFolder).setOnClickListener {
            dismiss()
            onExtractAndSelectFolder?.invoke()
        }

        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dismiss()
            onCancel?.invoke()
        }

        if (DeviceUtils.isTvDevice(requireContext())) {
            view.findViewById<View>(R.id.btnExtractHere).requestFocus()
        }
    }

    companion object {
        const val TAG = "ExtractOptionsDialog"

        fun newInstance(archiveNames: List<String> = emptyList()): ExtractOptionsDialog {
            val dialog = ExtractOptionsDialog()
            dialog.setArchiveNames(archiveNames)
            return dialog
        }
    }
}
