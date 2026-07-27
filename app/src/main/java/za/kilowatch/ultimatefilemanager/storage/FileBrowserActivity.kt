package za.kilowatch.ultimatefilemanager.storage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
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
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Displays the contents of a directory.
 * Supports navigating into sub-folders, opening files,
 * multi-select via long-press, batch delete, copy, move,
 * rename, share, sort, and filter.
 */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerFiles: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private var lottieEmptyFolder: com.airbnb.lottie.LottieAnimationView? = null
    private lateinit var layoutSelectionBar: LinearLayout
    private lateinit var txtSelectionCount: TextView
    private lateinit var btnCloseSelection: ImageView
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnDelete: MaterialButton
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
            // Local destination
            val localPath = data.getStringExtra(RESULT_SELECTED_LOCAL_PATH)
            if (localPath != null) {
                onFolderPicked?.invoke(File(localPath))
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

        rootPath = intent.getStringExtra(EXTRA_MOUNT_PATH) ?: internalPath
        if (rootPath.isEmpty() || !File(rootPath).exists()) {
            rootPath = internalPath
            storageLabel = internalLabel
            storageId = "internal"
            storageType = "internal"
        } else {
            storageLabel = intent.getStringExtra(EXTRA_STORAGE_LABEL) ?: getString(R.string.storage)
            storageId = intent.getStringExtra(EXTRA_STORAGE_ID) ?: if (rootPath.contains("emulated")) "internal" else "external"
            storageType = intent.getStringExtra(EXTRA_STORAGE_TYPE) ?: if (rootPath.contains("emulated")) "internal" else "external"
        }
        currentDir = File(rootPath)
        focusPath = intent.getStringExtra(EXTRA_FOCUS_PATH)

        // If an initial subfolder was provided (e.g. from closing twin window), navigate there
        val initialPath = intent.getStringExtra(EXTRA_INITIAL_PATH)
        if (!initialPath.isNullOrEmpty()) {
            val initialFile = File(initialPath)
            if (initialFile.exists() && initialFile.absolutePath.startsWith(rootPath)) {
                currentDir = initialFile
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

    override fun onResume() {
        super.onResume()
        applyLeftHandedFabSettings()
        applyToolbarIconVisibility()
        // Refresh file list so files created/modified in child activities
        // (image viewer, text viewer, etc.) appear immediately on return
        if (::currentDir.isInitialized) {
            loadDirectory(currentDir)
        }
        // Show/hide paste FAB based on clipboard state or picker modes
        updatePasteFab()
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

    private fun showConfirmExtractLocalFolderDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.extract_here)
            .setMessage(getString(R.string.extract_contents_to_path, path))
            .setIcon(R.drawable.ic_folder)
            .setPositiveButton(R.string.extract_here_1) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }



    private fun confirmSmartSortFolder() {
        val path = currentDir.absolutePath
        if (!isHighRiskFolder(path)) {
            returnSmartSortResult(path)
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.smart_sort_warning_title)
            .setMessage(getString(R.string.smart_sort_warning_message, path))
            .setIcon(R.drawable.ic_warning)
            .setPositiveButton(R.string.btn_continue) { _, _ -> returnSmartSortResult(path) }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.compress_here)
            .setMessage(getString(R.string.save_archive_to_path, path))
            .setIcon(R.drawable.ic_compress)
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmImageCompressLocalFolderDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.compress_here)
            .setMessage(getString(R.string.save_archive_to_path, path))
            .setIcon(R.drawable.ic_compress_image)
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmSyncLocalFolderDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_source_folder)
            .setMessage(
                getString(R.string.use_folder_as_sync_source, path) +
                getString(R.string.files_in_this_folder_will_be_backed_up_to_your_network_share)
            )
            .setIcon(R.drawable.ic_sync)
            .setPositiveButton(R.string.btn_continue) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmAdvancedSyncLocalFolderDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_source_folder)
            .setMessage(
                getString(R.string.use_folder_as_sync_source, path) +
                getString(R.string.files_in_this_folder_will_be_backed_up_to_your_network_share)
            )
            .setIcon(R.drawable.ic_sync_advanced)
            .setPositiveButton(R.string.btn_continue) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmAdvancedSyncDestFolderDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_destination_folder)
            .setMessage(getString(R.string.use_folder_as_sync_destination, path))
            .setIcon(R.drawable.ic_sync_advanced)
            .setPositiveButton(R.string.btn_continue) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmLocationPickerLocalFolderDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_folder)
            .setMessage(getString(R.string.use_folder_as_default_location, path))
            .setIcon(R.drawable.ic_folder)
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_URI, "file://$path")
                    putExtra(RESULT_LABEL, path)
                    putExtra(RESULT_TYPE, "LOCAL")
                    putExtra(RESULT_META_ID, null as String?)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.use_this_key_file)
            .setMessage(getString(R.string.use_key_file_confirm, path))
            .setIcon(R.drawable.ic_folder)
            .setPositiveButton(R.string.use_this_key_file) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmCertPickedDialog() {
        val path = selectedKeyFilePath ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_cert_import_title)
            .setMessage(getString(R.string.remote_cert_import_msg) + "\n\n$path")
            .setIcon(R.drawable.ic_lock)
            .setPositiveButton(R.string.remote_use_ca) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmNetworkCacheFolderDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nt_use_this_folder_for_caching)
            .setMessage(getString(R.string.nt_cache_limit_title))
            .setIcon(R.drawable.ic_folder)
            .setPositiveButton(R.string.nt_use_this_folder_for_caching) { _, _ ->
                val cacheDir = java.io.File(path, ".ufm_network_thumbnails")
                cacheDir.mkdirs()
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, cacheDir.absolutePath)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmShareDestDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.use_this_folder)
            .setMessage(getString(R.string.share_receive_confirm, path))
            .setIcon(R.drawable.ic_folder)
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmNotepadFolderDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.use_this_folder)
            .setMessage(getString(R.string.notepad_folder_picker_title))
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmScannerFolderDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scanner_use_this_folder)
            .setMessage(getString(R.string.scanner_folder_picker_title))
            .setPositiveButton(R.string.scanner_use_this_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmAutoBackupFolderDialog() {
        val path = currentDir.absolutePath
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.auto_backup_location_confirm_title)
            .setMessage(R.string.auto_backup_location_confirm_message)
            .setPositiveButton(R.string.auto_backup_select_folder) { _, _ ->
                val result = Intent().apply {
                    putExtra(RESULT_SELECTED_LOCAL_PATH, path)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // â”€â”€ Quick Transfer helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Confirms the chosen folder and immediately triggers the file transfer.
     * The transfer engine reuses [performPaste]: currentDir is temporarily swapped
     * to the destination, paste is invoked, then currentDir is restored.
     */
    private fun showConfirmQuickTransferDialog(isMove: Boolean) {
        val path = currentDir.absolutePath
        // In the picker instance pendingQuickTransferFiles is null â€” fall back to FileClipboard
        val fileCount = pendingQuickTransferFiles?.size ?: FileClipboard.files.size
        val titleRes = if (isMove) R.string.action_move_to else R.string.action_copy_to
        val msgRes   = if (isMove) R.string.quick_transfer_move_confirm else R.string.quick_transfer_copy_confirm
        val posRes   = if (isMove) R.string.quick_transfer_move_here else R.string.quick_transfer_copy_here
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(getString(msgRes, fileCount))
            .setIcon(if (isMove) R.drawable.ic_move else R.drawable.ic_copy)
            .setPositiveButton(posRes) { _, _ ->
                quickTransferDestDir = currentDir
                performPaste()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                .filter { isFileVisible(it, showHidden, hiddenPaths) }

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

    private fun submitAdapterList(action: () -> Unit) {
        val currentPath = currentDir.absolutePath
        val oldPath = lastLoadedPath
        val isNavigatingFolder = oldPath != null && oldPath != currentPath
        lastLoadedPath = currentPath

        if (isNavigatingFolder && ::recyclerFiles.isInitialized && za.kilowatch.ultimatefilemanager.util.AnimationHelper.areFolderTransitionsEnabled(this)) {
            val isForward = currentPath.length > (oldPath?.length ?: 0)
            za.kilowatch.ultimatefilemanager.util.AnimationHelper.animateFolderTransition(recyclerFiles, isForward) {
                action()
            }
        } else {
            action()
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
            .setTitle(R.string.reindexing_title)
            .setView(dialogView)
            .setCancelable(false)
            .create()

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
        btnSearchToggle.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_denied)) // Initial state: red
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
            layoutOptionsRow = findViewById(R.id.layoutOptionsRow)
            
            val prefs = getSharedPreferences("ufm_prefs", MODE_PRIVATE)
            isOptionsVisible = prefs.getBoolean("toolbar_options_visible", false)
            
            layoutOptionsRow?.visibility = if (isOptionsVisible) View.VISIBLE else View.GONE
            btnOptionsToggle?.setImageResource(if (isOptionsVisible) R.drawable.ic_settings else R.drawable.ic_settings_off)

            btnOptionsToggle?.setOnClickListener {
                isOptionsVisible = !isOptionsVisible
                layoutOptionsRow?.visibility = if (isOptionsVisible) View.VISIBLE else View.GONE
                btnOptionsToggle?.setImageResource(if (isOptionsVisible) R.drawable.ic_settings else R.drawable.ic_settings_off)
                prefs.edit().putBoolean("toolbar_options_visible", isOptionsVisible).apply()
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
        
        // Load and apply initial view mode
        val initialMode = ViewModeManager.load(this)
        applyViewMode(initialMode)

        // Keyfile / cert picker mode: show FAB only after a file is tapped
        if (isKeyfilePickerMode || isCertPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnCreateNew)?.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnViewToggle)?.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnSort)?.visibility = View.GONE
            // Don't return — keeps paste FAB wiring but onResume overrides it
        }

        // Smart Sort picker mode: show FAB with sort icon
        if (isSmartSortPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.setText(R.string.smart_sort_here)
            fabPaste.setIconResource(R.drawable.ic_sort)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { confirmSmartSortFolder() }
            findViewById<android.widget.ImageView>(R.id.btnCreateNew)?.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnViewToggle)?.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnSort)?.visibility = View.GONE
            return
        }

        // Hide editing controls in file picker mode
        if (isPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.visibility = View.GONE
            // Hide new folder button
            findViewById<android.widget.ImageView>(R.id.btnCreateNew)?.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnViewToggle)?.visibility = View.GONE
            findViewById<android.widget.ImageView>(R.id.btnSort)?.visibility = View.GONE
            return // Skip wiring selection/paste buttons
        }

        // Extract dest picker mode: show Use This Folder FAB (folder icon)
        if (isExtractDestPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.setText(R.string.extract_here_1)
            fabPaste.setIconResource(R.drawable.ic_folder)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmExtractLocalFolderDialog() }
            return
        }

        // Compress dest picker mode: show New Folder + Use This Folder FAB (compress icon)
        if (isCompressDestPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_compress)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmCompressLocalFolderDialog() }
            return
        }

        // Image Compress dest picker mode
        if (isImageCompressDestPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.setText(R.string.use_this_folder_image)
            fabPaste.setIconResource(R.drawable.ic_compress_image)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener { showConfirmImageCompressLocalFolderDialog() }
            return
        }

        // Sync folder picker mode: show New Folder + Use This Folder FAB
        if (isSyncFolderPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            // Keep new folder button visible
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_sync)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener {
                showConfirmSyncLocalFolderDialog()
            }
            return
        }

        // Advanced Sync folder picker mode: show New Folder + Use This Folder FAB
        if (isAdvancedSyncFolderPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_sync_advanced)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener {
                showConfirmAdvancedSyncLocalFolderDialog()
            }
            return
        }

        // Advanced Sync destination picker mode
        if (isAdvancedSyncDestPickerMode) {
            layoutSelectionBar.visibility = View.GONE
            fabPaste.setText(R.string.use_this_folder)
            fabPaste.setIconResource(R.drawable.ic_sync_advanced)
            fabPaste.visibility = View.VISIBLE
            fabPaste.setOnClickListener {
                showConfirmAdvancedSyncDestFolderDialog()
            }
            return
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
            if (selected.isNotEmpty()) {
                if (za.kilowatch.ultimatefilemanager.settings.QuickTransferPreferenceManager.isEnabled(this)) {
                    launchQuickTransferPicker(selected, isMove = false)
                } else {
                    FileClipboard.set(selected, FileClipboard.Operation.COPY)
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
                    FileClipboard.set(selected, FileClipboard.Operation.MOVE)
                    fileAdapter.exitSelectionMode()
                    showPremiumSnackbar(getString(R.string.clipboard_cut, selected.size))
                    updatePasteFab()
                }
            }
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
            if (selected.size == 1 && !selected.first().isDirectory) {
                val file = selected.first()
                val sheet = FilePropertiesBottomSheet.newInstance(
                    filePath = file.absolutePath,
                    isDirectory = false,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    isNetwork = false
                )
                sheet.show(supportFragmentManager, FilePropertiesBottomSheet.TAG)
            } else if (selected.size > 1 && selected.all { !it.isDirectory }) {
                val filePaths = selected.map { it.absolutePath }
                FileTagsManager.showMultiFileTagDialog(this, filePaths) {
                    fileAdapter.exitSelectionMode()
                    loadDirectory(currentDir)
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

            // 1. Copy
            if (pm.isIconEnabled(this, pm.KEY_COPY)) {
                list.add(FileToolsBottomSheet.ActionItem("copy", getString(R.string.action_copy), R.drawable.ic_copy, "toolbar_copy") {
                    if (za.kilowatch.ultimatefilemanager.settings.QuickTransferPreferenceManager.isEnabled(this)) {
                        launchQuickTransferPicker(selected, isMove = false)
                    } else {
                        FileClipboard.set(selected, FileClipboard.Operation.COPY)
                        fileAdapter.exitSelectionMode()
                        showPremiumSnackbar(getString(R.string.clipboard_copied, selected.size))
                        updatePasteFab()
                    }
                })
            }

            // 2. Move (Cut)
            if (pm.isIconEnabled(this, pm.KEY_MOVE)) {
                list.add(FileToolsBottomSheet.ActionItem("move", getString(R.string.action_move), R.drawable.ic_move, "toolbar_move") {
                    if (za.kilowatch.ultimatefilemanager.settings.QuickTransferPreferenceManager.isEnabled(this)) {
                        launchQuickTransferPicker(selected, isMove = true)
                    } else {
                        FileClipboard.set(selected, FileClipboard.Operation.MOVE)
                        fileAdapter.exitSelectionMode()
                        showPremiumSnackbar(getString(R.string.clipboard_cut, selected.size))
                        updatePasteFab()
                    }
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
            if (count == 1 && !selected.first().isDirectory) {
                val file = selected.first()
                list.add(FileToolsBottomSheet.ActionItem("properties", getString(R.string.action_properties), R.drawable.ic_about, "toolbar_properties") {
                    val sheet = FilePropertiesBottomSheet.newInstance(
                        filePath = file.absolutePath,
                        isDirectory = false,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        isNetwork = false
                    )
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
        sheet.currentShowHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
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
        sheet.currentViewMode = activeState?.viewMode
        sheet.currentIsRecursive = activeState?.isRecursive ?: false

        sheet.onApply = { mode, order, filter, showHidden, groupByDate, tags, scope, selectedViewMode, isRecursive ->
            sortMode = mode
            sortOrder = order
            filterType = filter
            activeTagsFilter = tags
            za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled = showHidden

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
        if (showSelection) {
            val showActions = count > 0
            layoutSelectionBar.visibility = View.VISIBLE
            txtSelectionCount.text = if (count == 0) getString(R.string.selection_prompt_select_item) else getString(R.string.selection_count, count)
            
            val isTv = DeviceUtils.isTvDevice(this)
            val row2 = btnCopy.parent.parent as? View
            val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
            val hasHidden = fileAdapter.hasAnySelectedHidden()
            val hasVisible = fileAdapter.hasAnySelectedVisible()
            val hasProtected = fileAdapter.hasAnySelectedProtected(this)
            val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(this)
            val hasPinned = fileAdapter.hasAnySelectedPinned(this)
            val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(this)

            if (!isTv) {
                row2?.visibility = View.GONE
                btnSelectAll.visibility = if (pm.isIconEnabled(this, pm.KEY_SELECT_ALL)) View.VISIBLE else View.GONE
                btnDelete.visibility = View.GONE
                btnCompress.visibility = View.GONE
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
            }


            val selectedFiles = fileAdapter.getSelectedFiles()
            val isSingleFile = selectedFiles.size == 1 && !selectedFiles.first().isDirectory
            
            val prefs = getSharedPreferences("ufm_prefs", MODE_PRIVATE)
            val isMultiTaggingEnabled = prefs.getBoolean("pref_multi_file_tagging", false)
            val isMultiFileOnly = selectedFiles.size > 1 && selectedFiles.all { !it.isDirectory }
            
            fabProperties?.visibility = View.GONE
            updatePasteFab()

            if (fileAdapter.isAllSelected()) {
                btnSelectAll.text = getString(R.string.action_deselect_all)
            } else {
                btnSelectAll.text = getString(R.string.action_select_all)
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

        if (recycleEnabled) {
            MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(getString(R.string.move_to_bin))
                .setMessage(getString(R.string.recycle_bin_move_confirm, selected.size))
                .setIcon(R.drawable.ic_delete)
                .setNegativeButton(getString(R.string.delete_cancel), null)
                .setPositiveButton(getString(R.string.move_to_bin)) { _, _ ->
                    performDelete(selected)
                }
                .show()
        } else {
            val folders = selected.count { it.isDirectory }
            val files = selected.count { it.isFile }

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
                    performDelete(selected)
                }
                .show()
        }
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
                        val success = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(file.absolutePath)) {
                            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(file.absolutePath)
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
        val editText = EditText(this).apply {
            setText(file.name)
            selectAll()
            setPadding(64, 32, 64, 32)
        }

        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.rename_title))
            .setView(editText)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .setPositiveButton(getString(R.string.rename_confirm)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    val newFile = File(file.parent, newName)
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (file.renameTo(newFile)) {
                            FileTagsManager.onPathMoved(this@FileBrowserActivity, file.absolutePath, newFile.absolutePath)
                            // Sync the database index immediately after rename
                            syncFolderWithIndex(currentDir)
                            
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                loadDirectory(currentDir)
                                showPremiumSnackbar(getString(R.string.rename_success))
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                showPremiumSnackbar(getString(R.string.rename_error))
                            }
                        }
                    }
                }
            }
            .show()
    }
    
    private fun showFavoriteDialog(file: File) {
        val isOnTv = DeviceUtils.isTvDevice(this)
        
        // Define colors
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
                        path = file.absolutePath,
                        label = name,
                        isFolder = file.isDirectory,
                        isNetwork = false
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

    /**
     * Shows a dialog with two choices: "New Folder" and "New Text File".
     * Mobile uses a MaterialAlertDialog; TV uses focusable card rows.
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

        // Rows built but not yet wired — wiring happens after dialog.show()
        val rowFolder = createMenuRow(R.drawable.ic_folder, getString(R.string.new_menu_new_folder), textPrimary, textSecondary)
        container.addView(rowFolder)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = 8; bottomMargin = 8
            }
            setBackgroundColor(0x33FFFFFF.toInt())
        }
        container.addView(divider)

        val rowFile = createMenuRow(R.drawable.ic_file_text, getString(R.string.new_menu_new_file), textPrimary, textSecondary)
        container.addView(rowFile)

        val dialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.create_new_title))
            .setView(container)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .show()

        // Wire row clicks AFTER the dialog is shown so we have a reference to dismiss it
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
            val titleView = dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)
            titleView?.setTextColor(textPrimary)

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
    }

    /** Builds a single option row for the [showCreateNewMenu] dialog. */
    private fun createMenuRow(iconRes: Int, label: String, textPrimary: Int, textSecondary: Int): LinearLayout {
        val isOnTv = DeviceUtils.isTvDevice(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            isClickable = true
            isFocusable = true
            // Ripple effect handled by click listener
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(40, 40).apply { marginEnd = 16 }
            if (isOnTv) {
                imageTintList = android.content.res.ColorStateList.valueOf(textPrimary)
            }
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

    /**
     * Shows a dialog to name and create a new .txt file in [currentDir].
     * On success, opens the text viewer in edit mode.
     */
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

        val dialogTheme = com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog

        MaterialAlertDialogBuilder(this, dialogTheme)
            .setTitle(getString(R.string.new_file_title))
            .setIcon(R.drawable.ic_create_new)
            .setView(container)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .setPositiveButton(getString(R.string.new_file_create)) { _, _ ->
                val name = editText.text.toString().trim()
                when {
                    name.isEmpty() -> showPremiumSnackbar(getString(R.string.new_file_empty))
                    else -> {
                        createTextFile(name)
                    }
                }
            }
            .show()
            .also { dialog ->
                val titleColor = if (isOnTv) getColor(R.color.tv_text_primary) else getColor(R.color.ufm_text_primary)
                val titleView = dialog.findViewById<TextView>(
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

    /**
     * Creates the .txt file with auto-rename on collision,
     * indexes it, reloads the directory, and opens the text viewer.
     */
    private fun createTextFile(baseName: String) {
        var targetFile = File(currentDir, baseName)
        // Auto-rename if file already exists
        if (targetFile.exists()) {
            val nameWithoutExt = targetFile.nameWithoutExtension
            val ext = targetFile.extension
            var counter = 2
            while (targetFile.exists()) {
                targetFile = File(currentDir, "$nameWithoutExt ($counter).$ext")
                counter++
            }
        }
        val fileToCreate = targetFile

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val created = fileToCreate.createNewFile()
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
        
        // Define colors
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

        val dialogTheme = com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog

        MaterialAlertDialogBuilder(this, dialogTheme)
            .setTitle(getString(R.string.new_folder_title))
            .setIcon(R.drawable.ic_folder)
            .setView(container)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .setPositiveButton(getString(R.string.new_folder_create)) { _, _ ->
                val name = editText.text.toString().trim()
                when {
                    name.isEmpty() -> showPremiumSnackbar(getString(R.string.new_folder_empty))
                    else -> {
                        val newDir = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(currentDir.absolutePath)) {
                            za.kilowatch.ultimatefilemanager.storage.ShizukuFile(currentDir.absolutePath, name, true)
                        } else {
                            File(currentDir, name)
                        }
                        when {
                            newDir.exists() -> showPremiumSnackbar(getString(R.string.new_folder_exists))
                            newDir.mkdirs() -> {
                                // Index the new folder immediately so the database is up to date.
                                // Resolve the correct storage volume from the actual path so this
                                // works for both internal storage and SD cards.
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
            .show()
            .also { dialog ->
                // Fix: Apply title color dynamically for both Mobile and TV
                val titleColor = if (isOnTv) getColor(R.color.tv_text_primary) else getColor(R.color.ufm_text_primary)
                val titleView = dialog.findViewById<android.widget.TextView>(
                    com.google.android.material.R.id.alertTitle
                ) ?: dialog.findViewById(
                    resources.getIdentifier("alertTitle", "id", "android")
                )
                titleView?.setTextColor(titleColor)

                if (isOnTv) {
                    // Dark background for the dialog window itself
                    dialog.window?.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(getColor(R.color.tv_bg_gradient_end))
                    )

                    val white = getColor(R.color.tv_text_primary)
                    val black = getColor(R.color.tv_button_focused_yellow_text)
                    val yellow = getColor(R.color.tv_button_focused_yellow)
                    val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
                    val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

                    // Positive button: yellow bg + black text, focus-aware
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                        backgroundTintList = yellowCsl
                        setTextColor(black)
                    }
                    // Negative button: transparent + white text, focus turns yellow
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
        val dialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
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
        val dialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
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
    }

    private fun updatePasteFab() {
        if (isExtractDestPickerMode || isCompressDestPickerMode || isImageCompressDestPickerMode ||
            isSyncFolderPickerMode || isAdvancedSyncFolderPickerMode || isAdvancedSyncDestPickerMode ||
            isLocationPickerMode || isNetworkCachePickerMode || isQuickTransferPickerMode ||
            isShareDestPickerMode || isNotepadFolderPicker || isScannerFolderPicker ||
            isAutoBackupFolderPicker || isKeyfilePickerMode || isCertPickerMode ||
            isSupportAttachmentPicker || isSmartSortPickerMode) {
            applyPickerFabState()
            return
        }

        val hasLocal = FileClipboard.hasItems()
        val hasNet = za.kilowatch.ultimatefilemanager.network.NetworkClipboard.hasItems()
        val total = (if (hasLocal) FileClipboard.files.size else 0) + (if (hasNet) za.kilowatch.ultimatefilemanager.network.NetworkClipboard.files.size else 0)

        if (total > 0) {
            val label = "${getString(R.string.action_paste)} ($total)"
            fabPaste.text = label
            fabPaste.visibility = View.VISIBLE
        } else {
            fabPaste.visibility = View.GONE
        }
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
    }

    private fun showClipboardSheet() {
        val isOnTv = DeviceUtils.isTvDevice(this)

        data class ClipEntry(val name: String, val isMove: Boolean, val isNetwork: Boolean,
                             val netFile: za.kilowatch.ultimatefilemanager.network.NetworkFile? = null,
                             val localFile: java.io.File? = null)

        fun buildEntries(): MutableList<ClipEntry> {
            val list = mutableListOf<ClipEntry>()
            for (e in za.kilowatch.ultimatefilemanager.network.NetworkClipboard.entries)
                list.add(ClipEntry(e.file.name, e.operation == za.kilowatch.ultimatefilemanager.network.NetworkClipboard.Operation.MOVE, true, netFile = e.file))
            for (e in FileClipboard.entries)
                list.add(ClipEntry(e.file.name, e.operation == FileClipboard.Operation.MOVE, false, localFile = e.file))
            return list
        }

        // Start with an empty list — the dialog opens immediately while entries load in the background
        var entries = mutableListOf<ClipEntry>()
        val colorCopy = getColor(R.color.ufm_primary)
        val colorCut = getColor(R.color.ufm_denied)

        // Choose layout & item based on TV vs mobile
        val layoutRes = if (isOnTv) R.layout.dialog_clipboard_tv else R.layout.bottom_sheet_clipboard
        val itemLayoutRes = if (isOnTv) R.layout.item_clipboard_entry_tv else R.layout.item_clipboard_entry
        val contentView = layoutInflater.inflate(layoutRes, null)

        val recycler = contentView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerClipboard)
        val btnPasteHere = contentView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPasteHere)
        val btnClearAll = contentView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearClipboard)
        val txtTitle = contentView.findViewById<android.widget.TextView>(R.id.txtClipboardTitle)

        // Title always reflects the real total from the singletons, not the loaded list size
        fun realTotal() = (if (FileClipboard.hasItems()) FileClipboard.files.size else 0) +
                (if (za.kilowatch.ultimatefilemanager.network.NetworkClipboard.hasItems()) za.kilowatch.ultimatefilemanager.network.NetworkClipboard.files.size else 0)

        fun updateTitle() {
            val n = realTotal()
            txtTitle.text = if (n == 1) getString(R.string.clipboard_1_file) else getString(R.string.clipboard_total_files, n)
        }
        updateTitle()

        // Create the dialog — AlertDialog on TV (centered), BottomSheetDialog on mobile
        val dialog: android.app.Dialog = if (isOnTv) {
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
                if (isOnTv) {
                    val yellowTint = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                    val redTint = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_denied))
                    btnRemove.setOnFocusChangeListener { _, hasFocus ->
                        btnRemove.imageTintList = if (hasFocus) yellowTint else redTint
                    }
                }

                btnRemove.setOnClickListener {
                    if (entry.isNetwork && entry.netFile != null) za.kilowatch.ultimatefilemanager.network.NetworkClipboard.remove(entry.netFile)
                    else if (!entry.isNetwork && entry.localFile != null) FileClipboard.remove(entry.localFile)
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
            za.kilowatch.ultimatefilemanager.network.NetworkClipboard.clear()
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

            btnClearAll.isFocusable = true
            btnClearAll.isFocusableInTouchMode = true
            btnClearAll.setOnFocusChangeListener { _, hasFocus ->
                btnClearAll.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnClearAll.setTextColor(if (hasFocus) blackText else getColor(R.color.ufm_denied))
            }
        }

        dialog.show()

        // TV: set dialog width for TV screens
        if (isOnTv) {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.5).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun performPaste() {
        val hasLocal = FileClipboard.hasItems()
        val hasNet = za.kilowatch.ultimatefilemanager.network.NetworkClipboard.hasItems()
        if (!hasLocal && !hasNet) return



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
            .setNegativeButton(R.string.cancel, null)  // listener set after show()
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            isCancelled = true
            transferJob?.cancel()
            // Close the raw TCP connection directly — this immediately kills the socket and
            // aborts the blocking SMB write with no 15-second timeout wait.
            runCatching { currentTransferConnection?.close() }
            currentTransferConnection = null
            currentTransferStreams?.let { (inp, out) ->
                runCatching { out?.close() }
                runCatching { inp?.close() }
                currentTransferStreams = null
            }
            // Clean up the incomplete destination file
            currentTransferDestFile?.let { f ->
                try {
                    if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(f.absolutePath)) {
                        za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(f.absolutePath)
                    } else if (f.isDirectory) {
                        f.deleteRecursively()
                    } else {
                        f.delete()
                    }
                } catch (_: Exception) {}
                currentTransferDestFile = null
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
                    } catch (_: Exception) { /* UI update failed during lifecycle transition — ignore */ }
                }
            } catch (_: Exception) { /* Activity might be finishing — ignore */ }
        }

        // Acquire WakeLock + WifiLock to keep CPU and Wi-Fi alive during screen-off
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "UFM:FileCopy")
        val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val wifiLockMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else
            @Suppress("DEPRECATION") android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        val wifiLock = wm.createWifiLock(wifiLockMode, "UFM:FileCopy")
        wakeLock.acquire(60 * 60 * 1000L) // 1 hour max
        wifiLock.acquire()

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
            // Pre-count total files (expand directories to get accurate count)
            var totalFiles = 0
            if (hasLocal) {
                for (e in FileClipboard.entries) {
                    if (e.file.isDirectory) totalFiles += za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.countLocalFiles(e.file)
                    else totalFiles++
                }
            }
            if (hasNet) {
                for (source in za.kilowatch.ultimatefilemanager.network.NetworkClipboard.files) {
                    if (source.isDirectory) {
                        // We don't pre-count network dirs to avoid extra network calls; use entry count
                        totalFiles++
                    } else totalFiles++
                }
            }
            var fileIndex = 0

            // ── Local-to-local paste ──
            if (hasLocal) {
                // Capture the Quick Transfer destination (if set) as an immutable local val.
                // This is essential: performPaste() is async, and handleQuickTransferResult
                // never modifies currentDir anymore, so quickTransferDestDir is the only
                // correct way to pass the chosen destination into this coroutine.
                val effectiveDestDir = quickTransferDestDir ?: currentDir
                val sources = FileClipboard.files
                val operation = FileClipboard.operation
                val applyToAllRef = booleanArrayOf(false)
                var globalAction: za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction? = null

                suspend fun processLocalItem(source: java.io.File, destBase: java.io.File) {
                        if (source.isDirectory) {
                            try {
                                if (!destBase.exists()) destBase.mkdirs()
                                // Index the new folder immediately
                                if (!UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                    pendingIndices.add(metadataExtractor.extractMetadata(destBase, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                                    if (pendingIndices.size >= 50) flushIndices()
                                }
                            } catch (_: Exception) {}
                        
                        val children = source.listFiles()
                        if (children != null) {
                            for (child in children) { 
                                try {
                                    processLocalItem(child, java.io.File(destBase, child.name)) 
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.util.Log.e("PasteFeature", "Error processing local child ${child.name}: ${e.message}")
                                    failCount++
                                }
                            }
                        }
                        if (operation == za.kilowatch.ultimatefilemanager.storage.FileClipboard.Operation.MOVE) {
                            try { 
                                if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(source.absolutePath)) {
                                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(source.absolutePath)
                                } else {
                                    source.delete() 
                                }
                                UfmApplication.indexingRepository.deleteTreeFromIndex(source.absolutePath)
                            } catch (_: Exception) {}
                            FileTagsManager.onPathMoved(this@FileBrowserActivity, source.absolutePath, destBase.absolutePath)
                        } else {
                            FileTagsManager.onPathCopied(this@FileBrowserActivity, source.absolutePath, destBase.absolutePath)
                        }
                    } else {
                        fileIndex++
                        val hasConflict = destBase.exists()
                        val resolvedAction = if (hasConflict) {
                            globalAction ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                    this@FileBrowserActivity, source.name, false, destBase.length(), applyToAllRef
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

                        val sourceSize = source.length()
                        updateProgress(source.name, 0, sourceSize, fileIndex, totalFiles)
                        // Resolve the final destination before copying. For KEEP_BOTH this
                        // produces a unique name (e.g. "photo (1).jpg") so copyLocalToLocalAtomic
                        // never receives a same-as-source path and the self-copy guard cannot
                        // silently swallow the operation.
                        val finalDest = if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH)
                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueLocalFile(destBase.parentFile!!, destBase.name)
                        else destBase
                        val writtenDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.copyLocalToLocalAtomic(source, finalDest, resolvedAction) { c, t ->
                            updateProgress(source.name, c, t, fileIndex, totalFiles)
                        }

                        // Index incrementally
                        if (!UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                            pendingIndices.add(metadataExtractor.extractMetadata(writtenDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                            if (pendingIndices.size >= 50) flushIndices()
                        }

                        if (operation == za.kilowatch.ultimatefilemanager.storage.FileClipboard.Operation.MOVE) {
                            // Zero-byte guard: only delete source if destination has data
                            if (za.kilowatch.ultimatefilemanager.util.FileTransferGuard.requireSourceSafeToDelete(
                                    writtenDest.length(), source.length(), source.name)) {
                                if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(source.absolutePath)) {
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


                    // Process items with per-file error isolation
                    for (entry in FileClipboard.entries) {
                        coroutineContext.ensureActive()
                        try {
                            // Note: processLocalItem currently uses global 'operation' for simplicity, 
                            // but we could pass entry.operation here if needed for mixed batches.
                            processLocalItem(entry.file, File(effectiveDestDir, entry.file.name))
                        } catch (e: CancellationException) {
                            throw e 
                        } catch (e: Exception) {
                            failCount++
                        }
                    }
                    flushIndices()
                    FileClipboard.clear()
                    quickTransferDestDir = null   // consumed; clear so normal paste is unaffected
                }

            // ── Network-to-local paste ──
            if (hasNet) {
                val sources = za.kilowatch.ultimatefilemanager.network.NetworkClipboard.files
                val operation = za.kilowatch.ultimatefilemanager.network.NetworkClipboard.operation
                val sourceShareId = za.kilowatch.ultimatefilemanager.network.NetworkClipboard.sourceShareId
                var share = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(this@FileBrowserActivity).getById(sourceShareId)
                // Server-mode shares need remotePath from the clipboard entry
                val sourceRemotePath = za.kilowatch.ultimatefilemanager.network.NetworkClipboard.sourceRemotePath
                if (share?.isServerMode == true && sourceRemotePath.isNotEmpty()) {
                    share = share.copy(remotePath = sourceRemotePath)
                }

                if (share == null) {
                    val pairedDevice = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(this@FileBrowserActivity).getPairedDevice(sourceShareId)
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
                    val onlineStorage = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(this@FileBrowserActivity).getById(sourceShareId)
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
                    val applyToAllRef = booleanArrayOf(false)
                    var globalAction: za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction? = null

                    suspend fun processNetItem(source: za.kilowatch.ultimatefilemanager.network.NetworkFile, destBase: java.io.File) {
                        if (source.isDirectory) {
                            try {
                                if (!destBase.exists()) destBase.mkdirs()
                                // Index the new folder immediately
                                if (!UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                    pendingIndices.add(metadataExtractor.extractMetadata(destBase, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
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
                                        processNetItem(child, java.io.File(destBase, child.name))
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (isCancelled) throw CancellationException()
                                        android.util.Log.e("PasteFeature", "Error processing net child ${child.name}: ${e.message}")
                                        failCount++
                                    }
                                }
                            if (operation == za.kilowatch.ultimatefilemanager.network.NetworkClipboard.Operation.MOVE) {
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
                                FileTagsManager.onPathMoved(this@FileBrowserActivity, source.path, destBase.absolutePath)
                            } else {
                                FileTagsManager.onPathCopied(this@FileBrowserActivity, source.path, destBase.absolutePath)
                            }
                        } else {
                            fileIndex++
                            val hasConflict = destBase.exists()
                            val resolvedAction = if (hasConflict) {
                                globalAction ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                        this@FileBrowserActivity, source.name, false, destBase.length(), applyToAllRef
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
                            
                            // Index incrementally
                            if (!UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                pendingIndices.add(metadataExtractor.extractMetadata(writtenDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                                if (pendingIndices.size >= 50) flushIndices()
                            }

                            if (operation == za.kilowatch.ultimatefilemanager.network.NetworkClipboard.Operation.MOVE) {
                                // Zero-byte guard: only delete network source if local destination has data
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

                    // Process items with per-file error isolation
                    val sources = za.kilowatch.ultimatefilemanager.network.NetworkClipboard.files
                    for (source in sources) {
                        try {
                            processNetItem(source, java.io.File(currentDir, source.name))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw CancellationException() // Bubbles to outer finally for cleanup
                        } catch (e: Exception) {
                            if (isCancelled) throw CancellationException()
                            failCount++
                        }
                    }
                    za.kilowatch.ultimatefilemanager.network.NetworkClipboard.clear()
                }
            } // closes if (hasNet)

            withContext(Dispatchers.Main) {
                isTransferring = false
                dialog.dismiss()
                flushIndices() // Final flush for network items if any
                
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
                
                if (failCount == 0 && successCount > 0) showPremiumSnackbar(getString(R.string.paste_success, successCount))
                else if (failCount > 0) showPremiumSnackbar(getString(R.string.paste_error))
            }
            } finally {
                isTransferring = false
                za.kilowatch.ultimatefilemanager.util.TransferService.stop(this@FileBrowserActivity)
                if (wakeLock.isHeld) wakeLock.release()
                if (wifiLock.isHeld) wifiLock.release()
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
            val actualFiles: List<File> = directory.listFiles()?.toList() ?: emptyList<File>()
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

    private fun loadDirectory(directory: File) {
        if (isTransferring) return   // Don't refresh while a copy/move is in progress

        // Clear file selection highlight when navigating directories
        if (isSupportAttachmentPicker) {
            selectedKeyFilePath = null
            fileAdapter.focusedPath = null
        }

        // Load folder-specific sort settings (or fall back to global) on IO thread
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val state = SortFilterPreferenceManager.loadForPath(this@FileBrowserActivity, directory.absolutePath)
                ?: SortFilterPreferenceManager.loadGlobal(this@FileBrowserActivity)
            val hasFolderOverride = SortFilterPreferenceManager.hasFolderOverride(this@FileBrowserActivity, directory.absolutePath)
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

        currentDir = directory
        updateBreadcrumbs()

        // Exit selection mode when navigating
        if (fileAdapter.isSelectionMode) {
            fileAdapter.exitSelectionMode()
        }

        // Update toolbar (mobile) or TV header views
        val title = if (isCategoryMode) (categoryName ?: storageLabel) else {
             if (directory.absolutePath == rootPath) storageLabel else directory.name
        }
        toolbar.title = title
        toolbar.subtitle = if (isCategoryMode) getString(R.string.files_on_storagelabel) else directory.absolutePath
        // TV header views (null-safe: only present in TV layout)
        findViewById<android.widget.TextView>(R.id.txtTvTitle)?.text = title
        findViewById<android.widget.TextView>(R.id.txtTvSubtitle)?.text = if (isCategoryMode) getString(R.string.files_on_storagelabel, storageLabel) else directory.absolutePath

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
                        val files = currentDir.walkTopDown()
                            .filter { coroutineContext.ensureActive(); true }
                            .filter { it.isFile && SortFilterSheet.matchesFilter(it, filterType) }
                            .filter { isFileVisible(it, showHidden, hiddenPaths) }
                            .sortedByDescending { it.lastModified() }
                            .toList()
                        withContext(Dispatchers.Main) {
                            fileAdapter.submitList(files, showAllAsIndexed = false, hiddenPaths = hiddenPaths)
                            updateEmptyState(files.isEmpty())
                            applyFileFocus()
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
                
                val rawFiles = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(directory.absolutePath)) {
                    coroutineContext.ensureActive()
                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.listFiles(directory.absolutePath)
                } else {
                    coroutineContext.ensureActive()
                    directory.listFiles()?.toList() ?: emptyList()
                }
                
                val visibleFiles = rawFiles.filter { isFileVisible(it, showHidden, hiddenPaths) }

                val indexedPaths = try { dao.getIndexedPathsInFolder(directory.absolutePath).toSet() } catch (e: Exception) { emptySet<String>() }
                val sorted = sortAndFilterFiles(visibleFiles)
                withContext(Dispatchers.Main) {
                    fileAdapter.submitList(sorted, indexedPaths = indexedPaths, hiddenPaths = hiddenPaths)
                    updateEmptyState(sorted.isEmpty())
                    applyFileFocus()
                }
            }
        } else {
            folderFlowJob = lifecycleScope.launch {
                try {
                    val db = UfmIndexingDatabase.getInstance(applicationContext)
                    val dao = db.fileIndexDao()
                    dao.getFilesInFolderFlow(directory.absolutePath).collectLatest { fileIndices ->
                        withContext(Dispatchers.IO) {
                            val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
                            val hiddenPaths = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase.getInstance(applicationContext).hiddenFileDao().getAllPaths().toSet()

                            if (fileIndices.isEmpty()) {
                                // Fallback to filesystem if DB has no entries yet
                                val rawFiles = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(directory.absolutePath)) {
                                    coroutineContext.ensureActive()
                                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.listFiles(directory.absolutePath)
                                } else {
                                    coroutineContext.ensureActive()
                                    directory.listFiles()?.toList() ?: emptyList()
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
                                val files = fileIndices.map { File(it.path) }.filter { isFileVisible(it, showHidden, hiddenPaths) }
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
            SortFilterSheet.SortMode.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { f: File -> f.name }
            SortFilterSheet.SortMode.SIZE -> compareBy { f: File -> if (f.isDirectory) 0L else f.length() }
            SortFilterSheet.SortMode.DATE -> compareBy { f: File -> f.lastModified() }
            SortFilterSheet.SortMode.TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { f: File -> f.extension }
        }
        val orderedComparator = if (sortOrder == SortFilterSheet.SortOrder.DESC) secondaryComparator.reversed() else secondaryComparator
        
        val customComparator = Comparator<File> { f1, f2 ->
            val p1 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(applicationContext, f1.absolutePath)
            val p2 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(applicationContext, f2.absolutePath)
            if (p1 && p2) {
                f1.name.compareTo(f2.name, ignoreCase = true)
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
        // Try built-in viewer first (with optional shared element)
        if (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.openFile(this, file, transitionView)) return

        // Fall back to external app
        try {
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
    internal fun showPremiumSnackbar(message: String) {
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
        val bgColor   = getColor(R.color.tv_bg_gradient_end)
        val white     = getColor(R.color.tv_text_primary)
        val black     = getColor(R.color.tv_button_focused_yellow_text)
        val yellow    = getColor(R.color.tv_button_focused_yellow)
        val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
        val glassCsl  = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

        val folderNames = folders.joinToString(", ") { it.name }
        val message = getString(R.string.vault_folder_encrypt_warning, folderNames)

        val dialog = MaterialAlertDialogBuilder(this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.vault_folder_encrypt_title))
            .setMessage(message)
            .setIcon(R.drawable.ic_lock)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.vault_folder_encrypt_confirm)) { _, _ ->
                // Encrypt each folder as a new vault entry (always deletes original)
                folders.forEach { folder ->
                    encryptFolderToNewVaultEntry(folder)
                }
                // If there were also files selected, run them through the vault picker
                if (extraFiles.isNotEmpty()) {
                    showVaultPickerForFiles(extraFiles, isMove)
                }
            }
            .create()

        dialog.show()

        // Dark window styling
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(bgColor)
        )
        val titleView = dialog.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.alertTitle
        ) ?: dialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(white)
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(white)

        // Confirm button: yellow bg + black text
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            backgroundTintList = yellowCsl
            setTextColor(black)
            if (DeviceUtils.isTvDevice(this@FileBrowserActivity)) {
                setOnFocusChangeListener { _, hasFocus ->
                    backgroundTintList = if (hasFocus) yellowCsl else yellowCsl // always yellow
                    setTextColor(black)
                }
            }
        }
        // Cancel button: glass default, yellow on TV focus
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
     * Auto-creates a new vault entry for a folder (same logic as VaultActivity.encryptFolder).
     * Always deletes the original folder after encryption.
     */
    private fun encryptFolderToNewVaultEntry(root: File) {
        if (!root.exists() || !root.isDirectory) return

        val progressView = layoutInflater.inflate(R.layout.dialog_vault_progress, null)
        val txtProgress = progressView.findViewById<TextView>(R.id.txtVaultProgress)
        val progressBar = progressView.findViewById<android.widget.ProgressBar>(R.id.progressVault)

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.encrypt_copy_title))
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()
        progressDialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(getColor(R.color.tv_bg_gradient_end))
        )
        val titleView = progressDialog.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.alertTitle
        ) ?: progressDialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(getColor(R.color.tv_text_primary))

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val entryId = java.util.UUID.randomUUID().toString()
                    val vaultBase = File(filesDir, "vault")
                    val entryDir = File(vaultBase, entryId)
                    entryDir.mkdirs()

                    val allFiles = root.walkTopDown()
                        .filter { it.isFile }
                        .filter { !isSystemFile(it) }
                        .toList()

                    val total = allFiles.size.coerceAtLeast(1)
                    val relativeList = mutableListOf<String>()

                    allFiles.forEachIndexed { index, file ->
                        val relative = file.relativeTo(root).path
                        val encryptedFile = File(entryDir, "$relative.enc")
                        encryptedFile.parentFile?.mkdirs()
                        VaultCrypto.encryptFile(file, encryptedFile)
                        relativeList.add(relative)
                        if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(file.absolutePath)) {
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

                    val metadata = org.json.JSONObject().apply {
                        put("id", entryId)
                        put("displayName", root.name)
                        put("originalRoot", root.absolutePath)
                        put("files", org.json.JSONArray(relativeList))
                    }
                    File(entryDir, "metadata.json").writeText(metadata.toString())
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

    /**
     * Shows vault picker dialog for encrypting files (files only, not folders).
     * If no vaults exist, prompts user to create one first.
     */
    private fun showArchiveOptions(files: List<File>) {
        val dialog = ArchiveOptionsDialog()
        dialog.setOnConfirm { filename, format, password ->
            // Stash so folderPickerLauncher can reach them for network destinations
            pendingCompressSourceFiles = files
            pendingCompressFileName    = filename
            pendingCompressFormat      = format
            pendingCompressPassword    = password
            pickDestinationFolder { destDir ->
                performCompression(files, destDir, filename, format, password)
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

        val extension = if (format == ArchiveManager.Format.ZIP) ".zip" else ".7z"
        val archiveName = "$customFileName$extension"

        val job = lifecycleScope.launch(Dispatchers.IO) {
            val tempArchive = File(cacheDir, "local_comp_${System.currentTimeMillis()}$extension")
            try {
                // 1. Compress locally
                ArchiveManager.compress(sourceFiles, tempArchive, password) { progress ->
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
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val statusText = android.widget.TextView(this).apply {
            text = getString(R.string.compressing_2)
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
            .setTitle(R.string.compressing_files)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        val fileName = customFileName
        val extension = if (format == ArchiveManager.Format.ZIP) ".zip" else ".7z"
        var destFile = File(destDir, "$fileName$extension")
        var counter = 1
        while (destFile.exists()) {
            destFile = File(destDir, "$fileName ($counter)$extension")
            counter++
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ArchiveManager.compress(sourceFiles, destFile, password) { progress ->
                    runOnUiThread {
                        dialogProgress.progress = progress
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
            val noVaultDialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(getString(R.string.encrypt_no_vaults))
                .setMessage(getString(R.string.encrypt_create_first))
                .setIcon(R.drawable.ic_lock)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(getString(R.string.encrypt_create_vault)) { _, _ ->
                    // Open VaultActivity to create a vault
                    val intent = Intent(this, VaultActivity::class.java)
                    startActivity(intent)
                }
                .show()

            // Apply dark-theme styling: white title + message, dark bg, themed buttons
            val bgColor   = getColor(R.color.tv_bg_gradient_end)
            val white     = getColor(R.color.tv_text_primary)
            val black     = getColor(R.color.tv_button_focused_yellow_text)
            val yellow    = getColor(R.color.tv_button_focused_yellow)
            val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
            val glassCsl  = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

            noVaultDialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(bgColor)
            )
            val titleView = noVaultDialog.findViewById<android.widget.TextView>(
                com.google.android.material.R.id.alertTitle
            ) ?: noVaultDialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
            titleView?.setTextColor(white)
            noVaultDialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(white)

            noVaultDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                backgroundTintList = yellowCsl
                setTextColor(black)
            }
            noVaultDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                backgroundTintList = glassCsl
                setTextColor(white)
            }
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

        val dialog = MaterialAlertDialogBuilder(this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
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
            android.graphics.drawable.ColorDrawable(bgColor)
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
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isMove) R.string.encrypt_move_title else R.string.encrypt_copy_title)
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()

        // Dark window + white title to match TV theme
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(getColor(R.color.tv_bg_gradient_end))
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
                        put("displayName", entry.displayName)
                        put("originalRoot", entry.originalRoot)
                        put("files", org.json.JSONArray(existingFiles))
                    }
                    File(entryDir, "metadata.json").writeText(metadata.toString())
                    
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
        return try {
            val metadataFile = File(dir, "metadata.json")
            if (!metadataFile.exists()) return null
            val json = org.json.JSONObject(metadataFile.readText())
            val filesJson = json.getJSONArray("files")
            val files = mutableListOf<String>()
            for (i in 0 until filesJson.length()) {
                files.add(filesJson.getString(i))
            }
            VaultEntry(
                id = json.getString("id"),
                displayName = json.getString("displayName"),
                originalRoot = json.getString("originalRoot"),
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
                    results.map { File(it.path) }
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
        
        val list = mutableListOf<Pair<String, File?>>()
        list.add(Pair("Home", null))
        list.add(Pair(storageLabel, File(rootPath)))
        
        if (currentDir.absolutePath.startsWith(rootPath) && currentDir.absolutePath != rootPath) {
            val relativePath = currentDir.absolutePath.substring(rootPath.length).removePrefix("/")
            if (relativePath.isNotEmpty()) {
                val parts = relativePath.split("/")
                var currentAccumulated = File(rootPath)
                for (part in parts) {
                    if (part.isNotEmpty()) {
                        currentAccumulated = File(currentAccumulated, part)
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
}
