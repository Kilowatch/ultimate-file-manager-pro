package za.kilowatch.ultimatefilemanager.flatview

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.archive.ArchiveManager
import za.kilowatch.ultimatefilemanager.archive.ArchiveOperationType
import za.kilowatch.ultimatefilemanager.archive.ArchiveOptionsDialog
import za.kilowatch.ultimatefilemanager.archive.ArchiveProgressDialog
import za.kilowatch.ultimatefilemanager.archive.ExtractOptionsDialog
import za.kilowatch.ultimatefilemanager.checksum.ChecksumDialogFragment
import za.kilowatch.ultimatefilemanager.checksum.ChecksumManifestVerifyDialogFragment
import za.kilowatch.ultimatefilemanager.checksum.FileCompareDialogFragment
import za.kilowatch.ultimatefilemanager.checksum.LocalFileSource
import za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase
import za.kilowatch.ultimatefilemanager.media.FFmpegMediaHelper
import za.kilowatch.ultimatefilemanager.media.MediaOperationProgressDialog
import za.kilowatch.ultimatefilemanager.media.MediaOperationWorker
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager
import za.kilowatch.ultimatefilemanager.settings.FlatViewPreferenceManager
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager
import za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager
import za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
import za.kilowatch.ultimatefilemanager.shortcuts.FavoriteHomeScreenManager
import za.kilowatch.ultimatefilemanager.storage.BatchRenameDialogFragment
import za.kilowatch.ultimatefilemanager.storage.BatchRenameItem
import za.kilowatch.ultimatefilemanager.storage.BatchRenameTvActivity
import za.kilowatch.ultimatefilemanager.storage.DocumentProviderExportHelper
import za.kilowatch.ultimatefilemanager.storage.DocumentProviderRepository
import za.kilowatch.ultimatefilemanager.storage.FileAdapter
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.FileClipboard
import za.kilowatch.ultimatefilemanager.storage.FilePropertiesBottomSheet
import za.kilowatch.ultimatefilemanager.storage.FileTagsManager
import za.kilowatch.ultimatefilemanager.storage.FileToolsBottomSheet
import za.kilowatch.ultimatefilemanager.storage.MountedDocumentProvider
import za.kilowatch.ultimatefilemanager.storage.VaultActivity
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import za.kilowatch.ultimatefilemanager.storage.VaultEntry
import za.kilowatch.ultimatefilemanager.ui.FloatingQuickActionBar
import za.kilowatch.ultimatefilemanager.ui.UfmDialogHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.RingtoneHelper
import za.kilowatch.ultimatefilemanager.util.WallpaperHelper
import za.kilowatch.ultimatefilemanager.viewer.AudioTrackBottomSheet
import za.kilowatch.ultimatefilemanager.viewer.ExifToolsActivity
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.viewer.GifCreatorActivity
import za.kilowatch.ultimatefilemanager.viewer.ImageCompressActivity
import za.kilowatch.ultimatefilemanager.viewer.MusicTaggerActivity
import za.kilowatch.ultimatefilemanager.viewer.OpenAsBottomSheet
import za.kilowatch.ultimatefilemanager.viewer.SubtitleTrackBottomSheet
import java.io.File
import java.util.Locale
import java.util.UUID

class FlatViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STORAGE_ID = "extra_storage_id"
        const val EXTRA_ROOT_PATH = "extra_root_path"
        const val EXTRA_STORAGE_LABEL = "extra_storage_label"
    }

    private var storageId: String = ""
    private var rootPath: String = ""
    private var storageLabel: String = ""
    private var isTv: Boolean = false

    private val viewModel: FlatViewViewModel by viewModels {
        FlatViewViewModel.Factory(
            storageId = storageId,
            rootPath = rootPath,
            fileIndexDao = UfmIndexingDatabase.getInstance(applicationContext).fileIndexDao()
        )
    }

    private lateinit var adapter: FlatViewAdapter

    // Activity result launchers for batch rename and tools
    private val batchRenameTvLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.setEditMode(false)
        reloadFlatView()
    }

    // Views
    private var recyclerFlatView: RecyclerView? = null
    private var progressLoading: ProgressBar? = null
    private var layoutEmpty: View? = null
    private var txtHeaderTitle: TextView? = null
    private var txtHeaderSubtitle: TextView? = null
    private var layoutHeaderNormal: View? = null
    private var layoutHeaderSelection: View? = null
    private var txtSelectionCount: TextView? = null
    private var btnSelectAll: TextView? = null
    private var layoutSearchBar: View? = null
    private var edtSearch: EditText? = null
    private var btnSearchClear: ImageView? = null
    private var fabTools: ExtendedFloatingActionButton? = null
    private var floatingQuickBar: FloatingQuickActionBar? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            setContentView(R.layout.activity_flat_view_tv)
        } else {
            enableEdgeToEdge()
            setContentView(R.layout.activity_flat_view)
            applyEdgeToEdgeInsets()
        }

        storageId = intent.getStringExtra(EXTRA_STORAGE_ID) ?: ""
        rootPath = intent.getStringExtra(EXTRA_ROOT_PATH) ?: ""
        storageLabel = intent.getStringExtra(EXTRA_STORAGE_LABEL) ?: getString(R.string.internal_storage)

        initViews()
        setupRecyclerView()
        setupListeners()
        observeState()

        reloadFlatView()
    }

    private fun reloadFlatView() {
        val excludeSystem = !FlatViewPreferenceManager.isSystemFoldersIncluded(this)
        val defaultExpanded = FlatViewPreferenceManager.isDefaultExpanded(this)
        viewModel.loadData(excludeSystem, defaultExpanded)
    }

    private fun applyEdgeToEdgeInsets() {
        val root = findViewById<View>(R.id.main) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.headerLayout)?.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                0
            )

            val bottomNav = systemBars.bottom
            val density = resources.displayMetrics.density

            // Lift floatingQuickBar above bottom system bars
            (floatingQuickBar?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = (24 * density).toInt() + bottomNav
                floatingQuickBar?.layoutParams = lp
            }

            // Lift fabTools above bottom system bars
            (fabTools?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = (16 * density).toInt() + bottomNav
                fabTools?.layoutParams = lp
            }

            // Pad recycler so bottom items aren't obscured by floating toolbar / FAB + system bars
            recyclerFlatView?.setPadding(
                systemBars.left,
                (4 * density).toInt(),
                systemBars.right,
                bottomNav + (100 * density).toInt()
            )
            insets
        }
    }

    private fun initViews() {
        recyclerFlatView = findViewById(R.id.recyclerFlatView)
        progressLoading = findViewById(R.id.progressLoading)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtHeaderTitle = findViewById(R.id.txtHeaderTitle)
        txtHeaderSubtitle = findViewById(R.id.txtHeaderSubtitle)
        layoutHeaderNormal = findViewById(R.id.layoutHeaderNormal)
        layoutHeaderSelection = findViewById(R.id.layoutHeaderSelection)
        txtSelectionCount = findViewById(R.id.txtSelectionCount)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        layoutSearchBar = findViewById(R.id.layoutSearchBar)
        edtSearch = findViewById(R.id.edtSearch)
        btnSearchClear = findViewById(R.id.btnSearchClear)
        fabTools = findViewById(R.id.fabTools)
        floatingQuickBar = findViewById(R.id.floatingQuickBar)

        txtHeaderTitle?.text = storageLabel
    }

    private fun setupRecyclerView() {
        adapter = FlatViewAdapter(
            isTv = isTv,
            onCategoryToggle = { category ->
                viewModel.toggleCategoryExpansion(category.categoryPath)
            },
            onSubfolderToggle = { subfolder ->
                viewModel.toggleSubfolderExpansion(subfolder.folderPath)
            },
            onOpenFolder = { folderPath ->
                openFolderInBrowser(folderPath)
            },
            onFileClick = { fileEntry, view ->
                if (viewModel.state.value.isEditMode) {
                    viewModel.toggleFileSelection(fileEntry.fileIndex.path)
                } else {
                    openFile(File(fileEntry.fileIndex.path), view)
                }
            },
            onFileLongClick = { fileEntry ->
                if (!viewModel.state.value.isEditMode) {
                    viewModel.setEditMode(true)
                }
                viewModel.toggleFileSelection(fileEntry.fileIndex.path)
            },
            onCategoryCheckChanged = { category, isChecked ->
                viewModel.toggleCategorySelection(category.categoryPath, isChecked)
            },
            onSubfolderCheckChanged = { subfolder, isChecked ->
                viewModel.toggleSubfolderSelection(subfolder.folderPath, isChecked)
            },
            onFileCheckChanged = { fileEntry, _ ->
                viewModel.toggleFileSelection(fileEntry.fileIndex.path)
            }
        )

        recyclerFlatView?.layoutManager = LinearLayoutManager(this)
        recyclerFlatView?.adapter = adapter
        // Draw unified glass background container wrapping each root category and its children
        recyclerFlatView?.addItemDecoration(CategoryBackgroundDecoration(this, isTv))
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        findViewById<View>(R.id.btnSearchToggle)?.setOnClickListener {
            val isVisible = layoutSearchBar?.visibility == View.VISIBLE
            toggleSearch(!isVisible)
        }

        findViewById<View>(R.id.btnExpandAll)?.setOnClickListener {
            viewModel.expandAll()
        }

        findViewById<View>(R.id.btnCollapseAll)?.setOnClickListener {
            viewModel.collapseAll()
        }

        findViewById<View>(R.id.btnCloseSelection)?.setOnClickListener {
            viewModel.setEditMode(false)
        }

        btnSelectAll?.setOnClickListener {
            if (viewModel.state.value.areAllSelected) {
                viewModel.deselectAll()
            } else {
                viewModel.selectAll()
            }
        }

        btnSearchClear?.setOnClickListener {
            edtSearch?.setText("")
        }

        edtSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                btnSearchClear?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                viewModel.setFilterQuery(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        fabTools?.setOnClickListener {
            showStandardToolsBottomSheet()
        }

        floatingQuickBar?.setOnActionClickListener { actionId ->
            handleQuickAction(actionId)
        }
    }

    private fun toggleSearch(show: Boolean) {
        layoutSearchBar?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            edtSearch?.requestFocus()
        } else {
            edtSearch?.setText("")
            viewModel.setFilterQuery("")
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    progressLoading?.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    layoutEmpty?.visibility = if (!state.isLoading && state.items.isEmpty()) View.VISIBLE else View.GONE
                    recyclerFlatView?.visibility = if (!state.isLoading && state.items.isNotEmpty()) View.VISIBLE else View.GONE

                    adapter.isEditMode = state.isEditMode
                    adapter.submitList(state.items) {
                        recyclerFlatView?.invalidateItemDecorations()
                    }

                    if (!state.isLoading) {
                        txtHeaderSubtitle?.text = getString(
                            R.string.flat_view_files_count,
                            state.totalFiles,
                            state.totalFolders
                        )
                    }

                    if (state.isEditMode) {
                        layoutHeaderNormal?.visibility = View.GONE
                        layoutHeaderSelection?.visibility = View.VISIBLE
                        txtSelectionCount?.text = getString(
                            R.string.flat_view_selected_count,
                            state.selectedPaths.size
                        )

                        // Update Select All vs Deselect All text
                        btnSelectAll?.text = getString(
                            if (state.areAllSelected) R.string.action_deselect_all else R.string.action_select_all
                        )

                        // Mutually exclusive: Bottom Quick Action Bar OR Tools FAB
                        val pm = ToolbarIconsPreferenceManager
                        val isQuickBar = pm.isQuickBarEnabled(this@FlatViewActivity) && !isTv
                        val hasSelectedFiles = state.selectedPaths.isNotEmpty()

                        if (isQuickBar) {
                            fabTools?.visibility = View.GONE
                            if (hasSelectedFiles) {
                                val selectedIndices = viewModel.getSelectedFiles()
                                val selState = FloatingQuickActionBar.SelectionState(
                                    selectedCount = selectedIndices.size,
                                    hasDirectories = false,
                                    hasProtected = false,
                                    hasUnprotected = false,
                                    hasPinned = false,
                                    hasUnpinned = false,
                                    hasArchiveSelected = selectedIndices.any { ArchiveManager.isSupportedArchiveName(it.filename) },
                                    allImagesSelected = selectedIndices.isNotEmpty() && selectedIndices.all { it.extension.lowercase() in FileViewerRouter.IMAGE_EXTENSIONS },
                                    allAudioSelected = selectedIndices.isNotEmpty() && selectedIndices.all { FileViewerRouter.isAudio(it.extension) },
                                    allVideosSelected = selectedIndices.isNotEmpty() && selectedIndices.all { FileViewerRouter.isVideo(it.extension) }
                                )
                                floatingQuickBar?.bindSelection(selState)
                                floatingQuickBar?.visibility = View.VISIBLE
                            } else {
                                floatingQuickBar?.visibility = View.GONE
                            }
                        } else {
                            floatingQuickBar?.visibility = View.GONE
                            fabTools?.visibility = if (!isTv && hasSelectedFiles) View.VISIBLE else View.GONE
                        }
                    } else {
                        layoutHeaderNormal?.visibility = View.VISIBLE
                        layoutHeaderSelection?.visibility = View.GONE
                        fabTools?.visibility = View.GONE
                        floatingQuickBar?.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun openFolderInBrowser(folderPath: String) {
        val intent = Intent(this, FileBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, folderPath)
            putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, rootPath)
            putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, storageLabel)
        }
        startActivity(intent)
    }

    private fun openFile(file: File, transitionView: View? = null) {
        if (!file.exists()) {
            Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        if (FileViewerRouter.openFile(this, file, transitionView)) return

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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Reuses the full suite of tools from normal browsing (FileBrowserActivity:2823-3425).
     */
    private fun showStandardToolsBottomSheet() {
        val selectedFiles = viewModel.getSelectedFiles().map { File(it.path) }
        val count = selectedFiles.size
        if (count == 0) return

        val list = mutableListOf<FileToolsBottomSheet.ActionItem>()
        val pm = ToolbarIconsPreferenceManager

        // 0. Invert Selection
        if (pm.isIconEnabled(this, pm.KEY_INVERT_SELECTION)) {
            list.add(FileToolsBottomSheet.ActionItem("invert_selection", getString(R.string.action_invert_selection), R.drawable.ic_invert_selection, "toolbar_invert_selection") {
                viewModel.invertSelection()
            })
        }

        // 1. Copy
        if (pm.isIconEnabled(this, pm.KEY_COPY)) {
            list.add(FileToolsBottomSheet.ActionItem("copy", getString(R.string.action_copy), R.drawable.ic_copy, "toolbar_copy") {
                copyOrCut(selectedFiles, isMove = false)
            })
        }

        // 2. Move (Cut)
        if (pm.isIconEnabled(this, pm.KEY_MOVE)) {
            list.add(FileToolsBottomSheet.ActionItem("move", getString(R.string.action_move), R.drawable.ic_move, "toolbar_move") {
                copyOrCut(selectedFiles, isMove = true)
            })
        }

        // 3. Delete
        if (pm.isIconEnabled(this, pm.KEY_DELETE)) {
            list.add(FileToolsBottomSheet.ActionItem("delete", getString(R.string.action_delete), R.drawable.ic_delete, "toolbar_delete") {
                deleteFiles(selectedFiles)
            })
        }

        // 4. Rename
        if (pm.isIconEnabled(this, pm.KEY_RENAME)) {
            list.add(FileToolsBottomSheet.ActionItem("rename", getString(R.string.action_rename), R.drawable.ic_edit, "toolbar_rename") {
                if (selectedFiles.size == 1) {
                    showRenameDialog(selectedFiles.first())
                } else {
                    val items = selectedFiles.map { BatchRenameItem.fromLocalFile(it) }
                    if (isTv) {
                        val intent = Intent(this, BatchRenameTvActivity::class.java).apply {
                            putParcelableArrayListExtra("items", ArrayList(items))
                        }
                        batchRenameTvLauncher.launch(intent)
                    } else {
                        val dialog = BatchRenameDialogFragment.newInstance(items)
                        dialog.setOnCompleteListener { _, _, _ ->
                            viewModel.setEditMode(false)
                            reloadFlatView()
                        }
                        dialog.show(supportFragmentManager, BatchRenameDialogFragment.TAG)
                    }
                }
            })
        }

        // 5. Upload to Other Storage
        if (pm.isIconEnabled(this, pm.KEY_UPLOAD_OTHER_STORAGE)) {
            list.add(FileToolsBottomSheet.ActionItem("upload_other_storage", getString(R.string.action_upload_other_storage), R.drawable.ic_cloud, "toolbar_upload_other_storage") {
                val mounted = DocumentProviderRepository.getMountedProviders(this)
                showSelectMountedProviderForExportDialog(selectedFiles, mounted)
            })
        }

        // 6. Share
        if (pm.isIconEnabled(this, pm.KEY_SHARE)) {
            list.add(FileToolsBottomSheet.ActionItem("share", getString(R.string.action_share), R.drawable.ic_share, "toolbar_share") {
                shareFiles(selectedFiles)
            })
        }

        // 7. Open With (Single file)
        if (count == 1 && pm.isIconEnabled(this, pm.KEY_OPEN_WITH)) {
            list.add(FileToolsBottomSheet.ActionItem("open_with", getString(R.string.toolbar_open_with), R.drawable.ic_apps, "toolbar_open_with") {
                FileViewerRouter.showOpenWithDialog(this, selectedFiles.first(), isNetwork = false)
            })
        }

        // 8. Open As (Single file)
        if (count == 1 && pm.isIconEnabled(this, pm.KEY_OPEN_AS)) {
            list.add(FileToolsBottomSheet.ActionItem("open_as", getString(R.string.toolbar_open_as), R.drawable.ic_apps, "toolbar_open_as") {
                OpenAsBottomSheet.newInstance(selectedFiles.first().absolutePath, selectedFiles.first().name, false)
                    .show(supportFragmentManager, OpenAsBottomSheet.TAG)
            })
        }

        // 9. Favorite (Single file)
        if (count == 1 && pm.isIconEnabled(this, pm.KEY_FAVORITE)) {
            list.add(FileToolsBottomSheet.ActionItem("favorite", getString(R.string.action_favorite), R.drawable.ic_star, "toolbar_favorite") {
                showFavoriteDialog(selectedFiles.first())
            })
        }

        // 10. Compress
        if (pm.isIconEnabled(this, pm.KEY_COMPRESS)) {
            list.add(FileToolsBottomSheet.ActionItem("compress", getString(R.string.action_compress), R.drawable.ic_compress, "toolbar_compress") {
                showArchiveOptions(selectedFiles)
            })
        }

        // 11. Add to Existing Archive
        list.add(FileToolsBottomSheet.ActionItem("add_to_existing_archive", getString(R.string.action_add_to_existing_archive), R.drawable.ic_compress, "toolbar_add_to_archive") {
            showAddToExistingArchive(selectedFiles)
        })

        // 12. Extract Here
        val hasArchiveSelected = selectedFiles.any { ArchiveManager.isSupportedArchiveName(it.name) }
        if (hasArchiveSelected && pm.isIconEnabled(this, pm.KEY_EXTRACT)) {
            list.add(FileToolsBottomSheet.ActionItem("extract_here", getString(R.string.action_extract_here), R.drawable.ic_extract, "toolbar_extract") {
                performExtractHere(selectedFiles)
            })
        }

        // 13. Image Compress
        val allImages = selectedFiles.isNotEmpty() && selectedFiles.all {
            it.extension.lowercase() in FileViewerRouter.IMAGE_EXTENSIONS
        }
        if (allImages && pm.isIconEnabled(this, pm.KEY_IMAGE_COMPRESS)) {
            list.add(FileToolsBottomSheet.ActionItem("image_compress", getString(R.string.action_compress_image), R.drawable.ic_compress_image, "toolbar_image_compress") {
                startActivity(Intent(this, ImageCompressActivity::class.java).apply {
                    putStringArrayListExtra(
                        ImageCompressActivity.EXTRA_FILE_PATHS,
                        ArrayList(selectedFiles.map { it.absolutePath })
                    )
                })
            })
        }

        // 14. Create GIF
        val canCreateGif = selectedFiles.size >= 2 && allImages
        if (canCreateGif && pm.isIconEnabled(this, pm.KEY_CREATE_GIF)) {
            list.add(FileToolsBottomSheet.ActionItem("create_gif", getString(R.string.action_create_gif), R.drawable.ic_gif, "toolbar_create_gif") {
                startActivity(Intent(this, GifCreatorActivity::class.java).apply {
                    putStringArrayListExtra(
                        GifCreatorActivity.EXTRA_FILE_PATHS,
                        ArrayList(selectedFiles.map { it.absolutePath })
                    )
                })
            })
        }

        // 15. Extract Subtitles from Video
        if (count == 1 && FileViewerRouter.isVideo(selectedFiles.first().extension) &&
            FFmpegMediaHelper.isAvailable() &&
            pm.isIconEnabled(this, pm.KEY_EXTRACT_SUBTITLES)) {
            list.add(FileToolsBottomSheet.ActionItem("extract_subtitles", getString(R.string.toolbar_extract_subtitles), R.drawable.ic_subtitles, "toolbar_extract_subtitles") {
                extractSubtitlesFromVideo(selectedFiles.first())
            })
        }

        // 16. Extract Audio from Video
        if (count == 1 && FileViewerRouter.isVideo(selectedFiles.first().extension) &&
            FFmpegMediaHelper.isAvailable() &&
            pm.isIconEnabled(this, pm.KEY_EXTRACT_AUDIO)) {
            list.add(FileToolsBottomSheet.ActionItem("extract_audio", getString(R.string.toolbar_extract_audio), R.drawable.ic_audio_track, "toolbar_extract_audio") {
                extractAudioFromVideo(selectedFiles.first())
            })
        }

        // 17. Convert Video to MP4
        if (count == 1 && FileViewerRouter.isVideo(selectedFiles.first().extension) &&
            FFmpegMediaHelper.isAvailable() &&
            pm.isIconEnabled(this, pm.KEY_CONVERT_TO_MP4)) {
            list.add(FileToolsBottomSheet.ActionItem("convert_to_mp4", getString(R.string.toolbar_convert_to_mp4), R.drawable.ic_convert_video, "toolbar_convert_to_mp4") {
                convertVideoToMp4(selectedFiles.first())
            })
        }

        // 18. Photo EXIF Cleaner & Renamer (Mobile Only)
        if (allImages && !isTv && pm.isIconEnabled(this, pm.KEY_EXIF_TOOLS)) {
            list.add(FileToolsBottomSheet.ActionItem("exif_tools", getString(R.string.action_exif_cleaner_renamer), R.drawable.ic_exif_cleaner, "toolbar_exif_cleaner") {
                startActivity(Intent(this, ExifToolsActivity::class.java).apply {
                    putStringArrayListExtra(
                        ExifToolsActivity.EXTRA_FILE_PATHS,
                        ArrayList(selectedFiles.map { it.absolutePath })
                    )
                })
            })
        }

        // 19. Wallpaper (Mobile Only, single image)
        val isSingleImage = count == 1 && allImages
        if (isSingleImage && !isTv) {
            val imgFile = selectedFiles.first()
            if (pm.isIconEnabled(this, pm.KEY_SET_HOME_WALLPAPER)) {
                list.add(FileToolsBottomSheet.ActionItem("set_home_wallpaper", getString(R.string.action_set_home_wallpaper), R.drawable.ic_wallpaper_home, "toolbar_set_home_wallpaper") {
                    setWallpaper(imgFile, android.app.WallpaperManager.FLAG_SYSTEM)
                })
            }
            if (pm.isIconEnabled(this, pm.KEY_SET_LOCK_WALLPAPER)) {
                list.add(FileToolsBottomSheet.ActionItem("set_lock_wallpaper", getString(R.string.action_set_lock_wallpaper), R.drawable.ic_wallpaper_lock, "toolbar_set_lock_wallpaper") {
                    setWallpaper(imgFile, android.app.WallpaperManager.FLAG_LOCK)
                })
            }
        }

        // 20. Audio -> System Sounds (Mobile Only, single audio)
        val isSingleAudio = count == 1 && FileViewerRouter.isAudio(selectedFiles.first().extension)
        if (isSingleAudio && !isTv) {
            val audioFile = selectedFiles.first()
            if (pm.isIconEnabled(this, pm.KEY_SET_RINGTONE)) {
                list.add(FileToolsBottomSheet.ActionItem("set_ringtone", getString(R.string.action_set_ringtone), R.drawable.ic_ringtone, "toolbar_set_ringtone") {
                    setSystemSound(audioFile, android.media.RingtoneManager.TYPE_RINGTONE)
                })
            }
            if (pm.isIconEnabled(this, pm.KEY_SET_NOTIFICATION)) {
                list.add(FileToolsBottomSheet.ActionItem("set_notification", getString(R.string.action_set_notification), R.drawable.ic_notification_sound, "toolbar_set_notification") {
                    setSystemSound(audioFile, android.media.RingtoneManager.TYPE_NOTIFICATION)
                })
            }
            if (pm.isIconEnabled(this, pm.KEY_SET_ALARM)) {
                list.add(FileToolsBottomSheet.ActionItem("set_alarm", getString(R.string.action_set_alarm), R.drawable.ic_alarm_sound, "toolbar_set_alarm") {
                    setSystemSound(audioFile, android.media.RingtoneManager.TYPE_ALARM)
                })
            }
        }

        // 21. Music Tagger (All audio, mobile only)
        val allAudio = selectedFiles.isNotEmpty() && selectedFiles.all { FileViewerRouter.isAudio(it.extension) }
        if (allAudio && !isTv && pm.isIconEnabled(this, pm.KEY_MUSIC_TAGGER)) {
            list.add(FileToolsBottomSheet.ActionItem("music_tagger", getString(R.string.action_music_tagger), R.drawable.ic_music_tag, "toolbar_music_tagger") {
                startActivity(Intent(this, MusicTaggerActivity::class.java).apply {
                    putStringArrayListExtra(
                        MusicTaggerActivity.EXTRA_FILE_PATHS,
                        ArrayList(selectedFiles.map { it.absolutePath })
                    )
                })
            })
        }

        // 22. Copy Encrypted
        if (pm.isIconEnabled(this, pm.KEY_COPY_ENCRYPT)) {
            list.add(FileToolsBottomSheet.ActionItem("copy_encrypt", getString(R.string.action_copy_encrypt), R.drawable.ic_copy, "toolbar_copy_encrypt") {
                showVaultPickerForEncrypt(selectedFiles, isMove = false)
            })
        }

        // 23. Move Encrypted
        if (pm.isIconEnabled(this, pm.KEY_MOVE_ENCRYPT)) {
            list.add(FileToolsBottomSheet.ActionItem("move_encrypt", getString(R.string.action_move_encrypt), R.drawable.ic_move, "toolbar_move_encrypt") {
                showVaultPickerForEncrypt(selectedFiles, isMove = true)
            })
        }

        // 24. Hide / Unhide
        if (pm.isIconEnabled(this, pm.KEY_HIDE)) {
            list.add(FileToolsBottomSheet.ActionItem("hide", getString(R.string.hide), R.drawable.ic_eye_off, "toolbar_hide") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selectedFiles) {
                        HiddenFilesManager.hide(file.absolutePath)
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.setEditMode(false)
                        reloadFlatView()
                        Toast.makeText(this@FlatViewActivity, getString(R.string.toast_hidden_success, selectedFiles.size), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        if (pm.isIconEnabled(this, pm.KEY_UNHIDE)) {
            list.add(FileToolsBottomSheet.ActionItem("unhide", getString(R.string.unhide), R.drawable.ic_eye, "toolbar_unhide") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selectedFiles) {
                        HiddenFilesManager.unhide(file.absolutePath)
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.setEditMode(false)
                        reloadFlatView()
                        Toast.makeText(this@FlatViewActivity, getString(R.string.toast_unhidden_success, selectedFiles.size), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        // 25. Protect / Unprotect
        if (pm.isIconEnabled(this, pm.KEY_PROTECT)) {
            list.add(FileToolsBottomSheet.ActionItem("protect", getString(R.string.protect), R.drawable.ic_shield_protected, "toolbar_protect") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selectedFiles) {
                        ProtectedFilesManager.setProtected(this@FlatViewActivity, file.absolutePath, protected = true)
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.setEditMode(false)
                        reloadFlatView()
                        Toast.makeText(this@FlatViewActivity, getString(R.string.toast_protected_success, selectedFiles.size), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        if (pm.isIconEnabled(this, pm.KEY_UNPROTECT)) {
            list.add(FileToolsBottomSheet.ActionItem("unprotect", getString(R.string.unprotect), R.drawable.ic_shield_unprotected, "toolbar_unprotect") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selectedFiles) {
                        ProtectedFilesManager.setProtected(this@FlatViewActivity, file.absolutePath, protected = false)
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.setEditMode(false)
                        reloadFlatView()
                        Toast.makeText(this@FlatViewActivity, getString(R.string.toast_unprotected_success, selectedFiles.size), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        // 26. Pin / Unpin
        if (pm.isIconEnabled(this, pm.KEY_PIN)) {
            list.add(FileToolsBottomSheet.ActionItem("pin", getString(R.string.pin), R.drawable.ic_paperclip, "toolbar_pin") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selectedFiles) {
                        PinnedFilesManager.setPinned(this@FlatViewActivity, file.absolutePath, pinned = true)
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.setEditMode(false)
                        reloadFlatView()
                        Toast.makeText(this@FlatViewActivity, getString(R.string.toast_pinned_success, selectedFiles.size), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        if (pm.isIconEnabled(this, pm.KEY_UNPIN)) {
            list.add(FileToolsBottomSheet.ActionItem("unpin", getString(R.string.unpin), R.drawable.ic_paperclip_off, "toolbar_unpin") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selectedFiles) {
                        PinnedFilesManager.setPinned(this@FlatViewActivity, file.absolutePath, pinned = false)
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.setEditMode(false)
                        reloadFlatView()
                        Toast.makeText(this@FlatViewActivity, getString(R.string.toast_unpinned_success, selectedFiles.size), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        // 27. Properties
        list.add(FileToolsBottomSheet.ActionItem("properties", getString(R.string.action_properties), R.drawable.ic_about, "toolbar_properties") {
            val sheet = FilePropertiesBottomSheet.newInstanceForLocalFiles(selectedFiles)
            sheet.show(supportFragmentManager, FilePropertiesBottomSheet.TAG)
        })

        // 28. Tag (multi-file)
        val prefs = getSharedPreferences("ufm_prefs", MODE_PRIVATE)
        val isMultiTaggingEnabled = prefs.getBoolean("pref_multi_file_tagging", false)
        if (isMultiTaggingEnabled && selectedFiles.size > 1) {
            list.add(FileToolsBottomSheet.ActionItem("tag", getString(R.string.action_tag), R.drawable.ic_edit, "toolbar_tag") {
                FileTagsManager.showMultiFileTagDialog(this, selectedFiles.map { it.absolutePath }) {
                    viewModel.setEditMode(false)
                    reloadFlatView()
                }
            })
        }

        // 29. Retrigger Thumbnails
        if (pm.isIconEnabled(this, pm.KEY_RETRIGGER_THUMBNAILS)) {
            list.add(FileToolsBottomSheet.ActionItem("retrigger_thumbnails", getString(R.string.action_retrigger_thumbnails), R.drawable.ic_photo_video, "toolbar_retrigger_thumbnails") {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in selectedFiles) {
                        FileAdapter.clearCacheForPath(file.absolutePath)
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.setEditMode(false)
                        reloadFlatView()
                        Toast.makeText(this@FlatViewActivity, getString(R.string.retrigger_thumbnails_success), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        // 30. Checksum Tools
        if (pm.isIconEnabled(this, pm.KEY_CHECKSUM)) {
            list.add(FileToolsBottomSheet.ActionItem("checksum", getString(R.string.action_checksum), R.drawable.ic_checksum, "toolbar_checksum") {
                viewModel.setEditMode(false)
                val sources = selectedFiles.map { LocalFileSource(it) }
                ChecksumDialogFragment.newInstance(sources)
                    .show(supportFragmentManager, ChecksumDialogFragment.TAG)
            })
        }

        // 31. Compare Files (2 files)
        if (count == 2 && pm.isIconEnabled(this, pm.KEY_CHECKSUM)) {
            list.add(FileToolsBottomSheet.ActionItem("compare_files", getString(R.string.action_compare_files), R.drawable.ic_checksum, "toolbar_checksum") {
                viewModel.setEditMode(false)
                val sources = selectedFiles.map { LocalFileSource(it) }
                FileCompareDialogFragment.newInstance(sources[0], sources[1])
                    .show(supportFragmentManager, FileCompareDialogFragment.TAG)
            })
        }

        // 32. Verify Checksum Manifest
        if (count == 1) {
            val sel = selectedFiles.first()
            val ext = sel.extension.lowercase(Locale.ROOT)
            if (ext in listOf("sha256", "sha512", "sha1", "md5", "sfv", "crc")) {
                list.add(FileToolsBottomSheet.ActionItem("verify_checksum_manifest", getString(R.string.action_verify_checksum_file), R.drawable.ic_checksum, "toolbar_checksum") {
                    viewModel.setEditMode(false)
                    ChecksumManifestVerifyDialogFragment.newInstance(
                        LocalFileSource(sel),
                        sel.parentFile
                    ).show(supportFragmentManager, ChecksumManifestVerifyDialogFragment.TAG)
                })
            }
        }

        val displayList = pm.filterItemsForBottomSheet(this, list)
        if (displayList.isNotEmpty()) {
            val title = getString(R.string.action_tools)
            val subtitle = getString(R.string.selection_count, count)
            val sheet = FileToolsBottomSheet.newInstance(displayList, title, subtitle)
            sheet.show(supportFragmentManager, FileToolsBottomSheet.TAG)
        }
    }

    private fun handleQuickAction(actionId: String) {
        val selectedFiles = viewModel.getSelectedFiles().map { File(it.path) }
        if (selectedFiles.isEmpty()) return

        val pm = ToolbarIconsPreferenceManager
        when (actionId) {
            pm.ACTION_COPY -> copyOrCut(selectedFiles, isMove = false)
            pm.ACTION_MOVE -> copyOrCut(selectedFiles, isMove = true)
            pm.ACTION_DELETE -> deleteFiles(selectedFiles)
            pm.ACTION_SHARE -> shareFiles(selectedFiles)
            pm.ACTION_RENAME -> {
                if (selectedFiles.size == 1) {
                    showRenameDialog(selectedFiles.first())
                } else {
                    val items = selectedFiles.map { BatchRenameItem.fromLocalFile(it) }
                    val dialog = BatchRenameDialogFragment.newInstance(items)
                    dialog.setOnCompleteListener { _, _, _ ->
                        viewModel.setEditMode(false)
                        reloadFlatView()
                    }
                    dialog.show(supportFragmentManager, BatchRenameDialogFragment.TAG)
                }
            }
            pm.ACTION_COMPRESS -> showArchiveOptions(selectedFiles)
            pm.ACTION_EXTRACT -> performExtractHere(selectedFiles)
            pm.ACTION_FAVORITE -> if (selectedFiles.isNotEmpty()) showFavoriteDialog(selectedFiles.first())
            pm.ACTION_OPEN_WITH -> if (selectedFiles.isNotEmpty()) FileViewerRouter.showOpenWithDialog(this, selectedFiles.first(), isNetwork = false)
            pm.ACTION_OPEN_AS -> if (selectedFiles.isNotEmpty()) {
                OpenAsBottomSheet.newInstance(selectedFiles.first().absolutePath, selectedFiles.first().name, false)
                    .show(supportFragmentManager, OpenAsBottomSheet.TAG)
            }
            pm.ACTION_IMAGE_COMPRESS -> {
                startActivity(Intent(this, ImageCompressActivity::class.java).apply {
                    putStringArrayListExtra(
                        ImageCompressActivity.EXTRA_FILE_PATHS,
                        ArrayList(selectedFiles.map { it.absolutePath })
                    )
                })
            }
            pm.ACTION_CREATE_GIF -> {
                startActivity(Intent(this, GifCreatorActivity::class.java).apply {
                    putStringArrayListExtra(
                        GifCreatorActivity.EXTRA_FILE_PATHS,
                        ArrayList(selectedFiles.map { it.absolutePath })
                    )
                })
            }
            pm.ACTION_EXIF_TOOLS -> {
                startActivity(Intent(this, ExifToolsActivity::class.java).apply {
                    putStringArrayListExtra(
                        ExifToolsActivity.EXTRA_FILE_PATHS,
                        ArrayList(selectedFiles.map { it.absolutePath })
                    )
                })
            }
            pm.ACTION_SET_HOME_WALLPAPER -> {
                if (selectedFiles.isNotEmpty()) setWallpaper(selectedFiles.first(), android.app.WallpaperManager.FLAG_SYSTEM)
            }
            pm.ACTION_SET_LOCK_WALLPAPER -> {
                if (selectedFiles.isNotEmpty()) setWallpaper(selectedFiles.first(), android.app.WallpaperManager.FLAG_LOCK)
            }
            pm.ACTION_SET_RINGTONE -> {
                if (selectedFiles.isNotEmpty()) setSystemSound(selectedFiles.first(), android.media.RingtoneManager.TYPE_RINGTONE)
            }
            pm.ACTION_SET_NOTIFICATION -> {
                if (selectedFiles.isNotEmpty()) setSystemSound(selectedFiles.first(), android.media.RingtoneManager.TYPE_NOTIFICATION)
            }
            pm.ACTION_SET_ALARM -> {
                if (selectedFiles.isNotEmpty()) setSystemSound(selectedFiles.first(), android.media.RingtoneManager.TYPE_ALARM)
            }
            pm.ACTION_MUSIC_TAGGER -> {
                startActivity(Intent(this, MusicTaggerActivity::class.java).apply {
                    putStringArrayListExtra(
                        MusicTaggerActivity.EXTRA_FILE_PATHS,
                        ArrayList(selectedFiles.map { it.absolutePath })
                    )
                })
            }
            pm.ACTION_COPY_ENCRYPT -> showVaultPickerForEncrypt(selectedFiles, isMove = false)
            pm.ACTION_MOVE_ENCRYPT -> showVaultPickerForEncrypt(selectedFiles, isMove = true)
            pm.ACTION_SELECT_ALL -> viewModel.selectAll()
            pm.ACTION_INVERT_SELECTION -> viewModel.invertSelection()
            pm.ACTION_CHECKSUM -> {
                viewModel.setEditMode(false)
                val sources = selectedFiles.map { LocalFileSource(it) }
                ChecksumDialogFragment.newInstance(sources)
                    .show(supportFragmentManager, ChecksumDialogFragment.TAG)
            }
            pm.ACTION_UPLOAD_OTHER_STORAGE -> {
                val mounted = DocumentProviderRepository.getMountedProviders(this)
                showSelectMountedProviderForExportDialog(selectedFiles, mounted)
            }
            pm.ACTION_MORE -> showStandardToolsBottomSheet()
            else -> {
                if (actionId == "properties") {
                    val sheet = FilePropertiesBottomSheet.newInstanceForLocalFiles(selectedFiles)
                    sheet.show(supportFragmentManager, FilePropertiesBottomSheet.TAG)
                }
            }
        }
    }

    private fun copyOrCut(files: List<File>, isMove: Boolean) {
        val op = if (isMove) FileClipboard.Operation.MOVE else FileClipboard.Operation.COPY
        FileClipboard.pushLocalSlot(files, op, rootPath)
        viewModel.setEditMode(false)
        val msg = getString(if (isMove) R.string.clipboard_cut else R.string.clipboard_copied, files.size)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun deleteFiles(files: List<File>) {
        val layoutRes = if (isTv) R.layout.dialog_flat_view_delete_confirm_tv else R.layout.dialog_flat_view_delete_confirm
        val dialogView = layoutInflater.inflate(layoutRes, null)

        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        txtMessage?.text = getString(R.string.flat_view_delete_message, files.size)

        val btnDeleteConfirm = dialogView.findViewById<View>(R.id.btnDeleteConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnDeleteConfirm?.setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch {
                var deletedCount = 0
                withContext(Dispatchers.IO) {
                    files.forEach { file ->
                        if (file.delete()) deletedCount++
                    }
                }
                viewModel.setEditMode(false)
                reloadFlatView()
                Toast.makeText(this@FlatViewActivity, getString(R.string.flat_view_deleted_count, deletedCount), Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showRenameDialog(file: File) {
        val layoutRes = if (isTv) R.layout.dialog_file_rename_tv else R.layout.dialog_file_rename
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

        btnSaveRename?.setOnClickListener {
            val newName = editFileName?.text?.toString()?.trim() ?: ""
            if (newName.isNotEmpty() && newName != file.name) {
                val newFile = File(file.parentFile, newName)
                if (file.renameTo(newFile)) {
                    viewModel.setEditMode(false)
                    reloadFlatView()
                    Toast.makeText(this, R.string.rename_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.rename_error, Toast.LENGTH_SHORT).show()
                }
            }
            dialog.dismiss()
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showFavoriteDialog(file: File) {
        val layoutRes = if (isTv) R.layout.dialog_add_favorite_tv else R.layout.dialog_add_favorite
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val txtOriginalName = dialogView.findViewById<TextView>(R.id.txtOriginalName)
        val edtFavoriteName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtFavoriteName)
        val btnSaveFavorite = dialogView.findViewById<View>(R.id.btnSaveFavorite)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)
        val rowAddToHomeScreen = dialogView.findViewById<View>(R.id.rowAddToHomeScreen)
        val switchAddToHomeScreen = dialogView.findViewById<android.widget.CompoundButton>(R.id.switchAddToHomeScreen)

        if (!isTv && !FavoriteHomeScreenManager.isMobilePinSupported(this)) {
            rowAddToHomeScreen?.visibility = View.GONE
        }
        rowAddToHomeScreen?.setOnClickListener {
            switchAddToHomeScreen?.toggle()
        }

        txtOriginalName?.text = file.name
        edtFavoriteName?.setText(file.name)

        btnSaveFavorite?.setOnClickListener {
            val name = edtFavoriteName?.text?.toString()?.trim()?.ifEmpty { file.name } ?: file.name
            val addToHome = switchAddToHomeScreen?.isChecked == true
            val favorite = FavoritesManager.FavoriteItem(
                id = UUID.randomUUID().toString(),
                path = file.absolutePath,
                label = name,
                isFolder = file.isDirectory,
                isNetwork = false,
                isPinnedMobile = (!isTv && addToHome),
                isPinnedTv = (isTv && addToHome)
            )
            FavoritesManager.addFavorite(this, favorite)
            if (addToHome) {
                FavoriteHomeScreenManager.pinOrSchedule(this, favorite, this)
            }
            viewModel.setEditMode(false)
            Toast.makeText(this, R.string.favorite_added, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showArchiveOptions(files: List<File>) {
        val dialog = ArchiveOptionsDialog()
        dialog.setOnConfirm { filename, format, password, useCurrentFolder ->
            val targetDir = if (useCurrentFolder) {
                files.firstOrNull()?.parentFile ?: File(rootPath)
            } else {
                File(rootPath)
            }
            performCompression(files, targetDir, filename, format, password)
        }
        dialog.show(supportFragmentManager, "archive_options")
    }

    private fun performCompression(sourceFiles: List<File>, destDir: File, customFileName: String, format: ArchiveManager.Format, password: String?) {
        val fileName = customFileName
        val extension = format.displayName
        var destFile = File(destDir, "$fileName$extension")
        var counter = 1
        while (destFile.exists()) {
            destFile = File(destDir, "$fileName ($counter)$extension")
            counter++
        }

        val progressDialog = ArchiveProgressDialog(this).apply {
            show(
                operation = ArchiveOperationType.COMPRESS,
                archiveName = destFile.name,
                totalFiles = sourceFiles.size
            )
        }

        var compressJob: kotlinx.coroutines.Job? = null
        progressDialog.setOnCancelListener {
            compressJob?.cancel()
        }

        compressJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                ArchiveManager.compress(
                    context = this@FlatViewActivity,
                    sourceFiles = sourceFiles,
                    destFile = destFile,
                    password = password,
                    format = format,
                    onArchiveProgress = { progress ->
                        runOnUiThread { progressDialog.update(progress) }
                    },
                    onProgress = {}
                )
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    viewModel.setEditMode(false)
                    reloadFlatView()
                    Toast.makeText(this@FlatViewActivity, getString(R.string.compression_completed_destfilename, destFile.name), Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@FlatViewActivity, R.string.archive_cancelled, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@FlatViewActivity, e.message ?: getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAddToExistingArchive(files: List<File>) {
        if (files.isEmpty()) return
        val currentDir = File(rootPath)
        val currentArchives = currentDir.listFiles()?.filter { it.isFile && ArchiveManager.isWritableArchive(it) } ?: emptyList()
        if (currentArchives.isEmpty()) {
            Toast.makeText(this, R.string.no_archives_found_in_folder, Toast.LENGTH_SHORT).show()
        } else {
            val items = currentArchives.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(R.string.select_target_archive)
                .setItems(items) { _, which ->
                    val targetArchive = currentArchives[which]
                    confirmAddFilesToArchive(targetArchive, files)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .apply {
                    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    show()
                }
        }
    }

    private fun confirmAddFilesToArchive(archiveFile: File, files: List<File>) {
        val progressDialog = ArchiveProgressDialog(this).apply {
            show(
                operation = ArchiveOperationType.ADD,
                archiveName = archiveFile.name,
                totalFiles = files.size
            )
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ArchiveManager.addFilesToArchive(
                    context = this@FlatViewActivity,
                    archiveFile = archiveFile,
                    sourceFiles = files,
                    targetDirInArchive = "",
                    isMove = false,
                    onArchiveProgress = { progress ->
                        runOnUiThread { progressDialog.update(progress) }
                    }
                )
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    viewModel.setEditMode(false)
                    reloadFlatView()
                    Toast.makeText(this@FlatViewActivity, getString(R.string.add_to_archive_success, files.size, archiveFile.name), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@FlatViewActivity, e.message ?: getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performExtractHere(files: List<File>) {
        val archives = files.filter { ArchiveManager.isSupportedArchiveName(it.name) }
        if (archives.isEmpty()) return

        val dialog = ExtractOptionsDialog.newInstance(archives.map { it.name })
        dialog.setOnExtractHere {
            performExtract(archives)
        }
        dialog.setOnExtractToNewFolder {
            promptExtractToNewFolder(archives)
        }
        dialog.show(supportFragmentManager, ExtractOptionsDialog.TAG)
    }

    private fun performExtract(archives: List<File>) {
        val firstArchive = archives.firstOrNull() ?: return
        val destDir = firstArchive.parentFile ?: File(rootPath)

        val progressDialog = ArchiveProgressDialog(this).apply {
            show(
                operation = ArchiveOperationType.EXTRACT,
                archiveName = firstArchive.name,
                totalFiles = archives.size
            )
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                for (archive in archives) {
                    ArchiveManager.extract(
                        context = this@FlatViewActivity,
                        archiveFile = archive,
                        destDir = destDir,
                        onArchiveProgress = { progress ->
                            runOnUiThread { progressDialog.update(progress) }
                        }
                    )
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    viewModel.setEditMode(false)
                    reloadFlatView()
                    Toast.makeText(this@FlatViewActivity, getString(R.string.extract_success, archives.size), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@FlatViewActivity, e.message ?: getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun promptExtractToNewFolder(archives: List<File>) {
        val firstArchive = archives.firstOrNull() ?: return
        val defaultFolderName = firstArchive.nameWithoutExtension
        val baseDir = firstArchive.parentFile ?: File(rootPath)
        val targetFolder = File(baseDir, defaultFolderName)
        targetFolder.mkdirs()

        val progressDialog = ArchiveProgressDialog(this).apply {
            show(
                operation = ArchiveOperationType.EXTRACT,
                archiveName = firstArchive.name,
                totalFiles = archives.size
            )
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                for (archive in archives) {
                    ArchiveManager.extract(
                        context = this@FlatViewActivity,
                        archiveFile = archive,
                        destDir = targetFolder,
                        onArchiveProgress = { progress ->
                            runOnUiThread { progressDialog.update(progress) }
                        }
                    )
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    viewModel.setEditMode(false)
                    reloadFlatView()
                    Toast.makeText(this@FlatViewActivity, getString(R.string.extract_success, archives.size), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@FlatViewActivity, e.message ?: getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun extractSubtitlesFromVideo(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = FFmpegMediaHelper.getMediaInfo(file)
            val subStreams = info?.subtitleStreams ?: emptyList()
            withContext(Dispatchers.Main) {
                if (subStreams.isEmpty()) {
                    Toast.makeText(this@FlatViewActivity, R.string.no_subtitles_found_in_video, Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val sheet = SubtitleTrackBottomSheet.newInstance(file.name, subStreams)
                sheet.onTrackSelected = { streamIndex, langTag, extractAll ->
                    val op = if (extractAll) {
                        MediaOperationWorker.OP_EXTRACT_ALL_SUBTITLES
                    } else {
                        MediaOperationWorker.OP_EXTRACT_SUBTITLES
                    }
                    launchLocalMediaWorker(
                        opType = op,
                        file = file,
                        streamIndex = streamIndex,
                        langTag = langTag,
                        title = getString(R.string.extracting_subtitles),
                        iconRes = R.drawable.ic_subtitles
                    )
                }
                sheet.show(supportFragmentManager, SubtitleTrackBottomSheet.TAG)
            }
        }
    }

    private fun extractAudioFromVideo(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = FFmpegMediaHelper.getMediaInfo(file)
            val audioStreams = info?.audioStreams ?: emptyList()
            withContext(Dispatchers.Main) {
                if (audioStreams.isEmpty()) {
                    Toast.makeText(this@FlatViewActivity, R.string.no_audio_found_in_video, Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val sheet = AudioTrackBottomSheet.newInstance(file.name, audioStreams)
                sheet.onTrackSelected = { streamIndex, langTag, extractAll, universalM4a ->
                    val op = if (extractAll) {
                        MediaOperationWorker.OP_EXTRACT_ALL_AUDIO
                    } else {
                        MediaOperationWorker.OP_EXTRACT_AUDIO
                    }
                    launchLocalMediaWorker(
                        opType = op,
                        file = file,
                        streamIndex = streamIndex,
                        langTag = langTag,
                        universalAac = universalM4a,
                        title = getString(R.string.extracting_audio),
                        iconRes = R.drawable.ic_audio_track
                    )
                }
                sheet.show(supportFragmentManager, AudioTrackBottomSheet.TAG)
            }
        }
    }

    private fun convertVideoToMp4(file: File) {
        UfmDialogHelper.showConfirmation(
            context = this,
            title = getString(R.string.convert_to_mp4_title),
            message = getString(R.string.convert_to_mp4_confirm, file.name),
            iconRes = R.drawable.ic_convert_video,
            positiveText = getString(R.string.action_convert_to_mp4),
            negativeText = getString(android.R.string.cancel),
            onPositive = {
                launchLocalMediaWorker(
                    opType = MediaOperationWorker.OP_CONVERT_TO_MP4,
                    file = file,
                    title = getString(R.string.convert_to_mp4_progress),
                    iconRes = R.drawable.ic_convert_video
                )
            }
        )
    }

    private fun launchLocalMediaWorker(
        opType: String,
        file: File,
        streamIndex: Int = -1,
        langTag: String? = null,
        universalAac: Boolean = false,
        title: String,
        iconRes: Int
    ) {
        val progress = MediaOperationProgressDialog(
            this,
            title,
            file.name,
            iconRes
        )
        progress.show()

        val workId = MediaOperationWorker.enqueueLocal(
            context = this,
            opType = opType,
            localFile = file,
            streamIndex = streamIndex,
            langTag = langTag,
            universalAac = universalAac
        )

        androidx.work.WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(workId)
            .observe(this) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            progress.dismiss()
                            viewModel.setEditMode(false)
                            reloadFlatView()
                            val count = workInfo.outputData.getInt(MediaOperationWorker.KEY_OUTPUT_COUNT, 1)
                            val name = workInfo.outputData.getString(MediaOperationWorker.KEY_OUTPUT_NAME)
                            val msg = when (opType) {
                                MediaOperationWorker.OP_CONVERT_TO_MP4 -> getString(R.string.convert_to_mp4_success, name ?: file.name)
                                MediaOperationWorker.OP_EXTRACT_ALL_SUBTITLES -> getString(R.string.subtitles_extracted_multiple_success, count)
                                MediaOperationWorker.OP_EXTRACT_SUBTITLES -> getString(R.string.subtitles_extracted_success) + (if (!name.isNullOrBlank()) "\n$name" else "")
                                MediaOperationWorker.OP_EXTRACT_ALL_AUDIO -> getString(R.string.audio_extracted_multiple_success, count)
                                else -> getString(R.string.audio_extracted_success) + (if (!name.isNullOrBlank()) "\n$name" else "")
                            }
                            Toast.makeText(this@FlatViewActivity, msg, Toast.LENGTH_LONG).show()
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            progress.dismiss()
                            val err = workInfo.outputData.getString(MediaOperationWorker.KEY_ERROR_MESSAGE)
                            Toast.makeText(this@FlatViewActivity, err ?: getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                        }
                        androidx.work.WorkInfo.State.CANCELLED -> {
                            progress.dismiss()
                            Toast.makeText(this@FlatViewActivity, R.string.archive_cancelled, Toast.LENGTH_SHORT).show()
                        }
                        else -> {}
                    }
                }
            }
    }

    private fun showSelectMountedProviderForExportDialog(files: List<File>, mountedProviders: List<MountedDocumentProvider>) {
        DocumentProviderExportHelper.showSelectMountedProviderDialog(this, files, mountedProviders) { provider ->
            DocumentProviderExportHelper.exportFilesViaProviderApp(this, files, provider)
            viewModel.setEditMode(false)
        }
    }

    private fun showVaultPickerForEncrypt(files: List<File>, isMove: Boolean) {
        val vaultDir = File(filesDir, "vault")
        val entries = mutableListOf<VaultEntry>()
        if (vaultDir.exists() && vaultDir.isDirectory) {
            vaultDir.listFiles()?.forEach { entryDir ->
                if (entryDir.isDirectory) {
                    readVaultEntry(entryDir)?.let { entries.add(it) }
                }
            }
        }

        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.encrypt_no_vaults, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, VaultActivity::class.java))
            return
        }

        val vaultNames = entries.map { it.displayName }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(if (isMove) R.string.encrypt_move_title else R.string.encrypt_copy_title)
            .setItems(vaultNames) { _, which ->
                encryptFilesToVault(files, entries[which], isMove)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                show()
            }
    }

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
                    val raw = filesJson.getString(i)
                    list.add(if (raw.startsWith("enc:")) VaultCrypto.decryptString(raw.removePrefix("enc:")) else raw)
                }
                list
            } else {
                emptyList()
            }

            VaultEntry(dir.name, displayName, originalRoot, files)
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptFilesToVault(files: List<File>, entry: VaultEntry, isMove: Boolean) {
        val entryDir = File(filesDir, "vault/${entry.id}")
        val progressView = layoutInflater.inflate(R.layout.dialog_vault_progress, null)
        val txtProgress = progressView.findViewById<TextView>(R.id.txtVaultProgress)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.progressVault)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(if (isMove) R.string.encrypt_move_title else R.string.encrypt_copy_title)
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val total = files.size.coerceAtLeast(1)
            var count = 0
            var success = true
            try {
                files.forEach { file ->
                    val encryptedFile = File(entryDir, "${file.name}.enc")
                    encryptedFile.parentFile?.mkdirs()
                    VaultCrypto.encryptFile(file, encryptedFile)
                    count++
                    val percent = ((count.toFloat() / total.toFloat()) * 100).toInt()
                    runOnUiThread {
                        txtProgress?.text = getString(R.string.encrypt_progress, count, total)
                        progressBar?.progress = percent
                    }
                }

                val existingFiles = entry.files.toMutableList()
                files.forEach { file ->
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

                if (isMove) {
                    files.forEach { file ->
                        file.delete()
                    }
                }
            } catch (_: Exception) {
                success = false
            }

            withContext(Dispatchers.Main) {
                dialog.dismiss()
                viewModel.setEditMode(false)
                reloadFlatView()
                if (success) {
                    Toast.makeText(this@FlatViewActivity, getString(R.string.encrypt_success, files.size), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FlatViewActivity, R.string.encrypt_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setWallpaper(imageFile: File, flag: Int) {
        WallpaperHelper.showConfirmDialog(
            this,
            imageFile.name,
            flag
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                val success = WallpaperHelper.setWallpaper(
                    this@FlatViewActivity,
                    imageFile,
                    flag
                )
                withContext(Dispatchers.Main) {
                    viewModel.setEditMode(false)
                    val msg = if (success) {
                        getString(if (flag == android.app.WallpaperManager.FLAG_SYSTEM) R.string.toast_wallpaper_set_home_success else R.string.toast_wallpaper_set_lock_success)
                    } else {
                        getString(R.string.toast_wallpaper_set_failed)
                    }
                    Toast.makeText(this@FlatViewActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setSystemSound(audioFile: File, soundType: Int) {
        RingtoneHelper.showConfirmDialog(
            this,
            audioFile.name,
            soundType
        ) {
            if (!RingtoneHelper.canWriteSettings(this)) {
                Toast.makeText(this, R.string.toast_sound_permission_required, Toast.LENGTH_SHORT).show()
                RingtoneHelper.requestWriteSettings(this)
                return@showConfirmDialog
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val success = RingtoneHelper.setAsSystemSound(
                    this@FlatViewActivity,
                    audioFile,
                    soundType
                )
                withContext(Dispatchers.Main) {
                    viewModel.setEditMode(false)
                    val msg = if (success) {
                        getString(when (soundType) {
                            android.media.RingtoneManager.TYPE_RINGTONE -> R.string.toast_ringtone_set_success
                            android.media.RingtoneManager.TYPE_NOTIFICATION -> R.string.toast_notification_set_success
                            else -> R.string.toast_alarm_set_success
                        })
                    } else {
                        getString(R.string.toast_sound_set_failed)
                    }
                    Toast.makeText(this@FlatViewActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareFiles(files: List<File>) {
        try {
            val uris = files.map { file ->
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
        }
    }
}
