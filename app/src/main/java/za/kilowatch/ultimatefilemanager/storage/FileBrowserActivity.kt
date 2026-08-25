package za.kilowatch.ultimatefilemanager.storage

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor
import za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase
import za.kilowatch.ultimatefilemanager.sync.advanced.InstantSyncWatcher
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.DialogInputHelper
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.ui.PremiumShareActivity
import za.kilowatch.ultimatefilemanager.ui.PremiumShareTvActivity
import java.io.File
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.archive.ArchiveOptionsDialog
import za.kilowatch.ultimatefilemanager.archive.ArchiveManager
import za.kilowatch.ultimatefilemanager.util.FolderScrollState
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.util.KeyboardShortcutHandler
import za.kilowatch.ultimatefilemanager.ui.KeyboardShortcutDialog

/**
 * Displays the contents of a directory.
 * Supports navigating into sub-folders, opening files,
 * multi-select via long-press, batch delete, copy, move,
 * rename, share, sort, and filter.
 */
class FileBrowserActivity : AppCompatActivity() {

    private val isTv by lazy { DeviceUtils.isTvDevice(this) }
    private lateinit var keyboardShortcutHandler: KeyboardShortcutHandler

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerFiles: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private var lottieEmptyFolder: com.airbnb.lottie.LottieAnimationView? = null
    private lateinit var layoutSelectionBar: LinearLayout
    private lateinit var txtSelectionCount: TextView
    private lateinit var btnCloseSelection: ImageView
    private lateinit var btnSelectAll: View
    private lateinit var btnDelete: View
    private lateinit var btnCopy: ImageView
    private lateinit var btnMove: ImageView
    private lateinit var btnRename: ImageView
    private lateinit var btnShare: ImageView
    private lateinit var btnCopyEncrypt: ImageView
    private lateinit var btnMoveEncrypt: ImageView
    private lateinit var btnFavorite: ImageView
    private lateinit var btnHide: ImageView
    private lateinit var btnUnhide: ImageView
    private lateinit var btnProtect: ImageView
    private lateinit var btnUnprotect: ImageView
    private var btnPin: ImageView? = null
    private var btnUnpin: ImageView? = null
    private lateinit var btnCompress: android.view.View
    private var btnExtract: android.view.View? = null
    private lateinit var btnImageCompress: android.view.View
    private var btnViewToggle: ImageView? = null
    private var btnRetriggerThumbnails: ImageView? = null
    private var btnDuplicateFinder: ImageView? = null
    private var btnLargeFilesFinder: ImageView? = null

    private var btnSort: ImageView? = null
    private var btnOptionsToggle: ImageView? = null
    private var layoutOptionsRow: LinearLayout? = null
    private var isOptionsVisible = false
    private lateinit var btnSearchToggle: android.widget.ImageView
    private lateinit var layoutSearchRow: android.widget.LinearLayout
    private lateinit var edtSearch: android.widget.EditText
    private lateinit var btnSearchClear: android.widget.ImageView
    private var isSearchVisible = false
    private var searchJob: kotlinx.coroutines.Job? = null
    private var isKeyfilePickerMode = false
    private var isCertPickerMode = false
    private var selectedKeyFilePath: String? = null
    private lateinit var fabPaste: ExtendedFloatingActionButton
    private var fabProperties: ExtendedFloatingActionButton? = null
    private var fabTools: ExtendedFloatingActionButton? = null
    private lateinit var fileAdapter: FileAdapter
    private var layoutBreadcrumbsScroll: android.widget.HorizontalScrollView? = null
    private var layoutBreadcrumbs: android.widget.LinearLayout? = null

