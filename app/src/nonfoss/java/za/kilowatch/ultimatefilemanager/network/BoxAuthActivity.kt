package za.kilowatch.ultimatefilemanager.network

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Mobile Box OAuth 2.0 authentication using an embedded WebView.
 *
 * Uses rclone's built-in Box OAuth credentials — no user registration needed.
 * The WebView intercepts the redirect to http://127.0.0.1:53682/ (rclone's
 * registered redirect URI) before it loads, extracting the auth code locally.
 *
 * Flow:
 *  1. Open WebView to account.box.com/api/oauth2/authorize with rclone credentials.
 *  2. User logs in to Box and grants access.
 *  3. Box redirects to http://127.0.0.1:53682/?code=... — intercepted by WebViewClient.
 *  4. Exchange the auth code for OAuth tokens via Box's token endpoint.
 *  5. Fetch user info (email) from Box's /users/me endpoint.
 *  6. Return token JSON + email via intent result to RCloneProviderActivity.
 *
 * No intent filter or custom scheme needed — the redirect is handled entirely
 * inside the WebView.
 */
class BoxAuthActivity : AppCompatActivity() {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()

    companion object {
        const val EXTRA_TOKEN_JSON = "token_json"
        const val EXTRA_EMAIL = "email"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_box_auth)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        val webView = findViewById<WebView>(R.id.webview)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            saveFormData = false
            savePassword = false
        }

        // Focus the WebView so it receives key events for D-pad handling
        webView.requestFocus()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                GoRoLog.d("BoxAuth", "WebView navigation: $url")
                // Intercept the redirect to rclone's registered redirect URI
                if (url.startsWith(BoxOAuthConfig.REDIRECT_URI)) {
                    GoRoLog.d("BoxAuth", ">>> Redirect intercepted! URL: $url")
                    handleRedirectUrl(url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                GoRoLog.d("BoxAuth", "Page started: ${url?.take(100)}")
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                GoRoLog.d("BoxAuth", "Page finished: ${url?.take(100)}")
                progressBar.visibility = View.GONE
                // Poll for the dynamically-rendered grant button.
                // Give it tabindex and a yellow focus ring so the system D-pad
                // can navigate between Grant/Deny and the user can see where focus is.
                view?.evaluateJavascript(
                    "javascript:(function(){" +
                    "  var t=setInterval(function(){" +
                    "    var g=document.querySelector('button[data-target-id=\"Button-grantAccessButtonLabel\"]');" +
                    "    var d=document.querySelector('button[data-target-id=\"Button-denyAccessButtonLabel\"]');" +
                    "    if(!g)return;clearInterval(t);" +
                    "    [g,d].forEach(function(el){" +
                    "      if(!el)return;" +
                    "      el.tabIndex=0;" +
                    "      el.style.outline='none';" +
                    "      el.addEventListener('focus',function(){this.style.outline='3px solid #FFD700';});" +
                    "      el.addEventListener('blur',function(){this.style.outline='none';});" +
                    "    });" +
                    "    g.focus();" +
                    "  },200);" +
                    "  setTimeout(function(){clearInterval(t);},20000);" +
                    "})()", null
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                val errorCode = error?.errorCode ?: -1
                val description = error?.description?.toString() ?: "unknown"
                GoRoLog.e("BoxAuth", "WebView error [$errorCode]: $description (url: ${request?.url})")
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                val statusCode = errorResponse?.statusCode ?: -1
                GoRoLog.e("BoxAuth", "WebView HTTP error [$statusCode] for: ${request?.url}")
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                GoRoLog.e("BoxAuth", "WebView SSL error: ${error?.toString()}")
                // Allow SSL errors for localhost redirect (safe — it never loads)
                handler?.proceed()
            }
        }

        // Build the Box OAuth authorization URL using rclone's public credentials
        val authUri = Uri.parse("https://account.box.com/api/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", BoxOAuthConfig.CLIENT_ID)
            .appendQueryParameter("redirect_uri", BoxOAuthConfig.REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", BoxOAuthConfig.SCOPE)
            .build()

        GoRoLog.d("BoxAuth", "=== WebView OAuth Flow ===")
        GoRoLog.d("BoxAuth", "Loading OAuth URL: ${authUri.toString().take(100)}...")  // trim for readability
        GoRoLog.d("BoxAuth", "Redirect URI: ${BoxOAuthConfig.REDIRECT_URI}")
        GoRoLog.d("BoxAuth", "Client ID: ${BoxOAuthConfig.CLIENT_ID.take(10)}...")
        webView.loadUrl(authUri.toString())
    }

    /**
     * Handles the OAuth redirect from Box. Extracts the auth code from the
     * redirect URL, exchanges it for tokens, and returns the result.
     */
    private fun handleRedirectUrl(url: String) {
        GoRoLog.d("BoxAuth", "=== Handling OAuth redirect ===")
        val uri = Uri.parse(url)
        val error = uri.getQueryParameter("error")
        if (error != null) {
            GoRoLog.e("BoxAuth", "OAuth error from Box: $error")
            showAuthErrorDialog("OAuth error: $error")
            return
        }
        val code = uri.getQueryParameter("code")
        GoRoLog.d("BoxAuth", "Code param present: ${code != null}, uri: $uri")
        if (code == null) {
            // Log all query params for debugging
            val params = uri.queryParameterNames.joinToString { "$it=${uri.getQueryParameter(it)}" }
            GoRoLog.e("BoxAuth", "No auth code found. Query params: $params")
            showAuthErrorDialog("No authorization code received")
            return
        }

        GoRoLog.d("BoxAuth", "Auth code received (${code.take(10)}...), exchanging for tokens...")

        lifecycleScope.launch {
            try {
                GoRoLog.d("BoxAuth", "Exchanging auth code for tokens...")
                val tokenResponse = exchangeCode(code)
                GoRoLog.d("BoxAuth", "Token exchange successful. Has access_token: ${tokenResponse.has("access_token")}, has refresh_token: ${tokenResponse.has("refresh_token")}")
                val accessToken = tokenResponse.get("access_token").asString

                GoRoLog.d("BoxAuth", "Fetching user info...")
                val userInfo = fetchUserInfo(accessToken)
                val email = userInfo.get("login").asString
                GoRoLog.d("BoxAuth", "User info received: email=$email")

                val tokenJson = gson.toJson(tokenResponse)

                GoRoLog.d("BoxAuth", "=== Auth success for $email ===")
                Toast.makeText(this@BoxAuthActivity, "Connected to $email", Toast.LENGTH_SHORT).show()

                val resultIntent = Intent().apply {
                    putExtra(EXTRA_TOKEN_JSON, tokenJson)
                    putExtra(EXTRA_EMAIL, email)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            } catch (e: Exception) {
                GoRoLog.e("BoxAuth", "Token exchange or userinfo failed", e)
                showAuthErrorDialog("Auth failed: ${e.message}")
            }
        }
    }

    private suspend fun exchangeCode(code: String): JsonObject = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("code", code)
            .add("client_id", BoxOAuthConfig.CLIENT_ID)
            .add("client_secret", BoxOAuthConfig.CLIENT_SECRET)
            .add("redirect_uri", BoxOAuthConfig.REDIRECT_URI)
            .add("grant_type", "authorization_code")
            .build()

        httpClient.newCall(
            Request.Builder()
                .url("https://api.box.com/oauth2/token")
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

        val dialogView = layoutInflater.inflate(R.layout.dialog_policy_blocked, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setCancelable(true)
            .setView(dialogView)
            .create()

        dialogView.findViewById<android.widget.TextView>(R.id.txtPolicyDetails)?.text = message
        dialogView.findViewById<View>(R.id.btnPolicyOk).setOnClickListener {
            dialog.dismiss()
            finish()
        }
        dialog.show()
    }
}
