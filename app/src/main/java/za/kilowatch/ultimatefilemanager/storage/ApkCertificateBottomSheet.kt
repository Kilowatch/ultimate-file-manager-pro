package za.kilowatch.ultimatefilemanager.storage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.ApkPackageDetailsHelper.CertificateInfo

/**
 * Bottom sheet displaying detailed APK / XAPK signing certificate information:
 * Subject, Issuer, Validity range, Algorithm, Serial number, and Fingerprints (MD5, SHA-1, SHA-256)
 * with single-tap copy actions.
 */
class ApkCertificateBottomSheet : BottomSheetDialogFragment() {

    private var packageNameArg: String = ""
    private var subjectArg: String = ""
    private var issuerArg: String = ""
    private var validityArg: String = ""
    private var algorithmArg: String = ""
    private var serialArg: String = ""
    private var md5Arg: String = ""
    private var sha1Arg: String = ""
    private var sha256Arg: String = ""

    companion object {
        const val TAG = "ApkCertificateBottomSheet"

        private const val ARG_PACKAGE_NAME = "arg_package_name"
        private const val ARG_SUBJECT = "arg_subject"
        private const val ARG_ISSUER = "arg_issuer"
        private const val ARG_VALIDITY = "arg_validity"
        private const val ARG_ALGORITHM = "arg_algorithm"
        private const val ARG_SERIAL = "arg_serial"
        private const val ARG_MD5 = "arg_md5"
        private const val ARG_SHA1 = "arg_sha1"
        private const val ARG_SHA256 = "arg_sha256"

        fun newInstance(packageName: String, certInfo: CertificateInfo): ApkCertificateBottomSheet {
            return ApkCertificateBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PACKAGE_NAME, packageName)
                    putString(ARG_SUBJECT, certInfo.subject)
                    putString(ARG_ISSUER, certInfo.issuer)
                    putString(ARG_VALIDITY, "${certInfo.validFrom} to ${certInfo.validTo}")
                    putString(ARG_ALGORITHM, certInfo.algorithm)
                    putString(ARG_SERIAL, certInfo.serialNumber)
                    putString(ARG_MD5, certInfo.md5)
                    putString(ARG_SHA1, certInfo.sha1)
                    putString(ARG_SHA256, certInfo.sha256)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            packageNameArg = it.getString(ARG_PACKAGE_NAME, "")
            subjectArg = it.getString(ARG_SUBJECT, "")
            issuerArg = it.getString(ARG_ISSUER, "")
            validityArg = it.getString(ARG_VALIDITY, "")
            algorithmArg = it.getString(ARG_ALGORITHM, "")
            serialArg = it.getString(ARG_SERIAL, "")
            md5Arg = it.getString(ARG_MD5, "")
            sha1Arg = it.getString(ARG_SHA1, "")
            sha256Arg = it.getString(ARG_SHA256, "")
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
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_apk_certificate, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.txtCertPackage).text = packageNameArg
        view.findViewById<TextView>(R.id.txtCertSubject).text = if (subjectArg.isNotEmpty()) subjectArg else "—"
        view.findViewById<TextView>(R.id.txtCertIssuer).text = if (issuerArg.isNotEmpty()) issuerArg else "—"
        view.findViewById<TextView>(R.id.txtCertValidity).text = if (validityArg.isNotEmpty()) validityArg else "—"
        view.findViewById<TextView>(R.id.txtCertAlgorithm).text = if (algorithmArg.isNotEmpty()) algorithmArg else "—"
        view.findViewById<TextView>(R.id.txtCertSerial).text = if (serialArg.isNotEmpty()) serialArg else "—"

        val txtMd5 = view.findViewById<TextView>(R.id.txtCertMd5)
        val txtSha1 = view.findViewById<TextView>(R.id.txtCertSha1)
        val txtSha256 = view.findViewById<TextView>(R.id.txtCertSha256)

        txtMd5.text = if (md5Arg.isNotEmpty()) md5Arg else "—"
        txtSha1.text = if (sha1Arg.isNotEmpty()) sha1Arg else "—"
        txtSha256.text = if (sha256Arg.isNotEmpty()) sha256Arg else "—"

        view.findViewById<ImageView>(R.id.btnCopyMd5).setOnClickListener {
            copyToClipboard("MD5", md5Arg)
        }

        view.findViewById<ImageView>(R.id.btnCopySha1).setOnClickListener {
            copyToClipboard("SHA-1", sha1Arg)
        }

        view.findViewById<ImageView>(R.id.btnCopySha256).setOnClickListener {
            copyToClipboard("SHA-256", sha256Arg)
        }

        view.findViewById<View>(R.id.btnCertClose).setOnClickListener {
            dismiss()
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        if (value.isBlank()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "$label: ${getString(R.string.apk_details_copied)}", Toast.LENGTH_SHORT).show()
    }
}