    private val batchRenameTvLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            fileAdapter.exitSelectionMode()
            loadDirectory(currentDir)
        }
    }

    private val folderDuplicateFinderLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            fileAdapter.exitSelectionMode()
            triggerReindex()
        }
    }

    private val folderLargeFilesFinderLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            fileAdapter.exitSelectionMode()
            triggerReindex()
        }
    }


    private lateinit var rootPath: String
    private lateinit var currentDir: File
    private var storageLabel: String = ""

    // Sort & filter state
    private var sortMode = SortFilterSheet.SortMode.NAME
    private var sortOrder = SortFilterSheet.SortOrder.ASC
    private var filterType = SortFilterSheet.FilterType.ALL
    private var activeTagsFilter: Set<String> = emptySet()
    private var isTransferring = false
    private var transferJob: kotlinx.coroutines.Job? = null
    private var currentTransferDestFile: java.io.File? = null
    private var currentTransferStreams: Pair<java.io.InputStream?, java.io.OutputStream?>? = null
    private var currentTransferConnection: AutoCloseable? = null  // raw TCP connection — close() kills SMB write socket instantly
    private var isCancelled = false
    private var folderFlowJob: kotlinx.coroutines.Job? = null

    private var onFolderPicked: ((File) -> Unit)? = null

    // â”€â”€ Quick Transfer (Copy To / Move To) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private var isQuickTransferPickerMode = false
    private var pendingQuickTransferFiles: List<File>? = null
    private var pendingQuickTransferIsMove: Boolean = false
    /**
     * Set by [handleQuickTransferResult] before calling [performPaste].
     * The paste coroutine captures this as a local val and uses it as the
     * destination base â€” avoiding a race where restoring [currentDir] on the
     * main thread would win over the coroutine reading it on the IO thread.
     * Cleared at the end of the paste coroutine.
     */
    private var quickTransferDestDir: File? = null

    /**
     * Launched when the user taps "Copy To..." or "Move To...".
     * StorageBrowserActivity (with EXTRA_QUICK_TRANSFER_PICKER) opens FileBrowserActivity
     * as a destination picker; the chosen local path is forwarded back here.
     */
    private val quickTransferLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleQuickTransferResult(result)
    }

    // Stash for compress params when dest is a network share (TV/Phone/SMB/FTP)
    private var pendingCompressSourceFiles: List<File>? = null
    private var pendingCompressFileName: String? = null
    private var pendingCompressFormat: za.kilowatch.ultimatefilemanager.archive.ArchiveManager.Format? = null
    private var pendingCompressPassword: String? = null

    private val folderPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            // Local / SAF destination
            val localPath = data.getStringExtra(RESULT_SELECTED_LOCAL_PATH) ?: data.getStringExtra(RESULT_SELECTED_PATH)
            if (localPath != null) {
                val pickedFile = if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(localPath)) {
                    za.kilowatch.ultimatefilemanager.storage.SafFile(localPath, isDir = true)
                } else {
                    File(localPath)
                }
                onFolderPicked?.invoke(pickedFile)
                onFolderPicked = null
                return@registerForActivityResult
            }
            // Network destination (TV/Phone/SMB/FTP selected in StorageBrowserActivity)
            val shareId = data.getStringExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.RESULT_SELECTED_COMPRESS_SHARE_ID)
            val netPath = data.getStringExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.RESULT_SELECTED_COMPRESS_NET_PATH)
            if (shareId != null && netPath != null) {
                val src  = pendingCompressSourceFiles ?: return@registerForActivityResult
                val name = pendingCompressFileName    ?: return@registerForActivityResult
                val fmt  = pendingCompressFormat      ?: return@registerForActivityResult
                val share = resolveShareById(shareId)
                if (share != null) {
                    performNetworkUploadCompress(src, share, netPath, name, fmt, pendingCompressPassword)
                }
            }
        }
        onFolderPicked = null
        pendingCompressSourceFiles = null
        pendingCompressFileName    = null
        pendingCompressFormat      = null
        pendingCompressPassword    = null
    }

    private val safTreeLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    val takeFlags = (result.data?.flags ?: 0) and (
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val flagsToUse = if (takeFlags != 0) takeFlags else (
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    contentResolver.takePersistableUriPermission(uri, flagsToUse)
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.saveTreePermission(this, currentDir.absolutePath, uri)
                    android.widget.Toast.makeText(this, R.string.protected_folder_saf_success, android.widget.Toast.LENGTH_SHORT).show()
                    loadDirectory(currentDir)
                } catch (e: Exception) {
                    android.util.Log.e("FileBrowser", "Failed to persist SAF tree uri: ${e.message}")
                }
            }
        } else {
            android.widget.Toast.makeText(this, R.string.protected_folder_saf_denied, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchSafTreePicker(path: String) {
        try {
            val intent = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createDocumentTreeIntent(path)
            safTreeLauncher.launch(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Could not launch folder picker: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_MOUNT_PATH = "extra_mount_path"
        const val EXTRA_STORAGE_LABEL = "extra_storage_label"
        const val EXTRA_STORAGE_ID = "extra_storage_id"
        const val EXTRA_STORAGE_TYPE = "extra_storage_type"
        const val EXTRA_PICKER_MODE = "extra_picker_mode"
        const val EXTRA_PICKER_EXTENSIONS = "extra_picker_extensions" // comma-separated, e.g. "apk" or "xapk,apks"
        const val EXTRA_SYNC_FOLDER_PICKER = "extra_sync_folder_picker"
        /** When true, the user is picking a source folder for Advanced Sync */
        const val EXTRA_ADVANCED_SYNC_FOLDER_PICKER = "extra_advanced_sync_folder_picker"
        /** When true, the user is picking a destination folder for Advanced Sync */
        const val EXTRA_ADVANCED_SYNC_DEST_PICKER = "extra_advanced_sync_dest_picker"
        /** When true the user is picking a destination folder for Compress */
        const val EXTRA_COMPRESS_DEST_PICKER = "extra_compress_dest_picker"
        /** When true the user is picking a destination folder for Extract */
        const val EXTRA_EXTRACT_DEST_PICKER = "extra_extract_dest_picker"
        /** When true the user is picking a destination folder for Image Compress */
        const val EXTRA_IMAGE_COMPRESS_DEST_PICKER = "extra_image_compress_dest_picker"
        /** When true the user is picking a destination folder for GIF Creator */
        const val EXTRA_GIF_CREATOR_DEST_PICKER = "extra_gif_creator_dest_picker"
        const val EXTRA_KEYFILE_PICKER = "extra_keyfile_picker"
        const val EXTRA_CERT_PICKER = "extra_cert_picker"
        const val RESULT_SELECTED_PATH = "selected_path"
        const val RESULT_SELECTED_LOCAL_PATH = "result_selected_local_path"
        const val EXTRA_FOCUS_PATH = "extra_focus_path"
        const val EXTRA_INITIAL_PATH = "extra_initial_path"
        const val EXTRA_IS_CATEGORY_MODE = "extra_is_category_mode"
        const val EXTRA_CATEGORY_NAME = "extra_category_name"
        const val EXTRA_FILTER_TYPE = "extra_filter_type"
        
        /** When true, use this activity to pick a location (URI, etc) for server/vault */
        const val EXTRA_LOCATION_PICKER = "extra_location_picker"
        /** When true, the user is picking a destination folder for Share Receive */
        const val EXTRA_SHARE_DEST_PICKER = "extra_share_dest_picker"
        /** When true the user is picking a local folder for network thumbnail caching */
        const val EXTRA_NETWORK_CACHE_PICKER = "extra_network_cache_picker"
        const val RESULT_URI = "result_uri"
        const val RESULT_LABEL = "result_label"
        const val RESULT_TYPE = "result_type"
        const val RESULT_META_ID = "result_meta_id"
        /** When true, this FileBrowserActivity is acting as a destination folder picker for Quick Transfer */
        const val EXTRA_QUICK_TRANSFER_PICKER = "extra_quick_transfer_picker"
        /** "COPY" or "MOVE" â€” passed alongside EXTRA_QUICK_TRANSFER_PICKER */
        const val EXTRA_QUICK_TRANSFER_OP = "extra_quick_transfer_op"

        /** When true, the user is picking a destination folder for Notepad Save As */
        const val EXTRA_NOTEPAD_FOLDER_PICKER = "extra_notepad_folder_picker"

        /** When true, the user is picking a destination folder for Document Scanner Save As */
        const val EXTRA_SCANNER_FOLDER_PICKER = "extra_scanner_folder_picker"

        /** When true, the user is picking a folder for Smart Sort */
        const val EXTRA_SMART_SORT_PICKER = "extra_smart_sort_picker"

        /** When true, the user is picking a destination folder for Auto Backup */
        const val EXTRA_AUTO_BACKUP_FOLDER_PICKER = "extra_auto_backup_folder_picker"

        /** When true, the user is picking a file to attach to a support request */
        const val EXTRA_SUPPORT_ATTACHMENT_PICKER = "extra_support_attachment_picker"
    }
    
    private var isLocationPickerMode = false

    private var isPickerMode = false
    private var pickerExtensions: Set<String> = emptySet()
    private var isSyncFolderPickerMode = false
    private var isAdvancedSyncFolderPickerMode = false
    private var isAdvancedSyncDestPickerMode = false
    private var isCompressDestPickerMode = false
    private var isExtractDestPickerMode = false
    private var isImageCompressDestPickerMode = false
    private var isGifCreatorDestPickerMode = false
    private var isNetworkCachePickerMode = false
    private var isShareDestPickerMode = false
    private var isNotepadFolderPicker = false
    private var isScannerFolderPicker = false
    private var isAutoBackupFolderPicker = false
    private var isSupportAttachmentPicker = false
    private var isSmartSortPickerMode = false

    // (Removed Saf directory permission launchers)

    private var storageId: String = ""
    private var storageType: String = ""
    private var focusPath: String? = null
    private var isCategoryMode = false
    private var categoryName: String? = null

    // ── Category-mode pagination ─────────────────────────────────────────────
    private val CATEGORY_PAGE_SIZE = 1000
    private var categoryPage = 0          // next page index (0-based)
    private var categoryAllLoaded = false // true once DB returns < PAGE_SIZE rows
    private var isCategoryLoading = false // guard against overlapping fetches
    private var categoryScrollListener: RecyclerView.OnScrollListener? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        savedInstanceState?.getBundle("KEY_FOLDER_SCROLL_STATES")?.let { bundle ->
            folderScrollStates.putAll(FolderScrollState.fromBundle(bundle))
        }
        enableEdgeToEdge()
        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_file_browser_tv else R.layout.activity_file_browser)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // TV layout has built-in safe-zone padding; only apply system bars
            v.setPadding(
                systemBars.left, systemBars.top,
                systemBars.right, systemBars.bottom
            )
            insets
        }

        val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val internalLabel = getString(R.string.storage_internal)

        rootPath = SafFile.cleanSafPath(intent.getStringExtra(EXTRA_MOUNT_PATH) ?: internalPath)
        val isRootProtected = ShizukuShellWrapper.isProtectedPath(rootPath)
        val isRootSaf = SafTreeManager.isSafPath(rootPath) || SafTreeManager.hasTreePermissionForPath(this, rootPath)

        za.kilowatch.ultimatefilemanager.util.GoRoLog.d("SafStorage", "FileBrowserActivity onCreate: rootPath=$rootPath, isRootProtected=$isRootProtected, isRootSaf=$isRootSaf")

        if (rootPath.isEmpty() || (!isRootProtected && !isRootSaf && !File(rootPath).exists())) {
            za.kilowatch.ultimatefilemanager.util.GoRoLog.w("SafStorage", "Root path $rootPath does not exist and is neither protected nor SAF. Falling back to internal storage.")
            rootPath = internalPath
            storageLabel = internalLabel
            storageId = "internal"
            storageType = "internal"
        } else {
            storageLabel = intent.getStringExtra(EXTRA_STORAGE_LABEL) ?: getString(R.string.storage)
            storageId = intent.getStringExtra(EXTRA_STORAGE_ID) ?: if (isRootSaf) "saf" else if (rootPath.contains("emulated")) "internal" else "external"
            storageType = intent.getStringExtra(EXTRA_STORAGE_TYPE) ?: if (isRootSaf) "saf_custom" else if (rootPath.contains("emulated")) "internal" else "external"
        }
        currentDir = if (isRootProtected && ShizukuShellWrapper.canUseShizukuForPath(rootPath)) {
            val pName = rootPath.substringAfterLast("/")
            val pParent = rootPath.substringBeforeLast("/", "")
            ShizukuFile(pParent, pName, true)
        } else if (isRootSaf) {
            SafFile(rootPath, true)
        } else {
            File(rootPath)
        }
        
        // ANR Watchdog Verification Snippet: Simulates a 6-second main-thread freeze when opening local storage
        // Uncomment the line below to test genuine ANR detection:
        // Thread.sleep(6_000L)
        focusPath = intent.getStringExtra(EXTRA_FOCUS_PATH)

        // If an initial subfolder was provided (e.g. from closing twin window), navigate there
        val initialPath = intent.getStringExtra(EXTRA_INITIAL_PATH)
        if (!initialPath.isNullOrEmpty()) {
            val cleanInit = SafFile.cleanSafPath(initialPath)
            val isInitProtected = ShizukuShellWrapper.isProtectedPath(cleanInit)
            val isInitSaf = SafTreeManager.isSafPath(cleanInit) || SafTreeManager.hasTreePermissionForPath(this, cleanInit)
            val initExists = if (isInitProtected) {
                if (ShizukuShellWrapper.canUseShizukuForPath(cleanInit)) ShizukuShellWrapper.exists(cleanInit)
                else if (SafTreeManager.hasTreePermissionForPath(this, cleanInit)) SafTreeManager.exists(this, cleanInit)
                else true
            } else if (isInitSaf) {
                SafTreeManager.exists(this, cleanInit)
            } else {
                File(cleanInit).exists()
            }
            if (initExists && cleanInit.startsWith(rootPath)) {
                currentDir = if (isInitProtected && ShizukuShellWrapper.canUseShizukuForPath(cleanInit)) {
                    val pName = cleanInit.substringAfterLast("/")
                    val pParent = cleanInit.substringBeforeLast("/", "")
                    ShizukuFile(pParent, pName, true)
                } else if (isInitSaf) {
                    SafFile(cleanInit, true)
                } else {
                    File(cleanInit)
                }
            }
        }

        // Picker mode
        isPickerMode = intent.getBooleanExtra(EXTRA_PICKER_MODE, false)
        isKeyfilePickerMode = intent.getBooleanExtra(EXTRA_KEYFILE_PICKER, false)
        isCertPickerMode = intent.getBooleanExtra(EXTRA_CERT_PICKER, false)
        val extString = intent.getStringExtra(EXTRA_PICKER_EXTENSIONS) ?: ""
        pickerExtensions = extString.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (isPickerMode && pickerExtensions.isNotEmpty()) {
            storageLabel = "Select ${pickerExtensions.joinToString("/") { ".${it.uppercase()}" }} File"
        }
        isSyncFolderPickerMode = intent.getBooleanExtra(EXTRA_SYNC_FOLDER_PICKER, false)
        isAdvancedSyncFolderPickerMode = intent.getBooleanExtra(EXTRA_ADVANCED_SYNC_FOLDER_PICKER, false)
        isAdvancedSyncDestPickerMode = intent.getBooleanExtra(EXTRA_ADVANCED_SYNC_DEST_PICKER, false)
        isCompressDestPickerMode = intent.getBooleanExtra(EXTRA_COMPRESS_DEST_PICKER, false)
        isExtractDestPickerMode = intent.getBooleanExtra(EXTRA_EXTRACT_DEST_PICKER, false)
        isImageCompressDestPickerMode = intent.getBooleanExtra(EXTRA_IMAGE_COMPRESS_DEST_PICKER, false)
        isGifCreatorDestPickerMode = intent.getBooleanExtra(EXTRA_GIF_CREATOR_DEST_PICKER, false)
        isLocationPickerMode = intent.getBooleanExtra(EXTRA_LOCATION_PICKER, false)
        isNetworkCachePickerMode = intent.getBooleanExtra(EXTRA_NETWORK_CACHE_PICKER, false)
        isQuickTransferPickerMode = intent.getBooleanExtra(EXTRA_QUICK_TRANSFER_PICKER, false)
        isShareDestPickerMode = intent.getBooleanExtra(EXTRA_SHARE_DEST_PICKER, false)
        isNotepadFolderPicker = intent.getBooleanExtra(EXTRA_NOTEPAD_FOLDER_PICKER, false)
        isScannerFolderPicker = intent.getBooleanExtra(EXTRA_SCANNER_FOLDER_PICKER, false)
        isAutoBackupFolderPicker = intent.getBooleanExtra(EXTRA_AUTO_BACKUP_FOLDER_PICKER, false)
        isSupportAttachmentPicker = intent.getBooleanExtra(EXTRA_SUPPORT_ATTACHMENT_PICKER, false)
        isSmartSortPickerMode = intent.getBooleanExtra(EXTRA_SMART_SORT_PICKER, false)

        // Category mode — read extras that drive the filtered file list
        isCategoryMode = intent.getBooleanExtra(EXTRA_IS_CATEGORY_MODE, false)
        categoryName   = intent.getStringExtra(EXTRA_CATEGORY_NAME)
        if (isCategoryMode) {
            val filterOrdinal = intent.getIntExtra(EXTRA_FILTER_TYPE, 0)
            filterType = SortFilterSheet.FilterType.entries.getOrElse(filterOrdinal) { SortFilterSheet.FilterType.ALL }
        }

        // Restore sort preferences — prefer folder-specific, fall back to global
        val globalState = SortFilterPreferenceManager.loadGlobal(this)
        sortMode  = globalState.sortMode
        sortOrder = globalState.sortOrder
        // Folder-specific overrides are applied in loadDirectory() on the IO thread.

        setupViews()
        loadDirectory(currentDir)
    }

    private val folderChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            val folderPath = intent.getStringExtra(InstantSyncWatcher.EXTRA_FOLDER_PATH) ?: return
            if (::currentDir.isInitialized) {
                val currentNorm = InstantSyncWatcher.normalizePath(currentDir.absolutePath)
                val targetNorm = InstantSyncWatcher.normalizePath(folderPath)
                if (currentNorm == targetNorm || currentNorm.startsWith("$targetNorm/") || targetNorm.startsWith("$currentNorm/")) {
                    Log.d("FileBrowserActivity", "folderChangedReceiver: Auto-refreshing $currentNorm")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        loadDirectory(currentDir)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyLeftHandedFabSettings()
        applyToolbarIconVisibility()
        // Refresh file list so files created/modified in child activities
        // (image viewer, text viewer, etc.) appear immediately on return
        if (::currentDir.isInitialized) {
            loadDirectory(currentDir, preserveSelection = true)
        }
        // Show/hide paste FAB based on clipboard state or picker modes
        updatePasteFab()
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                folderChangedReceiver,
                android.content.IntentFilter(InstantSyncWatcher.ACTION_FOLDER_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(folderChangedReceiver)
        } catch (_: Exception) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveCurrentFolderScroll()
        outState.putBundle("KEY_FOLDER_SCROLL_STATES", FolderScrollState.toBundle(folderScrollStates))
    }

    private fun updateFabPositions() {
        if (!::fabPaste.isInitialized) return
        val isLeftHanded = za.kilowatch.ultimatefilemanager.settings.LeftHandedFabPreferenceManager.isLeftHanded(this)
        val isToolsVisible = fabTools?.visibility == View.VISIBLE

        fabTools?.let { tools ->
            val lp = tools.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            if (isLeftHanded) {
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            } else {
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            }
            tools.layoutParams = lp
        }

        val pasteLp = fabPaste.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        if (pasteLp != null) {
            if (isToolsVisible) {
                pasteLp.bottomToTop = R.id.fabTools
                pasteLp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            } else {
                pasteLp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                pasteLp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            }
            if (isLeftHanded) {
                pasteLp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                pasteLp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            } else {
                pasteLp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                pasteLp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            }
            fabPaste.layoutParams = pasteLp
        }
    }

    private fun applyLeftHandedFabSettings() {
        updateFabPositions()
    }

    private fun applyToolbarIconVisibility() {
        if (!::btnCopy.isInitialized) return
        val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
        btnCopy.visibility = if (pm.isIconEnabled(this, pm.KEY_COPY)) View.VISIBLE else View.GONE
        btnMove.visibility = if (pm.isIconEnabled(this, pm.KEY_MOVE)) View.VISIBLE else View.GONE
        btnRename.visibility = if (pm.isIconEnabled(this, pm.KEY_RENAME)) View.VISIBLE else View.GONE
        btnShare.visibility = if (pm.isIconEnabled(this, pm.KEY_SHARE)) View.VISIBLE else View.GONE
        btnCopyEncrypt.visibility = if (pm.isIconEnabled(this, pm.KEY_COPY_ENCRYPT)) View.VISIBLE else View.GONE
        btnMoveEncrypt.visibility = if (pm.isIconEnabled(this, pm.KEY_MOVE_ENCRYPT)) View.VISIBLE else View.GONE
        btnFavorite.visibility = if (pm.isIconEnabled(this, pm.KEY_FAVORITE)) View.VISIBLE else View.GONE
        btnHide.visibility = if (pm.isIconEnabled(this, pm.KEY_HIDE)) View.VISIBLE else View.GONE
        btnUnhide.visibility = if (pm.isIconEnabled(this, pm.KEY_UNHIDE)) View.VISIBLE else View.GONE
        btnCompress.visibility = if (pm.isIconEnabled(this, pm.KEY_COMPRESS)) View.VISIBLE else View.GONE
        btnImageCompress.visibility = View.GONE
        btnSelectAll.visibility = if (pm.isIconEnabled(this, pm.KEY_SELECT_ALL)) View.VISIBLE else View.GONE
        btnDelete.visibility = if (pm.isIconEnabled(this, pm.KEY_DELETE)) View.VISIBLE else View.GONE
        btnRetriggerThumbnails?.visibility = if (pm.isIconEnabled(this, pm.KEY_RETRIGGER_THUMBNAILS)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnCreateNew)?.visibility = if (pm.isIconEnabled(this, pm.KEY_CREATE_NEW)) View.VISIBLE else View.GONE

        val isIndexed = UfmApplication.indexingRepository.isStorageFullyIndexed(storageId)
        findViewById<View>(R.id.btnRefreshIndex)?.visibility = if (isIndexed) View.VISIBLE else View.GONE
    }

    private fun showFolderConfirmDialog(
        heroIconRes: Int,
        title: CharSequence,
        subtitle: CharSequence,
        folderName: String,
        path: String,
        description: CharSequence?,
        actionText: CharSequence,
        actionIconRes: Int = R.drawable.ic_check_circle,
        onConfirm: () -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_folder_confirm, null)
        val imgHero = dialogView.findViewById<ImageView>(R.id.imgPickerConfirmHero)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtPickerConfirmTitle)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtPickerConfirmSubtitle)
        val imgCardIcon = dialogView.findViewById<ImageView>(R.id.imgPickerConfirmCardIcon)
        val txtFolderName = dialogView.findViewById<TextView>(R.id.txtPickerConfirmFolderName)
        val txtPath = dialogView.findViewById<TextView>(R.id.txtPickerConfirmPath)
        val txtDesc = dialogView.findViewById<TextView>(R.id.txtPickerConfirmDesc)
        val btnAction = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPickerConfirmAction)
        val btnCancel = dialogView.findViewById<View>(R.id.btnPickerConfirmCancel)

        imgHero?.setImageResource(heroIconRes)
        imgCardIcon?.setImageResource(heroIconRes)
        txtTitle?.text = title
        txtSubtitle?.text = subtitle
        txtFolderName?.text = folderName
        txtPath?.text = path

        if (description != null) {
            txtDesc?.text = description
            txtDesc?.visibility = View.VISIBLE
        } else {
            txtDesc?.visibility = View.GONE
        }

        btnAction?.text = actionText
        btnAction?.setIconResource(actionIconRes)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnAction?.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showConfirmExtractLocalFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_extract,
            title = getString(R.string.extract_here),
            subtitle = "Choose destination for archive extraction",
            folderName = folderName,
            path = path,
            description = getString(R.string.extract_contents_to_path, path),
            actionText = getString(R.string.extract_here_1),
            actionIconRes = R.drawable.ic_extract
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun confirmSmartSortFolder() {
        val path = currentDir.absolutePath
        if (!isHighRiskFolder(path)) {
            returnSmartSortResult(path)
            return
        }
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_warning,
            title = getString(R.string.smart_sort_warning_title),
            subtitle = "High-Risk Directory Warning",
            folderName = folderName,
            path = path,
            description = getString(R.string.smart_sort_warning_message, path),
            actionText = getString(R.string.btn_continue),
            actionIconRes = R.drawable.ic_warning
        ) {
            returnSmartSortResult(path)
        }
    }

    private fun returnSmartSortResult(path: String) {
        Intent().apply { putExtra(RESULT_SELECTED_LOCAL_PATH, path) }
            .let { setResult(RESULT_OK, it); finish() }
    }

    private fun isHighRiskFolder(path: String): Boolean {
        val normalized = path.trimEnd('/').lowercase()
        return normalized in setOf(
            "/", "/system", "/data", "/cache", "/vendor",
            "/dev", "/proc", "/sys", "/etc", "/sbin",
            "/storage/emulated", "/storage/emulated/0",
            "/storage/emulated/0/android",
            "/storage/emulated/0/data",
            "/storage/emulated/0/obb"
        )
    }

    private fun showConfirmCompressLocalFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_compress,
            title = getString(R.string.compress_here),
            subtitle = "Choose destination folder for archive",
            folderName = folderName,
            path = path,
            description = getString(R.string.save_archive_to_path, path),
            actionText = getString(R.string.use_this_folder),
            actionIconRes = R.drawable.ic_compress
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmImageCompressLocalFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_compress_image,
            title = getString(R.string.compress_here),
            subtitle = "Choose destination folder for compressed images",
            folderName = folderName,
            path = path,
            description = getString(R.string.save_archive_to_path, path),
            actionText = getString(R.string.use_this_folder_image),
            actionIconRes = R.drawable.ic_compress_image
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmGifCreatorLocalFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_gif,
            title = getString(R.string.gif_creator_title),
            subtitle = "Choose destination folder for generated GIF",
            folderName = folderName,
            path = path,
            description = getString(R.string.save_archive_to_path, path),
            actionText = getString(R.string.use_this_folder),
            actionIconRes = R.drawable.ic_gif
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmSyncLocalFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_sync,
            title = getString(R.string.confirm_source_folder),
            subtitle = "Select source directory for folder sync",
            folderName = folderName,
            path = path,
            description = getString(R.string.use_folder_as_sync_source, path) + " " +
                getString(R.string.files_in_this_folder_will_be_backed_up_to_your_network_share),
            actionText = getString(R.string.btn_continue),
            actionIconRes = R.drawable.ic_sync
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmAdvancedSyncLocalFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_sync_advanced,
            title = getString(R.string.confirm_source_folder),
            subtitle = "Select source directory for advanced sync profile",
            folderName = folderName,
            path = path,
            description = getString(R.string.use_folder_as_sync_source, path) + " " +
                getString(R.string.files_in_this_folder_will_be_backed_up_to_your_network_share),
            actionText = getString(R.string.btn_continue),
            actionIconRes = R.drawable.ic_sync_advanced
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmAdvancedSyncDestFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_sync_advanced,
            title = getString(R.string.confirm_destination_folder),
            subtitle = "Select destination directory for advanced sync profile",
            folderName = folderName,
            path = path,
            description = getString(R.string.use_folder_as_sync_destination, path),
            actionText = getString(R.string.btn_continue),
            actionIconRes = R.drawable.ic_sync_advanced
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmLocationPickerLocalFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(path) ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, path)
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_folder,
            title = getString(R.string.select_folder),
            subtitle = "Choose root storage location",
            folderName = folderName,
            path = path,
            description = getString(R.string.use_folder_as_default_location, path),
            actionText = getString(R.string.use_this_folder),
            actionIconRes = R.drawable.ic_folder
        ) {
            val result = Intent().apply {
                putExtra(RESULT_URI, if (isSaf) path else "file://$path")
                putExtra(RESULT_LABEL, folderName)
                putExtra(RESULT_TYPE, if (isSaf) "SAF" else "LOCAL")
                putExtra(RESULT_META_ID, null as String?)
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmSupportAttachmentDialog() {
        val path = selectedKeyFilePath ?: return
        val fileName = path.substringAfterLast('/')
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
        val customView = layoutInflater.inflate(layoutRes, null)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(customView)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
            android.graphics.Color.TRANSPARENT
        ))

        val imgIcon = customView.findViewById<android.widget.ImageView>(R.id.imgDialogIcon)
        imgIcon.setImageResource(R.drawable.ic_add)
        imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_primary))

        customView.findViewById<android.widget.TextView>(R.id.txtDialogTitle).text = getString(R.string.support_attach_file)
        customView.findViewById<android.widget.TextView>(R.id.txtDialogMessage).text = getString(R.string.support_file_attached, fileName)

        val btnPositive = customView.findViewById<android.view.View>(R.id.btnDialogPositive)
        if (btnPositive is android.widget.TextView) btnPositive.text = getString(R.string.support_attach_file)

        if (isTv && btnPositive is android.widget.Button) {
            val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            val defaultCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            btnPositive.setTextColor(defaultCsl)
            btnPositive.setOnFocusChangeListener { _, hasFocus ->
                btnPositive.setTextColor(if (hasFocus) yellowCsl else defaultCsl)
                btnPositive.setBackgroundResource(
                    if (hasFocus) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button
                )
            }
        }

        btnPositive.setOnClickListener {
            dialog.dismiss()
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }

        val btnNegative = customView.findViewById<android.view.View>(R.id.btnDialogNegative)
        btnNegative.visibility = android.view.View.VISIBLE
        if (btnNegative is android.widget.TextView) btnNegative.text = getString(R.string.cancel)

        if (isTv && btnNegative is android.widget.Button) {
            val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            val defaultCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_secondary))
            btnNegative.setTextColor(defaultCsl)
            btnNegative.setOnFocusChangeListener { _, hasFocus ->
                btnNegative.setTextColor(if (hasFocus) yellowCsl else defaultCsl)
                btnNegative.setBackgroundResource(
                    if (hasFocus) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button
                )
            }
        }

        btnNegative.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        if (isTv) {
            btnPositive.requestFocus()
        }
    }

    private fun showConfirmKeyfilePickedDialog() {
        val path = selectedKeyFilePath ?: return
        val fileName = java.io.File(path).name
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_lock,
            title = getString(R.string.use_this_key_file),
            subtitle = "Public Key Authentication",
            folderName = fileName,
            path = path,
            description = getString(R.string.use_key_file_confirm, path),
            actionText = getString(R.string.use_this_key_file),
            actionIconRes = R.drawable.ic_lock
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmCertPickedDialog() {
        val path = selectedKeyFilePath ?: return
        val fileName = java.io.File(path).name

        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_use_cert, null)
        val txtFileName = dialogView.findViewById<TextView>(R.id.txtCertFileName)
        val txtPath = dialogView.findViewById<TextView>(R.id.txtCertPath)
        val btnConfirm = dialogView.findViewById<View>(R.id.btnConfirmUseCert)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelUseCert)

        txtFileName?.text = fileName
        txtPath?.text = path

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        btnConfirm?.setOnClickListener {
            dialog.dismiss()
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showConfirmNetworkCacheFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_folder,
            title = getString(R.string.nt_use_this_folder_for_caching),
            subtitle = "Network Thumbnail Cache",
            folderName = folderName,
            path = path,
            description = getString(R.string.nt_cache_limit_title),
            actionText = getString(R.string.nt_use_this_folder_for_caching),
            actionIconRes = R.drawable.ic_folder
        ) {
            val cacheDir = java.io.File(path, ".ufm_network_thumbnails")
            cacheDir.mkdirs()
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, cacheDir.absolutePath)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmShareDestDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_folder,
            title = getString(R.string.use_this_folder),
            subtitle = "Save Incoming Files",
            folderName = folderName,
            path = path,
            description = getString(R.string.share_receive_confirm, path),
            actionText = getString(R.string.use_this_folder),
            actionIconRes = R.drawable.ic_folder
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmNotepadFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_folder,
            title = getString(R.string.use_this_folder),
            subtitle = "Notepad Save Destination",
            folderName = folderName,
            path = path,
            description = getString(R.string.notepad_folder_picker_title),
            actionText = getString(R.string.use_this_folder),
            actionIconRes = R.drawable.ic_folder
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmScannerFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_scanner,
            title = getString(R.string.scanner_use_this_folder),
            subtitle = "Document Scanner Destination",
            folderName = folderName,
            path = path,
            description = getString(R.string.scanner_folder_picker_title),
            actionText = getString(R.string.scanner_use_this_folder),
            actionIconRes = R.drawable.ic_scanner
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun showConfirmAutoBackupFolderDialog() {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        showFolderConfirmDialog(
            heroIconRes = R.drawable.ic_cloud,
            title = getString(R.string.auto_backup_location_confirm_title),
            subtitle = "Automatic Backup Destination",
            folderName = folderName,
            path = path,
            description = getString(R.string.auto_backup_location_confirm_message),
            actionText = getString(R.string.auto_backup_select_folder),
            actionIconRes = R.drawable.ic_cloud
        ) {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                putExtra(RESULT_SELECTED_PATH, path)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    // ── Quick Transfer helpers ──────────────────────────────────────────────────

    /**
     * Confirms the chosen folder and immediately triggers the file transfer.
     * The transfer engine reuses [performPaste]: currentDir is temporarily swapped
     * to the destination, paste is invoked, then currentDir is restored.
     */
    private fun showConfirmQuickTransferDialog(isMove: Boolean) {
        val path = currentDir.absolutePath
        val folderName = if (currentDir.name.isNotEmpty()) currentDir.name else path
        // In the picker instance pendingQuickTransferFiles is null — fall back to FileClipboard
        val fileCount = pendingQuickTransferFiles?.size ?: FileClipboard.files.size
        val titleRes = if (isMove) R.string.action_move_to else R.string.action_copy_to
        val msgRes   = if (isMove) R.string.quick_transfer_move_confirm else R.string.quick_transfer_copy_confirm
        val posRes   = if (isMove) R.string.quick_transfer_move_here else R.string.quick_transfer_copy_here
        val iconRes  = if (isMove) R.drawable.ic_move else R.drawable.ic_copy

        showFolderConfirmDialog(
            heroIconRes = iconRes,
            title = getString(titleRes),
            subtitle = if (isMove) "Move files to destination" else "Copy files to destination",
            folderName = folderName,
            path = path,
            description = getString(msgRes, fileCount),
            actionText = getString(posRes),
            actionIconRes = iconRes
        ) {
            quickTransferDestDir = currentDir
            performPaste()
        }
    }

    /**
     * Opens StorageBrowserActivity in Quick Transfer picker mode.
     * Stores the selected files and operation type for use when the result returns.
     */
    private fun launchQuickTransferPicker(files: List<File>, isMove: Boolean) {
        pendingQuickTransferFiles = files
        pendingQuickTransferIsMove = isMove
        fileAdapter.exitSelectionMode()
        // Pre-load FileClipboard so NetworkBrowserActivity.performPaste() can read it
        // directly when the user picks a network destination.
        FileClipboard.set(files, if (isMove) FileClipboard.Operation.MOVE else FileClipboard.Operation.COPY)
        val intent = android.content.Intent(this, StorageBrowserActivity::class.java).apply {
            putExtra(EXTRA_QUICK_TRANSFER_PICKER, true)
            putExtra(EXTRA_QUICK_TRANSFER_OP, if (isMove) "MOVE" else "COPY")
        }
        quickTransferLauncher.launch(intent)
    }

    private fun handleCopyOrCut(selected: List<File>, isMove: Boolean) {
        if (selected.isEmpty()) return
        if (za.kilowatch.ultimatefilemanager.settings.QuickTransferPreferenceManager.isEnabled(this)) {
            launchQuickTransferPicker(selected, isMove = isMove)
            return
        }

        val op = if (isMove) FileClipboard.Operation.MOVE else FileClipboard.Operation.COPY
        val recentSlot = FileClipboard.getRecentSlot()

        if (recentSlot == null) {
            FileClipboard.pushLocalSlot(selected, op, currentDir.absolutePath)
            fileAdapter.exitSelectionMode()
            showPremiumSnackbar(getString(if (isMove) R.string.clipboard_cut else R.string.clipboard_copied, selected.size))
            updatePasteFab()
        } else {
            fileAdapter.exitSelectionMode()
            val isOnTv = DeviceUtils.isTvDevice(this)
            val layoutRes = if (isOnTv) R.layout.dialog_clipboard_add_or_new_tv else R.layout.dialog_clipboard_add_or_new
            val itemLayoutRes = if (isOnTv) R.layout.item_clipboard_slot_choice_tv else R.layout.item_clipboard_slot_choice
            val dialogView = layoutInflater.inflate(layoutRes, null)
            val txtSubtitle = dialogView.findViewById<android.widget.TextView>(R.id.txtAddOrNewSubtitle)
            val recyclerSlots = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerExistingSlots)
            val btnNewSlot = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNewSlot)
            val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelAddOrNew)

            txtSubtitle.text = getString(R.string.clipboard_slots_title, FileClipboard.slots.size, FileClipboard.totalItemCount())

            val dialog: android.app.Dialog = if (isOnTv) {
                MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                    .setView(dialogView)
                    .create()
            } else {
                com.google.android.material.bottomsheet.BottomSheetDialog(this).apply {
                    setContentView(dialogView)
                }
            }

            recyclerSlots.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            class SlotChoiceViewHolder(val v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
                val txtLabel: android.widget.TextView = v.findViewById(R.id.txtSlotLabel)
                val txtSummary: android.widget.TextView = v.findViewById(R.id.txtSlotSummary)
                val card: View = v.findViewById(R.id.cardSlotChoice)
            }

            val slotsList = FileClipboard.slots
            recyclerSlots.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<SlotChoiceViewHolder>() {
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SlotChoiceViewHolder {
                    val view = layoutInflater.inflate(itemLayoutRes, parent, false)
                    return SlotChoiceViewHolder(view)
                }

                override fun getItemCount(): Int = slotsList.size

                override fun onBindViewHolder(holder: SlotChoiceViewHolder, position: Int) {
                    val slot = slotsList.getOrNull(position) ?: return
                    holder.txtLabel.text = slot.label
                    val fileSummary = slot.items.take(3).joinToString(", ") { it.name }
                    holder.txtSummary.text = "${slot.totalCount} item(s) • $fileSummary"

                    if (isOnTv) {
                        val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                        val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())
                        val yellowText = getColor(R.color.tv_button_focused_yellow_text)
                        val whiteText = getColor(R.color.tv_text_primary)
                        holder.card.isFocusable = true
                        holder.card.isFocusableInTouchMode = true
                        holder.card.setOnFocusChangeListener { _, hasFocus ->
                            holder.card.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                            holder.txtLabel.setTextColor(if (hasFocus) yellowText else whiteText)
                        }
                    }

                    holder.card.setOnClickListener {
                        dialog.dismiss()
                        FileClipboard.addLocalToSlot(slot.id, selected, op)
                        showPremiumSnackbar(getString(if (isMove) R.string.clipboard_cut else R.string.clipboard_copied, selected.size))
                        updatePasteFab()
                    }
                }
            }

            btnNewSlot.setOnClickListener {
                dialog.dismiss()
                if (FileClipboard.isFull) {
                    android.widget.Toast.makeText(this, R.string.clipboard_full_paste_first, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    FileClipboard.pushLocalSlot(selected, op, currentDir.absolutePath)
                    if (FileClipboard.slots.size == 9) {
                        android.widget.Toast.makeText(this, R.string.clipboard_warning_one_slot_left, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    showPremiumSnackbar(getString(R.string.clipboard_slot_created, selected.size, FileClipboard.slots.size))
                    updatePasteFab()
                }
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            if (isOnTv) {
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            dialog.show()
        }
    }

    /**
     * Handles the result from the Quick Transfer destination picker.
     * Temporarily sets the clipboard to the pending files, swaps [currentDir] to
     * the chosen destination, calls [performPaste], then restores state.
     */
    private fun handleQuickTransferResult(result: androidx.activity.result.ActivityResult) {
        pendingQuickTransferFiles = null   // clear stash regardless of outcome

        if (result.resultCode != android.app.Activity.RESULT_OK) {
            // User cancelled â€” clear the clipboard we pre-loaded in launchQuickTransferPicker
            FileClipboard.clear()
            updatePasteFab()
            return
        }

        val successCount = result.data?.getIntExtra("QT_SUCCESS_COUNT", -1) ?: -1
        val failCount = result.data?.getIntExtra("QT_FAIL_COUNT", -1) ?: -1

        // Destination: transfer was already executed inside the destination Activity.
        // Clipboard was cleared there too. Nothing more to do here.
        updatePasteFab()
        loadDirectory(currentDir)
        
        if (successCount >= 0 && failCount >= 0) {
            if (failCount == 0 && successCount > 0) showPremiumSnackbar(getString(R.string.paste_success, successCount))
            else if (failCount > 0) showPremiumSnackbar(getString(R.string.paste_error))
        }
    }

    /**
     * Fetches one page of category files from the Room DB and delivers them to the adapter.
     *
     * @param append  false → replaces the adapter list (first page); true → appends (subsequent pages)
     *
     * Each call advances [categoryPage] by 1.  Once the DB returns fewer than [CATEGORY_PAGE_SIZE]
     * rows, [categoryAllLoaded] is set to true so the scroll listener stops triggering new fetches.
     */
    private suspend fun loadCategoryPage(append: Boolean) {
        val db = UfmIndexingDatabase.getInstance(applicationContext)
        val dao = db.fileIndexDao()
        val offset = categoryPage * CATEGORY_PAGE_SIZE

        val fileIndices = withContext(Dispatchers.IO) {
            dao.getFilesByCategory(
                storageId  = storageId,
                filterType = filterType.ordinal,
                limit      = CATEGORY_PAGE_SIZE,
                offset     = offset
            )
        }

        // Mark exhausted if we got fewer rows than requested
        if (fileIndices.size < CATEGORY_PAGE_SIZE) {
            categoryAllLoaded = true
        }
        categoryPage++

        withContext(Dispatchers.IO) {
            val showHidden  = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
            val hiddenPaths = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase
                .getInstance(applicationContext).hiddenFileDao().getAllPaths().toSet()
            val files = fileIndices.map { File(it.path) }
                .filter { it.exists() && isFileVisible(it, showHidden, hiddenPaths) }

            withContext(Dispatchers.Main) {
                if (append) {
                    fileAdapter.appendList(files)
                } else {
                    fileAdapter.submitList(files, showAllAsIndexed = true, hiddenPaths = hiddenPaths)
                    updateEmptyState(files.isEmpty())
                    applyFileFocus()
                }
            }
        }
    }

    private var lastLoadedPath: String? = null
    private val folderScrollStates = mutableMapOf<String, FolderScrollState>()

    private fun saveCurrentFolderScroll(targetChildPath: String? = null) {
        if (!::recyclerFiles.isInitialized || !::currentDir.isInitialized) return
        val state = FolderScrollState.capture(recyclerFiles, targetChildPath)
        if (state != null) {
            folderScrollStates[currentDir.absolutePath] = state
        }
    }

    private fun submitAdapterList(action: () -> Unit) {
        val currentPath = currentDir.absolutePath
        val oldPath = lastLoadedPath
        val isNavigatingFolder = oldPath != null && oldPath != currentPath
        lastLoadedPath = currentPath

        // 1. Capture scroll position for same-folder reloads (rename, delete, paste, Room Flow update)
        val lm = if (!isNavigatingFolder) recyclerFiles.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager else null
        val sameFolderPosition = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val sameFolderOffset = if (sameFolderPosition != RecyclerView.NO_POSITION) {
            lm?.findViewByPosition(sameFolderPosition)?.top ?: 0
        } else 0

        // 2. Lookup saved scroll state if returning to a previously visited folder
        val restoredFolderState = if (isNavigatingFolder) folderScrollStates[currentPath] else null

        val restoreScroll = {
            if (!isNavigatingFolder && sameFolderPosition != RecyclerView.NO_POSITION) {
                recyclerFiles.post {
                    (recyclerFiles.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.scrollToPositionWithOffset(sameFolderPosition, sameFolderOffset)
                }
            } else if (restoredFolderState != null) {
                if (isTv) {
                    val targetChild = restoredFolderState.targetChildPath
                    val targetPos = if (targetChild != null) fileAdapter.findPosition(targetChild) else -1
                    val focusPos = if (targetPos != -1) targetPos else restoredFolderState.position.coerceIn(0, (fileAdapter.itemCount - 1).coerceAtLeast(0))
                    if (fileAdapter.itemCount > 0) {
                        recyclerFiles.scrollToPosition(focusPos)
                        recyclerFiles.post {
                            val holder = recyclerFiles.findViewHolderForAdapterPosition(focusPos)
                            if (holder != null) {
                                holder.itemView.requestFocus()
                                recyclerFiles.isFocusable = false
                                recyclerFiles.isFocusableInTouchMode = false
                            } else {
                                recyclerFiles.isFocusable = true
                                recyclerFiles.isFocusableInTouchMode = true
                                recyclerFiles.requestFocus()
                            }
                        }
                    }
                } else {
                    recyclerFiles.post {
                        (recyclerFiles.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                            ?.scrollToPositionWithOffset(restoredFolderState.position, restoredFolderState.offset)
                    }
                }
            } else if (isNavigatingFolder && !isTv) {
                recyclerFiles.post {
                    (recyclerFiles.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                }
            }
        }

        val updateAdapter = {
            action()
            restoreScroll()
        }

        if (isNavigatingFolder && ::recyclerFiles.isInitialized && za.kilowatch.ultimatefilemanager.util.AnimationHelper.areFolderTransitionsEnabled(this)) {
            val isForward = currentPath.length > (oldPath?.length ?: 0)
            za.kilowatch.ultimatefilemanager.util.AnimationHelper.animateFolderTransition(recyclerFiles, isForward) {
                action()
                restoreScroll()
            }
        } else {
            updateAdapter()
        }
    }

    private fun navigateBack() {
        // If in selection mode, exit it first
        if (fileAdapter.isSelectionMode) {
            fileAdapter.exitSelectionMode()
            return
        }
        if (currentDir.absolutePath != rootPath) {
            currentDir.parentFile?.let { parent ->
                loadDirectory(parent)
                return
            }
        }
        if (isTaskRoot) {
            val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
            za.kilowatch.ultimatefilemanager.util.AnimationHelper.startActivityWithTransition(this, intent)
        }
        finish()
        za.kilowatch.ultimatefilemanager.util.AnimationHelper.applyActivityCloseTransition(this)
    }

    private fun triggerReindex() {
        val ctx = this
        val currentPath = currentDir.absolutePath
        if (UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId) || storageId.isEmpty()) {
            loadDirectory(currentDir)
            android.widget.Toast.makeText(ctx, R.string.refresh, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val isTv = DeviceUtils.isTvDevice(ctx)
        val optionsDialogView = LayoutInflater.from(ctx).inflate(
            if (isTv) R.layout.dialog_reindex_options_tv else R.layout.dialog_reindex_options, 
            null
        )

        val optionsDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
            .setView(optionsDialogView)
            .setCancelable(true)
            .create()

        optionsDialogView.findViewById<View>(R.id.btnOptionShallow).setOnClickListener {
            optionsDialog.dismiss()
            startReindexingFlow(currentPath, recursive = false)
        }

        optionsDialogView.findViewById<View>(R.id.btnOptionRecursive).setOnClickListener {
            optionsDialog.dismiss()
            startReindexingFlow(currentPath, recursive = true)
        }

        optionsDialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            optionsDialog.dismiss()
        }

        optionsDialog.show()

        if (isTv) {
            optionsDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            val widthPx = (800 * ctx.resources.displayMetrics.density).toInt()
            val screenWidth = ctx.resources.displayMetrics.widthPixels
            val finalWidth = minOf(widthPx, (screenWidth * 0.85).toInt())
            optionsDialog.window?.setLayout(finalWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun startReindexingFlow(currentPath: String, recursive: Boolean) {
        val ctx = this
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_reindex_progress, null)
        
        val msgView = dialogView.findViewById<TextView>(R.id.txtMessage)
        if (recursive) {
            msgView.setText(R.string.reindexing_msg)
        } else {
            msgView.setText(R.string.reindex_option_shallow_desc)
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    UfmApplication.indexingRepository.reindexFolder(
                        folderPath = currentPath,
                        storageId = storageId,
                        storageType = storageType,
                        recursive = recursive
                    )
                }
                loadDirectory(currentDir)
                android.widget.Toast.makeText(ctx, R.string.reindexing_complete, android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("FileBrowser", "Re-index error: ${e.message}")
            } finally {
                dialog.dismiss()
            }
        }
    }

    private fun setupViews() {
        val isTv = DeviceUtils.isTvDevice(this)
        toolbar = findViewById(R.id.toolbar)
        recyclerFiles = findViewById(R.id.recyclerFiles)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        lottieEmptyFolder = findViewById(R.id.lottieEmptyFolder)
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
        btnHide = findViewById(R.id.btnHide)
        btnUnhide = findViewById(R.id.btnUnhide)
        btnProtect = findViewById(R.id.btnProtect)
        btnUnprotect = findViewById(R.id.btnUnprotect)
        btnPin = findViewById(R.id.btnPin)
        btnUnpin = findViewById(R.id.btnUnpin)
        btnCompress = findViewById(R.id.btnCompress)
        btnExtract = findViewById(R.id.btnExtract)
        btnImageCompress = findViewById(R.id.btnImageCompress)
        btnRetriggerThumbnails = findViewById(R.id.btnRetriggerThumbnails)
        btnDuplicateFinder = findViewById(R.id.btnDuplicateFinder)
        btnLargeFilesFinder = findViewById(R.id.btnLargeFilesFinder)
        fabPaste = findViewById(R.id.fabPaste)

        fabTools = findViewById(R.id.fabTools)

        // Apply custom toolbar action icons
        applyCustomToolbarIcons()

        btnSearchToggle = findViewById(R.id.btnSearchToggle)
        btnSearchToggle.setImageResource(R.drawable.ic_search)
        if (isTv) {
            btnSearchToggle.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_denied)) // Initial state: red on TV
        }
        layoutSearchRow = findViewById(R.id.layoutSearchRow)
        edtSearch = findViewById(R.id.edtSearch)
        btnSearchClear = findViewById(R.id.btnSearchClear)

        // Modern back handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTransferring) {
                    showPremiumSnackbar(getString(R.string.please_wait_for_the_transfer_to_finish_or_press_cancel_on_the_dialog))
                    return
                }
                navigateBack()
            }
        })

        if (isTv) {
            val iconTintFocused = android.content.res.ColorStateList.valueOf(
                getColor(R.color.tv_button_focused_yellow_text)  // near-black
            )
            val iconTintDefault = android.content.res.ColorStateList.valueOf(
                getColor(R.color.tv_text_primary)  // white
            )

            fun wireTvIconBtn(id: Int, onClick: () -> Unit) {
                val btn = findViewById<android.widget.ImageView>(id) ?: return
                btn.imageTintList = iconTintDefault  // set initial white tint (app:tint removed from XML)
                btn.setOnClickListener { onClick() }
                btn.setOnFocusChangeListener { _, hasFocus ->
                    btn.imageTintList = if (hasFocus) iconTintFocused else iconTintDefault
                }
            }

            wireTvIconBtn(R.id.btnTvBack) { navigateBack() }
            wireTvIconBtn(R.id.btnCreateNew) { showCreateNewMenu() }
            
            val btnSortTv = findViewById<ImageView?>(R.id.btnSort)
            btnSortTv?.setOnClickListener { showSortFilterSheet() }
            btnSortTv?.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    btnSortTv.imageTintList = iconTintFocused
                } else {
                    val hasOverride = SortFilterPreferenceManager.hasFolderOverride(this, currentDir.absolutePath)
                    btnSortTv.imageTintList = android.content.res.ColorStateList.valueOf(
                        getColor(
                            if (hasOverride) R.color.tv_button_focused_yellow else R.color.tv_text_primary
                        )
                    )
                }
            }

            wireTvIconBtn(R.id.btnSearchToggle) { toggleSearch() }
            wireTvIconBtn(R.id.btnRefreshIndex) { triggerReindex() }
            wireTvIconBtn(R.id.btnViewToggle) {
                ViewModeManager.showSelectionDialog(this, fileAdapter.viewMode) { selectedMode ->
                    val folderKey = SortFilterPreferenceManager.folderKey(currentDir.absolutePath)
                    if (SortFilterPreferenceManager.hasFolderOverride(this, currentDir.absolutePath)) {
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val state = SortFilterPreferenceManager.loadForPath(this@FileBrowserActivity, currentDir.absolutePath)
                            if (state != null) {
                                SortFilterPreferenceManager.saveFolderSpecific(
                                    this@FileBrowserActivity, folderKey, currentDir.absolutePath,
                                    state.copy(viewMode = selectedMode), isNetwork = false
                                )
                            }
                        }
                    } else {
                        ViewModeManager.save(this, selectedMode)
                    }
                    applyViewMode(selectedMode)
                }
            }

            // Twin Window: launch with current directory as top pane (TV)
            wireTvIconBtn(R.id.btnTwinWindow) {
                val intent = Intent(this, TwinWindowActivity::class.java).apply {
                    putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_PATH, rootPath)
                    putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_LABEL, storageLabel)
                    putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_INITIAL_PATH, currentDir.absolutePath)
                }
                startActivity(intent)
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

            // FAB focus: toggle yellow bg + black text/icon on focus
            val fabYellowBg = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
            val fabDefaultBg = fabPaste.backgroundTintList  // preserve original tint
            fabPaste.setOnFocusChangeListener { _, hasFocus ->
                fabPaste.backgroundTintList = if (hasFocus) fabYellowBg else fabDefaultBg
                fabPaste.setTextColor(
                    if (hasFocus) getColor(R.color.tv_button_focused_yellow_text)
                    else getColor(R.color.tv_text_primary)
                )
                fabPaste.iconTint = if (hasFocus) iconTintFocused else iconTintDefault
            }
        } else {
            // Mobile: wire up header bar buttons directly
            val btnBack = findViewById<android.widget.ImageView>(R.id.btnTvBack)
            btnBack?.setOnClickListener { navigateBack() }
            
            btnOptionsToggle = findViewById(R.id.btnOptionsToggle)
            btnOptionsToggle?.setImageResource(R.drawable.ic_more_vert)
            btnOptionsToggle?.setOnClickListener { v ->
                showMobileOptionsPopupMenu(v)
            }
            
            val btnCreateNew = findViewById<android.widget.ImageView>(R.id.btnCreateNew)
            btnCreateNew?.setOnClickListener { showCreateNewMenu() }

            btnViewToggle = findViewById(R.id.btnViewToggle)
            if (!isTv) {
                btnViewToggle?.setOnClickListener {
                    ViewModeManager.showSelectionDialog(this, fileAdapter.viewMode) { selectedMode ->
                        val folderKey = SortFilterPreferenceManager.folderKey(currentDir.absolutePath)
                        if (SortFilterPreferenceManager.hasFolderOverride(this, currentDir.absolutePath)) {
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val state = SortFilterPreferenceManager.loadForPath(this@FileBrowserActivity, currentDir.absolutePath)
                                if (state != null) {
                                    SortFilterPreferenceManager.saveFolderSpecific(
                                        this@FileBrowserActivity, folderKey, currentDir.absolutePath,
                                        state.copy(viewMode = selectedMode), isNetwork = false
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
            
            btnSearchToggle.setOnClickListener {
                toggleSearch()
            }

            findViewById<android.widget.ImageView>(R.id.btnRefreshIndex)?.setOnClickListener {
                triggerReindex()
            }
            
            // Twin Window: launch with current directory as top pane
            findViewById<android.widget.ImageView>(R.id.btnTwinWindow)?.setOnClickListener {
                val intent = Intent(this, TwinWindowActivity::class.java).apply {
                    putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_PATH, rootPath)
                    putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_LABEL, storageLabel)
                    putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_INITIAL_PATH, currentDir.absolutePath)
                }
                startActivity(intent)
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
        }
        fileAdapter = FileAdapter(
            isTv = isTv,
            onItemClick = { file, transitionView ->
                if (file.isDirectory) {
                    saveCurrentFolderScroll(targetChildPath = file.absolutePath)
                    folderScrollStates.remove(file.absolutePath)
                    loadDirectory(file)
                } else if (isKeyfilePickerMode) {
                    selectedKeyFilePath = file.absolutePath
                    fabPaste.visibility = View.VISIBLE
                    fabPaste.text = getString(R.string.use_this_key_file)
                } else if (isCertPickerMode) {
                    selectedKeyFilePath = file.absolutePath
                    fabPaste.visibility = View.VISIBLE
                    fabPaste.text = getString(R.string.remote_use_ca)
                } else if (isSupportAttachmentPicker) {
                    selectedKeyFilePath = file.absolutePath
                    fabPaste.visibility = View.VISIBLE
                    fabPaste.text = getString(R.string.support_attach_file)
                    // Highlight the selected file
                    fileAdapter.focusedPath = file.absolutePath
                    fileAdapter.notifyDataSetChanged()
                } else if (isPickerMode) {
                    // Return selected file path to caller
                    val resultIntent = Intent().apply {
                        putExtra(RESULT_SELECTED_PATH, file.absolutePath)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                } else {
                    openFile(file, transitionView)
                }
            },
            onSelectionChanged = { count ->
                updateSelectionBar(count)
            }
        )

        recyclerFiles.adapter = fileAdapter
        fileAdapter.isGroupedByDate = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(this)

        keyboardShortcutHandler = KeyboardShortcutHandler(this, object : KeyboardShortcutHandler.KeyboardActionListener {
            override fun onMoveDown() {
                val currentPos = fileAdapter.findPosition(fileAdapter.focusedPath)
                val nextPos = if (currentPos == -1) 0 else (currentPos + 1).coerceAtMost(fileAdapter.itemCount - 1)
                val item = fileAdapter.getItemAt(nextPos) as? ListItem.FileEntry
                if (item != null) {
                    fileAdapter.focusedPath = item.javaFile.absolutePath
                    recyclerFiles.scrollToPosition(nextPos)
                }
            }

            override fun onMoveUp() {
                val currentPos = fileAdapter.findPosition(fileAdapter.focusedPath)
                val prevPos = if (currentPos == -1) 0 else (currentPos - 1).coerceAtLeast(0)
                val item = fileAdapter.getItemAt(prevPos) as? ListItem.FileEntry
                if (item != null) {
                    fileAdapter.focusedPath = item.javaFile.absolutePath
                    recyclerFiles.scrollToPosition(prevPos)
                }
            }

            override fun onOpen() {
                val path = fileAdapter.focusedPath
                if (path != null) {
                    val file = File(path)
                    if (file.isDirectory) {
                        saveCurrentFolderScroll(targetChildPath = file.absolutePath)
                        folderScrollStates.remove(file.absolutePath)
                        loadDirectory(file)
                    } else openFile(file, null)
                }
            }

            override fun onParentDir() {
                if (::currentDir.isInitialized && currentDir.absolutePath != rootPath) {
                    val parent = currentDir.parentFile
                    if (parent != null) loadDirectory(parent)
                } else {
                    finish()
                }
            }

            override fun onJumpTop() {
                if (fileAdapter.itemCount > 0) {
                    val item = fileAdapter.getItemAt(0) as? ListItem.FileEntry
                    if (item != null) {
                        fileAdapter.focusedPath = item.javaFile.absolutePath
                        recyclerFiles.scrollToPosition(0)
                    }
                }
            }

            override fun onJumpBottom() {
                if (fileAdapter.itemCount > 0) {
                    val lastIdx = fileAdapter.itemCount - 1
                    val item = fileAdapter.getItemAt(lastIdx) as? ListItem.FileEntry
                    if (item != null) {
                        fileAdapter.focusedPath = item.javaFile.absolutePath
                        recyclerFiles.scrollToPosition(lastIdx)
                    }
                }
            }

            override fun onGoToPath() {
                showGoToPathDialog()
            }

            override fun onToggleSelect() {
                val path = fileAdapter.focusedPath
                if (path != null) {
                    val pos = fileAdapter.findPosition(path)
                    if (pos != -1) fileAdapter.enterSelectionModeAt(pos)
                }
            }

            override fun onSelectAll() {
                if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
            }

            override fun onCopy() {
                val selected = fileAdapter.getSelectedFiles().ifEmpty {
                    fileAdapter.focusedPath?.let { listOf(File(it)) } ?: emptyList()
                }
                if (selected.isNotEmpty()) handleCopyOrCut(selected, isMove = false)
            }

            override fun onCut() {
                val selected = fileAdapter.getSelectedFiles().ifEmpty {
                    fileAdapter.focusedPath?.let { listOf(File(it)) } ?: emptyList()
                }
                if (selected.isNotEmpty()) handleCopyOrCut(selected, isMove = true)
            }

            override fun onPaste() {
                if (FileClipboard.hasItems()) {
                    showClipboardSheet()
                }
            }

            override fun onDelete() {
                val selected = fileAdapter.getSelectedFiles().ifEmpty {
                    fileAdapter.focusedPath?.let { listOf(File(it)) } ?: emptyList()
                }
                if (selected.isNotEmpty()) {
                    findViewById<View?>(R.id.btnDelete)?.performClick()
                }
            }

            override fun onRename() {
                findViewById<View?>(R.id.btnRename)?.performClick()
            }

            override fun onNewFolder() {
                findViewById<View?>(R.id.btnCreateNew)?.performClick()
            }

            override fun onSearch() {
                findViewById<ImageView?>(R.id.btnSearchToggle)?.performClick()
            }

            override fun onToggleHidden() {
                za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled =
                    !za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
                loadDirectory(currentDir)
            }

            override fun onRefresh() {
                lifecycleScope.launch(Dispatchers.IO) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.invalidatePath(currentDir.absolutePath)
                    syncFolderWithIndex(currentDir)
                    withContext(Dispatchers.Main) {
                        loadDirectory(currentDir)
                        showPremiumSnackbar(getString(R.string.refresh))
                    }
                }
            }

            override fun onCheatsheet() {
                KeyboardShortcutDialog.show(this@FileBrowserActivity)
            }

            override fun onEscape() {
                if (fileAdapter.isSelectionMode) {
                    fileAdapter.exitSelectionMode()
                } else {
                    finish()
                }
            }
        })
        
        // Load and apply initial view mode
        val initialMode = ViewModeManager.load(this)
        applyViewMode(initialMode)

        // Picker mode FAB setup: configure and return early to avoid overwriting FAB click listeners
        if (isExtractDestPickerMode || isCompressDestPickerMode || isImageCompressDestPickerMode ||
            isGifCreatorDestPickerMode || isSyncFolderPickerMode || isAdvancedSyncFolderPickerMode ||
            isAdvancedSyncDestPickerMode || isLocationPickerMode || isNetworkCachePickerMode ||
            isQuickTransferPickerMode || isShareDestPickerMode || isNotepadFolderPicker ||
            isScannerFolderPicker || isAutoBackupFolderPicker || isSupportAttachmentPicker ||
            isKeyfilePickerMode || isCertPickerMode || isSmartSortPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            applyPickerFabState()
            if (isKeyfilePickerMode || isCertPickerMode || isSupportAttachmentPicker || isSmartSortPickerMode) {
                findViewById<android.widget.ImageView>(R.id.btnCreateNew)?.visibility = View.GONE
                findViewById<android.widget.ImageView>(R.id.btnViewToggle)?.visibility = View.GONE
                findViewById<android.widget.ImageView>(R.id.btnSort)?.visibility = View.GONE
            }
            return
        }

        // Hide editing controls in standard file picker mode
        if (isPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnCreateNew)?.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnViewToggle)?.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnSort)?.visibility = View.GONE
            return // Skip wiring selection/paste buttons
        }

        // Selection bar actions
        btnCloseSelection.setOnClickListener {
            fileAdapter.exitSelectionMode()
        }

        btnSelectAll.setOnClickListener {
            if (fileAdapter.isAllSelected()) {
                fileAdapter.deselectAll()
            } else {
                fileAdapter.selectAll()
            }
        }

        btnDelete.setOnClickListener {
            showDeleteConfirmation()
        }

        btnCopy.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            handleCopyOrCut(selected, isMove = false)
        }

        btnMove.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            handleCopyOrCut(selected, isMove = true)
        }

        btnRename.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isEmpty()) return@setOnClickListener
            if (selected.size == 1) {
                showRenameDialog(selected.first())
            } else {
                val items = selected.map { BatchRenameItem.fromLocalFile(it) }
                if (DeviceUtils.isTvDevice(this)) {
                    val intent = Intent(this, BatchRenameTvActivity::class.java).apply {
                        putParcelableArrayListExtra("items", ArrayList(items))
                    }
                    batchRenameTvLauncher.launch(intent)
                } else {
                    val dialog = BatchRenameDialogFragment.newInstance(items)
                    dialog.setOnCompleteListener { _, _ ->
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                    }
                    dialog.show(supportFragmentManager, BatchRenameDialogFragment.TAG)
                }
            }
        }

        btnShare.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles().filter { it.isFile }
            if (selected.isEmpty()) return@setOnClickListener
            shareFiles(selected)
        }

        btnCopyEncrypt.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                showVaultPickerForEncrypt(selected, isMove = false)
            }
        }

        btnMoveEncrypt.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                showVaultPickerForEncrypt(selected, isMove = true)
            }
        }
        
        btnFavorite.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.size == 1) {
                showFavoriteDialog(selected.first())
            }
        }

        btnCompress.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                showArchiveOptions(selected)
            }
        }

        btnExtract?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                performExtractHere(selected)
            }
        }

        btnImageCompress.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                startActivity(android.content.Intent(this, za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity::class.java).apply {
                    putStringArrayListExtra(
                        za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity.EXTRA_FILE_PATHS,
                        java.util.ArrayList(selected.map { it.absolutePath })
                    )
                })
            }
        }

        btnHide.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.hide(file.absolutePath)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                        showPremiumSnackbar(getString(R.string.toast_hidden_success, selected.size))
                    }
                }
            }
        }



        btnUnhide.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.unhide(file.absolutePath)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                        showPremiumSnackbar(getString(R.string.toast_unhidden_success, selected.size))
                    }
                }
            }
        }

        btnProtect.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(this@FileBrowserActivity, file.absolutePath, protected = true)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
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
                        za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(this@FileBrowserActivity, file.absolutePath, protected = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
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
                        za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(this@FileBrowserActivity, file.absolutePath, pinned = true)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
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
                        za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(this@FileBrowserActivity, file.absolutePath, pinned = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                        showPremiumSnackbar(getString(R.string.toast_unpinned_success, selected.size))
                    }
                }
            }
        }

        fabPaste.setOnClickListener {
            showClipboardSheet()
        }

        btnRetriggerThumbnails?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        if (file.isDirectory) {
                            FileAdapter.clearCacheForFolder(file.absolutePath)
                        } else {
                            FileAdapter.clearCacheForPath(file.absolutePath)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                        showPremiumSnackbar(getString(R.string.retrigger_thumbnails_success))
                    }
                }
            }
        }

        btnDuplicateFinder?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.size == 1 && selected.first().isDirectory) {
                val targetFolder = selected.first()
                val (folderStorageId, _, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(targetFolder.absolutePath)
                val isFolderIndexed = folderStorageId.isNotEmpty() && UfmApplication.indexingRepository.isStorageFullyIndexed(folderStorageId)
                if (isFolderIndexed) {
                    fileAdapter.exitSelectionMode()
                    val intent = Intent(this@FileBrowserActivity, FolderDuplicateFinderActivity::class.java).apply {
                        putExtra(FolderDuplicateFinderActivity.EXTRA_FOLDER_PATH, targetFolder.absolutePath)
                        putExtra(FolderDuplicateFinderActivity.EXTRA_STORAGE_ID, folderStorageId)
                    }
                    folderDuplicateFinderLauncher.launch(intent)
                }
            }
        }

        btnLargeFilesFinder?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.size == 1 && selected.first().isDirectory) {
                val targetFolder = selected.first()
                val (folderStorageId, _, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(targetFolder.absolutePath)
                val isFolderIndexed = folderStorageId.isNotEmpty() && UfmApplication.indexingRepository.isStorageFullyIndexed(folderStorageId)
                if (isFolderIndexed) {
                    fileAdapter.exitSelectionMode()
                    val intent = Intent(this@FileBrowserActivity, FolderLargeFilesFinderActivity::class.java).apply {
                        putExtra(FolderLargeFilesFinderActivity.EXTRA_FOLDER_PATH, targetFolder.absolutePath)
                        putExtra(FolderLargeFilesFinderActivity.EXTRA_STORAGE_ID, folderStorageId)
                    }
                    folderLargeFilesFinderLauncher.launch(intent)
                }
            }
        }


        fabProperties?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                val sheet = FilePropertiesBottomSheet.newInstanceForLocalFiles(selected)
                sheet.show(supportFragmentManager, FilePropertiesBottomSheet.TAG)
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
                    handleCopyOrCut(selected, isMove = false)
                })
            }

            // 2. Move (Cut)
            if (pm.isIconEnabled(this, pm.KEY_MOVE)) {
                list.add(FileToolsBottomSheet.ActionItem("move", getString(R.string.action_move), R.drawable.ic_move, "toolbar_move") {
                    handleCopyOrCut(selected, isMove = true)
                })
            }

            // Delete
            if (pm.isIconEnabled(this, pm.KEY_DELETE)) {
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
                        val items = selected.map { BatchRenameItem.fromLocalFile(it) }
                        if (DeviceUtils.isTvDevice(this)) {
                            val intent = Intent(this, BatchRenameTvActivity::class.java).apply {
                                putParcelableArrayListExtra("items", ArrayList(items))
                            }
                            batchRenameTvLauncher.launch(intent)
                        } else {
                            val dialog = BatchRenameDialogFragment.newInstance(items)
                            dialog.setOnCompleteListener { _, _ ->
                                fileAdapter.exitSelectionMode()
                                loadDirectory(currentDir)
                            }
                            dialog.show(supportFragmentManager, BatchRenameDialogFragment.TAG)
                        }
                    }
                })
            }

            // 4. Share
            if (pm.isIconEnabled(this, pm.KEY_SHARE)) {
                val shareable = selected.filter { it.isFile }
                if (shareable.isNotEmpty()) {
                    list.add(FileToolsBottomSheet.ActionItem("share", getString(R.string.action_share), R.drawable.ic_share, "toolbar_share") {
                        shareFiles(shareable)
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

            // Extract Here
            val hasArchiveSelected = selected.any { ArchiveManager.isSupportedArchive(it) }
            if (hasArchiveSelected && pm.isIconEnabled(this, pm.KEY_EXTRACT)) {
                list.add(FileToolsBottomSheet.ActionItem("extract_here", getString(R.string.action_extract_here), R.drawable.ic_extract, "toolbar_extract") {
                    performExtractHere(selected)
                })
            }

            // 7. Compress Image
            val allImages = selected.isNotEmpty() && selected.all {
                it.extension.lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            }
            if (allImages && pm.isIconEnabled(this, pm.KEY_IMAGE_COMPRESS)) {
                list.add(FileToolsBottomSheet.ActionItem("image_compress", getString(R.string.action_compress_image), R.drawable.ic_compress_image, "toolbar_image_compress") {
                    startActivity(Intent(this, za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity::class.java).apply {
                        putStringArrayListExtra(
                            za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity.EXTRA_FILE_PATHS,
                            ArrayList(selected.map { it.absolutePath })
                        )
                    })
                })
            }

            // Create GIF (Requires 2+ images)
            val canCreateGif = selected.size >= 2 && allImages
            if (canCreateGif && pm.isIconEnabled(this, pm.KEY_CREATE_GIF)) {
                list.add(FileToolsBottomSheet.ActionItem("create_gif", getString(R.string.action_create_gif), R.drawable.ic_gif, "toolbar_create_gif") {
                    startActivity(Intent(this, za.kilowatch.ultimatefilemanager.viewer.GifCreatorActivity::class.java).apply {
                        putStringArrayListExtra(
                            za.kilowatch.ultimatefilemanager.viewer.GifCreatorActivity.EXTRA_FILE_PATHS,
                            ArrayList(selected.map { it.absolutePath })
                        )
                    })
                })
            }

            // Photo EXIF Cleaner & Renamer (Mobile Only)
            if (allImages && !DeviceUtils.isTvDevice(this) && pm.isIconEnabled(this, pm.KEY_EXIF_TOOLS)) {
                list.add(FileToolsBottomSheet.ActionItem("exif_tools", getString(R.string.action_exif_cleaner_renamer), R.drawable.ic_exif_cleaner, "toolbar_exif_cleaner") {
                    startActivity(Intent(this, za.kilowatch.ultimatefilemanager.viewer.ExifToolsActivity::class.java).apply {
                        putStringArrayListExtra(
                            za.kilowatch.ultimatefilemanager.viewer.ExifToolsActivity.EXTRA_FILE_PATHS,
                            ArrayList(selected.map { it.absolutePath })
                        )
                    })
                })
            }

            // Wallpaper (Single image file, mobile only)
            val isSingleImage = count == 1 && selected.first().isFile &&
                selected.first().extension.lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            if (isSingleImage && !DeviceUtils.isTvDevice(this)) {
                val imageFile = selected.first()

                // Set Home Wallpaper
                if (pm.isIconEnabled(this, pm.KEY_SET_HOME_WALLPAPER)) {
                    list.add(FileToolsBottomSheet.ActionItem("set_home_wallpaper", getString(R.string.action_set_home_wallpaper), R.drawable.ic_wallpaper_home, "toolbar_set_home_wallpaper") {
                        za.kilowatch.ultimatefilemanager.util.WallpaperHelper.showConfirmDialog(
                            this@FileBrowserActivity,
                            imageFile.name,
                            android.app.WallpaperManager.FLAG_SYSTEM
                        ) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val success = za.kilowatch.ultimatefilemanager.util.WallpaperHelper.setWallpaper(
                                    this@FileBrowserActivity,
                                    imageFile,
                                    android.app.WallpaperManager.FLAG_SYSTEM
                                )
                                withContext(Dispatchers.Main) {
                                    fileAdapter.exitSelectionMode()
                                    val msg = if (success) getString(R.string.toast_wallpaper_set_home_success) else getString(R.string.toast_wallpaper_set_failed)
                                    showPremiumSnackbar(msg)
                                }
                            }
                        }
                    })
                }

                // Set Lock Wallpaper
                if (pm.isIconEnabled(this, pm.KEY_SET_LOCK_WALLPAPER)) {
                    list.add(FileToolsBottomSheet.ActionItem("set_lock_wallpaper", getString(R.string.action_set_lock_wallpaper), R.drawable.ic_wallpaper_lock, "toolbar_set_lock_wallpaper") {
                        za.kilowatch.ultimatefilemanager.util.WallpaperHelper.showConfirmDialog(
                            this@FileBrowserActivity,
                            imageFile.name,
                            android.app.WallpaperManager.FLAG_LOCK
                        ) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val success = za.kilowatch.ultimatefilemanager.util.WallpaperHelper.setWallpaper(
                                    this@FileBrowserActivity,
                                    imageFile,
                                    android.app.WallpaperManager.FLAG_LOCK
                                )
                                withContext(Dispatchers.Main) {
                                    fileAdapter.exitSelectionMode()
                                    val msg = if (success) getString(R.string.toast_wallpaper_set_lock_success) else getString(R.string.toast_wallpaper_set_failed)
                                    showPremiumSnackbar(msg)
                                }
                            }
                        }
                    })
                }
            }

            // 8. Copy Encrypted
            if (pm.isIconEnabled(this, pm.KEY_COPY_ENCRYPT)) {
                list.add(FileToolsBottomSheet.ActionItem("copy_encrypt", getString(R.string.action_copy_encrypt), R.drawable.ic_copy, "toolbar_copy_encrypt") {
                    showVaultPickerForEncrypt(selected, isMove = false)
                })
            }

            // 9. Move Encrypted
            if (pm.isIconEnabled(this, pm.KEY_MOVE_ENCRYPT)) {
                list.add(FileToolsBottomSheet.ActionItem("move_encrypt", getString(R.string.action_move_encrypt), R.drawable.ic_move, "toolbar_move_encrypt") {
                    showVaultPickerForEncrypt(selected, isMove = true)
                })
            }

            // 10. Hide
            val hasVisible = fileAdapter.hasAnySelectedVisible()
            if (hasVisible && pm.isIconEnabled(this, pm.KEY_HIDE)) {
                list.add(FileToolsBottomSheet.ActionItem("hide", getString(R.string.hide), R.drawable.ic_eye_off, "toolbar_hide") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.hide(file.absolutePath)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory(currentDir)
                            showPremiumSnackbar(getString(R.string.toast_hidden_success, selected.size))
                        }
                    }
                })
            }

            // 11. Unhide
            val hasHidden = fileAdapter.hasAnySelectedHidden()
            if (hasHidden && pm.isIconEnabled(this, pm.KEY_UNHIDE)) {
                list.add(FileToolsBottomSheet.ActionItem("unhide", getString(R.string.unhide), R.drawable.ic_eye, "toolbar_unhide") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.unhide(file.absolutePath)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory(currentDir)
                            showPremiumSnackbar(getString(R.string.toast_unhidden_success, selected.size))
                        }
                    }
                })
            }

            // 12. Protect
            val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(this)
            if (hasUnprotected && pm.isIconEnabled(this, pm.KEY_PROTECT)) {
                list.add(FileToolsBottomSheet.ActionItem("protect", getString(R.string.protect), R.drawable.ic_shield_protected, "toolbar_protect") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(this@FileBrowserActivity, file.absolutePath, protected = true)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory(currentDir)
                            showPremiumSnackbar(getString(R.string.toast_protected_success, selected.size))
                        }
                    }
                })
            }

            // 13. Unprotect
            val hasProtectedItem = fileAdapter.hasAnySelectedProtected(this)
            if (hasProtectedItem && pm.isIconEnabled(this, pm.KEY_UNPROTECT)) {
                list.add(FileToolsBottomSheet.ActionItem("unprotect", getString(R.string.unprotect), R.drawable.ic_shield_unprotected, "toolbar_unprotect") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(this@FileBrowserActivity, file.absolutePath, protected = false)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory(currentDir)
                            showPremiumSnackbar(getString(R.string.toast_unprotected_success, selected.size))
                        }
                    }
                })
            }

            // Pin
            val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(this)
            if (hasUnpinned && pm.isIconEnabled(this, pm.KEY_PIN)) {
                list.add(FileToolsBottomSheet.ActionItem("pin", getString(R.string.pin), R.drawable.ic_paperclip, "toolbar_pin") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(this@FileBrowserActivity, file.absolutePath, pinned = true)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory(currentDir)
                            showPremiumSnackbar(getString(R.string.toast_pinned_success, selected.size))
                        }
                    }
                })
            }

            // Unpin
            val hasPinned = fileAdapter.hasAnySelectedPinned(this)
            if (hasPinned && pm.isIconEnabled(this, pm.KEY_UNPIN)) {
                list.add(FileToolsBottomSheet.ActionItem("unpin", getString(R.string.unpin), R.drawable.ic_paperclip_off, "toolbar_unpin") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(this@FileBrowserActivity, file.absolutePath, pinned = false)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory(currentDir)
                            showPremiumSnackbar(getString(R.string.toast_unpinned_success, selected.size))
                        }
                    }
                })
            }

            // 14. Properties
            if (count > 0) {
                list.add(FileToolsBottomSheet.ActionItem("properties", getString(R.string.action_properties), R.drawable.ic_about, "toolbar_properties") {
                    val sheet = FilePropertiesBottomSheet.newInstanceForLocalFiles(selected)
                    sheet.show(supportFragmentManager, FilePropertiesBottomSheet.TAG)
                })
            }

            // 15. Tag
            val isMultiFileOnly = selected.size > 1 && selected.all { !it.isDirectory }
            val prefs = getSharedPreferences("ufm_prefs", MODE_PRIVATE)
            val isMultiTaggingEnabled = prefs.getBoolean("pref_multi_file_tagging", false)
            if (isMultiTaggingEnabled && isMultiFileOnly) {
                list.add(FileToolsBottomSheet.ActionItem("tag", getString(R.string.action_tag), R.drawable.ic_edit, "toolbar_tag") {
                    val filePaths = selected.map { it.absolutePath }
                    FileTagsManager.showMultiFileTagDialog(this@FileBrowserActivity, filePaths) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                    }
                })
            }

            // Retrigger Thumbnails
            val hasVideoOrFolder = selected.isNotEmpty() && selected.any {
                it.isDirectory || it.extension.lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS
            }
            if (hasVideoOrFolder && pm.isIconEnabled(this, pm.KEY_RETRIGGER_THUMBNAILS)) {
                list.add(FileToolsBottomSheet.ActionItem("retrigger_thumbnails", getString(R.string.action_retrigger_thumbnails), R.drawable.ic_photo_video, "toolbar_retrigger_thumbnails") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            if (file.isDirectory) {
                                FileAdapter.clearCacheForFolder(file.absolutePath)
                            } else {
                                FileAdapter.clearCacheForPath(file.absolutePath)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory(currentDir)
                            showPremiumSnackbar(getString(R.string.retrigger_thumbnails_success))
                        }
                    }
                })
            }

            // Duplicate Finder (single folder, indexed storage)
            if (count == 1 && selected.first().isDirectory && pm.isIconEnabled(this, pm.KEY_DUPLICATE_FINDER)) {
                val targetFolder = selected.first()
                val (folderStorageId, _, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(targetFolder.absolutePath)
                val isFolderIndexed = folderStorageId.isNotEmpty() && UfmApplication.indexingRepository.isStorageFullyIndexed(folderStorageId)
                if (isFolderIndexed) {
                    list.add(FileToolsBottomSheet.ActionItem("duplicate_finder", getString(R.string.action_duplicate_finder), R.drawable.ic_duplicate_finder, "toolbar_duplicate_finder") {
                        fileAdapter.exitSelectionMode()
                        val intent = Intent(this@FileBrowserActivity, FolderDuplicateFinderActivity::class.java).apply {
                            putExtra(FolderDuplicateFinderActivity.EXTRA_FOLDER_PATH, targetFolder.absolutePath)
                            putExtra(FolderDuplicateFinderActivity.EXTRA_STORAGE_ID, folderStorageId)
                        }
                        folderDuplicateFinderLauncher.launch(intent)
                    })
                }
            }

            // Large Files Finder (single folder, indexed storage)
            if (count == 1 && selected.first().isDirectory && pm.isIconEnabled(this, pm.KEY_LARGE_FILES_FINDER)) {
                val targetFolder = selected.first()
                val (folderStorageId, _, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(targetFolder.absolutePath)
                val isFolderIndexed = folderStorageId.isNotEmpty() && UfmApplication.indexingRepository.isStorageFullyIndexed(folderStorageId)
                if (isFolderIndexed) {
                    list.add(FileToolsBottomSheet.ActionItem("large_files_finder", getString(R.string.action_large_files_finder), R.drawable.ic_folder_large_files, "toolbar_large_files_finder") {
                        fileAdapter.exitSelectionMode()
                        val intent = Intent(this@FileBrowserActivity, FolderLargeFilesFinderActivity::class.java).apply {
                            putExtra(FolderLargeFilesFinderActivity.EXTRA_FOLDER_PATH, targetFolder.absolutePath)
                            putExtra(FolderLargeFilesFinderActivity.EXTRA_STORAGE_ID, folderStorageId)
                        }
                        folderLargeFilesFinderLauncher.launch(intent)
                    })
                }
            }

            if (list.isNotEmpty()) {
                val title = getString(R.string.action_tools)
                val subtitle = getString(R.string.selection_count, selected.size)
                val sheet = FileToolsBottomSheet.newInstance(list, title, subtitle)
                sheet.show(supportFragmentManager, FileToolsBottomSheet.TAG)
            }
        }

        // TV: swap icon tint on focus for all selection bar icon buttons
        if (DeviceUtils.isTvDevice(this)) {
            val iconTintFocused = android.content.res.ColorStateList.valueOf(
                getColor(R.color.tv_button_focused_yellow_text)
            )
            val iconTintDefault = android.content.res.ColorStateList.valueOf(
                getColor(R.color.tv_text_primary)
            )
            val tvButtons = mutableListOf(btnCloseSelection, btnCopy, btnMove, btnRename, btnFavorite, btnShare,
                   btnCopyEncrypt, btnMoveEncrypt, btnHide, btnUnhide)
            btnRetriggerThumbnails?.let { tvButtons.add(it) }
            btnDuplicateFinder?.let { tvButtons.add(it) }
            btnLargeFilesFinder?.let { tvButtons.add(it) }
            tvButtons.forEach { btn ->
                btn.imageTintList = iconTintDefault  // set initial white tint
                btn.setOnFocusChangeListener { _, hasFocus ->
                    btn.imageTintList = if (hasFocus) iconTintFocused else iconTintDefault
                }
            }
        }

    }

    private fun showSortFilterSheet() {
        val sheet = SortFilterSheet()
        sheet.currentSortMode = sortMode
        sheet.currentSortOrder = sortOrder
        sheet.currentFilterType = filterType
        sheet.currentGroupByDate = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(this)
        sheet.activeTags = activeTagsFilter

        val folderKey = SortFilterPreferenceManager.folderKey(currentDir.absolutePath)
        sheet.currentFolderKey = folderKey
        sheet.currentFolderDisplayPath = currentDir.absolutePath
        val hasFolderOverride = SortFilterPreferenceManager.hasFolderOverride(this, currentDir.absolutePath)
        sheet.currentScope = if (hasFolderOverride) SortFilterSheet.Scope.FOLDER else SortFilterSheet.Scope.GLOBAL
        
        val activeState = if (hasFolderOverride) {
            SortFilterPreferenceManager.loadForPath(this, currentDir.absolutePath)
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
            if (scope == SortFilterSheet.Scope.GLOBAL) {
                za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled = showHidden
            }

            val state = SortFilterPreferenceManager.SortFilterState(
                mode, order, filter, showHidden, groupByDate, tags,
                viewMode = if (scope == SortFilterSheet.Scope.FOLDER) selectedViewMode else null,
                isRecursive = if (scope == SortFilterSheet.Scope.FOLDER) isRecursive else false
            )
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (scope == SortFilterSheet.Scope.FOLDER) {
                    SortFilterPreferenceManager.saveFolderSpecific(
                        this@FileBrowserActivity, folderKey, currentDir.absolutePath, state, isNetwork = false)
                } else {
                    SortFilterPreferenceManager.saveGlobal(this@FileBrowserActivity, state)
                    ViewModeManager.save(this@FileBrowserActivity, selectedViewMode)
                    SortFilterPreferenceManager.clearFolderSpecific(this@FileBrowserActivity, folderKey)
                }
                val hasFolderOverrideNow = SortFilterPreferenceManager.hasFolderOverride(this@FileBrowserActivity, currentDir.absolutePath)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    updateSortBadge(hasFolderOverrideNow)
                }
            }

            if (groupByDate != za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(this)) {
                za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.setEnabled(this, groupByDate)
                fileAdapter.isGroupedByDate = groupByDate
            }
            applyViewMode(selectedViewMode)
            loadDirectory(currentDir)
        }
        sheet.show(supportFragmentManager, SortFilterSheet.TAG)
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

    private fun updateSelectionBar(count: Int) {
        val showSelection = fileAdapter.isSelectionMode
        val isTv = DeviceUtils.isTvDevice(this)

        if (!isTv) {
            val layoutHeaderNormal = findViewById<View>(R.id.layoutHeaderNormal)
            val layoutHeaderSelection = findViewById<View>(R.id.layoutHeaderSelection)

            if (showSelection) {
                val showActions = count > 0
                layoutHeaderNormal?.visibility = View.GONE
                layoutHeaderSelection?.visibility = View.VISIBLE
                layoutSelectionBar.visibility = View.GONE
                txtSelectionCount.text = if (count == 0) getString(R.string.selection_prompt_select_item) else getString(R.string.selection_count, count)

                val isAll = fileAdapter.isAllSelected()
                if (btnSelectAll is ImageView) {
                    (btnSelectAll as ImageView).setImageResource(if (isAll) R.drawable.ic_deselect_all else R.drawable.ic_select_all)
                    (btnSelectAll as ImageView).contentDescription = getString(if (isAll) R.string.action_deselect_all else R.string.action_select_all)
                } else if (btnSelectAll is MaterialButton) {
                    (btnSelectAll as MaterialButton).text = if (isAll) getString(R.string.action_deselect_all) else getString(R.string.action_select_all)
                }

                fabTools?.visibility = if (showActions) View.VISIBLE else View.GONE
                fabProperties?.visibility = View.GONE
                updatePasteFab()
            } else {
                layoutHeaderNormal?.visibility = View.VISIBLE
                layoutHeaderSelection?.visibility = View.GONE
                layoutSelectionBar.visibility = View.GONE
                fabProperties?.visibility = View.GONE
                fabTools?.visibility = View.GONE
                updatePasteFab()
            }
            return
        }

        if (showSelection) {
            val showActions = count > 0
            layoutSelectionBar.visibility = View.VISIBLE
            txtSelectionCount.text = if (count == 0) getString(R.string.selection_prompt_select_item) else getString(R.string.selection_count, count)
            
            val row2 = btnCopy.parent.parent as? View
            val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
            val hasHidden = fileAdapter.hasAnySelectedHidden()
            val hasVisible = fileAdapter.hasAnySelectedVisible()
            val hasProtected = fileAdapter.hasAnySelectedProtected(this)
            val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(this)
            val hasPinned = fileAdapter.hasAnySelectedPinned(this)
            val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(this)

            fabTools?.visibility = View.GONE
            if (showActions) {
                za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.stopAnimation(layoutSelectionBar)
                row2?.visibility = View.VISIBLE
            } else {
                row2?.visibility = View.GONE
                za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.startAnimation(layoutSelectionBar)
            }
            
            // TV-only icon/row visibility
            btnHide.visibility = if (showActions && hasVisible && pm.isIconEnabled(this, pm.KEY_HIDE)) View.VISIBLE else View.GONE
            btnUnhide.visibility = if (showActions && hasHidden && pm.isIconEnabled(this, pm.KEY_UNHIDE)) View.VISIBLE else View.GONE
            btnProtect.visibility = if (showActions && hasUnprotected && pm.isIconEnabled(this, pm.KEY_PROTECT)) View.VISIBLE else View.GONE
            btnUnprotect.visibility = if (showActions && hasProtected && pm.isIconEnabled(this, pm.KEY_UNPROTECT)) View.VISIBLE else View.GONE
            btnPin?.visibility = if (showActions && hasUnpinned && pm.isIconEnabled(this, pm.KEY_PIN)) View.VISIBLE else View.GONE
            btnUnpin?.visibility = if (showActions && hasPinned && pm.isIconEnabled(this, pm.KEY_UNPIN)) View.VISIBLE else View.GONE
            btnFavorite.visibility = if (count == 1 && pm.isIconEnabled(this, pm.KEY_FAVORITE)) View.VISIBLE else View.GONE
            btnDelete.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_DELETE)) View.VISIBLE else View.GONE
            btnCopy.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_COPY)) View.VISIBLE else View.GONE
            btnMove.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_MOVE)) View.VISIBLE else View.GONE
            btnRename.visibility = if (count >= 1 && pm.isIconEnabled(this, pm.KEY_RENAME)) View.VISIBLE else View.GONE
            btnShare.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_SHARE)) View.VISIBLE else View.GONE
            btnCopyEncrypt.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_COPY_ENCRYPT)) View.VISIBLE else View.GONE
            btnMoveEncrypt.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_MOVE_ENCRYPT)) View.VISIBLE else View.GONE
            btnCompress.visibility = if (showActions && pm.isIconEnabled(this, pm.KEY_COMPRESS)) View.VISIBLE else View.GONE
            val imgFiles = fileAdapter.getSelectedFiles()
            val hasArchiveSelected = imgFiles.isNotEmpty() && imgFiles.any { ArchiveManager.isSupportedArchive(it) }
            btnExtract?.visibility = if (isTv && showActions && hasArchiveSelected && pm.isIconEnabled(this, pm.KEY_EXTRACT)) View.VISIBLE else View.GONE
            val allImages = imgFiles.isNotEmpty() && imgFiles.all {
                it.extension.lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            }
            btnImageCompress.visibility = if (showActions && allImages && pm.isIconEnabled(this, pm.KEY_IMAGE_COMPRESS)) View.VISIBLE else View.GONE
            val hasVideoOrFolder = imgFiles.isNotEmpty() && imgFiles.any {
                it.isDirectory || it.extension.lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS
            }
            btnRetriggerThumbnails?.visibility = if (showActions && hasVideoOrFolder && pm.isIconEnabled(this, pm.KEY_RETRIGGER_THUMBNAILS)) View.VISIBLE else View.GONE

            val isSingleFolderSel = imgFiles.size == 1 && imgFiles.first().isDirectory
            val (selStorageId, _, _) = if (isSingleFolderSel) za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(imgFiles.first().absolutePath) else Triple("", "", "")
            val isSelFolderIndexed = selStorageId.isNotEmpty() && UfmApplication.indexingRepository.isStorageFullyIndexed(selStorageId)
            btnDuplicateFinder?.visibility = if (showActions && isSingleFolderSel && isSelFolderIndexed && pm.isIconEnabled(this, pm.KEY_DUPLICATE_FINDER)) View.VISIBLE else View.GONE
            btnLargeFilesFinder?.visibility = if (showActions && isSingleFolderSel && isSelFolderIndexed && pm.isIconEnabled(this, pm.KEY_LARGE_FILES_FINDER)) View.VISIBLE else View.GONE

            val selectedFiles = fileAdapter.getSelectedFiles()
            val isSingleFile = selectedFiles.size == 1 && !selectedFiles.first().isDirectory
            
            val prefs = getSharedPreferences("ufm_prefs", MODE_PRIVATE)
            val isMultiTaggingEnabled = prefs.getBoolean("pref_multi_file_tagging", false)
            val isMultiFileOnly = selectedFiles.size > 1 && selectedFiles.all { !it.isDirectory }
            
            fabProperties?.visibility = View.GONE
            updatePasteFab()

            val isAll = fileAdapter.isAllSelected()
            if (btnSelectAll is MaterialButton) {
                (btnSelectAll as MaterialButton).text = if (isAll) getString(R.string.action_deselect_all) else getString(R.string.action_select_all)
            }
        } else {
            layoutSelectionBar.visibility = View.GONE
            za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.stopAnimation(layoutSelectionBar)
            fabProperties?.visibility = View.GONE
            fabTools?.visibility = View.GONE
            updatePasteFab()
        }
    }

    private fun showDeleteConfirmation() {
        val selected = fileAdapter.getSelectedFiles()
        if (selected.isEmpty()) return

        val hasProtected = selected.any {
            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isOrContainsProtected(this, it.absolutePath)
        }
        if (hasProtected) {
            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.showProtectedDeleteDialog(this, DeviceUtils.isTvDevice(this))
            return
        }

        val recycleEnabled = za.kilowatch.ultimatefilemanager.recycle.RecycleBinManager.isEnabled
        val isOnTv = DeviceUtils.isTvDevice(this)
        val layoutRes = if (isOnTv) R.layout.dialog_file_delete_confirm_tv else R.layout.dialog_file_delete_confirm
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtTitle)
        val txtDeleteMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        val btnDeleteConfirm = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        if (recycleEnabled) {
            txtTitle?.text = getString(R.string.move_to_bin)
            txtDeleteMessage?.text = getString(R.string.recycle_bin_move_confirm, selected.size)
            btnDeleteConfirm?.text = getString(R.string.move_to_bin)
        } else {
            val folders = selected.count { it.isDirectory }
            val files = selected.count { it.isFile }
            val message = when {
                folders > 0 && files > 0 -> getString(R.string.delete_message_mixed, folders, files)
                folders > 0 -> getString(R.string.delete_message_folders, folders)
                else -> getString(R.string.delete_message_files, files)
            }
            txtTitle?.text = getString(R.string.delete_title)
            txtDeleteMessage?.text = message
            btnDeleteConfirm?.text = getString(R.string.delete_confirm)
        }

        btnDeleteConfirm?.setOnClickListener {
            dialog.dismiss()
            performDelete(selected)
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun performDelete(filesToDelete: List<File>) {
        val recycleEnabled = za.kilowatch.ultimatefilemanager.recycle.RecycleBinManager.isEnabled

        if (recycleEnabled) {
            lifecycleScope.launch(Dispatchers.IO) {
                var movedCount = 0
                var failedCount = 0
                val hasProtected = filesToDelete.any { za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isProtectedPath(it.absolutePath) }
                val shizukuAuthorized = za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isAuthorized()

                for (file in filesToDelete) {
                    val (storageId, storageType, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(file.absolutePath)
                    val storageLabel = storageId
                    val success = za.kilowatch.ultimatefilemanager.recycle.RecycleBinManager.moveToTrash(
                        this@FileBrowserActivity, file, storageType, storageId, storageLabel
                    )
                    if (success) {
                        UfmApplication.indexingRepository.deleteTreeFromIndex(file.absolutePath)
                        movedCount++
                    } else {
                        failedCount++
                    }
                }

                syncFolderWithIndex(currentDir)

                withContext(Dispatchers.Main) {
                    fileAdapter.exitSelectionMode()
                    loadDirectory(currentDir)
                    if (failedCount == 0) {
                        showPremiumSnackbar(getString(R.string.recycle_bin_move_success, movedCount))
                    } else {
                        val msg = if (hasProtected && !shizukuAuthorized) {
                            getString(R.string.delete_error_shizuku_required)
                        } else {
                            getString(R.string.delete_error)
                        }
                        showPremiumSnackbar(msg)
                    }
                }
            }
        } else {
            val folderName = if (filesToDelete.size == 1 && filesToDelete[0].isDirectory) filesToDelete[0].name else ""
            
            val isIndexed = if (filesToDelete.isNotEmpty()) {
                val (storageId, _, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(filesToDelete[0].absolutePath)
                UfmApplication.indexingRepository.isStorageFullyIndexed(storageId) && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)
            } else false
            
            val progressDialog = za.kilowatch.ultimatefilemanager.indexing.IndexingUiHelper.showDeletionProgressDialog(this, folderName, isIndexing = isIndexed)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    var deletedCount = 0
                    var failedCount = 0
                    val hasProtected = filesToDelete.any { za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isProtectedPath(it.absolutePath) }
                    val shizukuAuthorized = za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isAuthorized()

                    for (file in filesToDelete) {
                        val isSaf = file is za.kilowatch.ultimatefilemanager.storage.SafFile || za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) || za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, file.absolutePath)
                        val success = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(file.absolutePath)) {
                            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(file.absolutePath)
                        } else if (isSaf) {
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.deleteRecursively(this@FileBrowserActivity, file.absolutePath)
                        } else if (file.isDirectory) {
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
                        if (success) {
                            UfmApplication.indexingRepository.deleteTreeFromIndex(file.absolutePath)
                            deletedCount++
                        } else {
                            failedCount++
                        }
                    }

                    syncFolderWithIndex(currentDir)

                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                        
                        if (failedCount == 0) {
                            showPremiumSnackbar(getString(R.string.delete_success, deletedCount))
                        } else {
                            val msg = if (hasProtected && !shizukuAuthorized) {
                                getString(R.string.delete_error_shizuku_required)
                            } else {
                                getString(R.string.delete_error)
                            }
                            showPremiumSnackbar(msg)
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                    }
                }
            }
        }
    }

    private fun showRenameDialog(file: File) {
        val isOnTv = DeviceUtils.isTvDevice(this)
        val layoutRes = if (isOnTv) R.layout.dialog_file_rename_tv else R.layout.dialog_file_rename
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val txtOriginalName = dialogView.findViewById<TextView>(R.id.txtOriginalName)
        val editFileName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editFileName)
        val btnSaveRename = dialogView.findViewById<View>(R.id.btnSaveRename)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtOriginalName?.text = file.name
        editFileName?.setText(file.name)
        val dotIndex = file.name.lastIndexOf('.')
        if (!file.isDirectory && dotIndex > 0) {
            editFileName?.setSelection(0, dotIndex)
        } else {
            editFileName?.selectAll()
        }

        btnSaveRename?.setOnClickListener {
            val newName = editFileName?.text?.toString()?.trim().orEmpty()
            if (newName.isNotEmpty() && newName != file.name) {
                val newFile = File(file.parent, newName)
                lifecycleScope.launch(Dispatchers.IO) {
                    val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) || za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, file.absolutePath)
                    val success = if (isSaf) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.rename(this@FileBrowserActivity, file.absolutePath, newName)
                    } else {
                        file.renameTo(newFile)
                    }
                    if (success) {
                        FileTagsManager.onPathMoved(this@FileBrowserActivity, file.absolutePath, newFile.absolutePath)
                        syncFolderWithIndex(currentDir)
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory(currentDir)
                            showPremiumSnackbar(getString(R.string.rename_success))
                            dialog.dismiss()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            showPremiumSnackbar(getString(R.string.rename_error))
                        }
                    }
                }
            } else if (newName == file.name) {
                dialog.dismiss()
            }
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        DialogInputHelper.setupDialogInput(dialog, editFileName) {
            btnSaveRename?.performClick()
        }
    }
    
    private fun showFavoriteDialog(file: File) {
        val isOnTv = DeviceUtils.isTvDevice(this)
        val layoutRes = if (isOnTv) R.layout.dialog_add_favorite_tv else R.layout.dialog_add_favorite
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val txtOriginalName = dialogView.findViewById<TextView>(R.id.txtOriginalName)
        val edtFavoriteName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtFavoriteName)
        val btnSaveFavorite = dialogView.findViewById<View>(R.id.btnSaveFavorite)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtOriginalName?.text = file.name
        edtFavoriteName?.setText(file.name)
        edtFavoriteName?.selectAll()

        btnSaveFavorite?.setOnClickListener {
            val name = edtFavoriteName?.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                showPremiumSnackbar(getString(R.string.favorite_name_empty))
            } else {
                val favorite = za.kilowatch.ultimatefilemanager.settings.FavoritesManager.FavoriteItem(
                    id = "fav_${System.currentTimeMillis()}",
                    path = file.absolutePath,
                    label = name,
                    isFolder = file.isDirectory,
                    isNetwork = false
                )
                za.kilowatch.ultimatefilemanager.settings.FavoritesManager.addFavorite(this, favorite)
                fileAdapter.exitSelectionMode()
                showPremiumSnackbar(getString(R.string.favorite_added))
                dialog.dismiss()
            }
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        DialogInputHelper.setupDialogInput(dialog, edtFavoriteName) {
            btnSaveFavorite?.performClick()
        }
    }

    /**
     * Shows a modern premium dialog with two choices: "New Folder" and "New Text File".
     */
    private fun showCreateNewMenu() {
        val isOnTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isOnTv) R.layout.dialog_create_new_options_tv else R.layout.dialog_create_new_options,
            null
        )

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnOptionNewFolder)?.setOnClickListener {
            dialog.dismiss()
            showCreateFolderDialog()
        }
        dialogView.findViewById<View>(R.id.btnOptionNewFile)?.setOnClickListener {
            dialog.dismiss()
            showCreateTextFileDialog()
        }
        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
                    dialog.dismiss()
        }

        dialog.show()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        if (isOnTv) {
            val widthPx = (800 * resources.displayMetrics.density).toInt()
            val screenWidth = resources.displayMetrics.widthPixels
            val finalWidth = minOf(widthPx, (screenWidth * 0.85).toInt())
            dialog.window?.setLayout(finalWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Shows a dialog to name and create a new .txt file in [currentDir].
     * On success, opens the text viewer in edit mode.
     */
    private fun showCreateTextFileDialog() {
        val isOnTv = DeviceUtils.isTvDevice(this)
        val layoutRes = if (isOnTv) R.layout.dialog_create_text_file_tv else R.layout.dialog_create_text_file
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)
        val edtFileName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtFileName)
        edtFileName?.setText(getString(R.string.new_file_default))
        edtFileName?.selectAll()

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnCreate)?.setOnClickListener {
            val name = edtFileName?.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                showPremiumSnackbar(getString(R.string.new_file_empty))
            } else {
                dialog.dismiss()
                createTextFile(name)
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        DialogInputHelper.setupDialogInput(dialog, edtFileName) {
            dialogView.findViewById<View>(R.id.btnCreate)?.performClick()
        }
    }

    /**
     * Creates the .txt file with auto-rename on collision,
     * indexes it, reloads the directory, and opens the text viewer.
     */
    private fun createTextFile(baseName: String) {
        val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(currentDir.absolutePath) || za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, currentDir.absolutePath)
        val fileToCreate = if (isSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafFile(currentDir.absolutePath, baseName, false)
        } else {
            var targetFile = File(currentDir, baseName)
            if (targetFile.exists()) {
                val nameWithoutExt = targetFile.nameWithoutExtension
                val ext = targetFile.extension
                var counter = 2
                while (targetFile.exists()) {
                    targetFile = File(currentDir, "$nameWithoutExt ($counter).$ext")
                    counter++
                }
            }
            targetFile
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val created = if (isSaf) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFile(this@FileBrowserActivity, currentDir.absolutePath, fileToCreate.name, "text/plain") != null
                } else {
                    fileToCreate.createNewFile()
                }
                if (created) {
                    // Index the new file
                    try {
                        val (sid, stype) = IndexingRepository.resolveStorageForPath(fileToCreate.absolutePath)
                            .let { it.first to it.second }
                        UfmApplication.indexingRepository.indexFile(fileToCreate, sid, stype)
                    } catch (_: Exception) { }

                    withContext(Dispatchers.Main) {
                        loadDirectory(currentDir)
                        showPremiumSnackbar(getString(R.string.new_file_success))

                        // Open text viewer in edit mode
                        val intent = Intent(this@FileBrowserActivity, za.kilowatch.ultimatefilemanager.viewer.TextViewerActivity::class.java).apply {
                            putExtra(FileViewerRouter.EXTRA_FILE_PATH, fileToCreate.absolutePath)
                            putExtra(FileViewerRouter.EXTRA_FILE_NAME, fileToCreate.name)
                            putExtra(FileViewerRouter.EXTRA_START_IN_EDIT_MODE, true)
                        }
                        startActivity(intent)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showPremiumSnackbar(getString(R.string.new_file_error))
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
        val isOnTv = DeviceUtils.isTvDevice(this)
        val layoutRes = if (isOnTv) R.layout.dialog_create_folder_tv else R.layout.dialog_create_folder
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)
        val edtFolderName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtFolderName)
        edtFolderName?.setText(getString(R.string.new_menu_new_folder))
        edtFolderName?.selectAll()

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnCreate)?.setOnClickListener {
            val name = edtFolderName?.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                showPremiumSnackbar(getString(R.string.new_folder_empty))
            } else {
                dialog.dismiss()
                val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(currentDir.absolutePath) || za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, currentDir.absolutePath)
                val newDir = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(currentDir.absolutePath)) {
                    za.kilowatch.ultimatefilemanager.storage.ShizukuFile(currentDir.absolutePath, name, true)
                } else if (isSaf) {
                    za.kilowatch.ultimatefilemanager.storage.SafFile(currentDir.absolutePath, name, true)
                } else {
                    File(currentDir, name)
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val exists = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(currentDir.absolutePath)) {
                        za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.exists(newDir.absolutePath)
                    } else if (isSaf) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(this@FileBrowserActivity, newDir.absolutePath)
                    } else {
                        newDir.exists()
                    }
                    val created = if (exists) {
                        false
                    } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(currentDir.absolutePath)) {
                        za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.mkdir(newDir.absolutePath)
                    } else if (isSaf) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.mkdir(this@FileBrowserActivity, currentDir.absolutePath, name)
                    } else {
                        newDir.mkdirs()
                    }

                    withContext(Dispatchers.Main) {
                        when {
                            exists -> showPremiumSnackbar(getString(R.string.new_folder_exists))
                            created -> {
                                try {
                                    val (sid, stype) = za.kilowatch.ultimatefilemanager.indexing
                                        .IndexingRepository.resolveStorageForPath(newDir.absolutePath)
                                        .let { it.first to it.second }
                                    UfmApplication.indexingRepository.indexFile(newDir, sid, stype)
                                } catch (_: Exception) { }
                                loadDirectory(currentDir)
                                showPremiumSnackbar(getString(R.string.new_folder_success))
                            }
                            else -> showPremiumSnackbar(getString(R.string.new_folder_error))
                        }
                    }
                }
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        DialogInputHelper.setupDialogInput(dialog, edtFolderName) {
            dialogView.findViewById<View>(R.id.btnCreate)?.performClick()
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

    private fun shareFiles(files: List<File>) {
        if (files.isEmpty()) return
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)

        val layoutRes = if (isTv) R.layout.dialog_premium_share_chooser_tv else R.layout.dialog_premium_share_chooser
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val cardStandardShare = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardStandardShare)
        val cardPremiumShare = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardPremiumShare)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        cardStandardShare.setOnClickListener {
            dialog.dismiss()
            performStandardShare(files)
        }

        cardPremiumShare.setOnClickListener {
            dialog.dismiss()
            if (isTv) {
                val filePaths = ArrayList(files.map { it.absolutePath })
                val intent = Intent(this, PremiumShareTvActivity::class.java).apply {
                    putStringArrayListExtra("files", filePaths)
                    putExtra("target_type", "web")
                }
                startActivity(intent)
                fileAdapter.exitSelectionMode()
            } else {
                showTargetChooserDialog(files)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        if (isTv) {
            setupTvShareChooserFocus(dialog, dialogView, cardStandardShare, cardPremiumShare, btnCancel)
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun performStandardShare(files: List<File>) {
        try {
            val uris = ArrayList<Uri>()
            for (file in files) {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                uris.add(uri)
            }

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    val ext = files[0].extension.lowercase()
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
            fileAdapter.exitSelectionMode()
        } catch (e: Exception) {
            showPremiumSnackbar(getString(R.string.share_error))
        }
    }

    private fun showTargetChooserDialog(files: List<File>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_premium_target_chooser, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val cardTargetTv = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTargetTv)
        val cardTargetMobilePc = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTargetMobilePc)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val filePaths = ArrayList(files.map { it.absolutePath })

        cardTargetTv.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, PremiumShareActivity::class.java).apply {
                putStringArrayListExtra("files", filePaths)
                putExtra("target_type", "tv")
            }
            startActivity(intent)
            fileAdapter.exitSelectionMode()
        }

        cardTargetMobilePc.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, PremiumShareActivity::class.java).apply {
                putStringArrayListExtra("files", filePaths)
                putExtra("target_type", "web")
            }
            startActivity(intent)
            fileAdapter.exitSelectionMode()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun updatePasteFab() {
        if (isExtractDestPickerMode || isCompressDestPickerMode || isImageCompressDestPickerMode ||
            isGifCreatorDestPickerMode || isSyncFolderPickerMode || isAdvancedSyncFolderPickerMode ||
            isAdvancedSyncDestPickerMode || isLocationPickerMode || isNetworkCachePickerMode ||
            isQuickTransferPickerMode || isShareDestPickerMode || isNotepadFolderPicker ||
            isScannerFolderPicker || isAutoBackupFolderPicker || isKeyfilePickerMode ||
            isCertPickerMode || isSupportAttachmentPicker || isSmartSortPickerMode) {
            applyPickerFabState()
            return
        }

        val total = FileClipboard.totalItemCount()

        if (total > 0) {
            if (FileClipboard.slots.any { it.isExtract }) {
                fabPaste.text = "${getString(R.string.extract_here)} ($total)"
                fabPaste.setIconResource(R.drawable.ic_extract)
            } else {
                fabPaste.text = "${getString(R.string.action_paste)} ($total)"
                fabPaste.setIconResource(R.drawable.ic_paste)
            }
            fabPaste.visibility = View.VISIBLE
        } else {
            fabPaste.visibility = View.GONE
        }
        updateFabPositions()
    }

    private fun applyPickerFabState() {
        when {
            isExtractDestPickerMode -> {
                fabPaste.setText(R.string.extract_here_1)
                fabPaste.setIconResource(R.drawable.ic_folder)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmExtractLocalFolderDialog() }
            }
            isCompressDestPickerMode -> {
                fabPaste.setText(R.string.use_this_folder)
                fabPaste.setIconResource(R.drawable.ic_compress)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmCompressLocalFolderDialog() }
            }
            isImageCompressDestPickerMode -> {
                fabPaste.setText(R.string.use_this_folder_image)
                fabPaste.setIconResource(R.drawable.ic_compress_image)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmImageCompressLocalFolderDialog() }
            }
            isGifCreatorDestPickerMode -> {
                fabPaste.setText(R.string.use_this_folder)
                fabPaste.setIconResource(R.drawable.ic_gif)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmGifCreatorLocalFolderDialog() }
            }
            isSyncFolderPickerMode -> {
                fabPaste.setText(R.string.use_this_folder)
                fabPaste.setIconResource(R.drawable.ic_sync)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmSyncLocalFolderDialog() }
            }
            isAdvancedSyncFolderPickerMode -> {
                fabPaste.setText(R.string.use_this_folder)
                fabPaste.setIconResource(R.drawable.ic_sync_advanced)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmAdvancedSyncLocalFolderDialog() }
            }
            isAdvancedSyncDestPickerMode -> {
                fabPaste.setText(R.string.use_this_folder)
                fabPaste.setIconResource(R.drawable.ic_sync_advanced)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmAdvancedSyncDestFolderDialog() }
            }
            isLocationPickerMode -> {
                fabPaste.setText(R.string.use_this_folder)
                fabPaste.setIconResource(R.drawable.ic_folder)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmLocationPickerLocalFolderDialog() }
            }
            isNetworkCachePickerMode -> {
                fabPaste.setText(R.string.nt_use_this_folder_for_caching)
                fabPaste.setIconResource(R.drawable.ic_folder)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmNetworkCacheFolderDialog() }
            }
            isQuickTransferPickerMode -> {
                val isMove = intent.getStringExtra(EXTRA_QUICK_TRANSFER_OP) == "MOVE"
                fabPaste.setText(if (isMove) R.string.quick_transfer_move_here else R.string.quick_transfer_copy_here)
                fabPaste.setIconResource(if (isMove) R.drawable.ic_move else R.drawable.ic_copy)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmQuickTransferDialog(isMove) }
            }
            isShareDestPickerMode -> {
                fabPaste.setText(R.string.use_this_folder)
                fabPaste.setIconResource(R.drawable.ic_folder)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmShareDestDialog() }
            }
            isNotepadFolderPicker -> {
                fabPaste.setText(R.string.use_this_folder)
                fabPaste.setIconResource(R.drawable.ic_folder)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmNotepadFolderDialog() }
            }
            isScannerFolderPicker -> {
                fabPaste.setText(R.string.scanner_use_this_folder)
                fabPaste.setIconResource(R.drawable.ic_scanner)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmScannerFolderDialog() }
            }
            isAutoBackupFolderPicker -> {
                fabPaste.setText(R.string.auto_backup_select_folder)
                fabPaste.setIconResource(R.drawable.ic_cloud)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { showConfirmAutoBackupFolderDialog() }
            }
            isSupportAttachmentPicker -> {
                fabPaste.setText(R.string.support_attach_file)
                fabPaste.setIconResource(R.drawable.ic_add)
                fabPaste.visibility = if (selectedKeyFilePath != null) View.VISIBLE else View.GONE
                fabPaste.setOnClickListener { showConfirmSupportAttachmentDialog() }
            }
            isKeyfilePickerMode -> {
                fabPaste.setText(R.string.use_this_key_file)
                fabPaste.setIconResource(R.drawable.ic_folder)
                fabPaste.visibility = if (selectedKeyFilePath != null) View.VISIBLE else View.GONE
                fabPaste.setOnClickListener { showConfirmKeyfilePickedDialog() }
            }
            isCertPickerMode -> {
                fabPaste.setText(R.string.remote_use_ca)
                fabPaste.setIconResource(R.drawable.ic_lock)
                fabPaste.visibility = if (selectedKeyFilePath != null) View.VISIBLE else View.GONE
                fabPaste.setOnClickListener { showConfirmCertPickedDialog() }
            }
            isSmartSortPickerMode -> {
                fabPaste.setText(R.string.smart_sort_here)
                fabPaste.setIconResource(R.drawable.ic_sort)
                fabPaste.visibility = View.VISIBLE
                fabPaste.setOnClickListener { confirmSmartSortFolder() }
            }
        }
        updateFabPositions()
    }

    private fun showClipboardSheet() {
        val isOnTv = DeviceUtils.isTvDevice(this)
        if (!FileClipboard.hasItems()) return

        val colorCopy = getColor(R.color.ufm_primary)
        val colorCut = getColor(R.color.ufm_denied)

        val layoutRes = if (isOnTv) R.layout.dialog_clipboard_tv else R.layout.bottom_sheet_clipboard
        val itemLayoutRes = if (isOnTv) R.layout.item_clipboard_entry_tv else R.layout.item_clipboard_entry
        val contentView = layoutInflater.inflate(layoutRes, null)

        val tabLayout = contentView.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabClipboardSlots)
        val viewPager = contentView.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.vpClipboardSlots)
        val btnPasteHere = contentView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPasteHere)
        val btnPasteSlot = contentView.findViewById<com.google.android.material.button.MaterialButton?>(R.id.btnPasteSlot)
        val btnRemoveSlot = contentView.findViewById<com.google.android.material.button.MaterialButton?>(R.id.btnRemoveSlot)
        val btnClearAll = contentView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearClipboard)
        val txtTitle = contentView.findViewById<android.widget.TextView>(R.id.txtClipboardTitle)
        val layoutSlotActions = contentView.findViewById<View?>(R.id.layoutSlotActions)

        if (isOnTv) {
            viewPager.isUserInputEnabled = false
        }

        val dialog: android.app.Dialog = if (isOnTv) {
            MaterialAlertDialogBuilder(this)
                .setView(contentView)
                .create()
        } else {
            com.google.android.material.bottomsheet.BottomSheetDialog(this).apply {
                setContentView(contentView)
            }
        }

        fun updateUI() {
            val slots = FileClipboard.slots
            val total = FileClipboard.totalItemCount()
            if (slots.isEmpty() || total == 0) {
                dialog.dismiss()
                updatePasteFab()
                return
            }

            if (slots.size <= 1) {
                txtTitle.text = if (total == 1) getString(R.string.clipboard_1_file) else getString(R.string.clipboard_total_files, total)
                tabLayout.visibility = View.GONE
                btnPasteHere.text = getString(R.string.paste_here)
                layoutSlotActions?.visibility = View.GONE
            } else {
                txtTitle.text = getString(R.string.clipboard_slots_title, slots.size, total)
                tabLayout.visibility = View.VISIBLE
                btnPasteHere.text = getString(R.string.paste_all_slots)
                layoutSlotActions?.visibility = View.VISIBLE
            }
            updatePasteFab()
        }

        class SlotItemViewHolder(val v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)

        class SlotPageViewHolder(val view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val recycler: androidx.recyclerview.widget.RecyclerView = view.findViewById(R.id.recyclerSlotItems)
        }

        val pagerAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<SlotPageViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SlotPageViewHolder {
                val v = layoutInflater.inflate(R.layout.item_clipboard_slot_page, parent, false)
                return SlotPageViewHolder(v)
            }

            override fun getItemCount(): Int = FileClipboard.slots.size

            override fun onBindViewHolder(holder: SlotPageViewHolder, position: Int) {
                val slot = FileClipboard.slots.getOrNull(position) ?: return
                val itemsRecycler = holder.recycler
                itemsRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@FileBrowserActivity)

                itemsRecycler.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<SlotItemViewHolder>() {
                    override fun onCreateViewHolder(p: android.view.ViewGroup, vt: Int) =
                        SlotItemViewHolder(layoutInflater.inflate(itemLayoutRes, p, false))

                    override fun getItemCount() = slot.items.size

                    override fun onBindViewHolder(h: SlotItemViewHolder, itemPos: Int) {
                        val item = slot.items.getOrNull(itemPos) ?: return
                        val v = h.itemView
                        val txtOp = v.findViewById<android.widget.TextView>(R.id.txtOperation)
                        val txtName = v.findViewById<android.widget.TextView>(R.id.txtFileName)
                        val btnRemove = v.findViewById<android.widget.ImageView>(R.id.btnRemoveClipboard)

                        val isMove = item.operation == FileClipboard.Operation.MOVE
                        txtOp.text = when (item.operation) {
                            FileClipboard.Operation.MOVE -> "CUT"
                            FileClipboard.Operation.COPY -> "COPY"
                            FileClipboard.Operation.EXTRACT -> "EXTRACT"
                        }
                        (txtOp.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                            if (isMove) colorCut else colorCopy
                        )

                        val prefix = if (item is FileClipboard.ClipItem.Remote) "[Remote] " else ""
                        txtName.text = "$prefix${item.name}"

                        if (isOnTv) {
                            val yellowTint = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                            val redTint = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_denied))
                            btnRemove.setOnFocusChangeListener { _, hasFocus ->
                                btnRemove.imageTintList = if (hasFocus) yellowTint else redTint
                            }
                        }

                        btnRemove.setOnClickListener {
                            FileClipboard.removeItem(slot.id, item)
                            updateUI()
                            notifyDataSetChanged()
                            viewPager.adapter?.notifyDataSetChanged()
                        }
                    }
                }
            }
        }

        viewPager.adapter = pagerAdapter

        com.google.android.material.tabs.TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            val slot = FileClipboard.slots.getOrNull(position)
            tab.text = slot?.let { "${it.label} (${it.totalCount})" } ?: "Slot ${position + 1}"
        }.attach()

        updateUI()

        btnPasteHere.setOnClickListener {
            dialog.dismiss()
            performPaste()
        }

        btnPasteSlot?.setOnClickListener {
            val currentPos = viewPager.currentItem
            val slot = FileClipboard.slots.getOrNull(currentPos)
            if (slot != null) {
                dialog.dismiss()
                performPaste(targetSlotId = slot.id)
            }
        }

        btnRemoveSlot?.setOnClickListener {
            val currentPos = viewPager.currentItem
            val slot = FileClipboard.slots.getOrNull(currentPos)
            if (slot != null) {
                FileClipboard.removeSlot(slot.id)
                updateUI()
                pagerAdapter.notifyDataSetChanged()
            }
        }

        btnClearAll.setOnClickListener {
            FileClipboard.clear()
            updatePasteFab()
            dialog.dismiss()
        }

        // TV: add yellow-focus D-pad states to action buttons
        if (isOnTv) {
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

            btnPasteSlot?.isFocusable = true
            btnPasteSlot?.isFocusableInTouchMode = true
            btnPasteSlot?.setOnFocusChangeListener { _, hasFocus ->
                btnPasteSlot.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnPasteSlot.setTextColor(if (hasFocus) blackText else getColor(R.color.tv_text_primary))
            }

            btnRemoveSlot?.isFocusable = true
            btnRemoveSlot?.isFocusableInTouchMode = true
            btnRemoveSlot?.setOnFocusChangeListener { _, hasFocus ->
                btnRemoveSlot.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnRemoveSlot.setTextColor(if (hasFocus) blackText else getColor(R.color.ufm_denied))
            }

            btnClearAll.isFocusable = true
            btnClearAll.isFocusableInTouchMode = true
            btnClearAll.setOnFocusChangeListener { _, hasFocus ->
                btnClearAll.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnClearAll.setTextColor(if (hasFocus) blackText else getColor(R.color.ufm_denied))
            }
        }

        dialog.show()

        if (isOnTv) {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.75).toInt().coerceAtLeast((680 * resources.displayMetrics.density).toInt()),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun performPaste(targetSlotId: Long? = null) {
        val targetSlots = if (targetSlotId != null) FileClipboard.slots.filter { it.id == targetSlotId } else FileClipboard.slots
        if (targetSlots.isEmpty()) return
        val hasLocal = targetSlots.any { it.hasLocal }
        val hasNet = targetSlots.any { it.hasRemote }
        val isExtractOperation = targetSlots.any { it.isExtract }

        // ── Build progress dialog ──────────────────────────────────────
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val statusText = android.widget.TextView(this).apply {
            text = getString(R.string.preparing)
            textSize = 14f
        }
        val detailText = android.widget.TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        val dialogProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progress = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16; bottomMargin = 8 }
        }
        val hintText = android.widget.TextView(this).apply {
            text = getString(R.string.press_cancel_to_stop_the_transfer)
            textSize = 11f
            setTextColor(0xFF999999.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            isCancelled = true
            transferJob?.cancel()
            runCatching { currentTransferConnection?.close() }
            currentTransferConnection = null
            currentTransferStreams?.let { (inp, out) ->
                runCatching { out?.close() }
                runCatching { inp?.close() }
                currentTransferStreams = null
            }
            currentTransferDestFile?.let { f ->
                currentTransferDestFile = null
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(f.absolutePath)) {
                            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(f.absolutePath)
                        } else if (f.isDirectory) {
                            f.deleteRecursively()
                        } else {
                            f.delete()
                        }
                    } catch (_: Exception) {}
                }
            }
            isTransferring = false
            za.kilowatch.ultimatefilemanager.util.TransferService.stop(this)
            dialog.dismiss()
            loadDirectory(currentDir)
        }

        fun updateProgress(fileName: String, bytesCopied: Long, totalBytes: Long, fileIndex: Int, totalFiles: Int) {
            try {
                runOnUiThread {
                    try {
                        val copiedStr = android.text.format.Formatter.formatFileSize(this@FileBrowserActivity, bytesCopied)
                        val totalStr = if (totalBytes > 0) android.text.format.Formatter.formatFileSize(this@FileBrowserActivity, totalBytes) else "?"
                        statusText.text = if (totalFiles > 1) getString(R.string.file_fileindex_of_totalfiles_filename, fileIndex, totalFiles, fileName) else fileName
                        detailText.text = getString(R.string.copiedstr_totalstr, copiedStr, totalStr)
                        if (totalBytes > 0) {
                            dialogProgress.progress = ((bytesCopied * 1000L) / totalBytes).toInt()
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        isTransferring = true
        za.kilowatch.ultimatefilemanager.util.TransferService.start(this)
        transferJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
            try {
                var successCount = 0
                var failCount = 0

                val db = UfmIndexingDatabase.getInstance(this@FileBrowserActivity)
                val dao = db.fileIndexDao()
                val metadataExtractor = MetadataExtractor(this@FileBrowserActivity)
                val pendingIndices = mutableListOf<FileIndex>()

                suspend fun flushIndices() {
                    if (pendingIndices.isNotEmpty()) {
                        dao.insertAll(pendingIndices.toList())
                        pendingIndices.clear()
                    }
                }

                // Pre-count total files
                var totalFiles = 0
                for (slot in targetSlots) {
                    for (item in slot.items) {
                        when (item) {
                            is FileClipboard.ClipItem.Local -> {
                                if (item.file.isDirectory) totalFiles += za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.countLocalFiles(item.file)
                                else totalFiles++
                            }
                            is FileClipboard.ClipItem.Remote -> {
                                totalFiles++
                            }
                        }
                    }
                }
                var fileIndex = 0

                val effectiveDestDir = quickTransferDestDir ?: currentDir
                val applyToAllRef = booleanArrayOf(false)
                var globalAction: za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction? = null

                suspend fun processLocalItem(source: java.io.File, destBase: java.io.File, operation: FileClipboard.Operation) {
                    val isSrcSaf = source is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                                   za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(source.absolutePath) ||
                                   za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, source.absolutePath)
                    val isDestSaf = destBase is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(destBase.absolutePath) ||
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, destBase.absolutePath)

                    if (source.isDirectory) {
                        val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileExists(
                            destBase.parentFile ?: effectiveDestDir, destBase.name, this@FileBrowserActivity
                        )

                        var effectiveDest = destBase
                        if (hasConflict) {
                            val resolvedAction = globalAction ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                    this@FileBrowserActivity, source.name, true, -1L, applyToAllRef
                                ).also { if (applyToAllRef[0]) globalAction = it }
                            }
                            when (resolvedAction) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL -> throw CancellationException()
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP -> {
                                    successCount++
                                    return
                                }
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                    effectiveDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueLocalFolder(
                                        destBase.parentFile ?: effectiveDestDir, source.name, this@FileBrowserActivity
                                    )
                                }
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                    effectiveDest = destBase
                                }
                            }
                        }

                        val isEffSaf = effectiveDest is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(effectiveDest.absolutePath) ||
                                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, effectiveDest.absolutePath)
                        try {
                            if (isEffSaf) {
                                if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(this@FileBrowserActivity, effectiveDest.absolutePath)) {
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.mkdir(this@FileBrowserActivity, effectiveDest.parent ?: effectiveDestDir.absolutePath, effectiveDest.name)
                                }
                            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(effectiveDest.absolutePath)) {
                                if (!za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.exists(effectiveDest.absolutePath)) {
                                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.mkdir(effectiveDest.absolutePath)
                                }
                            } else {
                                if (!effectiveDest.exists()) effectiveDest.mkdirs()
                            }
                            if (!UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                pendingIndices.add(metadataExtractor.extractMetadata(effectiveDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                                if (pendingIndices.size >= 50) flushIndices()
                            }
                        } catch (_: Exception) {}

                        val children = if (isSrcSaf) {
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.listFiles(this@FileBrowserActivity, source.absolutePath)
                        } else {
                            source.listFiles()?.toList()
                        }
                        if (children != null) {
                            for (child in children) {
                                try {
                                    val childDest = if (isEffSaf) {
                                        za.kilowatch.ultimatefilemanager.storage.SafFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                                    } else {
                                        java.io.File(effectiveDest, child.name)
                                    }
                                    processLocalItem(child, childDest, operation)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.util.Log.e("PasteFeature", "Error processing local child ${child.name}: ${e.message}")
                                    failCount++
                                }
                            }
                        }
                        if (operation == FileClipboard.Operation.MOVE || operation == FileClipboard.Operation.EXTRACT) {
                            try {
                                if (isSrcSaf) {
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(this@FileBrowserActivity, source.absolutePath)
                                } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(source.absolutePath)) {
                                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(source.absolutePath)
                                } else {
                                    source.delete()
                                }
                                UfmApplication.indexingRepository.deleteTreeFromIndex(source.absolutePath)
                            } catch (_: Exception) {}
                            FileTagsManager.onPathMoved(this@FileBrowserActivity, source.absolutePath, effectiveDest.absolutePath)
                        } else {
                            FileTagsManager.onPathCopied(this@FileBrowserActivity, source.absolutePath, effectiveDest.absolutePath)
                        }
                    } else {
                        fileIndex++
                        val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileExists(
                            destBase.parentFile ?: effectiveDestDir, destBase.name, this@FileBrowserActivity
                        )
                        val resolvedAction = if (hasConflict) {
                            val destSize = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileSize(
                                destBase.parentFile ?: effectiveDestDir, destBase.name, this@FileBrowserActivity
                            )
                            globalAction ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                    this@FileBrowserActivity, source.name, false, destSize, applyToAllRef
                                ).also { if (applyToAllRef[0]) globalAction = it }
                            }
                        } else za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH

                        if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL) {
                            throw CancellationException()
                        }
                        if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP) {
                            successCount++
                            return
                        }

                        val sourceSize = if (isSrcSaf) za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(this@FileBrowserActivity, source.absolutePath) else source.length()
                        updateProgress(source.name, 0, sourceSize, fileIndex, totalFiles)
                        val finalDest = if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH)
                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueLocalFile(destBase.parentFile ?: effectiveDestDir, destBase.name, this@FileBrowserActivity)
                        else destBase
                        val writtenDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.copyLocalToLocalAtomic(source, finalDest, resolvedAction) { c, t ->
                            updateProgress(source.name, c, t, fileIndex, totalFiles)
                        }

                        if (!UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                            pendingIndices.add(metadataExtractor.extractMetadata(writtenDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                            if (pendingIndices.size >= 50) flushIndices()
                        }

                        if (operation == FileClipboard.Operation.MOVE || operation == FileClipboard.Operation.EXTRACT) {
                            val writtenSize = if (isDestSaf) za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(this@FileBrowserActivity, writtenDest.absolutePath) else writtenDest.length()
                            if (za.kilowatch.ultimatefilemanager.util.FileTransferGuard.requireSourceSafeToDelete(
                                    writtenSize, sourceSize, source.name)) {
                                if (isSrcSaf) {
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(this@FileBrowserActivity, source.absolutePath)
                                } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(source.absolutePath)) {
                                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(source.absolutePath)
                                } else {
                                    source.delete()
                                }
                                UfmApplication.indexingRepository.deleteTreeFromIndex(source.absolutePath)
                            }
                            FileTagsManager.onPathMoved(this@FileBrowserActivity, source.absolutePath, writtenDest.absolutePath)
                        } else {
                            FileTagsManager.onPathCopied(this@FileBrowserActivity, source.absolutePath, writtenDest.absolutePath)
                        }
                        successCount++
                    }
                }

                suspend fun processNetItem(source: za.kilowatch.ultimatefilemanager.network.NetworkFile, destBase: java.io.File, share: za.kilowatch.ultimatefilemanager.network.NetworkShare, operation: FileClipboard.Operation) {
                    val isDestSaf = destBase is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(destBase.absolutePath) ||
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, destBase.absolutePath)

                    if (source.isDirectory) {
                        val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileExists(
                            destBase.parentFile ?: effectiveDestDir, destBase.name, this@FileBrowserActivity
                        )

                        var effectiveDest = destBase
                        if (hasConflict) {
                            val resolvedAction = globalAction ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                    this@FileBrowserActivity, source.name, true, -1L, applyToAllRef
                                ).also { if (applyToAllRef[0]) globalAction = it }
                            }
                            when (resolvedAction) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL -> throw CancellationException()
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP -> {
                                    successCount++
                                    return
                                }
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                    effectiveDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueLocalFolder(
                                        destBase.parentFile ?: effectiveDestDir, source.name, this@FileBrowserActivity
                                    )
                                }
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                    effectiveDest = destBase
                                }
                            }
                        }

                        val isEffSaf = effectiveDest is za.kilowatch.ultimatefilemanager.storage.SafFile || 
                                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(effectiveDest.absolutePath) ||
                                       za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, effectiveDest.absolutePath)
                        try {
                            if (isEffSaf) {
                                if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(this@FileBrowserActivity, effectiveDest.absolutePath)) {
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.mkdir(this@FileBrowserActivity, effectiveDest.parent ?: effectiveDestDir.absolutePath, effectiveDest.name)
                                }
                            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(effectiveDest.absolutePath)) {
                                if (!za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.exists(effectiveDest.absolutePath)) {
                                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.mkdir(effectiveDest.absolutePath)
                                }
                            } else {
                                if (!effectiveDest.exists()) effectiveDest.mkdirs()
                            }
                            if (!UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                pendingIndices.add(metadataExtractor.extractMetadata(effectiveDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                                if (pendingIndices.size >= 50) flushIndices()
                            }
                        } catch (_: Exception) {}

                        val children = when (share.type) {
                            za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.listFiles(share, source.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.listFiles(share, source.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.TV  -> za.kilowatch.ultimatefilemanager.network.TvShareClient.listFiles(share, source.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, source.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, source.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, source.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, source.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, source.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, source.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(share, source.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                        for (child in children) {
                            if (isCancelled) break
                            coroutineContext.ensureActive()
                            try {
                                val childDest = if (isEffSaf) {
                                    za.kilowatch.ultimatefilemanager.storage.SafFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                                } else {
                                    java.io.File(effectiveDest, child.name)
                                }
                                processNetItem(child, childDest, share, operation)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (isCancelled) throw CancellationException()
                                android.util.Log.e("PasteFeature", "Error processing net child ${child.name}: ${e.message}")
                                failCount++
                            }
                        }
                        if (operation == FileClipboard.Operation.MOVE) {
                            try {
                                when (share.type) {
                                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteDir(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteDir(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.TV  -> za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteDir(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(share, source.path, true)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteDir(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                }
                            } catch (_: Exception) {}
                            FileTagsManager.onPathMoved(this@FileBrowserActivity, source.path, effectiveDest.absolutePath)
                        } else {
                            FileTagsManager.onPathCopied(this@FileBrowserActivity, source.path, effectiveDest.absolutePath)
                        }
                    } else {
                        fileIndex++
                        val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileExists(
                            destBase.parentFile ?: effectiveDestDir, destBase.name, this@FileBrowserActivity
                        )
                        val resolvedAction = if (hasConflict) {
                            val destSize = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileSize(
                                destBase.parentFile ?: effectiveDestDir, destBase.name, this@FileBrowserActivity
                            )
                            globalAction ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                    this@FileBrowserActivity, source.name, false, destSize, applyToAllRef
                                ).also { if (applyToAllRef[0]) globalAction = it }
                            }
                        } else za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH

                        if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL) throw CancellationException()
                        if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP) {
                            successCount++
                            return
                        }

                        updateProgress(source.name, 0, source.size, fileIndex, totalFiles)
                        val writtenDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.downloadNetworkToLocalAtomic(
                            share, source, destBase, resolvedAction,
                            onProgress = { c, t -> updateProgress(source.name, c, t, fileIndex, totalFiles) },
                            onConnectionReady = { conn -> currentTransferConnection = conn }
                        )
                        currentTransferConnection = null

                        if (!UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                            pendingIndices.add(metadataExtractor.extractMetadata(writtenDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                            if (pendingIndices.size >= 50) flushIndices()
                        }

                        if (operation == FileClipboard.Operation.MOVE) {
                            if (za.kilowatch.ultimatefilemanager.util.FileTransferGuard.requireSourceSafeToDelete(
                                    writtenDest.length(), source.size, source.name)) {
                                when (share.type) {
                                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.TV  -> za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(share, source.path, false)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteFile(share, source.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                }
                            }
                            FileTagsManager.onPathMoved(this@FileBrowserActivity, source.path, writtenDest.absolutePath)
                        } else {
                            FileTagsManager.onPathCopied(this@FileBrowserActivity, source.path, writtenDest.absolutePath)
                        }
                        successCount++
                    }
                }

                // Process target slots
                for (slot in targetSlots) {
                    for (item in slot.items) {
                        coroutineContext.ensureActive()
                        when (item) {
                            is FileClipboard.ClipItem.Local -> {
                                try {
                                    processLocalItem(item.file, java.io.File(effectiveDestDir, item.file.name), item.operation)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    failCount++
                                }
                            }
                            is FileClipboard.ClipItem.Remote -> {
                                var share = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(this@FileBrowserActivity).getById(item.sourceShareId)
                                if (share?.isServerMode == true && item.sourceRemotePath.isNotEmpty()) {
                                    share = share.copy(remotePath = item.sourceRemotePath)
                                }
                                if (share == null) {
                                    val pairedDevice = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(this@FileBrowserActivity).getPairedDevice(item.sourceShareId)
                                    if (pairedDevice != null && pairedDevice.isConnected) {
                                        share = za.kilowatch.ultimatefilemanager.network.NetworkShare(
                                            id = pairedDevice.deviceId,
                                            name = pairedDevice.name,
                                            type = za.kilowatch.ultimatefilemanager.network.ShareType.TV,
                                            host = pairedDevice.lastIp,
                                            port = pairedDevice.lastPort
                                        )
                                    }
                                }
                                if (share == null) {
                                    val onlineStorage = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(this@FileBrowserActivity).getById(item.sourceShareId)
                                    if (onlineStorage != null) {
                                        share = za.kilowatch.ultimatefilemanager.network.NetworkShare(
                                            id = onlineStorage.id,
                                            name = onlineStorage.displayName,
                                            type = when (onlineStorage.provider) {
                                                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE
                                                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE
                                                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.DROPBOX -> za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX
                                                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.AWS_S3 -> za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3
                                                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2
                                                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.WEBDAV -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
                                                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
                                            },
                                            host = when (onlineStorage.provider) {
                                                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> za.kilowatch.ultimatefilemanager.network.RCloneShareClient.RCLONE_HOST_MARKER
                                                else -> if (onlineStorage.isWebDavProvider) onlineStorage.webDavUrl ?: onlineStorage.email else onlineStorage.s3Endpoint ?: onlineStorage.email
                                            },
                                            username = when (onlineStorage.provider) {
                                                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> onlineStorage.id
                                                else -> if (onlineStorage.isWebDavProvider) onlineStorage.webDavUsername ?: "" else onlineStorage.s3AccessKey ?: ""
                                            },
                                            password = if (onlineStorage.isWebDavProvider) onlineStorage.webDavPassword ?: "" else onlineStorage.s3SecretKey ?: "",
                                            readOnly = false
                                        )
                                    }
                                }

                                if (share != null) {
                                    try {
                                        processNetItem(item.file, java.io.File(currentDir, item.file.name), share, item.operation)
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (isCancelled) throw CancellationException()
                                        failCount++
                                    }
                                } else {
                                    failCount++
                                }
                            }
                        }
                    }
                    FileClipboard.removeSlot(slot.id)
                }

                if (targetSlotId == null) {
                    FileClipboard.clear()
                }
                quickTransferDestDir = null
                flushIndices()

                withContext(Dispatchers.Main) {
                    isTransferring = false
                    dialog.dismiss()

                    if (isQuickTransferPickerMode) {
                        val result = Intent().apply {
                            putExtra(RESULT_SELECTED_LOCAL_PATH, quickTransferDestDir?.absolutePath ?: currentDir.absolutePath)
                            putExtra("QT_SUCCESS_COUNT", successCount)
                            putExtra("QT_FAIL_COUNT", failCount)
                        }
                        setResult(RESULT_OK, result)
                        finish()
                        return@withContext
                    }

                    updatePasteFab()
                    loadDirectory(currentDir)
                    InstantSyncWatcher.notifyDirectoryChanged(this@FileBrowserActivity, currentDir.absolutePath)

                    if (failCount == 0 && successCount > 0) {
                        if (isExtractOperation) showPremiumSnackbar(getString(R.string.extract_move_success, successCount))
                        else showPremiumSnackbar(getString(R.string.paste_success, successCount))
                    } else if (failCount > 0) {
                        showPremiumSnackbar(getString(R.string.paste_error))
                    }
                }
            } finally {
                isTransferring = false
                za.kilowatch.ultimatefilemanager.util.TransferService.stop(this@FileBrowserActivity)
            }
        }
    }

    private suspend fun syncFolderWithIndex(directory: File) {
        if (UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) return
        
        try {
            val db = UfmIndexingDatabase.getInstance(this@FileBrowserActivity)
            val dao = db.fileIndexDao()
            val metadataExtractor = MetadataExtractor(this@FileBrowserActivity)
            
            // 1. Snapshot physical files
            val isSaf = directory is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(directory.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, directory.absolutePath)
            if (isSaf) {
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.invalidatePath(directory.absolutePath)
            }
            val actualFiles: List<File> = if (isSaf) {
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.listFiles(this@FileBrowserActivity, directory.absolutePath)
            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(directory.absolutePath)) {
                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.listFiles(directory.absolutePath)
            } else {
                directory.listFiles()?.toList() ?: emptyList<File>()
            }

            val actualFilePaths = actualFiles.map { it.absolutePath }.toSet()
            
            // 2. Extract explicitly missing or changed metadata (Fast Path)
            val fileIndices = actualFiles.map { 
                metadataExtractor.extractMetadata(it, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE) 
            }
            if (fileIndices.isNotEmpty()) {
                dao.insertAll(fileIndices)
            }
            
            // 3. Remove DB entries that are physically gone
            val existingInDb = dao.getFilesInFolder(directory.absolutePath)
            val stalePaths = existingInDb.map { it.path }.filter { it !in actualFilePaths }
            stalePaths.forEach { dao.deleteByPath(it) }
            
            Log.d("FileBrowser", "Synchronized folder to DB index: ${directory.absolutePath}")
        } catch (e: Exception) {
            Log.e("FileBrowser", "Error syncing folder: ${e.message}")
        }
    }

    private fun loadDirectory(directory: File, preserveSelection: Boolean = false) {
        if (isTransferring) return   // Don't refresh while a copy/move is in progress

        val pathStr = SafFile.cleanSafPath(directory.absolutePath)
        val isProtected = za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isProtectedPath(pathStr)
        val canUseShizuku = isProtected && za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(pathStr)
        val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(pathStr) || (isProtected && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, pathStr))

        za.kilowatch.ultimatefilemanager.util.GoRoLog.d("SafStorage", "loadDirectory: path=$pathStr, isProtected=$isProtected, canUseShizuku=$canUseShizuku, isSaf=$isSaf, directoryClass=${directory::class.java.simpleName}")

        val targetDir = if (canUseShizuku && directory !is za.kilowatch.ultimatefilemanager.storage.ShizukuFile) {
            val pName = pathStr.substringAfterLast("/")
            val pParent = pathStr.substringBeforeLast("/", "")
            za.kilowatch.ultimatefilemanager.storage.ShizukuFile(pParent, pName, true)
        } else if (isSaf && directory !is za.kilowatch.ultimatefilemanager.storage.SafFile) {
            za.kilowatch.ultimatefilemanager.storage.SafFile(pathStr, true)
        } else {
            directory
        }

        // Clear file selection highlight when navigating directories
        if (isSupportAttachmentPicker) {
            selectedKeyFilePath = null
            fileAdapter.focusedPath = null
        }

        // Load folder-specific sort settings (or fall back to global) on IO thread
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val state = SortFilterPreferenceManager.loadForPath(this@FileBrowserActivity, targetDir.absolutePath)
                ?: SortFilterPreferenceManager.loadGlobal(this@FileBrowserActivity)
            val hasFolderOverride = SortFilterPreferenceManager.hasFolderOverride(this@FileBrowserActivity, targetDir.absolutePath)
            val viewModeToApply = state.viewMode ?: ViewModeManager.load(this@FileBrowserActivity)
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

        currentDir = targetDir
        updateBreadcrumbs()

        // Exit selection mode when navigating or after an operation. Preserve it only
        // when reloading the same directory on resume (background/foreground).
        if (fileAdapter.isSelectionMode && !preserveSelection) {
            fileAdapter.exitSelectionMode()
        }

        // Update toolbar (mobile) or TV header views
        val title = if (isCategoryMode) (categoryName ?: storageLabel) else {
             if (targetDir.absolutePath == rootPath) storageLabel else targetDir.name
        }
        toolbar.title = title
        val subtitleText = if (isCategoryMode) {
            getString(R.string.files_on_storagelabel, storageLabel)
        } else if (targetDir.absolutePath == rootPath) {
            if (SafTreeManager.isSafPath(targetDir.absolutePath)) {
                getString(R.string.saf_storage)
            } else {
                try {
                    val freeBytes = targetDir.freeSpace
                    val totalBytes = targetDir.totalSpace
                    if (totalBytes > 0) {
                        val freeStr = android.text.format.Formatter.formatFileSize(this, freeBytes)
                        val totalStr = android.text.format.Formatter.formatFileSize(this, totalBytes)
                        "$freeStr free of $totalStr"
                    } else {
                        targetDir.absolutePath
                    }
                } catch (_: Exception) {
                    targetDir.absolutePath
                }
            }
        } else {
            val rel = targetDir.absolutePath.removePrefix(rootPath).trimStart('/')
            if (rel.isNotEmpty()) rel else targetDir.absolutePath
        }
        toolbar.subtitle = subtitleText
        // TV and Mobile header views
        findViewById<android.widget.TextView>(R.id.txtTvTitle)?.text = title
        findViewById<android.widget.TextView>(R.id.txtTvSubtitle)?.text = subtitleText
        findViewById<android.widget.TextView>(R.id.txtSubtitle)?.text = subtitleText

        val badgeStorage = findViewById<android.widget.TextView>(R.id.badgeStorageType)
        if (badgeStorage != null && !isTv) {
            badgeStorage.visibility = View.VISIBLE
            badgeStorage.text = when {
                storageLabel.contains("Vault", ignoreCase = true) -> "VAULT"
                isCategoryMode -> "CATEGORY"
                rootPath.contains("emulated", ignoreCase = true) -> "LOCAL"
                rootPath.contains("sdcard", ignoreCase = true) || rootPath.contains("storage/", ignoreCase = true) -> "STORAGE"
                else -> "LOCAL"
            }
        }

        if (isCategoryMode) {
            // Hide "New Folder" and "Paste" in category mode
            findViewById<View>(R.id.btnCreateNew)?.visibility = View.GONE
            findViewById<View>(R.id.btnViewToggle)?.visibility = View.GONE
            updatePasteFab() // This will check isCategoryMode
        }

        // Show files: 
        // 1. If indexing is NOT declined, collect from Room Flow for reactive updates.
        // 2. If indexing IS declined, fetch directly from filesystem via listFiles().
        folderFlowJob?.cancel()

        // Cert and keyfile pickers always list directly from the filesystem so that
        // unindexed SD cards (and any other storage) are fully browseable with all
        // files visible — no hidden-file filtering or Room DB dependency.
        if (isCertPickerMode || isKeyfilePickerMode) {
            folderFlowJob = lifecycleScope.launch(Dispatchers.IO) {
                val rawFiles = directory.listFiles()?.toList() ?: emptyList()
                val sorted = rawFiles.sortedWith(
                    compareBy<File> { !it.isDirectory }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
                withContext(Dispatchers.Main) {
                    fileAdapter.submitList(sorted, showAllAsIndexed = false, hiddenPaths = emptySet())
                    updateEmptyState(sorted.isEmpty())
                }
            }
            return
        }

        val hasDeclined = UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)
        
        if (isCategoryMode) {
            // Reset pagination state for each fresh category load (drive change, etc.)
            categoryPage = 0
            categoryAllLoaded = false
            isCategoryLoading = false

            // Remove any existing scroll listener before attaching a new one
            categoryScrollListener?.let { recyclerFiles.removeOnScrollListener(it) }
            categoryScrollListener = null

            if (storageId.isNotEmpty()) {
                // ── Indexed storage: paginated DB query ─────────────────────
                folderFlowJob = lifecycleScope.launch {
                    try {
                        loadCategoryPage(append = false)

                        // Attach scroll listener for subsequent pages
                        val listener = object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                                if (dy <= 0 || categoryAllLoaded || isCategoryLoading) return
                                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                                val lastVisible = lm.findLastVisibleItemPosition()
                                val total = rv.adapter?.itemCount ?: return
                                if (total - lastVisible <= 50) {
                                    // Near bottom — fetch next page
                                    isCategoryLoading = true
                                    lifecycleScope.launch {
                                        try { loadCategoryPage(append = true) }
                                        finally { isCategoryLoading = false }
                                    }
                                }
                            }
                        }
                        categoryScrollListener = listener
                        recyclerFiles.addOnScrollListener(listener)
                    } catch (e: Exception) {
                        Log.w("FileBrowser", "Error fetching category files: ${e.message}")
                        withContext(Dispatchers.Main) {
                            fileAdapter.submitList(emptyList())
                            updateEmptyState(true)
                        }
                    }
                }
            } else {
                // ── Non-indexed storage: full filesystem walk ────────────────
                folderFlowJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
                        val hiddenPaths = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase.getInstance(applicationContext).hiddenFileDao().getAllPaths().toSet()
                        val isSaf = currentDir is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(currentDir.absolutePath) ||
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, currentDir.absolutePath)
                        val files = if (isSaf) {
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.walkSafTopDown(this@FileBrowserActivity, currentDir.absolutePath)
                                .filter { coroutineContext.ensureActive(); true }
                                .filter { !it.isDirectory && SortFilterSheet.matchesFilter(it, filterType) }
                                .filter { isFileVisible(it, showHidden, hiddenPaths) }
                                .sortedByDescending { it.lastModified() }
                        } else {
                            currentDir.walkTopDown()
                                .filter { coroutineContext.ensureActive(); true }
                                .filter { it.isFile && SortFilterSheet.matchesFilter(it, filterType) }
                                .filter { isFileVisible(it, showHidden, hiddenPaths) }
                                .sortedByDescending { it.lastModified() }
                                .toList()
                        }
                        withContext(Dispatchers.Main) {
                            submitAdapterList {
                                fileAdapter.submitList(files, showAllAsIndexed = false, hiddenPaths = hiddenPaths)
                                updateEmptyState(files.isEmpty())
                                applyFileFocus()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("FileBrowser", "Category walk error: ${e.message}")
                        withContext(Dispatchers.Main) {
                            fileAdapter.submitList(emptyList())
                            updateEmptyState(true)
                        }
                    }
                }
            }
        } else if (hasDeclined) {
            folderFlowJob = lifecycleScope.launch(Dispatchers.IO) {
                val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
                val hiddenPaths = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase.getInstance(applicationContext).hiddenFileDao().getAllPaths().toSet()

                val db = UfmIndexingDatabase.getInstance(applicationContext)
                val dao = db.fileIndexDao()
                
                val rawFiles = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(targetDir.absolutePath)) {
                    coroutineContext.ensureActive()
                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.listFiles(targetDir.absolutePath)
                } else if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(targetDir.absolutePath) || za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, targetDir.absolutePath)) {
                    coroutineContext.ensureActive()
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.listFiles(this@FileBrowserActivity, targetDir.absolutePath)
                } else {
                    coroutineContext.ensureActive()
                    targetDir.listFiles()?.toList() ?: emptyList()
                }
                
                val visibleFiles = rawFiles.filter { isFileVisible(it, showHidden, hiddenPaths) }

                val indexedPaths = try { dao.getIndexedPathsInFolder(targetDir.absolutePath).toSet() } catch (e: Exception) { emptySet<String>() }
                val sorted = sortAndFilterFiles(visibleFiles)
                withContext(Dispatchers.Main) {
                    submitAdapterList {
                        fileAdapter.submitList(sorted, indexedPaths = indexedPaths, hiddenPaths = hiddenPaths)
                        updateEmptyState(sorted.isEmpty())
                        applyFileFocus()
                    }
                }
            }
        } else {
            folderFlowJob = lifecycleScope.launch {
                try {
                    val db = UfmIndexingDatabase.getInstance(applicationContext)
                    val dao = db.fileIndexDao()
                    dao.getFilesInFolderFlow(targetDir.absolutePath).collectLatest { fileIndices ->
                        withContext(Dispatchers.IO) {
                            val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
                            val hiddenPaths = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase.getInstance(applicationContext).hiddenFileDao().getAllPaths().toSet()

                            if (fileIndices.isEmpty()) {
                                // Fallback to filesystem if DB has no entries yet
                                val rawFiles = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(targetDir.absolutePath)) {
                                    coroutineContext.ensureActive()
                                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.listFiles(targetDir.absolutePath)
                                } else if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(targetDir.absolutePath) || za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, targetDir.absolutePath)) {
                                    coroutineContext.ensureActive()
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.listFiles(this@FileBrowserActivity, targetDir.absolutePath)
                                } else {
                                    coroutineContext.ensureActive()
                                    targetDir.listFiles()?.toList() ?: emptyList()
                                }
                                val visibleFiles = rawFiles.filter { isFileVisible(it, showHidden, hiddenPaths) }
                                val sorted = sortAndFilterFiles(visibleFiles)
                                withContext(Dispatchers.Main) {
                                    submitAdapterList {
                                        fileAdapter.submitList(sorted, showAllAsIndexed = false, hiddenPaths = hiddenPaths)
                                        updateEmptyState(sorted.isEmpty())
                                        applyFileFocus()
                                    }
                                }
                            } else {
                                val files = fileIndices.map { index ->
                                    if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(index.path)) {
                                        za.kilowatch.ultimatefilemanager.storage.ShizukuFile(
                                            parentPath = index.folderPath,
                                            docName = index.filename,
                                            isDir = index.isDirectory,
                                            docLength = index.size,
                                            docLastModified = index.lastModified
                                        )
                                    } else if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, index.path)) {
                                        za.kilowatch.ultimatefilemanager.storage.SafFile(
                                            parentPath = index.folderPath,
                                            docName = index.filename,
                                            isDir = index.isDirectory,
                                            docLength = index.size,
                                            docLastModified = index.lastModified
                                        )
                                    } else {
                                        File(index.path)
                                    }
                                }.filter { file ->
                                    (file is za.kilowatch.ultimatefilemanager.storage.ShizukuFile || file is za.kilowatch.ultimatefilemanager.storage.SafFile || file.exists()) && isFileVisible(file, showHidden, hiddenPaths)
                                }
                                val sorted = sortAndFilterFiles(files)
                                withContext(Dispatchers.Main) {
                                    submitAdapterList {
                                        fileAdapter.submitList(sorted, showAllAsIndexed = true, hiddenPaths = hiddenPaths)
                                        updateEmptyState(sorted.isEmpty())
                                        applyFileFocus()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("FileBrowser", "Error collecting folder index flow: ${e.message}")
                    withContext(Dispatchers.Main) {
                        fileAdapter.submitList(emptyList())
                        updateEmptyState(true)
                    }
                }
            }
        }

        // Background sync to ensure index matches filesystem
        lifecycleScope.launch(Dispatchers.IO) {
            syncFolderWithIndex(targetDir)
        }
    }

    private fun applyFileFocus() {
        val path = focusPath ?: return
        val pos = fileAdapter.findPosition(path)
        if (pos != -1) {
            fileAdapter.focusedPath = path
            recyclerFiles.scrollToPosition(pos)
            
            // Clear highlight after 3 seconds
            lifecycleScope.launch {
                delay(3000)
                if (fileAdapter.focusedPath == path) {
                    fileAdapter.focusedPath = null
                }
            }
        }
        // Only focus once per intent
        focusPath = null
    }

    /**
     * Determines whether a file should be visible in the file list.
     * When [showHidden] is false, filters out both:
     * - Files/folders whose name starts with "." (Unix dotfile convention)
     * - Files whose absolute path is in the explicit hidden-paths database
     */
    private fun isFileVisible(file: File, showHidden: Boolean, hiddenPaths: Set<String>): Boolean {
        return showHidden || (!HiddenFilesManager.isJunkOrHidden(file.name) && file.absolutePath !in hiddenPaths)
    }

    /**
     * Helper to apply current filter, picker extensions, and sorting to a list of files.
     */
    private fun sortAndFilterFiles(files: List<File>): List<File> {
        val preFiltered = if (isPickerMode && pickerExtensions.isNotEmpty()) {
            files.filter { it.isDirectory || it.extension.lowercase() in pickerExtensions }
        } else files

        val filtered = preFiltered.filter { SortFilterSheet.matchesFilter(it, filterType) }

        val tagFiltered = if (activeTagsFilter.isNotEmpty()) {
            filtered.filter { it.isDirectory || FileTagsManager.getTags(this, it.absolutePath).any { t -> t in activeTagsFilter } }
        } else {
            filtered
        }

        val secondaryComparator: Comparator<File> = when (sortMode) {
            SortFilterSheet.SortMode.NAME -> compareBy(NaturalSort.order) { f: File -> f.name }
            SortFilterSheet.SortMode.SIZE -> compareBy { f: File -> if (f.isDirectory) 0L else f.length() }
            SortFilterSheet.SortMode.DATE -> compareBy { f: File -> f.lastModified() }
            SortFilterSheet.SortMode.TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { f: File -> f.extension }
        }
        val orderedComparator = if (sortOrder == SortFilterSheet.SortOrder.DESC) secondaryComparator.reversed() else secondaryComparator
        
        val customComparator = Comparator<File> { f1, f2 ->
            val p1 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(applicationContext, f1.absolutePath)
            val p2 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(applicationContext, f2.absolutePath)
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
        return tagFiltered.sortedWith(customComparator)
    }

    private fun openFile(file: File, transitionView: android.view.View? = null) {
        val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) || za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, file.absolutePath)
        val safDocUri = if (isSaf) (file as? za.kilowatch.ultimatefilemanager.storage.SafFile)?.documentUri ?: za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getDocumentUriForPath(this, file.absolutePath) else null

        // Try built-in viewer first (with optional shared element)
        if (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.openFile(this, file, transitionView)) return

        // Fall back to external app
        try {
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

            val uri: Uri = safDocUri ?: FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                showPremiumSnackbar(getString(R.string.no_app_found_to_open_this_file_type))
            }
        } catch (e: Exception) {
            showPremiumSnackbar(getString(R.string.unable_to_open_file_emessage))
        }
    }

    private fun setFolderIndexingIndicator(show: Boolean) {
        try {
            val base = currentDir.absolutePath
            val subtitle = if (show) getString(R.string.base_indexing) else base
            toolbar.subtitle = subtitle
            findViewById<android.widget.TextView>(R.id.txtTvSubtitle)?.text = subtitle
        } catch (e: Exception) {
            // Ignore UI errors
        }
    }

    /**
     * Shows a modern Material Snackbar with premium styling.
     */
    fun showPremiumSnackbar(message: String) {
        val rootView = findViewById<View>(R.id.main)
        Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.ufm_surface_variant))
            .setTextColor(getColor(R.color.ufm_text_primary))
            .setActionTextColor(getColor(R.color.ufm_primary))
            .show()
    }

    /**
     * Entry point for Copy & Encrypt / Move & Encrypt.
     * - Folders: always auto-create a new vault entry (original always deleted). Show warning first.
     * - Files: existing vault picker dialog (copy keeps original, move deletes).
     */
    private fun showVaultPickerForEncrypt(files: List<File>, isMove: Boolean) {
        val folders = files.filter { it.isDirectory }
        val fileItems = files.filter { it.isFile }

        if (folders.isNotEmpty()) {
            // Show premium warning dialog before encrypting folders
            showFolderEncryptWarning(folders, fileItems, isMove)
        } else {
            // Only files — use existing vault picker
            showVaultPickerForFiles(fileItems, isMove)
        }
    }

    /**
     * Premium warning dialog for folder encryption.
     * Yellow confirm button + black text to match design system.
     */
    private fun showFolderEncryptWarning(folders: List<File>, extraFiles: List<File>, isMove: Boolean) {
        val folderNames = folders.joinToString(", ") { it.name }
        val message = getString(R.string.vault_folder_encrypt_warning, folderNames)
        val isTv = DeviceUtils.isTvDevice(this)

        val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val imgIcon = dialogView.findViewById<android.widget.ImageView>(R.id.imgDialogIcon)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDialogMessage)
        val btnPositive = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogNegative)

        imgIcon?.setImageResource(R.drawable.ic_lock)
        txtTitle?.setText(R.string.vault_folder_encrypt_title)
        txtMessage?.text = message
        btnPositive?.setText(R.string.vault_folder_encrypt_confirm)
        btnNegative?.visibility = View.VISIBLE
        btnNegative?.setText(android.R.string.cancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnPositive?.setOnClickListener {
            dialog.dismiss()
            folders.forEach { folder ->
                encryptFolderToNewVaultEntry(folder)
            }
            if (extraFiles.isNotEmpty()) {
                showVaultPickerForFiles(extraFiles, isMove)
            }
        }

        btnNegative?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    /**
     * Auto-creates a new vault entry for a folder (same logic as VaultActivity.encryptFolder).
     * Always deletes the original folder after encryption.
     */
    private fun encryptFolderToNewVaultEntry(root: File) {
        val isSaf = root is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(root.absolutePath) ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, root.absolutePath)
        val exists = if (isSaf) za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(this, root.absolutePath) else (root.exists() && root.isDirectory)
        if (!exists) return

        val progressView = layoutInflater.inflate(R.layout.dialog_vault_progress, null)
        val txtProgress = progressView.findViewById<TextView>(R.id.txtVaultProgress)
        val progressBar = progressView.findViewById<android.widget.ProgressBar>(R.id.progressVault)

        val progressDialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(getString(R.string.encrypt_copy_title))
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()
        progressDialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val entryId = java.util.UUID.randomUUID().toString()
                    val vaultBase = File(filesDir, "vault")
                    val entryDir = File(vaultBase, entryId)
                    entryDir.mkdirs()

                    val allFiles = if (isSaf) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.walkSafTopDown(this@FileBrowserActivity, root.absolutePath)
                            .filter { !it.isDirectory }
                            .filter { !isSystemFile(it) }
                    } else {
                        root.walkTopDown()
                            .filter { it.isFile }
                            .filter { !isSystemFile(it) }
                            .toList()
                    }

                    val total = allFiles.size.coerceAtLeast(1)
                    val relativeList = mutableListOf<String>()

                    allFiles.forEachIndexed { index, file ->
                        val relative = if (isSaf) {
                            val normRoot = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.normalizePath(root.absolutePath)
                            val normFile = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.normalizePath(file.absolutePath)
                            normFile.removePrefix(normRoot).removePrefix("/")
                        } else {
                            file.relativeTo(root).path
                        }
                        val encryptedFile = File(entryDir, "$relative.enc")
                        encryptedFile.parentFile?.mkdirs()
                        VaultCrypto.encryptFile(this@FileBrowserActivity, file, encryptedFile)
                        relativeList.add(relative)
                        if (isSaf) {
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(this@FileBrowserActivity, file.absolutePath)
                        } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(file.absolutePath)) {
                            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(file.absolutePath)
                        } else {
                            file.delete()
                        }

                        val percent = (((index + 1).toFloat() / total.toFloat()) * 100).toInt()
                        runOnUiThread {
                            txtProgress.text = getString(R.string.encrypt_progress, index + 1, total)
                            progressBar.progress = percent
                        }
                    }

                    // Clean empty directories
                    if (isSaf) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.deleteRecursively(this@FileBrowserActivity, root.absolutePath)
                    } else {
                        root.walkBottomUp().forEach { dir ->
                            if (dir.isDirectory) {
                                val isEmpty = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(dir.absolutePath)) {
                                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.listFiles(dir.absolutePath).isEmpty()
                                } else {
                                    dir.listFiles()?.isEmpty() == true
                                }
                                if (isEmpty) {
                                    if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(dir.absolutePath)) {
                                        za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(dir.absolutePath)
                                    } else {
                                        dir.delete()
                                    }
                                }
                            }
                        }
                    }

                    val sortedRelatives = relativeList.toList()
                    val metadata = org.json.JSONObject().apply {
                        put("id", entryId)
                        put("displayName", "enc:" + VaultCrypto.encryptString(root.name))
                        put("originalRoot", "enc:" + VaultCrypto.encryptString(root.absolutePath))
                        put("filesPayload", VaultCrypto.encryptStrings(sortedRelatives))
                        put("files", org.json.JSONArray(sortedRelatives.map { "enc:" + VaultCrypto.encryptString(it) }))
                    }
                    val tempMeta = File(entryDir, "metadata.json.tmp")
                    tempMeta.writeText(metadata.toString())
                    val finalMeta = File(entryDir, "metadata.json")
                    finalMeta.delete()
                    tempMeta.renameTo(finalMeta)
                    true
                } catch (_: Exception) { false }
            }

            progressDialog.dismiss()
            fileAdapter.exitSelectionMode()
            loadDirectory(currentDir)

            if (success) {
                showPremiumSnackbar(getString(R.string.encrypt_success, 1))
            } else {
                showPremiumSnackbar(getString(R.string.encrypt_error))
            }
        }
    }

    private fun performExtractHere(files: List<File>) {
        showExtractOptions(files)
    }

    private fun showExtractOptions(files: List<File>) {
        val archives = files.filter { ArchiveManager.isSupportedArchive(it) }
        if (archives.isEmpty()) return

        val dialog = za.kilowatch.ultimatefilemanager.archive.ExtractOptionsDialog.newInstance(archives.map { it.name })
        dialog.setOnExtractHere {
            performExtract(archives, isSelectFolderMode = false)
        }
        dialog.setOnExtractToNewFolder {
            promptExtractToNewFolder(archives)
        }
        dialog.setOnExtractAndSelectFolder {
            performExtract(archives, isSelectFolderMode = true)
        }
        dialog.show(supportFragmentManager, za.kilowatch.ultimatefilemanager.archive.ExtractOptionsDialog.TAG)
    }

    private fun promptExtractToNewFolder(archives: List<File>) {
        if (archives.isEmpty()) return
        val defaultName = if (archives.size == 1) za.kilowatch.ultimatefilemanager.archive.ArchiveManager.getArchiveBaseName(archives.first().name) else "Extracted"
        val isOnTv = DeviceUtils.isTvDevice(this)

        val layoutRes = if (isOnTv) R.layout.dialog_create_folder_tv else R.layout.dialog_create_folder
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)
        val edtFolderName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtFolderName)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtTitle)
        val btnCreate = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCreate)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtTitle?.setText(R.string.extract_new_folder_title)
        btnCreate?.setText(R.string.extract_to_new_folder)
        edtFolderName?.hint = getString(R.string.extract_new_folder_hint)
        edtFolderName?.setText(defaultName)
        edtFolderName?.selectAll()

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        btnCreate?.setOnClickListener {
            val name = edtFolderName?.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                showPremiumSnackbar(getString(R.string.new_folder_empty))
                return@setOnClickListener
            }
            dialog.dismiss()
            val isCurrentSaf = currentDir is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                              za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(currentDir.absolutePath) ||
                              za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, currentDir.absolutePath)
            val newDir = if (isCurrentSaf) {
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.mkdir(this, currentDir.absolutePath, name)
                za.kilowatch.ultimatefilemanager.storage.SafFile(za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getSafChildPath(currentDir.absolutePath, name), isDir = true)
            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(currentDir.absolutePath)) {
                za.kilowatch.ultimatefilemanager.storage.ShizukuFile(currentDir.absolutePath, name, true).apply { if (!exists()) mkdirs() }
            } else {
                File(currentDir, name).apply { if (!exists()) mkdirs() }
            }
            performExtract(archives, customDestFolder = newDir, isSelectFolderMode = false)
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        DialogInputHelper.setupDialogInput(dialog, edtFolderName) {
            btnCreate?.performClick()
        }
    }

    private fun performExtract(archives: List<File>, customDestFolder: File? = null, isSelectFolderMode: Boolean) {
        if (archives.isEmpty()) return

        fileAdapter.exitSelectionMode()

        lifecycleScope.launch(Dispatchers.Main) {
            val isTv = DeviceUtils.isTvDevice(this@FileBrowserActivity)
            val layoutRes = if (isTv) R.layout.dialog_transfer_progress_tv else R.layout.dialog_transfer_progress
            val dialogView = layoutInflater.inflate(layoutRes, null)
            val txtTitle = dialogView.findViewById<TextView>(R.id.txtProgressTitle)
            val txtCurrentFile = dialogView.findViewById<TextView>(R.id.txtProgressCurrentFile)
            txtTitle?.setText(R.string.extract_progress_title)
            txtCurrentFile?.text = archives.first().name

            val progressDialog = MaterialAlertDialogBuilder(this@FileBrowserActivity, R.style.UFM_Dialog)
                .setView(dialogView)
                .setCancelable(false)
                .create()
            progressDialog.show()
            progressDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

            var extractedCount = 0
            var lastError: Exception? = null
            val tempExtractDir = if (isSelectFolderMode) {
                File(cacheDir, "extract_temp_${System.currentTimeMillis()}").apply { mkdirs() }
            } else null

            withContext(Dispatchers.IO) {
                for (archive in archives) {
                    var password: String? = null
                    var success = false
                    var attempts = 0
                    withContext(Dispatchers.Main) {
                        txtCurrentFile?.text = archive.name
                    }

                    val targetDest = if (isSelectFolderMode && tempExtractDir != null) {
                        if (archives.size > 1) {
                            File(tempExtractDir, za.kilowatch.ultimatefilemanager.archive.ArchiveManager.getArchiveBaseName(archive.name)).apply { mkdirs() }
                        } else {
                            tempExtractDir
                        }
                    } else if (customDestFolder != null) {
                        val isCustomSaf = customDestFolder is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                                         za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(customDestFolder.absolutePath) ||
                                         za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, customDestFolder.absolutePath)
                        if (archives.size > 1) {
                            val subName = za.kilowatch.ultimatefilemanager.archive.ArchiveManager.getArchiveBaseName(archive.name)
                            if (isCustomSaf) {
                                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.mkdir(this@FileBrowserActivity, customDestFolder.absolutePath, subName)
                                za.kilowatch.ultimatefilemanager.storage.SafFile(za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getSafChildPath(customDestFolder.absolutePath, subName), isDir = true)
                            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(customDestFolder.absolutePath)) {
                                za.kilowatch.ultimatefilemanager.storage.ShizukuFile(customDestFolder.absolutePath, subName, true).apply { if (!exists()) mkdirs() }
                            } else {
                                File(customDestFolder, subName).apply { if (!exists()) mkdirs() }
                            }
                        } else {
                            customDestFolder
                        }
                    } else {
                        val baseParent = archive.parentFile ?: currentDir
                        val isParentSaf = baseParent is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                                         za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(baseParent.absolutePath) ||
                                         za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, baseParent.absolutePath)
                        if (archives.size > 1) {
                            val subName = za.kilowatch.ultimatefilemanager.archive.ArchiveManager.getArchiveBaseName(archive.name)
                            if (isParentSaf) {
                                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.mkdir(this@FileBrowserActivity, baseParent.absolutePath, subName)
                                za.kilowatch.ultimatefilemanager.storage.SafFile(za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getSafChildPath(baseParent.absolutePath, subName), isDir = true)
                            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(baseParent.absolutePath)) {
                                za.kilowatch.ultimatefilemanager.storage.ShizukuFile(baseParent.absolutePath, subName, true).apply { if (!exists()) mkdirs() }
                            } else {
                                File(baseParent, subName).apply { if (!exists()) mkdirs() }
                            }
                        } else {
                            baseParent
                        }
                    }

                    while (!success && attempts < 3) {
                        val result = ArchiveManager.extract(
                            this@FileBrowserActivity,
                            archive,
                            targetDest,
                            password,
                            onProgress = {},
                            onConflict = { file, isFolder, destSizeBytes, applyToAllRef ->
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                    this@FileBrowserActivity,
                                    file.name,
                                    isFolder,
                                    destSizeBytes,
                                    applyToAllRef
                                )
                            }
                        )

                        if (result.isSuccess) {
                            success = true
                            extractedCount++
                        } else {
                            val ex = result.exceptionOrNull()
                            lastError = ex as? Exception ?: Exception(ex?.message)
                            val msg = ex?.message?.lowercase(java.util.Locale.ROOT) ?: ""
                            val isEncryptedErr = msg.contains("password") || msg.contains("encrypt") ||
                                    msg.contains("decrypt") || (ex is net.lingala.zip4j.exception.ZipException)

                            if (attempts == 0 && (isEncryptedErr || password == null)) {
                                val pwd = withContext(Dispatchers.Main) {
                                    suspendCancellableCoroutine<String?> { cont ->
                                        val dialog = za.kilowatch.ultimatefilemanager.archive.PasswordPromptDialog()
                                        dialog.setOnConfirm { pw ->
                                            if (cont.isActive) cont.resume(pw)
                                        }
                                        dialog.setOnCancel {
                                            if (cont.isActive) cont.resume(null)
                                        }
                                        dialog.show(supportFragmentManager, za.kilowatch.ultimatefilemanager.archive.PasswordPromptDialog.TAG)
                                    }
                                }
                                if (pwd == null) {
                                    break
                                }
                                password = pwd
                                attempts++
                            } else {
                                break
                            }
                        }
                    }
                }
            }

            progressDialog.dismiss()

            if (isSelectFolderMode && tempExtractDir != null) {
                if (extractedCount > 0) {
                    val extractedFiles = tempExtractDir.listFiles()?.toList() ?: emptyList()
                    if (extractedFiles.isNotEmpty()) {
                        FileClipboard.setExtract(extractedFiles, tempExtractDir)
                        updatePasteFab()
                        showPremiumSnackbar(getString(R.string.extract_staged_snackbar))
                    } else {
                        tempExtractDir.deleteRecursively()
                        showPremiumSnackbar(getString(R.string.extract_error, "No files extracted"))
                    }
                } else {
                    tempExtractDir.deleteRecursively()
                    if (lastError != null) {
                        showPremiumSnackbar(getString(R.string.extract_error, lastError?.message ?: "Extraction failed"))
                    }
                }
            } else {
                loadDirectory(currentDir)
                if (extractedCount > 0) {
                    showPremiumSnackbar(getString(R.string.extract_success, extractedCount))
                } else if (lastError != null) {
                    showPremiumSnackbar(getString(R.string.extract_error, lastError?.message ?: "Extraction failed"))
                }
            }
        }
    }

    /**
     * Shows vault picker dialog for encrypting files (files only, not folders).
     * If no vaults exist, prompts user to create one first.
     */
    private fun showArchiveOptions(files: List<File>) {
        val dialog = ArchiveOptionsDialog()
        dialog.setOnConfirm { filename, format, password, useCurrentFolder ->
            if (useCurrentFolder) {
                performCompression(files, currentDir, filename, format, password)
            } else {
                // Stash so folderPickerLauncher can reach them for network destinations
                pendingCompressSourceFiles = files
                pendingCompressFileName    = filename
                pendingCompressFormat      = format
                pendingCompressPassword    = password
                pickDestinationFolder { destDir ->
                    performCompression(files, destDir, filename, format, password)
                }
            }
        }
        dialog.show(supportFragmentManager, "ArchiveOptions")
    }

    private fun pickDestinationFolder(callback: (File) -> Unit) {
        onFolderPicked = callback
        val intent = Intent(this, StorageBrowserActivity::class.java).apply {
            putExtra(StorageBrowserActivity.EXTRA_COMPRESS_DEST_PICKER, true)
        }
        folderPickerLauncher.launch(intent)
    }

    /** Resolves a share ID to a NetworkShare — checks SMB/FTP repo first, then paired TV/Phone devices. */
    private fun resolveShareById(id: String): za.kilowatch.ultimatefilemanager.network.NetworkShare? {
        val fromRepo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(this).getById(id)
        if (fromRepo != null) return fromRepo
        val dev = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(this).getPairedDevice(id)
        if (dev != null) return za.kilowatch.ultimatefilemanager.network.NetworkShare(
            id = dev.deviceId, name = dev.name,
            type = za.kilowatch.ultimatefilemanager.network.ShareType.TV,
            host = dev.lastIp, port = dev.lastPort, readOnly = false
        )
        return null
    }

    /**
     * Compresses [sourceFiles] locally to a temp archive, then uploads the archive to
     * the given network [share] at [remotePath] via TvShareClient / SmbShareClient / FtpShareClient.
     * Called when the user picks a Phone / SMB / FTP / TV destination for a local compress.
     */
    private fun performNetworkUploadCompress(
        sourceFiles: List<File>,
        share: za.kilowatch.ultimatefilemanager.network.NetworkShare,
        remotePath: String,
        customFileName: String,
        format: ArchiveManager.Format,
        password: String?
    ) {
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val statusText = android.widget.TextView(this).apply {
            text = getString(R.string.compressing_2)
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

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.compressing_uploading)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        val extension = format.displayName
        val archiveName = "$customFileName$extension"

        val job = lifecycleScope.launch(Dispatchers.IO) {
            val tempArchive = File(cacheDir, "local_comp_${System.currentTimeMillis()}$extension")
            try {
                // 1. Compress locally
                ArchiveManager.compress(sourceFiles, tempArchive, password, format) { progress ->
                    runOnUiThread { dialogProgress.progress = (progress * 0.7f).toInt() }
                }

                // 2. Upload to network destination
                withContext(Dispatchers.Main) { statusText.text = getString(R.string.uploading_to_sharename) }
                val destPath = if (remotePath.isEmpty()) archiveName else "$remotePath/$archiveName"
                val inStream = tempArchive.inputStream()
                try {
                    when (share.type) {
                        za.kilowatch.ultimatefilemanager.network.ShareType.TV  ->
                            za.kilowatch.ultimatefilemanager.network.TvShareClient.uploadStream(share, destPath, inStream, tempArchive.length())
                        za.kilowatch.ultimatefilemanager.network.ShareType.SMB ->
                            za.kilowatch.ultimatefilemanager.network.SmbShareClient.openOutputStream(share, destPath)
                                .use { out -> inStream.copyTo(out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.FTP ->
                            za.kilowatch.ultimatefilemanager.network.FtpShareClient.openOutputStream(share, destPath)
                                .use { out -> inStream.copyTo(out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP ->
                            za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(share, destPath)
                                .use { out -> inStream.copyTo(out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE ->
                            za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openOutputStream(share, destPath)
                                .use { out -> inStream.copyTo(out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE ->
                            za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openOutputStream(share, destPath)
                                .use { out -> inStream.copyTo(out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX ->
                            za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openOutputStream(share, destPath)
                                .use { out -> inStream.copyTo(out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 ->
                            za.kilowatch.ultimatefilemanager.network.S3ShareClient.openOutputStream(share, destPath)
                                .use { out -> inStream.copyTo(out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV ->
                            za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(share, destPath)
                                .use { out -> inStream.copyTo(out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.NFS ->
                            za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(share, destPath)
                                .use { out -> inStream.copyTo(out) }
                        za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                    }
                } finally {
                    inStream.close()
                }

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    fileAdapter.exitSelectionMode()
                    showPremiumSnackbar(getString(R.string.compression_complete_archivename_sharename))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    showPremiumSnackbar(getString(R.string.compression_failed_emessage))
                }
            } finally {
                tempArchive.delete()
            }
        }

        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            job.cancel()
            dialog.dismiss()
            showPremiumSnackbar(getString(R.string.compression_cancelled))
        }
    }

    private fun performCompression(sourceFiles: List<File>, destDir: File, customFileName: String, format: ArchiveManager.Format, password: String?) {
        val isTv = DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_transfer_progress_tv else R.layout.dialog_transfer_progress
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtProgressTitle)
        val txtCurrentFile = dialogView.findViewById<TextView>(R.id.txtProgressCurrentFile)
        val progressFile = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressFile)
        txtTitle?.setText(R.string.compressing_files)
        txtCurrentFile?.setText(R.string.compressing_2)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val fileName = customFileName
        val extension = format.displayName
        val isDestSaf = destDir is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(destDir.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, destDir.absolutePath)

        var destFile = if (isDestSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafFile(za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getSafChildPath(destDir.absolutePath, "$fileName$extension"))
        } else {
            File(destDir, "$fileName$extension")
        }
        var counter = 1
        if (isDestSaf) {
            while (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(this, destFile.absolutePath)) {
                destFile = za.kilowatch.ultimatefilemanager.storage.SafFile(za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getSafChildPath(destDir.absolutePath, "$fileName ($counter)$extension"))
                counter++
            }
        } else {
            while (destFile.exists()) {
                destFile = File(destDir, "$fileName ($counter)$extension")
                counter++
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ArchiveManager.compress(this@FileBrowserActivity, sourceFiles, destFile, password, format) { progress ->
                    runOnUiThread {
                        progressFile?.isIndeterminate = false
                        progressFile?.progress = progress
                    }
                }
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    fileAdapter.exitSelectionMode()
                    loadDirectory(currentDir)
                    showPremiumSnackbar(getString(R.string.compression_completed_destfilename, destFile.name))
                    syncFolderWithIndex(destDir)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    showPremiumSnackbar(getString(R.string.compression_failed_emessage))
                }
            }
        }
    }

    private fun showVaultPickerForFiles(files: List<File>, isMove: Boolean) {
        val vaultDir = File(filesDir, "vault")
        val entries = mutableListOf<VaultEntry>()
        
        // Read existing vault entries
        if (vaultDir.exists() && vaultDir.isDirectory) {
            vaultDir.listFiles()?.forEach { entryDir ->
                if (entryDir.isDirectory) {
                    readVaultEntry(entryDir)?.let { entries.add(it) }
                }
            }
        }
        
        if (entries.isEmpty()) {
            // No vaults exist - prompt to create one
            val isTv = DeviceUtils.isTvDevice(this)
            val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
            val noVaultView = layoutInflater.inflate(layoutRes, null)
            val imgIcon = noVaultView.findViewById<android.widget.ImageView>(R.id.imgDialogIcon)
            val txtTitle = noVaultView.findViewById<TextView>(R.id.txtDialogTitle)
            val txtMessage = noVaultView.findViewById<TextView>(R.id.txtDialogMessage)
            val btnPositive = noVaultView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogPositive)
            val btnNegative = noVaultView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogNegative)

            imgIcon?.setImageResource(R.drawable.ic_lock)
            txtTitle?.setText(R.string.encrypt_no_vaults)
            txtMessage?.setText(R.string.encrypt_create_first)
            btnPositive?.setText(R.string.encrypt_create_vault)
            btnNegative?.visibility = View.VISIBLE
            btnNegative?.setText(android.R.string.cancel)

            val noVaultDialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setView(noVaultView)
                .create()

            btnPositive?.setOnClickListener {
                noVaultDialog.dismiss()
                val intent = Intent(this, VaultActivity::class.java)
                startActivity(intent)
            }

            btnNegative?.setOnClickListener {
                noVaultDialog.dismiss()
            }

            noVaultDialog.show()
            noVaultDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            return
        }
        
        // Build a premium dark-styled vault picker
        val vaultNames = entries.map { it.displayName }.toTypedArray()
        val title = if (isMove) getString(R.string.encrypt_move_title)
                    else getString(R.string.encrypt_copy_title)

        val bgColor   = getColor(R.color.tv_bg_gradient_end)
        val white     = getColor(R.color.tv_text_primary)
        val black     = getColor(R.color.tv_button_focused_yellow_text)
        val yellow    = getColor(R.color.tv_button_focused_yellow)
        val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
        val glassCsl  = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

        // Yellow selector for ListView rows (handles D-pad focus state)
        val focusedDrawable = android.graphics.drawable.ColorDrawable(yellow)
        val normalDrawable  = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        val rowSelector = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
            addState(intArrayOf(android.R.attr.state_pressed), focusedDrawable)
            addState(intArrayOf(), normalDrawable)
        }

        // Custom adapter: white text, turns black when row is focused/selected
        val adapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, vaultNames.toList()
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<android.widget.TextView>(android.R.id.text1)
                tv.textSize = 17f
                tv.setPadding(48, 36, 48, 36)
                tv.isFocusable = false  // let the ListView row handle focus
                // Use a text color state list so text turns black when row is focused
                val textCsl = android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_focused),
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf()
                    ),
                    intArrayOf(black, black, white)
                )
                tv.setTextColor(textCsl)
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                return view
            }
        }

        // Container: subtitle label + list
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // Subtitle label
        val subtitle = android.widget.TextView(this).apply {
            text = getString(R.string.encrypt_select_vault)
            setTextColor(0xB3FFFFFF.toInt())  // 70% white
            textSize = 13f
            setPadding(48, 16, 48, 8)
        }
        container.addView(subtitle)

        // Divider above list
        val topDivider = android.view.View(this).apply {
            setBackgroundColor(0x1AFFFFFF.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }
        container.addView(topDivider)

        val listView = android.widget.ListView(this).apply {
            this.adapter = adapter
            divider = android.graphics.drawable.ColorDrawable(0x1AFFFFFF.toInt())
            dividerHeight = 1
            setSelector(rowSelector)
            isFocusable = true
            isFocusableInTouchMode = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // Constrain height so dialog doesn't overflow screen
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(listView)

        // Auto-select if only one vault exists
        var selectedIndex = if (entries.size == 1) 0 else -1

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selectedIndex >= 0) {
                    encryptFilesToVault(files, entries[selectedIndex], isMove)
                }
            }
            .create()

        // Row click: highlight selection and track index
        listView.setOnItemClickListener { _, view, which, _ ->
            // Reset previously selected row tint
            for (i in 0 until listView.childCount) {
                listView.getChildAt(i)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            // Highlight selected row with a persistent 20% yellow tint
            view.setBackgroundColor(0x33FBBF24.toInt())
            selectedIndex = which
            // Enable OK button and immediately brighten it to full yellow
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                isEnabled = true
                backgroundTintList = yellowCsl
            }
        }

        dialog.show()

        // Apply dark theme styling after show()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        val titleView = dialog.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.alertTitle
        ) ?: dialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(white)

        // OK button: bright yellow if auto-selected (single vault), dim if waiting for selection
        val okEnabled = selectedIndex >= 0
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            isEnabled = okEnabled
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (okEnabled) yellow else 0x66FBBF24.toInt()
            )
            setTextColor(black)
            if (DeviceUtils.isTvDevice(this@FileBrowserActivity)) {
                setOnFocusChangeListener { _, hasFocus ->
                    backgroundTintList = if (hasFocus) yellowCsl
                        else android.content.res.ColorStateList.valueOf(if (isEnabled) yellow else 0x66FBBF24.toInt())
                    setTextColor(if (hasFocus) black else black)
                }
            }
        }

        // Cancel button: glass-white default, yellow on TV focus
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            backgroundTintList = glassCsl
            setTextColor(white)
            if (DeviceUtils.isTvDevice(this@FileBrowserActivity)) {
                setOnFocusChangeListener { _, hasFocus ->
                    backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                    setTextColor(if (hasFocus) black else white)
                }
            }
        }
    }
    
    /**
     * Encrypts files to the selected vault entry.
     */
    private fun encryptFilesToVault(files: List<File>, entry: VaultEntry, isMove: Boolean) {
        val entryDir = File(filesDir, "vault/${entry.id}")
        
        // Show progress dialog
        val progressView = layoutInflater.inflate(R.layout.dialog_vault_progress, null)
        val txtProgress = progressView.findViewById<TextView>(R.id.txtVaultProgress)
        val progressBar = progressView.findViewById<android.widget.ProgressBar>(R.id.progressVault)
        
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(if (isMove) R.string.encrypt_move_title else R.string.encrypt_copy_title)
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()

        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        val titleView = dialog.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.alertTitle
        ) ?: dialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(getColor(R.color.tv_text_primary))
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    // Filter out system files
                    val filesToEncrypt = files.filter { !isSystemFile(it) }
                    val total = filesToEncrypt.size.coerceAtLeast(1)
                    var count = 0
                    
                    filesToEncrypt.forEach { file ->
                        val relativePath = file.name
                        val encryptedFile = File(entryDir, "$relativePath.enc")
                        encryptedFile.parentFile?.mkdirs()
                        VaultCrypto.encryptFile(file, encryptedFile)
                        
                        count++
                        val percent = ((count.toFloat() / total.toFloat()) * 100).toInt()
                        runOnUiThread {
                            txtProgress.text = getString(R.string.encrypt_progress, count, total)
                            progressBar.progress = percent
                        }
                    }
                    
                    // Update metadata
                    val existingFiles = entry.files.toMutableList()
                    filesToEncrypt.forEach { file ->
                        if (!existingFiles.contains(file.name)) {
                            existingFiles.add(file.name)
                        }
                    }
                    
                    val metadata = org.json.JSONObject().apply {
                        put("id", entry.id)
                        put("displayName", "enc:" + VaultCrypto.encryptString(entry.displayName))
                        put("originalRoot", "enc:" + VaultCrypto.encryptString(entry.originalRoot))
                        put("filesPayload", VaultCrypto.encryptStrings(existingFiles))
                        put("files", org.json.JSONArray(existingFiles.map { "enc:" + VaultCrypto.encryptString(it) }))
                    }
                    val tempMeta = File(entryDir, "metadata.json.tmp")
                    tempMeta.writeText(metadata.toString())
                    val finalMeta = File(entryDir, "metadata.json")
                    finalMeta.delete()
                    tempMeta.renameTo(finalMeta)
                    
                    // If move, delete originals
                    if (isMove) {
                        filesToEncrypt.forEach { file ->
                            if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(file.absolutePath)) {
                                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(file.absolutePath)
                            } else if (file.isDirectory) {
                                file.deleteRecursively()
                            } else {
                                file.delete()
                            }
                        }
                    }
                    
                    true
                } catch (e: Exception) {
                    false
                }
            }
            
            dialog.dismiss()
            fileAdapter.exitSelectionMode()
            loadDirectory(currentDir)
            
            if (success) {
                showPremiumSnackbar(getString(R.string.encrypt_success, files.size))
            } else {
                showPremiumSnackbar(getString(R.string.encrypt_error))
            }
        }
    }
    
    /**
     * Checks if a file is a system file that should not be encrypted.
     */
    private fun isSystemFile(file: File): Boolean {
        val path = file.absolutePath.lowercase()
        
        val systemPaths = listOf(
            "/system/", "/proc/", "/sys/", "/dev/", "/data/system/",
            "/data/dalvik-cache/", "/data/app/"
        )
        
        if (systemPaths.any { path.startsWith(it) }) return true
        
        val systemFilePatterns = listOf(
            ".nomedia", "thumbs.db", "desktop.ini", ".ds_store"
        )
        
        return systemFilePatterns.any { file.name.lowercase() == it.lowercase() }
    }
    
    /**
     * Reads a vault entry from its directory.
     */
    private fun readVaultEntry(dir: File): VaultEntry? {
        val metadataFile = File(dir, "metadata.json")
        val fileToRead = if (metadataFile.exists()) metadataFile else File(dir, "metadata.json.bak")
        if (!fileToRead.exists()) return null
        return try {
            val json = org.json.JSONObject(fileToRead.readText())
            val rawName = json.getString("displayName")
            val displayName = if (rawName.startsWith("enc:")) VaultCrypto.decryptString(rawName.removePrefix("enc:")) else rawName
            val rawRoot = json.optString("originalRoot", "")
            val originalRoot = if (rawRoot.startsWith("enc:")) VaultCrypto.decryptString(rawRoot.removePrefix("enc:")) else rawRoot

            val files: List<String> = if (json.has("filesPayload")) {
                VaultCrypto.decryptStrings(json.getString("filesPayload"))
            } else if (json.has("files")) {
                val filesJson = json.getJSONArray("files")
                val list = ArrayList<String>(filesJson.length())
                for (i in 0 until filesJson.length()) {
                    val rawF = filesJson.getString(i)
                    list.add(if (rawF.startsWith("enc:")) VaultCrypto.decryptString(rawF.removePrefix("enc:")) else rawF)
                }
                list
            } else {
                emptyList()
            }
            VaultEntry(
                id = json.getString("id"),
                displayName = displayName,
                originalRoot = originalRoot,
                files = files
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            layoutEmpty.visibility = View.VISIBLE
            recyclerFiles.visibility = View.GONE
            lottieEmptyFolder?.playAnimation()

            val isProtected = za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isProtectedPath(currentDir.absolutePath)
            val canUseShizuku = isProtected && za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(currentDir.absolutePath)
            val hasSaf = (isProtected || currentDir.absolutePath.startsWith("saf://")) && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, currentDir.absolutePath)

            val layoutProtected = findViewById<View>(R.id.layoutProtectedPrompt)
            val txtEmptyFolder = findViewById<View>(R.id.txtEmptyFolder)
            if (isProtected && !canUseShizuku && !hasSaf) {
                layoutProtected?.visibility = View.VISIBLE
                txtEmptyFolder?.visibility = View.GONE
                findViewById<View>(R.id.btnEnableElevated)?.setOnClickListener {
                    val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
                    val intent = if (isTv) Intent(this, za.kilowatch.ultimatefilemanager.ui.ShizukuTvActivity::class.java)
                                 else Intent(this, za.kilowatch.ultimatefilemanager.ui.ShizukuActivity::class.java)
                    startActivity(intent)
                }
                findViewById<View>(R.id.btnGrantSaf)?.setOnClickListener {
                    launchSafTreePicker(currentDir.absolutePath)
                }
            } else {
                layoutProtected?.visibility = View.GONE
                txtEmptyFolder?.visibility = View.VISIBLE
            }
        } else {
            layoutEmpty.visibility = View.GONE
            recyclerFiles.visibility = View.VISIBLE
            lottieEmptyFolder?.cancelAnimation()
        }
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
    private fun toggleSearch() {
        isSearchVisible = !isSearchVisible
        layoutSearchRow.visibility = if (isSearchVisible) View.VISIBLE else View.GONE
        btnSearchToggle.setImageResource(R.drawable.ic_search)
        if (isTv) {
            btnSearchToggle.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(if (isSearchVisible) R.color.ufm_granted else R.color.ufm_denied)
            )
        }
        
        if (isSearchVisible) {
            edtSearch.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(edtSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            edtSearch.setText("")
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(edtSearch.windowToken, 0)
            loadDirectory(currentDir)
        }
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) {
            loadDirectory(currentDir)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val indexingRepository = UfmApplication.indexingRepository
                val isIndexed = indexingRepository.isStorageFullyIndexed(storageId)

                val files = if (isIndexed) {
                    val results = indexingRepository.searchSmart(
                        query = query,
                        storageId = storageId,
                        folderScope = currentDir.absolutePath
                    )
                    results.map { File(it.path) }.filter { it.exists() }
                } else {
                    val isSaf = currentDir is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(currentDir.absolutePath) ||
                                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@FileBrowserActivity, currentDir.absolutePath)
                    if (isSaf) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.searchSaf(this@FileBrowserActivity, currentDir.absolutePath, query, maxResults = 200)
                    } else {
                        val lowerQuery = query.lowercase()
                        val found = mutableListOf<File>()
                        try {
                            currentDir.walkTopDown()
                                .onEnter { isActive }
                                .filter { it.name.lowercase().contains(lowerQuery) }
                                .take(200)
                                .forEach { found.add(it) }
                        } catch (_: Exception) {}
                        found
                    }
                }
                
                val hiddenPaths = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase.getInstance(applicationContext).hiddenFileDao().getAllPaths().toSet()

                withContext(Dispatchers.Main) {
                    fileAdapter.submitList(
                        newFiles = files,
                        showAllAsIndexed = isIndexed,
                        hiddenPaths = hiddenPaths,
                        searchBasePath = currentDir.absolutePath
                    )
                    updateEmptyState(files.isEmpty())
                }
            } catch (e: Exception) {
                Log.e("FileBrowser", "Search failed: ${e.message}")
            }
        }
    }

    private fun updateBreadcrumbs() {
        val scroll = layoutBreadcrumbsScroll ?: return
        val container = layoutBreadcrumbs ?: return
        
        val enabled = za.kilowatch.ultimatefilemanager.settings.BreadcrumbsPreferenceManager.isEnabled(this)
                && !DeviceUtils.isTvDevice(this)
                && !isCategoryMode
                
        if (!enabled) {
            scroll.visibility = View.GONE
            return
        }
        
        scroll.visibility = View.VISIBLE
        container.removeAllViews()
        
        val isRootSaf = SafTreeManager.isSafPath(rootPath)
        val list = mutableListOf<Pair<String, File?>>()
        list.add(Pair("Home", null))
        list.add(Pair(storageLabel, if (isRootSaf) SafFile(rootPath, true) else File(rootPath)))
        
        if (currentDir.absolutePath.startsWith(rootPath) && currentDir.absolutePath != rootPath) {
            val relativePath = currentDir.absolutePath.substring(rootPath.length).removePrefix("/")
            if (relativePath.isNotEmpty()) {
                val parts = relativePath.split("/")
                var currentAccumulated: File = if (isRootSaf) SafFile(rootPath, true) else File(rootPath)
                for (part in parts) {
                    if (part.isNotEmpty()) {
                        currentAccumulated = if (isRootSaf) SafFile(currentAccumulated.absolutePath, part, true) else File(currentAccumulated, part)
                        list.add(Pair(part, currentAccumulated))
                    }
                }
            }
        }
        
        val inflater = LayoutInflater.from(this)
        for (i in list.indices) {
            val item = list[i]
            
            // Inflate breadcrumb item
            val view = if (item.second == null) {
                // Home icon
                inflater.inflate(R.layout.item_breadcrumb_home, container, false).apply {
                    setOnClickListener {
                        val intent = Intent(this@FileBrowserActivity, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
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
                            saveCurrentFolderScroll()
                            loadDirectory(item.second!!)
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

    private fun showMobileOptionsPopupMenu(anchor: View) {
        val popupView = layoutInflater.inflate(R.layout.popup_header_options_menu, null)
        val popupWindow = android.widget.PopupWindow(
            popupView,
            (200 * resources.displayMetrics.density).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 16f * resources.displayMetrics.density
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            animationStyle = android.R.style.Animation_Dialog
        }

        popupView.findViewById<View>(R.id.menuItemTwinWindow)?.setOnClickListener {
            popupWindow.dismiss()
            val intent = Intent(this, TwinWindowActivity::class.java).apply {
                putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_PATH, rootPath)
                putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_LABEL, storageLabel)
                putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_INITIAL_PATH, currentDir.absolutePath)
            }
            startActivity(intent)
        }

        popupView.findViewById<View>(R.id.menuItemSettings)?.setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(this, za.kilowatch.ultimatefilemanager.settings.SettingsActivity::class.java))
        }

        val xOffset = -(200 * resources.displayMetrics.density - anchor.width).toInt()
        popupWindow.showAsDropDown(anchor, xOffset, (4 * resources.displayMetrics.density).toInt())
    }

    private fun applyCustomToolbarIcons() {
        if (!::btnCopy.isInitialized) return
        IconCustomizationManager.applyToView(this, btnCopy, "toolbar_copy", R.drawable.ic_copy)
        IconCustomizationManager.applyToView(this, btnMove, "toolbar_move", R.drawable.ic_move)
        IconCustomizationManager.applyToView(this, btnRename, "toolbar_rename", R.drawable.ic_edit)
        IconCustomizationManager.applyToView(this, btnShare, "toolbar_share", R.drawable.ic_share)
        IconCustomizationManager.applyToView(this, btnCopyEncrypt, "toolbar_copy_encrypt", R.drawable.ic_copy)
        IconCustomizationManager.applyToView(this, btnMoveEncrypt, "toolbar_move_encrypt", R.drawable.ic_move)
        if (::btnFavorite.isInitialized) {
            IconCustomizationManager.applyToView(this, btnFavorite, "toolbar_favorite", R.drawable.ic_star)
        }
        if (::btnHide.isInitialized) {
            IconCustomizationManager.applyToView(this, btnHide, "toolbar_hide", R.drawable.ic_eye_off)
        }
        if (::btnUnhide.isInitialized) {
            IconCustomizationManager.applyToView(this, btnUnhide, "toolbar_unhide", R.drawable.ic_eye)
        }
        if (btnPin != null) {
            IconCustomizationManager.applyToView(this, btnPin!!, "toolbar_pin", R.drawable.ic_paperclip)
        }
        if (btnUnpin != null) {
            IconCustomizationManager.applyToView(this, btnUnpin!!, "toolbar_unpin", R.drawable.ic_paperclip_off)
        }
        // btnCompress, btnImageCompress, and btnDelete are MaterialButton/View, not ImageView.
        // Custom icons are applied via the ImageView children inside them.
        if (::btnCompress.isInitialized && btnCompress is ImageView) {
            IconCustomizationManager.applyToView(this, btnCompress as ImageView, "toolbar_compress", R.drawable.ic_compress)
        }
        if (::btnImageCompress.isInitialized && btnImageCompress is ImageView) {
            IconCustomizationManager.applyToView(this, btnImageCompress as ImageView, "toolbar_image_compress", R.drawable.ic_compress_image)
        }
        // btnDelete uses setIconResource via MaterialButton — skip for now
    }

    private fun showGoToPathDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            if (::currentDir.isInitialized) {
                setText(currentDir.absolutePath)
            }
            setHint(R.string.go_to_path_hint)
            selectAll()
        }
        val til = com.google.android.material.textfield.TextInputLayout(this).apply {
            addView(input)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (12 * resources.displayMetrics.density).toInt(), pad, 0)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.go_to_path_title)
            .setView(til)
            .setPositiveButton(R.string.go_to_path_action) { _, _ ->
                val inputPath = input.text?.toString()?.trim().orEmpty()
                if (inputPath.isNotEmpty()) {
                    val target = File(inputPath)
                    if (target.exists() && target.isDirectory) {
                        loadDirectory(target)
                    } else if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, inputPath)) {
                        loadDirectory(File(inputPath))
                    } else {
                        showPremiumSnackbar(getString(R.string.go_to_path_invalid))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::keyboardShortcutHandler.isInitialized && keyboardShortcutHandler.handleKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
