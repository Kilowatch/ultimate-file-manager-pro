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
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
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

    companion object {
        /** Type marker stored in [OnlineStorage.refreshToken] to distinguish Box
         *  from other RClone providers in the storage list display. */
        const val BOX_REFRESH_TOKEN_MARKER = "box"
    }

    private var isTv = false
    private var selectedProvider: RCloneProviderInfo? = null
    /** Path to the rclone.conf file created by [ensureRcloneInitialized]. */
    private var rcloneConfigPath: String? = null

    private val viewModel: RCloneProviderViewModel by viewModels()

    // Views (TV)
    private var toggleGroup: LinearLayout? = null
    private var toggleGroup2: LinearLayout? = null
    // Views (Mobile)
    private lateinit var svProviderList: NestedScrollView
    private lateinit var providerListContainer: LinearLayout
    private lateinit var scrollThumb: View
    private lateinit var svFields: NestedScrollView
    private lateinit var scrollIndicatorFields: View
    private lateinit var scrollThumbFields: View

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

    // ── Box OAuth state ─────────────────────────────────────────────────────────
    private var boxTokenJson: String? = null
    private var boxEmail: String = ""

    private val boxAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        GoRoLog.d("BoxAuth", "=== boxAuthLauncher result ===")
        GoRoLog.d("BoxAuth", "resultCode=${result.resultCode} (RESULT_OK=$RESULT_OK)")
        if (result.resultCode == RESULT_OK) {
            boxTokenJson = result.data?.getStringExtra(BoxAuthActivity.EXTRA_TOKEN_JSON)
            boxEmail = result.data?.getStringExtra(BoxAuthActivity.EXTRA_EMAIL) ?: ""
            GoRoLog.d("BoxAuth", "Token received: ${boxTokenJson != null}, email=$boxEmail")
            if (boxTokenJson != null) {
                if (etStorageName.text.isNullOrBlank()) {
                    etStorageName.setText(boxEmail)
                }
                setSaveButtonEnabled(true)
                showResult(getString(R.string.rclone_box_auth_success), isError = false)
                GoRoLog.d("BoxAuth", "Save enabled — user can now save")
            } else {
                GoRoLog.e("BoxAuth", "RESULT_OK but no token_json extra in intent data")
            }
        } else {
            GoRoLog.e("BoxAuth", "Auth cancelled or failed (resultCode=${result.resultCode})")
            showResult(getString(R.string.rclone_box_auth_failed, "Authorization cancelled"), isError = true)
        }
    }
    // ─────────────────────────────────────────────────────────────────────────────

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
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Add 30dp extra padding when the soft keyboard is visible
            val extraKeyboardPadding = if (ime.bottom > 0) {
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 30f, resources.displayMetrics
                ).toInt()
            } else 0
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom + ime.bottom + extraKeyboardPadding)
            insets
        }

        bindViews()

        if (isTv) {
            populateProviderChips()
        } else {
            populateProviderList()
        }

        // Collect ViewModel state — drives provider selection for both TV and mobile
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedProviderId.collect { providerId ->
                    onProviderSelected(providerId)
                    // Update button selection highlights when selection changes
                    if (!isTv) {
                        updateProviderSelection()
                    }
                }
            }
        }

        // TV: auto-select the first provider on first launch (mobile handles this in setupProviderList)
        if (isTv && viewModel.selectedProviderId.value == null && ALL_RCLONE_PROVIDERS.isNotEmpty()) {
            viewModel.selectProvider(ALL_RCLONE_PROVIDERS[0].id)
        }
    }

    private fun bindViews() {
        if (isTv) {
            toggleGroup = findViewById(R.id.toggleGroupProvider)
            toggleGroup2 = findViewById(R.id.toggleGroupProvider2)
        } else {
            svProviderList = findViewById(R.id.svProviderList)
            providerListContainer = findViewById(R.id.providerListContainer)
            scrollThumb = findViewById(R.id.scrollThumb)
            svFields = findViewById(R.id.svFields)
            scrollIndicatorFields = findViewById(R.id.scrollIndicatorFields)
            scrollThumbFields = findViewById(R.id.scrollThumbFields)

            // Provider scroll tracking
            svProviderList.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                if (scrollY != oldScrollY) {
                    updateScrollThumbPosition()
                }
            }
            svProviderList.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateScrollThumbPosition()
            }
            // Fields scroll tracking
            svFields.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                if (scrollY != oldScrollY) {
                    updateFieldsScrollThumbPosition()
                }
            }
            svFields.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateFieldsScrollThumbPosition()
            }
        }
        cardFields = findViewById(R.id.cardFields)
        etStorageName = findViewById(R.id.etStorageName)
        etStorageName.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isTv) {
                svFields.post {
                    val parent = etStorageName.parent as? View
                    if (parent != null) {
                        svFields.smoothScrollTo(0, parent.top)
                    }
                }
            }
        }
        fieldContainer = findViewById(R.id.fieldContainer)
        txtResult = findViewById(R.id.txtResult)
        btnTest = findViewById(R.id.btnTest)
        btnSave = findViewById(R.id.btnSave)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        btnTest.setOnClickListener {
            if (selectedProvider?.id == "box") {
                authenticateBox()
            } else {
                testConnection()
            }
        }
        btnSave.setOnClickListener { saveProvider() }
    }

    private fun populateProviderChips() {
        val tg = toggleGroup ?: return
        val tg2 = toggleGroup2 ?: return
        tg.removeAllViews()
        tg2.removeAllViews()

        val maxRow1 = minOf(3, ALL_RCLONE_PROVIDERS.size)
        for (i in ALL_RCLONE_PROVIDERS.indices) {
            val provider = ALL_RCLONE_PROVIDERS[i]
            val parent = if (i < maxRow1) tg else tg2
            val chip = layoutInflater.inflate(
                R.layout.item_tv_toggle_chip,
                parent,
                false
            ) as MaterialButton
            chip.id = View.generateViewId()
            chip.tag = provider.id
            chip.text = getString(provider.nameResId)
            chip.setIconResource(provider.iconResId)

            chip.setOnClickListener {
                viewModel.selectProvider(provider.id)
                updateChipCheckedState(provider.id)
            }

            parent.addView(chip)
        }

        // Align Row 2 buttons with Row 1 by adding dummy invisible buttons if needed
        val row1Count = tg.childCount
        val row2Count = tg2.childCount
        if (row1Count > 0 && row2Count > 0 && row2Count < row1Count) {
            val dummiesNeeded = row1Count - row2Count
            for (d in 0 until dummiesNeeded) {
                val dummy = layoutInflater.inflate(
                    R.layout.item_tv_toggle_chip,
                    tg2,
                    false
                ) as MaterialButton
                dummy.id = View.generateViewId()
                dummy.visibility = View.INVISIBLE
                dummy.isEnabled = false
                dummy.isClickable = false
                dummy.isFocusable = false
                dummy.isCheckable = false
                tg2.addView(dummy)
            }
        }
    }

    /** Updates chip checked state to match the current selection. */
    private fun updateChipCheckedState(providerId: String?) {
        val tg = toggleGroup ?: return
        val tg2 = toggleGroup2 ?: return
        val groups = listOf(tg, tg2)
        for (group in groups) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) as? MaterialButton ?: continue
                val isTarget = child.tag == providerId
                if (child.isChecked != isTarget) {
                    child.isChecked = isTarget
                }
            }
        }
    }

    /**
     * Populates the provider list (mobile only) by inflating MaterialButton items
     * into the ScrollView's LinearLayout container. Selection state is driven by
     * [viewModel.selectedProviderId] and updated via [updateProviderSelection].
     */
    private fun populateProviderList() {
        val sorted = ALL_RCLONE_PROVIDERS.sortedBy { getString(it.nameResId) }
        providerListContainer.removeAllViews()

        for (provider in sorted) {
            val button = layoutInflater.inflate(
                R.layout.item_rclone_provider,
                providerListContainer,
                false
            ) as MaterialButton
            button.id = View.generateViewId()
            button.tag = provider.id
            button.text = getString(provider.nameResId)
            button.setIconResource(provider.iconResId)

            button.setOnClickListener {
                viewModel.selectProvider(provider.id)
            }

            providerListContainer.addView(button)
        }

        // Auto-select the first (alphabetically) provider on first launch
        if (viewModel.selectedProviderId.value == null && sorted.isNotEmpty()) {
            viewModel.selectProvider(sorted[0].id)
        }

        // Apply initial selection highlight
        updateProviderSelection()

        // Set initial scroll thumb positions after layout
        svProviderList.post {
            updateScrollThumbPosition()
            updateFieldsScrollThumbPosition()
        }
    }

    /**
     * Moves the custom scroll thumb to reflect the current scroll position
     * within the fields section. Shows the indicator only when content overflows.
     */
    private fun updateFieldsScrollThumbPosition() {
        if (isTv || !::svFields.isInitialized) return
        val contentHeight = svFields.getChildAt(0)?.height ?: return
        val viewHeight = svFields.height
        val hasOverflow = contentHeight > viewHeight
        scrollIndicatorFields.visibility = if (hasOverflow) View.VISIBLE else View.GONE
        if (!hasOverflow) return

        val maxScrollY = contentHeight - viewHeight
        val scrollY = svFields.scrollY
        val progress = if (maxScrollY > 0) scrollY.toFloat() / maxScrollY else 0f

        val indicatorHeight = svFields.height - 12
        val thumbH = scrollThumbFields.height
        scrollThumbFields.translationY = progress * (indicatorHeight - thumbH)
    }

    /**
     * Moves the custom scroll thumb to reflect the current scroll position
     * within the provider list.
     */
    private fun updateScrollThumbPosition() {
        val scrollView = svProviderList
        val contentHeight = scrollView.getChildAt(0)?.height ?: return
        val viewHeight = scrollView.height
        if (contentHeight <= viewHeight) return  // content fits — no scrolling

        val maxScrollY = contentHeight - viewHeight
        val scrollY = scrollView.scrollY
        val progress = if (maxScrollY > 0) scrollY.toFloat() / maxScrollY else 0f

        val indicatorHeight = scrollView.height - 12  // minus 6dp top + 6dp bottom margin
        val thumbHeight = scrollThumb.height
        val maxThumbY = indicatorHeight - thumbHeight
        scrollThumb.translationY = progress * maxThumbY
    }

    /**
     * Updates background and text colors for all provider buttons to reflect
     * the current selection in [viewModel.selectedProviderId].
     */
    private fun updateProviderSelection() {
        val selectedId = viewModel.selectedProviderId.value
        for (i in 0 until providerListContainer.childCount) {
            val btn = providerListContainer.getChildAt(i) as? MaterialButton ?: continue
            val isSelected = btn.tag == selectedId
            if (isSelected) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.tv_button_focused_yellow)
                )
                btn.setTextColor(
                    ContextCompat.getColor(this, R.color.tv_button_focused_yellow_text)
                )
            } else {
                btn.backgroundTintList = ContextCompat.getColorStateList(
                    this, R.color.selector_protocol_bg
                )
                btn.setTextColor(
                    ContextCompat.getColorStateList(this, R.color.selector_protocol_text)
                )
            }
            btn.isChecked = isSelected
        }
    }

    private fun onProviderSelected(providerId: String?) {
        val provider = ALL_RCLONE_PROVIDERS.find { it.id == providerId } ?: return
        selectedProvider = provider
        etStorageName.setText("")   // Clear storage name on provider switch
        renderFields(provider)
        txtResult.visibility = View.GONE

        if (provider.id == "box") {
            // Box uses OAuth instead of credential testing
            boxTokenJson = null
            boxEmail = ""
            setButtonText(btnTest, R.string.rclone_btn_authenticate)
            setSaveButtonEnabled(false)
        } else {
            setButtonText(btnTest, R.string.rclone_btn_test)
            setSaveButtonEnabled(false)
        }
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

            // Pre-populate default value if set
            if (!field.defaultValue.isNullOrBlank()) {
                input.setText(field.defaultValue)
            }

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
            input.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !isTv) {
                    svFields.post {
                        svFields.smoothScrollTo(0, fieldContainer.top + fieldRoot.top)
                    }
                }
            }
            fieldContainer.addView(fieldRoot)

            // Show helper text below the field (e.g. guidance about app passwords)
            if (field.helperTextResId != null) {
                fieldLayout.helperText = getString(field.helperTextResId)
            }

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
        if (!isTv) {
            svFields.post {
                updateFieldsScrollThumbPosition()
            }
        }
    }

    /**
     * Launches the Box OAuth flow. Dispatches to [BoxAuthActivity] (mobile)
     * or [BoxDeviceCodeAuthActivity] (TV). The result is handled by
     * [boxAuthLauncher].
     */
    private fun authenticateBox() {
        GoRoLog.d("BoxAuth", "=== authenticateBox() called ===")
        GoRoLog.d("BoxAuth", "isTv=$isTv, networkAvailable=${isNetworkAvailable()}")
        if (!isNetworkAvailable()) {
            showResult(getString(R.string.rclone_error_no_network), isError = true)
            return
        }
        val intent = if (isTv) {
            GoRoLog.d("BoxAuth", "Launching BoxDeviceCodeAuthActivity (TV)")
            Intent(this, BoxDeviceCodeAuthActivity::class.java)
        } else {
            GoRoLog.d("BoxAuth", "Launching BoxAuthActivity (Mobile)")
            Intent(this, BoxAuthActivity::class.java)
        }
        boxAuthLauncher.launch(intent)
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
                val configMap = buildConfigMap(provider, obscurePasswords = true) ?: run {
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        setButtonText(btnTest, R.string.rclone_btn_test)
                    }
                    return@launch
                }

                // ── Provider-specific routing ──────────────────────────────────
                // premiumizeme's Go Config callback always returns OAuth state,
                // which deadlocks on Android. Bypass it by writing the test config
                // directly to the rclone.conf file (rclone reads remotes lazily
                // from the file on each access).  All other providers use the
                // standard config/create RC path (proven for Filen/Drime/Mega/Koofr).
                // ──────────────────────────────────────────────────────────────
                var testStatus = 0L
                var testOutput = ""

                if (provider.typeName == "premiumizeme") {
                    val configFile = File(
                        rcloneConfigPath ?: File(filesDir, "rclone/rclone.conf").absolutePath
                    )
                    val rawConfig = buildConfigMap(provider, obscurePasswords = false) ?: run {
                        withContext(Dispatchers.Main) {
                            btnTest.isEnabled = true
                            setButtonText(btnTest, R.string.rclone_btn_test)
                        }
                        return@launch
                    }
                    val remoteIni = buildString {
                        appendLine()
                        appendLine("[ufmtest]")
                        for ((key, value) in rawConfig) {
                            appendLine("$key = $value")
                        }
                    }
                    configFile.appendText(remoteIni)
                    gomobile.Gomobile.rcloneRPC(
                        "config/setpath",
                        """{"path": "${configFile.absolutePath}"}"""
                    )
                    val result = gomobile.Gomobile.rcloneRPC(
                        "operations/list",
                        """{"fs": "ufmtest:", "remote": ""}"""
                    )
                    testStatus = result.status
                    testOutput = result.output
                    // Clean up: remove the [ufmtest] section from the config file
                    try {
                        val content = configFile.readText()
                        val cleaned = content.replace(Regex("""\n\[ufmtest]\n(?:[^\[\n]+\n?)*"""), "")
                        configFile.writeText(cleaned)
                        gomobile.Gomobile.rcloneRPC(
                            "config/setpath",
                            """{"path": "${configFile.absolutePath}"}"""
                        )
                    } catch (_: Exception) {
                        // Non-critical cleanup failure — ignore
                    }
                } else {
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
                    val result = gomobile.Gomobile.rcloneRPC(
                        "operations/list",
                        """{"fs": "ufmtest:", "remote": ""}"""
                    )
                    testStatus = result.status
                    testOutput = result.output
                    gomobile.Gomobile.rcloneRPC("config/delete", """{"name": "ufmtest"}""")
                }

                withContext(Dispatchers.Main) {
                    if (testStatus == 200L) {
                        showResult(getString(R.string.rclone_test_success), isError = false)
                        setSaveButtonEnabled(true)
                    } else {
                        val msg = try {
                            JSONObject(testOutput).optString("error", testOutput)
                        } catch (_: org.json.JSONException) {
                            testOutput
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
        rcloneConfigPath = configFile.absolutePath

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
                if (provider.id == "box") {
                    // ── Box-specific save path ──────────────────────────────────────
                    // Box uses OAuth tokens stored directly in the config — no need to
                    // initialise rclone or call core/obscure (boxConfig is a pure map builder).
                    val tokenJson = boxTokenJson
                    if (tokenJson == null) {
                        withContext(Dispatchers.Main) {
                            showResult(getString(R.string.rclone_auth_required), isError = true)
                            setSaveButtonEnabled(true)
                            setButtonText(btnSave, R.string.rclone_btn_save)
                        }
                        return@launch
                    }
                    val configMap = RCloneConfig.boxConfig(tokenJson)
                    val existingProviders = RCloneConfig.readEncrypted(this@RCloneProviderActivity)
                    val mergedProviders = existingProviders.toMutableMap()

                    val storage = OnlineStorage(
                        provider = OnlineStorageProvider.RCLONE,
                        email = boxEmail,
                        displayName = displayName,
                        refreshToken = BOX_REFRESH_TOKEN_MARKER
                    )
                    OnlineStorageRepository.getInstance(this@RCloneProviderActivity).save(storage)

                    mergedProviders[storage.id] = configMap
                    mergedProviders.remove(RCloneShareClient.REMOTE_NAME)
                    RCloneConfig.saveEncrypted(this@RCloneProviderActivity, mergedProviders)
                    RCloneShareClient.resetInitialized()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@RCloneProviderActivity, R.string.rclone_save_success, Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                    return@launch
                }

                // Ensure rclone is initialized so core/obscure RPC is available
                ensureRcloneInitialized()

                // Passwords MUST be obscured before writing to the config file —
                // rclone always expects its own XOR+base64 encoding in config files.
                // EXCEPTION: premiumizeme's api_key is Sensitive (not IsPassword), so
                // rclone won't auto-reveal it — save the raw key instead.
                val configMap = buildConfigMap(
                    provider,
                    obscurePasswords = provider.typeName != "premiumizeme"
                ) ?: run {
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

            // Skip non-required fields with blank values (e.g. optional URL)
            if (!field.required && value.isBlank()) continue

            map[field.key] = if (obscurePasswords &&
                (field.inputType == FieldType.PASSWORD || field.inputType == FieldType.API_KEY)) {
                obscurePassword(value)
            } else {
                value
            }
        }

        // Koofr-specific: the backend's password option is scoped to
        // Provider:"koofr", so we MUST set provider explicitly.
        // When endpoint differs from the default, we also send it +
        // switch to provider="other" so rclone uses the custom endpoint.
        if (provider.typeName == "koofr") {
            val endpointVal = map["endpoint"]
            if (endpointVal.isNullOrBlank() || endpointVal == "https://app.koofr.net") {
                // Default endpoint — let backend handle it, skip sending it
                map.remove("endpoint")
                map["provider"] = "koofr"
            } else {
                // Custom endpoint — must use "other" provider
                map["provider"] = "other"
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
