package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.AdbDevice
import za.kilowatch.ultimatefilemanager.network.AdbDeviceDiscovery
import za.kilowatch.ultimatefilemanager.network.AdbManager
import za.kilowatch.ultimatefilemanager.network.AdbPushManager
import za.kilowatch.ultimatefilemanager.network.WebShareServer
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.QrCodeUtils
import java.io.File

class PremiumShareActivity : AppCompatActivity() {

    private val TAG = "PremiumShareActivity"

    private lateinit var files: List<File>
    private var targetType: String = "web" // default to web share

    // Common UI views
    private lateinit var btnBack: ImageView

    // ADB UI views
    private lateinit var layoutAdbPush: View
    private lateinit var layoutScanning: View
    private lateinit var rvDevices: RecyclerView
    private lateinit var edtCustomPort: EditText
    private lateinit var btnConnectManual: MaterialButton
    private lateinit var cardAdbProgress: MaterialCardView
    private lateinit var txtAdbProgressStatus: TextView
    private lateinit var pbAdbTransfer: ProgressBar

    // Web Share UI views
    private lateinit var layoutWebShare: View
    private lateinit var txtWebUrl: TextView
    private lateinit var txtWebPin: TextView
    private lateinit var imgQrCode: ImageView
    private lateinit var btnStopWebShare: MaterialButton

