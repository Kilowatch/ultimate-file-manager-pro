package za.kilowatch.ultimatefilemanager.remote

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.ui.policy.ProminentDisclosureHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Arrays
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Displays server IP:port and manages the embedded file server lifecycle.
 * The server is activity-scoped — it starts in onCreate, stops in onDestroy.
 * Leaving this screen stops the server.
 */
class RemoteManageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PIN = "extra_pin"
        private const val PORT = 8444
    }

    private var fileServer: FileServer? = null
    private var pendingCertPath: String? = null

    private val certPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (path != null) {
                if (path.endsWith(".p12", true) || path.endsWith(".pfx", true)) {
                    promptPkcs12Password(path)
                } else {
                    pendingCertPath = path
                    keyPickerLauncher.launch(
                        Intent(this, StorageBrowserActivity::class.java).apply {
                            putExtra(StorageBrowserActivity.EXTRA_KEYFILE_PICKER, true)
                        }
                    )
                }
            }
        }
    }

    private val keyPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val keyPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (keyPath != null && pendingCertPath != null) {
                importCert(pendingCertPath!!, keyPath)
                pendingCertPath = null
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (DeviceUtils.isTvDevice(this)) {
            setContentView(R.layout.activity_remote_manage_tv)
        } else {
            setContentView(R.layout.activity_remote_manage)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val isTv = DeviceUtils.isTvDevice(this)
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad,
                systemBars.top + tvPad,
                systemBars.right + tvPad,
                systemBars.bottom + tvPad
            )
            insets
        }

        val pin = intent.getStringExtra(EXTRA_PIN) ?: run {
            finish()
            return
        }

        setupUI(pin)
        // setupCertButton/updateCaStatusBadge read prefs directly, not the running server:
        setupCertButton()
        updateCaStatusBadge()
        if (savedInstanceState == null) {
            // First creation only — avoid re-binding the port on config change re-creates.
            startServer(pin)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    private fun updateFingerprintDisplay() {
        val label = findViewById<TextView>(R.id.txtFingerprintLabel)
        val value = findViewById<TextView>(R.id.txtFingerprint)
        val hint = findViewById<TextView>(R.id.txtVerifyFingerprint)
        fileServer?.serverCertFingerprint?.let { fp ->
            value.text = fp.chunked(2).joinToString(":") { it.uppercase() }
            value.visibility = View.VISIBLE
            label.visibility = View.VISIBLE
            hint.visibility = View.VISIBLE
        }
    }

    /**
     * Reads the custom-cert prefs directly without needing a running [FileServer].
     * Safe to call before [startServer] completes.
     */
    private fun hasCustomCertImported(): Boolean {
        val certPrefs = getSharedPreferences("ufm_remote_cert_prefs", android.content.Context.MODE_PRIVATE)
        return certPrefs.getBoolean("custom_cert_imported", false)
    }

    /**
     * Updates the CA trust-status badge:
     *   • Green shield + "Verified — Custom CA"        when a custom cert is loaded
     *   • Red shield   + "Unverified — Auto-generated CA" when using the default self-signed cert
     */
    private fun updateCaStatusBadge() {
        val layout = findViewById<LinearLayout>(R.id.layoutCaStatus) ?: return
        val imgShield = findViewById<ImageView>(R.id.imgCaShield) ?: return
        val txtStatus = findViewById<TextView>(R.id.txtCaStatus) ?: return
        layout.visibility = View.VISIBLE
        // Use prefs directly so badge is correct before the server has started.
        if (fileServer?.hasCustomCert() == true || hasCustomCertImported()) {
            imgShield.setImageResource(R.drawable.ic_shield_check)
            imgShield.imageTintList = ColorStateList.valueOf(getColor(R.color.ufm_granted))
            txtStatus.text = getString(R.string.remote_ca_status_verified)
            txtStatus.setTextColor(getColor(R.color.ufm_granted))
        } else {
            imgShield.setImageResource(R.drawable.ic_shield_alert)
            imgShield.imageTintList = ColorStateList.valueOf(getColor(R.color.ufm_denied))
            txtStatus.text = getString(R.string.remote_ca_status_unverified)
            txtStatus.setTextColor(getColor(R.color.ufm_denied))
        }
    }

    private fun setupCertButton() {
        val btn = findViewById<MaterialButton>(R.id.btnUseCA) ?: return
        val isTv = DeviceUtils.isTvDevice(this)
        // Use prefs directly so button state is correct before the server has started.
        if (fileServer?.hasCustomCert() == true || hasCustomCertImported()) {
            btn.text = getString(R.string.remote_ca_action_remove)
            btn.setIconResource(R.drawable.ic_check)
            btn.setOnClickListener { promptRemoveCert() }
        } else {
            btn.text = getString(R.string.remote_ca_action_import)
            btn.setIconResource(R.drawable.ic_lock)
            btn.setOnClickListener { showCaInfoDialog() }
        }
        btn.visibility = View.VISIBLE

        // TV: wire yellow focus highlight for Import/Remove CA button
        if (isTv) {
            val white = getColor(R.color.tv_text_primary)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            val yellow = getColor(R.color.tv_button_focused_yellow)
            val yellowCsl = ColorStateList.valueOf(yellow)
            // Keep the gradient background as default; swap to yellow on focus
            val defaultBgTint: ColorStateList? = null  // gradient drawable, no tint override
            btn.setTextColor(white)
            btn.iconTint = ColorStateList.valueOf(white)
            btn.setOnFocusChangeListener { _, hasFocus ->
                btn.backgroundTintList = if (hasFocus) yellowCsl else defaultBgTint
                btn.setTextColor(if (hasFocus) black else white)
                btn.iconTint = ColorStateList.valueOf(if (hasFocus) black else white)
            }
        }
    }

    /**
     * Premium info dialog explaining what a CA cert is and offering to browse for one.
     * Shown whenever the user taps "Import CA Certificate".
     */
    private fun showCaInfoDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_ca_info_title)
            .setMessage(R.string.remote_ca_info_message)
            .setIcon(R.drawable.ic_lock)
            .setPositiveButton(R.string.remote_ca_info_browse) { _: DialogInterface, _: Int ->
                openCertPicker()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openCertPicker() {
        certPickerLauncher.launch(
            Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_CERT_PICKER, true)
            }
        )
    }

    private fun promptPkcs12Password(path: String) {
        // Build a TextInputLayout so the user gets a password-toggle eye button
        val inputLayout = com.google.android.material.textfield.TextInputLayout(this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.remote_cert_password_hint)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(0, 8, 0, 0)
        }
        val inputField = com.google.android.material.textfield.TextInputEditText(inputLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputLayout.addView(inputField)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val hPad = (24 * resources.displayMetrics.density).toInt()
            val vPad = (12 * resources.displayMetrics.density).toInt()
            setPadding(hPad, vPad, hPad, 0)
            addView(inputLayout)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_cert_password_title)
            .setView(container)
            .setPositiveButton(R.string.remote_cert_confirm) { _: DialogInterface, _: Int ->
                val pw = inputField.text.toString().toCharArray()
                if (fileServer?.importPkcs12Cert(path, pw) == true) {
                    Toast.makeText(this, R.string.remote_cert_imported, Toast.LENGTH_SHORT).show()
                    restartServer()
                } else {
                    Toast.makeText(this, R.string.remote_cert_failed, Toast.LENGTH_LONG).show()
                }
                Arrays.fill(pw, '0')
            }
            .setNegativeButton(R.string.cancel, null)
            .show()

        // Auto-focus the field and open the keyboard
        inputField.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun importCert(certPath: String, keyPath: String) {
        if (fileServer?.importPemCert(certPath, keyPath) == true) {
            Toast.makeText(this, R.string.remote_cert_imported, Toast.LENGTH_SHORT).show()
            restartServer()
        } else {
            Toast.makeText(this, R.string.remote_cert_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun promptRemoveCert() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_cert_remove_title)
            .setMessage(R.string.remote_cert_remove_msg)
            .setPositiveButton(R.string.remote_cert_remove) { _: DialogInterface, _: Int ->
                fileServer?.removeCustomCert()
                restartServer()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restartServer() {
        val pin = intent.getStringExtra(EXTRA_PIN) ?: return
        stopServer()
        // Small delay so the OS releases the port before we re-bind.
        findViewById<View>(R.id.main).postDelayed({
            startServer(pin)
        }, 400)
    }

    private fun setupUI(pin: String) {
        val txtServerUrl = findViewById<TextView>(R.id.txtServerUrl)
        val btnStopServer = findViewById<MaterialButton>(R.id.btnStopServer)
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val viewStatusDot = findViewById<View>(R.id.viewStatusDot)

        val ipAddress = getDeviceIpAddress()
        // Placeholder — updated with the real port once the server binds successfully.
        val url = "https://$ipAddress:$PORT"
        txtServerUrl.text = url

        // Continuous pulse animation for status dot
        fun pulse() {
            viewStatusDot.animate()
                .alpha(0.2f)
                .setDuration(1200)
                .withEndAction {
                    viewStatusDot.animate()
                        .alpha(1f)
                        .setDuration(1200)
                        .withEndAction { pulse() }
                        .start()
                }
                .start()
        }
        pulse()

        btnStopServer.setOnClickListener {
            navigateBack()
        }

        btnBack.setOnClickListener {
            navigateBack()
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        // TV: wire focus states for icon tint and button text
        if (DeviceUtils.isTvDevice(this)) {
            val white = getColor(R.color.tv_text_primary)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            val yellow = getColor(R.color.tv_button_focused_yellow)
            val yellowCsl = ColorStateList.valueOf(yellow)
            val glassCsl  = ColorStateList.valueOf(0x26FFFFFF.toInt())

            // Back icon: white tint default, near-black on yellow focus
            btnBack.imageTintList = ColorStateList.valueOf(white)
            btnBack.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = ColorStateList.valueOf(
                    if (hasFocus) black else white
                )
            }

            // Stop button: glass-white default, yellow on focus with black text
            btnStopServer.backgroundTintList = glassCsl
            btnStopServer.setTextColor(white)
            btnStopServer.iconTint = ColorStateList.valueOf(white)
            btnStopServer.setOnFocusChangeListener { _, hasFocus ->
                btnStopServer.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnStopServer.setTextColor(if (hasFocus) black else white)
                btnStopServer.iconTint = ColorStateList.valueOf(
                    if (hasFocus) black else white
                )
            }
        }
    }

    private fun startServer(pin: String) {
        // Show the Remote File Server apps disclosure if not yet accepted.
        // The server starts regardless of acceptance — only /api/apps is gated.
        ProminentDisclosureHelper.showRemoteAppsIfNeeded(
            activity = this,
            onContinue = { doStartServer(pin) },
            onCancel = { doStartServer(pin) }
        )
    }

    private fun doStartServer(pin: String) {
        // Kill any still-running global instance synchronously before trying to bind.
        FileServer.stopGlobal()

        val progressContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padH = (24 * resources.displayMetrics.density).toInt()
            val padV = (20 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(android.widget.ProgressBar(this@RemoteManageActivity).apply {
                isIndeterminate = true
            })
            addView(TextView(this@RemoteManageActivity).apply {
                text = getString(R.string.remote_server_starting_msg)
                textSize = 16f
                setTextColor(getColor(R.color.tv_text_primary))
                val textPad = (16 * resources.displayMetrics.density).toInt()
                setPadding(textPad, 0, 0, 0)
            })
        }

        val startingDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_server_starting_title)
            .setView(progressContainer)
            .setCancelable(false)
            .show()

        // Offload to a background thread so:
        //  a) The main thread is never blocked (no frame skipping)
        //  b) The OS gets a moment to fully release the port after stopGlobal()
        Thread({
            Thread.sleep(150) // Brief pause — lets the OS complete port teardown
            try {
                val server = FileServer(
                    applicationContext, pin,
                    za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.getInstance(applicationContext),
                    PORT
                )
                server.start()
                fileServer = server
                runOnUiThread {
                    startingDialog.dismiss()
                    // Refresh URL with actual bound port (may differ from 8443 if fallback was used).
                    val ip = getDeviceIpAddress()
                    findViewById<TextView>(R.id.txtServerUrl)?.text = "https://$ip:${server.boundPort}"
                    updateFingerprintDisplay()
                    setupCertButton()
                    updateCaStatusBadge()
                }
            } catch (e: Exception) {
                android.util.Log.e("RemoteManageActivity", "startServer failed", e)
                runOnUiThread {
                    startingDialog.dismiss()
                    val txtStatus = findViewById<TextView>(R.id.txtStatus)
                    txtStatus?.text = getString(R.string.remote_server_error)
                    txtStatus?.setTextColor(getColor(R.color.ufm_denied))
                }
            }
        }, "file-server-start").start()
    }

    private fun stopServer() {
        fileServer?.stop()
        fileServer = null
    }

    /**
     * Gets the device's LAN IP address.
     * Tries WifiManager first, then falls back to NetworkInterface enumeration.
     */
    private fun getDeviceIpAddress(): String {
        // Try WifiManager
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ip = wifiInfo.ipAddress
            if (ip != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
            }
        } catch (_: Exception) { }

        // Fallback: enumerate network interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) { }

        return "0.0.0.0"
    }

    private fun navigateBack() {
        if (isTaskRoot) {
            val intent = android.content.Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
            startActivity(intent)
        }
        finish()
    }
}
