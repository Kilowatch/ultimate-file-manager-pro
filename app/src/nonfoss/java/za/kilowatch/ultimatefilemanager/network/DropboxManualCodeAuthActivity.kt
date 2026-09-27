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

import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import android.net.Uri

class DropboxManualCodeAuthActivity : AppCompatActivity() {

    companion object {
        private const val KEY_CODE_VERIFIER = "extra_code_verifier"
        private const val MIN_REASONABLE_TIME_MILLIS = 1735689600000L // 2025-01-01 00:00:00 UTC
    }

    private val gson = Gson()
    private var codeVerifier: String = ""

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CODE_VERIFIER, codeVerifier)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            codeVerifier = savedInstanceState.getString(KEY_CODE_VERIFIER, "")
        }
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
            val code = edtAuthCode.text.toString().trim().replace("\\s+".toRegex(), "")
            if (code.isNotEmpty()) {
                if (System.currentTimeMillis() < MIN_REASONABLE_TIME_MILLIS) {
                    showAuthErrorDialog(
                        errorMessage = getString(R.string.dropbox_auth_clock_warning),
                        technicalDetails = "System time skew detected: ${java.util.Date()}",
                        isPolicyBlocked = false
                    )
                    return@setOnClickListener
                }
                btnVerify.isEnabled = false
                btnVerify.text = getString(R.string.dropbox_auth_verifying)
                verifyCode(code)
            }
        }
    }

    private fun setupUrlAndQrCode() {
        if (codeVerifier.isBlank()) {
            codeVerifier = generateCodeVerifier()
        }
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
                val isPolicy = e.message?.contains("policy", ignoreCase = true) == true ||
                               e.message?.contains("restricted", ignoreCase = true) == true
                showAuthErrorDialog(
                    errorMessage = if (isPolicy) getString(R.string.policy_blocked_message) else getString(R.string.dropbox_auth_failed_help),
                    technicalDetails = e.message ?: e.javaClass.simpleName,
                    isPolicyBlocked = isPolicy
                )
                val btnVerify = findViewById<MaterialButton>(R.id.btnVerify)
                btnVerify.isEnabled = true
                btnVerify.text = "Connect"
            }
        }
    }

    private suspend fun exchangeCode(code: String): JsonObject = withContext(Dispatchers.IO) {
        val formParams = mapOf(
            "code" to code,
            "client_id" to BuildConfig.DROPBOX_APP_KEY,
            "client_secret" to BuildConfig.DROPBOX_APP_SECRET,
            "grant_type" to "authorization_code",
            "code_verifier" to codeVerifier
        )
        val response = UfmHttpClient.postFormSync(
            "https://api.dropboxapi.com/oauth2/token",
            headers = emptyMap(),
            formFields = formParams
        )
        val body = response.bodyString
        if (!response.isSuccessful) {
            var detail = body
            try {
                val json = gson.fromJson(body, JsonObject::class.java)
                val err = json.get("error")?.asString
                val desc = json.get("error_description")?.asString
                if (!err.isNullOrBlank()) {
                    detail = if (!desc.isNullOrBlank()) "$err: $desc" else err
                }
            } catch (_: Exception) {}
            throw IOException("Token exchange failed (${response.statusCode}): $detail")
        }
        gson.fromJson(body, JsonObject::class.java)
    }

    private suspend fun fetchUserInfo(accessToken: String): JsonObject = withContext(Dispatchers.IO) {
        val response = UfmHttpClient.postSync(
            "https://api.dropboxapi.com/2/users/get_current_account",
            headers = mapOf("Authorization" to "Bearer $accessToken"),
            bodyBytes = ByteArray(0)
        )
        if (!response.isSuccessful) throw IOException("Userinfo failed: ${response.statusCode}")
        gson.fromJson(response.bodyString, JsonObject::class.java)
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

    private fun showAuthErrorDialog(
        errorMessage: String,
        technicalDetails: String? = null,
        isPolicyBlocked: Boolean = false
    ) {
        if (isFinishing || isDestroyed) return

        val layoutId = if (isPolicyBlocked) R.layout.dialog_policy_blocked_tv else R.layout.dialog_auth_error_tv
        val dialogView = layoutInflater.inflate(layoutId, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setCancelable(true)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (isPolicyBlocked) {
            dialogView.findViewById<TextView>(R.id.txtPolicyMessage)?.text = errorMessage
            val txtDetails = dialogView.findViewById<TextView>(R.id.txtPolicyDetails)
            if (!technicalDetails.isNullOrBlank()) {
                txtDetails?.text = technicalDetails
                txtDetails?.visibility = View.VISIBLE
            } else {
                txtDetails?.visibility = View.GONE
            }
            val btnOk = dialogView.findViewById<MaterialButton>(R.id.btnPolicyOk)
            btnOk.setOnClickListener { dialog.dismiss() }
            setupButtonFocus(btnOk)
            btnOk.requestFocus()
        } else {
            dialogView.findViewById<TextView>(R.id.txtErrorMessage)?.text = errorMessage
            val txtDetails = dialogView.findViewById<TextView>(R.id.txtErrorDetails)
            if (!technicalDetails.isNullOrBlank()) {
                txtDetails?.text = technicalDetails
                dialogView.findViewById<View>(R.id.scrollDetails)?.visibility = View.VISIBLE
            } else {
                dialogView.findViewById<View>(R.id.scrollDetails)?.visibility = View.GONE
            }
            val btnOk = dialogView.findViewById<MaterialButton>(R.id.btnErrorOk)
            btnOk.setOnClickListener { dialog.dismiss() }
            setupButtonFocus(btnOk)
            btnOk.requestFocus()
        }

        dialog.show()
    }

    private fun setupButtonFocus(btn: MaterialButton) {
        btn.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundColor(ColorblindPalette.focusFill(this))
                (v as MaterialButton).setTextColor(ColorblindPalette.focusFillText(this))
            } else {
                v.setBackgroundColor(getColor(R.color.tv_glass_white_10))
                (v as MaterialButton).setTextColor(getColor(R.color.tv_text_primary))
            }
        }
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
