package za.kilowatch.ultimatefilemanager.network

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import android.util.Log

/**
 * Add or edit a network share (SMB / FTP).
 * Pass EXTRA_SHARE_ID to edit an existing share; omit for a new one.
 *
 * SMB-only extras:
 *  - btnScanHosts    → scans LAN for TCP-445 hosts, shows discovery dialog, then credential popup
 *  - btnBrowseShares → lists top-level shares via jcifs-ng, lets user pick one for Remote Path
 */
class NetworkShareEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHARE_ID = "share_id"
    }

    private lateinit var repo: NetworkShareRepository
    private var existingShare: NetworkShare? = null

    /** True only after a test connection has succeeded for the current form values. */
    private var connectionTested = false

    /** Tracks the last selected share type ID to avoid re-clearing fields on re-tap. */
    private var previousTypeId: Int? = null

    // Views
    private lateinit var etName:          TextInputEditText
    private lateinit var etHost:          TextInputEditText
    private lateinit var etPort:          TextInputEditText
    private lateinit var etUsername:      TextInputEditText
    private lateinit var etPassword:      TextInputEditText

    private lateinit var tilUsername:     TextInputLayout
    private lateinit var tilPassword:     TextInputLayout

    private var rgType:           RadioGroup? = null
    private var chipSmb:          MaterialButton? = null
    private var chipFtp:          MaterialButton? = null
    private var chipSftp:         MaterialButton? = null
    private var chipScp:          MaterialButton? = null
    private var chipNfs:          MaterialButton? = null
    private var chipDlna:         MaterialButton? = null

    private var rbSmb:            RadioButton? = null
    private var rbSftp:           RadioButton? = null
    private var rbScp:            RadioButton? = null
    private var rbNfs:            RadioButton? = null
    private var rbDlna:           RadioButton? = null

    // DLNA-specific views
    private lateinit var btnSelectDlnaDevice: MaterialButton
    private lateinit var txtDlnaSelectedDevice: TextView
    private lateinit var layerHost: View
    private lateinit var layerPort: View
    private lateinit var layerPath: View
    private var selectedDlnaServer: DlnaServerInfo? = null

    private lateinit var tilDomain:       TextInputLayout
    private lateinit var etDomain:        TextInputEditText
    private lateinit var etPath:          TextInputEditText
    private var layerSmbProtocol:         View? = null
    private lateinit var rgSmbProtocol:   RadioGroup
    private lateinit var rgAccess:        RadioGroup
    private lateinit var rbReadOnly:      RadioButton
    private lateinit var txtResult:       TextView
    private lateinit var txtHostKeyFingerprint: TextView
    private lateinit var btnTest:         View
    private lateinit var btnSave:         View
    private lateinit var txtTitle:        TextView
    private lateinit var btnScanHosts:    ImageButton
    private lateinit var btnScanNfsHosts: ImageButton
    private lateinit var btnBrowseShares: ImageButton

    // SMB Connection Type toggle (Share / Server)
    private var layerSmbConnectionType:    View? = null
    private var chipSmbShare:             MaterialButton? = null
    private var chipSmbServer:            MaterialButton? = null
    private var rgSmbConnectionType:      RadioGroup? = null

    // SSH views
    private lateinit var cardSshAuth:     View
    private lateinit var btnToggleSshAuth: MaterialButton
    private lateinit var etPrivateKey:    TextInputEditText
    private lateinit var btnPickKey:      View
    private lateinit var cbUseKeychain:   com.google.android.material.checkbox.MaterialCheckBox

    // NFS-specific views
    private var layerNfsVersion:  View? = null
    private var rgNfsVersion:    RadioGroup? = null
    private var layerNfsDebugLog: View? = null
    private var txtNfsDebugLog:  TextView? = null
    private var btnCopyDebugLog: View? = null

    private val pickKeyLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleKeyPicked(it) }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_network_share_edit_tv
            else       R.layout.activity_network_share_edit
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Add 30dp extra padding when the soft keyboard is visible
            val extraKeyboardPadding = if (ime.bottom > 0) {
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 30f, resources.displayMetrics
                ).toInt()
            } else 0
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom + ime.bottom + extraKeyboardPadding)
            insets
        }

        repo = NetworkShareRepository.getInstance(this)

        bindViews()
        setupTypeToggle()
        setupUsernameWatcher()

        // If editing, populate fields
        val shareId = intent.getStringExtra(EXTRA_SHARE_ID)
        if (shareId != null) {
            existingShare = repo.getById(shareId)
            existingShare?.let { populateFields(it) }
            txtTitle.text = getString(R.string.network_edit_title_edit)
            // Editing an already-saved share: allow saving without re-testing
            connectionTested = true
        } else {
            // New share: Save is locked until test passes
            btnSave.isEnabled = false
        }

        // Any change to connection-relevant fields invalidates the previous test result
        setupConnectionInvalidationWatchers()

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        btnTest.setOnClickListener { testConnection() }
        btnSave.setOnClickListener { saveShare() }

        // Discovery / browse buttons
        btnScanHosts.setOnClickListener {
            val isDlna = chipDlna?.isChecked == true
            if (isDlna) showDlnaScanDialog() else showScanDialog()
        }
        btnScanNfsHosts.setOnClickListener { showNfsScanDialog() }
        btnSelectDlnaDevice.setOnClickListener { showDlnaScanDialog() }
        btnBrowseShares.setOnClickListener { showShareBrowserDialog() }
    }

    private fun bindViews() {
        val isTv = DeviceUtils.isTvDevice(this)
        rgType           = findViewById(R.id.rgType)
        // Note: cgType is gone, replaced by individual buttons in grid
        
        chipSmb          = findViewById(R.id.chipSmb)
        chipFtp          = findViewById(R.id.chipFtp)
        chipSftp         = findViewById(R.id.chipSftp)
        chipScp          = findViewById(R.id.chipScp)
        chipNfs          = findViewById(R.id.chipNfs)
        chipDlna         = findViewById(R.id.chipDlna)
        rbSmb            = findViewById(R.id.rbSmb)

        etName           = findViewById(R.id.etName)
        etHost           = findViewById(R.id.etHost)
        etPort           = findViewById(R.id.etPort)
        etUsername       = findViewById(R.id.etUsername)
        etPassword       = findViewById(R.id.etPassword)
        tilUsername      = findViewById(R.id.tilUsername)
        tilPassword      = findViewById(R.id.tilPassword)
        tilDomain        = findViewById(R.id.tilDomain)
        etDomain         = findViewById(R.id.etDomain)
        etPath           = findViewById(R.id.etPath)
        layerSmbProtocol = findViewById(R.id.cardSmbProtocol) ?: findViewById(R.id.layerSmbProtocol)
        rgSmbProtocol    = findViewById(R.id.rgSmbProtocol)
        rgAccess         = findViewById(R.id.rgAccess)
        rbReadOnly       = findViewById(R.id.rbReadOnly)
        txtResult        = findViewById(R.id.txtTestResult)
        txtHostKeyFingerprint = findViewById(R.id.txtHostKeyFingerprint)
        btnTest          = findViewById(R.id.btnTest)
        btnSave          = findViewById(R.id.btnSave)
        txtTitle         = findViewById(R.id.txtToolbarTitle)
        btnScanHosts     = findViewById(R.id.btnScanHosts)
        btnScanNfsHosts  = findViewById(R.id.btnScanNfsHosts)
        btnBrowseShares  = findViewById(R.id.btnBrowseShares)

        // SMB Connection Type toggle
        layerSmbConnectionType = findViewById(R.id.layerSmbConnectionType)
        chipSmbShare          = findViewById(R.id.chipSmbShare)
        chipSmbServer         = findViewById(R.id.chipSmbServer)
        rgSmbConnectionType   = findViewById(R.id.rgSmbConnectionType)

        // SSH TV RadioButtons
        rbSftp           = findViewById(R.id.rbSftp)
        rbScp            = findViewById(R.id.rbScp)
        rbNfs            = findViewById(R.id.rbNfs)
        rbDlna           = findViewById(R.id.rbDlna)

        // DLNA-specific views
        btnSelectDlnaDevice = findViewById(R.id.btnSelectDlnaDevice)
        txtDlnaSelectedDevice = findViewById(R.id.txtDlnaSelectedDevice)
        layerHost         = findViewById(R.id.layerHost)
        layerPort         = findViewById(R.id.layerPort)
        layerPath         = findViewById(R.id.layerPath)

        cardSshAuth      = findViewById(R.id.cardSshAuth)
        etPrivateKey     = findViewById(R.id.etPrivateKey)
        btnPickKey       = findViewById(R.id.btnPickKey)
        cbUseKeychain    = findViewById(R.id.cbUseKeychain)
        btnToggleSshAuth = findViewById(R.id.btnToggleSshAuth)
        layerNfsVersion  = findViewById(R.id.layerNfsVersion)
        rgNfsVersion     = findViewById(R.id.rgNfsVersion)
        layerNfsDebugLog = findViewById(R.id.layerNfsDebugLog)
        txtNfsDebugLog   = findViewById(R.id.txtNfsDebugLog)
        btnCopyDebugLog  = findViewById(R.id.btnCopyDebugLog)
    }

    private fun handleKeyPicked(uri: Uri) {
        try {
            // SEC-MED-6: Extract only the leaf filename from the URI and strip unsafe characters
            // to prevent path traversal when writing to filesDir/ssh_keys/.
            val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: "id_rsa"
            val fileName = rawName
                .replace("..", "")
                .replace("/", "")
                .replace("\\", "")
                .replace("\u0000", "")
                .trim()
                .ifBlank { "id_rsa" }
            val dir = File(filesDir, "ssh_keys")
            dir.mkdirs()
            val destFile = File(dir, fileName)
            
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            etPrivateKey.setText(destFile.absolutePath)
            resetConnectionTested()
        } catch (e: Exception) {
            Log.e("NetworkShareEdit", "Failed to copy key: ${e.message}")
        }
    }

    private fun setupTypeToggle() {
        // Ensure SMB is the default protocol for both mobile and TV
        chipSmb?.isChecked = true
        rgType?.check(R.id.rbSmb)
        val isSmbNow = rbSmb?.isChecked == true || chipSmb?.isChecked == true
        val isDlnaNow = chipDlna?.isChecked == true || rbDlna?.isChecked == true
        tilDomain.visibility       = if (isSmbNow) View.VISIBLE else View.GONE
        btnBrowseShares.visibility = if (isSmbNow) View.VISIBLE else View.GONE
        layerSmbProtocol?.visibility = if (isSmbNow) View.VISIBLE else View.GONE
        layerSmbConnectionType?.visibility = if (isSmbNow) View.VISIBLE else View.GONE

        // Apply initial connection type visibility
        applyConnectionTypeVisibility(isSmbNow)

        // DLNA initial visibility
        if (isDlnaNow) {
            applyDlnaVisibility()
        } else {
            btnScanHosts.visibility = if (isSmbNow) View.VISIBLE else View.GONE
        }

        val onTypeChange: (Int) -> Unit = { checkedId ->
            // Clear all provider-specific fields when switching to a different type.
            // Re-tapping the same type chip does NOT clear fields.
            if (previousTypeId == null || previousTypeId != checkedId) {
                clearShareFields()
            }
            previousTypeId = checkedId

            val isSmb = (checkedId == R.id.rbSmb || checkedId == R.id.chipSmb)
            val isSsh = (checkedId == R.id.rbSftp || checkedId == R.id.chipSftp ||
                         checkedId == R.id.rbScp || checkedId == R.id.chipScp)
            val isNfs = (checkedId == R.id.rbNfs || checkedId == R.id.chipNfs)
            val isDlna = (checkedId == R.id.rbDlna || checkedId == R.id.chipDlna)

            if (isDlna) {
                applyDlnaVisibility()
            } else {
                // Restore normal visibility when switching away from DLNA
                restoreNormalVisibility(isSmb, isSsh, isNfs)
            }

            resetConnectionTested()
        }

        // TV RadioGroup
        rgType?.setOnCheckedChangeListener { _, checkedId -> onTypeChange(checkedId) }

        // Mobile individual buttons (Grid)
        val mobileButtons = listOf(chipSmb, chipFtp, chipSftp, chipScp, chipNfs, chipDlna)
        mobileButtons.forEach { btn ->
            btn?.setOnClickListener { clicked ->
                mobileButtons.forEach { it?.isChecked = (it == clicked) }
                onTypeChange(clicked.id)
            }
        }

        btnToggleSshAuth.setOnClickListener {
            if (cardSshAuth.visibility == View.VISIBLE) {
                cardSshAuth.visibility = View.GONE
                btnToggleSshAuth.text = getString(R.string.network_btn_show_ssh_auth)
            } else {
                cardSshAuth.visibility = View.VISIBLE
                btnToggleSshAuth.text = getString(R.string.network_btn_hide_ssh_auth)
            }
        }

        btnPickKey.setOnClickListener { pickKeyLauncher.launch(arrayOf("*/*")) }
        etPrivateKey.setOnClickListener { btnPickKey.performClick() }

        // ── SMB Connection Type (Share / Server) ──────────────────────────────

        // Explicitly default to Share (covers any edge case where XML defaults don't propagate)
        chipSmbShare?.isChecked = true
        chipSmbServer?.isChecked = false
        rgSmbConnectionType?.check(R.id.rbSmbShare)

        val onConnTypeChange: () -> Unit = {
            val isServer = chipSmbServer?.isChecked == true ||
                    rgSmbConnectionType?.checkedRadioButtonId == R.id.rbSmbServer
            layerPath.visibility = if (isServer) View.GONE else View.VISIBLE
            btnBrowseShares.visibility = if (isServer) View.GONE else View.VISIBLE
        }

        // Mobile buttons
        val connButtons = listOf(chipSmbShare, chipSmbServer)
        connButtons.forEach { btn ->
            btn?.setOnClickListener { clicked ->
                connButtons.forEach { it?.isChecked = (it == clicked) }
                onConnTypeChange()
            }
        }

        // TV RadioGroup
        rgSmbConnectionType?.setOnCheckedChangeListener { _, _ -> onConnTypeChange() }
    }

    /** Apply layerPath visibility based on whether the connection type is Server mode. */
    private fun applyConnectionTypeVisibility(isSmb: Boolean) {
        if (!isSmb) {
            layerPath.visibility = View.VISIBLE
            btnBrowseShares.visibility = View.GONE
            return
        }
        val isServer = chipSmbServer?.isChecked == true ||
                rgSmbConnectionType?.checkedRadioButtonId == R.id.rbSmbServer
        layerPath.visibility = if (isServer) View.GONE else View.VISIBLE
        btnBrowseShares.visibility = if (isServer) View.GONE else View.VISIBLE
    }

    /**
     * Clears all connection-specific fields. Called when the user switches
     * share types so that no stale credentials carry over.
     *
     * Preserves: [etName] (display name), [rgAccess] (Read Only / Read Write).
     * Also discards any in-progress edit reference so switching back to the
     * previous type does not restore old field values.
     */
    private fun clearShareFields() {
        etHost.setText("")
        etPort.setText("")
        etUsername.setText("")
        etPassword.setText("")
        etDomain.setText("WORKGROUP")
        etPath.setText("")
        etPrivateKey.setText("")
        cbUseKeychain.isChecked = false
        txtDlnaSelectedDevice.visibility = View.GONE
        txtDlnaSelectedDevice.text = ""
        selectedDlnaServer = null
        rgSmbProtocol.check(R.id.rbSmbAuto)
        // Reset SMB connection type to Share
        chipSmbShare?.isChecked = true
        chipSmbServer?.isChecked = false
        rgSmbConnectionType?.check(R.id.rbSmbShare)
        txtHostKeyFingerprint.visibility = View.GONE
        txtHostKeyFingerprint.text = ""
        txtResult.visibility = View.GONE
        cardSshAuth.visibility = View.GONE
        btnToggleSshAuth.text = getString(R.string.network_btn_show_ssh_auth)
        btnSave.isEnabled = false
        connectionTested = false
        // Discard the edit reference so switching back doesn't restore old data
        existingShare = null
    }

    /** Hide all non-DLNA fields and show DLNA device picker. */
    private fun applyDlnaVisibility() {
        layerHost.visibility = View.GONE
        layerPort.visibility = View.GONE
        layerPath.visibility = View.GONE
        tilUsername.visibility = View.GONE
        tilPassword.visibility = View.GONE
        tilDomain.visibility = View.GONE
        btnBrowseShares.visibility = View.GONE
        btnScanHosts.visibility = View.GONE
        btnScanNfsHosts.visibility = View.GONE
        layerSmbProtocol?.visibility = View.GONE
        layerSmbConnectionType?.visibility = View.GONE
        layerNfsVersion?.visibility = View.GONE
        btnToggleSshAuth.visibility = View.GONE
        cardSshAuth.visibility = View.GONE
        txtHostKeyFingerprint.visibility = View.GONE
        btnSelectDlnaDevice.visibility = View.VISIBLE
        // Force read-only for DLNA
        rgAccess.check(R.id.rbReadOnly)
        for (i in 0 until rgAccess.childCount) {
            rgAccess.getChildAt(i).isEnabled = false
        }
    }

    /** Restore normal field visibility when switching away from DLNA. */
    private fun restoreNormalVisibility(isSmb: Boolean, isSsh: Boolean, isNfs: Boolean) {
        layerHost.visibility = View.VISIBLE
        layerPort.visibility = View.VISIBLE
        tilDomain.visibility = if (isSmb) View.VISIBLE else View.GONE
        btnScanHosts.visibility = if (isSmb) View.VISIBLE else View.GONE
        layerSmbProtocol?.visibility = if (isSmb) View.VISIBLE else View.GONE
        layerSmbConnectionType?.visibility = if (isSmb) View.VISIBLE else View.GONE
        btnSelectDlnaDevice.visibility = View.GONE
        // Apply connection type visibility (hides/shows layerPath + btnBrowseShares)
        applyConnectionTypeVisibility(isSmb)
        txtDlnaSelectedDevice.visibility = View.GONE

        // NFS-specific visibility
        tilUsername.visibility = if (isNfs) View.GONE else View.VISIBLE
        tilPassword.visibility = if (isNfs) View.GONE else View.VISIBLE
        layerNfsVersion?.visibility = if (isNfs) View.VISIBLE else View.GONE
        btnScanNfsHosts.visibility = if (isNfs) View.VISIBLE else View.GONE

        btnToggleSshAuth.visibility = if (isSsh) View.VISIBLE else View.GONE
        cardSshAuth.visibility = View.GONE
        btnToggleSshAuth.text = getString(R.string.network_btn_show_ssh_auth)

        txtHostKeyFingerprint.visibility = if (isSsh) View.VISIBLE else View.GONE
        if (isSsh) {
            txtHostKeyFingerprint.text = getString(R.string.ssh_host_key_not_verified)
            txtHostKeyFingerprint.setOnClickListener(null)
        }

        tilUsername.visibility = if (isNfs) View.GONE else View.VISIBLE
        tilPassword.visibility = if (isNfs) View.GONE else View.VISIBLE

        // Re-enable access mode
        for (i in 0 until rgAccess.childCount) {
            rgAccess.getChildAt(i).isEnabled = true
        }
    }

    /**
     * Attaches simple TextWatchers to the fields that affect the connection.
     * Any edit resets the "test passed" flag and re-disables Save.
     */
    private fun setupConnectionInvalidationWatchers() {
        val invalidatingFields = listOf(etHost, etPort, etPassword, etPath)
        // etUsername already has its own watcher; piggyback invalidation there via afterTextChanged
        for (field in invalidatingFields) {
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = resetConnectionTested()
            })
        }
    }

    /** Marks the connection as untested and disables Save until the next successful test. */
    private fun resetConnectionTested() {
        if (connectionTested) {
            connectionTested = false
            btnSave.isEnabled = false
        }
    }

    /**
     * Watches the Username field. When the user types an email (contains '@'):
     *  - Sets the Domain field to the part after '@'
     *  - Locks the Domain field so it cannot be manually edited
     * When '@' is removed the Domain reverts to "WORKGROUP" and becomes editable again.
     */
    private fun setupUsernameWatcher() {
        etUsername.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyEmailDomainLogic(
                    username  = s?.toString() ?: "",
                    tilDomain = tilDomain,
                    etDomain  = etDomain
                )
                resetConnectionTested()
            }
        })
    }

    /**
     * Shared helper — auto-fills [etDomain] from the email domain when [username] contains '@',
     * and toggles the field's editability accordingly.
     */
    private fun applyEmailDomainLogic(
        username:  String,
        tilDomain: TextInputLayout,
        etDomain:  TextInputEditText,
        defaultDomain: String = "WORKGROUP"
    ) {
        val atIndex = username.indexOf('@')
        if (atIndex > 0) {
            val emailDomain = username.substring(atIndex + 1)
            // Only update if the text actually changed to avoid cursor-jump loops
            if (etDomain.text?.toString() != emailDomain) {
                etDomain.setText(emailDomain)
            }
            etDomain.isEnabled = false
            tilDomain.hint = getString(R.string.network_field_domain) + " (from username)"
        } else {
            etDomain.isEnabled = true
            tilDomain.hint = getString(R.string.network_field_domain)
            // Restore default text if the field was previously locked and is now blank
            if (etDomain.text.isNullOrBlank()) {
                etDomain.setText(defaultDomain)
            }
        }
    }

    private fun populateFields(share: NetworkShare) {
        val mobileButtons = listOf(chipSmb, chipFtp, chipSftp, chipScp, chipNfs, chipDlna)
        when (share.type) {
            ShareType.SMB  -> {
                rgType?.check(R.id.rbSmb)
                mobileButtons.forEach { it?.isChecked = (it?.id == R.id.chipSmb) }
            }
            ShareType.FTP  -> {
                rgType?.check(R.id.rbFtp)
                mobileButtons.forEach { it?.isChecked = (it?.id == R.id.chipFtp) }
            }
            ShareType.SFTP -> {
                rgType?.check(R.id.rbSftp)
                mobileButtons.forEach { it?.isChecked = (it?.id == R.id.chipSftp) }
            }
            ShareType.SCP -> {
                rgType?.check(R.id.rbScp)
                mobileButtons.forEach { it?.isChecked = (it?.id == R.id.chipScp) }
            }
            ShareType.NFS -> {
                rgType?.check(R.id.rbNfs)
                mobileButtons.forEach { it?.isChecked = (it?.id == R.id.chipNfs) }
            }
            ShareType.DLNA -> {
                rgType?.check(R.id.rbDlna)
                mobileButtons.forEach { it?.isChecked = (it?.id == R.id.chipDlna) }
            }
            else -> {}
        }
        etName.setText(share.name)
        etHost.setText(share.host)
        etPort.setText(if (share.port > 0) share.port.toString() else "")
        etUsername.setText(share.username)
        
        // Password (decrypted by repository during load)
        etPassword.setText(share.password)
        
        etDomain.setText(share.domain)
        etPath.setText(share.remotePath)
        etPrivateKey.setText(share.privateKeyPath ?: "")
        cbUseKeychain.isChecked = share.useKeychain
        
        if (share.type == ShareType.SMB) {
            when (share.smbProtocol) {
                "SMB2" -> rgSmbProtocol.check(R.id.rbSmb2)
                "SMB3" -> rgSmbProtocol.check(R.id.rbSmb3)
                else   -> rgSmbProtocol.check(R.id.rbSmbAuto)
            }
            // Restore connection type toggle
            if (share.isServerMode) {
                chipSmbServer?.isChecked = true
                chipSmbShare?.isChecked = false
                rgSmbConnectionType?.check(R.id.rbSmbServer)
            } else {
                chipSmbShare?.isChecked = true
                chipSmbServer?.isChecked = false
                rgSmbConnectionType?.check(R.id.rbSmbShare)
            }
            // Apply connection type visibility so layerPath is hidden for server mode
            applyConnectionTypeVisibility(true)
        }
        
        rgAccess.check(if (share.readOnly) R.id.rbReadOnly else R.id.rbReadWrite)

        // Initial visibility
        val isSsh = (share.type == ShareType.SFTP || share.type == ShareType.SCP)
        btnToggleSshAuth.visibility = if (isSsh) View.VISIBLE else View.GONE
        
        if (isSsh && !share.privateKeyPath.isNullOrBlank()) {
            cardSshAuth.visibility = View.VISIBLE
            btnToggleSshAuth.text = getString(R.string.network_btn_hide_ssh_auth)
        } else {
            cardSshAuth.visibility = View.GONE
            btnToggleSshAuth.text = getString(R.string.network_btn_show_ssh_auth)
        }
        
        val isSmb = (share.type == ShareType.SMB)
        tilDomain.visibility       = if (isSmb) View.VISIBLE else View.GONE
        btnScanHosts.visibility    = if (isSmb) View.VISIBLE else View.GONE
        layerSmbProtocol?.visibility = if (isSmb) View.VISIBLE else View.GONE
        layerSmbConnectionType?.visibility = if (isSmb) View.VISIBLE else View.GONE
        // Connection-type-dependent visibility (layerPath + btnBrowseShares)
        applyConnectionTypeVisibility(isSmb)

        val isNfs = (share.type == ShareType.NFS)
        tilUsername.visibility = if (isNfs) View.GONE else View.VISIBLE
        tilPassword.visibility = if (isNfs) View.GONE else View.VISIBLE

        // NFS version selector visibility + state
        layerNfsVersion?.visibility = if (isNfs) View.VISIBLE else View.GONE
        btnScanNfsHosts.visibility = if (isNfs) View.VISIBLE else View.GONE
        if (isNfs) {
            when (share.nfsVersion) {
                0 -> rgNfsVersion?.check(R.id.rbNfsAuto)
                4 -> rgNfsVersion?.check(R.id.rbNfs4)
                else -> rgNfsVersion?.check(R.id.rbNfs3) // default v3
            }
        }

        // DLNA-specific visibility when editing an existing DLNA share
        if (share.type == ShareType.DLNA) {
            applyDlnaVisibility()
            if (share.dlnaUdn.isNotBlank()) {
                txtDlnaSelectedDevice.text = getString(R.string.dlna_selected_device, share.name, share.host)
                txtDlnaSelectedDevice.visibility = View.VISIBLE
            }
        }

        // Host key fingerprint display (SSH only)
        if (isSsh) {
            updateFingerprintDisplay(share)
        } else {
            txtHostKeyFingerprint.visibility = View.GONE
        }
    }

    private fun updateFingerprintDisplay(share: NetworkShare) {
        val fp = share.hostKeyFingerprint
        txtHostKeyFingerprint.visibility = View.VISIBLE
        if (fp != null && fp.isNotEmpty()) {
            txtHostKeyFingerprint.text = getString(R.string.ssh_host_key_verified, "SHA256:$fp")
            txtHostKeyFingerprint.setOnClickListener {
                // Clear fingerprint
                existingShare = existingShare?.copy(hostKeyFingerprint = null)
                txtHostKeyFingerprint.text = getString(R.string.ssh_host_key_not_verified)
            }
        } else {
            txtHostKeyFingerprint.text = getString(R.string.ssh_host_key_not_verified)
            txtHostKeyFingerprint.setOnClickListener(null)
        }
    }

    // ── SMB Host Scan ─────────────────────────────────────────────────────────

    private fun showScanDialog() {
        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_smb_scan_tv else R.layout.dialog_smb_scan_mobile,
            null
        )

        val layerScanning = dialogView.findViewById<View>(R.id.layerScanning)
        val layerResults  = dialogView.findViewById<View>(R.id.layerResults)
        val txtNoHosts    = dialogView.findViewById<TextView>(R.id.txtNoHosts)
        val rvHosts       = dialogView.findViewById<RecyclerView>(R.id.rvHosts)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnScanCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()

        // Show results area immediately so hosts appear progressively as they respond
        layerScanning.visibility = View.VISIBLE
        layerResults.visibility  = View.VISIBLE
        txtNoHosts.visibility = View.GONE
        rvHosts.visibility    = View.VISIBLE
        rvHosts.layoutManager = LinearLayoutManager(this)

        val hostList = mutableListOf<String>()
        val adapter = SimpleStringAdapter(
            hostList, R.layout.item_smb_host, R.id.txtHostIp
        ) { ip ->
            dialog.dismiss()
            etHost.setText(ip)
            showCredentialDialog(ip)
        }
        rvHosts.adapter = adapter

        // Start scan on background thread with progressive results
        Thread {
            SmbDiscovery.scanLan(this) { ip ->
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    hostList.add(ip)
                    hostList.sortWith(compareBy { it.split(".").last().toIntOrNull() ?: 0 })
                    adapter.notifyItemInserted(hostList.indexOf(ip))
                }
            }
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                layerScanning.visibility = View.GONE
                if (hostList.isEmpty()) {
                    txtNoHosts.visibility = View.VISIBLE
                    rvHosts.visibility    = View.GONE
                }
            }
        }.start()
    }

    // ── Credential Dialog ─────────────────────────────────────────────────────

    /**
     * Shows the credential entry dialog for [host].
     * Pre-fills from any existing saved SMB share for that host, or from the current form.
     */
    private fun showCredentialDialog(host: String) {
        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_smb_credentials_tv
            else      R.layout.dialog_smb_credentials_mobile,
            null
        )

        val txtCredHost  = dialogView.findViewById<TextView>(R.id.txtCredHost)
        val etCredUser   = dialogView.findViewById<TextInputEditText>(R.id.etCredUsername)
        val etCredPass   = dialogView.findViewById<TextInputEditText>(R.id.etCredPassword)
        val etCredDomain = dialogView.findViewById<TextInputEditText>(R.id.etCredDomain)

        txtCredHost.text = host

        // Pre-fill from any existing saved share with the same host
        val existing = repo.getAll().firstOrNull {
            it.type == ShareType.SMB && it.host.equals(host, ignoreCase = true)
        }
        // Credential dialog — find the domain TIL so we can run the same email-watcher logic
        val tilCredDomain = dialogView.findViewById<TextInputLayout>(R.id.tilCredDomain)

        if (existing != null) {
            etCredUser.setText(existing.username)
            etCredPass.setText(existing.password)
            etCredDomain.setText(existing.domain)
        } else {
            // Fall back to whatever is typed in the current form
            val curUser   = etUsername.text?.toString()?.trim() ?: ""
            val curDomain = etDomain.text?.toString()?.trim() ?: ""
            if (curUser.isNotBlank()) etCredUser.setText(curUser)
            etCredDomain.setText(if (curDomain.isNotBlank()) curDomain else "WORKGROUP")
        }

        // Apply initial state in case the pre-filled username is already an email
        applyEmailDomainLogic(etCredUser.text?.toString() ?: "", tilCredDomain, etCredDomain)

        // Watch the credential-dialog username field live
        etCredUser.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyEmailDomainLogic(
                    username  = s?.toString() ?: "",
                    tilDomain = tilCredDomain,
                    etDomain  = etCredDomain
                )
            }
        })

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnCredCancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btnCredConnect).setOnClickListener {
            // Copy credential fields back to the main form
            val credUser = etCredUser.text?.toString() ?: ""
            etUsername.setText(credUser)
            etPassword.setText(etCredPass.text?.toString() ?: "")
            // Domain from the credential dialog already reflects the email split;
            // sync it back (the main form watcher will immediately re-lock it if needed)
            etDomain.setText(etCredDomain.text?.toString()?.ifBlank { "WORKGROUP" } ?: "WORKGROUP")
            dialog.dismiss()
            // Automatically open the share browser only in Share mode.
            // In Server mode the user connects to the whole server, not a specific share.
            val isServerMode = chipSmbServer?.isChecked == true ||
                    rgSmbConnectionType?.checkedRadioButtonId == R.id.rbSmbServer
            if (!isServerMode) {
                showShareBrowserDialog()
            }
        }

        dialog.show()
    }

    // ── Share Browser Dialog ──────────────────────────────────────────────────

    private fun showShareBrowserDialog() {
        val host     = etHost.text?.toString()?.trim() ?: ""
        val username = etUsername.text?.toString()?.trim() ?: ""
        val password = etPassword.text?.toString() ?: ""
        val domain   = etDomain.text?.toString()?.trim()?.ifBlank { "WORKGROUP" } ?: "WORKGROUP"

        if (host.isBlank()) {
            showErrorResult(getString(R.string.network_error_required))
            return
        }

        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_smb_shares_tv else R.layout.dialog_smb_shares_mobile,
            null
        )

        val layerLoading    = dialogView.findViewById<View>(R.id.layerLoading)
        val layerSharesList = dialogView.findViewById<View>(R.id.layerSharesList)
        val txtNoShares     = dialogView.findViewById<TextView>(R.id.txtNoShares)
        val rvShares        = dialogView.findViewById<RecyclerView>(R.id.rvShares)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnSharesCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()

        Thread {
            var errorMsg: String? = null
            val allShares = runCatching {
                SmbDiscovery.listShares(host, username, password, domain)
            }.getOrElse { e ->
                errorMsg = e.message ?: getString(R.string.unknown_error)
                emptyList()
            }

            // Filter to only shares the current credentials can actually open.
            // This uses smbj (same library as actual browsing) so the result
            // is consistent — protected shares won't appear for GUEST/blank creds.
            val shares = allShares.filter { shareName ->
                SmbShareClient.isShareAccessible(host, shareName, username, password, domain)
            }

            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                layerLoading.visibility    = View.GONE
                layerSharesList.visibility = View.VISIBLE

                if (shares.isEmpty()) {
                    txtNoShares.visibility = View.VISIBLE
                    txtNoShares.text = if (errorMsg != null) {
                        getString(R.string.could_not_list_shares_errormsg, errorMsg)
                    } else {
                        getString(R.string.smb_shares_none)
                    }
                    rvShares.visibility    = View.GONE
                } else {
                    txtNoShares.visibility = View.GONE
                    rvShares.visibility    = View.VISIBLE
                    rvShares.layoutManager = LinearLayoutManager(this)
                    rvShares.adapter = SimpleStringAdapter(
                        shares, R.layout.item_smb_share, R.id.txtShareName
                    ) { share ->
                        etPath.setText("/$share")
                        dialog.dismiss()
                    }
                }
            }
        }.start()
    }

    // ── DLNA Scan Dialog ──────────────────────────────────────────────────────

    /** Ports we probe during the active subnet sweep. */
    private val dlnaProbePorts = intArrayOf(8200, 8080)

    private fun showDlnaScanDialog() {
        // Check for any active network (Wi-Fi or Ethernet), not just Wi-Fi.
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isConnected = cm?.activeNetwork != null
        if (!isConnected) {
            Toast.makeText(this, R.string.dlna_wifi_required, Toast.LENGTH_LONG).show()
            return
        }

        // Resolve the local subnet for the active-IP sweep.
        val localIp = getLocalIpAddress()
        val subnetIps = if (localIp != null) buildSubnetIpList(localIp) else emptyList()

        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_smb_scan_tv else R.layout.dialog_smb_scan_mobile,
            null
        )

        val layerScanning = dialogView.findViewById<View>(R.id.layerScanning)
        val layerResults  = dialogView.findViewById<View>(R.id.layerResults)
        val txtNoHosts    = dialogView.findViewById<TextView>(R.id.txtNoHosts)
        val rvHosts       = dialogView.findViewById<RecyclerView>(R.id.rvHosts)
        val progressBar     = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val txtScanTitle    = dialogView.findViewById<TextView>(R.id.txtScanTitle)
        val txtScanningLabel = dialogView.findViewById<TextView>(R.id.txtScanningLabel)

        // Override SMB defaults with DLNA-specific strings
        txtScanTitle?.text = getString(R.string.dlna_discovering)
        txtScanningLabel?.text = getString(R.string.dlna_discovering)
        txtNoHosts.text = getString(R.string.dlna_no_servers_found)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        var cancelled = false
        dialogView.findViewById<View>(R.id.btnScanCancel).setOnClickListener {
            cancelled = true
            dialog.dismiss()
        }
        dialog.setOnDismissListener { cancelled = true }
        dialog.show()

        layerScanning.visibility = View.VISIBLE
        layerResults.visibility  = View.VISIBLE
        txtNoHosts.visibility = View.GONE
        rvHosts.visibility    = View.VISIBLE
        rvHosts.layoutManager = LinearLayoutManager(this)

        val serverList = mutableListOf<DlnaServerInfo>()
        val seenUdns = mutableSetOf<String>()

        fun addServer(server: DlnaServerInfo) {
            if (seenUdns.add(server.udn)) {
                serverList.add(server)
            }
        }

        fun rebuildAdapter() {
            val items = serverList.map { "${it.friendlyName} (${it.ip})" }
            rvHosts.adapter = SimpleStringAdapter(
                items, R.layout.item_smb_host, R.id.txtHostIp
            ) { selected ->
                val idx = items.indexOf(selected)
                if (idx >= 0) {
                    val server = serverList.getOrNull(idx) ?: return@SimpleStringAdapter
                    onDlnaServerSelected(server)
                    dialog.dismiss()
                }
            }
        }
        rebuildAdapter()

        // ── Background scan thread ─────────────────────────────────────────
        Thread {
            // ── Phase 1: SSDP M-SEARCH (fast, catches devices on any port) ──
            runOnUiThread {
                if (cancelled) return@runOnUiThread
                txtScanTitle?.text = getString(R.string.dlna_discovering)
            }

            val servers = DlnaDiscovery.scanLan()
            for (s in servers) {
                if (seenUdns.add(s.udn)) serverList.add(s)
            }
            runOnUiThread {
                if (cancelled) return@runOnUiThread
                rebuildAdapter()
            }

            // ── Phase 2: Active subnet sweep (reliable, shows progress) ──
            if (!cancelled && subnetIps.isNotEmpty()) {
                runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    progressBar?.isIndeterminate = false
                    progressBar?.max = subnetIps.size
                    progressBar?.progress = 0
                    layerScanning.visibility = View.VISIBLE
                }

                // Scan in parallel batches to stay fast
                val batchSize = 20
                val pool = Executors.newFixedThreadPool(batchSize)
                val scanned = AtomicInteger(0)
                val subnetPrefix = localIp!!.substringBeforeLast('.')

                val tasks = subnetIps.map { host ->
                    Runnable {
                        if (cancelled) return@Runnable
                        val count = scanned.incrementAndGet()
                        // Update UI every 5 IPs
                        if (count % 5 == 0 || count == subnetIps.size) {
                            runOnUiThread {
                                if (cancelled) return@runOnUiThread
                                val ip = "$subnetPrefix.$host"
                                txtScanTitle?.text = "Scanning $ip ($count/${subnetIps.size}) — Found: ${serverList.size}"
                                progressBar?.progress = count
                            }
                        }

                        // Try each probe port
                        for (port in dlnaProbePorts) {
                            if (cancelled) return@Runnable
                            val ip = "$subnetPrefix.$host"
                            if (tryConnect(ip, port, 200)) {
                                val info = fetchDlnaDescription(ip, port)
                                if (info != null) {
                                    synchronized(serverList) {
                                        addServer(info)
                                    }
                                    runOnUiThread {
                                        if (cancelled) return@runOnUiThread
                                        rebuildAdapter()
                                    }
                                }
                                break  // got a response, no need to try other ports
                            }
                        }
                    }
                }

                tasks.forEach { pool.execute(it) }
                pool.shutdown()
                try { pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            }

            // ── Final UI update ────────────────────────────────────────────
            runOnUiThread {
                if (cancelled) return@runOnUiThread
                layerScanning.visibility = View.GONE
                if (serverList.isEmpty()) {
                    txtNoHosts.visibility = View.VISIBLE
                    rvHosts.visibility    = View.GONE
                } else {
                    txtScanTitle?.text = getString(
                        R.string.dlna_selected_device,
                        "${serverList.size} device(s)",
                        ""
                    ).replace(" ()", "")
                }
            }
        }.start()
    }

    // ── NFS Scan Dialog ────────────────────────────────────────────────────────

    private fun showNfsScanDialog() {
        Log.i("NFS_SCAN", "showNfsScanDialog: opening NFS server discovery dialog...")
        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_smb_scan_tv else R.layout.dialog_smb_scan_mobile,
            null
        )

        val layerScanning = dialogView.findViewById<View>(R.id.layerScanning)
        val layerResults  = dialogView.findViewById<View>(R.id.layerResults)
        val txtNoHosts    = dialogView.findViewById<TextView>(R.id.txtNoHosts)
        val rvHosts       = dialogView.findViewById<RecyclerView>(R.id.rvHosts)
        val txtScanTitle  = dialogView.findViewById<TextView>(R.id.txtScanTitle)
        val txtScanningLabel = dialogView.findViewById<TextView>(R.id.txtScanningLabel)

        // Override SMB defaults with NFS-specific strings
        txtScanTitle?.text = getString(R.string.nfs_scan_title)
        txtScanningLabel?.text = getString(R.string.nfs_scanning)
        txtNoHosts.text = getString(R.string.nfs_no_servers_found)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        var cancelled = false
        dialogView.findViewById<View>(R.id.btnScanCancel).setOnClickListener {
            cancelled = true
            dialog.dismiss()
        }
        dialog.setOnDismissListener { cancelled = true }
        dialog.show()

        // Show results area immediately so servers appear progressively
        layerScanning.visibility = View.VISIBLE
        layerResults.visibility  = View.VISIBLE
        txtNoHosts.visibility = View.GONE
        rvHosts.visibility    = View.VISIBLE
        rvHosts.layoutManager = LinearLayoutManager(this)

        val serverList = mutableListOf<NfsDiscoveredServer>()

        fun rebuildAdapter() {
            val items = serverList.flatMap { srv ->
                if (srv.exports.isNotEmpty() && srv.exports.size <= 3) {
                    // Show each export path alongside the IP for small sets.
                    srv.exports.map { export ->
                        "${srv.ip}  —  $export" to export
                    }
                } else if (srv.exports.isNotEmpty()) {
                    // Many exports: show the count, opening picker on tap.
                    srv.exports.map { export ->
                        getString(R.string.nfs_server_with_exports, srv.ip, srv.exports.size) to export
                    }
                } else if (srv.exportsError != null) {
                    listOf(srv.ip to srv.exportsError)
                } else {
                    listOf("${srv.ip}  ${getString(R.string.nfs_no_exports)}" to "")
                }
            }
            rvHosts.adapter = SimpleStringAdapter(
                items.map { it.first },
                R.layout.item_smb_host,
                R.id.txtHostIp
            ) { selected ->
                // Find the server whose IP is the prefix of the selected item
                val server = serverList.find { selected.startsWith(it.ip) } ?: return@SimpleStringAdapter
                val exportsForServer = server.exports
                if (exportsForServer.size == 1) {
                    // Single export — pre-fill both host and path
                    etHost.setText(server.ip)
                    etPath.setText(exportsForServer[0])
                    resetConnectionTested()
                    dialog.dismiss()
                } else if (exportsForServer.isNotEmpty()) {
                    // Multiple exports — show export picker
                    dialog.dismiss()
                    showNfsExportPicker(server)
                } else {
                    // No exports — just pre-fill host
                    etHost.setText(server.ip)
                    resetConnectionTested()
                    dialog.dismiss()
                }
            }
        }

        // Start scan on background thread with cancellation support
        Log.i("NFS_SCAN", "showNfsScanDialog: starting scan background thread...")
        Thread {
            Log.i("NFS_SCAN", "showNfsScanDialog: calling NfsDiscovery.scanLan()...")
            val scanStart = System.currentTimeMillis()
            val results = NfsDiscovery.scanLan(
                context = this,
                onServerFound = { server ->
                    runOnUiThread {
                        if (cancelled) return@runOnUiThread
                        Log.i("NFS_SCAN", "showNfsScanDialog: received server: ${server.ip}  exports=${server.exports.size}")
                        serverList.add(server)
                        rebuildAdapter()
                    }
                },
                cancelled = { cancelled }
            )
            val scanTime = System.currentTimeMillis() - scanStart
            Log.i("NFS_SCAN", "showNfsScanDialog: scanLan returned ${results.size} server(s) in ${scanTime}ms")
            for (srv in results) {
                Log.i("NFS_SCAN", "  Result: ${srv.ip}  exports=${srv.exports}  error=${srv.exportsError}")
            }
            runOnUiThread {
                if (cancelled) return@runOnUiThread
                Log.i("NFS_SCAN", "showNfsScanDialog: scan complete — ${results.size} servers, ${serverList.size} in UI list")
                layerScanning.visibility = View.GONE
                if (serverList.isEmpty()) {
                    Log.w("NFS_SCAN", "showNfsScanDialog: no servers found, showing empty state")
                    txtNoHosts.visibility = View.VISIBLE
                    rvHosts.visibility    = View.GONE
                } else {
                    Log.i("NFS_SCAN", "showNfsScanDialog: ${serverList.size} server(s) in list, rebuilding adapter")
                }
            }
        }.start()
    }

    /**
     * Shows an export picker dialog for a server that has multiple exports.
     * Lets the user pick which export path to mount.
     */
    private fun showNfsExportPicker(server: NfsDiscoveredServer) {
        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_smb_shares_tv else R.layout.dialog_smb_shares_mobile,
            null
        )

        val layerLoading    = dialogView.findViewById<View>(R.id.layerLoading)
        val layerSharesList = dialogView.findViewById<View>(R.id.layerSharesList)
        val txtNoShares     = dialogView.findViewById<TextView>(R.id.txtNoShares)
        val rvShares        = dialogView.findViewById<RecyclerView>(R.id.rvShares)
        val txtSharesTitle  = dialogView.findViewById<TextView>(R.id.txtSharesTitle)

        // Override labels
        txtSharesTitle?.text = getString(R.string.nfs_scan_title)
        txtNoShares.text = getString(R.string.nfs_no_servers_found)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnSharesCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()

        layerLoading.visibility = View.GONE
        layerSharesList.visibility = View.VISIBLE
        rvShares.visibility = View.VISIBLE
        txtNoShares.visibility = View.GONE
        rvShares.layoutManager = LinearLayoutManager(this)
        rvShares.adapter = SimpleStringAdapter(
            server.exports,
            R.layout.item_smb_share,
            R.id.txtShareName
        ) { export ->
            etHost.setText(server.ip)
            etPath.setText(export)
            resetConnectionTested()
            dialog.dismiss()
        }
    }

    /** Quick TCP connect to [host]:[port] with [timeoutMs] timeout. */
    private fun tryConnect(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            sock.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Fetch and parse a DLNA device description from http://[ip]:[port]/description.xml. */
    private fun fetchDlnaDescription(ip: String, port: Int): DlnaServerInfo? {
        return try {
            val url = "http://$ip:$port/description.xml"
            val client = BypassCleartextOkHttpClient.applyBypass(
                OkHttpClient.Builder()
                    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
            ).build()
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val body = response.body?.bytes()
            response.close()
            if (body == null || body.isEmpty()) return null

            val builder = za.kilowatch.ultimatefilemanager.server.DlnaXmlParser.newSecureDocumentBuilder()
            val doc = builder.parse(java.io.ByteArrayInputStream(body))
            val deviceElement = doc.getElementsByTagName("device")?.item(0) as? org.w3c.dom.Element
                ?: return null

            val udn = getText(deviceElement, "UDN")?.takeIf { it.isNotBlank() } ?: return null
            val friendlyName = getText(deviceElement, "friendlyName") ?: "Unknown"

            var cds = ""
            var cms = ""
            val sl = doc.getElementsByTagName("serviceList")?.item(0) as? org.w3c.dom.Element
            if (sl != null) {
                val services = sl.getElementsByTagName("service")
                for (i in 0 until services.length) {
                    val svc = services.item(i) as? org.w3c.dom.Element ?: continue
                    val st = getText(svc, "serviceType") ?: ""
                    val cu = getText(svc, "controlURL") ?: ""
                    if (cu.isBlank()) continue
                    val resolved = if (cu.startsWith("/")) "http://$ip:$port$cu" else cu
                    when {
                        st.contains("ContentDirectory:1") -> cds = resolved
                        st.contains("ConnectionManager:1") -> cms = resolved
                    }
                }
            }

            DlnaServerInfo(
                udn = udn,
                friendlyName = friendlyName,
                ip = ip,
                port = port,
                contentDirectoryUrl = cds.ifBlank { "http://$ip:$port/cds/control" },
                connectionManagerUrl = cms.ifBlank { "http://$ip:$port/cms/control" }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getText(parent: org.w3c.dom.Element, tag: String): String? {
        val list = parent.getElementsByTagName(tag)
        if (list.length == 0) return null
        return (list.item(0) as? org.w3c.dom.Element)?.textContent?.trim()
    }

    /** Returns the non-loopback IPv4 address of the active network interface. */
    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /** Builds a list of host octets (1-254) for a /24 subnet. */
    private fun buildSubnetIpList(localIp: String): List<Int> {
        return (1..254).toList()
    }

    /** Called when the user taps a DLNA server in the scan results list. */
    private fun onDlnaServerSelected(server: DlnaServerInfo) {
        selectedDlnaServer = server
        // Auto-fill name with device's friendly name
        etName.setText(server.friendlyName)
        // Silently populate hidden host/port fields for buildShareFromFields()
        etHost.setText(server.ip)
        etPort.setText(if (server.port > 0) server.port.toString() else "")
        // Show confirmation label
        txtDlnaSelectedDevice.text = getString(R.string.dlna_selected_device, server.friendlyName, server.ip)
        txtDlnaSelectedDevice.visibility = View.VISIBLE
        // Invalidate any previous test result since the device changed
        resetConnectionTested()
    }

    // ── Generic string-list RecyclerView adapter ──────────────────────────────

    private inner class SimpleStringAdapter(
        private val items: List<String>,
        private val itemLayoutRes: Int,
        private val textViewId: Int,
        private val onItemClick: (String) -> Unit
    ) : RecyclerView.Adapter<SimpleStringAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val txt: TextView = view.findViewById(textViewId)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(itemLayoutRes, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.txt.text = items[position]
            holder.itemView.setOnClickListener { onItemClick(items[position]) }
        }
    }

    // ── Build / validate / save ───────────────────────────────────────────────

    private fun buildShareFromFields(): NetworkShare? {
        val name = etName.text?.toString()?.trim() ?: ""
        val host = etHost.text?.toString()?.trim() ?: ""
        if (name.isBlank() || host.isBlank()) {
            showErrorResult(getString(R.string.network_error_required))
            return null
        }
        
        val readOnly = (rgAccess.checkedRadioButtonId == R.id.rbReadOnly)
        val rawPath  = etPath.text?.toString()?.trim() ?: ""

        val mobileSelectedId = listOf(chipSmb, chipFtp, chipSftp, chipScp, chipNfs, chipDlna).firstOrNull { it?.isChecked == true }?.id
        val selectedType = rgType?.checkedRadioButtonId ?: mobileSelectedId ?: -1
        val type = when (selectedType) {
            R.id.rbSftp, R.id.chipSftp -> ShareType.SFTP
            R.id.rbScp, R.id.chipScp   -> ShareType.SCP
            R.id.rbNfs, R.id.chipNfs   -> ShareType.NFS
            R.id.rbFtp, R.id.chipFtp   -> ShareType.FTP
            R.id.rbDlna, R.id.chipDlna  -> ShareType.DLNA
            else                       -> ShareType.SMB
        }

        val isServerMode = type == ShareType.SMB && (
            chipSmbServer?.isChecked == true ||
            rgSmbConnectionType?.checkedRadioButtonId == R.id.rbSmbServer
        )

        val normalizedPath = if (isServerMode) {
            ""  // Server mode: no remote path needed
        } else {
            normalizeAndValidateRemotePath(rawPath, type == ShareType.SMB)
                ?: run {
                    showErrorResult(getString(R.string.invalid_path_for_smb_enter_a_share_name_eg_sharename_or_hostsharefolder))
                    return null
                }
        }

        val password = etPassword.text?.toString() ?: ""
        val useKeychain = cbUseKeychain.isChecked
        // Note: Password encryption is handled by NetworkShareRepository during persistence.

        val smbProtocol = if (type == ShareType.SMB) {
            when (rgSmbProtocol.checkedRadioButtonId) {
                R.id.rbSmb2 -> "SMB2"
                R.id.rbSmb3 -> "SMB3"
                else        -> "AUTO"
            }
        } else "AUTO"

        return NetworkShare(
            id         = existingShare?.id ?: java.util.UUID.randomUUID().toString(),
            name       = name,
            type       = type,
            host       = host,
            port       = etPort.text?.toString()?.trim()?.toIntOrNull() ?: 0,
            username   = etUsername.text?.toString()?.trim() ?: "",
            password   = password,
            domain     = etDomain.text?.toString()?.trim()?.ifBlank { "WORKGROUP" } ?: "WORKGROUP",
            remotePath = normalizedPath,
            readOnly   = readOnly,
            privateKeyPath = etPrivateKey.text?.toString()?.ifBlank { null },
            useKeychain = useKeychain,
            smbProtocol = smbProtocol,
            dlnaUdn = selectedDlnaServer?.udn ?: existingShare?.dlnaUdn ?: "",
            dlnaContentDirectoryUrl = selectedDlnaServer?.contentDirectoryUrl
                ?: existingShare?.dlnaContentDirectoryUrl ?: "",
            dlnaConnectionManagerUrl = selectedDlnaServer?.connectionManagerUrl
                ?: existingShare?.dlnaConnectionManagerUrl ?: "",
            isCredentialsStripped = false,
            isServerMode = isServerMode,
            hostKeyFingerprint = if (existingShare != null &&
                (host != existingShare!!.host || (etPort.text?.toString()?.trim()?.toIntOrNull() ?: 0) != existingShare!!.port)
            ) null else existingShare?.hostKeyFingerprint,
            nfsVersion = if (type == ShareType.NFS && rgNfsVersion != null) {
                when (rgNfsVersion!!.checkedRadioButtonId) {
                    R.id.rbNfsAuto -> 0
                    R.id.rbNfs4 -> 4
                    else -> 3
                }
            } else 3
        )
    }

    /**
     * Normalize and validate the remote path entered by the user for SMB shares.
     * Returns a normalized path (leading '/'), or null if invalid.
     */
    private fun normalizeAndValidateRemotePath(input: String, isSmb: Boolean): String? {
        var orig = input.trim()
        if (!isSmb) return orig
        if (orig.isBlank()) return null

        // Handle UNC form: \\host\share\path
        if (orig.startsWith("\\\\")) {
            val trimmed = orig.trimStart('\\')
            val parts = trimmed.split('\\').filter { it.isNotBlank() }
            // Expect at least [host, share]
            if (parts.size >= 2) {
                val rest = parts.drop(1).joinToString("/")
                if (rest.isBlank()) return null
                return "/" + rest.trimStart('/')
            }
            return null
        }

        // Replace backslashes with slashes, remove leading slashes
        var p = orig.replace('\\', '/').trimStart('/')
        if (p.isBlank()) return null
        val shareName = p.split('/', limit = 2)[0]
        if (shareName.isBlank()) return null
        return "/" + p
    }

    private fun testConnection() {
        val share = buildShareFromFields() ?: return
        txtResult.visibility = View.GONE
        showErrorResult(getString(R.string.network_testing))
        btnTest.isEnabled = false
        btnSave.isEnabled = false
        Thread {
            // SMB/FTP: null = success, non-null = error message
            val errorMsg: String? = when (share.type) {
                ShareType.SMB  -> {
                    if (share.isServerMode) {
                        // Server mode: test by discovering shares
                        runCatching {
                            SmbDiscovery.listShares(share.host, share.username, share.password, share.domain)
                            null // success
                        }.getOrElse { e ->
                            e.message ?: getString(R.string.unknown_error)
                        }
                    } else {
                        SmbShareClient.testConnection(share)
                    }
                }
                ShareType.FTP  -> FtpShareClient.testConnection(share)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.testConnection(this, share)
                ShareType.NFS  -> {
                    Log.i("NFS_TEST", "=== NFS Connection Test: ${share.host}:${share.effectivePort} path=${share.remotePath} version=${share.nfsVersion} uid=${share.username} ===")
                    // No port probes — the RPC handshake itself is the connectivity check.
                    // libnfs's mount call handles DNS resolution, TCP connection, and
                    // protocol negotiation internally.
                    NfsShareClient.testConnection(share)
                }
                ShareType.DLNA -> DlnaShareClient.testConnection(share)
                ShareType.TV, ShareType.ONEDRIVE, ShareType.GOOGLE_DRIVE,
                ShareType.DROPBOX, ShareType.AWS_S3, ShareType.IDRIVE_E2, ShareType.WEBDAV,
                ShareType.WEBDAV -> null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnTest.isEnabled = true
                if (errorMsg == null) {
                    connectionTested = true
                    btnSave.isEnabled = true
                    txtResult.visibility = View.GONE
                    // Reload share from repo to pick up fingerprint persisted by testConnection
                    val updatedShare = if (share.type == ShareType.SFTP || share.type == ShareType.SCP) {
                        repo.getById(share.id) ?: share
                    } else share
                    showSuccessDialog(updatedShare)
                } else {
                    connectionTested = false
                    btnSave.isEnabled = false
                    val displayMsg = resolveSmbError(errorMsg)
                    showErrorResult(getString(R.string.errormsg, displayMsg))
                }
            }
        }.start()
    }

    /**
     * Maps sentinel strings from [SmbShareClient] and [NfsShareClient] to localised,
     * user-friendly messages. Falls back to the raw message if it's not a known sentinel.
     */
    private fun resolveSmbError(sentinel: String): String = when (sentinel) {
        SmbShareClient.ErrorSentinel.MAX_CONNECTIONS ->
            getString(R.string.smb_error_max_connections)
        NfsShareClient.ErrorSentinel.STALE_HANDLE ->
            getString(R.string.nfs_error_stale_handle)
        NfsShareClient.ErrorSentinel.PORTMAPPER_UNREACHABLE ->
            getString(R.string.nfs_error_portmapper_unreachable)
        NfsShareClient.ErrorSentinel.PERMISSION_DENIED ->
            getString(R.string.nfs_error_permission_denied)
        NfsShareClient.ErrorSentinel.AUTH_REJECTED ->
            getString(R.string.nfs_error_auth_rejected)
        NfsShareClient.ErrorSentinel.CONNECTION_FAILED ->
            getString(R.string.nfs_error_connection_failed)
        NfsShareClient.ErrorSentinel.PATH_NOT_FOUND ->
            getString(R.string.nfs_error_path_not_found)
        NfsShareClient.ErrorSentinel.SERVICE_UNAVAILABLE ->
            getString(R.string.nfs_error_service_unavailable)
        NfsShareClient.ErrorSentinel.VERSION_MISMATCH ->
            getString(R.string.nfs_error_version_mismatch)
        else -> sentinel
    }

    /** Shows a premium dialog on successful connection test. */
    private fun showSuccessDialog(share: NetworkShare) {
        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_connection_success
            else      R.layout.dialog_connection_success_mobile,
            null
        )

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btnSuccessSave).setOnClickListener {
            repo.save(share)
            dialog.dismiss()
            finish()
        }
        dialogView.findViewById<MaterialButton>(R.id.btnSuccessCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun saveShare() {
        if (!connectionTested) return   // safety net; button should already be disabled
        val share = buildShareFromFields() ?: return
        repo.save(share)
        finish()
    }

    /** Shows an inline error or status result (used for errors and getString(R.string.testing)). */
    private fun showErrorResult(msg: String) {
        txtResult.text = msg
        txtResult.visibility = View.VISIBLE
        val isError = !msg.contains(getString(R.string.network_testing))
        txtResult.setTextColor(
            getColor(if (isError) R.color.status_error else R.color.ufm_primary_light)
        )

        // Show NFS debug log section when an NFS error is displayed
        if (isError && layerNfsDebugLog != null) {
            val lastEntry = NfsDebugLogger.lastEntry()
            if (lastEntry != null) {
                layerNfsDebugLog?.visibility = View.VISIBLE
                txtNfsDebugLog?.text = NfsDebugLogger.exportSummary()
                btnCopyDebugLog?.setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("NFS Debug Log", NfsDebugLogger.exportSummary())
                    )
                    Toast.makeText(this, R.string.nfs_debug_log_copied, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
