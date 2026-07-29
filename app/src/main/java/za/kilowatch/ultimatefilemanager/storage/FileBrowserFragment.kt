package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor
import za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.ui.PremiumShareActivity
import za.kilowatch.ultimatefilemanager.ui.PremiumShareTvActivity
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import java.io.File
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager

/**
 * Reusable Fragment for browsing files.
 * Extracted from FileBrowserActivity to support Twin Window (dual-pane) mode.
 */
class FileBrowserFragment : Fragment() {

    private lateinit var recyclerFiles: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private var lottieEmptyFolder: com.airbnb.lottie.LottieAnimationView? = null
    private lateinit var layoutSelectionBar: LinearLayout
    private lateinit var layoutActionPills: View
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
    private lateinit var btnCompress: View
    private lateinit var btnImageCompress: View
    private var btnViewToggle: ImageView? = null
    private var btnSort: ImageView? = null
    private var btnOptionsToggle: ImageView? = null
    private var layoutOptionsRow: View? = null
    private var isOptionsVisible = false
    private var fabPaste: ExtendedFloatingActionButton? = null
    private var fabProperties: ExtendedFloatingActionButton? = null
    private var fabTools: ExtendedFloatingActionButton? = null
    private var fabSelectAll: ExtendedFloatingActionButton? = null
    private var btnRetriggerThumbnails: ImageView? = null
    private var btnDuplicateFinder: ImageView? = null
    private var btnLargeFilesFinder: ImageView? = null
    private lateinit var fileAdapter: FileAdapter


