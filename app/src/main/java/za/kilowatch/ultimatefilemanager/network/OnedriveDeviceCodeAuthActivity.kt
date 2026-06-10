package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.util.QrCodeUtils
import java.io.IOException
import java.util.concurrent.TimeUnit

class OnedriveDeviceCodeAuthActivity : AppCompatActivity() {

    private lateinit var txtUserCode: TextView
    private lateinit var txtVerificationUrl: TextView
    private lateinit var imgQrCode: ImageView
    private lateinit var progressQr: ProgressBar
    private lateinit var layoutPolling: View
    private lateinit var txtStatus: TextView
    private lateinit var txtExpiry: TextView
    private lateinit var btnBack: ImageView

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()
    private val clientId = "1c135efb-c510-42a7-a7a6-32d29ab38d19"
    private val scopes = "Files.ReadWrite User.Read offline_access"

    private var pollingJob: Job? = null
    private var expiryTimer: CountDownTimer? = null
    private lateinit var repository: OnlineStorageRepository

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_onedrive_device_code_auth_tv
            else R.layout.activity_onedrive_device_code_auth
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        repository = OnlineStorageRepository.getInstance(this)

        txtUserCode = findViewById(R.id.txtUserCode)
        txtVerificationUrl = findViewById(R.id.txtVerificationUrl)
        imgQrCode = findViewById(R.id.imgQrCode)
        progressQr = findViewById(R.id.progressQr)
        layoutPolling = findViewById(R.id.layoutPolling)
        txtStatus = findViewById(R.id.txtStatus)
        txtExpiry = findViewById(R.id.txtExpiry)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }
        btnBack.requestFocus()

        txtVerificationUrl.setOnClickListener {
            val url = txtVerificationUrl.text.toString()
            val targetUrl = if (url.startsWith("http", ignoreCase = true)) url else "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(targetUrl))
            startActivity(intent)
        }

        txtUserCode.setOnClickListener {
            val code = txtUserCode.text.toString()
            if (code.isNotBlank() && code != "---- ----") {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("OneDrive Code", code)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@OnedriveDeviceCodeAuthActivity, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        startDeviceCodeFlow()
    }

    private fun startDeviceCodeFlow() {
        lifecycleScope.launch {
            try {
                val deviceCodeResponse = fetchDeviceCode()
                updateUiWithDeviceCode(deviceCodeResponse)
                startPolling(deviceCodeResponse)
            } catch (e: Exception) {
                GoRoLog.e("GoRoAuth", "DeviceCodeFlow failed", e)
                Toast.makeText(this@OnedriveDeviceCodeAuthActivity, getString(R.string.onedrive_auth_failed, e.message), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private suspend fun fetchDeviceCode(): JsonObject = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scopes)
            .build()

        val request = Request.Builder()
            .url("https://login.microsoftonline.com/common/oauth2/v2.0/devicecode")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        }
    }

    private fun updateUiWithDeviceCode(response: JsonObject) {
        val userCode = response.get("user_code").asString
        val verificationUrl = response.get("verification_uri").asString
        val expiresIn = response.get("expires_in").asLong

        txtUserCode.text = userCode
        txtVerificationUrl.text = verificationUrl

        // Generate QR Code
        lifecycleScope.launch(Dispatchers.Default) {
            val qrBitmap = QrCodeUtils.generateQrCode(verificationUrl, 512)
            withContext(Dispatchers.Main) {
                if (qrBitmap != null) {
                    imgQrCode.setImageBitmap(qrBitmap)
                    progressQr.visibility = View.GONE
                }
            }
        }

        // Start Expiry Timer
        expiryTimer?.cancel()
        expiryTimer = object : CountDownTimer(expiresIn * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                txtExpiry.text = getString(R.string.onedrive_code_expires_in, String.format("%02d:%02d", minutes, seconds))
            }

            override fun onFinish() {
                txtExpiry.text = getString(R.string.onedrive_code_expired)
                layoutPolling.visibility = View.GONE
            }
        }.start()
    }

    private fun startPolling(response: JsonObject) {
        val deviceCode = response.get("device_code").asString
        val interval = response.get("interval")?.asLong ?: 5L

        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                delay(interval * 1000)
                try {
                    val tokenResponse = pollForToken(deviceCode)
                    if (tokenResponse != null) {
                        handleAuthSuccess(tokenResponse)
                        break
                    }
                } catch (e: Exception) {
                    GoRoLog.d("GoRoAuth", "Polling error (expected if waiting): ${e.message}")
                }
            }
        }
    }

    private suspend fun pollForToken(deviceCode: String): JsonObject? = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .build()

        val request = Request.Builder()
            .url("https://login.microsoftonline.com/common/oauth2/v2.0/token")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            
            if (response.isSuccessful) return@withContext json
            
            val error = json.get("error").asString
            if (error == "authorization_pending") return@withContext null
            
            throw IOException("Token poll failed: $error")
        }
    }

    private fun handleAuthSuccess(tokenResponse: JsonObject) {
        val refreshToken = tokenResponse.get("refresh_token").asString
        val accessToken = tokenResponse.get("access_token").asString
        
        // We need the user's email/username. Graph API /me can provide it.
        lifecycleScope.launch {
            try {
                val userProfile = fetchUserProfile(accessToken)
                val email = userProfile.get("userPrincipalName").asString
                val displayName = userProfile.get("displayName").asString
                
                val newStorage = OnlineStorage(
                    provider = OnlineStorageProvider.ONEDRIVE,
                    email = email,
                    displayName = "${getString(R.string.add_online_storage_onedrive)} ($displayName)",
                    refreshToken = refreshToken
                )
                repository.save(newStorage)
                
                Toast.makeText(this@OnedriveDeviceCodeAuthActivity, getString(R.string.onedrive_auth_success, email), Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                GoRoLog.e("GoRoAuth", "Failed to fetch user profile", e)
                Toast.makeText(this@OnedriveDeviceCodeAuthActivity, getString(R.string.onedrive_auth_profile_failed), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private suspend fun fetchUserProfile(accessToken: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://graph.microsoft.com/v1.0/me")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to fetch profile: ${response.code}")
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        expiryTimer?.cancel()
    }
}
