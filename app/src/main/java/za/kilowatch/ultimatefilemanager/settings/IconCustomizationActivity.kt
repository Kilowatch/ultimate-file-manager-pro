package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.TileColorBottomSheet
import za.kilowatch.ultimatefilemanager.storage.TileColorManager
import za.kilowatch.ultimatefilemanager.storage.TileIconManager
import za.kilowatch.ultimatefilemanager.storage.TileColorConfig
import za.kilowatch.ultimatefilemanager.storage.TvTileDataHolder
import za.kilowatch.ultimatefilemanager.storage.TileColorTvActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File

class IconCustomizationActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var adapter: IconCustomizationAdapter
    private val categories = mutableListOf<IconCategoryData>()
    private var pendingCustomizeIconId: String? = null
    private var activePickerSheet: IconPickerSheet? = null
    private var activeTileSheet: TileColorBottomSheet? = null
    private var pendingTileIdForBrowse: String? = null

    // Launcher for IconPickerSheet browse — opens StorageBrowserActivity in tile-icon-picker mode,
    // then communicates the result back to the sheet for preview (waits for Done).
    private val tileIconPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val iconId = pendingCustomizeIconId
        pendingCustomizeIconId = null
        if (result.resultCode == RESULT_OK && iconId != null) {
            val selectedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (selectedPath != null) {
                val sourceFile = File(selectedPath)
                if (sourceFile.exists() && sourceFile.length() > TileIconManager.MAX_SIZE_BYTES) {
                    Toast.makeText(this, R.string.tile_icon_file_too_large, Toast.LENGTH_SHORT).show()
                } else {
                    // Communicate the result to the picker sheet — it will handle saving on Done
                    activePickerSheet?.onBrowseResult(selectedPath)
                }
            }
        }
    }

    // Launcher for TileColorBottomSheet browse — immediately saves and notifies the sheet
    private val tileIconBrowseLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (selectedPath != null) {
                val sourceFile = File(selectedPath)
                if (sourceFile.exists() && sourceFile.length() > TileIconManager.MAX_SIZE_BYTES) {
                    Toast.makeText(this, R.string.tile_icon_file_too_large, Toast.LENGTH_SHORT).show()
                } else {
                    val tileId = pendingTileIdForBrowse ?: return@registerForActivityResult
                    pendingTileIdForBrowse = null
                    val sheet = activeTileSheet
                    val privatePath = TileIconManager.copyToPrivateStorage(this, tileId, selectedPath)
                    if (privatePath != null) {
                        TileIconManager.saveTileIcon(this, tileId, privatePath)
                        sheet?.onIconPicked(privatePath)
                        loadIconCategories()
                    } else {
                        Toast.makeText(this, R.string.tile_icon_invalid_file, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val importPackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (path != null) {
                val intent = Intent(this, IconPackImportActivity::class.java).apply {
                    putExtra(IconPackImportActivity.EXTRA_PACK_PATH, path)
                }
                startActivity(intent)
            }
        }
    }

    private val tvTileColorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val tileId = TvTileDataHolder.sourceTileId
            val data = result.data!!
            val config = TileColorConfig(
                ringColor   = data.getIntExtra(TileColorTvActivity.RESULT_RING_COLOR,   Color.TRANSPARENT),
                iconColor   = data.getIntExtra(TileColorTvActivity.RESULT_ICON_COLOR,   Color.TRANSPARENT),
                iconBgColor = data.getIntExtra(TileColorTvActivity.RESULT_ICON_BG,      Color.TRANSPARENT),
                tileBgColor = data.getIntExtra(TileColorTvActivity.RESULT_TILE_BG,      Color.TRANSPARENT),
                labelColor  = data.getIntExtra(TileColorTvActivity.RESULT_LABEL_COLOR,  Color.TRANSPARENT)
            )
            TileColorManager.saveTileColor(this, tileId, config)
            loadIconCategories()
        }
    }

    private val tvIconPickerActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            loadIconCategories()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            setContentView(R.layout.activity_icon_customization_tv)
        } else {
            setContentView(R.layout.activity_icon_customization)
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
        loadIconCategories()
    }

    override fun onResume() {
        super.onResume()
        loadIconCategories()
    }

    private fun setupViews() {
        val btnBack = findViewById<ImageView?>(if (isTv) R.id.btnBack else R.id.btnBack)
        btnBack?.setOnClickListener { finish() }
        if (isTv) {
            btnBack?.let { setupTvIconFocus(it) }
        }

        val btnExport = findViewById<MaterialButton?>(R.id.btnExportPack)
        btnExport?.setOnClickListener {
            startActivity(Intent(this, IconPackExportActivity::class.java))
        }

        val btnImport = findViewById<MaterialButton?>(R.id.btnImportPack)
        btnImport?.setOnClickListener {
            val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, "ufmtheme")
            }
            importPackLauncher.launch(intent)
        }

        if (isTv) {
            btnExport?.let { setupTvButtonFocus(it) }
            btnImport?.let { setupTvButtonFocus(it) }
            findViewById<MaterialButton?>(R.id.btnResetAll)?.let { setupTvButtonFocus(it) }
        }

        findViewById<MaterialButton?>(R.id.btnResetAll)?.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_all_confirm_title)
                .setMessage(R.string.reset_all_confirm_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    IconCustomizationManager.clearAll(this)
                    TileIconManager.getAllTileIcons(this).keys.forEach {
                        TileIconManager.clearTileIcon(this, it)
                    }
                    loadIconCategories()
                    Toast.makeText(this, R.string.reset_all_icons, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        val recycler = findViewById<RecyclerView>(R.id.rvIconCategories)
        recycler?.layoutManager = LinearLayoutManager(this)
        adapter = IconCustomizationAdapter(
            categories,
            isTv,
            onIconClicked = { iconItem ->
                // Main Menu Tiles open TileColorBottomSheet (mobile) or TileColorTvActivity (TV)
                if (iconItem.id.startsWith("tile_")) {
                    val tileId = iconItem.id.removePrefix("tile_")
                    val tileName = iconItem.label
                    val tileIcon = TileIconManager.getTileIconRes(this, tileId)
                        .takeIf { it != 0 } ?: iconItem.defaultRes
                    val customIconPath = TileIconManager.getTileIcon(this, tileId)
                    val colorConfig = TileColorManager.getTileColor(this, tileId)

                    if (isTv) {
                        TvTileDataHolder.tiles = StorageBrowserActivity.getConnectedStorages(this)
                        TvTileDataHolder.sourceTileId = tileId
                        TvTileDataHolder.sourceConfig = colorConfig
                        TvTileDataHolder.isListView = false
                        tvTileColorLauncher.launch(TileColorTvActivity.createIntent(this, false))
                        return@IconCustomizationAdapter
                    }

                    val sheet = TileColorBottomSheet.newInstance(
                        tileId = tileId,
                        tileName = tileName,
                        tileIconRes = tileIcon,
                        tileSubtitle = null,
                        config = colorConfig,
                        isListView = false,
                        customIconPath = customIconPath
                    )
                    activeTileSheet = sheet
                    pendingTileIdForBrowse = tileId

                    sheet.setOnColorChangedListener { config ->
                        TileColorManager.saveTileColor(this, tileId, config)
                    }
                    sheet.setOnBrowseIconClickedListener {
                        val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                            putExtra(StorageBrowserActivity.EXTRA_TILE_ICON_PICKER, true)
                        }
                        tileIconBrowseLauncher.launch(intent)
                    }
                    sheet.setOnDoneListener {
                        loadIconCategories()
                    }
                    sheet.setOnIconChangedListener { iconConfig ->
                        if (iconConfig.customIconPath != null) {
                            TileIconManager.saveTileIcon(this, tileId, iconConfig.customIconPath)
                        } else if (iconConfig.selectedIconRes != iconConfig.originalIconRes) {
                            TileIconManager.saveTileIconRes(this, tileId, iconConfig.selectedIconRes)
                        } else {
                            TileIconManager.clearTileIcon(this, tileId)
                        }
                        loadIconCategories()
                    }
                    sheet.show(supportFragmentManager, "tile_color")
                    return@IconCustomizationAdapter
                }

                val override = IconCustomizationManager.getOverride(this, iconItem.id)
                val currentRes = override?.builtinRes?.takeIf { it != 0 } ?: iconItem.defaultRes
                val customPath = override?.customPath

                if (isTv) {
                    val intent = Intent(this, IconPickerTvActivity::class.java).apply {
                        putExtra(IconPickerTvActivity.EXTRA_ICON_ID, iconItem.id)
                        putExtra(IconPickerTvActivity.EXTRA_LABEL, iconItem.label)
                        putExtra(IconPickerTvActivity.EXTRA_CURRENT_RES, currentRes)
                        putExtra(IconPickerTvActivity.EXTRA_CUSTOM_PATH, customPath)
                        putExtra(IconPickerTvActivity.EXTRA_BUILTIN_ALTERNATIVES, ALL_BUILTIN_ICONS)
                    }
                    tvIconPickerActivityLauncher.launch(intent)
                    return@IconCustomizationAdapter
                }

                val sheet = IconPickerSheet.newInstance(
                    iconId = iconItem.id,
                    label = iconItem.label,
                    currentRes = currentRes,
                    customPath = customPath,
                    builtinAlts = ALL_BUILTIN_ICONS
                )
                activePickerSheet = sheet
                sheet.setOnIconPickedCallback { id, path, res ->
                    if (res == 0 && path == null) {
                        IconCustomizationManager.clearOverride(this, id)
                    } else if (path != null) {
                        IconCustomizationManager.setCustomPath(this, id, path)
                    } else if (res != 0) {
                        IconCustomizationManager.setBuiltinRes(this, id, res)
                    }
                    activePickerSheet = null
                    loadIconCategories()
                }
                // Wire Browse button to open StorageBrowserActivity in tile-icon-picker mode
                sheet.setOnBrowseClickedListener {
                    pendingCustomizeIconId = iconItem.id
                    val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                        putExtra(StorageBrowserActivity.EXTRA_TILE_ICON_PICKER, true)
                    }
                    tileIconPickerLauncher.launch(intent)
                }
                sheet.show(supportFragmentManager, "icon_picker")
            },
            onIconReset = { iconItem ->
                IconCustomizationManager.clearOverride(this, iconItem.id)
                loadIconCategories()
            }
        )
        recycler?.adapter = adapter
    }

    private fun loadIconCategories() {
        categories.clear()

        // File Types (22)
        categories.add(IconCategoryData(
            "file_types", getString(R.string.category_file_types), buildFileTypeIcons()
        ))

        // Folders (2)
        categories.add(IconCategoryData(
            "folders", getString(R.string.category_folders), listOf(
                IconItemData("folder_default", getString(R.string.icon_folder_default), R.drawable.ic_folder, emptyList()),
                IconItemData("folder_network", getString(R.string.icon_folder_network), R.drawable.ic_folder, emptyList())
            )
        ))

        // Toolbar Actions (13)
        categories.add(IconCategoryData(
            "toolbar", getString(R.string.category_toolbar), buildToolbarIcons()
        ))

        // Navigation (subset of common nav icons)
        categories.add(IconCategoryData(
            "navigation", getString(R.string.category_navigation), listOf(
                IconItemData("nav_back", getString(R.string.icon_nav_back), R.drawable.ic_arrow_back, emptyList()),
                IconItemData("nav_forward", getString(R.string.icon_nav_forward), R.drawable.ic_arrow_forward, emptyList()),
                IconItemData("nav_up", getString(R.string.icon_nav_up), R.drawable.ic_arrow_up, emptyList())
            )
        ))

        // Settings (all 28 settings icons)
        categories.add(IconCategoryData(
            "settings", getString(R.string.category_settings), listOf(
                IconItemData("settings_search_bar", getString(R.string.icon_settings_search_bar), R.drawable.ic_search, emptyList()),
                IconItemData("settings_default_start_screen", getString(R.string.icon_settings_default_start_screen), R.drawable.ic_storage_internal, emptyList()),
                IconItemData("settings_language", getString(R.string.icon_settings_language), R.drawable.ic_language, emptyList()),
                IconItemData("settings_appearance", getString(R.string.icon_settings_appearance), R.drawable.ic_theme, emptyList()),
                IconItemData("settings_icons", getString(R.string.icon_settings_icons), R.drawable.ic_palette, emptyList()),
                IconItemData("settings_backup_restore", getString(R.string.icon_settings_backup_restore), R.drawable.ic_export, emptyList()),
                IconItemData("settings_main_menu_layout", getString(R.string.icon_settings_main_menu_layout), R.drawable.ic_view_list, emptyList()),
                IconItemData("settings_twin_window_layout", getString(R.string.icon_settings_twin_window_layout), R.drawable.ic_view_list, emptyList()),
                IconItemData("settings_twin_window_startup", getString(R.string.icon_settings_twin_window_startup), R.drawable.ic_view_list, emptyList()),
                IconItemData("settings_side_by_side_video", getString(R.string.icon_settings_side_by_side_video), R.drawable.ic_play, emptyList()),
                IconItemData("settings_breadcrumbs", getString(R.string.icon_settings_breadcrumbs), R.drawable.ic_home, emptyList()),
                IconItemData("settings_default_apps", getString(R.string.icon_settings_default_apps), R.drawable.ic_apps, emptyList()),
                IconItemData("settings_font_size", getString(R.string.icon_settings_font_size), R.drawable.ic_font_size, emptyList()),
                IconItemData("settings_apk_extract", getString(R.string.icon_settings_apk_extract), R.drawable.ic_file_apk, emptyList()),
                IconItemData("settings_long_press", getString(R.string.icon_settings_long_press), R.drawable.ic_long_press, emptyList()),
                IconItemData("settings_controls_timeout", getString(R.string.icon_settings_controls_timeout), R.drawable.ic_controls_timeout, emptyList()),
                IconItemData("settings_toolbar_icons", getString(R.string.icon_settings_toolbar_icons), R.drawable.ic_star, emptyList()),
                IconItemData("settings_favorites", getString(R.string.icon_settings_favorites), R.drawable.ic_star, emptyList()),
                IconItemData("settings_custom_drive_names", getString(R.string.icon_settings_custom_drive_names), R.drawable.ic_edit, emptyList()),
                IconItemData("settings_file_server_tiles", getString(R.string.icon_settings_file_server_tiles), R.drawable.ic_ufm_ftp, emptyList()),
                IconItemData("settings_hidden_files", getString(R.string.icon_settings_hidden_files), R.drawable.ic_eye, emptyList()),
                IconItemData("settings_recycle_bin", getString(R.string.icon_settings_recycle_bin), R.drawable.ic_delete, emptyList()),
                IconItemData("settings_media_thumbnails", getString(R.string.icon_settings_media_thumbnails), R.drawable.ic_photo_video, emptyList()),
                IconItemData("settings_video_thumbnail_time", getString(R.string.icon_settings_video_thumbnail_time), R.drawable.ic_photo_video, emptyList()),
                IconItemData("settings_network_thumbnails", getString(R.string.icon_settings_network_thumbnails), R.drawable.ic_cloud, emptyList()),
                IconItemData("settings_cache_copy", getString(R.string.icon_settings_cache_copy), R.drawable.ic_copy, emptyList()),
                IconItemData("settings_quick_transfer", getString(R.string.icon_settings_quick_transfer), R.drawable.ic_copy, emptyList()),
                IconItemData("settings_network_open_cache", getString(R.string.icon_settings_network_open_cache), R.drawable.ic_cloud, emptyList()),
                IconItemData("settings_storage_indexer", getString(R.string.icon_settings_storage_indexer), R.drawable.ic_storage_internal, emptyList()),
                IconItemData("settings_analytics", getString(R.string.icon_settings_analytics), R.drawable.ic_tune, emptyList())
            )
        ))

        // Media Player (10)
        categories.add(IconCategoryData(
            "media_player", getString(R.string.category_media_player), listOf(
                IconItemData("media_play", getString(R.string.icon_media_play), R.drawable.ic_play, emptyList()),
                IconItemData("media_pause", getString(R.string.icon_media_pause), R.drawable.ic_pause, emptyList()),
                IconItemData("media_skip_next", getString(R.string.icon_media_skip_next), R.drawable.ic_skip_next, emptyList()),
                IconItemData("media_skip_previous", getString(R.string.icon_media_skip_previous), R.drawable.ic_skip_previous, emptyList()),
                IconItemData("media_shuffle", getString(R.string.icon_media_shuffle), R.drawable.ic_shuffle, emptyList()),
                IconItemData("media_repeat", getString(R.string.icon_media_repeat), R.drawable.ic_repeat, emptyList()),
                IconItemData("media_fullscreen", getString(R.string.icon_media_fullscreen), R.drawable.ic_fullscreen, emptyList()),
                IconItemData("media_fullscreen_exit", getString(R.string.icon_media_fullscreen_exit), R.drawable.ic_fullscreen_exit, emptyList()),
                IconItemData("media_volume", getString(R.string.icon_media_volume), R.drawable.ic_volume_down, emptyList()),
                IconItemData("media_volume_off", getString(R.string.icon_media_volume_off), R.drawable.ic_volume_off, emptyList())
            )
        ))

        // Utility / Action (12)
        categories.add(IconCategoryData(
            "utility", getString(R.string.category_utility), listOf(
                IconItemData("action_add", getString(R.string.icon_action_add), R.drawable.ic_add, emptyList()),
                IconItemData("action_close", getString(R.string.icon_action_close), R.drawable.ic_close, emptyList()),
                IconItemData("action_edit", getString(R.string.icon_action_edit), R.drawable.ic_edit, emptyList()),
                IconItemData("action_refresh", getString(R.string.icon_action_refresh), R.drawable.ic_refresh, emptyList()),
                IconItemData("action_save", getString(R.string.icon_action_save), R.drawable.ic_save, emptyList()),
                IconItemData("action_paste", getString(R.string.icon_action_paste), R.drawable.ic_paste, emptyList()),
                IconItemData("action_undo", getString(R.string.icon_action_undo), R.drawable.ic_undo, emptyList()),
                IconItemData("action_duplicate", getString(R.string.icon_action_duplicate), R.drawable.ic_duplicate, emptyList()),
                IconItemData("action_crop", getString(R.string.icon_action_crop), R.drawable.ic_crop, emptyList()),
                IconItemData("action_zoom_in", getString(R.string.icon_action_zoom_in), R.drawable.ic_zoom_in, emptyList()),
                IconItemData("action_zoom_out", getString(R.string.icon_action_zoom_out), R.drawable.ic_zoom_out, emptyList()),
                IconItemData("action_fit_screen", getString(R.string.icon_action_fit_screen), R.drawable.ic_fit_screen, emptyList())
            )
        ))

        // Status / Alert (5)
        categories.add(IconCategoryData(
            "status", getString(R.string.category_status), listOf(
                IconItemData("status_warning", getString(R.string.icon_status_warning), R.drawable.ic_warning, emptyList()),
                IconItemData("status_warning_badge", getString(R.string.icon_status_warning_badge), R.drawable.ic_warning_badge, emptyList()),
                IconItemData("status_check_circle", getString(R.string.icon_status_check_circle), R.drawable.ic_check_circle, emptyList()),
                IconItemData("status_shield_check", getString(R.string.icon_status_shield_check), R.drawable.ic_shield_check, emptyList()),
                IconItemData("status_shield_alert", getString(R.string.icon_status_shield_alert), R.drawable.ic_shield_alert, emptyList())
            )
        ))

        // View Modes (3)
        categories.add(IconCategoryData(
            "view_modes", getString(R.string.category_view_modes), listOf(
                IconItemData("view_grid_small", getString(R.string.icon_view_grid_small), R.drawable.ic_view_grid_small, emptyList()),
                IconItemData("view_grid_medium", getString(R.string.icon_view_grid_medium), R.drawable.ic_view_grid_medium, emptyList()),
                IconItemData("view_grid_large", getString(R.string.icon_view_grid_large), R.drawable.ic_view_grid_large, emptyList())
            )
        ))

        // Feature Tile Extras (11)
        categories.add(IconCategoryData(
            "feature_tiles", getString(R.string.category_feature_tiles), listOf(
                IconItemData("badge_lightning", getString(R.string.icon_tile_indexed_badge), R.drawable.ic_lightning, emptyList()),
                IconItemData("badge_remove_circle", getString(R.string.icon_tile_remove_circle), R.drawable.ic_remove_circle, emptyList()),
                IconItemData("badge_more_vert", getString(R.string.icon_tile_more), R.drawable.ic_more_vert, emptyList()),
                IconItemData("tile_history", getString(R.string.icon_tile_history), R.drawable.ic_history, emptyList()),
                IconItemData("tile_import_code", getString(R.string.icon_tile_import_code), R.drawable.ic_import_code, emptyList()),
                IconItemData("tile_refresh_custom", getString(R.string.icon_tile_refresh_custom), R.drawable.ic_refresh_custom, emptyList()),
                IconItemData("tile_search_off", getString(R.string.icon_tile_search_off), R.drawable.ic_search_off, emptyList()),
                IconItemData("tile_twin_window_off", getString(R.string.icon_tile_twin_window_off), R.drawable.ic_twin_window_off, emptyList()),
                IconItemData("tile_vpn_warning", getString(R.string.icon_tile_vpn_warning), R.drawable.ic_vpn_warning, emptyList()),
                IconItemData("tile_saf", getString(R.string.icon_tile_saf), R.drawable.ic_saf, emptyList()),
                IconItemData("tile_visibility_off", getString(R.string.icon_tile_visibility_off), R.drawable.ic_visibility_off, emptyList())
            )
        ))

        // Main Menu Tiles — load all storage tiles from StorageBrowserActivity + feature tiles
        val tileItems = buildMainMenuTiles()
        categories.add(IconCategoryData(
            "main_menu_tiles", getString(R.string.category_main_menu_tiles), tileItems
        ))

        adapter.notifyDataSetChanged()
    }

    private fun buildFileTypeIcons(): List<IconItemData> {
        return listOf(
            IconItemData("file_generic", getString(R.string.icon_file_generic), R.drawable.ic_file, emptyList()),
            IconItemData("file_image", getString(R.string.icon_file_image), R.drawable.ic_file_image, emptyList()),
            IconItemData("file_video", getString(R.string.icon_file_video), R.drawable.ic_file_video, emptyList()),
            IconItemData("file_audio", getString(R.string.icon_file_audio), R.drawable.ic_file_audio, emptyList()),
            IconItemData("file_pdf", getString(R.string.icon_file_pdf), R.drawable.ic_file_pdf, emptyList()),
            IconItemData("file_word", getString(R.string.icon_file_word), R.drawable.ic_file_word, emptyList()),
            IconItemData("file_spreadsheet", getString(R.string.icon_file_spreadsheet), R.drawable.ic_file_spreadsheet, emptyList()),
            IconItemData("file_presentation", getString(R.string.icon_file_presentation), R.drawable.ic_file_presentation, emptyList()),
            IconItemData("file_apk", getString(R.string.icon_file_apk), R.drawable.ic_file_apk, emptyList()),
            IconItemData("file_archive", getString(R.string.icon_file_archive), R.drawable.ic_file_archive, emptyList()),
            IconItemData("file_code", getString(R.string.icon_file_code), R.drawable.ic_file_code, emptyList()),
            IconItemData("file_xml", getString(R.string.icon_file_xml), R.drawable.ic_file_xml, emptyList()),
            IconItemData("file_text", getString(R.string.icon_file_text), R.drawable.ic_file_text, emptyList()),
            IconItemData("file_font", getString(R.string.icon_file_font), R.drawable.ic_file_font, emptyList()),
            IconItemData("file_ebook", getString(R.string.icon_file_ebook), R.drawable.ic_file_ebook, emptyList()),
            IconItemData("file_iso", getString(R.string.icon_file_iso), R.drawable.ic_file_iso, emptyList()),
            IconItemData("file_database", getString(R.string.icon_file_database), R.drawable.ic_file_database, emptyList()),
            IconItemData("file_torrent", getString(R.string.icon_file_torrent), R.drawable.ic_file_torrent, emptyList()),
            IconItemData("file_subtitle", getString(R.string.icon_file_subtitle), R.drawable.ic_file_subtitle, emptyList()),
            IconItemData("file_3d", getString(R.string.icon_file_3d), R.drawable.ic_file_3d, emptyList()),
            IconItemData("file_backup", getString(R.string.icon_file_backup), R.drawable.ic_file_backup, emptyList())
        )
    }

    private fun buildToolbarIcons(): List<IconItemData> {
        return listOf(
            IconItemData("toolbar_create_new", getString(R.string.icon_actions_create_new), R.drawable.ic_create_new, emptyList()),
            IconItemData("toolbar_copy", getString(R.string.icon_toolbar_copy), R.drawable.ic_copy, emptyList()),
            IconItemData("toolbar_move", getString(R.string.icon_toolbar_move), R.drawable.ic_move, emptyList()),
            IconItemData("toolbar_rename", getString(R.string.icon_toolbar_rename), R.drawable.ic_edit, emptyList()),
            IconItemData("toolbar_share", getString(R.string.icon_toolbar_share), R.drawable.ic_share, emptyList()),
            IconItemData("toolbar_copy_encrypt", getString(R.string.icon_toolbar_copy_encrypt), R.drawable.ic_copy, emptyList()),
            IconItemData("toolbar_move_encrypt", getString(R.string.icon_toolbar_move_encrypt), R.drawable.ic_move, emptyList()),
            IconItemData("toolbar_favorite", getString(R.string.icon_toolbar_favorite), R.drawable.ic_star, emptyList()),
            IconItemData("toolbar_hide", getString(R.string.icon_toolbar_hide), R.drawable.ic_eye_off, emptyList()),
            IconItemData("toolbar_unhide", getString(R.string.icon_toolbar_unhide), R.drawable.ic_eye, emptyList()),
            IconItemData("toolbar_select_all", getString(R.string.icon_toolbar_select_all), R.drawable.ic_check, emptyList()),
            IconItemData("toolbar_compress", getString(R.string.icon_toolbar_compress), R.drawable.ic_compress, emptyList()),
            IconItemData("toolbar_image_compress", getString(R.string.icon_toolbar_image_compress), R.drawable.ic_compress_image, emptyList()),
            IconItemData("toolbar_delete", getString(R.string.icon_toolbar_delete), R.drawable.ic_delete, emptyList())
        )
    }

    private fun buildMainMenuTiles(): List<IconItemData> {
        val items = mutableListOf<IconItemData>()

        // Physical storages + network shares + paired devices from StorageBrowserActivity
        val connectedStorages = StorageBrowserActivity.getConnectedStorages(this)
        for (storage in connectedStorages) {
            val effectiveRes = TileIconManager.getTileIconRes(this, storage.id)
                .takeIf { it != 0 } ?: storage.iconRes
            items.add(IconItemData(
                id = "tile_${storage.id}",
                label = storage.label,
                defaultRes = effectiveRes,
                builtinAlternatives = emptyList()
            ))
        }

        // Feature tiles
        data class FeatureTile(val id: String, val labelRes: Int, val iconRes: Int)

        val featureTiles = mutableListOf(
            FeatureTile("twin_window_tile", R.string.twin_window_title, R.drawable.ic_twin_window),
            FeatureTile("notepad_tile", R.string.notepad, R.drawable.ic_notepad),
            FeatureTile("scanner_tile", R.string.scanner_title, R.drawable.ic_scanner),
            FeatureTile("apps_tile", R.string.perm_query_apps_title, R.drawable.ic_apps),
            FeatureTile("search_tile", R.string.search_title, R.drawable.ic_search),
            FeatureTile("analyzer_tile", R.string.analyzer_title, R.drawable.ic_analyzer),
            FeatureTile("vault_tile", R.string.vault_title, R.drawable.ic_lock),
            FeatureTile("settings_tile", R.string.settings, R.drawable.ic_settings),
            FeatureTile("recycle_bin_tile", R.string.recycle_bin_title, R.drawable.ic_delete),
            FeatureTile("file_server_tile", R.string.file_server_title, R.drawable.ic_ufm_ftp),
            FeatureTile("sync_tile", R.string.sync_title, R.drawable.ic_sync),
            FeatureTile("advanced_sync_tile", R.string.advanced_sync_title, R.drawable.ic_sync_advanced),
            FeatureTile("smart_sort_tile", R.string.smart_sort_title, R.drawable.ic_sort),
            FeatureTile("terminal_tile", R.string.adb_terminal_title, R.drawable.ic_terminal),
            FeatureTile("network_tile", R.string.network_tile_title, R.drawable.ic_network),
            FeatureTile("about_tile", R.string.about_title, R.drawable.ic_about),
            FeatureTile("rate_us_tile", R.string.rate_us_title, R.drawable.ic_star),
            FeatureTile("tip_jar_tile", R.string.tip_jar_title, R.drawable.ic_home),
            FeatureTile("legal_tile", R.string.policy_selection_title, R.drawable.ic_policy)
        )
        if (isTv) {
            featureTiles.add(FeatureTile("tv_remote_tile", R.string.tv_remote, R.drawable.ic_remote_manage))
        }

        for (tile in featureTiles) {
            val effectiveRes = TileIconManager.getTileIconRes(this, tile.id)
                .takeIf { it != 0 } ?: tile.iconRes
            items.add(IconItemData(
                id = "tile_${tile.id}",
                label = getString(tile.labelRes),
                defaultRes = effectiveRes,
                builtinAlternatives = emptyList()
            ))
        }

        return items
    }

    private fun setupTvIconFocus(view: View) {
        val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
        val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
        if (view is ImageView) {
            view.imageTintList = whiteCsl
            view.setOnFocusChangeListener { _, hasFocus ->
                view.imageTintList = if (hasFocus) yellowCsl else whiteCsl
                if (hasFocus) {
                    view.setBackgroundResource(R.drawable.bg_icon_circle_focused)
                } else {
                    view.setBackgroundResource(R.drawable.bg_icon_circle_accent)
                }
            }
        }
    }

    private fun setupTvButtonFocus(btn: MaterialButton) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val defaultText = getColor(R.color.tv_text_primary)
        val defaultBg = getColor(R.color.tv_glass_white_10)

        btn.setTextColor(defaultText)
        btn.iconTint = android.content.res.ColorStateList.valueOf(defaultText)

        btn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(yellowFill)
                btn.setTextColor(blackText)
                btn.iconTint = android.content.res.ColorStateList.valueOf(blackText)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(defaultBg)
                btn.setTextColor(defaultText)
                btn.iconTint = android.content.res.ColorStateList.valueOf(defaultText)
            }
        }
    }
}
