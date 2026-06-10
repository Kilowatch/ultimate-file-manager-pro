package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class ExtractApkSettingsActivity : AppCompatActivity() {

    private var isTv = false

    private lateinit var switchMaster: SwitchMaterial
    private lateinit var txtMasterSubtitle: TextView
    private lateinit var layoutOptions: LinearLayout

    private lateinit var switchIcon: SwitchMaterial
    private lateinit var cardExtractIcon: MaterialCardView

    private data class FieldRow(
        val card: MaterialCardView,
        val check: CheckBox,
        val key: String
    )

    private val fieldRows = mutableListOf<FieldRow>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_extract_apk_settings_tv)
        } else {
            setContentView(R.layout.activity_extract_apk_settings)
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

        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        switchMaster = findViewById(R.id.switchMaster)
        txtMasterSubtitle = findViewById(R.id.txtMasterSubtitle)
        layoutOptions = findViewById(R.id.layoutOptions)
        switchIcon = findViewById(R.id.switchIcon)
        cardExtractIcon = findViewById(R.id.cardExtractIcon)

        val cardMasterToggle = findViewById<MaterialCardView>(R.id.cardMasterToggle)
        cardMasterToggle.setOnClickListener { toggleMaster() }
        switchMaster.setOnCheckedChangeListener(null)

        cardExtractIcon.setOnClickListener { toggleIcon() }
        switchIcon.setOnCheckedChangeListener(null)

        registerField(R.id.cardFieldPackageName, R.id.checkFieldPackageName, "package_name")
        registerField(R.id.cardFieldVersionName, R.id.checkFieldVersionName, "version_name")
        registerField(R.id.cardFieldVersionCode, R.id.checkFieldVersionCode, "version_code")
        registerField(R.id.cardFieldLabel, R.id.checkFieldLabel, "label")
        registerField(R.id.cardFieldExtractedDate, R.id.checkFieldExtractedDate, "extracted_date")
        registerField(R.id.cardFieldInstallTime, R.id.checkFieldInstallTime, "install_time")
        registerField(R.id.cardFieldLastUpdateTime, R.id.checkFieldLastUpdateTime, "last_update_time")
        registerField(R.id.cardFieldTargetSdk, R.id.checkFieldTargetSdk, "target_sdk")
        registerField(R.id.cardFieldMinSdk, R.id.checkFieldMinSdk, "min_sdk")
        registerField(R.id.cardFieldAppSize, R.id.checkFieldAppSize, "app_size")
        registerField(R.id.cardFieldSourceDir, R.id.checkFieldSourceDir, "source_dir")
        registerField(R.id.cardFieldSplitApks, R.id.checkFieldSplitApks, "split_apks")
        registerField(R.id.cardFieldHasObb, R.id.checkFieldHasObb, "has_obb")
        registerField(R.id.cardFieldPermissions, R.id.checkFieldPermissions, "permissions")

        if (isTv) {
            setupTvCardFocus(cardMasterToggle)
            setupTvCardFocus(cardExtractIcon)
            fieldRows.forEach { setupTvCardFocus(it.card) }
        }

        updateUi()
    }

    private fun registerField(cardId: Int, checkId: Int, key: String) {
        val card = findViewById<MaterialCardView>(cardId)
        val check = findViewById<CheckBox>(checkId)
        card.setOnClickListener { toggleField(key) }
        check.setOnCheckedChangeListener(null)
        fieldRows.add(FieldRow(card, check, key))
    }

    private fun toggleMaster() {
        val newValue = !switchMaster.isChecked
        switchMaster.isChecked = newValue
        ApkExtractPreferenceManager.setEnabled(this, newValue)
        updateUi()
    }

    private fun toggleIcon() {
        val newValue = !switchIcon.isChecked
        switchIcon.isChecked = newValue
        ApkExtractPreferenceManager.setExtractIcon(this, newValue)
    }

    private fun toggleField(key: String) {
        ApkExtractPreferenceManager.toggleField(this, key)
        updateFieldChecks()
    }

    private fun updateUi() {
        val enabled = ApkExtractPreferenceManager.isEnabled(this)
        switchMaster.isChecked = enabled
        txtMasterSubtitle.text = if (enabled) {
            getString(R.string.apk_extract_master_subtitle_on)
        } else {
            getString(R.string.apk_extract_master_subtitle_off)
        }
        layoutOptions.visibility = if (enabled) View.VISIBLE else View.GONE

        switchIcon.isChecked = ApkExtractPreferenceManager.isExtractIcon(this)
        updateFieldChecks()
    }

    private fun updateFieldChecks() {
        val fields = ApkExtractPreferenceManager.getSelectedFields(this)
        for (row in fieldRows) {
            row.check.isChecked = row.key in fields
        }
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill   = getColor(R.color.tv_button_focused_yellow)
        val blackText    = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor   = getColor(R.color.tv_glass_white_10)
        val primaryText  = getColor(R.color.tv_text_primary)
        val secondaryText = getColor(R.color.tv_text_secondary)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setCardTextColors(card, blackText, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setCardTextColors(card, primaryText, secondaryText)
            }
        }
    }

    private fun setCardTextColors(view: View, primaryColor: Int, secondaryColor: Int) {
        if (view is TextView) {
            val isSubtitle = view.textSize < resources.displayMetrics.density * 16
            view.setTextColor(if (isSubtitle) secondaryColor else primaryColor)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount)
                setCardTextColors(view.getChildAt(i), primaryColor, secondaryColor)
        }
    }
}
