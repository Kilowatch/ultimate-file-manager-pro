package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.viewer.OpenAsBottomSheet
import za.kilowatch.ultimatefilemanager.storage.ViewModeManager
import za.kilowatch.ultimatefilemanager.storage.FilePropertiesBottomSheet
import za.kilowatch.ultimatefilemanager.storage.FileTagsManager
import za.kilowatch.ultimatefilemanager.storage.BatchRenameItem
import za.kilowatch.ultimatefilemanager.storage.BatchRenameDialogFragment
import za.kilowatch.ultimatefilemanager.storage.BatchRenameTvActivity
import za.kilowatch.ultimatefilemanager.storage.FileToolsBottomSheet
import za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager
import za.kilowatch.ultimatefilemanager.storage.SortFilterSheet
import java.io.File
import java.io.FileOutputStream
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager
import za.kilowatch.ultimatefilemanager.util.FolderScrollState
import android.webkit.MimeTypeMap
import za.kilowatch.ultimatefilemanager.archive.ArchiveManager
import za.kilowatch.ultimatefilemanager.archive.ArchiveOptionsDialog
import za.kilowatch.ultimatefilemanager.archive.ExtractOptionsDialog
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager
import za.kilowatch.ultimatefilemanager.ui.PremiumShareActivity
import za.kilowatch.ultimatefilemanager.ui.PremiumShareTvActivity
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.VaultActivity
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import za.kilowatch.ultimatefilemanager.storage.VaultEntry
import za.kilowatch.ultimatefilemanager.util.DialogInputHelper

class NetworkBrowserFragment : Fragment() {

