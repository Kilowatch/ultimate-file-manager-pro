package za.kilowatch.ultimatefilemanager.archive

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Dialog to choose archive format and optional password.
 * - Mobile: shown as a BottomSheet that expands from the bottom.
 * - TV:     shown as a centered fixed-width dialog.
 */
class ArchiveOptionsDialog : DialogFragment() {

    private var onConfirm: ((String, ArchiveManager.Format, String?) -> Unit)? = null

    fun setOnConfirm(listener: (String, ArchiveManager.Format, String?) -> Unit) {
        this.onConfirm = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        return if (isTv) {
            // TV: use a plain AlertDialog so we control window size centrally
            super.onCreateDialog(savedInstanceState)
        } else {
            // Mobile: BottomSheetDialog slides in from the bottom and respects keyboard insets
            com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), theme)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = if (isTv) R.layout.dialog_archive_options_tv else R.layout.dialog_archive_options
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onStart() {
        super.onStart()
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val dialog = dialog ?: return
        val window = dialog.window ?: return
        if (isTv) {
            // TV: centered dialog. layout_width in XML is ignored by the dialog window —
            // we must set the pixel width here. 75% of screen width fits nicely on a TV.
            val screenWidth = requireContext().resources.displayMetrics.widthPixels
            window.setLayout(
                (screenWidth * 0.75f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(android.view.Gravity.CENTER)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        } else {
            // Mobile: full-width, slides up from the bottom
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
        
        val cgFormat = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.cgFormat)
        val edtFilename = view.findViewById<EditText>(R.id.edtFilename)
        val switchPassword = view.findViewById<CompoundButton>(R.id.switchPassword)
        val layoutPasswordFields = view.findViewById<View>(R.id.layoutPasswordFields)
        val edtPassword = view.findViewById<EditText>(R.id.edtPassword)
        val edtConfirmPassword = view.findViewById<EditText>(R.id.edtConfirmPassword)
        val btnStart = view.findViewById<View>(R.id.btnStart)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)

        // Mobile specific TILs for error handling
        val tilFilename = view.findViewById<TextInputLayout>(R.id.tilFilename)
        val tilPassword = view.findViewById<TextInputLayout>(R.id.tilPassword)
        val tilConfirmPassword = view.findViewById<TextInputLayout>(R.id.tilConfirmPassword)

        val chipToFormat = mapOf(
            R.id.chipFormatZip to ArchiveManager.Format.ZIP,
            R.id.chipFormat7z to ArchiveManager.Format.SEVEN_Z,
            R.id.chipFormatTar to ArchiveManager.Format.TAR,
            R.id.chipFormatTarGz to ArchiveManager.Format.TAR_GZ,
            R.id.chipFormatTarXz to ArchiveManager.Format.TAR_XZ,
            R.id.chipFormatTarZst to ArchiveManager.Format.TAR_ZST,
            R.id.chipFormatTarBz2 to ArchiveManager.Format.TAR_BZ2,
            R.id.chipFormatGz to ArchiveManager.Format.GZ,
            R.id.chipFormatXz to ArchiveManager.Format.XZ,
            R.id.chipFormatZst to ArchiveManager.Format.ZST,
            R.id.chipFormatBz2 to ArchiveManager.Format.BZ2
        )

        var selectedFormat = ArchiveManager.Format.ZIP

        edtFilename.setText("archive")

        cgFormat.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipFormatZip
            selectedFormat = chipToFormat[checkedId] ?: ArchiveManager.Format.ZIP
            if (!selectedFormat.supportsPassword) {
                switchPassword.isChecked = false
                switchPassword.visibility = View.GONE
                layoutPasswordFields.visibility = View.GONE
            } else {
                switchPassword.visibility = View.VISIBLE
            }
        }

        switchPassword.setOnCheckedChangeListener { _, isChecked ->
            layoutPasswordFields.visibility = if (isChecked && selectedFormat.supportsPassword) View.VISIBLE else View.GONE
        }

        btnCancel.setOnClickListener { dismiss() }

        btnStart.setOnClickListener {
            val filename = edtFilename.text.toString().trim()
            if (filename.isEmpty()) {
                if (tilFilename != null) tilFilename.error = getString(R.string.compress_filename_empty)
                else Toast.makeText(context, R.string.compress_filename_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var password: String? = null

            if (selectedFormat.supportsPassword && switchPassword.isChecked) {
                val p1 = edtPassword.text.toString()
                val p2 = edtConfirmPassword.text.toString()

                if (p1.length < 4) {
                    if (tilPassword != null) tilPassword.error = getString(R.string.compress_password_too_short)
                    else Toast.makeText(context, R.string.compress_password_too_short, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (p1 != p2) {
                    if (tilConfirmPassword != null) tilConfirmPassword.error = getString(R.string.compress_password_mismatch)
                    else Toast.makeText(context, R.string.compress_password_mismatch, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                password = p1
            }

            onConfirm?.invoke(filename, selectedFormat, password)
            dismiss()
        }
    }

    companion object {
        const val TAG = "ArchiveOptionsDialog"
    }
}
