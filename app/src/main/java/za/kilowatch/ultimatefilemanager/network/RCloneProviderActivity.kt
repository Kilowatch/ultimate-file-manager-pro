package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import org.json.JSONObject
import java.io.File

/**
 * Activity for adding RClone cloud storage providers.
 *
 * Data-driven: selecting a provider from the toggle group shows ONLY the
 * fields that provider needs. Supports Test Connection (via rclone RC)
 * and Save (writes to rclone.conf via [RCloneConfig]).
 */
class RCloneProviderActivity : AppCompatActivity() {

    private var isTv = false
    private var selectedProvider: RCloneProviderInfo? = null

    // Views
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var cardFields: View
    private lateinit var etStorageName: TextInputEditText
    private lateinit var fieldContainer: LinearLayout
    private lateinit var txtResult: TextView
    private lateinit var btnTest: android.widget.Button
    private lateinit var btnSave: android.widget.Button

    /** Dynamic field inputs, keyed by the field's config key (e.g. "email", "password"). */
    private val fieldInputs = mutableMapOf<String, TextInputEditText>()

    /** Tracks whether rclone has been initialized for this session. */
    private var rcloneInitialized = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_rclone_provider_tv
            else R.layout.activity_rclone_provider
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        bindViews()
        populateProviderChips()
    }

    private fun bindViews() {
        toggleGroup = findViewById(R.id.toggleGroupProvider)
        cardFields = findViewById(R.id.cardFields)
        etStorageName = findViewById(R.id.etStorageName)
        fieldContainer = findViewById(R.id.fieldContainer)
        txtResult = findViewById(R.id.txtResult)
        btnTest = findViewById(R.id.btnTest)
        btnSave = findViewById(R.id.btnSave)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        toggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                val chip = group.findViewById<View>(checkedId)
                val providerId = chip?.tag as? String
                onProviderSelected(providerId)
            }
        }

        btnTest.setOnClickListener { testConnection() }
        btnSave.setOnClickListener { saveProvider() }
    }

    private fun populateProviderChips() {
        toggleGroup.removeAllViews()
        ALL_RCLONE_PROVIDERS.forEach { provider ->
            val chip = layoutInflater.inflate(
                if (isTv) R.layout.item_tv_toggle_chip
                else R.layout.item_mobile_toggle_chip,
                toggleGroup,
                false
            ) as MaterialButton
            chip.id = View.generateViewId()
            chip.tag = provider.id
            chip.text = getString(provider.nameResId)
            chip.setIconResource(provider.iconResId)
            toggleGroup.addView(chip)
        }

        // Auto-select the first provider
        if (ALL_RCLONE_PROVIDERS.isNotEmpty()) {
            val firstChip = toggleGroup.getChildAt(0)
            if (firstChip is MaterialButton) {
                firstChip.isChecked = true
                onProviderSelected(ALL_RCLONE_PROVIDERS[0].id)
            }
        }
    }

    private fun onProviderSelected(providerId: String?) {
        val provider = ALL_RCLONE_PROVIDERS.find { it.id == providerId } ?: return
        selectedProvider = provider
        renderFields(provider)
        txtResult.visibility = View.GONE
        setSaveButtonEnabled(false)
    }

    /**
     * Dynamically creates input fields for the given provider.
     * Removes any previously rendered fields first.
     */
    private fun renderFields(provider: RCloneProviderInfo) {
        fieldContainer.removeAllViews()
        fieldInputs.clear()

        for (field in provider.fields) {
            val fieldRoot = layoutInflater.inflate(
                if (isTv) R.layout.item_rclone_field_tv
                else R.layout.item_rclone_field,
                fieldContainer,
                false
            ) as LinearLayout
            val fieldLayout = fieldRoot.findViewById<TextInputLayout>(R.id.fieldLayout)!!

            fieldLayout.hint = getString(field.labelResId)
            fieldLayout.tag = field.key

            val input = fieldLayout.findViewById<TextInputEditText>(R.id.input)

            when (field.inputType) {
                FieldType.PASSWORD -> {
                    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    fieldLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
                FieldType.API_KEY -> {
                    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    fieldLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
                FieldType.TEXT -> {
                    input.inputType = InputType.TYPE_CLASS_TEXT
                }
            }

            fieldInputs[field.key] = input
            fieldContainer.addView(fieldRoot)

            // Show a "How to get this?" help link if the field has a setup URL
            if (field.helpUrl != null) {
                val helpLink = TextView(this).apply {
                    text = getString(R.string.rclone_field_help_link)
                    setTextColor(
                        if (isTv) ContextCompat.getColor(
                            this@RCloneProviderActivity,
                            R.color.tv_accent
                        )
                        else ContextCompat.getColor(
                            this@RCloneProviderActivity,
                            R.color.ufm_primary
                        )
                    )
                    textSize = if (isTv) 14f else 12f
                    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(field.helpUrl))
                        startActivity(intent)
                    }
                    if (isTv) {
                        isFocusable = true
                        setOnFocusChangeListener { v, hasFocus ->
                            v.alpha = if (hasFocus) 1f else 0.7f
                        }
                    }
                }
                val lp = ViewGroup.MarginLayoutParams(
                    if (isTv) ViewGroup.LayoutParams.MATCH_PARENT
                    else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics
                ).toInt()
                lp.marginStart = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
                ).toInt()
                fieldRoot.addView(helpLink, lp)
            }
        }

        cardFields.visibility = View.VISIBLE
    }

    private fun testConnection() {
        if (!isNetworkAvailable()) {
            showResult(getString(R.string.rclone_error_no_network), isError = true)
            return
        }

        val provider = selectedProvider ?: return

        // Validate fields on the main thread before we go async (required check only,
        // no obscuring yet — obscuring needs rclone to be initialized first).
        if (buildConfigMap(provider) == null) return

        btnTest.isEnabled = false
        setButtonText(btnTest, R.string.rclone_testing)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Ensure rclone is initialized once
                ensureRcloneInitialized()

                // Build the config map with passwords obscured via core/obscure RPC.
                // The Filen backend sends the password in an HTTP Authorization header;
                // Go's net/http rejects raw passwords that contain header-illegal
                // characters, so we must obscure before passing to config/create.
                val configMap = buildConfigMap(provider, obscurePasswords = true) ?: run {
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        setButtonText(btnTest, R.string.rclone_btn_test)
                    }
                    return@launch
                }

                // Create a temporary remote via RC API (no config file write needed)
                val createParams = JSONObject().apply {
                    put("name", "ufmtest")
                    put("type", provider.typeName)
                    put("parameters", JSONObject(configMap))
                }
                val createResult = gomobile.Gomobile.rcloneRPC(
                    "config/create",
                    createParams.toString()
                )

                if (createResult.status != 200L) {
                    val errMsg = try {
                        JSONObject(createResult.output).optString("error", createResult.output)
                    } catch (_: org.json.JSONException) {
                        createResult.output
                    }
                    withContext(Dispatchers.Main) {
                        showResult(getString(R.string.rclone_test_failed, errMsg), isError = true)
                        btnTest.isEnabled = true
                        setButtonText(btnTest, R.string.rclone_btn_test)
                    }
                    return@launch
                }

                // Test by listing the remote root
                val testResult = gomobile.Gomobile.rcloneRPC(
                    "operations/list",
                    """{"fs": "ufmtest:", "remote": ""}"""
                )

                // Clean up the test remote regardless of result
                gomobile.Gomobile.rcloneRPC("config/delete", """{"name": "ufmtest"}""")

                withContext(Dispatchers.Main) {
                    if (testResult.status == 200L) {
                        showResult(getString(R.string.rclone_test_success), isError = false)
                        setSaveButtonEnabled(true)
                    } else {
                        val msg = try {
                            JSONObject(testResult.output).optString("error", testResult.output)
                        } catch (_: org.json.JSONException) {
                            testResult.output
                        }
                        showResult(getString(R.string.rclone_test_failed, msg), isError = true)
                    }
                    btnTest.isEnabled = true
                    setButtonText(btnTest, R.string.rclone_btn_test)
                }
            } catch (e: Exception) {
                // Clean up test remote on error too
                try { gomobile.Gomobile.rcloneRPC("config/delete", """{"name": "ufmtest"}""") } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    showResult(getString(R.string.rclone_test_failed, e.message ?: "Unknown error"), isError = true)
                    btnTest.isEnabled = true
                    setButtonText(btnTest, R.string.rclone_btn_test)
                }
            }
        }
    }

    /**
     * Ensures rclone is initialized once per Activity lifecycle.
     * After init the config path is set via the `config/setpath` RPC
     * so rclone can write to app-private storage (Android has no /home).
     * Remotes are added dynamically via the `config/create` RC method.
     */
    private fun ensureRcloneInitialized() {
        if (rcloneInitialized) return
        val configDir = File(filesDir, "rclone").also { it.mkdirs() }
        val configFile = File(configDir, "rclone.conf")
        if (!configFile.exists()) configFile.writeText("")

        gomobile.Gomobile.rcloneInitialize()
        // librclone doesn't read env vars or CLI flags for config path —
        // must set it via RPC after init
        gomobile.Gomobile.rcloneRPC(
            "config/setpath",
            """{"path": "${configFile.absolutePath}"}"""
        )
        rcloneInitialized = true
    }

    private fun saveProvider() {
        val provider = selectedProvider ?: return
        val displayName = etStorageName.text?.toString()?.trim() ?: ""

        if (displayName.isBlank()) {
            etStorageName.error = getString(R.string.field_required)
            etStorageName.requestFocus()
            return
        }

        setSaveButtonEnabled(false)
        setButtonText(btnSave, R.string.rclone_saving)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Ensure rclone is initialized so core/obscure RPC is available
                ensureRcloneInitialized()

                // Passwords MUST be obscured before writing to the config file —
                // rclone always expects its own XOR+base64 encoding in config files.
                val configMap = buildConfigMap(provider, obscurePasswords = true) ?: run {
                    withContext(Dispatchers.Main) {
                        setSaveButtonEnabled(true)
                        setButtonText(btnSave, R.string.rclone_btn_save)
                    }
                    return@launch
                }

                // Save encrypted to app-private storage — never plaintext on disk.
                // Merge with any existing providers so multiple RClone storages can coexist.
                val existingProviders = RCloneConfig.readEncrypted(this@RCloneProviderActivity)
                val mergedProviders = existingProviders.toMutableMap()

                // Save the OnlineStorage FIRST to get a unique storage ID,
                // then use that ID as the config key so launchRCloneBrowse can look it up.
                val label = configMap["email"] ?: configMap["user"] ?: displayName
                val storage = OnlineStorage(
                    provider = OnlineStorageProvider.RCLONE,
                    email = label,
                    displayName = displayName
                )
                OnlineStorageRepository.getInstance(this@RCloneProviderActivity).save(storage)

                // Key the provider config by storage.id (a unique UUID), not REMOTE_NAME,
                // so each provider has its own entry in the encrypted blob.
                mergedProviders[storage.id] = configMap
                // Remove any legacy key from the old single-remote system so stale
                // entries from before the multi-provider fix don't pollute the config.
                mergedProviders.remove(RCloneShareClient.REMOTE_NAME)
                RCloneConfig.saveEncrypted(this@RCloneProviderActivity, mergedProviders)

                // Reset the process-scoped rclone state so the browser reloads the new config
                RCloneShareClient.resetInitialized()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RCloneProviderActivity, R.string.rclone_save_success, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: java.io.IOException) {
                withContext(Dispatchers.Main) {
                    showResult(getString(R.string.rclone_test_failed, "Failed to write config: ${e.message}"), isError = true)
                    setSaveButtonEnabled(true)
                    setButtonText(btnSave, R.string.rclone_btn_save)
                }
            } catch (e: IllegalStateException) {
                withContext(Dispatchers.Main) {
                    showResult(getString(R.string.rclone_test_failed, "Encryption error: ${e.message}"), isError = true)
                    setSaveButtonEnabled(true)
                    setButtonText(btnSave, R.string.rclone_btn_save)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showResult(
                        getString(R.string.rclone_test_failed, e.message ?: "Unknown error"),
                        isError = true
                    )
                    setSaveButtonEnabled(true)
                    setButtonText(btnSave, R.string.rclone_btn_save)
                }
            }
        }
    }

    /**
     * Builds a config map from the current field values.
     * If [obscurePasswords] is true, password and API key fields are obscured
     * via rclone's core/obscure RC method before being written to the config.
     * This is required because rclone backends (e.g. Filen) pass these values
     * directly into HTTP headers, and Go's net/http rejects raw values that
     * contain header-illegal characters.
     */
    private fun buildConfigMap(
        provider: RCloneProviderInfo,
        obscurePasswords: Boolean = false
    ): Map<String, String>? {
        val map = mutableMapOf<String, String>()
        map["type"] = provider.typeName

        for (field in provider.fields) {
            val input = fieldInputs[field.key] ?: continue
            val value = input.text?.toString()?.trim() ?: ""

            if (field.required && value.isBlank()) {
                input.error = getString(R.string.field_required)
                input.requestFocus()
                return null
            }

            map[field.key] = if (obscurePasswords &&
                (field.inputType == FieldType.PASSWORD || field.inputType == FieldType.API_KEY)) {
                obscurePassword(value)
            } else {
                value
            }
        }
        return map
    }

    /**
     * Obscures a plaintext password using rclone's core/obscure RC method.
     * The password is safely JSON-encoded to prevent injection.
     *
     * RC contract: input key is "clear", output JSON contains "obscured".
     *
     * @throws IllegalStateException if obscuring fails — never falls back to plaintext
     */
    private fun obscurePassword(plaintext: String): String {
        val json = JSONObject().apply {
            put("clear", plaintext)
        }
        val result = gomobile.Gomobile.rcloneRPC(
            "core/obscure",
            json.toString()
        )
        if (result.status != 200L) {
            throw IllegalStateException("Password obscuring failed (status ${result.status})")
        }
        return JSONObject(result.output).getString("obscured")
    }

    private fun showResult(message: String, isError: Boolean) {
        txtResult.text = message
        txtResult.visibility = View.VISIBLE
        txtResult.setTextColor(
            if (isError) getColor(R.color.tv_error)
            else getColor(R.color.tv_accent)
        )
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setSaveButtonEnabled(enabled: Boolean) {
        btnSave.isEnabled = enabled
        val color = if (enabled) {
            ContextCompat.getColor(this, R.color.ufm_granted)
        } else {
            ContextCompat.getColor(this, R.color.btn_disabled_bg)
        }
        androidx.core.view.ViewCompat.setBackgroundTintList(btnSave, android.content.res.ColorStateList.valueOf(color))
        btnSave.setTextColor(android.graphics.Color.WHITE)
    }

    private fun setButtonText(button: View, resId: Int) {
        (button as? android.widget.TextView)?.text = getString(resId)
    }
}