    private var devicesAdapter: DevicesAdapter? = null
    private var isScanning = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_premium_share_mobile)

        // Parse files and target type
        val filePaths = intent.getStringArrayListExtra("files") ?: emptyList()
        files = filePaths.map { File(it) }
        targetType = intent.getStringExtra("target_type") ?: "web"

        if (files.isEmpty()) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupListeners()

        if (targetType == "tv") {
            setupAdbMode()
        } else {
            setupWebShareMode()
        }
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)

        // ADB components
        layoutAdbPush = findViewById(R.id.layoutAdbPush)
        layoutScanning = findViewById(R.id.layoutScanning)
        rvDevices = findViewById(R.id.rvDevices)
        edtCustomPort = findViewById(R.id.edtCustomPort)
        btnConnectManual = findViewById(R.id.btnConnectManual)
        cardAdbProgress = findViewById(R.id.cardAdbProgress)
        txtAdbProgressStatus = findViewById(R.id.txtAdbProgressStatus)
        pbAdbTransfer = findViewById(R.id.pbAdbTransfer)

        // Web Share components
        layoutWebShare = findViewById(R.id.layoutWebShare)
        txtWebUrl = findViewById(R.id.txtWebUrl)
        txtWebPin = findViewById(R.id.txtWebPin)
        imgQrCode = findViewById(R.id.imgQrCode)
        btnStopWebShare = findViewById(R.id.btnStopWebShare)

        // Setup recycler view
        rvDevices.layoutManager = LinearLayoutManager(this)
        devicesAdapter = DevicesAdapter { device ->
            onDeviceSelected(device)
        }
        rvDevices.adapter = devicesAdapter
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            handleExit()
        }

        btnStopWebShare.setOnClickListener {
            handleExit()
        }

        btnConnectManual.setOnClickListener {
            val portText = edtCustomPort.text.toString().trim()
            val port = portText.toIntOrNull() ?: 5555
            // Subnet sweep find standard address or let user enter ip?
            // Since we scan, let's search for first active device address or show alert
            Toast.makeText(this, "Select a discovered device from the list above.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAdbMode() {
        layoutAdbPush.visibility = View.VISIBLE
        layoutWebShare.visibility = View.GONE
        startAdbScan()
    }

    private fun startAdbScan() {
        if (isScanning) return
        isScanning = true
        layoutScanning.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val discovery = AdbDeviceDiscovery(this@PremiumShareActivity)
                val found = discovery.scanNetwork { scanned, total, devicesFound ->
                    // Network progress if needed
                }
                devicesAdapter?.updateDevices(found)
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning for ADB devices: ${e.message}")
            } finally {
                isScanning = false
                layoutScanning.visibility = View.GONE
            }
        }
    }

    private fun onDeviceSelected(device: AdbDevice) {
        // If single APK/XAPK, prompt user
        val isPackage = files.size == 1 && (files[0].name.endsWith(".apk", ignoreCase = true) ||
                files[0].name.endsWith(".xapk", ignoreCase = true) ||
                files[0].name.endsWith(".apks", ignoreCase = true))
        if (isPackage) {
            showPremiumApkActionDialog(device, files[0])
        } else {
            showPremiumConfirmTransferDialog(device, files, false)
        }
    }

    private fun showPremiumApkActionDialog(device: AdbDevice, apkFile: File) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_premium_share_chooser, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        // Customize the layout content for APK choices
        val titleText = dialogView.findViewById<TextView>(R.id.share_dialog_title) ?: dialogView.findViewById(R.id.txtTitle)
        titleText?.text = getString(R.string.apk_action_title)

        val descText = dialogView.findViewById<TextView>(R.id.share_dialog_subtitle) ?: dialogView.findViewById(R.id.txtSubtitle)
        descText?.text = getString(R.string.apk_action_message, apkFile.name)

        val cardInstall = dialogView.findViewById<MaterialCardView>(R.id.cardStandardShare)
        val txtInstallTitle = cardInstall.findViewById<TextView>(R.id.share_option_standard)
        txtInstallTitle.text = getString(R.string.apk_action_install)
        val txtInstallDesc = cardInstall.findViewById<TextView>(R.id.share_option_standard_desc)
        txtInstallDesc.text = getString(R.string.installing_apk, apkFile.name)

        val cardCopy = dialogView.findViewById<MaterialCardView>(R.id.cardPremiumShare)
        val txtCopyTitle = cardCopy.findViewById<TextView>(R.id.share_option_premium)
        txtCopyTitle.text = getString(R.string.apk_action_copy)
        val txtCopyDesc = cardCopy.findViewById<TextView>(R.id.share_option_premium_desc)
        txtCopyDesc.text = getString(R.string.confirm_transfer_message, 1, Formatter.formatFileSize(this, getFileSize(apkFile)), getString(R.string.default_destination_path))

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        cardInstall.setOnClickListener {
            dialog.dismiss()
            startAdbTransfer(device, listOf(apkFile), true)
        }

        cardCopy.setOnClickListener {
            dialog.dismiss()
            startAdbTransfer(device, listOf(apkFile), false)
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun getFileSize(file: File): Long {
        val path = file.absolutePath
        return if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(path) ||
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, path)) {
            val size = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(this, path)
            if (size >= 0L) size else file.length()
        } else {
            file.length()
        }
    }

    private fun showPremiumConfirmTransferDialog(device: AdbDevice, transferFiles: List<File>, installApk: Boolean) {
        val totalBytes = transferFiles.sumOf { getFileSize(it) }
        val sizeStr = Formatter.formatFileSize(this, totalBytes)
        val destPath = getString(R.string.default_destination_path)
        val message = getString(R.string.confirm_transfer_message, transferFiles.size, sizeStr, destPath)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_support_message, null)
        val imgIcon = dialogView.findViewById<ImageView>(R.id.imgDialogIcon)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val txtMsg = dialogView.findViewById<TextView>(R.id.txtDialogMessage)
        val btnPositive = dialogView.findViewById<MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = dialogView.findViewById<MaterialButton>(R.id.btnDialogNegative)

        imgIcon?.setImageResource(R.drawable.ic_sync)
        txtTitle?.setText(R.string.confirm_transfer_title)
        txtMsg?.text = message
        btnPositive?.setText(R.string.btn_continue)
        btnNegative?.visibility = View.VISIBLE
        btnNegative?.setText(R.string.delete_cancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnPositive?.setOnClickListener {
            dialog.dismiss()
            startAdbTransfer(device, transferFiles, installApk)
        }
        btnNegative?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun startAdbTransfer(device: AdbDevice, transferFiles: List<File>, installApk: Boolean) {
        cardAdbProgress.visibility = View.VISIBLE
        txtAdbProgressStatus.text = getString(R.string.connecting_to_device, device.toString())
        pbAdbTransfer.progress = 0

        lifecycleScope.launch(Dispatchers.IO) {
            val adbManager = AdbManager.getInstance()
            
            // Connect to the target device
            val connected = adbManager.connect(device.host, device.port)
            if (!connected) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PremiumShareActivity,
                        adbManager.lastError ?: getString(R.string.adb_connection_denied_or_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    cardAdbProgress.visibility = View.GONE
                }
                return@launch
            }

            // Transfer all files
            for (file in transferFiles) {
                val isApk = file.name.endsWith(".apk", ignoreCase = true)
                val isXapk = file.name.endsWith(".xapk", ignoreCase = true) || file.name.endsWith(".apks", ignoreCase = true)
                val isInstall = installApk && (isApk || isXapk)
                val remoteDir = if (isInstall) "/data/local/tmp" else "/storage/emulated/0/Download"

                withContext(Dispatchers.Main) {
                    txtAdbProgressStatus.text = getString(R.string.transferring_file, file.name, 0, 0.0)
                }

                val startTime = System.currentTimeMillis()
                var installSuccess = false

                if (isInstall && isXapk) {
                    // For XAPK, extract and install splits via session
                    val success = AdbPushManager.installXapk(this@PremiumShareActivity, file) { bytesSent, totalBytes ->
                        val percent = if (totalBytes > 0) (bytesSent * 100 / totalBytes).toInt() else 0
                        val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                        val speedMBs = if (elapsedSeconds > 0) (bytesSent / (1024.0 * 1024.0)) / elapsedSeconds else 0.0
                        
                        runOnUiThread {
                            pbAdbTransfer.isIndeterminate = false
                            pbAdbTransfer.progress = percent
                            txtAdbProgressStatus.text = getString(R.string.transferring_file, file.name, percent, speedMBs)
                        }
                    }

                    if (success) {
                        installSuccess = true
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PremiumShareActivity, "Failed to install ${file.name}", Toast.LENGTH_SHORT).show()
                            cardAdbProgress.visibility = View.GONE
                        }
                        return@launch
                    }
                } else {
                    // For standard files or regular APKs, push them first
                    val success = AdbPushManager.pushFile(file, remoteDir) { bytesSent, totalBytes ->
                        val percent = if (totalBytes > 0) (bytesSent * 100 / totalBytes).toInt() else 0
                        val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                        val speedMBs = if (elapsedSeconds > 0) (bytesSent / (1024.0 * 1024.0)) / elapsedSeconds else 0.0
                        
                        runOnUiThread {
                            pbAdbTransfer.progress = percent
                            txtAdbProgressStatus.text = getString(R.string.transferring_file, file.name, percent, speedMBs)
                        }
                    }

                    if (!success) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PremiumShareActivity, "Failed to send ${file.name}", Toast.LENGTH_SHORT).show()
                            cardAdbProgress.visibility = View.GONE
                        }
                        return@launch
                    }

                    if (isInstall && isApk) {
                        withContext(Dispatchers.Main) {
                            txtAdbProgressStatus.text = getString(R.string.installing_apk, file.name)
                            pbAdbTransfer.isIndeterminate = true
                        }

                        installSuccess = AdbPushManager.installApk(file.name, remoteDir)
                    }
                }

                // If install requested, show result message
                if (isInstall) {
                    withContext(Dispatchers.Main) {
                        pbAdbTransfer.isIndeterminate = false
                        if (installSuccess) {
                            Toast.makeText(this@PremiumShareActivity, getString(R.string.installation_success), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@PremiumShareActivity, getString(R.string.installation_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@PremiumShareActivity, R.string.transfer_complete, Toast.LENGTH_SHORT).show()
                cardAdbProgress.visibility = View.GONE
                adbManager.disconnectExplicit()
                finish()
            }
        }
    }

    private fun setupWebShareMode() {
        layoutAdbPush.visibility = View.GONE
        layoutWebShare.visibility = View.VISIBLE

        val cleanUpOnStop = intent.getBooleanExtra("clean_up_on_stop", false)
        val url = WebShareServer.start(this, files, cleanUpOnStop)
        if (url.isBlank()) {
            // WebShareServer.start() returns "" when the embedded Ktor/Netty engine could not boot
            // (device verifier rejected the R8-optimized Netty bytecode, e.g. Android 16 / API 36).
            Log.e(TAG, "WebShare server failed to start")
            Toast.makeText(this, getString(R.string.remote_server_error), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        txtWebUrl.text = url
        txtWebPin.text = WebShareServer.pin

        // Generate and display QR Code off the main thread — QR generation is
        // CPU-heavy and can freeze the main thread for >5 s on low-end devices.
        lifecycleScope.launch(Dispatchers.Default) {
            val qrBitmap = QrCodeUtils.generateQrCode(url, 512)
            withContext(Dispatchers.Main) {
                if (qrBitmap != null) {
                    imgQrCode.setImageBitmap(qrBitmap)
                }
            }
        }

        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateConnectionInfo()
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                updateConnectionInfo()
            }
            override fun onLost(network: Network) {
                updateConnectionInfo()
            }
        }
        networkCallback = callback
        connectivityManager.registerNetworkCallback(request, callback)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
    }

    private fun updateConnectionInfo() {
        runOnUiThread {
            val ip = WebShareServer.getLocalIpAddress()
            val port = WebShareServer.port
            if (port > 0 && ip != "127.0.0.1") {
                val proto = if (WebShareServer.sslPort > 0) "https" else "http"
                val url = "$proto://$ip:$port"
                txtWebUrl.text = url
                // Regenerate the QR off the main thread (see setupWebShareMode).
                lifecycleScope.launch(Dispatchers.Default) {
                    val qr = QrCodeUtils.generateQrCode(url, 512)
                    withContext(Dispatchers.Main) {
                        if (qr != null) {
                            imgQrCode.setImageBitmap(qr)
                        }
                    }
                }
            }
        }
    }

    private fun handleExit() {
        if (targetType == "web") {
            WebShareServer.stop()
            unregisterNetworkCallback()
        }
        
        // Clean up temporary downloaded network files if flag set
        if (intent.getBooleanExtra("clean_up_on_stop", false)) {
            val filePaths = intent.getStringArrayListExtra("files") ?: emptyList()
            if (filePaths.isNotEmpty()) {
                val firstFile = File(filePaths[0])
                val parentDir = firstFile.parentFile
                if (parentDir != null && parentDir.name.startsWith("share_temp_")) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            parentDir.deleteRecursively()
                            Log.d(TAG, "Deleted temporary network files directory: ${parentDir.absolutePath}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error cleaning up temporary files directory", e)
                        }
                    }
                }
            }
        }

        finish()
    }

    override fun onBackPressed() {
        handleExit()
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (targetType == "web") {
            WebShareServer.stop()
            try { unregisterNetworkCallback() } catch (_: Exception) {}
        }
    }

    // RecyclerView Adapter for discovered ADB devices
    private class DevicesAdapter(private val onDeviceClick: (AdbDevice) -> Unit) :
        RecyclerView.Adapter<DevicesAdapter.DeviceViewHolder>() {

        private var deviceList = listOf<AdbDevice>()

        fun updateDevices(newList: List<AdbDevice>) {
            deviceList = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_discovered_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = deviceList[position]
            holder.bind(device)
            holder.itemView.setOnClickListener {
                onDeviceClick(device)
            }
        }

        override fun getItemCount(): Int = deviceList.size

        class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val txtDeviceName: TextView = itemView.findViewById(R.id.txtDeviceName)
            private val txtDeviceAddress: TextView = itemView.findViewById(R.id.txtDeviceAddress)

            fun bind(device: AdbDevice) {
                // Use build model name or standard TV description
                txtDeviceName.text = "Android TV Device"
                txtDeviceAddress.text = device.toString()
            }
        }
    }
}
