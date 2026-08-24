package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.billing.AutoBackupPrefs
import za.kilowatch.ultimatefilemanager.billing.AutoBackupScheduler
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

class AutoBackupActivity : AppCompatActivity() {

    private var isTv = false
    private var isUpdatingLocationUI = false
    private var isUpdatingScheduleUI = false
    private var handledFontChange = false
    private var handledLocaleChange = false

    // Views
    private lateinit var switchAutoBackup: SwitchMaterial
    private lateinit var chkBackupSettings: CheckBox
    private lateinit var chkBackupTheme: CheckBox
    private lateinit var toggleSchedule: MaterialButtonToggleGroup
    private lateinit var btnDaily: MaterialButton
    private lateinit var btnWeekly: MaterialButton
    private lateinit var btnMonthly: MaterialButton
    private lateinit var txtPasswordTitle: TextView
    private lateinit var txtPasswordStatus: TextView
    private lateinit var txtSummary: TextView
    private lateinit var btnBackupNow: View
    private lateinit var progressBar: ProgressBar
    private lateinit var toggleLocation: MaterialButtonToggleGroup
    private lateinit var btnLocationDefault: MaterialButton
    private lateinit var btnLocationCustom: MaterialButton
    private lateinit var txtLocationPath: TextView
    private lateinit var txtLocationWarning: TextView
    private lateinit var btnSelectFolder: View
    private lateinit var btnResetLocation: View

