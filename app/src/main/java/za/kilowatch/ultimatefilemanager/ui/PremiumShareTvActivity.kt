package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.WebShareServer
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.QrCodeUtils
import java.io.File

class PremiumShareTvActivity : AppCompatActivity() {

    private val TAG = "PremiumShareTvActivity"

    private lateinit var files: List<File>

    // UI views
    private lateinit var btnBack: ImageView
    private lateinit var txtWebUrl: TextView
    private lateinit var txtPinCode: TextView
    private lateinit var btnStopWebShare: MaterialButton
    private lateinit var imgQrCode: ImageView
    private lateinit var txtStatus: TextView

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_premium_share_tv)

        // Parse files
        val filePaths = intent.getStringArrayListExtra("files") ?: emptyList()
        files = filePaths.map { File(it) }

        if (files.isEmpty()) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        setupWebShareMode()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        txtWebUrl = findViewById(R.id.txtWebUrl)
        txtPinCode = findViewById(R.id.txtPinCode)
        btnStopWebShare = findViewById(R.id.btnStopWebShare)
        imgQrCode = findViewById(R.id.imgQrCode)
        txtStatus = findViewById(R.id.txtStatus)

        // Set initial focus for TV D-pad accessibility
        btnStopWebShare.requestFocus()
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            handleExit()
        }

        btnStopWebShare.setOnClickListener {
            handleExit()
        }
    }

    private fun setupWebShareMode() {
        val cleanUpOnStop = intent.getBooleanExtra("clean_up_on_stop", false)
        val url = WebShareServer.start(this, files, cleanUpOnStop)
        txtWebUrl.text = url
        txtPinCode.text = WebShareServer.pin

        // Generate and display QR Code
        val qrBitmap = QrCodeUtils.generateQrCode(url, 512)
        if (qrBitmap != null) {
            imgQrCode.setImageBitmap(qrBitmap)
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
                val qr = QrCodeUtils.generateQrCode(url, 512)
                if (qr != null) {
                    imgQrCode.setImageBitmap(qr)
                }
            }
        }
    }

    private fun handleExit() {
        WebShareServer.stop()
        unregisterNetworkCallback()
        
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
        WebShareServer.stop()
        try { unregisterNetworkCallback() } catch (_: Exception) {}
    }
}
