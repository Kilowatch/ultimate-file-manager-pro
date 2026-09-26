package za.kilowatch.ultimatefilemanager.network

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Credential-entry screen for WebDAV storage (Nextcloud, ownCloud, Box, generic).
 *
 * Mobile: shows server-type hint chips that pre-fill the URL field with a template.
 * TV:     shows a Spinner for server-type selection (D-Pad friendly).
 *
 * On "Test & Connect", a PROPFIND is sent to validate the credentials, then
 * the [OnlineStorage] is saved to [OnlineStorageRepository].
 */
class WebDavSetupActivity : AppCompatActivity() {

    private lateinit var repo: OnlineStorageRepository
    private var isTv = false
    private var editingStorageId: String? = null

    companion object {
        const val EXTRA_STORAGE_ID = "extra_storage_id"
        private const val TAG = "WebDavSetup"

        // URL templates for the hint chips / spinner
        private val URL_TEMPLATES = mapOf(
            "Nextcloud"     to "https://<host>/remote.php/dav/files/<username>/",
            "ownCloud"      to "https://<host>/remote.php/webdav/",
            "Box"           to "https://dav.box.com/dav/",
            "Generic WebDAV" to "https://"
        )
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_webdav_setup_tv
            else      R.layout.activity_webdav_setup
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val combined = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(combined.left, combined.top, combined.right, combined.bottom)
            insets
        }

