package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Settings Activity for configuring APK / XAPK enhanced extraction and manifest metadata.
 * Follows the Language and Grouped Glass Card design standard.
 */
class ExtractApkSettingsActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var contentLayout: LinearLayout

    private data class FieldItem(
        val key: String,
        val titleRes: Int,
        val descRes: Int,
        val iconRes: Int
    )

    private val metadataFields = listOf(
        FieldItem("package_name", R.string.field_package_name, R.string.field_package_name_desc, R.drawable.ic_apps),
        FieldItem("version_name", R.string.field_version_name, R.string.field_version_name_desc, R.drawable.ic_about),
        FieldItem("version_code", R.string.field_version_code, R.string.field_version_code_desc, R.drawable.ic_about),
        FieldItem("label", R.string.field_label, R.string.field_label_desc, R.drawable.ic_edit),
        FieldItem("extracted_date", R.string.field_extracted_date, R.string.field_extracted_date_desc, R.drawable.ic_time),
        FieldItem("install_time", R.string.field_install_time, R.string.field_install_time_desc, R.drawable.ic_time),
        FieldItem("last_update_time", R.string.field_last_update_time, R.string.field_last_update_time_desc, R.drawable.ic_time),
        FieldItem("target_sdk", R.string.field_target_sdk, R.string.field_target_sdk_desc, R.drawable.ic_shield_check),
        FieldItem("min_sdk", R.string.field_min_sdk, R.string.field_min_sdk_desc, R.drawable.ic_shield_check),
        FieldItem("app_size", R.string.field_app_size, R.string.field_app_size_desc, R.drawable.ic_storage_internal),
        FieldItem("source_dir", R.string.field_source_dir, R.string.field_source_dir_desc, R.drawable.ic_folder),
        FieldItem("split_apks", R.string.field_split_apks, R.string.field_split_apks_desc, R.drawable.ic_file_apk),
        FieldItem("has_obb", R.string.field_has_obb, R.string.field_has_obb_desc, R.drawable.ic_file_archive),
        FieldItem("permissions", R.string.field_permissions, R.string.field_permissions_desc, R.drawable.ic_policy)
    )

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
        val btnResetDefaults = findViewById<View?>(R.id.btnResetDefaults)
        contentLayout = findViewById(R.id.contentLayout)

        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))

            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }

            if (btnResetDefaults is ImageView) {
                btnResetDefaults.imageTintList = whiteCsl
                btnResetDefaults.setOnFocusChangeListener { _, hasFocus ->
                    btnResetDefaults.imageTintList = if (hasFocus) blackCsl else whiteCsl
                }
            }
        }

        btnBack?.setOnClickListener { finish() }
        btnResetDefaults?.setOnClickListener { showResetConfirmDialog() }

        buildLayout()
    }

    private fun buildLayout() {
        val count = contentLayout.childCount
        if (count > 1) {
            contentLayout.removeViews(1, count - 1)
        }

        if (isTv) {
            buildTvLayout()
        } else {
            buildMobileLayout()
        }
    }

    private fun buildMobileLayout() {
        val inflater = LayoutInflater.from(this)
        val isMasterEnabled = ApkExtractPreferenceManager.isEnabled(this)
        val isIconEnabled = ApkExtractPreferenceManager.isExtractIcon(this)

        // Section 1: Extraction Features
        contentLayout.addView(createSectionHeader(R.string.settings_apk_extract_section_general))
        val generalCard = createGlassCard()
        val generalContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Row 1: Master Enhanced Extraction
        val rowMaster = inflater.inflate(R.layout.item_extract_apk_toggle_row, generalContainer, false)
        rowMaster.findViewById<ImageView>(R.id.imgIcon).setImageResource(R.drawable.ic_file_apk)
        rowMaster.findViewById<TextView>(R.id.txtTitle).setText(R.string.apk_extract_master_title)
        val txtMasterSub = rowMaster.findViewById<TextView>(R.id.txtSubtitle)
        txtMasterSub.setText(if (isMasterEnabled) R.string.apk_extract_master_subtitle_on else R.string.apk_extract_master_subtitle_off)
        val swMaster = rowMaster.findViewById<SwitchMaterial>(R.id.switchToggle)
        swMaster.isChecked = isMasterEnabled

        swMaster.setOnCheckedChangeListener { _, isChecked ->
            ApkExtractPreferenceManager.setEnabled(this, isChecked)
            txtMasterSub.setText(if (isChecked) R.string.apk_extract_master_subtitle_on else R.string.apk_extract_master_subtitle_off)
            buildLayout()
        }
        rowMaster.setOnClickListener { swMaster.isChecked = !swMaster.isChecked }
        generalContainer.addView(rowMaster)

        if (isMasterEnabled) {
            generalContainer.addView(createDivider())

            // Row 2: Extract App Icon
            val rowIcon = inflater.inflate(R.layout.item_extract_apk_toggle_row, generalContainer, false)
            rowIcon.findViewById<ImageView>(R.id.imgIcon).setImageResource(R.drawable.ic_apps)
            rowIcon.findViewById<TextView>(R.id.txtTitle).setText(R.string.apk_extract_icon_title)
            rowIcon.findViewById<TextView>(R.id.txtSubtitle).setText(R.string.apk_extract_icon_summary)
            val swIcon = rowIcon.findViewById<SwitchMaterial>(R.id.switchToggle)
            swIcon.isChecked = isIconEnabled

            swIcon.setOnCheckedChangeListener { _, isChecked ->
                ApkExtractPreferenceManager.setExtractIcon(this, isChecked)
            }
            rowIcon.setOnClickListener { swIcon.isChecked = !swIcon.isChecked }
            generalContainer.addView(rowIcon)
        }

        generalCard.addView(generalContainer)
        contentLayout.addView(generalCard)

        // Section 2: Metadata Fields (only when master enabled)
        if (isMasterEnabled) {
            contentLayout.addView(createSectionHeader(R.string.settings_apk_extract_metadata_header))
            val metaCard = createGlassCard()
            val metaContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            metadataFields.forEachIndexed { index, field ->
                val row = inflater.inflate(R.layout.item_extract_apk_check_row, metaContainer, false)
                row.findViewById<ImageView>(R.id.imgIcon).setImageResource(field.iconRes)
                row.findViewById<TextView>(R.id.txtTitle).setText(field.titleRes)
                row.findViewById<TextView>(R.id.txtSubtitle).setText(field.descRes)
                val cb = row.findViewById<MaterialCheckBox>(R.id.cbSelect)
                cb.isChecked = ApkExtractPreferenceManager.isFieldSelected(this, field.key)

                cb.setOnCheckedChangeListener { _, _ ->
                    ApkExtractPreferenceManager.toggleField(this, field.key)
                }
                row.setOnClickListener { cb.isChecked = !cb.isChecked }

                metaContainer.addView(row)

                if (index < metadataFields.size - 1) {
                    metaContainer.addView(createDivider())
                }
            }
            metaCard.addView(metaContainer)
            contentLayout.addView(metaCard)
        }
    }

    private fun buildTvLayout() {
        val inflater = LayoutInflater.from(this)
        val isMasterEnabled = ApkExtractPreferenceManager.isEnabled(this)
        val isIconEnabled = ApkExtractPreferenceManager.isExtractIcon(this)

        // Section 1: Extraction Features
        contentLayout.addView(createSectionHeader(R.string.settings_apk_extract_section_general))

        // Master Card
        val cardMaster = inflater.inflate(R.layout.item_extract_apk_toggle_card_tv, contentLayout, false) as MaterialCardView
        cardMaster.findViewById<ImageView>(R.id.imgIcon).setImageResource(R.drawable.ic_file_apk)
        cardMaster.findViewById<TextView>(R.id.txtLabel).setText(R.string.apk_extract_master_title)
        val txtMasterSub = cardMaster.findViewById<TextView>(R.id.txtSubtitle)
        txtMasterSub.setText(if (isMasterEnabled) R.string.apk_extract_master_subtitle_on else R.string.apk_extract_master_subtitle_off)
        val swMaster = cardMaster.findViewById<SwitchMaterial>(R.id.switchToggle)
        swMaster.isChecked = isMasterEnabled

        swMaster.setOnCheckedChangeListener { _, isChecked ->
            ApkExtractPreferenceManager.setEnabled(this, isChecked)
            txtMasterSub.setText(if (isChecked) R.string.apk_extract_master_subtitle_on else R.string.apk_extract_master_subtitle_off)
            buildLayout()
        }
        cardMaster.setOnClickListener { swMaster.isChecked = !swMaster.isChecked }
        setupTvCardFocus(cardMaster)
        contentLayout.addView(cardMaster)

        if (isMasterEnabled) {
            // Icon Card
            val cardIcon = inflater.inflate(R.layout.item_extract_apk_toggle_card_tv, contentLayout, false) as MaterialCardView
            cardIcon.findViewById<ImageView>(R.id.imgIcon).setImageResource(R.drawable.ic_apps)
            cardIcon.findViewById<TextView>(R.id.txtLabel).setText(R.string.apk_extract_icon_title)
            cardIcon.findViewById<TextView>(R.id.txtSubtitle).setText(R.string.apk_extract_icon_summary)
            val swIcon = cardIcon.findViewById<SwitchMaterial>(R.id.switchToggle)
            swIcon.isChecked = isIconEnabled

            swIcon.setOnCheckedChangeListener { _, isChecked ->
                ApkExtractPreferenceManager.setExtractIcon(this, isChecked)
            }
            cardIcon.setOnClickListener { swIcon.isChecked = !swIcon.isChecked }
            setupTvCardFocus(cardIcon)
            contentLayout.addView(cardIcon)

            // Section 2: Metadata Fields
            contentLayout.addView(createSectionHeader(R.string.settings_apk_extract_metadata_header))

            for (field in metadataFields) {
                val cardField = inflater.inflate(R.layout.item_extract_apk_check_card_tv, contentLayout, false) as MaterialCardView
                cardField.findViewById<ImageView>(R.id.imgIcon).setImageResource(field.iconRes)
                cardField.findViewById<TextView>(R.id.txtLabel).setText(field.titleRes)
                cardField.findViewById<TextView>(R.id.txtSubtitle).setText(field.descRes)
                val cb = cardField.findViewById<MaterialCheckBox>(R.id.cbSelect)
                cb.isChecked = ApkExtractPreferenceManager.isFieldSelected(this, field.key)

                cb.setOnCheckedChangeListener { _, _ ->
                    ApkExtractPreferenceManager.toggleField(this, field.key)
                }
                cardField.setOnClickListener { cb.isChecked = !cb.isChecked }
                setupTvCardFocus(cardField)
                contentLayout.addView(cardField)
            }
        }
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondText = getColor(R.color.tv_text_secondary)

        val txtLabel = card.findViewById<TextView>(R.id.txtLabel)
        val txtSubtitle = card.findViewById<TextView>(R.id.txtSubtitle)
        val imgIcon = card.findViewById<ImageView>(R.id.imgIcon)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                txtLabel?.setTextColor(blackText)
                txtSubtitle?.setTextColor(blackText)
                imgIcon?.imageTintList = ColorStateList.valueOf(blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                txtLabel?.setTextColor(primaryText)
                txtSubtitle?.setTextColor(secondText)
                imgIcon?.imageTintList = ColorStateList.valueOf(getColor(R.color.tv_accent))
            }
        }
    }

    private fun createSectionHeader(titleRes: Int): TextView {
        return TextView(this).apply {
            setText(titleRes)
            setTextColor(ThemeColors.primary(this@ExtractApkSettingsActivity))
            textSize = 13f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            isAllCaps = true
            letterSpacing = 0.05f
            val density = resources.displayMetrics.density
            setPadding(
                (4 * density).toInt(),
                (14 * density).toInt(),
                (4 * density).toInt(),
                (8 * density).toInt()
            )
        }
    }

    private fun createGlassCard(): MaterialCardView {
        val density = resources.displayMetrics.density
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            radius = 16 * density
            strokeWidth = (1 * density).toInt()
            strokeColor = getColor(R.color.mobile_glass_stroke)
            setCardBackgroundColor(getColor(R.color.mobile_glass_card))
            cardElevation = 0f
        }
    }

    private fun createDivider(): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                marginStart = (14 * density).toInt()
                marginEnd = (14 * density).toInt()
            }
            setBackgroundColor(getColor(R.color.mobile_glass_stroke))
        }
    }

    private fun showResetConfirmDialog() {
        val layoutRes = if (isTv) {
            R.layout.dialog_apk_extract_reset_confirm_tv
        } else {
            R.layout.dialog_apk_extract_reset_confirm
        }

        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)
        val btnResetConfirm = dialogView.findViewById<View>(R.id.btnResetConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnResetConfirm.setOnClickListener {
            dialog.dismiss()
            ApkExtractPreferenceManager.resetToDefaults(this)
            Toast.makeText(this, R.string.settings_apk_extract_reset_toast, Toast.LENGTH_SHORT).show()
            buildLayout()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            btnCancel.requestFocus()
        }
    }
}
