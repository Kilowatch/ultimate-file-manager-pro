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

    private fun setupViews() {
        val btnBack     = findViewById<ImageView>(R.id.btnBack)
        val edtLabel    = findViewById<TextInputEditText>(R.id.edtWebDavLabel)
        val edtUrl      = findViewById<TextInputEditText>(R.id.edtWebDavUrl)
        val edtUsername = findViewById<TextInputEditText>(R.id.edtWebDavUsername)
        val edtPassword = findViewById<TextInputEditText>(R.id.edtWebDavPassword)
        val btnConnect  = findViewById<MaterialButton>(R.id.btnConnect)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvStatus    = findViewById<TextView>(R.id.tvStatus)

        btnBack.setOnClickListener { finish() }

        val existing = editingStorageId?.let { repo.getById(it) }
        if (existing != null) {
            edtLabel.setText(existing.email)
            edtUrl.setText(existing.webDavUrl ?: "")
            edtUsername.setText(existing.webDavUsername ?: "")
            edtPassword.setText("")
        }

        if (isTv) {
            setupTvButtons(edtUrl)
        } else {
            setupMobileChips(edtUrl)
        }

        btnConnect.setOnClickListener {
            val label    = edtLabel.text?.toString()?.trim() ?: ""
            val url      = edtUrl.text?.toString()?.trim() ?: ""
            val username = edtUsername.text?.toString()?.trim() ?: ""
            val password = edtPassword.text?.toString() ?: ""

            if (label.isEmpty() || url.isEmpty()) {
                tvStatus.text = getString(R.string.webdav_setup_error_empty_fields)
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                tvStatus.text = getString(R.string.webdav_setup_error_bad_url)
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Warn user if using plain HTTP
            if (url.startsWith("http://")) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.webdav_setup_http_warning_title))
                    .setMessage(getString(R.string.webdav_setup_http_warning_msg))
                    .setPositiveButton(getString(R.string.webdav_setup_http_warning_continue)) { _, _ ->
                        attemptConnect(label, url, username, password, btnConnect, progressBar, tvStatus)
                    }
                    .setNegativeButton(getString(R.string.webdav_setup_http_warning_cancel), null)
                    .show()
            } else {
                attemptConnect(label, url, username, password, btnConnect, progressBar, tvStatus)
            }
        }
    }

    private fun attemptConnect(
        label: String,
        url: String,
        username: String,
        password: String,
        btnConnect: MaterialButton,
        progressBar: ProgressBar,
        tvStatus: TextView
    ) {
        val testShare = NetworkShare(
            id       = "test_${System.currentTimeMillis()}",
            name     = label,
            type     = ShareType.WEBDAV,
            host     = url.trimEnd('/') + "/",
            username = username,
            password = password,
            readOnly = false
        )

        progressBar.visibility = View.VISIBLE
        btnConnect.isEnabled   = false
        tvStatus.visibility    = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    WebDavShareClient.testConnection(testShare)
                }

                if (!ok) throw Exception(getString(R.string.webdav_setup_error_server_rejected))

                // Success — persist
                val storage = OnlineStorage(
                    id             = editingStorageId ?: java.util.UUID.randomUUID().toString(),
                    provider       = OnlineStorageProvider.WEBDAV,
                    email          = label,
                    displayName    = label,
                    webDavUrl      = testShare.host,
                    webDavUsername = username.ifEmpty { null },
                    webDavPassword = password.ifEmpty { null },
                    isCredentialsStripped = false
                )
                repo.save(storage)
                GoRoLog.d(TAG, "WebDAV storage connected: $label")

                progressBar.visibility = View.GONE
                tvStatus.text          = getString(R.string.webdav_setup_success)
                tvStatus.visibility    = View.VISIBLE

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 1000)

            } catch (e: Exception) {
                GoRoLog.e(TAG, "WebDAV connection test failed", e)
                progressBar.visibility = View.GONE
                btnConnect.isEnabled   = true
                tvStatus.text          = getString(R.string.webdav_setup_error_connection, e.message ?: "")
                tvStatus.visibility    = View.VISIBLE
            }
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
