package za.kilowatch.ultimatefilemanager.storage

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil3.asImage
import coil3.load
import coil3.request.Disposable
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Scale
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import za.kilowatch.ultimatefilemanager.settings.ThumbnailPreferenceManager
import za.kilowatch.ultimatefilemanager.storage.ViewModeManager
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.settings.IconTapEditModePreferenceManager
import za.kilowatch.ultimatefilemanager.settings.DefaultIconColorManager
import za.kilowatch.ultimatefilemanager.settings.ScrollingTextHelper
import za.kilowatch.ultimatefilemanager.settings.ScrollingTextPreferenceManager
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val VIDEO_EXTENSIONS = listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "ts", "m2ts", "vob", "mpg", "mpeg", "rmvb", "asf", "divx", "xvid")

sealed class ListItem {
    data class Header(val year: Int, val month: Int, val count: Int, val isCollapsed: Boolean = false) : ListItem()
    data class FileEntry(val javaFile: java.io.File) : ListItem()
    data class NetworkEntry(val file: za.kilowatch.ultimatefilemanager.network.NetworkFile) : ListItem()
}

/**
 * RecyclerView adapter for listing files and folders.
 * Supports long-press multi-select with visual checkbox feedback.
 */
class FileAdapter(
    private val isTv: Boolean = false,
    private val isCompact: Boolean = false,
    private val onItemClick: (File, View?) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onItemLongClick: ((File) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private val videoCache = android.util.LruCache<String, android.graphics.Bitmap>(64)

        fun clearCacheForPath(path: String) {
            videoCache.remove(path)
        }

        fun clearCacheForFolder(folderPath: String) {
            val prefix = if (folderPath.endsWith(java.io.File.separator)) folderPath else folderPath + java.io.File.separator
            val keys = videoCache.snapshot().keys
            for (key in keys) {
                if (key == folderPath || key.startsWith(prefix)) {
                    videoCache.remove(key)
                }
            }
        }
    }

    var viewMode: ViewModeManager.ViewMode = ViewModeManager.ViewMode.LIST_MEDIUM
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private var attachedContext: android.content.Context? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedContext = recyclerView.context
    }
    
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedContext = null
    }

    private val files = mutableListOf<File>()
    private val items = mutableListOf<ListItem>()
    var isGroupedByDate = false
    private val selectedPaths = mutableSetOf<String>()
    
    private val storageLabels = mutableMapOf<String, String>()
    private val indexedPaths = mutableSetOf<String>()
    private val hiddenPaths = mutableSetOf<String>()
    private var showAllAsIndexed = false
    private var searchBasePath: String? = null
    private val childCountCache = mutableMapOf<String, Int>()
    private val folderSizeCache = mutableMapOf<String, Long>()
    private var childCountJob: Job? = null

    /**
     * Per-path snapshot of each listed file's metadata (isDirectory / size / lastModified),
     * taken once in [submitList] / [appendList]. The RecyclerView bind path must never
     * re-run `File.stat()` on the main thread — on slow TV storage (or under heavy
     * background file I/O) those per-row stats accumulated past the 5s ANR threshold
     * (sampled at `java.io.UnixFileSystem.getBooleanAttributes0` inside a
     * `LinearLayoutManager` layout). The cache is only touched on the main thread, so no
     * synchronisation is needed.
     */
    private data class FileMeta(val isDirectory: Boolean, val size: Long, val lastModified: Long)

    private var fileMetaCache = mutableMapOf<String, FileMeta>()

    private fun File.isDirectoryCached(): Boolean = fileMetaCache[absolutePath]?.isDirectory ?: isDirectory
    private fun File.lengthCached(): Long = fileMetaCache[absolutePath]?.size ?: length()
    private fun File.lastModifiedCached(): Long = fileMetaCache[absolutePath]?.lastModified ?: lastModified()
    
    var focusedPath: String? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var isSelectionMode = false
        private set

    /**
     * Tracks the adapter position of the last long-pressed item for range selection.
     * When the user long-presses a second file while in selection mode, all file entries
     * between this anchor and the new position are selected. Reset to [RecyclerView.NO_POSITION]
     * on [exitSelectionMode], [deselectAll], and [submitList].
     */
    var longPressAnchorIndex: Int = RecyclerView.NO_POSITION

    fun submitList(
        newFiles: List<File>, 
        indexedPaths: Set<String> = emptySet(), 
        hiddenPaths: Set<String> = emptySet(),
        showAllAsIndexed: Boolean = false,
        storageLabels: Map<String, String> = emptyMap(),
        searchBasePath: String? = null
    ) {
        val filesCopy = newFiles.toList()
        files.clear()
        files.addAll(filesCopy)
        longPressAnchorIndex = RecyclerView.NO_POSITION

        this.indexedPaths.clear()
        this.indexedPaths.addAll(indexedPaths)
        
        this.hiddenPaths.clear()
        this.hiddenPaths.addAll(hiddenPaths)
        this.showAllAsIndexed = showAllAsIndexed

        this.storageLabels.clear()
        this.storageLabels.putAll(storageLabels)
        this.searchBasePath = searchBasePath

        // Snapshot each file's metadata (isDirectory / size / lastModified) once, so the
        // RecyclerView bind path below never re-runs File.stat() on the main thread. The
        // same snapshot is reused for the folders-first / date-grouping ordering here.
        val metaSnapshot = HashMap<String, FileMeta>(filesCopy.size)
        for (f in filesCopy) {
            metaSnapshot[f.absolutePath] = FileMeta(f.isDirectory, f.length(), f.lastModified())
        }
        fileMetaCache = metaSnapshot

        items.clear()
        if (isGroupedByDate) {
            val folders = filesCopy.filter { it.isDirectoryCached() }
            val fileList = filesCopy.filter { !it.isDirectoryCached() }
            
            // Add folders first, ungrouped
            items.addAll(folders.map { ListItem.FileEntry(it) })
            
            val collapsedSet = attachedContext?.let {
                za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.getCollapsedGroups(it)
            } ?: emptySet()
            
            // Group only files by date
            val grouped = fileList.groupBy {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = it.lastModifiedCached()
                Pair(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH))
            }.toSortedMap(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })
            
            for ((key, groupFiles) in grouped) {
                val groupKey = "${key.first}-${key.second}"
                val isCollapsed = collapsedSet.contains(groupKey)
                
                items.add(ListItem.Header(key.first, key.second, groupFiles.size, isCollapsed))
                
                if (!isCollapsed) {
                    items.addAll(groupFiles.map { ListItem.FileEntry(it) })
                }
            }
        } else {
            items.addAll(filesCopy.map { ListItem.FileEntry(it) })
        }

        notifyDataSetChanged()

        // Re-apply selection by stable identifier after the fresh listing lands, and
        // silently drop any selected path that is no longer present (deleted, moved,
        // filtered out, or otherwise removed). This keeps selection intact across a
        // background/foreground reload, while navigation/operation reloads — which
        // already cleared selection before reaching here — remain unaffected.
        if (isSelectionMode) {
            val stillPresent = files.mapTo(HashSet<String>()) { it.absolutePath }
            selectedPaths.retainAll(stillPresent)
            if (selectedPaths.isEmpty()) {
                isSelectionMode = false
                longPressAnchorIndex = RecyclerView.NO_POSITION
            }
            onSelectionChanged(selectedPaths.size)
        }

        // Pre-compute directory child counts and total folder sizes off the main thread
        childCountJob?.cancel()
        val dirs = filesCopy.filter { it.isDirectoryCached() }
        if (dirs.isNotEmpty()) {
            val ctx = attachedContext
            @OptIn(DelicateCoroutinesApi::class)
            childCountJob = GlobalScope.launch(Dispatchers.IO) {
                val counts = mutableMapOf<String, Int>()
                val sizes = mutableMapOf<String, Long>()
                val dao = ctx?.let { za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase.getInstance(it).fileIndexDao() }
                val indexingRepo = try { za.kilowatch.ultimatefilemanager.UfmApplication.indexingRepository } catch (_: Exception) { null }

                for (dir in dirs) {
                    if (!isActive) return@launch
                    val children = dir.list()
                    val visibleCount = children?.count { subName ->
                        !za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isJunkOrHidden(subName) &&
                        File(dir, subName).absolutePath !in hiddenPaths
                    } ?: 0
                    counts[dir.absolutePath] = visibleCount

                    if (dao != null && indexingRepo != null) {
                        val path = dir.absolutePath
                        val (storageId, _, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(path)
                        val isIndexed = showAllAsIndexed || path in indexedPaths || (storageId.isNotEmpty() && indexingRepo.isStorageFullyIndexed(storageId))
                        if (isIndexed) {
                            val totalSize = dao.getFolderTotalSize(storageId, path)
                            if (totalSize != null) {
                                sizes[path] = totalSize
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!isActive) return@withContext
                    childCountCache.putAll(counts)
                    folderSizeCache.putAll(sizes)
                    notifyDataSetChanged()
                }
            }
        }
    }

    /**
     * Appends [newFiles] to the current list without resetting scroll position.
     * Used by category-mode pagination to load the next page as the user scrolls.
     * Hidden-path filtering and showAllAsIndexed are inherited from the last [submitList] call.
     */
    fun appendList(newFiles: List<File>) {
        if (newFiles.isEmpty()) return
        val startPos = items.size
        files.addAll(newFiles)
        // The appended files aren't in the metadata cache yet — snapshot them so the
        // bind path doesn't fall back to File.stat() on the main thread.
        for (f in newFiles) {
            fileMetaCache[f.absolutePath] = FileMeta(f.isDirectory, f.length(), f.lastModified())
        }
        if (isGroupedByDate) {
            // For grouped mode, rebuild the full list (rare in category mode, but safe)
            submitList(files.toList(), indexedPaths.toSet(), hiddenPaths.toSet(), showAllAsIndexed, storageLabels.toMap(), searchBasePath)
        } else {
            items.addAll(newFiles.map { ListItem.FileEntry(it) })
            notifyItemRangeInserted(startPos, newFiles.size)
        }
    }

    fun findPosition(path: String?): Int {
        if (path == null) return -1
        // Normalize path for robust matching (handling different mount points pointing to same place)
        val targetPath = java.io.File(path).absolutePath
        val index = items.indexOfFirst { it is ListItem.FileEntry && it.javaFile.absolutePath == targetPath }
        if (index != -1) return index

        // Fallback 1: match by canonical path to resolve symlinks
        val targetCanonical = try { java.io.File(path).canonicalPath } catch (_: Exception) { targetPath }
        val canonicalIndex = items.indexOfFirst {
            if (it !is ListItem.FileEntry) return@indexOfFirst false
            val itemCanonical = try { it.javaFile.canonicalPath } catch (_: Exception) { it.javaFile.absolutePath }
            itemCanonical == targetCanonical
        }
        if (canonicalIndex != -1) return canonicalIndex

        // Fallback 2: compare by name (since the exited directory must be a direct child in the loaded folder list)
        val targetName = java.io.File(path).name
        return items.indexOfFirst { it is ListItem.FileEntry && it.javaFile.name.equals(targetName, ignoreCase = true) }
    }

    /** Returns all currently selected files. */
    fun getSelectedFiles(): List<File> = files.filter { it.absolutePath in selectedPaths }

    fun hasAnySelectedHidden(): Boolean = selectedPaths.any { it in hiddenPaths }
    fun hasAnySelectedVisible(): Boolean = selectedPaths.any { it !in hiddenPaths }

    fun hasAnySelectedProtected(context: android.content.Context): Boolean = selectedPaths.any { za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isProtected(context, it) }
    fun hasAnySelectedUnprotected(context: android.content.Context): Boolean = selectedPaths.any { !za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isProtected(context, it) }

    fun hasAnySelectedPinned(context: android.content.Context): Boolean = selectedPaths.any { za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(context, it) }
    fun hasAnySelectedUnpinned(context: android.content.Context): Boolean = selectedPaths.any { !za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(context, it) }

    /** Select all items in the current list. */
    fun selectAll() {
        isSelectionMode = true
        selectedPaths.clear()
        longPressAnchorIndex = RecyclerView.NO_POSITION
        files.forEach { selectedPaths.add(it.absolutePath) }
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selectedPaths.size)
    }

    /** Whether every item is currently selected. */
    fun isAllSelected(): Boolean = files.isNotEmpty() && selectedPaths.size == files.size

    /** Invert the current selection over the visible listing, staying in selection mode. */
    fun invertSelection() {
        files.forEach {
            val path = it.absolutePath
            if (path in selectedPaths) selectedPaths.remove(path) else selectedPaths.add(path)
        }
        longPressAnchorIndex = RecyclerView.NO_POSITION
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selectedPaths.size)
    }

    /** Deselect all while staying in selection mode. */
    fun deselectAll() {
        selectedPaths.clear()
        longPressAnchorIndex = RecyclerView.NO_POSITION
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(0)
    }

    /** Clear all selections and exit selection mode. */
    fun exitSelectionMode() {
        selectedPaths.clear()
        isSelectionMode = false
        longPressAnchorIndex = RecyclerView.NO_POSITION
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(0)
    }

    /**
     * Enter selection mode and select the item at [position].
     * Called by the TV RecyclerView key listener on D-pad long-press.
     *
     * Behaviour depends on the current selection state:
     * - Not in selection mode → enter mode, set anchor, select the file.
     * - In selection mode + no anchor → set anchor, select the file.
     * - In selection mode + valid anchor → range-select from anchor to [position],
     *   then update the anchor to [position].
     */
    fun enterSelectionModeAt(position: Int) {
        if (position < 0 || position >= items.size) return
        val item = items[position] as? ListItem.FileEntry ?: return
        val file = item.javaFile
        if (!isSelectionMode) {
            isSelectionMode = true
            longPressAnchorIndex = position
            selectedPaths.add(file.absolutePath)
        } else if (longPressAnchorIndex == RecyclerView.NO_POSITION) {
            // Already in selection mode but no anchor (e.g. after selectAll/deselectAll)
            longPressAnchorIndex = position
            selectedPaths.add(file.absolutePath)
        } else {
            // Already in selection mode with an anchor — do range selection
            selectRange(longPressAnchorIndex, position)
            longPressAnchorIndex = position
        }
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selectedPaths.size)
    }

    /**
     * Selects every file entry between [fromPos] and [toPos] in the adapter's [items] list,
     * inclusive of both endpoints. Date-group headers ([ListItem.Header]) are silently skipped.
     * Works in both directions (order of arguments does not matter).
     */
    private fun selectRange(fromPos: Int, toPos: Int) {
        val start = minOf(fromPos, toPos)
        val end = maxOf(fromPos, toPos)
        for (i in start..end) {
            val entry = items[i]
            if (entry is ListItem.FileEntry) {
                selectedPaths.add(entry.javaFile.absolutePath)
            }
            // Headers (ListItem.Header) are automatically skipped
        }
    }

    private fun toggleSelection(file: File) {
        val path = file.absolutePath
        if (path in selectedPaths) {
            selectedPaths.remove(path)
        } else {
            selectedPaths.add(path)
        }
        onSelectionChanged(selectedPaths.size)
    }

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        if (item is ListItem.Header) return 3
        val isGrid = ViewModeManager.isGrid(viewMode)
        return when {
            isGrid    -> 1             // grid layout
            isCompact -> 2             // compact list (vertical-split twin window)
            else      -> 0             // list layout (can be TV list or mobile list)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 3) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_date_group_header, parent, false)
            return HeaderViewHolder(view)
        }
        val layoutRes = when {
            viewType == 1 -> R.layout.item_file_grid
            isTv         -> R.layout.item_file_tv
            viewType == 2 -> R.layout.item_file_compact
            else         -> R.layout.item_file
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderViewHolder && item is ListItem.Header) {
            holder.bind(item)
        } else if (holder is FileViewHolder && item is ListItem.FileEntry) {
            holder.bind(item.javaFile)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtYear: TextView = itemView.findViewById(R.id.txtYear)
        private val txtMonth: TextView = itemView.findViewById(R.id.txtMonth)
        private val txtCount: TextView = itemView.findViewById(R.id.txtCount)

        fun bind(header: ListItem.Header) {
            txtYear.text = header.year.toString()
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.MONTH, header.month)
            txtMonth.text = SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time)
            txtCount.text = itemView.context.getString(R.string.group_header_format_files, header.count)
            
            val imgCollapseToggle = itemView.findViewById<android.widget.ImageView>(R.id.imgCollapseToggle)
            if (isTv) {
                imgCollapseToggle?.visibility = View.GONE
                itemView.setOnClickListener(null)
                itemView.isFocusable = false
            } else {
                imgCollapseToggle?.visibility = View.VISIBLE
                imgCollapseToggle?.setImageResource(if (header.isCollapsed) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up)
                
                itemView.setOnClickListener {
                    val ctx = itemView.context
                    val pm = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager
                    val currentCollapsed = pm.getCollapsedGroups(ctx).toMutableSet()
                    val key = "${header.year}-${header.month}"
                    
                    if (header.isCollapsed) {
                        currentCollapsed.remove(key)
                    } else {
                        currentCollapsed.add(key)
                    }
                    pm.setCollapsedGroups(ctx, currentCollapsed)
                    
                    // Rebuild flat list
                    submitList(files, indexedPaths, hiddenPaths, showAllAsIndexed, storageLabels, searchBasePath)
                }
            }
        }
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgFileIcon)
        private val txtName: TextView = itemView.findViewById(R.id.txtFileName)
        private val txtInfo: TextView = itemView.findViewById(R.id.txtFileInfo)
        private val txtSize: TextView = itemView.findViewById(R.id.txtFileSize)
        private val checkSelect: CheckBox = itemView.findViewById(R.id.checkSelect)
        private val layoutRow: View = itemView.findViewById(R.id.layoutFileRow)
        private val imgIndexedBadge: ImageView = itemView.findViewById(R.id.imgIndexedBadge)
        // iconContainer has a transitionName set dynamically per position for shared element transitions
        private val iconContainer: View? = itemView.findViewById(R.id.iconContainer)

        /**
         * Tracks the currently in-flight Coil image request for this ViewHolder.
         * Disposed at the start of every bind() so a stale async thumbnail load
         * can never overwrite a folder/file icon set via setImageResource().
         */
        private var coilDisposable: Disposable? = null

        /**
         * Tracks a background coroutine extracting a video frame.
         * Cancelled alongside coilDisposable at the start of every bind()
         * so a slow extraction never lands on a recycled ViewHolder.
         */
        private var videoJob: kotlinx.coroutines.Job? = null

        private fun startPulse() {
            val anim = android.view.animation.AlphaAnimation(1.0f, 0.4f).apply {
                duration = 600
                repeatCount = android.view.animation.Animation.INFINITE
                repeatMode = android.view.animation.Animation.REVERSE
            }
            imgIcon.startAnimation(anim)
        }

        private fun stopPulse() {
            imgIcon.clearAnimation()
            imgIcon.alpha = 1.0f
        }

        fun bind(file: File) {
            val context = itemView.context
            val isGrid = ViewModeManager.isGrid(viewMode)

            val ext = file.extension.lowercase()
            val isImage = ext in listOf("jpg", "jpeg", "png", "bmp", "webp", "gif", "heic", "heif", "avif", "jxl")
            val isVideo = ext in VIDEO_EXTENSIONS
            val isApk = ext in listOf("apk", "xapk", "apks")
            val showThumbnails = ThumbnailPreferenceManager.isEnabled(context)
            val isThumbnail = !file.isDirectoryCached() && showThumbnails && (isImage || isVideo || isApk)

            // Cancel any in-flight Coil request or video-frame extraction from a previous bind.
            coilDisposable?.dispose()
            coilDisposable = null
            videoJob?.cancel()
            videoJob = null

            // Apply dynamic list mode scaling
            if (!isGrid) {
                itemView.minimumHeight = 0
                val density = context.resources.displayMetrics.density
                val heightDp = when (viewMode) {
                    ViewModeManager.ViewMode.LIST_SMALL -> 48
                    ViewModeManager.ViewMode.LIST_MEDIUM -> 64
                    ViewModeManager.ViewMode.LIST_LARGE -> 80
                    ViewModeManager.ViewMode.LIST_XLARGE -> 96
                    else -> 64
                }
                val iconSizeDp = when (viewMode) {
                    ViewModeManager.ViewMode.LIST_SMALL -> 36
                    ViewModeManager.ViewMode.LIST_MEDIUM -> 48
                    ViewModeManager.ViewMode.LIST_LARGE -> 56
                    ViewModeManager.ViewMode.LIST_XLARGE -> 64
                    else -> 48
                }
                val titleSp = when (viewMode) {
                    ViewModeManager.ViewMode.LIST_SMALL -> 14f
                    ViewModeManager.ViewMode.LIST_MEDIUM -> 16f
                    ViewModeManager.ViewMode.LIST_LARGE -> 18f
                    ViewModeManager.ViewMode.LIST_XLARGE -> 20f
                    else -> 16f
                }
                val subtitleSp = when (viewMode) {
                    ViewModeManager.ViewMode.LIST_SMALL -> 11f
                    ViewModeManager.ViewMode.LIST_MEDIUM -> 12f
                    ViewModeManager.ViewMode.LIST_LARGE -> 13f
                    ViewModeManager.ViewMode.LIST_XLARGE -> 14f
                    else -> 12f
                }

                itemView.minimumHeight = (heightDp * density + 0.5f).toInt()
                val params = itemView.layoutParams
                if (params != null) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    itemView.layoutParams = params
                }

                iconContainer?.let { container ->
                    val iconParams = container.layoutParams as? ViewGroup.MarginLayoutParams
                    if (iconParams != null) {
                        val rowHeightPx = (heightDp * density + 0.5f).toInt()
                        val marginPx = (3 * density + 0.5f).toInt()
                        val containerSizePx = rowHeightPx - 2 * marginPx
                        iconParams.width = containerSizePx
                        iconParams.height = containerSizePx
                        iconParams.topMargin = marginPx
                        iconParams.bottomMargin = marginPx
                        container.layoutParams = iconParams
                    }
                }

                txtName.textSize = titleSp
                txtInfo.textSize = subtitleSp
                txtSize.textSize = subtitleSp
            }

            // Assign a stable, position-specific transitionName to the icon container
            // so each item has a unique shared element name for the image viewer transition.
            val pos = bindingAdapterPosition
            val tn = if (pos != RecyclerView.NO_POSITION) "file_icon_$pos" else null
            iconContainer?.transitionName = tn

            // ── RESET ALL CONDITIONALLY-SET STATE ──────────────────────────────
            // Must be done unconditionally before any branching so that recycled
            // ViewHolders never carry stale values from a previous bind.
            txtName.text = file.name
            var isDisplayingThumbnail = false

            val base = searchBasePath
            var relativePath: String? = null
            if (base != null && file.absolutePath.startsWith(base)) {
                val relative = file.absolutePath.substring(base.length).removePrefix("/")
                if (relative.contains("/")) {
                    relativePath = relative.substringBeforeLast("/")
                }
            }

            txtInfo.text = ""
            txtSize.text = ""
            txtSize.visibility = View.GONE
            imgIndexedBadge.visibility = View.GONE
            stopPulse()
            imgIcon.imageTintList = null
            imgIcon.scaleType = if (isGrid) {
                if (file.isDirectoryCached() || isApk || (!isImage && !isVideo)) {
                    ImageView.ScaleType.FIT_CENTER
                } else {
                    ImageView.ScaleType.CENTER_CROP
                }
            } else {
                ImageView.ScaleType.FIT_CENTER
            }
            imgIcon.clipToOutline = false
            itemView.findViewById<View>(R.id.viewSelectionOverlay)?.visibility = View.GONE

            // Remove all background circles and padding for consistency across grid and list
            imgIcon.setPadding(0, 0, 0, 0)
            iconContainer?.setBackgroundResource(0)

            if (file.isDirectoryCached()) {
                imgIcon.setImageResource(IconCustomizationManager.getEffectiveIconRes(context, "folder_default", R.drawable.ic_folder))
                val tintColor = if (isTv) {
                    DefaultIconColorManager.getTvIconTint(context)
                } else {
                    DefaultIconColorManager.getMobileIconTint(context)
                }
                imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
                if (!isGrid) {
                    val childCount = childCountCache[file.absolutePath] ?: 0
                    val itemsText = "$childCount item${if (childCount != 1) "s" else ""}"
                    val (storageId, _, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(file.absolutePath)
                    val isIndexed = showAllAsIndexed || file.absolutePath in indexedPaths || (storageId.isNotEmpty() && za.kilowatch.ultimatefilemanager.UfmApplication.indexingRepository.isStorageFullyIndexed(storageId))
                    val folderSize = folderSizeCache[file.absolutePath]
                    val sizeText = if (isIndexed && folderSize != null) {
                        Formatter.formatFileSize(context, folderSize)
                    } else null

                    val baseInfo = if (sizeText != null) {
                        "$itemsText · $sizeText · ${formatDate(context, file.lastModifiedCached())}"
                    } else {
                        "$itemsText · ${formatDate(context, file.lastModifiedCached())}"
                    }
                    val storage = storageLabels[file.absolutePath]
                    val detailedInfo = if (storage != null) "$storage · $baseInfo" else baseInfo
                    txtInfo.text = if (relativePath != null) "$relativePath\n$detailedInfo" else detailedInfo
                }
            } else {
                val ext = file.extension.lowercase()
                val isImage = ext in listOf("jpg", "jpeg", "png", "bmp", "webp", "gif", "heic", "heif", "avif", "jxl")
                val isVideo = ext in VIDEO_EXTENSIONS
                val isApk = ext in listOf("apk", "xapk", "apks")
                val showThumbnails = ThumbnailPreferenceManager.isEnabled(context)

                if (!isGrid && showThumbnails && (isImage || isVideo || isApk)) {
                    // ── Thumbnail mode ────────────────────────────────────────
                    // Zero out image padding and clear the circle bg so the
                    // thumbnail crops to fill the full row height.
                    iconContainer?.setBackgroundResource(0)
                    imgIcon.setPadding(0, 0, 0, 0)
                    imgIcon.scaleType = if (isApk) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    imgIcon.imageTintList = null
                    
                    imgIcon.clipToOutline = true
                    imgIcon.outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            val radius = 10f * view.context.resources.displayMetrics.density
                            outline.setRoundRect(0, 0, view.width, view.height, radius)
                        }
                    }

                    isDisplayingThumbnail = true
                    loadListThumbnail(file, isImage, isApk)

                    val baseDate = formatDate(context, file.lastModifiedCached())
                    val storage = storageLabels[file.absolutePath]
                    val detailedInfo = if (storage != null) "$storage · $baseDate" else baseDate
                    txtInfo.text = if (relativePath != null) "$relativePath\n$detailedInfo" else detailedInfo
                    txtSize.text = Formatter.formatFileSize(context, file.lengthCached())
                    txtSize.visibility = View.VISIBLE
                } else if (!isGrid) {
                    // ── Normal icon mode (list) ───────────────────────────────
                    iconContainer?.setBackgroundResource(0)
                    imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                    val tintColor = if (isTv) {
                        DefaultIconColorManager.getTvIconTint(context)
                    } else {
                        DefaultIconColorManager.getMobileIconTint(context)
                    }
                    imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
                    val baseDate = formatDate(context, file.lastModifiedCached())
                    val storage = storageLabels[file.absolutePath]
                    val detailedInfo = if (storage != null) "$storage · $baseDate" else baseDate
                    txtInfo.text = if (relativePath != null) "$relativePath\n$detailedInfo" else detailedInfo
                    txtSize.text = Formatter.formatFileSize(context, file.lengthCached())
                    txtSize.visibility = View.VISIBLE
                } else {
                    if (showThumbnails && (isImage || isVideo || isApk)) {
                        iconContainer?.setBackgroundResource(0)
                        imgIcon.setPadding(0, 0, 0, 0)
                    }
                    loadThumbnail(file)
                }
            }

            // ── Indexed badge, hidden badge, selection, click handling ─────────
            // Must run for BOTH directories and files.

            // Indexed status: lightning bolt badge
            val isIndexed = showAllAsIndexed || file.absolutePath in indexedPaths
            imgIndexedBadge.visibility = if (isIndexed) View.VISIBLE else View.GONE

            // Hidden status indicator
            val isItemHidden = file.absolutePath in hiddenPaths
            itemView.findViewById<ImageView>(R.id.imgHiddenBadge)?.visibility = if (isItemHidden) View.VISIBLE else View.GONE

            // Pinned status indicator
            val isItemPinned = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(context, file.absolutePath)
            itemView.findViewById<ImageView>(R.id.imgPinnedBadge)?.visibility = if (isItemPinned) View.VISIBLE else View.GONE

            // Protected status indicator
            val isItemProtected = za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isProtected(context, file.absolutePath)
            itemView.findViewById<ImageView>(R.id.imgProtectedBadge)?.visibility = if (isItemProtected) View.VISIBLE else View.GONE

            // Selection state
            val isSelected = file.absolutePath in selectedPaths
            checkSelect.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            checkSelect.isChecked = isSelected

            // Highlight selected rows / cards
            val isFocused = file.absolutePath == focusedPath
            if (isSelected || isFocused) {
                if (isGrid) {
                    itemView.findViewById<View>(R.id.viewSelectionOverlay)?.visibility = View.VISIBLE
                } else {
                    val colorRes = if (isFocused) R.color.tv_button_focused_yellow_glow else R.color.ufm_selection_highlight
                    layoutRow.setBackgroundColor(ContextCompat.getColor(context, colorRes))
                }
            } else {
                if (isGrid) {
                    itemView.findViewById<View>(R.id.viewSelectionOverlay)?.visibility = View.GONE
                } else {
                    layoutRow.setBackgroundColor(0x00000000) // transparent
                }
            }

            // Icon tap to enter edit/selection mode (Mobile List view only)
            val targetIconView = iconContainer ?: itemView.findViewById<View>(R.id.imgFileIcon)
            if (!isTv && !isGrid && IconTapEditModePreferenceManager.isEnabled(context)) {
                targetIconView?.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val item = items[pos]
                        if (item is ListItem.FileEntry) {
                            val currentFile = item.javaFile
                            if (onItemLongClick != null) {
                                onItemLongClick.invoke(currentFile)
                            } else if (!isSelectionMode) {
                                isSelectionMode = true
                                longPressAnchorIndex = pos
                                selectedPaths.add(currentFile.absolutePath)
                                notifyItemRangeChanged(0, itemCount)
                                onSelectionChanged(selectedPaths.size)
                            } else {
                                val wasSelected = currentFile.absolutePath in selectedPaths
                                toggleSelection(currentFile)
                                if (!wasSelected) {
                                    longPressAnchorIndex = pos
                                }
                                notifyItemChanged(pos)
                            }
                        }
                    }
                }
            } else {
                targetIconView?.setOnClickListener(null)
                targetIconView?.isClickable = false
            }

            // Click handling
            itemView.setOnClickListener {

                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos]
                    if (item is ListItem.FileEntry) {
                        val currentFile = item.javaFile
                        if (isSelectionMode) {
                            val wasSelected = currentFile.absolutePath in selectedPaths
                            toggleSelection(currentFile)
                            if (!wasSelected) {
                                // File was newly added to the selection — update the anchor
                                // so the next long press ranges from this position.
                                longPressAnchorIndex = pos
                            }
                            notifyItemChanged(pos)
                        } else {
                            // Pass the icon container as the shared element view for image transitions
                            onItemClick(currentFile, iconContainer)
                        }
                    }
                }
            }

            // Long-press to enter selection mode, set anchor, or do range selection.
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos]
                    if (item is ListItem.FileEntry) {
                        val currentFile = item.javaFile
                        if (onItemLongClick != null) {
                            // Delegated (SearchActivity context menu path)
                            onItemLongClick.invoke(currentFile)
                        } else if (!isSelectionMode) {
                            // First long press: enter selection mode, set anchor, select file
                            isSelectionMode = true
                            longPressAnchorIndex = pos
                            selectedPaths.add(currentFile.absolutePath)
                            notifyItemRangeChanged(0, itemCount)
                            onSelectionChanged(selectedPaths.size)
                        } else if (longPressAnchorIndex == RecyclerView.NO_POSITION) {
                            // Already in selection mode but no anchor (e.g. after selectAll/deselectAll)
                            longPressAnchorIndex = pos
                            selectedPaths.add(currentFile.absolutePath)
                            notifyItemRangeChanged(0, itemCount)
                            onSelectionChanged(selectedPaths.size)
                        } else if (longPressAnchorIndex != RecyclerView.NO_POSITION) {
                            // Second (or subsequent) long press while in selection mode:
                            // range-select from anchor to this position, then update anchor
                            selectRange(longPressAnchorIndex, pos)
                            longPressAnchorIndex = pos
                            notifyItemRangeChanged(0, itemCount)
                            onSelectionChanged(selectedPaths.size)
                        }
                    }
                }
                true
            }

            // TV: black text/icons on focus (yellow bg handled by selector_tv_list_item)
            if (isTv) {
                val ctx = itemView.context
                val black     = ctx.getColor(R.color.tv_button_focused_yellow_text)
                val white     = ctx.getColor(R.color.tv_text_primary)
                val secondary = ctx.getColor(R.color.tv_text_secondary)
                val hint      = ctx.getColor(R.color.tv_text_hint)
                val accent    = DefaultIconColorManager.getTvIconTint(ctx)
                val blackCsl  = android.content.res.ColorStateList.valueOf(black)
                val accentCsl = android.content.res.ColorStateList.valueOf(accent)

                if (isGrid) {
                    itemView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardFile)?.foreground =
                        ContextCompat.getDrawable(ctx, R.drawable.selector_tv_card)
                }

                itemView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        if (!isGrid) {
                            txtName.setTextColor(black)
                            txtInfo.setTextColor(black)
                            txtSize.setTextColor(black)
                        }
                        if (!isThumbnail) imgIcon.imageTintList = blackCsl
                    } else {
                        if (isGrid) {
                            applyGridTextColor(file)
                        } else {
                            txtName.setTextColor(white)
                            txtInfo.setTextColor(secondary)
                            txtSize.setTextColor(hint)
                        }
                        if (!isThumbnail) imgIcon.imageTintList = accentCsl
                    }
                }
            }

            // ── Scrolling text for long file names (list only, not grid) ─────
            if (!isGrid) {
                val scrollingEnabled = ScrollingTextPreferenceManager.isEnabled(context)
                ScrollingTextHelper.applyScrollingText(txtName, scrollingEnabled)
            }

            if (isGrid) {
                applyGridTextColor(file)
            }
        }


        /**
         * Loads a thumbnail for the list view using Coil.
         *
         * - Images: Coil loads the file directly from disk (memory + disk cached).
         * - Videos: a frame is extracted on Dispatchers.IO via ThumbnailUtils /
         *   MediaMetadataRetriever, then the resulting Bitmap is fed back to Coil
         *   on the main thread.  A tag guard prevents stale frames landing on a
         *   recycled ViewHolder.
         */
        private fun loadListThumbnail(file: File, isImage: Boolean, isApk: Boolean) {
            val placeholderImage = ContextCompat.getDrawable(itemView.context, R.drawable.ic_photo_video)?.asImage()

            if (isImage) {
                // Coil can decode images natively — fast path.
                coilDisposable = imgIcon.load(file) {
                    crossfade(200)
                    allowHardware(false)
                    scale(Scale.FILL)
                    placeholder(placeholderImage)
                    error(placeholderImage)
                }
            } else if (isApk) {
                // APK / XAPK / APKS: extract the app icon via the shared helper
                imgIcon.tag = file.absolutePath
                imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                startPulse()

                @OptIn(DelicateCoroutinesApi::class)
                videoJob = GlobalScope.launch(Dispatchers.IO) {
                    val drawable = resolveApkIcon(file)

                    withContext(Dispatchers.Main) {
                        if (imgIcon.tag == file.absolutePath) {
                            stopPulse()
                            if (drawable != null) {
                                coilDisposable = imgIcon.load(drawable) {
                                    crossfade(150)
                                    allowHardware(false)
                                }
                            }
                        }
                    }
                }
            } else {
                // Video: extract a frame at the configured percentage into the video.
                imgIcon.tag = file.absolutePath
                val cached = videoCache.get(file.absolutePath)
                if (cached != null) {
                    imgIcon.setImageBitmap(cached)
                } else {
                    imgIcon.setImageDrawable(
                        ContextCompat.getDrawable(itemView.context, R.drawable.ic_photo_video)
                    )
                    startPulse()

                    @OptIn(DelicateCoroutinesApi::class)
                    videoJob = GlobalScope.launch(Dispatchers.IO) {
                        val pct = za.kilowatch.ultimatefilemanager.settings.VideoThumbnailTimePreferenceManager.getPercent(itemView.context)
                        var bitmap: android.graphics.Bitmap? = za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(
                            file.absolutePath, pct, 512, 512
                        )

                        if (bitmap == null) {
                            bitmap = try {
                                val retriever = android.media.MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(file.absolutePath)
                                    val durationMs = retriever.extractMetadata(
                                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                                    )?.toLongOrNull() ?: 0L
                                    val durationUs = durationMs * 1000L
                                    val timeUs = if (durationUs > 0) durationUs * pct / 100L else 0L
                                    val raw = retriever.getFrameAtTime(
                                        timeUs,
                                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                                    )
                                    if (raw != null) {
                                        val maxPx = 512
                                        val w = raw.width; val h = raw.height
                                        if (w <= maxPx && h <= maxPx) raw else {
                                            val scale = maxPx.toFloat() / maxOf(w, h)
                                            android.graphics.Bitmap.createScaledBitmap(raw,
                                                (w * scale).toInt().coerceAtLeast(1),
                                                (h * scale).toInt().coerceAtLeast(1), true)
                                        }
                                    } else null
                                } finally {
                                    try { retriever.release() } catch (_: Exception) {}
                                }
                            } catch (_: Throwable) { null }
                        }

                        withContext(Dispatchers.Main) {
                            if (imgIcon.tag == file.absolutePath) {
                                stopPulse()
                                if (bitmap != null) {
                                    videoCache.put(file.absolutePath, bitmap)
                                    coilDisposable = imgIcon.load(bitmap) {
                                        crossfade(150)
                                        allowHardware(false)
                                        scale(Scale.FILL)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        /**
         * Resolves the launcher icon for any APK-like archive.
         *
         * - Standard `.apk`: parsed directly by [android.content.pm.PackageManager].
         * - `.xapk` / `.apks` (multi-APK ZIPs):
         *   1. Tries to decode a root-level `icon.png` embedded in the ZIP (fast, common in
         *      XAPKs distributed by APKPure and similar sources).
         *   2. Falls back to extracting `base.apk` to a temp file and letting
         *      [android.content.pm.PackageManager] parse that.
         *
         * Returns `null` if no icon can be resolved (the caller keeps the generic icon).
         */
        private suspend fun resolveApkIcon(file: File): android.graphics.drawable.Drawable? =
            withContext(Dispatchers.IO) {
                val ext = file.extension.lowercase()
                val pm = itemView.context.packageManager

                if (ext == "apk") {
                    // Standard APK — PackageManager can parse it directly.
                    try {
                        val pi = pm.getPackageArchiveInfo(file.absolutePath, 0)
                        if (pi != null) {
                            pi.applicationInfo?.sourceDir = file.absolutePath
                            pi.applicationInfo?.publicSourceDir = file.absolutePath
                            pi.applicationInfo?.loadIcon(pm)
                        } else null
                    } catch (_: Exception) { null }
                } else {
                    // XAPK / APKS — multi-APK ZIP format; PackageManager can't parse directly.

                    // Strategy 1: look for a root-level icon.png embedded in the ZIP.
                    // This is the fastest path (single entry read) and works for most XAPKs
                    // distributed through APKPure and similar stores.
                    val iconBitmap: android.graphics.Bitmap? = try {
                        java.util.zip.ZipFile(file).use { zip ->
                            val entry = zip.getEntry("icon.png")
                            if (entry != null) {
                                android.graphics.BitmapFactory.decodeStream(zip.getInputStream(entry))
                            } else null
                        }
                    } catch (_: Exception) { null }

                    if (iconBitmap != null) {
                        android.graphics.drawable.BitmapDrawable(itemView.context.resources, iconBitmap)
                    } else {
                        // Strategy 2: extract base.apk to a temp file and let PackageManager parse it.
                        var tempApk: File? = null
                        try {
                            tempApk = File(
                                itemView.context.cacheDir,
                                "xapk_base_${System.currentTimeMillis()}.apk"
                            )
                            java.util.zip.ZipFile(file).use { zip ->
                                val entry = zip.getEntry("base.apk")
                                if (entry != null) {
                                    zip.getInputStream(entry).use { input ->
                                        tempApk.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }
                            }
                            if (tempApk.exists() && tempApk.length() > 0L) {
                                val pi = pm.getPackageArchiveInfo(tempApk.absolutePath, 0)
                                if (pi != null) {
                                    pi.applicationInfo?.sourceDir = tempApk.absolutePath
                                    pi.applicationInfo?.publicSourceDir = tempApk.absolutePath
                                    pi.applicationInfo?.loadIcon(pm)
                                } else null
                            } else null
                        } catch (_: Exception) { null } finally {
                            tempApk?.delete()
                        }
                    }
                }
            }

        /**
         * Loads a thumbnail for grid view using Coil.
         * Images load natively; videos use the same frame-extraction path as list view.
         */
        private fun loadThumbnail(file: File) {
            val ext = file.extension.lowercase()
            val isImage = ext in listOf("jpg", "jpeg", "png", "bmp", "webp", "gif", "heic", "heif", "avif", "jxl")
            val isVideo = ext in VIDEO_EXTENSIONS
            val isApk = ext in listOf("apk", "xapk", "apks")

            if (!isImage && !isVideo && !isApk) {
                imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                imgIcon.imageTintList = null
                imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                return
            }

            // For thumbnails in grid, clear background and padding
            iconContainer?.setBackgroundResource(0)
            imgIcon.setPadding(0, 0, 0, 0)

            imgIcon.clipToOutline = true
            imgIcon.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    val radius = 10f * view.context.resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }

            val placeholderImage = ContextCompat.getDrawable(itemView.context, R.drawable.ic_photo_video)?.asImage()

            if (isImage) {
                coilDisposable = imgIcon.load(file) {
                    crossfade(150)
                    allowHardware(false)
                    scale(Scale.FILL)
                    placeholder(placeholderImage)
                    error(placeholderImage)
                    listener(
                        onSuccess = { _, _ ->
                            updateTextColorForDrawable(imgIcon.drawable, true)
                        }
                    )
                }
            } else if (isApk) {
                // APK / XAPK / APKS: extract the app icon via the shared helper
                imgIcon.tag = file.absolutePath
                imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                startPulse()

                @OptIn(DelicateCoroutinesApi::class)
                videoJob = GlobalScope.launch(Dispatchers.IO) {
                    val drawable = resolveApkIcon(file)

                    withContext(Dispatchers.Main) {
                        if (imgIcon.tag == file.absolutePath) {
                            stopPulse()
                            if (drawable != null) {
                                coilDisposable = imgIcon.load(drawable) {
                                    crossfade(150)
                                    allowHardware(false)
                                    listener(
                                        onSuccess = { _, _ ->
                                            updateTextColorForDrawable(imgIcon.drawable, true)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Video: extract frame on background thread (same as list path).
                imgIcon.tag = file.absolutePath
                val cached = videoCache.get(file.absolutePath)
                if (cached != null) {
                    imgIcon.setImageBitmap(cached)
                    updateTextColorForDrawable(imgIcon.drawable, true)
                } else {
                    imgIcon.setImageDrawable(
                        ContextCompat.getDrawable(itemView.context, R.drawable.ic_photo_video)
                    )
                    startPulse()
                    
                    @OptIn(DelicateCoroutinesApi::class)
                    videoJob = GlobalScope.launch(Dispatchers.IO) {
                        val pct = za.kilowatch.ultimatefilemanager.settings.VideoThumbnailTimePreferenceManager.getPercent(itemView.context)
                        var bitmap: android.graphics.Bitmap? = za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(
                            file.absolutePath, pct, 480, 480
                        )

                        if (bitmap == null) {
                            bitmap = try {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    android.media.ThumbnailUtils.createVideoThumbnail(
                                        file, android.util.Size(480, 480), null
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    android.media.ThumbnailUtils.createVideoThumbnail(
                                        file.absolutePath,
                                        android.provider.MediaStore.Video.Thumbnails.MINI_KIND
                                    )
                                }
                            } catch (_: Throwable) { null }
                        }

                        withContext(Dispatchers.Main) {
                            if (imgIcon.tag == file.absolutePath) {
                                stopPulse()
                                if (bitmap != null) {
                                    videoCache.put(file.absolutePath, bitmap)
                                    coilDisposable = imgIcon.load(bitmap) {
                                        crossfade(150)
                                        allowHardware(false)
                                        scale(Scale.FILL)
                                        listener(
                                            onSuccess = { _, _ ->
                                                updateTextColorForDrawable(imgIcon.drawable, true)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun formatDate(context: android.content.Context, timestamp: Long): String {
            if (timestamp <= 0) return ""
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < 60_000 -> context.getString(R.string.just_now)
                diff < 3_600_000 -> "${diff / 60_000}m ago"
                diff < 86_400_000 -> "${diff / 3_600_000}h ago"
                diff < 604_800_000 -> "${diff / 86_400_000}d ago"
                else -> SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
            }
        }

        private fun updateTextColorForDrawable(drawable: android.graphics.drawable.Drawable?, isThumbnail: Boolean) {
            val context = itemView.context
            if (!isThumbnail) {
                val themeColor = ContextCompat.getColor(context, if (isTv) R.color.tv_text_primary else R.color.mobile_card_text_primary)
                txtName.setTextColor(themeColor)
                return
            }
            if (drawable == null) {
                txtName.setTextColor(0xFFFFFFFF.toInt())
                return
            }
            try {
                val width = 16
                val height = 16
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                val savedBounds = drawable.copyBounds()
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                drawable.bounds = savedBounds

                val bgRed = 0x12
                val bgGreen = 0x12
                val bgBlue = 0x12

                val startRow = 10
                val endRow = 15
                var totalLuminance = 0.0
                var count = 0

                for (y in startRow..endRow) {
                    for (x in 0 until width) {
                        val pixel = bitmap.getPixel(x, y)
                        val alpha = android.graphics.Color.alpha(pixel)
                        val r = android.graphics.Color.red(pixel)
                        val g = android.graphics.Color.green(pixel)
                        val b = android.graphics.Color.blue(pixel)

                        val finalR = (r * alpha + bgRed * (255 - alpha)) / 255
                        val finalG = (g * alpha + bgGreen * (255 - alpha)) / 255
                        val finalB = (b * alpha + bgBlue * (255 - alpha)) / 255

                        val luminance = 0.2126 * finalR + 0.7152 * finalG + 0.0722 * finalB
                        totalLuminance += luminance
                        count++
                    }
                }
                bitmap.recycle()

                val avgLuminance = if (count > 0) totalLuminance / count else 0.0
                if (avgLuminance > 135.0) {
                    txtName.setTextColor(0xDE000000.toInt())
                } else {
                    txtName.setTextColor(0xFFFFFFFF.toInt())
                }
            } catch (e: Exception) {
                txtName.setTextColor(0xFFFFFFFF.toInt())
            }
        }

        private fun applyGridTextColor(file: File) {
            val ext = file.extension.lowercase()
            val isImage = ext in listOf("jpg", "jpeg", "png", "bmp", "webp", "gif", "heic", "heif", "avif", "jxl")
            val isVideo = ext in VIDEO_EXTENSIONS
            val isApk = ext in listOf("apk", "xapk", "apks")
            val showThumbnails = ThumbnailPreferenceManager.isEnabled(itemView.context)
            val hasThumbnail = !file.isDirectoryCached() && showThumbnails && (isImage || isVideo || isApk)

            updateTextColorForDrawable(imgIcon.drawable, hasThumbnail && imgIcon.drawable != null && imgIcon.tag == file.absolutePath)
        }
    }
}
