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
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.util.QrCodeUtils
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TV-only Google Drive authentication via the Device Authorization Grant.
 *
 * Mirrors [OnedriveDeviceCodeAuthActivity] but uses Google's device code endpoints:
 *  - Init:  POST https://oauth2.googleapis.com/device/code
 *  - Poll:  POST https://oauth2.googleapis.com/token
 *  - Info:  GET  https://www.googleapis.com/oauth2/v3/userinfo
 *
 * Reuses [R.layout.activity_onedrive_device_code_auth_tv] (same view IDs).
 */
class GoogleDriveDeviceCodeAuthActivity : AppCompatActivity() {

    private lateinit var txtUserCode: TextView
    private lateinit var txtVerificationUrl: TextView
    private lateinit var imgQrCode: ImageView
    private lateinit var progressQr: ProgressBar
    private lateinit var layoutPolling: View
    private lateinit var txtStatus: TextView
    private lateinit var txtExpiry: TextView
    private lateinit var btnBack: ImageView

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()
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
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
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
        txtUserCode      = findViewById(R.id.txtUserCode)
        txtVerificationUrl = findViewById(R.id.txtVerificationUrl)
        imgQrCode        = findViewById(R.id.imgQrCode)
        progressQr       = findViewById(R.id.progressQr)
        layoutPolling    = findViewById(R.id.layoutPolling)
        txtStatus        = findViewById(R.id.txtStatus)
        txtExpiry        = findViewById(R.id.txtExpiry)
        btnBack          = findViewById(R.id.btnBack)
        findViewById<TextView>(R.id.txtAuthTitle).setText(R.string.add_online_storage_auth_gdrive)

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
                val clip = android.content.ClipData.newPlainText("Google Drive Code", code)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@GoogleDriveDeviceCodeAuthActivity, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
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
                GoRoLog.e("GDriveAuth", "GDrive DeviceCodeFlow failed", e)
                Toast.makeText(
                    this@GoogleDriveDeviceCodeAuthActivity,
                    getString(R.string.onedrive_auth_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private suspend fun fetchDeviceCode(): JsonObject = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", za.kilowatch.ultimatefilemanager.BuildConfig.GOOGLE_DRIVE_TV_CLIENT_ID)
            .add("scope", "https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/userinfo.email")
            .build()

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/device/code")
            .post(formBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "(empty)"
            GoRoLog.d("GDriveAuth", "device/code response ${response.code}: $body")
            if (!response.isSuccessful) throw IOException("GDrive device code request failed: ${response.code} $body")
            gson.fromJson(body, JsonObject::class.java)
        }
    }

    private fun updateUiWithDeviceCode(response: JsonObject) {
        val userCode        = response.get("user_code").asString
        val verificationUrl = response.get("verification_url").asString
        val expiresIn       = response.get("expires_in").asLong

        txtUserCode.text       = userCode
        txtVerificationUrl.text = verificationUrl

        lifecycleScope.launch(Dispatchers.Default) {
            val qrBitmap: Bitmap? = QrCodeUtils.generateQrCode(verificationUrl, 512)
            withContext(Dispatchers.Main) {
                if (qrBitmap != null) {
                    imgQrCode.setImageBitmap(qrBitmap)
                    progressQr.visibility = View.GONE
                }
            }
        }

        expiryTimer?.cancel()
        expiryTimer = object : CountDownTimer(expiresIn * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                txtExpiry.text = getString(
                    R.string.onedrive_code_expires_in,
                    String.format("%02d:%02d", minutes, seconds)
                )
            }
            override fun onFinish() {
                txtExpiry.text = getString(R.string.onedrive_code_expired)
                layoutPolling.visibility = View.GONE
            }
        }.start()
    }

    private fun startPolling(response: JsonObject) {
        val deviceCode = response.get("device_code").asString
        val interval   = response.get("interval")?.asLong ?: 5L

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
                    GoRoLog.d("GDriveAuth", "GDrive polling (expected if pending): ${e.message}")
                }
            }
        }
    }

    private suspend fun pollForToken(deviceCode: String): JsonObject? = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id",     za.kilowatch.ultimatefilemanager.BuildConfig.GOOGLE_DRIVE_TV_CLIENT_ID)
            .add("client_secret", za.kilowatch.ultimatefilemanager.BuildConfig.GOOGLE_DRIVE_TV_CLIENT_SECRET)
            .add("device_code",   deviceCode)
            .build()

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(formBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body  = response.body?.string() ?: ""
            val json  = gson.fromJson(body, JsonObject::class.java)
            if (response.isSuccessful) return@withContext json
            val error = json.get("error")?.asString
            if (error == "authorization_pending" || error == "slow_down") return@withContext null
            throw IOException("GDrive token poll failed: $error")
        }
    }

    private fun handleAuthSuccess(tokenResponse: JsonObject) {
        val refreshToken = tokenResponse.get("refresh_token")?.asString
        val accessToken  = tokenResponse.get("access_token").asString

        lifecycleScope.launch {
            try {
                val userInfo = fetchUserInfo(accessToken)
                val email    = userInfo.get("email").asString
                val name     = userInfo.get("name")?.asString ?: email

                val newStorage = OnlineStorage(
                    provider     = OnlineStorageProvider.GOOGLE_DRIVE,
                    email        = email,
                    displayName  = "Google Drive ($name)",
                    refreshToken = refreshToken
                )
                repository.save(newStorage)

                // Seed the access token so the first browse uses it directly
                // instead of attempting a refresh (which can transiently fail
                // for newly issued tokens).
                val expiresIn = tokenResponse.get("expires_in")?.asLong ?: 3600L
                GoogleDriveShareClient.seedAccessToken(email, accessToken, expiresIn)

                Toast.makeText(
                    this@GoogleDriveDeviceCodeAuthActivity,
                    getString(R.string.onedrive_auth_success, email),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (e: Exception) {
                GoRoLog.e("GDriveAuth", "GDrive: failed to fetch user info", e)
                Toast.makeText(
                    this@GoogleDriveDeviceCodeAuthActivity,
                    getString(R.string.onedrive_auth_profile_failed),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private suspend fun fetchUserInfo(accessToken: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v3/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GDrive userinfo failed: ${response.code}")
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        expiryTimer?.cancel()
    }
}
