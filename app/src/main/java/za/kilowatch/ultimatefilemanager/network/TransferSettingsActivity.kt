package za.kilowatch.ultimatefilemanager.network

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import android.widget.ImageView
import za.kilowatch.ultimatefilemanager.settings.DefaultIconColorManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.SettingsTransferManager
import za.kilowatch.ultimatefilemanager.settings.SettingsTransferManager.SettingItem
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.server.FileServerService
import java.util.UUID

/**
 * Mobile-side activity to select settings/shares for secure transfer to a paired TV.
 *
 * Security design:
 *  H-2: [TvShareClient.transferSettings] is only called from inside
 *       [BiometricPrompt.AuthenticationCallback.onAuthenticationSucceeded].
 *  C-1: Payload built as a ByteArray and sent directly to TvShareClient — no String holding.
 *  C-2: A fresh nonce UUID is embedded in each payload.
 */
class TransferSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHARE_HOST = "extra_ts_share_host"
        const val EXTRA_SHARE_PORT = "extra_ts_share_port"
        private const val TAG = "TransferSettingsActivity"
    }

    private lateinit var recyclerItems: RecyclerView
    private lateinit var btnTransfer: MaterialButton
    private lateinit var txtSubtitle: TextView
    private lateinit var txtSelectAll: TextView

    private lateinit var share: NetworkShare
    private lateinit var listAdapter: TransferSettingsAdapter

    override fun attachBaseContext(base: Context) = super.attachBaseContext(
        LocaleHelper.wrap(base)  // chains FontSizeHelper.applyTo() + LocaleHelper.applyTo()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transfer_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        // Resolve the TV share from intent extras
        val pairedDeviceId = intent.getStringExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID) ?: ""
        val host = intent.getStringExtra(EXTRA_SHARE_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_SHARE_PORT, 8085)
        val pm = PairingManager.getInstance(this)
        val device = pm.getPairedDevice(pairedDeviceId) ?: pm.getAllPairedDevices()
            .firstOrNull { it.lastIp == host }
        if (device == null) {
            Toast.makeText(this, getString(R.string.connection_failed_make_sure_the), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        share = NetworkShare(
            type = ShareType.TV,
            host = device.lastIp,
            port = device.lastPort,
            name = device.name
        )

        txtSubtitle  = findViewById(R.id.txtSubtitle)
        txtSelectAll = findViewById(R.id.txtSelectAll)
        recyclerItems = findViewById(R.id.recyclerItems)
        btnTransfer   = findViewById(R.id.btnTransfer)

        findViewById<TextView>(R.id.txtTargetDeviceName)?.text = device.name
        txtSubtitle.text = getString(
            R.string.transfer_settings_subtitle,
            android.os.Build.MODEL,
            device.name
        )

        // Build the item list
        val settingItems = SettingsTransferManager.collectTransferableSettings(this)
        val shareItems   = SettingsTransferManager.getTransferableShares(this)
        
        val fileServerPrefs = getSharedPreferences("ufm_file_server", Context.MODE_PRIVATE)
        // If "port" is not saved yet, don't show the file server settings transfer option
        val fileServerPort = if (fileServerPrefs.contains("port")) fileServerPrefs.getInt("port", 8080) else null

        listAdapter = TransferSettingsAdapter(
            context        = this,
            settingItems   = settingItems,
            shareItems     = shareItems,
            fileServerPort = fileServerPort
        )
        recyclerItems.layoutManager = LinearLayoutManager(this)
        recyclerItems.adapter       = listAdapter

        // Select All toggle
        txtSelectAll.setOnClickListener {
            listAdapter.toggleSelectAll()
        }

        // Back
        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // Transfer button
        btnTransfer.setOnClickListener { onTransferClicked() }
    }

    private fun onTransferClicked() {
        val selectedSettings = listAdapter.getSelectedSettings()
        val selectedShares   = listAdapter.getSelectedShares()
        val transferFileServer = listAdapter.isFileServerSelected()

        if (selectedSettings.isEmpty() && selectedShares.isEmpty() && !transferFileServer) {
            Toast.makeText(this, getString(R.string.please_select_a_network_share), Toast.LENGTH_SHORT).show()
            return
        }

        // Check biometric / device credential enrolment
        val biometricManager = BiometricManager.from(this)
        val canAuth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        } else {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isDeviceSecure = keyguardManager?.isDeviceSecure == true
            val biometricStatus = biometricManager.canAuthenticate(BIOMETRIC_STRONG)
            if (isDeviceSecure || biometricStatus == BiometricManager.BIOMETRIC_SUCCESS) {
                BiometricManager.BIOMETRIC_SUCCESS
            } else {
                biometricStatus
            }
        }

        if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
            canAuth == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
            canAuth == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
            showSecurityRequiredDialog()
            return
        }

        // Show biometric prompt — transfer only inside onAuthenticationSucceeded (H-2)
        val executor = ContextCompat.getMainExecutor(this)
        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.transfer_settings))
            .setSubtitle(getString(R.string.transfer_settings_biometric_subtitle))
            .setConfirmationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            promptInfoBuilder.setDeviceCredentialAllowed(true)
        }

        val promptInfo = promptInfoBuilder.build()

        BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // H-2: Network call ONLY here, inside the success callback
                doTransfer(selectedSettings, selectedShares, transferFileServer)
            }
            override fun onAuthenticationError(code: Int, errString: CharSequence) {
                if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                    code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(this@TransferSettingsActivity, errString, Toast.LENGTH_SHORT).show()
                }
            }
            override fun onAuthenticationFailed() {
                Toast.makeText(this@TransferSettingsActivity,
                    getString(R.string.connection_failed_make_sure_the), Toast.LENGTH_SHORT).show()
            }
        }).authenticate(promptInfo)
    }

    /**
     * Builds the payload and sends it to the TV.
     * Called exclusively from [BiometricPrompt.AuthenticationCallback.onAuthenticationSucceeded].
     */
    private fun doTransfer(
        selectedSettings: List<SettingItem>,
        selectedShares: List<NetworkShare>,
        fileServer: Boolean
    ) {
        btnTransfer.isEnabled = false
        btnTransfer.text = getString(R.string.processing)

        val deviceName = Build.MODEL ?: getString(R.string.android_device)
        val nonce = UUID.randomUUID().toString()

        // Read file server port if selected
        val fileServerPort: Int? = if (fileServer) {
            getSharedPreferences("ufm_file_server", Context.MODE_PRIVATE).getInt("port", 8080)
        } else null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // C-2: Fresh nonce embedded in every payload
                val payloadBytes = SettingsTransferManager.buildPayload(
                    selectedSettings = selectedSettings,
                    selectedShares   = selectedShares,
                    deviceName       = deviceName,
                    nonce            = nonce,
                    fileServerPort   = fileServerPort
                )

                // Send over pinned HTTPS tunnel
                TvShareClient.transferSettings(share, payloadBytes)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@TransferSettingsActivity,
                        getString(R.string.transfer_settings_success, share.name),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transfer failed", e)
                withContext(Dispatchers.Main) {
                    btnTransfer.isEnabled = true
                    btnTransfer.text = getString(R.string.transfer_to_tv)
                    Toast.makeText(
                        this@TransferSettingsActivity,
                        getString(R.string.transfer_settings_timeout),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showSecurityRequiredDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_security_required, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialogView.findViewById<View>(R.id.btnOpenSettings)?.setOnClickListener {
            dialog.dismiss()
            startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
        }
        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }
}

// ─── Adapter ────────────────────────────────────────────────────────────────

class TransferSettingsAdapter(
    private val context: Context,
    private val settingItems: List<SettingItem>,
    private val shareItems: List<NetworkShare>,
    private val fileServerPort: Int?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM   = 1
    }

    private sealed class Row {
        data class Header(val label: String) : Row()
        data class SettingRow(val item: SettingItem, var checked: Boolean = false) : Row()
        data class ShareRow(val share: NetworkShare, var checked: Boolean = false) : Row()
        data class FileServerRow(val port: Int, var checked: Boolean = false) : Row()
    }

    private val rows: MutableList<Row> = buildRows()

    private fun buildRows(): MutableList<Row> {
        val list = mutableListOf<Row>()
        if (settingItems.isNotEmpty()) {
            list.add(Row.Header(context.getString(R.string.general_app_settings)))
            settingItems.forEach { list.add(Row.SettingRow(it)) }
        }
        if (shareItems.isNotEmpty()) {
            list.add(Row.Header(context.getString(R.string.transfer_settings_section_network)))
            shareItems.forEach { list.add(Row.ShareRow(it)) }
        }
        if (fileServerPort != null) {
            list.add(Row.Header(context.getString(R.string.transfer_settings_section_fileserver)))
            list.add(Row.FileServerRow(fileServerPort))
        }
        return list
    }

    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = android.view.LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val v = inflater.inflate(R.layout.item_section_header, parent, false)
            HeaderVH(v)
        } else {
            val v = inflater.inflate(R.layout.item_transfer_setting, parent, false)
            ItemVH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(row.label)
            is Row.SettingRow -> (holder as ItemVH).bind(
                iconRes  = R.drawable.ic_settings,
                label    = context.getString(R.string.general_setting_prefix, row.item.displayLabel),
                subLabel = null,
                checked  = row.checked,
                onToggle = { row.checked = it }
            )
            is Row.ShareRow -> {
                val (iconRes, prefix) = when (row.share.type) {
                    ShareType.SMB  -> Pair(R.drawable.ic_network,     context.getString(R.string.smb_share_prefix,  row.share.name, row.share.host))
                    ShareType.FTP  -> Pair(R.drawable.ic_network,     context.getString(R.string.ftp_share_prefix,  row.share.name, row.share.host))
                    ShareType.SFTP -> Pair(R.drawable.ic_lock,        context.getString(R.string.sftp_share_prefix, row.share.name, row.share.host))
                    ShareType.SCP  -> Pair(R.drawable.ic_lock,        context.getString(R.string.scp_share_prefix,  row.share.name, row.share.host))
                    ShareType.NFS  -> Pair(R.drawable.ic_nfs,         row.share.name)
                    else           -> Pair(R.drawable.ic_network,     row.share.name)
                }
                (holder as ItemVH).bind(
                    iconRes  = iconRes,
                    label    = prefix,
                    subLabel = row.share.username.takeIf { it.isNotEmpty() }?.let { "User: $it" },
                    checked  = row.checked,
                    onToggle = { row.checked = it }
                )
            }
            is Row.FileServerRow -> (holder as ItemVH).bind(
                iconRes  = R.drawable.ic_file_server,
                label    = context.getString(R.string.file_server_port_item, row.port),
                subLabel = context.getString(R.string.file_server_pin_warning),
                checked  = row.checked,
                onToggle = { row.checked = it }
            )
        }
    }

    fun toggleSelectAll() {
        val anyUnchecked = rows.any { it is Row.SettingRow && !(it as Row.SettingRow).checked ||
                                     it is Row.ShareRow && !(it as Row.ShareRow).checked ||
                                     it is Row.FileServerRow && !(it as Row.FileServerRow).checked }
        rows.forEach {
            if (it is Row.SettingRow)    it.checked = anyUnchecked
            if (it is Row.ShareRow)      it.checked = anyUnchecked
            if (it is Row.FileServerRow) it.checked = anyUnchecked
        }
        notifyDataSetChanged()
    }

    fun getSelectedSettings() = rows.filterIsInstance<Row.SettingRow>()
        .filter { it.checked }.map { it.item }

    fun getSelectedShares() = rows.filterIsInstance<Row.ShareRow>()
        .filter { it.checked }.map { it.share }

    fun isFileServerSelected() = rows.filterIsInstance<Row.FileServerRow>()
        .firstOrNull()?.checked == true

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txt = v.findViewById<TextView>(R.id.txtSectionHeader)
        fun bind(label: String) { txt.text = label }
    }

    inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        private val checkBox = v.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkBox)
        private val txtLabel = v.findViewById<TextView>(R.id.txtLabel)
        private val txtSub   = v.findViewById<TextView>(R.id.txtSubLabel)
        private val imgIcon  = v.findViewById<ImageView>(R.id.imgSettingIcon)

        fun bind(iconRes: Int, label: String, subLabel: String?, checked: Boolean, onToggle: (Boolean) -> Unit) {
            imgIcon?.setImageResource(iconRes)
            val accent = za.kilowatch.ultimatefilemanager.settings.DefaultIconColorManager.getMobileIconTint(context)
            imgIcon?.imageTintList = android.content.res.ColorStateList.valueOf(accent)
            txtLabel.text = label
            checkBox.isChecked = checked
            if (subLabel != null) {
                txtSub.text = subLabel
                txtSub.visibility = View.VISIBLE
            } else {
                txtSub.visibility = View.GONE
            }
            itemView.setOnClickListener {
                val newState = !checkBox.isChecked
                checkBox.isChecked = newState
                onToggle(newState)
            }
        }
    }
}
