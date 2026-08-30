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

        dialogView.findViewById<View>(R.id.btnConvert)?.setOnClickListener {
            runConversion()
        }
        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dismiss()
        }

        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog)
            .setView(dialogView)

        return builder.create()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        za.kilowatch.ultimatefilemanager.util.DialogInputHelper.setupDialogFragmentInput(this, edtFilename) {
            runConversion()
        }
        za.kilowatch.ultimatefilemanager.util.DialogInputHelper.setupDoneAction(edtConfirmPassword) {
            runConversion()
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

        val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(activity, sourcePath)
        val sourceFile = if (isSaf) za.kilowatch.ultimatefilemanager.storage.SafFile(sourcePath) else File(sourcePath)
        val parentPath = sourceFile.parentFile?.absolutePath ?: ""
        val outputPdfName = "$filename.pdf"
        val outputSafOrLocalPath = if (isSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getSafChildPath(parentPath, outputPdfName)
        } else {
            val outputDir = sourceFile.parentFile
                ?: activity.getExternalFilesDir(null)
                ?: activity.filesDir
            File(outputDir, outputPdfName).absolutePath
        }

        // Non-cancellable premium progress dialog
        val progressLayoutRes = if (isTv) R.layout.dialog_pdf_converting_tv
                                else       R.layout.dialog_pdf_converting
        val progressView = LayoutInflater.from(activity).inflate(progressLayoutRes, null)
        progressView.findViewById<android.widget.TextView>(R.id.txtFilename)?.text =
            activity.getString(R.string.pdf_converting_desc, "$filename.pdf")

        val progressDialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()
        progressDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        // Activity scope survives fragment dismissal
        activity.lifecycleScope.launch {
            val converter = PdfConverter(activity)
            val success = withContext(Dispatchers.IO) {
                if (imagePath != null) {
                    converter.convertImageToPdf(sourcePath, outputSafOrLocalPath, password)
                } else {
                    converter.convertTextToPdf(sourcePath, outputSafOrLocalPath, password)
                }
            }
            progressDialog.dismiss()
            if (success) {
                val displayMsg = if (isSaf) outputPdfName else outputSafOrLocalPath
                Toast.makeText(
                    activity,
                    activity.getString(R.string.pdf_convert_success, displayMsg),
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
