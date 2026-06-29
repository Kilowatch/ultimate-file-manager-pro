package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
import za.kilowatch.ultimatefilemanager.util.QrCodeUtils
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TV Box OAuth 2.0 authentication using a QR code + manual code entry flow.
 *
 * Box doesn't support device code grant and WebView D-pad focus is unreliable
 * on TV, so this shows the OAuth URL as a QR code. The user opens it on their
 * phone, authorizes, and enters the auth code from the redirect URL into the
 * TV. All UI is built programmatically to avoid layout conflicts.
 */
class BoxDeviceCodeAuthActivity : AppCompatActivity() {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()

    private var codeInput: TextInputEditText? = null

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

        // Build the full screen programmatically — no XML layout
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            id = View.generateViewId()
        }

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        // ── Header with back button ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val btnBack = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.selector_tv_icon_btn)
            isFocusable = true
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val title = TextView(this).apply {
            text = getString(R.string.box_device_code_title)
            textSize = 28f
            setTextColor(getColor(R.color.tv_text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(24)
            }
        }
        header.addView(btnBack)
        header.addView(title)
        root.addView(header)

        // ── Scrollable content ──
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        scroll.addView(content)
        root.addView(scroll)

        // ── Generate OAuth URL ──
        val authUri = android.net.Uri.parse("https://account.box.com/api/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", BoxOAuthConfig.CLIENT_ID)
            .appendQueryParameter("redirect_uri", BoxOAuthConfig.REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", BoxOAuthConfig.SCOPE)
            .build()
        val authUrl = authUri.toString()

        // ── QR Code ──
        val qrImage = ImageView(this).apply {
            val size = dp(220)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        content.addView(qrImage)

        val progressQr = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        content.addView(progressQr)

        // Generate QR in background
        val qrSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 220f, resources.displayMetrics).toInt()
        QrCodeUtils.generateQrCode(authUrl, qrSizePx)?.let { bitmap ->
            qrImage.setImageBitmap(bitmap)
            qrImage.visibility = View.VISIBLE
            progressQr.visibility = View.GONE
        } ?: run { progressQr.visibility = View.GONE }

        // ── URL (scrollable horizontally) ──
        val urlLabel = TextView(this).apply {
            text = "Scan the QR code or open this URL on your phone:"
            textSize = 14f
            setTextColor(getColor(R.color.tv_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        content.addView(urlLabel)

        val urlScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val urlText = TextView(this).apply {
            text = authUrl
            textSize = 12f
            setTextColor(getColor(R.color.tv_accent))
        }
        urlScroll.addView(urlText)
        content.addView(urlScroll)

        // ── Instruction text ──
        val instr = TextView(this).apply {
            text = "After authorizing, your browser will redirect to a page that says " +
                   "the site cannot be reached. Look in the address bar for code=... " +
                   "and copy that value, then paste it below."
            textSize = 14f
            setTextColor(getColor(R.color.tv_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        content.addView(instr)

        // ── Code input ──
        val inputLayout = TextInputLayout(this).apply {
            hint = "Paste the auth code here"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxBackgroundColorResource(android.R.color.transparent)
            defaultHintTextColor = android.content.res.ColorStateList.valueOf(
                getColor(R.color.tv_text_hint)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        codeInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(getColor(R.color.tv_text_primary))
        }.also { inputLayout.addView(it) }
        content.addView(inputLayout)

        // ── Buttons ──
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }

        val btnCancel = MaterialButton(this).apply {
            text = getString(R.string.cancel)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        val btnOk = MaterialButton(this).apply {
            text = getString(R.string.btn_ok)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        btnOk.setOnClickListener {
            val code = codeInput?.text?.toString()?.trim() ?: ""
            if (code.isNotBlank()) {
                btnOk.isEnabled = false
                exchangeCode(code)
            }
        }
        btnCancel.setOnClickListener { finish() }

        // TV focus styling
        listOf(btnOk, btnCancel).forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.setBackgroundColor(getColor(R.color.tv_button_focused_yellow))
                    (v as MaterialButton).setTextColor(getColor(R.color.tv_button_focused_yellow_text))
                } else {
                    v.setBackgroundColor(getColor(R.color.tv_glass_white_10))
                    (v as MaterialButton).setTextColor(getColor(R.color.tv_text_primary))
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnOk)
        content.addView(btnRow)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun exchangeCode(code: String) {
        lifecycleScope.launch {
            try {
                GoRoLog.d("BoxDeviceAuth", "Exchanging manual code for tokens...")

                val tokenResponse = withContext(Dispatchers.IO) {
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

                val accessToken = tokenResponse.get("access_token").asString

                val userInfo = withContext(Dispatchers.IO) {
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
                GoRoLog.e("BoxDeviceAuth", "Failed to exchange code", e)
                codeInput?.text = null
                codeInput?.isEnabled = true
                Toast.makeText(this@BoxDeviceCodeAuthActivity,
                    getString(R.string.box_auth_failed, e.message ?: "Error"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