    companion object {
        const val ARG_SHARE_ID = "share_id"
        const val ARG_INITIAL_PATH = "initial_path"
        const val ARG_IS_TWIN_WINDOW = "is_twin_window"
        const val ARG_REQUEST_INITIAL_FOCUS = "request_initial_focus"
        const val ARG_TAB_ID = "tab_id"

        fun newInstance(
            shareId: String,
            initialPath: String = "",
            isTwinWindow: Boolean = false,
            requestInitialFocus: Boolean = false,
            tabId: String = ""
        ): NetworkBrowserFragment {
            return NetworkBrowserFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SHARE_ID, shareId)
                    putString(ARG_INITIAL_PATH, initialPath)
                    putBoolean(ARG_IS_TWIN_WINDOW, isTwinWindow)
                    putBoolean(ARG_REQUEST_INITIAL_FOCUS, requestInitialFocus)
                    if (tabId.isNotEmpty()) putString(ARG_TAB_ID, tabId)
                }
            }
        }
    }

    private lateinit var share: NetworkShare
    private var originalRemotePath: String = ""
    private var currentPath: String = ""
    private var isTv: Boolean = false
    private var isTwinWindow: Boolean = false
    private var isCompactMode: Boolean = false
    private var lastExitedPath: String? = null
    private var shouldRestoreFocus = false
    private val folderScrollStates = mutableMapOf<String, FolderScrollState>()

    private fun saveCurrentFolderScroll(targetChildPath: String? = null) {
        if (!::recyclerFiles.isInitialized) return
        val state = FolderScrollState.capture(recyclerFiles, targetChildPath)
        if (state != null) {
            folderScrollStates[currentPath] = state
        }
    }

    private lateinit var recyclerFiles: RecyclerView
    private lateinit var fileAdapter: NetworkFileAdapter
    private lateinit var progressBar: ProgressBar
    private var txtTitle: TextView? = null
    private var txtSubtitle: TextView? = null
    
    private var btnSearchToggle: ImageView? = null
    private var layoutSearchRow: LinearLayout? = null
    private var edtSearch: EditText? = null
    private var btnSearchClear: ImageView? = null
    private var isSearchVisible = false
    private var searchJob: Job? = null
    private var loadJob: Job? = null

    private var sortMode = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.NAME
    private var sortOrder = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortOrder.ASC
    private var filterType = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.ALL
    private var currentDateFilter = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.DateFilter.ANY
    private var currentSizeFilter = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SizeFilter.ANY
    private var isSearchActive = false
    private var activeTagsFilter: Set<String> = emptySet()
    private var currentFiles: List<NetworkFile> = emptyList()
    private var layoutSelectionBar: View? = null
    private var txtSelectionCount: TextView? = null
    private var layoutEmpty: View? = null
    private var fabPaste: ExtendedFloatingActionButton? = null
    private var fabProperties: ExtendedFloatingActionButton? = null
    private var fabTools: ExtendedFloatingActionButton? = null
    private var fabSelectAll: ExtendedFloatingActionButton? = null
    private var floatingQuickBar: za.kilowatch.ultimatefilemanager.ui.FloatingQuickActionBar? = null
    private lateinit var cacheManager: za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager
    private var btnOptionsToggle: View? = null
    private var layoutOptionsRow: LinearLayout? = null
    private var isOptionsVisible = false

    private val batchRenameTvLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val oldPaths = result.data?.getStringArrayListExtra(za.kilowatch.ultimatefilemanager.storage.BatchRenameTvActivity.EXTRA_RENAMED_OLD_PATHS) ?: emptyList()
            val newPaths = result.data?.getStringArrayListExtra(za.kilowatch.ultimatefilemanager.storage.BatchRenameTvActivity.EXTRA_RENAMED_NEW_PATHS) ?: emptyList()
            val renamedMap = oldPaths.zip(newPaths).toMap()
            onBatchRenameCompleted(renamedMap)
        }
    }

    private var pendingCompressSourceFiles: List<NetworkFile>? = null
    private var pendingCompressFileName: String? = null
    private var pendingCompressFormat: ArchiveManager.Format? = null
    private var pendingCompressPassword: String? = null

    private val localFolderPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val localPath = data.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
            if (localPath != null) {
                val destDir = File(localPath)
                val src = pendingCompressSourceFiles ?: return@registerForActivityResult
                val name = pendingCompressFileName ?: return@registerForActivityResult
                val fmt = pendingCompressFormat ?: return@registerForActivityResult
                performNetworkCompression(src, CompressDest.Local(destDir), name, fmt, pendingCompressPassword)
                clearPendingCompress()
                return@registerForActivityResult
            }
            val shareId = data.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_COMPRESS_SHARE_ID)
            val netPath = data.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_COMPRESS_NET_PATH)
            if (shareId != null && netPath != null) {
                val destShare = resolveShareById(shareId)
                if (destShare != null) {
                    val src = pendingCompressSourceFiles ?: return@registerForActivityResult
                    val name = pendingCompressFileName ?: return@registerForActivityResult
                    val fmt = pendingCompressFormat ?: return@registerForActivityResult
                    performNetworkCompression(src, CompressDest.Network(destShare, netPath), name, fmt, pendingCompressPassword)
                }
                clearPendingCompress()
            }
        } else {
            clearPendingCompress()
        }
    }

    private fun clearPendingCompress() {
        pendingCompressSourceFiles = null
        pendingCompressFileName = null
        pendingCompressFormat = null
        pendingCompressPassword = null
    }

    // Twin Window specific
    var onStoragePickerRequested: (() -> Unit)? = null
    var onActionRequested: ((String) -> Unit)? = null
    var onFileSelected: ((NetworkFile) -> Unit)? = null
    var onMediaFileSelected: ((NetworkFile) -> Unit)? = null
    var onCloseTwinWindow: (() -> Unit)? = null
    var onSelectionChanged: ((List<NetworkFile>) -> Unit)? = null
    var onInvalidShare: (() -> Unit)? = null
    var onDirectoryChanged: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shareId = arguments?.getString(ARG_SHARE_ID) ?: ""
        currentPath = arguments?.getString(ARG_INITIAL_PATH) ?: ""
        
        // Resolve share
        val context = requireContext()
        val fromRepo = NetworkShareRepository.getInstance(context).getById(shareId)
        val resolvedShare = if (fromRepo != null) {
            fromRepo
        } else {
            val online = OnlineStorageRepository.getInstance(context).getById(shareId)
            if (online != null) {
                NetworkShare(
                    id = online.id,
                    name = online.displayName,
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
                        OnlineStorageProvider.RCLONE -> online.id
                        else -> if (online.isWebDavProvider) online.webDavUsername ?: "" else online.s3AccessKey ?: online.email
                    },
                    password = when {
                        online.isWebDavProvider -> online.webDavPassword ?: ""
                        else                    -> online.s3SecretKey ?: ""
                    },
                    readOnly = false
                )
            } else {
                PairingManager.getInstance(context).getPairedDevice(shareId)?.let { dev ->
                    NetworkShare(id = dev.deviceId, name = dev.name, type = ShareType.TV, host = dev.lastIp, port = dev.lastPort, readOnly = false)
                }
            }
        }

        if (resolvedShare == null) {
            onInvalidShare?.invoke()
            return
        }
        share = resolvedShare
        originalRemotePath = share.remotePath

        isTv = context.packageManager.hasSystemFeature("android.software.leanback")
        isTwinWindow = arguments?.getBoolean(ARG_IS_TWIN_WINDOW, false) == true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val isVerticalSplit = !isTv && isTwinWindow &&
            za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(requireContext())
        isCompactMode = isVerticalSplit
        
        val layoutId = when {
            isTv -> R.layout.fragment_network_browser_tv
            isCompactMode -> R.layout.fragment_network_browser_compact
            else -> R.layout.fragment_network_browser
        }
        return inflater.inflate(layoutId, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.getBundle("KEY_FOLDER_SCROLL_STATES")?.let { bundle ->
            folderScrollStates.putAll(FolderScrollState.fromBundle(bundle))
        }
        if (!::share.isInitialized) {
            onInvalidShare?.invoke()
            return
        }
        cacheManager = za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager(requireContext())
        setupViews(view)
        loadDirectory()
    }

    override fun onResume() {
        super.onResume()
        applyLeftHandedFabSettings()
        applyToolbarIconVisibility()
        updatePasteFab()
        if (::fileAdapter.isInitialized) {
            context?.let { ctx ->
                val savedMode = ViewModeManager.load(ctx)
                val currentSpan = (recyclerFiles.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)?.spanCount
                val targetSpan = if (ViewModeManager.isGrid(savedMode)) ViewModeManager.spanCount(ctx, savedMode) else 1
                if (fileAdapter.viewMode != savedMode || (ViewModeManager.isGrid(savedMode) && currentSpan != targetSpan)) {
                    applyViewMode(savedMode)
                } else {
                    fileAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveCurrentFolderScroll()
        outState.putBundle("KEY_FOLDER_SCROLL_STATES", FolderScrollState.toBundle(folderScrollStates))
    }

    fun updateFabPositions() {
        val ctx = context ?: return
        val isToolsVisible = fabTools?.visibility == View.VISIBLE
        val isQuickBarVisible = floatingQuickBar?.visibility == View.VISIBLE
        val isPasteVisible = fabPaste?.visibility == View.VISIBLE

        if (isCompactMode) {
            val fabT = fabTools
            val fabS = fabSelectAll
            val fabP = fabPaste

            fabT?.let { fab ->
                val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                fab.layoutParams = lp
            }
            fabP?.let { fab ->
                val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                if (isToolsVisible) {
                    lp.bottomToTop = R.id.fabTools
                    lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                } else if (isQuickBarVisible) {
                    lp.bottomToTop = R.id.floatingQuickBar
                    lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                } else {
                    lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                }
                fab.layoutParams = lp
            }
            fabS?.let { fab ->
                val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                val anchorTop = when {
                    isPasteVisible -> R.id.fabPaste
                    isToolsVisible -> R.id.fabTools
                    else -> 0
                }
                if (anchorTop != 0) {
                    lp.bottomToTop = anchorTop
                    lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                } else {
                    lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                }
                fab.layoutParams = lp
            }
            return
        }

        if (isTwinWindow) {
            val fabS = fabSelectAll
            val fabT = fabTools
            val fabP = fabPaste

            fabS?.let { fab ->
                val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToStart = R.id.fabTools
                lp.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.horizontalChainStyle = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.CHAIN_PACKED
                lp.horizontalBias = 0.5f
                fab.layoutParams = lp
            }
            fabT?.let { fab ->
                val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.startToEnd = R.id.fabSelectAll
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                fab.layoutParams = lp
            }
            fabP?.let { fab ->
                val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.startToEnd = R.id.fabSelectAll
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                if (isToolsVisible) {
                    lp.bottomToTop = R.id.fabTools
                    lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                } else if (isQuickBarVisible) {
                    lp.bottomToTop = R.id.floatingQuickBar
                    lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                } else {
                    lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                }
                fab.layoutParams = lp
            }
            return
        }

        val isLeftHanded = za.kilowatch.ultimatefilemanager.settings.LeftHandedFabPreferenceManager.isLeftHanded(ctx)
        val isSelectionMode = fileAdapter.isSelectionMode
        val density = resources.displayMetrics.density
        val baseMargin = (16 * density).toInt()
        val selectionLeftMargin = (56 * density).toInt()

        fabTools?.let { fab ->
            val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
            lp.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.bottomToTop = R.id.layoutActionPillsScroll
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            if (isLeftHanded) {
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.marginStart = if (isSelectionMode) selectionLeftMargin else baseMargin
                lp.marginEnd = baseMargin
            } else {
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.marginStart = baseMargin
                lp.marginEnd = baseMargin
            }
            fab.layoutParams = lp
        }

        fabPaste?.let { fab ->
            val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
            lp.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            if (isToolsVisible) {
                lp.bottomToTop = R.id.fabTools
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            } else if (isQuickBarVisible) {
                lp.bottomToTop = R.id.floatingQuickBar
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            } else {
                lp.bottomToTop = R.id.layoutActionPillsScroll
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            }
            if (isLeftHanded) {
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.marginStart = if (isSelectionMode) selectionLeftMargin else baseMargin
                lp.marginEnd = baseMargin
            } else {
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.marginStart = baseMargin
                lp.marginEnd = baseMargin
            }
            fab.layoutParams = lp
        }
    }

    private fun applyLeftHandedFabSettings() {
        updateFabPositions()
    }

    private fun applyToolbarIconVisibility() {
        val v = view ?: return
        val context = context ?: return
        val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager

        v.findViewById<View>(R.id.btnCopy)?.visibility = if (pm.isIconEnabled(context, pm.KEY_COPY)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnMove)?.visibility = if (pm.isIconEnabled(context, pm.KEY_MOVE)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnRename)?.visibility = if (pm.isIconEnabled(context, pm.KEY_RENAME)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnShare)?.visibility = if (pm.isIconEnabled(context, pm.KEY_SHARE)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnCopyEncrypt)?.visibility = if (pm.isIconEnabled(context, pm.KEY_COPY_ENCRYPT)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnMoveEncrypt)?.visibility = if (pm.isIconEnabled(context, pm.KEY_MOVE_ENCRYPT)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnFavorite)?.visibility = if (pm.isIconEnabled(context, pm.KEY_FAVORITE)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnHide)?.visibility = if (pm.isIconEnabled(context, pm.KEY_HIDE)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnUnhide)?.visibility = if (pm.isIconEnabled(context, pm.KEY_UNHIDE)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnProtect)?.visibility = if (pm.isIconEnabled(context, pm.KEY_PROTECT)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnUnprotect)?.visibility = if (pm.isIconEnabled(context, pm.KEY_UNPROTECT)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnPin)?.visibility = if (pm.isIconEnabled(context, pm.KEY_PIN)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnUnpin)?.visibility = if (pm.isIconEnabled(context, pm.KEY_UNPIN)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnCompress)?.visibility = if (pm.isIconEnabled(context, pm.KEY_COMPRESS)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnImageCompress)?.visibility = View.GONE
        v.findViewById<View>(R.id.btnSelectAll)?.visibility = if (pm.isIconEnabled(context, pm.KEY_SELECT_ALL)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnDelete)?.visibility = if (pm.isIconEnabled(context, pm.KEY_DELETE)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnRetriggerThumbnails)?.visibility = if (pm.isIconEnabled(context, pm.KEY_RETRIGGER_THUMBNAILS)) View.VISIBLE else View.GONE
    }

    private fun setupViews(view: View) {
        recyclerFiles = view.findViewById(R.id.recyclerFiles)
        if (activity is za.kilowatch.ultimatefilemanager.tabs.TabbedBrowserActivity) {
            view.findViewById<View>(R.id.headerLayout)?.visibility = View.GONE
            view.findViewById<View>(R.id.layoutSearchRow)?.visibility = View.GONE
        }
        progressBar = view.findViewById(R.id.progressBar)
        txtTitle = view.findViewById(R.id.txtTitle)
        txtSubtitle = view.findViewById(R.id.txtSubtitle)
        layoutSelectionBar = view.findViewById(R.id.layoutSelectionBar)
        txtSelectionCount = view.findViewById(R.id.txtSelectionCount)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        fabPaste = view.findViewById(R.id.fabPaste)
        fabTools = view.findViewById(R.id.fabTools)
        floatingQuickBar = view.findViewById(R.id.floatingQuickBar)
        floatingQuickBar?.setOnActionClickListener { actionId ->
            handleQuickActionClick(actionId)
        }
        fabSelectAll = view.findViewById(R.id.fabSelectAll)
        fabSelectAll?.setOnClickListener {
            if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
        }
        fabPaste?.setOnClickListener {
            val act = activity
            if (act is TwinWindowActivity) {
                act.onPasteRequested(this)
            } else if (act is NetworkOperationsListener) {
                act.onNetworkPasteRequested(this, currentPath)
            }
        }
        
        fabProperties?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                val sheet = FilePropertiesBottomSheet.newInstanceForNetworkFiles(selected, currentPath)
                sheet.show(parentFragmentManager, FilePropertiesBottomSheet.TAG)
            }
        }

        fabTools?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            val count = selected.size
            val showActions = count > 0
            if (!showActions) return@setOnClickListener

            val list = mutableListOf<FileToolsBottomSheet.ActionItem>()
            val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
            val context = context ?: return@setOnClickListener

            // 0. Invert Selection
            if (pm.isIconEnabled(context, pm.KEY_INVERT_SELECTION)) {
                list.add(FileToolsBottomSheet.ActionItem("invert_selection", getString(R.string.action_invert_selection), R.drawable.ic_invert_selection, "toolbar_invert_selection") {
                    fileAdapter.invertSelection()
                })
            }

            // 1. Copy
            if (pm.isIconEnabled(context, pm.KEY_COPY)) {
                list.add(FileToolsBottomSheet.ActionItem("copy", getString(R.string.action_copy), R.drawable.ic_copy, "toolbar_copy") {
                    if (isTwinWindow) {
                        onActionRequested?.invoke("copy")
                    } else {
                        (activity as? NetworkOperationsListener)?.onNetworkCopyRequested(this, selected)
                    }
                    fileAdapter.exitSelectionMode()
                })
            }

            // 2. Move (Cut)
            if (pm.isIconEnabled(context, pm.KEY_MOVE)) {
                list.add(FileToolsBottomSheet.ActionItem("move", getString(R.string.action_move), R.drawable.ic_move, "toolbar_move") {
                    if (isTwinWindow) {
                        onActionRequested?.invoke("move")
                    } else {
                        (activity as? NetworkOperationsListener)?.onNetworkMoveRequested(this, selected)
                    }
                    fileAdapter.exitSelectionMode()
                })
            }

            // Delete
            if (!share.readOnly && pm.isIconEnabled(context, pm.KEY_DELETE)) {
                list.add(FileToolsBottomSheet.ActionItem("delete", getString(R.string.action_delete), R.drawable.ic_delete, "toolbar_delete") {
                    showDeleteConfirmation()
                })
            }

            // 3. Rename
            if (pm.isIconEnabled(context, pm.KEY_RENAME)) {
                list.add(FileToolsBottomSheet.ActionItem("rename", getString(R.string.action_rename), R.drawable.ic_edit, "toolbar_rename") {
                    if (selected.size == 1) {
                        showRenameDialog(selected.first())
                    } else if (selected.size > 1) {
                        val items = selected.map { BatchRenameItem.fromNetworkFile(it, share) }
                        if (DeviceUtils.isTvDevice(requireContext())) {
                            val intent = android.content.Intent(requireContext(), BatchRenameTvActivity::class.java).apply {
                                putParcelableArrayListExtra("items", java.util.ArrayList(items))
                            }
                            batchRenameTvLauncher.launch(intent)
                        } else {
                            val dialog = BatchRenameDialogFragment.newInstance(items)
                            dialog.setOnCompleteListener { _, _, renamedMap ->
                                onBatchRenameCompleted(renamedMap)
                            }
                            dialog.show(parentFragmentManager, BatchRenameDialogFragment.TAG)
                        }
                    }
                })
            }

            // 4. Share
            if (pm.isIconEnabled(context, pm.KEY_SHARE)) {
                val shareable = selected.filter { !it.isDirectory }
                if (shareable.isNotEmpty()) {
                    list.add(FileToolsBottomSheet.ActionItem("share", getString(R.string.action_share), R.drawable.ic_share, "toolbar_share") {
                        shareNetworkFiles(shareable)
                    })
                }
            }

            // 4a. Open With
            if (count == 1 && !selected.first().isDirectory && pm.isIconEnabled(context, pm.KEY_OPEN_WITH)) {
                list.add(FileToolsBottomSheet.ActionItem("open_with", getString(R.string.toolbar_open_with), R.drawable.ic_apps, "toolbar_open_with") {
                    cacheNetworkFile(selected.first()) { localFile ->
                        FileViewerRouter.showOpenWithDialog(requireActivity(), localFile, isNetwork = true)
                    }
                })
            }

            // 4b. Open As
            if (count == 1 && !selected.first().isDirectory && pm.isIconEnabled(context, pm.KEY_OPEN_AS)) {
                list.add(FileToolsBottomSheet.ActionItem("open_as", getString(R.string.toolbar_open_as), R.drawable.ic_apps, "toolbar_open_as") {
                    cacheNetworkFile(selected.first()) { localFile ->
                        OpenAsBottomSheet.newInstance(localFile.absolutePath, selected.first().name, true)
                            .show(parentFragmentManager, OpenAsBottomSheet.TAG)
                    }
                })
            }

            // 5. Favorite
            if (count == 1 && pm.isIconEnabled(context, pm.KEY_FAVORITE)) {
                list.add(FileToolsBottomSheet.ActionItem("favorite", getString(R.string.action_favorite), R.drawable.ic_star, "toolbar_favorite") {
                    showFavoriteDialog(selected.first())
                })
            }

            // 6. Compress
            if (pm.isIconEnabled(context, pm.KEY_COMPRESS)) {
                list.add(FileToolsBottomSheet.ActionItem("compress", getString(R.string.action_compress), R.drawable.ic_compress, "toolbar_compress") {
                    showArchiveOptions(selected)
                })
            }

            // 6b. Extract Here
            val hasArchiveSelected = selected.isNotEmpty() && selected.any {
                ArchiveManager.isSupportedArchiveExtension(it.name.substringAfterLast('.'))
            }
            if (hasArchiveSelected && pm.isIconEnabled(context, pm.KEY_EXTRACT)) {
                list.add(FileToolsBottomSheet.ActionItem("extract_here", getString(R.string.action_extract_here), R.drawable.ic_extract, "toolbar_extract") {
                    performNetworkExtractHere(selected.filter {
                        ArchiveManager.isSupportedArchiveExtension(it.name.substringAfterLast('.'))
                    })
                })
            }

            // 7. Compress Image
            val allNetworkImages = selected.isNotEmpty() && selected.all {
                it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            }
            if (allNetworkImages && pm.isIconEnabled(context, pm.KEY_IMAGE_COMPRESS)) {
                list.add(FileToolsBottomSheet.ActionItem("image_compress", getString(R.string.action_compress_image), R.drawable.ic_compress_image, "toolbar_image_compress") {
                    downloadNetworkImagesAndCompress(selected.filter {
                        it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                    })
                })
            }

            // Create GIF (Requires 2+ images)
            val canCreateGif = selected.size >= 2 && allNetworkImages
            if (canCreateGif && pm.isIconEnabled(requireContext(), pm.KEY_CREATE_GIF)) {
                list.add(FileToolsBottomSheet.ActionItem("create_gif", getString(R.string.action_create_gif), R.drawable.ic_gif, "toolbar_create_gif") {
                    val netImages = selected.filter {
                        it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                    }
                    downloadNetworkImagesAndCreateGif(netImages)
                })
            }

            // Extract Subtitles from Video
            if (count == 1 && !selected.first().isDirectory &&
                za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(selected.first().name.substringAfterLast('.')) &&
                za.kilowatch.ultimatefilemanager.media.FFmpegMediaHelper.isAvailable() &&
                pm.isIconEnabled(context, pm.KEY_EXTRACT_SUBTITLES)) {
                list.add(FileToolsBottomSheet.ActionItem("extract_subtitles", getString(R.string.toolbar_extract_subtitles), R.drawable.ic_subtitles, "toolbar_extract_subtitles") {
                    extractSubtitlesFromNetworkVideo(selected.first())
                })
            }

            // Extract Audio from Video
            if (count == 1 && !selected.first().isDirectory &&
                za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(selected.first().name.substringAfterLast('.')) &&
                za.kilowatch.ultimatefilemanager.media.FFmpegMediaHelper.isAvailable() &&
                pm.isIconEnabled(context, pm.KEY_EXTRACT_AUDIO)) {
                list.add(FileToolsBottomSheet.ActionItem("extract_audio", getString(R.string.toolbar_extract_audio), R.drawable.ic_audio_track, "toolbar_extract_audio") {
                    extractAudioFromNetworkVideo(selected.first())
                })
            }

            // Convert Video to MP4
            if (count == 1 && !selected.first().isDirectory &&
                za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(selected.first().name.substringAfterLast('.')) &&
                za.kilowatch.ultimatefilemanager.media.FFmpegMediaHelper.isAvailable() &&
                pm.isIconEnabled(context, pm.KEY_CONVERT_TO_MP4)) {
                list.add(FileToolsBottomSheet.ActionItem("convert_to_mp4", getString(R.string.toolbar_convert_to_mp4), R.drawable.ic_convert_video, "toolbar_convert_to_mp4") {
                    convertNetworkVideoToMp4(selected.first())
                })
            }

            // Photo EXIF Cleaner & Renamer (Mobile Only)
            if (allNetworkImages && !DeviceUtils.isTvDevice(requireContext()) && pm.isIconEnabled(context, pm.KEY_EXIF_TOOLS)) {
                list.add(FileToolsBottomSheet.ActionItem("exif_tools", getString(R.string.action_exif_cleaner_renamer), R.drawable.ic_exif_cleaner, "toolbar_exif_cleaner") {
                    val netImages = selected.filter {
                        it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                    }
                    downloadNetworkImagesAndLaunchExifTools(netImages)
                })
            }

            // Wallpaper (Single network image file, mobile only)
            val isSingleNetworkImage = count == 1 && !selected.first().isDirectory &&
                selected.first().name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            if (isSingleNetworkImage && !DeviceUtils.isTvDevice(requireContext())) {
                val targetFile = selected.first()

                // Set Home Wallpaper
                if (pm.isIconEnabled(context, pm.KEY_SET_HOME_WALLPAPER)) {
                    list.add(FileToolsBottomSheet.ActionItem("set_home_wallpaper", getString(R.string.action_set_home_wallpaper), R.drawable.ic_wallpaper_home, "toolbar_set_home_wallpaper") {
                        setNetworkWallpaper(targetFile, android.app.WallpaperManager.FLAG_SYSTEM)
                    })
                }

                // Set Lock Wallpaper
                if (pm.isIconEnabled(context, pm.KEY_SET_LOCK_WALLPAPER)) {
                    list.add(FileToolsBottomSheet.ActionItem("set_lock_wallpaper", getString(R.string.action_set_lock_wallpaper), R.drawable.ic_wallpaper_lock, "toolbar_set_lock_wallpaper") {
                        setNetworkWallpaper(targetFile, android.app.WallpaperManager.FLAG_LOCK)
                    })
                }
            }

            // Music Tagger (All selected items are audio files, mobile only)
            val allNetworkAudio = selected.isNotEmpty() && selected.all {
                !it.isDirectory && za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(it.name.substringAfterLast('.'))
            }
            if (allNetworkAudio && !DeviceUtils.isTvDevice(requireContext()) && pm.isIconEnabled(context, pm.KEY_MUSIC_TAGGER)) {
                list.add(FileToolsBottomSheet.ActionItem("music_tagger", getString(R.string.action_music_tagger), R.drawable.ic_music_tag, "toolbar_music_tagger") {
                    val netAudio = selected.filter {
                        !it.isDirectory && za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(it.name.substringAfterLast('.'))
                    }
                    downloadNetworkAudioAndLaunchTagger(netAudio)
                })
            }

            // System Sound (Single network audio file, mobile only)
            val isSingleNetworkAudio = count == 1 && !selected.first().isDirectory &&
                za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(selected.first().name.substringAfterLast('.'))
            if (isSingleNetworkAudio && !DeviceUtils.isTvDevice(requireContext())) {
                val targetFile = selected.first()

                // Set Ringtone
                if (pm.isIconEnabled(context, pm.KEY_SET_RINGTONE)) {
                    list.add(FileToolsBottomSheet.ActionItem("set_ringtone", getString(R.string.action_set_ringtone), R.drawable.ic_ringtone, "toolbar_set_ringtone") {
                        setNetworkSystemSound(targetFile, android.media.RingtoneManager.TYPE_RINGTONE)
                    })
                }

                // Set Notification Sound
                if (pm.isIconEnabled(context, pm.KEY_SET_NOTIFICATION)) {
                    list.add(FileToolsBottomSheet.ActionItem("set_notification", getString(R.string.action_set_notification), R.drawable.ic_notification_sound, "toolbar_set_notification") {
                        setNetworkSystemSound(targetFile, android.media.RingtoneManager.TYPE_NOTIFICATION)
                    })
                }

                // Set Alarm Sound
                if (pm.isIconEnabled(context, pm.KEY_SET_ALARM)) {
                    list.add(FileToolsBottomSheet.ActionItem("set_alarm", getString(R.string.action_set_alarm), R.drawable.ic_alarm_sound, "toolbar_set_alarm") {
                        setNetworkSystemSound(targetFile, android.media.RingtoneManager.TYPE_ALARM)
                    })
                }
            }

            // 8. Copy Encrypted
            if (pm.isIconEnabled(context, pm.KEY_COPY_ENCRYPT)) {
                val encryptable = selected.filter { !it.isDirectory }
                if (encryptable.isNotEmpty()) {
                    list.add(FileToolsBottomSheet.ActionItem("copy_encrypt", getString(R.string.action_copy_encrypt), R.drawable.ic_copy, "toolbar_copy_encrypt") {
                        showNetworkVaultPicker(encryptable, isMove = false)
                    })
                }
            }

            // 9. Move Encrypted
            if (pm.isIconEnabled(context, pm.KEY_MOVE_ENCRYPT)) {
                val encryptable = selected.filter { !it.isDirectory }
                if (encryptable.isNotEmpty()) {
                    list.add(FileToolsBottomSheet.ActionItem("move_encrypt", getString(R.string.action_move_encrypt), R.drawable.ic_move, "toolbar_move_encrypt") {
                        showNetworkVaultPicker(encryptable, isMove = true)
                    })
                }
            }

            // 4. Protect
            val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(context, share.id)
            if (hasUnprotected && pm.isIconEnabled(context, pm.KEY_PROTECT)) {
                list.add(FileToolsBottomSheet.ActionItem("protect", getString(R.string.protect), R.drawable.ic_shield_protected, "toolbar_protect") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(requireContext(), file.path, share.id, protected = true)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory()
                            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_protected_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }

            // 5. Unprotect
            val hasProtected = fileAdapter.hasAnySelectedProtected(context, share.id)
            if (hasProtected && pm.isIconEnabled(context, pm.KEY_UNPROTECT)) {
                list.add(FileToolsBottomSheet.ActionItem("unprotect", getString(R.string.unprotect), R.drawable.ic_shield_unprotected, "toolbar_unprotect") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(requireContext(), file.path, share.id, protected = false)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory()
                            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_unprotected_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }

            // Pin
            val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(context, share.id)
            if (hasUnpinned && pm.isIconEnabled(context, pm.KEY_PIN)) {
                list.add(FileToolsBottomSheet.ActionItem("pin", getString(R.string.pin), R.drawable.ic_paperclip, "toolbar_pin") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(requireContext(), file.path, share.id, pinned = true)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory()
                            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_pinned_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }

            // Unpin
            val hasPinned = fileAdapter.hasAnySelectedPinned(context, share.id)
            if (hasPinned && pm.isIconEnabled(context, pm.KEY_UNPIN)) {
                list.add(FileToolsBottomSheet.ActionItem("unpin", getString(R.string.unpin), R.drawable.ic_paperclip_off, "toolbar_unpin") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(requireContext(), file.path, share.id, pinned = false)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory()
                            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_unpinned_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }

            // 14. Properties
            if (count > 0) {
                list.add(FileToolsBottomSheet.ActionItem("properties", getString(R.string.action_properties), R.drawable.ic_about, "toolbar_properties") {
                    val sheet = FilePropertiesBottomSheet.newInstanceForNetworkFiles(selected, currentPath)
                    sheet.show(parentFragmentManager, FilePropertiesBottomSheet.TAG)
                })
            }

            // 15. Tag
            val isMultiFileOnly = selected.size > 1 && selected.all { !it.isDirectory }
            val prefs = requireContext().getSharedPreferences("ufm_prefs", android.content.Context.MODE_PRIVATE)
            val isMultiTaggingEnabledPref = prefs.getBoolean("pref_multi_file_tagging", false)
            if (isMultiTaggingEnabledPref && isMultiFileOnly) {
                list.add(FileToolsBottomSheet.ActionItem("tag", getString(R.string.action_tag), R.drawable.ic_edit, "toolbar_tag") {
                    val filePaths = selected.map { it.path }
                    FileTagsManager.showMultiFileTagDialog(requireContext(), filePaths) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory()
                    }
                })
            }

            // Retrigger Thumbnails
            val hasVideoOrFolder = selected.isNotEmpty() && selected.any {
                it.isDirectory || it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS
            }
            if (hasVideoOrFolder && pm.isIconEnabled(context, pm.KEY_RETRIGGER_THUMBNAILS)) {
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
                            context?.let { ctx ->
                                android.widget.Toast.makeText(ctx, getString(R.string.retrigger_thumbnails_success), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            }

            // Checksum Tools
            if (count > 0 && selected.any { !it.isDirectory } && pm.isIconEnabled(context, pm.KEY_CHECKSUM)) {
                list.add(FileToolsBottomSheet.ActionItem("checksum", getString(R.string.action_checksum), R.drawable.ic_checksum, "toolbar_checksum") {
                    val files = selected.filter { !it.isDirectory }
                    if (files.isNotEmpty()) {
                        fileAdapter.exitSelectionMode()
                        val sources = files.map { za.kilowatch.ultimatefilemanager.checksum.NetworkFileSource(share, it) }
                        za.kilowatch.ultimatefilemanager.checksum.ChecksumDialogFragment.newInstance(sources)
                            .show(parentFragmentManager, za.kilowatch.ultimatefilemanager.checksum.ChecksumDialogFragment.TAG)
                    }
                })
            }

            // Compare Files
            if (count == 2 && selected.all { !it.isDirectory } && pm.isIconEnabled(context, pm.KEY_CHECKSUM)) {
                list.add(FileToolsBottomSheet.ActionItem("compare_files", getString(R.string.action_compare_files), R.drawable.ic_checksum, "toolbar_checksum") {
                    val files = selected.filter { !it.isDirectory }
                    fileAdapter.exitSelectionMode()
                    val sources = files.map { za.kilowatch.ultimatefilemanager.checksum.NetworkFileSource(share, it) }
                    za.kilowatch.ultimatefilemanager.checksum.FileCompareDialogFragment.newInstance(sources[0], sources[1])
                        .show(parentFragmentManager, za.kilowatch.ultimatefilemanager.checksum.FileCompareDialogFragment.TAG)
                })
            }
            if (isTwinWindow && count == 1 && !selected.first().isDirectory && pm.isIconEnabled(context, pm.KEY_CHECKSUM)) {
                list.add(FileToolsBottomSheet.ActionItem("compare_twin", getString(R.string.action_compare_files), R.drawable.ic_checksum, "toolbar_checksum") {
                    fileAdapter.exitSelectionMode()
                    onActionRequested?.invoke("compare")
                })
            }

            val displayList = pm.filterItemsForBottomSheet(context, list)
            if (displayList.isNotEmpty()) {
                val title = getString(R.string.action_tools)
                val subtitle = getString(R.string.selection_count, selected.size)
                val sheet = FileToolsBottomSheet.newInstance(displayList, title, subtitle)
                sheet.show(parentFragmentManager, FileToolsBottomSheet.TAG)
            }
        }
        
        btnSearchToggle = view.findViewById(R.id.btnSearchToggle)
        btnSearchToggle?.setImageResource(R.drawable.ic_search)
        if (isTv) {
            btnSearchToggle?.imageTintList = android.content.res.ColorStateList.valueOf(ColorblindPalette.denied(requireContext()))
        }
        layoutSearchRow = view.findViewById(R.id.layoutSearchRow)
        edtSearch = view.findViewById(R.id.edtSearch)
        btnSearchClear = view.findViewById(R.id.btnSearchClear)

        val badgeStorage = view.findViewById<TextView>(R.id.badgeStorageType)
        if (badgeStorage != null && !isTv) {
            badgeStorage.visibility = View.VISIBLE
            badgeStorage.text = when (share.type) {
                ShareType.SMB -> "SMB"
                ShareType.SFTP -> "SFTP"
                ShareType.FTP -> "FTP"
                ShareType.NFS -> "NFS"
                ShareType.GOOGLE_DRIVE -> "GDRIVE"
                ShareType.ONEDRIVE -> "ONEDRIVE"
                ShareType.DROPBOX -> "DROPBOX"
                ShareType.AWS_S3 -> "S3"
                ShareType.WEBDAV -> "WEBDAV"
                ShareType.DLNA -> "DLNA"
                ShareType.SCP -> "SCP"
                ShareType.IDRIVE_E2 -> "E2"
                ShareType.TV -> "TV"
            }
        }

        view.findViewById<View>(R.id.btnBack)?.setOnClickListener { navigateUp() }
        view.findViewById<View>(R.id.btnRefresh)?.setOnClickListener {
            // An explicit refresh must bypass the RClone directory-list cache.
            za.kilowatch.ultimatefilemanager.network.RCloneShareClient.clearDirListCache()
            loadDirectory()
        }
        view.findViewById<View>(R.id.btnCreateNew)?.setOnClickListener { showCreateNewMenu() }
        view.findViewById<View>(R.id.btnDrivePicker)?.setOnClickListener { onStoragePickerRequested?.invoke() }

        // Close Twin Window button: visible only in twin window mode
        val btnCloseTwin = view.findViewById<ImageView>(R.id.btnCloseTwin)
        if (btnCloseTwin != null) {
            btnCloseTwin.visibility = if (isTwinWindow) View.VISIBLE else View.GONE
            btnCloseTwin.setOnClickListener { onCloseTwinWindow?.invoke() }
        }
        
        btnOptionsToggle = view.findViewById(R.id.btnOptionsToggle)
        layoutOptionsRow = view.findViewById(R.id.layoutOptionsRow)

        btnOptionsToggle?.visibility = View.GONE
        layoutOptionsRow?.visibility = View.GONE
        
        btnSearchToggle?.setOnClickListener { toggleSearch() }
        btnSearchClear?.setOnClickListener { edtSearch?.setText("") }
        
        edtSearch?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""
                btnSearchClear?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(500)
                    // Debounce the keystrokes, then run the (internally offloaded)
                    // search directly — doSearchInternal does NOT manage searchJob,
                    // so there is no self-cancellation of this debounce coroutine.
                    doSearchInternal(query, currentFiles)
                }
            }
        })
        
        edtSearch?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = edtSearch?.text?.toString()?.trim() ?: ""
                performSearch(query)
                true
            } else false
        }
        
        // Pills are handled below for Twin Window mode

        if (!isTv) {
            view.findViewById<View>(R.id.btnViewToggle)?.setOnClickListener {
                ViewModeManager.showSelectionDialog(requireContext(), fileAdapter.viewMode) { selectedMode ->
                    val folderKey = SortFilterPreferenceManager.folderKey(share.id, currentPath)
                    if (SortFilterPreferenceManager.hasFolderOverride(requireContext(), currentPath, share.id)) {
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val state = SortFilterPreferenceManager.loadForPath(requireContext(), currentPath, share.id)
                            if (state != null) {
                                SortFilterPreferenceManager.saveFolderSpecific(
                                    requireContext(), folderKey, "${if (share.name.isNotEmpty()) share.name else share.host}:$currentPath",
                                    state.copy(viewMode = selectedMode), isNetwork = true
                                )
                            }
                        }
                    } else {
                        ViewModeManager.save(requireContext(), selectedMode)
                    }
                    applyViewMode(selectedMode)
                }
            }
        }
        view.findViewById<View>(R.id.btnSort)?.setOnClickListener { showSortFilterSheet() }

        view.findViewById<View>(R.id.btnCloseSelection)?.setOnClickListener { fileAdapter.exitSelectionMode() }
        view.findViewById<View>(R.id.btnSelectAll)?.setOnClickListener {
            if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
        }
        view.findViewById<View>(R.id.btnCopy)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                if (isTwinWindow) {
                    onActionRequested?.invoke("copy")
                } else {
                    (activity as? NetworkOperationsListener)?.onNetworkCopyRequested(this, selected)
                }
                fileAdapter.exitSelectionMode()
            }
        }
        view.findViewById<View>(R.id.btnMove)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                if (isTwinWindow) {
                    onActionRequested?.invoke("move")
                } else {
                    (activity as? NetworkOperationsListener)?.onNetworkMoveRequested(this, selected)
                }
                fileAdapter.exitSelectionMode()
            }
        }
        view.findViewById<View>(R.id.btnDelete)?.setOnClickListener { showDeleteConfirmation() }
        view.findViewById<View>(R.id.btnProtect)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(requireContext(), file.path, share.id, protected = true)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.deselectAll()
                        loadDirectory()
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_protected_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        view.findViewById<View>(R.id.btnUnprotect)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(requireContext(), file.path, share.id, protected = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.deselectAll()
                        loadDirectory()
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_unprotected_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        view.findViewById<View>(R.id.btnPin)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(requireContext(), file.path, share.id, pinned = true)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.deselectAll()
                        loadDirectory()
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_pinned_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        view.findViewById<View>(R.id.btnUnpin)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(requireContext(), file.path, share.id, pinned = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.deselectAll()
                        loadDirectory()
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_unpinned_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        view.findViewById<View>(R.id.btnChecksum)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles().filter { !it.isDirectory }
            if (selected.isNotEmpty()) {
                fileAdapter.exitSelectionMode()
                val sources = selected.map { za.kilowatch.ultimatefilemanager.checksum.NetworkFileSource(share, it) }
                za.kilowatch.ultimatefilemanager.checksum.ChecksumDialogFragment.newInstance(sources)
                    .show(parentFragmentManager, za.kilowatch.ultimatefilemanager.checksum.ChecksumDialogFragment.TAG)
            }
        }
        view.findViewById<View>(R.id.btnRetriggerThumbnails)?.setOnClickListener {
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
                        context?.let { ctx ->
                            android.widget.Toast.makeText(ctx, getString(R.string.retrigger_thumbnails_success), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        view.findViewById<View>(R.id.btnRename)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isEmpty()) return@setOnClickListener
            if (selected.size == 1) {
                showRenameDialog(selected[0])
            } else {
                val items = selected.map { BatchRenameItem.fromNetworkFile(it, share) }
                if (isTv) {
                    val intent = android.content.Intent(requireContext(), BatchRenameTvActivity::class.java).apply {
                        putParcelableArrayListExtra("items", ArrayList(items))
                    }
                    batchRenameTvLauncher.launch(intent)
                } else {
                    val dialog = BatchRenameDialogFragment.newInstance(items)
                    dialog.setOnCompleteListener { _, _, renamedMap ->
                        onBatchRenameCompleted(renamedMap)
                    }
                    dialog.show(parentFragmentManager, BatchRenameDialogFragment.TAG)
                }
            }
        }

        fileAdapter = NetworkFileAdapter(
            isTv = isTv,
            initialShare = share,
            context = requireContext(),
            isCompact = isCompactMode,
            onItemClick = { file ->
                if (file.isDirectory) {
                    saveCurrentFolderScroll(targetChildPath = file.path)
                    val nextPath = if (share.type == ShareType.TV || share.type == ShareType.DLNA || share.type == ShareType.SFTP || share.type == ShareType.SCP) {
                        file.path
                    } else if (currentPath.isEmpty() || currentPath == "/") {
                        file.name
                    } else {
                        "${currentPath.trimEnd('/')}/${file.name}"
                    }
                    folderScrollStates.remove(nextPath)
                    currentPath = nextPath
                    loadDirectory()
                } else {
                    val ext = file.name.substringAfterLast(".").lowercase()
                    if (onMediaFileSelected != null && (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(ext) || za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(ext))) {
                        onMediaFileSelected!!(file)
                    } else if (onFileSelected != null) {
                        onFileSelected?.invoke(file)
                    } else {
                        openNetworkFile(file)
                    }
                }
            },
            onSelectionChanged = { count ->
                updateSelectionUI(count)
                onSelectionChanged?.invoke(fileAdapter.getSelectedFiles())
            }
        )

        if (isTwinWindow) {
            view.findViewById<View>(R.id.btnSort)?.visibility = View.GONE
            view.findViewById<View>(R.id.btnRefresh)?.visibility = View.GONE

            view.findViewById<View>(R.id.btnPillSelectAll)?.setOnClickListener {
                if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
            }
            view.findViewById<View>(R.id.btnPillCopy)?.setOnClickListener { onActionRequested?.invoke("copy") }
            view.findViewById<View>(R.id.btnPillMove)?.setOnClickListener { onActionRequested?.invoke("move") }
            view.findViewById<View>(R.id.btnPillDelete)?.setOnClickListener { showDeleteConfirmation() }
        } else {
            view.findViewById<View>(R.id.btnPillCopy)?.setOnClickListener {
                val selected = fileAdapter.getSelectedFiles()
                if (selected.isNotEmpty()) {
                    (activity as? NetworkOperationsListener)?.onNetworkCopyRequested(this, selected)
                    fileAdapter.exitSelectionMode()
                }
            }
            view.findViewById<View>(R.id.btnPillMove)?.setOnClickListener {
                val selected = fileAdapter.getSelectedFiles()
                if (selected.isNotEmpty()) {
                    (activity as? NetworkOperationsListener)?.onNetworkMoveRequested(this, selected)
                    fileAdapter.exitSelectionMode()
                }
            }
            view.findViewById<View>(R.id.btnPillDelete)?.setOnClickListener { showDeleteConfirmation() }
        }

        val initialMode = ViewModeManager.load(requireContext())
        applyViewMode(initialMode)
        
        fileAdapter.isGroupedByDate = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(requireContext())
        
        // TV: intercept DPAD_CENTER long-press at the RecyclerView level (mirrors FileBrowserFragment).
        if (isTv) {
            val tvLongPressHandler = Handler(Looper.getMainLooper())
            var tvLongPressRunnable: Runnable? = null

            recyclerFiles.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (event.repeatCount == 0) {
                                val ctx = context ?: return@setOnKeyListener false
                                val durationMs = za.kilowatch.ultimatefilemanager.settings.LongPressDurationManager
                                    .loadDurationMs(ctx)
                                tvLongPressRunnable = Runnable {
                                    tvLongPressRunnable = null
                                    val focusedChild = recyclerFiles.focusedChild ?: return@Runnable
                                    val position = recyclerFiles.getChildAdapterPosition(focusedChild)
                                    if (position == RecyclerView.NO_ID.toInt()) return@Runnable
                                    focusedChild.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    fileAdapter.enterSelectionModeAt(position)
                                }
                                tvLongPressHandler.postDelayed(tvLongPressRunnable!!, durationMs)
                            }
                            false
                        }
                        KeyEvent.ACTION_UP -> {
                            if (tvLongPressRunnable != null) {
                                tvLongPressHandler.removeCallbacks(tvLongPressRunnable!!)
                                tvLongPressRunnable = null
                                false
                            } else {
                                true // consume to block follow-up click
                            }
                        }
                        else -> false
                    }
                } else false
            }
        }

        if (isTv) setupTvFocus(view)
        
        txtTitle?.text = share.name
        updateSubtitle()
    }

    fun loadDirectory() {
        val isTv = context?.packageManager?.hasSystemFeature("android.software.leanback") == true
        val ctx = context ?: return

        if (isTv && ::recyclerFiles.isInitialized) {
            val hadFocus = view?.hasFocus() == true || recyclerFiles.hasFocus()
            if (hadFocus) {
                shouldRestoreFocus = true
                recyclerFiles.isFocusable = true
                recyclerFiles.isFocusableInTouchMode = true
                recyclerFiles.requestFocus()
            }
        }

        za.kilowatch.ultimatefilemanager.util.GoRoLog.d("NetFragment", "[${hashCode()}] Loading directory: $currentPath")
        progressBar.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            val state = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.loadForPath(ctx, currentPath, share.id)
                ?: za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.loadGlobal(ctx)
            val hasFolderOverride = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.hasFolderOverride(ctx, currentPath, share.id)
            val viewModeToApply = state.viewMode ?: ViewModeManager.load(ctx)

            try {
                // Server-mode SMB: intercept at root to discover shares
                val files = kotlinx.coroutines.withTimeout(35_000L) {
                    if (share.type == ShareType.SMB && share.isServerMode) {
                        val cleanPath = currentPath.trimStart('/')
                        if (cleanPath.isEmpty()) {
                            if (share.remotePath != originalRemotePath) {
                                share = share.copy(remotePath = originalRemotePath)
                                withContext(Dispatchers.Main) {
                                    fileAdapter.share = share
                                }
                            }
                            discoverServerShares(share)
                        } else {
                            // Inside a discovered share: first segment is ALWAYS the SMB share name
                            val shareName = cleanPath.substringBefore('/')
                            val innerPath = if (cleanPath.contains('/')) cleanPath.substringAfter('/') else ""
                            val targetRemotePath = "/$shareName"
                            if (share.remotePath != targetRemotePath) {
                                share = share.copy(remotePath = targetRemotePath)
                                withContext(Dispatchers.Main) {
                                    fileAdapter.share = share
                                }
                            }
                            SmbShareClient.listFiles(share, innerPath).filter { it.name != ".." }
                        }
                    } else {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.listFiles(share, currentPath)
                            ShareType.FTP          -> FtpShareClient.listFiles(share, currentPath)
                            ShareType.TV           -> TvShareClient.listFiles(share, currentPath)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, currentPath)
                            ShareType.NFS          -> NfsShareClient.listFiles(share, currentPath)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.listFiles(share, currentPath)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, currentPath)
                            ShareType.DROPBOX      -> DropboxShareClient.listFiles(share, currentPath)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, currentPath)
                            ShareType.WEBDAV       -> WebDavShareClient.listFiles(share, currentPath)
                            ShareType.DLNA         -> DlnaShareClient.listFiles(share, currentPath)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    sortMode  = state.sortMode
                    sortOrder = state.sortOrder
                    filterType = state.filterType
                    currentDateFilter = state.dateFilter
                    currentSizeFilter = state.sizeFilter
                    activeTagsFilter = state.activeTags
                    updateSortBadge(hasFolderOverride)
                    if (fileAdapter.viewMode != viewModeToApply) {
                        applyViewMode(viewModeToApply)
                    }

                    progressBar.visibility = View.GONE
                    currentFiles = files
                    if (share.type == ShareType.SMB && share.isServerMode && currentPath.isEmpty() && files.isEmpty()) {
                        fileAdapter.submitList(emptyList())
                        layoutEmpty?.visibility = View.VISIBLE
                    } else {
                        performSearch(edtSearch?.text?.toString()?.trim() ?: "")
                    }
                    updateSubtitle()
                    onDirectoryChanged?.invoke(currentPath)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    val errMsg = if (e is kotlinx.coroutines.TimeoutCancellationException) {
                        if (share.type == ShareType.TV) {
                            getString(R.string.network_connection_restored_first)
                        } else {
                            getString(R.string.network_server_connection_failed)
                        }
                    } else {
                        getString(R.string.error_loading_emessage, e.message ?: "Unknown error")
                    }
                    showPremiumSnackbar(errMsg)
                }
            }
        }
    }

    private fun updateSubtitle() {
        val hostOrUser = if (share.username.isNotEmpty() && (share.type == ShareType.GOOGLE_DRIVE || share.type == ShareType.ONEDRIVE || share.type == ShareType.DROPBOX)) {
            share.username
        } else if (share.host.isNotEmpty()) {
            share.host
        } else {
            ""
        }
        val displayPath = if (share.type == ShareType.SMB && share.isServerMode && currentPath.isEmpty()) {
            getString(R.string.network_folder_shared_folders)
        } else if (isTwinWindow) {
            val rel = currentPath.removePrefix(share.docIdPrefix).removePrefix("/")
            if (rel.isEmpty()) "/" else "/$rel"
        } else {
            if (currentPath.isEmpty()) "/" else currentPath
        }
        txtSubtitle?.text = if (hostOrUser.isNotEmpty() && !isTwinWindow) "$hostOrUser · $displayPath" else displayPath
    }

    /**
     * Discovers all accessible shares on an SMB server.
     * Throws if the server is unreachable or authentication fails.
     * Returns an empty list if the server is reachable but has no accessible shares.
     */
    private fun discoverServerShares(server: NetworkShare): List<NetworkFile> {
        return SmbDiscovery.listAccessibleShares(
            server.host, server.username, server.password, server.domain
        ).map { shareName ->
            NetworkFile(
                name = shareName,
                path = "/$shareName",
                isDirectory = true
            )
        }
    }

    private fun handleQuickActionClick(actionId: String) {
        val selected = fileAdapter.getSelectedFiles()
        val count = selected.size
        val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager

        when (actionId) {
            pm.ACTION_DELETE -> {
                if (!share.readOnly) {
                    if (isTwinWindow) {
                        onActionRequested?.invoke("delete")
                    } else {
                        showDeleteConfirmation()
                    }
                    fileAdapter.exitSelectionMode()
                }
            }
            pm.ACTION_COPY -> {
                if (isTwinWindow) {
                    onActionRequested?.invoke("copy")
                } else {
                    val handled = (activity as? NetworkOperationsListener)?.let {
                        it.onNetworkCopyRequested(this, selected)
                        true
                    } ?: false
                    if (!handled) {
                        (activity as? NetworkBrowserActivity)?.handleNetworkCopyOrCut(selected, isMove = false)
                    }
                }
                fileAdapter.exitSelectionMode()
            }
            pm.ACTION_MOVE -> {
                if (!share.readOnly) {
                    if (isTwinWindow) {
                        onActionRequested?.invoke("move")
                    } else {
                        val handled = (activity as? NetworkOperationsListener)?.let {
                            it.onNetworkMoveRequested(this, selected)
                            true
                        } ?: false
                        if (!handled) {
                            (activity as? NetworkBrowserActivity)?.handleNetworkCopyOrCut(selected, isMove = true)
                        }
                    }
                    fileAdapter.exitSelectionMode()
                }
            }
            pm.ACTION_RENAME -> {
                if (selected.size == 1) {
                    showRenameDialog(selected.first())
                } else if (selected.size > 1) {
                    val items = selected.map { za.kilowatch.ultimatefilemanager.storage.BatchRenameItem.fromNetworkFile(it, share) }
                    if (DeviceUtils.isTvDevice(requireContext())) {
                        val intent = android.content.Intent(requireContext(), BatchRenameTvActivity::class.java).apply {
                            putParcelableArrayListExtra("items", java.util.ArrayList(items))
                        }
                        batchRenameTvLauncher.launch(intent)
                    } else {
                        val dialog = za.kilowatch.ultimatefilemanager.storage.BatchRenameDialogFragment.newInstance(items)
                        dialog.setOnCompleteListener { _, _, renamedMap -> onBatchRenameCompleted(renamedMap) }
                        dialog.show(parentFragmentManager, za.kilowatch.ultimatefilemanager.storage.BatchRenameDialogFragment.TAG)
                    }
                }
            }
            pm.ACTION_SHARE -> {
                val shareable = selected.filter { !it.isDirectory }
                if (shareable.isNotEmpty()) {
                    shareNetworkFiles(shareable)
                }
            }
            pm.ACTION_OPEN_WITH -> {
                if (selected.size == 1 && !selected.first().isDirectory) {
                    cacheNetworkFile(selected.first()) { localFile ->
                        FileViewerRouter.showOpenWithDialog(requireActivity(), localFile, isNetwork = true)
                    }
                }
            }
            pm.ACTION_OPEN_AS -> {
                if (selected.size == 1 && !selected.first().isDirectory) {
                    cacheNetworkFile(selected.first()) { localFile ->
                        OpenAsBottomSheet.newInstance(localFile.absolutePath, selected.first().name, true)
                            .show(parentFragmentManager, OpenAsBottomSheet.TAG)
                    }
                }
            }
            pm.ACTION_EXTRACT_SUBTITLES -> {
                if (selected.size == 1 && !selected.first().isDirectory) {
                    extractSubtitlesFromNetworkVideo(selected.first())
                }
            }
            pm.ACTION_EXTRACT_AUDIO -> {
                if (selected.size == 1 && !selected.first().isDirectory) {
                    extractAudioFromNetworkVideo(selected.first())
                }
            }
            pm.ACTION_CONVERT_TO_MP4 -> {
                if (selected.size == 1 && !selected.first().isDirectory) {
                    convertNetworkVideoToMp4(selected.first())
                }
            }
            pm.ACTION_COMPRESS -> {
                showArchiveOptions(selected)
            }
            pm.ACTION_EXTRACT -> {
                val archives = selected.filter { ArchiveManager.isSupportedArchiveExtension(it.name.substringAfterLast('.')) }
                if (archives.isNotEmpty()) {
                    performNetworkExtractHere(archives)
                }
            }
            pm.ACTION_FAVORITE -> {
                if (count == 1) {
                    showFavoriteDialog(selected.first())
                }
            }
            pm.ACTION_SELECT_ALL -> {
                if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
            }
            pm.ACTION_INVERT_SELECTION -> fileAdapter.invertSelection()
            pm.ACTION_CHECKSUM -> {
                val files = selected.filter { !it.isDirectory }
                if (files.isNotEmpty()) {
                    fileAdapter.exitSelectionMode()
                    val sources = files.map { za.kilowatch.ultimatefilemanager.checksum.NetworkFileSource(share, it) }
                    za.kilowatch.ultimatefilemanager.checksum.ChecksumDialogFragment.newInstance(sources)
                        .show(parentFragmentManager, za.kilowatch.ultimatefilemanager.checksum.ChecksumDialogFragment.TAG)
                }
            }
            pm.ACTION_MUSIC_TAGGER -> {
                val audioFiles = selected.filter {
                    !it.isDirectory && za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(it.name.substringAfterLast('.'))
                }
                if (audioFiles.isNotEmpty()) {
                    fileAdapter.exitSelectionMode()
                    downloadNetworkAudioAndLaunchTagger(audioFiles)
                }
            }
            pm.ACTION_COPY_ENCRYPT -> {
                val encryptable = selected.filter { !it.isDirectory }
                if (encryptable.isNotEmpty()) {
                    showNetworkVaultPicker(encryptable, isMove = false)
                }
            }
            pm.ACTION_MOVE_ENCRYPT -> {
                val encryptable = selected.filter { !it.isDirectory }
                if (encryptable.isNotEmpty()) {
                    showNetworkVaultPicker(encryptable, isMove = true)
                }
            }
            pm.ACTION_IMAGE_COMPRESS -> {
                val netImages = selected.filter {
                    !it.isDirectory && it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                }
                if (netImages.isNotEmpty()) {
                    downloadNetworkImagesAndCompress(netImages)
                }
            }
            pm.ACTION_CREATE_GIF -> {
                val netImages = selected.filter {
                    !it.isDirectory && it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                }
                if (netImages.size >= 2) {
                    downloadNetworkImagesAndCreateGif(netImages)
                }
            }
            pm.ACTION_EXIF_TOOLS -> {
                val netImages = selected.filter {
                    !it.isDirectory && it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                }
                if (netImages.isNotEmpty() && !DeviceUtils.isTvDevice(requireContext())) {
                    downloadNetworkImagesAndLaunchExifTools(netImages)
                }
            }
            pm.ACTION_SET_HOME_WALLPAPER -> {
                if (count == 1 && !selected.first().isDirectory && !DeviceUtils.isTvDevice(requireContext())) {
                    setNetworkWallpaper(selected.first(), android.app.WallpaperManager.FLAG_SYSTEM)
                }
            }
            pm.ACTION_SET_LOCK_WALLPAPER -> {
                if (count == 1 && !selected.first().isDirectory && !DeviceUtils.isTvDevice(requireContext())) {
                    setNetworkWallpaper(selected.first(), android.app.WallpaperManager.FLAG_LOCK)
                }
            }
            pm.ACTION_SET_RINGTONE -> {
                if (count == 1 && !selected.first().isDirectory && !DeviceUtils.isTvDevice(requireContext())) {
                    setNetworkSystemSound(selected.first(), android.media.RingtoneManager.TYPE_RINGTONE)
                }
            }
            pm.ACTION_SET_NOTIFICATION -> {
                if (count == 1 && !selected.first().isDirectory && !DeviceUtils.isTvDevice(requireContext())) {
                    setNetworkSystemSound(selected.first(), android.media.RingtoneManager.TYPE_NOTIFICATION)
                }
            }
            pm.ACTION_SET_ALARM -> {
                if (count == 1 && !selected.first().isDirectory && !DeviceUtils.isTvDevice(requireContext())) {
                    setNetworkSystemSound(selected.first(), android.media.RingtoneManager.TYPE_ALARM)
                }
            }
            "protect", pm.ACTION_PROTECT_UNPROTECT -> {
                val ctx = context ?: return
                val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(ctx, share.id)
                val targetProtect = hasUnprotected
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(ctx, file.path, share.id, protected = targetProtect)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory()
                        val msg = if (targetProtect) getString(R.string.toast_protected_success, selected.size) else getString(R.string.toast_unprotected_success, selected.size)
                        showPremiumSnackbar(msg)
                    }
                }
            }
            "unprotect" -> {
                val ctx = context ?: return
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(ctx, file.path, share.id, protected = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory()
                        showPremiumSnackbar(getString(R.string.toast_unprotected_success, selected.size))
                    }
                }
            }
            "pin", pm.ACTION_PIN_UNPIN -> {
                val ctx = context ?: return
                val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(ctx, share.id)
                val targetPin = hasUnpinned
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(ctx, file.path, share.id, pinned = targetPin)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory()
                        val msg = if (targetPin) getString(R.string.toast_pinned_success, selected.size) else getString(R.string.toast_unpinned_success, selected.size)
                        showPremiumSnackbar(msg)
                    }
                }
            }
            "unpin" -> {
                val ctx = context ?: return
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(ctx, file.path, share.id, pinned = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory()
                        showPremiumSnackbar(getString(R.string.toast_unpinned_success, selected.size))
                    }
                }
            }
            pm.ACTION_MORE -> {
                fabTools?.performClick()
            }
        }
    }

    private fun updateSelectionUI(count: Int) {
        val showSelection = fileAdapter.isSelectionMode
        val isTv = DeviceUtils.isTvDevice(requireContext())
        (activity as? NetworkOperationsListener)?.onNetworkSelectionChanged(this, showSelection, count, fileAdapter.isAllSelected())
        if (!isTv) {
            val layoutHeaderNormal = view?.findViewById<View>(R.id.layoutHeaderNormal)
            val layoutHeaderSelection = view?.findViewById<View>(R.id.layoutHeaderSelection)
            val btnSelectAll = view?.findViewById<View>(R.id.btnSelectAll)
            val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
            val isQuickBarOn = pm.isQuickBarEnabled(requireContext())

            if (showSelection) {
                val showActions = count > 0
                layoutHeaderNormal?.visibility = View.GONE
                layoutHeaderSelection?.visibility = View.VISIBLE
                layoutSelectionBar?.visibility = View.GONE
                view?.findViewById<View>(R.id.layoutActionPillsScroll)?.visibility = View.GONE
                view?.findViewById<View>(R.id.layoutActionPills)?.visibility = View.GONE
                txtSelectionCount?.text = if (count == 0) getString(R.string.selection_prompt_select_item) else getString(R.string.selection_count, count)

                val isAll = fileAdapter.isAllSelected()
                if (btnSelectAll is ImageView) {
                    btnSelectAll.setImageResource(if (isAll) R.drawable.ic_deselect_all else R.drawable.ic_select_all)
                    btnSelectAll.contentDescription = getString(if (isAll) R.string.action_deselect_all else R.string.action_select_all)
                } else if (btnSelectAll is MaterialButton) {
                    btnSelectAll.text = if (isAll) getString(R.string.action_deselect_all) else getString(R.string.action_select_all)
                }

                if (isQuickBarOn && showActions) {
                    val netFiles = fileAdapter.getSelectedFiles()
                    val state = za.kilowatch.ultimatefilemanager.ui.FloatingQuickActionBar.SelectionState(
                        selectedCount = count,
                        isAllSelected = isAll,
                        hasProtected = fileAdapter.hasAnySelectedProtected(requireContext(), share.id),
                        hasUnprotected = fileAdapter.hasAnySelectedUnprotected(requireContext(), share.id),
                        hasPinned = fileAdapter.hasAnySelectedPinned(requireContext(), share.id),
                        hasUnpinned = fileAdapter.hasAnySelectedUnpinned(requireContext(), share.id),
                        hasArchiveSelected = netFiles.isNotEmpty() && netFiles.any {
                            ArchiveManager.isSupportedArchiveExtension(it.name.substringAfterLast('.'))
                        },
                        allImagesSelected = netFiles.isNotEmpty() && netFiles.all {
                            !it.isDirectory && it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                        },
                        allAudioSelected = netFiles.isNotEmpty() && netFiles.all {
                            !it.isDirectory && za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(it.name.substringAfterLast('.'))
                        },
                        allVideosSelected = netFiles.isNotEmpty() && netFiles.all {
                            !it.isDirectory && za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(it.name.substringAfterLast('.'))
                        },
                        hasDirectories = netFiles.any { it.isDirectory }
                    )
                    floatingQuickBar?.bindSelection(state)
                    floatingQuickBar?.showAnimated { updateFabPositions() }
                    fabTools?.visibility = View.GONE
                } else {
                    floatingQuickBar?.hideAnimated { updateFabPositions() }
                    fabTools?.visibility = if (showActions) View.VISIBLE else View.GONE
                }

                fabProperties?.visibility = View.GONE
                fabSelectAll?.visibility = View.GONE
                updatePasteFab()
            } else {
                layoutHeaderNormal?.visibility = View.VISIBLE
                layoutHeaderSelection?.visibility = View.GONE
                layoutSelectionBar?.visibility = View.GONE
                view?.findViewById<View>(R.id.layoutActionPillsScroll)?.visibility = View.GONE
                view?.findViewById<View>(R.id.layoutActionPills)?.visibility = View.GONE
                fabProperties?.visibility = View.GONE
                fabTools?.visibility = View.GONE
                fabSelectAll?.visibility = View.GONE
                floatingQuickBar?.hideAnimated { updateFabPositions() }
                updatePasteFab()
            }
            return
        }

        if (showSelection) {
            val showActions = count > 0
            val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
            val context = context ?: return
            if (isTwinWindow) {
                view?.findViewById<View>(R.id.layoutActionPillsScroll)?.visibility = if (showActions) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.layoutActionPills)?.visibility = if (showActions) View.VISIBLE else View.GONE
                fabSelectAll?.visibility = View.GONE
                view?.findViewById<View>(R.id.btnPillCopy)?.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_COPY)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnPillMove)?.visibility = if (showActions && !share.readOnly && pm.isIconEnabled(context, pm.KEY_MOVE)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnPillSelectAll)?.visibility = View.GONE
                view?.findViewById<View>(R.id.btnPillDelete)?.visibility = if (showActions && !share.readOnly && pm.isIconEnabled(context, pm.KEY_DELETE)) View.VISIBLE else View.GONE
                fabTools?.visibility = View.GONE
            } else {
                fabSelectAll?.visibility = View.GONE
                layoutSelectionBar?.visibility = View.VISIBLE
                view?.findViewById<View>(R.id.layoutActionPillsScroll)?.visibility = View.GONE
                view?.findViewById<View>(R.id.layoutActionPills)?.visibility = View.GONE
                
                val btnCopyView = view?.findViewById<View>(R.id.btnCopy)
                val row2 = btnCopyView?.parent?.parent as? View

                fabTools?.visibility = View.GONE
                if (showActions) {
                    (layoutSelectionBar as? ViewGroup)?.let { za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.stopAnimation(it) }
                    row2?.visibility = View.VISIBLE
                } else {
                    row2?.visibility = View.GONE
                    (layoutSelectionBar as? ViewGroup)?.let { za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.startAnimation(it) }
                }
                
                // TV-only icon/row visibility
                view?.findViewById<View>(R.id.btnDelete)?.visibility = if (showActions && !share.readOnly && pm.isIconEnabled(context, pm.KEY_DELETE)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnCopy)?.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_COPY)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnMove)?.visibility = if (showActions && !share.readOnly && pm.isIconEnabled(context, pm.KEY_MOVE)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnRename)?.visibility = if (count >= 1 && !share.readOnly && pm.isIconEnabled(context, pm.KEY_RENAME)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnShare)?.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_SHARE)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnCopyEncrypt)?.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_COPY_ENCRYPT)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnMoveEncrypt)?.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_MOVE_ENCRYPT)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnCompress)?.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_COMPRESS)) View.VISIBLE else View.GONE
                val netFiles = fileAdapter.getSelectedFiles()
                val allImages = netFiles.isNotEmpty() && netFiles.all {
                    it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                }
                view?.findViewById<View>(R.id.btnImageCompress)?.visibility = if (showActions && allImages && pm.isIconEnabled(context, pm.KEY_IMAGE_COMPRESS)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnFavorite)?.visibility = if (count == 1 && pm.isIconEnabled(context, pm.KEY_FAVORITE)) View.VISIBLE else View.GONE
                val hasProtected = fileAdapter.hasAnySelectedProtected(context, share.id)
                val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(context, share.id)
                val hasPinned = fileAdapter.hasAnySelectedPinned(context, share.id)
                val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(context, share.id)
                view?.findViewById<View>(R.id.btnProtect)?.visibility = if (showActions && hasUnprotected && pm.isIconEnabled(context, pm.KEY_PROTECT)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnUnprotect)?.visibility = if (showActions && hasProtected && pm.isIconEnabled(context, pm.KEY_UNPROTECT)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnPin)?.visibility = if (showActions && hasUnpinned && pm.isIconEnabled(context, pm.KEY_PIN)) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.btnUnpin)?.visibility = if (showActions && hasPinned && pm.isIconEnabled(context, pm.KEY_UNPIN)) View.VISIBLE else View.GONE
                val selectedFiles = fileAdapter.getSelectedFiles()
                view?.findViewById<View>(R.id.btnChecksum)?.visibility = if (showActions && selectedFiles.any { !it.isDirectory } && pm.isIconEnabled(context, pm.KEY_CHECKSUM)) View.VISIBLE else View.GONE
                val isSingleFile = selectedFiles.size == 1 && !selectedFiles.first().isDirectory
                
                val prefs = requireContext().getSharedPreferences("ufm_prefs", android.content.Context.MODE_PRIVATE)
                val isMultiTaggingEnabled = prefs.getBoolean("pref_multi_file_tagging", false)
                val isMultiFileOnly = selectedFiles.size > 1 && selectedFiles.all { !it.isDirectory }
                
                fabProperties?.visibility = View.GONE
                updatePasteFab()
            }
            txtSelectionCount?.text = if (count == 0) getString(R.string.selection_prompt_select_item) else getString(R.string.selection_count, count)
        } else {
            layoutSelectionBar?.visibility = View.GONE
            view?.findViewById<View>(R.id.layoutActionPillsScroll)?.visibility = View.GONE
            view?.findViewById<View>(R.id.layoutActionPills)?.visibility = View.GONE
            (layoutSelectionBar as? ViewGroup)?.let { za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.stopAnimation(it) }
            fabProperties?.visibility = View.GONE
            fabTools?.visibility = View.GONE
            fabSelectAll?.visibility = View.GONE
            updatePasteFab()
        }
    }

    private fun navigateUp() {
        if (!handleBackPress()) {
            // In full-screen NetworkBrowserActivity, back at root closes the screen.
            // In TwinWindow, handleBackPress() returns false and the activity decides.
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    fun handleBackPress(): Boolean {
        if (fileAdapter.isSelectionMode) {
            fileAdapter.exitSelectionMode()
            return true
        }
        if (currentPath.isEmpty() || currentPath == "/") {
            // At share root — go back to server root (discovered shares)
            if (share.isServerMode && share.remotePath.isNotEmpty()) {
                share = share.copy(remotePath = originalRemotePath)
                fileAdapter.share = share
                loadDirectory()
                return true
            }
            return false
        }

        val clean = currentPath.trimStart('/')
        val lastSlash = clean.lastIndexOf('/')
        lastExitedPath = currentPath
        currentPath = if (lastSlash <= 0) {
            // Reset share path when returning to server root in server-mode SMB
            if (share.isServerMode) {
                share = share.copy(remotePath = originalRemotePath)
                fileAdapter.share = share
            }
            ""
        } else {
            clean.substring(0, lastSlash)
        }
        loadDirectory()
        return true
    }

    private fun showPremiumSnackbar(message: String) {
        Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show()
    }

    // --- Operations (simplified for Fragment, mostly calls Activity or performs IO) ---

    private fun showCreateNewMenu() {
        val ctx = requireContext()
        val isOnTv = DeviceUtils.isTvDevice(ctx)
        val dialogView = LayoutInflater.from(ctx).inflate(
            if (isOnTv) R.layout.dialog_create_new_options_tv else R.layout.dialog_create_new_options,
            null
        )

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
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
            val widthPx = (800 * ctx.resources.displayMetrics.density).toInt()
            val screenWidth = ctx.resources.displayMetrics.widthPixels
            val finalWidth = minOf(widthPx, (screenWidth * 0.85).toInt())
            dialog.window?.setLayout(finalWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    fun showCreateTextFileDialog() {
        val ctx = requireContext()
        val isOnTv = DeviceUtils.isTvDevice(ctx)
        val layoutRes = if (isOnTv) R.layout.dialog_create_text_file_tv else R.layout.dialog_create_text_file
        val dialogView = LayoutInflater.from(ctx).inflate(layoutRes, null)
        val edtFileName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtFileName)
        edtFileName?.setText(getString(R.string.new_file_default))
        edtFileName?.selectAll()

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnCreate)?.setOnClickListener {
            val name = edtFileName?.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                showFragmentSnackbar(getString(R.string.new_file_empty))
            } else {
                dialog.dismiss()
                createNetworkTextFile(name)
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        edtFileName?.requestFocus()
    }

    /**
     * Strips the share-name prefix from [path] when in server-mode.
     * In server-mode, share.remotePath already encodes the share name (e.g. "/docker"),
     * so currentPath contains it as a leading segment (e.g. "/docker/_projects").
     * Passing the raw currentPath to SmbShareClient produces a duplicate segment.
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

    private fun createNetworkTextFile(filename: String) {
        val share = this.share
        val currentPath = this.currentPath
        val cleanPath = stripSharePrefix(currentPath.trimStart('/'))
        // Clear any stale network save bridge
        za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileSaved = null
        lifecycleScope.launch(Dispatchers.IO) {
            try {
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
                    ShareType.SMB -> SmbShareClient.openOutputStream(share, finalPath).use { }
                    ShareType.FTP -> FtpShareClient.openOutputStream(share, finalPath).use { }
                    ShareType.SFTP, ShareType.SCP -> withContext(Dispatchers.IO) {
                        SshShareClient.openOutputStream(share, finalPath).use { }
                    }
                    ShareType.TV -> TvShareClient.uploadStream(share, finalPath,
                        java.io.ByteArrayInputStream(ByteArray(0)), 0L)
                    ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(share, finalPath).use { }
                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(share, finalPath).use { }
                    ShareType.DROPBOX -> DropboxShareClient.openOutputStream(share, finalPath).use { }
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(share, finalPath).use { }
                    ShareType.WEBDAV -> WebDavShareClient.openOutputStream(share, finalPath).use { }
                    ShareType.NFS -> withContext(Dispatchers.IO) {
                        NfsShareClient.openOutputStream(share, finalPath).use { }
                    }
                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                }

                withContext(Dispatchers.Main) {
                    loadDirectory()
                    showFragmentSnackbar(getString(R.string.new_file_success))

                    // Download to cache and open in text viewer
                    withContext(Dispatchers.IO) {
                        val safeName = finalName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                        val cacheFile = java.io.File(requireContext().cacheDir, "ufm_open_$safeName")
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
                                FileOutputStream(cacheFile).use { out -> inp.copyTo(out) }
                            }
                            // Set the network save bridge so content is uploaded back on save
                            val capturedShare = share
                            val capturedFinalPath = finalPath
                            za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileSaved = { savedFile ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val fis = java.io.FileInputStream(savedFile)
                                        fis.use { inp ->
                                            when (capturedShare.type) {
                                                ShareType.SMB -> SmbShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> inp.copyTo(out) }
                                                ShareType.FTP -> FtpShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> inp.copyTo(out) }
                                                ShareType.SFTP, ShareType.SCP -> withContext(Dispatchers.IO) { SshShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> inp.copyTo(out) } }
                                                ShareType.TV -> TvShareClient.uploadStream(capturedShare, capturedFinalPath, inp, savedFile.length())
                                                ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> inp.copyTo(out) }
                                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> inp.copyTo(out) }
                                                ShareType.DROPBOX -> DropboxShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> inp.copyTo(out) }
                                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> inp.copyTo(out) }
                                                ShareType.WEBDAV -> WebDavShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> inp.copyTo(out) }
                                                ShareType.NFS -> withContext(Dispatchers.IO) { NfsShareClient.openOutputStream(capturedShare, capturedFinalPath).use { out -> inp.copyTo(out) } }
                                                ShareType.DLNA -> throw UnsupportedOperationException()
                                            }
                                        }
                                    } catch (_: Exception) { }
                                    // Keep the bridge alive for subsequent saves.
                                    // The existing stale-cache sweeper (30 min) cleans up cache files.
                                }
                            }
                            withContext(Dispatchers.Main) {
                                val intent = Intent(requireContext(), za.kilowatch.ultimatefilemanager.viewer.TextViewerActivity::class.java).apply {
                                    putExtra(FileViewerRouter.EXTRA_FILE_PATH, cacheFile.absolutePath)
                                    putExtra(FileViewerRouter.EXTRA_FILE_NAME, finalName)
                                    putExtra(FileViewerRouter.EXTRA_START_IN_EDIT_MODE, true)
                                }
                                startActivity(intent)
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) {
                                showFragmentSnackbar(getString(R.string.new_file_success))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showFragmentSnackbar(getString(R.string.new_file_error) + ": ${e.message}")
                }
            }
        }
    }

    private fun showFragmentSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT)
                .setBackgroundTint(requireContext().getColor(R.color.ufm_surface_variant))
                .setTextColor(requireContext().getColor(R.color.ufm_text_primary))
                .show()
        }
    }

    fun showCreateFolderDialog() {
        val ctx = requireContext()
        val isOnTv = DeviceUtils.isTvDevice(ctx)
        val layoutRes = if (isOnTv) R.layout.dialog_create_folder_tv else R.layout.dialog_create_folder
        val dialogView = LayoutInflater.from(ctx).inflate(layoutRes, null)
        val edtFolderName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtFolderName)
        edtFolderName?.setText(getString(R.string.new_menu_new_folder))
        edtFolderName?.selectAll()

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnCreate)?.setOnClickListener {
            val name = edtFolderName?.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                showFragmentSnackbar(getString(R.string.new_folder_empty))
            } else {
                dialog.dismiss()
                val cleanPath = stripSharePrefix(currentPath.trimStart('/'))
                val targetPath = if (cleanPath.isEmpty()) name else "$cleanPath/$name"
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.mkdir(share, targetPath)
                            ShareType.FTP          -> FtpShareClient.mkdir(share, targetPath)
                            ShareType.TV           -> TvShareClient.mkdir(share, targetPath)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, targetPath)
                            ShareType.NFS          -> NfsShareClient.mkdir(share, targetPath)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.mkdir(share, targetPath)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, targetPath)
                            ShareType.DROPBOX      -> DropboxShareClient.mkdir(share, targetPath)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, targetPath)
                            ShareType.WEBDAV       -> WebDavShareClient.mkdir(share, targetPath)
                            ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                        withContext(Dispatchers.Main) { loadDirectory() }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { showPremiumSnackbar(getString(R.string.error_emessage, e.message ?: "Unknown error")) }
                    }
                }
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        edtFolderName?.requestFocus()
    }

    private fun showRenameDialog(file: NetworkFile) {
        val ctx = requireContext()
        val isOnTv = DeviceUtils.isTvDevice(ctx)
        val layoutRes = if (isOnTv) R.layout.dialog_file_rename_tv else R.layout.dialog_file_rename
        val dialogView = LayoutInflater.from(ctx).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
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
                val cleanPath = stripSharePrefix(currentPath.trimStart('/'))
                val targetPath = if (cleanPath.isEmpty()) newName else "$cleanPath/$newName"
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.rename(share, file.path, targetPath)
                            ShareType.FTP          -> FtpShareClient.rename(share, file.path, targetPath)
                            ShareType.TV           -> TvShareClient.rename(share, file.path, targetPath)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(share, file.path, targetPath)
                            ShareType.NFS          -> NfsShareClient.rename(share, file.path, targetPath)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.rename(share, file.path, targetPath)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, file.path, targetPath)
                            ShareType.DROPBOX      -> DropboxShareClient.rename(share, file.path, targetPath)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, file.path, targetPath)
                            ShareType.WEBDAV       -> WebDavShareClient.rename(share, file.path, targetPath, file.isDirectory)
                            ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                        withContext(Dispatchers.Main) { 
                            fileAdapter.exitSelectionMode()
                            if (isSearchActive) {
                                currentFiles = currentFiles.map { nf ->
                                    if (nf.path == file.path) {
                                        nf.copy(name = newName, path = targetPath)
                                    } else nf
                                }
                                val query = edtSearch?.text?.toString()?.trim().orEmpty()
                                if (query.isNotEmpty()) {
                                    performSearch(query)
                                }
                            } else {
                                loadDirectory()
                            }
                            dialog.dismiss()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { showPremiumSnackbar(getString(R.string.error_emessage, e.message ?: "Unknown error")) }
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
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        editFileName?.requestFocus()
    }

    private fun showDeleteConfirmation() {
        val selected = fileAdapter.getSelectedFiles()
        if (selected.isEmpty()) return

        val hasProtected = selected.any {
            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isOrContainsProtected(requireContext(), it.path, share.id)
        }
        if (hasProtected) {
            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.showProtectedDeleteDialog(requireContext(), isTv)
            return
        }

        val isOnTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = if (isOnTv) R.layout.dialog_file_delete_confirm_tv else R.layout.dialog_file_delete_confirm
        val dialogView = LayoutInflater.from(requireContext()).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtTitle)
        val txtDeleteMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        val btnDeleteConfirm = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val folders = selected.count { it.isDirectory }
        val files = selected.count { !it.isDirectory }
        val message = when {
            folders > 0 && files > 0 -> getString(R.string.delete_message_mixed, folders, files)
            folders > 0 -> getString(R.string.delete_message_folders, folders)
            else -> getString(R.string.delete_message_files, files)
        }

        txtTitle?.text = getString(R.string.delete_title)
        txtDeleteMessage?.text = message
        btnDeleteConfirm?.text = getString(R.string.delete_confirm)
        btnDeleteConfirm?.setOnClickListener {
            dialog.dismiss()
            progressBar.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.IO) {
                for (f in selected) {
                    try {
                        when (share.type) {
                            ShareType.SMB          -> if (f.isDirectory) SmbShareClient.deleteDir(share, f.path) else SmbShareClient.deleteFile(share, f.path)
                            ShareType.FTP          -> if (f.isDirectory) FtpShareClient.deleteDir(share, f.path) else FtpShareClient.deleteFile(share, f.path)
                            ShareType.TV           -> if (f.isDirectory) TvShareClient.deleteDir(share, f.path) else TvShareClient.deleteFile(share, f.path)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, f.path, false)
                            ShareType.NFS          -> if (f.isDirectory) NfsShareClient.deleteDir(share, f.path) else NfsShareClient.deleteFile(share, f.path)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.deleteFile(share, f.path)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, f.path)
                            ShareType.DROPBOX      -> DropboxShareClient.deleteFile(share, f.path)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, f.path)
                            ShareType.WEBDAV       -> if (f.isDirectory) WebDavShareClient.deleteDir(share, f.path) else WebDavShareClient.deleteFile(share, f.path)
                            ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                    } catch (_: Exception) {}
                }
                withContext(Dispatchers.Main) {
                    fileAdapter.exitSelectionMode()
                    loadDirectory()
                }
            }
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    fun getSelectedFiles(): List<NetworkFile> = fileAdapter.getSelectedFiles()
    fun exitSelectionMode() = fileAdapter.exitSelectionMode()
    fun getCurrentPath(): String = currentPath
    fun getShare(): NetworkShare = share
    fun getCurrentFiles(): List<NetworkFile> = currentFiles
    fun getSortedFiles(): List<NetworkFile> = sortAndFilterFiles(currentFiles)
    fun openNetworkFile(file: NetworkFile) {
        if (!::share.isInitialized) return
        val act = activity ?: return
        val scope = try { viewLifecycleOwner.lifecycleScope } catch (_: Exception) { lifecycleScope }
        NetworkFileOpener.openFile(
            activity = act,
            scope = scope,
            share = share,
            file = file,
            currentFiles = currentFiles,
            sortedFiles = getSortedFiles(),
            snackAnchorView = view,
            onShowSnackbar = { showFragmentSnackbar(it) }
        )
    }
    fun navigateTo(path: String) {
        saveCurrentFolderScroll()
        currentPath = path
        loadDirectory()
    }
    fun search(query: String) {
        performSearch(query)
    }
    fun openSortFilterSheet() {
        showSortFilterSheet()
    }
    fun openViewModeDialog() {
        ViewModeManager.showSelectionDialog(requireContext(), fileAdapter.viewMode) { selectedMode ->
            val folderKey = SortFilterPreferenceManager.folderKey(share.id, currentPath)
            if (SortFilterPreferenceManager.hasFolderOverride(requireContext(), currentPath, share.id)) {
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val state = SortFilterPreferenceManager.loadForPath(requireContext(), currentPath, share.id)
                    if (state != null) {
                        SortFilterPreferenceManager.saveFolderSpecific(
                            requireContext(), folderKey, "${if (share.name.isNotEmpty()) share.name else share.host}:$currentPath",
                            state.copy(viewMode = selectedMode), isNetwork = true
                        )
                    }
                }
            } else {
                ViewModeManager.save(requireContext(), selectedMode)
            }
            applyViewMode(selectedMode)
        }
    }

    private fun setupTvFocus(view: View) {
        val iconTintFocused = android.content.res.ColorStateList.valueOf(ColorblindPalette.focusFillText(requireContext()))
        val iconTintDefault = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_text_primary))

        fun wireTvIconBtn(v: View?, onClick: () -> Unit) {
            val btn = v as? ImageView ?: return
            btn.imageTintList = iconTintDefault
            btn.setOnClickListener { onClick() }
            btn.setOnFocusChangeListener { _, hasFocus ->
                btn.imageTintList = if (hasFocus) iconTintFocused else iconTintDefault
            }
        }

        wireTvIconBtn(view.findViewById(R.id.btnBack)) { navigateUp() }
        wireTvIconBtn(view.findViewById(R.id.btnCreateNew)) { showCreateNewMenu() }
        
        val btnSortTv = view.findViewById<ImageView?>(R.id.btnSort)
        btnSortTv?.setOnClickListener { showSortFilterSheet() }
        btnSortTv?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btnSortTv.imageTintList = iconTintFocused
            } else {
                val hasOverride = SortFilterPreferenceManager.hasFolderOverride(requireContext(), currentPath, share.id)
                btnSortTv.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (hasOverride) ColorblindPalette.focusFill(requireContext()) else requireContext().getColor(R.color.tv_text_primary)
                )
            }
        }

        wireTvIconBtn(view.findViewById(R.id.btnRefresh)) {
            // An explicit refresh must bypass the RClone directory-list cache.
            za.kilowatch.ultimatefilemanager.network.RCloneShareClient.clearDirListCache()
            loadDirectory()
        }
        wireTvIconBtn(view.findViewById(R.id.btnDrivePicker)) { onStoragePickerRequested?.invoke() }
        wireTvIconBtn(view.findViewById(R.id.btnSearchToggle)) { toggleSearch() }
        wireTvIconBtn(view.findViewById(R.id.btnViewToggle)) {
            ViewModeManager.showSelectionDialog(requireContext(), fileAdapter.viewMode) { selectedMode ->
                val folderKey = SortFilterPreferenceManager.folderKey(share.id, currentPath)
                if (SortFilterPreferenceManager.hasFolderOverride(requireContext(), currentPath, share.id)) {
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val state = SortFilterPreferenceManager.loadForPath(requireContext(), currentPath, share.id)
                        if (state != null) {
                            SortFilterPreferenceManager.saveFolderSpecific(
                                requireContext(), folderKey, "${if (share.name.isNotEmpty()) share.name else share.host}:$currentPath",
                                state.copy(viewMode = selectedMode), isNetwork = true
                            )
                        }
                    }
                } else {
                    ViewModeManager.save(requireContext(), selectedMode)
                }
                applyViewMode(selectedMode)
            }
        }
        // Update initial TV tint
        val btnSearch = view.findViewById<ImageView>(R.id.btnSearchToggle)
        btnSearch?.imageTintList = android.content.res.ColorStateList.valueOf(ColorblindPalette.denied(requireContext()))

        listOf(
            R.id.btnCloseSelection, R.id.btnCopy, R.id.btnMove, R.id.btnRename, R.id.btnFavorite,
            R.id.btnShare, R.id.btnCopyEncrypt, R.id.btnMoveEncrypt, R.id.btnProtect, R.id.btnUnprotect,
            R.id.btnPin, R.id.btnUnpin,
            R.id.btnRetriggerThumbnails,
            R.id.btnChecksum
        ).forEach { id ->
            val btn = view.findViewById<ImageView>(id) ?: return@forEach
            btn.imageTintList = iconTintDefault
            btn.setOnFocusChangeListener { _, hasFocus ->
                btn.imageTintList = if (hasFocus) iconTintFocused else iconTintDefault
            }
        }

        // Action pills yellow theme
        listOf(R.id.btnPillCopy, R.id.btnPillMove, R.id.btnPillDelete).forEach { id ->
            val btn = view.findViewById<com.google.android.material.button.MaterialButton>(id) ?: return@forEach
            btn.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    btn.setBackgroundColor(ColorblindPalette.focusFill(requireContext()))
                    btn.setTextColor(ColorblindPalette.focusFillText(requireContext()))
                    btn.iconTint = android.content.res.ColorStateList.valueOf(ColorblindPalette.focusFillText(requireContext()))
                } else {
                    btn.setBackgroundColor(requireContext().getColor(R.color.tv_glass_white_10))
                    btn.setTextColor(requireContext().getColor(R.color.tv_text_primary))
                    btn.iconTint = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_text_primary))
                }
            }
        }
    }
    private fun toggleSearch() {
        val btnToggle = btnSearchToggle ?: return
        val searchEdit = edtSearch ?: return
        val searchRow = layoutSearchRow ?: return

        isSearchVisible = !isSearchVisible
        searchRow.visibility = if (isSearchVisible) View.VISIBLE else View.GONE
        
        if (isTv) {
            val color = if (isSearchVisible) ColorblindPalette.statusSuccess(requireContext())
                         else ColorblindPalette.denied(requireContext())
            btnToggle.imageTintList = android.content.res.ColorStateList.valueOf(color)
        }
        
        if (isSearchVisible) {
            searchEdit.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            searchEdit.setText("")
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(searchEdit.windowToken, 0)
            performSearch("") // Reset filter
        }
    }

    private var lastLoadedPath: String? = null

    private fun submitAdapterList(action: () -> Unit) {
        val safeContext = context ?: run { action(); return }
        val currentPath = currentPath
        val oldPath = lastLoadedPath
        val isNavigatingFolder = oldPath != null && oldPath != currentPath
        lastLoadedPath = currentPath

        // 1. Capture scroll position for same-folder reloads
        val lm = if (!isNavigatingFolder && !isTv) recyclerFiles.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager else null
        val sameFolderPosition = lm?.findFirstVisibleItemPosition() ?: androidx.recyclerview.widget.RecyclerView.NO_POSITION
        val sameFolderOffset = if (sameFolderPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
            lm?.findViewByPosition(sameFolderPosition)?.top ?: 0
        } else 0

        // 2. Lookup saved scroll state if returning to a previously visited folder
        val restoredFolderState = if (isNavigatingFolder) folderScrollStates[currentPath] else null

        val restoreScroll = {
            if (!isNavigatingFolder && sameFolderPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
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

        val wrappedAction: () -> Unit = {
            action()
            restoreScroll()
        }

        if (isNavigatingFolder && ::recyclerFiles.isInitialized && za.kilowatch.ultimatefilemanager.util.AnimationHelper.areFolderTransitionsEnabled(safeContext)) {
            val isForward = currentPath.length > (oldPath?.length ?: 0)
            za.kilowatch.ultimatefilemanager.util.AnimationHelper.animateFolderTransition(recyclerFiles, isForward) {
                if (isAdded) {
                    wrappedAction()
                }
            }
        } else {
            wrappedAction()
        }
    }

    private fun onBatchRenameCompleted(renamedMap: Map<String, String> = emptyMap()) {
        fileAdapter.exitSelectionMode()
        if (isSearchActive) {
            if (renamedMap.isNotEmpty()) {
                currentFiles = currentFiles.map { nf ->
                    val newPath = renamedMap[nf.path]
                    if (newPath != null) {
                        val newName = newPath.substringAfterLast('/')
                        nf.copy(name = newName, path = newPath)
                    } else nf
                }
            }
            val query = edtSearch?.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                performSearch(query)
            }
        } else {
            loadDirectory()
        }
    }

    private fun performSearch(query: String) {
        isSearchActive = query.isNotEmpty()
        val snapshot = currentFiles
        searchJob?.cancel()
        searchJob = lifecycleScope.launch { doSearchInternal(query, snapshot) }
    }

    /**
     * The actual search/filter/sort. The filter + NaturalSort sort over a large
     * network listing is pure CPU work; it runs on [kotlinx.coroutines.Dispatchers.Default]
     * so a huge share can't freeze the main thread past the ANR watchdog threshold
     * (reported from an NVIDIA SHIELD, SDK 30, app 1.8.6-GOOGLE). Only the
     * adapter/RecyclerView updates run on the main thread.
     */
    private suspend fun doSearchInternal(query: String, snapshot: List<NetworkFile>) {
        val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
        val sortedAndFiltered = withContext(kotlinx.coroutines.Dispatchers.Default) {
            val baseList = if (query.isEmpty()) snapshot else snapshot.filter { it.name.contains(query, ignoreCase = true) }
            val filtered = baseList.filter { isNetworkFileVisible(it, showHidden) }
            sortAndFilterFiles(filtered)
        }

        submitAdapterList {
            fileAdapter.submitList(sortedAndFiltered)
            layoutEmpty?.visibility = if (sortedAndFiltered.isEmpty()) View.VISIBLE else View.GONE
            val currentFolder = currentPath
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager(requireContext().applicationContext)
                        .warmCacheForFolder(share.id, currentFolder)
                } catch (_: Throwable) {}
            }
        }

        if (isTv) {
            val requestFocus = shouldRestoreFocus || arguments?.getBoolean(ARG_REQUEST_INITIAL_FOCUS, false) == true
            arguments?.putBoolean(ARG_REQUEST_INITIAL_FOCUS, false)
            shouldRestoreFocus = false

            if (requestFocus) {
                recyclerFiles.post {
                    var focusPos = 0
                    val exitedPath = lastExitedPath
                    if (exitedPath != null) {
                        var index = sortedAndFiltered.indexOfFirst { it.path == exitedPath }
                        if (index == -1) {
                            val targetName = exitedPath.substringAfterLast('/')
                            index = sortedAndFiltered.indexOfFirst { it.name.equals(targetName, ignoreCase = true) }
                        }
                        if (index != -1) {
                            focusPos = index
                        }
                        lastExitedPath = null
                    }
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
                    } else {
                        recyclerFiles.isFocusable = true
                        recyclerFiles.isFocusableInTouchMode = true
                        recyclerFiles.requestFocus()
                    }
                }
            } else {
                val hasFocusNow = recyclerFiles.hasFocus() || (view?.findFocus() == recyclerFiles)
                if (!hasFocusNow) {
                    recyclerFiles.isFocusable = false
                    recyclerFiles.isFocusableInTouchMode = false
                }
            }
        }
        updatePasteFab()
    }

    fun updatePasteFab() {
        val fab = fabPaste ?: return
        val hasLocal = za.kilowatch.ultimatefilemanager.storage.FileClipboard.hasItems()
        val hasNet = NetworkClipboard.hasItems()
        val total = za.kilowatch.ultimatefilemanager.storage.FileClipboard.totalItemCount() + (if (hasNet) NetworkClipboard.files.size else 0)

        if (total > 0) {
            val label = "${getString(R.string.action_paste)} ($total)"
            fab.text = label
            fab.visibility = View.VISIBLE
        } else {
            fab.visibility = View.GONE
        }
        updateFabPositions()
    }

    private fun sortAndFilterFiles(files: List<NetworkFile>): List<NetworkFile> {
        val filtered = files.filter { file ->
            val ext = if (file.name.contains('.')) file.name.substringAfterLast('.').lowercase() else ""
            val matchesCategory = if (filterType == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.ALL) true
            else if (file.isDirectory) true
            else za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.matchesExtension(ext, filterType)

            val matchesDate = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.matchesDate(file.lastModified, currentDateFilter)
            val matchesSize = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.matchesSize(file.size, file.isDirectory, currentSizeFilter)

            matchesCategory && matchesDate && matchesSize
        }
        val tagFiltered = if (activeTagsFilter.isNotEmpty()) {
            val ctx = context ?: return filtered
            filtered.filter { it.isDirectory || za.kilowatch.ultimatefilemanager.storage.FileTagsManager.getTags(ctx, it.path).any { t -> t in activeTagsFilter } }
        } else {
            filtered
        }
        val state = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.SortFilterState(
            sortMode = sortMode,
            sortOrder = sortOrder,
            filterType = filterType,
            showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled,
            groupByDate = false,
            activeTags = activeTagsFilter,
            dateFilter = currentDateFilter,
            sizeFilter = currentSizeFilter
        )
        val fileComparator = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.getNetworkFileComparator(
            state = state,
            context = context,
            shareId = share.id,
            directoriesFirst = true
        )
        return tagFiltered.sortedWith(fileComparator)
    }

    private fun applyViewMode(mode: ViewModeManager.ViewMode) {
        val safeContext = context ?: return
        val updateLayout = {
            if (isAdded) {
                val ctx = context ?: safeContext
                fileAdapter.viewMode = mode
                val lm = if (!ViewModeManager.isGrid(mode)) {
                    androidx.recyclerview.widget.LinearLayoutManager(ctx)
                } else {
                    androidx.recyclerview.widget.GridLayoutManager(
                        ctx, ViewModeManager.spanCount(ctx, mode)
                    ).apply {
                        spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                            override fun getSpanSize(position: Int): Int {
                                val vt = fileAdapter.getItemViewType(position)
                                return if (vt == 3 || vt == 4) spanCount else 1
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

                view?.findViewById<ImageView>(R.id.btnViewToggle)?.setImageResource(ViewModeManager.iconRes(mode))
                recyclerFiles.adapter = fileAdapter
            }
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
        if (isAdded && ::fileAdapter.isInitialized && ViewModeManager.isGrid(fileAdapter.viewMode)) {
            applyViewMode(fileAdapter.viewMode)
        }
    }

    /**
     * Determines whether a network file should be visible in the file list.
     * When [showHidden] is false, filters out files/folders whose name starts with "." (Unix dotfile convention).
     */
    private fun isNetworkFileVisible(nf: za.kilowatch.ultimatefilemanager.network.NetworkFile, showHidden: Boolean): Boolean {
        return showHidden || !HiddenFilesManager.isJunkOrHidden(nf.name)
    }

    private fun showSortFilterSheet() {
        val ctx = context ?: return
        val sheet = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet()
        sheet.currentSortMode = sortMode
        sheet.currentSortOrder = sortOrder
        sheet.currentFilterType = filterType
        sheet.currentDateFilter = currentDateFilter
        sheet.currentSizeFilter = currentSizeFilter
        sheet.isSearchMode = isSearchActive
        sheet.currentGroupByDate = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(ctx)
        sheet.activeTags = activeTagsFilter

        val folderKey = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.folderKey(share.id, currentPath)
        sheet.currentFolderKey = folderKey
        sheet.currentFolderDisplayPath = "${if (share.name.isNotEmpty()) share.name else share.host}:$currentPath"
        val hasFolderOverride = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.hasFolderOverride(ctx, currentPath, share.id)
        sheet.currentScope = if (hasFolderOverride)
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.FOLDER
            else za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.GLOBAL

        val activeState = if (hasFolderOverride) {
            za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.loadForPath(ctx, currentPath, share.id)
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

        sheet.onApply = { mode, order, filter, dateFilter, sizeFilter, showHidden, groupByDate, tags, scope, selectedViewMode, isRecursive ->
            sortMode = mode
            sortOrder = order
            filterType = filter
            currentDateFilter = dateFilter
            currentSizeFilter = sizeFilter
            activeTagsFilter = tags
            if (scope == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.GLOBAL) {
                za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled = showHidden
            }

            if (!isSearchActive) {
                val state = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.SortFilterState(
                    mode, order, filter, showHidden, groupByDate, tags,
                    viewMode = if (scope == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.FOLDER) selectedViewMode else null,
                    isRecursive = if (scope == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.FOLDER) isRecursive else false,
                    dateFilter = dateFilter,
                    sizeFilter = sizeFilter
                )
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (scope == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.Scope.FOLDER) {
                        za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.saveFolderSpecific(
                            ctx, folderKey, "${if (share.name.isNotEmpty()) share.name else share.host}:$currentPath", state, isNetwork = true)
                    } else {
                        za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.saveGlobal(ctx, state)
                        ViewModeManager.save(ctx, selectedViewMode)
                        za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.clearFolderSpecific(ctx, folderKey)
                    }
                    val hasFolderOverrideNow = za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager.hasFolderOverride(ctx, currentPath, share.id)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        updateSortBadge(hasFolderOverrideNow)
                    }
                }
            }

            if (groupByDate != za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(ctx)) {
                za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.setEnabled(ctx, groupByDate)
                fileAdapter.isGroupedByDate = groupByDate
            }

            applyViewMode(selectedViewMode)
            performSearch(edtSearch?.text?.toString()?.trim() ?: "")
        }
        sheet.show(parentFragmentManager, za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.TAG)
    }

    /**
     * Tints the sort icon when a folder-specific sort override is active.
     */
    private fun updateSortBadge(hasFolderOverride: Boolean) {
        val ctx = context ?: return
        val btn = view?.findViewById<android.widget.ImageView>(R.id.btnSort) ?: return
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(ctx)
        if (hasFolderOverride) {
            btn.imageTintList = android.content.res.ColorStateList.valueOf(
                if (isTv) ColorblindPalette.focusFill(ctx) else ctx.getColor(R.color.ufm_primary))
        } else {
            btn.imageTintList = android.content.res.ColorStateList.valueOf(
                ctx.getColor(if (isTv) za.kilowatch.ultimatefilemanager.R.color.tv_text_primary else za.kilowatch.ultimatefilemanager.R.color.mobile_icon_tint))
        }
    }

    private fun setNetworkWallpaper(networkFile: NetworkFile, flag: Int) {
        val ctx = context ?: return
        za.kilowatch.ultimatefilemanager.util.WallpaperHelper.showConfirmDialog(
            ctx,
            networkFile.name,
            flag
        ) {
            val toastFetching = android.widget.Toast.makeText(ctx, getString(R.string.fetching_filename, networkFile.name), android.widget.Toast.LENGTH_SHORT)
            toastFetching.show()
            lifecycleScope.launch(Dispatchers.IO) {
                var tempFile: java.io.File? = null
                var success = false
                try {
                    val tempDir = java.io.File(ctx.cacheDir, "wallpaper_temp")
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
                        success = za.kilowatch.ultimatefilemanager.util.WallpaperHelper.setWallpaper(ctx, tempFile, flag)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    tempFile?.delete()
                }

                withContext(Dispatchers.Main) {
                    fileAdapter.exitSelectionMode()
                    val isHome = flag == android.app.WallpaperManager.FLAG_SYSTEM
                    val msgRes = if (success) {
                        if (isHome) R.string.toast_wallpaper_set_home_success else R.string.toast_wallpaper_set_lock_success
                    } else {
                        R.string.toast_wallpaper_set_failed
                    }
                    android.widget.Toast.makeText(ctx, getString(msgRes), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setNetworkSystemSound(networkFile: NetworkFile, type: Int) {
        val ctx = context ?: return
        za.kilowatch.ultimatefilemanager.util.RingtoneHelper.showConfirmDialog(
            ctx,
            networkFile.name,
            type
        ) {
            if (!za.kilowatch.ultimatefilemanager.util.RingtoneHelper.canWriteSettings(ctx)) {
                android.widget.Toast.makeText(ctx, R.string.toast_sound_permission_required, android.widget.Toast.LENGTH_LONG).show()
                za.kilowatch.ultimatefilemanager.util.RingtoneHelper.requestWriteSettings(ctx)
                return@showConfirmDialog
            }
            val toastFetching = android.widget.Toast.makeText(ctx, getString(R.string.fetching_filename, networkFile.name), android.widget.Toast.LENGTH_SHORT)
            toastFetching.show()
            lifecycleScope.launch(Dispatchers.IO) {
                var tempFile: java.io.File? = null
                var success = false
                try {
                    val tempDir = java.io.File(ctx.cacheDir, "ringtone_temp")
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
                        success = za.kilowatch.ultimatefilemanager.util.RingtoneHelper.setAsSystemSound(ctx, tempFile, type)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    tempFile?.delete()
                }

                withContext(Dispatchers.Main) {
                    fileAdapter.exitSelectionMode()
                    val msgRes = if (success) {
                        when (type) {
                            android.media.RingtoneManager.TYPE_RINGTONE -> R.string.toast_ringtone_set_success
                            android.media.RingtoneManager.TYPE_NOTIFICATION -> R.string.toast_notification_set_success
                            android.media.RingtoneManager.TYPE_ALARM -> R.string.toast_alarm_set_success
                            else -> R.string.toast_ringtone_set_success
                        }
                    } else {
                        R.string.toast_sound_set_failed
                    }
                    android.widget.Toast.makeText(ctx, getString(msgRes), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resolveShareById(id: String): NetworkShare? {
        val ctx = context ?: return null
        if (::share.isInitialized && id == share.id) return share
        val fromRepo = NetworkShareRepository.getInstance(ctx).getById(id)
        if (fromRepo != null) return fromRepo
        val dev = PairingManager.getInstance(ctx).getPairedDevice(id)
        if (dev != null) return NetworkShare(
            id = dev.deviceId, name = dev.name,
            type = ShareType.TV, host = dev.lastIp, port = dev.lastPort, readOnly = false
        )
        return null
    }

    fun showFavoriteDialog(file: NetworkFile) {
        val ctx = context ?: return
        val isOnTv = DeviceUtils.isTvDevice(ctx)
        val layoutRes = if (isOnTv) R.layout.dialog_add_favorite_tv else R.layout.dialog_add_favorite
        val dialogView = LayoutInflater.from(ctx).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
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
                val effectivePath = if (share.type == ShareType.SMB && share.isServerMode) {
                    val shareName = share.remotePath.trimStart('/')
                    if (shareName.isNotEmpty() && !file.path.startsWith("/$shareName/") && file.path != "/$shareName") {
                        val sub = file.path.trimStart('/')
                        if (sub.isEmpty()) "/$shareName" else "/$shareName/$sub"
                    } else if (shareName.isEmpty() && currentPath.isNotEmpty() && !file.path.startsWith("/${currentPath.trimStart('/')}")) {
                        val fullPrefix = currentPath.trimStart('/')
                        val sub = file.name
                        if (fullPrefix.isEmpty()) "/$sub" else "/$fullPrefix/$sub"
                    } else {
                        file.path
                    }
                } else {
                    file.path
                }
                val favorite = FavoritesManager.FavoriteItem(
                    id = "fav_${System.currentTimeMillis()}",
                    path = effectivePath,
                    label = name,
                    isFolder = file.isDirectory,
                    isNetwork = true,
                    shareId = share.id
                )
                FavoritesManager.addFavorite(ctx, favorite)
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
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    fun showArchiveOptions(files: List<NetworkFile>) {
        val dialog = ArchiveOptionsDialog()
        dialog.setOnConfirm { filename, format, password, useCurrentFolder ->
            if (useCurrentFolder) {
                performNetworkCompression(files, CompressDest.Network(share, currentPath), filename, format, password)
            } else {
                pendingCompressSourceFiles = files
                pendingCompressFileName    = filename
                pendingCompressFormat      = format
                pendingCompressPassword    = password
                pickDestinationFolder()
            }
        }
        dialog.show(parentFragmentManager, "ArchiveOptions")
    }

    private fun pickDestinationFolder() {
        val ctx = context ?: return
        val intent = Intent(ctx, StorageBrowserActivity::class.java).apply {
            putExtra(StorageBrowserActivity.EXTRA_COMPRESS_DEST_PICKER, true)
        }
        localFolderPickerLauncher.launch(intent)
    }

    private sealed class CompressDest {
        data class Local(val dir: File) : CompressDest()
        data class Network(val share: NetworkShare, val remotePath: String) : CompressDest()
    }

    private fun performNetworkCompression(sourceFiles: List<NetworkFile>, dest: CompressDest, customFileName: String, format: ArchiveManager.Format, password: String?) {
        val ctx = context ?: return
        val isTv = DeviceUtils.isTvDevice(ctx)
        val layoutRes = if (isTv) R.layout.dialog_transfer_progress_tv else R.layout.dialog_transfer_progress
        val dialogView = LayoutInflater.from(ctx).inflate(layoutRes, null)
        val statusText = dialogView.findViewById<TextView>(R.id.txtProgressCurrentFile)
        val dialogProgress = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressFile)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtProgressTitle)
        txtTitle?.setText(R.string.compressing_network_files)
        statusText?.setText(R.string.preparing)

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val job = lifecycleScope.launch(Dispatchers.IO) {
            val tempDir = File(ctx.cacheDir, "comp_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            var tempArchive: File? = null

            try {
                val localFiles = mutableListOf<File>()
                sourceFiles.forEachIndexed { index, netFile ->
                    withContext(Dispatchers.Main) {
                        statusText?.text = if (netFile.isDirectory) getString(R.string.downloading_folder_netfilename) else "Downloading: ${netFile.name}"
                        dialogProgress?.progress = ((index.toFloat() / sourceFiles.size) * 50).toInt()
                    }
                    localFiles.add(downloadNetworkEntry(netFile, tempDir) { msg ->
                        activity?.runOnUiThread { statusText?.text = msg }
                    })
                }

                val extension = format.displayName
                val archiveName = "$customFileName$extension"
                val tempArchiveFile = File(ctx.cacheDir, "comp_arch_${System.currentTimeMillis()}$extension")
                tempArchive = tempArchiveFile

                withContext(Dispatchers.Main) { statusText?.setText(R.string.compressing) }
                ArchiveManager.compress(
                    sourceFiles = localFiles,
                    destFile = tempArchiveFile,
                    password = password,
                    format = format,
                    onProgress = { progress ->
                        activity?.runOnUiThread { dialogProgress?.progress = 50 + (progress / 2) }
                    }
                )

                when (dest) {
                    is CompressDest.Local -> {
                        val finalFile = uniqueFile(dest.dir, customFileName, extension)
                        withContext(Dispatchers.Main) { statusText?.setText(R.string.saving) }
                        tempArchiveFile.copyTo(finalFile, overwrite = false)
                        tempArchiveFile.delete()
                        tempArchive = null
                        withContext(Dispatchers.Main) {
                            dialog.dismiss()
                            fileAdapter.exitSelectionMode()
                            showPremiumSnackbar(getString(R.string.compression_completed_finalfilename, finalFile.name))
                        }
                    }
                    is CompressDest.Network -> {
                        withContext(Dispatchers.Main) {
                            statusText?.text = getString(R.string.uploading_to_destsharename)
                        }
                        val cleanDestPath = if (dest.share.isServerMode) {
                            stripSharePrefix(dest.remotePath.trimStart('/'))
                        } else {
                            dest.remotePath
                        }
                        val remotePath = if (cleanDestPath.isEmpty()) archiveName else "$cleanDestPath/$archiveName"
                        val inStream = tempArchiveFile.inputStream()
                        try {
                            when (dest.share.type) {
                                ShareType.SMB -> SmbShareClient.openOutputStream(dest.share, remotePath).use { out -> inStream.copyTo(out) }
                                ShareType.FTP -> FtpShareClient.openOutputStream(dest.share, remotePath).use { out -> inStream.copyTo(out) }
                                ShareType.TV  -> TvShareClient.uploadStream(dest.share, remotePath, inStream, tempArchiveFile.length())
                                ShareType.SFTP, ShareType.SCP -> SshShareClient.openOutputStream(dest.share, remotePath).use { out -> inStream.copyTo(out) }
                                ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(dest.share, remotePath).use { out -> inStream.copyTo(out) }
                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(dest.share, remotePath).use { out -> inStream.copyTo(out) }
                                ShareType.DROPBOX -> DropboxShareClient.openOutputStream(dest.share, remotePath).use { out -> inStream.copyTo(out) }
                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(dest.share, remotePath).use { out -> inStream.copyTo(out) }
                                ShareType.WEBDAV -> WebDavShareClient.openOutputStream(dest.share, remotePath).use { out -> inStream.copyTo(out) }
                                ShareType.NFS -> NfsShareClient.openOutputStream(dest.share, remotePath).use { out -> inStream.copyTo(out) }
                                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                            }
                        } finally {
                            inStream.close()
                        }
                        tempArchiveFile.delete()
                        tempArchive = null
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
        val cleanNetPath = stripSharePrefix(netFile.path)
        if (netFile.isDirectory) {
            localFile.mkdirs()
            val children = when (share.type) {
                ShareType.SMB -> SmbShareClient.listFiles(share, cleanNetPath)
                ShareType.FTP -> FtpShareClient.listFiles(share, cleanNetPath)
                ShareType.TV  -> TvShareClient.listFiles(share, cleanNetPath)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, cleanNetPath)
                ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(share, cleanNetPath)
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, cleanNetPath)
                ShareType.DROPBOX -> DropboxShareClient.listFiles(share, cleanNetPath)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, cleanNetPath)
                ShareType.WEBDAV -> WebDavShareClient.listFiles(share, cleanNetPath)
                ShareType.NFS -> NfsShareClient.listFiles(share, cleanNetPath)
                ShareType.DLNA -> DlnaShareClient.listFiles(share, cleanNetPath)
            }
            for (child in children) {
                downloadNetworkEntry(child, localFile, onStatusUpdate)
            }
        } else {
            onStatusUpdate?.invoke(getString(R.string.downloading_netfilename, netFile.name))
            val inStream = when (share.type) {
                ShareType.SMB -> SmbShareClient.openInputStream(share, cleanNetPath)
                ShareType.FTP -> FtpShareClient.openInputStream(share, cleanNetPath)
                ShareType.TV  -> TvShareClient.openInputStream(share, cleanNetPath)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, cleanNetPath)
                ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, cleanNetPath).first
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, cleanNetPath).first
                ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, cleanNetPath).first
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, cleanNetPath).first
                ShareType.WEBDAV -> WebDavShareClient.openInputStream(share, cleanNetPath).first
                ShareType.NFS -> NfsShareClient.openInputStream(share, cleanNetPath)
                ShareType.DLNA -> DlnaShareClient.openInputStream(share, cleanNetPath)
            }
            inStream.use { inp ->
                FileOutputStream(localFile).use { out ->
                    inp.copyTo(out)
                }
            }
        }
        return localFile
    }

    private suspend fun uploadLocalEntryToNetwork(localFile: File, rawRemotePath: String) {
        val cleanRemotePath = stripSharePrefix(rawRemotePath).replace('\\', '/')
        if (localFile.isDirectory) {
            val segments = cleanRemotePath.split("/").filter { it.isNotEmpty() }
            var currentSegment = ""
            for (segment in segments) {
                currentSegment = if (currentSegment.isEmpty()) segment else "$currentSegment/$segment"
                try {
                    when (share.type) {
                        ShareType.SMB -> SmbShareClient.mkdir(share, currentSegment)
                        ShareType.FTP -> FtpShareClient.mkdir(share, currentSegment)
                        ShareType.TV  -> TvShareClient.mkdir(share, currentSegment)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, currentSegment)
                        ShareType.ONEDRIVE -> OnedriveShareClient.mkdir(share, currentSegment)
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, currentSegment)
                        ShareType.DROPBOX -> DropboxShareClient.mkdir(share, currentSegment)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, currentSegment)
                        ShareType.WEBDAV -> WebDavShareClient.mkdir(share, currentSegment)
                        ShareType.NFS -> NfsShareClient.mkdir(share, currentSegment)
                        ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                    }
                } catch (_: Exception) {}
            }
            val children = localFile.listFiles() ?: return
            for (child in children) {
                val childRemotePath = if (cleanRemotePath.isEmpty()) child.name else "$cleanRemotePath/${child.name}"
                uploadLocalEntryToNetwork(child, childRemotePath)
            }
        } else {
            val parentPath = cleanRemotePath.substringBeforeLast('/', "")
            if (parentPath.isNotEmpty()) {
                val segments = parentPath.split("/").filter { it.isNotEmpty() }
                var currentSegment = ""
                for (segment in segments) {
                    currentSegment = if (currentSegment.isEmpty()) segment else "$currentSegment/$segment"
                    try {
                        when (share.type) {
                            ShareType.SMB -> SmbShareClient.mkdir(share, currentSegment)
                            ShareType.FTP -> FtpShareClient.mkdir(share, currentSegment)
                            ShareType.TV  -> TvShareClient.mkdir(share, currentSegment)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, currentSegment)
                            ShareType.ONEDRIVE -> OnedriveShareClient.mkdir(share, currentSegment)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, currentSegment)
                            ShareType.DROPBOX -> DropboxShareClient.mkdir(share, currentSegment)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, currentSegment)
                            ShareType.WEBDAV -> WebDavShareClient.mkdir(share, currentSegment)
                            ShareType.NFS -> NfsShareClient.mkdir(share, currentSegment)
                            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                    } catch (_: Exception) {}
                }
            }
            val inStream = localFile.inputStream()
            try {
                when (share.type) {
                    ShareType.SMB -> SmbShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                    ShareType.FTP -> FtpShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                    ShareType.TV -> TvShareClient.uploadStream(share, cleanRemotePath, inStream, localFile.length())
                    ShareType.SFTP, ShareType.SCP -> SshShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                    ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                    ShareType.DROPBOX -> DropboxShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                    ShareType.WEBDAV -> WebDavShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                    ShareType.NFS -> NfsShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                }
            } finally {
                inStream.close()
            }
        }
    }

    fun performNetworkExtractHere(archives: List<NetworkFile>) {
        showNetworkExtractOptions(archives)
    }

    private fun showNetworkExtractOptions(archives: List<NetworkFile>) {
        if (archives.isEmpty()) return
        val dialog = ExtractOptionsDialog.newInstance(archives.map { it.name })
        dialog.setOnExtractHere {
            performNetworkExtract(archives, customDestPath = null, isSelectFolderMode = false)
        }
        dialog.setOnExtractToNewFolder {
            promptExtractToNewFolder(archives)
        }
        dialog.setOnExtractAndSelectFolder {
            performNetworkExtract(archives, customDestPath = null, isSelectFolderMode = true)
        }
        dialog.show(parentFragmentManager, ExtractOptionsDialog.TAG)
    }

    private fun promptExtractToNewFolder(archives: List<NetworkFile>) {
        if (archives.isEmpty()) return
        val ctx = context ?: return
        val defaultName = if (archives.size == 1) ArchiveManager.getArchiveBaseName(archives.first().name) else "Extracted"
        val isOnTv = DeviceUtils.isTvDevice(ctx)

        val layoutRes = if (isOnTv) R.layout.dialog_create_folder_tv else R.layout.dialog_create_folder
        val dialogView = LayoutInflater.from(ctx).inflate(layoutRes, null)
        val edtFolderName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtFolderName)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtTitle)
        val btnCreate = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCreate)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtTitle?.setText(R.string.extract_new_folder_title)
        btnCreate?.setText(R.string.extract_to_new_folder)
        edtFolderName?.hint = getString(R.string.extract_new_folder_hint)
        edtFolderName?.setText(defaultName)
        edtFolderName?.selectAll()

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
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
            val remoteTarget = if (currentPath.isEmpty()) name else "$currentPath/$name"
            performNetworkExtract(archives, customDestPath = remoteTarget, isSelectFolderMode = false)
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        DialogInputHelper.setupDialogInput(dialog, edtFolderName) {
            btnCreate?.performClick()
        }
    }

    private fun performNetworkExtract(archives: List<NetworkFile>, customDestPath: String? = null, isSelectFolderMode: Boolean) {
        if (archives.isEmpty()) return
        val ctx = context ?: return
        fileAdapter.exitSelectionMode()

        if (share.type == ShareType.SMB && share.isServerMode && share.remotePath.isEmpty() && currentPath.isNotEmpty()) {
            val shareName = currentPath.trimStart('/').substringBefore('/')
            share = share.copy(remotePath = "/$shareName")
            fileAdapter.share = share
        }

        val isTv = DeviceUtils.isTvDevice(ctx)
        val layoutRes = if (isTv) R.layout.dialog_transfer_progress_tv else R.layout.dialog_transfer_progress
        val dialogView = LayoutInflater.from(ctx).inflate(layoutRes, null)
        val statusText = dialogView.findViewById<TextView>(R.id.txtProgressCurrentFile)
        val dialogProgress = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressFile)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtProgressTitle)
        txtTitle?.setText(R.string.extract_progress_title)
        statusText?.setText(R.string.extract_progress_title)

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val job = lifecycleScope.launch(Dispatchers.IO) {
            val tempExtractDir = File(ctx.cacheDir, "net_extract_${System.currentTimeMillis()}")
            tempExtractDir.mkdirs()

            suspend fun ensureRemoteDir(rawPath: String) {
                val cleanPath = stripSharePrefix(rawPath).replace('\\', '/').trim('/')
                if (cleanPath.isEmpty()) return
                val segments = cleanPath.split("/").filter { it.isNotEmpty() }
                var currentSegment = ""
                for (segment in segments) {
                    currentSegment = if (currentSegment.isEmpty()) segment else "$currentSegment/$segment"
                    try {
                        when(share.type) {
                            ShareType.SMB          -> SmbShareClient.mkdir(share, currentSegment)
                            ShareType.FTP          -> FtpShareClient.mkdir(share, currentSegment)
                            ShareType.TV           -> TvShareClient.mkdir(share, currentSegment)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, currentSegment)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.mkdir(share, currentSegment)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, currentSegment)
                            ShareType.DROPBOX      -> DropboxShareClient.mkdir(share, currentSegment)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, currentSegment)
                            ShareType.WEBDAV       -> WebDavShareClient.mkdir(share, currentSegment)
                            ShareType.NFS          -> NfsShareClient.mkdir(share, currentSegment)
                            ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                    } catch (_: Exception) {}
                }
            }

            if (customDestPath != null) {
                ensureRemoteDir(customDestPath)
            }

            try {
                var extractedCount = 0
                val stagedFiles = mutableListOf<File>()

                for ((index, netArchive) in archives.withIndex()) {
                    withContext(Dispatchers.Main) {
                        statusText?.text = getString(R.string.downloading_netfilename, netArchive.name)
                        dialogProgress?.progress = ((index.toFloat() / archives.size) * 30).toInt()
                    }

                    val archiveBaseName = ArchiveManager.getArchiveBaseName(netArchive.name)
                    val tempArchiveFile = downloadNetworkEntry(netArchive, tempExtractDir)
                    val localExtractedDir = if (isSelectFolderMode && archives.size > 1) {
                        File(tempExtractDir, archiveBaseName).apply { mkdirs() }
                    } else {
                        File(tempExtractDir, "extracted_$archiveBaseName").apply { mkdirs() }
                    }

                    withContext(Dispatchers.Main) {
                        statusText?.text = getString(R.string.archive_extracting)
                    }

                    val extractRes = ArchiveManager.extract(
                        context = ctx,
                        archiveFile = tempArchiveFile,
                        destDir = localExtractedDir,
                        password = null,
                        onArchiveProgress = { p ->
                            activity?.runOnUiThread {
                                dialogProgress?.progress = (30 + ((p.percentage * 0.3f) + (index * 30))).toInt().coerceIn(0, 100)
                                if (p.currentFileName.isNotEmpty()) {
                                    statusText?.text = "${getString(R.string.archive_extracting)}: ${p.currentFileName}"
                                }
                            }
                        }
                    )

                    if (extractRes.isFailure) {
                        throw extractRes.exceptionOrNull() ?: Exception("Extraction failed")
                    }

                    extractedCount++
                    tempArchiveFile.delete()

                    if (isSelectFolderMode) {
                        if (archives.size > 1) {
                            stagedFiles.add(localExtractedDir)
                        } else {
                            val items = localExtractedDir.listFiles() ?: emptyArray()
                            stagedFiles.addAll(items)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            statusText?.text = getString(R.string.uploading_to_sharename, share.name)
                        }

                        val itemsToUpload = localExtractedDir.listFiles() ?: emptyArray()
                        val baseUploadPath = if (customDestPath != null) {
                            if (archives.size > 1) {
                                val subPath = if (customDestPath.isEmpty()) archiveBaseName else "$customDestPath/$archiveBaseName"
                                ensureRemoteDir(subPath)
                                subPath
                            } else {
                                ensureRemoteDir(customDestPath)
                                customDestPath
                            }
                        } else {
                            if (archives.size > 1) {
                                val subPath = if (currentPath.isEmpty()) archiveBaseName else "$currentPath/$archiveBaseName"
                                ensureRemoteDir(subPath)
                                subPath
                            } else {
                                currentPath
                            }
                        }

                        for ((itemIndex, item) in itemsToUpload.withIndex()) {
                            val remoteDestPath = if (baseUploadPath.isEmpty()) item.name else "$baseUploadPath/${item.name}"
                            uploadLocalEntryToNetwork(item, remoteDestPath)
                            withContext(Dispatchers.Main) {
                                dialogProgress?.progress = 60 + (((itemIndex + 1).toFloat() / itemsToUpload.size) * 40).toInt()
                            }
                        }

                        localExtractedDir.deleteRecursively()
                    }
                }

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    if (isSelectFolderMode) {
                        if (stagedFiles.isNotEmpty()) {
                            za.kilowatch.ultimatefilemanager.storage.FileClipboard.setExtract(stagedFiles, tempExtractDir)
                            updatePasteFab()
                            showPremiumSnackbar(getString(R.string.extract_staged_snackbar))
                        } else {
                            tempExtractDir.deleteRecursively()
                            showPremiumSnackbar(getString(R.string.extract_error, "No files extracted"))
                        }
                    } else {
                        tempExtractDir.deleteRecursively()
                        showPremiumSnackbar(getString(R.string.extract_success, extractedCount))
                        loadDirectory()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    tempExtractDir.deleteRecursively()
                    val msg = e.message ?: "Unknown error"
                    showPremiumSnackbar(getString(R.string.extract_error, msg))
                    loadDirectory()
                }
            }
        }

        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            job.cancel()
            dialog.dismiss()
        }
    }

    fun shareNetworkFiles(files: List<NetworkFile>) {
        val ctx = context ?: return
        progressBar.visibility = View.VISIBLE
        fileAdapter.exitSelectionMode()
        lifecycleScope.launch(Dispatchers.IO) {
            val tempDir = File(ctx.cacheDir, "share_temp_${System.currentTimeMillis()}")
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
        val ctx = context ?: return
        val white = ctx.getColor(R.color.tv_text_primary)
        val black = ColorblindPalette.focusFillText(ctx)
        val yellow = ColorblindPalette.focusFill(ctx)
        val secondary = ctx.getColor(R.color.tv_text_secondary)

        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(ctx.getColor(R.color.tv_bg_gradient_end))
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
                    card.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.tv_glass_white_10)))
                    card.strokeColor = defaultStrokeColor
                    title?.setTextColor(white)
                    desc?.setTextColor(secondary)
                }
            }
        }

        cardStandard?.let { setupCardFocus(it, ctx.getColor(R.color.tv_glass_border)) }
        cardPremium?.let { setupCardFocus(it, ctx.getColor(R.color.tv_accent)) }

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
        val ctx = context ?: return
        var proceeded = false
        val isTv = DeviceUtils.isTvDevice(ctx)
        val layoutRes = if (isTv) R.layout.dialog_premium_share_chooser_tv else R.layout.dialog_premium_share_chooser
        val dialogView = LayoutInflater.from(ctx).inflate(layoutRes, null)
        val dialog = MaterialAlertDialogBuilder(ctx, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        val cardStandardShare = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardStandardShare)
        val cardPremiumShare = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardPremiumShare)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        cardStandardShare?.setOnClickListener {
            proceeded = true
            dialog.dismiss()
            performStandardShareNetwork(localFiles)
        }

        cardPremiumShare?.setOnClickListener {
            proceeded = true
            dialog.dismiss()
            if (isTv) {
                val filePaths = ArrayList(localFiles.map { it.absolutePath })
                val intent = Intent(ctx, PremiumShareTvActivity::class.java).apply {
                    putStringArrayListExtra("files", filePaths)
                    putExtra("target_type", "web")
                    putExtra("clean_up_on_stop", true)
                }
                startActivity(intent)
            } else {
                showPremiumTargetChooserDialog(localFiles)
            }
        }

        btnCancel?.setOnClickListener {
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
        val ctx = context ?: return
        var proceeded = false
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_premium_target_chooser, null)
        val dialog = MaterialAlertDialogBuilder(ctx, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        val cardTargetTv = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTargetTv)
        val cardTargetMobilePc = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTargetMobilePc)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val filePaths = ArrayList(localFiles.map { it.absolutePath })

        cardTargetTv?.setOnClickListener {
            proceeded = true
            dialog.dismiss()
            val intent = Intent(ctx, PremiumShareActivity::class.java).apply {
                putStringArrayListExtra("files", filePaths)
                putExtra("target_type", "tv")
                putExtra("clean_up_on_stop", true)
            }
            startActivity(intent)
        }

        cardTargetMobilePc?.setOnClickListener {
            proceeded = true
            dialog.dismiss()
            val intent = Intent(ctx, PremiumShareActivity::class.java).apply {
                putStringArrayListExtra("files", filePaths)
                putExtra("target_type", "web")
                putExtra("clean_up_on_stop", true)
            }
            startActivity(intent)
        }

        btnCancel?.setOnClickListener {
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
        val ctx = context ?: return
        try {
            val uris = ArrayList<Uri>()
            for (file in localFiles) {
                uris.add(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file))
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

    private fun showNetworkVaultPicker(files: List<NetworkFile>, isMove: Boolean) {
        val ctx = context ?: return
        val vaultDir = File(ctx.filesDir, "vault")
        val entries = mutableListOf<VaultEntry>()
        if (vaultDir.exists() && vaultDir.isDirectory) {
            vaultDir.listFiles()?.forEach { entryDir ->
                if (entryDir.isDirectory) {
                    readVaultEntry(entryDir)?.let { entries.add(it) }
                }
            }
        }

        if (entries.isEmpty()) {
            val isOnTv = DeviceUtils.isTvDevice(ctx)
            val layoutRes = if (isOnTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
            val noVaultView = LayoutInflater.from(ctx).inflate(layoutRes, null)
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

            val noVaultDialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
                .setView(noVaultView)
                .create()

            btnPositive?.setOnClickListener {
                noVaultDialog.dismiss()
                startActivity(Intent(ctx, VaultActivity::class.java))
            }
            btnNegative?.setOnClickListener {
                noVaultDialog.dismiss()
            }

            noVaultDialog.show()
            noVaultDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            return
        }

        val vaultNames = entries.map { it.displayName }.toTypedArray()
        val title = if (isMove) getString(R.string.encrypt_move_title)
                    else getString(R.string.encrypt_copy_title)

        val white = ctx.getColor(R.color.tv_text_primary)
        val adapter = object : android.widget.ArrayAdapter<String>(
            ctx, android.R.layout.simple_list_item_1, vaultNames.toList()
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

        val listView = android.widget.ListView(ctx).apply {
            this.adapter = adapter
            divider = android.graphics.drawable.ColorDrawable(0x1AFFFFFF.toInt())
            dividerHeight = 1
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
            .setTitle(title)
            .setView(listView)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        listView.setOnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            val entry = entries[which]
            downloadAndEncrypt(files, entry, isMove)
        }
    }

    private fun downloadAndEncrypt(files: List<NetworkFile>, entry: VaultEntry, isMove: Boolean) {
        val ctx = context ?: return
        progressBar.visibility = View.VISIBLE
        fileAdapter.exitSelectionMode()
        lifecycleScope.launch(Dispatchers.IO) {
            val tempDir = File(ctx.cacheDir, "net_temp")
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

                    val entryDir = File(ctx.filesDir, "vault/${entry.id}")
                    entryDir.mkdirs()
                    val encFile = File(entryDir, "${nf.name}.enc")
                    VaultCrypto.encryptFile(dest, encFile)
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

            if (encryptedNames.isNotEmpty()) {
                val entryDir = File(ctx.filesDir, "vault/${entry.id}")
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

    private fun readVaultEntry(dir: File): VaultEntry? {
        val metaFile = File(dir, "metadata.json")
        val fileToRead = if (metaFile.exists()) metaFile else File(dir, "metadata.json.bak")
        if (!fileToRead.exists()) return null
        return try {
            val json = org.json.JSONObject(fileToRead.readText())
            val rawName = json.getString("displayName")
            val displayName = if (rawName.startsWith("enc:")) VaultCrypto.decryptString(rawName.removePrefix("enc:")) else rawName
            val rawRoot = json.optString("originalRoot", "")
            val originalRoot = if (rawRoot.startsWith("enc:")) VaultCrypto.decryptString(rawRoot.removePrefix("enc:")) else rawRoot

            val filesList = if (json.has("filesPayload")) {
                VaultCrypto.decryptStrings(json.getString("filesPayload"))
            } else if (json.has("files")) {
                val filesArray = json.optJSONArray("files") ?: org.json.JSONArray()
                val list = ArrayList<String>(filesArray.length())
                for (i in 0 until filesArray.length()) {
                    val rawF = filesArray.getString(i)
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
                files = filesList
            )
        } catch (_: Exception) {
            null
        }
    }

    fun downloadNetworkImagesAndCompress(files: List<NetworkFile>) {
        val ctx = context ?: return
        val v = view ?: return
        val snack = Snackbar.make(v, getString(R.string.fetching_filename, files.first().name), Snackbar.LENGTH_INDEFINITE)
        snack.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(ctx.cacheDir, "img_compress_${System.currentTimeMillis()}")
                tempDir.mkdirs()
                val localPaths = java.util.ArrayList<String>()

                for (nf in files) {
                    val tempFile = File(tempDir, nf.name)
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
                            FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                        }
                        localPaths.add(tempFile.absolutePath)
                    }
                }

                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    if (localPaths.isNotEmpty()) {
                        startActivity(Intent(ctx, za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity::class.java).apply {
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

    fun downloadNetworkImagesAndCreateGif(files: List<NetworkFile>) {
        if (files.isEmpty()) return
        val ctx = context ?: return
        val v = view ?: return
        val snack = Snackbar.make(v, getString(R.string.fetching_filename, files.first().name), Snackbar.LENGTH_INDEFINITE)
        snack.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(ctx.cacheDir, "gif_src_${System.currentTimeMillis()}")
                tempDir.mkdirs()
                val localPaths = java.util.ArrayList<String>()

                for (nf in files) {
                    val tempFile = File(tempDir, nf.name)
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
                            FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                        }
                        localPaths.add(tempFile.absolutePath)
                    }
                }

                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    if (localPaths.isNotEmpty()) {
                        startActivity(Intent(ctx, za.kilowatch.ultimatefilemanager.viewer.GifCreatorActivity::class.java).apply {
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

    fun downloadNetworkImagesAndLaunchExifTools(files: List<NetworkFile>) {
        if (files.isEmpty()) return
        val ctx = context ?: return
        val v = view ?: return
        val snack = Snackbar.make(v, getString(R.string.fetching_filename, files.first().name), Snackbar.LENGTH_INDEFINITE)
        snack.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(ctx.cacheDir, "exif_src_${System.currentTimeMillis()}")
                tempDir.mkdirs()
                val localPaths = java.util.ArrayList<String>()

                for (nf in files) {
                    val tempFile = File(tempDir, nf.name)
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
                            FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                        }
                        localPaths.add(tempFile.absolutePath)
                    }
                }

                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    if (localPaths.isNotEmpty()) {
                        startActivity(Intent(ctx, za.kilowatch.ultimatefilemanager.viewer.ExifToolsActivity::class.java).apply {
                            putStringArrayListExtra(za.kilowatch.ultimatefilemanager.viewer.ExifToolsActivity.EXTRA_FILE_PATHS, localPaths)
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

    fun downloadNetworkAudioAndLaunchTagger(files: List<NetworkFile>) {
        if (files.isEmpty()) return
        val ctx = context ?: return
        val v = view ?: return
        val snack = Snackbar.make(v, getString(R.string.fetching_filename, files.first().name), Snackbar.LENGTH_INDEFINITE)
        snack.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(ctx.cacheDir, "music_tagger_${System.currentTimeMillis()}")
                tempDir.mkdirs()
                val localPaths = java.util.ArrayList<String>()
                val remotePathMap = java.util.concurrent.ConcurrentHashMap<String, String>()

                for (nf in files) {
                    val tempFile = File(tempDir, nf.name)
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
                    }
                    if (inp != null) {
                        inp.use { input ->
                            FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                        }
                        localPaths.add(tempFile.absolutePath)
                        remotePathMap[tempFile.absolutePath] = nf.path
                    }
                }

                if (localPaths.isNotEmpty()) {
                    val capturedShare = share
                    za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileSaved = { savedFile ->
                        val remotePath = remotePathMap[savedFile.absolutePath]
                        if (remotePath != null && !capturedShare.readOnly) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val fis = java.io.FileInputStream(savedFile)
                                    fis.use { input ->
                                        when (capturedShare.type) {
                                            ShareType.SMB -> SmbShareClient.openOutputStream(capturedShare, remotePath).use { out -> input.copyTo(out) }
                                            ShareType.FTP -> FtpShareClient.openOutputStream(capturedShare, remotePath).use { out -> input.copyTo(out) }
                                            ShareType.SFTP, ShareType.SCP -> withContext(Dispatchers.IO) { SshShareClient.openOutputStream(capturedShare, remotePath).use { out -> input.copyTo(out) } }
                                            ShareType.TV -> TvShareClient.uploadStream(capturedShare, remotePath, input, savedFile.length())
                                            ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(capturedShare, remotePath).use { out -> input.copyTo(out) }
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(capturedShare, remotePath).use { out -> input.copyTo(out) }
                                            ShareType.DROPBOX -> DropboxShareClient.openOutputStream(capturedShare, remotePath).use { out -> input.copyTo(out) }
                                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(capturedShare, remotePath).use { out -> input.copyTo(out) }
                                            ShareType.WEBDAV -> WebDavShareClient.openOutputStream(capturedShare, remotePath).use { out -> input.copyTo(out) }
                                            ShareType.NFS -> withContext(Dispatchers.IO) { NfsShareClient.openOutputStream(capturedShare, remotePath).use { out -> input.copyTo(out) } }
                                            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                        }
                                    }
                                    za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.clearCacheForPath(remotePath)
                                    context?.let { c ->
                                        za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager(c).evictThumbnail(capturedShare.id, remotePath)
                                    }
                                    withContext(Dispatchers.Main) {
                                        loadDirectory()
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                    }

                    za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileRenamed = { oldFile, newFile ->
                        val oldRemotePath = remotePathMap[oldFile.absolutePath]
                        if (oldRemotePath != null && !capturedShare.readOnly) {
                            val parent = if (oldRemotePath.contains('/')) oldRemotePath.substringBeforeLast('/') else ""
                            val newRemotePath = if (parent.isEmpty()) newFile.name else "$parent/${newFile.name}"
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    when (capturedShare.type) {
                                        ShareType.SMB -> SmbShareClient.rename(capturedShare, oldRemotePath, newRemotePath)
                                        ShareType.FTP -> FtpShareClient.rename(capturedShare, oldRemotePath, newRemotePath)
                                        ShareType.TV  -> TvShareClient.rename(capturedShare, oldRemotePath, newRemotePath)
                                        ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(capturedShare, oldRemotePath, newRemotePath)
                                        ShareType.ONEDRIVE -> OnedriveShareClient.rename(capturedShare, oldRemotePath, newRemotePath)
                                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(capturedShare, oldRemotePath, newRemotePath)
                                        ShareType.DROPBOX -> DropboxShareClient.rename(capturedShare, oldRemotePath, newRemotePath)
                                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(capturedShare, oldRemotePath, newRemotePath)
                                        ShareType.WEBDAV -> WebDavShareClient.rename(capturedShare, oldRemotePath, newRemotePath, false)
                                        ShareType.NFS -> NfsShareClient.rename(capturedShare, oldRemotePath, newRemotePath)
                                        ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                    }
                                    remotePathMap.remove(oldFile.absolutePath)
                                    remotePathMap[newFile.absolutePath] = newRemotePath
                                    withContext(Dispatchers.Main) {
                                        loadDirectory()
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    snack.dismiss()
                    if (localPaths.isNotEmpty()) {
                        startActivity(Intent(ctx, za.kilowatch.ultimatefilemanager.viewer.MusicTaggerActivity::class.java).apply {
                            putStringArrayListExtra(za.kilowatch.ultimatefilemanager.viewer.MusicTaggerActivity.EXTRA_FILE_PATHS, localPaths)
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

    fun isSelectionMode(): Boolean = fileAdapter.isSelectionMode
    fun getSelectedCount(): Int = fileAdapter.getSelectedFiles().size
    fun isAllSelected(): Boolean = fileAdapter.isAllSelected()
    fun selectAll() = fileAdapter.selectAll()
    fun deselectAll() = fileAdapter.deselectAll()
    fun getTabId(): String = arguments?.getString(ARG_TAB_ID) ?: ""

    private fun cacheNetworkFile(file: NetworkFile, onReady: (File) -> Unit) {
        val ctx = context ?: return
        val v = view ?: return
        val snack = com.google.android.material.snackbar.Snackbar.make(
            v, getString(R.string.opening_filename, file.name), com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
        snack.show()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val safeName = file.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                val cacheFile = File(ctx.cacheDir, "ufm_open_$safeName")
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
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    snack.dismiss()
                    onReady(cacheFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    snack.dismiss()
                    android.widget.Toast.makeText(requireContext(), R.string.error_generic, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkCopyToLocalAndProceed(file: NetworkFile, onProceed: (copyToLocal: Boolean) -> Unit) {
        val ctx = context ?: return
        // Read-only shares: output will be saved to Downloads — inform the user upfront.
        if (share.readOnly) {
            za.kilowatch.ultimatefilemanager.ui.UfmDialogHelper.showConfirmation(
                context = ctx,
                title = getString(R.string.dialog_media_op_readonly_title),
                message = getString(R.string.dialog_media_op_readonly_msg, share.name),
                iconRes = R.drawable.ic_direction_download,
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.cancel),
                onPositive = { onProceed(false) }
            )
            return
        }
        val copyPref = za.kilowatch.ultimatefilemanager.settings.NetworkMediaProcessPreferenceManager.isCopyBeforeProcessing(ctx)
        if (copyPref) {
            val sizeStr = android.text.format.Formatter.formatFileSize(ctx, file.size)
            za.kilowatch.ultimatefilemanager.ui.UfmDialogHelper.showConfirmation(
                context = ctx,
                title = getString(R.string.dialog_copy_before_process_title),
                message = getString(R.string.dialog_copy_before_process_msg, file.name, sizeStr),
                iconRes = R.drawable.ic_direction_download,
                positiveText = getString(R.string.action_copy_and_proceed),
                negativeText = getString(android.R.string.cancel),
                onPositive = { onProceed(true) }
            )
        } else {
            onProceed(false)
        }
    }

    private suspend fun openNetworkInputStreamForFile(file: NetworkFile): java.io.InputStream {
        return when (share.type) {
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
    }

    private fun launchNetworkMediaWorker(
        opType: String,
        file: NetworkFile,
        copyToLocal: Boolean,
        streamIndex: Int = -1,
        langTag: String? = null,
        universalAac: Boolean = false,
        title: String,
        iconRes: Int
    ) {
        val ctx = context ?: return
        val progress = za.kilowatch.ultimatefilemanager.media.MediaOperationProgressDialog(
            ctx,
            title,
            file.name,
            iconRes
        )
        progress.show()

        // For isServerMode SMB with empty remotePath, derive the effective share name from currentPath
        // so the worker can safely handle upload paths even at the server root boundary.
        val effectiveShareName = if (share.type == ShareType.SMB
            && share.isServerMode && share.remotePath.isEmpty() && currentPath.isNotEmpty()) {
            currentPath.trimStart('/').substringBefore('/')
        } else ""

        val workId = za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.enqueueNetwork(
            context = ctx,
            opType = opType,
            shareId = share.id,
            remoteFilePath = file.path,
            remoteDir = currentPath,
            fileName = file.name,
            fileSize = file.size,
            copyToLocal = copyToLocal,
            streamIndex = streamIndex,
            langTag = langTag,
            universalAac = universalAac,
            effectiveShareName = effectiveShareName
        )

        androidx.work.WorkManager.getInstance(ctx).getWorkInfoByIdLiveData(workId)
            .observe(viewLifecycleOwner) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            progress.dismiss()
                            if (!isAdded) return@observe
                            fileAdapter.exitSelectionMode()
                            val count = workInfo.outputData.getInt(za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.KEY_OUTPUT_COUNT, 1)
                            val name = workInfo.outputData.getString(za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.KEY_OUTPUT_NAME)
                            val msg = when (opType) {
                                za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.OP_CONVERT_TO_MP4 -> getString(R.string.convert_to_mp4_success, name ?: file.name)
                                za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.OP_EXTRACT_ALL_SUBTITLES -> getString(R.string.subtitles_extracted_multiple_success, count)
                                za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.OP_EXTRACT_SUBTITLES -> getString(R.string.subtitles_extracted_success) + (if (!name.isNullOrBlank()) "\n$name" else "")
                                za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.OP_EXTRACT_ALL_AUDIO -> getString(R.string.audio_extracted_multiple_success, count)
                                else -> getString(R.string.audio_extracted_success) + (if (!name.isNullOrBlank()) "\n$name" else "")
                            }
                            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
                            loadDirectory()
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            progress.dismiss()
                            if (!isAdded) return@observe
                            val err = workInfo.outputData.getString(za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.KEY_ERROR_MESSAGE)
                            android.widget.Toast.makeText(requireContext(), err ?: getString(R.string.error_generic), android.widget.Toast.LENGTH_SHORT).show()
                        }
                        androidx.work.WorkInfo.State.CANCELLED -> {
                            progress.dismiss()
                        }
                        else -> { /* In progress */ }
                    }
                }
            }
    }

    private fun extractSubtitlesFromNetworkVideo(file: NetworkFile) {
        checkCopyToLocalAndProceed(file) { copyToLocal ->
            val ctx = context ?: return@checkCopyToLocalAndProceed
            val progress = za.kilowatch.ultimatefilemanager.media.MediaOperationProgressDialog(
                ctx,
                getString(R.string.extracting_subtitles),
                file.name,
                R.drawable.ic_subtitles
            )
            progress.show()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val subStreams = if (copyToLocal) {
                        val safeName = file.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                        val cacheFile = File(ctx.cacheDir, "ufm_inspect_$safeName")
                        val inStream = openNetworkInputStreamForFile(file)
                        inStream.use { inp -> cacheFile.outputStream().use { out -> inp.copyTo(out) } }
                        val info = za.kilowatch.ultimatefilemanager.media.FFmpegMediaHelper.getMediaInfo(cacheFile)
                        cacheFile.delete()
                        info?.subtitleStreams ?: emptyList()
                    } else {
                        val mimeType = za.kilowatch.ultimatefilemanager.util.MimeTypeHelper.getOrFallback(file.name.substringAfterLast('.'))
                        val streamUrl = za.kilowatch.ultimatefilemanager.network.NetworkHttpProxyServer.register(share, file.path, mimeType, file.size)
                        val sessionUuid = streamUrl.substringAfter("127.0.0.1:").substringAfter('/').substringBefore('/')
                        val info = za.kilowatch.ultimatefilemanager.media.FFmpegMediaHelper.getMediaInfoFromPathOrUrl(streamUrl)
                        za.kilowatch.ultimatefilemanager.network.NetworkHttpProxyServer.unregister(sessionUuid)
                        info?.subtitleStreams ?: emptyList()
                    }

                    withContext(Dispatchers.Main) {
                        progress.dismiss()
                        if (!isAdded) return@withContext
                        if (subStreams.isEmpty()) {
                            android.widget.Toast.makeText(requireContext(), R.string.no_subtitles_found_in_video, android.widget.Toast.LENGTH_SHORT).show()
                            return@withContext
                        }

                        if (subStreams.size == 1) {
                            val stream = subStreams.first()
                            launchNetworkMediaWorker(
                                opType = za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.OP_EXTRACT_SUBTITLES,
                                file = file,
                                copyToLocal = copyToLocal,
                                streamIndex = stream.index,
                                langTag = stream.lang,
                                title = getString(R.string.extracting_subtitles),
                                iconRes = R.drawable.ic_subtitles
                            )
                        } else {
                            val sheet = za.kilowatch.ultimatefilemanager.viewer.SubtitleTrackBottomSheet.newInstance(file.name, subStreams)
                            sheet.onTrackSelected = { streamIndex, langTag, extractAll ->
                                val op = if (extractAll) {
                                    za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.OP_EXTRACT_ALL_SUBTITLES
                                } else {
                                    za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.OP_EXTRACT_SUBTITLES
                                }
                                launchNetworkMediaWorker(
                                    opType = op,
                                    file = file,
                                    copyToLocal = copyToLocal,
                                    streamIndex = streamIndex,
                                    langTag = langTag,
                                    title = getString(R.string.extracting_subtitles),
                                    iconRes = R.drawable.ic_subtitles
                                )
                            }
                            sheet.show(childFragmentManager, za.kilowatch.ultimatefilemanager.viewer.SubtitleTrackBottomSheet.TAG)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progress.dismiss()
                        if (!isAdded) return@withContext
                        android.widget.Toast.makeText(requireContext(), R.string.error_generic, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun extractAudioFromNetworkVideo(file: NetworkFile) {
        checkCopyToLocalAndProceed(file) { copyToLocal ->
            val ctx = context ?: return@checkCopyToLocalAndProceed
            val progress = za.kilowatch.ultimatefilemanager.media.MediaOperationProgressDialog(
                ctx,
                getString(R.string.extracting_audio),
                file.name,
                R.drawable.ic_audio
            )
            progress.show()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val audioStreams = if (copyToLocal) {
                        val safeName = file.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                        val cacheFile = File(ctx.cacheDir, "ufm_inspect_$safeName")
                        val inStream = openNetworkInputStreamForFile(file)
                        inStream.use { inp -> cacheFile.outputStream().use { out -> inp.copyTo(out) } }
                        val info = za.kilowatch.ultimatefilemanager.media.FFmpegMediaHelper.getMediaInfo(cacheFile)
                        cacheFile.delete()
                        info?.audioStreams ?: emptyList()
                    } else {
                        val mimeType = za.kilowatch.ultimatefilemanager.util.MimeTypeHelper.getOrFallback(file.name.substringAfterLast('.'))
                        val streamUrl = za.kilowatch.ultimatefilemanager.network.NetworkHttpProxyServer.register(share, file.path, mimeType, file.size)
                        val sessionUuid = streamUrl.substringAfter("127.0.0.1:").substringAfter('/').substringBefore('/')
                        val info = za.kilowatch.ultimatefilemanager.media.FFmpegMediaHelper.getMediaInfoFromPathOrUrl(streamUrl)
                        za.kilowatch.ultimatefilemanager.network.NetworkHttpProxyServer.unregister(sessionUuid)
                        info?.audioStreams ?: emptyList()
                    }

                    withContext(Dispatchers.Main) {
                        progress.dismiss()
                        if (!isAdded) return@withContext
                        if (audioStreams.isEmpty()) {
                            android.widget.Toast.makeText(requireContext(), R.string.no_audio_found_in_video, android.widget.Toast.LENGTH_SHORT).show()
                            return@withContext
                        }

                        val sheet = za.kilowatch.ultimatefilemanager.viewer.AudioTrackBottomSheet.newInstance(file.name, audioStreams)
                        sheet.onTrackSelected = { streamIndex, langTag, extractAll, universalM4a ->
                            val op = if (extractAll) {
                                za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.OP_EXTRACT_ALL_AUDIO
                            } else {
                                za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.OP_EXTRACT_AUDIO
                            }
                            launchNetworkMediaWorker(
                                opType = op,
                                file = file,
                                copyToLocal = copyToLocal,
                                streamIndex = streamIndex,
                                langTag = langTag,
                                universalAac = universalM4a,
                                title = getString(R.string.extracting_audio),
                                iconRes = R.drawable.ic_audio
                            )
                        }
                        sheet.show(childFragmentManager, za.kilowatch.ultimatefilemanager.viewer.AudioTrackBottomSheet.TAG)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progress.dismiss()
                        if (!isAdded) return@withContext
                        android.widget.Toast.makeText(requireContext(), R.string.error_generic, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun convertNetworkVideoToMp4(file: NetworkFile) {
        val ctx = context ?: return
        za.kilowatch.ultimatefilemanager.ui.UfmDialogHelper.showConfirmation(
            context = ctx,
            title = getString(R.string.convert_to_mp4_title),
            message = getString(R.string.convert_to_mp4_confirm, file.name),
            iconRes = R.drawable.ic_convert_video,
            positiveText = getString(R.string.action_convert_to_mp4),
            negativeText = getString(android.R.string.cancel),
            onPositive = {
                checkCopyToLocalAndProceed(file) { copyToLocal ->
                    launchNetworkMediaWorker(
                        opType = za.kilowatch.ultimatefilemanager.media.MediaOperationWorker.OP_CONVERT_TO_MP4,
                        file = file,
                        copyToLocal = copyToLocal,
                        title = getString(R.string.convert_to_mp4_progress),
                        iconRes = R.drawable.ic_convert_video
                    )
                }
            }
        )
    }

    interface NetworkOperationsListener {
        fun onNetworkCopyRequested(fragment: NetworkBrowserFragment, files: List<NetworkFile>)
        fun onNetworkMoveRequested(fragment: NetworkBrowserFragment, files: List<NetworkFile>)
        fun onNetworkDeleteRequested(fragment: NetworkBrowserFragment, files: List<NetworkFile>)
        fun onNetworkPasteRequested(fragment: NetworkBrowserFragment, destinationPath: String)
        fun onNetworkSelectionChanged(fragment: NetworkBrowserFragment, isSelectionMode: Boolean, count: Int, isAllSelected: Boolean) {}
    }
}
