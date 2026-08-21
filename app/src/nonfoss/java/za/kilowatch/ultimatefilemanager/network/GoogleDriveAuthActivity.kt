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

/**
 * Mobile Google Drive OAuth2 authentication using PKCE + Chrome Custom Tabs.
 *
 * Uses the Android OAuth client's reverse-scheme redirect URI
 * (com.googleusercontent.apps.<clientId>:/) which is automatically
 * authorised for Android-type OAuth clients without any Cloud Console config.
 *
 * Debug builds use the debug Android client (SHA-1 from debug.keystore).
 * Release builds use the production Android client (SHA-1 from UFM_GooglePlay).
 * Both client IDs are injected via BuildConfig.GOOGLE_DRIVE_MOBILE_CLIENT_ID.
 *
 * Flow:
 *  1. Generate PKCE code_verifier / code_challenge.
 *  2. Launch Chrome Custom Tab to accounts.google.com/o/oauth2/v2/auth.
 *  3. Google redirects to <reverseScheme>:/?code=... — caught by intent-filter.
 *  4. onNewIntent exchanges the code for tokens + fetches email.
 *  5. Saves OnlineStorage and navigates to OnlineStorageManagerActivity.
 */
class GoogleDriveAuthActivity : AppCompatActivity() {

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
        const val GDRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/userinfo.email"

        /** Reverse of the client ID, used as the redirect URI scheme. */
        private val reverseScheme: String get() =
            "com.googleusercontent.apps." +
                BuildConfig.GOOGLE_DRIVE_MOBILE_CLIENT_ID.removeSuffix(".apps.googleusercontent.com")

        val REDIRECT_URI: String get() = "$reverseScheme:/"
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
        GoRoLog.d("GDriveAuth", "onNewIntent: $data")
        if (data.scheme == reverseScheme) {
            handleRedirect(data)
        }
    }

    private fun startOAuth() {
        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val clientId = BuildConfig.GOOGLE_DRIVE_MOBILE_CLIENT_ID

        val authUri = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
            .appendQueryParameter("client_id",             clientId)
            .appendQueryParameter("redirect_uri",          REDIRECT_URI)
            .appendQueryParameter("response_type",         "code")
            .appendQueryParameter("scope",                 GDRIVE_SCOPE)
            .appendQueryParameter("access_type",           "offline")
            .appendQueryParameter("prompt",                "select_account consent")
            .appendQueryParameter("code_challenge",        codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        GoRoLog.d("GDriveAuth", "=== OAuth Request ===")
        GoRoLog.d("GDriveAuth", "clientId   : $clientId")
        GoRoLog.d("GDriveAuth", "redirectUri: $REDIRECT_URI")
        GoRoLog.d("GDriveAuth", "authUri    : $authUri")
        CustomTabsIntent.Builder().build().launchUrl(this, authUri)
    }

    private fun handleRedirect(data: Uri) {
        val error = data.getQueryParameter("error")
        if (error != null) {
            showAuthErrorDialog("OAuth error: $error")
            return
        }
        val code = data.getQueryParameter("code") ?: run { finish(); return }

        lifecycleScope.launch {
            try {
                val tokenResponse = exchangeCode(code)
                val accessToken  = tokenResponse.get("access_token").asString
                val refreshToken = tokenResponse.get("refresh_token")?.asString

                val userInfo = fetchUserInfo(accessToken)
                val email    = userInfo.get("email").asString
                val name     = userInfo.get("name")?.asString ?: email

                val newStorage = OnlineStorage(
                    provider     = OnlineStorageProvider.GOOGLE_DRIVE,
                    email        = email,
                    displayName  = "Google Drive ($name)",
                    refreshToken = refreshToken
                )
                OnlineStorageRepository.getInstance(this@GoogleDriveAuthActivity).save(newStorage)

                // Seed the access token so the first browse uses it directly
                // instead of attempting a refresh (which can transiently fail
                // for newly issued tokens).
                val expiresIn = tokenResponse.get("expires_in")?.asLong ?: 3600L
                GoogleDriveShareClient.seedAccessToken(email, accessToken, expiresIn)

                GoRoLog.d("GDriveAuth", "Auth success for $email")
                Toast.makeText(this@GoogleDriveAuthActivity, "Connected to $email", Toast.LENGTH_SHORT).show()

                startActivity(
                    Intent(this@GoogleDriveAuthActivity, OnlineStorageManagerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            } catch (e: Exception) {
                GoRoLog.e("GDriveAuth", "Token exchange failed", e)
                showAuthErrorDialog("Auth failed: ${e.message}")
            }
        }
    }

    private suspend fun exchangeCode(code: String): JsonObject = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("code",          code)
            .add("client_id",     BuildConfig.GOOGLE_DRIVE_MOBILE_CLIENT_ID)
            .add("redirect_uri",  REDIRECT_URI)
            .add("grant_type",    "authorization_code")
            .add("code_verifier", codeVerifier)
            .build()

        httpClient.newCall(
            Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(formBody)
                .build()
        ).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("Token exchange failed (${response.code}): $body")
            gson.fromJson(body, JsonObject::class.java)
        }
    }

    private suspend fun fetchUserInfo(accessToken: String): JsonObject = withContext(Dispatchers.IO) {
        httpClient.newCall(
            Request.Builder()
                .url("https://www.googleapis.com/oauth2/v3/userinfo")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Userinfo failed: ${response.code}")
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        }
    }

    private fun showAuthErrorDialog(message: String) {
        Toast.makeText(this, R.string.online_storages_policy_blocked_toast, Toast.LENGTH_LONG).show()
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
