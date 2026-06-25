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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.storage.ViewModeManager
import za.kilowatch.ultimatefilemanager.storage.BatchRenameItem
import za.kilowatch.ultimatefilemanager.storage.BatchRenameDialogFragment
import za.kilowatch.ultimatefilemanager.storage.BatchRenameTvActivity
import java.io.File
import java.io.FileOutputStream

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

    private lateinit var recyclerFiles: RecyclerView
    private lateinit var fileAdapter: NetworkFileAdapter
    private lateinit var progressBar: ProgressBar
    private var txtTitle: TextView? = null
    private var txtSubtitle: TextView? = null
    
    private lateinit var btnSearchToggle: ImageView
    private lateinit var layoutSearchRow: LinearLayout
    private lateinit var edtSearch: EditText
    private lateinit var btnSearchClear: ImageView
    private var isSearchVisible = false
    private var searchJob: Job? = null

    private var sortMode = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.NAME
    private var sortOrder = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortOrder.ASC
    private var filterType = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.ALL
    private var currentFiles: List<NetworkFile> = emptyList()
    private lateinit var layoutSelectionBar: View
    private lateinit var txtSelectionCount: TextView
    private lateinit var layoutEmpty: View
    private lateinit var fabPaste: ExtendedFloatingActionButton
    private var btnOptionsToggle: ImageView? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shareId = arguments?.getString(ARG_SHARE_ID) ?: return
        currentPath = arguments?.getString(ARG_INITIAL_PATH) ?: ""
        
        // Resolve share
        val context = requireContext()
        val fromRepo = NetworkShareRepository.getInstance(context).getById(shareId)
        share = if (fromRepo != null) {
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
                        // Use online.id as the rclone remote name — matches the section
                        // key in the encrypted config file and the name registered via
                        // launchRCloneBrowse, so all three stay in sync.
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
                } ?: return
            }
        }

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
        setupViews(view)
        loadDirectory()
    }

    override fun onResume() {
        super.onResume()
        applyToolbarIconVisibility()
        updatePasteFab()
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
        v.findViewById<View>(R.id.btnCompress)?.visibility = if (pm.isIconEnabled(context, pm.KEY_COMPRESS)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnImageCompress)?.visibility = View.GONE
        v.findViewById<View>(R.id.btnSelectAll)?.visibility = if (pm.isIconEnabled(context, pm.KEY_SELECT_ALL)) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btnDelete)?.visibility = if (pm.isIconEnabled(context, pm.KEY_DELETE)) View.VISIBLE else View.GONE
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
        fabPaste.setOnClickListener {
            val act = activity
            if (act is TwinWindowActivity) {
                act.onPasteRequested(this)
            }
        }
        
        btnSearchToggle = view.findViewById(R.id.btnSearchToggle)
        btnSearchToggle.setImageResource(R.drawable.ic_search)
        btnSearchToggle.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.ufm_denied))
        layoutSearchRow = view.findViewById(R.id.layoutSearchRow)
        edtSearch = view.findViewById(R.id.edtSearch)
        btnSearchClear = view.findViewById(R.id.btnSearchClear)

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
        
        btnSearchToggle.setOnClickListener { toggleSearch() }
        btnSearchClear.setOnClickListener { edtSearch.setText("") }
        
        edtSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""
                btnSearchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(500)
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
        
        // Pills are handled below for Twin Window mode

        if (!isTv) {
            view.findViewById<View>(R.id.btnViewToggle)?.setOnClickListener {
                ViewModeManager.showSelectionDialog(requireContext(), fileAdapter.viewMode) { selectedMode ->
                    ViewModeManager.save(requireContext(), selectedMode)
                    applyViewMode(selectedMode)
                }
            }
        }
        view.findViewById<View>(R.id.btnSort)?.setOnClickListener { showSortFilterSheet() }

        view.findViewById<View>(R.id.btnCloseSelection)?.setOnClickListener { fileAdapter.exitSelectionMode() }
        view.findViewById<View>(R.id.btnSelectAll)?.setOnClickListener { fileAdapter.selectAll() }
        view.findViewById<View>(R.id.btnDelete)?.setOnClickListener { showDeleteConfirmation() }
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
                    // For SMB/FTP, path is now relative to root. For TV it's already absolute.
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
                                val durationMs = za.kilowatch.ultimatefilemanager.settings.LongPressDurationManager
                                    .loadDurationMs(requireContext())
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Server-mode SMB: intercept at root to discover shares
                if (share.type == ShareType.SMB && share.isServerMode) {
                    if (currentPath.isEmpty()) {
                        val discovered = discoverServerShares(share)
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = View.GONE
                            if (discovered.isEmpty()) {
                                currentFiles = emptyList()
                                fileAdapter.submitList(emptyList())
                                layoutEmpty.visibility = View.VISIBLE
                            } else {
                                currentFiles = discovered
                                performSearch(edtSearch.text.toString().trim())
                                updateSubtitle()
                            }
                        }
                    } else {
                        // Inside a discovered share
                        val existingShare = share.remotePath.trimStart('/')
                        if (existingShare.isNotEmpty()) {
                            // Already navigated into a share — currentPath is relative to share root
                            val innerPath = stripSharePrefix(currentPath.trimStart('/'))
                            var files = SmbShareClient.listFiles(share, innerPath)
                            files = files.filter { it.name != ".." }
                            withContext(Dispatchers.Main) {
                                progressBar.visibility = View.GONE
                                currentFiles = files
                                performSearch(edtSearch.text.toString().trim())
                                updateSubtitle()
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
                                progressBar.visibility = View.GONE
                                currentFiles = files
                                performSearch(edtSearch.text.toString().trim())
                                updateSubtitle()
                            }
                        }
                    }
                    return@launch
                }

                val files = when (share.type) {
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
                
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    currentFiles = files
                    performSearch(edtSearch.text.toString().trim())
                    updateSubtitle()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showPremiumSnackbar(getString(R.string.error_loading_emessage, e.message ?: "Unknown error"))
                }
            }
        }
    }

    private fun updateSubtitle() {
        // Server-mode SMB at root: show "Shared Folders"
        if (share.type == ShareType.SMB && share.isServerMode && currentPath.isEmpty()) {
            txtSubtitle?.text = getString(R.string.network_folder_shared_folders)
            return
        }
        val displayPath = if (isTwinWindow) {
            currentPath.removePrefix(share.docIdPrefix).removePrefix("/")
        } else {
            currentPath
        }
        txtSubtitle?.text = if (displayPath.isEmpty()) "/" else (if (isTwinWindow && !displayPath.startsWith("/")) "/$displayPath" else displayPath)
    }

    /**
     * Discovers all accessible shares on an SMB server.
     * Throws if the server is unreachable or authentication fails.
     * Returns an empty list if the server is reachable but has no accessible shares.
     */
    private fun discoverServerShares(server: NetworkShare): List<NetworkFile> {
        val allShares = SmbDiscovery.listShares(
            server.host, server.username, server.password, server.domain
        )
        return allShares.filter { shareName ->
            SmbShareClient.isShareAccessible(
                server.host, shareName, server.username, server.password, server.domain
            )
        }.map { shareName ->
            NetworkFile(
                name = shareName,
                path = "/$shareName",
                isDirectory = true
            )
        }
    }

    private fun updateSelectionUI(count: Int) {
        val showSelection = fileAdapter.isSelectionMode
        if (showSelection) {
            val showActions = count > 0
            if (isTwinWindow) {
                view?.findViewById<View>(R.id.layoutActionPillsScroll)?.visibility = if (showActions) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.layoutActionPills)?.visibility = if (showActions) View.VISIBLE else View.GONE
                layoutSelectionBar.visibility = View.GONE
            } else {
                layoutSelectionBar.visibility = View.VISIBLE
                view?.findViewById<View>(R.id.layoutActionPillsScroll)?.visibility = View.GONE
                view?.findViewById<View>(R.id.layoutActionPills)?.visibility = View.GONE
                
                val btnCopyView = view?.findViewById<View>(R.id.btnCopy)
                val row2 = btnCopyView?.parent?.parent as? View
                if (showActions) {
                    za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.stopAnimation(layoutSelectionBar as ViewGroup)
                    row2?.visibility = View.VISIBLE
                } else {
                    row2?.visibility = View.GONE
                    za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.startAnimation(layoutSelectionBar as ViewGroup)
                }
                
                val context = context ?: return
                val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
                
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
            }
            txtSelectionCount.text = if (count == 0) getString(R.string.selection_prompt_select_item) else getString(R.string.selection_count, count)
        } else {
            layoutSelectionBar.visibility = View.GONE
            view?.findViewById<View>(R.id.layoutActionPillsScroll)?.visibility = View.GONE
            view?.findViewById<View>(R.id.layoutActionPills)?.visibility = View.GONE
            za.kilowatch.ultimatefilemanager.ui.SelectionAnimationHelper.stopAnimation(layoutSelectionBar as ViewGroup)
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
        val bgColor = if (isOnTv) ctx.getColor(R.color.tv_bg_gradient_end) else android.graphics.Color.TRANSPARENT
        val textPrimary = if (isOnTv) ctx.getColor(R.color.tv_text_primary) else ctx.getColor(R.color.ufm_text_primary)
        val textSecondary = if (isOnTv) ctx.getColor(R.color.tv_text_secondary) else ctx.getColor(R.color.ufm_text_hint)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
            setBackgroundColor(bgColor)
        }

        val rowFolder = createMenuRowView(ctx, R.drawable.ic_folder, getString(R.string.new_menu_new_folder), isOnTv, textPrimary, textSecondary)
        container.addView(rowFolder)

        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = 8; bottomMargin = 8
            }
            setBackgroundColor(0x33FFFFFF.toInt())
        }
        container.addView(divider)

        val rowFile = createMenuRowView(ctx, R.drawable.ic_file_text, getString(R.string.new_menu_new_file), isOnTv, textPrimary, textSecondary)
        container.addView(rowFile)

        val dialog = MaterialAlertDialogBuilder(ctx, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
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
                android.graphics.drawable.ColorDrawable(ctx.getColor(R.color.tv_bg_gradient_end))
            )
            dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)?.setTextColor(textPrimary)
            applyTvButtonStyle(ctx, dialog)
        }
    }

    private fun createMenuRowView(ctx: android.content.Context, iconRes: Int, label: String, isOnTv: Boolean, textPrimary: Int, textSecondary: Int): LinearLayout {
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

    private fun applyTvButtonStyle(ctx: android.content.Context, dialog: androidx.appcompat.app.AlertDialog) {
        val yellowCsl = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.tv_button_focused_yellow))
        val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())
        val white = ctx.getColor(R.color.tv_text_primary)
        val black = ctx.getColor(R.color.tv_button_focused_yellow_text)
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            backgroundTintList = glassCsl
            setTextColor(white)
            setOnFocusChangeListener { _, hasFocus ->
                backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                setTextColor(if (hasFocus) black else white)
            }
        }
    }

    private fun showCreateTextFileDialog() {
        val ctx = requireContext()
        val isOnTv = DeviceUtils.isTvDevice(ctx)
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
                    showFragmentSnackbar(getString(R.string.new_file_empty))
                } else {
                    createNetworkTextFile(name)
                }
            }
            .show()
            .also { dialog ->
                if (isOnTv) {
                    dialog.window?.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(ctx.getColor(R.color.tv_bg_gradient_end))
                    )
                    dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)?.setTextColor(textColorPrimary)
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
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.new_folder_hint)
            setText("New Folder")
            selectAll()
            setSingleLine(true)
        }
        container.addView(editText)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.new_folder_title))
            .setView(container)
            .setNegativeButton(R.string.delete_cancel, null)
            .setPositiveButton(R.string.new_folder_create) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
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
            .show()
    }

    private fun showRenameDialog(file: NetworkFile) {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val editText = EditText(requireContext()).apply {
            setText(file.name)
            selectAll()
            setSingleLine(true)
        }
        container.addView(editText)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename_title)
            .setView(container)
            .setNegativeButton(R.string.delete_cancel, null)
            .setPositiveButton(R.string.rename_confirm) { _, _ ->
                val newName = editText.text.toString().trim()
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
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { showPremiumSnackbar(getString(R.string.error_emessage, e.message ?: "Unknown error")) }
                        }
                    }
                }
            }
            .show()
    }

    private fun showDeleteConfirmation() {
        val selected = fileAdapter.getSelectedFiles()
        if (selected.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message_files, selected.size))
            .setNegativeButton(R.string.delete_cancel, null)
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
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
            .show()
    }

    fun getSelectedFiles(): List<NetworkFile> = fileAdapter.getSelectedFiles()
    fun exitSelectionMode() = fileAdapter.exitSelectionMode()
    fun getCurrentPath(): String = currentPath
    fun getShare(): NetworkShare = share
    fun getCurrentFiles(): List<NetworkFile> = currentFiles

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
        wireTvIconBtn(view.findViewById(R.id.btnSort)) { showSortFilterSheet() }
        wireTvIconBtn(view.findViewById(R.id.btnRefresh)) { loadDirectory() }
        wireTvIconBtn(view.findViewById(R.id.btnDrivePicker)) { onStoragePickerRequested?.invoke() }
        wireTvIconBtn(view.findViewById(R.id.btnSearchToggle)) { toggleSearch() }
        wireTvIconBtn(view.findViewById(R.id.btnViewToggle)) {
            ViewModeManager.showSelectionDialog(requireContext(), fileAdapter.viewMode) { selectedMode ->
                ViewModeManager.save(requireContext(), selectedMode)
                applyViewMode(selectedMode)
            }
        }
        // Update initial TV tint
        val btnSearch = view.findViewById<ImageView>(R.id.btnSearchToggle)
        btnSearch?.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.ufm_denied))

        listOf(
            R.id.btnCloseSelection, R.id.btnCopy, R.id.btnMove, R.id.btnRename, R.id.btnFavorite,
            R.id.btnShare, R.id.btnCopyEncrypt, R.id.btnMoveEncrypt
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
        isSearchVisible = !isSearchVisible
        layoutSearchRow.visibility = if (isSearchVisible) View.VISIBLE else View.GONE
        
        val colorRes = if (isSearchVisible) R.color.ufm_granted else R.color.ufm_denied
        btnSearchToggle.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(colorRes))
        
        if (isSearchVisible) {
            edtSearch.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(edtSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            edtSearch.setText("")
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(edtSearch.windowToken, 0)
            performSearch("") // Reset filter
        }
    }

    private fun performSearch(query: String) {
        val showHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
        val baseList = if (query.isEmpty()) currentFiles else currentFiles.filter { it.name.contains(query, ignoreCase = true) }
        val filtered = baseList.filter { isNetworkFileVisible(it, showHidden) }
        val sortedAndFiltered = sortAndFilterFiles(filtered)
        fileAdapter.submitList(sortedAndFiltered)
        layoutEmpty.visibility = if (sortedAndFiltered.isEmpty()) View.VISIBLE else View.GONE

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
        if (!::fabPaste.isInitialized) return
        val hasLocal = za.kilowatch.ultimatefilemanager.storage.FileClipboard.hasItems()
        val hasNet = NetworkClipboard.hasItems()
        val total = (if (hasLocal) za.kilowatch.ultimatefilemanager.storage.FileClipboard.files.size else 0) + (if (hasNet) NetworkClipboard.files.size else 0)

        if (total > 0) {
            val label = "${getString(R.string.action_paste)} ($total)"
            fabPaste.text = label
            fabPaste.visibility = View.VISIBLE
        } else {
            fabPaste.visibility = View.GONE
        }
    }

    private fun sortAndFilterFiles(files: List<NetworkFile>): List<NetworkFile> {
        val filtered = files.filter { za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.matchesFilter(File(it.path), filterType) }
        val secondaryComparator: Comparator<NetworkFile> = when (sortMode) {
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { f: NetworkFile -> f.name }
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.SIZE -> compareBy { f: NetworkFile -> if (f.isDirectory) 0L else f.size }
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.DATE -> compareBy { f: NetworkFile -> f.lastModified }
            za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { f: NetworkFile -> f.name.substringAfterLast('.', "") }
        }
        val orderedComparator = if (sortOrder == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortOrder.DESC) secondaryComparator.reversed() else secondaryComparator
        return filtered.sortedWith(compareBy<NetworkFile> { !it.isDirectory }.then(orderedComparator))
    }

    private fun applyViewMode(mode: ViewModeManager.ViewMode) {
        fileAdapter.viewMode = mode
        val lm = if (!ViewModeManager.isGrid(mode)) {
            androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        } else {
            androidx.recyclerview.widget.GridLayoutManager(
                requireContext(), ViewModeManager.spanCount(requireContext(), mode)
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

    /**
     * Determines whether a network file should be visible in the file list.
     * When [showHidden] is false, filters out files/folders whose name starts with "." (Unix dotfile convention).
     */
    private fun isNetworkFileVisible(nf: za.kilowatch.ultimatefilemanager.network.NetworkFile, showHidden: Boolean): Boolean {
        return showHidden || !nf.name.startsWith(".")
    }

    private fun showSortFilterSheet() {
        val sheet = za.kilowatch.ultimatefilemanager.storage.SortFilterSheet()
        sheet.currentSortMode = sortMode
        sheet.currentSortOrder = sortOrder
        sheet.currentFilterType = filterType
        sheet.currentShowHidden = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
        sheet.currentGroupByDate = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(requireContext())
        sheet.onApply = { mode, order, filter, showHidden, groupByDate ->
            sortMode = mode
            sortOrder = order
            filterType = filter
            za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled = showHidden
            
            val prefs = requireContext().getSharedPreferences("ufm_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("sort_mode", mode.ordinal).putInt("sort_order", order.ordinal).apply()
            
            if (groupByDate != za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(requireContext())) {
                za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.setEnabled(requireContext(), groupByDate)
                fileAdapter.isGroupedByDate = groupByDate
                applyViewMode(fileAdapter.viewMode)
            }
            
            performSearch(edtSearch.text.toString().trim())
        }
        sheet.show(parentFragmentManager, za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.TAG)
    }
}
