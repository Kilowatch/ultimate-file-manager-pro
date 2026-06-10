package za.kilowatch.ultimatefilemanager.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.AdbDevice
import za.kilowatch.ultimatefilemanager.network.AdbDeviceDiscovery
import za.kilowatch.ultimatefilemanager.network.AdbManager
import android.util.Log

class AdbPairingActivity : AppCompatActivity() {

    private lateinit var adbManager: AdbManager
    private var currentConnectionJob: kotlinx.coroutines.Job? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_adb_pairing)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layoutToolbar)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scrollView)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }

        adbManager = AdbManager.getInstance(this)

        val editPort = findViewById<EditText>(R.id.editAdbPort)
        val spinnerDevices = findViewById<Spinner>(R.id.spinnerDevices)
        val btnScan = findViewById<Button>(R.id.btnScanDevices)
        val btnConnectNoPIN = findViewById<Button>(R.id.btnConnectNoPIN)
        val btnTogglePIN = findViewById<Button>(R.id.btnTogglePIN)
        val btnConnectWithPin = findViewById<Button>(R.id.btnConnectWithPin)
        val pinContainer = findViewById<View>(R.id.pinContainer)
        val scanProgressContainer = findViewById<View>(R.id.scanProgressContainer)
        val scanProgressText = findViewById<TextView>(R.id.scanProgressText)
        val devicesDetectedText = findViewById<TextView>(R.id.devicesDetectedText)
        val txtAdbStatus = findViewById<TextView>(R.id.txtAdbStatus)
        val txtHeaderStatus = findViewById<TextView>(R.id.txtHeaderStatus)
        val btnBack = findViewById<ImageView>(R.id.btnBack)

        val pins = listOf(
            findViewById<EditText>(R.id.pin_1), findViewById(R.id.pin_2), findViewById(R.id.pin_3),
            findViewById(R.id.pin_4), findViewById(R.id.pin_5), findViewById(R.id.pin_6)
        )

        val discovery = AdbDeviceDiscovery(this)
        val discoveredDevices = mutableListOf<AdbDevice>()
        var selectedHost = ""
        var selectedPort = 5555

        val deviceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf(getString(R.string.no_devices_found)))
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices.adapter = deviceAdapter

        spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (discoveredDevices.isNotEmpty() && position < discoveredDevices.size) {
                    val device = discoveredDevices[position]
                    selectedHost = device.host
                    selectedPort = device.port
                    editPort.setText(device.port.toString())
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnScan.setOnClickListener {
            btnScan.isEnabled = false
            scanProgressContainer.visibility = View.VISIBLE
            scanProgressText.setText(R.string.scanning_0254_ips_scanned)
            devicesDetectedText.setText(R.string.devices_detected_0)

            lifecycleScope.launch {
                try {
                    discoveredDevices.clear()
                    val devices = discovery.scanNetwork(5000) { scanned, total, devicesFound ->
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
                            Toast.makeText(this@AdbPairingActivity, R.string.no_adb_devices_found, Toast.LENGTH_SHORT).show()
                        }

                        btnScan.visibility = View.GONE
                        scanProgressContainer.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AdbPairingActivity, R.string.scan_failed_emessage, Toast.LENGTH_SHORT).show()
                        btnScan.isEnabled = true
                        btnScan.text = resources.getString(R.string.adb_scan_devices)
                        scanProgressContainer.visibility = View.GONE
                    }
                }
            }
        }

        btnTogglePIN.setOnClickListener {
            val pinVisible = pinContainer.visibility != View.VISIBLE
            pinContainer.visibility = if (pinVisible) View.VISIBLE else View.GONE
            btnTogglePIN.text = if (pinVisible) getString(R.string.hide_pin) else "Use PIN"
        }

        btnConnectNoPIN.setOnClickListener {
            val host = selectedHost.ifEmpty { "127.0.0.1" }
            val port = editPort.text.toString().toIntOrNull() ?: 5555
            attemptConnection(host, port, "", txtAdbStatus, txtHeaderStatus, btnConnectNoPIN, btnConnectWithPin)
        }

        btnConnectWithPin.setOnClickListener {
            val host = selectedHost.ifEmpty { "127.0.0.1" }
            val port = editPort.text.toString().toIntOrNull() ?: 5555
            val pin = pins.joinToString("") { it.text.toString() }
            if (pin.length == 6) {
                attemptConnection(host, port, pin, txtAdbStatus, txtHeaderStatus, btnConnectNoPIN, btnConnectWithPin)
            } else {
                Toast.makeText(this, R.string.please_enter_a_6digit_pin, Toast.LENGTH_SHORT).show()
            }
        }

        // PIN auto-focus
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
            pins[i].setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && pins[i].text.isEmpty() && i > 0) {
                    pins[i - 1].requestFocus()
                    pins[i - 1].text.clear()
                    true
                } else false
            }
        }

        btnBack.setOnClickListener { disconnectAndFinish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        disconnectAndFinish()
    }

    private fun disconnectAndFinish() {
        currentConnectionJob?.cancel()
        adbManager.disconnectExplicit()
        finish()
    }

    private fun attemptConnection(host: String, port: Int, pin: String, statusText: TextView, headerStatusText: TextView, btnNoPIN: Button, btnWithPin: Button) {
        currentConnectionJob?.cancel()
        btnWithPin.isEnabled = false
        statusText.visibility = View.GONE

        // Toggle Connect button into Cancel mode
        val originalText = btnNoPIN.text
        val originalBgTint = btnNoPIN.backgroundTintList
        val originalTextColors = btnNoPIN.textColors
        btnNoPIN.text = getString(R.string.cancel)
        btnNoPIN.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_error))
        btnNoPIN.setTextColor(android.graphics.Color.WHITE)
        btnNoPIN.isEnabled = true
        btnNoPIN.setOnClickListener {
            // User pressed Cancel — cancel the connection job
            currentConnectionJob?.cancel()
        }

        // Show awaiting approval message with flicker
        statusText.apply {
            text = getString(R.string.adb_status_awaiting)
            setTextColor(android.graphics.Color.parseColor("#00E676")) // Bright Green
            visibility = View.VISIBLE
            startFlickerAnimation(this)
        }
        headerStatusText.text = getString(R.string.adb_terminal_status_connecting)

        var connectionSuccess = false

        currentConnectionJob = lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    try {
                        // If PIN is provided, pair first
                        if (pin.isNotEmpty()) {
                            if (!adbManager.pair(host, port, pin)) {
                                return@withContext false
                            }
                            delay(500)
                        }

                        adbManager.connect(host, port)
                    } catch (e: Exception) {
                        Log.e("AdbPairingActivity", "Connection failed: ${e.message}", e)
                        false
                    }
                }

                if (success) {
                    connectionSuccess = true
                    statusText.apply {
                        clearAnimation()
                        text = getString(R.string.adb_status_approved)
                        setTextColor(getColor(R.color.ufm_primary))
                    }
                    headerStatusText.text = getString(R.string.adb_terminal_status_connected)
                    delay(800)
                    setResult(RESULT_OK, android.content.Intent().apply { putExtra("connection_established", true) })
                    finish()
                }
            } catch (e: Exception) {
                Log.e("AdbPairingActivity", "Unexpected error: ${e.message}", e)
            } finally {
                if (!connectionSuccess) {
                    statusText.clearAnimation()
                    statusText.text = getString(R.string.adb_status_denied)
                    statusText.setTextColor(getColor(R.color.ufm_error))
                    statusText.visibility = View.VISIBLE
                    headerStatusText.text = getString(R.string.adb_pairing_title)
                    btnWithPin.isEnabled = true
                    // Restore Connect button from Cancel mode
                    btnNoPIN.text = originalText
                    btnNoPIN.backgroundTintList = originalBgTint
                    btnNoPIN.setTextColor(originalTextColors)
                    btnNoPIN.isEnabled = true
                    btnNoPIN.setOnClickListener {
                        val h = host.ifEmpty { "127.0.0.1" }
                        val p = findViewById<EditText>(R.id.editAdbPort).text.toString().toIntOrNull() ?: 5555
                        attemptConnection(h, p, "", statusText, headerStatusText, btnNoPIN, btnWithPin)
                    }
                    lifecycleScope.launch(Dispatchers.IO) { adbManager.disconnectExplicit() }
                }
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

    override fun onDestroy() {
        super.onDestroy()
        currentConnectionJob?.cancel()
        // Don't disconnect here - this fires on normal finish() too.
        // Only disconnect when user explicitly presses back (handled in onBackPressed)
    }
}
