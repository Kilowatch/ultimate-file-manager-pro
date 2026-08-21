package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import android.net.Uri

class DropboxManualCodeAuthActivity : AppCompatActivity() {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()
    private var codeVerifier: String = ""

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dropbox_manual_code_auth_tv)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val btnVerify = findViewById<MaterialButton>(R.id.btnVerify)
        val edtAuthCode = findViewById<EditText>(R.id.edtAuthCode)

        setupUrlAndQrCode()

        btnVerify.setOnClickListener {
            val code = edtAuthCode.text.toString().trim()
            if (code.isNotEmpty()) {
                btnVerify.isEnabled = false
                btnVerify.text = getString(R.string.dropbox_auth_verifying)
                verifyCode(code)
            }
        }
    }

    private fun setupUrlAndQrCode() {
        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val clientId = BuildConfig.DROPBOX_APP_KEY

        val authUri = Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id",             clientId)
            .appendQueryParameter("response_type",         "code")
            .appendQueryParameter("token_access_type",     "offline")
            .appendQueryParameter("code_challenge",        codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        val urlString = authUri.toString()
        findViewById<TextView>(R.id.txtVerificationUrl).text = urlString

        // Generate QR code
        lifecycleScope.launch(Dispatchers.Default) {
            val bitmap = generateQrCode(urlString)
            withContext(Dispatchers.Main) {
                findViewById<ImageView>(R.id.imgQrCode).setImageBitmap(bitmap)
            }
        }
    }

    private fun verifyCode(code: String) {
        lifecycleScope.launch {
            try {
                val tokenResponse = exchangeCode(code)
                val accessToken  = tokenResponse.get("access_token").asString
                val refreshToken = tokenResponse.get("refresh_token")?.asString

                val userInfo = fetchUserInfo(accessToken)
                val email    = userInfo.get("email").asString
                val nameObj  = userInfo.get("name").asJsonObject
                val name     = nameObj.get("display_name")?.asString ?: email

                val newStorage = OnlineStorage(
                    provider     = OnlineStorageProvider.DROPBOX,
                    email        = email,
                    displayName  = "Dropbox ($name)",
                    refreshToken = refreshToken
                )
                OnlineStorageRepository.getInstance(this@DropboxManualCodeAuthActivity).save(newStorage)

                GoRoLog.d("DropboxAuthTV", "Auth success for $email")
                Toast.makeText(this@DropboxManualCodeAuthActivity, getString(R.string.dropbox_auth_success), Toast.LENGTH_SHORT).show()

                startActivity(
                    Intent(this@DropboxManualCodeAuthActivity, OnlineStorageManagerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            } catch (e: Exception) {
                GoRoLog.e("DropboxAuthTV", "Code exchange failed", e)
                showAuthErrorDialog(getString(R.string.dropbox_auth_failed, e.message))
                val btnVerify = findViewById<MaterialButton>(R.id.btnVerify)
                btnVerify.isEnabled = true
                btnVerify.text = "Connect"
            }
        }
    }

    private suspend fun exchangeCode(code: String): JsonObject = withContext(Dispatchers.IO) {
        val formBodyBuilder = FormBody.Builder()
            .add("code",          code)
            .add("client_id",     BuildConfig.DROPBOX_APP_KEY)
            .add("client_secret", BuildConfig.DROPBOX_APP_SECRET)
            .add("grant_type",    "authorization_code")
            .add("code_verifier", codeVerifier)

        httpClient.newCall(
            Request.Builder()
                .url("https://api.dropboxapi.com/oauth2/token")
                .post(formBodyBuilder.build())
                .build()
        ).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("Token exchange failed (${response.code}): $body")
            gson.fromJson(body, JsonObject::class.java)
        }
    }

    private suspend fun fetchUserInfo(accessToken: String): JsonObject = withContext(Dispatchers.IO) {
        val emptyBody = ByteArray(0).toRequestBody(null)
        httpClient.newCall(
            Request.Builder()
                .url("https://api.dropboxapi.com/2/users/get_current_account")
                .header("Authorization", "Bearer $accessToken")
                .post(emptyBody)
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Userinfo failed: ${response.code}")
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        }
    }

    private fun generateQrCode(text: String): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            GoRoLog.e("DropboxAuthTV", "Error generating QR code", e)
            null
        }
    }

    private fun showAuthErrorDialog(message: String) {
        if (isFinishing || isDestroyed) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_policy_blocked_tv, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setCancelable(true)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.txtPolicyDetails)?.text = message
        dialogView.findViewById<View>(R.id.btnPolicyOk).setOnClickListener {
            dialog.dismiss()
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

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
