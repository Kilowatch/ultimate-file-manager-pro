package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.billing.AutoBackupScheduler
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors
import java.io.File
import javax.crypto.AEADBadTagException

class ImportDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BACKUP_PATH = "extra_backup_path"
    }

    private var isTv = false
    private var handledFontChange = false
    private var handledLocaleChange = false

    private var backupPath: String? = null
    private var parsedDetails: BackupDetails? = null
    private var importPasswordAttempts = 0
    private val MAX_IMPORT_ATTEMPTS = 3

    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var layoutContent: View
    private var rvImportPreview: RecyclerView? = null        // mobile only
    private var tvImportContainer: LinearLayout? = null      // TV only
    private lateinit var btnImportConfirm: MaterialButton

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        if (savedInstanceState != null) {
            handledFontChange = savedInstanceState.getBoolean("font_handled", false)
            handledLocaleChange = savedInstanceState.getBoolean("locale_handled", false)
        }

        if (isTv) {
            setContentView(R.layout.activity_import_details_tv)
        } else {
            setContentView(R.layout.activity_import_details)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        backupPath = intent.getStringExtra(EXTRA_BACKUP_PATH)
        if (backupPath.isNullOrEmpty()) {
            finish()
            return
        }

        setupViews()
        loadBackupFile()
    }

    override fun onResume() {
        super.onResume()
        if (LocaleHelper.restartPending && !handledLocaleChange) {
            handledLocaleChange = true
            recreate()
            return
        }
        if (FontSizeHelper.restartPending && !handledFontChange) {
            handledFontChange = true
            recreate()
            return
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("font_handled", handledFontChange)
        outState.putBoolean("locale_handled", handledLocaleChange)
    }

    private fun setupViews() {
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        layoutContent = findViewById(R.id.layoutContent)
        btnImportConfirm = findViewById(R.id.btnImportConfirm)

        if (isTv) {
            // TV: items are inflated into a LinearLayout inside NestedScrollView (no RecyclerView)
            tvImportContainer = findViewById(R.id.importPreviewContainer)
        } else {
            rvImportPreview = findViewById(R.id.rvImportPreview)
            rvImportPreview!!.layoutManager = LinearLayoutManager(this)
        }

        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val yellowCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) yellowCsl else whiteCsl
                if (hasFocus) {
                    btnBack.setBackgroundResource(R.drawable.bg_icon_circle_focused)
                } else {
                    btnBack.setBackgroundResource(R.drawable.bg_icon_circle_accent)
                }
            }
        }
        btnBack?.setOnClickListener { finish() }

        btnImportConfirm.setOnClickListener {
            val details = parsedDetails ?: return@setOnClickListener
            showImportConfirmationDialog(details)
        }

        if (isTv) {
            setupTvButtonFocus(btnImportConfirm)
        } else {
            val primaryColor = ThemeColors.primary(this)
            val onPrimaryColor = ThemeColors.onPrimary(this)
            btnImportConfirm.backgroundTintList = ColorStateList.valueOf(primaryColor)
            btnImportConfirm.setTextColor(onPrimaryColor)
        }
    }

    private fun loadBackupFile() {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(backupPath!!)
                if (!file.exists()) {
                    showError(getString(R.string.backup_import_error))
                    return@launch
                }

                val bytes = file.readBytes()
                val format = SettingsBackupManager.detectFormat(bytes)

                withContext(Dispatchers.Main) {
                    when (format) {
                        SettingsBackupManager.BackupFormat.V3_ENCRYPTED -> {
                            importPasswordAttempts = 0
                            showImportPasswordDialog(bytes) { password ->
                                decryptWithPassword(bytes, password)
                            }
                        }
                        SettingsBackupManager.BackupFormat.V3_PLAIN,
                        SettingsBackupManager.BackupFormat.V2_LEGACY,
                        SettingsBackupManager.BackupFormat.RAW_JSON -> {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val plainText = SettingsBackupManager.decryptBackup(bytes, null)
                                    parseAndPreview(plainText)
                                } catch (e: Exception) {
                                    showError(getString(R.string.backup_import_decrypt_error))
                                }
                            }
                        }
                        SettingsBackupManager.BackupFormat.UNKNOWN -> {
                            showError(getString(R.string.backup_import_unsupported_format))
                        }
                    }
                }
            } catch (e: Exception) {
                showError(getString(R.string.backup_import_error))
            }
        }
    }

    private fun showImportPasswordDialog(bytes: ByteArray, onPassword: (String) -> Unit) {
        val isTvDevice = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTvDevice) R.layout.dialog_backup_import_password_tv
            else R.layout.dialog_backup_import_password,
            null
        )

        val edtPassword = dialogView.findViewById<TextInputEditText>(R.id.edtPassword)
        val tilPassword = dialogView.findViewById<TextInputLayout>(R.id.tilPassword)
        val btnDecrypt = dialogView.findViewById<Button>(R.id.btnDecrypt)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener { finish() }
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (!isTvDevice) {
            val primaryColor = ThemeColors.primary(this)
            val onPrimaryColor = ThemeColors.onPrimary(this)
            (btnDecrypt as? MaterialButton)?.backgroundTintList = ColorStateList.valueOf(primaryColor)
            (btnDecrypt as? MaterialButton)?.setTextColor(onPrimaryColor)
        }

        btnDecrypt.setOnClickListener {
            val pw = edtPassword.text?.toString() ?: ""
            if (pw.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            onPassword(pw)
        }

        dialog.show()

        if (isTvDevice) {
            val yellow = getColor(R.color.tv_button_focused_yellow)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            btnDecrypt.backgroundTintList = ColorStateList.valueOf(yellow)
            btnDecrypt.setTextColor(black)
            btnDecrypt.setOnFocusChangeListener { _, hasFocus ->
                btnDecrypt.backgroundTintList =
                    if (hasFocus) ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                    else ColorStateList.valueOf(yellow)
            }
            btnDecrypt.requestFocus()
        }
    }

    private fun decryptWithPassword(bytes: ByteArray, password: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val plainText = SettingsBackupManager.decryptBackup(bytes, password)
                parseAndPreview(plainText)
            } catch (e: AEADBadTagException) {
                importPasswordAttempts++
                if (importPasswordAttempts >= MAX_IMPORT_ATTEMPTS) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ImportDetailsActivity,
                            R.string.backup_import_too_many_attempts, Toast.LENGTH_LONG).show()
                        finish()
                    }
                } else {
                    val remaining = MAX_IMPORT_ATTEMPTS - importPasswordAttempts
                    withContext(Dispatchers.Main) {
                        showError(getString(R.string.backup_import_wrong_password, remaining))
                        showImportPasswordDialog(bytes) { pw -> decryptWithPassword(bytes, pw) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError(getString(R.string.backup_import_decrypt_error))
                }
            }
        }
    }

    private suspend fun parseAndPreview(plainText: String) {
        val details = try {
            SettingsBackupManager.parseBackupContent(this@ImportDetailsActivity, plainText)
        } catch (e: Exception) {
            showError(getString(R.string.backup_import_invalid_error))
            return
        }

        val isEmpty = details.sharedPrefs.isEmpty() &&
                details.shares.isEmpty() &&
                details.storages.isEmpty() &&
                details.ftpProfiles.isEmpty() &&
                details.renames.isEmpty() &&
                details.smartSortConfigs.isEmpty() &&
                details.customTiles.isEmpty()

        if (isEmpty) {
            showError(getString(R.string.backup_import_details_empty))
            return
        }

        withContext(Dispatchers.Main) {
            parsedDetails = details
            if (isTv) {
                populateTvPreview(details)
            } else {
                val adapter = ImportDetailsAdapter(details)
                rvImportPreview!!.adapter = adapter
            }
            progressBar.visibility = View.GONE
            layoutContent.visibility = View.VISIBLE
            btnImportConfirm.requestFocus()
        }
    }

    private suspend fun showError(message: String) {
        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            tvError.text = message
            tvError.visibility = View.VISIBLE
        }
    }

    private fun showImportConfirmationDialog(details: BackupDetails) {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_backup_import_confirm_tv
            else R.layout.dialog_backup_import_confirm,
            null
        )

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnConfirm = dialogView.findViewById<View>(R.id.btnConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        if (!isTv) {
            val primaryColor = ThemeColors.primary(this)
            val onPrimaryColor = ThemeColors.onPrimary(this)
            (btnConfirm as? MaterialButton)?.backgroundTintList = ColorStateList.valueOf(primaryColor)
            (btnConfirm as? MaterialButton)?.setTextColor(onPrimaryColor)
            (btnConfirm as? MaterialButton)?.iconTint = ColorStateList.valueOf(onPrimaryColor)
        }

        btnConfirm.setOnClickListener {
            dialog.dismiss()
            performRestore(details)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            btnConfirm.requestFocus()
        }
    }

    private fun performRestore(details: BackupDetails) {
        progressBar.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val success = SettingsBackupManager.performRestore(this@ImportDetailsActivity, details)
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (success) {
                    // Trigger auto-backup of the newly restored state
                    AutoBackupScheduler.runOnceNow(this@ImportDetailsActivity)

                    Toast.makeText(this@ImportDetailsActivity, R.string.backup_import_success, Toast.LENGTH_SHORT).show()

                    // Self-restart application to reload preferences and db singletons cleanly
                    val pm = packageManager
                    val launchIntent = pm.getLaunchIntentForPackage(packageName)
                    val mainIntent = Intent.makeRestartActivityTask(launchIntent?.component)
                    startActivity(mainIntent)
                    Runtime.getRuntime().exit(0)
                } else {
                    layoutContent.visibility = View.VISIBLE
                    Toast.makeText(this@ImportDetailsActivity, R.string.backup_import_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * TV only: inflates each category group card directly into the NestedScrollView's
     * LinearLayout container so no individual item can receive D-pad focus.
     * Scrolling is handled by the focusable FrameLayout wrapper (same as the T&C screen).
     */
    private fun populateTvPreview(details: BackupDetails) {
        val container = tvImportContainer ?: return
        container.removeAllViews()
        val adapter = ImportDetailsAdapter(details)
        for (position in 0 until adapter.itemCount) {
            val vh = adapter.onCreateViewHolder(container, adapter.getItemViewType(position))
            adapter.onBindViewHolder(vh, position)
            // Remove any focus from the inflated card — this screen is read-only
            vh.itemView.isFocusable = false
            vh.itemView.isClickable = false
            (vh.itemView as? android.view.ViewGroup)?.descendantFocusability =
                android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            container.addView(vh.itemView)
        }
    }

    private fun setupTvButtonFocus(btn: MaterialButton) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText  = getColor(R.color.tv_button_focused_yellow_text)
        val defaultBg  = getColor(R.color.btn_save_bg_tint)
        val defaultText = getColor(android.R.color.white)

        btn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btn.backgroundTintList = ColorStateList.valueOf(yellowFill)
                btn.setTextColor(blackText)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(defaultBg)
                btn.setTextColor(defaultText)
            }
        }
    }
}
