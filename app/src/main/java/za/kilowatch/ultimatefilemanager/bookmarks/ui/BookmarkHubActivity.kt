package za.kilowatch.ultimatefilemanager.bookmarks.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkContentType
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkEntity
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkGroupEntity
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkRepository
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarkHubActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageView
    private lateinit var txtToolbarSubtitle: TextView
    private lateinit var btnSearch: ImageView
    private lateinit var btnSort: ImageView
    private lateinit var btnViewMode: ImageView
    private lateinit var btnExport: ImageView

    private lateinit var layoutSearchBar: FrameLayout
    private lateinit var editSearchQuery: EditText
    private lateinit var chipGroupGroups: ChipGroup
    private lateinit var chipGroupFilters: ChipGroup
    private lateinit var recyclerBookmarks: RecyclerView
    private lateinit var layoutEmpty: LinearLayout

    private lateinit var layoutSelectionBar: LinearLayout
    private lateinit var txtSelectedCount: TextView
    private lateinit var btnBatchMove: View
    private lateinit var btnBatchExport: View
    private lateinit var btnBatchDelete: View
    private lateinit var btnCloseSelection: ImageView

    private lateinit var adapter: BookmarkAdapter
    private var allBookmarks: List<BookmarkEntity> = emptyList()
    private var allGroups: List<BookmarkGroupEntity> = emptyList()

    private var selectedGroupId: String? = null
    private var selectedContentType: String? = null
    private var isFilterStarred: Boolean = false
    private var searchQuery: String = ""
    private var currentSortMode = SORT_NEWEST
    private var isGrid = false

    private var recentlyDeletedItem: BookmarkEntity? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_bookmark_hub)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupViews()
        setupRecyclerView()
        observeData()
    }

    private fun setupViews() {
        btnBack = findViewById(R.id.btnBack)
        txtToolbarSubtitle = findViewById(R.id.txtToolbarSubtitle)
        btnSearch = findViewById(R.id.btnSearch)
        btnSort = findViewById(R.id.btnSort)
        btnViewMode = findViewById(R.id.btnViewMode)
        btnExport = findViewById(R.id.btnExport)

        layoutSearchBar = findViewById(R.id.layoutSearchBar)
        editSearchQuery = findViewById(R.id.editSearchQuery)
        chipGroupGroups = findViewById(R.id.chipGroupGroups)
        chipGroupFilters = findViewById(R.id.chipGroupFilters)
        recyclerBookmarks = findViewById(R.id.recyclerBookmarks)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        layoutSelectionBar = findViewById(R.id.layoutSelectionBar)
        txtSelectedCount = findViewById(R.id.txtSelectedCount)
        btnBatchMove = findViewById(R.id.btnBatchMove)
        btnBatchExport = findViewById(R.id.btnBatchExport)
        btnBatchDelete = findViewById(R.id.btnBatchDelete)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)

        btnBack.setOnClickListener { finish() }

        btnSearch.setOnClickListener {
            val isVisible = layoutSearchBar.visibility == View.VISIBLE
            layoutSearchBar.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (isVisible) {
                editSearchQuery.setText("")
                searchQuery = ""
                applyFilterAndSort()
            }
        }

        editSearchQuery.doAfterTextChanged { text ->
            searchQuery = text?.toString()?.trim().orEmpty()
            applyFilterAndSort()
        }

        btnSort.setOnClickListener { showSortDialog() }

        btnViewMode.setOnClickListener {
            isGrid = !isGrid
            btnViewMode.setImageResource(if (isGrid) R.drawable.ic_view_list else R.drawable.ic_view_grid_medium)
            adapter.isGrid = isGrid
            recyclerBookmarks.layoutManager = if (isGrid) GridLayoutManager(this, 2) else LinearLayoutManager(this)
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }

        btnExport.setOnClickListener { showExportDialog(allBookmarks) }

        btnCloseSelection.setOnClickListener { exitSelectionMode() }
        btnBatchDelete.setOnClickListener { performBatchDelete() }
        btnBatchMove.setOnClickListener { performBatchMove() }
        btnBatchExport.setOnClickListener {
            val selectedItems = allBookmarks.filter { adapter.selectedIds.contains(it.id) }
            showExportDialog(selectedItems)
        }

        setupFilterChips()
    }

    private fun setupRecyclerView() {
        adapter = BookmarkAdapter(
            context = this,
            isGrid = false,
            onItemClick = { item -> handleItemClick(item) },
            onItemLongClick = { _ -> updateSelectionBar() },
            onStarToggle = { item -> toggleStar(item) },
            onOverflowClick = { item, anchor -> showCardMenu(item, anchor) }
        )
        recyclerBookmarks.layoutManager = LinearLayoutManager(this)
        recyclerBookmarks.adapter = adapter
    }

    private fun setupFilterChips() {
        chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipFilterLinks -> {
                    selectedContentType = BookmarkContentType.LINK
                    isFilterStarred = false
                }
                R.id.chipFilterMedia -> {
                    selectedContentType = BookmarkContentType.IMAGE // Handled in filter as Image/Video/Audio
                    isFilterStarred = false
                }
                R.id.chipFilterDocs -> {
                    selectedContentType = BookmarkContentType.DOCUMENT
                    isFilterStarred = false
                }
                R.id.chipFilterNotes -> {
                    selectedContentType = BookmarkContentType.TEXT_NOTE
                    isFilterStarred = false
                }
                R.id.chipFilterStarred -> {
                    selectedContentType = null
                    isFilterStarred = true
                }
                else -> {
                    selectedContentType = null
                    isFilterStarred = false
                }
            }
            applyFilterAndSort()
        }
    }

    private fun observeData() {
        val repo = BookmarkRepository.getInstance(this)

        lifecycleScope.launch {
            repo.getAllGroups().collectLatest { groups ->
                allGroups = groups
                populateGroupChips(groups)
                updateToolbarSubtitle()
            }
        }

        lifecycleScope.launch {
            repo.getAllBookmarks().collectLatest { bookmarks ->
                allBookmarks = bookmarks
                applyFilterAndSort()
                updateToolbarSubtitle()
            }
        }
    }

    private fun populateGroupChips(groups: List<BookmarkGroupEntity>) {
        chipGroupGroups.removeAllViews()

        // "All" Master Chip
        val allChip = Chip(this).apply {
            text = getString(R.string.bookmark_filter_all)
            isCheckable = true
            isChecked = selectedGroupId == null
            setOnClickListener {
                selectedGroupId = null
                applyFilterAndSort()
            }
        }
        chipGroupGroups.addView(allChip)

        for (group in groups) {
            val chip = Chip(this).apply {
                text = group.name
                isCheckable = true
                isChecked = selectedGroupId == group.id
                setOnClickListener {
                    if (group.isLocked) {
                        promptVaultUnlock {
                            selectedGroupId = group.id
                            applyFilterAndSort()
                        }
                    } else {
                        selectedGroupId = group.id
                        applyFilterAndSort()
                    }
                }
            }
            chipGroupGroups.addView(chip)
        }
    }

    private fun promptVaultUnlock(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(this@BookmarkHubActivity, errString, Toast.LENGTH_SHORT).show()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.bookmark_vault_prompt))
            .setNegativeButtonText(getString(R.string.cancel))
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun applyFilterAndSort() {
        var filtered = allBookmarks

        // Filter by Group
        if (selectedGroupId != null) {
            filtered = filtered.filter { it.groupId == selectedGroupId }
        }

        // Filter by Content Type
        if (selectedContentType != null) {
            filtered = if (selectedContentType == BookmarkContentType.IMAGE) {
                filtered.filter { it.contentType in setOf(BookmarkContentType.IMAGE, BookmarkContentType.VIDEO, BookmarkContentType.AUDIO) }
            } else {
                filtered.filter { it.contentType == selectedContentType }
            }
        }

        // Filter by Starred
        if (isFilterStarred) {
            filtered = filtered.filter { it.isStarred }
        }

        // Search Query
        if (searchQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                (it.notes?.contains(searchQuery, ignoreCase = true) == true) ||
                (it.url?.contains(searchQuery, ignoreCase = true) == true) ||
                it.sourceAppName.contains(searchQuery, ignoreCase = true)
            }
        }

        // Sort
        filtered = when (currentSortMode) {
            SORT_NEWEST -> filtered.sortedByDescending { it.updatedAt }
            SORT_OLDEST -> filtered.sortedBy { it.createdAt }
            SORT_TITLE_AZ -> filtered.sortedBy { it.title.lowercase() }
            SORT_SIZE -> filtered.sortedByDescending { it.fileSizeBytes }
            else -> filtered.sortedByDescending { it.updatedAt }
        }

        adapter.submitList(filtered)
        layoutEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateToolbarSubtitle() {
        val count = allBookmarks.size
        val groupCount = allGroups.size
        txtToolbarSubtitle.text = if (count > 0) {
            getString(R.string.bookmark_hub_subtitle_count, count, groupCount)
        } else {
            getString(R.string.bookmark_hub_subtitle_empty)
        }
    }

    private fun handleItemClick(item: BookmarkEntity) {
        // 1. Article Link -> Open Reader Mode if readable text cached, else open browser
        if (item.contentType == BookmarkContentType.LINK) {
            if (!item.cachedArticleText.isNullOrBlank()) {
                val intent = Intent(this, BookmarkReaderActivity::class.java).apply {
                    putExtra(BookmarkReaderActivity.EXTRA_BOOKMARK_ID, item.id)
                }
                startActivity(intent)
            } else if (!item.url.isNullOrBlank()) {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                }
            }
            return
        }

        // 2. Physical cloned file
        val path = item.filePath
        if (!path.isNullOrBlank() && File(path).exists()) {
            FileViewerRouter.openFile(this, File(path))
            return
        }

        // 3. Plain Text / Note
        if (item.contentType == BookmarkContentType.TEXT_NOTE) {
            MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(item.title)
                .setMessage(item.notes ?: "")
                .setPositiveButton(R.string.bookmark_action_copy_text) { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("note", item.notes ?: ""))
                    Toast.makeText(this, R.string.bookmark_action_copy_text, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    private fun showCardMenu(item: BookmarkEntity, anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu

        if (item.contentType == BookmarkContentType.LINK) {
            menu.add(0, 1, 0, R.string.bookmark_action_open_reader)
            menu.add(0, 2, 1, R.string.bookmark_action_open_browser)
            menu.add(0, 3, 2, R.string.bookmark_action_copy_link)
        } else {
            menu.add(0, 4, 0, R.string.bookmark_action_open_file)
            menu.add(0, 5, 1, R.string.bookmark_action_save_to_storage)
        }

        menu.add(0, 6, 3, R.string.bookmark_action_move_group)
        menu.add(0, 7, 4, R.string.bookmark_action_delete)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> { // Open Reader
                    startActivity(Intent(this, BookmarkReaderActivity::class.java).apply {
                        putExtra(BookmarkReaderActivity.EXTRA_BOOKMARK_ID, item.id)
                    })
                    true
                }
                2 -> { // Open Browser
                    item.url?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                    true
                }
                3 -> { // Copy Link
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("url", item.url ?: ""))
                    Toast.makeText(this, R.string.bookmark_action_copy_link, Toast.LENGTH_SHORT).show()
                    true
                }
                4 -> { // Open File
                    item.filePath?.let { FileViewerRouter.openFile(this, File(it)) }
                    true
                }
                5 -> { // Save to Storage (Export out of sandbox)
                    saveFileToDownloads(item)
                    true
                }
                6 -> { // Move Group
                    showMoveGroupDialog(listOf(item))
                    true
                }
                7 -> { // Delete with 5-second undo
                    deleteSingleItemWithUndo(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun saveFileToDownloads(item: BookmarkEntity) {
        val sourcePath = item.filePath ?: return
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadDir, sourceFile.name.substringAfter('_'))
            sourceFile.copyTo(destFile, overwrite = true)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@BookmarkHubActivity, getString(R.string.bookmark_export_success, 1, destFile.absolutePath), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteSingleItemWithUndo(item: BookmarkEntity) {
        recentlyDeletedItem = item
        val repo = BookmarkRepository.getInstance(this)

        lifecycleScope.launch {
            repo.deleteBookmark(item, purgeFiles = false) // Soft-delete from DB first

            Snackbar.make(findViewById(R.id.main), R.string.bookmark_deleted_undo, 5000)
                .setAction(R.string.bookmark_undo) {
                    lifecycleScope.launch {
                        repo.saveBookmark(item)
                    }
                }
                .addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (event != DISMISS_EVENT_ACTION) {
                            // Purge private physical files upon snackbar timeout
                            lifecycleScope.launch(Dispatchers.IO) {
                                item.filePath?.let { runCatching { File(it).delete() } }
                                item.thumbnailPath?.let { runCatching { File(it).delete() } }
                            }
                        }
                    }
                })
                .show()
        }
    }

    private fun showMoveGroupDialog(items: List<BookmarkEntity>) {
        val groupNames = allGroups.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.bookmark_action_move_group)
            .setItems(groupNames) { _, which ->
                val targetGroup = allGroups[which]
                lifecycleScope.launch(Dispatchers.IO) {
                    val repo = BookmarkRepository.getInstance(this@BookmarkHubActivity)
                    for (item in items) {
                        repo.updateBookmark(item.copy(groupId = targetGroup.id, groupName = targetGroup.name))
                    }
                }
                exitSelectionMode()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleStar(item: BookmarkEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val updated = item.copy(isStarred = !item.isStarred)
            BookmarkRepository.getInstance(this@BookmarkHubActivity).updateBookmark(updated)
        }
    }

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.bookmark_sort_newest),
            getString(R.string.bookmark_sort_oldest),
            getString(R.string.bookmark_sort_title_az),
            getString(R.string.bookmark_sort_size)
        )
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.bookmark_sort_title)
            .setSingleChoiceItems(options, currentSortMode) { dialog, which ->
                currentSortMode = which
                applyFilterAndSort()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateSelectionBar() {
        val count = adapter.selectedIds.size
        if (count > 0) {
            layoutSelectionBar.visibility = View.VISIBLE
            txtSelectedCount.text = getString(R.string.bookmark_batch_actions, count)
        } else {
            layoutSelectionBar.visibility = View.GONE
            adapter.isSelectionMode = false
        }
    }

    private fun exitSelectionMode() {
        adapter.selectedIds.clear()
        adapter.isSelectionMode = false
        adapter.notifyDataSetChanged()
        layoutSelectionBar.visibility = View.GONE
    }

    private fun performBatchDelete() {
        val itemsToDelete = allBookmarks.filter { adapter.selectedIds.contains(it.id) }
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.bookmark_batch_delete)
            .setMessage(getString(R.string.bookmark_batch_actions, itemsToDelete.size))
            .setPositiveButton(R.string.bookmark_action_delete) { _, _ ->
                lifecycleScope.launch {
                    BookmarkRepository.getInstance(this@BookmarkHubActivity).deleteBookmarks(itemsToDelete, purgeFiles = true)
                    exitSelectionMode()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performBatchMove() {
        val itemsToMove = allBookmarks.filter { adapter.selectedIds.contains(it.id) }
        showMoveGroupDialog(itemsToMove)
    }

    private fun showExportDialog(items: List<BookmarkEntity>) {
        val formats = arrayOf(
            getString(R.string.bookmark_export_html),
            getString(R.string.bookmark_export_markdown)
        )
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.bookmark_export_title)
            .setItems(formats) { _, which ->
                when (which) {
                    0 -> exportToHtml(items)
                    1 -> exportToMarkdown(items)
                }
                exitSelectionMode()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportToHtml(items: List<BookmarkEntity>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, "ufm_bookmarks_${System.currentTimeMillis()}.html")
            val sb = StringBuilder()
            sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
            sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
            sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
            for (item in items) {
                val url = item.url ?: item.filePath ?: ""
                sb.append("    <DT><A HREF=\"$url\" ADD_DATE=\"${item.createdAt / 1000}\">${item.title}</A>\n")
            }
            sb.append("</DL><p>\n")
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@BookmarkHubActivity, getString(R.string.bookmark_export_success, items.size, file.name), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportToMarkdown(items: List<BookmarkEntity>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, "ufm_bookmarks_${System.currentTimeMillis()}.md")
            val sb = StringBuilder()
            sb.append("# Ultimate File Manager Bookmarks\n\n")
            for (item in items) {
                sb.append("### [${item.title}](${item.url ?: item.filePath ?: ""})\n")
                sb.append("- **Collection:** ${item.groupName}\n")
                if (!item.notes.isNullOrBlank()) sb.append("- **Notes:** ${item.notes}\n")
                sb.append("\n")
            }
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@BookmarkHubActivity, getString(R.string.bookmark_export_success, items.size, file.name), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val SORT_NEWEST = 0
        const val SORT_OLDEST = 1
        const val SORT_TITLE_AZ = 2
        const val SORT_SIZE = 3
    }
}
