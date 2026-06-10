package za.kilowatch.ultimatefilemanager.archive

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Pre-extraction dialog that asks the user to choose a destination folder.
 *
 * - Mobile : slides up as a BottomSheet (theme-aware bg_bottom_sheet → ?attr/colorSurface)
 * - TV     : centered fixed-width dialog with glassmorphism background
 *
 * Usage:
 * ```
 * val dialog = ExtractLocationDialog()
 * dialog.setOnSetLocation { startLocationPicker() }
 * dialog.setOnCancel { /* nothing */ }
 * dialog.show(supportFragmentManager, ExtractLocationDialog.TAG)
 * ```
 */
class ExtractLocationDialog : DialogFragment() {

    private var onSetLocation: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    fun setOnSetLocation(listener: () -> Unit) { onSetLocation = listener }
    fun setOnCancel(listener: () -> Unit) { onCancel = listener }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        return if (isTv) {
            // TV: centered dialog via plain AlertDialog window
            super.onCreateDialog(savedInstanceState)
        } else {
            // Mobile: BottomSheetDialog — slides up from bottom, respects keyboard insets
            com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), theme)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = if (isTv) R.layout.dialog_extract_location_tv
                        else      R.layout.dialog_extract_location
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
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(android.view.Gravity.CENTER)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        } else {
            window.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(android.view.Gravity.BOTTOM)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnSetLocation).setOnClickListener {
            dismiss()
            onSetLocation?.invoke()
        }

        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dismiss()
            onCancel?.invoke()
        }

        // TV: request focus on the primary button for D-pad navigation
        if (DeviceUtils.isTvDevice(requireContext())) {
            view.findViewById<View>(R.id.btnSetLocation).requestFocus()
        }
    }

    companion object {
        const val TAG = "ExtractLocationDialog"
    }
}