    private val batchRenameTvLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            fileAdapter.exitSelectionMode()
            refresh()
        }
    }

    private val folderDuplicateFinderLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            fileAdapter.exitSelectionMode()
            triggerReindex()
        }
    }

    private val folderLargeFilesFinderLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            fileAdapter.exitSelectionMode()
            triggerReindex()
        }
    }


    
    private var btnSearchToggle: ImageView? = null
    private var layoutSearchRow: LinearLayout? = null
    private var edtSearch: EditText? = null
    private var btnSearchClear: ImageView? = null
    private var isSearchVisible = false
    private var searchJob: Job? = null

    private lateinit var rootPath: String
    private lateinit var currentDir: File
    private var storageLabel: String = ""
    private var storageId: String = ""
    private var storageType: String = ""
    private var labelPrefix: String = ""
    private var hideBack: Boolean = false
    private var isTwinWindow: Boolean = false
    private var isCompactMode: Boolean = false   // true when mobile + twin window + vertical split

    private var sortMode = SortFilterSheet.SortMode.NAME
    private var sortOrder = SortFilterSheet.SortOrder.ASC
    private var filterType = SortFilterSheet.FilterType.ALL
    private var activeTagsFilter: Set<String> = emptySet()

    private var isPickerMode = false
    var onStoragePickerRequested: (() -> Unit)? = null
    var onActionRequested: ((String) -> Unit)? = null
    var onSwitchToApps: (() -> Unit)? = null  // set only on the left pane for local storage
    var onMediaFileSelected: ((File) -> Unit)? = null
    var onCloseTwinWindow: (() -> Unit)? = null
    private var folderFlowJob: Job? = null
    private var lastExitedDir: File? = null
    private var shouldRestoreFocus = false

    companion object {
        private const val ARG_MOUNT_PATH = "arg_mount_path"
        private const val ARG_STORAGE_LABEL = "arg_storage_label"
        private const val ARG_PICKER_MODE = "arg_picker_mode"
        private const val ARG_LABEL_PREFIX = "arg_label_prefix"
        private const val ARG_HIDE_BACK = "arg_hide_back"
        private const val ARG_IS_TWIN_WINDOW = "arg_is_twin_window"
        private const val ARG_INITIAL_PATH = "arg_initial_path"
        private const val ARG_REQUEST_INITIAL_FOCUS = "arg_request_initial_focus"

        fun newInstance(
            mountPath: String,
            label: String,
            isPicker: Boolean = false,
            labelPrefix: String = "",
            hideBack: Boolean = false,
            isTwinWindow: Boolean = true,
            initialPath: String = "",
            requestInitialFocus: Boolean = false
        ): FileBrowserFragment {
            return FileBrowserFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MOUNT_PATH, mountPath)
                    putString(ARG_STORAGE_LABEL, label)
                    putBoolean(ARG_PICKER_MODE, isPicker)
                    putString(ARG_LABEL_PREFIX, labelPrefix)
                    putBoolean(ARG_HIDE_BACK, hideBack)
                    putBoolean(ARG_IS_TWIN_WINDOW, isTwinWindow)
                    if (initialPath.isNotEmpty()) putString(ARG_INITIAL_PATH, initialPath)
                    putBoolean(ARG_REQUEST_INITIAL_FOCUS, requestInitialFocus)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val isTwinWindowArg = arguments?.getBoolean(ARG_IS_TWIN_WINDOW, false) == true
        val isVerticalSplit = !isTv && isTwinWindowArg &&
            za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(requireContext())
        isCompactMode = isVerticalSplit
        return inflater.inflate(
            when {
                isTv            -> R.layout.fragment_file_browser_tv
                isVerticalSplit -> R.layout.fragment_file_browser_compact
                else            -> R.layout.fragment_file_browser
            },
            container, false
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val internalLabel = getString(R.string.storage_internal)

        var mount = arguments?.getString(ARG_MOUNT_PATH) ?: ""
        var label = arguments?.getString(ARG_STORAGE_LABEL) ?: internalLabel
        if (mount.isEmpty() || !File(mount).exists()) {
            mount = internalPath
            label = internalLabel
        }
        rootPath = mount
        storageLabel = label
        isPickerMode = arguments?.getBoolean(ARG_PICKER_MODE, false) == true
        labelPrefix = arguments?.getString(ARG_LABEL_PREFIX) ?: ""
        hideBack = arguments?.getBoolean(ARG_HIDE_BACK, false) == true
        isTwinWindow = arguments?.getBoolean(ARG_IS_TWIN_WINDOW, false) == true

        val initialPath = arguments?.getString(ARG_INITIAL_PATH)
        currentDir = when {
            !initialPath.isNullOrEmpty() && File(initialPath).exists() -> File(initialPath)
            File(rootPath).exists() -> File(rootPath)
            else -> {
                rootPath = internalPath
                storageLabel = internalLabel
                File(internalPath)
            }
        }

        // Resolve storage ID/Type for indexing
        val resolved = IndexingRepository.resolveStorageForPath(rootPath)
        storageId = resolved.first
        storageType = resolved.second

        // Restore sort preferences — prefer folder-specific, fall back to global
        // Read synchronously on the main thread using the lightweight global prefs;
        // encrypted folder prefs are initialised lazily on first background access.
        val globalState = SortFilterPreferenceManager.loadGlobal(requireContext())
        sortMode  = globalState.sortMode
        sortOrder = globalState.sortOrder
        filterType = globalState.filterType
        // Folder-specific overrides are applied in loadDirectory() on the IO thread.

        setupViews(view)
        loadDirectory(currentDir)
    }

    override fun onResume() {
        super.onResume()
        applyLeftHandedFabSettings()
        applyToolbarIconVisibility()
        updatePasteFab()
        // Refresh file list on return from child activities (e.g. Settings toggle)
        if (::currentDir.isInitialized) {
            loadDirectory(currentDir)
        }
    }

    private fun applyLeftHandedFabSettings() {
        val ctx = context ?: return
        val viewsToUpdate = mutableListOf<android.view.View>()
        fabPaste?.let { viewsToUpdate.add(it) }
        fabTools?.let { viewsToUpdate.add(it) }
        fabSelectAll?.let { viewsToUpdate.add(it) }

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
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                fab.layoutParams = lp
            }
            fabS?.let { fab ->
                val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return@let
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.bottomToTop = R.id.fabTools
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
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
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                fab.layoutParams = lp
            }
            return
        }

        val isLeftHanded = za.kilowatch.ultimatefilemanager.settings.LeftHandedFabPreferenceManager.isLeftHanded(ctx)
        for (fab in viewsToUpdate) {
            val lp = fab.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: continue
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
    }

    private fun applyToolbarIconVisibility() {
        val context = context ?: return
        if (!::btnCopy.isInitialized) return
        
        val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
        btnCopy.visibility = if (pm.isIconEnabled(context, pm.KEY_COPY)) View.VISIBLE else View.GONE
        btnMove.visibility = if (pm.isIconEnabled(context, pm.KEY_MOVE)) View.VISIBLE else View.GONE
        btnRename.visibility = if (pm.isIconEnabled(context, pm.KEY_RENAME)) View.VISIBLE else View.GONE
        btnShare.visibility = if (pm.isIconEnabled(context, pm.KEY_SHARE)) View.VISIBLE else View.GONE
        btnCopyEncrypt.visibility = if (pm.isIconEnabled(context, pm.KEY_COPY_ENCRYPT)) View.VISIBLE else View.GONE
        btnMoveEncrypt.visibility = if (pm.isIconEnabled(context, pm.KEY_MOVE_ENCRYPT)) View.VISIBLE else View.GONE
        btnFavorite.visibility = if (pm.isIconEnabled(context, pm.KEY_FAVORITE)) View.VISIBLE else View.GONE
        btnHide.visibility = if (pm.isIconEnabled(context, pm.KEY_HIDE)) View.VISIBLE else View.GONE
        btnUnhide.visibility = if (pm.isIconEnabled(context, pm.KEY_UNHIDE)) View.VISIBLE else View.GONE
        btnProtect.visibility = if (pm.isIconEnabled(context, pm.KEY_PROTECT)) View.VISIBLE else View.GONE
        btnUnprotect.visibility = if (pm.isIconEnabled(context, pm.KEY_UNPROTECT)) View.VISIBLE else View.GONE
        btnPin?.visibility = if (pm.isIconEnabled(context, pm.KEY_PIN)) View.VISIBLE else View.GONE
        btnUnpin?.visibility = if (pm.isIconEnabled(context, pm.KEY_UNPIN)) View.VISIBLE else View.GONE
        btnCompress.visibility = if (pm.isIconEnabled(context, pm.KEY_COMPRESS)) View.VISIBLE else View.GONE
        btnImageCompress.visibility = View.GONE
        btnSelectAll.visibility = if (pm.isIconEnabled(context, pm.KEY_SELECT_ALL)) View.VISIBLE else View.GONE
        btnDelete.visibility = if (pm.isIconEnabled(context, pm.KEY_DELETE)) View.VISIBLE else View.GONE
        btnRetriggerThumbnails?.visibility = if (pm.isIconEnabled(context, pm.KEY_RETRIGGER_THUMBNAILS)) View.VISIBLE else View.GONE

        val isIndexed = UfmApplication.indexingRepository.isStorageFullyIndexed(storageId)
        view?.findViewById<View>(R.id.btnRefreshIndex)?.visibility = if (isIndexed) View.VISIBLE else View.GONE
    }

    private fun setupViews(view: View) {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        recyclerFiles = view.findViewById(R.id.recyclerFiles)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        lottieEmptyFolder = view.findViewById(R.id.lottieEmptyFolder)
        layoutSelectionBar = view.findViewById(R.id.layoutSelectionBar)
        layoutActionPills = view.findViewById(R.id.layoutActionPillsScroll)
        txtSelectionCount = view.findViewById(R.id.txtSelectionCount)
        btnCloseSelection = view.findViewById(R.id.btnCloseSelection)
        btnSelectAll = view.findViewById(R.id.btnSelectAll)
        btnDelete = view.findViewById(R.id.btnDelete)
        btnCopy = view.findViewById(R.id.btnCopy)
        btnMove = view.findViewById(R.id.btnMove)
        btnRename = view.findViewById(R.id.btnRename)
        btnShare = view.findViewById(R.id.btnShare)
        btnFavorite = view.findViewById(R.id.btnFavorite)
        btnCopyEncrypt = view.findViewById(R.id.btnCopyEncrypt)
        btnMoveEncrypt = view.findViewById(R.id.btnMoveEncrypt)
        btnRetriggerThumbnails = view.findViewById(R.id.btnRetriggerThumbnails)
        btnDuplicateFinder = view.findViewById(R.id.btnDuplicateFinder)
        btnLargeFilesFinder = view.findViewById(R.id.btnLargeFilesFinder)

        btnHide = view.findViewById(R.id.btnHide)
        btnUnhide = view.findViewById(R.id.btnUnhide)
        btnProtect = view.findViewById(R.id.btnProtect)
        btnUnprotect = view.findViewById(R.id.btnUnprotect)
        btnPin = view.findViewById(R.id.btnPin)
        btnUnpin = view.findViewById(R.id.btnUnpin)
        btnCompress = view.findViewById(R.id.btnCompress)
        btnImageCompress = view.findViewById(R.id.btnImageCompress)
        fabPaste = view.findViewById(R.id.fabPaste)
        fabTools = view.findViewById(R.id.fabTools)
        fabSelectAll = view.findViewById(R.id.fabSelectAll)
        fabSelectAll?.setOnClickListener {
            if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
        }
        
        btnSearchToggle = view.findViewById(R.id.btnSearchToggle)
        btnSearchToggle?.setImageResource(R.drawable.ic_search) 
        btnSearchToggle?.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.ufm_denied))
        layoutSearchRow = view.findViewById(R.id.layoutSearchRow)
        edtSearch = view.findViewById(R.id.edtSearch)
        btnSearchClear = view.findViewById(R.id.btnSearchClear)

        val btnBack = view.findViewById<ImageView>(R.id.btnTvBack)
        // In twin-window mode: hide the decorative pipe dividers on BOTH rows
        // Row 1: the | between back button and the rest of the header
        // Row 2 (options row): the spacer + | that align with the back button width
        if (isTwinWindow) {
            view.findViewById<View>(R.id.divider_back)?.visibility = View.GONE
            view.findViewById<View>(R.id.spacerOptions)?.visibility = View.GONE
            view.findViewById<View>(R.id.dividerOptions)?.visibility = View.GONE
        }
        if (hideBack) {
            btnBack?.visibility = View.GONE
            view.findViewById<View>(R.id.spacerHeaderFlex)?.visibility = View.GONE
        } else {
            btnBack?.setOnClickListener { navigateBack() }
        }

        // Apps-switch button: visible only when onSwitchToApps callback is wired (left local pane)
        val btnAppsSwitch = view.findViewById<ImageView?>(R.id.btnAppsSwitch)
        if (onSwitchToApps != null) {
            btnAppsSwitch?.visibility = View.VISIBLE
            btnAppsSwitch?.setOnClickListener { onSwitchToApps?.invoke() }
        } else {
            btnAppsSwitch?.visibility = View.GONE
        }

        // Close Twin Window button: visible only in twin window mode
        val btnCloseTwin = view.findViewById<ImageView>(R.id.btnCloseTwin)
        if (btnCloseTwin != null) {
            btnCloseTwin.visibility = if (isTwinWindow) View.VISIBLE else View.GONE
            btnCloseTwin.setOnClickListener { onCloseTwinWindow?.invoke() }
        }

        view.findViewById<ImageView>(R.id.btnCreateNew)?.setOnClickListener { showCreateNewMenu() }
        view.findViewById<ImageView>(R.id.btnRefreshIndex)?.setOnClickListener { triggerReindex() }
        view.findViewById<View>(R.id.btnDrivePicker)?.setOnClickListener { 
            if (isTwinWindow) {
                onStoragePickerRequested?.invoke()
            } else {
                showDrivePicker() 
            }
        }

        // Action Pills
        view.findViewById<View>(R.id.btnPillSelectAll)?.setOnClickListener {
            if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
        }
        view.findViewById<View>(R.id.btnPillCopy)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                if (isTwinWindow) {
                    onActionRequested?.invoke("copy")
                } else {
                    (activity as? FileOperationsListener)?.onCopyRequested(this, selected)
                }
                fileAdapter.exitSelectionMode()
            }
        }
        view.findViewById<View>(R.id.btnPillMove)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                if (isTwinWindow) {
                    onActionRequested?.invoke("move")
                } else {
                    (activity as? FileOperationsListener)?.onMoveRequested(this, selected)
                }
                fileAdapter.exitSelectionMode()
            }
        }
        view.findViewById<View>(R.id.btnPillDelete)?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                if (isTwinWindow) {
                    onActionRequested?.invoke("delete")
                } else {
                    (activity as? FileOperationsListener)?.onDeleteRequested(this, selected)
                }
                fileAdapter.exitSelectionMode()
            }
        }
        
        btnViewToggle = view.findViewById(R.id.btnViewToggle)
        btnSort = view.findViewById(R.id.btnSort)
        btnOptionsToggle = view.findViewById(R.id.btnOptionsToggle)
        layoutOptionsRow = view.findViewById(R.id.layoutOptionsRow)

        val prefs = requireContext().getSharedPreferences("ufm_prefs", android.content.Context.MODE_PRIVATE)
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
                    performSearch(query)
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

        if (isTwinWindow) {
            // In twin window, we normally hide these, but with the new row and toggle, 
            // we'll follow the gear icon's state by default.
            // If the user wants them hidden in Twin window ALWAYS, we could force it here.
            // But let's allow the toggle to work.
        } else {
            // Already handled in layoutOptionsRow visibility
        }

        if (!isTv) {
            btnViewToggle?.setOnClickListener {
                ViewModeManager.showSelectionDialog(requireContext(), fileAdapter.viewMode) { selectedMode ->
                    val folderKey = SortFilterPreferenceManager.folderKey(currentDir.absolutePath)
                    if (SortFilterPreferenceManager.hasFolderOverride(requireContext(), currentDir.absolutePath)) {
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val state = SortFilterPreferenceManager.loadForPath(requireContext(), currentDir.absolutePath)
                            if (state != null) {
                                SortFilterPreferenceManager.saveFolderSpecific(
                                    requireContext(), folderKey, currentDir.absolutePath,
                                    state.copy(viewMode = selectedMode), isNetwork = false
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
        btnSort?.setOnClickListener { showSortFilterSheet() }

        fileAdapter = FileAdapter(
            isTv = isTv,
            isCompact = isCompactMode,
            onItemClick = { file, transitionView ->
                if (file.isDirectory) {
                    loadDirectory(file)
                } else {
                    openFile(file, transitionView)
                }
            },
            onSelectionChanged = { count ->
                updateSelectionBar(count)
            }
        )

        fileAdapter.isGroupedByDate = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(requireContext())
        recyclerFiles.adapter = fileAdapter
        val initialMode = ViewModeManager.load(requireContext())
        applyViewMode(initialMode)

        // TV: intercept DPAD_CENTER long-press at the RecyclerView level.
        // Individual item setOnLongClickListener / setOnKeyListener are unreliable on TV
        // because the RecyclerView may never forward the raw key events to the focused child.
        // Listening here is guaranteed — the RecyclerView always receives key events.
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
                                    // Null self out first — ACTION_UP checks this to decide
                                    // whether to consume the event and block the follow-up click.
                                    tvLongPressRunnable = null
                                    // Find the currently focused child in the RecyclerView
                                    val focusedChild = recyclerFiles.focusedChild ?: return@Runnable
                                    val position = recyclerFiles.getChildAdapterPosition(focusedChild)
                                    if (position == RecyclerView.NO_ID.toInt()) return@Runnable
                                    focusedChild.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    fileAdapter.enterSelectionModeAt(position)
                                }
                                tvLongPressHandler.postDelayed(tvLongPressRunnable!!, durationMs)
                            }
                            false // don't consume: short press still navigates/clicks normally
                        }
                        KeyEvent.ACTION_UP -> {
                            if (tvLongPressRunnable != null) {
                                // Short press — timer hasn't fired: cancel it, let click through
                                tvLongPressHandler.removeCallbacks(tvLongPressRunnable!!)
                                tvLongPressRunnable = null
                                false
                            } else {
                                // Long press already fired — consume ACTION_UP to block
                                // the follow-up click that would toggle selection back off.
                                true
                            }
                        }
                        else -> false
                    }
                } else false
            }
        }

        // Selection actions
        btnCloseSelection.setOnClickListener { fileAdapter.exitSelectionMode() }
        btnSelectAll.setOnClickListener {
            if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
        }
        
        btnDelete.setOnClickListener { (activity as? FileOperationsListener)?.onDeleteRequested(this, fileAdapter.getSelectedFiles()) }
        btnCopy.setOnClickListener { (activity as? FileOperationsListener)?.onCopyRequested(this, fileAdapter.getSelectedFiles()) }
        btnMove.setOnClickListener { (activity as? FileOperationsListener)?.onMoveRequested(this, fileAdapter.getSelectedFiles()) }
        btnRename.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isEmpty()) return@setOnClickListener
            if (selected.size == 1) {
                (activity as? FileOperationsListener)?.onRenameRequested(this, selected.first())
            } else {
                val items = selected.map { BatchRenameItem.fromLocalFile(it) }
                if (DeviceUtils.isTvDevice(requireContext())) {
                    val intent = android.content.Intent(requireContext(), BatchRenameTvActivity::class.java).apply {
                        putParcelableArrayListExtra("items", java.util.ArrayList(items))
                    }
                    batchRenameTvLauncher.launch(intent)
                } else {
                    val dialog = BatchRenameDialogFragment.newInstance(items)
                    dialog.setOnCompleteListener { _, _ ->
                        fileAdapter.exitSelectionMode()
                        refresh()
                    }
                    dialog.show(parentFragmentManager, BatchRenameDialogFragment.TAG)
                }
            }
        }
        btnShare.setOnClickListener { shareFiles(fileAdapter.getSelectedFiles()) }

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
                        refresh()
                        context?.let { ctx ->
                            android.widget.Toast.makeText(ctx, getString(R.string.retrigger_thumbnails_success), android.widget.Toast.LENGTH_SHORT).show()
                        }
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
                    val intent = android.content.Intent(requireContext(), FolderDuplicateFinderActivity::class.java).apply {
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
                    val intent = android.content.Intent(requireContext(), FolderLargeFilesFinderActivity::class.java).apply {
                        putExtra(FolderLargeFilesFinderActivity.EXTRA_FOLDER_PATH, targetFolder.absolutePath)
                        putExtra(FolderLargeFilesFinderActivity.EXTRA_STORAGE_ID, folderStorageId)
                    }
                    folderLargeFilesFinderLauncher.launch(intent)
                }
            }
        }


        btnImageCompress.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                startActivity(android.content.Intent(requireContext(), za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity::class.java).apply {
                    putStringArrayListExtra(
                        za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity.EXTRA_FILE_PATHS,
                        java.util.ArrayList(selected.map { it.absolutePath })
                    )
                })
            }
        }

        btnProtect.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(requireContext(), file.absolutePath, protected = true)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_protected_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnUnprotect.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(requireContext(), file.absolutePath, protected = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_unprotected_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnPin?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(requireContext(), file.absolutePath, pinned = true)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_pinned_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnUnpin?.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(requireContext(), file.absolutePath, pinned = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                        android.widget.Toast.makeText(requireContext(), getString(R.string.toast_unpinned_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        fabPaste?.setOnClickListener {
            val act = activity
            if (act is TwinWindowActivity) {
                act.onPasteRequested(this)
            } else {
                (act as? FileOperationsListener)?.onPasteRequested(this, currentDir)
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
                sheet.show(parentFragmentManager, FilePropertiesBottomSheet.TAG)
            } else if (selected.size > 1 && selected.all { !it.isDirectory }) {
                val filePaths = selected.map { it.absolutePath }
                val context = context ?: return@setOnClickListener
                FileTagsManager.showMultiFileTagDialog(context, filePaths) {
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
            val context = context ?: return@setOnClickListener

            // 1. Copy
            if (pm.isIconEnabled(context, pm.KEY_COPY)) {
                list.add(FileToolsBottomSheet.ActionItem("copy", getString(R.string.action_copy), R.drawable.ic_copy, "toolbar_copy") {
                    if (isTwinWindow) {
                        onActionRequested?.invoke("copy")
                    } else {
                        (activity as? FileOperationsListener)?.onCopyRequested(this@FileBrowserFragment, selected)
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
                        (activity as? FileOperationsListener)?.onMoveRequested(this@FileBrowserFragment, selected)
                    }
                    fileAdapter.exitSelectionMode()
                })
            }

            // Delete
            if (pm.isIconEnabled(context, pm.KEY_DELETE)) {
                list.add(FileToolsBottomSheet.ActionItem("delete", getString(R.string.action_delete), R.drawable.ic_delete, "toolbar_delete") {
                    if (isTwinWindow) {
                        onActionRequested?.invoke("delete")
                    } else {
                        (activity as? FileOperationsListener)?.onDeleteRequested(this@FileBrowserFragment, selected)
                    }
                    fileAdapter.exitSelectionMode()
                })
            }

            // 3. Rename
            if (pm.isIconEnabled(context, pm.KEY_RENAME)) {
                list.add(FileToolsBottomSheet.ActionItem("rename", getString(R.string.action_rename), R.drawable.ic_edit, "toolbar_rename") {
                    if (selected.size == 1) {
                        (activity as? FileOperationsListener)?.onRenameRequested(this@FileBrowserFragment, selected.first())
                    } else {
                        val items = selected.map { BatchRenameItem.fromLocalFile(it) }
                        if (DeviceUtils.isTvDevice(requireContext())) {
                            val intent = android.content.Intent(requireContext(), BatchRenameTvActivity::class.java).apply {
                                putParcelableArrayListExtra("items", java.util.ArrayList(items))
                            }
                            batchRenameTvLauncher.launch(intent)
                        } else {
                            val dialog = BatchRenameDialogFragment.newInstance(items)
                            dialog.setOnCompleteListener { _, _ ->
                                fileAdapter.exitSelectionMode()
                                refresh()
                            }
                            dialog.show(parentFragmentManager, BatchRenameDialogFragment.TAG)
                        }
                    }
                })
            }

            // 4. Share
            if (pm.isIconEnabled(context, pm.KEY_SHARE)) {
                val shareable = selected.filter { it.isFile }
                if (shareable.isNotEmpty()) {
                    list.add(FileToolsBottomSheet.ActionItem("share", getString(R.string.action_share), R.drawable.ic_share, "toolbar_share") {
                        shareFiles(shareable)
                    })
                }
            }

            // 5. Compress Image
            val allImages = selected.isNotEmpty() && selected.all {
                it.extension.lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            }
            if (allImages && pm.isIconEnabled(context, pm.KEY_IMAGE_COMPRESS)) {
                list.add(FileToolsBottomSheet.ActionItem("image_compress", getString(R.string.action_compress_image), R.drawable.ic_compress_image, "toolbar_image_compress") {
                    startActivity(android.content.Intent(requireContext(), za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity::class.java).apply {
                        putStringArrayListExtra(
                            za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity.EXTRA_FILE_PATHS,
                            java.util.ArrayList(selected.map { it.absolutePath })
                        )
                    })
                })
            }

            // 6. Hide
            val hasVisible = fileAdapter.hasAnySelectedVisible()
            if (hasVisible && pm.isIconEnabled(context, pm.KEY_HIDE)) {
                list.add(FileToolsBottomSheet.ActionItem("hide", getString(R.string.hide), R.drawable.ic_eye_off, "toolbar_hide") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.hide(file.absolutePath)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            refresh()
                            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_hidden_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }

            // 7. Unhide
            val hasHidden = fileAdapter.hasAnySelectedHidden()
            if (hasHidden && pm.isIconEnabled(context, pm.KEY_UNHIDE)) {
                list.add(FileToolsBottomSheet.ActionItem("unhide", getString(R.string.unhide), R.drawable.ic_eye, "toolbar_unhide") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.unhide(file.absolutePath)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            refresh()
                            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_unhidden_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }

            // 8. Protect
            val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(context)
            if (hasUnprotected && pm.isIconEnabled(context, pm.KEY_PROTECT)) {
                list.add(FileToolsBottomSheet.ActionItem("protect", getString(R.string.protect), R.drawable.ic_shield_protected, "toolbar_protect") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(requireContext(), file.absolutePath, protected = true)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            refresh()
                            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_protected_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }

            // 9. Unprotect
            val hasProtectedItem = fileAdapter.hasAnySelectedProtected(context)
            if (hasProtectedItem && pm.isIconEnabled(context, pm.KEY_UNPROTECT)) {
                list.add(FileToolsBottomSheet.ActionItem("unprotect", getString(R.string.unprotect), R.drawable.ic_shield_unprotected, "toolbar_unprotect") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.setProtected(requireContext(), file.absolutePath, protected = false)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            refresh()
                            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_unprotected_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }

            // Pin
            val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(context)
            if (hasUnpinned && pm.isIconEnabled(context, pm.KEY_PIN)) {
                list.add(FileToolsBottomSheet.ActionItem("pin", getString(R.string.pin), R.drawable.ic_paperclip, "toolbar_pin") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(requireContext(), file.absolutePath, pinned = true)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory(currentDir)
                            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_pinned_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }

            // Unpin
            val hasPinned = fileAdapter.hasAnySelectedPinned(context)
            if (hasPinned && pm.isIconEnabled(context, pm.KEY_UNPIN)) {
                list.add(FileToolsBottomSheet.ActionItem("unpin", getString(R.string.unpin), R.drawable.ic_paperclip_off, "toolbar_unpin") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (file in selected) {
                            za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.setPinned(requireContext(), file.absolutePath, pinned = false)
                        }
                        withContext(Dispatchers.Main) {
                            fileAdapter.exitSelectionMode()
                            loadDirectory(currentDir)
                            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_unpinned_success, selected.size), android.widget.Toast.LENGTH_SHORT).show()
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
                    sheet.show(parentFragmentManager, FilePropertiesBottomSheet.TAG)
                })
            }

            // 15. Tag
            val isMultiFileOnly = selected.size > 1 && selected.all { !it.isDirectory }
            val prefs = requireContext().getSharedPreferences("ufm_prefs", android.content.Context.MODE_PRIVATE)
            val isMultiTaggingEnabledPref = prefs.getBoolean("pref_multi_file_tagging", false)
            if (isMultiTaggingEnabledPref && isMultiFileOnly) {
                list.add(FileToolsBottomSheet.ActionItem("tag", getString(R.string.action_tag), R.drawable.ic_edit, "toolbar_tag") {
                    val filePaths = selected.map { it.absolutePath }
                    FileTagsManager.showMultiFileTagDialog(requireContext(), filePaths) {
                        fileAdapter.exitSelectionMode()
                        loadDirectory(currentDir)
                    }
                })
            }

            // Retrigger Thumbnails
            val hasVideoOrFolder = selected.isNotEmpty() && selected.any {
                it.isDirectory || it.extension.lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS
            }
            if (hasVideoOrFolder && pm.isIconEnabled(context, pm.KEY_RETRIGGER_THUMBNAILS)) {
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
                            context?.let { ctx ->
                                android.widget.Toast.makeText(ctx, getString(R.string.retrigger_thumbnails_success), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            }

            // Duplicate Finder (single folder, indexed storage)
            if (count == 1 && selected.first().isDirectory && pm.isIconEnabled(context, pm.KEY_DUPLICATE_FINDER)) {
                val targetFolder = selected.first()
                val (folderStorageId, _, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(targetFolder.absolutePath)
                val isFolderIndexed = folderStorageId.isNotEmpty() && UfmApplication.indexingRepository.isStorageFullyIndexed(folderStorageId)
                if (isFolderIndexed) {
                    list.add(FileToolsBottomSheet.ActionItem("duplicate_finder", getString(R.string.action_duplicate_finder), R.drawable.ic_duplicate_finder, "toolbar_duplicate_finder") {
                        fileAdapter.exitSelectionMode()
                        val intent = android.content.Intent(requireContext(), FolderDuplicateFinderActivity::class.java).apply {
                            putExtra(FolderDuplicateFinderActivity.EXTRA_FOLDER_PATH, targetFolder.absolutePath)
                            putExtra(FolderDuplicateFinderActivity.EXTRA_STORAGE_ID, folderStorageId)
                        }
                        folderDuplicateFinderLauncher.launch(intent)
                    })
                }
            }

            // Large Files Finder (single folder, indexed storage)
            if (count == 1 && selected.first().isDirectory && pm.isIconEnabled(context, pm.KEY_LARGE_FILES_FINDER)) {
                val targetFolder = selected.first()
                val (folderStorageId, _, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(targetFolder.absolutePath)
                val isFolderIndexed = folderStorageId.isNotEmpty() && UfmApplication.indexingRepository.isStorageFullyIndexed(folderStorageId)
                if (isFolderIndexed) {
                    list.add(FileToolsBottomSheet.ActionItem("large_files_finder", getString(R.string.action_large_files_finder), R.drawable.ic_folder_large_files, "toolbar_large_files_finder") {
                        fileAdapter.exitSelectionMode()
                        val intent = android.content.Intent(requireContext(), FolderLargeFilesFinderActivity::class.java).apply {
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
                sheet.show(parentFragmentManager, FileToolsBottomSheet.TAG)
            }
        }


        if (isTv) setupTvFocus(view)
    }

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

        wireTvIconBtn(view.findViewById(R.id.btnTvBack)) { navigateBack() }
        wireTvIconBtn(view.findViewById(R.id.btnCreateNew)) { showCreateNewMenu() }
        
        val btnSortTv = view.findViewById<ImageView?>(R.id.btnSort)
        btnSortTv?.setOnClickListener { showSortFilterSheet() }
        btnSortTv?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btnSortTv.imageTintList = iconTintFocused
            } else {
                val hasOverride = SortFilterPreferenceManager.hasFolderOverride(requireContext(), currentDir.absolutePath)
                btnSortTv.imageTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(
                        if (hasOverride) R.color.tv_button_focused_yellow else R.color.tv_text_primary
                    )
                )
            }
        }

        wireTvIconBtn(view.findViewById(R.id.btnSearchToggle)) { toggleSearch() }
        wireTvIconBtn(view.findViewById(R.id.btnRefreshIndex)) { triggerReindex() }
        wireTvIconBtn(view.findViewById(R.id.btnDuplicateFinder)) { }

        wireTvIconBtn(view.findViewById(R.id.btnViewToggle)) {
            ViewModeManager.showSelectionDialog(requireContext(), fileAdapter.viewMode) { selectedMode ->
                val folderKey = SortFilterPreferenceManager.folderKey(currentDir.absolutePath)
                if (SortFilterPreferenceManager.hasFolderOverride(requireContext(), currentDir.absolutePath)) {
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val state = SortFilterPreferenceManager.loadForPath(requireContext(), currentDir.absolutePath)
                        if (state != null) {
                            SortFilterPreferenceManager.saveFolderSpecific(
                                requireContext(), folderKey, currentDir.absolutePath,
                                state.copy(viewMode = selectedMode), isNetwork = false
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
        
        val tvButtons = mutableListOf(btnCloseSelection, btnCopy, btnMove, btnRename, btnFavorite, btnShare,
               btnCopyEncrypt, btnMoveEncrypt, btnHide, btnUnhide, btnProtect, btnUnprotect)
        btnPin?.let { tvButtons.add(it) }
        btnUnpin?.let { tvButtons.add(it) }
        btnRetriggerThumbnails?.let { tvButtons.add(it) }
        tvButtons.forEach { btn ->
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
                
                // Remove existing sticky decoration
                for (i in 0 until recyclerFiles.itemDecorationCount) {
                    val dec = recyclerFiles.getItemDecorationAt(i)
                    if (dec is DateGroupStickyHeaderDecoration) {
                        recyclerFiles.removeItemDecoration(dec)
                    }
                }
                
                if (fileAdapter.isGroupedByDate) {
                    recyclerFiles.addItemDecoration(DateGroupStickyHeaderDecoration(fileAdapter, 3))
                }

                btnViewToggle?.setImageResource(ViewModeManager.iconRes(mode))
                // Re-attach adapter so the layout manager change takes effect immediately
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

    private fun updateSelectionBar(count: Int) {
        if (isPickerMode) return
        
        val showSelection = fileAdapter.isSelectionMode
        val isTv = DeviceUtils.isTvDevice(requireContext())
        if (isTwinWindow) {
            layoutSelectionBar.visibility = View.GONE
            layoutActionPills.visibility = if (showSelection) View.VISIBLE else View.GONE
        } else {
            layoutActionPills.visibility = View.GONE
            layoutSelectionBar.visibility = if (showSelection) View.VISIBLE else View.GONE
        }

        if (showSelection) {
            val showActions = count > 0
            val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
            val context = context ?: return
            val hasHidden = fileAdapter.hasAnySelectedHidden()
            val hasVisible = fileAdapter.hasAnySelectedVisible()
            val hasProtected = fileAdapter.hasAnySelectedProtected(context)
            val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(context)
            val hasPinned = fileAdapter.hasAnySelectedPinned(context)
            val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(context)

            if (!isTv) {
                if (!isTwinWindow) {
                    val row2 = btnCopy.parent.parent as? View
                    row2?.visibility = View.GONE
                    btnSelectAll.visibility = if (pm.isIconEnabled(context, pm.KEY_SELECT_ALL)) View.VISIBLE else View.GONE
                    btnDelete.visibility = View.GONE
                    btnCompress.visibility = View.GONE
                    fabSelectAll?.visibility = View.GONE
                } else {
                    view?.findViewById<View>(R.id.btnPillCopy)?.visibility = View.GONE
                    view?.findViewById<View>(R.id.btnPillMove)?.visibility = View.GONE
                    view?.findViewById<View>(R.id.btnPillSelectAll)?.visibility = View.GONE
                    view?.findViewById<View>(R.id.btnPillDelete)?.visibility = View.GONE
                    layoutActionPills.visibility = View.GONE
                    
                    val fabSelAll = fabSelectAll
                    if (fabSelAll != null) {
                        val isAllSel = fileAdapter.isAllSelected()
                        fabSelAll.text = if (isAllSel) getString(R.string.action_deselect_all) else getString(R.string.action_select_all)
                        fabSelAll.setIconResource(if (isAllSel) R.drawable.ic_close else R.drawable.ic_check)
                        fabSelAll.visibility = View.VISIBLE
                    }
                }
                fabTools?.visibility = if (showActions) View.VISIBLE else View.GONE
            } else {
                fabTools?.visibility = View.GONE
                fabSelectAll?.visibility = View.GONE
                if (!isTwinWindow) {
                    val row2 = btnCopy.parent.parent as? View
                    if (showActions) {
                        za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.stopAnimation(layoutSelectionBar)
                        row2?.visibility = View.VISIBLE
                    } else {
                        row2?.visibility = View.GONE
                        za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.startAnimation(layoutSelectionBar)
                    }
                } else {
                    view?.findViewById<View>(R.id.btnPillCopy)?.visibility = if (showActions) View.VISIBLE else View.GONE
                    view?.findViewById<View>(R.id.btnPillMove)?.visibility = if (showActions) View.VISIBLE else View.GONE
                    view?.findViewById<View>(R.id.btnPillSelectAll)?.visibility = View.GONE
                    view?.findViewById<View>(R.id.btnPillDelete)?.visibility = if (showActions) View.VISIBLE else View.GONE
                    // On TV twin window, hide the pills container entirely if no actions are showable
                    layoutActionPills.visibility = if (showActions) View.VISIBLE else View.GONE
                }
                
                // TV-only icon/row visibility
                btnDelete.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_DELETE)) View.VISIBLE else View.GONE
                btnCopy.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_COPY)) View.VISIBLE else View.GONE
                btnMove.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_MOVE)) View.VISIBLE else View.GONE
                btnRename.visibility = if (count >= 1 && pm.isIconEnabled(context, pm.KEY_RENAME)) View.VISIBLE else View.GONE
                btnShare.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_SHARE)) View.VISIBLE else View.GONE
                btnCopyEncrypt.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_COPY_ENCRYPT)) View.VISIBLE else View.GONE
                btnMoveEncrypt.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_MOVE_ENCRYPT)) View.VISIBLE else View.GONE
                btnCompress.visibility = if (showActions && pm.isIconEnabled(context, pm.KEY_COMPRESS)) View.VISIBLE else View.GONE
                val imgFiles = fileAdapter.getSelectedFiles()
                val allImages = imgFiles.isNotEmpty() && imgFiles.all {
                    it.extension.lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                }
                btnImageCompress.visibility = if (showActions && allImages && pm.isIconEnabled(context, pm.KEY_IMAGE_COMPRESS)) View.VISIBLE else View.GONE
                val hasVideoOrFolder = imgFiles.isNotEmpty() && imgFiles.any {
                    it.isDirectory || it.extension.lowercase() in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS
                }
                btnRetriggerThumbnails?.visibility = if (showActions && hasVideoOrFolder && pm.isIconEnabled(context, pm.KEY_RETRIGGER_THUMBNAILS)) View.VISIBLE else View.GONE
                
                val isSingleFolderSel = imgFiles.size == 1 && imgFiles.first().isDirectory
                val (selStorageId, _, _) = if (isSingleFolderSel) za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(imgFiles.first().absolutePath) else Triple("", "", "")
                val isSelFolderIndexed = selStorageId.isNotEmpty() && UfmApplication.indexingRepository.isStorageFullyIndexed(selStorageId)
                btnDuplicateFinder?.visibility = if (showActions && isSingleFolderSel && isSelFolderIndexed && pm.isIconEnabled(context, pm.KEY_DUPLICATE_FINDER)) View.VISIBLE else View.GONE
                btnLargeFilesFinder?.visibility = if (showActions && isSingleFolderSel && isSelFolderIndexed && pm.isIconEnabled(context, pm.KEY_LARGE_FILES_FINDER)) View.VISIBLE else View.GONE

                
                btnHide.visibility = if (showActions && hasVisible && pm.isIconEnabled(context, pm.KEY_HIDE)) View.VISIBLE else View.GONE
                btnUnhide.visibility = if (showActions && hasHidden && pm.isIconEnabled(context, pm.KEY_UNHIDE)) View.VISIBLE else View.GONE
                btnProtect.visibility = if (showActions && hasUnprotected && pm.isIconEnabled(context, pm.KEY_PROTECT)) View.VISIBLE else View.GONE
                btnUnprotect.visibility = if (showActions && hasProtected && pm.isIconEnabled(context, pm.KEY_UNPROTECT)) View.VISIBLE else View.GONE
                btnPin?.visibility = if (showActions && hasUnpinned && pm.isIconEnabled(context, pm.KEY_PIN)) View.VISIBLE else View.GONE
                btnUnpin?.visibility = if (showActions && hasPinned && pm.isIconEnabled(context, pm.KEY_UNPIN)) View.VISIBLE else View.GONE
                btnFavorite.visibility = if (count == 1 && pm.isIconEnabled(context, pm.KEY_FAVORITE)) View.VISIBLE else View.GONE
            }

            txtSelectionCount.text = if (count == 0) getString(R.string.selection_prompt_select_item) else getString(R.string.selection_count, count)
            btnSelectAll.text = if (fileAdapter.isAllSelected()) getString(R.string.action_deselect_all) else getString(R.string.action_select_all)

            val selectedFiles = fileAdapter.getSelectedFiles()
            val isSingleFile = selectedFiles.size == 1 && !selectedFiles.first().isDirectory
            
            val prefs = requireContext().getSharedPreferences("ufm_prefs", android.content.Context.MODE_PRIVATE)
            val isMultiTaggingEnabled = prefs.getBoolean("pref_multi_file_tagging", false)
            val isMultiFileOnly = selectedFiles.size > 1 && selectedFiles.all { !it.isDirectory }
            
            fabProperties?.visibility = View.GONE
            updatePasteFab()
        } else {
            za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.stopAnimation(layoutSelectionBar)
            fabProperties?.visibility = View.GONE
            fabTools?.visibility = View.GONE
            fabSelectAll?.visibility = View.GONE
            updatePasteFab()
        }
    }

    private fun loadDirectory(directory: File) {
        val ctx = context ?: return
        val isTv = DeviceUtils.isTvDevice(ctx)
        val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val internalLabel = getString(R.string.storage_internal)

        var targetDir = directory
        if (!targetDir.exists()) {
            if (rootPath.isNotEmpty() && File(rootPath).exists()) {
                targetDir = File(rootPath)
            } else {
                rootPath = internalPath
                storageLabel = internalLabel
                targetDir = File(internalPath)
            }
        }

        // Load folder-specific sort settings (or fall back to global) on IO thread
        // before the coroutine that actually reads the files starts.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val state = SortFilterPreferenceManager.loadForPath(ctx, targetDir.absolutePath)
                ?: SortFilterPreferenceManager.loadGlobal(ctx)
            val hasFolderOverride = SortFilterPreferenceManager.hasFolderOverride(ctx, targetDir.absolutePath)
            val viewModeToApply = state.viewMode ?: ViewModeManager.load(ctx)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                sortMode  = state.sortMode
                sortOrder = state.sortOrder
                filterType = state.filterType
                activeTagsFilter = state.activeTags
                // Badge: tint the sort button to signal a folder-specific override is active
                updateSortBadge(hasFolderOverride)
                if (fileAdapter.viewMode != viewModeToApply) {
                    applyViewMode(viewModeToApply)
                }
            }
        }

        val isIndexed = UfmApplication.indexingRepository.isStorageFullyIndexed(storageId)
        view?.findViewById<View>(R.id.btnRefreshIndex)?.visibility = if (isIndexed) View.VISIBLE else View.GONE
        if (isTv && ::recyclerFiles.isInitialized) {
            val hadFocus = view?.hasFocus() == true || recyclerFiles.hasFocus()
            if (hadFocus) {
                shouldRestoreFocus = true
                recyclerFiles.isFocusable = true
                recyclerFiles.isFocusableInTouchMode = true
                recyclerFiles.requestFocus()
            }
        }

        currentDir = targetDir
        val displayTitle = if (labelPrefix.isNotEmpty()) "$labelPrefix${if (directory.absolutePath == rootPath) storageLabel else directory.name}" 
                          else if (directory.absolutePath == rootPath) storageLabel else directory.name
        view?.findViewById<TextView>(R.id.txtTvTitle)?.text = displayTitle
        val displaySubtitle = if (isTwinWindow) {
            val rel = directory.absolutePath.removePrefix(rootPath)
            if (rel.isEmpty()) "/" else rel
        } else {
            directory.absolutePath
        }
        view?.findViewById<TextView>(R.id.txtTvSubtitle)?.text = displaySubtitle
        view?.findViewById<TextView>(R.id.txtSubtitle)?.text = displaySubtitle

        if (fileAdapter.isSelectionMode) fileAdapter.exitSelectionMode()

        folderFlowJob?.cancel()
        val hasDeclined = UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)

        if (hasDeclined) {
            lifecycleScope.launch(Dispatchers.IO) {
                val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
                val hiddenPaths = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase.getInstance(requireContext().applicationContext).hiddenFileDao().getAllPaths().toSet()
                val rawFiles = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(directory.absolutePath)) {
                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.listFiles(directory.absolutePath)
                } else {
                    directory.listFiles()?.toList() ?: emptyList()
                }
                val visibleFiles = rawFiles.filter { isFileVisible(it, showHidden, hiddenPaths) }
                val sorted = sortAndFilterFiles(visibleFiles)
                withContext(Dispatchers.Main) {
                    submitAdapterList(sorted, null, hiddenPaths)
                }
            }
        } else {
            folderFlowJob = lifecycleScope.launch {
                try {
                    val db = UfmIndexingDatabase.getInstance(requireContext().applicationContext)
                    val dao = db.fileIndexDao()
                    dao.getFilesInFolderFlow(directory.absolutePath).collectLatest { fileIndices ->
                        withContext(Dispatchers.IO) {
                            val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
                            val hiddenPaths = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase.getInstance(requireContext().applicationContext).hiddenFileDao().getAllPaths().toSet()
                            if (fileIndices.isEmpty()) {
                                val rawFiles = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(directory.absolutePath)) {
                                    za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.listFiles(directory.absolutePath)
                                } else {
                                    directory.listFiles()?.toList() ?: emptyList()
                                }
                                val visibleFiles = rawFiles.filter { isFileVisible(it, showHidden, hiddenPaths) }
                                val sorted = sortAndFilterFiles(visibleFiles)
                                withContext(Dispatchers.Main) {
                                    submitAdapterList(sorted, false, hiddenPaths)
                                }
                            } else {
                                val files = fileIndices.map { File(it.path) }.filter { isFileVisible(it, showHidden, hiddenPaths) }
                                val sorted = sortAndFilterFiles(files)
                                withContext(Dispatchers.Main) {
                                    submitAdapterList(sorted, true, hiddenPaths)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("FileBrowser", "Error collecting flow: ${e.message}")
                }
            }
        }
        
        // Background sync to ensure index matches filesystem
        lifecycleScope.launch(Dispatchers.IO) {
            syncFolderWithIndex(directory)
        }
    }

    private var lastLoadedPath: String? = null

    private fun submitAdapterList(sorted: List<File>, showAllAsIndexed: Boolean?, hiddenPaths: Set<String>) {
        val safeContext = context ?: return
        val currentPath = currentDir.absolutePath
        val oldPath = lastLoadedPath
        val isNavigatingFolder = oldPath != null && oldPath != currentPath
        lastLoadedPath = currentPath

        val updateAdapter = {
            if (isAdded) {
                if (showAllAsIndexed != null) {
                    fileAdapter.submitList(sorted, showAllAsIndexed = showAllAsIndexed, hiddenPaths = hiddenPaths)
                } else {
                    fileAdapter.submitList(sorted, hiddenPaths = hiddenPaths)
                }
                updateEmptyState(sorted.isEmpty())
                updatePasteFab()
            }
        }

        if (isNavigatingFolder && ::recyclerFiles.isInitialized && za.kilowatch.ultimatefilemanager.util.AnimationHelper.areFolderTransitionsEnabled(safeContext)) {
            val isForward = currentPath.length > (oldPath?.length ?: 0)
            za.kilowatch.ultimatefilemanager.util.AnimationHelper.animateFolderTransition(recyclerFiles, isForward) {
                updateAdapter()
            }
        } else {
            updateAdapter()
        }

        val isTv = DeviceUtils.isTvDevice(safeContext)
        if (isTv) {
            val requestFocus = shouldRestoreFocus || arguments?.getBoolean(ARG_REQUEST_INITIAL_FOCUS, false) == true
            arguments?.putBoolean(ARG_REQUEST_INITIAL_FOCUS, false)
            shouldRestoreFocus = false
            
            if (requestFocus) {
                recyclerFiles.post {
                    var focusPos = 0
                    val exitedDir = lastExitedDir
                    if (exitedDir != null) {
                        val index = fileAdapter.findPosition(exitedDir.absolutePath)
                        if (index != -1) {
                            focusPos = index
                        }
                        lastExitedDir = null
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
    }

    fun updatePasteFab() {
        val fab = fabPaste ?: return
        val hasLocal = FileClipboard.hasItems()
        val hasNet = za.kilowatch.ultimatefilemanager.network.NetworkClipboard.hasItems()
        val total = (if (hasLocal) FileClipboard.files.size else 0) + (if (hasNet) za.kilowatch.ultimatefilemanager.network.NetworkClipboard.files.size else 0)

        if (total > 0) {
            val label = "${getString(R.string.action_paste)} ($total)"
            fab.text = label
            fab.visibility = View.VISIBLE
        } else {
            fab.visibility = View.GONE
        }
    }

    private suspend fun syncFolderWithIndex(directory: File) {
        if (UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) return
        try {
            val db = UfmIndexingDatabase.getInstance(requireContext().applicationContext)
            val dao = db.fileIndexDao()
            val metadataExtractor = MetadataExtractor(requireContext())
            val actualFiles = directory.listFiles()?.toList() ?: emptyList()
            val actualFilePaths = actualFiles.map { it.absolutePath }.toSet()
            
            val fileIndices = actualFiles.map { 
                metadataExtractor.extractMetadata(it, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE) 
            }
            if (fileIndices.isNotEmpty()) dao.insertAll(fileIndices)
            
            val existingInDb = dao.getFilesInFolder(directory.absolutePath)
            existingInDb.filter { it.path !in actualFilePaths }.forEach { dao.deleteByPath(it.path) }
        } catch (e: Exception) {
            Log.e("FileBrowser", "Sync error: ${e.message}")
        }
    }

    private fun sortAndFilterFiles(files: List<File>): List<File> {
        val filtered = files.filter { SortFilterSheet.matchesFilter(it, filterType) }
        val tagFiltered = if (activeTagsFilter.isNotEmpty()) {
            val ctx = context ?: return filtered
            filtered.filter { it.isDirectory || FileTagsManager.getTags(ctx, it.absolutePath).any { t -> t in activeTagsFilter } }
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
            val ctx = context ?: return@Comparator f1.name.compareTo(f2.name, ignoreCase = true)
            val p1 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(ctx.applicationContext, f1.absolutePath)
            val p2 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(ctx.applicationContext, f2.absolutePath)
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

    private fun openFile(file: File, transitionView: View? = null) {
        val ext = file.extension.lowercase()
        if (onMediaFileSelected != null && (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(ext) || za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isVideo(ext))) {
            onMediaFileSelected!!(file)
            return
        }
        if (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.openFile(requireActivity(), file, transitionView)) return
        try {
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
        } catch (e: Exception) {
            Log.e("FileBrowser", "Open error: ${e.message}")
        }
    }

    private fun navigateBack() {
        if (!handleBackPress()) {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun triggerReindex() {
        val ctx = context ?: return
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
        val ctx = context ?: return
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

        viewLifecycleOwner.lifecycleScope.launch {
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

    fun handleBackPress(): Boolean {
        if (fileAdapter.isSelectionMode) {
            fileAdapter.exitSelectionMode()
            return true
        }
        if (currentDir.absolutePath != rootPath) {
            val parent = currentDir.parentFile ?: return false
            lastExitedDir = currentDir
            loadDirectory(parent)
            return true
        }
        return false
    }

    private fun showCreateNewMenu() {
        val isOnTv = DeviceUtils.isTvDevice(requireContext())
        val bgColor = if (isOnTv) requireContext().getColor(R.color.tv_bg_gradient_end) else android.graphics.Color.TRANSPARENT
        val textPrimary = if (isOnTv) requireContext().getColor(R.color.tv_text_primary) else requireContext().getColor(R.color.ufm_text_primary)
        val textSecondary = if (isOnTv) requireContext().getColor(R.color.tv_text_secondary) else requireContext().getColor(R.color.ufm_text_hint)

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
            setBackgroundColor(bgColor)
        }

        val rowFolder = createMenuRowView(R.drawable.ic_folder, getString(R.string.new_menu_new_folder), isOnTv, textPrimary, textSecondary)
        container.addView(rowFolder)

        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = 8; bottomMargin = 8
            }
            setBackgroundColor(0x33FFFFFF.toInt())
        }
        container.addView(divider)

        val rowFile = createMenuRowView(R.drawable.ic_file_text, getString(R.string.new_menu_new_file), isOnTv, textPrimary, textSecondary)
        container.addView(rowFile)

        val dialog = MaterialAlertDialogBuilder(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
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
                android.graphics.drawable.ColorDrawable(requireContext().getColor(R.color.tv_bg_gradient_end))
            )
            dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)?.setTextColor(textPrimary)
        }
    }

    private fun createMenuRowView(iconRes: Int, label: String, isOnTv: Boolean, textPrimary: Int, textSecondary: Int): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(ctx).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(40, 40).apply { marginEnd = 16 }
            if (isOnTv) imageTintList = android.content.res.ColorStateList.valueOf(textPrimary)
        }
        row.addView(icon)
        val text = TextView(ctx).apply {
            this.text = label
            textSize = 16f
            setTextColor(textPrimary)
        }
        row.addView(text)
        if (isOnTv) {
            val white = ctx.getColor(R.color.tv_text_primary)
            val black = ctx.getColor(R.color.tv_button_focused_yellow_text)
            val yellow = ctx.getColor(R.color.tv_button_focused_yellow)
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

    private fun showCreateTextFileDialog() {
        val isOnTv = DeviceUtils.isTvDevice(requireContext())
        val ctx = requireContext()
        val bgColor = if (isOnTv) ctx.getColor(R.color.tv_bg_gradient_end) else android.graphics.Color.TRANSPARENT
        val textColorPrimary = if (isOnTv) ctx.getColor(R.color.tv_text_primary) else ctx.getColor(R.color.ufm_text_primary)
        val textColorHint = if (isOnTv) ctx.getColor(R.color.tv_text_hint) else ctx.getColor(R.color.ufm_text_hint)
        val accentColor = if (isOnTv) ctx.getColor(R.color.tv_button_focused_yellow) else ctx.getColor(R.color.ufm_primary)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
            setBackgroundColor(bgColor)
        }
        val editText = EditText(ctx).apply {
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

        MaterialAlertDialogBuilder(ctx, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.new_file_title))
            .setIcon(R.drawable.ic_create_new)
            .setView(container)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .setPositiveButton(getString(R.string.new_file_create)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    (activity as? FileBrowserActivity)?.showPremiumSnackbar(getString(R.string.new_file_empty))
                } else {
                    createTextFileInFragment(name)
                }
            }
            .show()
            .also { dialog ->
                if (isOnTv) {
                    dialog.window?.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(ctx.getColor(R.color.tv_bg_gradient_end))
                    )
                    val titleView = dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)
                    titleView?.setTextColor(textColorPrimary)
                    val yellowCsl = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.tv_button_focused_yellow))
                    val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())
                    val white = ctx.getColor(R.color.tv_text_primary)
                    val black = ctx.getColor(R.color.tv_button_focused_yellow_text)
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

    private fun createTextFileInFragment(baseName: String) {
        // Only works for local storage in fragment context
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
        val fileToCreate = targetFile

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val created = fileToCreate.createNewFile()
                if (created) {
                    try {
                        val (sid, stype) = IndexingRepository.resolveStorageForPath(fileToCreate.absolutePath)
                            .let { it.first to it.second }
                        UfmApplication.indexingRepository.indexFile(fileToCreate, sid, stype)
                    } catch (_: Exception) { }

                    withContext(Dispatchers.Main) {
                        loadDirectory(currentDir)
                        val act = activity
                        if (act is FileBrowserActivity) {
                            act.showPremiumSnackbar(getString(R.string.new_file_success))
                            val intent = Intent(act, za.kilowatch.ultimatefilemanager.viewer.TextViewerActivity::class.java).apply {
                                putExtra(FileViewerRouter.EXTRA_FILE_PATH, fileToCreate.absolutePath)
                                putExtra(FileViewerRouter.EXTRA_FILE_NAME, fileToCreate.name)
                                putExtra(FileViewerRouter.EXTRA_START_IN_EDIT_MODE, true)
                            }
                            startActivity(intent)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        (activity as? FileBrowserActivity)?.showPremiumSnackbar(getString(R.string.new_file_error))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    (activity as? FileBrowserActivity)?.showPremiumSnackbar(getString(R.string.new_file_error) + ": ${e.message}")
                }
            }
        }
    }

    private fun showCreateFolderDialog() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.new_folder_hint)
            setText("New Folder")
            selectAll()
            requestFocus()
        }
        container.addView(editText)

        MaterialAlertDialogBuilder(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(R.string.new_folder_title)
            .setIcon(R.drawable.ic_folder)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.new_folder_create) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newDir = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(currentDir.absolutePath)) {
                        za.kilowatch.ultimatefilemanager.storage.ShizukuFile(currentDir.absolutePath, name, true)
                    } else {
                        File(currentDir, name)
                    }
                    if (newDir.mkdirs()) {
                        try {
                            UfmApplication.indexingRepository.indexFile(newDir, storageId, storageType)
                        } catch (_: Exception) {}
                        loadDirectory(currentDir)
                    }
                }
            }
            .show()
    }

    private fun setupTvShareChooserFocus(
        ctx: Context,
        dialog: androidx.appcompat.app.AlertDialog,
        dialogView: View,
        cardStandard: com.google.android.material.card.MaterialCardView?,
        cardPremium: com.google.android.material.card.MaterialCardView?,
        btnCancel: View?
    ) {
        val white = ctx.getColor(R.color.tv_text_primary)
        val black = ctx.getColor(R.color.tv_button_focused_yellow_text)
        val yellow = ctx.getColor(R.color.tv_button_focused_yellow)
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

    private fun shareFiles(files: List<File>) {
        if (files.isEmpty()) return
        val ctx = context ?: return
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(ctx)

        val layoutRes = if (isTv) R.layout.dialog_premium_share_chooser_tv else R.layout.dialog_premium_share_chooser
        val dialogView = LayoutInflater.from(ctx).inflate(layoutRes, null)
        val dialog = MaterialAlertDialogBuilder(ctx, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
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
                val intent = Intent(ctx, PremiumShareTvActivity::class.java).apply {
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
            setupTvShareChooserFocus(ctx, dialog, dialogView, cardStandardShare, cardPremiumShare, btnCancel)
        }

        dialog.show()
    }

    private fun performStandardShare(files: List<File>) {
        val ctx = context ?: return
        try {
            val uris = ArrayList<Uri>()
            files.forEach { uris.add(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it)) }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(files[0].extension.lowercase()) ?: "*/*"
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
        } catch (_: Exception) {}
    }

    private fun showTargetChooserDialog(files: List<File>) {
        val ctx = context ?: return
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_premium_target_chooser, null)
        val dialog = MaterialAlertDialogBuilder(ctx, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        val cardTargetTv = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTargetTv)
        val cardTargetMobilePc = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTargetMobilePc)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val filePaths = ArrayList(files.map { it.absolutePath })

        cardTargetTv.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(ctx, PremiumShareActivity::class.java).apply {
                putStringArrayListExtra("files", filePaths)
                putExtra("target_type", "tv")
            }
            startActivity(intent)
            fileAdapter.exitSelectionMode()
        }

        cardTargetMobilePc.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(ctx, PremiumShareActivity::class.java).apply {
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

    private fun showSortFilterSheet() {
        val ctx = context ?: return
        val sheet = SortFilterSheet()
        sheet.currentSortMode = sortMode
        sheet.currentSortOrder = sortOrder
        sheet.currentFilterType = filterType
        sheet.currentShowHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
        sheet.currentGroupByDate = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(ctx)
        sheet.activeTags = activeTagsFilter

        val folderKey = SortFilterPreferenceManager.folderKey(currentDir.absolutePath)
        sheet.currentFolderKey = folderKey
        sheet.currentFolderDisplayPath = currentDir.absolutePath
        val hasFolderOverride = SortFilterPreferenceManager.hasFolderOverride(ctx, currentDir.absolutePath)
        sheet.currentScope = if (hasFolderOverride) SortFilterSheet.Scope.FOLDER else SortFilterSheet.Scope.GLOBAL
        
        val activeState = if (hasFolderOverride) {
            SortFilterPreferenceManager.loadForPath(ctx, currentDir.absolutePath)
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
                        ctx, folderKey, currentDir.absolutePath, state, isNetwork = false)
                } else {
                    SortFilterPreferenceManager.saveGlobal(ctx, state)
                    ViewModeManager.save(ctx, selectedViewMode)
                    // When switching to global, remove any existing folder override
                    SortFilterPreferenceManager.clearFolderSpecific(ctx, folderKey)
                }
                val hasFolderOverrideNow = SortFilterPreferenceManager.hasFolderOverride(ctx, currentDir.absolutePath)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    updateSortBadge(hasFolderOverrideNow)
                }
            }

            if (groupByDate != za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(ctx)) {
                za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.setEnabled(ctx, groupByDate)
                fileAdapter.isGroupedByDate = groupByDate
            }
            applyViewMode(selectedViewMode)
            loadDirectory(currentDir)
        }
        sheet.show(parentFragmentManager, SortFilterSheet.TAG)
    }

    /**
     * Updates the sort button tint to indicate whether a folder-specific sort override is active.
     * A coloured tint (accent) signals an active override; the default tint means global.
     */
    private fun updateSortBadge(hasFolderOverride: Boolean) {
        val ctx = context ?: return
        val btn = btnSort ?: return
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(ctx)
        if (hasFolderOverride) {
            btn.imageTintList = android.content.res.ColorStateList.valueOf(
                ctx.getColor(if (isTv) za.kilowatch.ultimatefilemanager.R.color.tv_button_focused_yellow else za.kilowatch.ultimatefilemanager.R.color.ufm_primary))
        } else {
            btn.imageTintList = android.content.res.ColorStateList.valueOf(
                ctx.getColor(if (isTv) za.kilowatch.ultimatefilemanager.R.color.tv_text_primary else za.kilowatch.ultimatefilemanager.R.color.mobile_icon_tint))
        }
    }

    interface FileOperationsListener {
        fun onDeleteRequested(fragment: FileBrowserFragment, files: List<File>)
        fun onCopyRequested(fragment: FileBrowserFragment, files: List<File>)
        fun onMoveRequested(fragment: FileBrowserFragment, files: List<File>)
        fun onRenameRequested(fragment: FileBrowserFragment, file: File?)
        fun onPasteRequested(fragment: FileBrowserFragment, destination: File)
    }

    fun getCurrentDir() = currentDir
    fun getRootPath(): String = rootPath
    fun getStorageLabel(): String = storageLabel
    fun getSelectedFiles() = fileAdapter.getSelectedFiles()
    fun exitSelectionMode() = fileAdapter.exitSelectionMode()
    fun refresh() = loadDirectory(currentDir)
    fun getStorageId() = storageId
    fun getStorageType() = storageType

    /**
     * Determines whether a file should be visible in the file list.
     * When [showHidden] is false, filters out both:
     * - Files/folders whose name starts with "." (Unix dotfile convention)
     * - Files whose absolute path is in the explicit hidden-paths database
     */
    private fun isFileVisible(file: File, showHidden: Boolean, hiddenPaths: Set<String>): Boolean {
        return showHidden || (!HiddenFilesManager.isJunkOrHidden(file.name) && file.absolutePath !in hiddenPaths)
    }

    private fun showDrivePicker() {
        val drives = StorageBrowserActivity.getConnectedStorages(requireContext())
        if (drives.isEmpty()) return

        val names = drives.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(R.string.select_drive)
            .setItems(names) { _, which ->
                val drive = drives[which]
                rootPath = drive.mountPath
                storageLabel = drive.label
                val resolved = IndexingRepository.resolveStorageForPath(rootPath)
                storageId = resolved.first
                storageType = resolved.second
                loadDirectory(File(rootPath))
            }
            .show()
    }
    private fun toggleSearch() {
        val btnToggle = btnSearchToggle ?: return
        val searchEdit = edtSearch ?: return
        val searchRow = layoutSearchRow ?: return

        isSearchVisible = !isSearchVisible
        searchRow.visibility = if (isSearchVisible) View.VISIBLE else View.GONE
        
        val colorRes = if (isSearchVisible) R.color.ufm_granted else R.color.ufm_denied
        btnToggle.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(colorRes))
        
        if (isSearchVisible) {
            searchEdit.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            searchEdit.setText("")
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(searchEdit.windowToken, 0)
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
                
                val hiddenPaths = za.kilowatch.ultimatefilemanager.settings.HiddenFilesDatabase.getInstance(requireContext().applicationContext).hiddenFileDao().getAllPaths().toSet()

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
}
