package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.text.format.Formatter
import coil3.request.Disposable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlinx.coroutines.*
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.settings.DefaultIconColorManager
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager
import za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailPreferenceManager
import za.kilowatch.ultimatefilemanager.settings.ScrollingTextHelper
import za.kilowatch.ultimatefilemanager.settings.ScrollingTextPreferenceManager
import za.kilowatch.ultimatefilemanager.storage.ViewModeManager
import coil3.load
import coil3.asImage
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.size.Scale

class NetworkFileAdapter(
    private val isTv: Boolean,
    initialShare: NetworkShare,
    private val context: Context,
    private val isCompact: Boolean = false,
    private val onItemClick: (NetworkFile) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onToggleChanged: ((NetworkFile, Boolean) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** The share used for SMB operations. Updated when navigating into a discovered share. */
    var share: NetworkShare = initialShare

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val cacheManager = NetworkThumbnailCacheManager(context)

    companion object {
        private val thumbnailPathCache = mutableMapOf<String, String>()
    }

    var viewMode: ViewModeManager.ViewMode = ViewModeManager.ViewMode.LIST_MEDIUM
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val files = mutableListOf<NetworkFile>()
    private val items = mutableListOf<za.kilowatch.ultimatefilemanager.storage.ListItem>()
    var isGroupedByDate = false
    private val selectedFiles = mutableSetOf<NetworkFile>()
    
    var isSelectionMode = false
        private set

    /**
     * Tracks the adapter position of the last long-pressed item for range selection.
     * When a second long press occurs while in selection mode, all file entries
     * between this anchor and the new position are selected. Reset on
     * [exitSelectionMode], [deselectAll], and [submitList].
     */
    var longPressAnchorIndex: Int = RecyclerView.NO_POSITION

    private var searchBasePath: String? = null

    private val childCountCache = mutableMapOf<String, Int>()
    private var childCountJob: Job? = null

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
    
    private val thumbnailReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "za.kilowatch.ultimatefilemanager.ACTION_NETWORK_THUMBNAIL_CREATED") {
                val shareId = intent.getStringExtra("shareId")
                val networkPath = intent.getStringExtra("networkPath")
                
                za.kilowatch.ultimatefilemanager.util.GoRoLog.d("UFM_CACHE", "Broadcast received: share=$shareId, path=$networkPath")

                if (shareId == share.id && networkPath != null) {
                    val index = files.indexOfFirst { it.path == networkPath }
                    if (index != -1) {
                        za.kilowatch.ultimatefilemanager.util.GoRoLog.d("UFM_CACHE", "Refreshing item at index $index")
                        notifyItemChanged(index)
                    } else {
                        za.kilowatch.ultimatefilemanager.util.GoRoLog.w("UFM_CACHE", "Path $networkPath not found in current list")
                    }
                }
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val filter = IntentFilter("za.kilowatch.ultimatefilemanager.ACTION_NETWORK_THUMBNAIL_CREATED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(thumbnailReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(thumbnailReceiver, filter)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        try {
            context.unregisterReceiver(thumbnailReceiver)
        } catch (_: Exception) {}
        adapterScope.coroutineContext.cancelChildren()
    }

    fun submitList(newFiles: List<NetworkFile>, searchBasePath: String? = null) {
        val filesCopy = newFiles.toList()
        files.clear()
        files.addAll(filesCopy)
        this.searchBasePath = searchBasePath
        childCountCache.clear()
        // Clean up selection if files were removed by a directory reload.
        // If retainAll drops any items (e.g. the server returned different metadata
        // for the same file), fire onSelectionChanged so the toolbar reflects the
        // real selection count rather than going stale.
        val prevSelectionSize = selectedFiles.size
        selectedFiles.retainAll(files.toSet())
        if (selectedFiles.size != prevSelectionSize) {
            onSelectionChanged(selectedFiles.size)
        }
        longPressAnchorIndex = RecyclerView.NO_POSITION

        items.clear()
        if (isGroupedByDate) {
            val folders = newFiles.filter { it.isDirectory }
            val fileList = newFiles.filter { !it.isDirectory }
            
            // Add folders first, ungrouped
            items.addAll(folders.map { za.kilowatch.ultimatefilemanager.storage.ListItem.NetworkEntry(it) })
            
            val collapsedSet = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.getCollapsedGroups(context)
            
            // Group only files by date
            val grouped = fileList.groupBy {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = it.lastModified
                Pair(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH))
            }.toSortedMap(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })
            
            for ((key, groupFiles) in grouped) {
                val groupKey = "${key.first}-${key.second}"
                val isCollapsed = collapsedSet.contains(groupKey)
                
                items.add(za.kilowatch.ultimatefilemanager.storage.ListItem.Header(key.first, key.second, groupFiles.size, isCollapsed))
                
                if (!isCollapsed) {
                    items.addAll(groupFiles.map { za.kilowatch.ultimatefilemanager.storage.ListItem.NetworkEntry(it) })
                }
            }
        } else {
            items.addAll(newFiles.map { za.kilowatch.ultimatefilemanager.storage.ListItem.NetworkEntry(it) })
        }
        
        notifyDataSetChanged()

        // Pre-compute directory child counts off the main thread
        childCountJob?.cancel()
        val dirs = newFiles.filter { it.isDirectory && it.freeSpace < 0 && it.iconRes == 0 }
        if (dirs.isNotEmpty()) {
            childCountJob = adapterScope.launch(Dispatchers.IO) {
                val counts = mutableMapOf<String, Int>()
                for (dir in dirs) {
                    if (!isActive) return@launch
                    try {
                        val rawFiles = when (share.type) {
                            za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.listFiles(share, dir.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.listFiles(share, dir.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.listFiles(share, dir.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, dir.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(share, dir.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, dir.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, dir.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, dir.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, dir.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, dir.path)
                            za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.listFiles(share, dir.path)
                        }
                        val visibleCount = rawFiles.count {
                            !za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isJunkOrHidden(it.name)
                        }
                        counts[dir.path] = visibleCount
                    } catch (e: Exception) {
                        za.kilowatch.ultimatefilemanager.util.GoRoLog.w("NetworkFileAdapter", "Failed to count files for directory ${dir.path}: ${e.message}")
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!isActive) return@withContext
                    childCountCache.putAll(counts)
                    notifyDataSetChanged()
                }
            }
        }

        // Prune stale cache entries for this folder asynchronously
        if (NetworkThumbnailPreferenceManager.isEnabled(context)) {
            adapterScope.launch(Dispatchers.IO) {
                cacheManager.pruneStaleThumbnails(share, newFiles)
            }
        }
    }
    
    fun getSelectedFiles(): List<NetworkFile> = selectedFiles.toList()

    fun hasAnySelectedProtected(context: Context, shareId: String): Boolean = selectedFiles.any { za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isProtected(context, it.path, shareId) }
    fun hasAnySelectedUnprotected(context: Context, shareId: String): Boolean = selectedFiles.any { !za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isProtected(context, it.path, shareId) }
    
    fun selectAll() {
        selectedFiles.addAll(files)
        longPressAnchorIndex = RecyclerView.NO_POSITION
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selectedFiles.size)
    }

    fun deselectAll() {
        selectedFiles.clear()
        longPressAnchorIndex = RecyclerView.NO_POSITION
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(0)
    }
    
    fun isAllSelected(): Boolean = selectedFiles.size == files.size && files.isNotEmpty()

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedFiles.clear()
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
        val item = items[position] as? za.kilowatch.ultimatefilemanager.storage.ListItem.NetworkEntry ?: return
        val file = item.file
        if (!isSelectionMode) {
            isSelectionMode = true
            longPressAnchorIndex = position
            selectedFiles.add(file)
        } else if (longPressAnchorIndex == RecyclerView.NO_POSITION) {
            // Already in selection mode but no anchor (e.g. after selectAll/deselectAll)
            longPressAnchorIndex = position
            selectedFiles.add(file)
        } else {
            // Already in selection mode with an anchor — do range selection
            selectRange(longPressAnchorIndex, position)
            longPressAnchorIndex = position
        }
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selectedFiles.size)
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
            if (entry is za.kilowatch.ultimatefilemanager.storage.ListItem.NetworkEntry) {
                selectedFiles.add(entry.file)
            }
            // Headers are automatically skipped
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        if (item is za.kilowatch.ultimatefilemanager.storage.ListItem.Header) return 3
        val isGrid = ViewModeManager.isGrid(viewMode)
        return when {
            isGrid    -> 1
            isCompact -> 2
            else      -> 0
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
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderViewHolder && item is za.kilowatch.ultimatefilemanager.storage.ListItem.Header) {
            holder.bind(item)
        } else if (holder is ViewHolder && item is za.kilowatch.ultimatefilemanager.storage.ListItem.NetworkEntry) {
            holder.bind(item.file)
        }
    }

    override fun getItemCount() = items.size

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtYear: TextView = itemView.findViewById(R.id.txtYear)
        private val txtMonth: TextView = itemView.findViewById(R.id.txtMonth)
        private val txtCount: TextView = itemView.findViewById(R.id.txtCount)

        fun bind(header: za.kilowatch.ultimatefilemanager.storage.ListItem.Header) {
            txtYear.text = header.year.toString()
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.MONTH, header.month)
            txtMonth.text = SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time)
            txtCount.text = itemView.context.getString(R.string.group_header_format_files, header.count)
            
            val imgCollapseToggle = itemView.findViewById<android.widget.ImageView>(R.id.imgCollapseToggle)
            if (isTv) {
                imgCollapseToggle?.visibility = android.view.View.GONE
                itemView.setOnClickListener(null)
                itemView.isFocusable = false
            } else {
                imgCollapseToggle?.visibility = android.view.View.VISIBLE
                imgCollapseToggle?.setImageResource(if (header.isCollapsed) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up)
                
                itemView.setOnClickListener {
                    val pm = za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager
                    val currentCollapsed = pm.getCollapsedGroups(context).toMutableSet()
                    val key = "${header.year}-${header.month}"
                    
                    if (header.isCollapsed) {
                        currentCollapsed.remove(key)
                    } else {
                        currentCollapsed.add(key)
                    }
                    pm.setCollapsedGroups(context, currentCollapsed)
                    
                    submitList(files, searchBasePath)
                }
            }
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgFileIcon)
        private val txtName: TextView = itemView.findViewById(R.id.txtFileName)
        private val txtDetails: TextView = itemView.findViewById(R.id.txtFileInfo)
        private val checkSelect: android.widget.CheckBox? = itemView.findViewById(R.id.checkSelect)
        private val progressDisk: android.widget.ProgressBar? = itemView.findViewById(R.id.progressDiskUsage)
        private val txtDisk: TextView? = itemView.findViewById(R.id.txtDiskUsage)
        private val circularDisk: za.kilowatch.ultimatefilemanager.ui.CircularProgressView? =
            itemView.findViewById(R.id.circularDiskUsage)
        private val iconContainer: View? = itemView.findViewById(R.id.iconContainer)
        private val context: Context = itemView.context

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos]
                    if (item is za.kilowatch.ultimatefilemanager.storage.ListItem.NetworkEntry) {
                        val file = item.file
                        if (isSelectionMode) {
                            val wasSelected = file in selectedFiles
                            toggleSelection(file)
                            if (!wasSelected) {
                                // File was newly added to the selection — update the anchor
                                // so the next long press ranges from this position.
                                longPressAnchorIndex = pos
                            }
                        } else {
                            onItemClick(file)
                        }
                    }
                }
            }
            
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos]
                    if (item is za.kilowatch.ultimatefilemanager.storage.ListItem.NetworkEntry) {
                        val file = item.file
                        if (!isSelectionMode) {
                            // First long press: enter selection mode, set anchor, select file
                            isSelectionMode = true
                            longPressAnchorIndex = pos
                            selectedFiles.add(file)
                            notifyItemRangeChanged(0, itemCount)
                            onSelectionChanged(selectedFiles.size)
                        } else if (longPressAnchorIndex == RecyclerView.NO_POSITION) {
                            // Already in selection mode but no anchor (e.g. after selectAll/deselectAll)
                            longPressAnchorIndex = pos
                            selectedFiles.add(file)
                            notifyItemRangeChanged(0, itemCount)
                            onSelectionChanged(selectedFiles.size)
                        } else if (longPressAnchorIndex != RecyclerView.NO_POSITION) {
                            // Second (or subsequent) long press while in selection mode:
                            // range-select from anchor to this position, then update anchor
                            selectRange(longPressAnchorIndex, pos)
                            longPressAnchorIndex = pos
                            notifyItemRangeChanged(0, itemCount)
                            onSelectionChanged(selectedFiles.size)
                        }
                    }
                }
                true
            }
        }
        
        private fun toggleSelection(file: NetworkFile) {
            if (selectedFiles.contains(file)) {
                selectedFiles.remove(file)
            } else {
                selectedFiles.add(file)
            }
            notifyItemChanged(bindingAdapterPosition)
            onSelectionChanged(selectedFiles.size)
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
        }        fun bind(file: NetworkFile) {
            // Cancel any previous Coil request or thumbnail job attached to this ViewHolder
            // to avoid stale images on recycled ViewHolders.
            try { itemView.findViewById<ImageView>(R.id.imgFileIcon)?.let { (it as ImageView).let { /* no-op */ } } } catch (e: Exception) {}
            // We'll dispose coilDisposable and cancel thumbnailJob at the start of bind
            coilDisposable?.dispose()
            coilDisposable = null
            thumbnailJob?.cancel()

            // Protected status indicator
            val isItemProtected = za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isProtected(context, file.path, share.id)
            itemView.findViewById<ImageView>(R.id.imgProtectedBadge)?.visibility = if (isItemProtected) View.VISIBLE else View.GONE

            val isEnabled = NetworkThumbnailPreferenceManager.isEnabled(context)
            za.kilowatch.ultimatefilemanager.util.GoRoLog.d("UFM_CACHE", "🚀 [GoRo] Binding ${file.name}, thumbnails enabled=$isEnabled")
            
            val isGrid = ViewModeManager.isGrid(viewMode)

            val ext = file.name.substringAfterLast('.', "").lowercase()
            val imageExts = setOf("jpg", "jpeg", "png", "bmp", "webp", "gif")
            val apkExts   = setOf("apk", "xapk", "apks")
            val isMedia = ext in imageExts || ext in za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager.VIDEO_EXTENSIONS || ext in apkExts
            val isThumbnail = !file.isDirectory && isEnabled && isMedia

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
                txtDetails.textSize = subtitleSp
                itemView.findViewById<TextView>(R.id.txtFileSize)?.textSize = subtitleSp
            }
            
            val base = searchBasePath
            val displayName = if (base != null && file.path.startsWith(base)) {
                val relative = file.path.substring(base.length).removePrefix("/")
                if (relative.contains("/")) relative else file.name
            } else {
                file.name
            }
            txtName.text = displayName

            // Reset image/tint state
            imgIcon.scaleType = if (isGrid) {
                if (file.isDirectory || ext in apkExts || (ext !in imageExts && ext !in za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager.VIDEO_EXTENSIONS)) {
                    ImageView.ScaleType.FIT_CENTER
                } else {
                    ImageView.ScaleType.CENTER_CROP
                }
            } else {
                ImageView.ScaleType.FIT_CENTER
            }
            imgIcon.imageTintList = null
            imgIcon.clipToOutline = false
            stopPulse()

            // Remove all background circles and padding for consistency across grid and list
            imgIcon.setPadding(0, 0, 0, 0)
            iconContainer?.setBackgroundResource(0)

            if (file.iconRes != 0) {
                // Custom icon (e.g. sideload items)
                imgIcon.setImageResource(file.iconRes)
                imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ufm_primary))
                if (!isGrid) {
                    progressDisk?.visibility = View.GONE
                    txtDisk?.visibility = View.GONE
                    circularDisk?.visibility = View.GONE
                    txtDetails.text = ""
                }
            } else if (file.isDirectory) {
                imgIcon.setImageResource(IconCustomizationManager.getEffectiveIconRes(context, "folder_default", R.drawable.ic_folder))
                val iconTintColor = if (isTv) DefaultIconColorManager.getTvIconTint(context) else DefaultIconColorManager.getMobileIconTint(context)
                imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(iconTintColor)
                if (!isGrid) {
                    // Show disk usage bar if the directory has storage info (TV drive entries)
                    if (file.freeSpace >= 0 && file.size > 0) {
                        val usedBytes = file.size - file.freeSpace
                        val usedPct = ((usedBytes.toDouble() / file.size) * 100).toInt().coerceIn(0, 100)
                        val freeStr = Formatter.formatFileSize(context, file.freeSpace)
                        val totalStr = Formatter.formatFileSize(context, file.size)
                        val tintColor = when {
                            usedPct >= 90 -> ContextCompat.getColor(context, R.color.ufm_denied)    // red
                            usedPct >= 75 -> ContextCompat.getColor(context, R.color.ufm_pending)   // orange
                            else          -> ContextCompat.getColor(context, R.color.ufm_primary)    // teal
                        }
                        // Linear bar
                        progressDisk?.apply {
                            visibility = View.VISIBLE
                            progress = usedPct
                            progressTintList = android.content.res.ColorStateList.valueOf(tintColor)
                        }
                        // Detail text under bar
                        txtDisk?.apply {
                            visibility = View.VISIBLE
                            text = context.getString(R.string.freestr_free_of_totalstr, freeStr, totalStr)
                        }
                        txtDetails.text = context.getString(R.string.usedpct_used, usedPct)
                        // Circular donut indicator
                        circularDisk?.apply {
                            visibility = View.VISIBLE
                            percentLabel = "used"
                            showPercentText = true
                            setProgressColor(tintColor)
                            progress = usedPct
                        }
                    } else {
                        progressDisk?.visibility = View.GONE
                        txtDisk?.visibility = View.GONE
                        circularDisk?.visibility = View.GONE
                        val childCount = childCountCache[file.path]
                        val dateStr = if (file.lastModified > 0) dateFormat.format(Date(file.lastModified)) else ""
                        if (childCount != null) {
                            val itemsText = "$childCount item${if (childCount != 1) "s" else ""}"
                            txtDetails.text = if (dateStr.isNotEmpty()) "$itemsText · $dateStr" else itemsText
                        } else {
                            txtDetails.text = dateStr
                        }
                    }
                } else {
                    // Hide overlays in grid
                    itemView.findViewById<View>(R.id.viewSelectionOverlay)?.visibility = View.GONE
                }
            } else {
                imgIcon.setImageResource(FileTypeIconProvider.iconForExtension(context, file.name.substringAfterLast('.', "")))
                val fileTintColor = if (isTv) DefaultIconColorManager.getTvIconTint(context) else DefaultIconColorManager.getMobileIconTint(context)
                imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(fileTintColor)
                if (!isGrid) {
                    val sizeStr = Formatter.formatFileSize(context, file.size)
                    val dateStr = if (file.lastModified > 0) dateFormat.format(Date(file.lastModified)) else ""
                    txtDetails.text = if (dateStr.isNotEmpty()) "$sizeStr • $dateStr" else sizeStr
                }
                
                // Attempt thumbnail loading if enabled
                if (isEnabled) {
                    loadThumbnail(file)
                }
            }
            
            val isSelected = selectedFiles.contains(file)
            if (isSelectionMode) {
                checkSelect?.visibility = View.VISIBLE
                checkSelect?.isChecked = isSelected
                if (isGrid) {
                    itemView.findViewById<View>(R.id.viewSelectionOverlay)?.visibility = if (isSelected) View.VISIBLE else View.GONE
                } else {
                    itemView.isActivated = isSelected
                }
            } else {
                checkSelect?.visibility = View.GONE
                checkSelect?.isChecked = false
                if (isGrid) {
                    itemView.findViewById<View>(R.id.viewSelectionOverlay)?.visibility = View.GONE
                } else {
                    itemView.isActivated = false
                }
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
                            txtDetails.setTextColor(black)
                            itemView.findViewById<TextView>(R.id.txtFileSize)?.setTextColor(black)
                        }
                        if (!isThumbnail) imgIcon.imageTintList = blackCsl
                    } else {
                        if (isGrid) {
                            applyGridTextColor(file)
                        } else {
                            txtName.setTextColor(white)
                            txtDetails.setTextColor(secondary)
                            itemView.findViewById<TextView>(R.id.txtFileSize)?.setTextColor(hint)
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

            // Toggle item handling (e.g. "Enable WiFi Remote" switch)
            val switchToggle = itemView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchToggle)
            val imgChevron = itemView.findViewById<ImageView>(R.id.imgChevron)
            val txtFileSize = itemView.findViewById<TextView>(R.id.txtFileSize)
            if (file.isToggle && !isGrid) {
                switchToggle?.visibility = View.VISIBLE
                imgChevron?.visibility = View.GONE
                txtFileSize?.visibility = View.GONE
                // Show subtitle text for toggle state
                txtDetails.text = if (file.isToggled) {
                    context.getString(R.string.use_remote_enabled_subtitle)
                } else {
                    context.getString(R.string.use_remote_disabled_subtitle)
                }
                txtDetails.visibility = View.VISIBLE
                switchToggle.setOnCheckedChangeListener(null)
                switchToggle.isChecked = file.isToggled
                switchToggle.setOnCheckedChangeListener { _, isChecked ->
                    onToggleChanged?.invoke(file, isChecked)
                }
            } else {
                switchToggle?.visibility = View.GONE
                imgChevron?.visibility = View.VISIBLE
            }
        }

        private var thumbnailJob: Job? = null
        private var coilDisposable: Disposable? = null

        private fun loadThumbnail(file: NetworkFile) {
            // Cancel any previous extraction/coils for this holder
            thumbnailJob?.cancel()
            coilDisposable?.dispose()
            coilDisposable = null

            // Tag the ImageView so any async result can verify the holder is still bound
            imgIcon.tag = file.path
            
            val ext = file.name.substringAfterLast('.', "").lowercase()
            val imageExts = setOf("jpg", "jpeg", "png", "bmp", "webp", "gif")
            val apkExts   = setOf("apk", "xapk", "apks")
            // VIDEO_EXTENSIONS is the single authoritative list shared with NetworkThumbnailCacheManager
            val isMedia = ext in imageExts || ext in za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager.VIDEO_EXTENSIONS || ext in apkExts
            if (!isMedia) {
                za.kilowatch.ultimatefilemanager.util.GoRoLog.d("UFM_CACHE", "🚀 [GoRo] Skipping non-media file: ${file.name}")
                imgIcon.setImageResource(FileTypeIconProvider.iconForExtension(context, file.name.substringAfterLast('.', "")))
                return
            }

            za.kilowatch.ultimatefilemanager.util.GoRoLog.d("UFM_CACHE", "🚀 [GoRo] Attempting loadThumbnail for: ${file.name}, scope_active=${adapterScope.isActive}")

            val cachedPath = thumbnailPathCache[file.path]
            val isGridMode = ViewModeManager.isGrid(viewMode)
            if (cachedPath != null && File(cachedPath).exists()) {
                imgIcon.imageTintList = null
                imgIcon.setPadding(0, 0, 0, 0)
                imgIcon.scaleType = if (ext in apkExts) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                imgIcon.clipToOutline = true
                imgIcon.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        val radius = 10f * view.context.resources.displayMetrics.density
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }

                val placeholderImage = ContextCompat.getDrawable(context, R.drawable.ic_photo_video)?.asImage()
                val fileTypeDrawable = ContextCompat.getDrawable(context, FileTypeIconProvider.iconForExtension(ext))
                val fileTypeImage = fileTypeDrawable?.asImage()

                coilDisposable = imgIcon.load(File(cachedPath)) {
                    crossfade(200)
                    allowHardware(false)
                    placeholder(placeholderImage)
                    error(fileTypeImage ?: placeholderImage)
                    if (!isGridMode) {
                        scale(Scale.FILL)
                    }
                    listener(
                        onSuccess = { _, _ ->
                            if (isGridMode) {
                                updateTextColorForDrawable(imgIcon.drawable, true)
                            }
                        }
                    )
                }
                return
            }

            imgIcon.setImageResource(R.drawable.ic_photo_video)

            thumbnailJob = adapterScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    startPulse()
                    za.kilowatch.ultimatefilemanager.util.GoRoLog.d("UFM_CACHE", "🚀 [GoRo] Coroutine started for: ${file.name}")
                    val thumbnailPath = withContext(Dispatchers.IO) {
                        cacheManager.getThumbnail(share, file)
                    }
                    
                    val placeholderImage = ContextCompat.getDrawable(context, R.drawable.ic_photo_video)?.asImage()

                    if (thumbnailPath != null && isActive) {
                        thumbnailPathCache[file.path] = thumbnailPath
                        imgIcon.imageTintList = null
                        // Clear background, padding, outline for both list and grid thumbnails
                        iconContainer?.setBackgroundResource(0)
                        imgIcon.setPadding(0, 0, 0, 0)
                        imgIcon.scaleType = if (ext in apkExts) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                        imgIcon.clipToOutline = true
                        imgIcon.outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: android.graphics.Outline) {
                                val radius = 10f * view.context.resources.displayMetrics.density
                                outline.setRoundRect(0, 0, view.width, view.height, radius)
                            }
                        }

                        // Prepare a file-type fallback drawable for Coil `error`
                        val fileTypeDrawable = ContextCompat.getDrawable(context, FileTypeIconProvider.iconForExtension(ext))
                        val fileTypeImage = fileTypeDrawable?.asImage()

                        // Only apply if this ViewHolder is still bound to the same file
                        if (imgIcon.tag == file.path) {
                            coilDisposable = imgIcon.load(File(thumbnailPath)) {
                                crossfade(200)
                                allowHardware(false)
                                placeholder(placeholderImage)
                                error(fileTypeImage ?: placeholderImage)
                                if (!isGridMode) {
                                    scale(Scale.FILL)
                                }
                                listener(
                                    onSuccess = { _, _ ->
                                        if (isGridMode) {
                                            updateTextColorForDrawable(imgIcon.drawable, true)
                                        }
                                    }
                                )
                            }
                        }
                    } else if (isActive) {
                        // Ensure we show the standard file-type icon and apply the usual tint
                        // Clear tag because we're showing a static drawable
                        imgIcon.tag = null
                        imgIcon.setImageResource(FileTypeIconProvider.iconForExtension(ext))
                        if (!isGridMode) {
                            imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                                if (isTv) DefaultIconColorManager.getTvIconTint(context) else DefaultIconColorManager.getMobileIconTint(context)
                            )
                            imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                        } else {
                            imgIcon.imageTintList = null
                            imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                    }
                } catch (e: Exception) {
                    za.kilowatch.ultimatefilemanager.util.GoRoLog.e("UFM_CACHE", "🚀 [GoRo] Exception in loadThumbnail for ${file.name}", e)
                    // Fallback to default icon on error
                    if (isActive) {
                        imgIcon.setImageResource(FileTypeIconProvider.iconForExtension(ext))
                        imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                            if (isTv) DefaultIconColorManager.getTvIconTint(context) else DefaultIconColorManager.getMobileIconTint(context)
                        )
                        imgIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                } finally {
                    if (imgIcon.tag == file.path) {
                        stopPulse()
                    }
                }
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

        private fun applyGridTextColor(file: NetworkFile) {
            val ext = file.name.substringAfterLast('.', "").lowercase()
            val imageExts = setOf("jpg", "jpeg", "png", "bmp", "webp", "gif")
            val apkExts   = setOf("apk", "xapk", "apks")
            val isEnabled = NetworkThumbnailPreferenceManager.isEnabled(context)
            val isMedia = ext in imageExts || ext in za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager.VIDEO_EXTENSIONS || ext in apkExts
            val hasThumbnail = !file.isDirectory && isEnabled && isMedia

            updateTextColorForDrawable(imgIcon.drawable, hasThumbnail && imgIcon.drawable != null && imgIcon.tag == file.path)
        }
    }
}
