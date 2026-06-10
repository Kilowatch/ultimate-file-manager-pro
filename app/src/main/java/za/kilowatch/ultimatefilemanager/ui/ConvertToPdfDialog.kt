package za.kilowatch.ultimatefilemanager.ui

import androidx.appcompat.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.PdfConverter
import java.io.File

class ConvertToPdfDialog : DialogFragment() {

    // Class-level view references so onStart() can access them for validation
    private var edtFilename: TextInputEditText? = null
    private var chkPassword: CheckBox? = null
    private var tilPassword: TextInputLayout? = null
    private var tilConfirmPassword: TextInputLayout? = null
    private var edtPassword: TextInputEditText? = null
    private var edtConfirmPassword: TextInputEditText? = null
    private var isTv = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = if (isTv) R.layout.dialog_convert_pdf_tv else R.layout.dialog_convert_pdf
        val dialogView = LayoutInflater.from(requireContext()).inflate(layoutRes, null)

        edtFilename        = dialogView.findViewById(R.id.edtFilename)
        chkPassword        = dialogView.findViewById(R.id.chkPassword)
        tilPassword        = dialogView.findViewById(R.id.tilPassword)
        tilConfirmPassword = dialogView.findViewById(R.id.tilConfirmPassword)
        edtPassword        = dialogView.findViewById(R.id.edtPassword)
        edtConfirmPassword = dialogView.findViewById(R.id.edtConfirmPassword)

        // Pre-fill with the original filename (without extension)
        edtFilename?.setText(arguments?.getString("original_filename") ?: "")

        // Show/hide password wrapper layouts when checkbox is toggled
        chkPassword?.setOnCheckedChangeListener { _, isChecked ->
            val visibility = if (isChecked) View.VISIBLE else View.GONE
            tilPassword?.visibility        = visibility
            tilConfirmPassword?.visibility = visibility
        }

        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog)
            .setView(dialogView)

        if (!isTv) {
            // Buttons added via builder — auto-dismiss is overridden in onStart()
            builder.setPositiveButton(R.string.pdf_convert_btn, null)
            builder.setNegativeButton(R.string.cancel, null)
        } else {
            // TV: button is in the layout; listener set here since onStart override handles mobile only
            dialogView.findViewById<Button>(R.id.btnConvert)?.setOnClickListener {
                runConversion()
            }
        }

        return builder.create()
    }

    /**
     * Override the positive button click AFTER the dialog is shown to prevent
     * MaterialAlertDialogBuilder from auto-dismissing on validation failure.
     */
    override fun onStart() {
        super.onStart()
        if (!isTv) {
            (dialog as? AlertDialog)
                ?.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setOnClickListener { runConversion() }
        }
    }

    private fun runConversion() {
        val filename = edtFilename?.text?.toString()?.trim() ?: ""
        val password = if (chkPassword?.isChecked == true) edtPassword?.text?.toString() else null
        val confirm  = if (chkPassword?.isChecked == true) edtConfirmPassword?.text?.toString() else null

        // Validation — stays open on mismatch
        if (chkPassword?.isChecked == true) {
            if (password.isNullOrEmpty() || password != confirm) {
                tilConfirmPassword?.error = getString(R.string.pdf_convert_pwd_mismatch)
                return   // ← dialog stays open
            } else {
                tilConfirmPassword?.error = null
            }
        }

        val imagePath    = arguments?.getString("image_path")
        val documentPath = arguments?.getString("document_path")
        val sourcePath   = imagePath ?: documentPath

        if (sourcePath.isNullOrEmpty()) { dismiss(); return }

        val activity = requireActivity()
        dismiss()

        val sourceFile = File(sourcePath)
        val outputDir  = sourceFile.parentFile
            ?: activity.getExternalFilesDir(null)
            ?: activity.filesDir
        val outputFile = File(outputDir, "$filename.pdf")

        // Non-cancellable premium progress dialog
        val progressLayoutRes = if (isTv) R.layout.dialog_pdf_converting_tv
                                else       R.layout.dialog_pdf_converting
        val progressView = LayoutInflater.from(activity).inflate(progressLayoutRes, null)
        progressView.findViewById<android.widget.TextView>(R.id.txtFilename)?.text =
            activity.getString(R.string.pdf_converting_desc, "$filename.pdf")

        val progressDialog = android.app.AlertDialog.Builder(activity)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        // Activity scope survives fragment dismissal
        activity.lifecycleScope.launch {
            val converter = PdfConverter(activity)
            val success = withContext(Dispatchers.IO) {
                if (imagePath != null) {
                    converter.convertImageToPdf(sourceFile, outputFile, password)
                } else {
                    converter.convertTextToPdf(sourceFile, outputFile, password)
                }
            }
            progressDialog.dismiss()
            if (success) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.pdf_convert_success, outputFile.absolutePath),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.pdf_convert_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
