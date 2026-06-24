package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.remote.PinDialogHelper
import za.kilowatch.ultimatefilemanager.remote.RemoteManageActivity
import za.kilowatch.ultimatefilemanager.remote.VpnWarningHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Displays tiles nested inside a single custom tile.
 * Reuses [StorageAdapter] with the same view mode, edit mode, hide,
 * color, icon, and reorder capabilities as the main Storage Browser.
 */
class CustomTileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CUSTOM_TILE_ID = "extra_custom_tile_id"
        private const val TAG = "CustomTileActivity"
        private const val REQUEST_TILE_ICON_PICKER = 1001
    }

    private lateinit var recyclerStorage: RecyclerView
    private lateinit var layoutEmptyStorage: View
    private lateinit var storageAdapter: StorageAdapter
    private lateinit var btnManageTiles: ImageView
    private var btnColorTile: ImageView? = null
    private var btnImportColorCode: ImageView? = null
    private var btnDoneTv: View? = null

    private var isTv = false
    private var isEditMode = false
    private var customTileId: String = ""
    private var customTileTitle: String = ""
    private var customTileSubtitle: String = ""

    // Picker extras propagated from StorageBrowserActivity
    private var isPickerMode = false
    private var pickerExtensions: String? = null
    private var isSyncFolderPickerMode = false
    private var isAdvancedSyncFolderPickerMode = false
    private var isCompressDestPickerMode = false
    private var isImageCompressDestPickerMode = false
    private var isExtractDestPickerMode = false
    private var isNetworkCachePickerMode = false
    private var isQuickTransferPickerMode = false
    private var quickTransferOp: String? = null
    private var isShareDestPickerMode = false
    private var isNotepadFolderPicker = false
    private var isScannerFolderPicker = false
    private var isAutoBackupFolderPicker = false
    private var isKeyfilePickerMode = false
    private var isCertPickerMode = false
    private var isLocationPickerMode = false
    private var isDrivePicker = false

    // TV D-Pad reorder state
    private var reorderModeItemId: String? = null
    private var reorderModeOriginalList: List<StorageItem>? = null

    // Drag-and-drop
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var tvSnapHelper: androidx.recyclerview.widget.SnapHelper? = null

    private val handler = Handler(Looper.getMainLooper())

    // Color TV result handler (same pattern as StorageBrowserActivity)
    private val tileColorTvLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val config = TileColorConfig(
                    ringColor   = data.getIntExtra(TileColorTvActivity.RESULT_RING_COLOR,   android.graphics.Color.TRANSPARENT),
                    iconColor   = data.getIntExtra(TileColorTvActivity.RESULT_ICON_COLOR,   android.graphics.Color.TRANSPARENT),
                    iconBgColor = data.getIntExtra(TileColorTvActivity.RESULT_ICON_BG,      android.graphics.Color.TRANSPARENT),
                    tileBgColor = data.getIntExtra(TileColorTvActivity.RESULT_TILE_BG,      android.graphics.Color.TRANSPARENT),
                    labelColor  = data.getIntExtra(TileColorTvActivity.RESULT_LABEL_COLOR,  android.graphics.Color.TRANSPARENT)
                )
                val tileId = TvTileDataHolder.sourceTileId
                TileColorManager.saveTileColor(this, tileId, config)
                storageAdapter.setTileColors(TileColorManager.loadTileColors(this))
                storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this))
                storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this))
                loadTiles()
            }
        }

    /** True when any folder/file picker mode is active on this activity. */
    private val isAnyPickerActive: Boolean get() = isPickerMode || isSyncFolderPickerMode || isAdvancedSyncFolderPickerMode || isCompressDestPickerMode || isImageCompressDestPickerMode || isExtractDestPickerMode || isNetworkCachePickerMode || isQuickTransferPickerMode || isShareDestPickerMode || isNotepadFolderPicker || isScannerFolderPicker || isAutoBackupFolderPicker || isKeyfilePickerMode || isCertPickerMode || isLocationPickerMode || isDrivePicker

    // Forwards file-browser picker results back through the activity chain
    private val pickerResultLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                setResult(Activity.RESULT_OK, result.data)
                finish()
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        customTileId = intent.getStringExtra(EXTRA_CUSTOM_TILE_ID) ?: run {
            finish()
            return
        }

        // Read picker extras propagated from StorageBrowserActivity
        isPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_PICKER_MODE, false)
        pickerExtensions = intent.getStringExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS)
        isSyncFolderPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_SYNC_FOLDER_PICKER, false)
        isAdvancedSyncFolderPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_ADVANCED_SYNC_FOLDER_PICKER, false)
        isCompressDestPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_COMPRESS_DEST_PICKER, false)
        isImageCompressDestPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER, false)
        isExtractDestPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, false)
        isNetworkCachePickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_NETWORK_CACHE_PICKER, false)
        isQuickTransferPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_PICKER, false)
        quickTransferOp = intent.getStringExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP)
        isShareDestPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_SHARE_DEST_PICKER, false)
        isNotepadFolderPicker = intent.getBooleanExtra(FileBrowserActivity.EXTRA_NOTEPAD_FOLDER_PICKER, false)
        isScannerFolderPicker = intent.getBooleanExtra(FileBrowserActivity.EXTRA_SCANNER_FOLDER_PICKER, false)
        isAutoBackupFolderPicker = intent.getBooleanExtra(FileBrowserActivity.EXTRA_AUTO_BACKUP_FOLDER_PICKER, false)
        isKeyfilePickerMode = intent.getBooleanExtra(StorageBrowserActivity.EXTRA_KEYFILE_PICKER, false)
        isCertPickerMode = intent.getBooleanExtra(StorageBrowserActivity.EXTRA_CERT_PICKER, false)
        isLocationPickerMode = intent.getBooleanExtra(StorageBrowserActivity.EXTRA_LOCATION_PICKER, false)
        isDrivePicker = intent.getBooleanExtra(StorageBrowserActivity.EXTRA_DRIVE_PICKER, false)

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_custom_tile_tv)
        } else {
            setContentView(R.layout.activity_custom_tile)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Load custom tile metadata for title
        val tileData = CustomTileManager.loadCustomTiles(this).find { it.id == customTileId }
        customTileTitle = tileData?.title ?: getString(R.string.custom_tile_create_title)
        customTileSubtitle = tileData?.subtitle ?: ""

        setupViews()
        loadTiles()
    }

    override fun onResume() {
        super.onResume()
        loadTiles()
        applyViewMode()
    }

    private fun setupViews() {
        recyclerStorage = findViewById(R.id.recyclerStorage)
        layoutEmptyStorage = findViewById(R.id.layoutEmptyStorage)

        // Initialize StorageAdapter
        storageAdapter = StorageAdapter(
            isTv = isTv,
            onStorageClick = { item -> onTileClicked(item) },
            onLongPress = { item, viewHolder ->
                if (isTv) {
                    // TV: long press enters Edit Mode first.
                    // If already in Edit Mode, show menu with move/reorder options.
                    if (isEditMode) {
                        showTvEditOptionsMenu(item)
                    } else {
                        enterEditMode()
                    }
                } else {
                    // Mobile: enter Edit Mode (pulse/jiggle) and start drag
                    if (!isEditMode) {
                        enterEditMode()
                    }
                    itemTouchHelper.startDrag(viewHolder)
                }
            },
            onHideClick = { item -> hideTile(item) },
            onEditModeClick = { item ->
                // Open color picker for this tile
                if (isSelectingTileForColor) {
                    openColorPicker(item)
                } else {
                    showPremiumSnackbar(getString(R.string.tile_color_select_tile))
                }
            }
        )

        // Attach adapter to RecyclerView
        recyclerStorage.adapter = storageAdapter

        // Mobile toolbar setup
        if (!isTv) {
            val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            toolbar?.let {
                setSupportActionBar(it)
                supportActionBar?.setDisplayShowTitleEnabled(false)
                it.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            }
            val txtTitle = findViewById<android.widget.TextView>(R.id.txtToolbarTitle)
            txtTitle?.text = customTileTitle
            val txtSubtitle = findViewById<android.widget.TextView>(R.id.txtToolbarSubtitle)
            if (txtSubtitle != null && customTileSubtitle.isNotEmpty()) {
                txtSubtitle.text = customTileSubtitle
                txtSubtitle.visibility = View.VISIBLE
            }
        } else {
            val txtTitle = findViewById<android.widget.TextView>(R.id.txtStorageTitle)
            txtTitle?.text = customTileTitle
            val btnBack = findViewById<ImageView>(R.id.btnBack)
            btnBack?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            val txtSubtitle = findViewById<android.widget.TextView>(R.id.txtStorageSubtitle)
            if (txtSubtitle != null && customTileSubtitle.isNotEmpty()) {
                txtSubtitle.text = customTileSubtitle
                txtSubtitle.visibility = View.VISIBLE
            }
        }

        btnManageTiles = findViewById(R.id.btnManageTiles)
        btnColorTile = findViewById(R.id.btnColorTile)
        btnImportColorCode = findViewById(R.id.btnImportColorCode)

        if (isTv) {
            btnDoneTv = findViewById(R.id.btnDone)
            btnDoneTv?.setOnClickListener { exitEditMode() }

            btnColorTile?.setOnClickListener {
                isSelectingTileForColor = true
                storageAdapter.isColorPickMode = true
                showPremiumSnackbar(getString(R.string.tile_color_select_tile))
            }

            btnImportColorCode?.setOnClickListener {
                val intent = Intent(this, TileColorImportTvActivity::class.java)
                startActivity(intent)
            }
        } else {
            btnColorTile?.setOnClickListener {
                if (isEditMode) {
                    isSelectingTileForColor = true
                    storageAdapter.isColorPickMode = true
                    showPremiumSnackbar(getString(R.string.tile_color_select_tile))
                }
            }
        }

        // Manage Tiles button: checkmark to exit edit mode (mobile), hidden on TV
        btnManageTiles.setOnClickListener {
            if (isEditMode) exitEditMode()
        }
        btnManageTiles.visibility = View.GONE

        // Import color code button not applicable inside custom tiles
        btnImportColorCode?.visibility = View.GONE

        setupItemTouchHelper()
        updateHiddenBadge()
    }

    private var isSelectingTileForColor = false
    private var activeTileIdForIcon: String? = null
    private var activeColorSheet: TileColorBottomSheet? = null

    // ── Tile Loading ────────────────────────────────────────────────────────

    private fun loadTiles() {
        GlobalScope.launch(Dispatchers.IO) {
            val childIds = CustomTileManager.getChildTiles(this@CustomTileActivity, customTileId)
            val savedOrder = CustomTileManager.loadTileOrder(this@CustomTileActivity, customTileId)

            // Build StorageItem list from child IDs (blocking I/O via StatFs)
            val allTiles = StorageBrowserActivity.buildAllKnownTiles(this@CustomTileActivity)
            val byId = allTiles.associateBy { it.id }

            val tiles = mutableListOf<StorageItem>()

            // First, add tiles in saved order
            for (id in savedOrder) {
                val item = byId[id]
                if (item != null && id in childIds) {
                    tiles.add(item.copy(parentCustomTileId = customTileId))
                }
            }

            // Then append any child tiles not in saved order
            for (id in childIds) {
                if (id !in savedOrder) {
                    val item = byId[id]
                    if (item != null) {
                        tiles.add(item.copy(parentCustomTileId = customTileId))
                    }
                }
            }

            withContext(Dispatchers.Main) {
                // Apply colors and icons
                storageAdapter.setTileColors(TileColorManager.loadTileColors(this@CustomTileActivity))
                storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this@CustomTileActivity))
                storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this@CustomTileActivity))

                // Filter hidden
                val hidden = TileOrderManager.loadHidden(this@CustomTileActivity)
                val visible = tiles.filter { it.id !in hidden }

                storageAdapter.submitList(visible)
                updateEmptyState(visible.isEmpty())
                updateHiddenBadge()
            }
        }
    }

    // ── Edit Mode ───────────────────────────────────────────────────────────

    private fun enterEditMode() {
        if (isEditMode) return
        isEditMode = true
        storageAdapter.isEditMode = true
        updateHiddenBadge()

        if (isTv) {
            showTvEditInstructions()
        } else {
            showPremiumSnackbar(getString(R.string.edit_mode_tap_x_to_hide_drag_to_reorder))
            recyclerStorage.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        storageAdapter.isEditMode = false
        updateHiddenBadge()
        isSelectingTileForColor = false
        storageAdapter.isColorPickMode = false
        showPremiumSnackbar(getString(R.string.tile_configuration_saved))
    }

    private fun showTvEditInstructions() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tv_edit_instructions, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnGotIt = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGotIt)
        btnGotIt.setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener { btnGotIt.requestFocus() }
        dialog.show()
    }

    /**
     * Shows an options dialog for a tile when already in Edit Mode on TV.
     * Provides "Reorder" (D-pad move mode) matching [StorageBrowserActivity.showTvEditOptionsMenu].
     */
    private fun showTvEditOptionsMenu(item: StorageItem) {
        val options = mutableListOf<String>()
        options.add(getString(R.string.dpad_moves_tile_ok_saves_back_cancels)) // Reorder

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(item.label)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> enterTvReorderMode(item)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Tile Actions ────────────────────────────────────────────────────────

    private fun onTileClicked(item: StorageItem) {
        // Reuse the same navigation logic as the main screen
        navigateTile(item)
    }

    private fun navigateTile(item: StorageItem) {
        when {
            item.isTwinWindowTile -> {
                startActivity(Intent(this, TwinWindowActivity::class.java))
            }
            item.isNotepadTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.notepad.NotepadActivity::class.java))
            }
            item.isScannerTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.scanner.DocumentScannerActivity::class.java))
            }
            item.isExtractsTile -> {
                // Navigate to file browser for extracts
                val intent = Intent(this, FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    applyPickerExtras()
                }
                if (isAnyPickerActive) pickerResultLauncher.launch(intent) else startActivity(intent)
            }
            item.isPairedDevicesTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.ui.DevicePairingActivity::class.java))
            }
            item.isTerminalTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.ui.TerminalActivity::class.java))
            }
            item.isShizukuTile -> {
                val intent = if (isTv) {
                    Intent(this, za.kilowatch.ultimatefilemanager.ui.ShizukuTvActivity::class.java)
                } else {
                    Intent(this, za.kilowatch.ultimatefilemanager.ui.ShizukuActivity::class.java)
                }
                startActivity(intent)
            }
            item.isAppsTile -> {
                startActivity(Intent(this, AppManagerActivity::class.java))
            }
            item.isRemoteTile -> {
                if (VpnWarningHelper.isVpnActive(this)) {
                    VpnWarningHelper.showVpnWarningDialog(this) {
                        PinDialogHelper.showPinDialog(this, onCancel = {}) { pin ->
                            val intent = Intent(this, RemoteManageActivity::class.java).apply {
                                putExtra(RemoteManageActivity.EXTRA_PIN, pin)
                            }
                            startActivity(intent)
                        }
                    }
                } else {
                    PinDialogHelper.showPinDialog(this, onCancel = {}) { pin ->
                        val intent = Intent(this, RemoteManageActivity::class.java).apply {
                            putExtra(RemoteManageActivity.EXTRA_PIN, pin)
                        }
                        startActivity(intent)
                    }
                }
            }
            item.isSearchTile -> {
                startActivity(Intent(this, SearchActivity::class.java))
            }
            item.isAnalyzerTile -> {
                startActivity(Intent(this, StorageAnalyzerActivity::class.java))
            }
            item.isSmartSortTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.smartsort.SmartSortActivity::class.java))
            }
            item.isSettingsTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.settings.SettingsActivity::class.java))
            }
            item.isLegalTile -> {
                za.kilowatch.ultimatefilemanager.ui.policy.PolicySelectionActivity.start(this)
            }
            item.isNetworkTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.network.NetworkShareManagerActivity::class.java))
            }
            item.isOnlineStoragesTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.network.OnlineStorageManagerActivity::class.java))
            }
            item.isVaultTile -> {
                startActivity(Intent(this, VaultActivity::class.java))
            }
            item.isRecycleBinTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.recycle.RecycleBinActivity::class.java))
            }
            item.isSyncTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.sync.SyncManagerActivity::class.java))
            }
            item.isFileServerTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.server.ServerHostActivity::class.java))
            }
            item.isRateUsTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.settings.RateUsActivity::class.java))
            }
            item.isAboutTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.settings.AboutActivity::class.java))
            }
            item.isTipJarTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.billing.SupporterLoyaltyActivity::class.java))
            }
            item.isCustomTile -> {
                // Custom tiles inside custom tiles: not allowed in flat hierarchy,
                // but handle gracefully by opening as sub-screen
                val intent = Intent(this, CustomTileActivity::class.java).apply {
                    putExtra(EXTRA_CUSTOM_TILE_ID, item.id)
                    // Propagate picker extras to nested CustomTileActivity too
                    applyPickerExtras()
                }
                startActivity(intent)
            }
            item.isOnlineStorage -> {
                if (isDrivePicker) {
                    val data = Intent().apply {
                        putExtra("is_network", true)
                        putExtra("isOnlineStorage", true)
                        putExtra("share_id", item.onlineStorage?.id)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    }
                    setResult(RESULT_OK, data)
                    finish()
                    return
                }
                val intent = Intent(this, za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity::class.java).apply {
                    putExtra("isOnlineStorage", true)
                    putExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_SHARE_ID, item.onlineStorage?.id)
                    putExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    applyPickerExtras()
                }
                if (isAnyPickerActive) pickerResultLauncher.launch(intent) else startActivity(intent)
            }
            item.isNetworkRoot -> {
                if (isDrivePicker) {
                    val data = Intent().apply {
                        putExtra("is_network", true)
                        putExtra("share_id", item.networkShare?.id)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    }
                    setResult(RESULT_OK, data)
                    finish()
                    return
                }
                val intent = Intent(this, za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity::class.java).apply {
                    if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                        putExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                    } else {
                        putExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                    }
                    putExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    applyPickerExtras()
                }
                if (isAnyPickerActive) pickerResultLauncher.launch(intent) else startActivity(intent)
            }
            item.isFavoriteTile -> {
                if (isDrivePicker) {
                    val data = Intent().apply {
                        putExtra("is_network", false)
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.favoritePath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    }
                    setResult(RESULT_OK, data)
                    finish()
                    return
                }
                val intent = Intent(this, FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.favoritePath)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    applyPickerExtras()
                }
                if (isAnyPickerActive) pickerResultLauncher.launch(intent) else startActivity(intent)
            }
            else -> {
                if (isDrivePicker) {
                    val data = Intent().apply {
                        putExtra("is_network", false)
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    }
                    setResult(RESULT_OK, data)
                    finish()
                    return
                }
                // Physical storage or unknown — open file browser
                val intent = Intent(this, FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    applyPickerExtras()
                }
                if (isAnyPickerActive) pickerResultLauncher.launch(intent) else startActivity(intent)
            }
        }
    }

    /**
     * Applies any active picker extras to an intent destined for
     * FileBrowserActivity or NetworkBrowserActivity, so the picker FAB
     * is shown when navigating from a custom tile.
     */
    private fun Intent.applyPickerExtras(): Intent {
        if (isPickerMode) {
            putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
            pickerExtensions?.let { putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, it) }
        }
        if (isSyncFolderPickerMode) putExtra(FileBrowserActivity.EXTRA_SYNC_FOLDER_PICKER, true)
        if (isAdvancedSyncFolderPickerMode) putExtra(FileBrowserActivity.EXTRA_ADVANCED_SYNC_FOLDER_PICKER, true)
        if (isCompressDestPickerMode) putExtra(FileBrowserActivity.EXTRA_COMPRESS_DEST_PICKER, true)
        if (isImageCompressDestPickerMode) putExtra(FileBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER, true)
        if (isExtractDestPickerMode) putExtra(FileBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, true)
        if (isNetworkCachePickerMode) putExtra(FileBrowserActivity.EXTRA_NETWORK_CACHE_PICKER, true)
        if (isQuickTransferPickerMode) {
            putExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_PICKER, true)
            quickTransferOp?.let { putExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP, it) }
        }
        if (isShareDestPickerMode) putExtra(FileBrowserActivity.EXTRA_SHARE_DEST_PICKER, true)
        if (isNotepadFolderPicker) putExtra(FileBrowserActivity.EXTRA_NOTEPAD_FOLDER_PICKER, true)
        if (isScannerFolderPicker) putExtra(FileBrowserActivity.EXTRA_SCANNER_FOLDER_PICKER, true)
        if (isAutoBackupFolderPicker) putExtra(FileBrowserActivity.EXTRA_AUTO_BACKUP_FOLDER_PICKER, true)
        if (isKeyfilePickerMode) putExtra(StorageBrowserActivity.EXTRA_KEYFILE_PICKER, true)
        if (isCertPickerMode) putExtra(StorageBrowserActivity.EXTRA_CERT_PICKER, true)
        if (isLocationPickerMode) putExtra(StorageBrowserActivity.EXTRA_LOCATION_PICKER, true)
        if (isDrivePicker) putExtra(StorageBrowserActivity.EXTRA_DRIVE_PICKER, true)
        return this
    }

    /**
     * Inside a custom tile, the X button removes the tile from this group
     * and sends it back to the main menu — it does NOT hide it.
     */
    private fun hideTile(item: StorageItem) {
        val currentPos = storageAdapter.getItems().indexOfFirst { it.id == item.id }

        // Move tile back to main menu
        CustomTileManager.setTileParent(this, item.id, null)
        // Remove from this custom tile's saved order
        val order = CustomTileManager.loadTileOrder(this, customTileId).toMutableList()
        order.remove(item.id)
        CustomTileManager.saveTileOrder(this, customTileId, order)

        showPremiumSnackbar(getString(R.string.custom_tile_moved_back_snackbar, item.label))
        loadTiles()

        // TV focus restoration
        if (isTv && currentPos >= 0) {
            recyclerStorage.postDelayed({
                val newCount = storageAdapter.itemCount
                if (newCount == 0) return@postDelayed
                val targetPos = currentPos.coerceAtMost(newCount - 1)
                recyclerStorage.scrollToPosition(targetPos)
                recyclerStorage.post {
                    recyclerStorage.findViewHolderForAdapterPosition(targetPos)
                        ?.itemView?.requestFocus()
                }
            }, 120)
        }
    }

    // ── Drag-and-drop ──────────────────────────────────────────────────────

    private fun setupItemTouchHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                return makeMovementFlags(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
                )
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to   = target.bindingAdapterPosition
                storageAdapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { /* disabled */ }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                    viewHolder.itemView.elevation = 24f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                viewHolder.itemView.elevation = 0f

                val orderedIds = storageAdapter.getItems().map { it.id }
                CustomTileManager.saveTileOrder(this@CustomTileActivity, customTileId, orderedIds)
                showPremiumSnackbar(getString(R.string.tile_order_saved))
            }

            override fun isLongPressDragEnabled() = false
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerStorage)
    }

    // ── TV D-Pad Reorder ───────────────────────────────────────────────────

    private fun enterTvReorderMode(item: StorageItem) {
        reorderModeOriginalList = storageAdapter.getItems().toList()
        reorderModeItemId = item.id
        storageAdapter.reorderModeId = item.id
        storageAdapter.notifyDataSetChanged()
        showPremiumSnackbar(getString(R.string.dpad_moves_tile_ok_saves_back_cancels))
    }

    private fun exitTvReorderMode(save: Boolean) {
        if (!save) {
            reorderModeOriginalList?.let { storageAdapter.submitList(it) }
            showPremiumSnackbar(getString(R.string.tile_order_cancelled))
        } else {
            val orderedIds = storageAdapter.getItems().map { it.id }
            CustomTileManager.saveTileOrder(this, customTileId, orderedIds)
            showPremiumSnackbar(getString(R.string.tile_order_saved))
        }
        reorderModeItemId = null
        reorderModeOriginalList = null
        storageAdapter.reorderModeId = null
        storageAdapter.notifyDataSetChanged()
    }

    private fun moveTileInReorderMode(direction: Int) {
        val id = reorderModeItemId ?: return
        val list = storageAdapter.getItems().toMutableList()
        val fromIndex = list.indexOfFirst { it.id == id }
        if (fromIndex < 0) return

        val toIndex = (fromIndex + direction).coerceIn(0, list.lastIndex)
        if (toIndex == fromIndex) return

        list.add(toIndex, list.removeAt(fromIndex))
        storageAdapter.submitList(list)

        storageAdapter.reorderModeId = id

        recyclerStorage.postDelayed({
            val newPos = storageAdapter.getItems().indexOfFirst { it.id == id }
            if (newPos >= 0) {
                recyclerStorage.scrollToPosition(newPos)
                recyclerStorage.findViewHolderForAdapterPosition(newPos)?.itemView?.requestFocus()
            }
        }, 80)
    }

    // ── TV Key Handling ────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isTv) return super.dispatchKeyEvent(event)

        if (reorderModeItemId != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val cols = MainMenuViewModeManager.loadColumnCount(this)
                    val dir = if (storageAdapter.viewMode == MainMenuViewModeManager.ViewMode.GRID) -cols else -1
                    moveTileInReorderMode(dir)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val cols = MainMenuViewModeManager.loadColumnCount(this)
                    val dir = if (storageAdapter.viewMode == MainMenuViewModeManager.ViewMode.GRID) cols else 1
                    moveTileInReorderMode(dir)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (storageAdapter.viewMode == MainMenuViewModeManager.ViewMode.GRID) {
                        moveTileInReorderMode(-1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (storageAdapter.viewMode == MainMenuViewModeManager.ViewMode.GRID) {
                        moveTileInReorderMode(1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    exitTvReorderMode(save = true)
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    exitTvReorderMode(save = false)
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // ── Manage Tiles Sheet ─────────────────────────────────────────────────

    private fun showManageTilesSheet() {
        ManageTilesBottomSheet
            .newInstance()
            .withTiles(buildAllTilesForSheet())
            .withTileIcons(TileIconManager.getAllTileIcons(this))
            .withTileIconRes(TileIconManager.getAllTileIconRes(this))
            .apply {
                onRestored  = { loadTiles() }
                onTileClick = { item -> navigateTile(item) }
            }
            .show(supportFragmentManager, ManageTilesBottomSheet.TAG)
    }

    private fun buildAllTilesForSheet(): List<StorageItem> {
        val childIds = CustomTileManager.getChildTiles(this, customTileId)
        val allTiles = StorageBrowserActivity.getConnectedStorages(this, localOnly = false)
        val byId = allTiles.associateBy { it.id }
        return childIds.mapNotNull { byId[it]?.copy(parentCustomTileId = customTileId) }
    }

    // ── View Mode ──────────────────────────────────────────────────────────

    private fun applyViewMode() {
        val mode = MainMenuViewModeManager.loadViewMode(this)
        val cols = MainMenuViewModeManager.loadColumnCount(this)
        val size = MainMenuViewModeManager.loadItemSize(this)

        if (::storageAdapter.isInitialized) {
            storageAdapter.viewMode = mode
            storageAdapter.itemSize = size
            storageAdapter.gridColumnCount = cols
        }

        try { tvSnapHelper?.attachToRecyclerView(null) } catch (_: Exception) {}
        tvSnapHelper = null

        if (mode == MainMenuViewModeManager.ViewMode.LIST) {
            recyclerStorage.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
            if (::storageAdapter.isInitialized) storageAdapter.gridItemHeightPx = -1
        } else {
            recyclerStorage.layoutManager = GridLayoutManager(this, cols)
        }
    }

    // ── Color Picker ──────────────────────────────────────────────────────

    private fun openColorPicker(item: StorageItem) {
        isSelectingTileForColor = false
        storageAdapter.isColorPickMode = false

        if (isTv) {
            TvTileDataHolder.tiles = storageAdapter.getItems()
            TvTileDataHolder.sourceTileId = item.id
            TvTileDataHolder.sourceConfig = TileColorManager.loadTileColors(this)[item.id] ?: TileColorConfig()
            val intent = Intent(this, TileColorTvActivity::class.java)
            tileColorTvLauncher.launch(intent)
        } else {
            val existingIconPath = TileIconManager.getTileIcon(this, item.id)
            val config = TileColorManager.loadTileColors(this)[item.id] ?: TileColorConfig()
            val isList = MainMenuViewModeManager.loadViewMode(this) == MainMenuViewModeManager.ViewMode.LIST
            val sheet = TileColorBottomSheet.newInstance(
                tileId = item.id,
                tileName = item.label,
                tileIconRes = item.iconRes,
                tileSubtitle = item.subtitle,
                config = config,
                isListView = isList,
                customIconPath = existingIconPath
            )
            activeColorSheet = sheet
            sheet
                .setOnColorChangedListener { newConfig ->
                    TileColorManager.saveTileColor(this, item.id, newConfig)
                    storageAdapter.setTileColors(TileColorManager.loadTileColors(this))
                }
                .setOnIconChangedListener { iconConfig ->
                    TileIconManager.saveTileIconRes(this, item.id, iconConfig.selectedIconRes)
                    if (iconConfig.hasCustomIcon && iconConfig.customIconPath != null) {
                        TileIconManager.saveTileIcon(this, item.id, iconConfig.customIconPath)
                    } else if (!iconConfig.isBuiltinSelection) {
                        TileIconManager.clearTileIcon(this, item.id)
                    }
                    storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this))
                    storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this))
                }
                .setOnBrowseIconClickedListener {
                    activeTileIdForIcon = item.id
                    val intent = Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                        putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, "ico,png")
                        putExtra(StorageBrowserActivity.EXTRA_TILE_ICON_PICKER, true)
                    }
                    startActivityForResult(intent, REQUEST_TILE_ICON_PICKER)
                }
            sheet.show(supportFragmentManager, "tileColor")
        }
    }

    // Icon picker result handler
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TILE_ICON_PICKER && resultCode == Activity.RESULT_OK) {
            val selectedPath = data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (selectedPath != null) {
                val tileId = activeTileIdForIcon ?: return
                val sourceFile = java.io.File(selectedPath)
                if (sourceFile.exists() && sourceFile.length() > TileIconManager.MAX_SIZE_BYTES) {
                    android.widget.Toast.makeText(this, getString(R.string.tile_icon_file_too_large), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val privatePath = TileIconManager.copyToPrivateStorage(this, tileId, selectedPath)
                    if (privatePath != null) {
                        activeColorSheet?.onIconPicked(privatePath)
                    } else {
                        android.widget.Toast.makeText(this, getString(R.string.tile_icon_invalid_file), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            activeTileIdForIcon = null
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            layoutEmptyStorage.visibility = View.VISIBLE
            recyclerStorage.visibility = View.GONE
        } else {
            layoutEmptyStorage.visibility = View.GONE
            recyclerStorage.visibility = View.VISIBLE
        }
    }

    private fun updateHiddenBadge() {
        if (isEditMode) {
            if (isTv) {
                btnDoneTv?.visibility = View.VISIBLE
                btnColorTile?.visibility = View.VISIBLE
                btnImportColorCode?.visibility = View.VISIBLE
            } else {
                // Mobile: show checkmark button to exit edit mode
                btnManageTiles.setImageResource(R.drawable.ic_check)
                btnManageTiles.visibility = View.VISIBLE
                btnManageTiles.clearColorFilter()
                btnManageTiles.setBackgroundResource(R.drawable.bg_icon_circle_accent)
                btnColorTile?.visibility = View.VISIBLE
            }
        } else {
            if (isTv) {
                btnDoneTv?.visibility = View.GONE
                btnColorTile?.visibility = View.GONE
                btnImportColorCode?.visibility = View.GONE
            } else {
                btnManageTiles.visibility = View.GONE
                btnColorTile?.visibility = View.GONE
            }
        }
    }

    private fun showPremiumSnackbar(message: String) {
        val rootView = findViewById<View>(R.id.main)
        Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.ufm_surface_variant))
            .setTextColor(getColor(R.color.ufm_text_primary))
            .setActionTextColor(getColor(R.color.ufm_primary))
            .show()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isEditMode) {
            exitEditMode()
        } else if (reorderModeItemId != null) {
            exitTvReorderMode(save = false)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
