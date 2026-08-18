package za.kilowatch.ultimatefilemanager.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.PairedDevice
import za.kilowatch.ultimatefilemanager.network.PairingDiscovery
import za.kilowatch.ultimatefilemanager.network.PairingManager
import za.kilowatch.ultimatefilemanager.network.PairingServer
import kotlin.random.Random
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class DevicePairingActivity : AppCompatActivity() {

    private lateinit var pairingManager: PairingManager
    private lateinit var adapter: PairedDeviceAdapter
    private var isTvMode = false
    private var tvPairingDialog: android.app.AlertDialog? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshList()
            if (isTvMode && intent != null) {
                val newDevice = intent.getStringExtra("newly_paired_device_name")
                if (newDevice != null && tvPairingDialog?.isShowing == true) {
                    tvPairingDialog?.dismiss()
                    Toast.makeText(this@DevicePairingActivity, getString(R.string.linked_with_newdevice, newDevice), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTvMode = isTvDevice()
        
        if (isTvMode) {
            setContentView(R.layout.activity_device_pairing_tv)
        } else {
            enableEdgeToEdge()
            setContentView(R.layout.activity_device_pairing)
            val mainView = findViewById<View>(R.id.main)
            if (mainView != null) {
                ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                    insets
                }
            }
            findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        }

        pairingManager = PairingManager.getInstance(this)
        setupRecyclerView()
        
        val btnAdd = findViewById<View>(R.id.btnAddDevice)
        btnAdd.setOnClickListener {
            if (isTvMode) {
                showTvPairingDialog()
            } else {
                startMobileDiscovery()
            }
        }
        
        if (isTvMode) {
            findViewById<View>(R.id.btnRenameTv)?.setOnClickListener {
                showTvRenameDialog()
            }
        }
        
        Log.d("DevicePairing", "Registering pairing update receiver")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, IntentFilter("za.kilowatch.ufm.PAIRING_UPDATED"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, IntentFilter("za.kilowatch.ufm.PAIRING_UPDATED"))
        }
    }

    private fun isTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        return uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun setupRecyclerView() {
        adapter = PairedDeviceAdapter(
            isTvLayout = isTvMode,
            onConnectClick = { device, connect -> handleConnectClick(device, connect) },
            onEditNameClick = { device -> showRenameDialog(device) },
            onDeleteClick = { device -> 
                showDeleteConfirmationDialog(device)
            }
        )
        
        val recycler = findViewById<RecyclerView>(R.id.recyclerPairedDevices)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        refreshList()
    }

    private fun refreshList() {
        val devices = pairingManager.getAllPairedDevices()
        adapter.setDevices(devices)
        findViewById<TextView>(R.id.txtEmptyState).visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE

        // Asynchronously verify connected devices are still reachable
        lifecycleScope.launch {
            for (device in devices) {
                if (device.isConnected) {
                    val isOnline = pairingManager.pingDevice(device)
                    if (!isOnline) {
                        pairingManager.updateConnectionStatus(device.deviceId, false)
                        withContext(Dispatchers.Main) {
                            adapter.setDevices(pairingManager.getAllPairedDevices())
                        }
                    }
                }
            }
        }
    }

    private fun handleConnectClick(device: PairedDevice, connect: Boolean) {
        lifecycleScope.launch {
            val success = pairingManager.connectToDevice(this@DevicePairingActivity, device.deviceId, connect)
            if (success) {
                refreshList()
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DevicePairingActivity, R.string.connection_failed_make_sure_the, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showRenameDialog(device: PairedDevice) {
        val input = EditText(this)
        input.setText(device.name)
        
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_device)
            .setView(input)
            .setPositiveButton(R.string.network_btn_save) { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotBlank()) {
                    // Update the local display name only.
                    // Do NOT push to the remote — renameRemoteDevice sends our own deviceId
                    // to the remote's /rename endpoint, which renames the phone's record on
                    // the TV, making both sides appear to have the same new name.
                    device.name = newName
                    pairingManager.addOrUpdateDevice(device)
                    refreshList()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmationDialog(device: PairedDevice) {
        if (isTvMode) {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.remove_device)
                .setMessage(getString(R.string.are_you_sure_you_want_to_remove_devicename_from_paired_devices, device.name))
                .setPositiveButton(R.string.network_delete_confirm_yes) { _, _ -> performDelete(device) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.remove_device)
                .setMessage(getString(R.string.are_you_sure_you_want_to_remove_devicename_from_paired_devices, device.name))
                .setPositiveButton(R.string.network_delete_confirm_yes) { _, _ -> performDelete(device) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun performDelete(device: PairedDevice) {
        lifecycleScope.launch {
            // Tell the remote end we are unpairing
            pairingManager.unpairRemoteDevice(device)
            
            withContext(Dispatchers.Main) {
                pairingManager.removeDevice(device.deviceId)
                if (isTvMode && pairingManager.getAllPairedDevices().isEmpty()) {
                    za.kilowatch.ultimatefilemanager.network.TvServerForegroundService.stop(this@DevicePairingActivity)
                }
                refreshList()
            }
        }
    }

    // --- TV FLOW ---
    private fun showTvPairingDialog() {
        val intent = Intent(this, TvPairingActivity::class.java)
        startActivity(intent)
    }

    private fun showTvRenameDialog() {
        val prefs = getSharedPreferences("UFM_Pairing_Prefs", Context.MODE_PRIVATE)
        val currentName = prefs.getString("my_tv_name", android.os.Build.MODEL ?: getString(R.string.android_tv))
        
        val input = EditText(this)
        input.setText(currentName)
        input.setSingleLine(true)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.rename_tv)
            .setView(input)
            .setPositiveButton(R.string.network_btn_save) { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotBlank()) {
                    prefs.edit().putString("my_tv_name", newName).apply()
                    Toast.makeText(this, getString(R.string.tv_renamed_to_newname, newName), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
            
        dialog.show()
    }

    // --- MOBILE FLOW ---
    private fun startMobileDiscovery() {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.searching_for_tvs)
            .setMessage(R.string.looking_for_tvs_on_local)
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        lifecycleScope.launch {
            val foundDevices = PairingDiscovery.discoverDevices(this@DevicePairingActivity)
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (foundDevices.isEmpty()) {
                    Toast.makeText(this@DevicePairingActivity, R.string.no_tvs_found_make_sure, Toast.LENGTH_LONG).show()
                } else {
                    showTvSelectionDialog(foundDevices)
                }
            }
        }
    }

    private fun showTvSelectionDialog(devices: List<PairingDiscovery.DiscoveredDevice>) {
        val names = devices.map { it.deviceName }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_tv_to_link)
            .setItems(names) { _, which ->
                showPinEntryDialog(devices[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPinEntryDialog(targetDevice: PairingDiscovery.DiscoveredDevice) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_mobile_pin_entry, null)

        val d1 = dialogView.findViewById<EditText>(R.id.pin_digit_1)
        val d2 = dialogView.findViewById<EditText>(R.id.pin_digit_2)
        val d3 = dialogView.findViewById<EditText>(R.id.pin_digit_3)
        val d4 = dialogView.findViewById<EditText>(R.id.pin_digit_4)
        val digits = listOf(d1, d2, d3, d4)

        // Auto-advance focus on each digit typed
        for (i in digits.indices) {
            digits[i].addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1 && i < digits.size - 1) {
                        digits[i + 1].requestFocus()
                    }
                }
            })
            // Backspace: move to previous box and clear it
            digits[i].setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    digits[i].text.isEmpty() && i > 0
                ) {
                    digits[i - 1].text.clear()
                    digits[i - 1].requestFocus()
                    true
                } else {
                    false
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_pin)
            .setView(dialogView)
            .setPositiveButton(R.string.link, null) // set null to override later
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            d1.requestFocus()
            // Show keyboard
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(d1, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = digits.joinToString("") { it.text.toString() }
                if (pin.length == 4) {
                    dialog.dismiss()
                    performPairingRequest(targetDevice, pin)
                } else {
                    Toast.makeText(this, R.string.please_enter_all_4_digits, Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    private fun performPairingRequest(targetDevice: PairingDiscovery.DiscoveredDevice, pin: String) {
        lifecycleScope.launch {
            val success = pairingManager.sendPairingRequest(targetDevice.ipAddress, targetDevice.httpPort, pin)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@DevicePairingActivity, R.string.paired_successfully, Toast.LENGTH_SHORT).show()
                    refreshList()
                } else {
                    Toast.makeText(this@DevicePairingActivity, R.string.pairing_failed_incorrect_pin, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(updateReceiver)
    }
}
