package za.kilowatch.ultimatefilemanager.support

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class SupportActivity : AppCompatActivity() {

    private var isTv = false
    private var currentType = "" // "bug", "feature", or "general"
    private val selectedFilePaths = mutableListOf<String>()
    // Suppresses redundant prefs writes while applyRememberedEmail() sets state programmatically
    private var isApplyingRememberState = false
    // Remembered email as of the last form open — avoids re-reading prefs on every back press
    private var rememberedEmailValue = ""

    // OkHttp client for API calls
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // File picker for attachments
    private val attachmentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val localPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
            if (localPath != null) {
                showAttachmentConfirmDialog(localPath)
            }
        }
    }

    // ─── Lifecycle ──────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            setContentView(R.layout.activity_support_tv)
        } else {
            setContentView(R.layout.activity_support)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPadding = maxOf(sysBars.bottom, imeInsets.bottom)
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                sysBars.left + tvPad, sysBars.top + tvPad,
                sysBars.right + tvPad, bottomPadding + tvPad
            )
            insets
        }

        setupViews()
    }

    // ─── View Setup ─────────────────────────────────────────────

    private fun setupViews() {
        // Back button
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        setupTvBackButton(btnBack)
        btnBack.setOnClickListener { onBackPressed() }

        // Option cards
        findViewById<View>(R.id.cardReportBug).setOnClickListener { showForm("bug") }
        findViewById<View>(R.id.cardFeatureRequest).setOnClickListener { showForm("feature") }
        findViewById<View>(R.id.cardGeneral).setOnClickListener { showForm("general") }

        // Attachment button
        findViewById<View>(R.id.layoutAttachment).setOnClickListener { pickAttachment() }

        // Remove attachment button
        findViewById<View>(R.id.btnRemoveAttachment)?.setOnClickListener {
            selectedFilePaths.clear()
            updateAttachmentViews()
        }

        // Send button
        val btnSend = findViewById<MaterialButton>(R.id.btnSend)
        btnSend.setOnClickListener { submitForm() }

        // Apply TV focus to Send button on TV
        if (isTv) {
            val yellowFill = getColor(R.color.tv_button_focused_yellow)
            val blackText = getColor(R.color.tv_button_focused_yellow_text)
            val defaultBg = getColor(R.color.tv_accent)
            val defaultText = getColor(R.color.tv_text_primary)

            btnSend.setOnFocusChangeListener { _, hasFocus ->
                btnSend.backgroundTintList = android.content.res.ColorStateList.valueOf(if (hasFocus) yellowFill else defaultBg)
                btnSend.setTextColor(if (hasFocus) blackText else defaultText)
                btnSend.iconTint = android.content.res.ColorStateList.valueOf(if (hasFocus) blackText else defaultText)
            }
        }
        val scrollView = findViewById<View?>(R.id.scrollView)
        val editTexts = listOf(
            findViewById<TextInputEditText>(R.id.edtSubject),
            findViewById<TextInputEditText>(R.id.edtMessage),
            findViewById<TextInputEditText>(R.id.edtSteps),
            findViewById<TextInputEditText>(R.id.edtUseCase),
            findViewById<TextInputEditText>(R.id.edtEmail)
        )
        editTexts.forEach { edt ->
            edt?.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus && scrollView != null) {
                    scrollView.postDelayed({
                        val parentLayout = view.parent?.parent as? View ?: view
                        when (scrollView) {
                            is androidx.core.widget.NestedScrollView -> scrollView.smoothScrollTo(0, parentLayout.top)
                            is android.widget.ScrollView -> scrollView.smoothScrollTo(0, parentLayout.top)
                        }
                    }, 200)
                }
            }
        }

        // Remember-email checkbox — persists the preference immediately on toggle (FR-11)
        val chkRememberEmail = findViewById<CheckBox>(R.id.chkRememberEmail)
        chkRememberEmail.setOnCheckedChangeListener { _, checked ->
            if (!isApplyingRememberState) {
                SupportEmailPrefs.setEnabled(this, checked)
            }
        }

        // TV Card click toggles checkbox
        findViewById<View?>(R.id.cardRememberEmail)?.setOnClickListener {
            chkRememberEmail.isChecked = !chkRememberEmail.isChecked
        }

        // Direct email card
        findViewById<View?>(R.id.cardDirectEmail)?.setOnClickListener {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:support@kilowatch.co.za")
                putExtra(Intent.EXTRA_SUBJECT, "[UFM Support] Help & Support")
            }
            try {
                startActivity(Intent.createChooser(emailIntent, getString(R.string.support_title)))
            } catch (e: Exception) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Support Email", "support@kilowatch.co.za")
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, R.string.support_direct_email_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupTvBackButton(btnBack: ImageView) {
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(
                getColor(R.color.tv_text_primary)
            )
            val blackCsl = android.content.res.ColorStateList.valueOf(
                getColor(R.color.tv_button_focused_yellow_text)
            )
            btnBack.imageTintList = whiteCsl
            btnBack.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
    }

    // ─── View Switching ─────────────────────────────────────────

    private fun showForm(type: String) {
        currentType = type

        findViewById<View>(R.id.layoutOptions).visibility = View.GONE
        findViewById<View>(R.id.layoutForm).visibility = View.VISIBLE

        // Set form title & header icon
        val (titleRes, iconRes) = when (type) {
            "bug" -> Pair(R.string.support_report_bug, R.drawable.ic_bug_report)
            "feature" -> Pair(R.string.support_feature_request, R.drawable.ic_feature_request)
            else -> Pair(R.string.support_general, R.drawable.ic_message)
        }
        findViewById<TextView>(R.id.txtFormType).text = getString(titleRes)
        findViewById<ImageView>(R.id.imgFormHeaderIcon)?.setImageResource(iconRes)

        // Show/hide type-specific fields
        findViewById<View>(R.id.inputSteps).visibility =
            if (type == "bug") View.VISIBLE else View.GONE
        findViewById<View>(R.id.inputUseCase).visibility =
            if (type == "feature") View.VISIBLE else View.GONE

        // Update description hint based on type
        val descHint = when (type) {
            "bug" -> getString(R.string.support_description)
            "feature" -> getString(R.string.support_description)
            else -> getString(R.string.support_message)
        }
        findViewById<TextInputLayout>(R.id.inputMessage).hint = descHint

        clearErrors()
        updateAttachmentViews()
        applyRememberedEmail()

        if (isTv) {
            findViewById<TextInputEditText>(R.id.edtSubject).requestFocus()
        }
    }

    private fun showOptions() {
        currentType = ""
        selectedFilePaths.clear()
        clearFields()

        findViewById<View>(R.id.layoutForm).visibility = View.GONE
        findViewById<View>(R.id.layoutOptions).visibility = View.VISIBLE

        if (isTv) {
            findViewById<View>(R.id.cardReportBug)?.requestFocus()
        }
    }

    // ─── Field Management ───────────────────────────────────────

    private fun clearFields() {
        findViewById<TextInputEditText>(R.id.edtSubject).text?.clear()
        findViewById<TextInputEditText>(R.id.edtMessage).text?.clear()
        findViewById<TextInputEditText>(R.id.edtSteps).text?.clear()
        findViewById<TextInputEditText>(R.id.edtUseCase).text?.clear()
        findViewById<TextInputEditText>(R.id.edtEmail).text?.clear()
        findViewById<TextInputEditText>(R.id.edtHoneypot).text?.clear()
        selectedFilePaths.clear()
        updateAttachmentViews()
        clearErrors()
    }

    private fun clearErrors() {
        findViewById<TextInputLayout>(R.id.inputSubject).error = null
        findViewById<TextInputLayout>(R.id.inputMessage).error = null
        findViewById<TextInputLayout>(R.id.inputEmail).error = null
    }

    /**
     * Applies the remembered-email state every time a form opens (FR-02):
     * - enabled + email stored → pre-fill the field, box checked
     * - enabled + no email yet → box checked, empty field
     * - disabled              → box unchecked, no pre-fill (background value lingers untouched)
     */
    private fun applyRememberedEmail() {
        val email = SupportEmailPrefs.getEmail(this)
        val prefill = SupportEmailPrefs.isEmailRemembered(this)
        rememberedEmailValue = email
        isApplyingRememberState = true
        findViewById<CheckBox>(R.id.chkRememberEmail).isChecked = SupportEmailPrefs.isEnabled(this)
        findViewById<TextInputEditText>(R.id.edtEmail).setText(if (prefill) email else "")
        isApplyingRememberState = false
    }

    // ─── Attachment ─────────────────────────────────────────────

    private fun pickAttachment() {
        if (selectedFilePaths.size >= MAX_ATTACHMENT_COUNT) {
            Toast.makeText(this, getString(R.string.support_max_attachments_reached, MAX_ATTACHMENT_COUNT), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, StorageBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_SUPPORT_ATTACHMENT_PICKER, true)
        }
        attachmentPickerLauncher.launch(intent)
    }

    private fun removeAttachment(index: Int) {
        if (index in selectedFilePaths.indices) {
            selectedFilePaths.removeAt(index)
            updateAttachmentViews()
        }
    }

    private fun showAttachmentConfirmDialog(path: String) {
        if (selectedFilePaths.contains(path)) {
            Toast.makeText(this, R.string.support_file_already_attached, Toast.LENGTH_SHORT).show()
            return
        }

        val file = File(path)
        val fileName = path.substringAfterLast('/')
        val fileSize = formatFileSize(file.length())

        if (file.length() > MAX_ATTACHMENT_SIZE) {
            Toast.makeText(this, R.string.support_file_too_large, Toast.LENGTH_LONG).show()
            return
        }

        showCustomSupportDialog(
            iconRes = R.drawable.ic_add,
            iconTintRes = R.color.ufm_primary,
            title = getString(R.string.support_attachment_confirm_title),
            message = getString(R.string.support_attachment_confirm_message, fileName, fileSize),
            positiveText = getString(R.string.support_proceed),
            negativeText = getString(android.R.string.cancel),
            onPositive = {
                if (selectedFilePaths.size < MAX_ATTACHMENT_COUNT) {
                    selectedFilePaths.add(path)
                    updateAttachmentViews()
                }
            }
        )
    }

    private fun updateAttachmentViews() {
        val container = findViewById<LinearLayout>(R.id.layoutAttachmentContainer) ?: return
        container.removeAllViews()

        selectedFilePaths.forEachIndexed { index, path ->
            val file = File(path)
            val fileName = path.substringAfterLast('/')
            val fileSize = formatFileSize(file.length())

            val itemView = layoutInflater.inflate(R.layout.item_support_attachment, container, false)
            itemView.findViewById<TextView>(R.id.txtFileName).text = fileName
            itemView.findViewById<TextView>(R.id.txtFileSize).text = fileSize

            val btnRemove = itemView.findViewById<View>(R.id.btnRemoveAttachment)
            btnRemove.setOnClickListener {
                removeAttachment(index)
            }

            if (isTv && btnRemove is ImageView) {
                val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
                val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
                btnRemove.imageTintList = whiteCsl
                btnRemove.setOnFocusChangeListener { _, hasFocus ->
                    btnRemove.imageTintList = if (hasFocus) yellowCsl else whiteCsl
                }
            }

            container.addView(itemView)
        }

        val btnAttach = findViewById<View>(R.id.layoutAttachment)
        val txtAttach = findViewById<TextView>(R.id.txtAttachment)
        val count = selectedFilePaths.size

        if (count >= MAX_ATTACHMENT_COUNT) {
            btnAttach.visibility = View.GONE
        } else {
            btnAttach.visibility = View.VISIBLE
            txtAttach.text = if (count == 0) {
                getString(R.string.support_attach_file)
            } else {
                "${getString(R.string.support_attach_file)} ($count/$MAX_ATTACHMENT_COUNT)"
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    // ─── Validation ─────────────────────────────────────────────

    private fun validate(): Boolean {
        var valid = true
        val subject = findViewById<TextInputEditText>(R.id.edtSubject).text?.toString()?.trim() ?: ""
        val message = findViewById<TextInputEditText>(R.id.edtMessage).text?.toString()?.trim() ?: ""
        val email = findViewById<TextInputEditText>(R.id.edtEmail).text?.toString()?.trim() ?: ""

        // Subject is required for bug and feature
        if ((currentType == "bug" || currentType == "feature") && subject.isEmpty()) {
            findViewById<TextInputLayout>(R.id.inputSubject).error = getString(R.string.field_required)
            valid = false
        } else {
            findViewById<TextInputLayout>(R.id.inputSubject).error = null
        }

        // Message is required for all types
        if (message.isEmpty()) {
            findViewById<TextInputLayout>(R.id.inputMessage).error = getString(R.string.field_required)
            valid = false
        } else {
            findViewById<TextInputLayout>(R.id.inputMessage).error = null
        }

        // Validate email format if provided
        if (email.isNotEmpty() && !isValidEmail(email)) {
            findViewById<TextInputLayout>(R.id.inputEmail).error = getString(R.string.invalid_email_format)
            valid = false
        } else {
            findViewById<TextInputLayout>(R.id.inputEmail).error = null
        }

        // File size check for each attached file
        selectedFilePaths.forEach { path ->
            val file = File(path)
            if (file.length() > MAX_ATTACHMENT_SIZE) {
                Toast.makeText(this, R.string.support_file_too_large, Toast.LENGTH_LONG).show()
                valid = false
            }
        }

        return valid
    }

    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    // ─── Submission ─────────────────────────────────────────────

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun submitForm() {
        if (!isInternetAvailable()) {
            MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(R.string.support_error_title)
                .setMessage(R.string.support_no_internet)
                .setPositiveButton(R.string.support_retry) { _, _ -> submitForm() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (!validate()) return

        val subject = findViewById<TextInputEditText>(R.id.edtSubject).text?.toString()?.trim() ?: ""
        val message = findViewById<TextInputEditText>(R.id.edtMessage).text?.toString()?.trim() ?: ""
        val steps = findViewById<TextInputEditText>(R.id.edtSteps).text?.toString()?.trim() ?: ""
        val useCase = findViewById<TextInputEditText>(R.id.edtUseCase).text?.toString()?.trim() ?: ""
        val email = findViewById<TextInputEditText>(R.id.edtEmail).text?.toString()?.trim() ?: ""
        val honeypot = findViewById<TextInputEditText>(R.id.edtHoneypot).text?.toString()?.trim() ?: ""
        val timestamp = System.currentTimeMillis() / 1000

        showLoading(true)

        // Build multipart request
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("type", currentType)
            .addFormDataPart("subject", subject)
            .addFormDataPart("message", message)
            .addFormDataPart("timestamp", timestamp.toString())
            .addFormDataPart("app_version", BuildConfig.VERSION_NAME)
            .addFormDataPart("app_code", BuildConfig.VERSION_CODE.toString())
            .addFormDataPart("sdk_version", Build.VERSION.SDK_INT.toString())
            .addFormDataPart("manufacturer", Build.MANUFACTURER)
            .addFormDataPart("device_model", Build.MODEL)
            .addFormDataPart("is_tv", if (isTv) "1" else "0")
            .addFormDataPart("package_name", BuildConfig.APPLICATION_ID)
            .addFormDataPart("honeypot", honeypot)

        // Optional fields
        if (steps.isNotEmpty()) bodyBuilder.addFormDataPart("steps", steps)
        if (useCase.isNotEmpty()) bodyBuilder.addFormDataPart("use_case", useCase)
        if (email.isNotEmpty()) bodyBuilder.addFormDataPart("email", email)

        // Attachments — keys must match PHP: attachment, attachment2, attachment3, attachment4, attachment5
        selectedFilePaths.forEachIndexed { index, path ->
            val file = File(path)
            val mediaType = guessMediaType(file.extension).toMediaType()
            val reqBody = file.asRequestBody(mediaType)
            val key = if (index == 0) "attachment" else "attachment${index + 1}"
            bodyBuilder.addFormDataPart(key, file.name, reqBody)
        }

        val requestBody = bodyBuilder.build()
        val request = Request.Builder()
            .url(SUPPORT_ENDPOINT)
            .post(requestBody)
            .build()

        // Execute on IO dispatcher
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    when (response.code) {
                        200 -> {
                            persistRememberedEmail(email)
                            showSuccessDialog()
                        }
                        429 -> showRateLimitedDialog()
                        else -> showErrorDialog()
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showErrorDialog()
                }
            }
        }
    }

    /**
     * Applies the remembered-email persistence on a successful send (HTTP 200):
     * - checked + non-empty email → save the email (FR-03/FR-05)
     * - unchecked                 → purge the stored email (FR-04)
     * - checked + empty email     → no-op; stored email retained as-is (spec edge case)
     */
    private fun persistRememberedEmail(email: String) {
        val checked = findViewById<CheckBox>(R.id.chkRememberEmail).isChecked
        when {
            checked && email.isNotEmpty() -> SupportEmailPrefs.saveEmail(this, email)
            !checked -> SupportEmailPrefs.purge(this)
        }
    }

    private fun showLoading(visible: Boolean) {
        findViewById<View>(R.id.btnSend).isEnabled = !visible
        val btnSendText = if (isTv) {
            findViewById<Button>(R.id.btnSend)
        } else {
            findViewById<MaterialButton>(R.id.btnSend)
        }
        btnSendText.text = if (visible) getString(R.string.support_sending) else getString(R.string.support_send)
    }

    // ─── Dialogs ────────────────────────────────────────────────

    private fun showCustomSupportDialog(
        iconRes: Int,
        iconTintRes: Int,
        title: String,
        message: String,
        positiveText: String,
        negativeText: String? = null,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null
    ) {
        val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
        val customView = layoutInflater.inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(customView)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
            android.graphics.Color.TRANSPARENT
        ))

        val imgIcon = customView.findViewById<ImageView>(R.id.imgDialogIcon)
        imgIcon.setImageResource(iconRes)
        imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(iconTintRes))

        customView.findViewById<TextView>(R.id.txtDialogTitle).text = title
        customView.findViewById<TextView>(R.id.txtDialogMessage).text = message

        val btnPositive = customView.findViewById<View>(R.id.btnDialogPositive)
        if (btnPositive is TextView) btnPositive.text = positiveText

        if (isTv && btnPositive is Button) {
            val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            val defaultCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            btnPositive.setTextColor(defaultCsl)
            btnPositive.setOnFocusChangeListener { _, hasFocus ->
                btnPositive.setTextColor(if (hasFocus) yellowCsl else defaultCsl)
                btnPositive.setBackgroundResource(
                    if (hasFocus) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button
                )
            }
        }

        btnPositive.setOnClickListener {
            dialog.dismiss()
            onPositive?.invoke()
        }

        val btnNegative = customView.findViewById<View>(R.id.btnDialogNegative)
        if (!negativeText.isNullOrEmpty()) {
            btnNegative.visibility = View.VISIBLE
            if (btnNegative is TextView) btnNegative.text = negativeText

            if (isTv && btnNegative is Button) {
                val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
                val defaultCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_secondary))
                btnNegative.setTextColor(defaultCsl)
                btnNegative.setOnFocusChangeListener { _, hasFocus ->
                    btnNegative.setTextColor(if (hasFocus) yellowCsl else defaultCsl)
                    btnNegative.setBackgroundResource(
                        if (hasFocus) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button
                    )
                }
            }

            btnNegative.setOnClickListener {
                dialog.dismiss()
                onNegative?.invoke()
            }
        } else {
            btnNegative.visibility = View.GONE
        }

        dialog.show()
        if (isTv) {
            btnPositive.requestFocus()
        }
    }

    private fun showSuccessDialog() {
        showCustomSupportDialog(
            iconRes = R.drawable.ic_success,
            iconTintRes = R.color.ufm_progress_fill,
            title = getString(R.string.support_success_title),
            message = getString(R.string.support_success_message),
            positiveText = getString(android.R.string.ok),
            onPositive = { showOptions() }
        )
    }

    private fun showErrorDialog() {
        showCustomSupportDialog(
            iconRes = R.drawable.ic_warning,
            iconTintRes = R.color.ufm_progress_critical,
            title = getString(R.string.support_error_title),
            message = getString(R.string.support_error_message),
            positiveText = getString(R.string.support_retry),
            negativeText = getString(android.R.string.cancel),
            onPositive = { submitForm() }
        )
    }

    private fun showRateLimitedDialog() {
        showCustomSupportDialog(
            iconRes = R.drawable.ic_warning,
            iconTintRes = R.color.ufm_progress_warning,
            title = getString(R.string.support_error_title),
            message = getString(R.string.support_rate_limited),
            positiveText = getString(android.R.string.ok)
        )
    }

    private fun showDiscardDialog() {
        showCustomSupportDialog(
            iconRes = R.drawable.ic_warning,
            iconTintRes = R.color.ufm_progress_warning,
            title = getString(R.string.support_discard_title),
            message = getString(R.string.support_discard_message),
            positiveText = getString(R.string.support_discard_confirm),
            negativeText = getString(android.R.string.cancel),
            onPositive = { showOptions() }
        )
    }

    // ─── Back Navigation ────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val formVisible = findViewById<View>(R.id.layoutForm).visibility == View.VISIBLE
        if (formVisible && hasUnsavedData()) {
            showDiscardDialog()
        } else if (formVisible) {
            showOptions()
        } else {
            super.onBackPressed()
        }
    }

    private fun hasUnsavedData(): Boolean {
        val subject = findViewById<TextInputEditText>(R.id.edtSubject).text?.toString()?.trim() ?: ""
        val message = findViewById<TextInputEditText>(R.id.edtMessage).text?.toString()?.trim() ?: ""
        val email = findViewById<TextInputEditText>(R.id.edtEmail).text?.toString()?.trim() ?: ""
        // A pristine pre-filled remembered email is not "unsaved" (FR-10)
        val emailChanged = email.isNotEmpty() && email != rememberedEmailValue
        return subject.isNotEmpty() || message.isNotEmpty() || emailChanged || selectedFilePaths.isNotEmpty()
    }

    // ─── Helpers ────────────────────────────────────────────────

    private fun guessMediaType(extension: String): String =
        za.kilowatch.ultimatefilemanager.util.MimeTypeHelper.getOrFallback(extension)

    companion object {
        private const val SUPPORT_ENDPOINT = "https://www.kilowatch.co.za/UFM/api/support.php"
        private const val MAX_ATTACHMENT_SIZE = 10L * 1024 * 1024 // 10MB
        private const val MAX_ATTACHMENT_COUNT = 5
    }
}
