package za.kilowatch.ultimatefilemanager.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import za.kilowatch.ultimatefilemanager.R

@RequiresApi(Build.VERSION_CODES.P)
@SuppressLint("MissingPermission")
class BluetoothRemoteManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothRemoteManager"
        private const val REPORT_ID_KEYBOARD: Byte = 0x01
        private const val REPORT_ID_CONSUMER: Byte = 0x02
        private const val PREF_NAME = "bluetooth_remote_prefs"
        private const val PREF_DEFAULT_TV = "default_tv_address"
        private const val PREF_SAVED_TVS = "saved_tv_addresses"
        private const val PREF_MANUAL_DISCONNECT = "manual_disconnect"
        private const val PREF_FORCED_SUBCLASS = "bt_hid_forced_subclass"
        private const val PREF_LAST_SUBCLASS = "bt_hid_last_subclass"
        private const val PREF_DEVICE_SUBCLASS_PREFIX = "device_subclass_"

        /**
         * How long (ms) to hold the key DOWN before sending the UP report.
         * Kodi's input poller checks state on a ~16 ms vsync; 20 ms gives it
         * at least one full poll cycle to register the press before the release.
         */
        private const val KEY_DOWN_HOLD_MS = 20L

        /**
         * Minimum gap (ms) between the end of one keystroke and the start of the
         * next.  Gives the receiving app a breath between characters so rapid-fire
         * paste events are spaced out and never collide on the HID bus.
         */
        private const val KEY_INTER_MS = 15L

        private val SDP_SUBCLASS_FALLBACK = byteArrayOf(
            BluetoothHidDevice.SUBCLASS1_NONE,
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            BluetoothHidDevice.SUBCLASS1_COMBO
        )
        // If a device connects then disconnects within this window we'll treat it
        // as a "rapid disconnect" and attempt device-specific workarounds.
        private const val RAPID_DISCONNECT_THRESHOLD_MS = 2_000L
        private const val STABLE_CONNECTION_RESET_MS = 3_000L
        private const val MAX_RAPID_DISCONNECT_ATTEMPTS = 3
        // Enhanced HID profile polling for state management
        private const val HID_PROFILE_POLL_INTERVAL_MS = 100L
        private const val HID_PROFILE_MAX_WAIT_MS = 5_000L

        @Volatile
        private var INSTANCE: BluetoothRemoteManager? = null

        fun getInstance(context: Context): BluetoothRemoteManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BluetoothRemoteManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Full printable ASCII -> (HID keycode, needsShift).
         * Returns null for characters that cannot be typed (e.g. non-ASCII).
         */
        fun charToHidKeycode(char: Char): Pair<Byte, Boolean>? = when (char) {
            'a' -> Pair(0x04, false); 'A' -> Pair(0x04, true)
            'b' -> Pair(0x05, false); 'B' -> Pair(0x05, true)
            'c' -> Pair(0x06, false); 'C' -> Pair(0x06, true)
            'd' -> Pair(0x07, false); 'D' -> Pair(0x07, true)
            'e' -> Pair(0x08, false); 'E' -> Pair(0x08, true)
            'f' -> Pair(0x09, false); 'F' -> Pair(0x09, true)
            'g' -> Pair(0x0A, false); 'G' -> Pair(0x0A, true)
            'h' -> Pair(0x0B, false); 'H' -> Pair(0x0B, true)
            'i' -> Pair(0x0C, false); 'I' -> Pair(0x0C, true)
            'j' -> Pair(0x0D, false); 'J' -> Pair(0x0D, true)
            'k' -> Pair(0x0E, false); 'K' -> Pair(0x0E, true)
            'l' -> Pair(0x0F, false); 'L' -> Pair(0x0F, true)
            'm' -> Pair(0x10, false); 'M' -> Pair(0x10, true)
            'n' -> Pair(0x11, false); 'N' -> Pair(0x11, true)
            'o' -> Pair(0x12, false); 'O' -> Pair(0x12, true)
            'p' -> Pair(0x13, false); 'P' -> Pair(0x13, true)
            'q' -> Pair(0x14, false); 'Q' -> Pair(0x14, true)
            'r' -> Pair(0x15, false); 'R' -> Pair(0x15, true)
            's' -> Pair(0x16, false); 'S' -> Pair(0x16, true)
            't' -> Pair(0x17, false); 'T' -> Pair(0x17, true)
            'u' -> Pair(0x18, false); 'U' -> Pair(0x18, true)
            'v' -> Pair(0x19, false); 'V' -> Pair(0x19, true)
            'w' -> Pair(0x1A, false); 'W' -> Pair(0x1A, true)
            'x' -> Pair(0x1B, false); 'X' -> Pair(0x1B, true)
            'y' -> Pair(0x1C, false); 'Y' -> Pair(0x1C, true)
            'z' -> Pair(0x1D, false); 'Z' -> Pair(0x1D, true)
            '1' -> Pair(0x1E, false); '!' -> Pair(0x1E, true)
            '2' -> Pair(0x1F, false); '@' -> Pair(0x1F, true)
            '3' -> Pair(0x20, false); '#' -> Pair(0x20, true)
            '4' -> Pair(0x21, false); '$' -> Pair(0x21, true)
            '5' -> Pair(0x22, false); '%' -> Pair(0x22, true)
            '6' -> Pair(0x23, false); '^' -> Pair(0x23, true)
            '7' -> Pair(0x24, false); '&' -> Pair(0x24, true)
            '8' -> Pair(0x25, false); '*' -> Pair(0x25, true)
            '9' -> Pair(0x26, false); '(' -> Pair(0x26, true)
            '0' -> Pair(0x27, false); ')' -> Pair(0x27, true)
            ' '  -> Pair(0x2C, false)
            '\n' -> Pair(0x28, false)
            '\t' -> Pair(0x2B, false)
            '-'  -> Pair(0x2D, false); '_' -> Pair(0x2D, true)
            '='  -> Pair(0x2E, false); '+' -> Pair(0x2E, true)
            '['  -> Pair(0x2F, false); '{' -> Pair(0x2F, true)
            ']'  -> Pair(0x30, false); '}' -> Pair(0x30, true)
            '\\' -> Pair(0x31, false); '|' -> Pair(0x31, true)
            ';'  -> Pair(0x33, false); ':' -> Pair(0x33, true)
            '\'' -> Pair(0x34, false); '"' -> Pair(0x34, true)
            '`'  -> Pair(0x35, false); '~' -> Pair(0x35, true)
            ','  -> Pair(0x36, false); '<' -> Pair(0x36, true)
            '.'  -> Pair(0x37, false); '>' -> Pair(0x37, true)
            '/'  -> Pair(0x38, false); '?' -> Pair(0x38, true)
            else -> null
        }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private var hidDevice: BluetoothHidDevice? = null
    internal var connectedDevice: BluetoothDevice? = null
        private set
    private var isAppRegistered = false

    /**
     * Set to true when the HID host sends a VIRTUAL_CABLE_UNPLUG command (i.e. the TV
     * intentionally dropped the peripheral). In this state we must NOT auto-reconnect —
     * only a deliberate user action may initiate a new connection attempt.
     * Persisted across service restarts via SharedPreferences.
     */
    var wasVirtualCableUnplugged: Boolean
        get() = prefs.getBoolean("virtual_cable_unplugged", false)
        set(value) = prefs.edit().putBoolean("virtual_cable_unplugged", value).apply()

    var manualDisconnect: Boolean
        get() = prefs.getBoolean(PREF_MANUAL_DISCONNECT, false)
        set(value) = prefs.edit().putBoolean(PREF_MANUAL_DISCONNECT, value).apply()

    /**
     * True only while UFM has explicitly opened the system Bluetooth Settings via
     * [startUfmPairingSession]. This flag lives purely in memory — it resets to false
     * on every app start/kill, so a crashed or killed session can never leave stale state.
     */
    @Volatile
    var isUfmPairingSessionActive: Boolean = false

    /**
     * Point-in-time snapshot of bonded device addresses taken when [startUfmPairingSession]
     * is called. Stored in memory only — never persisted. Resets to null on app kill.
     *
     * Used by [getCandidateTvsForUfm] to ensure only devices bonded DURING the UFM
     * pairing session are presented in the picker. Any device that was already bonded
     * before the session started (i.e. paired outside UFM) will be in this snapshot
     * and therefore excluded from the results.
     */
    private var pairingSessionSnapshot: Set<String>? = null

    /**
     * Serializes ALL HID key events so that a rapid sequence of characters
     * (e.g. paste) never sends the next DOWN before the previous UP has been
     * processed by the remote end.  This is the primary fix for Kodi double-
     * typing: Kodi's input poller missed the near-instant UP event and
     * treated each character as a held key, triggering its own auto-repeat.
     */
    private val keystrokeMutex = Mutex()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var connectionTimeoutJob: Job? = null
    // Per-device tracking for rapid disconnect heuristics and retry gating
    private val rapidDisconnectCounts: MutableMap<String, Int> = mutableMapOf()
    private val lastConnectedAt: MutableMap<String, Long> = mutableMapOf()
    private val reRegisteringDevices: MutableSet<String> = mutableSetOf()
    
    /**
     * Tracks if we're in the middle of a device-isolated re-registration.
     * During this time, we suppress re-registration attempts for OTHER devices
     * to avoid cascading re-registers that disrupt already-connected TVs.
     */
    @Volatile
    private var isInDeviceIsolatedReregister: Boolean = false

    /**
     * Save the successful SDP subclass for a specific device address.
     * This enables faster re-connection with the same subclass on subsequent attempts.
     */
    private fun saveDeviceSubclass(deviceAddress: String, subclass: Int) {
        try {
            prefs.edit()
                .putInt("${Companion.PREF_DEVICE_SUBCLASS_PREFIX}$deviceAddress", subclass)
                .apply()
            Log.d(TAG, "Saved subclass 0x${Integer.toHexString(subclass)} for device $deviceAddress")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving device subclass", e)
        }
    }

    /**
     * Get the preferred SDP subclass for a device, or null if not yet cached.
     * Returns the last successful subclass used with this device.
     */
    private fun getDeviceSubclass(deviceAddress: String): Byte? {
        return try {
            val stored = prefs.getInt("${Companion.PREF_DEVICE_SUBCLASS_PREFIX}$deviceAddress", -1)
            if (stored >= 0) stored.toByte() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the preferred subclass order for a device.
     * Tries cached subclass first, then falls back to standard order.
     */
    private fun getSubclassOrderForDevice(deviceAddress: String): ByteArray {
        val cached = getDeviceSubclass(deviceAddress)
        return if (cached != null) {
            // Put cached subclass first, then other options
            byteArrayOf(
                cached,
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                BluetoothHidDevice.SUBCLASS1_COMBO,
                BluetoothHidDevice.SUBCLASS1_NONE
            ).distinct().toByteArray()
        } else {
            Companion.SDP_SUBCLASS_FALLBACK
        }
    }

    private val bondStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)

            if (state == BluetoothDevice.BOND_NONE && device != null) {
                Log.d(TAG, "Device unpaired: ${device.name} (${device.address})")

                val savedTVs = getSavedTvAddresses().toMutableSet()
                if (savedTVs.remove(device.address)) {
                    prefs.edit().putStringSet(Companion.PREF_SAVED_TVS, savedTVs).apply()
                }
                if (getDefaultTvAddress() == device.address) clearDefaultTv()

                if (connectedDevice?.address == device.address ||
                    _connectionState.value == BluetoothProfile.STATE_CONNECTING
                ) {
                    Log.d(TAG, "Unpaired active device, forcing disconnect state")
                    connectionTimeoutJob?.cancel()
                    connectedDevice = null
                    _connectedDeviceName.value = null
                    _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
                    try { hidDevice?.disconnect(device) } catch (_: Exception) {}
                }
            }
        }
    }

    init {
        context.registerReceiver(bondStateReceiver, android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    // Track state for the UI
    private val _connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectionState: StateFlow<Int> = _connectionState
    
    // Track if HID app registration succeeded
    private val _appRegistrationState = MutableStateFlow(false)
    val appRegistrationState: StateFlow<Boolean> = _appRegistrationState
    
    // Track connected device info
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    // Standard Keyboard & Consumer Control Descriptor
    private val HID_REPORT_DESC = byteArrayOf(
        // Keyboard (Report ID 1)
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x06,       // Usage (Keyboard)
        0xA1.toByte(), 0x01,       // Collection (Application)
        0x85.toByte(), 0x01,       //   Report ID (1)
        0x05, 0x07,       //   Usage Page (Key Codes)
        0x19, 0xE0.toByte(), //   Usage Minimum (224)
        0x29, 0xE7.toByte(), //   Usage Maximum (231)
        0x15, 0x00,       //   Logical Minimum (0)
        0x25, 0x01,       //   Logical Maximum (1)
        0x75, 0x01,       //   Report Size (1)
        0x95.toByte(), 0x08,       //   Report Count (8)
        0x81.toByte(), 0x02.toByte(),       //   Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01,       //   Report Count (1)
        0x75, 0x08,       //   Report Size (8)
        0x81.toByte(), 0x01.toByte(),       //   Input (Constant)
        0x95.toByte(), 0x06,       //   Report Count (6)
        0x75, 0x08,       //   Report Size (8)
        0x15, 0x00,       //   Logical Minimum (0)
        0x25, 0x65,       //   Logical Maximum (101)
        0x05, 0x07,       //   Usage Page (Key codes)
        0x19, 0x00,       //   Usage Minimum (0)
        0x29, 0x65,       //   Usage Maximum (101)
        0x81.toByte(), 0x00.toByte(),       //   Input (Data, Array)
        0xC0.toByte(),    // End Collection

        // Consumer Control (Media/Volume) (Report ID 2)
        0x05, 0x0C,       // Usage Page (Consumer)
        0x09, 0x01,       // Usage (Consumer Control)
        0xA1.toByte(), 0x01,       // Collection (Application)
        0x85.toByte(), 0x02,       //   Report ID (2)
        0x15, 0x00,       //   Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x03, //   Logical Maximum (1023)
        0x19, 0x00,       //   Usage Minimum (0)
        0x2A, 0xFF.toByte(), 0x03, //   Usage Maximum (1023)
        0x75, 0x10,       //   Report Size (16)
        0x95.toByte(), 0x01,       //   Report Count (1)
        0x81.toByte(), 0x00.toByte(),       //   Input (Data, Array, Absolute)
        0xC0.toByte()     // End Collection
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            super.onConnectionStateChanged(device, state)
            val stateName = when (state) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN($state)"
            }
            Log.d(TAG, "┌── onConnectionStateChanged ──────────────────")
            Log.d(TAG, "│ device       = ${device.name} (${device.address})")
            Log.d(TAG, "│ newState     = $stateName")
            Log.d(TAG, "│ connectedDev = ${connectedDevice?.name} (${connectedDevice?.address})")
            Log.d(TAG, "│ manualDisc   = $manualDisconnect")
            Log.d(TAG, "│ vCableUnplug = $wasVirtualCableUnplugged")
            Log.d(TAG, "│ pairingActive= $isUfmPairingSessionActive")
            Log.d(TAG, "└──────────────────────────────────────────────")

            connectionTimeoutJob?.cancel()

            if (state == BluetoothProfile.STATE_CONNECTED) {
                wasVirtualCableUnplugged = false
                manualDisconnect = false
                connectedDevice = device
                updateConnectedDeviceName()
                // Track when this device became connected so we can detect rapid
                // disconnects (host accepting then immediately dropping peripheral).
                try {
                    lastConnectedAt[device.address] = System.currentTimeMillis()
                } catch (_: Exception) {}
                
                // Reset rapid-disconnect counter and clear re-register flag if the connection
                // remains stable for the threshold period. This confirms the device is truly connected.
                scope.launch {
                    delay(STABLE_CONNECTION_RESET_MS)
                    try {
                        if (_connectionState.value == BluetoothProfile.STATE_CONNECTED &&
                            connectedDevice?.address == device.address
                        ) {
                            val addr = device.address
                            rapidDisconnectCounts.remove(addr)
                            Log.d(TAG, "Connection stabilized for ${device.name} ($addr) — rapid-disconnect counter reset")
                        }
                    } catch (_: Exception) {}
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device.address) {
                    connectedDevice = null
                    _connectedDeviceName.value = null
                }

                // Detect rapid connect->disconnect behaviour and attempt a device-specific
                // workaround if seen repeatedly. ONLY trigger if:
                // - Not a manual/intentional disconnect
                // - Not a virtual cable unplug from TV
                // - Happened within rapid threshold
                // - Not already attempting re-register for this device
                try {
                    val last = lastConnectedAt[device.address] ?: 0L
                    val delta = if (last > 0) System.currentTimeMillis() - last else Long.MAX_VALUE
                    val addr = device.address
                    val count = (rapidDisconnectCounts[addr] ?: 0) + 1
                    
                    if (last > 0 && delta in 1 until RAPID_DISCONNECT_THRESHOLD_MS && 
                        !manualDisconnect && !wasVirtualCableUnplugged) {
                        
                        Log.w(TAG, "Rapid disconnect detected for ${device.name} ($addr) — attempt $count/${MAX_RAPID_DISCONNECT_ATTEMPTS} (delta=${delta}ms)")
                        rapidDisconnectCounts[addr] = count
                        
                        if (count <= MAX_RAPID_DISCONNECT_ATTEMPTS && !reRegisteringDevices.contains(addr)) {
                            Log.d(TAG, "Triggering device-isolated re-register for $addr (attempt $count)")
                            attemptAlternateSubclassAndReconnect(device)
                        } else if (count > MAX_RAPID_DISCONNECT_ATTEMPTS) {
                            Log.w(TAG, "Max re-register attempts reached for $addr — giving up")
                        }
                    }
                } catch (_: Exception) {}
            }
            _connectionState.value = state
        }

        /**
         * Called when the HID host (TV) sends a VIRTUAL_CABLE_UNPLUG command.
         * This means the TV has intentionally removed us as a paired peripheral.
         * We must NOT auto-reconnect — doing so causes the connect/disconnect loop
         * seen on onn streaming sticks and Mecool boxes.
         * The connection will transition to STATE_DISCONNECTED after this callback.
         */
        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            super.onVirtualCableUnplug(device)
            Log.w(TAG, "Virtual cable unplugged by host: ${device.name} (${device.address}) — suppressing auto-reconnect")
            wasVirtualCableUnplugged = true
            manualDisconnect = true  // Also set this so scheduleReconnect() bails out
            connectionTimeoutJob?.cancel()
            connectedDevice = null
            _connectedDeviceName.value = null
            // State will become STATE_DISCONNECTED automatically; we do not drive reconnect
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            Log.d(TAG, "onGetReport: type=$type, id=$id")
            val report = if (id == REPORT_ID_KEYBOARD.toByte()) {
                byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
            } else if (id == REPORT_ID_CONSUMER.toByte()) {
                byteArrayOf(0, 0)
            } else {
                ByteArray(bufferSize)
            }
            hidDevice?.replyReport(device, type, id, report)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            Log.d(TAG, "onSetReport: type=$type, id=$id")
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as? BluetoothHidDevice
                registerApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
            }
        }
    }

    fun initialize() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        bluetoothAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    fun cleanup() {
        unregisterApp()
        hidDevice?.let {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
        }
        hidDevice = null
        connectedDevice = null
        isAppRegistered = false
        _appRegistrationState.value = false
    }

    fun isBluetoothEnabled() = bluetoothAdapter?.isEnabled == true

    /**
     * Enable Bluetooth discoverable mode for 300 seconds (maximum).
     * This allows the TV to find this device when scanning for Bluetooth peripherals.
     */
    fun enableDiscoverability() {
        Log.d(TAG, "Enabling Bluetooth discoverable mode...")
        try {
            val discoverableIntent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300) // 5 minutes
            }
            discoverableIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(discoverableIntent)
            Log.d(TAG, "Discoverability request sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable discoverable mode", e)
        }
    }



    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * Disconnects ALL active HID connections — both our tracked [connectedDevice]
     * and any stale connections the BT stack may still have.
     */
    fun disconnectCurrentDevice() {
        Log.d(TAG, "disconnectCurrentDevice: tracked=${connectedDevice?.name} (${connectedDevice?.address})")
        manualDisconnect = true
        wasVirtualCableUnplugged = false // Manual disconnect is intentional; clear unplug flag
        
        // Disconnect tracked device
        connectedDevice?.let { device ->
            try { hidDevice?.disconnect(device) } catch (_: Exception) {}
        }
        // Also query the HID profile for any stale connections
        try {
            hidDevice?.getConnectedDevices()?.forEach { device ->
                Log.d(TAG, "disconnectCurrentDevice: stale HID ${device.name} (${device.address})")
                try { hidDevice?.disconnect(device) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        connectedDevice = null
        _connectedDeviceName.value = null
        _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
    }

    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "┌── connectToDevice ───────────────────────────")
        Log.d(TAG, "│ target       = ${device.name} (${device.address})")
        Log.d(TAG, "│ connectedDev = ${connectedDevice?.name} (${connectedDevice?.address})")
        Log.d(TAG, "│ manualDisc   = $manualDisconnect")
        Log.d(TAG, "│ currentState = ${_connectionState.value}")

        // Query the HID profile for ALL currently connected devices — our tracked
        // connectedDevice may be null even if the BT stack still has a live link.
        var needsDelay = false
        var staleDevices = 0
        try {
            hidDevice?.getConnectedDevices()?.forEach { staleDevice ->
                if (staleDevice.address != device.address) {
                    Log.d(TAG, "│ staleHID     = ${staleDevice.name} (${staleDevice.address}) — disconnecting")
                    manualDisconnect = true
                    hidDevice?.disconnect(staleDevice)
                    staleDevices++
                    needsDelay = true
                }
            }
        } catch (_: Exception) {}

        // Also disconnect our tracked device if it differs
        connectedDevice?.let { current ->
            if (current.address != device.address) {
                Log.d(TAG, "│ trackedDev   = ${current.name} (${current.address}) — disconnecting")
                manualDisconnect = true
                try { hidDevice?.disconnect(current) } catch (_: Exception) {}
                connectedDevice = null
                needsDelay = true
            }
        }
        Log.d(TAG, "└──────────────────────────────────────────────")

        // Set state to CONNECTING immediately for UI feedback
        _connectedDeviceName.value = device.name ?: device.address
        _connectionState.value = BluetoothProfile.STATE_CONNECTING

        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            // If we disconnected other HID devices above, wait for the BT stack
            // to settle by actively polling for stale connections to clear
            if (needsDelay) {
                Log.d(TAG, ">>> Waiting for stale HID disconnections (found $staleDevices stale device(s))")
                var waited = 0L
                while (waited < HID_PROFILE_MAX_WAIT_MS) {
                    try {
                        val connectedAddrs = hidDevice?.getConnectedDevices()?.map { it.address } ?: emptyList()
                        val staleCount = connectedAddrs.count { it != device.address }
                        if (staleCount == 0) {
                            Log.d(TAG, ">>> HID profile cleared after ${waited}ms")
                            break
                        }
                        if (waited > 0 && waited % 500 == 0L) {
                            Log.d(TAG, ">>> Still waiting for HID profile to clear ($staleCount device(s) connected, ${waited}ms elapsed)")
                        }
                    } catch (_: Exception) {}
                    delay(HID_PROFILE_POLL_INTERVAL_MS)
                    waited += HID_PROFILE_POLL_INTERVAL_MS
                }
                if (waited >= HID_PROFILE_MAX_WAIT_MS) {
                    Log.w(TAG, ">>> HID profile still has stale connections after ${HID_PROFILE_MAX_WAIT_MS}ms, proceeding anyway")
                }
            }

            // Reset manualDisconnect right before connecting the new device
            manualDisconnect = false
            Log.d(TAG, ">>> manualDisconnect reset to false, calling hidDevice.connect(${device.name})")
            hidDevice?.connect(device)

            // Timeout: if still CONNECTING after 4s, give up
            delay(4000)
            if (_connectionState.value == BluetoothProfile.STATE_CONNECTING) {
                Log.w(TAG, "Connection attempt timed out after 4s for ${device.name}")
                _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
                try { hidDevice?.disconnect(device) } catch (_: Exception) {}
            }
        }
    }


    private fun registerApp(attemptsOverride: ByteArray? = null, deviceAddress: String? = null) {
        Log.d(TAG, "Attempting to register HID app with subclass fallback...")

        // Use proper Handler executor for the main thread (returns Unit)
        val executor: (Runnable) -> Unit = { command -> Handler(Looper.getMainLooper()).post(command) }

        // Read a debug-forced subclass (-1 means none)
        val forcedSubclass = try { prefs.getInt(PREF_FORCED_SUBCLASS, -1) } catch (e: Exception) { -1 }
        val attempts: ByteArray = when {
            attemptsOverride != null -> attemptsOverride
            forcedSubclass >= 0 -> byteArrayOf(forcedSubclass.toByte())
            deviceAddress != null -> getSubclassOrderForDevice(deviceAddress)
            else -> SDP_SUBCLASS_FALLBACK
        }

        fun trySubclass(index: Int) {
            if (index >= attempts.size) {
                Log.e(TAG, "HID registerApp: all subclass attempts failed")
                isAppRegistered = false
                _appRegistrationState.value = false
                return
            }
            val subclass: Byte = attempts[index]
            val subclassInt = subclass.toInt() and 0xFF
            val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                "UFM Remote",
                "Ultimate File Manager",
                "UFM Remote",
                subclass,
                HID_REPORT_DESC
            )
            Log.d(TAG, "HID registerApp: trying subclass=0x${Integer.toHexString(subclassInt)} (index=$index, device=$deviceAddress)")
            try {
                val ret = hidDevice?.registerApp(sdpSettings, null, null, executor, callback)
                Log.d(TAG, "registerApp returned: $ret for subclass=0x${Integer.toHexString(subclassInt)}")
                if (ret == true) {
                    try { 
                        prefs.edit().putInt(PREF_LAST_SUBCLASS, subclassInt).apply()
                        // Also save per-device subclass if we know the device
                        if (deviceAddress != null) {
                            saveDeviceSubclass(deviceAddress, subclassInt)
                        }
                    } catch (_: Exception) {}
                    // callback will set app registration state on the main thread
                    isAppRegistered = true
                    _appRegistrationState.value = true
                } else {
                    Handler(Looper.getMainLooper()).postDelayed({ trySubclass(index + 1) }, 250)
                }
            } catch (e: Exception) {
                Log.e(TAG, "registerApp threw for subclass=0x${Integer.toHexString(subclassInt)}", e)
                Handler(Looper.getMainLooper()).postDelayed({ trySubclass(index + 1) }, 250)
            }
        }

        trySubclass(0)
    }

    private fun unregisterApp() {
        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.e(TAG, "unregisterApp threw", e)
        }
        isAppRegistered = false
        _appRegistrationState.value = false
    }

    /**
     * Attempt a device-specific workaround when a device rapidly connects then
     * disconnects. Uses DEVICE-ISOLATED re-registration to avoid disrupting
     * other connected devices. The cached subclass for this device will be tried
     * first on re-registration.
     *
     * Key behavior:
     * - If already in a device-isolated re-register, skip to avoid cascading re-registers
     * - Sets isInDeviceIsolatedReregister flag to suppress other device's re-register attempts
     * - Only re-registers the HID app (affects all devices, but controlled/intentional)
     * - Attempts to reconnect this specific device after re-registration
     */
    private fun attemptAlternateSubclassAndReconnect(device: BluetoothDevice) {
        val addr = device.address
        if (reRegisteringDevices.contains(addr)) {
            Log.d(TAG, "Already re-registering for $addr, skipping duplicate attempt")
            return
        }
        
        // If another device is already in re-registration, suppress this attempt
        // to avoid cascading re-register attempts
        if (isInDeviceIsolatedReregister) {
            Log.d(TAG, "Another device is in re-registration for ${device.name} ($addr), queuing retry")
            // Re-queue this device to try again after the current re-register completes
            scope.launch {
                delay(100)
                if (!isInDeviceIsolatedReregister) {
                    attemptAlternateSubclassAndReconnect(device)
                }
            }
            return
        }
        
        reRegisteringDevices.add(addr)
        isInDeviceIsolatedReregister = true
        
        scope.launch {
            try {
                Log.d(TAG, "┌─ DEVICE-ISOLATED re-register START ──────────")
                Log.d(TAG, "│ device = ${device.name} ($addr)")
                Log.d(TAG, "└──────────────────────────────────────────────")
                
                // Unregister the HID app
                try { 
                    Log.d(TAG, "Unregistering HID app for device-isolated re-register")
                    unregisterApp() 
                } catch (e: Exception) { 
                    Log.e(TAG, "unregisterApp failed", e) 
                }
                
                // Give the BT stack a moment to settle after unregister
                delay(300)

                // Use device-specific subclass order, which prioritizes any previously cached subclass
                val deviceSubclassOrder = getSubclassOrderForDevice(addr)
                Log.d(TAG, "Re-registering with device-specific subclass order for $addr")
                registerApp(deviceSubclassOrder, addr)

                // Wait until app registration completes (or timeout)
                var waited = 0L
                val maxWait = 5_000L
                while (!_appRegistrationState.value && waited < maxWait) {
                    delay(100)
                    waited += 100
                }
                
                if (_appRegistrationState.value) {
                    Log.d(TAG, "Device-isolated SDP re-registered for ${device.name} ($addr), reconnecting")
                    delay(150)
                    connectToDevice(device)
                } else {
                    Log.w(TAG, "Device-isolated SDP re-registration timed out for $addr")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during device-isolated re-register for $addr", e)
            } finally {
                reRegisteringDevices.remove(addr)
                isInDeviceIsolatedReregister = false
                Log.d(TAG, "Device-isolated re-register completed for $addr")
            }
        }
    }

    // ── Key Injection Methods ─────────────────────────────────────────────────

    /**
     * Sends a Keyboard key (from Usage Page 0x07) with no modifier.
     * e.g., Up Arrow, Down Arrow, Enter, Escape (Back)
     */
    fun sendKeyboardKey(hidKeyCode: Byte) {
        sendKeyboardKeyWithModifier(hidKeyCode, 0)
    }

    /**
     * Sends a Keyboard key with an optional modifier byte.
     *
     * Each call is serialised through [keystrokeMutex] and the key is held
     * down for [KEY_DOWN_HOLD_MS] before the UP report is sent.  A brief
     * [KEY_INTER_MS] gap after release ensures back-to-back keys (e.g. paste)
     * never collide on the HID bus, which caused Kodi to register each
     * character as a held key and auto-repeat it.
     *
     * Modifier bits (OR them together):
     *   0x01 = Left Ctrl   0x02 = Left Shift
     *   0x04 = Left Alt    0x08 = Left GUI (Win/Cmd)
     */
    fun sendKeyboardKeyWithModifier(hidKeyCode: Byte, modifier: Byte) {
        if (connectedDevice == null || !isAppRegistered) {
            Log.w(TAG, "Cannot send key: device=$connectedDevice, registered=$isAppRegistered")
            return
        }
        scope.launch {
            keystrokeMutex.withLock {
                val device = connectedDevice ?: return@withLock
                // Report ID 1: [modifier, reserved, key1, key2, key3, key4, key5, key6]
                val downReport = byteArrayOf(modifier, 0, hidKeyCode, 0, 0, 0, 0, 0)
                val upReport   = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
                try {
                    hidDevice?.sendReport(device, REPORT_ID_KEYBOARD.toInt(), downReport)
                    delay(KEY_DOWN_HOLD_MS)
                    hidDevice?.sendReport(device, REPORT_ID_KEYBOARD.toInt(), upReport)
                    delay(KEY_INTER_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending keyboard key", e)
                }
            }
        }
    }

    /**
     * Sends only the key-DOWN report (no UP).  Used for physical hold gestures
     * such as the OK/Center button long-press — the caller is responsible for
     * sending [sendKeyboardKeyUp] when the finger is lifted.  NOT serialised
     * through [keystrokeMutex] so the hold state is not blocked by queued text.
     */
    fun sendKeyboardKeyDown(hidKeyCode: Byte) {
        if (connectedDevice == null || !isAppRegistered) return
        val downReport = byteArrayOf(0, 0, hidKeyCode, 0, 0, 0, 0, 0)
        try {
            hidDevice?.sendReport(connectedDevice, REPORT_ID_KEYBOARD.toInt(), downReport)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending keyboard down", e)
        }
    }

    /**
     * Sends only the key-UP report.  Pair this with [sendKeyboardKeyDown].
     * NOT serialised through [keystrokeMutex] (see note on [sendKeyboardKeyDown]).
     */
    fun sendKeyboardKeyUp() {
        if (connectedDevice == null || !isAppRegistered) return
        val upReport = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        try {
            hidDevice?.sendReport(connectedDevice, REPORT_ID_KEYBOARD.toInt(), upReport)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending keyboard up", e)
        }
    }

    // ── Text / Keyboard Mode helpers ──────────────────────────────────────────

    /** Send Backspace (0x2A). */
    fun sendHidBackspace() = sendKeyboardKey(0x2A)

    /** Send Enter / Return (0x28). */
    fun sendHidEnter() = sendKeyboardKey(0x28)

    /** Send Tab (0x2B). */
    fun sendHidTab() = sendKeyboardKey(0x2B)

    /** Send Ctrl+A — Select All. */
    fun sendSelectAll() = sendKeyboardKeyWithModifier(0x04, 0x01) // keycode 'a'=0x04, modifier Left Ctrl

    /**
     * Send every character in [text] to the TV, one HID keystroke at a time.
     * Supports full printable ASCII.
     */
    fun sendText(text: String) {
        for (ch in text) sendCharacter(ch)
    }

    /**
     * Map a single printable character to its USB HID keycode + shift flag,
     * then send it as a keyboard HID report.
     */
    fun sendCharacter(char: Char) {
        val (keycode, shift) = charToHidKeycode(char) ?: return
        val modifier: Byte = if (shift) 0x02 else 0x00
        sendKeyboardKeyWithModifier(keycode, modifier)
    }

    /**
     * Sends a Consumer Control key (from Usage Page 0x0C)
     * e.g., Volume Up, Play/Pause, Home.
     *
     * Serialised through [keystrokeMutex] with the same DOWN→hold→UP→gap
     * timing as keyboard keys so volume/media events are also immune to
     * Kodi-style polling misses.
     */
    fun sendConsumerKey(hidKeyCode: Int) {
        if (connectedDevice == null || !isAppRegistered) {
            Log.w(TAG, "Cannot send consumer key: device=$connectedDevice, registered=$isAppRegistered")
            return
        }
        scope.launch {
            keystrokeMutex.withLock {
                val device = connectedDevice ?: return@withLock
                // Report ID 2 (Consumer): [keycode_low, keycode_high] = 2 bytes
                // Report ID is passed separately to sendReport(), NOT included in the data
                val downReport = byteArrayOf(
                    (hidKeyCode and 0xFF).toByte(),
                    ((hidKeyCode shr 8) and 0xFF).toByte()
                )
                val upReport = byteArrayOf(0, 0)
                try {
                    Log.d(TAG, "Sending consumer key: 0x${hidKeyCode.toString(16).padStart(4, '0')}")
                    hidDevice?.sendReport(device, REPORT_ID_CONSUMER.toInt(), downReport)
                    delay(KEY_DOWN_HOLD_MS)
                    hidDevice?.sendReport(device, REPORT_ID_CONSUMER.toInt(), upReport)
                    delay(KEY_INTER_MS)
                    Log.d(TAG, "Consumer key sent successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending consumer key: 0x${hidKeyCode.toString(16).padStart(4, '0')}", e)
                }
            }
        }
    }

    // ── TV Persistence & Auto-Connect ────────────────────────────────────────

    /**
     * Marks the start of a UFM-initiated pairing session.
     * Call immediately before opening the system Bluetooth Settings.
     * When the user returns, [TvRemoteActivity] will call [endUfmPairingSession]
     * and show an explicit picker of bonded devices not yet in UFM's list.
     */
    fun startUfmPairingSession() {
        // Snapshot all currently bonded devices before opening BT settings.
        // Devices in this snapshot were bonded BEFORE UFM's pairing flow — they
        // are excluded from the picker when the user returns.
        pairingSessionSnapshot = try {
            bluetoothAdapter?.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
        } catch (e: SecurityException) {
            emptySet()
        }
        isUfmPairingSessionActive = true
        Log.d(TAG, "UFM pairing session started (${pairingSessionSnapshot?.size} pre-existing device(s) excluded)")
    }

    /**
     * Marks the end of a UFM-initiated pairing session.
     * Called from TvRemoteActivity.onResume().
     */
    fun endUfmPairingSession() {
        isUfmPairingSessionActive = false
        pairingSessionSnapshot = null
        Log.d(TAG, "UFM pairing session ended")
    }

    /**
     * Returns Android-bonded Bluetooth devices that:
     *  1. Were bonded DURING the current UFM pairing session (not in [pairingSessionSnapshot]), AND
     *  2. Are not already saved in UFM's TV list.
     *
     * This guarantees that a device bonded outside of UFM (before the session started)
     * can never appear in the picker — even if the user goes through the "Pair TV" flow
     * without pairing anything new.
     */
    @SuppressLint("MissingPermission")
    fun getCandidateTvsForUfm(): List<BluetoothDevice> {
        val savedAddresses  = getSavedTvAddresses()
        val snapshot        = pairingSessionSnapshot ?: emptySet()
        return try {
            bluetoothAdapter?.bondedDevices
                ?.filter { it.address !in snapshot && it.address !in savedAddresses }
                ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * Save a TV device for future quick reconnection.
     */
    fun saveTvDevice(device: BluetoothDevice) {
        val address = device.address
        val name = device.name ?: address
        Log.d(TAG, "Saving TV device: $name ($address)")
        
        val savedTVs = getSavedTvAddresses().toMutableSet()
        savedTVs.add(address)
        
        prefs.edit()
            .putStringSet(PREF_SAVED_TVS, savedTVs)
            .apply()
        
        Log.d(TAG, "Saved TV devices: $savedTVs")
    }

    /**
     * Get all previously paired TV devices.
     */
    fun getSavedTvAddresses(): Set<String> {
        return prefs.getStringSet(PREF_SAVED_TVS, emptySet()) ?: emptySet()
    }

    /**
     * Get all available BluetoothDevice objects for saved TVs.
     */
    fun getSavedTvDevices(): List<BluetoothDevice> {
        val savedAddresses = getSavedTvAddresses()
        val allDevices = try {
            bluetoothAdapter?.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            emptySet()
        }
        return allDevices.filter { it.address in savedAddresses }
    }

    /**
     * Get the default TV address (if set).
     */
    fun getDefaultTvAddress(): String? {
        return prefs.getString(PREF_DEFAULT_TV, null)
    }

    /**
     * Set a TV as the default for auto-connect.
     */
    fun setDefaultTv(address: String) {
        Log.d(TAG, "Setting default TV: $address")
        prefs.edit()
            .putString(PREF_DEFAULT_TV, address)
            .apply()
    }

    /**
     * Clear the default TV selection.
     */
    fun clearDefaultTv() {
        Log.d(TAG, "Clearing default TV")
        prefs.edit()
            .remove(PREF_DEFAULT_TV)
            .apply()
    }

    /**
     * Auto-connect to the default TV if available, or the only saved TV.
     * Returns true if auto-connect was attempted, false otherwise.
     */
    fun autoConnectToSavedTv(isUserInitiated: Boolean = false): Boolean {
        if (!isUserInitiated && manualDisconnect) {
            Log.d(TAG, "Manual disconnect is set, skipping auto-connect")
            return false
        }
        if (!isUserInitiated && wasVirtualCableUnplugged) {
            Log.d(TAG, "Virtual cable was unplugged by TV — skipping auto-connect (requires user action)")
            return false
        }

        val savedDevices = getSavedTvDevices()
        if (savedDevices.isEmpty()) {
            Log.d(TAG, "No saved TVs to auto-connect")
            return false
        }

        val defaultAddress = getDefaultTvAddress()
        val targetDevice = if (defaultAddress != null) {
            savedDevices.find { it.address == defaultAddress }
        } else {
            // If no default and only 1 saved, connect to that one
            if (savedDevices.size == 1) savedDevices.first() else null
        }

        return if (targetDevice != null) {
            Log.d(TAG, "Auto-connecting to: ${targetDevice.name} (${targetDevice.address})")
            manualDisconnect = false
            connectToDevice(targetDevice)
            true
        } else {
            Log.d(TAG, "No default TV to auto-connect")
            false
        }
    }

    /**
     * Update UI with connected device name.
     */
    private fun updateConnectedDeviceName() {
        _connectedDeviceName.value = connectedDevice?.name ?: context.getString(R.string.unknown_device)
    }


    /**
     * Returns true if at least one TV has been saved/paired via this remote.
     */
    fun hasAnySavedTvs(): Boolean = getSavedTvDevices().isNotEmpty()
}