    private val backupFolderPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val localPath = data?.getStringExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
            val netShareId = data?.getStringExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.RESULT_SELECTED_SHARE_ID)
            val netPath = data?.getStringExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.RESULT_SELECTED_NET_PATH)
            if (localPath != null) {
                AutoBackupPrefs.setCustomLocationType(this, "local")
                AutoBackupPrefs.setCustomLocalPath(this, localPath)
                AutoBackupPrefs.setCustomShareId(this, "")
                AutoBackupPrefs.setCustomNetPath(this, "")
                Toast.makeText(this, getString(R.string.auto_backup_location_success, localPath), Toast.LENGTH_SHORT).show()
            } else if (netShareId != null) {
                AutoBackupPrefs.setCustomLocationType(this, "network")
                AutoBackupPrefs.setCustomShareId(this, netShareId)
                AutoBackupPrefs.setCustomNetPath(this, netPath ?: "")
                AutoBackupPrefs.setCustomLocalPath(this, "")
                Toast.makeText(this, R.string.auto_backup_select_folder, Toast.LENGTH_SHORT).show()
            }
            updateLocationUI()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        handledFontChange = savedInstanceState?.getBoolean("font_handled", false) ?: false
        handledLocaleChange = savedInstanceState?.getBoolean("locale_handled", false) ?: false
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            setContentView(R.layout.activity_auto_backup_tv)
        } else {
            setContentView(R.layout.activity_auto_backup)
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

        bindViews()
        setupViews()
        loadSettings()
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

    private fun bindViews() {
        switchAutoBackup = findViewById(R.id.switchAutoBackup)
        chkBackupSettings = findViewById(R.id.chkBackupSettings)
        chkBackupTheme = findViewById(R.id.chkBackupTheme)
        toggleSchedule = findViewById(R.id.toggleSchedule)
        btnDaily = findViewById(R.id.btnDaily)
        btnWeekly = findViewById(R.id.btnWeekly)
        btnMonthly = findViewById(R.id.btnMonthly)
        txtPasswordTitle = findViewById(R.id.txtPasswordTitle)
        txtPasswordStatus = findViewById(R.id.txtPasswordStatus)
        txtSummary = findViewById(R.id.txtAutoBackupSummary)
        btnBackupNow = findViewById(R.id.btnBackupNow)
        progressBar = findViewById(R.id.progressBar)
        toggleLocation = findViewById(R.id.toggleLocation)
        btnLocationDefault = findViewById(R.id.btnLocationDefault)
        btnLocationCustom = findViewById(R.id.btnLocationCustom)
        txtLocationPath = findViewById(R.id.txtLocationPath)
        txtLocationWarning = findViewById(R.id.txtLocationWarning)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        btnResetLocation = findViewById(R.id.btnResetLocation)
    }

    private fun setupViews() {
        val primaryColor = if (isTv) getColor(R.color.tv_button_focused_yellow) else ThemeColors.primary(this)
        val onPrimaryColor = if (isTv) getColor(R.color.tv_button_focused_yellow_text) else ThemeColors.onPrimary(this)

        if (!isTv) {
            // Apply theme colors to section headers
            findViewById<TextView?>(R.id.labelSectionGeneral)?.setTextColor(primaryColor)
            findViewById<TextView?>(R.id.labelSectionSchedule)?.setTextColor(primaryColor)
            findViewById<TextView?>(R.id.labelSectionSecurity)?.setTextColor(primaryColor)
            findViewById<TextView?>(R.id.labelSectionLocation)?.setTextColor(primaryColor)
        }

        setupToggleButtons()

        // ── Back button ──────────────────────────────────────────────────
        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        // ── Enable toggle row ────────────────────────────────────────────
        val cardEnable = findViewById<View>(R.id.cardAutoBackupEnable)
        cardEnable.setOnClickListener {
            switchAutoBackup.isChecked = !switchAutoBackup.isChecked
            onEnabledChanged(switchAutoBackup.isChecked)
        }

        // ── Settings checkbox row ────────────────────────────────────────
        val cardSettings = findViewById<View>(R.id.cardAutoBackupSettings)
        cardSettings.setOnClickListener {
            chkBackupSettings.isChecked = !chkBackupSettings.isChecked
            onSettingsChanged(chkBackupSettings.isChecked)
        }

        // ── Theme checkbox row ───────────────────────────────────────────
        val cardTheme = findViewById<View>(R.id.cardAutoBackupTheme)
        cardTheme.setOnClickListener {
            chkBackupTheme.isChecked = !chkBackupTheme.isChecked
            onThemeChanged(chkBackupTheme.isChecked)
        }

        // ── Schedule ToggleGroup ─────────────────────────────────────────
        toggleSchedule.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingScheduleUI || !isChecked) return@addOnButtonCheckedListener
            val type = when (checkedId) {
                R.id.btnDaily -> "daily"
                R.id.btnMonthly -> "monthly"
                else -> "weekly"
            }
            onScheduleChanged(type)
        }

        // ── Password card ────────────────────────────────────────────────
        val cardPassword = findViewById<View>(R.id.cardAutoBackupPassword)
        cardPassword.setOnClickListener { showPasswordDialog() }

        // ── Location ToggleGroup ─────────────────────────────────────────
        toggleLocation.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingLocationUI || !isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.btnLocationCustom) {
                if (!AutoBackupPrefs.isCustomLocationSet(this)) {
                    showFolderGuideDialog()
                }
                updateLocationUI()
            } else {
                AutoBackupPrefs.clearCustomLocation(this)
                updateLocationUI()
            }
        }

        // ── Select Folder Button ─────────────────────────────────────────
        btnSelectFolder.setOnClickListener { showFolderGuideDialog() }

        // ── Reset to Default Button ──────────────────────────────────────
        btnResetLocation.setOnClickListener { resetLocationToDefault() }

        // ── Backup Now button ────────────────────────────────────────────
        btnBackupNow.setOnClickListener { performBackupNow() }

        if (isTv) {
            cardEnable.requestFocus()
        }
    }

    private fun loadSettings() {
        switchAutoBackup.isChecked = AutoBackupPrefs.isEnabled(this)
        chkBackupSettings.isChecked = AutoBackupPrefs.isBackupSettings(this)
        chkBackupTheme.isChecked = AutoBackupPrefs.isBackupTheme(this)

        isUpdatingScheduleUI = true
        when (AutoBackupPrefs.getScheduleType(this)) {
            "daily" -> toggleSchedule.check(R.id.btnDaily)
            "monthly" -> toggleSchedule.check(R.id.btnMonthly)
            else -> toggleSchedule.check(R.id.btnWeekly)
        }
        isUpdatingScheduleUI = false

        updatePasswordStatus()
        updateLocationUI()
        updateSummary()

        // Enable/disable controls based on master toggle
        setControlsEnabled(switchAutoBackup.isChecked)
    }

    // ── Event handlers ─────────────────────────────────────────────────────

    private fun onEnabledChanged(enabled: Boolean) {
        AutoBackupPrefs.setEnabled(this, enabled)
        setControlsEnabled(enabled)
        if (enabled) {
            AutoBackupScheduler.schedule(this, AutoBackupPrefs.getScheduleType(this))
        } else {
            AutoBackupScheduler.cancel(this)
        }
        updateSummary()
    }

    private fun onSettingsChanged(enabled: Boolean) {
        AutoBackupPrefs.setBackupSettings(this, enabled)
        updateSummary()
    }

    private fun onThemeChanged(enabled: Boolean) {
        AutoBackupPrefs.setBackupTheme(this, enabled)
        updateSummary()
    }

    private fun onScheduleChanged(type: String) {
        AutoBackupPrefs.setScheduleType(this, type)
        if (AutoBackupPrefs.isEnabled(this)) {
            AutoBackupScheduler.schedule(this, type)
        }
        updateSummary()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.45f
        chkBackupSettings.isEnabled = enabled
        chkBackupTheme.isEnabled = enabled
        toggleSchedule.isEnabled = enabled
        btnDaily.isEnabled = enabled
        btnWeekly.isEnabled = enabled
        btnMonthly.isEnabled = enabled
        findViewById<View?>(R.id.cardAutoBackupSettings)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        findViewById<View?>(R.id.cardAutoBackupTheme)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        findViewById<View?>(R.id.cardSchedule)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        findViewById<View?>(R.id.cardAutoBackupPassword)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        findViewById<View?>(R.id.cardAutoBackupLocation)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        toggleLocation.isEnabled = enabled
        btnLocationDefault.isEnabled = enabled
        btnLocationCustom.isEnabled = enabled
        btnSelectFolder.isEnabled = enabled
        btnResetLocation.isEnabled = enabled
        btnBackupNow.isEnabled = enabled
        btnBackupNow.alpha = alpha
    }

    // ── Password dialog ────────────────────────────────────────────────────

    private fun showPasswordDialog() {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_backup_password_tv else R.layout.dialog_backup_password,
            null
        )

        val tilPassword = dialogView.findViewById<TextInputLayout>(R.id.tilPassword)
        val tilConfirm = dialogView.findViewById<TextInputLayout>(R.id.tilConfirmPassword)
        val edtPassword = dialogView.findViewById<TextInputEditText>(R.id.edtPassword)
        val edtConfirm = dialogView.findViewById<TextInputEditText>(R.id.edtConfirmPassword)
        val btnEncrypt = dialogView.findViewById<Button>(R.id.btnEncrypt)
        val btnSkip = dialogView.findViewById<Button>(R.id.btnSkip)

        // If a password is already set, pre-fill hint
        if (AutoBackupPrefs.isUsePassword(this)) {
            tilPassword.hint = getString(R.string.auto_backup_password_change)
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

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
            AutoBackupPrefs.setPassword(this, pw)
            AutoBackupPrefs.setUsePassword(this, true)
            updatePasswordStatus()
            updateSummary()
        }

        btnSkip.setOnClickListener {
            dialog.dismiss()
            showSkipConfirmDialog()
        }

        dialog.show()

        if (isTv) {
            val yellow = getColor(R.color.tv_button_focused_yellow)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            btnEncrypt.backgroundTintList = ColorStateList.valueOf(yellow)
            btnEncrypt.setTextColor(black)
            btnEncrypt.setOnFocusChangeListener { _, hasFocus ->
                btnEncrypt.backgroundTintList =
                    if (hasFocus) ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                    else ColorStateList.valueOf(yellow)
            }
            btnEncrypt.requestFocus()
        }
    }

    private fun showSkipConfirmDialog() {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_backup_skip_confirm_tv else R.layout.dialog_backup_skip_confirm,
            null
        )

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnConfirmSkip = dialogView.findViewById<Button>(R.id.btnConfirmSkip)
            ?: dialogView.findViewById(R.id.btnSaveUnencrypted)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        btnConfirmSkip?.setOnClickListener {
            AutoBackupPrefs.setPassword(this, null)
            AutoBackupPrefs.setUsePassword(this, false)
            updatePasswordStatus()
            updateSummary()
            dialog.dismiss()
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── Folder Guidance Dialog ─────────────────────────────────────────────

    private fun showFolderGuideDialog() {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_backup_folder_guide_tv else R.layout.dialog_backup_folder_guide,
            null
        )

        val cardCurrentLocation = dialogView.findViewById<View?>(R.id.cardCurrentLocation)
        val txtCurrentPath = dialogView.findViewById<TextView>(R.id.txtCurrentPath)
        val btnBrowse = dialogView.findViewById<Button>(R.id.btnBrowse)
        val btnResetDefault = dialogView.findViewById<Button>(R.id.btnResetDefault)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val isCustom = AutoBackupPrefs.isCustomLocationSet(this)
        if (isCustom) {
            txtCurrentPath.text = AutoBackupPrefs.getBackupDirectoryDisplayPath(this)
            cardCurrentLocation?.visibility = View.VISIBLE
            btnResetDefault.visibility = View.VISIBLE
        } else {
            cardCurrentLocation?.visibility = View.GONE
            btnResetDefault.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnBrowse.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java).apply {
                putExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_AUTO_BACKUP_FOLDER_PICKER, true)
            }
            backupFolderPickerLauncher.launch(intent)
        }

        btnResetDefault.setOnClickListener {
            dialog.dismiss()
            resetLocationToDefault()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            updateLocationUI()
        }

        dialog.show()
    }

    private fun resetLocationToDefault() {
        AutoBackupPrefs.clearCustomLocation(this)
        updateLocationUI()
        Toast.makeText(this, R.string.auto_backup_reset_success, Toast.LENGTH_SHORT).show()
    }

    // ── UI updates ─────────────────────────────────────────────────────────

    private fun updatePasswordStatus() {
        if (AutoBackupPrefs.isUsePassword(this)) {
            txtPasswordTitle.setText(R.string.auto_backup_password_change)
            txtPasswordStatus.setText(R.string.auto_backup_password_status_set)
        } else {
            txtPasswordTitle.setText(R.string.auto_backup_password_set)
            txtPasswordStatus.setText(R.string.auto_backup_password_status_none)
        }
    }

    private fun updateSummary() {
        if (!AutoBackupPrefs.isEnabled(this)) {
            txtSummary.visibility = View.GONE
            return
        }
        txtSummary.visibility = View.VISIBLE

        val items = mutableListOf<String>()
        if (AutoBackupPrefs.isBackupSettings(this)) items.add(getString(R.string.auto_backup_summary_items_settings))
        if (AutoBackupPrefs.isBackupTheme(this)) items.add(getString(R.string.auto_backup_summary_items_theme))
        val itemsStr = if (items.isEmpty()) getString(R.string.auto_backup_summary_items_settings)
            else items.joinToString(" + ")

        val scheduleStr = when (AutoBackupPrefs.getScheduleType(this)) {
            "daily" -> getString(R.string.auto_backup_schedule_daily)
            "monthly" -> getString(R.string.auto_backup_schedule_monthly)
            else -> getString(R.string.auto_backup_schedule_weekly)
        }

        val passwordStr = if (AutoBackupPrefs.isUsePassword(this))
            getString(R.string.auto_backup_password_status_set)
        else
            getString(R.string.auto_backup_password_status_none)

        txtSummary.text = getString(R.string.auto_backup_summary_on, "$itemsStr — $scheduleStr", passwordStr)
    }

    // ── Save Location UI ─────────────────────────────────────────────────────

    private fun updateLocationUI() {
        isUpdatingLocationUI = true
        val isCustomConfigured = AutoBackupPrefs.isCustomLocationSet(this)
        val isCustomToggled = (toggleLocation.checkedButtonId == R.id.btnLocationCustom) || isCustomConfigured
        if (isCustomToggled) {
            toggleLocation.check(R.id.btnLocationCustom)
        } else {
            toggleLocation.check(R.id.btnLocationDefault)
        }
        isUpdatingLocationUI = false

        if (isCustomToggled) {
            btnSelectFolder.visibility = View.VISIBLE

            if (isCustomConfigured) {
                val path = AutoBackupPrefs.getBackupDirectoryDisplayPath(this)
                txtLocationPath.text = path
                txtLocationPath.visibility = View.VISIBLE
                btnResetLocation.visibility = View.VISIBLE

                if (!AutoBackupPrefs.isCustomLocationAvailable(this)) {
                    txtLocationWarning.setText(R.string.auto_backup_location_unavailable)
                    txtLocationWarning.visibility = View.VISIBLE
                } else {
                    txtLocationWarning.visibility = View.GONE
                }
            } else {
                txtLocationPath.visibility = View.GONE
                txtLocationWarning.visibility = View.GONE
                btnResetLocation.visibility = View.GONE
            }
        } else {
            txtLocationPath.visibility = View.GONE
            txtLocationWarning.visibility = View.GONE
            btnSelectFolder.visibility = View.GONE
            btnResetLocation.visibility = View.GONE
        }
    }

    // ── Backup Now ─────────────────────────────────────────────────────────

    private fun performBackupNow() {
        progressBar.visibility = View.VISIBLE
        btnBackupNow.isEnabled = false

        AutoBackupScheduler.runOnceNow(this)

        btnBackupNow.postDelayed({
            progressBar.visibility = View.GONE
            btnBackupNow.isEnabled = true
            Toast.makeText(this, R.string.auto_backup_success, Toast.LENGTH_SHORT).show()
        }, 1500)
    }

    private fun setupToggleButtons() {
        val toggleButtons = listOf(btnDaily, btnWeekly, btnMonthly, btnLocationDefault, btnLocationCustom)
        if (isTv) {
            val yellowBg = getColor(R.color.tv_button_focused_yellow)
            val glassBg = getColor(R.color.tv_glass_white_10)
            val blackText = getColor(R.color.tv_button_focused_yellow_text)
            val whiteText = getColor(R.color.tv_text_primary)
            val strokeColor = getColor(R.color.tv_glass_border)

            val tvBgCsl = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    yellowBg,
                    yellowBg,
                    glassBg
                )
            )
            val tvTextCsl = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    blackText,
                    blackText,
                    whiteText
                )
            )
            val tvStrokeCsl = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    yellowBg,
                    yellowBg,
                    strokeColor
                )
            )

            for (btn in toggleButtons) {
                btn.backgroundTintList = tvBgCsl
                btn.setTextColor(tvTextCsl)
                btn.strokeColor = tvStrokeCsl
                btn.strokeWidth = (1.5f * resources.displayMetrics.density).toInt()
                btn.isFocusable = true
                btn.isClickable = true
            }
        } else {
            val primaryColor = ThemeColors.primary(this)
            val onPrimaryColor = ThemeColors.onPrimary(this)
            val glassBgColor = getColor(R.color.mobile_glass_white_10)
            val textPrimaryColor = getColor(R.color.mobile_card_text_primary)

            val toggleBgCsl = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(
                    primaryColor,
                    glassBgColor
                )
            )
            val toggleTextCsl = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(
                    onPrimaryColor,
                    textPrimaryColor
                )
            )

            for (btn in toggleButtons) {
                btn.backgroundTintList = toggleBgCsl
                btn.setTextColor(toggleTextCsl)
            }
        }
    }
}
