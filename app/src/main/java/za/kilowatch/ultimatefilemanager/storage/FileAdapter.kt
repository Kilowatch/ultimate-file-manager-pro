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
import coil3.size.Precision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import za.kilowatch.ultimatefilemanager.settings.ThumbnailPreferenceManager
import za.kilowatch.ultimatefilemanager.storage.ViewModeManager
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.settings.IconTapEditModePreferenceManager
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.settings.DefaultIconColorManager
import za.kilowatch.ultimatefilemanager.settings.ScrollingTextHelper
import za.kilowatch.ultimatefilemanager.settings.ScrollingTextPreferenceManager
import za.kilowatch.ultimatefilemanager.settings.FileNameDisplayHelper
import za.kilowatch.ultimatefilemanager.util.AppIconBadgeHelper
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val VIDEO_EXTENSIONS = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS

sealed class ListItem {
    data class Header(val year: Int, val month: Int, val count: Int, val isCollapsed: Boolean = false) : ListItem()
    data class FileEntry(val javaFile: java.io.File) : ListItem()
    data class NetworkEntry(val file: za.kilowatch.ultimatefilemanager.network.NetworkFile) : ListItem()
    data object EmptyBuffer : ListItem()
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
        private const val TAG = "FileAdapter"

        private val videoCache = android.util.LruCache<String, android.graphics.Bitmap>(64)
        val thumbnailPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun putCachedPath(path: String, localPath: String) {
            thumbnailPathCache[path] = localPath
        }

        fun getCachedPath(path: String): String? = thumbnailPathCache[path]

        fun getVideoThumbnail(path: String): android.graphics.Bitmap? = videoCache.get(path)

        fun putVideoThumbnail(path: String, bitmap: android.graphics.Bitmap) {
            videoCache.put(path, bitmap)
        }

        fun clearCacheForPath(path: String) {
            videoCache.remove(path)
            thumbnailPathCache.remove(path)
            za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.clearCacheForPath(path)
        }

        fun clearCacheForFolder(folderPath: String) {
            val prefix = if (folderPath.endsWith(java.io.File.separator)) folderPath else folderPath + java.io.File.separator
            val keys = videoCache.snapshot().keys
            for (key in keys) {
                if (key == folderPath || key.startsWith(prefix)) {
                    videoCache.remove(key)
                }
            }
            thumbnailPathCache.keys.filter { it == folderPath || it.startsWith(prefix) }.forEach { thumbnailPathCache.remove(it) }
            za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.clearCacheForFolder(folderPath)
        }

        /**
         * Drop every cached artifact derived from [volumePath] — decoded video frames, the
         * thumbnail path map, and audio cover art — ahead of an eject.
         *
         * Differs from [clearCacheForFolder] in one deliberate way: bitmaps are `recycle()`d,
         * not merely evicted. An evicted bitmap's native memory is retained until GC runs,
         * and an eject cannot wait for that.
         *
         * **Precondition:** no view may still be displaying one of these bitmaps. The release
         * sequence closes viewers and detaches adapters before reaching this stage, which is
         * what makes recycling safe; drawing a recycled bitmap throws. Do not call this from
         * a browsing path.
         *
         * Matching uses a path-segment boundary, so `/storage/7DE2-1219` does not sweep the
         * sibling `/storage/7DE2-12190`.
         */
        fun clearVolumeCaches(volumePath: String) {
            val root = volumePath.trimEnd(java.io.File.separatorChar)
            if (root.isEmpty()) return
            val prefix = root + java.io.File.separator

            val keys = videoCache.snapshot().keys
            for (key in keys) {
                if (key == root || key.startsWith(prefix)) {
                    videoCache.remove(key)?.recycle()
                }
            }
            thumbnailPathCache.keys.filter { it == root || it.startsWith(prefix) }
                .forEach { thumbnailPathCache.remove(it) }
            za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.clearVolumeCaches(root)
        }

