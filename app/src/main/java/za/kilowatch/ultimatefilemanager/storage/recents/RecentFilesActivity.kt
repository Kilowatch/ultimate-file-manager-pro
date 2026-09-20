package za.kilowatch.ultimatefilemanager.storage.recents

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.archive.ArchiveManager
import za.kilowatch.ultimatefilemanager.archive.ArchiveOptionsDialog
import za.kilowatch.ultimatefilemanager.checksum.ChecksumDialogFragment
import za.kilowatch.ultimatefilemanager.checksum.FileCompareDialogFragment
import za.kilowatch.ultimatefilemanager.checksum.LocalFileSource
import za.kilowatch.ultimatefilemanager.indexing.recents.AccessTier
import za.kilowatch.ultimatefilemanager.indexing.recents.AccessTierDetector
import za.kilowatch.ultimatefilemanager.indexing.recents.RecentFileItem
import za.kilowatch.ultimatefilemanager.indexing.recents.RecentFileSource
import za.kilowatch.ultimatefilemanager.indexing.recents.RecentsChangeWatcher
import za.kilowatch.ultimatefilemanager.indexing.recents.RecentsRepository
import za.kilowatch.ultimatefilemanager.indexing.recents.RecentsSettingsManager
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager
import za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager
import za.kilowatch.ultimatefilemanager.settings.StorageIndexerActivity
import za.kilowatch.ultimatefilemanager.util.MediaScannerNotifier
import za.kilowatch.ultimatefilemanager.util.ThemeColors
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
import za.kilowatch.ultimatefilemanager.storage.BatchRenameDialogFragment
import za.kilowatch.ultimatefilemanager.storage.BatchRenameItem
import za.kilowatch.ultimatefilemanager.storage.FileAdapter
import za.kilowatch.ultimatefilemanager.storage.FileClipboard
import za.kilowatch.ultimatefilemanager.storage.FilePropertiesBottomSheet
import za.kilowatch.ultimatefilemanager.storage.FileToolsBottomSheet
import za.kilowatch.ultimatefilemanager.storage.SafFile
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity
import za.kilowatch.ultimatefilemanager.storage.ViewModeManager
import za.kilowatch.ultimatefilemanager.ui.FloatingQuickActionBar
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.RingtoneHelper
import za.kilowatch.ultimatefilemanager.util.WallpaperHelper
import za.kilowatch.ultimatefilemanager.viewer.ExifToolsActivity
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.viewer.GifCreatorActivity
import za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity
import java.io.File

class RecentFilesActivity : AppCompatActivity() {

    enum class Category(val titleRes: Int) {
        ALL(R.string.recent_filter_all),
        DOCUMENTS(R.string.recent_filter_documents),
        IMAGES(R.string.recent_filter_images),
        VIDEOS(R.string.recent_filter_videos),
        AUDIO(R.string.recent_filter_audio),
        ARCHIVES(R.string.recent_filter_archives)
    }

    private var isTv = false
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var progressLoading: ProgressBar
    private lateinit var txtHeaderSubtitle: TextView

    // Refresh / Scanning progress card
    private lateinit var layoutRefreshStatus: View
    private lateinit var txtRefreshStatusTitle: TextView
    private lateinit var txtRefreshEta: TextView
    private lateinit var progressRefreshPercent: ProgressBar

    // Header layout elements
    private lateinit var layoutHeaderNormal: View
    private lateinit var layoutHeaderSelection: View
    private lateinit var txtSelectionCount: TextView
    private lateinit var btnCloseSelection: ImageView
    private lateinit var btnSelectAll: ImageView
    private var btnViewToggle: ImageView? = null

    // Mobile specific
    private var floatingQuickBar: FloatingQuickActionBar? = null
    private var fabTools: ExtendedFloatingActionButton? = null

    private var currentViewMode: ViewModeManager.ViewMode = ViewModeManager.ViewMode.LIST_MEDIUM
    private val categoryFiles = mutableMapOf<Category, List<File>>()
    private val categoryLabels = mutableMapOf<Category, Map<String, String>>()
    private val categoryAdapters = mutableMapOf<Category, FileAdapter>()
    private lateinit var pagerAdapter: CategoryPageAdapter

    private val currentCategory: Category
        get() = Category.entries.getOrElse(if (::viewPager.isInitialized) viewPager.currentItem else 0) { Category.ALL }

    private val fileAdapter: FileAdapter
        get() = categoryAdapters[currentCategory] ?: categoryAdapters.values.first()
    private val allRawItems = mutableListOf<RecentFileItem>()
    private var repository: RecentsRepository? = null
    private var changeWatcher: RecentsChangeWatcher? = null
    private var loadJob: Job? = null

