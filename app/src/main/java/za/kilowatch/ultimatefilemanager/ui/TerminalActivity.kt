package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.AdbDevice
import za.kilowatch.ultimatefilemanager.network.AdbDeviceDiscovery
import za.kilowatch.ultimatefilemanager.network.AdbManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class TerminalActivity : AppCompatActivity() {

    private lateinit var adbManager: AdbManager
    private lateinit var adapter: TerminalAdapter
    private var isTv = false
    private var shellJob: Job? = null
    private var shellOutput: OutputStream? = null
    private var connectionJob: Job? = null
    private var adbSessionVersion = 0

    // registerForActivityResult() MUST be called before the Activity is STARTED.
    // We register eagerly here and wire up the callback body in onCreate() before
    // any coroutine is launched, so the lifecycle contract is never violated.
    private lateinit var pairingLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            setContentView(R.layout.activity_terminal_tv)
        } else {
            enableEdgeToEdge()
            setContentView(R.layout.activity_terminal)
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    maxOf(systemBars.bottom, ime.bottom)
                )
                insets
            }
        }

        // Register the pairing launcher synchronously, before any coroutine or lifecycle
        // transition. The callback body safely references adbManager/adapter because it
        // can only fire after the user has launched the pairing Activity (which itself
        // requires setupUI() to have completed and the buttons to be visible).
        pairingLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (::adbManager.isInitialized) {
                handlePairingResult(result)
            } else {
                lifecycleScope.launch {
                    if (!::adbManager.isInitialized) {
                        adbManager = withContext(Dispatchers.IO) { AdbManager.getInstance() }
                    }
                    handlePairingResult(result)
                }
            }
        }

        // AdbManager.init() calls EncryptedSharedPreferences.create() which performs a
        // blocking Android Keystore IPC. Never call getInstance() on the main thread.
        // UfmApplication pre-warms it at startup so withContext(IO) is a near-instant
        // no-op in practice, but the dispatcher guarantees safety even on first launch.
        lifecycleScope.launch {
            adbManager = withContext(Dispatchers.IO) { AdbManager.getInstance() }
            setupUI()
        }
    }

    private fun setupUI() {
        findViewById<View>(R.id.btnBack).setOnClickListener { disconnectAndFinish() }

        val recycler = findViewById<RecyclerView>(R.id.recyclerTerminal)
        adapter = TerminalAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // Long-press on RecyclerView to copy all terminal output
        recycler.setOnLongClickListener {
            val allText = adapter.getAllLines().joinToString("\n")
            if (allText.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(getString(R.string.terminal_output), allText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.copied_all_output_to_clipboard, Toast.LENGTH_SHORT).show()
            }
            true
        }

        val editCommand = findViewById<EditText>(R.id.editCommand)
        val btnSend = findViewById<View>(R.id.btnSend)
        val btnConnect = findViewById<View>(R.id.btnConnect)
        val btnDisconnect = findViewById<View>(R.id.btnDisconnect)

        btnSend.setOnClickListener { sendCommand() }
        editCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else false
        }

        // pairingLauncher is registered in onCreate() before any coroutine launches
        // to satisfy the LifecycleOwner contract (must register before STARTED).

        btnConnect.setOnClickListener {
            // Launch dedicated pairing activity instead of dialog
            val intent = if (isTv) {
                android.content.Intent(this, AdbPairingTvActivity::class.java)
            } else {
                android.content.Intent(this, AdbPairingActivity::class.java)
            }
            pairingLauncher.launch(intent)
        }

        btnDisconnect.setOnClickListener {
            adbManager.disconnectExplicit()
            shellJob?.cancel()
            adbWarningShown = false  // SEC-§8.9: reset so warning fires on next connection
            updateStatus(getString(R.string.adb_terminal_status_disconnected))
            adapter.addLine(getString(R.string.disconnected_from_adb))
            updateButtonState()
        }

        // Try auto-connect to default local ADB port if possible (history could be used here)
        lifecycleScope.launch {
            adapter.addLine(getString(R.string.welcome_to_ufm_adb_shell))
            updateButtonState()
        }
    }

    /**
     * SEC-§8.9: Show a security warning once per ADB session.
     * Uses an in-memory flag so the warning fires once for each new session but is
     * not permanently suppressed — the user sees it every time they open a connection.
     */
    private var adbWarningShown = false
    private fun showAdbSecurityWarningIfNeeded() {
        if (adbWarningShown) return
        adbWarningShown = true
        val rootView = findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(
            rootView,
            "⚠️ ADB shell grants full device access. Disconnect when not in use.",
            Snackbar.LENGTH_LONG
        ).setAction("OK") { /* dismiss */ }.show()
    }

    private fun updateStatus(status: String) {
        findViewById<TextView>(R.id.txtStatus).text = status
    }

    private fun updateButtonState() {
        val btnConnect = findViewById<View>(R.id.btnConnect)
        val btnDisconnect = findViewById<View>(R.id.btnDisconnect)
        val isConnected = adbManager.isConnected()
        btnConnect.visibility = if (isConnected) View.GONE else View.VISIBLE
        btnDisconnect.visibility = if (isConnected) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        // Guard against the rare race where onResume fires before the async
        // AdbManager init coroutine in onCreate() has completed.
        if (::adbManager.isInitialized) updateButtonState()
    }

    private fun handlePairingResult(result: androidx.activity.result.ActivityResult) {
        updateButtonState()
        if (result.resultCode == RESULT_OK) {
            val connectionEstablished = result.data?.getBooleanExtra("connection_established", false) ?: false
            if (connectionEstablished && adbManager.isConnected()) {
                adapter.addLine(getString(R.string.adb_connection_established_from_pairing_activity))
                updateStatus(getString(R.string.adb_terminal_status_connected))
                // Reset inactivity timer - keep connection alive since user is active
                adbManager.resetInactivityTimer()
                // Start shell reader to display output from device
                startShellReader()
            } else {
                adapter.addLine(getString(R.string.pairing_activity_completed_but_connection_is_not_active))
                updateStatus(getString(R.string.adb_terminal_status_disconnected))
            }
        }
    }

    private fun sendCommand() {
        val edit = findViewById<EditText>(R.id.editCommand)
        val cmd = edit.text.toString().trim()
        if (cmd.isEmpty()) return

        if (!adbManager.isConnected()) {
            Toast.makeText(this, R.string.connect_to_adb_first, Toast.LENGTH_SHORT).show()
            return
        }

        // Reset inactivity timer on every command - keeps connection alive
        adbManager.resetActivityTimer()
        
        adapter.addLine("> $cmd")
        edit.text.clear()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                shellOutput?.write((cmd + "\n").toByteArray())
                shellOutput?.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    adapter.addLine("Error: ${e.message}")
                }
            }
        }
    }

    private fun showConnectDialog(onConnectionStateChanged: (() -> Unit)? = null) {
        // Dialog for ADB connection and pairing
        val builder = MaterialAlertDialogBuilder(this)
        
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_adb_pairing, null)
        
        val editPort = view.findViewById<EditText>(R.id.editAdbPort)
        val spinnerDevices = view.findViewById<Spinner>(R.id.spinnerDevices)
        val btnScan = view.findViewById<Button>(R.id.btnScanDevices)
        val btnConnectNoPIN = view.findViewById<Button>(R.id.btnConnectNoPIN)
        val btnTogglePIN = view.findViewById<Button>(R.id.btnTogglePIN)
        val pinContainer = view.findViewById<android.view.ViewGroup>(R.id.pinContainer)
        val scanProgressContainer = view.findViewById<android.view.ViewGroup>(R.id.scanProgressContainer)
        val scanProgressText = view.findViewById<android.widget.TextView>(R.id.scanProgressText)
        val devicesDetectedText = view.findViewById<android.widget.TextView>(R.id.devicesDetectedText)
        val txtAdbStatus = view.findViewById<TextView>(R.id.txtAdbStatus)
        
        val pins = listOf<EditText>(
            view.findViewById(R.id.pin_1), view.findViewById(R.id.pin_2), view.findViewById(R.id.pin_3),
            view.findViewById(R.id.pin_4), view.findViewById(R.id.pin_5), view.findViewById(R.id.pin_6)
        )

        // Device discovery
        val discovery = AdbDeviceDiscovery(this)
        val discoveredDevices = mutableListOf<AdbDevice>()
        var selectedHost = "" // Store the selected device host
        
        // Device spinner adapter
        val deviceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf(getString(R.string.no_devices_found)))
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices.adapter = deviceAdapter
        
        // Spinner selection listener
        spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (discoveredDevices.isNotEmpty() && position < discoveredDevices.size) {
                    val device = discoveredDevices[position]
                    selectedHost = device.host
                    editPort.setText(device.port.toString())
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Scan button click listener
        btnScan.setOnClickListener {
            btnScan.isEnabled = false
            scanProgressContainer.visibility = android.view.View.VISIBLE
            scanProgressText.setText(R.string.scanning_0254_ips_scanned)
            devicesDetectedText.setText(R.string.devices_detected_0)
            
            lifecycleScope.launch {
                try {
                    discoveredDevices.clear()
                    val devices = discovery.scanNetwork(5000) { scanned, total, devicesFound ->
                        // Update progress on Main thread
                        lifecycleScope.launch(Dispatchers.Main) {
                            scanProgressText.text = getString(R.string.scanning_scannedtotal_ips_scanned, scanned, total)
                            val deviceWord = if (devicesFound == 1) "device" else "devices"
                            devicesDetectedText.text = getString(R.string.devices_detected_devicesfound_deviceword, devicesFound, deviceWord)
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (devices.isNotEmpty()) {
                            discoveredDevices.addAll(devices)
                            val displayNames = devices.map { "${it.host}:${it.port}" }
                            deviceAdapter.clear()
                            deviceAdapter.addAll(displayNames)
                            deviceAdapter.notifyDataSetChanged()
                            
                            // Auto-select first device
                            if (spinnerDevices.selectedItemPosition != 0) {
                                spinnerDevices.setSelection(0)
                            } else {
                                spinnerDevices.onItemSelectedListener?.onItemSelected(spinnerDevices, null, 0, 0)
                            }
                        } else {
                            deviceAdapter.clear()
                            deviceAdapter.add("-- No devices found --")
                            deviceAdapter.notifyDataSetChanged()
                            Toast.makeText(this@TerminalActivity, R.string.no_adb_devices_found_on, Toast.LENGTH_SHORT).show()
                        }
                        
                        // Hide scan button and progress container after scan complete
                        btnScan.visibility = android.view.View.GONE
                        scanProgressContainer.visibility = android.view.View.GONE
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TerminalActivity, R.string.scan_failed_emessage, Toast.LENGTH_SHORT).show()
                        btnScan.isEnabled = true
                        btnScan.text = resources.getString(R.string.adb_scan_devices)
                        scanProgressContainer.visibility = android.view.View.GONE
                    }
                }
            }
        }

        // Toggle PIN visibility
        var pinVisible = false
        var isSessionActive = false
        
        // Create the dialog first
        val dialog = builder.setTitle(R.string.adb_pairing_title)
               .setView(view)
               .setNegativeButton(R.string.cancel) { d, _ ->
                   if (!isSessionActive) {
                       connectionJob?.cancel()
                       lifecycleScope.launch(Dispatchers.IO) { adbManager.disconnectExplicit() }
                   }
                   d.dismiss()
               }
               .setPositiveButton(R.string.adb_terminal_connect, null)  // Placeholder, will be overridden
               .setOnDismissListener {
                   if (!isSessionActive) {
                       connectionJob?.cancel()
                       lifecycleScope.launch(Dispatchers.IO) { adbManager.disconnectExplicit() }
                   }
                   txtAdbStatus.clearAnimation()
               }
               .show()

        // Get the positive button for PIN connection
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.visibility = android.view.View.GONE

        // Now set up the toggle with access to the dialog
        btnTogglePIN.setOnClickListener {
            pinVisible = !pinVisible
            pinContainer.visibility = if (pinVisible) android.view.View.VISIBLE else android.view.View.GONE
            positiveButton.visibility = if (pinVisible) android.view.View.VISIBLE else android.view.View.GONE
            btnTogglePIN.text = if (pinVisible) getString(R.string.hide_pin) else "Use PIN"
        }

        // Connect without PIN
        btnConnectNoPIN.setOnClickListener {
            val host = if (selectedHost.isNotEmpty()) selectedHost else "127.0.0.1"
            val port = editPort.text.toString().toIntOrNull() ?: 5555
            txtAdbStatus.visibility = View.GONE
            
            // Disable buttons to prevent double-click
            positiveButton.isEnabled = false
            btnConnectNoPIN.isEnabled = false
            
            adapter.addLine("[UI] Starting connect without PIN")
            startAdbSession(host, port, "", txtAdbStatus, { success ->
                adapter.addLine("[CB] connect callback (no PIN): success=$success")
                onConnectionStateChanged?.invoke()
                if (success) {
                    isSessionActive = true
                    dialog.dismiss()
                } else {
                    positiveButton.isEnabled = true
                    btnConnectNoPIN.isEnabled = true
                    // Ensure status text visible if not already
                    txtAdbStatus?.apply {
                        visibility = View.VISIBLE
                        invalidate()
                        requestLayout()
                    }
                }
            }, reenableButtons = {
                // Defensive re-enable in case callback wasn't invoked
                positiveButton.isEnabled = true
                btnConnectNoPIN.isEnabled = true
                txtAdbStatus?.apply {
                    clearAnimation()
                    text = getString(R.string.adb_status_denied)
                    setTextColor(getColor(R.color.ufm_error))
                    visibility = View.VISIBLE
                    invalidate()
                    requestLayout()
                }
                // Dismiss the pairing dialog automatically on rejection
                try {
                    if (dialog.isShowing) {
                        dialog.dismiss()
                        adapter.addLine("[UI] Dialog dismissed automatically due to denial")
                    } else {
                        adapter.addLine("[UI] Dialog already not showing; dismissal skipped")
                    }
                } catch (e: Exception) {
                    adapter.addLine("[ERROR] dialog.dismiss() failed: ${e.message}")
                }
                updateStatus(getString(R.string.adb_terminal_status_disconnected))
            })
        }

        // Override positive button for PIN
        positiveButton.setOnClickListener {
            val host = if (selectedHost.isNotEmpty()) selectedHost else "127.0.0.1"
            val port = editPort.text.toString().toIntOrNull() ?: 5555
            val pin = pins.joinToString("") { it.text.toString() }
            if (pin.length == 6) {
                positiveButton.isEnabled = false
                btnConnectNoPIN.isEnabled = false
                adapter.addLine("[UI] Starting connect with PIN")
                startAdbSession(host, port, pin, txtAdbStatus, { success ->
                    adapter.addLine("[CB] connect callback (PIN): success=$success")
                    onConnectionStateChanged?.invoke()
                    if (success) {
                        isSessionActive = true
                        dialog.dismiss()
                    } else {
                        positiveButton.isEnabled = true
                        btnConnectNoPIN.isEnabled = true
                        txtAdbStatus?.apply {
                            visibility = View.VISIBLE
                            invalidate()
                            requestLayout()
                        }
                    }
                }, reenableButtons = {
                    positiveButton.isEnabled = true
                    btnConnectNoPIN.isEnabled = true
                    txtAdbStatus?.apply {
                        clearAnimation()
                        text = getString(R.string.adb_status_denied)
                        setTextColor(getColor(R.color.ufm_error))
                        visibility = View.VISIBLE
                        invalidate()
                        requestLayout()
                    }
                    try {
                        if (dialog.isShowing) {
                            dialog.dismiss()
                            adapter.addLine("[UI] Dialog dismissed automatically due to denial (PIN)")
                        } else {
                            adapter.addLine("[UI] Dialog already not showing; dismissal skipped (PIN)")
                        }
                    } catch (e: Exception) {
                        adapter.addLine("[ERROR] dialog.dismiss() failed: ${e.message}")
                    }
                    updateStatus(getString(R.string.adb_terminal_status_disconnected))
                })
            } else {
                Toast.makeText(this, R.string.please_enter_a_6digit_pin, Toast.LENGTH_SHORT).show()
            }
        }

        // PIN auto-focus logic
        for (i in pins.indices) {
            pins[i].addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1 && i < pins.size - 1) {
                        pins[i + 1].requestFocus()
                    }
                }
            })
            // Backspace logic
            pins[i].setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && pins[i].text.isEmpty() && i > 0) {
                    pins[i - 1].requestFocus()
                    pins[i - 1].text.clear()
                    true
                } else false
            }
        }
    }

    private fun startFlickerAnimation(view: View) {
        val anim = AlphaAnimation(1.0f, 0.3f)
        anim.duration = 600
        anim.repeatMode = Animation.REVERSE
        anim.repeatCount = Animation.INFINITE
        view.startAnimation(anim)
    }

    private fun startAdbSession(host: String, port: Int, pin: String, statusText: TextView? = null, onComplete: ((Boolean) -> Unit)? = null, reenableButtons: (() -> Unit)? = null) {
        connectionJob?.cancel()
        adbSessionVersion++
        val currentVersion = adbSessionVersion
        var successResult = false
        
        connectionJob = lifecycleScope.launch {
            try {
                updateStatus(getString(R.string.adb_terminal_status_connecting))
                adapter.addLine(getString(R.string.attempting_to_connect_to_hostport, host, port))
                
                // Show awaiting approval message in dialog if linking without PIN or if pairing starts
                statusText?.apply {
                    text = getString(R.string.adb_status_awaiting)
                    setTextColor(android.graphics.Color.parseColor("#00E676")) // Bright Green
                    visibility = View.VISIBLE
                    startFlickerAnimation(this)
                }

                successResult = withContext(Dispatchers.IO) {
                    // If PIN is provided, attempt pairing first
                    if (pin.length == 6) {
                        withContext(Dispatchers.Main) {
                            adapter.addLine(getString(R.string.pairing_with_device))
                        }
                        if (adbManager.pair(host, port, pin)) {
                            delay(500)
                        } else {
                            withContext(Dispatchers.Main) {
                                adapter.addLine(getString(R.string.pairing_failed_attempting_direct_connection))
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            adapter.addLine(getString(R.string.connecting_without_pairing_usb_debugging))
                        }
                    }
                    
                    try {
                        withTimeout(8000) {
                            adbManager.connect(host, port)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e("TerminalActivity", "Connect failed: ${e.message}")
                        // Update status immediately on exception so user sees denial/failure
                        withContext(Dispatchers.Main) {
                            statusText?.apply {
                                clearAnimation()
                                text = getString(R.string.adb_status_denied)
                                setTextColor(getColor(R.color.ufm_error))
                                visibility = View.VISIBLE
                                invalidate()
                                requestLayout()
                            }
                            adapter.addLine("[UI] Dialog status set to DENIED (connect exception)")
                        }
                        false
                    }
                }

                adapter.addLine("[INTERNAL] successResult=$successResult currentVersion=$currentVersion adbSessionVersion=$adbSessionVersion")
                if (successResult && currentVersion == adbSessionVersion) {
                    statusText?.apply {
                        clearAnimation()
                        text = getString(R.string.adb_status_approved)
                        setTextColor(getColor(R.color.ufm_primary))
                    }
                    updateStatus(getString(R.string.adb_terminal_status_connected))
                    adapter.addLine(getString(R.string.connected_to_adb_successfully))
                    startShellReader()
                    showAdbSecurityWarningIfNeeded()
                    delay(800) // Brief delay to show getString(R.string.approved)
                    lifecycleScope.launch(Dispatchers.Main) { onComplete?.invoke(true) }
                } else {
                    lifecycleScope.launch(Dispatchers.Main) { onComplete?.invoke(false) }
                }
            } finally {
                // Ensure status is reset if we didn't end up connected
                // Use NonCancellable context to ensure UI update even after cancellation
                withContext(NonCancellable + Dispatchers.Main) {
                    if (!successResult && currentVersion == adbSessionVersion) {
                        updateStatus(getString(R.string.adb_terminal_status_disconnected))
                        statusText?.apply {
                            clearAnimation()
                            text = getString(R.string.adb_status_denied)
                            setTextColor(getColor(R.color.ufm_error))
                            visibility = View.VISIBLE
                            invalidate()
                            requestLayout()
                        }
                        adapter.addLine("[UI] Dialog status set to DENIED (finalizer)")
                        // Attempt to re-enable dialog buttons if caller supplied a handler
                        try {
                            reenableButtons?.invoke()
                        } catch (e: Exception) {
                            adapter.addLine("[ERROR] reenableButtons threw: ${e.message}")
                        }

                        // Add log entry only if not explicit cancellation to avoid clutter
                        if (isActive) {
                           adapter.addLine(getString(R.string.connection_failed_or_denied))
                        }
                    }
                }
            }
        }
    }

    private fun startShellReader() {
        shellJob?.cancel()
        shellJob = lifecycleScope.launch(Dispatchers.IO) {
            val stream = adbManager.openShell() ?: return@launch
            shellOutput = stream.openOutputStream()
            val reader = BufferedReader(InputStreamReader(stream.openInputStream()))
            
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    // Reset inactivity timer - activity on shell means connection is in use
                    adbManager.resetActivityTimer()
                    withContext(Dispatchers.Main) {
                        adapter.addLine(line)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    adapter.addLine(getString(R.string.adb_shell_closed, e.message ?: getString(R.string.unknown_error)))
                }
            } finally {
                withContext(Dispatchers.Main) {
                    updateStatus(getString(R.string.adb_terminal_status_disconnected))
                    adbManager.disconnectExplicit()
                }
            }
        }
    }

    private fun disconnectAndFinish() {
        shellJob?.cancel()
        connectionJob?.cancel()
        if (::adbManager.isInitialized) {
            adbManager.disconnectExplicit()
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        disconnectAndFinish()
    }

    override fun onDestroy() {
        super.onDestroy()
        shellJob?.cancel()
        connectionJob?.cancel()
        if (::adbManager.isInitialized) {
            adbManager.disconnectExplicit()
        }
    }

    // --- Adapter ---
    private class TerminalAdapter : RecyclerView.Adapter<TerminalAdapter.ViewHolder>() {
        // ArrayDeque gives O(1) removal from the front (vs ArrayList's O(n) element shift)
        private val lines = ArrayDeque<String>()
        private var recyclerView: RecyclerView? = null

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            this.recyclerView = recyclerView
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            super.onDetachedFromRecyclerView(recyclerView)
            this.recyclerView = null  // prevent leaking the RecyclerView reference
        }

        /**
         * Must be called on the Main thread only (already guaranteed by all callers).
         * Uses surgical notify calls instead of notifyDataSetChanged() so RecyclerView
         * only rebinds the two affected rows when the buffer is trimmed, rather than
         * forcing a full measure/layout/draw pass on every item.
         */
        fun addLine(line: String) {
            lines.addLast(line)
            if (lines.size > MAX_LINES) {
                lines.removeFirst()              // O(1) with ArrayDeque
                notifyItemRemoved(0)
                notifyItemInserted(lines.size - 1)
            } else {
                notifyItemInserted(lines.size - 1)
            }
            // Scroll after notify so the new item is already registered with the adapter
            recyclerView?.scrollToPosition(lines.size - 1)
        }

        /** Must be called on the Main thread only. */
        fun getAllLines(): List<String> = lines.toList()

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setTextColor(if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(context)) 
                    context.getColor(R.color.tv_text_primary) else 
                    context.getColor(R.color.mobile_text_primary))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(8, 4, 8, 4)
                // Terminal output is always LTR — skips expensive BiDi analysis on every setText()
                textDirection = View.TEXT_DIRECTION_LTR
                // Enable word wrapping
                isSingleLine = false
                isHorizontalScrollBarEnabled = false

                // Long-press on any line to copy all output
                setOnLongClickListener {
                    val allText = getAllLines().joinToString("\n")
                    if (allText.isNotEmpty()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(context.getString(R.string.terminal_output), allText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, R.string.copied_all_output_to_clipboard, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }
            return ViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val textView = holder.itemView as TextView
            textView.text = lines[position]
        }

        override fun getItemCount() = lines.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

        companion object {
            private const val MAX_LINES = 500
        }
    }
}