        /**
         * Weak references to every live adapter, so the eject path can reach adapters it
         * does not own.
         *
         * The releaser holds a `Context` and a `VolumeIdentity`, never an adapter. That is
         * fine for the standalone browser, which cancels its adapter before the release
         * starts — but the Tab and Twin hosts are `FileBrowserFragment` /
         * `NetworkBrowserFragment`, whose adapters the releaser cannot reach at all. Without
         * this registry their warm-cache and child-count jobs would keep running through the
         * release, and a job that opens a file *after* stage 4 has reported the volume clear
         * leaves a claim the unmount will find — one the user was never warned about. Stage 4
         * cannot catch it either: its scan is a single snapshot, so a claim opened after that
         * snapshot is invisible to it. A mapping opened late is worse still, because a mapping
         * is plan R1 — the one claim Java cannot release at all.
         *
         * Weak so a destroyed Activity's adapter is collectable without an explicit
         * unregister; a leak here would be worse than the bug it prevents. The map is
         * wrapped in a lock because the `newSetFromMap` view of a `WeakHashMap` is not
         * itself thread-safe, and adapters are registered on the main thread while the
         * eject path reads from a coroutine.
         */
        private val liveAdapters: MutableSet<FileAdapter> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<FileAdapter, Boolean>())
        private val liveAdaptersLock = Any()

        /**
         * Cancels pending background jobs on every live adapter whose current listing lies
         * under [volumePath]. Called by the release sequence's quiesce stage.
         *
         * **Attribution uses [files] rather than a tracked directory, deliberately.** Every
         * job [cancelPendingJobs] cancels derives from the listing the adapter is currently
         * showing — [submitList] launches `warmCacheJob` with that exact `filesCopy` and
         * builds `childCountJob` from its directories. So if no listed path is under the
         * volume, neither job can touch it, and an adapter showing an *empty* directory on
         * the volume creates neither job (`dirs` is empty, and `warmCacheForFiles(emptyList())`
         * has no `parentPath` to prune). Attribution is therefore exact in both directions
         * with no extra state to keep in sync.
         */
        fun cancelPendingJobsUnder(volumePath: String) {
            val root = volumePath.trimEnd(java.io.File.separatorChar)
            if (root.isEmpty()) return
            val prefix = root + java.io.File.separator

            val snapshot: List<FileAdapter>
            synchronized(liveAdaptersLock) { snapshot = liveAdapters.toList() }

            var cancelled = 0
            for (adapter in snapshot) {
                if (adapter.isShowingPathUnder(root, prefix)) {
                    adapter.cancelPendingJobs()
                    cancelled++
                }
            }
            if (cancelled > 0) {
                GoRoLog.i(TAG, "Cancelled pending jobs on $cancelled adapter(s) under $root")
            }
        }
    }

    var viewMode: ViewModeManager.ViewMode = ViewModeManager.ViewMode.LIST_MEDIUM
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /**
     * Controls whether fast directional-pad navigation or fast scrolling is underway.
     * When true, thumbnail decode jobs are deferred to ensure 60fps cursor responsiveness.
     */
    var isFastNavigating: Boolean = false

    /**
     * Iterates currently attached/visible ViewHolders and requests any pending deferred thumbnails.
     */
    fun loadVisibleThumbnails(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val holder = recyclerView.getChildViewHolder(child) as? FileViewHolder ?: continue
            holder.loadPendingThumbnail()
        }
    }

    private var attachedContext: android.content.Context? = null

    private var adapterJob = SupervisorJob()
    private var adapterScope = CoroutineScope(adapterJob + Dispatchers.Main.immediate)
    private var warmCacheJob: Job? = null

    private val clipboardListener = FileClipboard.ClipboardChangeListener {
        notifyDataSetChanged()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (!adapterJob.isActive) {
            adapterJob = SupervisorJob()
            adapterScope = CoroutineScope(adapterJob + Dispatchers.Main.immediate)
        }
        attachedContext = recyclerView.context
        FileClipboard.addListener(clipboardListener)
    }
    
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        FileClipboard.removeListener(clipboardListener)
        attachedContext = null
        cancelPendingJobs()
    }

    /**
     * Immediately cancels all background thumbnail, caching, and child-count coroutines.
     * Prevents Android vold from sending SIGINT during storage unmount.
     */
    fun cancelPendingJobs() {
        childCountJob?.cancel()
        childCountJob = null
        warmCacheJob?.cancel()
        warmCacheJob = null
        adapterJob.cancelChildren()
    }

    private val files = mutableListOf<File>()
    fun getFiles(): List<File> = files.toList()
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

    init {
        // Register for the eject path's volume-scoped cancellation. Placed after `files`
        // only for readability — it touches nothing but companion state, and the companion
        // is initialised on class load, before any instance exists. Weak, so no unregister
        // is needed or wanted.
        synchronized(liveAdaptersLock) { liveAdapters.add(this) }
    }

    /**
     * True when any path this adapter is currently listing lies under the given volume root.
     * [root] and [prefix] are pre-computed by [cancelPendingJobsUnder] so the trimming and
     * separator concatenation happen once per eject rather than once per listed file.
     *
     * Matching uses a path-segment boundary, matching [clearVolumeCaches]: `/storage/7DE2-1219`
     * must not match the sibling `/storage/7DE2-12190`.
     *
     * **Main thread only.** [files] is the adapter's backing list and carries no synchronisation —
     * it is written by [submitList] on the main thread, and reading it from anywhere else is a
     * data race. The sole caller, [cancelPendingJobsUnder], is reached from
     * `VolumeClaimReleaser.releaseAll` on `Dispatchers.Main`; that is why the release phase
     * deliberately keeps the caller on the main dispatcher instead of moving it to
     * `Dispatchers.IO` for the cheaper walk.
     */
    private fun isShowingPathUnder(root: String, prefix: String): Boolean {
        for (f in files) {
            val path = f.absolutePath
            if (path == root || path.startsWith(prefix)) return true
        }
        return false
    }

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

    private fun addBufferRows() {
        if (!isTv && items.isNotEmpty() && items.none { it is ListItem.EmptyBuffer }) {
            items.add(ListItem.EmptyBuffer)
            items.add(ListItem.EmptyBuffer)
        }
    }

    private fun removeBufferRows() {
        if (!isTv) {
            items.removeAll { it is ListItem.EmptyBuffer }
        }
    }

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

        if (!isTv && isSelectionMode && items.isNotEmpty()) {
            items.add(ListItem.EmptyBuffer)
            items.add(ListItem.EmptyBuffer)
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
                removeBufferRows()
            }
            onSelectionChanged(selectedPaths.size)
        }

        // Pre-warm local thumbnail cache and prune stale thumbnails asynchronously
        val ctx = attachedContext
        if (ctx != null && ThumbnailPreferenceManager.isEnabled(ctx)) {
            val cacheManager = za.kilowatch.ultimatefilemanager.settings.LocalThumbnailCacheManager.getInstance(ctx)
            warmCacheJob?.cancel()
            warmCacheJob = adapterScope.launch(Dispatchers.IO) {
                cacheManager.warmCacheForFiles(filesCopy)
                val parentPath = filesCopy.firstOrNull()?.parent
                if (parentPath != null) {
                    cacheManager.pruneStaleThumbnails(parentPath, filesCopy)
                }
            }
        }

        // Pre-compute directory child counts and total folder sizes off the main thread
        childCountJob?.cancel()
        val dirs = filesCopy.filter { it.isDirectoryCached() }
        if (dirs.isNotEmpty()) {
            val ctx = attachedContext
            childCountJob = adapterScope.launch(Dispatchers.IO) {
                val counts = mutableMapOf<String, Int>()
                val sizes = mutableMapOf<String, Long>()
                val dao = ctx?.let { za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase.getInstance(it).fileIndexDao() }
                val indexingRepo = try { za.kilowatch.ultimatefilemanager.UfmApplication.indexingRepository } catch (_: Exception) { null }

                for (dir in dirs) {
                    if (!isActive) return@launch
                    val isSafDir = dir is SafFile || (ctx != null && SafTreeManager.isSaf(ctx, dir))
                    val visibleCount = if (isSafDir && ctx != null) {
                        SafTreeManager.getChildCount(ctx, dir.absolutePath)
                    } else {
                        val children = dir.list()?.toList()
                        children?.count { subName ->
                            !za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isJunkOrHidden(subName) &&
                            SafFile.combineSafPath(dir.absolutePath, subName) !in hiddenPaths &&
                            File(dir, subName).absolutePath !in hiddenPaths
                        } ?: 0
                    }
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
            if (!isTv) {
                items.removeAll { it is ListItem.EmptyBuffer }
            }
            val startPos = items.size
            items.addAll(newFiles.map { ListItem.FileEntry(it) })
            if (!isTv && isSelectionMode && items.isNotEmpty()) {
                items.add(ListItem.EmptyBuffer)
                items.add(ListItem.EmptyBuffer)
                notifyDataSetChanged()
            } else {
                notifyItemRangeInserted(startPos, newFiles.size)
            }
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

    fun getItemAt(position: Int): ListItem? = items.getOrNull(position)
    fun getAllItems(): List<ListItem> = items.toList()

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
        val wasSelectionMode = isSelectionMode
        isSelectionMode = true
        selectedPaths.clear()
        longPressAnchorIndex = RecyclerView.NO_POSITION
        files.forEach { selectedPaths.add(it.absolutePath) }
        if (!isTv && !wasSelectionMode) {
            addBufferRows()
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPaths.size)
    }

    /** Whether every item is currently selected. */
    fun isAllSelected(): Boolean = files.isNotEmpty() && selectedPaths.size == files.size

    /** Invert the current selection over the visible listing. */
    fun invertSelection() {
        files.forEach {
            val path = it.absolutePath
            if (path in selectedPaths) selectedPaths.remove(path) else selectedPaths.add(path)
        }
        if (selectedPaths.isEmpty()) {
            exitSelectionMode()
            return
        }
        val wasSelectionMode = isSelectionMode
        isSelectionMode = true
        longPressAnchorIndex = RecyclerView.NO_POSITION
        if (!isTv && !wasSelectionMode) {
            addBufferRows()
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPaths.size)
    }

    /** Deselect all and exit selection mode. */
    fun deselectAll() {
        exitSelectionMode()
    }

    /** Clear all selections and exit selection mode. */
    fun exitSelectionMode() {
        val wasSelectionMode = isSelectionMode
        selectedPaths.clear()
        isSelectionMode = false
        longPressAnchorIndex = RecyclerView.NO_POSITION
        if (!isTv && wasSelectionMode) {
            removeBufferRows()
        }
        notifyDataSetChanged()
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
        val wasSelectionMode = isSelectionMode
        if (!isSelectionMode) {
            isSelectionMode = true
            longPressAnchorIndex = position
            selectedPaths.add(file.absolutePath)
            if (!isTv) {
                addBufferRows()
            }
        } else if (longPressAnchorIndex == RecyclerView.NO_POSITION) {
            // Already in selection mode but no anchor (e.g. after selectAll/deselectAll)
            longPressAnchorIndex = position
            selectedPaths.add(file.absolutePath)
        } else {
            // Already in selection mode with an anchor — do range selection
            selectRange(longPressAnchorIndex, position)
            longPressAnchorIndex = position
        }
        notifyDataSetChanged()
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

    fun toggleSelection(file: File) {
        val path = file.absolutePath
        if (path in selectedPaths) {
            selectedPaths.remove(path)
            if (selectedPaths.isEmpty()) {
                exitSelectionMode()
                return
            }
        } else {
            selectedPaths.add(path)
        }
        onSelectionChanged(selectedPaths.size)
    }

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        if (item is ListItem.EmptyBuffer) return 4
        if (item is ListItem.Header) return 3
        val isGrid = ViewModeManager.isGrid(viewMode)
        return when {
            isGrid    -> 1             // grid layout
            isCompact -> 2             // compact list (vertical-split twin window)
            else      -> 0             // list layout (can be TV list or mobile list)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 4) {
            val view = View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    0
                )
            }
            return EmptyBufferViewHolder(view)
        }
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
        if (holder is EmptyBufferViewHolder) {
            holder.bind()
        } else if (holder is HeaderViewHolder && item is ListItem.Header) {
            holder.bind(item)
        } else if (holder is FileViewHolder && item is ListItem.FileEntry) {
            holder.bind(item.javaFile)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is FileViewHolder) {
            holder.recycle()
        }
    }

    override fun getItemCount(): Int = items.size

    inner class EmptyBufferViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            val context = itemView.context
            val isGrid = ViewModeManager.isGrid(viewMode)
            val density = context.resources.displayMetrics.density
            val heightPx = if (isGrid) {
                val spanCount = ViewModeManager.spanCount(context, viewMode)
                val parentWidth = (itemView.parent as? View)?.width.takeIf { it != null && it > 0 }
                    ?: context.resources.displayMetrics.widthPixels
                val cellWidth = parentWidth / maxOf(1, spanCount)
                cellWidth + (10 * density).toInt()
            } else {
                val heightPx = if (isTv || isCompact) {
                    val heightDp = if (isCompact) 44 else when (viewMode) {
                        ViewModeManager.ViewMode.LIST_SMALL -> 48
                        ViewModeManager.ViewMode.LIST_MEDIUM -> 64
                        ViewModeManager.ViewMode.LIST_LARGE -> 80
                        ViewModeManager.ViewMode.LIST_XLARGE -> 96
                        else -> 64
                    }
                    (heightDp * density).toInt()
                } else {
                    za.kilowatch.ultimatefilemanager.settings.ViewStyleManager.computeMinRowHeightPx(context, viewMode)
                }
                heightPx
            }
            val lp = itemView.layoutParams as? RecyclerView.LayoutParams
                ?: RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, heightPx)
            lp.width = RecyclerView.LayoutParams.MATCH_PARENT
            lp.height = heightPx
            itemView.layoutParams = lp
            itemView.isClickable = false
            itemView.isFocusable = false
            itemView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

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
        private val imgAppBadge: ImageView? = itemView.findViewById(R.id.imgAppBadge)
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

        /**
         * Tracks a background coroutine loading an app icon badge for Android/data folders.
         */
        private var appBadgeJob: kotlinx.coroutines.Job? = null

        private var boundFile: File? = null
        private var hasLoadedThumbnail: Boolean = false

        fun recycle() {
            coilDisposable?.dispose()
            coilDisposable = null
            videoJob?.cancel()
            videoJob = null
            appBadgeJob?.cancel()
            appBadgeJob = null
            stopPulse()
            ScrollingTextHelper.cancelScrolling(txtName)
            imgIcon.tag = null
            imgAppBadge?.setImageDrawable(null)
            imgAppBadge?.tag = null
            imgAppBadge?.visibility = View.GONE
            hasLoadedThumbnail = false
            boundFile = null
        }

        fun loadPendingThumbnail() {
            val file = boundFile ?: return
            if (hasLoadedThumbnail) return
            val context = itemView.context
            val ext = file.extension.lowercase()
            val isImage = ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            val isVideo = ext in VIDEO_EXTENSIONS
            val isApk = ext in listOf("apk", "xapk", "apks")
            val isAudio = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(ext)
            val showThumbnails = ThumbnailPreferenceManager.isEnabled(context)
            val isThumbnail = !file.isDirectoryCached() && showThumbnails && (isImage || isVideo || isApk || (isAudio && !za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.isKnownNoArt(file.absolutePath)))
            if (!isThumbnail) return

            hasLoadedThumbnail = true
            val isGrid = ViewModeManager.isGrid(viewMode)
            if (!isGrid) {
                loadListThumbnail(file, isImage, isApk, isAudio)
            } else {
                loadThumbnail(file)
            }
        }

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
            boundFile = file
            hasLoadedThumbnail = false
            val context = itemView.context
            val isGrid = ViewModeManager.isGrid(viewMode)

            val ext = file.extension.lowercase()
            val isImage = ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            val isVideo = ext in VIDEO_EXTENSIONS
            val isApk = ext in listOf("apk", "xapk", "apks")
            val isAudio = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(ext)
            val showThumbnails = ThumbnailPreferenceManager.isEnabled(context)
            val isThumbnail = !file.isDirectoryCached() && showThumbnails && (isImage || isVideo || isApk || (isAudio && !za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.isKnownNoArt(file.absolutePath)))

            // Cancel any in-flight Coil request or video-frame extraction from a previous bind.
            coilDisposable?.dispose()
            coilDisposable = null
            videoJob?.cancel()
            videoJob = null
            appBadgeJob?.cancel()
            appBadgeJob = null

            // Apply dynamic list or grid mode scaling
            if (!isGrid) {
                itemView.minimumHeight = 0
                val density = context.resources.displayMetrics.density

                if (!isTv && !isCompact) {
                    val listStyle = za.kilowatch.ultimatefilemanager.settings.ViewStyleManager.getListStyle(context, viewMode)
                    val minHeightPx = za.kilowatch.ultimatefilemanager.settings.ViewStyleManager.computeMinRowHeightPx(context, viewMode)
                    itemView.minimumHeight = minHeightPx
                    layoutRow.minimumHeight = minHeightPx

                    val params = itemView.layoutParams
                    if (params != null) {
                        if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        if (params is ViewGroup.MarginLayoutParams) {
                            params.bottomMargin = (listStyle.itemSpacingDp * density + 0.5f).toInt()
                        }
                        itemView.layoutParams = params
                    }

                    val padHPx = (listStyle.itemPaddingHorizontalDp * density + 0.5f).toInt()
                    val padVPx = (listStyle.itemPaddingVerticalDp * density + 0.5f).toInt()
                    layoutRow.setPadding(padHPx, padVPx, padHPx, padVPx)

                    iconContainer?.let { container ->
                        val iconParams = container.layoutParams as? ViewGroup.MarginLayoutParams
                        if (iconParams != null) {
                            val iconSizePx = (listStyle.thumbnailSizeDp * density + 0.5f).toInt()
                            val marginPx = (4 * density + 0.5f).toInt()
                            iconParams.width = iconSizePx
                            iconParams.height = iconSizePx
                            iconParams.topMargin = marginPx
                            iconParams.bottomMargin = marginPx
                            container.layoutParams = iconParams

                            val radiusPx = listStyle.iconCornerRadiusDp * density
                            container.clipToOutline = radiusPx > 0f
                            container.outlineProvider = object : android.view.ViewOutlineProvider() {
                                override fun getOutline(v: android.view.View, outline: android.graphics.Outline) {
                                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                                }
                            }
                            container.invalidateOutline()

                            imgIcon.clipToOutline = radiusPx > 0f
                            imgIcon.outlineProvider = object : android.view.ViewOutlineProvider() {
                                override fun getOutline(v: android.view.View, outline: android.graphics.Outline) {
                                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                                }
                            }
                            imgIcon.invalidateOutline()
                        }
                    }

                    txtName.textSize = listStyle.primaryTextSizeSp.toFloat()
                    txtInfo.textSize = listStyle.secondaryTextSizeSp.toFloat()
                    txtSize.textSize = listStyle.secondaryTextSizeSp.toFloat()
                } else {
                    val heightDp = if (isCompact) 44 else when (viewMode) {
                        ViewModeManager.ViewMode.LIST_SMALL -> 48
                        ViewModeManager.ViewMode.LIST_MEDIUM -> 64
                        ViewModeManager.ViewMode.LIST_LARGE -> 80
                        ViewModeManager.ViewMode.LIST_XLARGE -> 96
                        else -> 64
                    }

                    val iconSizeDp = if (isCompact) 34 else when (viewMode) {
                        ViewModeManager.ViewMode.LIST_SMALL -> 36
                        ViewModeManager.ViewMode.LIST_MEDIUM -> 48
                        ViewModeManager.ViewMode.LIST_LARGE -> 56
                        ViewModeManager.ViewMode.LIST_XLARGE -> 64
                        else -> 48
                    }

                    val titleSp = if (isCompact) 12f else when (viewMode) {
                        ViewModeManager.ViewMode.LIST_SMALL -> 14f
                        ViewModeManager.ViewMode.LIST_MEDIUM -> 16f
                        ViewModeManager.ViewMode.LIST_LARGE -> 18f
                        ViewModeManager.ViewMode.LIST_XLARGE -> 20f
                        else -> 16f
                    }
                    val subtitleSp = if (isCompact) 10f else when (viewMode) {
                        ViewModeManager.ViewMode.LIST_SMALL -> 11f
                        ViewModeManager.ViewMode.LIST_MEDIUM -> 12f
                        ViewModeManager.ViewMode.LIST_LARGE -> 13f
                        ViewModeManager.ViewMode.LIST_XLARGE -> 14f
                        else -> 12f
                    }

                    val minHeightPx = (heightDp * density + 0.5f).toInt()
                    itemView.minimumHeight = minHeightPx
                    layoutRow.minimumHeight = minHeightPx
                    val params = itemView.layoutParams
                    if (params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        itemView.layoutParams = params
                    }

                    iconContainer?.let { container ->
                        val iconParams = container.layoutParams as? ViewGroup.MarginLayoutParams
                        if (iconParams != null) {
                            val iconSizePx = (iconSizeDp * density + 0.5f).toInt()
                            val marginPx = (4 * density + 0.5f).toInt()
                            iconParams.width = iconSizePx
                            iconParams.height = iconSizePx
                            iconParams.topMargin = marginPx
                            iconParams.bottomMargin = marginPx
                            container.layoutParams = iconParams
                        }
                    }

                    txtName.textSize = titleSp
                    txtInfo.textSize = subtitleSp
                    txtSize.textSize = subtitleSp
                }
            } else if (!isTv) {
                // Apply dynamic grid mode scaling on mobile
                val gridStyle = za.kilowatch.ultimatefilemanager.settings.ViewStyleManager.getGridStyle(context, viewMode)
                val density = context.resources.displayMetrics.density
                (itemView as? com.google.android.material.card.MaterialCardView)?.let { card ->
                    val radiusPx = gridStyle.cardCornerRadiusDp * density
                    if (radiusPx <= 0.5f) {
                        card.radius = 0f
                        card.clipToOutline = false
                        card.outlineProvider = null
                    } else {
                        card.radius = radiusPx
                        card.clipToOutline = true
                        card.outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                            }
                        }
                        card.invalidateOutline()
                    }
                    val marginPx = (gridStyle.cardMarginDp * density + 0.5f).toInt()
                    (card.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                txtName.textSize = gridStyle.primaryTextSizeSp.toFloat()
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
            imgAppBadge?.setImageDrawable(null)
            imgAppBadge?.tag = null
            imgAppBadge?.visibility = View.GONE
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
                if (isGrid && !isTv) {
                    val gridStyle = za.kilowatch.ultimatefilemanager.settings.ViewStyleManager.getGridStyle(context, viewMode)
                    val iconPadPx = (gridStyle.iconPaddingDp * context.resources.displayMetrics.density + 0.5f).toInt()
                    imgIcon.setPadding(iconPadPx, iconPadPx, iconPadPx, iconPadPx)
                }

                // App Package Badge on top-right of Android/data folders (Mobile only)
                if (!isTv && AppIconBadgeHelper.isAppFolder(file, true)) {
                    val pkg = file.name.trim()
                    val cached = AppIconBadgeHelper.getCachedIcon(pkg)
                    if (cached != null) {
                        imgAppBadge?.setImageDrawable(cached)
                        imgAppBadge?.visibility = View.VISIBLE
                    } else if (AppIconBadgeHelper.isKnownNotFound(pkg)) {
                        imgAppBadge?.visibility = View.GONE
                    } else {
                        imgAppBadge?.tag = pkg
                        imgAppBadge?.visibility = View.GONE
                        appBadgeJob = adapterScope.launch(Dispatchers.IO) {
                            val icon = AppIconBadgeHelper.loadIcon(context, pkg)
                            withContext(Dispatchers.Main) {
                                if (imgAppBadge?.tag == pkg) {
                                    if (icon != null) {
                                        imgAppBadge.setImageDrawable(icon)
                                        imgAppBadge.visibility = View.VISIBLE
                                    } else {
                                        imgAppBadge.visibility = View.GONE
                                    }
                                }
                            }
                        }
                    }
                } else {
                    imgAppBadge?.visibility = View.GONE
                    imgAppBadge?.setImageDrawable(null)
                }

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
                val isImage = ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
                val isVideo = ext in VIDEO_EXTENSIONS
                val isApk = ext in listOf("apk", "xapk", "apks")
                val isAudio = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(ext)
                val showThumbnails = ThumbnailPreferenceManager.isEnabled(context)

                val isMedia = isImage || isVideo || isApk || (isAudio && !za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.isKnownNoArt(file.absolutePath))
                val isCached = showThumbnails && isMedia && (
                    thumbnailPathCache[file.absolutePath]?.let { File(it).exists() } == true ||
                    za.kilowatch.ultimatefilemanager.settings.LocalThumbnailCacheManager.getInstance(context).getExistingDiskThumbnail(file)?.also { thumbnailPathCache[file.absolutePath] = it } != null
                )

                if (!isGrid && showThumbnails && isMedia) {
                    // ── Thumbnail mode ────────────────────────────────────────
                    // Zero out image padding and clear the circle bg so the
                    // thumbnail crops to fill the full row height.
                    iconContainer?.setBackgroundResource(0)
                    imgIcon.setPadding(0, 0, 0, 0)
                    imgIcon.scaleType = if (isApk) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    imgIcon.imageTintList = null
                    
                    val density = context.resources.displayMetrics.density
                    val radius = if (!isTv && !isGrid) {
                        za.kilowatch.ultimatefilemanager.settings.ViewStyleManager.getListStyle(context, viewMode).iconCornerRadiusDp * density
                    } else {
                        10f * density
                    }
                    imgIcon.clipToOutline = radius > 0f
                    imgIcon.outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, radius)
                        }
                    }

                    isDisplayingThumbnail = true
                    if (isFastNavigating && !isCached) {
                        hasLoadedThumbnail = false
                        if (isAudio) {
                            imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                            val tintColor = if (isTv) {
                                DefaultIconColorManager.getTvIconTint(context)
                            } else {
                                DefaultIconColorManager.getMobileIconTint(context)
                            }
                            imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
                            imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                            imgIcon.clipToOutline = false
                        } else {
                            imgIcon.setImageResource(R.drawable.ic_photo_video)
                        }
                    } else {
                        hasLoadedThumbnail = true
                        loadListThumbnail(file, isImage, isApk, isAudio)
                    }

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
                    if (showThumbnails && isMedia) {
                        iconContainer?.setBackgroundResource(0)
                        imgIcon.setPadding(0, 0, 0, 0)
                        imgIcon.clipToOutline = true
                        imgIcon.outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                                val radius = 10f * view.context.resources.displayMetrics.density
                                outline.setRoundRect(0, 0, view.width, view.height, radius)
                            }
                        }
                        if (isFastNavigating && !isCached) {
                            hasLoadedThumbnail = false
                            if (isAudio) {
                                imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                                val tintColor = if (isTv) {
                                    DefaultIconColorManager.getTvIconTint(context)
                                } else {
                                    DefaultIconColorManager.getMobileIconTint(context)
                                }
                                imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
                                imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                                imgIcon.clipToOutline = false
                            } else {
                                imgIcon.setImageResource(R.drawable.ic_photo_video)
                                imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                            }
                        } else {
                            hasLoadedThumbnail = true
                            loadThumbnail(file)
                        }
                    } else {
                        iconContainer?.setBackgroundResource(0)
                        imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                        val tintColor = if (isTv) {
                            DefaultIconColorManager.getTvIconTint(context)
                        } else {
                            DefaultIconColorManager.getMobileIconTint(context)
                        }
                        imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
                        imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                        imgIcon.clipToOutline = false
                        if (isGrid && !isTv) {
                            val gridStyle = za.kilowatch.ultimatefilemanager.settings.ViewStyleManager.getGridStyle(context, viewMode)
                            val iconPadPx = (gridStyle.iconPaddingDp * context.resources.displayMetrics.density + 0.5f).toInt()
                            imgIcon.setPadding(iconPadPx, iconPadPx, iconPadPx, iconPadPx)
                        }
                    }
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
            val showCheckbox = isSelectionMode && (isTv || za.kilowatch.ultimatefilemanager.settings.SelectionCheckboxPreferenceManager.isEnabled(context))
            checkSelect.visibility = if (showCheckbox) View.VISIBLE else View.GONE
            checkSelect.isChecked = isSelected

            // Highlight selected rows / cards
            val isFocused = file.absolutePath == focusedPath
            if (isSelected || isFocused) {
                if (isGrid) {
                    itemView.findViewById<View>(R.id.viewSelectionOverlay)?.visibility = View.VISIBLE
                } else {
                    // `ufmFocusGlow` defaults to @color/tv_button_focused_yellow_glow
                    // in both base themes, so the focused branch is byte-identical
                    // with the mode off.
                    val color = if (isFocused) ColorblindPalette.focusGlow(context)
                                else ColorblindPalette.selectionFill(context)
                    layoutRow.setBackgroundColor(color)
                }
            } else {
                if (isGrid) {
                    itemView.findViewById<View>(R.id.viewSelectionOverlay)?.visibility = View.GONE
                } else {
                    layoutRow.setBackgroundColor(0x00000000) // transparent
                }
            }

            // Clipboard Cut/Copy visual highlighting (Option A - MT Manager Classic)
            val clipOp = FileClipboard.getLocalOperation(file)
            val imgClipBadge = itemView.findViewById<ImageView>(R.id.imgClipboardBadge)

            when (clipOp) {
                FileClipboard.Operation.MOVE -> {
                    // Cut: 100% opacity (no washed out row/folder), amber filename text, amber bubble badge at top-left
                    layoutRow.alpha = 1.0f
                    val amberColor = ContextCompat.getColor(context, R.color.mobile_note_color)
                    txtName.setTextColor(amberColor)
                    imgClipBadge?.apply {
                        visibility = View.VISIBLE
                        setBackgroundResource(R.drawable.bg_badge_bubble_cut)
                        setImageResource(R.drawable.ic_cut)
                        imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#0F172A"))
                    }
                    if (!isSelected && !isFocused && !isGrid) {
                        layoutRow.setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(amberColor, 0x30)) // ~19% warm amber fill
                    }
                }
                FileClipboard.Operation.COPY -> {
                    // Copy: 100% opacity, sky blue filename text, sky blue bubble badge at top-left, distinct cobalt blue tint if not selected
                    layoutRow.alpha = 1.0f
                    val copyTextColor = ContextCompat.getColor(context, R.color.tv_accent)
                    val copyRowBg = androidx.core.graphics.ColorUtils.setAlphaComponent(android.graphics.Color.parseColor("#2563EB"), 0x30)
                    txtName.setTextColor(copyTextColor)
                    imgClipBadge?.apply {
                        visibility = View.VISIBLE
                        setBackgroundResource(R.drawable.bg_badge_bubble_copy)
                        setImageResource(R.drawable.ic_copy)
                        imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#0F172A"))
                    }
                    if (!isSelected && !isFocused && !isGrid) {
                        layoutRow.setBackgroundColor(copyRowBg)
                    }
                }
                else -> {
                    // Normal state reset (Crucial for ViewHolder recycling!)
                    layoutRow.alpha = 1.0f
                    imgClipBadge?.apply {
                        visibility = View.GONE
                        setImageDrawable(null)
                        background = null
                    }
                    txtName.setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_text_primary else R.color.mobile_text_primary))
                    if (!isSelected && !isFocused && !isGrid) {
                        layoutRow.setBackgroundColor(0x00000000)
                    }
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
                                if (!isTv) {
                                    addBufferRows()
                                }
                                notifyDataSetChanged()
                                onSelectionChanged(selectedPaths.size)
                            } else {
                                val wasSelected = currentFile.absolutePath in selectedPaths
                                toggleSelection(currentFile)
                                if (!wasSelected) {
                                    longPressAnchorIndex = pos
                                }
                                if (isSelectionMode) {
                                    notifyItemChanged(pos)
                                }
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
                            if (isSelectionMode) {
                                notifyItemChanged(pos)
                            }
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
                            if (!isTv) {
                                addBufferRows()
                            }
                            notifyDataSetChanged()
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
                val black     = ColorblindPalette.focusFillText(ctx)
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
                    val isShowingArt = isAudio && za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.getCachedArt(file.absolutePath) != null
                    val shouldTint = !isThumbnail || (isAudio && !isShowingArt)
                    if (hasFocus) {
                        if (!isGrid) {
                            txtName.setTextColor(black)
                            txtInfo.setTextColor(black)
                            txtSize.setTextColor(black)
                        }
                        if (shouldTint) imgIcon.imageTintList = blackCsl
                    } else {
                        if (isGrid) {
                            applyGridTextColor(file)
                        } else {
                            txtName.setTextColor(white)
                            txtInfo.setTextColor(secondary)
                            txtSize.setTextColor(hint)
                        }
                        if (shouldTint) imgIcon.imageTintList = accentCsl
                    }
                }
            }

            // ── File name presentation (marquee, multi-line, or grid) ───────
            FileNameDisplayHelper.applyFileNameDisplay(txtName, file.name, isTv, isGrid)

            if (isGrid) {
                applyGridTextColor(file)
            }
        }


        /**
         * Loads a thumbnail for the list view using Coil.
         *
         * - Images: Coil loads the file directly from disk (memory + disk cached) or via SAF DocumentUri.
         * - Videos: a frame is extracted on Dispatchers.IO via ThumbnailUtils /
         *   MediaMetadataRetriever, then the resulting Bitmap is fed back to Coil
         *   on the main thread.  A tag guard prevents stale frames landing on a
         *   recycled ViewHolder.
         */
        private fun loadListThumbnail(file: File, isImage: Boolean, isApk: Boolean, isAudio: Boolean = false) {
            val placeholderImage = ContextCompat.getDrawable(itemView.context, R.drawable.ic_photo_video)?.asImage()
            val isSaf = file is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath)
            val safDocUri = if (isSaf) {
                (file as? za.kilowatch.ultimatefilemanager.storage.SafFile)?.documentUri
                    ?: za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getDocumentUriForPath(itemView.context, file.absolutePath)
            } else null

            imgIcon.tag = file.absolutePath
            val cacheManager = za.kilowatch.ultimatefilemanager.settings.LocalThumbnailCacheManager.getInstance(itemView.context)
            val cachedPath = thumbnailPathCache[file.absolutePath]
                ?: cacheManager.getExistingDiskThumbnail(file)?.also { thumbnailPathCache[file.absolutePath] = it }

            if (cachedPath != null && File(cachedPath).exists()) {
                coilDisposable = imgIcon.load(File(cachedPath)) {
                    size(128, 128)
                    precision(Precision.INEXACT)
                    crossfade(false)
                    allowHardware(!isTv)
                    scale(Scale.FILL)
                    error(placeholderImage)
                }
                return
            }

            if (isImage) {
                videoJob = adapterScope.launch(Dispatchers.IO) {
                    val thumbPath = cacheManager.getThumbnail(file)
                    withContext(Dispatchers.Main) {
                        if (imgIcon.tag == file.absolutePath) {
                            if (thumbPath != null && File(thumbPath).exists()) {
                                thumbnailPathCache[file.absolutePath] = thumbPath
                                coilDisposable = imgIcon.load(File(thumbPath)) {
                                    size(128, 128)
                                    precision(Precision.INEXACT)
                                    crossfade(false)
                                    allowHardware(!isTv)
                                    scale(Scale.FILL)
                                    error(placeholderImage)
                                }
                            } else {
                                val loadTarget: Any = safDocUri ?: file
                                coilDisposable = imgIcon.load(loadTarget) {
                                    size(128, 128)
                                    precision(Precision.INEXACT)
                                    crossfade(false)
                                    allowHardware(!isTv)
                                    scale(Scale.FILL)
                                    placeholder(placeholderImage)
                                    error(placeholderImage)
                                    listener(
                                        onError = { _, _ ->
                                            if (imgIcon.tag == file.absolutePath) {
                                                videoJob = adapterScope.launch(Dispatchers.IO) {
                                                    val bmp = extractRawOrImageThumbnail(file, 512)
                                                    if (bmp != null) {
                                                        withContext(Dispatchers.Main) {
                                                            if (imgIcon.tag == file.absolutePath) {
                                                                imgIcon.setImageBitmap(bmp)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (isApk) {
                // APK / XAPK / APKS: extract the app icon via the shared helper
                imgIcon.tag = file.absolutePath
                imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                startPulse()

                videoJob = adapterScope.launch(Dispatchers.IO) {
                    val thumbPath = cacheManager.getThumbnail(file)
                    if (thumbPath != null && File(thumbPath).exists()) {
                        thumbnailPathCache[file.absolutePath] = thumbPath
                        withContext(Dispatchers.Main) {
                            if (imgIcon.tag == file.absolutePath) {
                                stopPulse()
                                coilDisposable = imgIcon.load(File(thumbPath)) {
                                    crossfade(false)
                                    allowHardware(!isTv)
                                }
                            }
                        }
                    } else {
                        val drawable = resolveApkIcon(file)
                        withContext(Dispatchers.Main) {
                            if (imgIcon.tag == file.absolutePath) {
                                stopPulse()
                                if (drawable != null) {
                                    coilDisposable = imgIcon.load(drawable) {
                                        crossfade(false)
                                        allowHardware(false)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (isAudio) {
                imgIcon.tag = file.absolutePath
                val cached = za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.getCachedArt(file.absolutePath)
                if (cached != null && !cached.isRecycled) {
                    imgIcon.setImageBitmap(cached)
                    imgIcon.imageTintList = null
                    imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    imgIcon.clipToOutline = true
                } else if (za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.isKnownNoArt(file.absolutePath)) {
                    imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                    val tintColor = if (isTv) {
                        DefaultIconColorManager.getTvIconTint(itemView.context)
                    } else {
                        DefaultIconColorManager.getMobileIconTint(itemView.context)
                    }
                    imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
                    imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                    imgIcon.clipToOutline = false
                } else {
                    imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                    val tintColor = if (isTv) {
                        DefaultIconColorManager.getTvIconTint(itemView.context)
                    } else {
                        DefaultIconColorManager.getMobileIconTint(itemView.context)
                    }
                    imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
                    imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                    imgIcon.clipToOutline = false

                    videoJob = adapterScope.launch(Dispatchers.IO) {
                        val thumbPath = cacheManager.getThumbnail(file)
                        if (thumbPath != null && File(thumbPath).exists()) {
                            thumbnailPathCache[file.absolutePath] = thumbPath
                            withContext(Dispatchers.Main) {
                                if (imgIcon.tag == file.absolutePath) {
                                    coilDisposable = imgIcon.load(File(thumbPath)) {
                                        crossfade(false)
                                        allowHardware(!isTv)
                                        scale(Scale.FILL)
                                    }
                                    imgIcon.imageTintList = null
                                    imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                                    imgIcon.clipToOutline = true
                                }
                            }
                        } else {
                            val bmp = za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.extractCover(itemView.context, file, 256)
                            if (bmp != null) {
                                withContext(Dispatchers.Main) {
                                    if (imgIcon.tag == file.absolutePath) {
                                        imgIcon.setImageBitmap(bmp)
                                        imgIcon.imageTintList = null
                                        imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                                        imgIcon.clipToOutline = true
                                    }
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

                    videoJob = adapterScope.launch(Dispatchers.IO) {
                        val thumbPath = cacheManager.getThumbnail(file)
                        if (thumbPath != null && File(thumbPath).exists()) {
                            thumbnailPathCache[file.absolutePath] = thumbPath
                            withContext(Dispatchers.Main) {
                                if (imgIcon.tag == file.absolutePath) {
                                    stopPulse()
                                    coilDisposable = imgIcon.load(File(thumbPath)) {
                                        crossfade(false)
                                        allowHardware(!isTv)
                                        scale(Scale.FILL)
                                    }
                                }
                            }
                        } else {
                            var bitmap: android.graphics.Bitmap? = null
                            val pct = za.kilowatch.ultimatefilemanager.settings.VideoThumbnailTimePreferenceManager.getPercent(itemView.context)
                            val isMjpeg = file.extension.lowercase() in listOf("mjpeg", "mjpg", "mjp")
                            if (isSaf) {
                                if (isMjpeg) {
                                    bitmap = decodeMjpegThumbnail(file, 512)
                                } else {
                                    if (safDocUri != null) {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                            try {
                                                bitmap = itemView.context.contentResolver.loadThumbnail(
                                                    safDocUri,
                                                    android.util.Size(512, 512),
                                                    null
                                                )
                                            } catch (_: Throwable) {}
                                        }

                                        if (bitmap == null) {
                                            try {
                                                val retriever = android.media.MediaMetadataRetriever()
                                                try {
                                                    retriever.setDataSource(itemView.context, safDocUri)
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
                                                        bitmap = if (w <= maxPx && h <= maxPx) raw else {
                                                            val scale = maxPx.toFloat() / maxOf(w, h)
                                                            android.graphics.Bitmap.createScaledBitmap(raw,
                                                                (w * scale).toInt().coerceAtLeast(1),
                                                                (h * scale).toInt().coerceAtLeast(1), true)
                                                        }
                                                    }
                                                } finally {
                                                    try { retriever.release() } catch (_: Exception) {}
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                }
                            } else {
                                bitmap = za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(
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
                                if (bitmap == null) {
                                    bitmap = decodeMjpegThumbnail(file, 512)
                                }
                            }

                            withContext(Dispatchers.Main) {
                                if (imgIcon.tag == file.absolutePath) {
                                    stopPulse()
                                    if (bitmap != null) {
                                        videoCache.put(file.absolutePath, bitmap)
                                        coilDisposable = imgIcon.load(bitmap) {
                                            crossfade(false)
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
                val isSaf = file is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) ||
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(itemView.context, file.absolutePath)
                val ext = file.extension.lowercase()
                val pm = itemView.context.packageManager

                if (isSaf) {
                    var iconDrawable: android.graphics.drawable.Drawable? = null
                    // 1. Fast path: try streaming zip for icon without copying full file.
                    // We collect candidates from all density buckets and pick the best
                    // (xxhdpi > xhdpi > hdpi > mdpi > ldpi > unknown) to avoid downloading
                    // the entire APK over the network.
                    try {
                        val inStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(itemView.context, file.absolutePath)
                        if (inStream != null) {
                            // Density rank: higher = better quality
                            fun densityRank(name: String): Int = when {
                                "xxxhdpi" in name -> 6
                                "xxhdpi"  in name -> 5
                                "xhdpi"   in name -> 4
                                "hdpi"    in name -> 3
                                "mdpi"    in name -> 2
                                "ldpi"    in name -> 1
                                else              -> 0
                            }
                            fun isIconEntry(n: String) =
                                n == "icon.png" ||
                                n == "ic_launcher.png" ||
                                (n.startsWith("res/mipmap")  && n.endsWith(".png") && "ic_launcher" in n) ||
                                (n.startsWith("res/drawable") && n.endsWith(".png") && "ic_launcher" in n) ||
                                n.endsWith("/icon.png")

                            var bestRank = -1
                            var bestBytes: ByteArray? = null

                            java.util.zip.ZipInputStream(inStream).use { zip ->
                                var entry = zip.nextEntry
                                while (entry != null) {
                                    val n = entry.name.lowercase()
                                    if (isIconEntry(n)) {
                                        val rank = densityRank(n)
                                        if (rank > bestRank) {
                                            val bytes = zip.readBytes()
                                            if (bytes.isNotEmpty()) {
                                                bestRank = rank
                                                bestBytes = bytes
                                            }
                                        }
                                    }
                                    entry = zip.nextEntry
                                }
                            }
                            bestBytes?.let { bytes ->
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) {
                                    iconDrawable = android.graphics.drawable.BitmapDrawable(itemView.context.resources, bmp)
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    if (iconDrawable != null) {
                        return@withContext iconDrawable
                    }

                    // 2. Fallback: copy to temp file and let PackageManager parse it
                    var tempApk: File? = null
                    try {
                        tempApk = File(itemView.context.cacheDir, "saf_apk_${System.currentTimeMillis()}.$ext")
                        val inStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(itemView.context, file.absolutePath)
                        if (inStream != null) {
                            inStream.use { input ->
                                tempApk.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (ext == "apk") {
                                val pi = pm.getPackageArchiveInfo(tempApk.absolutePath, 0)
                                if (pi != null) {
                                    pi.applicationInfo?.sourceDir = tempApk.absolutePath
                                    pi.applicationInfo?.publicSourceDir = tempApk.absolutePath
                                    iconDrawable = pi.applicationInfo?.loadIcon(pm)
                                }
                            } else {
                                val iconBitmap: android.graphics.Bitmap? = try {
                                    java.util.zip.ZipFile(tempApk).use { zip ->
                                        val entry = zip.getEntry("icon.png")
                                        if (entry != null) {
                                            android.graphics.BitmapFactory.decodeStream(zip.getInputStream(entry))
                                        } else null
                                    }
                                } catch (_: Exception) { null }

                                if (iconBitmap != null) {
                                    iconDrawable = android.graphics.drawable.BitmapDrawable(itemView.context.resources, iconBitmap)
                                } else {
                                    var innerApk: File? = null
                                    try {
                                        innerApk = File(itemView.context.cacheDir, "saf_xapk_base_${System.currentTimeMillis()}.apk")
                                        java.util.zip.ZipFile(tempApk).use { zip ->
                                            val entry = zip.getEntry("base.apk")
                                            if (entry != null) {
                                                zip.getInputStream(entry).use { input ->
                                                    innerApk.outputStream().use { output -> input.copyTo(output) }
                                                }
                                            }
                                        }
                                        if (innerApk.exists() && innerApk.length() > 0L) {
                                            val pi = pm.getPackageArchiveInfo(innerApk.absolutePath, 0)
                                            if (pi != null) {
                                                pi.applicationInfo?.sourceDir = innerApk.absolutePath
                                                pi.applicationInfo?.publicSourceDir = innerApk.absolutePath
                                                iconDrawable = pi.applicationInfo?.loadIcon(pm)
                                            }
                                        }
                                    } finally {
                                        innerApk?.delete()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) { null } finally {
                        tempApk?.delete()
                    }
                    return@withContext iconDrawable
                } else {
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
            }

        /**
         * Loads a thumbnail for grid view using Coil.
         * Images load natively; videos use the same frame-extraction path as list view.
         */
        private fun loadThumbnail(file: File) {
            val context = itemView.context
            val ext = file.extension.lowercase()
            val isImage = ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            val isVideo = ext in VIDEO_EXTENSIONS
            val isApk = ext in listOf("apk", "xapk", "apks")
            val isAudio = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(ext)

            if (!isImage && !isVideo && !isApk && !isAudio) {
                imgIcon.setImageResource(FileTypeIconProvider.iconForFile(context, file))
                val tintColor = if (isTv) {
                    DefaultIconColorManager.getTvIconTint(context)
                } else {
                    DefaultIconColorManager.getMobileIconTint(context)
                }
                imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
                imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                return
            }

            val isSaf = file is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath)
            val safDocUri = if (isSaf) {
                (file as? za.kilowatch.ultimatefilemanager.storage.SafFile)?.documentUri
                    ?: za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getDocumentUriForPath(context, file.absolutePath)
            } else null

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

            val cacheManager = za.kilowatch.ultimatefilemanager.settings.LocalThumbnailCacheManager.getInstance(context)
            val cachedPath = thumbnailPathCache[file.absolutePath]
                ?: cacheManager.getExistingDiskThumbnail(file)?.also { thumbnailPathCache[file.absolutePath] = it }

            if (cachedPath != null && File(cachedPath).exists()) {
                coilDisposable = imgIcon.load(File(cachedPath)) {
                    size(384, 384)
                    precision(Precision.INEXACT)
                    crossfade(false)
                    allowHardware(!isTv)
                    scale(Scale.FILL)
                    error(placeholderImage)
                    listener(
                        onSuccess = { _, _ ->
                            if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                        }
                    )
                }
                return
            }

            if (isImage) {
                imgIcon.tag = file.absolutePath
                videoJob = adapterScope.launch(Dispatchers.IO) {
                    val thumbPath = cacheManager.getThumbnail(file)
                    withContext(Dispatchers.Main) {
                        if (imgIcon.tag == file.absolutePath) {
                            if (thumbPath != null && File(thumbPath).exists()) {
                                thumbnailPathCache[file.absolutePath] = thumbPath
                                coilDisposable = imgIcon.load(File(thumbPath)) {
                                    size(384, 384)
                                    precision(Precision.INEXACT)
                                    crossfade(false)
                                    allowHardware(!isTv)
                                    scale(Scale.FILL)
                                    error(placeholderImage)
                                    listener(
                                        onSuccess = { _, _ ->
                                            if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                                        }
                                    )
                                }
                            } else {
                                val loadTarget: Any = safDocUri ?: file
                                coilDisposable = imgIcon.load(loadTarget) {
                                    size(384, 384)
                                    precision(Precision.INEXACT)
                                    crossfade(false)
                                    allowHardware(!isTv)
                                    scale(Scale.FILL)
                                    placeholder(placeholderImage)
                                    error(placeholderImage)
                                    listener(
                                        onSuccess = { _, _ ->
                                            if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                                        },
                                        onError = { _, _ ->
                                            if (imgIcon.tag == file.absolutePath) {
                                                videoJob = adapterScope.launch(Dispatchers.IO) {
                                                    val bmp = extractRawOrImageThumbnail(file, 512)
                                                    if (bmp != null) {
                                                        withContext(Dispatchers.Main) {
                                                            if (imgIcon.tag == file.absolutePath) {
                                                                imgIcon.setImageBitmap(bmp)
                                                                if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (isApk) {
                // APK / XAPK / APKS: extract the app icon via the shared helper
                imgIcon.tag = file.absolutePath
                imgIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                startPulse()

                videoJob = adapterScope.launch(Dispatchers.IO) {
                    val thumbPath = cacheManager.getThumbnail(file)
                    if (thumbPath != null && File(thumbPath).exists()) {
                        thumbnailPathCache[file.absolutePath] = thumbPath
                        withContext(Dispatchers.Main) {
                            if (imgIcon.tag == file.absolutePath) {
                                stopPulse()
                                coilDisposable = imgIcon.load(File(thumbPath)) {
                                    crossfade(false)
                                    allowHardware(!isTv)
                                    listener(
                                        onSuccess = { _, _ ->
                                            if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        val drawable = resolveApkIcon(file)
                        withContext(Dispatchers.Main) {
                            if (imgIcon.tag == file.absolutePath) {
                                stopPulse()
                                if (drawable != null) {
                                    coilDisposable = imgIcon.load(drawable) {
                                        crossfade(false)
                                        allowHardware(!isTv)
                                        listener(
                                            onSuccess = { _, _ ->
                                                if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (isAudio) {
                imgIcon.tag = file.absolutePath
                val cached = za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.getCachedArt(file.absolutePath)
                if (cached != null && !cached.isRecycled) {
                    imgIcon.setImageBitmap(cached)
                    imgIcon.imageTintList = null
                    imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                } else if (za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.isKnownNoArt(file.absolutePath)) {
                    imgIcon.setImageResource(FileTypeIconProvider.iconForFile(context, file))
                    val tintColor = if (isTv) {
                        DefaultIconColorManager.getTvIconTint(context)
                    } else {
                        DefaultIconColorManager.getMobileIconTint(context)
                    }
                    imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
                    imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                    imgIcon.clipToOutline = false
                } else {
                    imgIcon.setImageResource(FileTypeIconProvider.iconForFile(context, file))
                    val tintColor = if (isTv) {
                        DefaultIconColorManager.getTvIconTint(context)
                    } else {
                        DefaultIconColorManager.getMobileIconTint(context)
                    }
                    imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
                    imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                    imgIcon.clipToOutline = false

                    videoJob = adapterScope.launch(Dispatchers.IO) {
                        val thumbPath = cacheManager.getThumbnail(file)
                        if (thumbPath != null && File(thumbPath).exists()) {
                            thumbnailPathCache[file.absolutePath] = thumbPath
                            withContext(Dispatchers.Main) {
                                if (imgIcon.tag == file.absolutePath) {
                                    coilDisposable = imgIcon.load(File(thumbPath)) {
                                        crossfade(false)
                                        allowHardware(!isTv)
                                        scale(Scale.FILL)
                                        listener(
                                            onSuccess = { _, _ ->
                                                if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                                            }
                                        )
                                    }
                                    imgIcon.imageTintList = null
                                    imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                                    imgIcon.clipToOutline = true
                                }
                            }
                        } else {
                            val bmp = za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.extractCover(context, file, 384)
                            if (bmp != null) {
                                withContext(Dispatchers.Main) {
                                    if (imgIcon.tag == file.absolutePath) {
                                        imgIcon.setImageBitmap(bmp)
                                        imgIcon.imageTintList = null
                                        imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                                        imgIcon.clipToOutline = true
                                        if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                                    }
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
                    if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                } else {
                    imgIcon.setImageDrawable(
                        ContextCompat.getDrawable(itemView.context, R.drawable.ic_photo_video)
                    )
                    startPulse()
                    
                    videoJob = adapterScope.launch(Dispatchers.IO) {
                        val thumbPath = cacheManager.getThumbnail(file)
                        if (thumbPath != null && File(thumbPath).exists()) {
                            thumbnailPathCache[file.absolutePath] = thumbPath
                            withContext(Dispatchers.Main) {
                                if (imgIcon.tag == file.absolutePath) {
                                    stopPulse()
                                    coilDisposable = imgIcon.load(File(thumbPath)) {
                                        crossfade(false)
                                        allowHardware(!isTv)
                                        scale(Scale.FILL)
                                        listener(
                                            onSuccess = { _, _ ->
                                                if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            var bitmap: android.graphics.Bitmap? = null
                            val pct = za.kilowatch.ultimatefilemanager.settings.VideoThumbnailTimePreferenceManager.getPercent(itemView.context)
                            val isMjpeg = file.extension.lowercase() in listOf("mjpeg", "mjpg", "mjp")
                            if (isSaf) {
                                if (isMjpeg) {
                                    bitmap = decodeMjpegThumbnail(file, 480)
                                } else {
                                    if (safDocUri != null) {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                            try {
                                                bitmap = itemView.context.contentResolver.loadThumbnail(
                                                    safDocUri,
                                                    android.util.Size(480, 480),
                                                    null
                                                )
                                            } catch (_: Throwable) {}
                                        }

                                        if (bitmap == null) {
                                            try {
                                                val retriever = android.media.MediaMetadataRetriever()
                                                try {
                                                    retriever.setDataSource(itemView.context, safDocUri)
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
                                                        val maxPx = 480
                                                        val w = raw.width; val h = raw.height
                                                        bitmap = if (w <= maxPx && h <= maxPx) raw else {
                                                            val scale = maxPx.toFloat() / maxOf(w, h)
                                                            android.graphics.Bitmap.createScaledBitmap(raw,
                                                                (w * scale).toInt().coerceAtLeast(1),
                                                                (h * scale).toInt().coerceAtLeast(1), true)
                                                        }
                                                    }
                                                } finally {
                                                    try { retriever.release() } catch (_: Exception) {}
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                }
                            } else {
                                bitmap = za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(
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
                                if (bitmap == null) {
                                    bitmap = decodeMjpegThumbnail(file, 480)
                                }
                            }

                            withContext(Dispatchers.Main) {
                                if (imgIcon.tag == file.absolutePath) {
                                    stopPulse()
                                    if (bitmap != null) {
                                        videoCache.put(file.absolutePath, bitmap)
                                        coilDisposable = imgIcon.load(bitmap) {
                                            crossfade(false)
                                            allowHardware(!isTv)
                                            scale(Scale.FILL)
                                            listener(
                                                onSuccess = { _, _ ->
                                                    if (!isTv) updateTextColorForDrawable(imgIcon.drawable, true)
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
        }

        private fun decodeMjpegThumbnail(file: File, maxPx: Int = 512): android.graphics.Bitmap? {
            return try {
                val context = itemView.context
                val isSaf = file is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) ||
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, file.absolutePath)
                val inStream = if (isSaf) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, file.absolutePath)
                } else {
                    java.io.FileInputStream(file)
                } ?: return null

                inStream.use { stream ->
                    za.kilowatch.ultimatefilemanager.media.MjpegFrameDecoder.decodeFirstFrame(stream, maxPx)
                }
            } catch (_: Throwable) {
                null
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
            if (drawable is android.graphics.drawable.BitmapDrawable && (drawable.bitmap == null || drawable.bitmap.isRecycled)) {
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
            if (isTv) return
            val ext = file.extension.lowercase()
            val isImage = ext in za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
            val isVideo = ext in VIDEO_EXTENSIONS
            val isApk = ext in listOf("apk", "xapk", "apks")
            val showThumbnails = ThumbnailPreferenceManager.isEnabled(itemView.context)
            val hasThumbnail = !file.isDirectoryCached() && showThumbnails && (isImage || isVideo || isApk)

            updateTextColorForDrawable(imgIcon.drawable, hasThumbnail && imgIcon.drawable != null && imgIcon.tag == file.absolutePath)
        }

        private fun extractRawOrImageThumbnail(file: File, maxDim: Int): android.graphics.Bitmap? {
            val isSaf = file is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(itemView.context, file.absolutePath)
            if (isSaf) {
                return try {
                    val inStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(itemView.context, file.absolutePath)
                    if (inStream != null) {
                        inStream.use { stream ->
                            val exif = androidx.exifinterface.media.ExifInterface(stream)
                            val exifBmp = exif.thumbnailBitmap
                            if (exifBmp != null) return exifBmp
                            val bytes = exif.thumbnailBytes
                            if (bytes != null) {
                                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                val sampledOpts = android.graphics.BitmapFactory.Options().apply {
                                    inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxDim)
                                }
                                return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampledOpts)
                            }
                        }
                    }
                    val rawStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(itemView.context, file.absolutePath)
                    if (rawStream != null) {
                        rawStream.use { s ->
                            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeStream(s, null, opts)
                            if (opts.outWidth > 0 && opts.outHeight > 0) {
                                val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxDim)
                                val decodeStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(itemView.context, file.absolutePath)
                                decodeStream?.use { s2 ->
                                    val sampledOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                                    return android.graphics.BitmapFactory.decodeStream(s2, null, sampledOpts)
                                }
                            }
                        }
                    }
                    null
                } catch (_: Throwable) { null }
            }
            return try {
                val exif = android.media.ExifInterface(file.absolutePath)
                val exifBmp = exif.thumbnailBitmap
                if (exifBmp != null) return exifBmp
                val bytes = exif.thumbnailBytes
                if (bytes != null) {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    val sampledOpts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxDim)
                    }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampledOpts)
                } else {
                    za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(file.absolutePath, 0, maxDim, maxDim)
                }
            } catch (_: Throwable) {
                za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(file.absolutePath, 0, maxDim, maxDim)
            }
        }
    }

}
