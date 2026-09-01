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
import za.kilowatch.ultimatefilemanager.storage.TileIconManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class IconPackExportActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var progressBar: View
    private lateinit var tvError: TextView
    private lateinit var layoutContent: View
    private lateinit var rvExportPreview: RecyclerView
    private lateinit var btnExportConfirm: MaterialButton

    data class CategorySelection(
        val categoryId: String,
        val label: String,
        val iconIds: List<String>,
        var isSelected: Boolean = true
    )

    private val categories = mutableListOf<CategorySelection>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            setContentView(R.layout.activity_icon_pack_export_tv)
        } else {
            setContentView(R.layout.activity_icon_pack_export)
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
        loadCategories()
    }

    private fun setupViews() {
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        layoutContent = findViewById(R.id.layoutContent)
        rvExportPreview = findViewById(R.id.rvExportPreview)
        btnExportConfirm = findViewById(R.id.btnExportConfirm)

        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        btnBack?.setOnClickListener { finish() }

        rvExportPreview.layoutManager = LinearLayoutManager(this)
        btnExportConfirm.setOnClickListener { performExport() }

        if (isTv) {
            setupTvButtonFocus(btnExportConfirm)
        }
    }

    private fun loadCategories() {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        layoutContent.visibility = View.GONE

        categories.clear()

        // File Types
        categories.add(CategorySelection(
            "file_types", getString(R.string.category_file_types),
            listOf("file_generic", "file_image", "file_video", "file_audio", "file_pdf",
                "file_word", "file_spreadsheet", "file_presentation", "file_apk", "file_archive",
                "file_code", "file_xml", "file_text", "file_font", "file_ebook", "file_iso",
                "file_database", "file_torrent", "file_subtitle", "file_3d", "file_backup")
        ))

        // Folders
        categories.add(CategorySelection(
            "folders", getString(R.string.category_folders),
            listOf("folder_default", "folder_network")
        ))

        // Toolbar
        categories.add(CategorySelection(
            "toolbar", getString(R.string.category_toolbar),
            listOf("toolbar_create_new", "toolbar_copy", "toolbar_move", "toolbar_rename", "toolbar_share",
                "toolbar_copy_encrypt", "toolbar_move_encrypt", "toolbar_favorite",
                "toolbar_hide", "toolbar_unhide", "toolbar_select_all", "toolbar_invert_selection", "toolbar_compress", "toolbar_extract",
                "toolbar_image_compress", "toolbar_create_gif", "toolbar_delete", "toolbar_pin", "toolbar_unpin", "toolbar_duplicate_finder",
                "toolbar_set_home_wallpaper", "toolbar_set_lock_wallpaper", "toolbar_exif_cleaner")
        ))


        // Navigation
        categories.add(CategorySelection(
            "navigation", getString(R.string.category_navigation),
            listOf("nav_back", "nav_forward", "nav_up")
        ))

        // Settings
        val settingsKeys = mutableListOf(
            "settings_search_bar", "settings_default_start_screen", "settings_language", "settings_appearance",
            "settings_icons", "settings_backup_restore", "settings_main_menu_layout",
            "settings_twin_window_layout", "settings_twin_window_startup",
            "settings_side_by_side_video", "settings_side_by_side_video_show_controls_on_repeat", "settings_breadcrumbs", "settings_default_apps",
            "settings_font_size", "settings_apk_extract", "settings_long_press",
            "settings_controls_timeout",
            "settings_toolbar_icons", "settings_favorites", "settings_custom_drive_names",
            "settings_file_server_tiles", "settings_hidden_files", "settings_recycle_bin",
            "settings_media_thumbnails", "settings_video_thumbnail_time",
            "settings_network_thumbnails", "settings_cache_copy", "settings_quick_transfer",
            "settings_network_open_cache", "settings_storage_indexer", "settings_root_access", "settings_analytics"
        )
        if (isTv) {
            settingsKeys.add("settings_tv_background_server")
        }
        categories.add(CategorySelection(
            "settings", getString(R.string.category_settings),
            settingsKeys
        ))

        // Main Menu Tiles (dynamic from TileIconManager)
        val tileIds = TileIconManager.getAllTileIcons(this).keys +
            TileIconManager.getAllTileIconRes(this).keys
        if (tileIds.isNotEmpty()) {
            categories.add(CategorySelection(
                "main_menu_tiles", getString(R.string.category_main_menu_tiles),
                tileIds.map { "tile_$it" }
            ))
        }

        // Media Player (10)
        categories.add(CategorySelection(
            "media_player", getString(R.string.category_media_player),
            listOf("media_play", "media_pause", "media_skip_next", "media_skip_previous",
                "media_shuffle", "media_repeat", "media_fullscreen",
                "media_fullscreen_exit", "media_volume", "media_volume_off")
        ))

        // Utility / Action (13)
        categories.add(CategorySelection(
            "utility", getString(R.string.category_utility),
            listOf("action_add", "action_settings", "action_close", "action_edit", "action_refresh",
                "action_save", "action_paste", "action_undo", "action_duplicate",
                "action_crop", "action_zoom_in", "action_zoom_out", "action_fit_screen",
                "network_dlna")
        ))

        // Status / Alert (5)
        categories.add(CategorySelection(
            "status", getString(R.string.category_status),
            listOf("status_warning", "status_warning_badge", "status_check_circle",
                "status_shield_check", "status_shield_alert")
        ))

        // View Modes (3)
        categories.add(CategorySelection(
            "view_modes", getString(R.string.category_view_modes),
            listOf("view_grid_small", "view_grid_medium", "view_grid_large")
        ))

        // Feature Tile Extras (11)
        categories.add(CategorySelection(
            "feature_tiles", getString(R.string.category_feature_tiles),
            listOf("badge_lightning", "badge_remove_circle", "badge_more_vert",
                "tile_history", "tile_import_code", "tile_refresh_custom",
                "tile_search_off", "tile_twin_window_off", "tile_vpn_warning",
                "tile_saf", "tile_visibility_off")
        ))

        progressBar.visibility = View.GONE
        if (categories.isEmpty()) {
            tvError.text = getString(R.string.icon_pack_export_error)
            tvError.visibility = View.VISIBLE
        } else {
            layoutContent.visibility = View.VISIBLE
            rvExportPreview.adapter = CategoryExportAdapter(categories)
        }
    }

    private fun performExport() {
        val selectedIconIds = categories
            .filter { it.isSelected }
            .flatMap { it.iconIds }
            .toSet()

        if (selectedIconIds.isEmpty()) {
            Toast.makeText(this, R.string.icon_pack_export_error, Toast.LENGTH_SHORT).show()
            return
        }

        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_theme_password_tv else R.layout.dialog_theme_password,
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

        btnEncrypt.setOnClickListener {
            val pw = edtPassword.text?.toString() ?: ""
            val confirm = edtConfirm.text?.toString() ?: ""
            if (pw.length < 4) {
                tilPassword.error = getString(R.string.theme_password_too_short)
                return@setOnClickListener
            }
            if (pw != confirm) {
                tilConfirm.error = getString(R.string.theme_password_mismatch)
                return@setOnClickListener
            }
            tilPassword.error = null
            tilConfirm.error = null
            dialog.dismiss()
            doExport(selectedIconIds, pw)
        }

        btnSkip.setOnClickListener {
            dialog.dismiss()
            showSkipConfirmationDialog(selectedIconIds)
        }

        dialog.show()

        if (isTv) {
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

    private fun showSkipConfirmationDialog(selectedIconIds: Set<String>) {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_theme_skip_confirm_tv else R.layout.dialog_theme_skip_confirm,
            null
        )

        val btnSaveUnencrypted = dialogView.findViewById<Button>(R.id.btnSaveUnencrypted)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val confirmDialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        confirmDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnSaveUnencrypted.setOnClickListener {
            confirmDialog.dismiss()
            doExport(selectedIconIds, null)
        }

        btnCancel.setOnClickListener {
            confirmDialog.dismiss()
        }

        confirmDialog.show()

        if (isTv) {
            val yellow = getColor(R.color.tv_button_focused_yellow)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            val white = getColor(R.color.tv_text_primary)
            val glass = 0x26FFFFFF.toInt()

            btnSaveUnencrypted.backgroundTintList = ColorStateList.valueOf(yellow)
            btnSaveUnencrypted.setTextColor(black)
            btnSaveUnencrypted.setOnFocusChangeListener { _, hasFocus ->
                btnSaveUnencrypted.backgroundTintList =
                    if (hasFocus) ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                    else ColorStateList.valueOf(yellow)
            }

            btnCancel.backgroundTintList = ColorStateList.valueOf(glass)
            btnCancel.setTextColor(white)
            btnCancel.setOnFocusChangeListener { _, hasFocus ->
                btnCancel.backgroundTintList =
                    if (hasFocus) ColorStateList.valueOf(yellow)
                    else ColorStateList.valueOf(glass)
                btnCancel.setTextColor(if (hasFocus) black else white)
            }
            btnSaveUnencrypted.requestFocus()
        }
    }

    private fun doExport(selectedIconIds: Set<String>, password: String?) {
        progressBar.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Main) {
            val success = withContext(Dispatchers.IO) {
                val targetFile = ThemePackManager.getDefaultExportFile()
                ThemePackManager.performExport(this@IconPackExportActivity, selectedIconIds, targetFile, password)
            }

            progressBar.visibility = View.GONE
            if (success) {
                // Mirror to Documents/UFM/ if auto-backup is enabled
                AutoBackupScheduler.runOnceNow(this@IconPackExportActivity)

                Toast.makeText(
                    this@IconPackExportActivity,
                    R.string.icon_pack_export_success,
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                layoutContent.visibility = View.VISIBLE
                Toast.makeText(
                    this@IconPackExportActivity,
                    R.string.icon_pack_export_error,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupTvButtonFocus(btn: MaterialButton) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val defaultBg = getColor(R.color.btn_save_bg_tint)
        val defaultText = getColor(android.R.color.white)

        btn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(yellowFill)
                btn.setTextColor(blackText)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(defaultBg)
                btn.setTextColor(defaultText)
            }
        }
    }
}
