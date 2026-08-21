package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.JsonObject
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

class DropboxAuthActivity : AppCompatActivity() {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()

    private var codeVerifier: String = ""
    private var isTv = false

    companion object {
        val REDIRECT_URI: String get() = "db-${BuildConfig.DROPBOX_APP_KEY}://authorize"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_add_online_storage_tv
            else R.layout.activity_add_online_storage
        )
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // Only start auth if we were not launched by a redirect (no URI data)
        if (intent?.data == null) {
            startOAuth()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.data ?: return
        GoRoLog.d("DropboxAuth", "onNewIntent: $data")
        if (data.scheme == "db-${BuildConfig.DROPBOX_APP_KEY}") {
            handleRedirect(data)
        }
    }

    private fun startOAuth() {
        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val clientId = BuildConfig.DROPBOX_APP_KEY

        val authUri = Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id",             clientId)
            .appendQueryParameter("redirect_uri",          REDIRECT_URI)
            .appendQueryParameter("response_type",         "code")
            .appendQueryParameter("token_access_type",     "offline")
            .appendQueryParameter("code_challenge",        codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        GoRoLog.d("DropboxAuth", "=== OAuth Request ===")
        GoRoLog.d("DropboxAuth", "clientId   : $clientId")
        GoRoLog.d("DropboxAuth", "redirectUri: $REDIRECT_URI")
        GoRoLog.d("DropboxAuth", "authUri    : $authUri")
        CustomTabsIntent.Builder().build().launchUrl(this, authUri)
    }

    private fun handleRedirect(data: Uri) {
        val error = data.getQueryParameter("error")
        if (error != null) {
            showAuthErrorDialog("OAuth error: $error")
            return
        }
        // Sometimes Dropbox passes the code back using the 'oauth_token' or 'code' query parameter
        val code = data.getQueryParameter("code") ?: data.getQueryParameter("oauth_token") ?: run { finish(); return }

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
                OnlineStorageRepository.getInstance(this@DropboxAuthActivity).save(newStorage)

                GoRoLog.d("DropboxAuth", "Auth success for $email")
                Toast.makeText(this@DropboxAuthActivity, getString(R.string.dropbox_auth_success), Toast.LENGTH_SHORT).show()

                startActivity(
                    Intent(this@DropboxAuthActivity, OnlineStorageManagerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            } catch (e: Exception) {
                GoRoLog.e("DropboxAuth", "Token exchange failed", e)
                showAuthErrorDialog(getString(R.string.dropbox_auth_failed, e.message))
            }
        }
    }

    private suspend fun exchangeCode(code: String): JsonObject = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("code",          code)
            .add("client_id",     BuildConfig.DROPBOX_APP_KEY)
            .add("client_secret", BuildConfig.DROPBOX_APP_SECRET)
            .add("redirect_uri",  REDIRECT_URI)
            .add("grant_type",    "authorization_code")
            .add("code_verifier", codeVerifier)
            .build()

        httpClient.newCall(
            Request.Builder()
                .url("https://api.dropboxapi.com/oauth2/token")
                .post(formBody)
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

    private fun showAuthErrorDialog(message: String) {
        if (isFinishing || isDestroyed) return

        val layoutId = if (isTv) R.layout.dialog_policy_blocked_tv else R.layout.dialog_policy_blocked
        val dialogView = layoutInflater.inflate(layoutId, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setCancelable(true)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.txtPolicyDetails)?.text = message
        dialogView.findViewById<View>(R.id.btnPolicyOk).setOnClickListener {
            dialog.dismiss()
            finish()
        }

        if (isTv) {
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
        }
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
