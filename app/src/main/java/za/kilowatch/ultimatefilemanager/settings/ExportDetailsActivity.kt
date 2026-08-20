package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
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

class ExportDetailsActivity : AppCompatActivity() {

    private var isTv = false
    private var handledFontChange = false
    private var handledLocaleChange = false

    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var layoutContent: View
    private lateinit var rvExportPreview: RecyclerView
    private lateinit var btnExportConfirm: MaterialButton

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
            setContentView(R.layout.activity_export_details_tv)
        } else {
            setContentView(R.layout.activity_export_details)
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

        setupViews()
        loadExportableItems()
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
        rvExportPreview = findViewById(R.id.rvExportPreview)
        btnExportConfirm = findViewById(R.id.btnExportConfirm)

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

        rvExportPreview.layoutManager = LinearLayoutManager(this)

        if (isTv) {
            setupTvButtonFocus(btnExportConfirm)
        } else {
            val primaryColor = ThemeColors.primary(this)
            val onPrimaryColor = ThemeColors.onPrimary(this)
            btnExportConfirm.backgroundTintList = ColorStateList.valueOf(primaryColor)
            btnExportConfirm.setTextColor(onPrimaryColor)
        }
    }

    private fun loadExportableItems() {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val items = withContext(Dispatchers.IO) {
                    SettingsBackupManager.getAvailableBackupItems(this@ExportDetailsActivity)
                }

                if (items.isEmpty()) {
                    progressBar.visibility = View.GONE
                    tvError.text = getString(R.string.backup_no_exportable_items)
                    tvError.visibility = View.VISIBLE
                    return@launch
                }

                val adapter = ExportSelectionAdapter(items)
                rvExportPreview.adapter = adapter
                progressBar.visibility = View.GONE
                layoutContent.visibility = View.VISIBLE
                btnExportConfirm.requestFocus()

                btnExportConfirm.setOnClickListener {
                    performExport(items)
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvError.text = getString(R.string.backup_export_error)
                tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun performExport(items: List<BackupItem>) {
        val isTvDevice = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTvDevice) R.layout.dialog_backup_password_tv else R.layout.dialog_backup_password,
            null
        )

        val tilPassword = dialogView.findViewById<TextInputLayout>(R.id.tilPassword)
        val edtPassword = dialogView.findViewById<TextInputEditText>(R.id.edtPassword)
        val tilConfirm = dialogView.findViewById<TextInputLayout>(R.id.tilConfirmPassword)
        val edtConfirm = dialogView.findViewById<TextInputEditText>(R.id.edtConfirmPassword)
        val btnEncrypt = dialogView.findViewById<Button>(R.id.btnEncrypt)
        val btnSkip = dialogView.findViewById<Button>(R.id.btnSkip)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (!isTvDevice) {
            val primaryColor = ThemeColors.primary(this)
            val onPrimaryColor = ThemeColors.onPrimary(this)
            (btnEncrypt as? MaterialButton)?.backgroundTintList = ColorStateList.valueOf(primaryColor)
            (btnEncrypt as? MaterialButton)?.setTextColor(onPrimaryColor)
        }

        btnEncrypt.setOnClickListener {
            val pw = edtPassword.text?.toString() ?: ""
            val confirm = edtConfirm.text?.toString() ?: ""
            if (pw.length < 4) {
                tilPassword.error = getString(R.string.backup_password_too_short)
                return@setOnClickListener
            }
            if (pw != confirm) {
                tilConfirm.error = getString(R.string.backup_password_mismatch)
                return@setOnClickListener
            }
            tilPassword.error = null
            tilConfirm.error = null
            dialog.dismiss()
            doExport(items, pw)
        }

        btnSkip.setOnClickListener {
            dialog.dismiss()
            showSkipConfirmationDialog(items)
        }

        dialog.show()

        if (isTvDevice) {
            val yellow = getColor(R.color.tv_button_focused_yellow)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            val white = getColor(R.color.tv_text_primary)
            val glass = 0x26FFFFFF.toInt()

            btnEncrypt.backgroundTintList = ColorStateList.valueOf(yellow)
            btnEncrypt.setTextColor(black)
            btnEncrypt.setOnFocusChangeListener { _, hasFocus ->
                btnEncrypt.backgroundTintList =
                    if (hasFocus) ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                    else ColorStateList.valueOf(yellow)
            }

            btnSkip.backgroundTintList = ColorStateList.valueOf(glass)
            btnSkip.setTextColor(white)
            btnSkip.setOnFocusChangeListener { _, hasFocus ->
                btnSkip.backgroundTintList =
                    if (hasFocus) ColorStateList.valueOf(yellow)
                    else ColorStateList.valueOf(glass)
                btnSkip.setTextColor(if (hasFocus) black else white)
            }
            btnEncrypt.requestFocus()
        }
    }

    private fun showSkipConfirmationDialog(items: List<BackupItem>) {
        val isTvDevice = DeviceUtils.isTvDevice(this)
        val view = LayoutInflater.from(this).inflate(
            if (isTvDevice) R.layout.dialog_backup_skip_confirm_tv else R.layout.dialog_backup_skip_confirm,
            null
        )

        val confirmDialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(view)
            .setCancelable(true)
            .create()

        confirmDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnConfirmSkip = view.findViewById<Button>(R.id.btnConfirmSkip)

        if (!isTvDevice) {
            val primaryColor = ThemeColors.primary(this)
            val onPrimaryColor = ThemeColors.onPrimary(this)
            (btnConfirmSkip as? MaterialButton)?.backgroundTintList = ColorStateList.valueOf(primaryColor)
            (btnConfirmSkip as? MaterialButton)?.setTextColor(onPrimaryColor)
        }

        btnCancel.setOnClickListener {
            confirmDialog.dismiss()
        }

        btnConfirmSkip.setOnClickListener {
            confirmDialog.dismiss()
            doExport(items, null)
        }

        confirmDialog.show()
    }

    private fun doExport(items: List<BackupItem>, password: String?) {
        progressBar.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Main) {
            val targetFile = SettingsBackupManager.getBackupFile()
            val success = withContext(Dispatchers.IO) {
                SettingsBackupManager.performExport(this@ExportDetailsActivity, items, targetFile, password)
            }
            progressBar.visibility = View.GONE
            if (success) {
                // Mirror to Documents/UFM/ if auto-backup is enabled
                AutoBackupScheduler.runOnceNow(this@ExportDetailsActivity)

                val userPath = getString(R.string.backup_path_internal_storage, targetFile.name)
                val message = getString(R.string.backup_notification_file_saved, userPath)
                Toast.makeText(this@ExportDetailsActivity, message, Toast.LENGTH_LONG).show()
                finish()
            } else {
                layoutContent.visibility = View.VISIBLE
                Toast.makeText(this@ExportDetailsActivity, R.string.backup_export_error, Toast.LENGTH_LONG).show()
            }
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
