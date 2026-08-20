package za.kilowatch.ultimatefilemanager.storage

import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.storage.StorageManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.indexing.FileSearchEngine
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.indexing.SearchSuggestion
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Searches files by name across selected storage volume.
 * Uses coroutines for background searching with debounce.
 * Long-press shows context menu with file operations + "Open Folder Location".
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var editSearch: EditText
    private var spinnerDrive: Spinner? = null
    private lateinit var txtStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerResults: RecyclerView
    private lateinit var txtIndexingBanner: TextView
    private lateinit var scrollCategories: HorizontalScrollView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var fileAdapter: FileAdapter

    private val searchEngine by lazy { FileSearchEngine.getInstance(applicationContext) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchJob: Job? = null

    /** Pagination offset for the current query. Reset on new query. */
    private var currentOffset = 0
    private var currentQuery = ""
    private var isLoadingMore = false
    private val PAGE_SIZE = 200
    /** Accumulates pages of results for load-more pagination. */
    private val currentResults = mutableListOf<File>()
    /** Cumulative metadata maps — grow across load-more pages so every item keeps its badge/label. */
    private val cumulativeIndexedPaths  = mutableSetOf<String>()
    private val cumulativeStorageLabels = mutableMapOf<String, String>()

    /** Tracks which filters are currently active. */
    data class ActiveFilters(
        var minBytes: Long? = null,
        var maxBytes: Long? = null,
        var mimeTypes: MutableSet<String> = mutableSetOf(),  // "type:image", "type:video" etc.
        var extension: String? = null,  // comma-separated e.g. "pdf,jpg"
        var dateDays: Int? = null
    ) {
        fun isEmpty() = minBytes == null && maxBytes == null &&
                        mimeTypes.isEmpty() && extension == null && dateDays == null
    }
    private val activeFilters = ActiveFilters()

    /** Builds a combined query string from the EditText + active filters. */
    private fun buildQueryFromFilters(): String {
        val parts = mutableListOf<String>()
        val text = editSearch.text?.toString()?.trim() ?: ""
        if (text.isNotEmpty()) parts.add(text)
        activeFilters.minBytes?.let { parts.add("size>$it") }
        activeFilters.maxBytes?.let { parts.add("size<$it") }
        // Multiple mime types — one token per type
        activeFilters.mimeTypes.forEach { parts.add(it) }
        // Multiple extensions — comma-separated raw input split into individual ext: tokens
        activeFilters.extension?.let { raw ->
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
               .forEach { parts.add("ext:$it") }
        }
        activeFilters.dateDays?.let  { parts.add("date:${it}d") }
        return parts.joinToString(" ")
    }

    private fun triggerFilteredSearch() {
        val q = buildQueryFromFilters()
        if (q.isBlank()) {
            // All filters cleared and no text — reset to empty state
            searchJob?.cancel()
            currentResults.clear()
            fileAdapter.submitList(emptyList())
            txtStatus.visibility = View.GONE
            layoutEmpty.visibility = View.GONE
            progressBar.visibility = View.GONE
            recyclerResults.visibility = View.VISIBLE
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            currentOffset = 0
            currentQuery = q
            performSearch(q, offset = 0, append = false)
        }
    }

    /** Whether the device is a TV — cached after first use in setupViews. */
    private var isTv = false

    /** Represents a searchable drive entry. path==null means "All Drives" */
    data class DriveEntry(
        val label: String,
        val path: File?,
        val subtitle: String = "",
        val iconRes: Int = R.drawable.ic_storage_internal,
        val isIndexed: Boolean = false
    ) {
        override fun toString(): String = label
    }

    private val driveEntries = mutableListOf<DriveEntry>()
    private var selectedDriveIndex = 0
    private var selectedDrivePath: File? = null  // null = all drives

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (DeviceUtils.isTvDevice(this)) {
            setContentView(R.layout.activity_search_tv)
        } else {
            setContentView(R.layout.activity_search)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (DeviceUtils.isTvDevice(this)) (24 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        isTv = DeviceUtils.isTvDevice(this)
        setupViews()
    }

    override fun onResume() {
        super.onResume()
        updateIndexingBanner()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        editSearch = findViewById(R.id.editSearch)
        spinnerDrive = findViewById(R.id.spinnerDrive)
        txtStatus = findViewById(R.id.txtSearchStatus)
        txtIndexingBanner = findViewById(R.id.txtIndexingBanner)
        progressBar = findViewById(R.id.progressSearch)
        recyclerResults = findViewById(R.id.recyclerResults)
        scrollCategories = findViewById(R.id.scrollCategories)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        // Custom back button
        val btnBack = findViewById<ImageView>(R.id.btnSearchBack)
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            // TV: apply focus-based tint changes
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        // Mobile: tint is set via app:tint in XML, no override needed
        
        btnBack?.setOnClickListener { finish() }

        toolbar.setNavigationOnClickListener { finish() }

        // --- Clear button ---
        val btnClear = findViewById<ImageView>(R.id.btnSearchClear)
        btnClear?.setOnClickListener {
            editSearch.setText("")
            activeFilters.minBytes  = null
            activeFilters.maxBytes  = null
            activeFilters.mimeTypes.clear()
            activeFilters.extension = null
            activeFilters.dateDays  = null
            updateFilterPillState()
        }

        if (isTv) {
            val yellow = getColor(R.color.tv_button_focused_yellow)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            val white = getColor(R.color.tv_text_primary)
            val hint = getColor(R.color.tv_text_hint)

            editSearch.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.setBackgroundResource(R.drawable.selector_tv_button_yellow) // ensure selector is active
                    editSearch.setTextColor(black)
                    editSearch.setHintTextColor(black)
                    editSearch.setCompoundDrawablesWithIntrinsicBounds(
                        ContextCompat.getDrawable(this, R.drawable.ic_search)?.apply { setTint(black) },
                        null, null, null
                    )
                } else {
                    editSearch.setTextColor(white)
                    editSearch.setHintTextColor(hint)
                    editSearch.setCompoundDrawablesWithIntrinsicBounds(
                        ContextCompat.getDrawable(this, R.drawable.ic_search)?.apply { setTint(hint) },
                        null, null, null
                    )
                }
            }
        }

        // --- Drive Picker ---
        setupDrivePicker()

        // --- File Adapter with click and long-press ---
        fileAdapter = FileAdapter(
            isTv = isTv,
            onItemClick = { file, _ ->
                if (file.isDirectory) {
                    val (sid, stype, volumeRoot) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(file.absolutePath)
                    val intent = Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, volumeRoot)
                        putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, file.absolutePath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, file.name)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, sid)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, stype)
                    }
                    startActivity(intent)
                } else {
                    openFile(file)
                }
            },
            onSelectionChanged = { /* handled via long-press popup, not selection mode */ },
            onItemLongClick = { file -> showFileContextMenu(file) }
        )

        recyclerResults.layoutManager = LinearLayoutManager(this)
        recyclerResults.adapter = fileAdapter


        // --- Filter pill click listeners ---
        setupFilterPills()

        // --- Load-more on scroll ---
        recyclerResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore) return
                val lm = rv.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = lm.itemCount
                if (total > 0 && lastVisible >= total - 5) {
                    loadMore()
                }
            }
        })

        // Debounced search — 200 ms
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                // Show/hide clear button
                val btnClear = findViewById<ImageView>(R.id.btnSearchClear)
                btnClear?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                searchJob?.cancel()
                if (query.length < 2 && activeFilters.isEmpty()) {
                    fileAdapter.submitList(emptyList())
                    currentResults.clear()
                    txtStatus.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    layoutEmpty.visibility = View.GONE
                    recyclerResults.visibility = View.VISIBLE
                    return
                }
                searchJob = scope.launch {
                    delay(200) // debounce
                    val q = buildQueryFromFilters()
                    if (q.isNotBlank()) {
                        currentOffset = 0
                        currentQuery = q
                        performSearch(q, offset = 0, append = false)
                    }
                }
            }
        })

        editSearch.requestFocus()
    }

    private fun setupDrivePicker() {
        driveEntries.clear()
        val roots = getStorageRoots()
        val repo = IndexingRepository.getInstance(this)

        if (roots.size > 1) {
            val allIndexed = roots.all { repo.isStorageFullyIndexed(IndexingRepository.resolveStorageForPath(it.absolutePath).first) }
            driveEntries.add(
                DriveEntry(
                    label = getString(R.string.all_drives),
                    path = null,
                    subtitle = getString(R.string.search_all_drives_subtitle),
                    iconRes = R.drawable.ic_storage_internal,
                    isIndexed = allIndexed
                )
            )
        }
        for (root in roots) {
            val isInternal = root.absolutePath.contains("emulated/0")
            val label = when {
                isInternal -> getString(R.string.storage_internal)
                else -> root.name
            }
            val iconRes = when {
                isInternal -> R.drawable.ic_storage_internal
                root.absolutePath.contains("usb", ignoreCase = true) -> R.drawable.ic_storage_usb
                else -> R.drawable.ic_storage_sdcard
            }
            val (storageId, _, _) = IndexingRepository.resolveStorageForPath(root.absolutePath)
            val isIndexed = repo.isStorageFullyIndexed(storageId)

            val total = root.totalSpace
            val free = root.freeSpace
            val subtitle = if (total > 0) {
                "${android.text.format.Formatter.formatFileSize(this, free)} free of ${android.text.format.Formatter.formatFileSize(this, total)}"
            } else {
                root.absolutePath
            }

            driveEntries.add(DriveEntry(label, root, subtitle, iconRes, isIndexed))
        }

        if (isTv) {
            setupTvDriveSpinner()
        } else {
            setupMobileDriveCard()
        }

        updateIndexingBanner()
    }

    private fun setupMobileDriveCard() {
        val cardDrive = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardDrive) ?: return
        updateMobileDriveCardUI()
        cardDrive.setOnClickListener {
            showDriveSelectDialog()
        }
    }

    private fun updateMobileDriveCardUI() {
        val cardDrive = findViewById<View>(R.id.cardDrive) ?: return
        if (driveEntries.isEmpty()) return
        val entry = driveEntries.getOrNull(selectedDriveIndex) ?: driveEntries.first()

        cardDrive.findViewById<ImageView>(R.id.imgDriveIcon)?.setImageResource(entry.iconRes)
        cardDrive.findViewById<TextView>(R.id.txtDriveName)?.text = entry.label
        cardDrive.findViewById<TextView>(R.id.txtDriveSubtitle)?.text = entry.subtitle
        cardDrive.findViewById<ImageView>(R.id.imgIndexingBolt)?.visibility = if (entry.isIndexed) View.VISIBLE else View.GONE
    }

    private fun showDriveSelectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_search_drive_select, null)
        val layoutOptions = view.findViewById<LinearLayout>(R.id.layoutDriveOptions)

        val dlg = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(view)
            .create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        view.findViewById<View>(R.id.btnCancelDriveSelect)?.setOnClickListener {
            dlg.dismiss()
        }

        layoutOptions.removeAllViews()
        for ((index, entry) in driveEntries.withIndex()) {
            val isSelected = (index == selectedDriveIndex)
            val itemView = layoutInflater.inflate(R.layout.item_drive_option, layoutOptions, false)

            itemView.findViewById<ImageView>(R.id.imgDriveIcon)?.setImageResource(entry.iconRes)
            itemView.findViewById<TextView>(R.id.txtDriveName)?.text = entry.label
            itemView.findViewById<TextView>(R.id.txtDriveDetails)?.text = entry.subtitle
            itemView.findViewById<ImageView>(R.id.imgIndexingBolt)?.visibility = if (entry.isIndexed) View.VISIBLE else View.GONE
            itemView.findViewById<ImageView>(R.id.imgDriveCheck)?.visibility = if (isSelected) View.VISIBLE else View.GONE

            if (isSelected) {
                itemView.setBackgroundResource(R.drawable.bg_drive_option_card_selected)
            } else {
                itemView.setBackgroundResource(R.drawable.bg_reindex_option_card)
            }

            itemView.setOnClickListener {
                selectedDriveIndex = index
                val chosen = driveEntries[index]
                selectedDrivePath = chosen.path

                updateMobileDriveCardUI()
                dlg.dismiss()

                updateIndexingBanner()

                // Pill filters are index-only — clear them when switching to an unindexed drive
                if (!chosen.isIndexed && !activeFilters.isEmpty()) {
                    activeFilters.minBytes  = null
                    activeFilters.maxBytes  = null
                    activeFilters.mimeTypes.clear()
                    activeFilters.extension = null
                    activeFilters.dateDays  = null
                    updateFilterPillState()
                }

                // Always re-evaluate: triggerFilteredSearch handles text+filters, filters-only,
                // and the blank-query reset (clears list when nothing to search)
                triggerFilteredSearch()
            }

            layoutOptions.addView(itemView)
        }

        dlg.show()
    }

    private fun setupTvDriveSpinner() {
        val spinner = spinnerDrive ?: return
        val adapter = object : ArrayAdapter<DriveEntry>(this, R.layout.item_drive_spinner_selected, driveEntries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_drive_spinner_selected, parent, false)
                val entry = driveEntries[position]
                v.findViewById<TextView>(R.id.txtDriveName)?.text = entry.label
                v.findViewById<ImageView>(R.id.imgDriveIcon)?.setImageResource(entry.iconRes)
                v.findViewById<ImageView>(R.id.imgIndexingBolt)?.visibility = if (entry.isIndexed) View.VISIBLE else View.GONE
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_drive_spinner, parent, false)
                val entry = driveEntries[position]
                v.findViewById<TextView>(R.id.txtDriveName)?.text = entry.label
                v.findViewById<ImageView>(R.id.imgDriveIcon)?.setImageResource(entry.iconRes)
                v.findViewById<ImageView>(R.id.imgIndexingBolt)?.visibility = if (entry.isIndexed) View.VISIBLE else View.GONE
                return v
            }
        }
        spinner.adapter = adapter

        val yellowColor = getColor(R.color.tv_button_focused_yellow)
        val blackColor = getColor(R.color.tv_button_focused_yellow_text)
        val white = getColor(R.color.tv_text_primary)
        spinner.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundColor(if (hasFocus) yellowColor else android.graphics.Color.TRANSPARENT)
            val tv = (v as? Spinner)?.selectedView?.findViewById<TextView>(R.id.txtDriveName)
            tv?.setTextColor(if (hasFocus) blackColor else white)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDriveIndex = position
                val entry = driveEntries[position]
                selectedDrivePath = entry.path

                // Update subtitle for TV
                if (isTv) {
                    findViewById<TextView>(R.id.txtSearchSubtitle)?.text = entry.label
                }

                updateIndexingBanner()

                val isIndexed = entry.isIndexed
                if (!isIndexed && !activeFilters.isEmpty()) {
                    activeFilters.minBytes  = null
                    activeFilters.maxBytes  = null
                    activeFilters.mimeTypes.clear()
                    activeFilters.extension = null
                    activeFilters.dateDays  = null
                    updateFilterPillState()
                }

                triggerFilteredSearch()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateIndexingBanner() {
        val repo = IndexingRepository.getInstance(this)
        val roots = getStorageRoots()
        val isIndexed = if (selectedDrivePath == null) {
            roots.all { repo.isStorageFullyIndexed(IndexingRepository.resolveStorageForPath(it.absolutePath).first) }
        } else {
            repo.isStorageFullyIndexed(IndexingRepository.resolveStorageForPath(selectedDrivePath!!.absolutePath).first)
        }
        txtIndexingBanner.visibility = if (isIndexed) View.GONE else View.VISIBLE
        txtIndexingBanner.text = getString(R.string.some_storages_are_not_indexed)
        // Filter pills only make sense on indexed storage
        scrollCategories.visibility = if (isIndexed) View.VISIBLE else View.GONE
    }

    private suspend fun performSearch(
        query: String,
        offset: Int = 0,
        append: Boolean = false
    ) {
        if (!append) {
            progressBar.visibility = View.VISIBLE
            txtStatus.visibility = View.VISIBLE
            txtStatus.text = getString(R.string.search_searching)
            layoutEmpty.visibility = View.GONE
        } else {
            isLoadingMore = true
        }

        val results = withContext(Dispatchers.IO) {
            try {
                val indexingRepository = UfmApplication.indexingRepository
                val rootsForSearch = if (selectedDrivePath != null) listOf(selectedDrivePath!!) else getStorageRoots()

                val indexedRoots = mutableListOf<File>()
                val unindexedRoots = mutableListOf<File>()
                for (root in rootsForSearch) {
                    val storageId = IndexingRepository.resolveStorageForPath(root.absolutePath).first
                    if (indexingRepository.isStorageFullyIndexed(storageId)) {
                        indexedRoots.add(root)
                    } else {
                        unindexedRoots.add(root)
                    }
                }

                coroutineScope {
                    val indexedDeferred = async(Dispatchers.IO) {
                        if (indexedRoots.isEmpty()) return@async emptyList<File>()
                        val sid = if (selectedDrivePath == null && indexedRoots.size > 1) "%"
                                  else IndexingRepository.resolveStorageForPath(indexedRoots[0].absolutePath).first

                        // Use searchSmart for indexed storages
                        searchEngine.searchSmart(
                            query     = query,
                            storageId = sid,
                            limit     = PAGE_SIZE,
                            offset    = offset
                        ).map { File(it.path) }.filter { it.exists() }
                    }

                    val unindexedDeferreds = if (append) emptyList() else unindexedRoots.map { root ->
                        async(Dispatchers.IO) {
                            val lowerQuery = query.lowercase()
                            val found = mutableListOf<File>()
                            try {
                                root.walkTopDown()
                                    .onEnter { isActive }
                                    .filter { it.name.lowercase().contains(lowerQuery) }
                                    .take(PAGE_SIZE)
                                    .forEach { found.add(it) }
                            } catch (_: Exception) {}
                            found
                        }
                    }

                    val indexedResults   = indexedDeferred.await()
                    val unindexedResults = unindexedDeferreds.awaitAll().flatten()

                    (indexedResults + unindexedResults)
                        .distinctBy { it.absolutePath }
                        .sortedWith(NaturalSort.byName { it.name })
                        .take(PAGE_SIZE)
                }
            } catch (e: Exception) {
                Log.e("SearchActivity", "Error during indexed search: ${e.message}", e)
                performFallbackSearch(query)
            }
        }

        progressBar.visibility = View.GONE
        isLoadingMore = false

        if (results.isEmpty() && !append) {
            layoutEmpty.visibility = View.VISIBLE
            recyclerResults.visibility = View.GONE
            txtStatus.text = getString(R.string.search_no_results)
        } else if (results.isNotEmpty() || append) {
            layoutEmpty.visibility = View.GONE
            recyclerResults.visibility = View.VISIBLE

            val repo = IndexingRepository.getInstance(this)

            // Build storageLabels only for new-page results and add to cumulative map
            if (!append) cumulativeStorageLabels.clear()
            results.forEach { file ->
                val path = file.absolutePath
                val (storageId, _, _) = IndexingRepository.resolveStorageForPath(path)
                cumulativeStorageLabels[path] = when {
                    storageId == "internal"            -> getString(R.string.storage_internal)
                    storageId.startsWith("sdcard_")   -> "${getString(R.string.storage_sd_card)} (${storageId.removePrefix("sdcard_")})"
                    else                               -> getString(R.string.storage_unknown)
                }
            }

            scope.launch(Dispatchers.IO) {
                val db  = za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase.getInstance(applicationContext)
                val dao = db.fileIndexDao()

                // Look up indexed status for the new batch and accumulate
                if (!append) cumulativeIndexedPaths.clear()
                val newPaths = results.map { it.absolutePath }
                cumulativeIndexedPaths.addAll(dao.getIndexedPaths(newPaths))

                withContext(Dispatchers.Main) {
                    if (append) {
                        currentResults.addAll(results)
                    } else {
                        currentResults.clear()
                        currentResults.addAll(results)
                    }
                    // Pass FULL cumulative maps — never loses earlier pages' badge/label data
                    fileAdapter.submitList(
                        currentResults.toList(),
                        indexedPaths  = cumulativeIndexedPaths.toSet(),
                        storageLabels = cumulativeStorageLabels.toMap()
                    )
                    val shown = fileAdapter.itemCount
                    val hasMore = results.size >= PAGE_SIZE
                    txtStatus.text = if (hasMore)
                        getString(R.string.showing_shown_results_scroll_for_more, shown)
                    else
                        getString(R.string.showing_results_count, shown)
                }
            }

            // Save to recent searches only on first page
            if (!append) searchEngine.saveRecentSearch(query)
        }
    }

    /**
     * Fallback search when index is not available
     */
    private suspend fun performFallbackSearch(query: String): List<File> {
        return withContext(Dispatchers.IO) {
            val found = mutableListOf<File>()
            val roots = if (selectedDrivePath != null) listOf(selectedDrivePath!!) else getStorageRoots()
            val lowerQuery = query.lowercase()

            for (root in roots) {
                if (!isActive) break
                try {
                    root.walkTopDown()
                        .onEnter { isActive }
                        .filter { it.name.lowercase().contains(lowerQuery) }
                        .take(200)
                        .forEach { found.add(it) }
                } catch (_: Exception) { }
            }
            found.sortedWith(NaturalSort.byName { it.name })
        }
    }


    private fun showFileContextMenu(file: File) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // Open
        if (!file.isDirectory) {
            options.add(getString(R.string.action_open))
            actions.add { openFile(file) }
        }

        // Open Folder Location
        options.add(getString(R.string.action_open_folder))
        actions.add {
            val parentDir = file.parentFile ?: return@add
            val (sid, stype, volumeRoot) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(parentDir.absolutePath)
            val intent = Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, volumeRoot)
                putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, parentDir.absolutePath)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, parentDir.name)
                putExtra(FileBrowserActivity.EXTRA_FOCUS_PATH, file.absolutePath)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, sid)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, stype)
            }
            startActivity(intent)
        }

        // Copy
        options.add(getString(R.string.action_copy))
        actions.add {
            handleCopyOrCut(file, isMove = false)
        }

        // Move
        options.add(getString(R.string.action_move))
        actions.add {
            handleCopyOrCut(file, isMove = true)
        }

        // Rename
        options.add(getString(R.string.action_rename))
        actions.add { showRenameDialog(file) }

        // Share (files only)
        if (!file.isDirectory) {
            options.add(getString(R.string.action_share))
            actions.add { shareFile(file) }
        }

        // Extract Here
        val pm = za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager
        if (!file.isDirectory && za.kilowatch.ultimatefilemanager.archive.ArchiveManager.isSupportedArchive(file) && pm.isIconEnabled(this, pm.KEY_EXTRACT)) {
            options.add(getString(R.string.action_extract_here))
            actions.add { performExtractHere(file) }
        }

        // Delete
        options.add(getString(R.string.action_delete))
        actions.add { confirmDelete(file) }

        val bgColor = getColor(R.color.tv_bg_gradient_end)
        val white = getColor(R.color.tv_text_primary)

        // Custom adapter so list items render white on dark background
        val listAdapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.setTextColor(white)
                v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                return v
            }
        }

        val dialog = MaterialAlertDialogBuilder(this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(file.name)
            .setAdapter(listAdapter) { _, which ->
                actions[which]()
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(bgColor)
        )
        val titleView = dialog.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.alertTitle
        ) ?: dialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(white)
    }

    private fun handleCopyOrCut(file: File, isMove: Boolean) {
        val op = if (isMove) FileClipboard.Operation.MOVE else FileClipboard.Operation.COPY
        val recentSlot = FileClipboard.getRecentSlot()
        if (recentSlot == null) {
            FileClipboard.pushLocalSlot(listOf(file), op, file.parent ?: "")
            showSnackbar(getString(if (isMove) R.string.clipboard_cut else R.string.clipboard_copied, 1))
        } else {
            val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
            val layoutRes = if (isTv) R.layout.dialog_clipboard_add_or_new_tv else R.layout.dialog_clipboard_add_or_new
            val itemLayoutRes = if (isTv) R.layout.item_clipboard_slot_choice_tv else R.layout.item_clipboard_slot_choice
            val dialogView = layoutInflater.inflate(layoutRes, null)
            val txtSubtitle = dialogView.findViewById<android.widget.TextView>(R.id.txtAddOrNewSubtitle)
            val recyclerSlots = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerExistingSlots)
            val btnNewSlot = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNewSlot)
            val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelAddOrNew)

            txtSubtitle.text = getString(R.string.clipboard_slots_title, FileClipboard.slots.size, FileClipboard.totalItemCount())

            val dialog: android.app.Dialog = if (isTv) {
                MaterialAlertDialogBuilder(this)
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

                    if (isTv) {
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
                        FileClipboard.addLocalToSlot(slot.id, listOf(file), op)
                        showSnackbar(getString(if (isMove) R.string.clipboard_cut else R.string.clipboard_copied, 1))
                    }
                }
            }

            btnNewSlot.setOnClickListener {
                dialog.dismiss()
                if (FileClipboard.isFull) {
                    android.widget.Toast.makeText(this, R.string.clipboard_full_paste_first, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    FileClipboard.pushLocalSlot(listOf(file), op, file.parent ?: "")
                    if (FileClipboard.slots.size == 9) {
                        android.widget.Toast.makeText(this, R.string.clipboard_warning_one_slot_left, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    showSnackbar(getString(R.string.clipboard_slot_created, 1, FileClipboard.slots.size))
                }
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            if (isTv) {
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            dialog.show()
        }
    }

    private fun showRenameDialog(file: File) {
        val bgColor = getColor(R.color.tv_bg_gradient_end)
        val white = getColor(R.color.tv_text_primary)
        val black = getColor(R.color.tv_button_focused_yellow_text)
        val yellow = getColor(R.color.tv_button_focused_yellow)
        val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
        val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

        val input = EditText(this).apply {
            setText(file.name)
            selectAll()
            setPadding(48, 32, 48, 16)
            setTextColor(white)
            setHintTextColor(getColor(R.color.tv_text_hint))
            backgroundTintList = yellowCsl
        }

        val dialog = MaterialAlertDialogBuilder(this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    val target = File(file.parentFile, newName)
                    if (file.renameTo(target)) {
                        val oldPath = file.absolutePath
                        val newPath = target.absolutePath
                        val isDir = target.isDirectory

                        // Sync index in background
                        scope.launch(Dispatchers.IO) {
                            try {
                                val repo = UfmApplication.indexingRepository
                                if (isDir) {
                                    repo.deleteTreeFromIndex(oldPath)
                                    val (sid, stype, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(newPath)
                                    repo.indexFolder(newPath, sid, stype)
                                } else {
                                    repo.deleteFromIndex(oldPath)
                                    val (sid, stype, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(newPath)
                                    repo.indexFile(target, sid, stype)
                                }
                            } catch (e: Exception) {
                                GoRoLog.e("SearchActivity", "Index sync failed for rename: ${e.message}")
                            }
                        }

                        showSnackbar(getString(R.string.renamed_to_newname, newName))
                        val query = editSearch.text?.toString()?.trim() ?: ""
                        if (query.length >= 2) {
                            searchJob?.cancel()
                            searchJob = scope.launch { performSearch(query) }
                        }
                    } else {
                        showSnackbar(getString(R.string.rename_error))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))
        val titleView = dialog.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.alertTitle
        ) ?: dialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(white)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            backgroundTintList = yellowCsl; setTextColor(black)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            backgroundTintList = glassCsl; setTextColor(white)
        }
    }

    private fun performExtractHere(file: File) {
        if (!za.kilowatch.ultimatefilemanager.archive.ArchiveManager.isSupportedArchive(file)) return

        val dialog = za.kilowatch.ultimatefilemanager.archive.ExtractOptionsDialog.newInstance(listOf(file.name))
        dialog.setOnExtractHere {
            performExtract(file, isSelectFolderMode = false)
        }
        dialog.setOnExtractToNewFolder {
            promptExtractToNewFolder(file)
        }
        dialog.setOnExtractAndSelectFolder {
            performExtract(file, isSelectFolderMode = true)
        }
        dialog.show(supportFragmentManager, za.kilowatch.ultimatefilemanager.archive.ExtractOptionsDialog.TAG)
    }

    private fun promptExtractToNewFolder(file: File) {
        val defaultName = za.kilowatch.ultimatefilemanager.archive.ArchiveManager.getArchiveBaseName(file.name)
        val isOnTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)

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
            hint = getString(R.string.extract_new_folder_hint)
            setText(defaultName)
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
            .setTitle(getString(R.string.extract_new_folder_title))
            .setIcon(R.drawable.ic_folder)
            .setView(container)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .setPositiveButton(getString(R.string.extract_to_new_folder)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    showSnackbar(getString(R.string.new_folder_empty))
                    return@setPositiveButton
                }
                val parent = file.parentFile ?: filesDir
                val newDir = File(parent, name)
                if (!newDir.exists()) {
                    newDir.mkdirs()
                }
                performExtract(file, customDestFolder = newDir, isSelectFolderMode = false)
            }
            .show()
    }

    private fun performExtract(file: File, customDestFolder: File? = null, isSelectFolderMode: Boolean) {
        scope.launch(Dispatchers.Main) {
            val progressDialog = MaterialAlertDialogBuilder(this@SearchActivity, R.style.UFM_Dialog)
                .setTitle(R.string.extract_progress_title)
                .setMessage(file.name)
                .setCancelable(false)
                .create()
            progressDialog.show()

            var password: String? = null
            var success = false
            var attempts = 0
            var lastError: Exception? = null
            val tempExtractDir = if (isSelectFolderMode) {
                File(cacheDir, "extract_temp_${System.currentTimeMillis()}").apply { mkdirs() }
            } else null
            val targetDest = tempExtractDir ?: customDestFolder ?: (file.parentFile ?: filesDir)

            withContext(Dispatchers.IO) {
                while (!success && attempts < 3) {
                    val result = za.kilowatch.ultimatefilemanager.archive.ArchiveManager.extract(
                        this@SearchActivity,
                        file,
                        targetDest,
                        password,
                        onProgress = {},
                        onConflict = { conflictFile, isFolder, destSizeBytes, applyToAllRef ->
                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                this@SearchActivity,
                                conflictFile.name,
                                isFolder,
                                destSizeBytes,
                                applyToAllRef
                            )
                        }
                    )

                    if (result.isSuccess) {
                        success = true
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
                            if (pwd == null) break
                            password = pwd
                            attempts++
                        } else {
                            break
                        }
                    }
                }
            }

            progressDialog.dismiss()

            if (isSelectFolderMode && tempExtractDir != null) {
                if (success) {
                    val extractedFiles = tempExtractDir.listFiles()?.toList() ?: emptyList()
                    if (extractedFiles.isNotEmpty()) {
                        za.kilowatch.ultimatefilemanager.storage.FileClipboard.setExtract(extractedFiles, tempExtractDir)
                        android.widget.Toast.makeText(this@SearchActivity, R.string.extract_staged_snackbar, android.widget.Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        tempExtractDir.deleteRecursively()
                        showSnackbar(getString(R.string.extract_error, "No files extracted"))
                    }
                } else {
                    tempExtractDir.deleteRecursively()
                    if (lastError != null) {
                        showSnackbar(getString(R.string.extract_error, lastError?.message ?: "Extraction failed"))
                    }
                }
            } else {
                if (success) {
                    showSnackbar(getString(R.string.extract_success, 1))
                } else if (lastError != null) {
                    showSnackbar(getString(R.string.extract_error, lastError?.message ?: "Extraction failed"))
                }
            }
        }
    }

    private fun shareFile(file: File) {
        try {
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
        } catch (e: Exception) {
            showSnackbar(getString(R.string.share_error))
        }
    }

    private fun confirmDelete(file: File) {
        val bgColor = getColor(R.color.tv_bg_gradient_end)
        val white = getColor(R.color.tv_text_primary)
        val black = getColor(R.color.tv_button_focused_yellow_text)
        val yellow = getColor(R.color.tv_button_focused_yellow)
        val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
        val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

        val dialog = MaterialAlertDialogBuilder(this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.delete_filename, file.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val path = file.absolutePath
                val isDir = file.isDirectory
                val name = file.name

                // Run the blocking delete (File.delete -> native remove syscall) on the
                // IO dispatcher: on slow or busy storage a single delete can exceed the
                // 5 s ANR watchdog threshold and freeze the main thread (reported from a
                // KTC JVC 2K TV, SDK 34, app 1.8.0-GOOGLE).
                scope.launch(Dispatchers.IO) {
                    val deleted = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(path)) {
                        za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(path)
                    } else if (isDir) {
                        file.deleteRecursively()
                    } else {
                        file.delete()
                    }
                    withContext(Dispatchers.Main) {
                        if (deleted) {
                            // Sync index in background
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val repo = UfmApplication.indexingRepository
                                    if (isDir) repo.deleteTreeFromIndex(path)
                                    else repo.deleteFromIndex(path)
                                } catch (e: Exception) {
                                    GoRoLog.e("SearchActivity", "Index sync failed for delete: ${e.message}")
                                }
                            }

                            showSnackbar(getString(R.string.name_deleted, name))
                            val query = editSearch.text?.toString()?.trim() ?: ""
                            if (query.length >= 2) {
                                searchJob?.cancel()
                                searchJob = scope.launch { performSearch(query, offset = 0, append = false) }
                            }
                        } else {
                            val hasProtected = za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isProtectedPath(path)
                            val shizukuAuthorized = za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isAuthorized()
                            val msg = if (hasProtected && !shizukuAuthorized) {
                                getString(R.string.delete_error_shizuku_required)
                            } else {
                                getString(R.string.failed_to_delete_name, name)
                            }
                            showSnackbar(msg)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))
        val titleView = dialog.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.alertTitle
        ) ?: dialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(white)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            backgroundTintList = yellowCsl; setTextColor(black)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            backgroundTintList = glassCsl; setTextColor(white)
        }
    }


    // ============ FILTER PILL HELPERS ============

    /**
     * Sets up 4 filter pills. Each pill:
     *  - Inactive: opens a dialog to collect the filter value
     *  - Active (yellow): clears that specific filter and re-runs search
     * Mobile and TV each get their own dialog layout.
     */
    private fun setupFilterPills() {
        val pillSize      = findViewById<Chip>(R.id.chipFilterSize)
        val pillType      = findViewById<Chip>(R.id.chipFilterType)
        val pillExtension = findViewById<Chip>(R.id.chipFilterExtension)
        val pillDate      = findViewById<Chip>(R.id.chipFilterDate)

        // Disable the built-in auto-toggle so the tick only appears when
        // updateFilterPillState() explicitly marks a filter as active.
        listOf(pillSize, pillType, pillExtension, pillDate).forEach { it.isCheckable = false }

        // Size filter
        pillSize.setOnClickListener { showSizeDialog() }
        pillSize.setOnCloseIconClickListener {
            activeFilters.minBytes = null
            activeFilters.maxBytes = null
            updateFilterPillState()
            triggerFilteredSearch()
        }

        // Type filter
        pillType.setOnClickListener { showTypeDialog() }
        pillType.setOnCloseIconClickListener {
            activeFilters.mimeTypes.clear()
            updateFilterPillState()
            triggerFilteredSearch()
        }

        // Extension filter
        pillExtension.setOnClickListener { showExtensionDialog() }
        pillExtension.setOnCloseIconClickListener {
            activeFilters.extension = null
            updateFilterPillState()
            triggerFilteredSearch()
        }

        // Date filter
        pillDate.setOnClickListener { showDateDialog() }
        pillDate.setOnCloseIconClickListener {
            activeFilters.dateDays = null
            updateFilterPillState()
            triggerFilteredSearch()
        }

        if (isTv) {
            val pills = listOf(pillSize, pillType, pillExtension, pillDate)
            val black = getColor(R.color.tv_button_focused_yellow_text)

            pills.forEach { pill ->
                pill.setOnClickListener {
                    when (pill.id) {
                        R.id.chipFilterSize -> {
                            if (activeFilters.minBytes != null || activeFilters.maxBytes != null) {
                                activeFilters.minBytes = null
                                activeFilters.maxBytes = null
                                updateFilterPillState()
                                triggerFilteredSearch()
                            } else {
                                showSizeDialog()
                            }
                        }
                        R.id.chipFilterType -> {
                            if (activeFilters.mimeTypes.isNotEmpty()) {
                                activeFilters.mimeTypes.clear()
                                updateFilterPillState()
                                triggerFilteredSearch()
                            } else {
                                showTypeDialog()
                            }
                        }
                        R.id.chipFilterExtension -> {
                            if (activeFilters.extension != null) {
                                activeFilters.extension = null
                                updateFilterPillState()
                                triggerFilteredSearch()
                            } else {
                                showExtensionDialog()
                            }
                        }
                        R.id.chipFilterDate -> {
                            if (activeFilters.dateDays != null) {
                                activeFilters.dateDays = null
                                updateFilterPillState()
                                triggerFilteredSearch()
                            } else {
                                showDateDialog()
                            }
                        }
                    }
                }
                pill.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        pill.setChipBackgroundColorResource(R.color.tv_button_focused_yellow)
                        pill.setTextColor(black)
                    } else {
                        updateFilterPillState()
                    }
                }
            }
        }

        updateFilterPillState()
    }

    /** Applies active styling and dynamic summaries to pills on mobile and yellow tint on TV. */
    private fun updateFilterPillState() {
        val pillSize      = findViewById<Chip>(R.id.chipFilterSize) ?: return
        val pillType      = findViewById<Chip>(R.id.chipFilterType) ?: return
        val pillExtension = findViewById<Chip>(R.id.chipFilterExtension) ?: return
        val pillDate      = findViewById<Chip>(R.id.chipFilterDate) ?: return

        val isSizeActive = activeFilters.minBytes != null || activeFilters.maxBytes != null
        val isTypeActive = activeFilters.mimeTypes.isNotEmpty()
        val isExtActive  = activeFilters.extension != null
        val isDateActive = activeFilters.dateDays != null

        if (isTv) {
            val accentColor   = getColor(R.color.tv_accent)
            val accentCsl     = android.content.res.ColorStateList.valueOf(accentColor)
            val inactiveColor = getColor(R.color.tv_glass_white_10)
            val inactiveCsl   = android.content.res.ColorStateList.valueOf(inactiveColor)
            val blackText     = getColor(android.R.color.black)
            val normalText    = getColor(R.color.tv_text_primary)

            fun applyTv(pill: Chip, active: Boolean) {
                pill.chipBackgroundColor = if (active) accentCsl else inactiveCsl
                pill.setTextColor(if (active) blackText else normalText)
            }

            applyTv(pillSize, isSizeActive)
            applyTv(pillType, isTypeActive)
            applyTv(pillExtension, isExtActive)
            applyTv(pillDate, isDateActive)
            return
        }

        val primaryColor      = za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(this)
        val activeBgCsl       = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_selection_highlight))
        val inactiveBgCsl     = android.content.res.ColorStateList.valueOf(getColor(R.color.mobile_glass_card))
        val activeStrokeCsl   = android.content.res.ColorStateList.valueOf(primaryColor)
        val inactiveStrokeCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.mobile_glass_stroke))
        val activeText        = primaryColor
        val inactiveText      = getColor(R.color.mobile_card_text_primary)
        val density           = resources.displayMetrics.density

        fun applyMobile(
            pill: Chip,
            active: Boolean,
            defaultText: String,
            activeTextSummary: String
        ) {
            pill.text = if (active) activeTextSummary else defaultText
            pill.isCloseIconVisible = active
            pill.chipBackgroundColor = if (active) activeBgCsl else inactiveBgCsl
            pill.chipStrokeColor = if (active) activeStrokeCsl else inactiveStrokeCsl
            pill.chipStrokeWidth = if (active) 1.5f * density else 1f * density
            pill.setTextColor(if (active) activeText else inactiveText)
            pill.chipIconTint = android.content.res.ColorStateList.valueOf(if (active) activeText else getColor(R.color.mobile_icon_tint))
            pill.closeIconTint = android.content.res.ColorStateList.valueOf(activeText)
        }

        // Size summary
        val sizeSummary = when {
            activeFilters.minBytes != null && activeFilters.maxBytes != null ->
                "${android.text.format.Formatter.formatFileSize(this, activeFilters.minBytes!!)} - ${android.text.format.Formatter.formatFileSize(this, activeFilters.maxBytes!!)}"
            activeFilters.minBytes != null ->
                "> ${android.text.format.Formatter.formatFileSize(this, activeFilters.minBytes!!)}"
            activeFilters.maxBytes != null ->
                "< ${android.text.format.Formatter.formatFileSize(this, activeFilters.maxBytes!!)}"
            else -> getString(R.string.search_pill_size)
        }

        // Type summary
        val typeSummary = when {
            activeFilters.mimeTypes.size == 1 -> {
                val token = activeFilters.mimeTypes.first()
                when (token) {
                    "type:image" -> getString(R.string.analyzer_category_images)
                    "type:video" -> getString(R.string.analyzer_category_videos)
                    "type:audio" -> getString(R.string.analyzer_category_audio)
                    "type:doc" -> getString(R.string.analyzer_category_documents)
                    "type:apk" -> getString(R.string.analyzer_category_apks)
                    "type:archive" -> "Archives"
                    else -> token.removePrefix("type:")
                }
            }
            activeFilters.mimeTypes.size > 1 -> "${activeFilters.mimeTypes.size} Types"
            else -> getString(R.string.search_pill_type)
        }

        // Extension summary
        val extSummary = if (isExtActive) ".${activeFilters.extension}" else getString(R.string.search_pill_extension)

        // Date summary
        val dateSummary = if (isDateActive) "< ${activeFilters.dateDays}d" else getString(R.string.search_pill_date)

        applyMobile(pillSize, isSizeActive, getString(R.string.search_pill_size), sizeSummary)
        applyMobile(pillType, isTypeActive, getString(R.string.search_pill_type), typeSummary)
        applyMobile(pillExtension, isExtActive, getString(R.string.search_pill_extension), extSummary)
        applyMobile(pillDate, isDateActive, getString(R.string.search_pill_date), dateSummary)
    }

    // ---- Size dialog ----
    private fun showSizeDialog() {
        val layoutRes = if (isTv) R.layout.dialog_search_filter_size_tv
                        else R.layout.dialog_search_filter_size
        val view = layoutInflater.inflate(layoutRes, null)
        val editMin    = view.findViewById<android.widget.EditText>(R.id.editMinSize)
        val editMax    = view.findViewById<android.widget.EditText>(R.id.editMaxSize)
        val spinnerMin = view.findViewById<android.widget.Spinner>(R.id.spinnerMinUnit)
        val spinnerMax = view.findViewById<android.widget.Spinner>(R.id.spinnerMaxUnit)

        val allUnits  = arrayOf("KB", "MB", "GB")
        val allMultipliers = longArrayOf(1_024L, 1_048_576L, 1_073_741_824L)

        val bgColor   = getColor(R.color.tv_bg_gradient_end)
        val textColor = if (isTv) getColor(R.color.tv_text_primary) else getColor(R.color.mobile_card_text_primary)

        fun makeAdapter(items: Array<String>) =
            object : ArrayAdapter<String>(this, 
                if (isTv) R.layout.item_tv_spinner_dropdown else android.R.layout.simple_spinner_item, 
                items) {
                override fun getView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val v = super.getView(pos, cv, parent) as TextView
                    if (!isTv) {
                        v.setTextColor(textColor); v.setBackgroundColor(android.graphics.Color.TRANSPARENT); v.setPadding(8, 0, 8, 0)
                    }
                    return v
                }
                override fun getDropDownView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val v = super.getDropDownView(pos, cv, parent) as TextView
                    if (!isTv) {
                        v.setTextColor(textColor); v.setBackgroundColor(getColor(R.color.mobile_glass_card)); v.setPadding(24, 20, 24, 20)
                    }
                    return v
                }
            }

        spinnerMin.adapter = makeAdapter(allUnits)
        spinnerMin.setSelection(1) // default MB

        // Constrain Max spinner to units >= Min unit on every Min change
        fun updateMaxSpinner(minIdx: Int) {
            val validUnits = allUnits.drop(minIdx).toTypedArray()
            spinnerMax.adapter = makeAdapter(validUnits)
            spinnerMax.setSelection(0) // start at same unit as min
        }
        updateMaxSpinner(1) // initial: both start at MB

        spinnerMin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
                updateMaxSpinner(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val dlg = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(view)
            .create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (isTv) bgColor else android.graphics.Color.TRANSPARENT))

        if (isTv) {
            val yellow = getColor(R.color.tv_button_focused_yellow)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            val white = getColor(R.color.tv_text_primary)
            val hint = getColor(R.color.tv_text_hint)

            val tvElements = listOf(editMin, editMax, spinnerMin, spinnerMax, 
                                   view.findViewById<View>(R.id.btnSizeCancel), 
                                   view.findViewById<View>(R.id.btnSizeApply))
            
            tvElements.forEach { element ->
                element.setOnFocusChangeListener { v, hasFocus ->
                    when (v) {
                        is android.widget.EditText -> {
                            v.setTextColor(if (hasFocus) black else white)
                            v.setHintTextColor(if (hasFocus) black else hint)
                        }
                        is android.widget.TextView -> { // Buttons are TextViews
                            v.setTextColor(if (hasFocus) black else white)
                        }
                        is android.widget.Spinner -> {
                            (v.selectedView as? TextView)?.setTextColor(if (hasFocus) black else white)
                        }
                    }
                }
            }
        }

        view.findViewById<android.widget.Button>(R.id.btnSizeCancel)?.setOnClickListener { dlg.dismiss() }
        view.findViewById<android.widget.Button>(R.id.btnSizeApply)?.setOnClickListener {
            val minTxt = editMin.text.toString().toDoubleOrNull()
            val maxTxt = editMax.text.toString().toDoubleOrNull()
            if (minTxt == null && maxTxt == null) { dlg.dismiss(); return@setOnClickListener }
            val minUnitIdx = spinnerMin.selectedItemPosition          // index in allUnits
            val maxUnitIdx = spinnerMin.selectedItemPosition + spinnerMax.selectedItemPosition // offset from min
            activeFilters.minBytes = minTxt?.let { (it * allMultipliers[minUnitIdx]).toLong() }
            activeFilters.maxBytes = maxTxt?.let { (it * allMultipliers[maxUnitIdx]).toLong() }
            dlg.dismiss()
            updateFilterPillState()
            triggerFilteredSearch()
        }

        dlg.show()
    }

    // ---- Type dialog ----
    private fun showTypeDialog() {
        val layoutRes = if (isTv) R.layout.dialog_search_filter_type_tv
                        else R.layout.dialog_search_filter_type
        val view = layoutInflater.inflate(layoutRes, null)

        // Map checkbox views to their query token
        val checkMap = linkedMapOf(
            view.findViewById<android.widget.CheckBox>(R.id.checkImages)   to "type:image",
            view.findViewById<android.widget.CheckBox>(R.id.checkVideos)   to "type:video",
            view.findViewById<android.widget.CheckBox>(R.id.checkAudio)    to "type:audio",
            view.findViewById<android.widget.CheckBox>(R.id.checkDocs)     to "type:doc",
            view.findViewById<android.widget.CheckBox>(R.id.checkApk)      to "type:apk",
            view.findViewById<android.widget.CheckBox>(R.id.checkArchives) to "type:archive"
        )

        // Pre-check whatever is currently active
        checkMap.forEach { (cb, token) -> cb.isChecked = activeFilters.mimeTypes.contains(token) }

        val dlg = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(view)
            .create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (isTv) getColor(R.color.tv_bg_gradient_end) else android.graphics.Color.TRANSPARENT))

        if (isTv) {
            val black = getColor(R.color.tv_button_focused_yellow_text)
            val white = getColor(R.color.tv_text_primary)
            
            checkMap.keys.forEach { cb ->
                cb.setOnFocusChangeListener { _, hasFocus ->
                    cb.setTextColor(if (hasFocus) black else white)
                }
            }
            listOf(R.id.btnTypeCancel, R.id.btnTypeApply).forEach { id ->
                view.findViewById<View>(id)?.setOnFocusChangeListener { v, hasFocus ->
                    (v as? TextView)?.setTextColor(if (hasFocus) black else white)
                }
            }
        }

        view.findViewById<android.widget.Button>(R.id.btnTypeCancel)?.setOnClickListener { dlg.dismiss() }
        view.findViewById<android.widget.Button>(R.id.btnTypeApply)?.setOnClickListener {
            activeFilters.mimeTypes.clear()
            checkMap.forEach { (cb, token) -> if (cb.isChecked) activeFilters.mimeTypes.add(token) }
            dlg.dismiss()
            updateFilterPillState()
            triggerFilteredSearch()
        }
        dlg.show()
    }

    // ---- Extension dialog ----
    private fun showExtensionDialog() {
        val layoutRes = if (isTv) R.layout.dialog_search_filter_text_tv
                        else R.layout.dialog_search_filter_text
        val view = layoutInflater.inflate(layoutRes, null)
        view.findViewById<android.widget.TextView>(R.id.txtFilterTextTitle)?.text =
            getString(R.string.search_filter_ext_title)
        val edit = view.findViewById<android.widget.EditText>(R.id.editFilterText)
        edit.hint = getString(R.string.search_filter_ext_hint)
        activeFilters.extension?.let { edit.setText(it) }

        val dlg = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(view)
            .create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (isTv) getColor(R.color.tv_bg_gradient_end) else android.graphics.Color.TRANSPARENT))

        if (isTv) {
            val black = getColor(R.color.tv_button_focused_yellow_text)
            val white = getColor(R.color.tv_text_primary)
            val hint = getColor(R.color.tv_text_hint)

            edit.setOnFocusChangeListener { _, hasFocus ->
                edit.setTextColor(if (hasFocus) black else white)
                edit.setHintTextColor(if (hasFocus) black else hint)
            }
            listOf(R.id.btnTextCancel, R.id.btnTextApply).forEach { id ->
                view.findViewById<View>(id)?.setOnFocusChangeListener { v, hasFocus ->
                    (v as? TextView)?.setTextColor(if (hasFocus) black else white)
                }
            }
        }

        view.findViewById<android.widget.Button>(R.id.btnTextCancel)?.setOnClickListener { dlg.dismiss() }
        view.findViewById<android.widget.Button>(R.id.btnTextApply)?.setOnClickListener {
            val ext = edit.text.toString().trim().lowercase()
            activeFilters.extension = ext.ifEmpty { null }
            dlg.dismiss()
            updateFilterPillState()
            triggerFilteredSearch()
        }
        dlg.show()
    }

    // ---- Date dialog ----
    private fun showDateDialog() {
        val layoutRes = if (isTv) R.layout.dialog_search_filter_date_tv
                        else R.layout.dialog_search_filter_date
        val view = layoutInflater.inflate(layoutRes, null)
        val edit = view.findViewById<android.widget.EditText>(R.id.editDays)
        activeFilters.dateDays?.let { edit.setText(it.toString()) }

        val dlg = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(view)
            .create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (isTv) getColor(R.color.tv_bg_gradient_end) else android.graphics.Color.TRANSPARENT))

        if (isTv) {
            val black = getColor(R.color.tv_button_focused_yellow_text)
            val white = getColor(R.color.tv_text_primary)
            val hint = getColor(R.color.tv_text_hint)

            edit.setOnFocusChangeListener { _, hasFocus ->
                edit.setTextColor(if (hasFocus) black else white)
                edit.setHintTextColor(if (hasFocus) black else hint)
            }
            listOf(R.id.btnDateCancel, R.id.btnDateApply).forEach { id ->
                view.findViewById<View>(id)?.setOnFocusChangeListener { v, hasFocus ->
                    (v as? TextView)?.setTextColor(if (hasFocus) black else white)
                }
            }
        }

        view.findViewById<android.widget.Button>(R.id.btnDateCancel)?.setOnClickListener { dlg.dismiss() }
        view.findViewById<android.widget.Button>(R.id.btnDateApply)?.setOnClickListener {
            activeFilters.dateDays = edit.text.toString().toIntOrNull()
            dlg.dismiss()
            updateFilterPillState()
            triggerFilteredSearch()
        }
        dlg.show()
    }

    // ============ PAGINATION ============

    private fun loadMore() {
        if (currentQuery.length < 2) return
        currentOffset += PAGE_SIZE
        searchJob?.cancel()
        searchJob = scope.launch {
            performSearch(currentQuery, offset = currentOffset, append = true)
        }
    }

    private fun getStorageRoots(): List<File> {
        val roots = mutableListOf<File>()
        val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (vol in sm.storageVolumes) {
            try {
                val dirPath = vol.safeDirectoryPath
                val dir = dirPath?.let { File(it) }
                if (dir != null && dir.exists() && dir.canRead()) {
                    roots.add(dir)
                }
            } catch (_: Exception) { }
        }
        if (roots.isEmpty()) {
            roots.add(File("/storage/emulated/0"))
        }
        return roots
    }

    private fun openFile(file: File) {
        // Try built-in viewer first
        if (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.openFile(this, file)) return

        // Fall back to external app
        try {
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                showSnackbar(getString(R.string.no_app_found_to_open_this_file_type))
            }
        } catch (e: Exception) {
            showSnackbar(getString(R.string.unable_to_open_file_emessage))
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.tv_bg_gradient_end))
            .setTextColor(getColor(R.color.tv_text_primary))
            .show()
    }
}

/**
 * Simple RecyclerView adapter for search suggestions.
 * Works for both mobile ([R.layout.item_search_suggestion]) and TV
 * ([R.layout.item_search_suggestion_tv]) — the layout is passed at construction time.
 */
class SuggestionAdapter(
    private val itemLayoutRes: Int,
    private val onClick: (SearchSuggestion) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.VH>() {

    private var items: List<SearchSuggestion> = emptyList()

    fun submitList(list: List<SearchSuggestion>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(itemLayoutRes, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: SearchSuggestion) {
            view.findViewById<TextView>(R.id.txtSuggestion).text = item.text
            view.setOnClickListener { onClick(item) }
        }
    }
}
