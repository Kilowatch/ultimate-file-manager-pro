package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Bundle
import android.view.View
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
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
                "toolbar_hide", "toolbar_unhide", "toolbar_select_all", "toolbar_compress",
                "toolbar_image_compress", "toolbar_delete")
        ))

        // Navigation
        categories.add(CategorySelection(
            "navigation", getString(R.string.category_navigation),
            listOf("nav_back", "nav_forward", "nav_up")
        ))

        // Settings (28)
        categories.add(CategorySelection(
            "settings", getString(R.string.category_settings),
            listOf("settings_default_start_screen", "settings_language", "settings_appearance",
                "settings_icons", "settings_backup_restore", "settings_main_menu_layout",
                "settings_twin_window_layout", "settings_twin_window_startup",
                "settings_side_by_side_video", "settings_breadcrumbs", "settings_default_apps",
                "settings_font_size", "settings_apk_extract", "settings_long_press",
                "settings_toolbar_icons", "settings_favorites", "settings_custom_drive_names",
                "settings_file_server_tiles", "settings_hidden_files", "settings_recycle_bin",
                "settings_media_thumbnails", "settings_video_thumbnail_time",
                "settings_network_thumbnails", "settings_cache_copy", "settings_quick_transfer",
                "settings_network_open_cache", "settings_storage_indexer", "settings_analytics")
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
            listOf("action_add", "action_close", "action_edit", "action_refresh",
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
        progressBar.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Main) {
            val selectedIconIds = categories
                .filter { it.isSelected }
                .flatMap { it.iconIds }
                .toSet()

            val success = withContext(Dispatchers.IO) {
                if (selectedIconIds.isEmpty()) return@withContext false
                val targetFile = ThemePackManager.getDefaultExportFile()
                ThemePackManager.performExport(this@IconPackExportActivity, selectedIconIds, targetFile)
            }

            progressBar.visibility = View.GONE
            if (success) {
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
