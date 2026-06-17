package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.billing.AutoBackupPrefs
import za.kilowatch.ultimatefilemanager.billing.AutoBackupScheduler
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class AutoBackupActivity : AppCompatActivity() {

    private var isTv = false

    // Views
    private lateinit var switchAutoBackup: SwitchMaterial
    private lateinit var chkBackupSettings: CheckBox
    private lateinit var chkBackupTheme: CheckBox
    private lateinit var radioSchedule: RadioGroup
    private lateinit var rbDaily: RadioButton
    private lateinit var rbWeekly: RadioButton
    private lateinit var rbMonthly: RadioButton
    private lateinit var txtPasswordTitle: TextView
    private lateinit var txtPasswordStatus: TextView
    private lateinit var txtSummary: TextView
    private lateinit var btnBackupNow: View
    private lateinit var progressBar: ProgressBar

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
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

    private fun bindViews() {
        switchAutoBackup = findViewById(R.id.switchAutoBackup)
        chkBackupSettings = findViewById(R.id.chkBackupSettings)
        chkBackupTheme = findViewById(R.id.chkBackupTheme)
        radioSchedule = findViewById(R.id.radioSchedule)
        rbDaily = findViewById(R.id.rbDaily)
        rbWeekly = findViewById(R.id.rbWeekly)
        rbMonthly = findViewById(R.id.rbMonthly)
        txtPasswordTitle = findViewById(R.id.txtPasswordTitle)
        txtPasswordStatus = findViewById(R.id.txtPasswordStatus)
        txtSummary = findViewById(R.id.txtAutoBackupSummary)
        btnBackupNow = findViewById(R.id.btnBackupNow)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupViews() {
        // ── Back button ──────────────────────────────────────────────────
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

        // ── Enable toggle card ───────────────────────────────────────────
        val cardEnable = findViewById<MaterialCardView>(R.id.cardAutoBackupEnable)
        cardEnable.setOnClickListener {
            switchAutoBackup.isChecked = !switchAutoBackup.isChecked
            onEnabledChanged(switchAutoBackup.isChecked)
        }

        // ── Settings checkbox card ───────────────────────────────────────
        val cardSettings = findViewById<MaterialCardView>(R.id.cardAutoBackupSettings)
        cardSettings.setOnClickListener {
            chkBackupSettings.isChecked = !chkBackupSettings.isChecked
            onSettingsChanged(chkBackupSettings.isChecked)
        }

        // ── Theme checkbox card ──────────────────────────────────────────
        val cardTheme = findViewById<MaterialCardView>(R.id.cardAutoBackupTheme)
        cardTheme.setOnClickListener {
            chkBackupTheme.isChecked = !chkBackupTheme.isChecked
            onThemeChanged(chkBackupTheme.isChecked)
        }

        // ── Schedule RadioGroup ──────────────────────────────────────────
        radioSchedule.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.rbDaily -> "daily"
                R.id.rbMonthly -> "monthly"
                else -> "weekly"
            }
            onScheduleChanged(type)
        }

        // ── Password card ────────────────────────────────────────────────
        val cardPassword = findViewById<MaterialCardView>(R.id.cardAutoBackupPassword)
        cardPassword.setOnClickListener { showPasswordDialog() }

        // ── Backup Now button ────────────────────────────────────────────
        btnBackupNow.setOnClickListener { performBackupNow() }

        // ── TV focus handling ────────────────────────────────────────────
        if (isTv) {
            setupTvCardFocus(cardEnable)
            setupTvCardFocus(cardSettings)
            setupTvCardFocus(cardTheme)
            setupTvCardFocus(cardPassword)
            setupTvButtonFocus(btnBackupNow)
        }
    }

    private fun loadSettings() {
        switchAutoBackup.isChecked = AutoBackupPrefs.isEnabled(this)
        chkBackupSettings.isChecked = AutoBackupPrefs.isBackupSettings(this)
        chkBackupTheme.isChecked = AutoBackupPrefs.isBackupTheme(this)

        when (AutoBackupPrefs.getScheduleType(this)) {
            "daily" -> rbDaily.isChecked = true
            "monthly" -> rbMonthly.isChecked = true
            else -> rbWeekly.isChecked = true
        }

        updatePasswordStatus()
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
        chkBackupSettings.isEnabled = enabled
        chkBackupTheme.isEnabled = enabled
        radioSchedule.isEnabled = enabled
        findViewById<MaterialCardView>(R.id.cardAutoBackupSettings).isEnabled = enabled
        findViewById<MaterialCardView>(R.id.cardAutoBackupTheme).isEnabled = enabled
        findViewById<MaterialCardView>(R.id.cardSchedule).isEnabled = enabled
        findViewById<MaterialCardView>(R.id.cardAutoBackupPassword).isEnabled = enabled
        btnBackupNow.isEnabled = enabled
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
            // Store password in EncryptedSharedPreferences
            AutoBackupPrefs.setPassword(this, pw)
            AutoBackupPrefs.setUsePassword(this, true)
            updatePasswordStatus()
            updateSummary()
        }

        btnSkip.setOnClickListener {
            dialog.dismiss()
            // Confirm plain text choice
            MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(R.string.backup_password_skip_title)
                .setMessage(R.string.backup_password_skip_warning)
                .setPositiveButton(R.string.save_unencrypted) { _, _ ->
                    AutoBackupPrefs.setPassword(this, null)
                    AutoBackupPrefs.setUsePassword(this, false)
                    updatePasswordStatus()
                    updateSummary()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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

    // ── Backup Now ─────────────────────────────────────────────────────────

    private fun performBackupNow() {
        progressBar.visibility = View.VISIBLE
        btnBackupNow.isEnabled = false

        // Enqueue a one-shot backup worker
        AutoBackupScheduler.runOnceNow(this)

        // Show progress briefly, then check result
        btnBackupNow.postDelayed({
            progressBar.visibility = View.GONE
            btnBackupNow.isEnabled = true
            Toast.makeText(this, R.string.auto_backup_success, Toast.LENGTH_SHORT).show()
        }, 1500)
    }

    // ── TV helpers ─────────────────────────────────────────────────────────

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondText = getColor(R.color.tv_text_secondary)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setChildTextColors(card, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setChildTextColorsTwo(card, primaryText, secondText)
            }
        }
    }

    private fun setupTvButtonFocus(btn: View) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val defaultBg = getColor(R.color.btn_save_bg_tint)
        val defaultText = getColor(android.R.color.white)

        btn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btn.setBackgroundTintList(ColorStateList.valueOf(yellowFill))
                if (btn is Button) btn.setTextColor(blackText)
            } else {
                btn.setBackgroundTintList(ColorStateList.valueOf(defaultBg))
                if (btn is Button) btn.setTextColor(defaultText)
            }
        }
    }

    private fun setChildTextColors(view: View, color: Int) {
        if (view is TextView) {
            view.setTextColor(color)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setChildTextColors(view.getChildAt(i), color)
            }
        }
    }

    private fun setChildTextColorsTwo(view: View, primary: Int, secondary: Int) {
        if (view is TextView) {
            view.setTextColor(if (view.textSize > resources.displayMetrics.density * 16) primary else secondary)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setChildTextColorsTwo(view.getChildAt(i), primary, secondary)
            }
        }
    }
}
