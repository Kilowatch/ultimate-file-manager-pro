package za.kilowatch.ultimatefilemanager.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.remote.AdbRemoteForegroundService
import za.kilowatch.ultimatefilemanager.remote.AdbWifiTransport
import za.kilowatch.ultimatefilemanager.remote.BluetoothRemoteTransport
import za.kilowatch.ultimatefilemanager.remote.ManualDevice
import za.kilowatch.ultimatefilemanager.remote.RemoteTransport
import za.kilowatch.ultimatefilemanager.remote.RemoteTransportPrefs
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Full-screen TV Remote Control activity (mobile-only).
 *
 * Architecture:
 *  - Starts [BluetoothHidService] on open → service owns the HID registration,
 *    keeping the connection alive even when the activity goes to background.
 *  - Stops [BluetoothHidService] on explicit Back press → shows "Remote disconnected".
 *  - Auto-connects to the saved default TV as soon as the HID app registers
 *    (handled in the service; activity just observes state flows).
 *  - Shows a smart [MaterialCardView] status card with context-aware guidance
 *    and a single primary action button that adapts per connection state.
 */
@SuppressLint("MissingPermission")
class TvRemoteActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    companion object {
        private const val TAG = "TvRemoteActivity"

        // USB HID Keycodes (Usage Page 0x07 - Keyboard)
        private const val HID_DPAD_UP: Byte         = 0x52
        private const val HID_DPAD_DOWN: Byte       = 0x51
        private const val HID_DPAD_LEFT: Byte       = 0x50
        private const val HID_DPAD_RIGHT: Byte      = 0x4F
        private const val HID_DPAD_CENTER: Byte     = 0x28 // Enter

        // USB HID Consumer Control (Usage Page 0x0C)
        private const val HID_HOME             = 0x0223
        private const val HID_BACK             = 0x0224
        private const val HID_VOLUME_UP        = 0x00E9
        private const val HID_VOLUME_DOWN      = 0x00EA
        private const val HID_VOLUME_MUTE      = 0x00E2
        private const val HID_MEDIA_PLAY_PAUSE = 0x00CD
        private const val HID_MEDIA_STOP       = 0x00B7
        private const val HID_MEDIA_REWIND     = 0x00B4
        private const val HID_MEDIA_FAST_FWD   = 0x00B3

