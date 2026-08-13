package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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

/**
 * Activity to manage which icons are shown in the selection action bar.
 */
class ToolbarIconsActivity : AppCompatActivity() {

    private var isTv = false

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
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        populateIconsList()
    }

    private fun populateIconsList() {
        val container = findViewById<LinearLayout>(R.id.layoutIconsContainer)
        val inflater = LayoutInflater.from(this)

        data class IconItem(val iconResId: Int, val nameResId: Int, val prefKey: String)

        val items = mutableListOf(
            IconItem(R.drawable.ic_create_new, R.string.cd_create_new, ToolbarIconsPreferenceManager.KEY_CREATE_NEW),
            IconItem(R.drawable.ic_copy, R.string.action_copy, ToolbarIconsPreferenceManager.KEY_COPY),
            IconItem(R.drawable.ic_move, R.string.action_move, ToolbarIconsPreferenceManager.KEY_MOVE),
            IconItem(R.drawable.ic_rename, R.string.action_rename, ToolbarIconsPreferenceManager.KEY_RENAME),
            IconItem(R.drawable.ic_share, R.string.action_share, ToolbarIconsPreferenceManager.KEY_SHARE),
            IconItem(R.drawable.ic_copy_encrypt, R.string.action_copy_encrypt, ToolbarIconsPreferenceManager.KEY_COPY_ENCRYPT),
            IconItem(R.drawable.ic_move_encrypt, R.string.action_move_encrypt, ToolbarIconsPreferenceManager.KEY_MOVE_ENCRYPT),
            IconItem(R.drawable.ic_star, R.string.action_favorite, ToolbarIconsPreferenceManager.KEY_FAVORITE),
            IconItem(R.drawable.ic_eye_off, R.string.hide, ToolbarIconsPreferenceManager.KEY_HIDE),
            IconItem(R.drawable.ic_eye, R.string.unhide, ToolbarIconsPreferenceManager.KEY_UNHIDE),
            IconItem(R.drawable.ic_shield_protected, R.string.protect, ToolbarIconsPreferenceManager.KEY_PROTECT),
            IconItem(R.drawable.ic_shield_unprotected, R.string.unprotect, ToolbarIconsPreferenceManager.KEY_UNPROTECT),
            IconItem(R.drawable.ic_check, R.string.action_select_all, ToolbarIconsPreferenceManager.KEY_SELECT_ALL),
            IconItem(R.drawable.ic_compress, R.string.action_compress, ToolbarIconsPreferenceManager.KEY_COMPRESS),
            IconItem(R.drawable.ic_extract, R.string.action_extract_here, ToolbarIconsPreferenceManager.KEY_EXTRACT),
            IconItem(R.drawable.ic_compress_image, R.string.action_compress_image, ToolbarIconsPreferenceManager.KEY_IMAGE_COMPRESS),
            IconItem(R.drawable.ic_photo_video, R.string.action_retrigger_thumbnails, ToolbarIconsPreferenceManager.KEY_RETRIGGER_THUMBNAILS),
            IconItem(R.drawable.ic_delete, R.string.action_delete, ToolbarIconsPreferenceManager.KEY_DELETE),
            IconItem(R.drawable.ic_paperclip, R.string.pin, ToolbarIconsPreferenceManager.KEY_PIN),
            IconItem(R.drawable.ic_paperclip_off, R.string.unpin, ToolbarIconsPreferenceManager.KEY_UNPIN),
            IconItem(R.drawable.ic_duplicate_finder, R.string.action_duplicate_finder, ToolbarIconsPreferenceManager.KEY_DUPLICATE_FINDER),
            IconItem(R.drawable.ic_folder_large_files, R.string.action_large_files_finder, ToolbarIconsPreferenceManager.KEY_LARGE_FILES_FINDER),
            IconItem(R.drawable.ic_wallpaper_home, R.string.action_set_home_wallpaper, ToolbarIconsPreferenceManager.KEY_SET_HOME_WALLPAPER),
            IconItem(R.drawable.ic_wallpaper_lock, R.string.action_set_lock_wallpaper, ToolbarIconsPreferenceManager.KEY_SET_LOCK_WALLPAPER),
            IconItem(R.drawable.ic_gif, R.string.action_create_gif, ToolbarIconsPreferenceManager.KEY_CREATE_GIF)
        )

        if (!isTv) {
            items.add(
                items.indexOfFirst { it.prefKey == ToolbarIconsPreferenceManager.KEY_SELECT_ALL } + 1,
                IconItem(R.drawable.ic_invert_selection, R.string.action_invert_selection, ToolbarIconsPreferenceManager.KEY_INVERT_SELECTION)
            )
        }


        for (item in items) {
            val view = if (isTv) {
                inflater.inflate(R.layout.item_toolbar_icon_tv, container, false)
            } else {
                inflater.inflate(R.layout.item_toolbar_icon, container, false)
            }

            val imgIcon = view.findViewById<ImageView>(R.id.imgIcon)
            val txtName = view.findViewById<TextView>(R.id.txtName)
            val switchToggle = view.findViewById<SwitchMaterial>(R.id.switchToggle)

            imgIcon.setImageResource(item.iconResId)
            // Apply custom icon override from IconCustomizationManager
            val iconId = when (item.prefKey) {
                ToolbarIconsPreferenceManager.KEY_CREATE_NEW -> "toolbar_create_new"
                ToolbarIconsPreferenceManager.KEY_COPY -> "toolbar_copy"
                ToolbarIconsPreferenceManager.KEY_MOVE -> "toolbar_move"
                ToolbarIconsPreferenceManager.KEY_RENAME -> "toolbar_rename"
                ToolbarIconsPreferenceManager.KEY_SHARE -> "toolbar_share"
                ToolbarIconsPreferenceManager.KEY_COPY_ENCRYPT -> "toolbar_copy_encrypt"
                ToolbarIconsPreferenceManager.KEY_MOVE_ENCRYPT -> "toolbar_move_encrypt"
                ToolbarIconsPreferenceManager.KEY_FAVORITE -> "toolbar_favorite"
                ToolbarIconsPreferenceManager.KEY_HIDE -> "toolbar_hide"
                ToolbarIconsPreferenceManager.KEY_UNHIDE -> "toolbar_unhide"
                ToolbarIconsPreferenceManager.KEY_PROTECT -> "toolbar_protect"
                ToolbarIconsPreferenceManager.KEY_UNPROTECT -> "toolbar_unprotect"
                ToolbarIconsPreferenceManager.KEY_SELECT_ALL -> "toolbar_select_all"
                ToolbarIconsPreferenceManager.KEY_INVERT_SELECTION -> "toolbar_invert_selection"
                ToolbarIconsPreferenceManager.KEY_COMPRESS -> "toolbar_compress"
                ToolbarIconsPreferenceManager.KEY_IMAGE_COMPRESS -> "toolbar_image_compress"
                ToolbarIconsPreferenceManager.KEY_CREATE_GIF -> "toolbar_create_gif"
                ToolbarIconsPreferenceManager.KEY_RETRIGGER_THUMBNAILS -> "toolbar_retrigger_thumbnails"
                ToolbarIconsPreferenceManager.KEY_DELETE -> "toolbar_delete"
                ToolbarIconsPreferenceManager.KEY_PIN -> "toolbar_pin"
                ToolbarIconsPreferenceManager.KEY_UNPIN -> "toolbar_unpin"
                else -> null
            }
            if (iconId != null) {
                IconCustomizationManager.applyToView(this, imgIcon, iconId, item.iconResId)
            }
            txtName.setText(item.nameResId)

            val isEnabled = ToolbarIconsPreferenceManager.isIconEnabled(this, item.prefKey)
            switchToggle.isChecked = isEnabled

            // Save state when the switch changes
            switchToggle.setOnCheckedChangeListener { _, isChecked ->
                ToolbarIconsPreferenceManager.setIconEnabled(this, item.prefKey, isChecked)
            }

            // For mobile, make the entire row clickable to toggle the switch
            if (!isTv) {
                view.setOnClickListener {
                    switchToggle.isChecked = !switchToggle.isChecked
                }
            } else {
                // For TV, the MaterialCardView is focusable and clickable
                (view as? MaterialCardView)?.let { card ->
                    card.setOnClickListener {
                        switchToggle.isChecked = !switchToggle.isChecked
                    }
                    setupTvCardFocus(card)
                }
            }

            container.addView(view)
        }
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val innerLayout = card.findViewById<View>(R.id.layoutInner)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                innerLayout?.setBackgroundColor(yellowFill)
                setChildTextColors(card, blackText)
                setChildIconTint(card, blackText)
            } else {
                innerLayout?.setBackgroundResource(R.drawable.bg_glass_card)
                setChildTextColors(card, primaryText)
                setChildIconTint(card, getColor(R.color.tv_icon_tint))
            }
        }
    }

    private fun setChildTextColors(view: View, color: Int) {
        if (view is TextView) { view.setTextColor(color); return }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setChildTextColors(view.getChildAt(i), color)
        }
    }

    private fun setChildIconTint(view: View, color: Int) {
        if (view is ImageView) {
            view.imageTintList = android.content.res.ColorStateList.valueOf(color)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setChildIconTint(view.getChildAt(i), color)
        }
    }
}
