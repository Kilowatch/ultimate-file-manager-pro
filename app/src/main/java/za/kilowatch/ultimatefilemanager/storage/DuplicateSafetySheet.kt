package za.kilowatch.ultimatefilemanager.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity

/**
 * A safety dialog that warns the user when they are about to delete ALL copies
 * of one or more duplicate groups.
 */
class DuplicateSafetySheet : BottomSheetDialogFragment() {

    private var dangerousFilenames: String = ""
    private var onConfirm: (() -> Unit)? = null

    companion object {
        const val TAG = "DuplicateSafetySheet"
        
        fun newInstance(filenames: List<String>, onConfirm: () -> Unit): DuplicateSafetySheet {
            return DuplicateSafetySheet().apply {
                this.dangerousFilenames = filenames.joinToString("\n")
                this.onConfirm = onConfirm
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DeviceUtils.isTvDevice(requireContext())) {
            // Center dialog style for TV
            setStyle(STYLE_NORMAL, R.style.UFM_Dialog)
        } else {
            // Bottom sheet for mobile
            setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialog)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        if (DeviceUtils.isTvDevice(requireContext())) {
            // On TV, we want it to be a centered dialog, not a bottom sheet
            val centeredDialog = Dialog(requireContext(), theme)
            centeredDialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setGravity(Gravity.CENTER)
            }
            return centeredDialog
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        if (DeviceUtils.isTvDevice(requireContext())) {
            dialog?.window?.apply {
                setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = if (isTv) R.layout.sheet_duplicate_safety_tv else R.layout.sheet_duplicate_safety
        val view = inflater.inflate(layoutRes, container, false)

        view.findViewById<TextView>(R.id.txtDangerousFiles).text = dangerousFilenames

        view.findViewById<View>(R.id.btnDeleteAnyway).setOnClickListener {
            onConfirm?.invoke()
            dismiss()
        }

        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dismiss()
        }

        return view
    }
    
    // For TV, we want it rounded and centered, not a bottom sheet.
    // BottomSheetDialogFragment handles that automatically if we override theme or just use regular DialogFragment fallback
    // but for simplicity and "WOW" effect, let's just make it look good as a sheet.
}
