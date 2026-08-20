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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Settings activity to configure which icons appear on the selection action bar / toolbar.
 * Follows the Language and Grouped Glass Card design standard.
 */
class ToolbarIconsActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var contentLayout: LinearLayout

    private data class ToolbarItem(
        val iconResId: Int,
        val nameResId: Int,
        val descResId: Int,
        val prefKey: String,
        val customIconKey: String? = null,
        val mobileOnly: Boolean = false
    )

    private data class ToolbarSection(
        val titleResId: Int,
        val items: List<ToolbarItem>
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
            setContentView(R.layout.activity_toolbar_icons_tv)
        } else {
            setContentView(R.layout.activity_toolbar_icons)
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

    private fun getSections(): List<ToolbarSection> {
        return listOf(
            ToolbarSection(
                R.string.toolbar_section_file_operations,
                listOf(
                    ToolbarItem(R.drawable.ic_create_new, R.string.cd_create_new, R.string.toolbar_desc_create_new, ToolbarIconsPreferenceManager.KEY_CREATE_NEW, "toolbar_create_new"),
                    ToolbarItem(R.drawable.ic_copy, R.string.action_copy, R.string.toolbar_desc_copy, ToolbarIconsPreferenceManager.KEY_COPY, "toolbar_copy"),
                    ToolbarItem(R.drawable.ic_move, R.string.action_move, R.string.toolbar_desc_move, ToolbarIconsPreferenceManager.KEY_MOVE, "toolbar_move"),
                    ToolbarItem(R.drawable.ic_rename, R.string.action_rename, R.string.toolbar_desc_rename, ToolbarIconsPreferenceManager.KEY_RENAME, "toolbar_rename"),
                    ToolbarItem(R.drawable.ic_share, R.string.action_share, R.string.toolbar_desc_share, ToolbarIconsPreferenceManager.KEY_SHARE, "toolbar_share"),
                    ToolbarItem(R.drawable.ic_delete, R.string.action_delete, R.string.toolbar_desc_delete, ToolbarIconsPreferenceManager.KEY_DELETE, "toolbar_delete")
                )
            ),
            ToolbarSection(
                R.string.toolbar_section_security,
                listOf(
                    ToolbarItem(R.drawable.ic_copy_encrypt, R.string.action_copy_encrypt, R.string.toolbar_desc_copy_encrypt, ToolbarIconsPreferenceManager.KEY_COPY_ENCRYPT, "toolbar_copy_encrypt"),
                    ToolbarItem(R.drawable.ic_move_encrypt, R.string.action_move_encrypt, R.string.toolbar_desc_move_encrypt, ToolbarIconsPreferenceManager.KEY_MOVE_ENCRYPT, "toolbar_move_encrypt"),
                    ToolbarItem(R.drawable.ic_shield_protected, R.string.protect, R.string.toolbar_desc_protect, ToolbarIconsPreferenceManager.KEY_PROTECT, "toolbar_protect"),
                    ToolbarItem(R.drawable.ic_shield_unprotected, R.string.unprotect, R.string.toolbar_desc_unprotect, ToolbarIconsPreferenceManager.KEY_UNPROTECT, "toolbar_unprotect"),
                    ToolbarItem(R.drawable.ic_eye_off, R.string.hide, R.string.toolbar_desc_hide, ToolbarIconsPreferenceManager.KEY_HIDE, "toolbar_hide"),
                    ToolbarItem(R.drawable.ic_eye, R.string.unhide, R.string.toolbar_desc_unhide, ToolbarIconsPreferenceManager.KEY_UNHIDE, "toolbar_unhide")
                )
            ),
            ToolbarSection(
                R.string.toolbar_section_selection_navigation,
                listOf(
                    ToolbarItem(R.drawable.ic_check, R.string.action_select_all, R.string.toolbar_desc_select_all, ToolbarIconsPreferenceManager.KEY_SELECT_ALL, "toolbar_select_all"),
                    ToolbarItem(R.drawable.ic_invert_selection, R.string.action_invert_selection, R.string.toolbar_desc_invert_selection, ToolbarIconsPreferenceManager.KEY_INVERT_SELECTION, "toolbar_invert_selection", mobileOnly = true),
                    ToolbarItem(R.drawable.ic_star, R.string.action_favorite, R.string.toolbar_desc_favorite, ToolbarIconsPreferenceManager.KEY_FAVORITE, "toolbar_favorite"),
                    ToolbarItem(R.drawable.ic_paperclip, R.string.pin, R.string.toolbar_desc_pin, ToolbarIconsPreferenceManager.KEY_PIN, "toolbar_pin"),
                    ToolbarItem(R.drawable.ic_paperclip_off, R.string.unpin, R.string.toolbar_desc_unpin, ToolbarIconsPreferenceManager.KEY_UNPIN, "toolbar_unpin")
                )
            ),
            ToolbarSection(
                R.string.toolbar_section_tools_media,
                listOf(
                    ToolbarItem(R.drawable.ic_compress, R.string.action_compress, R.string.toolbar_desc_compress, ToolbarIconsPreferenceManager.KEY_COMPRESS, "toolbar_compress"),
                    ToolbarItem(R.drawable.ic_extract, R.string.action_extract_here, R.string.toolbar_desc_extract, ToolbarIconsPreferenceManager.KEY_EXTRACT),
                    ToolbarItem(R.drawable.ic_compress_image, R.string.action_compress_image, R.string.toolbar_desc_compress_image, ToolbarIconsPreferenceManager.KEY_IMAGE_COMPRESS, "toolbar_image_compress"),
                    ToolbarItem(R.drawable.ic_photo_video, R.string.action_retrigger_thumbnails, R.string.toolbar_desc_retrigger_thumbnails, ToolbarIconsPreferenceManager.KEY_RETRIGGER_THUMBNAILS, "toolbar_retrigger_thumbnails"),
                    ToolbarItem(R.drawable.ic_gif, R.string.action_create_gif, R.string.toolbar_desc_create_gif, ToolbarIconsPreferenceManager.KEY_CREATE_GIF, "toolbar_create_gif"),
                    ToolbarItem(R.drawable.ic_exif_cleaner, R.string.action_exif_cleaner_renamer, R.string.toolbar_desc_exif_tools, ToolbarIconsPreferenceManager.KEY_EXIF_TOOLS, "toolbar_exif_cleaner", mobileOnly = true),
                    ToolbarItem(R.drawable.ic_wallpaper_home, R.string.action_set_home_wallpaper, R.string.toolbar_desc_set_home_wallpaper, ToolbarIconsPreferenceManager.KEY_SET_HOME_WALLPAPER, "toolbar_set_home_wallpaper"),
                    ToolbarItem(R.drawable.ic_wallpaper_lock, R.string.action_set_lock_wallpaper, R.string.toolbar_desc_set_lock_wallpaper, ToolbarIconsPreferenceManager.KEY_SET_LOCK_WALLPAPER, "toolbar_set_lock_wallpaper"),
                    ToolbarItem(R.drawable.ic_duplicate_finder, R.string.action_duplicate_finder, R.string.toolbar_desc_duplicate_finder, ToolbarIconsPreferenceManager.KEY_DUPLICATE_FINDER, "toolbar_duplicate_finder"),
                    ToolbarItem(R.drawable.ic_folder_large_files, R.string.action_large_files_finder, R.string.toolbar_desc_large_files_finder, ToolbarIconsPreferenceManager.KEY_LARGE_FILES_FINDER, "toolbar_large_files_finder")
                )
            )
        )
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
        val sections = getSections()

        for (section in sections) {
            val visibleItems = section.items.filter { !it.mobileOnly || !isTv }
            if (visibleItems.isEmpty()) continue

            contentLayout.addView(createSectionHeader(section.titleResId))
            val glassCard = createGlassCard()
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            visibleItems.forEachIndexed { index, item ->
                val row = inflater.inflate(R.layout.item_toolbar_icon_row, container, false)
                val imgIcon = row.findViewById<ImageView>(R.id.imgIcon)
                val txtName = row.findViewById<TextView>(R.id.txtName)
                val txtSubtitle = row.findViewById<TextView>(R.id.txtSubtitle)
                val switchToggle = row.findViewById<SwitchMaterial>(R.id.switchToggle)

                imgIcon.setImageResource(item.iconResId)
                if (item.customIconKey != null) {
                    IconCustomizationManager.applyToView(this, imgIcon, item.customIconKey, item.iconResId)
                }

                txtName.setText(item.nameResId)
                txtSubtitle.setText(item.descResId)

                val isEnabled = ToolbarIconsPreferenceManager.isIconEnabled(this, item.prefKey)
                switchToggle.isChecked = isEnabled

                switchToggle.setOnCheckedChangeListener { _, isChecked ->
                    ToolbarIconsPreferenceManager.setIconEnabled(this, item.prefKey, isChecked)
                }
                row.setOnClickListener { switchToggle.isChecked = !switchToggle.isChecked }

                container.addView(row)

                if (index < visibleItems.size - 1) {
                    container.addView(createDivider())
                }
            }

            glassCard.addView(container)
            contentLayout.addView(glassCard)
        }
    }

    private fun buildTvLayout() {
        val inflater = LayoutInflater.from(this)
        val sections = getSections()

        for (section in sections) {
            val visibleItems = section.items.filter { !it.mobileOnly }
            if (visibleItems.isEmpty()) continue

            contentLayout.addView(createSectionHeader(section.titleResId))

            for (item in visibleItems) {
                val card = inflater.inflate(R.layout.item_toolbar_icon_card_tv, contentLayout, false) as MaterialCardView
                val imgIcon = card.findViewById<ImageView>(R.id.imgIcon)
                val txtLabel = card.findViewById<TextView>(R.id.txtLabel)
                val txtSubtitle = card.findViewById<TextView>(R.id.txtSubtitle)
                val switchToggle = card.findViewById<SwitchMaterial>(R.id.switchToggle)

                imgIcon.setImageResource(item.iconResId)
                if (item.customIconKey != null) {
                    IconCustomizationManager.applyToView(this, imgIcon, item.customIconKey, item.iconResId)
                }

                txtLabel.setText(item.nameResId)
                txtSubtitle.setText(item.descResId)

                val isEnabled = ToolbarIconsPreferenceManager.isIconEnabled(this, item.prefKey)
                switchToggle.isChecked = isEnabled

                switchToggle.setOnCheckedChangeListener { _, isChecked ->
                    ToolbarIconsPreferenceManager.setIconEnabled(this, item.prefKey, isChecked)
                }
                card.setOnClickListener { switchToggle.isChecked = !switchToggle.isChecked }
                setupTvCardFocus(card)
                contentLayout.addView(card)
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
            setTextColor(ThemeColors.primary(this@ToolbarIconsActivity))
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
            R.layout.dialog_toolbar_icons_reset_confirm_tv
        } else {
            R.layout.dialog_toolbar_icons_reset_confirm
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
            ToolbarIconsPreferenceManager.resetToDefaults(this)
            Toast.makeText(this, R.string.settings_toolbar_icons_reset_toast, Toast.LENGTH_SHORT).show()
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