        repo = OnlineStorageRepository.getInstance(this)
        editingStorageId = intent.getStringExtra(EXTRA_STORAGE_ID)
        setupViews()
    }

    private var cbAllowInsecureTls: android.widget.CompoundButton? = null

    private fun setupViews() {
        val btnBack     = findViewById<ImageView>(R.id.btnBack)
        val edtLabel    = findViewById<TextInputEditText>(R.id.edtWebDavLabel)
        val edtUrl      = findViewById<TextInputEditText>(R.id.edtWebDavUrl)
        val edtUsername = findViewById<TextInputEditText>(R.id.edtWebDavUsername)
        val edtPassword = findViewById<TextInputEditText>(R.id.edtWebDavPassword)
        val btnConnect  = findViewById<MaterialButton>(R.id.btnConnect)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvStatus    = findViewById<TextView>(R.id.tvStatus)
        cbAllowInsecureTls = findViewById(R.id.cbAllowInsecureTls)

        if (isTv) {
            val rowAllowInsecure = findViewById<View>(R.id.rowAllowInsecureTls)
            rowAllowInsecure?.setOnClickListener {
                cbAllowInsecureTls?.let { cb -> cb.isChecked = !cb.isChecked }
            }
        }

        btnBack.setOnClickListener { finish() }

        val existing = editingStorageId?.let { repo.getById(it) }
        if (existing != null) {
            edtLabel.setText(existing.email)
            edtUrl.setText(existing.webDavUrl ?: "")
            edtUsername.setText(existing.webDavUsername ?: "")
            if (!existing.webDavPassword.isNullOrEmpty()) {
                edtPassword.hint = getString(R.string.webdav_setup_password_keep_hint)
            }
            edtPassword.setText("")
            cbAllowInsecureTls?.isChecked = existing.allowInsecureTls
        }

        if (isTv) {
            setupTvButtons(edtUrl)
        } else {
            setupMobileChips(edtUrl)
        }

        btnConnect.setOnClickListener {
            val label       = edtLabel.text?.toString()?.trim() ?: ""
            var url         = edtUrl.text?.toString()?.trim() ?: ""
            val username    = edtUsername.text?.toString()?.trim() ?: ""
            val inputPass   = edtPassword.text?.toString() ?: ""
            val allowInsecure = cbAllowInsecureTls?.isChecked ?: false

            val password = if (inputPass.isNotEmpty()) {
                inputPass
            } else if (existing != null && !existing.webDavPassword.isNullOrEmpty()) {
                existing.webDavPassword
            } else {
                ""
            }

            if (label.isEmpty() || url.isEmpty()) {
                tvStatus.text = getString(R.string.webdav_setup_error_empty_fields)
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Auto-prefix https:// if user entered bare host/IP
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                url = "https://$url"
                edtUrl.setText(url)
            }

            // Warn user if using plain HTTP
            if (url.startsWith("http://", ignoreCase = true)) {
                za.kilowatch.ultimatefilemanager.ui.UfmDialogHelper.showConfirmation(
                    context = this,
                    title = getString(R.string.webdav_setup_http_warning_title),
                    message = getString(R.string.webdav_setup_http_warning_msg),
                    iconRes = R.drawable.ic_warning,
                    positiveText = getString(R.string.webdav_setup_http_warning_continue),
                    negativeText = getString(R.string.webdav_setup_http_warning_cancel),
                    onPositive = {
                        attemptConnect(label, url, username, password, allowInsecure, btnConnect, progressBar, tvStatus)
                    }
                )
            } else {
                attemptConnect(label, url, username, password, allowInsecure, btnConnect, progressBar, tvStatus)
            }
        }
    }

    private fun attemptConnect(
        label: String,
        url: String,
        username: String,
        password: String,
        allowInsecure: Boolean,
        btnConnect: MaterialButton,
        progressBar: ProgressBar,
        tvStatus: TextView
    ) {
        val testShare = NetworkShare(
            id               = "test_${System.currentTimeMillis()}",
            name             = label,
            type             = ShareType.WEBDAV,
            host             = url.trimEnd('/') + "/",
            username         = username,
            password         = password,
            readOnly         = false,
            allowInsecureTls = allowInsecure
        )

        progressBar.visibility = View.VISIBLE
        btnConnect.isEnabled   = false
        tvStatus.visibility    = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    WebDavShareClient.verifyConnection(testShare)
                }

                // Success — persist
                val storage = OnlineStorage(
                    id                    = editingStorageId ?: java.util.UUID.randomUUID().toString(),
                    provider              = OnlineStorageProvider.WEBDAV,
                    email                 = label,
                    displayName           = label,
                    webDavUrl             = testShare.host,
                    webDavUsername        = username.ifEmpty { null },
                    webDavPassword        = password.ifEmpty { null },
                    isCredentialsStripped = false,
                    exposeToSaf           = editingStorageId?.let { repo.getById(it)?.exposeToSaf } ?: true,
                    allowInsecureTls      = allowInsecure
                )
                repo.save(storage)
                GoRoLog.d(TAG, "WebDAV storage connected: $label (allowInsecureTls=$allowInsecure)")

                progressBar.visibility = View.GONE
                tvStatus.text          = getString(R.string.webdav_setup_success)
                tvStatus.visibility    = View.VISIBLE

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 1000)

            } catch (e: Exception) {
                GoRoLog.e(TAG, "WebDAV connection test failed", e)
                val errMsg = e.message ?: ""
                val isCertError = errMsg.contains("x509", ignoreCase = true) ||
                        errMsg.contains("certificate", ignoreCase = true) ||
                        errMsg.contains("authority", ignoreCase = true) ||
                        errMsg.contains("untrusted", ignoreCase = true) ||
                        errMsg.contains("self-signed", ignoreCase = true)

                if (isCertError && !allowInsecure) {
                    val certInfo = withContext(Dispatchers.IO) {
                        WebDavShareClient.probeCertificate(testShare.host)
                    }
                    progressBar.visibility = View.GONE
                    btnConnect.isEnabled   = true

                    if (certInfo != null) {
                        showCertificateTrustDialog(certInfo) {
                            cbAllowInsecureTls?.isChecked = true
                            attemptConnect(label, url, username, password, true, btnConnect, progressBar, tvStatus)
                        }
                        return@launch
                    }
                }

                progressBar.visibility = View.GONE
                btnConnect.isEnabled   = true
                tvStatus.text          = getString(R.string.webdav_setup_error_connection, errMsg)
                tvStatus.visibility    = View.VISIBLE
            }
        }
    }

    private fun showCertificateTrustDialog(
        certInfo: WebDavShareClient.WebDavCertInfo,
        onTrust: () -> Unit
    ) {
        val dialogView = layoutInflater.inflate(
            if (isTv) R.layout.dialog_webdav_cert_tv else R.layout.dialog_webdav_cert,
            null
        )
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val txtSubject     = dialogView.findViewById<TextView>(R.id.txtCertSubject)
        val txtIssuer      = dialogView.findViewById<TextView>(R.id.txtCertIssuer)
        val txtValidity    = dialogView.findViewById<TextView>(R.id.txtCertValidity)
        val txtFingerprint = dialogView.findViewById<TextView>(R.id.txtCertFingerprint)
        val btnTrust       = dialogView.findViewById<View>(R.id.btnTrust)
        val btnCancel      = dialogView.findViewById<View>(R.id.btnCancel)

        txtSubject.text     = certInfo.subject.ifEmpty { "—" }
        txtIssuer.text      = certInfo.issuer.ifEmpty { "—" }
        txtValidity.text    = if (certInfo.validFrom.isNotEmpty() && certInfo.validTo.isNotEmpty()) {
            "${certInfo.validFrom} – ${certInfo.validTo}"
        } else {
            certInfo.validTo.ifEmpty { "—" }
        }
        txtFingerprint.text = certInfo.sha256Fingerprint.ifEmpty { "—" }

        btnTrust.setOnClickListener {
            dialog.dismiss()
            onTrust()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            dialog.window?.setLayout(
                (600 * resources.displayMetrics.density).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            btnTrust.requestFocus()
        }
    }

    /**
     * Wires the mobile 2×2 hint button grid to pre-fill the URL field.
     * Cross-group clearing ensures only one button across both rows is
     * ever highlighted at the same time.
     */
    private fun setupMobileChips(edtUrl: TextInputEditText) {
        val buttonTemplates = mapOf(
            R.id.hintChipNextcloud to URL_TEMPLATES["Nextcloud"]!!,
            R.id.hintChipOwncloud  to URL_TEMPLATES["ownCloud"]!!,
            R.id.hintChipBox       to URL_TEMPLATES["Box"]!!,
            R.id.hintChipGeneric   to URL_TEMPLATES["Generic WebDAV"]!!
        )

        val group1 = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupWebDav1)
        val group2 = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupWebDav2)

        group1?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                group2?.clearChecked()  // deselect the other row
                val template = buttonTemplates[checkedId] ?: return@addOnButtonCheckedListener
                edtUrl.setText(template)
                edtUrl.setSelection(edtUrl.text?.length ?: 0)
            }
        }
        group2?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                group1?.clearChecked()  // deselect the other row
                val template = buttonTemplates[checkedId] ?: return@addOnButtonCheckedListener
                edtUrl.setText(template)
                edtUrl.setSelection(edtUrl.text?.length ?: 0)
            }
        }
    }

    /**
     * Wires the TV 2×2 hint button grid to pre-fill the URL field.
     * Cross-group clearing ensures only one button across both rows is
     * ever highlighted at the same time (yellow on focus/checked, black text).
     */
    private fun setupTvButtons(edtUrl: TextInputEditText) {
        val buttonTemplates = mapOf(
            R.id.hintChipNextcloud to URL_TEMPLATES["Nextcloud"]!!,
            R.id.hintChipOwncloud  to URL_TEMPLATES["ownCloud"]!!,
            R.id.hintChipBox       to URL_TEMPLATES["Box"]!!,
            R.id.hintChipGeneric   to URL_TEMPLATES["Generic WebDAV"]!!
        )

        val group1 = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupWebDav1)
        val group2 = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupWebDav2)

        group1?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                group2?.clearChecked()  // deselect the other row
                val template = buttonTemplates[checkedId] ?: return@addOnButtonCheckedListener
                edtUrl.setText(template)
                edtUrl.setSelection(edtUrl.text?.length ?: 0)
            }
        }
        group2?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                group1?.clearChecked()  // deselect the other row
                val template = buttonTemplates[checkedId] ?: return@addOnButtonCheckedListener
                edtUrl.setText(template)
                edtUrl.setSelection(edtUrl.text?.length ?: 0)
            }
        }
    }
}
