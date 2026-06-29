package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.TypedValue
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * TV Box OAuth 2.0 authentication using the Device Authorization Grant (RFC 8628).
 *
 * Reuses the OneDrive device code layout (activity_onedrive_device_code_auth_tv.xml).
 *
 * Flow:
 *  1. Request a device code from Box's OAuth token endpoint.
 *  2. Display user_code, verification_url, and QR code on screen.
 *  3. Poll the token endpoint until the user authorizes on another device.
 *  4. On success, return token JSON + email via intent result.
 *
 * NOTE: Reuses `activity_onedrive_device_code_auth_tv.xml` layout — if that
 * layout is modified for OneDrive-specific content, verify Box is not affected.
 */
class BoxDeviceCodeAuthActivity : AppCompatActivity() {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()

    private var countDownTimer: CountDownTimer? = null
    private var pollingActive = false

    companion object {
        const val EXTRA_TOKEN_JSON = "token_json"
        const val EXTRA_EMAIL = "email"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_onedrive_device_code_auth_tv)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            pollingActive = false
            finish()
        }
        findViewById<TextView>(R.id.txtAuthTitle).text = getString(R.string.box_device_code_title)
        findViewById<TextView>(R.id.txtStatus).text = getString(R.string.box_auth_waiting)

        startDeviceCodeFlow()
    }

    override fun onDestroy() {
        pollingActive = false
        countDownTimer?.cancel()
        super.onDestroy()
    }

    private data class DeviceCodeResponse(
        val device_code: String,
        val user_code: String,
        val verification_url: String,
        val expires_in: Int,
        val interval: Int = 5
    )

    private fun startDeviceCodeFlow() {
        findViewById<ProgressBar>(R.id.progressQr).visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = fetchDeviceCode()
                withContext(Dispatchers.Main) {
                    updateUiWithDeviceCode(response)
                    startPolling(response)
                }
            } catch (e: Exception) {
                GoRoLog.e("BoxDeviceAuth", "Failed to get device code", e)
                withContext(Dispatchers.Main) {
                    showAuthErrorDialog(getString(R.string.box_auth_failed, e.message ?: "Unknown error"))
                }
            }
        }
    }

    private suspend fun fetchDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", BoxOAuthConfig.CLIENT_ID)
            .add("scope", BoxOAuthConfig.SCOPE)
            .build()

        httpClient.newCall(
            Request.Builder()
                .url("https://api.box.com/oauth2/token")
                .post(formBody)
                .build()
        ).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("Device code request failed (${response.code}): $body")
            val json = gson.fromJson(body, JsonObject::class.java)
            DeviceCodeResponse(
                device_code = json.get("device_code").asString,
                user_code = json.get("user_code").asString,
                verification_url = json.get("verification_url").asString,
                expires_in = json.get("expires_in").asInt,
                interval = if (json.has("interval")) json.get("interval").asInt else 5
            )
        }
    }

    private fun updateUiWithDeviceCode(response: DeviceCodeResponse) {
        findViewById<ProgressBar>(R.id.progressQr).visibility = View.GONE
        findViewById<TextView>(R.id.txtVerificationUrl).text = response.verification_url
        findViewById<TextView>(R.id.txtUserCode).text = response.user_code

        // Generate QR code from the verification URL (240dp = the CardView size in the layout)
        val qrImage = findViewById<ImageView>(R.id.imgQrCode)
        val qrSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 240f, resources.displayMetrics).toInt()
        QrCodeUtils.generateQrCode(response.verification_url, qrSizePx)?.let { bitmap ->
            qrImage.setImageBitmap(bitmap)
            qrImage.visibility = View.VISIBLE
        }

        // Start countdown timer
        val expiryTextView = findViewById<TextView>(R.id.txtExpiry)
        countDownTimer = object : CountDownTimer(response.expires_in * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val minutes = seconds / 60
                val secs = seconds % 60
                expiryTextView.text = getString(
                    R.string.box_code_expires_in,
                    "%02d:%02d".format(minutes, secs)
                )
            }

            override fun onFinish() {
                expiryTextView.text = getString(R.string.box_code_expired)
                findViewById<TextView>(R.id.txtStatus).text = getString(R.string.box_code_expired)
                pollingActive = false
            }
        }.start()

        // Show polling indicator
        findViewById<View>(R.id.layoutPolling).visibility = View.VISIBLE
    }

    private fun startPolling(response: DeviceCodeResponse) {
        pollingActive = true
        lifecycleScope.launch(Dispatchers.IO) {
            while (pollingActive) {
                delay((response.interval * 1000L).coerceAtLeast(2000L))
                if (!pollingActive) break

                try {
                    val tokenResponse = pollForToken(response.device_code)
                    if (tokenResponse != null) {
                        pollingActive = false
                        withContext(Dispatchers.Main) {
                            handleAuthSuccess(tokenResponse)
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("authorization_pending") == true ||
                        e.message?.contains("slow_down") == true) {
                        // Continue polling — these are expected
                        continue
                    }
                    GoRoLog.e("BoxDeviceAuth", "Polling error", e)
                    // Don't stop on transient errors
                }
            }
        }
    }

    /**
     * Polls Box's token endpoint. Returns the token JSON on success, or null
     * if the user hasn't authorized yet (authorization_pending).
     */
    private suspend fun pollForToken(deviceCode: String): JsonObject? = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", BoxOAuthConfig.CLIENT_ID)
            .add("client_secret", BoxOAuthConfig.CLIENT_SECRET)
            .add("device_code", deviceCode)
            .build()

        httpClient.newCall(
            Request.Builder()
                .url("https://api.box.com/oauth2/token")
                .post(formBody)
                .build()
        ).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errorJson = try {
                    gson.fromJson(body, JsonObject::class.java)
                } catch (_: Exception) { null }
                val errorCode = errorJson?.get("error")?.asString ?: ""
                if (errorCode == "authorization_pending" || errorCode == "slow_down") {
                    return@use null
                }
                throw IOException("Token poll failed (${response.code}): $body")
            }
            gson.fromJson(body, JsonObject::class.java)
        }
    }

    private suspend fun handleAuthSuccess(tokenResponse: JsonObject) {
        countDownTimer?.cancel()
        val accessToken = tokenResponse.get("access_token").asString

        try {
            val userInfo = fetchUserInfo(accessToken)
            val email = userInfo.get("login").asString
            val tokenJson = gson.toJson(tokenResponse)

            GoRoLog.d("BoxDeviceAuth", "Auth success for $email")
            Toast.makeText(this@BoxDeviceCodeAuthActivity, "Connected to $email", Toast.LENGTH_SHORT).show()

            val resultIntent = Intent().apply {
                putExtra(EXTRA_TOKEN_JSON, tokenJson)
                putExtra(EXTRA_EMAIL, email)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            GoRoLog.e("BoxDeviceAuth", "Userinfo failed after auth success", e)
            withContext(Dispatchers.Main) {
                showAuthErrorDialog(getString(R.string.box_auth_failed, e.message ?: "User info error"))
            }
        }
    }

    private suspend fun fetchUserInfo(accessToken: String): JsonObject = withContext(Dispatchers.IO) {
        httpClient.newCall(
            Request.Builder()
                .url("https://api.box.com/2.0/users/me")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Userinfo failed: ${response.code}")
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        }
    }

    private fun showAuthErrorDialog(message: String) {
        if (isFinishing || isDestroyed) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_policy_blocked_tv, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setCancelable(true)
            .setView(dialogView)
            .create()

        dialogView.findViewById<TextView>(R.id.txtPolicyDetails)?.text = message
        dialogView.findViewById<View>(R.id.btnPolicyOk).setOnClickListener {
            dialog.dismiss()
            finish()
        }

        val btnOk = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPolicyOk)
        btnOk.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundColor(getColor(R.color.tv_button_focused_yellow))
                (v as com.google.android.material.button.MaterialButton).setTextColor(getColor(R.color.tv_button_focused_yellow_text))
            } else {
                v.setBackgroundColor(getColor(R.color.tv_glass_white_10))
                (v as com.google.android.material.button.MaterialButton).setTextColor(getColor(R.color.tv_text_primary))
            }
        }
        btnOk.requestFocus()
        dialog.show()
    }
}