    private val safTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                za.kilowatch.ultimatefilemanager.storage.SafLocationRepository.clearCache()
                refreshData(forceFull = true)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to persist folder permission", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_recent_files_tv)
        } else {
            setContentView(R.layout.activity_recent_files)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (24 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        repository = RecentsRepository.getInstance(this)
        setupViews()
        setupBackNavigation()
        setupChangeWatcher()

        lifecycleScope.launch {
            repository?.observeRecentFiles()?.collect { items ->
                allRawItems.clear()
                allRawItems.addAll(items)
                updateAllCategories()
            }
        }

        refreshData(forceFull = false)
    }

    override fun onResume() {
        super.onResume()
        changeWatcher?.start()
        refreshData(forceFull = false)
    }

    override fun onPause() {
        super.onPause()
        changeWatcher?.stop()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (categoryAdapters.values.any { it.isSelectionMode }) {
                    exitAllSelectionModes()
                } else if (::viewPager.isInitialized && viewPager.currentItem != 0) {
                    viewPager.currentItem = 0
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupChangeWatcher() {
        changeWatcher = RecentsChangeWatcher(this) {
            refreshData(forceFull = false)
        }
    }

    private fun setupViews() {
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        progressLoading = findViewById(R.id.progressLoading)
        txtHeaderSubtitle = findViewById(R.id.txtHeaderSubtitle)

        layoutRefreshStatus = findViewById(R.id.layoutRefreshStatus)
        txtRefreshStatusTitle = findViewById(R.id.txtRefreshStatusTitle)
        txtRefreshEta = findViewById(R.id.txtRefreshEta)
        progressRefreshPercent = findViewById(R.id.progressRefreshPercent)

        layoutHeaderNormal = findViewById(R.id.layoutHeaderNormal)
        layoutHeaderSelection = findViewById(R.id.layoutHeaderSelection)
        txtSelectionCount = findViewById(R.id.txtSelectionCount)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)
        btnSelectAll = findViewById(R.id.btnSelectAll)

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        btnBack.setOnClickListener {
            if (categoryAdapters.values.any { it.isSelectionMode }) {
                exitAllSelectionModes()
            } else if (::viewPager.isInitialized && viewPager.currentItem != 0) {
                viewPager.currentItem = 0
            } else {
                finish()
            }
        }

        btnViewToggle = findViewById(R.id.btnViewToggle)
        btnViewToggle?.setOnClickListener {
            ViewModeManager.showSelectionDialog(this, currentViewMode) { selectedMode ->
                ViewModeManager.save(this, selectedMode)
                applyViewMode(selectedMode)
            }
        }

        val btnMenu = findViewById<ImageView>(R.id.btnMenu)
        btnMenu.setOnClickListener { view ->
            showOverflowMenu(view)
        }

        btnCloseSelection.setOnClickListener {
            exitAllSelectionModes()
        }

        btnSelectAll.setOnClickListener {
            if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
        }

        setupAdapters()
        setupViewPager()
        setupSelectionBars()
        applyViewMode(ViewModeManager.load(this))
    }

    private fun setupAdapters() {
        for (cat in Category.entries) {
            val adapter = FileAdapter(
                isTv = isTv,
                onItemClick = { file, view ->
                    openFileItem(file, view)
                },
                onSelectionChanged = { count ->
                    updateSelectionState(count)
                }
            )
            categoryAdapters[cat] = adapter
        }
    }

    private fun setupViewPager() {
        pagerAdapter = CategoryPageAdapter()
        viewPager.adapter = pagerAdapter
        viewPager.offscreenPageLimit = Category.entries.size

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getString(Category.entries[position].titleRes)
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                exitAllSelectionModes()
                updateSubtitle()
            }
        })
    }

    private fun setupSelectionBars() {
        if (!isTv) {
            floatingQuickBar = findViewById(R.id.floatingQuickBar)
            fabTools = findViewById(R.id.fabTools)

            floatingQuickBar?.setOnActionClickListener { actionId ->
                handleQuickActionClick(actionId)
            }

            fabTools?.setOnClickListener {
                val selected = fileAdapter.getSelectedFiles()
                if (selected.isNotEmpty()) {
                    showFileToolsSheet(selected)
                }
            }
        } else {
            findViewById<View?>(R.id.btnTvActions)?.setOnClickListener {
                val selected = fileAdapter.getSelectedFiles()
                if (selected.isNotEmpty()) {
                    showTvActionDialog(selected)
                }
            }
        }
    }

    private fun applyViewMode(mode: ViewModeManager.ViewMode) {
        currentViewMode = mode
        btnViewToggle?.setImageResource(ViewModeManager.iconRes(mode))
        for (adapter in categoryAdapters.values) {
            adapter.viewMode = mode
        }
        if (::pagerAdapter.isInitialized) {
            pagerAdapter.notifyDataSetChanged()
        }
    }

    private fun exitAllSelectionModes() {
        for (adapter in categoryAdapters.values) {
            if (adapter.isSelectionMode) {
                adapter.exitSelectionMode()
            }
        }
    }

    private fun updateSubtitle() {
        val files = categoryFiles[currentCategory] ?: emptyList()
        txtHeaderSubtitle.text = getString(R.string.search_results_count, files.size)
    }

    private fun refreshData(forceFull: Boolean) {
        loadJob?.cancel()
        progressLoading.visibility = View.VISIBLE
        layoutRefreshStatus.visibility = View.VISIBLE
        layoutRefreshStatus.alpha = 1f
        txtRefreshStatusTitle.text = getString(R.string.recent_refreshing_preparing)
        txtRefreshEta.text = ""
        progressRefreshPercent.isIndeterminate = true

        loadJob = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                repository?.refresh(forceFullScan = forceFull) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (progress.totalFolders > 0 && progress.scannedFolders > 0) {
                            progressRefreshPercent.isIndeterminate = false
                            progressRefreshPercent.progress = (progress.scannedFolders * 100 / progress.totalFolders)
                            val folderDisplay = progress.currentFolder.ifEmpty { "Storage" }
                            txtRefreshStatusTitle.text = getString(
                                R.string.recent_refreshing_status,
                                progress.scannedFolders,
                                progress.totalFolders,
                                folderDisplay
                            )
                            txtRefreshEta.text = if (progress.estimatedRemainingSeconds > 0) {
                                getString(R.string.recent_refreshing_eta, progress.estimatedRemainingSeconds)
                            } else {
                                getString(R.string.recent_refreshing_eta_finishing)
                            }
                        }
                    }
                } ?: emptyList()
            }
            progressLoading.visibility = View.GONE
            txtRefreshStatusTitle.text = getString(R.string.recent_refresh_complete)
            txtRefreshEta.text = ""
            progressRefreshPercent.isIndeterminate = false
            progressRefreshPercent.progress = 100

            allRawItems.clear()
            allRawItems.addAll(items)
            updateAllCategories()

            layoutRefreshStatus.animate()
                .alpha(0f)
                .setDuration(500)
                .setStartDelay(1000)
                .withEndAction {
                    layoutRefreshStatus.visibility = View.GONE
                    layoutRefreshStatus.alpha = 1f
                }
        }
    }

    private fun updateAllCategories() {
        for (category in Category.entries) {
            val filtered = when (category) {
                Category.ALL -> allRawItems
                Category.DOCUMENTS -> allRawItems.filter { isDocument(it) }
                Category.IMAGES -> allRawItems.filter { isImage(it) }
                Category.VIDEOS -> allRawItems.filter { isVideo(it) }
                Category.AUDIO -> allRawItems.filter { isAudio(it) }
                Category.ARCHIVES -> allRawItems.filter { isArchive(it) }
            }

            val fileList = mutableListOf<File>()
            val labels = mutableMapOf<String, String>()

            for (item in filtered) {
                val fileObj = if (item.source == RecentFileSource.SAF) {
                    SafFile(
                        pathname = item.uriOrPath,
                        isDir = false,
                        docLength = item.sizeBytes,
                        docLastModified = item.lastModified,
                        documentUri = Uri.parse(item.uriOrPath)
                    )
                } else {
                    File(item.uriOrPath)
                }
                fileList.add(fileObj)
                labels[fileObj.absolutePath] = item.volumeLabel
            }

            categoryFiles[category] = fileList
            categoryLabels[category] = labels
            categoryAdapters[category]?.submitList(fileList, storageLabels = labels)
        }

        if (::pagerAdapter.isInitialized) {
            pagerAdapter.notifyDataSetChanged()
        }
        updateSubtitle()
    }

    private fun updateSelectionState(count: Int) {
        val showSelection = fileAdapter.isSelectionMode
        val showActions = count > 0

        if (::viewPager.isInitialized) {
            viewPager.isUserInputEnabled = !showSelection
        }

        if (showSelection) {
            layoutHeaderNormal.visibility = View.GONE
            layoutHeaderSelection.visibility = View.VISIBLE
            txtSelectionCount.text = if (count == 0) getString(R.string.selection_prompt_select_item) else getString(R.string.selection_count, count)

            val isAll = fileAdapter.isAllSelected()
            btnSelectAll.setImageResource(if (isAll) R.drawable.ic_deselect_all else R.drawable.ic_select_all)
            btnSelectAll.contentDescription = getString(if (isAll) R.string.action_deselect_all else R.string.action_select_all)

            if (!isTv) {
                val pm = ToolbarIconsPreferenceManager
                val isQuickBarOn = pm.isQuickBarEnabled(this)
                if (isQuickBarOn && showActions) {
                    val imgFiles = fileAdapter.getSelectedFiles()
                    val state = FloatingQuickActionBar.SelectionState(
                        selectedCount = count,
                        isAllSelected = isAll,
                        hasHidden = fileAdapter.hasAnySelectedHidden(),
                        hasVisible = fileAdapter.hasAnySelectedVisible(),
                        hasProtected = fileAdapter.hasAnySelectedProtected(this),
                        hasUnprotected = fileAdapter.hasAnySelectedUnprotected(this),
                        hasPinned = fileAdapter.hasAnySelectedPinned(this),
                        hasUnpinned = fileAdapter.hasAnySelectedUnpinned(this),
                        hasArchiveSelected = imgFiles.isNotEmpty() && imgFiles.any { ArchiveManager.isSupportedArchive(it) },
                        allImagesSelected = imgFiles.isNotEmpty() && imgFiles.all {
                            it.extension.lowercase() in FileViewerRouter.IMAGE_EXTENSIONS
                        },
                        allAudioSelected = imgFiles.isNotEmpty() && imgFiles.all {
                            it.isFile && FileViewerRouter.isAudio(it.extension)
                        }
                    )
                    floatingQuickBar?.bindSelection(state)
                    floatingQuickBar?.showAnimated()
                    fabTools?.visibility = View.GONE
                } else {
                    floatingQuickBar?.hideAnimated()
                    fabTools?.visibility = if (showActions) View.VISIBLE else View.GONE
                }
            }
        } else {
            layoutHeaderNormal.visibility = View.VISIBLE
            layoutHeaderSelection.visibility = View.GONE
            if (!isTv) {
                floatingQuickBar?.hideAnimated()
                fabTools?.visibility = View.GONE
            }
        }
    }

    private fun openFileItem(file: File, transitionView: View?) {
        // Try built-in UFM viewer first
        if (FileViewerRouter.openFile(this, file, transitionView)) return

        // Fallback to external application
        try {
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            val uri = if (file is SafFile && file.documentUri != null) {
                file.documentUri
            } else {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app_found_to_open_this_file_type, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCopyOrCut(selected: List<File>, isMove: Boolean) {
        val op = if (isMove) FileClipboard.Operation.MOVE else FileClipboard.Operation.COPY
        val parentPath = selected.firstOrNull()?.parent ?: "/storage/emulated/0"
        FileClipboard.pushLocalSlot(selected, op, parentPath)
        fileAdapter.exitSelectionMode()
        val resId = if (isMove) R.string.clipboard_cut else R.string.clipboard_copied
        Toast.makeText(this, getString(resId, selected.size), Toast.LENGTH_SHORT).show()
    }

    private fun handleDeleteFiles(selected: List<File>) {
        if (selected.isEmpty()) return
        val layoutRes = if (isTv) R.layout.dialog_search_delete_confirm_tv else R.layout.dialog_search_delete_confirm
        val dialogView = layoutInflater.inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val txtDeleteMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        val btnDeleteConfirm = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteConfirm)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        if (selected.size == 1) {
            txtDeleteMessage.text = getString(R.string.delete_filename, selected.first().name)
        } else {
            txtDeleteMessage.text = getString(R.string.delete_message_files, selected.size)
        }

        btnDeleteConfirm.setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch(Dispatchers.IO) {
                var deletedCount = 0
                val deletedPaths = mutableListOf<String>()
                for (file in selected) {
                    val path = file.absolutePath
                    val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                    if (deleted) {
                        deletedCount++
                        deletedPaths.add(path)
                        try {
                            contentResolver.delete(
                                MediaStore.Files.getContentUri("external"),
                                "${MediaStore.MediaColumns.DATA} = ?",
                                arrayOf(path)
                            )
                        } catch (_: Exception) {}
                    }
                }
                repository?.removePaths(deletedPaths)
                withContext(Dispatchers.Main) {
                    fileAdapter.exitSelectionMode()
                    allRawItems.removeAll { it.uriOrPath in deletedPaths }
                    updateAllCategories()
                    Toast.makeText(this@RecentFilesActivity, getString(R.string.delete_success, deletedCount), Toast.LENGTH_SHORT).show()
                    refreshData(forceFull = true)
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun handleShareFiles(selected: List<File>) {
        if (selected.isEmpty()) return
        try {
            val uris = ArrayList<Uri>()
            for (f in selected) {
                val uri = if (f is SafFile && f.documentUri != null) {
                    f.documentUri
                } else {
                    FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
                }
                uris.add(uri)
            }

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    type = "*/*"
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    type = "*/*"
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share files: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameDialog(file: File) {
        val layoutRes = if (isTv) R.layout.dialog_search_rename_tv else R.layout.dialog_search_rename
        val dialogView = layoutInflater.inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val txtOriginalName = dialogView.findViewById<TextView>(R.id.txtOriginalName)
        val editFileName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editFileName)
        val btnSaveRename = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveRename)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)
        val inputLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.inputLayoutFileName)

        if (!isTv) {
            val primaryColor = ThemeColors.primary(this)
            val onPrimaryColor = ThemeColors.onPrimary(this)
            inputLayout?.setBoxStrokeColor(primaryColor)
            btnSaveRename.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            btnSaveRename.setTextColor(onPrimaryColor)
        }

        txtOriginalName.text = file.name
        editFileName.setText(file.name)
        val dotIndex = file.name.lastIndexOf('.')
        if (!file.isDirectory && dotIndex > 0) {
            editFileName.setSelection(0, dotIndex)
        } else {
            editFileName.selectAll()
        }

        btnSaveRename.setOnClickListener {
            val newName = editFileName.text?.toString()?.trim().orEmpty()
            if (newName.isNotEmpty() && newName != file.name) {
                val target = File(file.parentFile, newName)
                val isSaf = file is SafFile ||
                        SafTreeManager.isSafPath(file.absolutePath) ||
                        SafTreeManager.hasTreePermissionForPath(this, file.absolutePath)
                val success = if (isSaf) {
                    SafTreeManager.rename(this, file.absolutePath, newName)
                } else {
                    file.renameTo(target)
                }
                if (success) {
                    val oldPath = file.absolutePath
                    fileAdapter.exitSelectionMode()
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            contentResolver.delete(
                                MediaStore.Files.getContentUri("external"),
                                "${MediaStore.MediaColumns.DATA} = ?",
                                arrayOf(oldPath)
                            )
                        } catch (_: Exception) {}
                        MediaScannerNotifier.scanFile(this@RecentFilesActivity, target.absolutePath)
                        repository?.onFileRenamed(oldPath, target)
                        val targetExists = target.exists() && target.isFile
                        val targetLastModified = if (targetExists) target.lastModified() else 0L
                        val targetLength = if (targetExists) target.length() else 0L
                        withContext(Dispatchers.Main) {
                            val oldItem = allRawItems.find { it.uriOrPath == oldPath }
                            val volLabel = oldItem?.volumeLabel ?: getString(R.string.internal_storage)
                            val volId = oldItem?.volumeId ?: "internal"
                            allRawItems.removeAll { it.uriOrPath == oldPath }
                            if (targetExists) {
                                allRawItems.add(
                                    0,
                                    RecentFileItem(
                                        displayName = target.name,
                                        uriOrPath = target.absolutePath,
                                        lastModified = targetLastModified,
                                        volumeLabel = volLabel,
                                        volumeId = volId,
                                        source = RecentFileSource.FULL_FS,
                                        sizeBytes = targetLength
                                    )
                                )
                            }
                            updateAllCategories()
                            refreshData(forceFull = true)
                            Toast.makeText(this@RecentFilesActivity, getString(R.string.rename_success, newName), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, R.string.rename_error, Toast.LENGTH_SHORT).show()
                }
            }
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showFileToolsSheet(selected: List<File>) {
        if (selected.isEmpty()) return
        val count = selected.size
        val pm = ToolbarIconsPreferenceManager
        val list = mutableListOf<FileToolsBottomSheet.ActionItem>()

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

        // 2. Move
        if (pm.isIconEnabled(this, pm.KEY_MOVE)) {
            list.add(FileToolsBottomSheet.ActionItem("move", getString(R.string.action_move), R.drawable.ic_move, "toolbar_move") {
                handleCopyOrCut(selected, isMove = true)
            })
        }

        // 3. Delete
        if (pm.isIconEnabled(this, pm.KEY_DELETE)) {
            list.add(FileToolsBottomSheet.ActionItem("delete", getString(R.string.action_delete), R.drawable.ic_delete, "toolbar_delete") {
                handleDeleteFiles(selected)
            })
        }

        // 4. Rename
        if (pm.isIconEnabled(this, pm.KEY_RENAME)) {
            list.add(FileToolsBottomSheet.ActionItem("rename", getString(R.string.action_rename), R.drawable.ic_edit, "toolbar_rename") {
                if (selected.size == 1) {
                    showRenameDialog(selected.first())
                } else {
                    val items = selected.map { BatchRenameItem.fromLocalFile(it) }
                    val dialog = BatchRenameDialogFragment.newInstance(items)
                    dialog.setOnCompleteListener { _, _, _ ->
                        fileAdapter.exitSelectionMode()
                        lifecycleScope.launch(Dispatchers.IO) {
                            repository?.purgeNonExistentFiles()
                            val parentDirs = selected.mapNotNull { it.parentFile }.distinct()
                            for (parent in parentDirs) {
                                MediaScannerNotifier.scanDirectory(this@RecentFilesActivity, parent, recursive = false)
                            }
                            withContext(Dispatchers.Main) {
                                refreshData(forceFull = true)
                            }
                        }
                    }
                    dialog.show(supportFragmentManager, BatchRenameDialogFragment.TAG)
                }
            })
        }

        // 5. Share
        if (pm.isIconEnabled(this, pm.KEY_SHARE)) {
            val shareable = selected.filter { it.isFile }
            if (shareable.isNotEmpty()) {
                list.add(FileToolsBottomSheet.ActionItem("share", getString(R.string.action_share), R.drawable.ic_share, "toolbar_share") {
                    handleShareFiles(shareable)
                })
            }
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
            it.extension.lowercase() in FileViewerRouter.IMAGE_EXTENSIONS
        }
        if (allImages && pm.isIconEnabled(this, pm.KEY_IMAGE_COMPRESS)) {
            list.add(FileToolsBottomSheet.ActionItem("image_compress", getString(R.string.action_compress_image), R.drawable.ic_compress_image, "toolbar_image_compress") {
                startActivity(Intent(this, ImageCompressActivity::class.java).apply {
                    putStringArrayListExtra(
                        ImageCompressActivity.EXTRA_FILE_PATHS,
                        ArrayList(selected.map { it.absolutePath })
                    )
                })
            })
        }

        // 8. Create GIF (2+ images)
        if (selected.size >= 2 && allImages && pm.isIconEnabled(this, pm.KEY_CREATE_GIF)) {
            list.add(FileToolsBottomSheet.ActionItem("create_gif", getString(R.string.action_create_gif), R.drawable.ic_gif, "toolbar_create_gif") {
                startActivity(Intent(this, GifCreatorActivity::class.java).apply {
                    putStringArrayListExtra(
                        GifCreatorActivity.EXTRA_FILE_PATHS,
                        ArrayList(selected.map { it.absolutePath })
                    )
                })
            })
        }

        // 9. EXIF Tools (Mobile Only)
        if (allImages && !isTv && pm.isIconEnabled(this, pm.KEY_EXIF_TOOLS)) {
            list.add(FileToolsBottomSheet.ActionItem("exif_tools", getString(R.string.action_exif_cleaner_renamer), R.drawable.ic_exif_cleaner, "toolbar_exif_cleaner") {
                startActivity(Intent(this, ExifToolsActivity::class.java).apply {
                    putStringArrayListExtra(
                        ExifToolsActivity.EXTRA_FILE_PATHS,
                        ArrayList(selected.map { it.absolutePath })
                    )
                })
            })
        }

        // 10. Wallpaper (single image, mobile only)
        val isSingleImage = count == 1 && selected.first().isFile &&
            selected.first().extension.lowercase() in FileViewerRouter.IMAGE_EXTENSIONS
        if (isSingleImage && !isTv) {
            val img = selected.first()
            if (pm.isIconEnabled(this, pm.KEY_SET_HOME_WALLPAPER)) {
                list.add(FileToolsBottomSheet.ActionItem("set_home_wallpaper", getString(R.string.action_set_home_wallpaper), R.drawable.ic_wallpaper_home, "toolbar_set_home_wallpaper") {
                    WallpaperHelper.showConfirmDialog(this, img.name, android.app.WallpaperManager.FLAG_SYSTEM) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = WallpaperHelper.setWallpaper(this@RecentFilesActivity, img, android.app.WallpaperManager.FLAG_SYSTEM)
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                val msg = if (success) getString(R.string.toast_wallpaper_set_home_success) else getString(R.string.toast_wallpaper_set_failed)
                                Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            }
            if (pm.isIconEnabled(this, pm.KEY_SET_LOCK_WALLPAPER)) {
                list.add(FileToolsBottomSheet.ActionItem("set_lock_wallpaper", getString(R.string.action_set_lock_wallpaper), R.drawable.ic_wallpaper_lock, "toolbar_set_lock_wallpaper") {
                    WallpaperHelper.showConfirmDialog(this, img.name, android.app.WallpaperManager.FLAG_LOCK) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = WallpaperHelper.setWallpaper(this@RecentFilesActivity, img, android.app.WallpaperManager.FLAG_LOCK)
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                val msg = if (success) getString(R.string.toast_wallpaper_set_lock_success) else getString(R.string.toast_wallpaper_set_failed)
                                Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            }
        }

        // 11. Audio -> System Sounds (single audio, mobile only)
        val isSingleAudio = count == 1 && selected.first().isFile && FileViewerRouter.isAudio(selected.first().extension)
        if (isSingleAudio && !isTv) {
            val audio = selected.first()
            if (pm.isIconEnabled(this, pm.KEY_SET_RINGTONE)) {
                list.add(FileToolsBottomSheet.ActionItem("set_ringtone", getString(R.string.action_set_ringtone), R.drawable.ic_ringtone, "toolbar_set_ringtone") {
                    RingtoneHelper.showConfirmDialog(this, audio.name, android.media.RingtoneManager.TYPE_RINGTONE) {
                        if (!RingtoneHelper.canWriteSettings(this)) {
                            RingtoneHelper.requestWriteSettings(this)
                            return@showConfirmDialog
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = RingtoneHelper.setAsSystemSound(this@RecentFilesActivity, audio, android.media.RingtoneManager.TYPE_RINGTONE)
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                val msg = if (success) getString(R.string.toast_ringtone_set_success) else getString(R.string.toast_sound_set_failed)
                                Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            }
        }

        // Audio → Music Tagger (All selected items are audio files, mobile only)
        val allAudio = selected.isNotEmpty() && selected.all {
            it.isFile && FileViewerRouter.isAudio(it.extension)
        }
        if (allAudio && !isTv && pm.isIconEnabled(this, pm.KEY_MUSIC_TAGGER)) {
            list.add(FileToolsBottomSheet.ActionItem("music_tagger", getString(R.string.action_music_tagger), R.drawable.ic_music_tag, "toolbar_music_tagger") {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.viewer.MusicTaggerActivity::class.java).apply {
                    putStringArrayListExtra(
                        za.kilowatch.ultimatefilemanager.viewer.MusicTaggerActivity.EXTRA_FILE_PATHS,
                        ArrayList(selected.map { it.absolutePath })
                    )
                })
            })
        }

        // 12. Hide / Unhide
        val hasVisible = fileAdapter.hasAnySelectedVisible()
        if (hasVisible && pm.isIconEnabled(this, pm.KEY_HIDE)) {
            list.add(FileToolsBottomSheet.ActionItem("hide", getString(R.string.hide), R.drawable.ic_eye_off, "toolbar_hide") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) HiddenFilesManager.hide(file.absolutePath)
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                    }
                }
            })
        }
        val hasHidden = fileAdapter.hasAnySelectedHidden()
        if (hasHidden && pm.isIconEnabled(this, pm.KEY_UNHIDE)) {
            list.add(FileToolsBottomSheet.ActionItem("unhide", getString(R.string.unhide), R.drawable.ic_eye, "toolbar_unhide") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) HiddenFilesManager.unhide(file.absolutePath)
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                    }
                }
            })
        }

        // 13. Protect / Unprotect
        val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(this)
        if (hasUnprotected && pm.isIconEnabled(this, pm.KEY_PROTECT)) {
            list.add(FileToolsBottomSheet.ActionItem("protect", getString(R.string.protect), R.drawable.ic_shield_protected, "toolbar_protect") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) ProtectedFilesManager.setProtected(this@RecentFilesActivity, file.absolutePath, protected = true)
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                    }
                }
            })
        }
        val hasProtected = fileAdapter.hasAnySelectedProtected(this)
        if (hasProtected && pm.isIconEnabled(this, pm.KEY_UNPROTECT)) {
            list.add(FileToolsBottomSheet.ActionItem("unprotect", getString(R.string.unprotect), R.drawable.ic_shield_unprotected, "toolbar_unprotect") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) ProtectedFilesManager.setProtected(this@RecentFilesActivity, file.absolutePath, protected = false)
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                    }
                }
            })
        }

        // 14. Pin / Unpin
        val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(this)
        if (hasUnpinned && pm.isIconEnabled(this, pm.KEY_PIN)) {
            list.add(FileToolsBottomSheet.ActionItem("pin", getString(R.string.pin), R.drawable.ic_paperclip, "toolbar_pin") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) PinnedFilesManager.setPinned(this@RecentFilesActivity, file.absolutePath, pinned = true)
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                    }
                }
            })
        }
        val hasPinned = fileAdapter.hasAnySelectedPinned(this)
        if (hasPinned && pm.isIconEnabled(this, pm.KEY_UNPIN)) {
            list.add(FileToolsBottomSheet.ActionItem("unpin", getString(R.string.unpin), R.drawable.ic_paperclip_off, "toolbar_unpin") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) PinnedFilesManager.setPinned(this@RecentFilesActivity, file.absolutePath, pinned = false)
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                    }
                }
            })
        }

        // 15. Checksum & Compare
        if (count > 0 && selected.any { !it.isDirectory } && pm.isIconEnabled(this, pm.KEY_CHECKSUM)) {
            list.add(FileToolsBottomSheet.ActionItem("checksum", getString(R.string.action_checksum), R.drawable.ic_checksum, "toolbar_checksum") {
                val files = selected.filter { !it.isDirectory }
                if (files.isNotEmpty()) {
                    fileAdapter.exitSelectionMode()
                    val sources = files.map { LocalFileSource(it) }
                    ChecksumDialogFragment.newInstance(sources)
                        .show(supportFragmentManager, ChecksumDialogFragment.TAG)
                }
            })
        }
        if (count == 2 && selected.all { !it.isDirectory } && pm.isIconEnabled(this, pm.KEY_CHECKSUM)) {
            list.add(FileToolsBottomSheet.ActionItem("compare_files", getString(R.string.action_compare_files), R.drawable.ic_checksum, "toolbar_checksum") {
                fileAdapter.exitSelectionMode()
                val sources = selected.map { LocalFileSource(it) }
                FileCompareDialogFragment.newInstance(sources[0], sources[1])
                    .show(supportFragmentManager, FileCompareDialogFragment.TAG)
            })
        }

        // 16. Properties
        if (count > 0) {
            list.add(FileToolsBottomSheet.ActionItem("properties", getString(R.string.action_properties), R.drawable.ic_about, "toolbar_properties") {
                FilePropertiesBottomSheet.newInstanceForLocalFiles(selected)
                    .show(supportFragmentManager, FilePropertiesBottomSheet.TAG)
            })
        }

        val displayList = pm.filterItemsForBottomSheet(this, list)
        val finalDisplayList = if (displayList.isNotEmpty()) displayList else list
        if (finalDisplayList.isNotEmpty()) {
            val title = getString(R.string.action_tools)
            val subtitle = getString(R.string.selection_count, selected.size)
            FileToolsBottomSheet.newInstance(finalDisplayList, title, subtitle)
                .show(supportFragmentManager, FileToolsBottomSheet.TAG)
        }
    }

    private fun handleQuickActionClick(actionId: String) {
        val selected = fileAdapter.getSelectedFiles()
        if (selected.isEmpty()) return
        val count = selected.size
        val pm = ToolbarIconsPreferenceManager

        when (actionId) {
            pm.ACTION_DELETE -> handleDeleteFiles(selected)
            pm.ACTION_COPY -> handleCopyOrCut(selected, isMove = false)
            pm.ACTION_MOVE -> handleCopyOrCut(selected, isMove = true)
            pm.ACTION_RENAME -> {
                if (selected.size == 1) {
                    showRenameDialog(selected.first())
                } else if (selected.size > 1) {
                    val items = selected.map { BatchRenameItem.fromLocalFile(it) }
                    val dialog = BatchRenameDialogFragment.newInstance(items)
                    dialog.setOnCompleteListener { _, _, _ ->
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = true)
                    }
                    dialog.show(supportFragmentManager, BatchRenameDialogFragment.TAG)
                }
            }
            pm.ACTION_SHARE -> handleShareFiles(selected)
            pm.ACTION_COMPRESS -> showArchiveOptions(selected)
            pm.ACTION_EXTRACT -> performExtractHere(selected)
            pm.ACTION_SELECT_ALL -> {
                if (fileAdapter.isAllSelected()) fileAdapter.deselectAll() else fileAdapter.selectAll()
            }
            pm.ACTION_INVERT_SELECTION -> fileAdapter.invertSelection()
            "protect", pm.ACTION_PROTECT_UNPROTECT -> {
                val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(this)
                val targetProtect = hasUnprotected
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        ProtectedFilesManager.setProtected(this@RecentFilesActivity, file.absolutePath, protected = targetProtect)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                        val msg = if (targetProtect) getString(R.string.toast_protected_success, selected.size) else getString(R.string.toast_unprotected_success, selected.size)
                        Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "unprotect" -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        ProtectedFilesManager.setProtected(this@RecentFilesActivity, file.absolutePath, protected = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                        Toast.makeText(this@RecentFilesActivity, getString(R.string.toast_unprotected_success, selected.size), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "hide", pm.ACTION_HIDE_UNHIDE -> {
                val hasVisible = fileAdapter.hasAnySelectedVisible()
                val targetHide = hasVisible
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        if (targetHide) {
                            HiddenFilesManager.hide(file.absolutePath)
                        } else {
                            HiddenFilesManager.unhide(file.absolutePath)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                        val msg = if (targetHide) getString(R.string.toast_hidden_success, selected.size) else getString(R.string.toast_unhidden_success, selected.size)
                        Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "unhide" -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        HiddenFilesManager.unhide(file.absolutePath)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                        Toast.makeText(this@RecentFilesActivity, getString(R.string.toast_unhidden_success, selected.size), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "pin", pm.ACTION_PIN_UNPIN -> {
                val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(this)
                val targetPin = hasUnpinned
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        PinnedFilesManager.setPinned(this@RecentFilesActivity, file.absolutePath, pinned = targetPin)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                        val msg = if (targetPin) getString(R.string.toast_pinned_success, selected.size) else getString(R.string.toast_unpinned_success, selected.size)
                        Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "unpin" -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selected) {
                        PinnedFilesManager.setPinned(this@RecentFilesActivity, file.absolutePath, pinned = false)
                    }
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        refreshData(forceFull = false)
                        Toast.makeText(this@RecentFilesActivity, getString(R.string.toast_unpinned_success, selected.size), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            pm.ACTION_IMAGE_COMPRESS -> {
                startActivity(Intent(this, ImageCompressActivity::class.java).apply {
                    putStringArrayListExtra(
                        ImageCompressActivity.EXTRA_FILE_PATHS,
                        ArrayList(selected.map { it.absolutePath })
                    )
                })
            }
            pm.ACTION_CREATE_GIF -> {
                startActivity(Intent(this, GifCreatorActivity::class.java).apply {
                    putStringArrayListExtra(
                        GifCreatorActivity.EXTRA_FILE_PATHS,
                        ArrayList(selected.map { it.absolutePath })
                    )
                })
            }
            pm.ACTION_EXIF_TOOLS -> {
                startActivity(Intent(this, ExifToolsActivity::class.java).apply {
                    putStringArrayListExtra(
                        ExifToolsActivity.EXTRA_FILE_PATHS,
                        ArrayList(selected.map { it.absolutePath })
                    )
                })
            }
            pm.ACTION_SET_HOME_WALLPAPER -> {
                if (count == 1 && selected.first().isFile) {
                    val imageFile = selected.first()
                    WallpaperHelper.showConfirmDialog(
                        this, imageFile.name, android.app.WallpaperManager.FLAG_SYSTEM
                    ) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = WallpaperHelper.setWallpaper(
                                this@RecentFilesActivity, imageFile, android.app.WallpaperManager.FLAG_SYSTEM
                            )
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                val msg = if (success) getString(R.string.toast_wallpaper_set_home_success) else getString(R.string.toast_wallpaper_set_failed)
                                Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            pm.ACTION_SET_LOCK_WALLPAPER -> {
                if (count == 1 && selected.first().isFile) {
                    val imageFile = selected.first()
                    WallpaperHelper.showConfirmDialog(
                        this, imageFile.name, android.app.WallpaperManager.FLAG_LOCK
                    ) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = WallpaperHelper.setWallpaper(
                                this@RecentFilesActivity, imageFile, android.app.WallpaperManager.FLAG_LOCK
                            )
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                val msg = if (success) getString(R.string.toast_wallpaper_set_lock_success) else getString(R.string.toast_wallpaper_set_failed)
                                Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            pm.ACTION_SET_RINGTONE -> {
                if (count == 1 && selected.first().isFile) {
                    val audioFile = selected.first()
                    RingtoneHelper.showConfirmDialog(
                        this, audioFile.name, android.media.RingtoneManager.TYPE_RINGTONE
                    ) {
                        if (!RingtoneHelper.canWriteSettings(this@RecentFilesActivity)) {
                            Toast.makeText(this@RecentFilesActivity, R.string.toast_sound_permission_required, Toast.LENGTH_LONG).show()
                            RingtoneHelper.requestWriteSettings(this@RecentFilesActivity)
                            return@showConfirmDialog
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = RingtoneHelper.setAsSystemSound(
                                this@RecentFilesActivity, audioFile, android.media.RingtoneManager.TYPE_RINGTONE
                            )
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                val msg = if (success) getString(R.string.toast_ringtone_set_success) else getString(R.string.toast_sound_set_failed)
                                Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            pm.ACTION_SET_NOTIFICATION -> {
                if (count == 1 && selected.first().isFile) {
                    val audioFile = selected.first()
                    RingtoneHelper.showConfirmDialog(
                        this, audioFile.name, android.media.RingtoneManager.TYPE_NOTIFICATION
                    ) {
                        if (!RingtoneHelper.canWriteSettings(this@RecentFilesActivity)) {
                            Toast.makeText(this@RecentFilesActivity, R.string.toast_sound_permission_required, Toast.LENGTH_LONG).show()
                            RingtoneHelper.requestWriteSettings(this@RecentFilesActivity)
                            return@showConfirmDialog
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = RingtoneHelper.setAsSystemSound(
                                this@RecentFilesActivity, audioFile, android.media.RingtoneManager.TYPE_NOTIFICATION
                            )
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                val msg = if (success) getString(R.string.toast_notification_set_success) else getString(R.string.toast_sound_set_failed)
                                Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            pm.ACTION_SET_ALARM -> {
                if (count == 1 && selected.first().isFile) {
                    val audioFile = selected.first()
                    RingtoneHelper.showConfirmDialog(
                        this, audioFile.name, android.media.RingtoneManager.TYPE_ALARM
                    ) {
                        if (!RingtoneHelper.canWriteSettings(this@RecentFilesActivity)) {
                            Toast.makeText(this@RecentFilesActivity, R.string.toast_sound_permission_required, Toast.LENGTH_LONG).show()
                            RingtoneHelper.requestWriteSettings(this@RecentFilesActivity)
                            return@showConfirmDialog
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = RingtoneHelper.setAsSystemSound(
                                this@RecentFilesActivity, audioFile, android.media.RingtoneManager.TYPE_ALARM
                            )
                            withContext(Dispatchers.Main) {
                                fileAdapter.exitSelectionMode()
                                val msg = if (success) getString(R.string.toast_alarm_set_success) else getString(R.string.toast_sound_set_failed)
                                Toast.makeText(this@RecentFilesActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            pm.ACTION_CHECKSUM -> {
                val files = selected.filter { !it.isDirectory }
                if (files.isNotEmpty()) {
                    fileAdapter.exitSelectionMode()
                    val sources = files.map { LocalFileSource(it) }
                    ChecksumDialogFragment.newInstance(sources)
                        .show(supportFragmentManager, ChecksumDialogFragment.TAG)
                }
            }
            pm.ACTION_MUSIC_TAGGER -> {
                val audioFiles = selected.filter { it.isFile && FileViewerRouter.isAudio(it.extension) }
                if (audioFiles.isNotEmpty()) {
                    fileAdapter.exitSelectionMode()
                    startActivity(Intent(this, za.kilowatch.ultimatefilemanager.viewer.MusicTaggerActivity::class.java).apply {
                        putStringArrayListExtra(
                            za.kilowatch.ultimatefilemanager.viewer.MusicTaggerActivity.EXTRA_FILE_PATHS,
                            ArrayList(audioFiles.map { it.absolutePath })
                        )
                    })
                }
            }
            pm.ACTION_MORE -> {
                showFileToolsSheet(selected)
            }
        }
    }

    private fun showArchiveOptions(files: List<File>) {
        val dialog = ArchiveOptionsDialog()
        dialog.setOnConfirm { filename, format, password, _ ->
            val destDir = files.firstOrNull()?.parentFile ?: File("/storage/emulated/0/Download")
            val destArchiveFile = File(destDir, "$filename${format.displayName}")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ArchiveManager.compress(
                        context = this@RecentFilesActivity,
                        sourceFiles = files,
                        destFile = destArchiveFile,
                        password = password,
                        format = format
                    )
                    withContext(Dispatchers.Main) {
                        fileAdapter.exitSelectionMode()
                        Toast.makeText(
                            this@RecentFilesActivity,
                            getString(R.string.compression_completed_destfilename, destArchiveFile.name),
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshData(forceFull = true)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@RecentFilesActivity,
                            getString(R.string.compression_failed_emessage, e.message ?: ""),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        dialog.show(supportFragmentManager, "ArchiveOptions")
    }

    private fun performExtractHere(files: List<File>) {
        val archives = files.filter { ArchiveManager.isSupportedArchive(it) }
        if (archives.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            var extractedCount = 0
            for (archive in archives) {
                val destDir = archive.parentFile ?: continue
                try {
                    val result = ArchiveManager.extract(
                        context = this@RecentFilesActivity,
                        archiveFile = archive,
                        destDir = destDir
                    )
                    if (result.isSuccess) extractedCount++
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                fileAdapter.exitSelectionMode()
                refreshData(forceFull = true)
                Toast.makeText(this@RecentFilesActivity, getString(R.string.extract_success), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTvActionDialog(selected: List<File>) {
        val list = mutableListOf<Pair<String, () -> Unit>>()
        list.add(Pair(getString(R.string.action_copy)) { handleCopyOrCut(selected, isMove = false) })
        list.add(Pair(getString(R.string.action_move)) { handleCopyOrCut(selected, isMove = true) })
        list.add(Pair(getString(R.string.action_delete)) { handleDeleteFiles(selected) })
        if (selected.size == 1) {
            list.add(Pair(getString(R.string.action_rename)) { showRenameDialog(selected.first()) })
        }
        list.add(Pair(getString(R.string.action_share)) { handleShareFiles(selected) })
        list.add(Pair(getString(R.string.action_compress)) { showArchiveOptions(selected) })
        val hasArchives = selected.any { ArchiveManager.isSupportedArchive(it) }
        if (hasArchives) {
            list.add(Pair(getString(R.string.action_extract_here)) { performExtractHere(selected) })
        }
        val hasUnprotected = fileAdapter.hasAnySelectedUnprotected(this)
        if (hasUnprotected) {
            list.add(Pair(getString(R.string.protect)) { handleQuickActionClick("protect") })
        }
        val hasProtected = fileAdapter.hasAnySelectedProtected(this)
        if (hasProtected) {
            list.add(Pair(getString(R.string.unprotect)) { handleQuickActionClick("unprotect") })
        }
        val hasVisible = fileAdapter.hasAnySelectedVisible()
        if (hasVisible) {
            list.add(Pair(getString(R.string.hide)) { handleQuickActionClick("hide") })
        }
        val hasHidden = fileAdapter.hasAnySelectedHidden()
        if (hasHidden) {
            list.add(Pair(getString(R.string.unhide)) { handleQuickActionClick("unhide") })
        }
        val hasUnpinned = fileAdapter.hasAnySelectedUnpinned(this)
        if (hasUnpinned) {
            list.add(Pair(getString(R.string.pin)) { handleQuickActionClick("pin") })
        }
        val hasPinned = fileAdapter.hasAnySelectedPinned(this)
        if (hasPinned) {
            list.add(Pair(getString(R.string.unpin)) { handleQuickActionClick("unpin") })
        }
        if (selected.any { !it.isDirectory }) {
            list.add(Pair(getString(R.string.action_checksum)) { handleQuickActionClick(ToolbarIconsPreferenceManager.ACTION_CHECKSUM) })
        }
        if (selected.size == 2 && selected.all { !it.isDirectory }) {
            list.add(Pair(getString(R.string.action_compare_files)) {
                fileAdapter.exitSelectionMode()
                val sources = selected.map { LocalFileSource(it) }
                FileCompareDialogFragment.newInstance(sources[0], sources[1])
                    .show(supportFragmentManager, FileCompareDialogFragment.TAG)
            })
        }
        list.add(Pair(getString(R.string.action_invert_selection)) { fileAdapter.invertSelection() })
        list.add(Pair(getString(R.string.action_properties)) {
            FilePropertiesBottomSheet.newInstanceForLocalFiles(selected)
                .show(supportFragmentManager, FilePropertiesBottomSheet.TAG)
        })

        val titles = list.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(getString(R.string.selection_count, selected.size))
            .setItems(titles) { _, which ->
                list[which].second.invoke()
            }
            .show()
    }

    private fun showOverflowMenu(anchor: View) {
        if (isTv) {
            showTvOptionsMenu()
            return
        }

        val popupView = layoutInflater.inflate(R.layout.popup_recents_options_menu, null)
        val popupWidth = (230 * resources.displayMetrics.density).toInt()
        val popupWindow = PopupWindow(
            popupView,
            popupWidth,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 16f * resources.displayMetrics.density
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            animationStyle = android.R.style.Animation_Dialog
        }

        popupView.findViewById<View>(R.id.menuItemRefresh)?.setOnClickListener {
            popupWindow.dismiss()
            refreshData(forceFull = true)
        }

        popupView.findViewById<View>(R.id.menuItemClearHistory)?.setOnClickListener {
            popupWindow.dismiss()
            lifecycleScope.launch {
                repository?.clearHistory()
                allRawItems.clear()
                updateAllCategories()
                Toast.makeText(this@RecentFilesActivity, R.string.recent_history_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        popupView.findViewById<View>(R.id.menuItemIndexerSettings)?.setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(this, StorageIndexerActivity::class.java))
        }

        val xOffset = -(popupWidth - anchor.width)
        popupWindow.showAsDropDown(anchor, xOffset, (4 * resources.displayMetrics.density).toInt())
    }

    private fun showTvOptionsMenu() {
        val items = arrayOf(
            getString(R.string.refresh),
            getString(R.string.recent_clear_history),
            getString(R.string.storage_indexer_title)
        )

        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.recent_files_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> refreshData(forceFull = true)
                    1 -> {
                        lifecycleScope.launch {
                            repository?.clearHistory()
                            allRawItems.clear()
                            updateAllCategories()
                            Toast.makeText(this@RecentFilesActivity, R.string.recent_history_cleared, Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> startActivity(Intent(this, StorageIndexerActivity::class.java))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    inner class CategoryPageAdapter : RecyclerView.Adapter<CategoryPageAdapter.CategoryViewHolder>() {

        inner class CategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val recyclerCategoryFiles: RecyclerView = view.findViewById(R.id.recyclerCategoryFiles)
            val layoutPageEmpty: View = view.findViewById(R.id.layoutPageEmpty)
            val txtPageEmptyTitle: TextView = view.findViewById(R.id.txtPageEmptyTitle)
            val txtPageEmptyDesc: TextView = view.findViewById(R.id.txtPageEmptyDesc)
            val btnPageGrantSaf: View = view.findViewById(R.id.btnPageGrantSaf)
        }

        override fun getItemCount(): Int = Category.entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
            val layoutRes = if (isTv) R.layout.item_recent_category_page_tv else R.layout.item_recent_category_page
            val view = layoutInflater.inflate(layoutRes, parent, false)
            return CategoryViewHolder(view)
        }

        override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
            val category = Category.entries[position]
            val adapter = categoryAdapters[category] ?: return

            val isGrid = ViewModeManager.isGrid(currentViewMode)
            val span = if (isGrid) ViewModeManager.spanCount(holder.itemView.context, currentViewMode) else 1

            val currentLm = holder.recyclerCategoryFiles.layoutManager
            val needsNewLm = when {
                holder.recyclerCategoryFiles.adapter !== adapter -> true
                isGrid && (currentLm !is GridLayoutManager || currentLm.spanCount != span) -> true
                !isGrid && currentLm !is LinearLayoutManager -> true
                else -> false
            }

            if (needsNewLm) {
                val lm = if (!isGrid) {
                    LinearLayoutManager(holder.itemView.context)
                } else {
                    GridLayoutManager(holder.itemView.context, span).apply {
                        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                            override fun getSpanSize(pos: Int): Int {
                                val vt = adapter.getItemViewType(pos)
                                return if (vt == 3 || vt == 4) spanCount else 1
                            }
                        }
                    }
                }
                holder.recyclerCategoryFiles.layoutManager = lm
                holder.recyclerCategoryFiles.adapter = adapter
            }

            val files = categoryFiles[category] ?: emptyList()
            val isEmpty = files.isEmpty()
            holder.layoutPageEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            holder.recyclerCategoryFiles.visibility = if (isEmpty) View.GONE else View.VISIBLE

            if (isEmpty) {
                val tier = AccessTierDetector.currentTier(this@RecentFilesActivity)
                if (tier == AccessTier.LIMITED && allRawItems.isEmpty()) {
                    holder.txtPageEmptyTitle.text = getString(R.string.recent_saf_empty_title)
                    holder.txtPageEmptyDesc.text = getString(R.string.recent_saf_empty_desc)
                    holder.btnPageGrantSaf.visibility = View.VISIBLE
                    holder.btnPageGrantSaf.setOnClickListener {
                        safTreeLauncher.launch(null)
                    }
                } else {
                    holder.txtPageEmptyTitle.text = getString(R.string.recent_empty_title)
                    holder.txtPageEmptyDesc.text = getString(R.string.recent_empty_desc)
                    holder.btnPageGrantSaf.visibility = View.GONE
                }
            }
        }
    }

    private fun isImage(item: RecentFileItem): Boolean {
        val ext = item.displayName.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg") || item.mimeType?.startsWith("image/") == true
    }

    private fun isVideo(item: RecentFileItem): Boolean {
        val ext = item.displayName.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mkv", "webm", "avi", "mov", "wmv", "3gp", "flv", "ts") || item.mimeType?.startsWith("video/") == true
    }

    private fun isAudio(item: RecentFileItem): Boolean {
        val ext = item.displayName.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "wma", "opus", "amr", "mid", "midi", "alac", "aiff", "mka") || item.mimeType?.startsWith("audio/") == true
    }

    private fun isArchive(item: RecentFileItem): Boolean {
        val ext = item.displayName.substringAfterLast('.', "").lowercase()
        return ext in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "apk", "xapk", "apks")
    }

    private fun isDocument(item: RecentFileItem): Boolean {
        val ext = item.displayName.substringAfterLast('.', "").lowercase()
        return ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "md", "csv", "json", "xml", "epub")
    }
}
