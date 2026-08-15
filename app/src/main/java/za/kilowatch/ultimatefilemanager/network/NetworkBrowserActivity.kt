package za.kilowatch.ultimatefilemanager.network

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.CheckBox
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.VaultActivity
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.ViewModeManager
import za.kilowatch.ultimatefilemanager.storage.FilePropertiesBottomSheet
import za.kilowatch.ultimatefilemanager.storage.FileTagsManager
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.storage.BatchRenameItem
import za.kilowatch.ultimatefilemanager.storage.BatchRenameDialogFragment
import za.kilowatch.ultimatefilemanager.storage.BatchRenameTvActivity
import za.kilowatch.ultimatefilemanager.storage.FileToolsBottomSheet
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.ui.PremiumShareActivity
import za.kilowatch.ultimatefilemanager.ui.PremiumShareTvActivity
import java.io.File
import java.io.FileOutputStream
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager
import za.kilowatch.ultimatefilemanager.archive.ArchiveOptionsDialog
import za.kilowatch.ultimatefilemanager.archive.ArchiveManager
import za.kilowatch.ultimatefilemanager.remote.RemoteTransportPrefs
import kotlin.coroutines.cancellation.CancellationException

object NetworkClipboard {
    enum class Operation { COPY, MOVE }

    data class Entry(
        val file: NetworkFile,
        val operation: Operation,
        val sourceShareId: String,
        val sourceRemotePath: String = ""
    )

    var entries: List<Entry> = emptyList()

    // Backward-compat aliases
    val files: List<NetworkFile> get() = entries.map { it.file }
    val operation: Operation get() = entries.firstOrNull()?.operation ?: Operation.COPY
    val sourceShareId: String get() = entries.firstOrNull()?.sourceShareId ?: ""
    val sourceRemotePath: String get() = entries.firstOrNull()?.sourceRemotePath ?: ""

    /** Append [items] with [op] from [shareId], deduplicating by path (newest wins). */
    fun add(items: List<NetworkFile>, op: Operation, shareId: String, sourceRemotePath: String = "") {
        val newPaths = items.map { it.path }.toSet()
        entries = entries.filter { it.file.path !in newPaths } +
                items.map { Entry(it, op, shareId, sourceRemotePath) }
    }

    /** Legacy alias — replaces entire clipboard. */
    fun set(items: List<NetworkFile>, op: Operation, shareId: String, sourceRemotePath: String = "") {
        entries = items.map { Entry(it, op, shareId, sourceRemotePath) }
    }

    fun remove(file: NetworkFile) {
        entries = entries.filter { it.file.path != file.path }
    }

    fun clear() { entries = emptyList() }
    fun hasItems() = entries.isNotEmpty()
}

class NetworkBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHARE_ID = "extra_share_id"
        const val EXTRA_STORAGE_LABEL = "extra_storage_label"
        const val EXTRA_PAIRED_DEVICE_ID = "extra_paired_device_id"
        /** Set when launching from a network favorite to start at a specific directory */
        const val EXTRA_INITIAL_PATH = "extra_initial_path"
        /** Set when launching from a network favorite file to open it automatically */
        const val EXTRA_OPEN_FILE_PATH = "extra_open_file_path"
        const val EXTRA_OPEN_FILE_NAME = "extra_open_file_name"
        /** When true: hides clipboard/selection UI and shows 'Use This Folder' FAB for Folder Sync setup */
        const val EXTRA_SYNC_FOLDER_PICKER = "extra_sync_folder_picker"
        /** When true: shows 'Use This Folder' FAB for Advanced Sync destination selection */
        const val EXTRA_ADVANCED_SYNC_FOLDER_PICKER = "extra_advanced_sync_folder_picker"
        /** When true: shows 'Use This Folder' FAB for Compress destination selection */
        const val EXTRA_COMPRESS_DEST_PICKER = "extra_compress_dest_picker"
        /** Returned in the result intent when the user confirms a sync folder path */
        const val RESULT_SELECTED_SYNC_PATH = "result_selected_sync_path"
        /** Returned when the user picks a network folder as compress destination */
        const val RESULT_SELECTED_COMPRESS_SHARE_ID = "result_compress_share_id"
        const val RESULT_SELECTED_COMPRESS_NET_PATH  = "result_compress_net_path"
        const val EXTRA_REMOTE_PATH = "extra_remote_path"
        private const val SIDELOAD_APK_PATH = "__sideload_apk__"
        private const val SIDELOAD_XAPK_PATH = "__sideload_xapk__"
        const val SCREENSHOT_PATH = "__screenshot__"
        const val RECORD_SCREEN_PATH = "__record_screen__"
        private const val USE_REMOTE_PATH = "__use_remote__"
        private const val TRANSFER_SETTINGS_PATH = "__transfer_settings__"
        
        /** When true, use this activity to pick a location (URI, etc) for server/vault */
        const val EXTRA_LOCATION_PICKER = "extra_location_picker"
        const val RESULT_URI = "result_uri"
        const val RESULT_LABEL = "result_label"
        const val RESULT_TYPE = "result_type"
        const val RESULT_META_ID = "result_meta_id"
        /** When true this activity is acting as a destination-folder picker for Quick Transfer */
        const val EXTRA_QUICK_TRANSFER_PICKER = "extra_quick_transfer_picker"
        /** "COPY" or "MOVE" â€” passed alongside EXTRA_QUICK_TRANSFER_PICKER */
        const val EXTRA_QUICK_TRANSFER_OP = "extra_quick_transfer_op"
        /** When true, the user is picking a folder for Smart Sort */
        const val EXTRA_SMART_SORT_PICKER = "extra_smart_sort_picker"
        const val EXTRA_SMART_SORT_CATEGORY_PICKER = "extra_smart_sort_category_picker"
        const val RESULT_SELECTED_SMART_SORT_PATH = "result_smart_sort_path"
        const val RESULT_SELECTED_SMART_SORT_SHARE_ID = "result_smart_sort_share_id"

        /** When true, the user is picking a destination folder for Share Receive */
        const val EXTRA_SHARE_DEST_PICKER = "extra_share_dest_picker"
        /** Result key: share ID returned when user confirms a Share Receive network destination */
        const val RESULT_SELECTED_SHARE_ID = "result_selected_share_id"
        /** Result key: network path returned when user confirms a Share Receive network destination */
        const val RESULT_SELECTED_NET_PATH = "result_selected_net_path"
        /** Result key: share ID returned when user confirms a Quick Transfer network destination */
        const val RESULT_SELECTED_QT_SHARE_ID = "result_qt_share_id"
        /** Result key: network path returned when user confirms a Quick Transfer network destination */
        const val RESULT_SELECTED_QT_NET_PATH = "result_qt_net_path"
    }

    private val isTv: Boolean
        get() {
            val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

    private lateinit var share: NetworkShare
    private var originalRemotePath: String = ""
    private lateinit var currentPath: String
    private var loadJob: kotlinx.coroutines.Job? = null
    
    // UI Elements
    private lateinit var txtTitle: TextView
    private var layoutBreadcrumbsScroll: android.widget.HorizontalScrollView? = null
    private var layoutBreadcrumbs: android.widget.LinearLayout? = null
    private lateinit var txtSubtitle: TextView
    private lateinit var btnBack: ImageView
    private lateinit var btnRefresh: ImageView
    private lateinit var btnCreateNew: ImageView
    private var btnViewToggle: ImageView? = null
    private var btnRetriggerThumbnails: ImageView? = null
    private var btnSort: ImageView? = null
    private lateinit var recyclerFiles: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var progressBar: ProgressBar

    // Selection UI
    private lateinit var layoutSelectionBar: LinearLayout
    private lateinit var txtSelectionCount: TextView
    private lateinit var btnCloseSelection: ImageView
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnDelete: MaterialButton
    private lateinit var btnCopy: ImageView
    private lateinit var btnMove: ImageView
    private lateinit var btnRename: ImageView
    private lateinit var btnShare: ImageView
    private lateinit var btnFavorite: ImageView
    private lateinit var btnCopyEncrypt: ImageView
    private lateinit var btnMoveEncrypt: ImageView
    private lateinit var btnProtect: ImageView
    private lateinit var btnUnprotect: ImageView
    private var btnPin: ImageView? = null
    private var btnUnpin: ImageView? = null
    private lateinit var btnCompress: android.view.View
    private var btnExtract: android.view.View? = null
    private lateinit var btnImageCompress: android.view.View
    private var btnOptionsToggle: ImageView? = null
    private var layoutOptionsRow: LinearLayout? = null
    private var isOptionsVisible = false
    private lateinit var btnSearchToggle: ImageView
    private lateinit var layoutSearchRow: LinearLayout
    private lateinit var edtSearch: EditText
    private lateinit var btnSearchClear: ImageView
    private var isSearchVisible = false
    private var searchJob: kotlinx.coroutines.Job? = null
    private lateinit var fabPaste: ExtendedFloatingActionButton
    private var fabProperties: ExtendedFloatingActionButton? = null
    private var fabTools: ExtendedFloatingActionButton? = null
    private var lastLoadedPath: String? = null

    // TV-only: inline clipboard panel (avoids BottomSheetDialog clipping on TV)
    private var tvClipboardPanel: View? = null
    private var tvClipboardTitle: TextView? = null
    
    private var initialRootPath: String? = null
    
    private var isPickerMode = false
    private var pickerExtensions: Set<String> = emptySet()
    private var isSyncFolderPickerMode = false
    private var isAdvancedSyncFolderPickerMode = false
    private var isCompressDestPickerMode = false
    private var isLocationPickerMode = false
    private var isQuickTransferPickerMode = false
    private var quickTransferIsMove = false
    private var isShareDestPickerMode = false
    private var isScannerFolderPicker = false
    private var isAutoBackupFolderPicker = false
    private var isImageCompressDestPickerMode = false
    private var isGifCreatorDestPickerMode = false
    private var isSmartSortPickerMode = false
    private var isSmartSortCategoryPickerMode = false
    private var currentFiles: List<NetworkFile> = emptyList()
    private var isTransferring = false
    private var transferJob: kotlinx.coroutines.Job? = null
    private var isCancelledByUser = false  // set when user presses Cancel; suppresses expected timeout errors
    private var currentTransferDestPath: String? = null  // tracks file being written for cancel cleanup
    private var currentTransferStreams: Pair<java.io.InputStream?, java.io.OutputStream?>? = null  // close on cancel to force copy exit
    private var currentTransferConnection: AutoCloseable? = null  // raw TCP connection — close() kills socket instantly
    private var standardShareTempDir: File? = null
    private lateinit var cacheManager: za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager
    
    private val batchRenameTvLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            fileAdapter.exitSelectionMode()
            loadDirectory()
        }
    }
    
    // Sort & filter state
    private var sortMode = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.NAME
    private var sortOrder = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortOrder.ASC
    private var filterType = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.ALL
    private var activeTagsFilter: Set<String> = emptySet()
    
    private lateinit var fileAdapter: NetworkFileAdapter

    // Pending compress-destination params (set before launching StorageBrowserActivity)
    private var pendingCompressSourceFiles: List<NetworkFile>? = null
    private var pendingCompressFileName: String? = null
    private var pendingCompressFormat: ArchiveManager.Format? = null
    private var pendingCompressPassword: String? = null

    private val localFolderPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            // Local destination?
            val localPath = data.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
            if (localPath != null) {
                val destDir = File(localPath)
                val src  = pendingCompressSourceFiles ?: return@registerForActivityResult
                val name = pendingCompressFileName   ?: return@registerForActivityResult
                val fmt  = pendingCompressFormat     ?: return@registerForActivityResult
                performNetworkCompression(src, CompressDest.Local(destDir), name, fmt, pendingCompressPassword)
                clearPendingCompress()
                return@registerForActivityResult
            }
            // Network destination?
            val shareId  = data.getStringExtra(RESULT_SELECTED_COMPRESS_SHARE_ID)
            val netPath  = data.getStringExtra(RESULT_SELECTED_COMPRESS_NET_PATH)
            if (shareId != null && netPath != null) {
                val destShare = resolveShareById(shareId)
                if (destShare != null) {
                    val src  = pendingCompressSourceFiles ?: return@registerForActivityResult
                    val name = pendingCompressFileName   ?: return@registerForActivityResult
                    val fmt  = pendingCompressFormat     ?: return@registerForActivityResult
                    performNetworkCompression(src, CompressDest.Network(destShare, netPath), name, fmt, pendingCompressPassword)
                }
                clearPendingCompress()
            }
        } else {
            clearPendingCompress()
        }
    }

    private var pendingQuickTransferFiles: List<NetworkFile>? = null
    private var pendingQuickTransferIsMove: Boolean = false

    private val quickTransferLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingQuickTransferFiles = null
        if (result.resultCode != RESULT_OK) {
            NetworkClipboard.clear()
            updatePasteFab()
            return@registerForActivityResult
        }
        
        val successCount = result.data?.getIntExtra("QT_SUCCESS_COUNT", -1) ?: -1
        val failCount = result.data?.getIntExtra("QT_FAIL_COUNT", -1) ?: -1

        // Destination: transfer was already executed inside the destination Activity.
        // Clipboard was cleared there too. Nothing more to do here.
        updatePasteFab()
        loadDirectory()
        
        if (successCount >= 0 && failCount >= 0) {
            if (failCount == 0 && successCount > 0) showPremiumSnackbar(getString(R.string.paste_success, successCount))
            else if (failCount > 0) showPremiumSnackbar(getString(R.string.paste_error))
        }
    }

    private fun launchQuickTransferPicker(files: List<NetworkFile>, isMove: Boolean) {
        pendingQuickTransferFiles = files
        pendingQuickTransferIsMove = isMove
        
        // Populate clipboard *now* so that the destination activity can read it directly
        NetworkClipboard.add(files, if (isMove) NetworkClipboard.Operation.MOVE else NetworkClipboard.Operation.COPY, share.id, share.remotePath)
        
        val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
            putExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_PICKER, true)
            putExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP, if (isMove) "move" else "copy")
        }
        quickTransferLauncher.launch(intent)
    }

    private fun clearPendingCompress() {
        pendingCompressSourceFiles = null
        pendingCompressFileName    = null
        pendingCompressFormat      = null
        pendingCompressPassword    = null
    }

    private fun resolveShareById(id: String): NetworkShare? {
        if (id == share.id) return share
        val fromRepo = NetworkShareRepository.getInstance(this).getById(id)
        if (fromRepo != null) return fromRepo
        val dev = PairingManager.getInstance(this).getPairedDevice(id)
        if (dev != null) return NetworkShare(
            id = dev.deviceId, name = dev.name,
            type = ShareType.TV, host = dev.lastIp, port = dev.lastPort, readOnly = false
        )
        return null
    }

    // Sideload picker result handler
    private var pendingSideloadType: String? = null // "apk" or "xapk"
    private val sideloadPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (selectedPath != null) {
                val type = pendingSideloadType ?: return@registerForActivityResult
                performSideloadInstall(File(selectedPath), type)
            }
        }
        pendingSideloadType = null
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Picker mode
        isPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_PICKER_MODE, false)
        val extString = intent.getStringExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS) ?: ""
        pickerExtensions = extString.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        isSyncFolderPickerMode = intent.getBooleanExtra(EXTRA_SYNC_FOLDER_PICKER, false)
        isAdvancedSyncFolderPickerMode = intent.getBooleanExtra(EXTRA_ADVANCED_SYNC_FOLDER_PICKER, false)
        android.util.Log.d("AdvSyncDest", "Flags: isAdvSyncPicker=$isAdvancedSyncFolderPickerMode, isSyncPicker=$isSyncFolderPickerMode, isPicker=$isPickerMode, isQuickTransfer=$isQuickTransferPickerMode, isCompress=$isCompressDestPickerMode, isShareDest=$isShareDestPickerMode, isLoc=$isLocationPickerMode")
        isCompressDestPickerMode = intent.getBooleanExtra(EXTRA_COMPRESS_DEST_PICKER, false)
        isLocationPickerMode = intent.getBooleanExtra(EXTRA_LOCATION_PICKER, false)
        isQuickTransferPickerMode = intent.getBooleanExtra(EXTRA_QUICK_TRANSFER_PICKER, false)
        quickTransferIsMove = intent.getStringExtra(EXTRA_QUICK_TRANSFER_OP) == "MOVE"
        isShareDestPickerMode = intent.getBooleanExtra(EXTRA_SHARE_DEST_PICKER, false)
        isScannerFolderPicker = intent.getBooleanExtra(FileBrowserActivity.EXTRA_SCANNER_FOLDER_PICKER, false)
        isAutoBackupFolderPicker = intent.getBooleanExtra(FileBrowserActivity.EXTRA_AUTO_BACKUP_FOLDER_PICKER, false)
        isImageCompressDestPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER, false)
        isGifCreatorDestPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_GIF_CREATOR_DEST_PICKER, false)
        isSmartSortPickerMode = intent.getBooleanExtra(EXTRA_SMART_SORT_PICKER, false)
        isSmartSortCategoryPickerMode = intent.getBooleanExtra(EXTRA_SMART_SORT_CATEGORY_PICKER, false)
        cacheManager = za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager(this)
        
        val layoutRes = if (isTv) R.layout.activity_network_browser_tv else R.layout.activity_network_browser
        setContentView(layoutRes)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                systemBars.left, systemBars.top,
                systemBars.right, systemBars.bottom
            )
            insets
        }

        val pairedDeviceId = intent.getStringExtra(EXTRA_PAIRED_DEVICE_ID)
        val device = if (pairedDeviceId != null) PairingManager.getInstance(this).getPairedDevice(pairedDeviceId) else null
        
        if (device != null) {
            share = NetworkShare(
                id = device.deviceId,
                name = device.name,
                type = ShareType.TV,
                host = device.lastIp,
                port = device.lastPort,
                readOnly = false
            )
        } else {
            val shareId = intent.getStringExtra(EXTRA_SHARE_ID) ?: run {
                finish()
                return
            }
            
            val isOnlineStorage = intent.getBooleanExtra("isOnlineStorage", false)
            if (isOnlineStorage) {
                val foundShare = OnlineStorageRepository.getInstance(this).getById(shareId)
                if (foundShare == null) {
                    finish()
                    return
                }
                share = NetworkShare(
                    id = foundShare.id,
                    name = foundShare.displayName,
                    type = when (foundShare.provider) {
                        OnlineStorageProvider.ONEDRIVE     -> ShareType.ONEDRIVE
                        OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                        OnlineStorageProvider.DROPBOX      -> ShareType.DROPBOX
                        OnlineStorageProvider.AWS_S3       -> ShareType.AWS_S3
                        OnlineStorageProvider.IDRIVE_E2    -> ShareType.IDRIVE_E2
                        OnlineStorageProvider.WEBDAV       -> ShareType.WEBDAV
                        OnlineStorageProvider.RCLONE       -> ShareType.WEBDAV
                    },
                    host = when (foundShare.provider) {
                        OnlineStorageProvider.RCLONE  -> RCloneShareClient.RCLONE_HOST_MARKER
                        else -> if (foundShare.isWebDavProvider) foundShare.webDavUrl ?: ""
                                else foundShare.s3Endpoint ?: foundShare.email
                    },
                    port = 0,
                    username = when (foundShare.provider) {
                        // Use storage.id as the remote name — it is the section header
                        // in the encrypted rclone.conf and the name registered via
                        // config/create in launchRCloneBrowse, so all three are in sync.
                        OnlineStorageProvider.RCLONE  -> foundShare.id
                        else -> if (foundShare.isWebDavProvider) foundShare.webDavUsername ?: ""
                                else foundShare.s3AccessKey ?: foundShare.email
                    },
                    password = when {
                        foundShare.isWebDavProvider -> foundShare.webDavPassword ?: ""
                        else                        -> foundShare.s3SecretKey ?: ""
                    },
                    domain   = foundShare.s3Bucket ?: "",
                    remotePath = foundShare.s3Region ?: "/",
                    readOnly = false
                )
            } else {
                val foundShare = NetworkShareRepository.getInstance(this).getById(shareId)
                if (foundShare == null) {
                    finish()
                    return
                }
                share = foundShare
            }
        }
        originalRemotePath = share.remotePath
        
        currentPath = intent.getStringExtra(EXTRA_INITIAL_PATH) ?: ""
        initialRootPath = currentPath.ifEmpty { null }
        
        // Restore sort preferences
        val prefs = getSharedPreferences("ufm_prefs", MODE_PRIVATE)
        sortMode = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.entries.getOrElse(
            prefs.getInt("sort_mode", 0)
        ) { za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.NAME }
        sortOrder = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortOrder.entries.getOrElse(
            prefs.getInt("sort_order", 0)
        ) { za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortOrder.ASC }
        
        setupViews()
        
        val openFilePath = intent.getStringExtra(EXTRA_OPEN_FILE_PATH)
        val openFileName = intent.getStringExtra(EXTRA_OPEN_FILE_NAME)
        if (openFilePath != null && openFileName != null) {
            val fileToOpen = NetworkFile(
                name = openFileName,
                path = openFilePath,
                isDirectory = false
            )
            intent.removeExtra(EXTRA_OPEN_FILE_PATH)
            intent.removeExtra(EXTRA_OPEN_FILE_NAME)
            findViewById<View>(R.id.main).post {
                openNetworkFile(fileToOpen)
            }
        }
        
        loadDirectory()
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pairedDeviceId = intent?.getStringExtra(EXTRA_PAIRED_DEVICE_ID) ?: this@NetworkBrowserActivity.intent.getStringExtra(EXTRA_PAIRED_DEVICE_ID)
            if (pairedDeviceId != null) {
                val device = PairingManager.getInstance(this@NetworkBrowserActivity).getPairedDevice(pairedDeviceId)
                if (device == null || !device.isConnected) {
                    Snackbar.make(findViewById(R.id.main), getString(R.string.device_disconnected), Snackbar.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.w("UFM_COPY", ">>> onStart called, isTransferring=$isTransferring")
        val filter = IntentFilter("za.kilowatch.ufm.PAIRING_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.w("UFM_COPY", ">>> onStop called, isTransferring=$isTransferring")
        unregisterReceiver(updateReceiver)
    }

    override fun onResume() {
        super.onResume()
        applyLeftHandedFabSettings()
        android.util.Log.w("UFM_COPY", ">>> onResume called, isTransferring=$isTransferring")
        if (isTransferring) return  // Don't interfere with active transfers
        za.kilowatch.ultimatefilemanager.util.TransferService.stop(this)
        updatePasteFab()
        applyToolbarIconVisibility()
        
        // Clean up standard share temp dir if returning from standard share
        standardShareTempDir?.let { tempDir ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    tempDir.deleteRecursively()
                    android.util.Log.d("NetworkBrowserActivity", "Cleaned up standard share temp: ${tempDir.absolutePath}")
                } catch (_: Exception) {}
            }
            standardShareTempDir = null
        }
        
        // If browsing a paired TV/Phone, check if it's still connected
        val pairedDeviceId = intent.getStringExtra(EXTRA_PAIRED_DEVICE_ID)
        if (pairedDeviceId != null) {
            val device = PairingManager.getInstance(this).getPairedDevice(pairedDeviceId)
            if (device == null || !device.isConnected) {
                Snackbar.make(findViewById(R.id.main), getString(R.string.device_disconnected), Snackbar.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun applyLeftHandedFabSettings() {
        val isLeftHanded = za.kilowatch.ultimatefilemanager.settings.LeftHandedFabPreferenceManager.isLeftHanded(this)
        val viewsToUpdate = mutableListOf<android.view.View>()
        if (::fabPaste.isInitialized) {
            viewsToUpdate.add(fabPaste)
        }
        fabTools?.let { viewsToUpdate.add(it) }

        for (fab in viewsToUpdate) {
            val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: continue
            if (isLeftHanded) {
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            } else {
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            }
            fab.layoutParams = lp
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up temporary downloaded files
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                cacheDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && file.name.startsWith("share_temp_")) {
                        file.deleteRecursively()
                        android.util.Log.d("NetworkBrowserActivity", "Cleaned up temporary directory: ${file.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkBrowserActivity", "Error cleaning up temporary files", e)
            }
        }
    }

    private fun applyToolbarIconVisibility() {
        if (!::btnCopy.isInitialized) return
        val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
        btnCopy.visibility = if (pm.isIconEnabled(this, pm.KEY_COPY)) View.VISIBLE else View.GONE
        btnMove.visibility = if (pm.isIconEnabled(this, pm.KEY_MOVE)) View.VISIBLE else View.GONE
        btnRetriggerThumbnails?.visibility = if (pm.isIconEnabled(this, pm.KEY_RETRIGGER_THUMBNAILS)) View.VISIBLE else View.GONE
        btnRename.visibility = if (pm.isIconEnabled(this, pm.KEY_RENAME)) View.VISIBLE else View.GONE
        btnShare.visibility = if (pm.isIconEnabled(this, pm.KEY_SHARE)) View.VISIBLE else View.GONE
        btnCopyEncrypt.visibility = if (pm.isIconEnabled(this, pm.KEY_COPY_ENCRYPT)) View.VISIBLE else View.GONE
        btnMoveEncrypt.visibility = if (pm.isIconEnabled(this, pm.KEY_MOVE_ENCRYPT)) View.VISIBLE else View.GONE
        btnFavorite.visibility = if (pm.isIconEnabled(this, pm.KEY_FAVORITE)) View.VISIBLE else View.GONE
        btnProtect.visibility = if (pm.isIconEnabled(this, pm.KEY_PROTECT)) View.VISIBLE else View.GONE
        btnUnprotect.visibility = if (pm.isIconEnabled(this, pm.KEY_UNPROTECT)) View.VISIBLE else View.GONE
        btnPin?.visibility = if (pm.isIconEnabled(this, pm.KEY_PIN)) View.VISIBLE else View.GONE
        btnUnpin?.visibility = if (pm.isIconEnabled(this, pm.KEY_UNPIN)) View.VISIBLE else View.GONE
        btnCompress.visibility = if (pm.isIconEnabled(this, pm.KEY_COMPRESS)) View.VISIBLE else View.GONE
        btnImageCompress.visibility = View.GONE
        btnSelectAll.visibility = if (pm.isIconEnabled(this, pm.KEY_SELECT_ALL)) View.VISIBLE else View.GONE
        btnDelete.visibility = if (pm.isIconEnabled(this, pm.KEY_DELETE)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnCreateNew)?.visibility = if (pm.isIconEnabled(this, pm.KEY_CREATE_NEW)) View.VISIBLE else View.GONE
    }

    private fun setupViews() {
        txtTitle = findViewById(R.id.txtTitle)
        txtSubtitle = findViewById(R.id.txtSubtitle)
        btnBack = findViewById(R.id.btnBack)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnCreateNew = findViewById(R.id.btnCreateNew)
        btnViewToggle = findViewById(R.id.btnViewToggle)
        recyclerFiles = findViewById(R.id.recyclerFiles)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        progressBar = findViewById(R.id.progressBar)
        layoutBreadcrumbsScroll = findViewById(R.id.layoutBreadcrumbsScroll)
        layoutBreadcrumbs = findViewById(R.id.layoutBreadcrumbs)

        layoutSelectionBar = findViewById(R.id.layoutSelectionBar)
        txtSelectionCount = findViewById(R.id.txtSelectionCount)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDelete = findViewById(R.id.btnDelete)
        btnCopy = findViewById(R.id.btnCopy)
        btnMove = findViewById(R.id.btnMove)
        btnRename = findViewById(R.id.btnRename)
        btnShare = findViewById(R.id.btnShare)
        btnFavorite = findViewById(R.id.btnFavorite)
        btnCopyEncrypt = findViewById(R.id.btnCopyEncrypt)
        btnMoveEncrypt = findViewById(R.id.btnMoveEncrypt)
        btnProtect = findViewById(R.id.btnProtect)
        btnUnprotect = findViewById(R.id.btnUnprotect)
        btnPin = findViewById(R.id.btnPin)
        btnUnpin = findViewById(R.id.btnUnpin)
        btnCompress = findViewById(R.id.btnCompress)
        btnExtract = findViewById(R.id.btnExtract)
        btnImageCompress = findViewById(R.id.btnImageCompress)
        btnRetriggerThumbnails = findViewById(R.id.btnRetriggerThumbnails)
        fabPaste = findViewById(R.id.fabPaste)
        fabTools = findViewById(R.id.fabTools)
        
        btnSearchToggle = findViewById(R.id.btnSearchToggle)
        btnSearchToggle.setImageResource(R.drawable.ic_search)
        btnSearchToggle.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_denied)) // Initial state: red
        layoutSearchRow = findViewById(R.id.layoutSearchRow)
        edtSearch = findViewById(R.id.edtSearch)
        btnSearchClear = findViewById(R.id.btnSearchClear)

        val storageLabel = intent.getStringExtra(EXTRA_STORAGE_LABEL) ?: share.name
        txtTitle.text = storageLabel
        
        if (share.readOnly) {
            btnCreateNew.visibility = View.GONE
            btnDelete.visibility = View.GONE
            btnRename.visibility = View.GONE
            btnMove.visibility = View.GONE
            btnMoveEncrypt.visibility = View.GONE
        }
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTransferring) {
                    showPremiumSnackbar(getString(R.string.please_wait_for_the_transfer_to_finish_or_press_cancel_on_the_dialog))
                    return
                }
                navigateBack()
            }
        })
        
        btnBack.setOnClickListener { navigateBack() }
        btnRefresh.setOnClickListener { loadDirectory() }
        
        btnOptionsToggle = findViewById(R.id.btnOptionsToggle)
        layoutOptionsRow = findViewById(R.id.layoutOptionsRow)
        
        val prefs = getSharedPreferences("ufm_prefs", MODE_PRIVATE)
        isOptionsVisible = prefs.getBoolean("toolbar_options_visible", false)

        if (isTv) {
            btnOptionsToggle?.visibility = View.GONE
            layoutOptionsRow?.visibility = View.GONE
        } else {
            layoutOptionsRow?.visibility = if (isOptionsVisible) View.VISIBLE else View.GONE
            btnOptionsToggle?.setImageResource(if (isOptionsVisible) R.drawable.ic_settings else R.drawable.ic_settings_off)

            btnOptionsToggle?.setOnClickListener {
                isOptionsVisible = !isOptionsVisible
                layoutOptionsRow?.visibility = if (isOptionsVisible) View.VISIBLE else View.GONE
                btnOptionsToggle?.setImageResource(if (isOptionsVisible) R.drawable.ic_settings else R.drawable.ic_settings_off)
                prefs.edit().putBoolean("toolbar_options_visible", isOptionsVisible).apply()
            }
        }
        
        btnSearchToggle.setOnClickListener {
            toggleSearch()
        }

        // Twin Window: launch with current network share as top pane
        if (!isTv) {
            findViewById<android.widget.ImageView>(R.id.btnTwinWindow)?.setOnClickListener {
                // In server-mode SMB, share.remotePath holds the active share name (e.g. "/Share")
                // and currentPath is the full UI path (e.g. "Share/Camera"). NetworkBrowserFragment
                // needs the path with the share name as the first segment. Reconstruct it from
                // share.remotePath + stripSharePrefix(currentPath) — same logic as the exit fix.
                val twinInitialPath = if (share.isServerMode) {
                    val shareName = share.remotePath.trimStart('/')
                    val subPath   = stripSharePrefix(currentPath.trimStart('/'))
                    if (shareName.isEmpty()) subPath
                    else if (subPath.isEmpty()) shareName
                    else "$shareName/$subPath"
                } else {
                    currentPath
                }
                android.util.Log.d("TPath", "Open twin window (mobile): share.id=${share.id} share.name=${share.name} serverMode=${share.isServerMode} remotePath='${share.remotePath}' currentPath='$currentPath' twinInitialPath='$twinInitialPath'")
                val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity::class.java).apply {
                    putExtra(za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity.EXTRA_TOP_SHARE_ID, share.id)
                    putExtra(za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity.EXTRA_TOP_SHARE_PATH, twinInitialPath)
                }
                startActivity(intent)
            }
        }

        btnSearchClear.setOnClickListener {
            edtSearch.setText("")
        }

        edtSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""
                btnSearchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(500)
                    performSearch(query)
                }
            }
        })
        
        edtSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = edtSearch.text.toString().trim()
                performSearch(query)
                true
            } else false
        }
        
        btnCreateNew.setOnClickListener { showCreateNewMenu() }

        if (!isTv) {
            btnViewToggle?.setOnClickListener {
                ViewModeManager.showSelectionDialog(this, fileAdapter.viewMode) { selectedMode ->
                    val folderKey = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.folderKey(share.id, currentPath)
                    if (za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.hasFolderOverride(this, currentPath, share.id)) {
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val state = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.loadForPath(this@NetworkBrowserActivity, currentPath, share.id)
                            if (state != null) {
                                za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.saveFolderSpecific(
                                    this@NetworkBrowserActivity, folderKey, "${if (share.name.isNotEmpty()) share.name else share.host}:$currentPath",
                                    state.copy(viewMode = selectedMode), isNetwork = true
                                )
                            }
                        }
                    } else {
                        ViewModeManager.save(this, selectedMode)
                    }
                    applyViewMode(selectedMode)
                }
            }
        }

        btnSort = findViewById(R.id.btnSort)
        btnSort?.setOnClickListener { showSortFilterSheet() }

        fileAdapter = NetworkFileAdapter(
            isTv = isTv,
            initialShare = share,
            context = this,
            onItemClick = { file ->
                // Toggle items are handled by their Switch; ignore row taps
                if (file.isToggle) return@NetworkFileAdapter
                // Intercept sentinel items
                when (file.path) {
                    SCREENSHOT_PATH -> {
                        performTvScreenshot()
                        return@NetworkFileAdapter
                    }
                    RECORD_SCREEN_PATH -> {
                        performTvRecordScreen()
                        return@NetworkFileAdapter
                    }
                    SIDELOAD_APK_PATH -> {
                        launchFilePicker("apk")
                        return@NetworkFileAdapter
                    }
                    SIDELOAD_XAPK_PATH -> {
                        launchFilePicker("xapk,apks")
                        return@NetworkFileAdapter
                    }
                    TRANSFER_SETTINGS_PATH -> {
                        val pairedDeviceId = intent.getStringExtra(EXTRA_PAIRED_DEVICE_ID) ?: ""
                        val intent = Intent(this@NetworkBrowserActivity, TransferSettingsActivity::class.java).apply {
                            putExtra(EXTRA_PAIRED_DEVICE_ID, pairedDeviceId)
                            putExtra(TransferSettingsActivity.EXTRA_SHARE_HOST, share.host)
                            putExtra(TransferSettingsActivity.EXTRA_SHARE_PORT, share.port)
                        }
                        startActivity(intent)
                        return@NetworkFileAdapter
                    }
                }

                if (file.isDirectory) {
                    if (share.type == ShareType.TV && initialRootPath == null) {
                        initialRootPath = file.path // Cache the first mount point we ever click
                    }
                    currentPath = if (share.type == ShareType.TV || share.type == ShareType.DLNA || share.type == ShareType.SFTP || share.type == ShareType.SCP) file.path else if (currentPath.isEmpty()) file.name else "$currentPath/${file.name}"
                    loadDirectory()
                } else if (isPickerMode) {
                    downloadAndReturnFile(file)
                } else {
                    openNetworkFile(file)
                }
            },
            onSelectionChanged = { count ->
                updateSelectionBar(count)
            },
            onToggleChanged = { file, isChecked ->
                val deviceId = intent.getStringExtra(EXTRA_PAIRED_DEVICE_ID) ?: ""
                handleUseRemoteToggle(deviceId, isChecked)
            }
        )

        recyclerFiles.adapter = fileAdapter
        fileAdapter.isGroupedByDate = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(this)
        
        // Load and apply initial view mode
        val initialMode = ViewModeManager.load(this)
        applyViewMode(initialMode)

        // Hide editing controls in picker mode
        if (isPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnCreateNew)?.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnViewToggle)?.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnSort)?.visibility = View.GONE
            return
        }

        // Sync folder picker mode: show New Folder + Use This Folder FAB
        if (isSyncFolderPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.visibility = View.GONE
            // Show new folder button so the user can create one if needed
            if (!share.readOnly) {
                btnCreateNew.visibility = View.VISIBLE
            }
            // Dynamically add a 'Use This Folder' FAB above the existing FAB area
            showUseFolderFab()
            return
        }

        // Advanced Sync folder picker mode: show New Folder + Use This Folder FAB
        if (isAdvancedSyncFolderPickerMode) {
            android.util.Log.d("AdvSyncDest", "Advanced sync picker mode MATCHED at setupViews")
            layoutSelectionBar.visibility = View.GONE
            fabPaste.visibility = View.GONE
            if (!share.readOnly) {
                btnCreateNew.visibility = View.VISIBLE
            }
            showUseFolderFab()
            return
        }

        // Quick Transfer picker mode: show "Copy Here" / "Move Here" FAB
        if (isQuickTransferPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            showUseFolderFab()
            return
        }

        // Share Receive picker mode: show "Save Here" FAB
        if (isShareDestPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            showUseFolderFab()
            return
        }

        // Scanner folder picker mode: show "Use This Folder" FAB
        if (isScannerFolderPicker) {
            layoutSelectionBar.visibility = View.GONE
            showUseFolderFab()
            return
        }

        // Auto Backup folder picker mode: show "Select as Backup Location" FAB
        if (isAutoBackupFolderPicker) {
            layoutSelectionBar.visibility = View.GONE
            showUseFolderFab()
            return
        }

        // Image Compress folder picker mode: show "Use This Folder" FAB
        if (isImageCompressDestPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            showUseFolderFab()
            return
        }

        // GIF Creator folder picker mode: show "Use This Folder" FAB
        if (isGifCreatorDestPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            showUseFolderFab()
            return
        }

        // Smart Sort picker mode: show "Smart Sort Here" FAB
        if (isSmartSortPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.visibility = View.GONE
            showUseFolderFab()
            return
        }

        // Selection Actions
        btnCloseSelection.setOnClickListener { fileAdapter.exitSelectionMode() }
        btnSelectAll.setOnClickListener {
            if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
        }
        btnDelete.setOnClickListener { showDeleteConfirmation() }
        btnRename.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isEmpty()) return@setOnClickListener
            if (selected.size == 1) {
                showRenameDialog(selected.first())
            } else {
                val items = selected.map { BatchRenameItem.fromNetworkFile(it, share) }
                if (isTv) {
                    val intent = Intent(this, BatchRenameTvActivity::class.java).apply {
                        putParcelableArrayListExtra("items", ArrayList(items))
                    }
                    batchRenameTvLauncher.launch(intent)
                } else {
                    val dialog = BatchRenameDialogFragment.newInstance(items)
                    dialog.setOnCompleteListener { _, _ ->
                        fileAdapter.exitSelectionMode()
                        loadDirectory()
                    }
                    dialog.show(supportFragmentManager, BatchRenameDialogFragment.TAG)
                }
            }
        }
        btnCopy.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                if (za.kilowatch.ultimatefilemanager.settings.QuickTransferPreferenceManager.isEnabled(this)) {
                    launchQuickTransferPicker(selected, isMove = false)
                } else {
                    android.util.Log.d("ClipboardTrace", "COPY: share.id=${share.id} share.name=${share.name} share.remotePath='${share.remotePath}' share.isServerMode=${share.isServerMode} firstFile.path='${selected.firstOrNull()?.path}' count=${selected.size}")
                    NetworkClipboard.add(selected, NetworkClipboard.Operation.COPY, share.id, share.remotePath)
                    fileAdapter.exitSelectionMode()
                    showPremiumSnackbar(getString(R.string.clipboard_copied, selected.size))
                    updatePasteFab()
                }
            }
        }
        btnMove.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                if (za.kilowatch.ultimatefilemanager.settings.QuickTransferPreferenceManager.isEnabled(this)) {
                    launchQuickTransferPicker(selected, isMove = true)
                } else {
                    NetworkClipboard.add(selected, NetworkClipboard.Operation.MOVE, share.id, share.remotePath)
                    fileAdapter.exitSelectionMode()
                    showPremiumSnackbar(getString(R.string.clipboard_cut, selected.size))
                    updatePasteFab()
                }
            }
        }

        btnShare.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles().filter { !it.isDirectory }
            if (selected.isEmpty()) {
                showPremiumSnackbar(getString(R.string.select_files_not_folders_to_share))
                return@setOnClickListener
            }
            shareNetworkFiles(selected)
        }
        
        btnFavorite.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.size == 1) {
                showFavoriteDialog(selected.first())
            }
        }

        btnCopyEncrypt.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles().filter { !it.isDirectory }
            if (selected.isEmpty()) {
                showPremiumSnackbar(getString(R.string.select_files_not_folders_to_encrypt))
                return@setOnClickListener
            }
            showNetworkVaultPicker(selected, isMove = false)
        }
        btnMoveEncrypt.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles().filter { !it.isDirectory }
            if (selected.isEmpty()) {
                showPremiumSnackbar("Select files (not folders) to encrypt")
                return@setOnClickListener
            }
            showNetworkVaultPicker(selected, isMove = true)
        }

        btnProtect.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(this@NetworkBrowserActivity, file.path, share.id, protected = true)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.deselectAll()
                        loadDirectory()
                        showPremiumSnackbar(getString(R.string.toast_protected_success, selected.size))
                    }
                }
            }
        }

        btnUnprotect.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(this@NetworkBrowserActivity, file.path, share.id, protected = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.deselectAll()
                        loadDirectory()
                        showPremiumSnackbar(getString(R.string.toast_unprotected_success, selected.size))
                    }
                }
            }
        }

        btnPin?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(this@NetworkBrowserActivity, file.path, share.id, pinned = true)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.deselectAll()
                        loadDirectory()
                        showPremiumSnackbar(getString(R.string.toast_pinned_success, selected.size))
                    }
                }
            }
        }

        btnUnpin?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(this@NetworkBrowserActivity, file.path, share.id, pinned = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.deselectAll()
                        loadDirectory()
                        showPremiumSnackbar(getString(R.string.toast_unpinned_success, selected.size))
                    }
                }
            }
        }

        btnCompress.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                showArchiveOptions(selected)
            }
        }

        btnExtract?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles().filter {
                ArchiveManager.isSupportedArchiveExtension(it.name.substringAfterLast('.'))
            }
            if (selected.isNotEmpty()) {
                performNetworkExtractHere(selected)
            }
        }

        btnImageCompress.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles().filter {
                it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            }
            if (selected.isNotEmpty()) {
                downloadNetworkImagesAndCompress(selected)
            }
        }

        btnRetriggerThumbnails?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    cacheManager.clearCacheForSelection(share.id, selected)
                    for (file in selected) {
                        if (file.isDirectory) {
                            NetworkFileAdapter.clearCacheForFolder(file.path)
                        } else {
                            NetworkFileAdapter.clearCacheForPath(file.path)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory()
                        showPremiumSnackbar(getString(R.string.retrigger_thumbnails_success))
                    }
                }
            }
        }

        fabPaste.setOnClickListener { showClipboardSheet() }

        fabProperties?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.size == 1 && !selected.first().isDirectory) {
                val file = selected.first()
                val sheet = FilePropertiesBottomSheet.newInstance(
                    filePath = file.path,
                    isDirectory = false,
                    size = file.size,
                    lastModified = file.lastModified,
                    isNetwork = true
                )
                sheet.show(supportFragmentManager, FilePropertiesBottomSheet.TAG)
            } else if (selected.size > 1 && selected.all { !it.isDirectory }) {
                val filePaths = selected.map { it.path }
                FileTagsManager.showMultiFileTagDialog(this, filePaths) {
                    fileAdapter.exitSelectionMode()
                    loadDirectory()
                }
            }
        }

        fabTools?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            val count = selected.size
            val showActions = count > 0
            if (!showActions) return@setOnClickListener

            val list = mutableListOf<FileToolsBottomSheet.ActionItem>()
            val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager

            // 0. Invert Selection
            if (pm.isIconEnabled(this, pm.KEY_INVERT_SELECTION)) {
                list.add(FileToolsBottomSheet.ActionItem("invert_selection", getString(R.string.action_invert_selection), R.drawable.ic_invert_selection, "toolbar_invert_selection") {
                    fileAdapter.invertSelection()
                })
            }

            // 1. Copy
            if (pm.isIconEnabled(this, pm.KEY_COPY)) {
                list.add(FileToolsBottomSheet.ActionItem("copy", getString(R.string.action_copy), R.drawable.ic_copy, "toolbar_copy") {
                    NetworkClipboard.add(selected, NetworkClipboard.Operation.COPY, share.id, share.remotePath)
                    fileAdapter.exitSelectionMode()
                    showPremiumSnackbar(getString(R.string.clipboard_copied, selected.size))
                    updatePasteFab()
                })
            }

            // 2. Move (Cut)
            if (pm.isIconEnabled(this, pm.KEY_MOVE)) {
                list.add(FileToolsBottomSheet.ActionItem("move", getString(R.string.action_move), R.drawable.ic_move, "toolbar_move") {
                    NetworkClipboard.add(selected, NetworkClipboard.Operation.MOVE, share.id, share.remotePath)
                    fileAdapter.exitSelectionMode()
                    showPremiumSnackbar(getString(R.string.clipboard_cut, selected.size))
                    updatePasteFab()
                })
            }

            // Delete
            if (!share.readOnly && pm.isIconEnabled(this, pm.KEY_DELETE)) {
                list.add(FileToolsBottomSheet.ActionItem("delete", getString(R.string.action_delete), R.drawable.ic_delete, "toolbar_delete") {
                    showDeleteConfirmation()
                })
            }

            // 3. Rename
            if (pm.isIconEnabled(this, pm.KEY_RENAME)) {
                list.add(FileToolsBottomSheet.ActionItem("rename", getString(R.string.action_rename), R.drawable.ic_edit, "toolbar_rename") {
                    if (selected.size == 1) {
                        showRenameDialog(selected.first())
                    } else {
                        val items = selected.map { BatchRenameItem.fromNetworkFile(it, share) }
                        if (DeviceUtils.isTvDevice(this)) {
                            val intent = Intent(this, BatchRenameTvActivity::class.java).apply {
                                putParcelableArrayListExtra("items", ArrayList(items))
                            }
                            batchRenameTvLauncher.launch(intent)
                        } else {
                            val dialog = BatchRenameDialogFragment.newInstance(items)
                            dialog.setOnCompleteListener { _, _ ->
                                fileAdapter.exitSelectionMode()
                                loadDirectory()
                            }
                            dialog.show(supportFragmentManager, BatchRenameDialogFragment.TAG)
                        }
                    }
                })
            }

            // 4. Share
            if (pm.isIconEnabled(this, pm.KEY_SHARE)) {
                val shareable = selected.filter { !it.isDirectory }
                if (shareable.isNotEmpty()) {
                    list.add(FileToolsBottomSheet.ActionItem("share", getString(R.string.action_share), R.drawable.ic_share, "toolbar_share") {
                        shareNetworkFiles(shareable)
                    })
                }
            }

            // 5. Favorite
            if (count == 1 && pm.isIconEnabled(this, pm.KEY_FAVORITE)) {
                list.add(FileToolsBottomSheet.ActionItem("favorite", getString(R.string.action_favorite), R.drawable.ic_star, "toolbar_favorite") {
                    showFavoriteDialog(selected.first())
                })
            }

            // 6. Compress
            if (pm.isIconEnabled(this, pm.KEY_COMPRESS)) {
                list.add(FileToolsBottomSheet.ActionItem("compress", getString(R.string.action_compress), R.drawable.ic_compress, "toolbar_compress") {
                    showArchiveOptions(selected)
                })
            }

            // 6b. Extract Here
            val hasArchiveSelected = selected.isNotEmpty() && selected.any {
                ArchiveManager.isSupportedArchiveExtension(it.name.substringAfterLast('.'))
            }
            if (hasArchiveSelected && pm.isIconEnabled(this, pm.KEY_EXTRACT)) {
                list.add(FileToolsBottomSheet.ActionItem("extract_here", getString(R.string.action_extract_here), R.drawable.ic_extract, "toolbar_extract") {
                    performNetworkExtractHere(selected.filter {
                        ArchiveManager.isSupportedArchiveExtension(it.name.substringAfterLast('.'))
                    })
                })
            }

            // 7. Compress Image
            val allImages = selected.isNotEmpty() && selected.all {
                it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            }
            if (allImages && pm.isIconEnabled(this, pm.KEY_IMAGE_COMPRESS)) {
                list.add(FileToolsBottomSheet.ActionItem("image_compress", getString(R.string.action_compress_image), R.drawable.ic_compress_image, "toolbar_image_compress") {
                    downloadNetworkImagesAndCompress(selected.filter {
                        it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                    })
                })
            }

            // Create GIF (Requires 2+ images)
            val canCreateGif = selected.size >= 2 && allImages
            if (canCreateGif && pm.isIconEnabled(this, pm.KEY_CREATE_GIF)) {
                list.add(FileToolsBottomSheet.ActionItem("create_gif", getString(R.string.action_create_gif), R.drawable.ic_gif, "toolbar_create_gif") {
                    downloadNetworkImagesAndCreateGif(selected.filter {
                        it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                    })
                })
            }

            // Wallpaper (Single network image file, mobile only)
            val isSingleNetworkImage = count == 1 && !selected.first().isDirectory &&
                selected.first().name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            if (isSingleNetworkImage && !za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)) {
                val targetFile = selected.first()

                // Set Home Wallpaper
                if (pm.isIconEnabled(this, pm.KEY_SET_HOME_WALLPAPER)) {
                    list.add(FileToolsBottomSheet.ActionItem("set_home_wallpaper", getString(R.string.action_set_home_wallpaper), R.drawable.ic_wallpaper_home, "toolbar_set_home_wallpaper") {
                        setNetworkWallpaper(targetFile, android.app.WallpaperManager.FLAG_SYSTEM)
                    })
                }

                // Set Lock Wallpaper
                if (pm.isIconEnabled(this, pm.KEY_SET_LOCK_WALLPAPER)) {
                    list.add(FileToolsBottomSheet.ActionItem("set_lock_wallpaper", getString(R.string.action_set_lock_wallpaper), R.drawable.ic_wallpaper_lock, "toolbar_set_lock_wallpaper") {
                        setNetworkWallpaper(targetFile, android.app.WallpaperManager.FLAG_LOCK)
                    })
                }
            }

            // 8. Copy Encrypted
            if (pm.isIconEnabled(this, pm.KEY_COPY_ENCRYPT)) {
                val encryptable = selected.filter { !it.isDirectory }
                if (encryptable.isNotEmpty()) {
                    list.add(FileToolsBottomSheet.ActionItem("copy_encrypt", getString(R.string.action_copy_encrypt), R.drawable.ic_copy, "toolbar_copy_encrypt") {
                        showNetworkVaultPicker(encryptable, isMove = false)
                    })
                }
            }

            // 9. Move Encrypted
            if (pm.isIconEnabled(this, pm.KEY_MOVE_ENCRYPT)) {
                val encryptable = selected.filter { !it.isDirectory }
                if (encryptable.isNotEmpty()) {
                    list.add(FileToolsBottomSheet.ActionItem("move_encrypt", getString(R.string.action_move_encrypt), R.drawable.ic_move, "toolbar_move_encrypt") {
                        showNetworkVaultPicker(encryptable, isMove = true)
                    })
                }
            }

            // 10. Protect
            val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(this, share.id)
            if (hasUnprotected && pm.isIconEnabled(this, pm.KEY_PROTECT)) {
                list.add(FileToolsBottomSheet.ActionItem("protect", getString(R.string.protect), R.drawable.ic_shield_protected, "toolbar_protect") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(this@NetworkBrowserActivity, file.path, share.id, protected = true)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.deselectAll()
                            loadDirectory()
                            showPremiumSnackbar(getString(R.string.toast_protected_success, selected.size))
                        }
                    }
                })
            }

            // 11. Unprotect
            val hasProtected = fileAdapter.hasAnySelectedProtected(this, share.id)
            if (hasProtected && pm.isIconEnabled(this, pm.KEY_UNPROTECT)) {
                list.add(FileToolsBottomSheet.ActionItem("unprotect", getString(R.string.unprotect), R.drawable.ic_shield_unprotected, "toolbar_unprotect") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(this@NetworkBrowserActivity, file.path, share.id, protected = false)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.deselectAll()
                            loadDirectory()
                            showPremiumSnackbar(getString(R.string.toast_unprotected_success, selected.size))
                        }
                    }
                })
            }

            // Pin
            val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(this, share.id)
            if (hasUnpinned && pm.isIconEnabled(this, pm.KEY_PIN)) {
                list.add(FileToolsBottomSheet.ActionItem("pin", getString(R.string.pin), R.drawable.ic_paperclip, "toolbar_pin") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(this@NetworkBrowserActivity, file.path, share.id, pinned = true)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.deselectAll()
                            loadDirectory()
                            showPremiumSnackbar(getString(R.string.toast_pinned_success, selected.size))
                        }
                    }
                })
            }

            // Unpin
            val hasPinned = fileAdapter.hasAnySelectedPinned(this, share.id)
            if (hasPinned && pm.isIconEnabled(this, pm.KEY_UNPIN)) {
                list.add(FileToolsBottomSheet.ActionItem("unpin", getString(R.string.unpin), R.drawable.ic_paperclip_off, "toolbar_unpin") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(this@NetworkBrowserActivity, file.path, share.id, pinned = false)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.deselectAll()
                            loadDirectory()
                            showPremiumSnackbar(getString(R.string.toast_unpinned_success, selected.size))
                        }
                    }
                })
            }

            // 12. Properties
            if (count == 1 && !selected.first().isDirectory) {
                val file = selected.first()
                list.add(FileToolsBottomSheet.ActionItem("properties", getString(R.string.action_properties), R.drawable.ic_about, "toolbar_properties") {
                    val sheet = FilePropertiesBottomSheet.newInstance(
                        filePath = file.path,
                        isDirectory = false,
                        size = file.size,
                        lastModified = file.lastModified,
                        isNetwork = true
                    )
                    sheet.show(supportFragmentManager, FilePropertiesBottomSheet.TAG)
                })
            }

            // 13. Tag
            val isMultiFileOnly = selected.size > 1 && selected.all { !it.isDirectory }
            val prefs = getSharedPreferences("ufm_prefs", MODE_PRIVATE)
            val isMultiTaggingEnabled = prefs.getBoolean("pref_multi_file_tagging", false)
            if (isMultiTaggingEnabled && isMultiFileOnly) {
                list.add(FileToolsBottomSheet.ActionItem("tag", getString(R.string.action_tag), R.drawable.ic_edit, "toolbar_tag") {
                    val filePaths = selected.map { it.path }
                    FileTagsManager.showMultiFileTagDialog(this@NetworkBrowserActivity, filePaths) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory()
                    }
                })
            }

            // Retrigger Thumbnails
            val hasVideoOrFolder = selected.isNotEmpty() && selected.any {
                it.isDirectory || it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS
            }
            if (hasVideoOrFolder && pm.isIconEnabled(this, pm.KEY_RETRIGGER_THUMBNAILS)) {
                list.add(FileToolsBottomSheet.ActionItem("retrigger_thumbnails", getString(R.string.action_retrigger_thumbnails), R.drawable.ic_photo_video, "toolbar_retrigger_thumbnails") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        cacheManager.clearCacheForSelection(share.id, selected)
                        for (file in selected) {
                            if (file.isDirectory) {
                                NetworkFileAdapter.clearCacheForFolder(file.path)
                            } else {
                                NetworkFileAdapter.clearCacheForPath(file.path)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory()
                            showPremiumSnackbar(getString(R.string.retrigger_thumbnails_success))
                        }
                    }
                })
            }

            if (list.isNotEmpty()) {
                val title = getString(R.string.action_tools)
                val subtitle = getString(R.string.selection_count, selected.size)
                val sheet = FileToolsBottomSheet.newInstance(list, title, subtitle)
                sheet.show(supportFragmentManager, FileToolsBottomSheet.TAG)
            }
        }

        // Wire TV inline clipboard panel buttons
        if (isTv) {
            tvClipboardPanel = findViewById(R.id.layoutTvClipboardPanel)
            tvClipboardTitle = findViewById(R.id.txtTvClipboardTitle)

            val yellowBg   = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
            val whiteBg    = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val glassBg    = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_glass_white_10))
            val yellowText = getColor(R.color.tv_button_focused_yellow_text)
            val blackText  = getColor(android.R.color.black)
            val redText    = getColor(R.color.ufm_denied)

            val btnPasteHere = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTvPasteHere)
            val btnClearAll  = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTvClearClipboard)

            btnPasteHere?.setOnClickListener {
                showClipboardSheet()
            }
            btnPasteHere?.setOnFocusChangeListener { _, hasFocus ->
                // Default: yellow bg + black text. Focused: white bg + black text (inverted highlight)
                btnPasteHere.backgroundTintList = if (hasFocus) whiteBg else yellowBg
                btnPasteHere.setTextColor(blackText)
                btnPasteHere.iconTint = android.content.res.ColorStateList.valueOf(blackText)
            }

            btnClearAll?.setOnClickListener {
                NetworkClipboard.clear()
                za.kilowatch.ultimatefilemanager.storage.FileClipboard.clear()
                updatePasteFab()
            }
            btnClearAll?.setOnFocusChangeListener { _, hasFocus ->
                // Default: glass bg + red text. Focused: yellow bg + black text
                btnClearAll.backgroundTintList = if (hasFocus) yellowBg else glassBg
                btnClearAll.setTextColor(if (hasFocus) blackText else redText)
            }
        }

        if (isTv) {
            // Setup TV focus states for header & action bar
            val iconTintFocused = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            val iconTintDefault = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            
            val tvButtons = mutableListOf(btnBack, btnCreateNew, btnRefresh,
                   btnCloseSelection, btnCopy, btnMove, btnRename, btnFavorite, btnShare,
                   btnCopyEncrypt, btnMoveEncrypt)
            btnViewToggle?.let { tvButtons.add(it) }
            btnRetriggerThumbnails?.let { tvButtons.add(it) }
            
            tvButtons.forEach { btn ->
                btn.imageTintList = iconTintDefault
                btn.setOnFocusChangeListener { _, hasFocus ->
                    btn.imageTintList = if (hasFocus) iconTintFocused else iconTintDefault
                }
            }
            
            btnSort?.let { sortBtn ->
                sortBtn.imageTintList = iconTintDefault
                sortBtn.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        sortBtn.imageTintList = iconTintFocused
                    } else {
                        val hasOverride = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.hasFolderOverride(this, currentPath, share.id)
                        sortBtn.imageTintList = android.content.res.ColorStateList.valueOf(
                            getColor(
                                if (hasOverride) R.color.tv_button_focused_yellow else R.color.tv_text_primary
                            )
                        )
                    }
                }
            }
            
            // View mode toggle for TV
            btnViewToggle?.setOnClickListener {
                ViewModeManager.showSelectionDialog(this, fileAdapter.viewMode) { selectedMode ->
                    val folderKey = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.folderKey(share.id, currentPath)
                    if (za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.hasFolderOverride(this, currentPath, share.id)) {
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val state = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.loadForPath(this@NetworkBrowserActivity, currentPath, share.id)
                            if (state != null) {
                                za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.saveFolderSpecific(
                                    this@NetworkBrowserActivity, folderKey, "${if (share.name.isNotEmpty()) share.name else share.host}:$currentPath",
                                    state.copy(viewMode = selectedMode), isNetwork = true
                                )
                            }
                        }
                    } else {
                        ViewModeManager.save(this, selectedMode)
                    }
                    applyViewMode(selectedMode)
                }
            }

            // Search toggle for TV
            btnSearchToggle.imageTintList = iconTintDefault
            btnSearchToggle.setOnClickListener { toggleSearch() }
            btnSearchToggle.setOnFocusChangeListener { _, hasFocus ->
                btnSearchToggle.imageTintList = if (hasFocus) iconTintFocused else iconTintDefault
            }

            // Twin Window: launch with current network share as top pane (TV)
            val btnTwinWindowTv = findViewById<android.widget.ImageView>(R.id.btnTwinWindow)
            btnTwinWindowTv?.imageTintList = iconTintDefault
            btnTwinWindowTv?.setOnClickListener {
                // Same server-mode path reconstruction as the mobile button above.
                val twinInitialPath = if (share.isServerMode) {
                    val shareName = share.remotePath.trimStart('/')
                    val subPath   = stripSharePrefix(currentPath.trimStart('/'))
                    if (shareName.isEmpty()) subPath
                    else if (subPath.isEmpty()) shareName
                    else "$shareName/$subPath"
                } else {
                    currentPath
                }
                android.util.Log.d("TPath", "Open twin window (TV): share.id=${share.id} share.name=${share.name} serverMode=${share.isServerMode} remotePath='${share.remotePath}' currentPath='$currentPath' twinInitialPath='$twinInitialPath'")
                val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity::class.java).apply {
                    putExtra(za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity.EXTRA_TOP_SHARE_ID, share.id)
                    putExtra(za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity.EXTRA_TOP_SHARE_PATH, twinInitialPath)
                }
                startActivity(intent)
            }
            btnTwinWindowTv?.setOnFocusChangeListener { _, hasFocus ->
                btnTwinWindowTv.imageTintList = if (hasFocus) iconTintFocused else iconTintDefault
            }
        }
    }

    private fun toggleSearch() {
        isSearchVisible = !isSearchVisible
        layoutSearchRow.visibility = if (isSearchVisible) View.VISIBLE else View.GONE
        btnSearchToggle.setImageResource(R.drawable.ic_search)
        btnSearchToggle.imageTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (isSearchVisible) R.color.ufm_granted else R.color.ufm_denied)
        )
        
        if (isSearchVisible) {
            edtSearch.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(edtSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            edtSearch.setText("")
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(edtSearch.windowToken, 0)
            performSearch("") // Reset filter
        }
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) {
            applyData()
            return
        }

        val filtered = currentFiles.filter { it.name.contains(query, ignoreCase = true) }
        fileAdapter.submitList(filtered)
        updateEmptyState(filtered.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerFiles.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    /**
     * In sync/compress folder picker mode: re-uses the fabPaste button as a "Use This Folder" action.
     */
    private fun showUseFolderFab() {
        if (isCompressDestPickerMode) {
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_compress)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmCompressNetworkFolderDialog() }
        } else if (isSyncFolderPickerMode) {
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_sync)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmSyncPathDialog() }
        } else if (isAdvancedSyncFolderPickerMode) {
            android.util.Log.d("AdvSyncDest", "showUseFolderFab: Setting FAB for advanced sync")
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_sync_advanced)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmAdvancedSyncPathDialog() }
        } else if (isLocationPickerMode) {
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_folder)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmLocationPickerNetworkFolderDialog() }
        } else         if (isQuickTransferPickerMode) {
            fabPaste.setText(if (quickTransferIsMove) R.string.quick_transfer_move_here else R.string.quick_transfer_copy_here)
            fabPaste.setIconResource(if (quickTransferIsMove) R.drawable.ic_move else R.drawable.ic_copy)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmQuickTransferNetworkDialog() }
        } else if (isShareDestPickerMode) {
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_folder)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmShareDestNetworkDialog() }
        } else if (isScannerFolderPicker) {
            fabPaste.setText(R.string.scanner_use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_scanner)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmScannerNetworkFolderDialog() }
        } else if (isAutoBackupFolderPicker) {
            fabPaste.setText(R.string.auto_backup_select_folder)
            fabPaste.setIconResource(R.drawable.ic_cloud)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmAutoBackupNetworkFolderDialog() }
        } else if (isImageCompressDestPickerMode) {
            fabPaste.setText(R.string.use_this_folder_image)
            fabPaste.setIconResource(R.drawable.ic_compress_image)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmImageCompressNetworkFolderDialog() }
        } else if (isGifCreatorDestPickerMode) {
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_gif)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmGifCreatorNetworkFolderDialog() }
        } else if (isSmartSortCategoryPickerMode) {
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_folder)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { confirmSmartSortFolder() }
        } else if (isSmartSortPickerMode) {
            fabPaste.setText(R.string.smart_sort_here)
            fabPaste.setIconResource(R.drawable.ic_sort)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { confirmSmartSortFolder() }
        }
    }

    private fun showConfirmQuickTransferNetworkDialog() {
        val displayPath = "${share.name}/${if (currentPath.isEmpty()) "" else currentPath}"
        val fileCount = za.kilowatch.ultimatefilemanager.storage.FileClipboard.files.size
        val msgRes = if (quickTransferIsMove) R.string.quick_transfer_move_confirm else R.string.quick_transfer_copy_confirm
        val posRes = if (quickTransferIsMove) R.string.quick_transfer_move_here else R.string.quick_transfer_copy_here
        val iconRes = if (quickTransferIsMove) R.drawable.ic_move else R.drawable.ic_copy
        MaterialAlertDialogBuilder(this)
            .setTitle(if (quickTransferIsMove) R.string.action_move_to else R.string.action_copy_to)
            .setMessage(getString(msgRes, fileCount) + "\n\n" + displayPath)
            .setIcon(iconRes)
            .setPositiveButton(posRes) { _, _ ->
                // Execute the transfer and signal completion back to FileBrowserActivity
                performPaste()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmShareDestNetworkDialog() {
        val displayPath = "${share.name}/${if (currentPath.isEmpty()) "" else currentPath}"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.use_this_folder)
            .setMessage(getString(R.string.share_receive_confirm, displayPath))
            .setIcon(R.drawable.ic_folder)
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_SHARE_ID, share.id)
                    putExtra(RESULT_SELECTED_NET_PATH, currentPath)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmCompressNetworkFolderDialog() {
        val displayPath = "${share.name}/${if (currentPath.isEmpty()) "" else currentPath}"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.compress_here)
            .setMessage(getString(R.string.save_archive_to_path, displayPath))
            .setIcon(R.drawable.ic_compress)
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_COMPRESS_SHARE_ID, share.id)
                    putExtra(RESULT_SELECTED_COMPRESS_NET_PATH, currentPath)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmLocationPickerNetworkFolderDialog() {
        val displayPath = "${share.name}/${if (currentPath.isEmpty()) "" else currentPath}"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_folder)
            .setMessage(getString(R.string.use_network_folder_as_default_location, displayPath))
            .setIcon(R.drawable.ic_folder)
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                val type = when (share.type) {
                    ShareType.SMB              -> "SMB"
                    ShareType.FTP              -> "FTP"
                    ShareType.SFTP, ShareType.SCP -> "SFTP"
                    ShareType.TV               -> "TV"
                    ShareType.GOOGLE_DRIVE     -> "GOOGLE_DRIVE"
                    ShareType.DROPBOX          -> "DROPBOX"
                    ShareType.ONEDRIVE         -> "ONEDRIVE"
                    ShareType.AWS_S3           -> "AWS S3"
                    ShareType.IDRIVE_E2        -> "S3 Storage"
                    ShareType.WEBDAV           -> "WebDAV"
                    ShareType.DLNA             -> "DLNA"
                    ShareType.NFS              -> "NFS"
                }
                val scheme = when (share.type) {
                    ShareType.SMB              -> "smb"
                    ShareType.FTP              -> "ftp"
                    ShareType.SFTP, ShareType.SCP -> "sftp"
                    ShareType.TV               -> "tv"
                    ShareType.GOOGLE_DRIVE     -> "gdrive"
                    ShareType.DROPBOX          -> "dropbox"
                    ShareType.ONEDRIVE         -> "onedrive"
                    ShareType.AWS_S3           -> "s3"
                    ShareType.IDRIVE_E2        -> "idrive-e2"
                    ShareType.WEBDAV           -> "webdav"
                    ShareType.DLNA             -> "dlna"
                    ShareType.NFS              -> "nfs"
                }

                val result = Intent().apply {
                    putExtra(RESULT_URI, "$scheme://${share.id}/$currentPath")
                    putExtra(RESULT_LABEL, displayPath)
                    putExtra(RESULT_TYPE, type)
                    putExtra(RESULT_META_ID, share.id)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmSmartSortFolder() {
        val result = Intent().apply {
            putExtra(RESULT_SELECTED_SMART_SORT_PATH, currentPath)
            putExtra(RESULT_SELECTED_SMART_SORT_SHARE_ID, share.id)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun showConfirmScannerNetworkFolderDialog() {
        val displayPath = "${share.name}/${if (currentPath.isEmpty()) "" else currentPath}"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scanner_use_this_folder)
            .setMessage(getString(R.string.scanner_folder_picker_title) + "\n\n${share.name}$displayPath")
            .setIcon(R.drawable.ic_scanner)
            .setPositiveButton(R.string.scanner_use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_SHARE_ID, share.id)
                    putExtra(RESULT_SELECTED_NET_PATH, currentPath)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmAutoBackupNetworkFolderDialog() {
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.auto_backup_location_confirm_title)
            .setMessage(R.string.auto_backup_location_confirm_message)
            .setPositiveButton(R.string.auto_backup_select_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_SHARE_ID, share.id)
                    putExtra(RESULT_SELECTED_NET_PATH, currentPath)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmImageCompressNetworkFolderDialog() {
        val displayPath = "${share.name}/${if (currentPath.isEmpty()) "" else currentPath}"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.compress_here)
            .setMessage(getString(R.string.save_archive_to_path, displayPath))
            .setIcon(R.drawable.ic_compress_image)
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_COMPRESS_SHARE_ID, share.id)
                    putExtra(RESULT_SELECTED_COMPRESS_NET_PATH, currentPath)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmGifCreatorNetworkFolderDialog() {
        val displayPath = "${share.name}/${if (currentPath.isEmpty()) "" else currentPath}"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.gif_creator_title)
            .setMessage(getString(R.string.save_archive_to_path, displayPath))
            .setIcon(R.drawable.ic_gif)
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_SHARE_ID, share.id.toString())
                    putExtra(NetworkBrowserActivity.RESULT_SELECTED_SHARE_ID, share.id)
                    putExtra(RESULT_SELECTED_NET_PATH, currentPath)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmSyncPathDialog() {
        val displayPath = if (currentPath.isEmpty()) "/" else "/$currentPath"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_sync_folder)
            .setMessage(
                getString(R.string.use_following_path_as_sync_destination, displayPath) +
                getString(R.string.folder_sync_will_back_up_to_this_location_on_the_network_share)
            )
            .setIcon(R.drawable.ic_sync)
            .setPositiveButton(R.string.btn_continue) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_SYNC_PATH, currentPath)
                    putExtra(RESULT_SELECTED_SHARE_ID, share.id)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmAdvancedSyncPathDialog() {
        val displayPath = if (currentPath.isEmpty()) "/" else "/$currentPath"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_sync_folder)
            .setMessage(
                getString(R.string.use_following_path_as_sync_destination, displayPath) +
                getString(R.string.folder_sync_will_back_up_to_this_location_on_the_network_share)
            )
            .setIcon(R.drawable.ic_sync_advanced)
            .setPositiveButton(R.string.btn_continue) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_SYNC_PATH, currentPath)
                    putExtra(RESULT_SELECTED_SHARE_ID, share.id)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFavoriteDialog(file: NetworkFile) {
        val isOnTv = DeviceUtils.isTvDevice(this)
        
        val bgColor = if (isOnTv) getColor(R.color.tv_bg_gradient_end) else android.graphics.Color.TRANSPARENT
        val textColorPrimary = if (isOnTv) getColor(R.color.tv_text_primary) else getColor(R.color.ufm_text_primary)
        val textColorHint = if (isOnTv) getColor(R.color.tv_text_hint) else getColor(R.color.ufm_text_hint)
        val accentColor = if (isOnTv) getColor(R.color.tv_button_focused_yellow) else getColor(R.color.ufm_primary)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
            setBackgroundColor(bgColor)
        }

        val editText = android.widget.EditText(this).apply {
            hint = getString(R.string.favorite_name_hint)
            setText(file.name)
            selectAll()
            setSingleLine(true)
            setTextColor(textColorPrimary)
            setHintTextColor(textColorHint)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            requestFocus()
        }
        container.addView(editText)

        val dialogTheme = com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog

        MaterialAlertDialogBuilder(this, dialogTheme)
            .setTitle(getString(R.string.favorite_name_title))
            .setIcon(R.drawable.ic_star)
            .setView(container)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .setPositiveButton(getString(R.string.action_done)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    showPremiumSnackbar(getString(R.string.favorite_name_empty))
                } else {
                    val favorite = za.kilowatch.ultimatefilemanager.settings.FavoritesManager.FavoriteItem(
                        id = "fav_${System.currentTimeMillis()}",
                        path = file.path,
                        label = name,
                        isFolder = file.isDirectory,
                        isNetwork = true,
                        shareId = share.id
                    )
                    za.kilowatch.ultimatefilemanager.settings.FavoritesManager.addFavorite(this, favorite)
                    fileAdapter.exitSelectionMode()
                    showPremiumSnackbar(getString(R.string.favorite_added))
                }
            }
            .show()
            .also { dialog ->
                val titleColor = if (isOnTv) getColor(R.color.tv_text_primary) else getColor(R.color.ufm_text_primary)
                val titleView = dialog.findViewById<android.widget.TextView>(
                    com.google.android.material.R.id.alertTitle
                ) ?: dialog.findViewById(
                    resources.getIdentifier("alertTitle", "id", "android")
                )
                titleView?.setTextColor(titleColor)

                if (isOnTv) {
                    dialog.window?.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(getColor(R.color.tv_bg_gradient_end))
                    )

                    val white = getColor(R.color.tv_text_primary)
                    val black = getColor(R.color.tv_button_focused_yellow_text)
                    val yellow = getColor(R.color.tv_button_focused_yellow)
                    val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
                    val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                        backgroundTintList = yellowCsl
                        setTextColor(black)
                    }
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                        backgroundTintList = glassCsl
                        setTextColor(white)
                        setOnFocusChangeListener { _, hasFocus ->
                            backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                            setTextColor(if (hasFocus) black else white)
                        }
                    }
                }
                dialog.window?.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                )
            }
    }

    private fun navigateBack() {
        if (fileAdapter.isSelectionMode) {
            fileAdapter.exitSelectionMode()
            return
        }
        if (currentPath.isNotEmpty()) {
            if (share.type == ShareType.TV) {
                // TV shares use absolute paths (e.g., /storage/emulated/0/Download)
                if (currentPath == initialRootPath) {
                    currentPath = "" // We hit the volume root, bail back to Drive Selection
                } else {
                    val file = java.io.File(currentPath)
                    val parent = file.parent
                    // Fallback boundary check
                    currentPath = if (parent == null || parent == "/") "" else parent
                }
            } else {
                // SMB/FTP use relative prefix appending
                val lastSlash = currentPath.lastIndexOf('/')
                currentPath = if (lastSlash > 0) currentPath.substring(0, lastSlash) else ""
            }
            // Reset share path when returning to server root in server-mode SMB
            if (share.isServerMode && currentPath.isEmpty()) {
                share = share.copy(remotePath = originalRemotePath)
                fileAdapter.share = share
            }
            loadDirectory()
        } else {
            if (isTaskRoot) {
                val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
                startActivity(intent)
            }
            finish()
        }
    }
    
    private fun updateSubtitle() {
        txtSubtitle.text = when {
            share.type == ShareType.SMB && share.isServerMode && currentPath.isEmpty() ->
                getString(R.string.network_folder_shared_folders)
            currentPath.isEmpty() -> "/"
            else -> "/$currentPath"
        }
    }

    private fun updateSelectionBar(count: Int) {
        val showSelection = fileAdapter.isSelectionMode
        if (showSelection) {
            val showActions = count > 0
            layoutSelectionBar.visibility = View.VISIBLE
            txtSelectionCount.text = if (count == 0) getString(R.string.selection_prompt_select_item) else getString(R.string.selection_count, count)
            btnSelectAll.text = if (fileAdapter.isAllSelected()) getString(R.string.action_deselect_all) else getString(R.string.action_select_all)
            
            val isTv = DeviceUtils.isTvDevice(this)
            val row2 = btnCopy.parent.parent as? View
            val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
            val hasProtected = fileAdapter.hasAnySelectedProtected(this, share.id)
            val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(this, share.id)
            val hasPinned = fileAdapter.hasAnySelectedPinned(this, share.id)
            val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(this, share.id)

            if (!isTv) {
                row2?.visibility = View.GONE
                btnSelectAll.visibility = if (pm.isIconEnabled(this, pm.KEY_SELECT_ALL)) View.VISIBLE else View.GONE
                btnDelete.visibility = View.GONE
                btnCompress.visibility = View.GONE
                btnExtract?.visibility = View.GONE
                fabTools?.visibility = if (showActions) View.VISIBLE else View.GONE
            } else {
                fabTools?.visibility = View.GONE
                if (showActions) {
                    za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.stopAnimation(layoutSelectionBar)
                    row2?.visibility = View.VISIBLE
                } else {
                    row2?.visibility = View.GONE
                    za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.startAnimation(layoutSelectionBar)
                }

                // TV-only icon/row visibility
                btnRename.visibility = if (count >= 1 && !share.readOnly && pm.isIconEnabled(this, pm.KEY_RENAME)) View.VISIBLE else View.GONE
                btnFavorite.visibility = if (count == 1 && pm.isIconEnabled(this, pm.KEY_FAVORITE)) View.VISIBLE else View.GONE
                btnProtect.visibility = if (showActions && hasUnprotected && pm.isIconEnabled(this, pm.KEY_PROTECT)) View.VISIBLE else View.GONE
                btnUnprotect.visibility = if (showActions && hasProtected && pm.isIconEnabled(this, pm.KEY_UNPROTECT)) View.VISIBLE else View.GONE
                btnPin?.visibility = if (showActions && hasUnpinned && pm.isIconEnabled(this, pm.KEY_PIN)) View.VISIBLE else View.GONE
                btnUnpin?.visibility = if (showActions && hasPinned && pm.isIconEnabled(this, pm.KEY_UNPIN)) View.VISIBLE else View.GONE
                btnDelete.visibility = if (showActions && !share.readOnly && pm.isIconEnabled(this, pm.KEY_DELETE)) View.VISIBLE else View.GONE
                btnCopy.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_COPY)) View.VISIBLE else View.GONE
                btnMove.visibility = if (showActions && !share.readOnly && pm.isIconEnabled(this, pm.KEY_MOVE)) View.VISIBLE else View.GONE
                btnShare.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_SHARE)) View.VISIBLE else View.GONE
                btnCopyEncrypt.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_COPY_ENCRYPT)) View.VISIBLE else View.GONE
                btnMoveEncrypt.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_MOVE_ENCRYPT)) View.VISIBLE else View.GONE
                btnCompress.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_COMPRESS)) View.VISIBLE else View.GONE
                val netFiles = fileAdapter.getSelectedFiles()
                val hasArchiveSelected = netFiles.isNotEmpty() && netFiles.any {
                    za.kilowatch.ultimatefilemanager.archive.ArchiveManager.isSupportedArchiveExtension(it.name.substringAfterLast('.'))
                }
                btnExtract?.visibility = if (isTv && showActions && hasArchiveSelected && pm.isIconEnabled(this, pm.KEY_EXTRACT)) View.VISIBLE else View.GONE
                val hasVideoOrFolder = netFiles.isNotEmpty() && netFiles.any {
                    it.isDirectory || it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS
                }
                btnRetriggerThumbnails?.visibility = if (showActions && hasVideoOrFolder && pm.isIconEnabled(this, pm.KEY_RETRIGGER_THUMBNAILS)) View.VISIBLE else View.GONE
                val allImages = netFiles.isNotEmpty() && netFiles.all {
                    it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                }
                btnImageCompress.visibility = if (showActions && allImages && pm.isIconEnabled(this, pm.KEY_IMAGE_COMPRESS)) View.VISIBLE else View.GONE
            }

            val selectedFiles = fileAdapter.getSelectedFiles()
            val isSingleFile = selectedFiles.size == 1 && !selectedFiles.first().isDirectory
            
            val prefs = getSharedPreferences("ufm_prefs", MODE_PRIVATE)
            val isMultiTaggingEnabled = prefs.getBoolean("pref_multi_file_tagging", false)
            val isMultiFileOnly = selectedFiles.size > 1 && selectedFiles.all { !it.isDirectory }
            
            fabProperties?.visibility = View.GONE
            updatePasteFab()
        } else {
            layoutSelectionBar.visibility = View.GONE
            za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.stopAnimation(layoutSelectionBar)
            fabProperties?.visibility = View.GONE
            fabTools?.visibility = View.GONE
            updatePasteFab()
        }
    }

    private fun updatePasteFab() {
        // In sync/compress folder picker mode + location picker mode the FAB is "Use This Folder" — never hide it here
        if (isSyncFolderPickerMode || isAdvancedSyncFolderPickerMode || isCompressDestPickerMode || isLocationPickerMode || isShareDestPickerMode || isScannerFolderPicker || isAutoBackupFolderPicker || isImageCompressDestPickerMode || isSmartSortPickerMode || isSmartSortCategoryPickerMode) {
            showUseFolderFab()
            return
        }

        if (share.readOnly) {
            fabPaste.visibility = View.GONE
            if (isTv) updateTvClipboardPanel()
            return
        }
        
        // Show FAB for ANY items in the clipboard — cross-share pastes are supported
        val netCount = if (NetworkClipboard.hasItems()) NetworkClipboard.files.size else 0
        val localCount = if (za.kilowatch.ultimatefilemanager.storage.FileClipboard.hasItems()) za.kilowatch.ultimatefilemanager.storage.FileClipboard.files.size else 0
        
        val total = netCount + localCount
        android.util.Log.e("PasteFeature", "NetworkBrowser updatePasteFab - netCount:$netCount, localCount:$localCount, readOnly:${share.readOnly}, isTv:$isTv, total:$total")
        
        if (total > 0) {
            fabPaste.text = "${getString(R.string.action_paste)} ($total)"
            fabPaste.visibility = if (isTv) View.GONE else View.VISIBLE
        } else {
            fabPaste.visibility = View.GONE
        }
        if (isTv) updateTvClipboardPanel()
    }

    /**
     * Updates the TV inline clipboard panel. Shown automatically whenever the clipboard
     * has items — no tap needed. Called instead of BottomSheetDialog on TV to avoid clipping.
     */
    private fun updateTvClipboardPanel() {
        val panel = tvClipboardPanel ?: return
        val netCount = if (NetworkClipboard.hasItems()) NetworkClipboard.files.size else 0
        val localCount = if (za.kilowatch.ultimatefilemanager.storage.FileClipboard.hasItems()) za.kilowatch.ultimatefilemanager.storage.FileClipboard.files.size else 0
        val total = netCount + localCount

        if (total == 0) {
            panel.visibility = View.GONE
            return
        }

        // Update title and always show when items are present
        tvClipboardTitle?.text = if (total == 1) getString(R.string.clipboard_1_file) else getString(R.string.clipboard_total_files, total)
        panel.visibility = View.VISIBLE
    }

    private fun loadDirectory() {
        if (isTransferring) return   // Don't refresh while a copy/move is in progress
        Log.d("NetBrowser", "loadDirectory: share=${share.name} type=${share.type} currentPath='$currentPath'")

        // Load folder-specific sort settings (or fall back to global) on IO thread
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val state = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.loadForPath(this@NetworkBrowserActivity, currentPath, share.id)
                ?: za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.loadGlobal(this@NetworkBrowserActivity)
            val hasFolderOverride = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.hasFolderOverride(this@NetworkBrowserActivity, currentPath, share.id)
            val viewModeToApply = state.viewMode ?: ViewModeManager.load(this@NetworkBrowserActivity)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                sortMode  = state.sortMode
                sortOrder = state.sortOrder
                filterType = state.filterType
                activeTagsFilter = state.activeTags
                updateSortBadge(hasFolderOverride)
                if (fileAdapter.viewMode != viewModeToApply) {
                    applyViewMode(viewModeToApply)
                }
            }
        }

        progressBar.visibility = View.VISIBLE
        recyclerFiles.visibility = View.GONE
        layoutEmpty.visibility = View.GONE
        // NOTE: Do NOT call fileAdapter.exitSelectionMode() here. loadDirectory() runs
        // an async network call (1-3s on SMB), so the user may enter selection mode
        // while the listing is in-flight. Clearing selection synchronously here races
        // against that user action and destroys their selection state.

        updateSubtitle()
        updateBreadcrumbs()

        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Server-mode SMB: intercept at root to discover shares
                if (share.type == ShareType.SMB && share.isServerMode) {
                    kotlinx.coroutines.withTimeout(15_000L) {
                        if (currentPath.isEmpty()) {
                            val discovered = discoverServerShares(share)
                            withContext(Dispatchers.Main) {
                                if (discovered.isEmpty()) {
                                    progressBar.visibility = View.GONE
                                    layoutEmpty.visibility = View.VISIBLE
                                    val tvEmptyState = findViewById<TextView>(R.id.txtEmptyState)
                                    tvEmptyState.text = getString(R.string.smb_server_no_shares)
                                    tvEmptyState.visibility = View.VISIBLE
                                    recyclerFiles.visibility = View.GONE
                                } else {
                                    currentFiles = discovered
                                    applyData()
                                }
                            }
                        } else {
                            // Inside a discovered share
                            val existingShare = share.remotePath.trimStart('/')
                            if (existingShare.isNotEmpty()) {
                                // Already navigated into a share — strip the share name prefix from
                                // currentPath before passing to SmbShareClient. currentPath holds the
                                // full UI path (e.g. "C/D") while share.remotePath already encodes the
                                // share name (e.g. "/C"). Passing "C/D" raw would make splitSharePath
                                // produce \\server\C\C\D (duplicate). Strip "C/" to get just "D".
                                val innerPath = stripSharePrefix(currentPath.trimStart('/'))
                                var files = SmbShareClient.listFiles(share, innerPath)
                                files = files.filter { it.name != ".." }
                                withContext(Dispatchers.Main) {
                                    currentFiles = files
                                    applyData()
                                }
                            } else {
                                // First navigation into a share — extract share name from currentPath
                                val parts = currentPath.trimStart('/').split("/", limit = 2)
                                val shareName = parts[0]
                                val innerPath = parts.getOrElse(1) { "" }
                                // Update share to the effective copy so all file operations
                                // (copy, delete, rename, etc.) use the correct remotePath
                                share = share.copy(remotePath = "/$shareName")
                                fileAdapter.share = share
                                var files = SmbShareClient.listFiles(share, innerPath)
                                files = files.filter { it.name != ".." }
                                withContext(Dispatchers.Main) {
                                    currentFiles = files
                                    applyData()
                                }
                            }
                        }
                    }
                    return@launch
                }

                var files = kotlinx.coroutines.withTimeout(15_000L) {
                    when (share.type) {
                        ShareType.SMB -> SmbShareClient.listFiles(share, currentPath)
                        ShareType.FTP -> FtpShareClient.listFiles(share, currentPath)
                        ShareType.TV  -> TvShareClient.listFiles(share, currentPath)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, currentPath)
                        ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(share, currentPath)
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, currentPath)
                        ShareType.DROPBOX -> DropboxShareClient.listFiles(share, currentPath)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, currentPath)
                        ShareType.WEBDAV                      -> WebDavShareClient.listFiles(share, currentPath)
                        ShareType.NFS                         -> NfsShareClient.listFiles(share, currentPath)
                        ShareType.DLNA                        -> DlnaShareClient.listFiles(share, currentPath)
                    }
                }
                
                // Remove '..' for safety on UI
                files = files.filter { it.name != ".." }

                // Apply picker filter
                if (isPickerMode && pickerExtensions.isNotEmpty()) {
                    files = files.filter { it.isDirectory || it.name.substringAfterLast('.', "").lowercase() in pickerExtensions }
                }
                
                currentFiles = files

                withContext(Dispatchers.Main) {
                    applyData()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    layoutEmpty.visibility = View.VISIBLE
                    
                    val errMessage = if (e is kotlinx.coroutines.TimeoutCancellationException) "Connection timed out" else (e.message ?: "")
                    val isConnectionError = errMessage.contains("timeout", ignoreCase = true) ||
                                            errMessage.contains("failed to connect", ignoreCase = true) ||
                                            errMessage.contains("unreachable", ignoreCase = true) ||
                                            errMessage.contains("reset", ignoreCase = true) ||
                                            errMessage.contains("refused", ignoreCase = true)

                    val isGDriveScopeError = errMessage.startsWith("GDrive") &&
                            errMessage.contains("403") &&
                            errMessage.contains("insufficient", ignoreCase = true)

                    val isGDriveAuthError = errMessage.startsWith("GDrive") &&
                            errMessage.contains("401") &&
                            (errMessage.contains("invalid authentication", ignoreCase = true) ||
                             errMessage.contains("Invalid Credentials") ||
                             errMessage.contains("UNAUTHENTICATED"))

                    val isRCloneBoxAuthError = share.host == RCloneShareClient.RCLONE_HOST_MARKER &&
                            errMessage.contains("401") &&
                            (errMessage.contains("unauthorized", ignoreCase = true) ||
                             errMessage.contains("invalid_token", ignoreCase = true) ||
                             errMessage.contains("expired_token", ignoreCase = true))

                    GoRoLog.d("NetworkBrowser", "Err msg: $errMessage")
                    GoRoLog.d("NetworkBrowser", "isConnectionError=$isConnectionError isGDriveScopeError=$isGDriveScopeError isGDriveAuthError=$isGDriveAuthError isRCloneBoxAuthError=$isRCloneBoxAuthError")

                    val tvEmptyState = findViewById<TextView>(R.id.txtEmptyState)
                    val cardGuide = findViewById<View>(R.id.cardScopeErrorGuide)
                    val tvGuide = findViewById<TextView>(R.id.txtScopeErrorGuide)
                    val imgEmptyIcon = findViewById<View>(R.id.imgEmptyIcon)

                    if (isConnectionError) {
                        tvEmptyState.text = getString(R.string.network_connection_restored_first)
                        tvEmptyState.visibility = View.VISIBLE
                        cardGuide.visibility = View.GONE
                    } else if (isGDriveScopeError) {
                        imgEmptyIcon.visibility = View.GONE
                        tvEmptyState.visibility = View.GONE
                        val guide = "${getString(R.string.gdrive_scope_error_title)}\n\n${getString(R.string.gdrive_scope_error_guide)}"
                        tvGuide.text = guide
                        cardGuide.visibility = View.VISIBLE
                    } else if (isGDriveAuthError) {
                        imgEmptyIcon.visibility = View.GONE
                        tvEmptyState.visibility = View.GONE
                        val guide = "${getString(R.string.gdrive_401_error_title)}\n\n${getString(R.string.gdrive_401_error_guide)}"
                        tvGuide.text = guide
                        cardGuide.visibility = View.VISIBLE
                    } else if (isRCloneBoxAuthError) {
                        // Check connectivity first — if offline, show connection error
                        val cm = this@NetworkBrowserActivity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                        val isOnline = cm?.activeNetwork?.let {
                            cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        } ?: false
                        if (!isOnline) {
                            tvEmptyState.text = getString(R.string.network_connection_restored_first)
                            tvEmptyState.visibility = View.VISIBLE
                            cardGuide.visibility = View.GONE
                        } else {
                            imgEmptyIcon.visibility = View.GONE
                            tvEmptyState.visibility = View.GONE
                            val guide = "${getString(R.string.rclone_box_401_error_title)}\n\n${getString(R.string.rclone_box_401_error_guide)}"
                            tvGuide.text = guide
                            cardGuide.visibility = View.VISIBLE
                        }
                    } else {
                        tvEmptyState.text = "Error loading directory:\n$errMessage"
                        tvEmptyState.visibility = View.VISIBLE
                        cardGuide.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * Discovers all accessible shares on an SMB server.
     * Returns a list of [NetworkFile] entries (directories) representing each share.
     */
    /**
     * Discovers all accessible shares on an SMB server.
     * Throws if the server is unreachable or authentication fails.
     * Returns an empty list if the server is reachable but has no accessible shares.
     */
    private fun discoverServerShares(server: NetworkShare): List<NetworkFile> {
        val accessibleShares = SmbDiscovery.listAccessibleShares(
            server.host, server.username, server.password, server.domain
        )
        return accessibleShares.map { shareName ->
            NetworkFile(
                name = shareName,
                path = "/$shareName",
                isDirectory = true
            )
        }
    }

    private fun applyData() {
        // Apply hidden files filter
        val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
        val withoutHidden = currentFiles.filter { isNetworkFileVisible(it, showHidden) }

        // Apply filter
        val filtered = withoutHidden.filter {
            if (filterType == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.ALL) true
            else if (it.isDirectory) true
            else {
                val ext = if (it.name.contains(".")) it.name.substringAfterLast(".").lowercase() else ""
                when (filterType) {
                    za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.IMAGES -> ext in za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.IMAGE_EXTENSIONS
                    za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.VIDEOS -> ext in za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.VIDEO_EXTENSIONS
                    za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.AUDIO -> ext in za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.AUDIO_EXTENSIONS
                    za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.DOCUMENTS -> ext in za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.DOCUMENT_EXTENSIONS
                    za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.APKS -> ext in za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.APK_EXTENSIONS
                    else -> true
                }
            }
        }

        val tagFiltered = if (activeTagsFilter.isNotEmpty()) {
            filtered.filter { it.isDirectory || za.kilowatch.ultimatefilemanager.storage.FileTagsManager.getTags(this, it.path).any { t -> t in activeTagsFilter } }
        } else {
            filtered
        }

        // Apply sort — folders first, then sort within groups
        val secondaryComparator: java.util.Comparator<NetworkFile> = when (sortMode) {
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.NAME -> compareBy(NaturalSort.order) { it.name }
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.SIZE -> compareBy { if (it.isDirectory) 0L else it.size }
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.DATE -> compareBy { it.lastModified }
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { if (it.name.contains(".")) it.name.substringAfterLast(".") else "" }
        }
        val orderedComparator = if (sortOrder == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortOrder.DESC) {
            secondaryComparator.reversed()
        } else {
            secondaryComparator
        }

        val customComparator = Comparator<NetworkFile> { f1, f2 ->
            val p1 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(this, f1.path, share.id)
            val p2 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(this, f2.path, share.id)
            if (p1 && p2) {
                NaturalSort.naturalCompare(f1.name, f2.name)
            } else if (p1) {
                -1
            } else if (p2) {
                1
            } else {
                val dir1 = f1.isDirectory
                val dir2 = f2.isDirectory
                if (dir1 != dir2) {
                    if (dir1) -1 else 1
                } else {
                    orderedComparator.compare(f1, f2)
                }
            }
        }
        val sortedFiles = tagFiltered.sortedWith(customComparator)

        // Append action items at TV root (mobile only)
        val displayFiles = if (share.type == ShareType.TV && currentPath.isEmpty() && !isTv) {
            sortedFiles + listOf(
                NetworkFile(
                    name = getString(R.string.take_screenshot),
                    path = SCREENSHOT_PATH,
                    isDirectory = false,
                    iconRes = R.drawable.ic_screenshot
                ),
                NetworkFile(
                    name = getString(R.string.record_screen),
                    path = RECORD_SCREEN_PATH,
                    isDirectory = false,
                    iconRes = R.drawable.ic_record_screen
                ),
                NetworkFile(
                    name = getString(R.string.sideload_apk),
                    path = SIDELOAD_APK_PATH,
                    isDirectory = false,
                    iconRes = R.drawable.ic_apps
                ),
                NetworkFile(
                    name = getString(R.string.sideload_xapk),
                    path = SIDELOAD_XAPK_PATH,
                    isDirectory = false,
                    iconRes = R.drawable.ic_apps
                ),
                NetworkFile(
                    name = getString(R.string.transfer_settings),
                    path = TRANSFER_SETTINGS_PATH,
                    isDirectory = false,
                    iconRes = R.drawable.ic_sync
                ),
                NetworkFile(
                    name = getUseRemoteLabel(),
                    path = USE_REMOTE_PATH,
                    isDirectory = false,
                    iconRes = getUseRemoteIcon(),
                    isToggle = true,
                    isToggled = RemoteTransportPrefs(this).isRemoteEnabled(
                        intent.getStringExtra(EXTRA_PAIRED_DEVICE_ID) ?: ""
                    )
                )
            )
        } else {
            sortedFiles
        }

        val oldPath = lastLoadedPath
        val isNavigatingFolder = oldPath != null && oldPath != currentPath
        lastLoadedPath = currentPath

        // Capture scroll position before reload so we can restore it after the
        // notifyDataSetChanged() that fires inside submitList. Only for same-folder
        // refreshes on mobile (TV uses focus-based navigation, not scroll position).
        val lm = if (!isNavigatingFolder && !isTv) recyclerFiles.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager else null
        val savedPosition = lm?.findFirstVisibleItemPosition() ?: androidx.recyclerview.widget.RecyclerView.NO_POSITION
        val savedOffset = if (savedPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
            lm?.findViewByPosition(savedPosition)?.top ?: 0
        } else 0

        val updateAdapter = {
            fileAdapter.submitList(displayFiles)
            progressBar.visibility = View.GONE
            if (displayFiles.isEmpty()) layoutEmpty.visibility = View.VISIBLE else recyclerFiles.visibility = View.VISIBLE
            // Restore scroll position after the data change (mobile same-folder only)
            if (savedPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                recyclerFiles.post {
                    (recyclerFiles.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.scrollToPositionWithOffset(savedPosition, savedOffset)
                }
            }
        }

        if (isNavigatingFolder && ::recyclerFiles.isInitialized && za.kilowatch.ultimatefilemanager.util.AnimationHelper.areFolderTransitionsEnabled(this)) {
            val isForward = currentPath.length > (oldPath?.length ?: 0)
            za.kilowatch.ultimatefilemanager.util.AnimationHelper.animateFolderTransition(recyclerFiles, isForward) {
                updateAdapter()
            }
        } else {
            updateAdapter()
        }
    }

    private fun handleUseRemoteToggle(deviceId: String, enable: Boolean) {
        if (enable) {
            val device = PairingManager.getInstance(this).getPairedDevice(deviceId)
            MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(R.string.use_remote_auth_title)
                .setMessage(getString(R.string.use_remote_auth_message, device?.name ?: ""))
                .setPositiveButton(R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        val adbManager = AdbManager.getInstance()
                        val success = withContext(Dispatchers.IO) {
                            adbManager.connect(share.host, 5555)
                        }
                        withContext(Dispatchers.Main) {
                            if (success) {
                                RemoteTransportPrefs(this@NetworkBrowserActivity).setRemoteEnabled(deviceId, true)
                                adbManager.disconnectExplicit()
                                loadDirectory()
                            } else {
                                MaterialAlertDialogBuilder(this@NetworkBrowserActivity, R.style.UFM_Dialog)
                                    .setTitle(R.string.use_remote_failed_title)
                                    .setMessage(R.string.use_remote_failed_message)
                                    .setPositiveButton(R.string.ok, null)
                                    .show()
                                loadDirectory()
                            }
                        }
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> loadDirectory() }
                .show()
        } else {
            RemoteTransportPrefs(this).setRemoteEnabled(deviceId, false)
            loadDirectory()
        }
    }

    private fun showSortFilterSheet() {
        val sheet = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet()
        sheet.currentSortMode = sortMode
        sheet.currentSortOrder = sortOrder
        sheet.currentFilterType = filterType
        sheet.currentGroupByDate = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(this)
        sheet.activeTags = activeTagsFilter

        val folderKey = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.folderKey(share.id, currentPath)
        sheet.currentFolderKey = folderKey
        sheet.currentFolderDisplayPath = "${if (share.name.isNotEmpty()) share.name else share.host}:$currentPath"
        val hasFolderOverride = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.hasFolderOverride(this, currentPath, share.id)
        sheet.currentScope = if (hasFolderOverride)
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.FOLDER
            else za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.GLOBAL

        val activeState = if (hasFolderOverride) {
            za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.loadForPath(this, currentPath, share.id)
        } else {
            null
        }
        sheet.currentShowHidden = if (hasFolderOverride) {
            activeState?.showHidden ?: za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
        } else {
            za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
        }
        sheet.currentViewMode = activeState?.viewMode
        sheet.currentIsRecursive = activeState?.isRecursive ?: false

        sheet.onApply = { mode, order, filter, showHidden, groupByDate, tags, scope, selectedViewMode, isRecursive ->
            sortMode = mode
            sortOrder = order
            filterType = filter
            activeTagsFilter = tags
            if (scope == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.GLOBAL) {
                za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled = showHidden
            }

            val state = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.SortFilterState(
                mode, order, filter, showHidden, groupByDate, tags,
                viewMode = if (scope == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.FOLDER) selectedViewMode else null,
                isRecursive = if (scope == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.FOLDER) isRecursive else false
            )
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (scope == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.FOLDER) {
                    za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.saveFolderSpecific(
                        this@NetworkBrowserActivity, folderKey,
                        "${if (share.name.isNotEmpty()) share.name else share.host}:$currentPath", state, isNetwork = true)
                } else {
                    za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.saveGlobal(this@NetworkBrowserActivity, state)
                    ViewModeManager.save(this@NetworkBrowserActivity, selectedViewMode)
                    za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.clearFolderSpecific(this@NetworkBrowserActivity, folderKey)
                }
                val hasFolderOverrideNow = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.hasFolderOverride(this@NetworkBrowserActivity, currentPath, share.id)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    updateSortBadge(hasFolderOverrideNow)
                }
            }

            if (groupByDate != za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(this)) {
                za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.setEnabled(this, groupByDate)
                fileAdapter.isGroupedByDate = groupByDate
            }
            applyViewMode(selectedViewMode)
            applyData()
        }
        sheet.show(supportFragmentManager, za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.TAG)
    }

    /**
     * Tints the sort icon to signal an active folder-specific sort override.
     */
    private fun updateSortBadge(hasFolderOverride: Boolean) {
        val btn = btnSort ?: return
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
        if (hasFolderOverride) {
            btn.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(if (isTv) za.kilowatch.ultimatefilemanager.R.color.tv_button_focused_yellow else za.kilowatch.ultimatefilemanager.R.color.ufm_primary))
        } else {
            btn.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(if (isTv) za.kilowatch.ultimatefilemanager.R.color.tv_text_primary else za.kilowatch.ultimatefilemanager.R.color.mobile_icon_tint))
        }
    }

    /**
     * Determines whether a network file should be visible in the file list.
     * When [showHidden] is false, filters out files/folders whose name starts with "." (Unix dotfile convention).
     */
    private fun isNetworkFileVisible(nf: NetworkFile, showHidden: Boolean): Boolean {
        return showHidden || !HiddenFilesManager.isJunkOrHidden(nf.name)
    }



    private fun showArchiveOptions(files: List<NetworkFile>) {
        val dialog = ArchiveOptionsDialog()
        dialog.setOnConfirm { filename, format, password, useCurrentFolder ->
            if (useCurrentFolder) {
                performNetworkCompression(files, CompressDest.Network(share, currentPath), filename, format, password)
            } else {
                // Stash params then open the storage/destination picker
                pendingCompressSourceFiles = files
                pendingCompressFileName    = filename
                pendingCompressFormat      = format
                pendingCompressPassword    = password
                pickDestinationFolder()
            }
        }
        dialog.show(supportFragmentManager, "ArchiveOptions")
    }

    private fun pickDestinationFolder() {
        val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java).apply {
            putExtra(za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity.EXTRA_COMPRESS_DEST_PICKER, true)
        }
        localFolderPickerLauncher.launch(intent)
    }

    /** Sealed destination type for performNetworkCompression */
    private sealed class CompressDest {
        data class Local(val dir: File) : CompressDest()
        data class Network(val share: NetworkShare, val remotePath: String) : CompressDest()
    }

    private fun performNetworkCompression(sourceFiles: List<NetworkFile>, dest: CompressDest, customFileName: String, format: ArchiveManager.Format, password: String?) {
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val statusText = android.widget.TextView(this).apply {
            text = getString(R.string.preparing)
            textSize = 14f
        }
        val dialogProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16; bottomMargin = 8 }
        }
        dialogView.addView(statusText)
        dialogView.addView(dialogProgress)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.compressing_network_files)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        val job = lifecycleScope.launch(Dispatchers.IO) {
            val tempDir = File(cacheDir, "comp_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            var tempArchive: File? = null  // set once archive is created; cleaned in finally

            // downloadNetworkEntry extracted to class level

            try {
                // 1. Recursively download all selected items to temp dir
                val localFiles = mutableListOf<File>()
                sourceFiles.forEachIndexed { index, netFile ->
                    withContext(Dispatchers.Main) {
                        statusText.text = if (netFile.isDirectory) getString(R.string.downloading_folder_netfilename) else "Downloading: ${netFile.name}"
                        dialogProgress.progress = ((index.toFloat() / sourceFiles.size) * 50).toInt()
                    }
                    localFiles.add(downloadNetworkEntry(netFile, tempDir) { msg ->
                        runOnUiThread { statusText.text = msg }
                    })
                }

                // 2. Compress into a temp archive alongside tempDir
                val extension = format.displayName
                val archiveName = "$customFileName$extension"
                val tempArchiveFile = File(cacheDir, "comp_arch_${System.currentTimeMillis()}$extension")
                tempArchive = tempArchiveFile

                withContext(Dispatchers.Main) { statusText.setText(R.string.compressing) }
                ArchiveManager.compress(localFiles, tempArchiveFile, password, format) { progress ->
                    runOnUiThread { dialogProgress.progress = 50 + (progress / 2) }
                }

                // 3. Deliver to chosen destination
                when (dest) {
                    is CompressDest.Local -> {
                        val finalFile = uniqueFile(dest.dir, customFileName, extension)
                        withContext(Dispatchers.Main) { statusText.setText(R.string.saving) }
                        tempArchiveFile.copyTo(finalFile, overwrite = false)
                        tempArchiveFile.delete(); tempArchive = null
                        withContext(Dispatchers.Main) {
                            dialog.dismiss()
                            fileAdapter.exitSelectionMode()
                            showPremiumSnackbar(getString(R.string.compression_completed_finalfilename, finalFile.name))
                        }
                    }
                    is CompressDest.Network -> {
                        withContext(Dispatchers.Main) {
                            statusText.text = getString(R.string.uploading_to_destsharename)
                        }
                        val cleanDestPath = if (dest.share.isServerMode) {
                            stripSharePrefix(dest.remotePath.trimStart('/'))
                        } else {
                            dest.remotePath
                        }
                        val remotePath = if (cleanDestPath.isEmpty()) archiveName
                                         else "$cleanDestPath/$archiveName"
                        val inStream = tempArchiveFile.inputStream()
                        try {
                            when (dest.share.type) {
                                ShareType.SMB -> SmbShareClient.openOutputStream(dest.share, remotePath)
                                    .use { out -> inStream.copyTo(out) }
                                ShareType.FTP -> FtpShareClient.openOutputStream(dest.share, remotePath)
                                    .use { out -> inStream.copyTo(out) }
                                ShareType.TV  -> TvShareClient.uploadStream(dest.share, remotePath, inStream, tempArchiveFile.length())
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.openOutputStream(dest.share, remotePath)
                                ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(dest.share, remotePath)
                                    .use { out -> inStream.copyTo(out) }
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(dest.share, remotePath)
                                ShareType.DROPBOX -> DropboxShareClient.openOutputStream(dest.share, remotePath)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(dest.share, remotePath)
                        ShareType.WEBDAV                      -> WebDavShareClient.openOutputStream(dest.share, remotePath)
                        ShareType.NFS                         -> NfsShareClient.openOutputStream(dest.share, remotePath)
                                    .use { out -> inStream.copyTo(out) }
                                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                        } finally {
                            inStream.close()
                        }
                        tempArchiveFile.delete(); tempArchive = null
                        withContext(Dispatchers.Main) {
                            dialog.dismiss()
                            fileAdapter.exitSelectionMode()
                            loadDirectory()
                            showPremiumSnackbar(getString(R.string.compression_completed_archivename_destsharename, archiveName, dest.share.name))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    showPremiumSnackbar(getString(R.string.compression_failed_emessage))
                }
            } finally {
                // Always clean up: temp download tree + any leftover temp archive
                tempDir.deleteRecursively()
                tempArchive?.delete()
            }
        }

        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            job.cancel()
            dialog.dismiss()
            showPremiumSnackbar(getString(R.string.compression_cancelled))
        }
    }

    /** Returns a File with no conflicts in [dir] — appends (1), (2) … if needed. */
    private fun uniqueFile(dir: File, baseName: String, extension: String): File {
        var candidate = File(dir, "$baseName$extension")
        var count = 1
        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($count)$extension")
            count++
        }
        return candidate
    }

    private suspend fun downloadNetworkEntry(
        netFile: NetworkFile,
        localParent: File,
        onStatusUpdate: ((String) -> Unit)? = null
    ): File {
        val localFile = File(localParent, netFile.name)
        if (netFile.isDirectory) {
            localFile.mkdirs()
            val children = when (share.type) {
                ShareType.SMB -> SmbShareClient.listFiles(share, netFile.path)
                ShareType.FTP -> FtpShareClient.listFiles(share, netFile.path)
                ShareType.TV  -> TvShareClient.listFiles(share, netFile.path)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, netFile.path)
                ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(share, netFile.path)
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, netFile.path)
                ShareType.DROPBOX -> DropboxShareClient.listFiles(share, netFile.path)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, netFile.path)
                ShareType.WEBDAV                      -> WebDavShareClient.listFiles(share, netFile.path)
                ShareType.NFS                         -> NfsShareClient.listFiles(share, netFile.path)
                ShareType.DLNA                        -> DlnaShareClient.listFiles(share, netFile.path)
            }
            for (child in children) {
                downloadNetworkEntry(child, localFile, onStatusUpdate)
            }
        } else {
            onStatusUpdate?.invoke(getString(R.string.downloading_netfilename, netFile.name))
            val inStream = when (share.type) {
                ShareType.SMB -> SmbShareClient.openInputStream(share, netFile.path)
                ShareType.FTP -> FtpShareClient.openInputStream(share, netFile.path)
                ShareType.TV  -> TvShareClient.openInputStream(share, netFile.path)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, netFile.path)
                ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, netFile.path).first
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, netFile.path).first
                ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, netFile.path).first
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, netFile.path).first
                ShareType.WEBDAV                      -> WebDavShareClient.openInputStream(share, netFile.path).first
                ShareType.NFS                         -> NfsShareClient.openInputStream(share, netFile.path)
                ShareType.DLNA                        -> DlnaShareClient.openInputStream(share, netFile.path)
            }
            inStream.use { inp ->
                FileOutputStream(localFile).use { out ->
                    inp.copyTo(out)
                }
            }
        }
        return localFile
    }

    private suspend fun uploadLocalEntryToNetwork(localFile: File, remotePath: String) {
        if (localFile.isDirectory) {
            val children = localFile.listFiles() ?: return
            for (child in children) {
                val childRemotePath = if (remotePath.isEmpty()) child.name else "$remotePath/${child.name}"
                uploadLocalEntryToNetwork(child, childRemotePath)
            }
        } else {
            val inStream = localFile.inputStream()
            try {
                when (share.type) {
                    ShareType.SMB -> SmbShareClient.openOutputStream(share, remotePath).use { out -> inStream.copyTo(out) }
                    ShareType.FTP -> FtpShareClient.openOutputStream(share, remotePath).use { out -> inStream.copyTo(out) }
                    ShareType.TV -> TvShareClient.uploadStream(share, remotePath, inStream, localFile.length())
                    ShareType.SFTP, ShareType.SCP -> SshShareClient.openOutputStream(share, remotePath).use { out -> inStream.copyTo(out) }
                    ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(share, remotePath).use { out -> inStream.copyTo(out) }
                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(share, remotePath).use { out -> inStream.copyTo(out) }
                    ShareType.DROPBOX -> DropboxShareClient.openOutputStream(share, remotePath).use { out -> inStream.copyTo(out) }
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(share, remotePath).use { out -> inStream.copyTo(out) }
                    ShareType.WEBDAV -> WebDavShareClient.openOutputStream(share, remotePath).use { out -> inStream.copyTo(out) }
                    ShareType.NFS -> NfsShareClient.openOutputStream(share, remotePath).use { out -> inStream.copyTo(out) }
                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                }
            } finally {
                inStream.close()
            }
        }
    }

    private fun performNetworkExtractHere(archives: List<NetworkFile>) {
        if (archives.isEmpty()) return
        fileAdapter.exitSelectionMode()

        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val statusText = android.widget.TextView(this).apply {
            text = getString(R.string.extract_progress_title)
            textSize = 14f
        }
        val dialogProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false; max = 100; progress = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16; bottomMargin = 8 }
        }
        dialogView.addView(statusText)
        dialogView.addView(dialogProgress)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.extract_progress_title)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        val job = lifecycleScope.launch(Dispatchers.IO) {
            val tempExtractDir = File(cacheDir, "net_extract_${System.currentTimeMillis()}")
            tempExtractDir.mkdirs()

            try {
                for ((index, netArchive) in archives.withIndex()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = getString(R.string.downloading_netfilename, netArchive.name)
                        dialogProgress.progress = ((index.toFloat() / archives.size) * 30).toInt()
                    }

                    // 1. Download network archive to temp file
                    val tempArchiveFile = downloadNetworkEntry(netArchive, tempExtractDir)

                    // 2. Extract locally into subfolder
                    val localExtractedDir = File(tempExtractDir, "extracted_${netArchive.name.substringBeforeLast('.')}")
                    localExtractedDir.mkdirs()

                    withContext(Dispatchers.Main) {
                        statusText.text = getString(R.string.archive_extracting)
                    }

                    val extractRes = ArchiveManager.extract(
                        context = this@NetworkBrowserActivity,
                        archiveFile = tempArchiveFile,
                        destDir = localExtractedDir,
                        password = null,
                        onProgress = { p ->
                            runOnUiThread {
                                dialogProgress.progress = 30 + ((p * 0.3f) + (index * 30)).toInt()
                            }
                        }
                    )

                    if (extractRes.isFailure) {
                        throw extractRes.exceptionOrNull() ?: Exception("Extraction failed")
                    }

                    // 3. Upload extracted items to network share at currentPath
                    withContext(Dispatchers.Main) {
                        statusText.text = getString(R.string.uploading_to_sharename, share.name)
                    }

                    val itemsToUpload = localExtractedDir.listFiles() ?: emptyArray()
                    for ((itemIndex, item) in itemsToUpload.withIndex()) {
                        val remoteDestPath = if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}"
                        uploadLocalEntryToNetwork(item, remoteDestPath)
                        withContext(Dispatchers.Main) {
                            dialogProgress.progress = 60 + (((itemIndex + 1).toFloat() / itemsToUpload.size) * 40).toInt()
                        }
                    }

                    // Clean up local temp for this archive
                    tempArchiveFile.delete()
                    localExtractedDir.deleteRecursively()
                }

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    showPremiumSnackbar(getString(R.string.extract_success))
                    loadDirectory()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    val msg = e.message ?: "Unknown error"
                    showPremiumSnackbar(getString(R.string.extract_error, msg))
                    loadDirectory()
                }
            } finally {
                tempExtractDir.deleteRecursively()
            }
        }

        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            job.cancel()
            dialog.dismiss()
            showPremiumSnackbar(getString(R.string.archive_extract_error))
        }
    }

    // ── Operations ────────────────────────────────────────────────────────────

    /**
     * Shows a dialog with two choices: "New Folder" and "New Text File".
     */
    private fun showCreateNewMenu() {
        val isOnTv = DeviceUtils.isTvDevice(this)
        val bgColor = if (isOnTv) getColor(R.color.tv_bg_gradient_end) else android.graphics.Color.TRANSPARENT
        val textPrimary = if (isOnTv) getColor(R.color.tv_text_primary) else getColor(R.color.ufm_text_primary)
        val textSecondary = if (isOnTv) getColor(R.color.tv_text_secondary) else getColor(R.color.ufm_text_hint)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
            setBackgroundColor(bgColor)
        }

        val rowFolder = createMenuRowView(R.drawable.ic_folder, getString(R.string.new_menu_new_folder), isOnTv, textPrimary, textSecondary)
        container.addView(rowFolder)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = 8; bottomMargin = 8
            }
            setBackgroundColor(0x33FFFFFF.toInt())
        }
        container.addView(divider)

        val rowFile = createMenuRowView(R.drawable.ic_file_text, getString(R.string.new_menu_new_file), isOnTv, textPrimary, textSecondary)
        container.addView(rowFile)

        val dialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.create_new_title))
            .setView(container)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .show()

        // Wire clicks after dialog.show() so we have a reference to dismiss it
        rowFolder.setOnClickListener {
            dialog.dismiss()
            showCreateFolderDialog()
        }
        rowFile.setOnClickListener {
            dialog.dismiss()
            showCreateTextFileDialog()
        }

        if (isOnTv) {
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(getColor(R.color.tv_bg_gradient_end))
            )
            dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)?.setTextColor(textPrimary)
            applyTvDialogButtonStyle(dialog)
        }
    }

    /** Builds a single option row for the create-new menu. */
    private fun createMenuRowView(iconRes: Int, label: String, isOnTv: Boolean, textPrimary: Int, textSecondary: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(40, 40).apply { marginEnd = 16 }
            if (isOnTv) imageTintList = android.content.res.ColorStateList.valueOf(textPrimary)
        }
        row.addView(icon)
        val text = TextView(this).apply {
            this.text = label
            textSize = 16f
            setTextColor(textPrimary)
        }
        row.addView(text)
        if (isOnTv) {
            val white = getColor(R.color.tv_text_primary)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            val yellow = getColor(R.color.tv_button_focused_yellow)
            row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    row.setBackgroundColor(yellow)
                    text.setTextColor(black)
                    icon.imageTintList = android.content.res.ColorStateList.valueOf(black)
                } else {
                    row.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    text.setTextColor(white)
                    icon.imageTintList = android.content.res.ColorStateList.valueOf(white)
                }
            }
        }
        return row
    }

    /** Applies the standard TV dialog button styling. */
    private fun applyTvDialogButtonStyle(dialog: androidx.appcompat.app.AlertDialog) {
        val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
        val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())
        val white = getColor(R.color.tv_text_primary)
        val black = getColor(R.color.tv_button_focused_yellow_text)
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            backgroundTintList = glassCsl
            setTextColor(white)
            setOnFocusChangeListener { _, hasFocus ->
                backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                setTextColor(if (hasFocus) black else white)
            }
        }
    }

    /** Shows a dialog to name and create a new .txt file at the current network path. */
    private fun showCreateTextFileDialog() {
        val isOnTv = DeviceUtils.isTvDevice(this)
        val bgColor = if (isOnTv) getColor(R.color.tv_bg_gradient_end) else android.graphics.Color.TRANSPARENT
        val textColorPrimary = if (isOnTv) getColor(R.color.tv_text_primary) else getColor(R.color.ufm_text_primary)
        val textColorHint = if (isOnTv) getColor(R.color.tv_text_hint) else getColor(R.color.ufm_text_hint)
        val accentColor = if (isOnTv) getColor(R.color.tv_button_focused_yellow) else getColor(R.color.ufm_primary)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
            setBackgroundColor(bgColor)
        }
        val editText = EditText(this).apply {
            hint = getString(R.string.new_file_hint)
            setText(getString(R.string.new_file_default))
            selectAll()
            setSingleLine(true)
            setTextColor(textColorPrimary)
            setHintTextColor(textColorHint)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            requestFocus()
        }
        container.addView(editText)

        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.new_file_title))
            .setIcon(R.drawable.ic_create_new)
            .setView(container)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .setPositiveButton(getString(R.string.new_file_create)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    showPremiumSnackbar(getString(R.string.new_file_empty))
                } else {
                    createNetworkTextFile(name)
                }
            }
            .show()
            .also { dialog ->
                val titleColor = if (isOnTv) getColor(R.color.tv_text_primary) else getColor(R.color.ufm_text_primary)
                dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)?.setTextColor(titleColor)
                if (isOnTv) {
                    dialog.window?.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(getColor(R.color.tv_bg_gradient_end))
                    )
                    val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                    val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())
                    val white = getColor(R.color.tv_text_primary)
                    val black = getColor(R.color.tv_button_focused_yellow_text)
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                        backgroundTintList = yellowCsl; setTextColor(black)
                    }
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                        backgroundTintList = glassCsl; setTextColor(white)
                        setOnFocusChangeListener { _, hasFocus ->
                            backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                            setTextColor(if (hasFocus) black else white)
                        }
                    }
                }
                dialog.window?.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                )
            }
    }

    /**
     * Strips the share-name prefix from [path] when the current share is in server-mode.
     * In server-mode, `share.remotePath` already encodes the share name (e.g. "/docker"),
     * so `currentPath` contains it as a leading segment too (e.g. "/docker/_projects").
     * Passing the raw `currentPath` to SmbShareClient would produce a duplicate segment
     * (e.g. \\server\docker\docker\_projects), so we strip it here before use.
     */
    private fun stripSharePrefix(path: String): String {
        if (!share.isServerMode || share.remotePath.isEmpty()) return path
        val prefix = share.remotePath.trimStart('/')
        return when {
            path.startsWith("$prefix/") -> path.removePrefix("$prefix/")
            path == prefix              -> ""
            else                        -> path
        }
    }

    /**
     * Creates a 0-byte .txt file on the current network/online share.
     * Dispatches to the correct provider API, auto-renames on collision.
     */
    private fun createNetworkTextFile(filename: String) {
        val cleanPath = stripSharePrefix(currentPath.trimStart('/'))
        val remotePath = if (cleanPath.isEmpty()) filename else "$cleanPath/$filename"
        // Clear any stale network save bridge from a previous operation
        za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileSaved = null
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Auto-rename: check existing files for local-like providers
                val existingNames = try {
                    val files = when (share.type) {
                        ShareType.SMB -> SmbShareClient.listFiles(share, cleanPath)
                        ShareType.FTP -> FtpShareClient.listFiles(share, cleanPath)
                        ShareType.TV -> TvShareClient.listFiles(share, cleanPath)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, cleanPath)
                        ShareType.NFS -> NfsShareClient.listFiles(share, cleanPath)
                        else -> emptyList()
                    }
                    files.map { it.name }.toSet()
                } catch (_: Exception) { emptySet() }

                val finalName = if (existingNames.contains(filename)) {
                    val base = filename.substringBeforeLast(".")
                    val ext = filename.substringAfterLast(".", "txt")
                    var counter = 2
                    var candidate = "$base ($counter).$ext"
                    while (candidate in existingNames) {
                        counter++
                        candidate = "$base ($counter).$ext"
                    }
                    candidate
                } else filename

                val finalPath = if (cleanPath.isEmpty()) finalName else "$cleanPath/$finalName"

                when (share.type) {
                    ShareType.SMB -> SmbShareClient.openOutputStream(share, finalPath).use { /* 0-byte */ }
                    ShareType.FTP -> FtpShareClient.openOutputStream(share, finalPath).use { /* 0-byte */ }
                    ShareType.SFTP, ShareType.SCP -> withContext(Dispatchers.IO) {
                        SshShareClient.openOutputStream(share, finalPath).use { /* 0-byte */ }
                    }
                    ShareType.TV -> TvShareClient.uploadStream(share, finalPath,
                        java.io.ByteArrayInputStream(ByteArray(0)), 0L)
                    ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(share, finalPath).use { /* 0-byte */ }
                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(share, finalPath).use { /* 0-byte */ }
                    ShareType.DROPBOX -> DropboxShareClient.openOutputStream(share, finalPath).use { /* 0-byte */ }
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(share, finalPath).use { /* 0-byte */ }
                    ShareType.WEBDAV -> WebDavShareClient.openOutputStream(share, finalPath).use { /* 0-byte */ }
                    ShareType.NFS -> withContext(Dispatchers.IO) {
                        NfsShareClient.openOutputStream(share, finalPath).use { /* 0-byte */ }
                    }
                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                }

                withContext(Dispatchers.Main) {
                    loadDirectory()
                    showPremiumSnackbar(getString(R.string.new_file_success))

                    // Open text viewer in edit mode (download network file to cache first)
                    withContext(Dispatchers.IO) {
                        val safeName = finalName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                        val cacheFile = java.io.File(cacheDir, "ufm_open_$safeName")
                        try {
                            val input = when (share.type) {
                                ShareType.SMB -> SmbShareClient.openInputStream(share, finalPath)
                                ShareType.FTP -> FtpShareClient.openInputStream(share, finalPath)
                                ShareType.TV -> TvShareClient.openInputStream(share, finalPath)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, finalPath)
                                ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, finalPath).first
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, finalPath).first
                                ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, finalPath).first
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, finalPath).first
                                ShareType.WEBDAV -> WebDavShareClient.openInputStream(share, finalPath).first
                                ShareType.NFS -> NfsShareClient.openInputStream(share, finalPath)
                                ShareType.DLNA -> throw UnsupportedOperationException()
                            }
                            input.use { inp ->
                                java.io.FileOutputStream(cacheFile).use { out ->
                                    inp.copyTo(out)
                                }
                            }
                            // Set the network save bridge so content is uploaded back on save
                            val capturedShare = share
                            val capturedFinalPath = finalPath
                            za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileSaved = { savedFile ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val inp = java.io.FileInputStream(savedFile)
                                        inp.use { fis ->
                                            when (capturedShare.type) {
                                                ShareType.SMB -> SmbShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> fis.copyTo(out) }
                                                ShareType.FTP -> FtpShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> fis.copyTo(out) }
                                                ShareType.SFTP, ShareType.SCP -> withContext(Dispatchers.IO) { SshShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> fis.copyTo(out) } }
                                                ShareType.TV -> TvShareClient.uploadStream(capturedShare, capturedFinalPath, fis, savedFile.length())
                                                ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> fis.copyTo(out) }
                                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> fis.copyTo(out) }
                                                ShareType.DROPBOX -> DropboxShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> fis.copyTo(out) }
                                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> fis.copyTo(out) }
                                                ShareType.WEBDAV -> WebDavShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> fis.copyTo(out) }
                                                ShareType.NFS -> withContext(Dispatchers.IO) { NfsShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> fis.copyTo(out) } }
                                                ShareType.DLNA -> throw UnsupportedOperationException()
                                            }
                                        }
                                        // Keep the bridge alive for subsequent saves.
                                        // The existing stale-cache sweeper (30 min) cleans up cache files.
                                    } catch (_: Exception) { }
                                }
                            }
                            withContext(Dispatchers.Main) {
                                val intent = Intent(this@NetworkBrowserActivity, za.kilowatch.ultimatefilemanager.viewer.TextViewerActivity::class.java).apply {
                                    putExtra(FileViewerRouter.EXTRA_FILE_PATH, cacheFile.absolutePath)
                                    putExtra(FileViewerRouter.EXTRA_FILE_NAME, finalName)
                                    putExtra(FileViewerRouter.EXTRA_START_IN_EDIT_MODE, true)
                                }
                                startActivity(intent)
                            }
                        } catch (_: Exception) {
                            // Fallback: just show snackbar
                            withContext(Dispatchers.Main) {
                                showPremiumSnackbar(getString(R.string.new_file_success))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showPremiumSnackbar(getString(R.string.new_file_error) + ": ${e.message}")
                }
            }
        }
    }

    private fun showCreateFolderDialog() {
        val bgColor = getColor(R.color.tv_bg_gradient_end)
        val textColorPrimary = getColor(R.color.tv_text_primary)
        val textColorHint = getColor(R.color.tv_text_hint)
        val accentColor = getColor(R.color.ufm_primary)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val editText = EditText(this).apply {
            hint = getString(R.string.new_folder_hint)
            setText("New Folder")
            selectAll()
            setSingleLine(true)
            setTextColor(textColorPrimary)
            setHintTextColor(textColorHint)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            requestFocus()
        }
        container.addView(editText)

        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.new_folder_title))
            .setIcon(R.drawable.ic_folder)
            .setView(container)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .setPositiveButton(getString(R.string.new_folder_create)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val cleanPath = stripSharePrefix(currentPath.trimStart('/'))
                    val targetPath = if (cleanPath.isEmpty()) name else "$cleanPath/$name"
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            when (share.type) {
                                ShareType.SMB -> SmbShareClient.mkdir(share, targetPath)
                                ShareType.FTP -> FtpShareClient.mkdir(share, targetPath)
                                ShareType.TV  -> TvShareClient.mkdir(share, targetPath)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, targetPath)
                                ShareType.ONEDRIVE -> OnedriveShareClient.mkdir(share, targetPath)
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, targetPath)
                                ShareType.DROPBOX -> DropboxShareClient.mkdir(share, targetPath)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, targetPath)
                    ShareType.WEBDAV                      -> WebDavShareClient.mkdir(share, targetPath)
                    ShareType.NFS                         -> NfsShareClient.mkdir(share, targetPath)
                    ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                            withContext(Dispatchers.Main) {
                                loadDirectory()
                                showPremiumSnackbar(getString(R.string.new_folder_success))
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { showPremiumSnackbar("Error: ${e.message}") }
                        }
                    }
                }
            }
            .show()
            .also { dialog ->
                applyDarkDialogStyle(dialog)
            }
    }

    private fun showRenameDialog(file: NetworkFile?) {
        if (file == null) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val editText = EditText(this).apply {
            setText(file.name)
            selectAll()
            setSingleLine(true)
            setTextColor(getColor(R.color.tv_text_primary))
            setHintTextColor(getColor(R.color.tv_text_hint))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_primary))
            requestFocus()
        }
        container.addView(editText)

        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.rename_title))
            .setView(container)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .setPositiveButton(getString(R.string.rename_confirm)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    val cleanPath = stripSharePrefix(currentPath.trimStart('/'))
                    val targetPath = if (cleanPath.isEmpty()) newName else "$cleanPath/$newName"
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            when (share.type) {
                                ShareType.SMB -> SmbShareClient.rename(share, file.path, targetPath)
                                ShareType.FTP -> FtpShareClient.rename(share, file.path, targetPath)
                                ShareType.TV  -> TvShareClient.rename(share, file.path, targetPath)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(share, file.path, targetPath)
                                ShareType.ONEDRIVE -> OnedriveShareClient.rename(share, file.path, targetPath)
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, file.path, targetPath)
                                ShareType.DROPBOX -> DropboxShareClient.rename(share, file.path, targetPath)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, file.path, targetPath)
                    ShareType.WEBDAV                      -> WebDavShareClient.rename(share, file.path, targetPath, file.isDirectory)
                    ShareType.NFS                         -> NfsShareClient.rename(share, file.path, targetPath)
                    ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                loadDirectory()
                                showPremiumSnackbar(getString(R.string.rename_success))
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { showPremiumSnackbar("Error: ${e.message}") }
                        }
                    }
                }
            }
            .show()
            .also { dialog ->
                applyDarkDialogStyle(dialog)
            }
    }

    private fun showDeleteConfirmation() {
        val selected = fileAdapter.getSelectedFiles()
        if (selected.isEmpty()) return

        val hasProtected = selected.any {
            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isOrContainsProtected(this, it.path, share.id)
        }
        if (hasProtected) {
            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.showProtectedDeleteDialog(this, isTv)
            return
        }

        val recycleEnabled = za.kilowatch.ultimatefilemanager.recycle.RecycleBinManager.isEnabled

        if (recycleEnabled) {
            MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(getString(R.string.move_to_bin))
                .setMessage(getString(R.string.recycle_bin_move_confirm, selected.size))
                .setIcon(R.drawable.ic_delete)
                .setNegativeButton(getString(R.string.delete_cancel), null)
                .setPositiveButton(getString(R.string.move_to_bin)) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            var movedCount = 0
                            var failCount = 0
                            for (f in selected) {
                                try {
                                    val success = za.kilowatch.ultimatefilemanager.recycle.RecycleBinManager.moveNetworkToTrash(
                                        this@NetworkBrowserActivity,
                                        share,
                                        f.path,
                                        f.name,
                                        f.isDirectory,
                                        f.size
                                    )
                                    if (success) movedCount++ else failCount++
                                } catch (e: Exception) {
                                    failCount++
                                }
                            }
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                loadDirectory()
                                if (failCount == 0) showPremiumSnackbar(getString(R.string.recycle_bin_move_success, movedCount))
                                else showPremiumSnackbar(getString(R.string.delete_completed_with_errors_failcount_failed, failCount))
                            }
                        } finally {
                            withContext(Dispatchers.Main) { /* no progress dialog for recycle bin */ }
                        }
                    }
                }
                .show()
                .also { dialog -> applyDarkDialogStyle(dialog) }
        } else {
            val folders = selected.count { it.isDirectory }
            val files = selected.count { !it.isDirectory }
            val message = when {
                folders > 0 && files > 0 -> getString(R.string.delete_message_mixed, folders, files)
                folders > 0 -> getString(R.string.delete_message_folders, folders)
                else -> getString(R.string.delete_message_files, files)
            }

            MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(getString(R.string.delete_title))
                .setMessage(message)
                .setIcon(R.drawable.ic_delete)
                .setNegativeButton(getString(R.string.delete_cancel), null)
                .setPositiveButton(getString(R.string.delete_confirm)) { _, _ ->
                    val folderName = if (selected.size == 1 && selected[0].isDirectory) selected[0].name else ""
                    val progressDialog = za.kilowatch.ultimatefilemanager.indexing.IndexingUiHelper.showDeletionProgressDialog(this, folderName, isIndexing = false)

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            var failCount = 0
                            for (f in selected) {
                                try {
                                    if (f.isDirectory) {
                                        za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.deleteNetworkDirRecursively(share, f.path)
                                    } else {
                                        when (share.type) {
                                            ShareType.SMB -> SmbShareClient.deleteFile(share, f.path)
                                            ShareType.FTP -> FtpShareClient.deleteFile(share, f.path)
                                            ShareType.TV  -> if (f.isDirectory) TvShareClient.deleteDir(share, f.path) else TvShareClient.deleteFile(share, f.path)
                                            ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, f.path, false)
                                            ShareType.ONEDRIVE -> OnedriveShareClient.deleteFile(share, f.path)
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, f.path)
                                            ShareType.DROPBOX -> DropboxShareClient.deleteFile(share, f.path)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, f.path)
                            ShareType.WEBDAV                      -> if (f.isDirectory) WebDavShareClient.deleteDir(share, f.path) else WebDavShareClient.deleteFile(share, f.path)
                            ShareType.NFS                         -> if (f.isDirectory) NfsShareClient.deleteDir(share, f.path) else NfsShareClient.deleteFile(share, f.path)
                            ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                                        }
                                    }
                                } catch (e: Exception) {
                                    failCount++
                                }
                            }
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                loadDirectory()
                                if (failCount == 0) showPremiumSnackbar(getString(R.string.delete_success, selected.size))
                                else showPremiumSnackbar(getString(R.string.delete_completed_with_errors_failcount_failed, failCount))
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                progressDialog.dismiss()
                            }
                        }
                    }
                }
                .show()
                .also { dialog ->
                    applyDarkDialogStyle(dialog)
                }
        }
    }

    private fun performPaste() {
        isCancelledByUser = false
        val hasNet = NetworkClipboard.hasItems()
        val hasLocal = za.kilowatch.ultimatefilemanager.storage.FileClipboard.hasItems()
        if (!hasNet && !hasLocal) return



        // ── Build progress dialog ──────────────────────────────────────
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val statusText = TextView(this).apply {
            text = getString(R.string.preparing)
            textSize = 14f
        }
        val detailText = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        val dialogProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16; bottomMargin = 8 }
        }
        val hintText = TextView(this).apply {
            text = getString(R.string.press_cancel_to_stop_the_transfer)
            textSize = 11f
            setTextColor(0xFF999999.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }
        dialogView.addView(statusText)
        dialogView.addView(dialogProgress)
        dialogView.addView(detailText)
        dialogView.addView(hintText)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.transferring_files)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)  // listener set after show()
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            isCancelledByUser = true
            transferJob?.cancel()
            // Close the raw TCP connection directly — this immediately kills the socket and
            // aborts the blocking SMB write with no 15-second timeout wait.
            runCatching { currentTransferConnection?.close() }
            currentTransferConnection = null
            currentTransferStreams = null  // streams are already dead via connection close
            isTransferring = false
            za.kilowatch.ultimatefilemanager.util.TransferService.stop(this)
            dialog.dismiss()
            // Clear the clipboard so the paste FAB hides
            NetworkClipboard.clear()
            za.kilowatch.ultimatefilemanager.storage.FileClipboard.clear()
            
            loadDirectory()
            updatePasteFab()
        }

        fun updateProgress(fileName: String, bytesCopied: Long, totalBytes: Long, fileIndex: Int, totalFiles: Int) {
            try {
                runOnUiThread {
                    try {
                        val copiedStr = android.text.format.Formatter.formatFileSize(this@NetworkBrowserActivity, bytesCopied)
                        val totalStr = if (totalBytes > 0) android.text.format.Formatter.formatFileSize(this@NetworkBrowserActivity, totalBytes) else "?"
                        statusText.text = if (totalFiles > 1) getString(R.string.file_fileindex_of_totalfiles_filename, fileIndex, totalFiles, fileName) else fileName
                        detailText.text = getString(R.string.copiedstr_totalstr, copiedStr, totalStr)
                        if (totalBytes > 0) {
                            dialogProgress.progress = ((bytesCopied * 1000L) / totalBytes).toInt()
                        }
                    } catch (_: Exception) { /* UI update failed during lifecycle transition — ignore */ }
                }
            } catch (_: Exception) { /* Activity might be finishing — ignore */ }
        }

        // WakeLock + WifiLock are now held by TransferService for the full transfer duration.
        // The service acquires them in onStartCommand and releases them in onDestroy,
        // keeping CPU and Wi-Fi alive regardless of Activity lifecycle.
        progressBar.visibility = View.VISIBLE
        isTransferring = true
        za.kilowatch.ultimatefilemanager.util.TransferService.start(this)
        transferJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
            android.util.Log.w("UFM_COPY", "=== Transfer coroutine STARTED (standalone scope) ===")
            var successCount = 0
            var failCount = 0
            var lastErrorMessage: String? = null
            val applyToAllRef = booleanArrayOf(false)
            var globalAction: za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction? = null
            
            // Cache for share resolution
            val shareCache = mutableMapOf<String, NetworkShare?>()
            fun resolveShare(shareId: String, remotePath: String = ""): NetworkShare? = shareCache.getOrPut(shareId) {
                if (shareId == share.id) {
                    // Server-mode: override remotePath with clipboard's sourceRemotePath
                    if (share.isServerMode && remotePath.isNotEmpty()) share.copy(remotePath = remotePath)
                    else share
                } else {
                    var fromRepo = NetworkShareRepository.getInstance(this@NetworkBrowserActivity).getById(shareId)
                    // Server-mode shares need remotePath from the clipboard entry
                    if (fromRepo?.isServerMode == true && remotePath.isNotEmpty()) {
                        fromRepo = fromRepo.copy(remotePath = remotePath)
                    }
                    if (fromRepo != null) fromRepo
                    else {
                        val dev = PairingManager.getInstance(this@NetworkBrowserActivity).getPairedDevice(shareId)
                        if (dev != null) NetworkShare(id = dev.deviceId, name = dev.name,
                            type = ShareType.TV, host = dev.lastIp, port = dev.lastPort, readOnly = false)
                        else {
                            val online = OnlineStorageRepository.getInstance(this@NetworkBrowserActivity).getById(shareId)
                            if (online != null) NetworkShare(
                                id = online.id, name = online.displayName,
                                type = when (online.provider) {
                                    OnlineStorageProvider.ONEDRIVE     -> ShareType.ONEDRIVE
                                    OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                                    OnlineStorageProvider.DROPBOX      -> ShareType.DROPBOX
                                    OnlineStorageProvider.AWS_S3       -> ShareType.AWS_S3
                                    OnlineStorageProvider.IDRIVE_E2    -> ShareType.IDRIVE_E2
                                    OnlineStorageProvider.WEBDAV       -> ShareType.WEBDAV
                                    OnlineStorageProvider.RCLONE       -> ShareType.WEBDAV
                                },
                                host = when (online.provider) {
                                    OnlineStorageProvider.RCLONE -> RCloneShareClient.RCLONE_HOST_MARKER
                                    else -> if (online.isWebDavProvider) online.webDavUrl ?: "" else online.s3Endpoint ?: online.email
                                },
                                domain = online.s3Bucket ?: "",
                                remotePath = online.s3Region ?: "",
                                username = when (online.provider) {
                                    // Use online.id as the rclone remote name — matches the
                                    // section key in the encrypted config and launchRCloneBrowse.
                                    OnlineStorageProvider.RCLONE -> online.id
                                    else -> if (online.isWebDavProvider) online.webDavUsername ?: "" else online.s3AccessKey ?: online.email
                                },
                                password = if (online.isWebDavProvider) online.webDavPassword ?: "" else online.s3SecretKey ?: "",
                                readOnly = false
                            )
                            else null
                        }
                    }
                }
            }

            try {


            // 1. Calculate total files
            var totalFiles = 0
            if (hasNet) {
                for (entry in NetworkClipboard.entries) {
                    val srcShare = resolveShare(entry.sourceShareId, entry.sourceRemotePath)
                    if (srcShare != null) {
                        totalFiles += if (entry.file.isDirectory) za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.countNetworkFiles(srcShare, entry.file.path) else 1
                    }
                }
            }
            if (hasLocal) {
                for (entry in za.kilowatch.ultimatefilemanager.storage.FileClipboard.entries) {
                    totalFiles += if (entry.file.isDirectory) za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.countLocalFiles(entry.file) else 1
                }
            }

            var fileIndex = 0
            /** Strip the share name prefix from [path] when in server mode, since
             *  share.remotePath already encodes it. E.g. "docker/_projects" → "_projects".
             *  Delegates to the class-level helper so both paste and create/rename use
             *  the same logic. */
            fun stripSharePrefix(path: String): String = this@NetworkBrowserActivity.stripSharePrefix(path)

            // Handle Network paste — each entry has its own sourceShareId and operation
            if (hasNet) {
                suspend fun processNetItem(srcShare: NetworkShare, source: NetworkFile, op: NetworkClipboard.Operation, currentDest: String, destChildren: List<NetworkFile>) {
                    if (isCancelledByUser) throw CancellationException()
                    val itemName = source.name
                    val cleanDest = stripSharePrefix(currentDest)
                    val targetPath = if (cleanDest.isEmpty() || cleanDest == "/") itemName else "${cleanDest.trimEnd('/')}/$itemName"
                    android.util.Log.d("ServerModePaste", "processNetItem: srcShare.isServerMode=${srcShare.isServerMode} srcShare.remotePath='${srcShare.remotePath}' share.isServerMode=${share.isServerMode} share.remotePath='${share.remotePath}' currentDest='$currentDest' cleanDest='$cleanDest' targetPath='$targetPath' itemName='$itemName' source.path='${source.path}'")

                    if (source.isDirectory) {
                        val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.networkFileExists(itemName, destChildren)
                        var effectiveDest = targetPath
                        if (hasConflict) {
                            val resolvedAction = globalAction ?: withContext(Dispatchers.Main) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                    this@NetworkBrowserActivity, itemName, true, -1L, applyToAllRef
                                ).also { if (applyToAllRef[0]) globalAction = it }
                            }
                            when (resolvedAction) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL -> {
                                    isCancelledByUser = true
                                    throw kotlinx.coroutines.CancellationException()
                                }
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP -> {
                                    successCount++
                                    return
                                }
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                    effectiveDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueNetworkPath(cleanDest, itemName, destChildren, isFolder = true)
                                }
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                    effectiveDest = targetPath
                                }
                            }
                        }

                        if (srcShare.id == share.id && op == NetworkClipboard.Operation.MOVE && (!hasConflict || effectiveDest != targetPath)) {
                            // OPTIMIZATION: Same-share directory move
                            updateProgress(itemName, 0, 0, fileIndex, totalFiles) // Directory doesn't have "size" for progress
                            when (share.type) {
                                ShareType.SMB          -> SmbShareClient.rename(share, source.path, effectiveDest)
                                ShareType.FTP          -> FtpShareClient.rename(share, source.path, effectiveDest)
                                ShareType.TV           -> TvShareClient.rename(share, source.path, effectiveDest)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(share, source.path, effectiveDest)
                                ShareType.ONEDRIVE     -> OnedriveShareClient.rename(share, source.path, effectiveDest)
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, source.path, effectiveDest)
                                ShareType.DROPBOX      -> DropboxShareClient.rename(share, source.path, effectiveDest)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, source.path, effectiveDest)
                                ShareType.WEBDAV                      -> WebDavShareClient.rename(share, source.path, effectiveDest)
                                ShareType.NFS                         -> NfsShareClient.rename(share, source.path, effectiveDest)
                                ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                            FileTagsManager.onPathMoved(this@NetworkBrowserActivity, source.path, effectiveDest)
                            return // Move is complete
                        }

                        try {
                            when(share.type) {
                                ShareType.SMB          -> SmbShareClient.mkdir(share, effectiveDest)
                                ShareType.FTP          -> FtpShareClient.mkdir(share, effectiveDest)
                                ShareType.TV           -> TvShareClient.mkdir(share, effectiveDest)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, effectiveDest)
                                ShareType.ONEDRIVE     -> OnedriveShareClient.mkdir(share, effectiveDest)
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, effectiveDest)
                                ShareType.DROPBOX      -> DropboxShareClient.mkdir(share, effectiveDest)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, effectiveDest)
                                ShareType.WEBDAV                      -> WebDavShareClient.mkdir(share, effectiveDest)
                                ShareType.NFS                         -> NfsShareClient.mkdir(share, effectiveDest)
                                ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                        } catch(_: Exception) {}

                        val children = when(srcShare.type) {
                            ShareType.SMB          -> SmbShareClient.listFiles(srcShare, source.path)
                            ShareType.FTP          -> FtpShareClient.listFiles(srcShare, source.path)
                            ShareType.TV           -> TvShareClient.listFiles(srcShare, source.path)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(srcShare, source.path)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.listFiles(srcShare, source.path)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(srcShare, source.path)
                            ShareType.DROPBOX      -> DropboxShareClient.listFiles(srcShare, source.path)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(srcShare, source.path)
                            ShareType.WEBDAV                      -> WebDavShareClient.listFiles(srcShare, source.path)
                            ShareType.NFS                         -> NfsShareClient.listFiles(srcShare, source.path)
                            ShareType.DLNA                        -> DlnaShareClient.listFiles(srcShare, source.path)
                        }

                        val newDestChildren = try {
                            when(share.type) {
                                ShareType.SMB          -> SmbShareClient.listFiles(share, effectiveDest)
                                ShareType.FTP          -> FtpShareClient.listFiles(share, effectiveDest)
                                ShareType.TV           -> TvShareClient.listFiles(share, effectiveDest)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, effectiveDest)
                                ShareType.ONEDRIVE     -> OnedriveShareClient.listFiles(share, effectiveDest)
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, effectiveDest)
                                ShareType.DROPBOX      -> DropboxShareClient.listFiles(share, effectiveDest)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, effectiveDest)
                                ShareType.WEBDAV                      -> WebDavShareClient.listFiles(share, effectiveDest)
                                ShareType.NFS                         -> NfsShareClient.listFiles(share, effectiveDest)
                                ShareType.DLNA                        -> DlnaShareClient.listFiles(share, effectiveDest)
                            }
                        } catch(_: Exception) { emptyList() }

                        for (child in children) {
                            if (isCancelledByUser) break
                            try {
                                processNetItem(srcShare, child, op, effectiveDest, newDestChildren) 
                            } catch (e: Exception) {
                                if (isCancelledByUser) throw CancellationException()
                                android.util.Log.e("PasteFeature", "Error processing net item child ${child.name}: ${e.message}")
                                failCount++
                            }
                        }
                        
                        if (op == NetworkClipboard.Operation.MOVE && !isCancelledByUser) {
                            try { za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.deleteNetworkDirRecursively(srcShare, source.path) } catch(_: Exception) {}
                            FileTagsManager.onPathMoved(this@NetworkBrowserActivity, source.path, effectiveDest)
                        } else if (!isCancelledByUser) {
                            FileTagsManager.onPathCopied(this@NetworkBrowserActivity, source.path, effectiveDest)
                        }
                    } else {
                        val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.networkFileExists(itemName, destChildren)
                        val resolvedAction = if (hasConflict) {
                            globalAction ?: withContext(Dispatchers.Main) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                    this@NetworkBrowserActivity, itemName, false, source.size, applyToAllRef
                                ).also { if (applyToAllRef[0]) globalAction = it }
                            }
                        } else za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH

                        if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL) {
                            throw kotlinx.coroutines.CancellationException()
                        }
                        if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP) {
                            successCount++
                            return
                        }

                        val finalPath = if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH)
                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueNetworkPath(cleanDest, itemName, destChildren)
                        else targetPath

                        fileIndex++
                        try {
                            if (srcShare.id == share.id && op == NetworkClipboard.Operation.MOVE) {
                                updateProgress(itemName, 0, source.size, fileIndex, totalFiles)
                                when (share.type) {
                                    ShareType.SMB          -> SmbShareClient.rename(share, source.path, finalPath)
                                    ShareType.FTP          -> FtpShareClient.rename(share, source.path, finalPath)
                                    ShareType.TV           -> TvShareClient.rename(share, source.path, finalPath)
                                    ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(share, source.path, finalPath)
                                    ShareType.ONEDRIVE     -> OnedriveShareClient.rename(share, source.path, finalPath)
                                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, source.path, finalPath)
                                    ShareType.DROPBOX      -> DropboxShareClient.rename(share, source.path, finalPath)
                                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, source.path, finalPath)
                                    ShareType.WEBDAV                      -> WebDavShareClient.rename(share, source.path, finalPath)
                                    ShareType.NFS                         -> NfsShareClient.rename(share, source.path, finalPath)
                                    ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                                }
                                FileTagsManager.onPathMoved(this@NetworkBrowserActivity, source.path, finalPath)
                            } else {
                                val useTmp = share.type != ShareType.AWS_S3 && share.type != ShareType.IDRIVE_E2 && share.type != ShareType.WEBDAV && share.type != ShareType.NFS
                                val tmpPath = if (useTmp) "$finalPath.ufm_tmp" else finalPath
                                updateProgress(itemName, 0, source.size, fileIndex, totalFiles)
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.copyNetworkFileToNetwork(
                                    srcShare, source, share, tmpPath,
                                    { n, c, t, _, _ -> updateProgress(n, c, t, fileIndex, totalFiles) },
                                    fileIndex, totalFiles,
                                    { conn -> currentTransferConnection = conn },
                                    cacheDir
                                )
                                currentTransferConnection = null
                                
                                if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE && useTmp) {
                                    try {
                                        when (share.type) {
                                            ShareType.SMB          -> SmbShareClient.deleteFile(share, finalPath)
                                            ShareType.FTP          -> FtpShareClient.deleteFile(share, finalPath)
                                            ShareType.TV           -> TvShareClient.deleteFile(share, finalPath)
                                            ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, finalPath, false)
                                            ShareType.ONEDRIVE     -> OnedriveShareClient.deleteFile(share, finalPath)
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, finalPath)
                                            ShareType.DROPBOX      -> DropboxShareClient.deleteFile(share, finalPath)
                                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, finalPath)
                                            ShareType.WEBDAV                      -> WebDavShareClient.deleteFile(share, finalPath)
                                            ShareType.NFS                         -> NfsShareClient.deleteFile(share, finalPath)
                                            ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                                        }
                                    } catch (_: Exception) {}
                                }

                                if (useTmp) {
                                    when (share.type) {
                                    ShareType.SMB          -> SmbShareClient.rename(share, tmpPath, finalPath)
                                    ShareType.FTP          -> FtpShareClient.rename(share, tmpPath, finalPath)
                                    ShareType.TV           -> TvShareClient.rename(share, tmpPath, finalPath)
                                    ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(share, tmpPath, finalPath)
                                    ShareType.ONEDRIVE     -> OnedriveShareClient.rename(share, tmpPath, finalPath)
                                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, tmpPath, finalPath)
                                    ShareType.DROPBOX      -> DropboxShareClient.rename(share, tmpPath, finalPath)
                                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, tmpPath, finalPath)
                                    ShareType.WEBDAV                      -> WebDavShareClient.rename(share, tmpPath, finalPath)
                                    ShareType.NFS                         -> NfsShareClient.rename(share, tmpPath, finalPath)
                                    ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                                    }
                                }

                                if (op == NetworkClipboard.Operation.MOVE) {
                                    // Zero-byte guard: query dest size before deleting network source
                                    val destSize = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.getRemoteFileSize(share, finalPath)
                                    if (za.kilowatch.ultimatefilemanager.util.FileTransferGuard.requireSourceSafeToDelete(
                                            destSize, source.size, source.name)) {
                                        when (srcShare.type) {
                                            ShareType.SMB          -> SmbShareClient.deleteFile(srcShare, source.path)
                                            ShareType.FTP          -> FtpShareClient.deleteFile(srcShare, source.path)
                                            ShareType.TV           -> TvShareClient.deleteFile(srcShare, source.path)
                                            ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(srcShare, source.path, false)
                                            ShareType.ONEDRIVE     -> OnedriveShareClient.deleteFile(srcShare, source.path)
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(srcShare, source.path)
                                            ShareType.DROPBOX      -> DropboxShareClient.deleteFile(srcShare, source.path)
                                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(srcShare, source.path)
                                            ShareType.WEBDAV                      -> WebDavShareClient.deleteFile(srcShare, source.path)
                                            ShareType.NFS                         -> NfsShareClient.deleteFile(srcShare, source.path)
                                            ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                                        }
                                    }
                                    FileTagsManager.onPathMoved(this@NetworkBrowserActivity, source.path, finalPath)
                                } else {
                                    FileTagsManager.onPathCopied(this@NetworkBrowserActivity, source.path, finalPath)
                                }
                            }
                            successCount++
                        } catch (e: Exception) {
                            if (isCancelledByUser) throw CancellationException()
                            android.util.Log.e("PasteFeature", "Error: ${e.message}")
                            android.util.Log.e("ServerModePaste", "Paste item failed: srcShare.id=${srcShare.id} share.id=${share.id} srcShare.remotePath='${srcShare.remotePath}' share.remotePath='${share.remotePath}' targetPath='${targetPath}' currentDest='$currentDest' op=$op exception=${e.message}")
                            lastErrorMessage = e.message
                            failCount++
                        }
                    }
                }

                // 2. Process items
                for (entry in NetworkClipboard.entries) {
                    val srcShare = resolveShare(entry.sourceShareId, entry.sourceRemotePath)
                    if (srcShare != null) {
                        try {
                            processNetItem(srcShare, entry.file, entry.operation, currentPath, currentFiles)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (isCancelledByUser) throw CancellationException()
                            android.util.Log.e("PasteFeature", "Root processNetItem error: ${e.message}")
                            lastErrorMessage = e.message
                            failCount++
                        }
                    }
                }
                NetworkClipboard.clear()
            }

            // Handle Network-to-Local (e.g. copying TV files to Phone Storage)
            if (hasNet && currentPath.isEmpty()) { 
               // Warning: We are inside NetworkBrowserActivity. So `currentPath` belongs to the NETWORK SHARE.
               // We shouldn't be triggering Network-to-Local writes here unless we are utilizing a FileBrowserActivity intent. 
               // Wait... The user is pressing "Paste" inside NetworkBrowserActivity. 
               // This means the user copied a file from Local, and is pasting INTO the Network.
            }
            if (hasLocal) {
                suspend fun processLocalItem(source: File, op: za.kilowatch.ultimatefilemanager.storage.FileClipboard.Operation, currentDest: String, destChildren: List<NetworkFile>) {
                    val itemName = source.name
                    val cleanDest = stripSharePrefix(currentDest)
                    val targetPath = if (cleanDest.isEmpty() || cleanDest == "/") itemName else "${cleanDest.trimEnd('/')}/$itemName"

                    if (source.isDirectory) {
                        val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.networkFileExists(itemName, destChildren)
                        var effectiveDest = targetPath
                        if (hasConflict) {
                            val resolvedAction = globalAction ?: withContext(Dispatchers.Main) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                    this@NetworkBrowserActivity, itemName, true, -1L, applyToAllRef
                                ).also { if (applyToAllRef[0]) globalAction = it }
                            }
                            when (resolvedAction) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL -> {
                                    isCancelledByUser = true
                                    throw CancellationException()
                                }
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP -> {
                                    successCount++
                                    return
                                }
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                    effectiveDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueNetworkPath(cleanDest, itemName, destChildren, isFolder = true)
                                }
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                    effectiveDest = targetPath
                                }
                            }
                        }

                        try {
                            when(share.type) {
                                ShareType.SMB          -> SmbShareClient.mkdir(share, effectiveDest)
                                ShareType.FTP          -> FtpShareClient.mkdir(share, effectiveDest)
                                ShareType.TV           -> TvShareClient.mkdir(share, effectiveDest)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, effectiveDest)
                                ShareType.ONEDRIVE     -> OnedriveShareClient.mkdir(share, effectiveDest)
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, effectiveDest)
                                ShareType.DROPBOX      -> DropboxShareClient.mkdir(share, effectiveDest)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, effectiveDest)
                                ShareType.WEBDAV                      -> WebDavShareClient.mkdir(share, effectiveDest)
                                ShareType.NFS                         -> NfsShareClient.mkdir(share, effectiveDest)
                                ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                        } catch(e: Exception) {
                            android.util.Log.e("PasteFeature", "mkdir failed for effectiveDest=$effectiveDest: ${e.message}", e)
                        }

                        val children = source.listFiles()
                        
                        val newDestChildren = try {
                            when(share.type) {
                                ShareType.SMB          -> SmbShareClient.listFiles(share, effectiveDest)
                                ShareType.FTP          -> FtpShareClient.listFiles(share, effectiveDest)
                                ShareType.TV           -> TvShareClient.listFiles(share, effectiveDest)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, effectiveDest)
                                ShareType.ONEDRIVE     -> OnedriveShareClient.listFiles(share, effectiveDest)
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, effectiveDest)
                                ShareType.DROPBOX      -> DropboxShareClient.listFiles(share, effectiveDest)
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, effectiveDest)
                                ShareType.WEBDAV                      -> WebDavShareClient.listFiles(share, effectiveDest)
                                ShareType.NFS                         -> NfsShareClient.listFiles(share, effectiveDest)
                                ShareType.DLNA                        -> DlnaShareClient.listFiles(share, effectiveDest)
                            }
                        } catch(_: Exception) { emptyList() }

                        if (children != null) {
                            for (child in children) { 
                                try {
                                    processLocalItem(child, op, effectiveDest, newDestChildren) 
                                } catch (e: Exception) {
                                    if (isCancelledByUser) throw CancellationException()
                                    android.util.Log.e("PasteFeature", "Error processing local item child ${child.name}: ${e.message}")
                                    failCount++
                                }
                            }
                        }
                        if (op == za.kilowatch.ultimatefilemanager.storage.FileClipboard.Operation.MOVE && !isCancelledByUser) {
                            try { source.deleteRecursively() } catch(_: Exception) {}
                            FileTagsManager.onPathMoved(this@NetworkBrowserActivity, source.absolutePath, effectiveDest)
                        } else if (!isCancelledByUser) {
                            FileTagsManager.onPathCopied(this@NetworkBrowserActivity, source.absolutePath, effectiveDest)
                        }
                    } else {
                        val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.networkFileExists(itemName, destChildren)
                        val resolvedAction = if (hasConflict) {
                            globalAction ?: withContext(Dispatchers.Main) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                    this@NetworkBrowserActivity, itemName, false, source.length(), applyToAllRef
                                ).also { if (applyToAllRef[0]) globalAction = it }
                            }
                        } else za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH

                        if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL) {
                            isCancelledByUser = true
                            throw CancellationException()
                        }
                        if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP) { successCount++; return }

                        val finalPath = if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH)
                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueNetworkPath(cleanDest, itemName, destChildren)
                        else targetPath

                        fileIndex++
                        try {
                            updateProgress(itemName, 0, source.length(), fileIndex, totalFiles)
                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uploadLocalToNetworkAtomic(
                                source, share, finalPath,
                                { c, t -> updateProgress(itemName, c, t, fileIndex, totalFiles) },
                                { conn -> currentTransferConnection = conn }
                            )
                            currentTransferConnection = null
                            if (op == za.kilowatch.ultimatefilemanager.storage.FileClipboard.Operation.MOVE) {
                                // Zero-byte guard: query remote dest size before deleting local source
                                val remoteSize = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.getRemoteFileSize(share, finalPath)
                                if (za.kilowatch.ultimatefilemanager.util.FileTransferGuard.requireSourceSafeToDelete(
                                        remoteSize, source.length(), source.name)) {
                                    try { source.delete() } catch(_: Exception) {}
                                }
                                FileTagsManager.onPathMoved(this@NetworkBrowserActivity, source.absolutePath, finalPath)
                            } else {
                                FileTagsManager.onPathCopied(this@NetworkBrowserActivity, source.absolutePath, finalPath)
                            }
                            successCount++
                        } catch (e: Exception) {
                            if (isCancelledByUser) throw CancellationException()
                            android.util.Log.e("PasteFeature", "Error: ${e.message}")
                            lastErrorMessage = e.message
                            failCount++
                        }
                    }
                }

                for (entry in za.kilowatch.ultimatefilemanager.storage.FileClipboard.entries) {
                    try {
                        processLocalItem(entry.file, entry.operation, currentPath, currentFiles)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isCancelledByUser) throw CancellationException()
                        android.util.Log.e("PasteFeature", "Root processLocalItem error: ${e.message}")
                        lastErrorMessage = e.message
                        failCount++
                    }
                }
                za.kilowatch.ultimatefilemanager.storage.FileClipboard.clear()
            }
            withContext(Dispatchers.Main) {
                isTransferring = false
                dialog.dismiss()
                
                if (isQuickTransferPickerMode) {
                    val result = Intent().apply {
                        putExtra("QT_SUCCESS_COUNT", successCount)
                        putExtra("QT_FAIL_COUNT", failCount)
                    }
                    setResult(RESULT_OK, result)
                    finish()
                    return@withContext
                }

                updatePasteFab()
                loadDirectory()
                if (failCount == 0 && successCount > 0) showPremiumSnackbar(getString(R.string.paste_success, successCount))
                else if (failCount > 0) {
                    val detail = lastErrorMessage?.let { "\n$it" } ?: ""
                    showPremiumSnackbar(getString(R.string.paste_error) + detail)
                }
            }
            } finally {
                android.util.Log.w("UFM_COPY", "=== Transfer FINALLY block: stopping service ===")
                isTransferring = false
                za.kilowatch.ultimatefilemanager.util.TransferService.stop(this@NetworkBrowserActivity)
            }
        }
    }

    // ── Share & Vault ─────────────────────────────────────────────────────────

    /**
     * Downloads selected network files to a temp directory, then shares them.
     */
    private fun shareNetworkFiles(files: List<NetworkFile>) {
        progressBar.visibility = View.VISIBLE
        fileAdapter.exitSelectionMode()
        lifecycleScope.launch(Dispatchers.IO) {
            val tempDir = File(cacheDir, "share_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            val localFiles = mutableListOf<File>()
            for (nf in files) {
                try {
                    val dest = File(tempDir, nf.name)
                    val inStream = when (share.type) {
                        ShareType.SMB -> SmbShareClient.openInputStream(share, nf.path)
                        ShareType.FTP -> FtpShareClient.openInputStream(share, nf.path)
                        ShareType.TV  -> TvShareClient.openInputStream(share, nf.path)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, nf.path)
                        ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, nf.path).first
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, nf.path).first
                        ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, nf.path).first
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, nf.path).first
                        ShareType.WEBDAV                      -> WebDavShareClient.openInputStream(share, nf.path).first
                        ShareType.NFS                         -> NfsShareClient.openInputStream(share, nf.path)
                        ShareType.DLNA                        -> DlnaShareClient.openInputStream(share, nf.path)
                    }
                    inStream.use { inp -> FileOutputStream(dest).use { out -> inp.copyTo(out) } }
                    localFiles.add(dest)
                } catch (_: Exception) { }
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (localFiles.isEmpty()) {
                    showPremiumSnackbar(getString(R.string.share_error))
                    return@withContext
                }

                showPremiumShareChooserDialog(localFiles)
            }
        }
    }

    private fun setupTvShareChooserFocus(
        dialog: androidx.appcompat.app.AlertDialog,
        dialogView: View,
        cardStandard: com.google.android.material.card.MaterialCardView?,
        cardPremium: com.google.android.material.card.MaterialCardView?,
        btnCancel: View?
    ) {
        val white = getColor(R.color.tv_text_primary)
        val black = getColor(R.color.tv_button_focused_yellow_text)
        val yellow = getColor(R.color.tv_button_focused_yellow)
        val secondary = getColor(R.color.tv_text_secondary)

        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(getColor(R.color.tv_bg_gradient_end))
        )

        fun setupCardFocus(card: com.google.android.material.card.MaterialCardView, defaultStrokeColor: Int) {
            val horizontal = card.getChildAt(0) as? android.widget.LinearLayout
            val vertical = horizontal?.getChildAt(1) as? android.widget.LinearLayout
            val title = vertical?.getChildAt(0) as? android.widget.TextView
            val desc = vertical?.getChildAt(1) as? android.widget.TextView

            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    card.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(yellow))
                    card.strokeColor = yellow
                    title?.setTextColor(black)
                    desc?.setTextColor(black)
                } else {
                    card.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(getColor(R.color.tv_glass_white_10)))
                    card.strokeColor = defaultStrokeColor
                    title?.setTextColor(white)
                    desc?.setTextColor(secondary)
                }
            }
        }

        cardStandard?.let { setupCardFocus(it, getColor(R.color.tv_glass_border)) }
        cardPremium?.let { setupCardFocus(it, getColor(R.color.tv_accent)) }

        btnCancel?.let { btn ->
            (btn as? com.google.android.material.button.MaterialButton)?.apply {
                val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())
                val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
                backgroundTintList = glassCsl
                setTextColor(white)
                setOnFocusChangeListener { _, hasFocus ->
                    backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                    setTextColor(if (hasFocus) black else white)
                }
            }
        }
    }

    private fun showPremiumShareChooserDialog(localFiles: List<File>) {
        var proceeded = false
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_premium_share_chooser_tv else R.layout.dialog_premium_share_chooser
        val dialogView = android.view.LayoutInflater.from(this).inflate(layoutRes, null)
        val dialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        val cardStandardShare = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardStandardShare)
        val cardPremiumShare = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardPremiumShare)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        cardStandardShare.setOnClickListener {
            proceeded = true
            dialog.dismiss()
            localFiles.firstOrNull()?.parentFile?.let {
                standardShareTempDir = it
            }
            performStandardShareNetwork(localFiles)
        }

        cardPremiumShare.setOnClickListener {
            proceeded = true
            dialog.dismiss()
            if (isTv) {
                val filePaths = ArrayList(localFiles.map { it.absolutePath })
                val intent = Intent(this, PremiumShareTvActivity::class.java).apply {
                    putStringArrayListExtra("files", filePaths)
                    putExtra("target_type", "web")
                    putExtra("clean_up_on_stop", true)
                }
                startActivity(intent)
            } else {
                showPremiumTargetChooserDialog(localFiles)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!proceeded) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        localFiles.firstOrNull()?.parentFile?.deleteRecursively()
                    } catch (_: Exception) {}
                }
            }
        }

        if (isTv) {
            setupTvShareChooserFocus(dialog, dialogView, cardStandardShare, cardPremiumShare, btnCancel)
        }

        dialog.show()
    }

    private fun showPremiumTargetChooserDialog(localFiles: List<File>) {
        var proceeded = false
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_premium_target_chooser, null)
        val dialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        val cardTargetTv = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTargetTv)
        val cardTargetMobilePc = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTargetMobilePc)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val filePaths = ArrayList(localFiles.map { it.absolutePath })

        cardTargetTv.setOnClickListener {
            proceeded = true
            dialog.dismiss()
            val intent = Intent(this, PremiumShareActivity::class.java).apply {
                putStringArrayListExtra("files", filePaths)
                putExtra("target_type", "tv")
                putExtra("clean_up_on_stop", true)
            }
            startActivity(intent)
        }

        cardTargetMobilePc.setOnClickListener {
            proceeded = true
            dialog.dismiss()
            val intent = Intent(this, PremiumShareActivity::class.java).apply {
                putStringArrayListExtra("files", filePaths)
                putExtra("target_type", "web")
                putExtra("clean_up_on_stop", true)
            }
            startActivity(intent)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!proceeded) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        localFiles.firstOrNull()?.parentFile?.deleteRecursively()
                    } catch (_: Exception) {}
                }
            }
        }

        dialog.show()
    }

    private fun performStandardShareNetwork(localFiles: List<File>) {
        try {
            val uris = ArrayList<Uri>()
            for (file in localFiles) {
                uris.add(FileProvider.getUriForFile(this, "${packageName}.fileprovider", file))
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    val ext = localFiles[0].extension.lowercase()
                    type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
        } catch (e: Exception) {
            showPremiumSnackbar(getString(R.string.share_error))
        }
    }

    /**
     * Shows a vault picker dialog for encrypting network files.
     * Downloads them to temp, then opens VaultActivity.
     */
    private fun showNetworkVaultPicker(files: List<NetworkFile>, isMove: Boolean) {
        val vaultDir = File(filesDir, "vault")
        val entries = mutableListOf<za.kilowatch.ultimatefilemanager.storage.VaultEntry>()
        if (vaultDir.exists() && vaultDir.isDirectory) {
            vaultDir.listFiles()?.forEach { entryDir ->
                if (entryDir.isDirectory) {
                    readVaultEntry(entryDir)?.let { entries.add(it) }
                }
            }
        }

        if (entries.isEmpty()) {
            // No vaults exist — prompt to create one
            MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(getString(R.string.encrypt_no_vaults))
                .setMessage(getString(R.string.encrypt_create_first))
                .setIcon(R.drawable.ic_lock)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(getString(R.string.encrypt_create_vault)) { _, _ ->
                    startActivity(Intent(this, VaultActivity::class.java))
                }
                .show()
                .also { dialog -> applyDarkDialogStyle(dialog) }
            return
        }

        // Show vault picker list with white text
        val vaultNames = entries.map { it.displayName }.toTypedArray()
        val title = if (isMove) getString(R.string.encrypt_move_title)
                    else getString(R.string.encrypt_copy_title)

        val white = getColor(R.color.tv_text_primary)
        val adapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, vaultNames.toList()
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<TextView>(android.R.id.text1)
                tv.setTextColor(white)
                tv.textSize = 17f
                tv.setPadding(48, 28, 48, 28)
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                return view
            }
        }

        val listView = android.widget.ListView(this).apply {
            this.adapter = adapter
            divider = android.graphics.drawable.ColorDrawable(0x1AFFFFFF.toInt())
            dividerHeight = 1
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val dialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(title)
            .setView(listView)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        listView.setOnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            val entry = entries[which]
            downloadAndEncrypt(files, entry, isMove)
        }

        applyDarkDialogStyle(dialog)
    }

    private fun downloadAndEncrypt(files: List<NetworkFile>, entry: za.kilowatch.ultimatefilemanager.storage.VaultEntry, isMove: Boolean) {
        progressBar.visibility = View.VISIBLE
        fileAdapter.exitSelectionMode()
        lifecycleScope.launch(Dispatchers.IO) {
            val tempDir = File(cacheDir, "net_temp")
            tempDir.mkdirs()
            var successCount = 0
            val encryptedNames = mutableListOf<String>()
            for (nf in files) {
                try {
                    val dest = File(tempDir, nf.name)
                    val inStream = when (share.type) {
                        ShareType.SMB -> SmbShareClient.openInputStream(share, nf.path)
                        ShareType.FTP -> FtpShareClient.openInputStream(share, nf.path)
                        ShareType.TV  -> TvShareClient.openInputStream(share, nf.path)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, nf.path)
                            ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, nf.path).first
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, nf.path).first
                            ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, nf.path).first
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, nf.path).first
                        ShareType.WEBDAV                      -> WebDavShareClient.openInputStream(share, nf.path).first
                        ShareType.NFS                         -> NfsShareClient.openInputStream(share, nf.path)
                        ShareType.DLNA                        -> DlnaShareClient.openInputStream(share, nf.path)
                    }
                    inStream.use { inp -> FileOutputStream(dest).use { out -> inp.copyTo(out) } }

                    // Encrypt file to vault
                    val entryDir = File(filesDir, "vault/${entry.id}")
                    entryDir.mkdirs()
                    val encFile = File(entryDir, "${nf.name}.enc")
                    za.kilowatch.ultimatefilemanager.storage.VaultCrypto.encryptFile(dest, encFile)
                    dest.delete()
                    encryptedNames.add(nf.name)

                    if (isMove) {
                        when (share.type) {
                                ShareType.SMB -> SmbShareClient.deleteFile(share, nf.path)
                                ShareType.FTP -> FtpShareClient.deleteFile(share, nf.path)
                                ShareType.TV  -> TvShareClient.deleteFile(share, nf.path)
                                        ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, nf.path, false)
                                        ShareType.ONEDRIVE -> OnedriveShareClient.deleteFile(share, nf.path)
                                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, nf.path)
                                        ShareType.DROPBOX -> DropboxShareClient.deleteFile(share, nf.path)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, nf.path)
                        ShareType.WEBDAV                      -> WebDavShareClient.deleteFile(share, nf.path)
                        ShareType.NFS                         -> NfsShareClient.deleteFile(share, nf.path)
                        ShareType.DLNA                        -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                    }
                    successCount++
                } catch (_: Exception) { }
            }

            // Update vault metadata
            if (encryptedNames.isNotEmpty()) {
                val entryDir = File(filesDir, "vault/${entry.id}")
                val existingFiles = entry.files.toMutableList()
                encryptedNames.forEach { name ->
                    if (!existingFiles.contains(name)) existingFiles.add(name)
                }
                val metadata = org.json.JSONObject().apply {
                    put("id", entry.id)
                    put("displayName", entry.displayName)
                    put("originalRoot", entry.originalRoot)
                    put("files", org.json.JSONArray(existingFiles))
                }
                File(entryDir, "metadata.json").writeText(metadata.toString())
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (successCount > 0) {
                    showPremiumSnackbar(getString(R.string.encrypted_successcount_files_to_vault))
                    if (isMove) loadDirectory()
                } else {
                    showPremiumSnackbar(getString(R.string.failed_to_encrypt_files))
                }
            }
        }
    }

    private fun readVaultEntry(dir: File): za.kilowatch.ultimatefilemanager.storage.VaultEntry? {
        val metaFile = File(dir, "metadata.json")
        if (!metaFile.exists()) return null
        return try {
            val json = org.json.JSONObject(metaFile.readText())
            val filesArray = json.optJSONArray("files") ?: org.json.JSONArray()
            val filesList = mutableListOf<String>()
            for (i in 0 until filesArray.length()) {
                filesList.add(filesArray.getString(i))
            }
            za.kilowatch.ultimatefilemanager.storage.VaultEntry(
                id = json.getString("id"),
                displayName = json.getString("displayName"),
                originalRoot = json.optString("originalRoot", ""),
                files = filesList
            )
        } catch (_: Exception) { null }
    }

    // ── Dialog Styling ────────────────────────────────────────────────────────

    /**
     * Applies dark-theme styling to a MaterialAlertDialog: dark background,
     * white title + message text, and themed buttons.
     */
    private fun applyDarkDialogStyle(dialog: androidx.appcompat.app.AlertDialog) {
        val bgColor = getColor(R.color.tv_bg_gradient_end)
        val white = getColor(R.color.tv_text_primary)
        val black = getColor(R.color.tv_button_focused_yellow_text)
        val yellow = getColor(R.color.tv_button_focused_yellow)
        val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
        val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

        // Dark background
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))

        // White title
        val titleView = dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)
            ?: dialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(white)

        // White message
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(white)

        // Positive button: yellow bg + black text
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            backgroundTintList = yellowCsl
            setTextColor(black)
        }
        // Negative button: glass default, focus-aware on TV
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            backgroundTintList = glassCsl
            setTextColor(white)
            if (isTv) {
                setOnFocusChangeListener { _, hasFocus ->
                    backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                    setTextColor(if (hasFocus) black else white)
                }
            }
        }

        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
    }

    /**
     * Shows a clipboard dialog listing all clipboard items (NetworkClipboard + FileClipboard).
     * Each row displays the operation type (COPY/CUT) and has a bin button to dequeue.
     * On mobile: BottomSheetDialog. On TV: centered AlertDialog with D-pad focus states.
     */
    private fun showClipboardSheet() {
        // Unified clipboard entry model
        data class ClipEntry(val name: String, val isMove: Boolean, val isNetwork: Boolean,
                             val netFile: NetworkFile? = null, val localFile: java.io.File? = null)

        fun buildEntries(): MutableList<ClipEntry> {
            val list = mutableListOf<ClipEntry>()
            for (e in NetworkClipboard.entries)
                list.add(ClipEntry(e.file.name, e.operation == NetworkClipboard.Operation.MOVE, true, netFile = e.file))
            for (e in za.kilowatch.ultimatefilemanager.storage.FileClipboard.entries)
                list.add(ClipEntry(e.file.name, e.operation == za.kilowatch.ultimatefilemanager.storage.FileClipboard.Operation.MOVE, false, localFile = e.file))
            return list
        }

        // Start with an empty list — the dialog opens immediately while entries load in the background
        var entries = mutableListOf<ClipEntry>()
        val colorCopy = getColor(R.color.ufm_primary)
        val colorCut = getColor(R.color.ufm_denied)

        // Choose layout & item based on TV vs mobile
        val layoutRes = if (isTv) R.layout.dialog_clipboard_tv else R.layout.bottom_sheet_clipboard
        val itemLayoutRes = if (isTv) R.layout.item_clipboard_entry_tv else R.layout.item_clipboard_entry
        val contentView = layoutInflater.inflate(layoutRes, null)

        val recycler = contentView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerClipboard)
        val btnPasteHere = contentView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPasteHere)
        val btnClearAll = contentView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearClipboard)
        val txtTitle = contentView.findViewById<android.widget.TextView>(R.id.txtClipboardTitle)

        // Title always reflects the real total from the singletons, not the loaded list size
        fun realTotal() = (if (za.kilowatch.ultimatefilemanager.storage.FileClipboard.hasItems()) za.kilowatch.ultimatefilemanager.storage.FileClipboard.files.size else 0) +
                (if (NetworkClipboard.hasItems()) NetworkClipboard.files.size else 0)

        fun updateTitle() {
            val n = realTotal()
            txtTitle.text = if (n == 1) getString(R.string.clipboard_1_file) else getString(R.string.clipboard_total_files, n)
        }
        updateTitle()

        // Create the dialog — AlertDialog on TV (centered), BottomSheetDialog on mobile
        val dialog: android.app.Dialog = if (isTv) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setView(contentView)
                .create()
        } else {
            com.google.android.material.bottomsheet.BottomSheetDialog(this).apply {
                setContentView(contentView)
            }
        }

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class VH(val v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                VH(layoutInflater.inflate(itemLayoutRes, parent, false))
            override fun getItemCount() = entries.size
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val entry = entries[position]
                val v = holder.itemView
                val txtOp = v.findViewById<android.widget.TextView>(R.id.txtOperation)
                val txtName = v.findViewById<android.widget.TextView>(R.id.txtFileName)
                val btnRemove = v.findViewById<android.widget.ImageView>(R.id.btnRemoveClipboard)

                txtOp.text = if (entry.isMove) "CUT" else "COPY"
                (txtOp.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                    if (entry.isMove) colorCut else colorCopy
                )
                txtName.text = entry.name

                // TV: yellow tint on focus for remove button
                if (isTv) {
                    val yellowTint = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                    val redTint = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_denied))
                    btnRemove.setOnFocusChangeListener { _, hasFocus ->
                        btnRemove.imageTintList = if (hasFocus) yellowTint else redTint
                    }
                }

                btnRemove.setOnClickListener {
                    if (entry.isNetwork && entry.netFile != null) NetworkClipboard.remove(entry.netFile)
                    else if (!entry.isNetwork && entry.localFile != null) za.kilowatch.ultimatefilemanager.storage.FileClipboard.remove(entry.localFile)
                    entries = buildEntries()
                    notifyDataSetChanged()
                    updateTitle()
                    updatePasteFab()
                    if (entries.isEmpty()) dialog.dismiss()
                }
            }
        }

        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recycler.adapter = adapter

        // Load entries off the main thread — avoids ANR for large clipboards (5000+ files)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                buildEntries()
            }
            if (dialog.isShowing) {
                entries = loaded
                adapter.notifyDataSetChanged()
                updateTitle()
            }
        }

        btnPasteHere.setOnClickListener {
            dialog.dismiss()
            performPaste()
        }

        btnClearAll.setOnClickListener {
            NetworkClipboard.clear()
            za.kilowatch.ultimatefilemanager.storage.FileClipboard.clear()
            updatePasteFab()
            dialog.dismiss()
        }

        // TV: add yellow-focus D-pad states to action buttons
        if (isTv) {
            val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
            val blackText = getColor(R.color.tv_button_focused_yellow_text)
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

            btnPasteHere.isFocusable = true
            btnPasteHere.isFocusableInTouchMode = true
            btnPasteHere.setOnFocusChangeListener { _, hasFocus ->
                btnPasteHere.backgroundTintList = if (hasFocus) whiteCsl else yellowCsl
                btnPasteHere.setTextColor(blackText)
                btnPasteHere.iconTint = android.content.res.ColorStateList.valueOf(blackText)
            }

            btnClearAll.isFocusable = true
            btnClearAll.isFocusableInTouchMode = true
            btnClearAll.setOnFocusChangeListener { _, hasFocus ->
                btnClearAll.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnClearAll.setTextColor(if (hasFocus) blackText else getColor(R.color.ufm_denied))
            }
        }

        dialog.show()

        // TV: set dialog width for TV screens
        if (isTv) {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.5).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * Downloads a network file to the app's cache directory, then opens it with an ACTION_VIEW
     * intent so the system can launch the appropriate viewer (video player, image viewer, etc.).
     */
    private fun openNetworkFile(file: NetworkFile) {
        val isStreamingSupported = share.type == za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE ||
                                   share.type == za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE ||
                                   share.type == za.kilowatch.ultimatefilemanager.network.ShareType.SMB ||
                                   share.type == za.kilowatch.ultimatefilemanager.network.ShareType.FTP ||
                                   share.type == za.kilowatch.ultimatefilemanager.network.ShareType.SFTP ||
                                   share.type == za.kilowatch.ultimatefilemanager.network.ShareType.SCP ||
                                   share.type == za.kilowatch.ultimatefilemanager.network.ShareType.NFS ||
                                   share.type == za.kilowatch.ultimatefilemanager.network.ShareType.DLNA ||
                                   share.type == za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val isMedia = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(ext) || za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(ext)
        
        if (isStreamingSupported && isMedia) {
            // Check for saved default preference (network context)
            val defaultAction = za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.getDefaultAction(this, ext, isNetwork = true)
            if (defaultAction != za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.Action.ASK) {
                when (defaultAction) {
                    za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.Action.INTERNAL -> {
                        openNetworkFileDirectly(file, forceExternal = false)
                        return
                    }
                    za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.Action.SLIDESHOW -> {
                        val filesToConsider = currentFiles.filter { !it.isDirectory && !it.name.startsWith(".") }
                            .filter { f ->
                                val e = f.name.substringAfterLast('.', "").lowercase()
                                e in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS ||
                                e in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS
                            }
                        startSlideShow(file, filesToConsider)
                        return
                    }
                    za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.Action.PLAYER -> {
                        startUfmPlayer(file)
                        return
                    }
                    za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.Action.EXTERNAL -> {
                        val preferred = za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.getPreferredPackage(this, ext, isNetwork = true)
                        openNetworkFileDirectly(
                            file, forceExternal = true,
                            preferredPackage = preferred,
                            // If EXTERNAL is saved but no specific app yet, capture it now.
                            remember = preferred == null,
                            extension = ext
                        )
                        return
                    }
                    else -> {}
                }
            }
            showNetworkMediaChoiceDialog(file)
            return
        }
        openNetworkFileDirectly(file, forceExternal = false)
    }

    private fun showNetworkMediaChoiceDialog(file: NetworkFile) {
        val dp = { px: Int -> (px * resources.displayMetrics.density).toInt() }
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
        val bgColor = getColor(R.color.tv_dialog_background)
        val textPrimary = getColor(R.color.tv_text_primary)
        val textSecondary = getColor(R.color.tv_text_secondary)

        val dialogBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(bgColor)
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = dialogBg
            setPadding(dp(24), dp(28), dp(24), dp(20))
        }

        val title = android.widget.TextView(this).apply {
            text = getString(R.string.open_with_1)
            textSize = 22f
            setTextColor(textPrimary)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }
        root.addView(title, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })

        val subtitle = android.widget.TextView(this).apply {
            text = file.name
            textSize = 13f
            setTextColor(textSecondary)
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        root.addView(subtitle, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })

        // ── Remember checkbox ──
        val checkbox = android.widget.CheckBox(this).apply {
            text = getString(R.string.remember_my_choice)
            setTextColor(textSecondary)
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(textSecondary)
        }
        root.addView(checkbox, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { 
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(root)
            .setCancelable(true)
            .create()

        fun createChoiceButton(label: String, desc: String, icon: String, gradientColors: IntArray, isFocusedYellow: Boolean, onClick: () -> Unit): android.widget.LinearLayout {
            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                colors = gradientColors
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
            }
            val focusedBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(if (isFocusedYellow) android.graphics.Color.parseColor("#FBBF24") else getColor(R.color.ufm_surface_variant))
            }
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                background = btnBg
                setPadding(dp(16), dp(16), dp(16), dp(16))
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            val iconView = android.widget.TextView(this).apply {
                text = icon
                textSize = 24f
            }
            val textContainer = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
            }
            val labelView = android.widget.TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(textPrimary)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            val descView = android.widget.TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(textSecondary)
            }
            textContainer.addView(labelView)
            textContainer.addView(descView)
            container.addView(iconView)
            container.addView(textContainer, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            
            container.setOnFocusChangeListener { _, hasFocus ->
                container.background = if (hasFocus) focusedBg else btnBg
                if (hasFocus && isFocusedYellow) {
                    labelView.setTextColor(android.graphics.Color.BLACK)
                    descView.setTextColor(android.graphics.Color.DKGRAY)
                } else {
                    labelView.setTextColor(textPrimary)
                    descView.setTextColor(textSecondary)
                }
            }
            return container
        }

        val ext = file.name.substringAfterLast('.', "").lowercase()
        val isVideo = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(ext)
        val isAudio = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(ext)

        // UFM Viewer
        if (!isVideo && !isAudio) {
            root.addView(createChoiceButton(
                label = getString(R.string.ufm_viewer),
                desc = getString(R.string.open_with_builtin_viewer),
                icon = "📂",
                gradientColors = intArrayOf(za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(this), android.graphics.Color.parseColor("#0369A1")),
                isFocusedYellow = isTv
            ) {
                if (checkbox.isChecked) {
                    za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.setDefaultAction(this, ext, true, za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.Action.INTERNAL)
                }
                dialog.dismiss()
                openNetworkFileDirectly(file, forceExternal = false)
            }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }

        // UFM Media Player (STREAMING)
        if (isVideo || isAudio) {
            root.addView(createChoiceButton(
                label = getString(R.string.ufm_media_player),
                desc = getString(R.string.ufm_media_player_stream_desc),
                icon = "▶️",
                gradientColors = intArrayOf(android.graphics.Color.parseColor("#10B981"), android.graphics.Color.parseColor("#059669")),
                isFocusedYellow = isTv
            ) {
                if (checkbox.isChecked) {
                    val ext = file.name.substringAfterLast('.', "").lowercase()
                    za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.setDefaultAction(this, ext, true, za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.Action.PLAYER)
                }
                dialog.dismiss()
                startUfmPlayer(file)
            }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }

        // External App
        root.addView(createChoiceButton(
            label = getString(R.string.external_app),
            desc = getString(R.string.choose_another_app_to_open),
            icon = "🔗",
            gradientColors = intArrayOf(bgColor, bgColor),
            isFocusedYellow = isTv
        ) {
            val remember = checkbox.isChecked
            val ext = file.name.substringAfterLast('.', "").lowercase()
            if (remember) {
                za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.setDefaultAction(this, ext, true, za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.Action.EXTERNAL)
            }
            dialog.dismiss()
            openNetworkFileDirectly(file, forceExternal = true,
                preferredPackage = null,
                remember = remember,
                extension = ext
            )
        })

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(dp(320), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun getActiveNetworkComparator(): Comparator<NetworkFile> {
        val state = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.SortFilterState(
            sortMode = sortMode,
            sortOrder = sortOrder,
            filterType = filterType,
            showHidden = false,
            groupByDate = false,
            activeTags = emptySet()
        )
        return za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.getNetworkFileComparator(
            state = state,
            context = this,
            shareId = share.id,
            directoriesFirst = false
        )
    }

    private fun startUfmPlayer(file: NetworkFile) {
        val comparator = getActiveNetworkComparator()
        val sortedFiles = currentFiles.sortedWith(comparator)
        val playlist = sortedFiles.filter {
            val x = it.name.substringAfterLast('.', "").lowercase()
            za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(x) || za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(x)
        }.map { it.path }
        
        val sizesMap = currentFiles.associate { it.path to it.size }
        
        val intent = android.content.Intent(this, za.kilowatch.ultimatefilemanager.viewer.UFMPlayerActivity::class.java).apply {
            putExtra("shareId", share.id)
            putExtra(EXTRA_REMOTE_PATH, share.remotePath)
            putExtra("shareHost", share.host)
            putExtra("shareUsername", share.username)
            putExtra("shareName", share.name)
            putExtra("provider", share.type.name)
            putExtra("isServerMode", share.isServerMode)
            putExtra("initialPath", file.path)
            putExtra("initialSize", file.size)
            putExtra("sizesMap", java.util.HashMap(sizesMap))
            putStringArrayListExtra("playlist", java.util.ArrayList(playlist))
        }
        startActivity(intent)
    }

    private fun startSlideShow(file: NetworkFile, filesToConsider: List<NetworkFile>) {
        val comparator = getActiveNetworkComparator()
        val sortedFiles = filesToConsider.sortedWith(comparator)
        val playlist = sortedFiles.map { it.path }
        val sizesMap = sortedFiles.associate { it.path to it.size }
        val intent = android.content.Intent(this, za.kilowatch.ultimatefilemanager.viewer.SlideShowActivity::class.java).apply {
            putExtra("shareId", share.id)
            putExtra(EXTRA_REMOTE_PATH, share.remotePath)
            putExtra("shareHost", share.host)
            putExtra("shareName", share.name)
            putExtra("provider", share.type.name)
            putExtra("initialPath", file.path)
            putExtra("initialSize", file.size)
            putExtra("sizesMap", java.util.HashMap(sizesMap))
            putStringArrayListExtra("playlist", java.util.ArrayList(playlist))
        }
        startActivity(intent)
    }

    private fun openNetworkFileDirectly(
        file: NetworkFile,
        forceExternal: Boolean,
        preferredPackage: String? = null,
        remember: Boolean = false,
        extension: String = file.name.substringAfterLast('.', "").lowercase()
    ) {
        // Set up the network save bridge so edits to this file upload back.
        // Also clean up the save-target cache file from any previous session.
        val capturedUploadShare = share
        val capturedUploadPath = file.path
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val isDotConfig = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isDotConfigFile(file.name)
        val saveTarget = java.io.File(cacheDir, file.name)
        if (saveTarget.exists()) saveTarget.delete()
        if (!forceExternal && (ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.TEXT_EXTENSIONS || ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.DAT_EXTENSIONS || isDotConfig)) {
            za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileSaved = { savedFile ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val fis = java.io.FileInputStream(savedFile)
                        fis.use { inp ->
                            when (capturedUploadShare.type) {
                                ShareType.SMB -> SmbShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.FTP -> FtpShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.SFTP, ShareType.SCP -> withContext(Dispatchers.IO) { SshShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) } }
                                ShareType.TV -> TvShareClient.uploadStream(capturedUploadShare, capturedUploadPath, inp, savedFile.length())
                                ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.DROPBOX -> DropboxShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.WEBDAV -> WebDavShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) }
                                ShareType.NFS -> withContext(Dispatchers.IO) { NfsShareClient.openOutputStream(capturedUploadShare, capturedUploadPath).use { out -> inp.copyTo(out) } }
                                ShareType.DLNA -> throw UnsupportedOperationException()
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        } else {
            za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileSaved = null
        }
        val snack = com.google.android.material.snackbar.Snackbar.make(
            findViewById(R.id.main), getString(R.string.opening_filename, file.name), com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
        snack.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isNetworkOpenCacheEnabled = za.kilowatch.ultimatefilemanager.settings.NetworkOpenCachePreferenceManager.isEnabled(this@NetworkBrowserActivity)
                val isInternalViewer = (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.canOpenInternally(ext) || isDotConfig) && !forceExternal

                if (isNetworkOpenCacheEnabled || isInternalViewer) {
                    // Sweep stale cache files (older than 30 minutes) before writing new ones
                    val cutoff = System.currentTimeMillis() - 30 * 60 * 1000L
                    cacheDir.listFiles { f -> f.name.startsWith("ufm_open_") && f.lastModified() < cutoff }
                        ?.forEach { it.delete() }

                    // Use a safe filename (clean slashes/colons)
                    val safeName = file.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                    val cacheFile = java.io.File(cacheDir, "ufm_open_$safeName")

                    // Stream from whichever share type
                    val inStream = when (share.type) {
                        ShareType.SMB -> SmbShareClient.openInputStream(share, file.path)
                        ShareType.FTP -> FtpShareClient.openInputStream(share, file.path)
                        ShareType.TV  -> TvShareClient.openInputStream(share, file.path)
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, file.path)
                                ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, file.path).first
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, file.path).first
                                ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, file.path).first
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, file.path).first
                        ShareType.WEBDAV                      -> WebDavShareClient.openInputStream(share, file.path).first
                        ShareType.NFS                         -> NfsShareClient.openInputStream(share, file.path)
                        ShareType.DLNA                        -> DlnaShareClient.openInputStream(share, file.path)
                    }
                    inStream.use { inp -> cacheFile.outputStream().use { out -> inp.copyTo(out) } }

                    // Resolve MIME type from extension
                    var mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    if (mime == null || mime == "*/*") {
                        mime = when (ext) {
                            "mkv", "mp4", "avi", "mov", "webm", "ts" -> "video/*"
                            "mp3", "flac", "wav", "m4a", "ogg", "aac" -> "audio/*"
                            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif" -> "image/*"
                            "pdf" -> "application/pdf"
                            else -> "*/*"
                        }
                    }

                    // Build a FileProvider URI (required for Android 7+)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@NetworkBrowserActivity,
                        "${packageName}.fileprovider",
                        cacheFile
                    )

                    withContext(Dispatchers.Main) {
                        snack.dismiss()

                        if (!forceExternal) {
                            val ext = file.name.substringAfterLast('.', "").lowercase()
                            val isTextViewable = ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.TEXT_EXTENSIONS ||
                                ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.DAT_EXTENSIONS ||
                                isDotConfig
                            if (isTextViewable) {
                                // Launch text viewer directly with the ORIGINAL filename (not the cache filename)
                                val intent = android.content.Intent(this@NetworkBrowserActivity, za.kilowatch.ultimatefilemanager.viewer.TextViewerActivity::class.java).apply {
                                    putExtra(za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.EXTRA_FILE_PATH, cacheFile.absolutePath)
                                    putExtra(za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.EXTRA_FILE_NAME, file.name)
                                }
                                startActivity(intent)
                                return@withContext
                            }
                            // For non-text files, use the standard router
                            if (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.openFile(
                                    this@NetworkBrowserActivity, cacheFile, isNetwork = true)) return@withContext
                        }

                        // Fall back to external app — try preferred package first
                        // ── Subtitle support: download all companion subtitles to cache ──
                        val isExternalVideo = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(ext)
                        if (isExternalVideo && forceExternal) {
                            val subFiles = SubtitleIntentHelper.findNetworkSubtitles(file.name, currentFiles)
                            if (subFiles.isNotEmpty()) {
                                val cachedSubs = SubtitleIntentHelper.downloadSubtitlesToCache(
                                    cacheDir, subFiles
                                ) { subPath ->
                                    when (share.type) {
                                        ShareType.SMB -> SmbShareClient.openInputStream(share, subPath)
                                        ShareType.FTP -> FtpShareClient.openInputStream(share, subPath)
                                        ShareType.TV  -> TvShareClient.openInputStream(share, subPath)
                                        ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, subPath)
                                        ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, subPath).first
                                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, subPath).first
                                        ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, subPath).first
                                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, subPath).first
                                        ShareType.WEBDAV -> WebDavShareClient.openInputStream(share, subPath).first
                                        ShareType.NFS -> NfsShareClient.openInputStream(share, subPath)
                                        ShareType.DLNA -> DlnaShareClient.openInputStream(share, subPath)
                                    }
                                }
                                // Attach to both the direct intent and the chooser
                                withContext(Dispatchers.Main) {
                                    val externalIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        SubtitleIntentHelper.attachCachedSubtitleExtras(this, packageName, this@NetworkBrowserActivity, cachedSubs)
                                    }
                                    if (preferredPackage != null) {
                                        val pm = packageManager
                                        val isInstalled = try { pm.getPackageInfo(preferredPackage, 0); true }
                                                          catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
                                        if (isInstalled) {
                                            val directIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, mime)
                                                setPackage(preferredPackage)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                SubtitleIntentHelper.attachCachedSubtitleExtras(this, packageName, this@NetworkBrowserActivity, cachedSubs)
                                            }
                                            val resolves = pm.queryIntentActivities(directIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                                            if (resolves.isNotEmpty()) {
                                                startActivity(directIntent)
                                                return@withContext
                                            }
                                        }
                                        za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.clearPreferredPackage(
                                            this@NetworkBrowserActivity, extension, isNetwork = true
                                        )
                                    }
                                    try {
                                        val chooser = android.content.Intent.createChooser(externalIntent, getString(R.string.open_with))
                                        chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        startActivity(chooser)
                                    } catch (e: android.content.ActivityNotFoundException) {
                                        showPremiumSnackbar(getString(R.string.no_app_found_to_open_this_file_type))
                                    }
                                }
                                return@withContext
                            }
                        }
                        // No subtitles (or not a video) — fall through to standard intent
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        // Attempt direct launch if a preferred package is saved
                        if (preferredPackage != null) {
                            val pm = packageManager
                            val isInstalled = try { pm.getPackageInfo(preferredPackage, 0); true }
                                              catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
                            if (isInstalled) {
                                val directIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mime)
                                    setPackage(preferredPackage)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                val resolves = pm.queryIntentActivities(directIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                                if (resolves.isNotEmpty()) {
                                    startActivity(directIntent)
                                    return@withContext
                                }
                            }
                            // Stale pref — clear it and fall through to chooser
                            za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.clearPreferredPackage(
                                this@NetworkBrowserActivity, extension, isNetwork = true
                            )
                        }

                        try {
                            val chooser = android.content.Intent.createChooser(intent, getString(R.string.open_with))
                            chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (remember) {
                                val callbackAction = "${packageName}.CHOSEN_NET_APP_$extension"
                                val callbackIntent = android.content.Intent(callbackAction).apply { setPackage(packageName) }
                                val piFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                                else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                val pi = android.app.PendingIntent.getBroadcast(this@NetworkBrowserActivity, extension.hashCode(), callbackIntent, piFlags)
                                val recv = object : android.content.BroadcastReceiver() {
                                    override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                                        ctx.unregisterReceiver(this)
                                        @Suppress("DEPRECATION")
                                        val component = intent.getParcelableExtra<android.content.ComponentName>(android.content.Intent.EXTRA_CHOSEN_COMPONENT)
                                        if (component != null) {
                                            za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.setPreferredPackage(
                                                ctx, extension, isNetwork = true, component.packageName
                                            )
                                        }
                                    }
                                }
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    registerReceiver(recv, android.content.IntentFilter(callbackAction), android.content.Context.RECEIVER_NOT_EXPORTED)
                                } else {
                                    @Suppress("UnspecifiedRegisterReceiverFlag")
                                    registerReceiver(recv, android.content.IntentFilter(callbackAction))
                                }
                                chooser.putExtra(android.content.Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, pi.intentSender)
                            }
                            startActivity(chooser)
                        } catch (e: android.content.ActivityNotFoundException) {
                            showPremiumSnackbar(getString(R.string.no_app_found_to_open_this_file_type))
                        }
                    }
                } else {
                    // Direct Stream — no local cache
                    var mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    if (mime == null || mime == "*/*") {
                        mime = when (ext) {
                            "mkv", "mp4", "avi", "mov", "webm", "ts" -> "video/*"
                            "mp3", "flac", "wav", "m4a", "ogg", "aac" -> "audio/*"
                            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif" -> "image/*"
                            "pdf" -> "application/pdf"
                            else -> "*/*"
                        }
                    }

                    // Share types with true random-access APIs get a seekable local HTTP proxy URL.
                    // VLC/MX Player send HTTP Range requests which the proxy translates into seeks.
                    // This completely bypasses FUSE/DocumentsProvider (which has a ~13-slot system limit).
                    //
                    // The proxy is ONLY used for Audio and Video formats. Other formats (APKs, ZIPs, Docs)
                    // MUST use standard content:// URIs because the Android OS (e.g. PackageInstaller) 
                    // rejects http:// URIs.
                    val isMedia = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(ext) || 
                                  za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(ext)
                                  
                    val supportsProxy = isMedia && (share.type == ShareType.SMB ||
                        share.type == ShareType.FTP ||
                        share.type == ShareType.SFTP ||
                        share.type == ShareType.SCP ||
                        share.type == ShareType.GOOGLE_DRIVE ||
                        share.type == ShareType.ONEDRIVE ||
                        share.type == ShareType.DROPBOX ||
                        share.type == ShareType.AWS_S3 ||
                        share.type == ShareType.IDRIVE_E2 ||
                        share.type == ShareType.WEBDAV ||
                        share.type == ShareType.NFS)

                    if (supportsProxy) {
                        val proxyUrl = NetworkHttpProxyServer.register(share, file.path, mime, file.size)
                        val mediaTypeLabel = if (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(ext)) "video" else "audio"
                        val streamTitle = "Streaming $mediaTypeLabel file"
                        val streamText  = "Streaming ${file.name} to external player"
                        za.kilowatch.ultimatefilemanager.util.TransferService.start(this@NetworkBrowserActivity, streamTitle, streamText)
                        android.util.Log.d("NetworkBrowser", "Streaming via HTTP proxy for: ${file.name}")

                        // ── Subtitle support ──
                        val isExternalVideo = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(ext)
                        val proxySubtitleUris: List<android.net.Uri> = if (isExternalVideo && forceExternal) {
                            SubtitleIntentHelper.findNetworkSubtitles(file.name, currentFiles).map { subFile ->
                                val subExt = subFile.name.substringAfterLast('.', "").lowercase()
                                val subMime = when (subExt) {
                                    "vtt" -> "text/vtt"
                                    "ass", "ssa" -> "text/x-ass"
                                    else -> "application/x-subrip"
                                }
                                val subProxyUrl = NetworkHttpProxyServer.register(share, subFile.path, subMime, subFile.size)
                                android.net.Uri.parse(subProxyUrl)
                            }
                        } else emptyList()

                        withContext(Dispatchers.Main) {
                            snack.dismiss()
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(android.net.Uri.parse(proxyUrl), mime)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (proxySubtitleUris.isNotEmpty()) {
                                    val subNames  = currentFiles.filter { f ->
                                        val e = f.name.substringAfterLast('.', "").lowercase()
                                        e in SubtitleIntentHelper.SUBTITLE_EXTENSIONS &&
                                        f.name.substringBeforeLast('.').let { b ->
                                            val vb = file.name.substringBeforeLast('.')
                                            b.equals(vb, ignoreCase = true) || b.startsWith("$vb.", ignoreCase = true)
                                        }
                                    }.sortedBy { it.name.lowercase() }
                                    val names  = subNames.map { it.name.substringBeforeLast('.') }
                                    val fnames = subNames.map { it.name }
                                    SubtitleIntentHelper.attachSubtitleExtras(this, proxySubtitleUris, names, fnames)
                                }
                            }

                            // Attempt direct launch if a preferred package is saved
                            if (preferredPackage != null) {
                                val pm = packageManager
                                val isInstalled = try { pm.getPackageInfo(preferredPackage, 0); true }
                                                  catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
                                if (isInstalled) {
                                    val directIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(android.net.Uri.parse(proxyUrl), mime)
                                        setPackage(preferredPackage)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        if (proxySubtitleUris.isNotEmpty()) {
                                            val subNames  = currentFiles.filter { f ->
                                                val e = f.name.substringAfterLast('.', "").lowercase()
                                                e in SubtitleIntentHelper.SUBTITLE_EXTENSIONS &&
                                                f.name.substringBeforeLast('.').let { b ->
                                                    val vb = file.name.substringBeforeLast('.')
                                                    b.equals(vb, ignoreCase = true) || b.startsWith("$vb.", ignoreCase = true)
                                                }
                                            }.sortedBy { it.name.lowercase() }
                                            val names  = subNames.map { it.name.substringBeforeLast('.') }
                                            val fnames = subNames.map { it.name }
                                            SubtitleIntentHelper.attachSubtitleExtras(this, proxySubtitleUris, names, fnames)
                                        }
                                    }
                                    val resolves = pm.queryIntentActivities(directIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                                    if (resolves.isNotEmpty()) { startActivity(directIntent); return@withContext }
                                }
                                za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.clearPreferredPackage(
                                    this@NetworkBrowserActivity, extension, isNetwork = true
                                )
                            }

                            try {
                                val chooser = android.content.Intent.createChooser(intent, getString(R.string.open_with))
                                if (remember) {
                                    val callbackAction = "${packageName}.CHOSEN_NET_APP_$extension"
                                    val callbackIntent = android.content.Intent(callbackAction).apply { setPackage(packageName) }
                                    val piFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                                    else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                    val pi = android.app.PendingIntent.getBroadcast(this@NetworkBrowserActivity, extension.hashCode(), callbackIntent, piFlags)
                                    val recv = object : android.content.BroadcastReceiver() {
                                        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                                            ctx.unregisterReceiver(this)
                                            @Suppress("DEPRECATION")
                                            val component = intent.getParcelableExtra<android.content.ComponentName>(android.content.Intent.EXTRA_CHOSEN_COMPONENT)
                                            if (component != null) {
                                                za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.setPreferredPackage(
                                                    ctx, extension, isNetwork = true, component.packageName
                                                )
                                            }
                                        }
                                    }
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        registerReceiver(recv, android.content.IntentFilter(callbackAction), android.content.Context.RECEIVER_NOT_EXPORTED)
                                    } else {
                                        @Suppress("UnspecifiedRegisterReceiverFlag")
                                        registerReceiver(recv, android.content.IntentFilter(callbackAction))
                                    }
                                    chooser.putExtra(android.content.Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, pi.intentSender)
                                }
                                startActivity(chooser)
                            } catch (e: android.content.ActivityNotFoundException) {
                                showPremiumSnackbar(getString(R.string.no_app_found_to_open_this_file_type))
                            }
                        }
                    } else {
                        // FTP / TV: sequential pipe via UfmDocumentsProvider (no seeking)
                        // ── Subtitle support: always download companion subtitles to cache ──
                        val isExternalVideo = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(ext)
                        val cachedSubsFtpTv: List<java.io.File> = if (isExternalVideo && forceExternal) {
                            val subFiles = SubtitleIntentHelper.findNetworkSubtitles(file.name, currentFiles)
                            SubtitleIntentHelper.downloadSubtitlesToCache(cacheDir, subFiles) { subPath ->
                                when (share.type) {
                                    ShareType.FTP  -> FtpShareClient.openInputStream(share, subPath)
                                    ShareType.TV   -> TvShareClient.openInputStream(share, subPath)
                                    ShareType.DLNA -> DlnaShareClient.openInputStream(share, subPath)
                                    else -> throw UnsupportedOperationException("Subtitle download not supported for ${share.type}")
                                }
                            }
                        } else emptyList()

                        val cleanPath = file.path.removePrefix("/")
                        val docId = "${share.docIdPrefix}${cleanPath}"
                        val uri = android.provider.DocumentsContract.buildDocumentUri(
                            "${packageName}.documents",
                            docId
                        )

                        withContext(Dispatchers.Main) {
                            snack.dismiss()

                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (cachedSubsFtpTv.isNotEmpty()) {
                                    SubtitleIntentHelper.attachCachedSubtitleExtras(this, packageName, this@NetworkBrowserActivity, cachedSubsFtpTv)
                                }
                            }

                            // Attempt direct launch if a preferred package is saved
                            if (preferredPackage != null) {
                                val pm = packageManager
                                val isInstalled = try { pm.getPackageInfo(preferredPackage, 0); true }
                                                  catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
                                if (isInstalled) {
                                    val directIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        setPackage(preferredPackage)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    val resolves = pm.queryIntentActivities(directIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                                    if (resolves.isNotEmpty()) { startActivity(directIntent); return@withContext }
                                }
                                za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.clearPreferredPackage(
                                    this@NetworkBrowserActivity, extension, isNetwork = true
                                )
                            }

                            try {
                                val chooser = android.content.Intent.createChooser(intent, getString(R.string.open_with))
                                chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                if (remember) {
                                    val callbackAction = "${packageName}.CHOSEN_NET_APP_$extension"
                                    val callbackIntent = android.content.Intent(callbackAction).apply { setPackage(packageName) }
                                    val piFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                                    else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                    val pi = android.app.PendingIntent.getBroadcast(this@NetworkBrowserActivity, extension.hashCode(), callbackIntent, piFlags)
                                    val recv = object : android.content.BroadcastReceiver() {
                                        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                                            ctx.unregisterReceiver(this)
                                            @Suppress("DEPRECATION")
                                            val component = intent.getParcelableExtra<android.content.ComponentName>(android.content.Intent.EXTRA_CHOSEN_COMPONENT)
                                            if (component != null) {
                                                za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.setPreferredPackage(
                                                    ctx, extension, isNetwork = true, component.packageName
                                                )
                                            }
                                        }
                                    }
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        registerReceiver(recv, android.content.IntentFilter(callbackAction), android.content.Context.RECEIVER_NOT_EXPORTED)
                                    } else {
                                        @Suppress("UnspecifiedRegisterReceiverFlag")
                                        registerReceiver(recv, android.content.IntentFilter(callbackAction))
                                    }
                                    chooser.putExtra(android.content.Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, pi.intentSender)
                                }
                                startActivity(chooser)
                            } catch (e: android.content.ActivityNotFoundException) {
                                showPremiumSnackbar(getString(R.string.no_app_found_to_open_this_file_type))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkBrowser", "openNetworkFile failed: ", e)
                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    showPremiumSnackbar(getString(R.string.failed_to_open_file_emessage))
                }
            }
        }
    }

    /**
     * Downloads selected image files to a local temp cache directory and launches ImageCompressActivity.
     */
    private fun downloadNetworkImagesAndCompress(files: List<NetworkFile>) {
        val snack = Snackbar.make(findViewById(R.id.main), getString(R.string.fetching_filename, files.first().name), Snackbar.LENGTH_INDEFINITE)
        snack.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = java.io.File(cacheDir, "img_compress_${System.currentTimeMillis()}")
                tempDir.mkdirs()
                val localPaths = java.util.ArrayList<String>()

                for (nf in files) {
                    val tempFile = java.io.File(tempDir, nf.name)
                    val inp = when (share.type) {
                        ShareType.SMB -> SmbShareClient.openInputStream(share, nf.path)
                        ShareType.FTP -> FtpShareClient.openInputStream(share, nf.path)
                        ShareType.TV  -> TvShareClient.openInputStream(share, nf.path)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, nf.path)
                        ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, nf.path).first
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, nf.path).first
                        ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, nf.path).first
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, nf.path).first
                        ShareType.WEBDAV -> WebDavShareClient.openInputStream(share, nf.path).first
                        ShareType.NFS -> NfsShareClient.openInputStream(share, nf.path)
                        ShareType.DLNA -> DlnaShareClient.openInputStream(share, nf.path)
                        else -> null
                    }
                    if (inp != null) {
                        inp.use { input ->
                            java.io.FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                        }
                        localPaths.add(tempFile.absolutePath)
                    }
                }

                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    if (localPaths.isNotEmpty()) {
                        startActivity(android.content.Intent(this@NetworkBrowserActivity, za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity::class.java).apply {
                            putStringArrayListExtra(za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity.EXTRA_FILE_PATHS, localPaths)
                            putExtra(za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity.EXTRA_SOURCE_SHARE_ID, share.id)
                            putExtra(za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity.EXTRA_NETWORK_SHARE_ID, share.id)
                            putExtra(za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity.EXTRA_NETWORK_PATH, currentPath)
                        })
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    showPremiumSnackbar(getString(R.string.compress_image_error, files.first().name, e.message ?: ""))
                }
            }
        }
    }

    /**
     * Downloads selected image files to a local temp cache directory and launches GifCreatorActivity.
     */
    fun downloadNetworkImagesAndCreateGif(files: List<NetworkFile>) {
        if (files.isEmpty()) return
        val snack = Snackbar.make(findViewById(R.id.main), getString(R.string.fetching_filename, files.first().name), Snackbar.LENGTH_INDEFINITE)
        snack.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = java.io.File(cacheDir, "gif_src_${System.currentTimeMillis()}")
                tempDir.mkdirs()
                val localPaths = java.util.ArrayList<String>()

                for (nf in files) {
                    val tempFile = java.io.File(tempDir, nf.name)
                    val inp = when (share.type) {
                        ShareType.SMB -> SmbShareClient.openInputStream(share, nf.path)
                        ShareType.FTP -> FtpShareClient.openInputStream(share, nf.path)
                        ShareType.TV  -> TvShareClient.openInputStream(share, nf.path)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, nf.path)
                        ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, nf.path).first
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, nf.path).first
                        ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, nf.path).first
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, nf.path).first
                        ShareType.WEBDAV -> WebDavShareClient.openInputStream(share, nf.path).first
                        ShareType.NFS -> NfsShareClient.openInputStream(share, nf.path)
                        ShareType.DLNA -> DlnaShareClient.openInputStream(share, nf.path)
                        else -> null
                    }
                    if (inp != null) {
                        inp.use { input ->
                            java.io.FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                        }
                        localPaths.add(tempFile.absolutePath)
                    }
                }

                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    if (localPaths.isNotEmpty()) {
                        startActivity(android.content.Intent(this@NetworkBrowserActivity, za.kilowatch.ultimatefilemanager.viewer.GifCreatorActivity::class.java).apply {
                            putStringArrayListExtra(za.kilowatch.ultimatefilemanager.viewer.GifCreatorActivity.EXTRA_FILE_PATHS, localPaths)
                            putExtra(za.kilowatch.ultimatefilemanager.viewer.GifCreatorActivity.EXTRA_SOURCE_SHARE_ID, share.id)
                            putExtra(za.kilowatch.ultimatefilemanager.viewer.GifCreatorActivity.EXTRA_NETWORK_SHARE_ID, share.id)
                            putExtra(za.kilowatch.ultimatefilemanager.viewer.GifCreatorActivity.EXTRA_NETWORK_PATH, currentPath)
                        })
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    showPremiumSnackbar(getString(R.string.compress_image_error, files.first().name, e.message ?: ""))
                }
            }
        }
    }

    private fun setNetworkWallpaper(networkFile: NetworkFile, flag: Int) {
        za.kilowatch.ultimatefilemanager.util.WallpaperHelper.showConfirmDialog(
            this,
            networkFile.name,
            flag
        ) {
            val snack = Snackbar.make(findViewById(R.id.main), getString(R.string.fetching_filename, networkFile.name), Snackbar.LENGTH_INDEFINITE)
            snack.show()
            lifecycleScope.launch(Dispatchers.IO) {
                var tempFile: java.io.File? = null
                var success = false
                try {
                    val tempDir = java.io.File(cacheDir, "wallpaper_temp")
                    tempDir.mkdirs()
                    tempFile = java.io.File(tempDir, "${System.currentTimeMillis()}_${networkFile.name}")
                    val inp = when (share.type) {
                        ShareType.SMB -> SmbShareClient.openInputStream(share, networkFile.path)
                        ShareType.FTP -> FtpShareClient.openInputStream(share, networkFile.path)
                        ShareType.TV  -> TvShareClient.openInputStream(share, networkFile.path)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, networkFile.path)
                        ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, networkFile.path).first
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, networkFile.path).first
                        ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, networkFile.path).first
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, networkFile.path).first
                        ShareType.WEBDAV -> WebDavShareClient.openInputStream(share, networkFile.path).first
                        ShareType.NFS -> NfsShareClient.openInputStream(share, networkFile.path)
                        ShareType.DLNA -> DlnaShareClient.openInputStream(share, networkFile.path)
                        else -> null
                    }
                    if (inp != null) {
                        inp.use { input ->
                            java.io.FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        }
                        success = za.kilowatch.ultimatefilemanager.util.WallpaperHelper.setWallpaper(this@NetworkBrowserActivity, tempFile, flag)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    tempFile?.delete()
                }

                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    fileAdapter.exitSelectionMode()
                    val isHome = flag == android.app.WallpaperManager.FLAG_SYSTEM
                    val msgRes = if (success) {
                        if (isHome) R.string.toast_wallpaper_set_home_success else R.string.toast_wallpaper_set_lock_success
                    } else {
                        R.string.toast_wallpaper_set_failed
                    }
                    showPremiumSnackbar(getString(msgRes))
                }
            }
        }
    }

    /**
     * Downloads an SMB/FTP file to the local cache and returns the path to the Caller.
     */
    private fun downloadAndReturnFile(file: NetworkFile) {
        val snack = Snackbar.make(findViewById(R.id.main), getString(R.string.fetching_filename, file.name), Snackbar.LENGTH_INDEFINITE)
        snack.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = java.io.File(cacheDir, "unified_picker")
                tempDir.mkdirs()
                val tempFile = java.io.File(tempDir, file.name)
                
                val inp = when (share.type) {
                    ShareType.SMB -> SmbShareClient.openInputStream(share, file.path)
                    ShareType.FTP -> FtpShareClient.openInputStream(share, file.path)
                    ShareType.TV  -> TvShareClient.openInputStream(share, file.path)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, file.path)
                            ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, file.path).first
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, file.path).first
                            ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, file.path).first
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, file.path).first
                    ShareType.WEBDAV                      -> WebDavShareClient.openInputStream(share, file.path).first
                    ShareType.NFS                         -> NfsShareClient.openInputStream(share, file.path)
                    ShareType.DLNA                        -> DlnaShareClient.openInputStream(share, file.path)
                }

                inp.use { input ->
                    java.io.FileOutputStream(tempFile).use { out ->
                        input.copyTo(out)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    val resultIntent = android.content.Intent().apply {
                        putExtra(FileBrowserActivity.RESULT_SELECTED_PATH, tempFile.absolutePath)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            } catch(e: Exception) {
                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    showPremiumSnackbar(getString(R.string.failed_to_download_file_emessage, e.message ?: "Unknown error"))
                }
            }
        }
    }

    // ── Sideload APK / XAPK ──────────────────────────────────────────────────

    /**
     * Launches the global StorageBrowserActivity in picker mode, filtered
     * to only show files matching [extensions] (e.g. "apk" or "xapk,apks").
     */
    private fun launchFilePicker(extensions: String) {
        pendingSideloadType = extensions.split(",").first().trim() // "apk" or "xapk"

        val intent = android.content.Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
            putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, extensions)
        }
        sideloadPickerLauncher.launch(intent)
    }

    /**
     * Performs the full sideload flow:
     * 1. Upload the local file to the TV's ufm-temp directory.
     * 2. Trigger the appropriate install endpoint.
     * 3. Show progress & results via Snackbar.
     */
    private fun performSideloadInstall(localFile: File, type: String) {
        val snack = Snackbar.make(
            findViewById(R.id.main),
            getString(R.string.uploading_localfilename_to_tv, localFile.name),
            Snackbar.LENGTH_INDEFINITE
        )
        snack.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Ensure remote temp directory exists
                val tempDir = "%CACHE%/ufm-temp"
                runCatching { TvShareClient.mkdir(share, tempDir) }

                // 2. Upload file to TV temp
                val remotePath = "$tempDir/${localFile.name}"
                localFile.inputStream().use { inp ->
                    TvShareClient.uploadStream(share, remotePath, inp, localFile.length())
                }

                withContext(Dispatchers.Main) {
                    snack.setText(getString(R.string.installing_localfilename_on_tv, localFile.name))
                }

                // 3. Trigger install
                if (type == "apk") {
                    TvShareClient.installApk(share, remotePath)
                    withContext(Dispatchers.Main) {
                        snack.dismiss()
                        showPremiumSnackbar(getString(R.string.apk_install_triggered_on_tv))
                    }
                } else {
                                val useTmp = share.type != ShareType.AWS_S3 && share.type != ShareType.IDRIVE_E2 && share.type != ShareType.WEBDAV && share.type != ShareType.NFS
                    // XAPK — start job and poll status
                    val jobId = TvShareClient.installXapk(share, remotePath)
                    if (jobId.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            snack.dismiss()
                            showPremiumSnackbar(getString(R.string.failed_to_start_xapk_install_on_tv))
                        }
                        return@launch
                    }

                    // Poll status
                    var done = false
                    while (!done) {
                        kotlinx.coroutines.delay(1000)
                        val status = TvShareClient.getXapkInstallStatus(share, jobId)
                        val state = status.optString("status", "")
                        val current = status.optInt("current", 0)
                        val total = status.optInt("total", 0)
                        val currentFile = status.optString("currentFile", "")

                        when (state) {
                            "extracting" -> {
                                withContext(Dispatchers.Main) {
                                    snack.setText(getString(R.string.extracting_currentfile_currenttotal, currentFile, current, total))
                                }
                            }
                            "installing" -> {
                                withContext(Dispatchers.Main) {
                                    snack.setText(getString(R.string.installing_split_apks_on_tv))
                                }
                            }
                            "awaiting_os" -> {
                                withContext(Dispatchers.Main) {
                                    snack.dismiss()
                                    showPremiumSnackbar(getString(R.string.xapk_install_triggered_on_tv_check_the_tv_screen))
                                }
                                done = true
                            }
                            "error" -> {
                                val error = status.optString("error", "Unknown error")
                                withContext(Dispatchers.Main) {
                                    snack.dismiss()
                                    showPremiumSnackbar(getString(R.string.xapk_install_failed_error, error))
                                }
                                done = true
                            }
                            else -> {
                                done = true
                                withContext(Dispatchers.Main) {
                                    snack.dismiss()
                                    showPremiumSnackbar(getString(R.string.xapk_install_status_state, state))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkBrowser", "Sideload install failed", e)
                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    showPremiumSnackbar(getString(R.string.sideload_failed_emessage, e.message ?: "Unknown error"))
                }
            }
        }
    }


    // ── TV Screenshot ─────────────────────────────────────────────────────────

    private var screenshotJob: kotlinx.coroutines.Job? = null

    private fun getUseRemoteLabel(): String {
        val deviceId = intent.getStringExtra(EXTRA_PAIRED_DEVICE_ID) ?: ""
        val enabled = RemoteTransportPrefs(this).isRemoteEnabled(deviceId)
        return getString(if (enabled) R.string.use_remote_enabled else R.string.use_remote_disabled)
    }

    private fun getUseRemoteIcon(): Int {
        return R.drawable.ic_tv_remote
    }

    private fun performTvScreenshot() {
        // First check if ADB is enabled on the TV via HTTP
        lifecycleScope.launch {
            val adbEnabled = withContext(Dispatchers.IO) {
                TvShareClient.isAdbEnabled(share)
            }
            android.util.Log.d("GoRoScreen", "performTvScreenshot() adbEnabled=$adbEnabled")

            if (!adbEnabled) {
                // Show dialog prompting user to enable USB debugging
                showEnableAdbDialog()
            } else {
                // ADB is enabled — proceed with screenshot
                startAdbScreenshot()
            }
        }
    }

    private fun showEnableAdbDialog() {
        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(R.string.adb_required_title)
            .setMessage(R.string.adb_required_to_take_screenshots)
            .setIcon(R.drawable.ic_screenshot)
            .setPositiveButton(R.string.action_done, null)
            .show()
            .also { applyDarkDialogStyle(it) }
    }

    private fun startAdbScreenshot() {
        val adbManager = AdbManager.getInstance(this)

        // Build a status dialog with Cancel
        val statusText = TextView(this).apply {
            text = getString(R.string.adb_terminal_status_connecting)
            textSize = 14f
            setPadding(64, 32, 64, 32)
        }
        val dialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(R.string.take_screenshot)
            .setIcon(R.drawable.ic_screenshot)
            .setView(statusText)
            .setCancelable(false)
            .setNegativeButton(R.string.delete_cancel) { d, _ ->
                screenshotJob?.cancel()
                adbManager.disconnectExplicit()
                d.dismiss()
            }
            .show()
            .also { applyDarkDialogStyle(it) }

        val host = share.host
        val port = 5555
        android.util.Log.d("GoRoScreen", "startAdbScreenshot() connecting to $host:$port")

        screenshotJob = lifecycleScope.launch {
            try {
                // 1. Connect via ADB
                val connected = adbManager.connect(host, port)
                if (!connected) {
                    dialog.dismiss()
                    val reason = adbManager.lastError ?: ""
                    showPremiumSnackbar(getString(R.string.adb_connection_denied_or_failed) + " ($host:$port) $reason")
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    statusText.setText(R.string.connected_capturing_screen)
                }

                // 2. Run screencap via ADB exec (shell user has framebuffer access)
                val pngBytes = withContext(Dispatchers.IO) {
                    android.util.Log.d("GoRoScreen", "startAdbScreenshot() opening exec:screencap -p")
                    val stream = adbManager.openExec("screencap -p")
                    if (stream == null) {
                        android.util.Log.e("GoRoScreen", "startAdbScreenshot() openExec returned null")
                        null
                    } else {
                        val baos = java.io.ByteArrayOutputStream()
                        try {
                            val inputStream = stream.openInputStream()
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                baos.write(buffer, 0, bytesRead)
                            }
                        } catch (e: Exception) {
                            // Stream closed = command finished, which is expected
                            android.util.Log.d("GoRoScreen", "startAdbScreenshot() stream ended: ${e.message}")
                        }
                        android.util.Log.d("GoRoScreen", "startAdbScreenshot() captured ${baos.size()} bytes")
                        if (baos.size() > 0) baos.toByteArray() else null
                    }
                }

                // 3. Disconnect ADB immediately — we only needed it for the screenshot
                withContext(Dispatchers.IO) {
                    adbManager.disconnectExplicit()
                }

                if (pngBytes == null || pngBytes.isEmpty()) {
                    dialog.dismiss()
                    showPremiumSnackbar(getString(R.string.screenshot_failed_no_data_received))
                    return@launch
                }

                // 4. Save to Pictures/Screenshots/
                withContext(Dispatchers.Main) {
                    statusText.setText(R.string.saving_screenshot)
                }

                val savedFileName = withContext(Dispatchers.IO) {
                    val screenshotsDir = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES
                        ),
                        "Screenshots"
                    )
                    if (!screenshotsDir.exists()) screenshotsDir.mkdirs()

                    val timestamp = java.text.SimpleDateFormat(
                        "yyyyMMdd_HHmmss", java.util.Locale.US
                    ).format(java.util.Date())
                    val fileName = "TV_Screenshot_$timestamp.png"
                    val outputFile = File(screenshotsDir, fileName)

                    FileOutputStream(outputFile).use { out ->
                        out.write(pngBytes)
                    }

                    // Notify MediaStore so it appears in gallery
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.Images.Media.DATA, outputFile.absolutePath)
                    }
                    contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                    )
                    android.util.Log.d("GoRoScreen", "startAdbScreenshot() saved: ${outputFile.absolutePath} (${pngBytes.size} bytes)")
                    fileName
                }

                dialog.dismiss()
                showPremiumSnackbar(getString(R.string.screenshot_saved_savedfilename, savedFileName))

            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("GoRoScreen", "startAdbScreenshot() cancelled")
                withContext(kotlinx.coroutines.NonCancellable) {
                    adbManager.disconnectExplicit()
                }
                dialog.dismiss()
            } catch (e: Exception) {
                android.util.Log.e("GoRoScreen", "startAdbScreenshot() error: ${e.message}", e)
                withContext(Dispatchers.IO) { adbManager.disconnectExplicit() }
                dialog.dismiss()
                showPremiumSnackbar(getString(R.string.screenshot_failed_emessage, e.message ?: "Unknown error"))
            }
        }
    }

    // ── TV Screen Record ───────────────────────────────────────────────────────

    private fun performTvRecordScreen() {
        lifecycleScope.launch {
            val adbEnabled = withContext(Dispatchers.IO) {
                TvShareClient.isAdbEnabled(share)
            }
            android.util.Log.d("GoRoScreen", "performTvRecordScreen() adbEnabled=$adbEnabled")

            if (!adbEnabled) {
                showEnableAdbDialog()
            } else {
                showScreenRecordingDialog()
            }
        }
    }

    private fun showScreenRecordingDialog() {
        val adbManager = AdbManager.getInstance(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_record_screen, null)
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this).apply {
            setContentView(sheetView)
            setCancelable(false)
        }

        val cardMicToggle = sheetView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardMicToggle)
        val imgMicIcon = sheetView.findViewById<ImageView>(R.id.imgMicIcon)
        val tvMicDesc = sheetView.findViewById<TextView>(R.id.tvMicDesc)
        val switchMicAudio = sheetView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMicAudio)

        val layoutConfigContainer = sheetView.findViewById<View>(R.id.layoutConfigContainer)
        val cardActiveRecording = sheetView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardActiveRecording)
        val tvCountdownClock = sheetView.findViewById<TextView>(R.id.tvCountdownClock)
        val tvRecordingStatusDetail = sheetView.findViewById<TextView>(R.id.tvRecordingStatusDetail)

        val chip1Min = sheetView.findViewById<TextView>(R.id.chip1Min)
        val chip3Min = sheetView.findViewById<TextView>(R.id.chip3Min)
        val chip5Min = sheetView.findViewById<TextView>(R.id.chip5Min)
        val chip10Min = sheetView.findViewById<TextView>(R.id.chip10Min)

        val btnExit = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnExit)
        val btnStartStop = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStartStop)

        var isAudioEnabled = true
        fun updateMicUI() {
            if (isAudioEnabled) {
                imgMicIcon.setImageResource(R.drawable.ic_mic)
                imgMicIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_primary))
                tvMicDesc.text = getString(R.string.record_screen_audio_enabled)
                switchMicAudio.isChecked = true
            } else {
                imgMicIcon.setImageResource(R.drawable.ic_mic_off)
                imgMicIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_text_secondary))
                tvMicDesc.text = getString(R.string.record_screen_audio_disabled)
                switchMicAudio.isChecked = false
            }
        }

        cardMicToggle.setOnClickListener {
            isAudioEnabled = !isAudioEnabled
            updateMicUI()
        }
        switchMicAudio.setOnCheckedChangeListener { _, isChecked ->
            if (isAudioEnabled != isChecked) {
                isAudioEnabled = isChecked
                updateMicUI()
            }
        }

        var selectedDurationSeconds = 180
        fun selectDurationChip(selectedChip: TextView, duration: Int) {
            selectedDurationSeconds = duration
            val chips = listOf(chip1Min, chip3Min, chip5Min, chip10Min)
            for (c in chips) {
                if (c == selectedChip) {
                    c.setBackgroundResource(R.drawable.bg_chip_selected)
                    c.setTextColor(getColor(R.color.white))
                    c.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    c.setBackgroundResource(R.drawable.bg_chip_unselected)
                    c.setTextColor(getColor(R.color.ufm_text_primary))
                    c.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
            }
        }

        // Pre-select 3 Min by default
        selectDurationChip(chip3Min, 180)

        chip1Min.setOnClickListener { selectDurationChip(chip1Min, 60) }
        chip3Min.setOnClickListener { selectDurationChip(chip3Min, 180) }
        chip5Min.setOnClickListener { selectDurationChip(chip5Min, 300) }
        chip10Min.setOnClickListener { selectDurationChip(chip10Min, 600) }

        var isRecording = false
        var isStopping = false
        var recordingExecJob: kotlinx.coroutines.Job? = null
        var timerJob: kotlinx.coroutines.Job? = null
        var screenRecordPid: Int? = null

        fun stopAndSaveRecording(userInitiated: Boolean) {
            if (isStopping) return
            isStopping = true
            isRecording = false
            timerJob?.cancel()

            btnStartStop.isEnabled = false
            btnStartStop.text = getString(R.string.record_screen_stop)
            tvRecordingStatusDetail.text = getString(R.string.record_screen_saving)

            lifecycleScope.launch {
                var savedFile: File? = null
                try {
                    if (userInitiated) {
                        screenRecordPid?.let { pid ->
                            withContext(Dispatchers.IO) {
                                try {
                                    adbManager.sendShellCommandSync("kill -2 $pid")
                                } catch (e: Exception) {
                                    android.util.Log.w("GoRoScreen", "kill -2 $pid failed: ${e.message}")
                                }
                            }
                        }
                        delay(2000)
                    }

                    // Wait for the recording coroutine to finish
                    try {
                        kotlinx.coroutines.withTimeout(10_000L) {
                            recordingExecJob?.join()
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        android.util.Log.e("GoRoScreen", "screenrecord did not exit within 10s, force-cancelling")
                        recordingExecJob?.cancel()
                    }

                    savedFile = pullRecordingFile(adbManager) { pct ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            tvRecordingStatusDetail.text = getString(R.string.record_screen_saving) + " $pct%"
                        }
                    }

                    withContext(Dispatchers.IO) {
                        adbManager.sendShellCommandSync("rm -f /data/local/tmp/ufm_record.mp4")
                        adbManager.disconnectExplicit()
                    }

                } catch (e: kotlinx.coroutines.CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        adbManager.disconnectExplicit()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GoRoScreen", "stopAndSaveRecording error: ${e.message}", e)
                    withContext(Dispatchers.IO) { adbManager.disconnectExplicit() }
                } finally {
                    withContext(Dispatchers.Main) {
                        if (bottomSheet.isShowing) {
                            bottomSheet.dismiss()
                        }
                        if (savedFile != null && savedFile.length() > 0) {
                            showPremiumSnackbar(getString(R.string.record_screen_saved, "Movies/Recordings/${savedFile.name}"))
                        } else {
                            showPremiumSnackbar(getString(R.string.record_screen_failed, "No video data received"))
                        }
                    }
                }
            }
        }

        btnExit.setOnClickListener {
            if (!isRecording && !isStopping) {
                lifecycleScope.launch(Dispatchers.IO) {
                    adbManager.disconnectExplicit()
                }
                bottomSheet.dismiss()
            }
        }

        btnStartStop.setOnClickListener {
            if (!isRecording && !isStopping) {
                btnStartStop.isEnabled = false
                btnStartStop.text = getString(R.string.adb_terminal_status_connecting)

                lifecycleScope.launch {
                    val host = share.host
                    val port = 5555
                    val connected = withContext(Dispatchers.IO) {
                        adbManager.connect(host, port)
                    }

                    if (!connected) {
                        btnStartStop.isEnabled = true
                        btnStartStop.text = getString(R.string.record_screen_start)
                        val reason = adbManager.lastError ?: ""
                        showPremiumSnackbar(getString(R.string.adb_connection_denied_or_failed) + " ($host:$port) $reason")
                        return@launch
                    }

                    isRecording = true
                    btnStartStop.isEnabled = true
                    layoutConfigContainer.visibility = View.GONE
                    cardActiveRecording.visibility = View.VISIBLE
                    btnExit.visibility = View.GONE

                    btnStartStop.text = getString(R.string.record_screen_stop)
                    btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_error))

                    // Clean slate: best-effort cleanup of stale screenrecord processes
                    withContext(Dispatchers.IO) {
                        try { adbManager.sendShellCommandSync("pkill -2 -f screenrecord") } catch (_: Exception) {}
                        try { adbManager.sendShellCommandSync("rm -f /data/local/tmp/ufm_record.mp4") } catch (_: Exception) {}
                    }
                    delay(300)

                    // Reset PID before starting new recording
                    screenRecordPid = null

                    // Auto-detect: resolution → bitrate, refresh rate → fps
                    tvRecordingStatusDetail.text = getString(R.string.record_screen_detecting)
                    var resW = 0; var resH = 0
                    var bitrate = 8000000
                    var frameRate = 60
                    try {
                        withContext(Dispatchers.IO) {
                            val wmStream = adbManager.openExec("wm size")
                            val wmOutput = wmStream?.openInputStream()?.bufferedReader()?.readLine()?.trim() ?: ""
                            val match = Regex("""(\d+)x(\d+)""").find(wmOutput)
                            if (match != null) {
                                resW = match.groupValues[1].toInt()
                                resH = match.groupValues[2].toInt()
                                bitrate = ((resW * resH) * 4).coerceIn(4_000_000, 20_000_000)
                            }
                        }
                    } catch (_: Exception) {}
                    try {
                        withContext(Dispatchers.IO) {
                            // Use head -1 instead of grep -m1 (toybox grep lacks -m).
                            // Drop stderr redirect — toybox sh may not support 2>/dev/null.
                            val fpsStream = adbManager.openExec("sh -c \"dumpsys display | grep mRefreshRate | head -1\"")
                            val fpsLine = fpsStream?.openInputStream()?.bufferedReader()?.readLine()?.trim() ?: ""
                            val fpsMatch = Regex("""(\d+)""").find(fpsLine)
                            if (fpsMatch != null) {
                                val detected = fpsMatch.groupValues[1].toInt()
                                if (detected > 0) frameRate = detected
                            }
                        }
                    } catch (_: Exception) {}

                    val mbps = bitrate / 1_000_000
                    tvRecordingStatusDetail.text = if (resW > 0) {
                        getString(R.string.record_screen_quality_full, resW, resH, frameRate, mbps)
                    } else {
                        getString(R.string.record_screen_quality_min, frameRate, mbps)
                    }

                    // Use 180s as the screenrecord time-limit — this is the max supported on all
                    // Android TV versions. Our own timer + SIGINT handle stopping at the user's
                    // selected duration (60s, 180s, 300s, 600s). The screenrecord time-limit is
                    // just a safety fallback in case SIGINT delivery fails.
                    val tvSafetyLimit = kotlin.math.min(selectedDurationSeconds, 180)
                    // audio-source=playback reliably fails on most Android TVs — shell
                    // doesn't have the system-level permissions required for internal capture
                    val cmd = "screenrecord --time-limit $tvSafetyLimit --bit-rate $bitrate /data/local/tmp/ufm_record.mp4"
                    recordingExecJob = lifecycleScope.launch(Dispatchers.IO) {
                        val stream = adbManager.openExec(cmd)
                        if (stream != null) {
                            try {
                                stream.openInputStream()?.use { it.readBytes() }
                            } catch (_: Exception) {}
                        }
                    }

                    // Give screenrecord a moment to start, then capture its PID
                    delay(500)
                    try {
                        withContext(Dispatchers.IO) {
                            val pidofStream = adbManager.openExec("pidof screenrecord")
                            val pidOutput = pidofStream?.openInputStream()?.bufferedReader()?.readLine()?.trim() ?: ""
                            screenRecordPid = pidOutput.split("\\s+".toRegex()).mapNotNull { it.toIntOrNull() }.firstOrNull()
                        }
                    } catch (_: Exception) {
                        screenRecordPid = null
                    }

                    var remainingSeconds = selectedDurationSeconds
                    timerJob = lifecycleScope.launch(Dispatchers.Main) {
                        while (isActive && isRecording) {
                            val mins = remainingSeconds / 60
                            val secs = remainingSeconds % 60
                            val timeStr = String.format("%02d:%02d", mins, secs)
                            tvCountdownClock.text = timeStr
                            if (remainingSeconds <= 0) {
                                stopAndSaveRecording(userInitiated = false)
                                break
                            }
                            delay(1000)
                            remainingSeconds--
                        }
                    }
                }
            } else if (isRecording && !isStopping) {
                stopAndSaveRecording(userInitiated = true)
            }
        }

        bottomSheet.show()
    }

    private suspend fun pullRecordingFile(adbManager: AdbManager, onProgress: (Int) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        // Check file exists on TV before attempting pull
        val existsCheck = adbManager.openExec("test -f /data/local/tmp/ufm_record.mp4 && echo 1 || echo 0")
        if (existsCheck?.openInputStream()?.bufferedReader()?.readLine()?.trim() != "1") return@withContext null

        // Get file size for progress tracking and dd block count
        val totalSize = try {
            val s = adbManager.openExec("stat -c%s /data/local/tmp/ufm_record.mp4")
            s?.openInputStream()?.bufferedReader()?.readLine()?.trim()?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }

        onProgress(0)

        val blockSize = 65536 // larger blocks = faster transfer for big files
        val blockCount = if (totalSize > 0) ((totalSize + blockSize - 1) / blockSize).toInt() else 0
        val ddCmd = if (blockCount > 0)
            "dd if=/data/local/tmp/ufm_record.mp4 bs=$blockSize count=$blockCount"
        else
            "dd if=/data/local/tmp/ufm_record.mp4 bs=$blockSize"
        val ddStream = adbManager.openExec(ddCmd) ?: return@withContext null

        val moviesDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
            "Recordings"
        ).apply { if (!exists()) mkdirs() }

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val outputFile = File(moviesDir, "TV_Record_$timestamp.mp4")

        var totalBytes = 0L
        // Dynamic watchdog: at least 60s, scales with file size assuming ~1 MB/s worst-case
        val watchdogSec = if (totalSize > 0) kotlin.math.max(totalSize / 1_000_000L, 60L) else 60L
        val watchdogJob = kotlinx.coroutines.GlobalScope.launch {
            delay(watchdogSec * 1000L)
            try { ddStream.close() } catch (_: Exception) {}
        }
        try {
            ddStream.openInputStream().use { input ->
                java.io.FileOutputStream(outputFile).use { out ->
                    val buffer = ByteArray(blockSize)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (totalSize > 0) {
                            onProgress(((totalBytes * 100) / totalSize).toInt().coerceAtMost(99))
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            watchdogJob.cancel()
        }

        onProgress(100)

        if (!outputFile.exists() || outputFile.length() == 0L) {
            android.util.Log.w("GoRoScreen", "Recording file empty or missing after pull")
            return@withContext null
        }

        // Extract duration via MediaPlayer for accurate container timestamps
        val durationMs = try {
            val player = android.media.MediaPlayer()
            try {
                player.setDataSource(outputFile.absolutePath)
                player.prepare()
                val dur = player.duration.toLong()
                if (dur > 0) dur else 0L
            } finally {
                player.release()
            }
        } catch (_: Exception) {
            // Fallback to MediaMetadataRetriever
            try {
                android.media.MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(outputFile.absolutePath)
                    retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                }
            } catch (_: Exception) { 0L }
        }

        contentResolver.insert(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, outputFile.name)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.DATA, outputFile.absolutePath)
                put(android.provider.MediaStore.Video.Media.DURATION, durationMs)
            }
        )
        android.media.MediaScannerConnection.scanFile(
            this@NetworkBrowserActivity,
            arrayOf(outputFile.absolutePath),
            arrayOf("video/mp4"),
            null
        )

        outputFile
    }

    private fun showPremiumSnackbar(message: String) {
        val rootView = findViewById<View>(R.id.main)
        Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.ufm_surface_variant))
            .setTextColor(getColor(R.color.ufm_text_primary))
            .setActionTextColor(getColor(R.color.ufm_primary))
            .show()
    }



    private fun applyViewMode(mode: ViewModeManager.ViewMode) {
        val updateLayout = {
            fileAdapter.viewMode = mode
            btnViewToggle?.setImageResource(ViewModeManager.iconRes(mode))

            val lm = if (!ViewModeManager.isGrid(mode)) {
                androidx.recyclerview.widget.LinearLayoutManager(this)
            } else {
                androidx.recyclerview.widget.GridLayoutManager(
                    this, ViewModeManager.spanCount(this, mode)
                ).apply {
                    spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return if (fileAdapter.getItemViewType(position) == 3) spanCount else 1
                        }
                    }
                }
            }
            recyclerFiles.layoutManager = lm
            
            for (i in 0 until recyclerFiles.itemDecorationCount) {
                val dec = recyclerFiles.getItemDecorationAt(i)
                if (dec is za.kilowatch.ultimatefilemanager.storage.DateGroupStickyHeaderDecoration) {
                    recyclerFiles.removeItemDecoration(dec)
                }
            }
            
            if (fileAdapter.isGroupedByDate) {
                recyclerFiles.addItemDecoration(za.kilowatch.ultimatefilemanager.storage.DateGroupStickyHeaderDecoration(fileAdapter, 3))
            }

            // Re-attach adapter to ensure layout manager updates correctly
            recyclerFiles.adapter = fileAdapter
        }

        if (::recyclerFiles.isInitialized) {
            za.kilowatch.ultimatefilemanager.util.AnimationHelper.animateViewModeSwitch(recyclerFiles) {
                updateLayout()
            }
        } else {
            updateLayout()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::fileAdapter.isInitialized && ViewModeManager.isGrid(fileAdapter.viewMode)) {
            applyViewMode(fileAdapter.viewMode)
        }
    }

    private fun updateBreadcrumbs() {
        val scroll = layoutBreadcrumbsScroll ?: return
        val container = layoutBreadcrumbs ?: return
        
        val enabled = za.kilowatch.ultimatefilemanager.settings.BreadcrumbsPreferenceManager.isEnabled(this)
                && !isTv
                
        if (!enabled) {
            scroll.visibility = View.GONE
            return
        }
        
        scroll.visibility = View.VISIBLE
        container.removeAllViews()
        
        val list = mutableListOf<Pair<String, String>>()
        list.add(Pair("Home", ""))
        val storageLabel = intent.getStringExtra(EXTRA_STORAGE_LABEL) ?: share.name
        list.add(Pair(storageLabel, if (share.type == ShareType.TV) initialRootPath ?: "" else ""))
        
        if (currentPath.isNotEmpty()) {
            if (share.type == ShareType.TV) {
                val initRoot = initialRootPath
                if (initRoot != null && currentPath.startsWith(initRoot) && currentPath != initRoot) {
                    val relativePath = currentPath.substring(initRoot.length).removePrefix("/")
                    if (relativePath.isNotEmpty()) {
                        val parts = relativePath.split("/")
                        var accumulated = initRoot.removeSuffix("/")
                        for (part in parts) {
                            if (part.isNotEmpty()) {
                                accumulated = "$accumulated/$part"
                                list.add(Pair(part, accumulated))
                            }
                        }
                    }
                }
            } else {
                val parts = currentPath.split("/")
                var accumulated = ""
                for (part in parts) {
                    if (part.isNotEmpty()) {
                        accumulated = if (accumulated.isEmpty()) part else "$accumulated/$part"
                        list.add(Pair(part, accumulated))
                    }
                }
            }
        }
        
        val inflater = android.view.LayoutInflater.from(this)
        for (i in list.indices) {
            val item = list[i]
            
            // Inflate breadcrumb item
            val view = if (i == 0) {
                // Home icon
                inflater.inflate(R.layout.item_breadcrumb_home, container, false).apply {
                    setOnClickListener {
                        val intent = Intent(this@NetworkBrowserActivity, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        startActivity(intent)
                        finish()
                    }
                }
            } else {
                // Text item
                inflater.inflate(R.layout.item_breadcrumb_text, container, false).apply {
                    findViewById<TextView>(R.id.txtBreadcrumbName).apply {
                        text = item.first
                    }
                    if (i == list.lastIndex) {
                        findViewById<TextView>(R.id.txtBreadcrumbName).setTextColor(getColor(R.color.ufm_primary))
                        isClickable = false
                        isFocusable = false
                    } else {
                        setOnClickListener {
                            currentPath = item.second
                            // In server-mode SMB, navigating back to the server root via
                            // a breadcrumb must also reset share.remotePath — otherwise the
                            // next share selection inherits the old share name (e.g. /C)
                            // and produces paths like \\server\C\Open instead of \\server\Open.
                            if (share.isServerMode && currentPath.isEmpty()) {
                                share = share.copy(remotePath = originalRemotePath)
                                fileAdapter.share = share
                            }
                            loadDirectory()
                        }
                    }
                }
            }
            
            container.addView(view)
            
            // Add separator if not the last item
            if (i < list.lastIndex) {
                val separator = inflater.inflate(R.layout.item_breadcrumb_separator, container, false)
                container.addView(separator)
            }
        }
        
        // Auto scroll to the end
        scroll.post {
            scroll.fullScroll(View.FOCUS_RIGHT)
        }
    }
}