        private const val REPEAT_DELAY_MS          = 100L
        private const val REPEAT_INITIAL_DELAY_MS  = 400L
        private const val VIBRATE_MS               = 35L
    }

    private var btManager: BluetoothRemoteManager? = null
    private var currentTransport: RemoteTransport? = null
    private var transportObserverJob: Job? = null
    private var manualConnectJob: Job? = null
    private var currentBottomSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    // ── UI references ─────────────────────────────────────────────────────

    private lateinit var toolbar: MaterialToolbar
    private lateinit var coordinatorRoot: View

    private lateinit var viewStatusDot: View
    private lateinit var txtStatusLabel: TextView

    private lateinit var progressConnecting: ProgressBar
    private lateinit var btnStatusAction: MaterialButton

    // D-Pad
    private lateinit var btnDpadUp: ImageButton
    private lateinit var btnDpadDown: ImageButton
    private lateinit var btnDpadLeft: ImageButton
    private lateinit var btnDpadRight: ImageButton
    private lateinit var btnDpadOk: MaterialButton

    // Nav
    private lateinit var btnNavBack: LinearLayout
    private lateinit var btnNavHome: LinearLayout

    // Volume (always visible in main layout)
    private lateinit var layoutVolumeRow:      LinearLayout
    private lateinit var btnTogglePhoneVolume: LinearLayout
    private lateinit var txtToggleVolume:      TextView
    private lateinit var imgToggleVolume:      ImageView
    private lateinit var btnVolDown:           ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var btnVolUp: ImageButton

    // Media (always visible in main layout)
    private lateinit var btnRewind: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnFastForward: ImageButton

    // Keyboard panel (toggled via FAB)
    private lateinit var fabKeyboard: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var layoutKeyboardPanel: LinearLayout
    private lateinit var etKeyboard: TextInputEditText
    private lateinit var btnKbBackspace: MaterialButton
    private lateinit var btnKbEnter: MaterialButton
    private lateinit var btnKbClear: MaterialButton

    private var isKeyboardVisible = false
    private var isMuted = false
    private var isBluetoothReceiverRegistered = false

    private var previousKeyboardText = ""

    private var repeatJob: Job? = null

    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    checkPermissions()
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    showCardBluetoothOff()
                }
            }
        }
    }

    // Permission launcher for Android 12+ Bluetooth permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            startBluetoothFlow()
        } else {
            showCardNoPermission()
        }
    }

    private val setupGuideLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            launchBluetoothSettings()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_tv_remote)

        bindViews()

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(coordinatorRoot) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            btManager = BluetoothRemoteManager.getInstance(this)
        }

        setupToolbar()
        setupButtons()
        observeConnectionState()
        checkExistingConnection()

        registerReceiver(bluetoothStateReceiver, android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        isBluetoothReceiverRegistered = true
    }

    /**
     * On config change (isFinishing == false): leave the service running.
     * The service is intentionally NOT stopped here — the BT connection must persist
     * when the user exits the activity. It is only stopped on:
     *  • Manual disconnect (toolbar action)
     *  • App task removal (onTaskRemoved in BluetoothHidService)
     *  • When the activity finishes and no TV is paired
     */
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (btManager?.isUfmPairingSessionActive == true) {
                val candidates = btManager?.getCandidateTvsForUfm() ?: emptyList()
                if (candidates.isNotEmpty()) {
                    // Auto-save all devices bonded during the UFM pairing session.
                    candidates.forEach { btManager?.saveTvDevice(it) }
                    // Set the last paired device as default and connect to it.
                    val newDevice = candidates.last()
                    btManager?.setDefaultTv(newDevice.address)
                    btManager?.manualDisconnect = false // clear flag so autoConnect works
                    if (btManager?.appRegistrationState?.value == true) {
                        btManager?.connectToDevice(newDevice)
                    } else {
                        // The user manually disconnected the previous TV, which stopped the service.
                        // We must restart it now so the HID proxy and SDP records are recreated.
                        // The service will auto-connect to the new default TV once registered.
                        BluetoothHidService.start(this)
                    }
                    // Wrap transport and start observing so UI reflects the connection
                    currentTransport = BluetoothRemoteTransport(btManager!!)
                    startTransportObserver()
                    // Save BT as last-used transport and last connected device
                    val prefs = RemoteTransportPrefs(this)
                    prefs.setLastTransport(newDevice.address, "bluetooth")
                    prefs.setLastConnectedRemoteDevice(newDevice.address, newDevice.name ?: newDevice.address, "bluetooth")
                    btManager?.endUfmPairingSession()
                    updatePairMenuTitle()
                }
                // If no candidates yet, keep session alive (pairing may still
                // be in progress — Android's pairing dialog can trigger onResume).
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBluetoothReceiverRegistered) {
            unregisterReceiver(bluetoothStateReceiver)
            isBluetoothReceiverRegistered = false
        }
        repeatJob?.cancel()
        transportObserverJob?.cancel()
        manualConnectJob?.cancel()

        if (isFinishing) {
            val isConnected = currentTransport?.isConnected() == true
            if (!isConnected) {
                Log.d(TAG, "Activity finishing with no active connection — stopping services")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    BluetoothHidService.stop(this)
                }
                AdbRemoteForegroundService.stop()
            } else {
                Log.d(TAG, "Activity finishing but transport is connected — keeping services running")
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Do NOT stop the foreground service if a TV is paired — the BT connection 
        // must stay alive when the user navigates away. The service is stopped on 
        // manual disconnect, app task removal, or in onDestroy if no TV is paired.
        super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val prefs = getSharedPreferences("TvRemotePrefs", Context.MODE_PRIVATE)
        val usePhoneVolume = prefs.getBoolean("usePhoneVolume", false)
        if (usePhoneVolume) {
            val action = event.action
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                val hidKey = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) HID_VOLUME_UP else HID_VOLUME_DOWN
                when (action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            vibrate()
                            currentTransport?.sendConsumerKey(hidKey)
                            repeatJob?.cancel()
                            repeatJob = lifecycleScope.launch {
                                kotlinx.coroutines.delay(REPEAT_INITIAL_DELAY_MS)
                                while (isActive) {
                                    vibrate()
                                    currentTransport?.sendConsumerKey(hidKey)
                                    kotlinx.coroutines.delay(REPEAT_DELAY_MS)
                                }
                            }
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        repeatJob?.cancel()
                        repeatJob = null
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Connection & Transport Selection ──────────────────────────────────

    /**
     * Called from onCreate. If a BT connection is already active, wrap it and
     * proceed immediately. Otherwise show the appropriate status card.
     */
    private fun checkExistingConnection() {
        // 1. Check Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            btManager?.connectionState?.value == BluetoothProfile.STATE_CONNECTED) {
            currentTransport = BluetoothRemoteTransport(btManager!!)
            startTransportObserver()
            val name = btManager?.connectedDeviceName?.value ?: ""
            showCardConnected(name)
            return
        }

        // 2. Check ADB (connection survives activity recreation via AdbManager singleton)
        val adbManager = AdbManager.getInstance()
        if (adbManager.isConnected() && adbManager.isRemoteMode) {
            val deviceId = adbManager.activeRemoteDeviceId
            if (deviceId != null) {
                // Check paired devices first
                val paired = PairingManager.getInstance(this).getPairedDevice(deviceId)
                if (paired != null) {
                    currentTransport = AdbWifiTransport.reconnect(paired)
                    startTransportObserver()
                    showCardConnected(paired.name)
                    return
                }
                // Check manual devices
                val manual = RemoteTransportPrefs(this).getManualDevices().find { it.deviceId == deviceId }
                if (manual != null) {
                    currentTransport = AdbWifiTransport.reconnect(ManualDevice.toPairedDevice(manual))
                    startTransportObserver()
                    showCardConnected(manual.name)
                    return
                }
            }
        }

        // 3. Neither connected
        refreshDisconnectedCard()
    }

    /**
     * Shows the transport picker dialog with Bluetooth and WiFi/ADB options.
     */
    private fun showTransportPicker() {
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.remote_transport_picker_title)
            .setItems(arrayOf(
                getString(R.string.remote_transport_bt_option),
                getString(R.string.remote_transport_wifi_option)
            )) { _, which ->
                when (which) {
                    0 -> {
                        // Bluetooth — ensure permissions, then start the BT flow
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val needed = arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_ADVERTISE
                            )
                            val missing = needed.filter {
                                ContextCompat.checkSelfPermission(this@TvRemoteActivity, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (missing.isNotEmpty()) {
                                requestPermissionLauncher.launch(missing.toTypedArray())
                            } else {
                                startBluetoothFlow()
                            }
                        } else {
                            startBluetoothFlow()
                        }
                    }
                    1 -> connectViaAdbTransport(directConnect = false)
                }
            }
            .setNegativeButton(R.string.bt_remote_cancel, null)
            .show()
    }

    /** Attempt to connect via WiFi ADB using a "Use Remote"-enabled paired TV. */
    private fun connectViaAdbTransport(directConnect: Boolean = false, deviceId: String? = null) {
        val transportPrefs = RemoteTransportPrefs(this)
        val pairingManager = PairingManager.getInstance(this)
        val manualDevices = transportPrefs.getManualDevices()
        val manualIps = transportPrefs.getManualDeviceIps()
        val adbManager = AdbManager.getInstance()

        // Direct connect: check paired devices AND manual devices
        if (directConnect || deviceId != null) {
            val targetId = deviceId ?: transportPrefs.getLastConnectedRemoteDeviceId() ?: adbManager.activeRemoteDeviceId
            // Check paired first
            val paired = targetId?.let { pairingManager.getPairedDevice(it) }
            if (paired != null) {
                doAdbConnect(paired, transportPrefs)
                return
            }
            // Check manual devices
            val manual = manualDevices.find { it.deviceId == targetId }
            if (manual != null) {
                doAdbConnectManual(manual, transportPrefs)
                return
            }
        }

        // Collect paired devices, deduplicated against manual IPs
        val enabledIds = transportPrefs.getRemoteEnabledDeviceIds()
        val pairedDevices = enabledIds.mapNotNull { pairingManager.getPairedDevice(it) }
            .filter { it.lastIp !in manualIps }

        // Build combined list
        data class AdbEntry(val name: String, val paired: PairedDevice?, val manual: ManualDevice?)
        val allEntries = mutableListOf<AdbEntry>()
        for (p in pairedDevices) {
            allEntries.add(AdbEntry("${p.name}  (${p.lastIp})", paired = p, manual = null))
        }
        for (m in manualDevices) {
            allEntries.add(AdbEntry("${m.name}  (${m.ip})${getString(R.string.manual_device_suffix)}", paired = null, manual = m))
        }

        if (allEntries.isEmpty()) {
            // No devices — show guidance with "Add Manually" and "Cancel"
            MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(R.string.remote_transport_no_wifi_tvs_title)
                .setMessage(R.string.remote_transport_no_wifi_tvs_message)
                .setPositiveButton(R.string.remote_no_tvs_add_manual_button) { _, _ ->
                    showManualEntryDialog()
                }
                .setNegativeButton(R.string.bt_remote_cancel, null)
                .show()
            return
        }

        val deviceNames = allEntries.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.remote_transport_picker_title)
            .setItems(deviceNames) { _, which ->
                val entry = allEntries[which]
                when {
                    entry.paired != null -> doAdbConnect(entry.paired!!, transportPrefs)
                    entry.manual != null -> doAdbConnectManual(entry.manual!!, transportPrefs)
                }
            }
            .setNeutralButton(R.string.remote_select_tv_add_manual) { _, _ ->
                showManualEntryDialog()
            }
            .setNegativeButton(R.string.bt_remote_cancel, null)
            .show()
    }

    /** Connect via ADB to a manually-added device. */
    private fun doAdbConnectManual(device: ManualDevice, transportPrefs: RemoteTransportPrefs) {
        manualConnectJob?.cancel()
        showCardConnecting(device.name, R.string.remote_transport_wifi_connecting)

        // Show Cancel button while waiting for the TV to accept the RSA prompt.
        // No timeout — the ADB connect blocks until the user accepts/rejects
        // the SHA fingerprint on the TV (same pattern as Take Screenshot).
        btnStatusAction.text = getString(R.string.bt_remote_cancel)
        btnStatusAction.visibility = View.VISIBLE
        btnStatusAction.setOnClickListener {
            manualConnectJob?.cancel()
            AdbManager.getInstance().disconnectExplicit()
            refreshDisconnectedCard()
        }

        manualConnectJob = lifecycleScope.launch {
            val transport = AdbWifiTransport(ManualDevice.toPairedDevice(device))
            try {
                val success = transport.connect()
                if (success) {
                    currentTransport?.disconnect()
                    currentTransport = transport
                    transportPrefs.setLastTransport(device.deviceId, "adb_wifi")
                    transportPrefs.setLastConnectedRemoteDevice(device.deviceId, device.name, "adb_wifi")
                    startTransportObserver()
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        showCardConnected(device.name)
                    }
                } else {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        MaterialAlertDialogBuilder(this@TvRemoteActivity, R.style.UFM_Dialog)
                            .setTitle(R.string.use_remote_failed_title)
                            .setMessage(R.string.use_remote_failed_message)
                            .setPositiveButton(R.string.ok, null)
                            .show()
                        refreshDisconnectedCard()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User pressed Cancel — already handled above
            } finally {
                manualConnectJob = null
            }
        }
    }

    /** Shows a dialog telling the user the IP is already in the list. */
    private fun showAlreadyInListDialog() {
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.manual_entry_duplicate_title)
            .setMessage(R.string.manual_entry_duplicate_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * Shows a dialog for manually adding a TV by IP address and name.
     * On success, saves as a [ManualDevice] and connects via ADB.
     */
    private fun showManualEntryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_remote_entry, null)
        val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etManualName)
        val etIp = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etManualIp)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.manual_entry_title)
            .setView(dialogView)
            .setPositiveButton(R.string.manual_entry_add, null)
            .setNegativeButton(R.string.bt_remote_cancel, null)
            .create()

        dialog.setOnShowListener {
            val addButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            addButton.isEnabled = false

            val ipPattern = Regex(
                """^(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)\.""" +
                """(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)\.""" +
                """(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)\.""" +
                """(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)$"""
            )

            fun validate() {
                val name = etName.text?.toString()?.trim() ?: ""
                val ip = etIp.text?.toString()?.trim() ?: ""
                addButton.isEnabled = name.isNotEmpty() && ipPattern.matches(ip)
            }

            etName.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { validate() }
            })
            etIp.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { validate() }
            })

            addButton.setOnClickListener {
                val name = etName.text?.toString()?.trim() ?: ""
                val ip = etIp.text?.toString()?.trim() ?: ""

                val transportPrefs = RemoteTransportPrefs(this@TvRemoteActivity)
                // Check manual list
                if (transportPrefs.isIpInManualList(ip)) {
                    showAlreadyInListDialog()
                    return@setOnClickListener
                }
                // Check paired TVs with "Use Remote" enabled
                val pairingManager = PairingManager.getInstance(this@TvRemoteActivity)
                val pairedWithSameIp = transportPrefs.getRemoteEnabledDeviceIds()
                    .mapNotNull { pairingManager.getPairedDevice(it) }
                    .find { it.lastIp == ip }
                if (pairedWithSameIp != null) {
                    showAlreadyInListDialog()
                    return@setOnClickListener
                }

                dialog.dismiss()
                val device = ManualDevice(name = name, ip = ip)
                transportPrefs.addManualDevice(device)
                // Show guidance — user must accept the RSA prompt on the TV
                MaterialAlertDialogBuilder(this@TvRemoteActivity, R.style.UFM_Dialog)
                    .setTitle(R.string.use_remote_auth_title)
                    .setMessage(getString(R.string.use_remote_auth_message, name))
                    .setPositiveButton(R.string.ok) { _, _ ->
                        doAdbConnectManual(device, transportPrefs)
                    }
                    .setNegativeButton(R.string.bt_remote_cancel) { _, _ ->
                        transportPrefs.removeManualDevice(device.deviceId)
                        refreshDisconnectedCard()
                    }
                    .show()
            }
        }

        dialog.show()
    }

    private fun doAdbConnect(device: PairedDevice, transportPrefs: RemoteTransportPrefs) {
        showCardConnecting(device.name, R.string.remote_transport_wifi_connecting)
        lifecycleScope.launch {
            val transport = AdbWifiTransport(device)
            val success = transport.connect()
            if (success) {
                currentTransport?.disconnect()
                currentTransport = transport
                transportPrefs.setLastTransport(device.deviceId, "adb_wifi")
                transportPrefs.setLastConnectedRemoteDevice(device.deviceId, device.name, "adb_wifi")
                startTransportObserver()
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    showCardConnected(device.name)
                }
            } else {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    refreshDisconnectedCard()
                }
            }
        }
    }

    /** Update the card action button to show transport picker. */
    private fun setActionButtonToPicker() {
        btnStatusAction.text = getString(R.string.bt_remote_card_action_connect)
        btnStatusAction.visibility = View.VISIBLE
        btnStatusAction.setOnClickListener { showTransportPicker() }
    }

    // ── Permissions & BT check ────────────────────────────────────────────

    /**
     * Called when the user picks "Connect via Bluetooth" and permissions
     * are granted (or not needed). Always does something visible:
     * - BT off → show BT-off card with "Turn On" button
     * - BT on, no saved TVs → open the pairing flow
     * - BT on, saved TVs → start service and auto-connect
     */
    private fun startBluetoothFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            refreshDisconnectedCard()
            return
        }
        if (btManager?.isBluetoothEnabled() == false) {
            showCardBluetoothOff()
            return
        }
        // Always open the pairing flow — user wants to pair a TV.
        // Auto-connect to saved TV is handled by the disconnected card's Connect button.
        promptOpenBluetoothSettings()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            val missing = needed.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                requestPermissionLauncher.launch(missing.toTypedArray())
            } else {
                checkBluetoothEnabledAndStartService()
            }
        } else {
            checkBluetoothEnabledAndStartService()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun checkBluetoothEnabledAndStartService() {
        if (btManager?.isBluetoothEnabled() == true) {
            // Start the foreground service — safe to call even if already running.
            BluetoothHidService.start(this)

            // Wrap BT manager as the current transport
            currentTransport = BluetoothRemoteTransport(btManager!!)
            startTransportObserver()

            when (btManager?.connectionState?.value) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val name = btManager?.connectedDeviceName?.value ?: ""
                    showCardConnected(name)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    showCardConnecting(getString(R.string.tv_remote))
                }
                else -> {
                    if (btManager?.manualDisconnect == false && btManager?.hasAnySavedTvs() == true) {
                        showCardConnecting(getString(R.string.tv_remote))
                    } else {
                        refreshDisconnectedCard()
                    }
                }
            }
        } else {
            showCardBluetoothOff()
        }
    }

    // ── Connection state observation ──────────────────────────────────────

    private fun observeConnectionState() {
        // Initial observation is set up via startTransportObserver() when a
        // transport is activated. This method exists for symmetry; the actual
        // observation happens lazily on first transport activation.
    }

    /**
     * Start observing the current transport's connection state.
     * Cancels any previous observation job.
     */
    private fun startTransportObserver() {
        transportObserverJob?.cancel()
        val transport = currentTransport ?: return

        transportObserverJob = lifecycleScope.launch {
            transport.connectionState.collect { state ->
                val deviceName = transport.connectedDeviceName.value ?: ""
                when (state) {
                    BluetoothProfile.STATE_CONNECTED    -> showCardConnected(deviceName)
                    BluetoothProfile.STATE_CONNECTING   -> showCardConnecting(deviceName)
                    BluetoothProfile.STATE_DISCONNECTED -> refreshDisconnectedCard()
                }
            }
        }

        // Update title in card when connected device name resolves
        lifecycleScope.launch {
            transport.connectedDeviceName.collect { name ->
                if (!name.isNullOrEmpty() &&
                    transport.connectionState.value == BluetoothProfile.STATE_CONNECTED
                ) {
                    showCardConnected(name)
                }
            }
        }
    }

    // ── Smart Status Card ─────────────────────────────────────────────────

    private fun refreshDisconnectedCard() {
        updatePairMenuTitle()
        // Check the last connected remote device first (works for both BT and WiFi)
        val transportPrefs = RemoteTransportPrefs(this)
        val lastName = transportPrefs.getLastConnectedRemoteDeviceName()
        if (lastName != null) {
            showCardDisconnected(lastName)
            return
        }

        // Fall back to BT saved TVs
        if (btManager?.hasAnySavedTvs() == true) {
            val savedName = btManager?.getSavedTvDevices()
                ?.let { tvs -> btManager?.getDefaultTvAddress()?.let { addr -> tvs.find { it.address == addr } } ?: tvs.firstOrNull() }
                ?.name ?: getString(R.string.android_tv)
            showCardDisconnected(savedName)
            return
        }

        showCardNoPairedTvs()
    }

    private fun showCardNoPairedTvs() {
        txtStatusLabel.text    = getString(R.string.bt_remote_card_no_tvs_title)

        progressConnecting.visibility = View.GONE
        viewStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.vpn_warning_amber)
        btnStatusAction.text = getString(R.string.bt_remote_card_action_pair_tv)
        btnStatusAction.visibility = View.VISIBLE
        btnStatusAction.setOnClickListener {
            showTransportPicker()
        }
        toolbar.menu.findItem(R.id.action_pair)?.isVisible = false
        toolbar.menu.findItem(R.id.action_disconnect)?.isVisible = false
    }

    private fun showCardDisconnected(tvName: String) {
        txtStatusLabel.text    = getString(R.string.bt_remote_card_disconnected_title)

        progressConnecting.visibility = View.GONE
        viewStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.vpn_warning_amber)

        // Use the last connected transport — this is the single source of truth
        val transportPrefs = RemoteTransportPrefs(this)
        val lastTransport = transportPrefs.getLastConnectedRemoteTransport()
            ?: btManager?.getDefaultTvAddress()?.let { transportPrefs.getLastTransport(it) }

        when (lastTransport) {
            "bluetooth" -> {
                btnStatusAction.text = getString(R.string.remote_connect_via_bt, tvName)
                btnStatusAction.visibility = View.VISIBLE
                btnStatusAction.setOnClickListener {
                    connectViaBluetoothTransport(tvName)
                }
            }
            "adb_wifi" -> {
                btnStatusAction.text = getString(R.string.remote_connect_via_wifi, tvName)
                btnStatusAction.visibility = View.VISIBLE
                btnStatusAction.setOnClickListener {
                    connectViaAdbTransport(directConnect = true)
                }
            }
            else -> {
                btnStatusAction.text = getString(R.string.bt_remote_card_action_connect_to, tvName)
                btnStatusAction.visibility = View.VISIBLE
                btnStatusAction.setOnClickListener {
                    showTransportPicker()
                }
            }
        }
        toolbar.menu.findItem(R.id.action_pair)?.isVisible = true
        toolbar.menu.findItem(R.id.action_disconnect)?.isVisible = false
    }

    /** Connect via Bluetooth with the existing BT flow. */
    private fun connectViaBluetoothTransport(tvName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            btManager?.manualDisconnect = false
            btManager?.wasVirtualCableUnplugged = false
            val currentName = btManager?.getSavedTvDevices()
                ?.let { tvs -> btManager?.getDefaultTvAddress()
                    ?.let { addr -> tvs.find { it.address == addr } } ?: tvs.firstOrNull() }
                ?.name ?: tvName
            showCardConnecting(currentName, R.string.remote_transport_bt_connecting)
            // Save preference
            val addr = btManager?.getDefaultTvAddress() ?: ""
            if (addr.isNotEmpty()) {
                RemoteTransportPrefs(this).setLastTransport(addr, "bluetooth")
                RemoteTransportPrefs(this).setLastConnectedRemoteDevice(addr, currentName, "bluetooth")
            }
            // Wrap and observe the transport
            currentTransport = BluetoothRemoteTransport(btManager!!)
            startTransportObserver()
            BluetoothHidService.start(this)
            btManager?.autoConnectToSavedTv(true)
        }
    }

    private fun showCardConnecting(tvName: String, formatResId: Int = R.string.bt_remote_card_connecting_title) {
        val displayName = if (tvName.isNotEmpty() && tvName != getString(R.string.tv_remote)) tvName else "TV"
        txtStatusLabel.text = getString(formatResId, displayName)

        progressConnecting.visibility = View.VISIBLE
        viewStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.vpn_warning_amber)
        btnStatusAction.visibility = View.GONE
        toolbar.menu.findItem(R.id.action_pair)?.isVisible = false
        toolbar.menu.findItem(R.id.action_disconnect)?.isVisible = false
    }

    private fun showCardConnected(tvName: String) {
        // Show only the TV name — no "Connected to" prefix
        txtStatusLabel.text    = if (tvName.isNotEmpty()) tvName else getString(R.string.connected)

        progressConnecting.visibility = View.GONE
        viewStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.ufm_success)
        btnStatusAction.visibility = View.GONE
        toolbar.menu.findItem(R.id.action_pair)?.isVisible = false
        toolbar.menu.findItem(R.id.action_disconnect)?.isVisible = true
    }

    private fun showCardBluetoothOff() {
        txtStatusLabel.text    = getString(R.string.bt_remote_card_bt_off_title)

        progressConnecting.visibility = View.GONE
        viewStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.ufm_error)
        btnStatusAction.text = getString(R.string.bt_remote_card_action_turn_on)
        btnStatusAction.visibility = View.VISIBLE
        btnStatusAction.setOnClickListener {
            @Suppress("DEPRECATION")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivity(enableBtIntent)
        }
        toolbar.menu.findItem(R.id.action_pair)?.isVisible = false
        toolbar.menu.findItem(R.id.action_disconnect)?.isVisible = false
    }

    private fun showCardNoPermission() {
        txtStatusLabel.text    = getString(R.string.bt_remote_card_no_permission_title)

        progressConnecting.visibility = View.GONE
        viewStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.ufm_error)
        btnStatusAction.text = getString(R.string.bt_remote_card_action_grant_permission)
        btnStatusAction.visibility = View.VISIBLE
        btnStatusAction.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
        toolbar.menu.findItem(R.id.action_pair)?.isVisible = false
        toolbar.menu.findItem(R.id.action_disconnect)?.isVisible = false
    }

    // ── Toolbar & Device Selection ────────────────────────────────────────

    private fun setupToolbar() {
        toolbar.title = ""
        toolbar.setNavigationOnClickListener { onBackPressed() }
        toolbar.inflateMenu(R.menu.menu_tv_remote)
        updatePairMenuTitle()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_pair -> {
                    promptSelectTv()
                    true
                }
                R.id.action_disconnect -> {
                    currentTransport?.disconnect()
                    currentTransport = null
                    transportObserverJob?.cancel()
                    transportObserverJob = null
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Dynamically updates the toolbar menu item title based on how many UFM-saved
     * TVs exist. 0 or 1 saved → "Add Device"; 2+ saved → "Add / Select Device".
     */
    private fun updatePairMenuTitle() {
        val btCount = btManager?.getSavedTvDevices()?.size ?: 0
        val prefs = RemoteTransportPrefs(this)
        val adbCount = prefs.getRemoteEnabledDeviceIds().size
        val manualCount = prefs.getManualDevices().size
        val totalCount = btCount + adbCount + manualCount
        val menuItem = toolbar.menu.findItem(R.id.action_pair)
        menuItem?.title = if (totalCount <= 1) {
            getString(R.string.bt_remote_add_device)
        } else {
            getString(R.string.bt_remote_select_device)
        }
    }

    private fun toggleKeyboardPanel() {
        isKeyboardVisible = !isKeyboardVisible
        layoutKeyboardPanel.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
        if (isKeyboardVisible) {
            etKeyboard.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etKeyboard, InputMethodManager.SHOW_IMPLICIT)
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etKeyboard.windowToken, 0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun promptSelectTv() {
        val btDevices = btManager?.getSavedTvDevices() ?: emptyList()
        val transportPrefs = RemoteTransportPrefs(this)
        val defaultAddress = btManager?.getDefaultTvAddress()
        val lastConnectedId = transportPrefs.getLastConnectedRemoteDeviceId()

        // Collect ADB remote devices
        val pairingManager = PairingManager.getInstance(this)
        val adbIds = transportPrefs.getRemoteEnabledDeviceIds()
        val adbDevices = adbIds.mapNotNull { pairingManager.getPairedDevice(it) }
        val manualDevices = transportPrefs.getManualDevices()
        val manualIps = transportPrefs.getManualDeviceIps()

        // Build combined list: BT devices first, then paired ADB, then manual
        data class RemoteEntry(
            val name: String,
            val address: String,
            val transport: String,
            val isDefault: Boolean,
            val btDevice: BluetoothDevice? = null,
            val adbPairedDevice: PairedDevice? = null,
            val manualDevice: ManualDevice? = null
        )
        val allEntries = mutableListOf<RemoteEntry>()
        for (bt in btDevices) {
            val transport = transportPrefs.getLastTransport(bt.address) ?: "bluetooth"
            val isDefault = bt.address == lastConnectedId || (lastConnectedId == null && bt.address == defaultAddress)
            allEntries.add(RemoteEntry(bt.name ?: bt.address, bt.address, transport, isDefault,
                btDevice = bt))
        }
        for (adb in adbDevices) {
            // Dedup: skip if manual device has same IP
            if (adb.lastIp in manualIps) continue
            val transport = transportPrefs.getLastTransport(adb.deviceId) ?: "adb_wifi"
            allEntries.add(RemoteEntry(adb.name, adb.deviceId, transport,
                adb.deviceId == lastConnectedId, adbPairedDevice = adb))
        }
        for (manual in manualDevices) {
            allEntries.add(RemoteEntry(manual.name, manual.deviceId, "adb_wifi",
                manual.deviceId == lastConnectedId, manualDevice = manual))
        }

        if (allEntries.isEmpty()) {
            showTransportPicker()
            return
        }

        val deviceNames = allEntries.map { entry ->
            val suffix = when {
                entry.manualDevice != null -> getString(R.string.manual_device_suffix)
                entry.transport == "adb_wifi" -> getString(R.string.remote_device_wifi_suffix)
                entry.transport == "bluetooth" -> getString(R.string.remote_device_bt_suffix)
                else -> ""
            }
            buildString {
                append(entry.name)
                if (entry.isDefault) append(" ⭐")
                append(suffix)
            }
        }.toTypedArray()

        // Bottom sheet with custom adapter for per-row trash icons
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        currentBottomSheet = bottomSheet
        val sheetView = layoutInflater.inflate(R.layout.dialog_remote_device_sheet, null)
        val listView = sheetView.findViewById<android.widget.ListView>(R.id.listDevices)
        val btnAdd = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSheetAdd)
        val btnCancel = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSheetCancel)

        listView.adapter = object : android.widget.ArrayAdapter<String>(
            this, R.layout.dialog_remote_device_row, R.id.txtDeviceName, deviceNames
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val txtName = view.findViewById<TextView>(R.id.txtDeviceName)
                val btnTrash = view.findViewById<ImageView>(R.id.btnTrash)
                val entry = allEntries.getOrNull(position)
                txtName.text = getItem(position)
                if (entry?.manualDevice != null) {
                    btnTrash.visibility = android.view.View.VISIBLE
                    btnTrash.setOnClickListener {
                        showRemoveManualConfirm(entry.manualDevice!!, transportPrefs)
                    }
                } else {
                    btnTrash.visibility = android.view.View.GONE
                    btnTrash.setOnClickListener(null)
                }
                return view
            }
        }
        listView.setOnItemClickListener { _, _, which, _ ->
            bottomSheet.dismiss()
            val entry = allEntries.getOrNull(which) ?: return@setOnItemClickListener
            when {
                entry.adbPairedDevice != null -> {
                    val adb = entry.adbPairedDevice!!
                    showCardConnecting(adb.name, R.string.remote_transport_wifi_connecting)
                    connectViaAdbTransport(deviceId = adb.deviceId)
                }
                entry.manualDevice != null -> {
                    val m = entry.manualDevice!!
                    showCardConnecting(m.name, R.string.remote_transport_wifi_connecting)
                    doAdbConnectManual(m, transportPrefs)
                }
                else -> {
                    val bt = entry.btDevice ?: return@setOnItemClickListener
                    btManager?.manualDisconnect = false
                    btManager?.wasVirtualCableUnplugged = false
                    btManager?.setDefaultTv(bt.address)
                    showCardConnecting(bt.name ?: entry.name)
                    val name = bt.name ?: entry.name
                    transportPrefs.setLastTransport(bt.address, "bluetooth")
                    transportPrefs.setLastConnectedRemoteDevice(bt.address, name, "bluetooth")
                    currentTransport = BluetoothRemoteTransport(btManager!!)
                    startTransportObserver()
                    BluetoothHidService.start(this)
                    btManager?.connectToDevice(bt)
                }
            }
        }
        btnAdd.setOnClickListener {
            bottomSheet.dismiss()
            showAddTvOptions()
        }
        btnCancel.setOnClickListener { bottomSheet.dismiss() }

        bottomSheet.setOnDismissListener { currentBottomSheet = null }
        bottomSheet.setContentView(sheetView)
        bottomSheet.show()
    }

    /** Shows confirmation then removes a manual device. */
    private fun showRemoveManualConfirm(device: ManualDevice, transportPrefs: RemoteTransportPrefs) {
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.remote_remove_manual_confirm_title)
            .setMessage(getString(R.string.remote_remove_manual_confirm_message, device.name))
            .setPositiveButton(R.string.remote_remove_manual_proceed) { _, _ ->
                transportPrefs.removeManualDevice(device.deviceId)
                if (transportPrefs.getLastConnectedRemoteDeviceId() == device.deviceId) {
                    transportPrefs.clearLastConnectedRemoteDevice()
                }
                updatePairMenuTitle()
                refreshDisconnectedCard()
                // Dismiss old sheet and re-open with fresh data
                currentBottomSheet?.dismiss()
                promptSelectTv()
            }
            .setNegativeButton(R.string.bt_remote_cancel, null)
            .show()
    }

    /** Shows a sub-dialog to remove a manually-added device. */
    private fun showRemoveManualDeviceDialog(manualDevices: List<ManualDevice>, transportPrefs: RemoteTransportPrefs) {
        val names = manualDevices.map { "${it.name}  (${it.ip})" }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.remote_remove_manual_title)
            .setItems(names) { _, which ->
                val device = manualDevices[which]
                MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                    .setTitle(R.string.remote_remove_manual_confirm_title)
                    .setMessage(getString(R.string.remote_remove_manual_confirm_message, device.name))
                    .setPositiveButton(R.string.remote_remove_manual_proceed) { _, _ ->
                        transportPrefs.removeManualDevice(device.deviceId)
                        if (transportPrefs.getLastConnectedRemoteDeviceId() == device.deviceId) {
                            transportPrefs.clearLastConnectedRemoteDevice()
                        }
                        promptSelectTv() // refresh
                    }
                    .setNegativeButton(R.string.bt_remote_cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.bt_remote_cancel, null)
            .show()
    }

    /** Shows transport picker: Bluetooth or WiFi ADB. */
    private fun showAddTvOptions() {
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.remote_transport_picker_title)
            .setItems(arrayOf(
                getString(R.string.remote_transport_bt_option),
                getString(R.string.remote_transport_wifi_option)
            )) { _, which ->
                when (which) {
                    0 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                            val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                            if (missing.isNotEmpty()) {
                                requestPermissionLauncher.launch(missing.toTypedArray())
                            } else {
                                startBluetoothFlow()
                            }
                        } else {
                            startBluetoothFlow()
                        }
                    }
                    1 -> connectViaAdbTransport()
                }
            }
            .setNegativeButton(R.string.bt_remote_cancel, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceConnectOptions(device: BluetoothDevice) {
        val isDefault = device.address == btManager?.getDefaultTvAddress()
        val defaultLabel = if (isDefault) getString(R.string.bt_remote_remove_default)
                           else getString(R.string.bt_remote_connect_set_default)

        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(device.name ?: device.address)
            .setItems(arrayOf(
                getString(R.string.bt_remote_connect_now),
                defaultLabel
            )) { _, which ->
                when (which) {
                    0 -> {
                        // Connect — also save this device as known TV
                        btManager?.saveTvDevice(device)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            btManager?.connectToDevice(device)
                        }
                    }
                    1 -> {
                        if (isDefault) {
                            btManager?.clearDefaultTv()
                        } else {
                            // Also save as a known TV so refreshDisconnectedCard()
                            // can look it up by address and show the correct name.
                            btManager?.saveTvDevice(device)
                            btManager?.setDefaultTv(device.address)
                        }
                        // Refresh the card whenever not actively connected
                        if (btManager?.connectionState?.value != BluetoothProfile.STATE_CONNECTED) {
                            refreshDisconnectedCard()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.bt_remote_cancel, null)
            .show()
    }

    /**
     * Entry point for ALL "add a new TV" actions. Always shows the setup guide
     * first, which then calls [launchBluetoothSettings] via [setupGuideLauncher]
     * on RESULT_OK. This ensures a consistent experience regardless of how the
     * user reached this point (first-time card, toolbar button, or picker dialog).
     */
    private fun promptOpenBluetoothSettings() {
        val intent = Intent(this, TvSetupGuideActivity::class.java)
        setupGuideLauncher.launch(intent)
    }

    /**
     * Opens the system Bluetooth Settings with a UFM pairing session active.
     * Called exclusively from [setupGuideLauncher]'s RESULT_OK callback.
     *
     * Disconnects the currently connected TV first so the HID profile is
     * clean when the user pairs a new device — prevents connect/disconnect
     * loops on some TVs (e.g. TCL Google TV).
     */
    private fun launchBluetoothSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Disconnect current TV to give the new pairing a clean HID state
            btManager?.connectedDevice?.let { current ->
                Log.d("TvRemoteActivity", "Disconnecting ${current.name} before opening BT settings for new pairing")
                btManager?.manualDisconnect = true
                btManager?.disconnectCurrentDevice()
            }
            btManager?.startUfmPairingSession()
        }
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    // ── Button Setup ──────────────────────────────────────────────────────

    private fun bindViews() {
        coordinatorRoot    = findViewById(R.id.coordinatorRoot)
        toolbar            = findViewById(R.id.toolbar)
        viewStatusDot      = findViewById(R.id.viewStatusDot)
        txtStatusLabel     = findViewById(R.id.txtStatusLabel)

        progressConnecting = findViewById(R.id.progressConnecting)
        btnStatusAction    = findViewById(R.id.btnStatusAction)

        btnDpadUp          = findViewById(R.id.btnDpadUp)
        btnDpadDown        = findViewById(R.id.btnDpadDown)
        btnDpadLeft        = findViewById(R.id.btnDpadLeft)
        btnDpadRight       = findViewById(R.id.btnDpadRight)
        btnDpadOk          = findViewById(R.id.btnDpadOk)

        btnNavBack         = findViewById(R.id.btnNavBack)
        btnNavHome         = findViewById(R.id.btnNavHome)

        layoutVolumeRow      = findViewById(R.id.layoutVolumeRow)
        btnTogglePhoneVolume = findViewById(R.id.btnTogglePhoneVolume)
        txtToggleVolume      = findViewById(R.id.txtToggleVolume)
        imgToggleVolume      = findViewById(R.id.imgToggleVolume)

        // Volume + media are now direct children of the main layout
        btnVolDown         = findViewById(R.id.btnVolDown)
        btnMute            = findViewById(R.id.btnMute)
        btnVolUp           = findViewById(R.id.btnVolUp)

        btnRewind          = findViewById(R.id.btnRewind)
        btnPlayPause       = findViewById(R.id.btnPlayPause)
        btnFastForward     = findViewById(R.id.btnFastForward)

        // Keyboard FAB
        fabKeyboard         = findViewById(R.id.fabKeyboard)

        // Keyboard panel is embedded in the main layout, hidden by default
        layoutKeyboardPanel = findViewById(R.id.layoutKeyboardPanel)
        layoutKeyboardPanel.visibility = View.GONE

        etKeyboard     = findViewById(R.id.etKeyboard)
        btnKbBackspace = findViewById(R.id.btnKbBackspace)
        btnKbEnter     = findViewById(R.id.btnKbEnter)
        btnKbClear     = findViewById(R.id.btnKbClear)
    }

    private fun setupButtons() {
        // D-Pad (hold-to-repeat)
        setupDpadButton(btnDpadUp,    HID_DPAD_UP)
        setupDpadButton(btnDpadDown,  HID_DPAD_DOWN)
        setupDpadButton(btnDpadLeft,  HID_DPAD_LEFT)
        setupDpadButton(btnDpadRight, HID_DPAD_RIGHT)

        // OK — support long press (no repeat, just hold DOWN state)
        btnDpadOk.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    vibrate()
                    currentTransport?.sendKeyboardKeyDown(HID_DPAD_CENTER)
                    repeatJob?.cancel()
                    repeatJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(REPEAT_INITIAL_DELAY_MS)
                        if (isActive) vibrate()
                    }
                    btnDpadOk.isPressed = true
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    repeatJob?.cancel()
                    repeatJob = null
                    currentTransport?.sendKeyboardKeyUp()
                    btnDpadOk.isPressed = false
                    true
                }
                else -> false
            }
        }

        setupKeyboardPanel()

        // FAB toggles the keyboard panel
        fabKeyboard.setOnClickListener { toggleKeyboardPanel() }

        // Navigation
        btnNavBack.setOnClickListener { vibrate(); currentTransport?.sendConsumerKey(HID_BACK) }
        btnNavHome.setOnClickListener { vibrate(); currentTransport?.sendConsumerKey(HID_HOME) }

        // Phone Volume Toggle Preference
        val prefs = getSharedPreferences("TvRemotePrefs", Context.MODE_PRIVATE)
        var usePhoneVolume = prefs.getBoolean("usePhoneVolume", false)
        
        fun updateVolumeUI(usePhone: Boolean) {
            btnTogglePhoneVolume.isActivated = usePhone
            imgToggleVolume.isActivated = usePhone
            txtToggleVolume.isActivated = usePhone
            
            if (usePhone) {
                btnVolDown.visibility = View.GONE
                btnVolUp.visibility = View.GONE
                txtToggleVolume.text = getString(R.string.bt_remote_volume_on)
            } else {
                btnVolDown.visibility = View.VISIBLE
                btnVolUp.visibility = View.VISIBLE
                txtToggleVolume.text = getString(R.string.bt_remote_volume_off)
            }
        }
        updateVolumeUI(usePhoneVolume)

        btnTogglePhoneVolume.setOnClickListener {
            vibrate()
            usePhoneVolume = !usePhoneVolume
            prefs.edit().putBoolean("usePhoneVolume", usePhoneVolume).apply()
            updateVolumeUI(usePhoneVolume)

            if (usePhoneVolume) {
                val showGuidance = prefs.getBoolean("show_volume_guidance", true)
                if (showGuidance) {
                    val dialogView = layoutInflater.inflate(R.layout.dialog_volume_guidance, null)
                    val cbDontShowAgain = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cbDontShowAgain)
                    
                    MaterialAlertDialogBuilder(this@TvRemoteActivity, R.style.UFM_Dialog)
                        .setTitle(R.string.bt_remote_volume_guidance_title)
                        .setView(dialogView)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            if (cbDontShowAgain.isChecked) {
                                prefs.edit().putBoolean("show_volume_guidance", false).apply()
                            }
                        }
                        .show()
                }
            }
        }

        // Volume
        setupRepeatAction(btnVolDown) { vibrate(); currentTransport?.sendConsumerKey(HID_VOLUME_DOWN) }
        btnMute.setOnClickListener    {
            vibrate()
            isMuted = !isMuted
            btnMute.isActivated = isMuted
            currentTransport?.sendConsumerKey(HID_VOLUME_MUTE)
        }
        setupRepeatAction(btnVolUp)   { vibrate(); currentTransport?.sendConsumerKey(HID_VOLUME_UP) }

        // Media — using keyboard keycodes that all Android TV apps respond to:
        // Rewind/FF → Left/Right arrow (same as what stock remotes send for seek)
        // Play/Pause → Enter (universal across Netflix, Prime, Debrid Stream etc.)
        btnRewind.setOnClickListener      { vibrate(); currentTransport?.sendKeyboardKey(HID_DPAD_LEFT) }
        btnPlayPause.setOnClickListener   { vibrate(); currentTransport?.sendKeyboardKey(HID_DPAD_CENTER) }
        btnFastForward.setOnClickListener { vibrate(); currentTransport?.sendKeyboardKey(HID_DPAD_RIGHT) }
    }

    // ── Keyboard Mode ─────────────────────────────────────────────────────

    private fun setupKeyboardPanel() {

        setupRepeatAction(btnKbBackspace) {
            vibrate()
            currentTransport?.sendHidBackspace()
            val cur = etKeyboard.text?.toString() ?: ""
            if (cur.isNotEmpty()) {
                previousKeyboardText = cur.dropLast(1)
                etKeyboard.setText(previousKeyboardText)
                etKeyboard.setSelection(previousKeyboardText.length)
            }
        }
        btnKbEnter.setOnClickListener {
            vibrate()
            currentTransport?.sendHidEnter()
            previousKeyboardText = ""
            etKeyboard.text?.clear()
        }
        btnKbClear.setOnClickListener {
            vibrate()
            if (!etKeyboard.text.isNullOrEmpty()) {
                currentTransport?.sendSelectAll()
                currentTransport?.sendHidBackspace()
            }
            previousKeyboardText = ""
            etKeyboard.text?.clear()
        }

        // Handle enter on the soft keyboard
        etKeyboard.setOnEditorActionListener { _, _, _ ->
            btnKbEnter.performClick()
            true
        }

        // TextWatcher: send only the delta between previous and new text
        etKeyboard.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                val newText = editable?.toString() ?: ""
                val old = previousKeyboardText

                if (newText == old) return

                previousKeyboardText = newText

                // Compute common prefix length
                val commonLen = old.zip(newText).takeWhile { (a, b) -> a == b }.count()

                // Deletions: send backspace for each char removed after common prefix
                val deleted = old.length - commonLen
                repeat(deleted) { currentTransport?.sendHidBackspace() }

                // Additions: send each new character after the common prefix
                val added = newText.substring(commonLen)
                currentTransport?.sendText(added)
            }
        })
    }



    @Suppress("ClickableViewAccessibility")
    private fun setupRepeatAction(button: View, action: () -> Unit) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    action()
                    repeatJob?.cancel()
                    repeatJob = lifecycleScope.launch {
                        delay(REPEAT_INITIAL_DELAY_MS)
                        while (isActive) {
                            action()
                            delay(REPEAT_DELAY_MS)
                        }
                    }
                    button.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatJob?.cancel()
                    repeatJob = null
                    button.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupDpadButton(button: ImageButton, keycode: Byte) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    vibrate()
                    currentTransport?.sendKeyboardKey(keycode)
                    repeatJob?.cancel()
                    repeatJob = lifecycleScope.launch {
                        delay(REPEAT_INITIAL_DELAY_MS)
                        while (isActive) {
                            vibrate()
                            currentTransport?.sendKeyboardKey(keycode)
                            delay(REPEAT_DELAY_MS)
                        }
                    }
                    button.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatJob?.cancel()
                    repeatJob = null
                    button.isPressed = false
                    true
                }
                else -> false
            }
        }
    }


    // ── Haptics ───────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                v.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }
}
