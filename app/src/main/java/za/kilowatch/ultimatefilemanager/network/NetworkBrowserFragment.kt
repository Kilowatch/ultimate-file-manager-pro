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
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
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

class NetworkBrowserFragment : Fragment() {

    companion object {
        const val ARG_SHARE_ID = "share_id"
        const val ARG_INITIAL_PATH = "initial_path"
        const val ARG_IS_TWIN_WINDOW = "is_twin_window"
        const val ARG_REQUEST_INITIAL_FOCUS = "request_initial_focus"

        fun newInstance(
            shareId: String,
            initialPath: String = "",
            isTwinWindow: Boolean = false,
            requestInitialFocus: Boolean = false
        ): NetworkBrowserFragment {
            return NetworkBrowserFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SHARE_ID, shareId)
                    putString(ARG_INITIAL_PATH, initialPath)
                    putBoolean(ARG_IS_TWIN_WINDOW, isTwinWindow)
                    putBoolean(ARG_REQUEST_INITIAL_FOCUS, requestInitialFocus)
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
    private var activeTagsFilter: Set<String> = emptySet()
    private var currentFiles: List<NetworkFile> = emptyList()
    private var layoutSelectionBar: View? = null
    private var txtSelectionCount: TextView? = null
    private var layoutEmpty: View? = null
    private var fabPaste: ExtendedFloatingActionButton? = null
    private var fabProperties: ExtendedFloatingActionButton? = null
    private var fabTools: ExtendedFloatingActionButton? = null
    private var fabSelectAll: ExtendedFloatingActionButton? = null
    private lateinit var cacheManager: za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager
    private var btnOptionsToggle: View? = null
    private var layoutOptionsRow: LinearLayout? = null
    private var isOptionsVisible = false

    private val batchRenameTvLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            fileAdapter.exitSelectionMode()
            loadDirectory()
        }
    }

    // Twin Window specific
    var onStoragePickerRequested: (() -> Unit)? = null
    var onActionRequested: ((String) -> Unit)? = null
    var onFileSelected: ((NetworkFile) -> Unit)? = null
    var onMediaFileSelected: ((NetworkFile) -> Unit)? = null
    var onCloseTwinWindow: (() -> Unit)? = null
    var onSelectionChanged: ((List<NetworkFile>) -> Unit)? = null
    var onInvalidShare: (() -> Unit)? = null

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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveCurrentFolderScroll()
        outState.putBundle("KEY_FOLDER_SCROLL_STATES", FolderScrollState.toBundle(folderScrollStates))
    }

    fun updateFabPositions() {
        val ctx = context ?: return
        val isToolsVisible = fabTools?.visibility == View.VISIBLE
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
                } else {
                    lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                }
                fab.layoutParams = lp
            }
            return
        }

        val isLeftHanded = za.kilowatch.ultimatefilemanager.settings.LeftHandedFabPreferenceManager.isLeftHanded(ctx)

        fabTools?.let { fab ->
            val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
            lp.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.bottomToTop = R.id.layoutActionPillsScroll
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            if (isLeftHanded) {
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            } else {
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            }
            fab.layoutParams = lp
        }

        fabPaste?.let { fab ->
            val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
            lp.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            if (isToolsVisible) {
                lp.bottomToTop = R.id.fabTools
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            } else {
                lp.bottomToTop = R.id.layoutActionPillsScroll
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            }
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
        progressBar = view.findViewById(R.id.progressBar)
        txtTitle = view.findViewById(R.id.txtTitle)
        txtSubtitle = view.findViewById(R.id.txtSubtitle)
        layoutSelectionBar = view.findViewById(R.id.layoutSelectionBar)
        txtSelectionCount = view.findViewById(R.id.txtSelectionCount)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        fabPaste = view.findViewById(R.id.fabPaste)
        fabTools = view.findViewById(R.id.fabTools)
        fabSelectAll = view.findViewById(R.id.fabSelectAll)
        fabSelectAll?.setOnClickListener {
            if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
        }
        fabPaste?.setOnClickListener {
            val act = activity
            if (act is TwinWindowActivity) {
                act.onPasteRequested(this)
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
                    }
                    fileAdapter.exitSelectionMode()
                })
            }

            // 2. Move (Cut)
            if (pm.isIconEnabled(context, pm.KEY_MOVE)) {
                list.add(FileToolsBottomSheet.ActionItem("move", getString(R.string.action_move), R.drawable.ic_move, "toolbar_move") {
                    if (isTwinWindow) {
                        onActionRequested?.invoke("move")
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
                            dialog.setOnCompleteListener { _, _ ->
                                fileAdapter.exitSelectionMode()
                                loadDirectory()
                            }
                            dialog.show(parentFragmentManager, BatchRenameDialogFragment.TAG)
                        }
                    }
                })
            }
            // Create GIF (Requires 2+ images)
            val allNetworkImages = selected.isNotEmpty() && selected.all {
                it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            }
            val canCreateGif = selected.size >= 2 && allNetworkImages
            if (canCreateGif && pm.isIconEnabled(requireContext(), pm.KEY_CREATE_GIF)) {
                list.add(FileToolsBottomSheet.ActionItem("create_gif", getString(R.string.action_create_gif), R.drawable.ic_gif, "toolbar_create_gif") {
                    val netImages = selected.filter {
                        it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                    }
                    (activity as? NetworkBrowserActivity)?.downloadNetworkImagesAndCreateGif(netImages)
                })
            }

            // Photo EXIF Cleaner & Renamer (Mobile Only)
            if (allNetworkImages && !DeviceUtils.isTvDevice(requireContext()) && pm.isIconEnabled(context, pm.KEY_EXIF_TOOLS)) {
                list.add(FileToolsBottomSheet.ActionItem("exif_tools", getString(R.string.action_exif_cleaner_renamer), R.drawable.ic_exif_cleaner, "toolbar_exif_cleaner") {
                    val netImages = selected.filter {
                        it.name.substringAfterLast('.').lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                    }
                    (activity as? NetworkBrowserActivity)?.downloadNetworkImagesAndLaunchExifTools(netImages)
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

            if (list.isNotEmpty()) {
                val title = getString(R.string.action_tools)
                val subtitle = getString(R.string.selection_count, selected.size)
                val sheet = FileToolsBottomSheet.newInstance(list, title, subtitle)
                sheet.show(parentFragmentManager, FileToolsBottomSheet.TAG)
            }
        }
        
        btnSearchToggle = view.findViewById(R.id.btnSearchToggle)
        btnSearchToggle?.setImageResource(R.drawable.ic_search)
        if (isTv) {
            btnSearchToggle?.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.ufm_denied))
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
        view.findViewById<View>(R.id.btnRefresh)?.setOnClickListener { loadDirectory() }
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
                    dialog.setOnCompleteListener { _, _ ->
                        fileAdapter.exitSelectionMode()
                        loadDirectory()
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
                    folderScrollStates.remove(file.path)
                    currentPath = file.path
                    loadDirectory()
                } else {
                    val ext = file.name.substringAfterLast(".").lowercase()
                    if (onMediaFileSelected != null && (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(ext) || za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(ext))) {
                        onMediaFileSelected!!(file)
                    } else {
                        onFileSelected?.invoke(file)
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
                val files = kotlinx.coroutines.withTimeout(15_000L) {
                    if (share.type == ShareType.SMB && share.isServerMode) {
                        if (currentPath.isEmpty()) {
                            discoverServerShares(share)
                        } else {
                            // Inside a discovered share
                            val existingShare = share.remotePath.trimStart('/')
                            if (existingShare.isNotEmpty()) {
                                // Already navigated into a share — currentPath is relative to share root
                                val innerPath = stripSharePrefix(currentPath.trimStart('/'))
                                SmbShareClient.listFiles(share, innerPath).filter { it.name != ".." }
                            } else {
                                // First navigation into a share — extract share name from currentPath
                                val parts = currentPath.trimStart('/').split("/", limit = 2)
                                val shareName = parts[0]
                                val innerPath = parts.getOrElse(1) { "" }
                                // Update share to the effective copy so all file operations
                                // (copy, delete, rename, etc.) use the correct remotePath
                                share = share.copy(remotePath = "/$shareName")
                                withContext(Dispatchers.Main) {
                                    fileAdapter.share = share
                                }
                                SmbShareClient.listFiles(share, innerPath).filter { it.name != ".." }
                            }
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
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    val errMsg = if (e is kotlinx.coroutines.TimeoutCancellationException) {
                        getString(R.string.network_connection_restored_first)
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

    private fun updateSelectionUI(count: Int) {
        val showSelection = fileAdapter.isSelectionMode
        val isTv = DeviceUtils.isTvDevice(requireContext())
        if (!isTv) {
            val layoutHeaderNormal = view?.findViewById<View>(R.id.layoutHeaderNormal)
            val layoutHeaderSelection = view?.findViewById<View>(R.id.layoutHeaderSelection)
            val btnSelectAll = view?.findViewById<View>(R.id.btnSelectAll)

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

                fabTools?.visibility = if (showActions) View.VISIBLE else View.GONE
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

        val lastSlash = currentPath.lastIndexOf('/')
        lastExitedPath = currentPath
        currentPath = if (lastSlash <= 0) {
            // Going back from the last path segment.
            val trimmedPath = currentPath.trimStart('/')
            val shareName = share.remotePath.trimStart('/')
            if (share.isServerMode && shareName.isNotEmpty() && trimmedPath != shareName) {
                // At a subfolder level — go to share root (show share's contents).
                // Use share name so loadDirectory enters the already-navigated branch
                // instead of the "discover shares" root path.
                "/$shareName"
            } else {
                // At share root or non-server mode — go back to root (server root or empty).
                if (share.isServerMode) {
                    share = share.copy(remotePath = originalRemotePath)
                    fileAdapter.share = share
                }
                ""
            }
        } else {
            currentPath.substring(0, lastSlash)
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

    private fun showCreateTextFileDialog() {
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

    private fun showCreateFolderDialog() {
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
                            loadDirectory() 
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

    private fun setupTvFocus(view: View) {
        val iconTintFocused = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_button_focused_yellow_text))
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
                    requireContext().getColor(
                        if (hasOverride) R.color.tv_button_focused_yellow else R.color.tv_text_primary
                    )
                )
            }
        }

        wireTvIconBtn(view.findViewById(R.id.btnRefresh)) { loadDirectory() }
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
        btnSearch?.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.ufm_denied))

        listOf(
            R.id.btnCloseSelection, R.id.btnCopy, R.id.btnMove, R.id.btnRename, R.id.btnFavorite,
            R.id.btnShare, R.id.btnCopyEncrypt, R.id.btnMoveEncrypt, R.id.btnProtect, R.id.btnUnprotect,
        R.id.btnPin, R.id.btnUnpin,
            R.id.btnRetriggerThumbnails
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
                    btn.setBackgroundColor(requireContext().getColor(R.color.tv_button_focused_yellow))
                    btn.setTextColor(requireContext().getColor(R.color.tv_button_focused_yellow_text))
                    btn.iconTint = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_button_focused_yellow_text))
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
            val colorRes = if (isSearchVisible) R.color.ufm_granted else R.color.ufm_denied
            btnToggle.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(colorRes))
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

    private fun performSearch(query: String) {
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
        val total = (if (hasLocal) za.kilowatch.ultimatefilemanager.storage.FileClipboard.files.size else 0) + (if (hasNet) NetworkClipboard.files.size else 0)

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
            if (filterType == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.ALL) true
            else if (file.isDirectory) true
            else {
                val ext = file.name.substringAfterLast(".").lowercase()
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.matchesExtension(ext, filterType)
            }
        }
        val tagFiltered = if (activeTagsFilter.isNotEmpty()) {
            val ctx = context ?: return filtered
            filtered.filter { it.isDirectory || za.kilowatch.ultimatefilemanager.storage.FileTagsManager.getTags(ctx, it.path).any { t -> t in activeTagsFilter } }
        } else {
            filtered
        }
        val secondaryComparator: Comparator<NetworkFile> = when (sortMode) {
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.NAME -> compareBy(NaturalSort.order) { f: NetworkFile -> f.name }
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.SIZE -> compareBy { f: NetworkFile -> if (f.isDirectory) 0L else f.size }
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.DATE -> compareBy { f: NetworkFile -> f.lastModified }
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { f: NetworkFile -> f.name.substringAfterLast('.', "") }
        }
        val orderedComparator = if (sortOrder == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortOrder.DESC) secondaryComparator.reversed() else secondaryComparator
        
        val customComparator = Comparator<NetworkFile> { f1, f2 ->
            val ctx = context ?: return@Comparator NaturalSort.naturalCompare(f1.name, f2.name)
            val p1 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(ctx.applicationContext, f1.path, share.id)
            val p2 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(ctx.applicationContext, f2.path, share.id)
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
                ctx.getColor(if (isTv) za.kilowatch.ultimatefilemanager.R.color.tv_button_focused_yellow else za.kilowatch.ultimatefilemanager.R.color.ufm_primary))
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
}
