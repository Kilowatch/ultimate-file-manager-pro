package za.kilowatch.ultimatefilemanager.storage

import android.animation.LayoutTransition
import android.content.Context
import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.view.DragEvent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.settings.DefaultIconColorManager
import za.kilowatch.ultimatefilemanager.settings.LongPressDurationManager
import za.kilowatch.ultimatefilemanager.ui.CircularProgressView
import za.kilowatch.ultimatefilemanager.network.ShareType

/**
 * Sets a tile icon from a resource ID, tolerating stale persisted IDs — e.g. a
 * custom-tile icon saved before its drawable was renamed or removed by resource
 * shrinking. Never crashes: falls back to the default folder icon.
 */
private fun ImageView.safeSetIcon(resId: Int) {
    if (resId == 0) {
        setImageResource(R.drawable.ic_folder)
        return
    }
    try {
        setImageResource(resId)
    } catch (_: Exception) {
        setImageResource(R.drawable.ic_folder)
    }
}

/**
 * RecyclerView adapter for the main StorageBrowser screen.
 *
 * @param isTv           Whether to use the TV tile layout.
 * @param onStorageClick Callback when a tile is tapped normally.
 * @param onLongPress    Fires when the user long-presses a tile.
 *                       Mobile: triggered after a 2-second touch hold.
 *                       TV:     triggered on long-press of the OK / DPAD_CENTER key.
 *                       Receives the [StorageItem] AND the [RecyclerView.ViewHolder] so
 *                       the Activity can call ItemTouchHelper.startDrag() for mobile.
 */class StorageAdapter(
    private val isTv: Boolean,
    private val onStorageClick: (StorageItem) -> Unit,
    private val onLongPress: ((StorageItem, RecyclerView.ViewHolder) -> Unit)? = null,
    var onHideClick: ((StorageItem) -> Unit)? = null,
    var onEditModeClick: ((StorageItem) -> Unit)? = null,
    var onEjectClick: ((StorageItem) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    var onTileFocused: ((StorageItem) -> Unit)? = null

    private val rawItems = mutableListOf<StorageItem>()
    private val items = mutableListOf<StorageItem>()
    private var tileColors: Map<String, TileColorConfig> = emptyMap()
    private var tileIcons: Map<String, String> = emptyMap()
    private var tileIconRes: Map<String, Int> = emptyMap()

    var onCategoryHeaderToggled: ((categoryId: String, isExpanded: Boolean) -> Unit)? = null
    var onTileDroppedIntoCustomTile: ((tileId: String, customTileId: String) -> Unit)? = null
    var onEnterEditModeRequested: (() -> Unit)? = null

    fun setTileColors(colors: Map<String, TileColorConfig>) {
        if (tileColors == colors) return
        tileColors = colors
        safeNotifyDataSetChanged()
    }

    fun getTileColor(tileId: String): TileColorConfig {
        return tileColors[tileId] ?: TileColorConfig()
    }

    fun setTileIcons(icons: Map<String, String>) {
        if (tileIcons == icons) return
        tileIcons = icons
        safeNotifyDataSetChanged()
    }

    fun getTileIcon(tileId: String): String? {
        return tileIcons[tileId]?.takeIf { it.isNotEmpty() }
    }

    fun setTileIconRes(res: Map<String, Int>) {
        if (tileIconRes == res) return
        tileIconRes = res
        safeNotifyDataSetChanged()
    }

    /** When true, tiles pulse and show a hide (X) button if they are hideable. */
    var isEditMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            safeNotifyDataSetChanged()
        }

    /**
     * Set to the ID of the tile currently in TV reorder mode.
     * While set, that tile renders with an enlarged scale and a highlighted stroke.
     */
    var reorderModeId: String? = null
        set(value) {
            if (field == value) return
            field = value
            safeNotifyDataSetChanged()
        }

    private fun isFeatureTile(item: StorageItem): Boolean =
        item.isAppsTile || item.isRemoteTile || item.isTvRemoteTile || item.isSearchTile ||
        item.isAnalyzerTile || item.isVaultTile ||
        item.isLegalTile || item.isRateUsTile || item.isSafTile ||
        item.isNetworkTile || item.isPairedDevicesTile || item.isExtractsTile ||
        item.isTipJarTile || item.isSyncTile || item.isAdvancedSyncTile || item.isTwinWindowTile || item.isShizukuTile || item.isTerminalTile || item.isFileServerTile || item.isSupportTile || item.isAboutTile ||
        item.isNotepadTile || item.isRecycleBinTile || item.isScannerTile ||
        item.isSmartSortTile || item.isOnlineStoragesTile || item.isFavoriteTile ||
        item.isRecentFilesTile ||
        item.isSettingsTile || item.isCustomTile

    fun defaultCategoryForTile(item: StorageItem): String {
        return when {
            // Category 1: Storage (explicitly include Recent Files)
            item.isRecentFilesTile -> MainMenuViewModeManager.CATEGORY_STORAGE

            // Category 2: Connect
            item.isNetworkTile || item.isOnlineStoragesTile || item.isFileServerTile ||
            item.isPairedDevicesTile || item.isTvRemoteTile || item.isRemoteTile -> MainMenuViewModeManager.CATEGORY_CONNECT

            // Category 3: Folder Sync
            item.isSyncTile || item.isAdvancedSyncTile -> MainMenuViewModeManager.CATEGORY_SYNC

            // Category 4: Organize
            item.isCustomTile || item.isFavoriteTile || item.isSearchTile ||
            item.isSmartSortTile || item.isAnalyzerTile || item.isRecycleBinTile -> MainMenuViewModeManager.CATEGORY_ORGANIZE

            // Category 5: Utilities
            item.isScannerTile || item.isNotepadTile || item.isAppsTile ||
            item.isExtractsTile || item.isTwinWindowTile -> MainMenuViewModeManager.CATEGORY_UTILITIES

            // Category 6: Security & Advanced
            item.isVaultTile || item.isTerminalTile || item.isShizukuTile -> MainMenuViewModeManager.CATEGORY_SECURITY

            // Category 7: Settings & More
            item.isSettingsTile || item.isSupportTile || item.isAboutTile ||
            item.isLegalTile || item.isTipJarTile || item.isRateUsTile -> MainMenuViewModeManager.CATEGORY_SETTINGS

            // Category 1: Storage (Physical Drives, Mounted SMB/FTP Shares, Mounted Cloud Accounts, Connected TV Mounts, SAF)
            else -> MainMenuViewModeManager.CATEGORY_STORAGE
        }
    }

    fun categoryForTile(item: StorageItem, context: android.content.Context? = null): String {
        val ctx = context ?: adapterContext
        if (ctx != null) {
            val customCat = MainMenuViewModeManager.loadTileCategory(ctx, item.id)
            if (customCat != null) return customCat
        }
        return defaultCategoryForTile(item)
    }

    private var adapterContext: android.content.Context? = null
    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        adapterContext = recyclerView.context
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecyclerView == recyclerView) {
            attachedRecyclerView = null
        }
        // Reset the pending-notify flag so it can't get stuck if the adapter
        // is detached while a post() is still queued.
        notifyScheduled = false
    }

    /**
     * Deduplication flag: true while a [notifyDataSetChanged] is already posted
     * and waiting in the RecyclerView's message queue.
     */
    private var notifyScheduled = false

    /**
     * Safely dispatches [notifyDataSetChanged].
     *
     * **Always posts to the next looper frame** via [RecyclerView.post], which:
     *  1. Guarantees the notification never fires *inside* an active layout pass
     *     ([RecyclerView.isComputingLayout]) or while one is already queued
     *     ([RecyclerView.isLayoutRequested]), preventing the
     *     *"attach on child which is not detached"* [IllegalArgumentException].
     *  2. **Deduplicates** rapid-fire calls — `updateTileDecorations` + `submitList` +
     *     view-mode changes can all trigger this within the same main-thread batch;
     *     the [notifyScheduled] flag ensures only ONE actual rebind is issued per frame.
     *
     * **The notification is deferred, not synchronous.** This used to call
     * [notifyDataSetChanged] inline whenever the RecyclerView was not mid-layout; it now always
     * queues. A caller that dispatches and then immediately reads adapter or list state observes
     * the pre-notification value for one frame. Nothing on the eject path depends on that
     * visibility — it acts on the volume's descriptors, not on the adapter — but new callers
     * should not assume `safe` means `immediate`.
     */
    fun safeNotifyDataSetChanged() {
        val rv = attachedRecyclerView
        if (rv == null) {
            // No RecyclerView attached yet — safe to call directly.
            try {
                notifyDataSetChanged()
            } catch (_: IllegalStateException) {}
            return
        }
        if (!notifyScheduled) {
            notifyScheduled = true
            rv.post(object : Runnable {
                override fun run() {
                    val currentRv = attachedRecyclerView
                    if (currentRv == null) {
                        notifyScheduled = false
                        return
                    }
                    if (currentRv.isComputingLayout) {
                        // RecyclerView is actively computing layout or scrolling; defer execution
                        currentRv.post(this)
                    } else {
                        notifyScheduled = false
                        try {
                            notifyDataSetChanged()
                        } catch (_: IllegalStateException) {
                            // Defensive recovery in case layout pass started concurrently
                            currentRv.post {
                                if (attachedRecyclerView != null) {
                                    try {
                                        notifyDataSetChanged()
                                    } catch (_: IllegalStateException) {}
                                }
                            }
                        }
                    }
                }
            })
        }
        // else: a notification is already queued for the next frame — no-op.
    }

    /**
     * Batch-updates tile colors, icons, and icon resources in a single pass, emitting
     * at most ONE [notifyDataSetChanged] at the end instead of three separate ones.
     * Use this instead of calling [setTileColors] / [setTileIcons] / [setTileIconRes]
     * individually when all three values are available at the same time (e.g. inside
     * [za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity.loadStorageVolumes]).
     */
    fun updateTileDecorations(
        colors: Map<String, TileColorConfig>,
        icons: Map<String, String>,
        iconRes: Map<String, Int>
    ) {
        var changed = false
        if (tileColors != colors) { tileColors = colors; changed = true }
        if (tileIcons  != icons)  { tileIcons  = icons;  changed = true }
        if (tileIconRes != iconRes) { tileIconRes = iconRes; changed = true }
        if (changed) safeNotifyDataSetChanged()
    }

    fun refreshDisplayedList(context: android.content.Context? = null) {
        val ctx = context ?: adapterContext
        if (viewMode == MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED && !isTv) {
            val categorized = mutableListOf<StorageItem>()

            val allCategoriesInfo = mapOf(
                MainMenuViewModeManager.CATEGORY_STORAGE to Pair(R.string.main_menu_category_storage, R.drawable.ic_storage_internal),
                MainMenuViewModeManager.CATEGORY_CONNECT to Pair(R.string.main_menu_category_connect, R.drawable.ic_network),
                MainMenuViewModeManager.CATEGORY_SYNC to Pair(R.string.main_menu_category_sync, R.drawable.ic_sync),
                MainMenuViewModeManager.CATEGORY_ORGANIZE to Pair(R.string.main_menu_category_organize, R.drawable.ic_folder_special),
                MainMenuViewModeManager.CATEGORY_UTILITIES to Pair(R.string.main_menu_category_utilities, R.drawable.ic_apps),
                MainMenuViewModeManager.CATEGORY_SECURITY to Pair(R.string.main_menu_category_security, R.drawable.ic_shield),
                MainMenuViewModeManager.CATEGORY_SETTINGS to Pair(R.string.main_menu_category_settings, R.drawable.ic_settings)
            )

            val customCats = if (ctx != null) MainMenuViewModeManager.loadCustomCategories(ctx) else emptyMap()
            val savedCatOrder = if (ctx != null) MainMenuViewModeManager.loadCategoryOrder(ctx) else MainMenuViewModeManager.DEFAULT_CATEGORY_ORDER

            for (catId in savedCatOrder) {
                val isBuiltin = allCategoriesInfo.containsKey(catId)
                val isCustom = customCats.containsKey(catId)
                if (!isBuiltin && !isCustom) continue

                val titleStr = if (isBuiltin) {
                    val titleRes = allCategoriesInfo[catId]!!.first
                    if (ctx != null) {
                        try { ctx.getString(titleRes) } catch (_: Exception) { catId }
                    } else catId
                } else {
                    customCats[catId] ?: catId
                }

                val iconRes = if (isBuiltin) {
                    allCategoriesInfo[catId]!!.second
                } else {
                    R.drawable.ic_category_header
                }

                val groupItems = rawItems.filter { categoryForTile(it, ctx) == catId }
                if (groupItems.isNotEmpty() || isCustom) {
                    val expanded = if (ctx != null) MainMenuViewModeManager.isCategoryExpanded(ctx, catId) else (catId == MainMenuViewModeManager.CATEGORY_STORAGE)
                    val header = StorageItem(
                        id = "header_$catId",
                        label = titleStr,
                        iconRes = iconRes,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = "",
                        isCategoryHeader = true,
                        categoryId = catId,
                        isCategoryExpanded = expanded,
                        categoryItemCount = groupItems.size,
                        categoryGroupItems = groupItems
                    )
                    categorized.add(header)
                }
            }

            items.clear()
            items.addAll(categorized)
        } else {
            items.clear()
            items.addAll(rawItems)
        }
    }

    fun submitList(newItems: List<StorageItem>, context: android.content.Context? = null) {
        if (context != null) adapterContext = context
        rawItems.clear()
        rawItems.addAll(newItems.filter { !it.isCategoryHeader })
        refreshDisplayedList(context)
        safeNotifyDataSetChanged()
    }

    fun addItem(item: StorageItem) {
        if (item.isCategoryHeader) return
        rawItems.add(item)
        refreshDisplayedList()
        safeNotifyDataSetChanged()
    }

    fun removeById(id: String) {
        val rawIdx = rawItems.indexOfFirst { it.id == id }
        if (rawIdx >= 0) {
            rawItems.removeAt(rawIdx)
            refreshDisplayedList()
            safeNotifyDataSetChanged()
        }
    }

    /**
     * Swap item at [from] with item at [to] and animate the move.
     * Used by [androidx.recyclerview.widget.ItemTouchHelper] during live drag on mobile.
     */
    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return

        items.add(to, items.removeAt(from))
        val rv = attachedRecyclerView
        if (rv != null && rv.isComputingLayout) {
            rv.post {
                try {
                    notifyItemMoved(from, to)
                } catch (_: IllegalStateException) {}
            }
        } else {
            try {
                notifyItemMoved(from, to)
            } catch (_: IllegalStateException) {
                rv?.post {
                    try {
                        notifyItemMoved(from, to)
                    } catch (_: IllegalStateException) {}
                }
            }
        }

        if (viewMode == MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED && !isTv) {
            val ctx = adapterContext
            val newRaw = mutableListOf<StorageItem>()
            for (header in items) {
                val catId = header.categoryId ?: continue
                val catTiles = rawItems.filter { categoryForTile(it, ctx) == catId }
                newRaw.addAll(catTiles)
            }
            for (raw in rawItems) {
                if (newRaw.none { it.id == raw.id }) {
                    newRaw.add(raw)
                }
            }
            rawItems.clear()
            rawItems.addAll(newRaw)
        } else {
            val nonHeaders = items.filter { !it.isCategoryHeader }
            val newRaw = mutableListOf<StorageItem>()
            for (nh in nonHeaders) {
                val found = rawItems.find { it.id == nh.id } ?: nh
                newRaw.add(found)
            }
            for (raw in rawItems) {
                if (newRaw.none { it.id == raw.id }) {
                    newRaw.add(raw)
                }
            }
            rawItems.clear()
            rawItems.addAll(newRaw)
        }
    }

    fun onDragFinished(context: android.content.Context? = null, draggedItem: StorageItem? = null) {
        val ctx = context ?: adapterContext
        if (viewMode == MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED && !isTv && ctx != null) {
            // 1. Extract and save new category header order
            val newCatOrder = items.filter { it.isCategoryHeader }.mapNotNull { it.categoryId }.distinct()
            if (newCatOrder.isNotEmpty()) {
                MainMenuViewModeManager.saveCategoryOrder(ctx, newCatOrder)
            }

            // 2. Persist rawItems order to TileOrderManager
            val orderedIds = rawItems.map { it.id }
            TileOrderManager.save(ctx, orderedIds)

            // 3. Extract and save tile category memberships ONLY when a regular tile was dragged.
            // When a category header is dragged, we only reorder the categories themselves without
            // reassigning the category memberships of the tiles under them.
            if (draggedItem == null || !draggedItem.isCategoryHeader) {
                var currentCat: String? = null
                val categoryMap = MainMenuViewModeManager.loadAllTileCategories(ctx).toMutableMap()
                for (item in items) {
                    if (item.isCategoryHeader) {
                        currentCat = item.categoryId
                    } else if (currentCat != null) {
                        categoryMap[item.id] = currentCat
                    }
                }
                MainMenuViewModeManager.saveAllTileCategories(ctx, categoryMap)
            }
        }
        refreshDisplayedList(ctx)
        safeNotifyDataSetChanged()
    }

    fun reorderTile(context: Context, draggedItemId: String, targetCatId: String, targetIndex: Int) {
        val draggedItem = rawItems.find { it.id == draggedItemId } ?: return

        // 1. Remove from current position in rawItems
        rawItems.removeAll { it.id == draggedItemId }

        // 2. Persist new category assignment
        MainMenuViewModeManager.saveTileCategory(context, draggedItemId, targetCatId)

        // 3. Find remaining items in target category
        val catItems = rawItems.filter { categoryForTile(it, context) == targetCatId }

        if (catItems.isEmpty()) {
            val savedCatOrder = MainMenuViewModeManager.loadCategoryOrder(context)
            val targetCatOrderIdx = savedCatOrder.indexOf(targetCatId)
            var inserted = false
            if (targetCatOrderIdx >= 0) {
                for (idx in (targetCatOrderIdx + 1) until savedCatOrder.size) {
                    val nextCat = savedCatOrder[idx]
                    val firstItemOfNextCat = rawItems.firstOrNull { categoryForTile(it, context) == nextCat }
                    if (firstItemOfNextCat != null) {
                        val rawInsertIdx = rawItems.indexOfFirst { it.id == firstItemOfNextCat.id }
                        if (rawInsertIdx >= 0) {
                            rawItems.add(rawInsertIdx, draggedItem)
                            inserted = true
                            break
                        }
                    }
                }
            }
            if (!inserted) {
                rawItems.add(draggedItem)
            }
        } else {
            val clampedIndex = targetIndex.coerceIn(0, catItems.size)
            if (clampedIndex < catItems.size) {
                val pivotItem = catItems[clampedIndex]
                val rawInsertIdx = rawItems.indexOfFirst { it.id == pivotItem.id }
                if (rawInsertIdx >= 0) {
                    rawItems.add(rawInsertIdx, draggedItem)
                } else {
                    rawItems.add(draggedItem)
                }
            } else {
                val lastItem = catItems.last()
                val rawInsertIdx = rawItems.indexOfFirst { it.id == lastItem.id }
                if (rawInsertIdx >= 0) {
                    rawItems.add(rawInsertIdx + 1, draggedItem)
                } else {
                    rawItems.add(draggedItem)
                }
            }
        }

        // 4. Persist global order
        val orderedIds = rawItems.map { it.id }
        TileOrderManager.save(context, orderedIds)

        // 5. Refresh adapter and notify
        refreshDisplayedList(context)
        safeNotifyDataSetChanged()
    }

    fun getItems(): List<StorageItem> = items.toList()
    fun getRawItems(): List<StorageItem> = rawItems.toList()

    companion object {
        const val VIEW_TYPE_STORAGE = 0
        const val VIEW_TYPE_GRID = 1
        const val VIEW_TYPE_CATEGORY_HEADER = 2
    }

    var viewMode = MainMenuViewModeManager.ViewMode.LIST
        set(value) {
            if (field == value) return
            field = value
            refreshDisplayedList()
            safeNotifyDataSetChanged()
        }

    var itemSize = MainMenuViewModeManager.ItemSize.MEDIUM
        set(value) {
            if (field == value) return
            field = value
            safeNotifyDataSetChanged()
        }

    var gridColumnCount = 3
        set(value) {
            if (field == value) return
            field = value
            safeNotifyDataSetChanged()
        }

    /**
     * When > 0, the adapter forces the inner ConstraintLayout of each TV grid tile to
     * exactly this height (px). Set by [StorageBrowserActivity] after layout so that
     * exactly 2 complete rows fill the RecyclerView — no partial rows.
     */
    var gridItemHeightPx: Int = -1
        set(value) {
            if (field == value) return
            field = value
            safeNotifyDataSetChanged()
        }

    /**
     * Helper used by [StorageBrowserActivity] to sync TV grid tile dimensions.
     */
    fun updateItemSize(width: Int, height: Int) {
        this.gridItemHeightPx = height
    }

    /**
     * When true, a short D-Pad OK press opens the colour picker instead of hiding the tile.
     * Set by [StorageBrowserActivity] when the palette button is pressed.
     */
    var isColorPickMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            safeNotifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int {
        val item = items.getOrNull(position)
        if (item?.isCategoryHeader == true) return VIEW_TYPE_CATEGORY_HEADER
        return if (viewMode == MainMenuViewModeManager.ViewMode.GRID) VIEW_TYPE_GRID else VIEW_TYPE_STORAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_CATEGORY_HEADER -> {
                val layoutRes = if (isTv) R.layout.item_storage_category_header else R.layout.item_storage_category_group
                val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
                if (isTv) CategoryHeaderViewHolder(view) else CategoryGroupViewHolder(view)
            }
            VIEW_TYPE_GRID -> {
                val layoutRes = if (isTv) R.layout.item_storage_grid_tv else R.layout.item_storage_grid
                val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
                StorageViewHolder(view)
            }
            else -> {
                val layoutRes = if (isTv) R.layout.item_storage_card_tv else R.layout.item_storage_card
                val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
                StorageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items.getOrNull(position) ?: return
        when (holder) {
            is CategoryGroupViewHolder -> holder.bind(item)
            is CategoryHeaderViewHolder -> holder.bind(item)
            is StorageViewHolder -> holder.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        holder.itemView.translationX = 0f
        holder.itemView.translationY = 0f
        holder.itemView.elevation = 0f
        if (holder is CategoryGroupViewHolder) {
            holder.itemView.findViewById<LinearLayout>(R.id.layoutCategoryItems)?.removeAllViews()
        }
        if (holder is StorageViewHolder) {
            holder.itemView.findViewById<View>(R.id.cardStorage)?.let { card ->
                card.animate().cancel()
                card.scaleX = 1f
                card.scaleY = 1f
                card.translationX = 0f
                card.translationY = 0f
                card.elevation = 0f
            }
        }
    }

    inner class CategoryGroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardCategoryGroup: MaterialCardView = itemView.findViewById(R.id.cardCategoryGroup)
        private val layoutCategoryHeader: View = itemView.findViewById(R.id.layoutCategoryHeader)
        private val iconContainerCategory: FrameLayout = itemView.findViewById(R.id.iconContainerCategory)
        private val imgCategoryIcon: ImageView = itemView.findViewById(R.id.imgCategoryIcon)
        private val txtCategoryTitle: TextView = itemView.findViewById(R.id.txtCategoryTitle)
        private val txtCategorySubtitle: TextView = itemView.findViewById(R.id.txtCategorySubtitle)
        private val txtCategoryCountPill: TextView = itemView.findViewById(R.id.txtCategoryCountPill)
        private val btnDeleteCategoryHeader: ImageView? = itemView.findViewById(R.id.btnDeleteCategoryHeader)
        private val imgCategoryChevron: ImageView = itemView.findViewById(R.id.imgCategoryChevron)
        private val layoutCategoryItems: LinearLayout = itemView.findViewById(R.id.layoutCategoryItems)

        fun bind(item: StorageItem) {
            val context = itemView.context
            val density = context.resources.displayMetrics.density

            // Enforce solid card background and stroke on outer card
            cardCategoryGroup.setCardBackgroundColor(ContextCompat.getColor(context, R.color.mobile_glass_card))
            cardCategoryGroup.strokeWidth = (1 * density).toInt().coerceAtLeast(1)
            cardCategoryGroup.strokeColor = ContextCompat.getColor(context, R.color.mobile_glass_stroke)

            imgCategoryIcon.safeSetIcon(item.iconRes)
            val iconTint = DefaultIconColorManager.getMobileIconTint(context)
            imgCategoryIcon.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
            txtCategoryTitle.text = item.label

            val catId = item.categoryId ?: ""
            val isExpanded = item.isCategoryExpanded
            val groupItems = item.categoryGroupItems
            val count = groupItems.size

            val countText = if (count == 1) {
                context.getString(R.string.main_menu_section_item_count_single)
            } else {
                context.getString(R.string.main_menu_section_items_count, count)
            }

            // Enable layout transition for smooth child tile sliding animations
            val layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                disableTransitionType(LayoutTransition.APPEARING)
                disableTransitionType(LayoutTransition.DISAPPEARING)
                disableTransitionType(LayoutTransition.CHANGE_APPEARING)
                disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
                setDuration(LayoutTransition.CHANGING, 180)
                setInterpolator(LayoutTransition.CHANGING, DecelerateInterpolator())
            }
            layoutCategoryItems.layoutTransition = layoutTransition

            if (isExpanded) {
                // Header highlighted when expanded
                layoutCategoryHeader.setBackgroundResource(R.drawable.bg_category_header_highlight)
                txtCategorySubtitle.visibility = View.VISIBLE
                txtCategorySubtitle.text = countText
                txtCategoryCountPill.visibility = View.GONE
                imgCategoryChevron.setImageResource(R.drawable.ic_expand_less)
                layoutCategoryItems.visibility = View.VISIBLE
                populateChildItems(context, groupItems, catId)
            } else {
                // Header NOT highlighted when collapsed
                layoutCategoryHeader.background = null
                txtCategorySubtitle.visibility = View.GONE
                txtCategoryCountPill.visibility = View.VISIBLE
                txtCategoryCountPill.text = countText
                imgCategoryChevron.setImageResource(R.drawable.ic_chevron_right)
                layoutCategoryItems.visibility = View.GONE
                layoutCategoryItems.removeAllViews()
            }

            val isCustomCategory = !isTv && catId.isNotBlank() && MainMenuViewModeManager.loadCustomCategories(context).containsKey(catId)
            if (isEditMode && isCustomCategory) {
                btnDeleteCategoryHeader?.visibility = View.VISIBLE
                imgCategoryChevron.visibility = View.GONE
                btnDeleteCategoryHeader?.setOnClickListener {
                    onEditModeClick?.invoke(item)
                }
            } else {
                btnDeleteCategoryHeader?.visibility = View.GONE
                imgCategoryChevron.visibility = View.VISIBLE
                btnDeleteCategoryHeader?.setOnClickListener(null)
            }

            // Smooth expand/collapse animation with TransitionManager
            layoutCategoryHeader.setOnClickListener {
                if (isEditMode && isCustomCategory) {
                    onEditModeClick?.invoke(item)
                } else {
                    val parentView = itemView.parent as? ViewGroup ?: (itemView as? ViewGroup)
                    if (parentView != null) {
                        val transition = android.transition.AutoTransition().apply {
                            duration = 200
                            interpolator = DecelerateInterpolator()
                        }
                        android.transition.TransitionManager.beginDelayedTransition(parentView, transition)
                    }
                    val newExpanded = !item.isCategoryExpanded
                    item.isCategoryExpanded = newExpanded
                    MainMenuViewModeManager.setCategoryExpanded(context, catId, newExpanded)
                    onCategoryHeaderToggled?.invoke(catId, newExpanded)
                    bind(item)
                }
            }

            // Header long-press starts whole-category drag reordering (enters edit mode if needed)
            if (!isTv && onLongPress != null) {
                layoutCategoryHeader.setOnLongClickListener {
                    itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onEnterEditModeRequested?.invoke()
                    onLongPress.invoke(item, this@CategoryGroupViewHolder)
                    true
                }
            }

            // Outer card drag listener: handles hover border and auto-expanding collapsed categories
            cardCategoryGroup.setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> {
                        val state = event.localState as? StorageItem
                        state != null && !state.isCategoryHeader
                    }
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        cardCategoryGroup.strokeWidth = (2 * density).toInt()
                        cardCategoryGroup.setStrokeColor(ContextCompat.getColor(context, R.color.theme_primary))
                        if (!item.isCategoryExpanded) {
                            item.isCategoryExpanded = true
                            MainMenuViewModeManager.setCategoryExpanded(context, catId, true)
                            bind(item)
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        cardCategoryGroup.strokeWidth = (1 * density).toInt().coerceAtLeast(1)
                        cardCategoryGroup.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        cardCategoryGroup.strokeWidth = (1 * density).toInt().coerceAtLeast(1)
                        cardCategoryGroup.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
                        val droppedItem = event.localState as? StorageItem
                        if (droppedItem != null && catId.isNotBlank()) {
                            val targetIdx = layoutCategoryItems.childCount
                            reorderTile(context, droppedItem.id, catId, targetIdx)
                            itemView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        cardCategoryGroup.strokeWidth = (1 * density).toInt().coerceAtLeast(1)
                        cardCategoryGroup.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
                        true
                    }
                    else -> false
                }
            }

            // Nested items container drag listener: live reordering with fluid animations
            layoutCategoryItems.setOnDragListener { v, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> {
                        val state = event.localState as? StorageItem
                        state != null && !state.isCategoryHeader
                    }
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        val dragged = event.localState as? StorageItem ?: return@setOnDragListener false
                        if (!item.isCategoryExpanded) {
                            item.isCategoryExpanded = true
                            MainMenuViewModeManager.setCategoryExpanded(context, catId, true)
                            bind(item)
                        }
                        val existingView = layoutCategoryItems.findViewWithTag<View>(dragged.id)
                        if (existingView == null) {
                            val placeholder = createChildTileView(context, dragged)
                            placeholder.alpha = 0.35f
                            layoutCategoryItems.addView(placeholder)
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_LOCATION -> {
                        val dragged = event.localState as? StorageItem ?: return@setOnDragListener false
                        val draggedView = layoutCategoryItems.findViewWithTag<View>(dragged.id) ?: return@setOnDragListener true
                        val currentIdx = layoutCategoryItems.indexOfChild(draggedView)
                        if (currentIdx == -1) return@setOnDragListener true

                        // Auto-scroll RecyclerView if near screen edge
                        val locOnScreen = IntArray(2)
                        v.getLocationOnScreen(locOnScreen)
                        val screenY = locOnScreen[1] + event.y
                        val displayMetrics = context.resources.displayMetrics
                        val topThreshold = 100 * displayMetrics.density
                        val bottomThreshold = displayMetrics.heightPixels - (120 * displayMetrics.density)
                        val rv = attachedRecyclerView
                        if (rv != null) {
                            if (screenY < topThreshold) {
                                rv.scrollBy(0, -18)
                            } else if (screenY > bottomThreshold) {
                                rv.scrollBy(0, 18)
                            }
                        }

                        // Determine target index based on child midpoints (excluding dragged view)
                        val y = event.y
                        var targetIdx = 0
                        for (i in 0 until layoutCategoryItems.childCount) {
                            val child = layoutCategoryItems.getChildAt(i)
                            if (child === draggedView) continue
                            val midY = (child.top + child.bottom) / 2f
                            if (y > midY) {
                                targetIdx++
                            }
                        }

                        if (targetIdx != currentIdx) {
                            layoutCategoryItems.removeView(draggedView)
                            val safeTarget = targetIdx.coerceIn(0, layoutCategoryItems.childCount)
                            layoutCategoryItems.addView(draggedView, safeTarget)
                            itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        val dragged = event.localState as? StorageItem
                        if (dragged != null && categoryForTile(dragged, context) != catId) {
                            val placeholder = layoutCategoryItems.findViewWithTag<View>(dragged.id)
                            if (placeholder != null) {
                                layoutCategoryItems.removeView(placeholder)
                            }
                        }
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        val dragged = event.localState as? StorageItem ?: return@setOnDragListener false
                        val draggedView = layoutCategoryItems.findViewWithTag<View>(dragged.id)
                        val dropIdx = if (draggedView != null) {
                            layoutCategoryItems.indexOfChild(draggedView)
                        } else {
                            layoutCategoryItems.childCount
                        }
                        draggedView?.alpha = 1.0f

                        reorderTile(context, dragged.id, catId, dropIdx)
                        itemView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        for (i in 0 until layoutCategoryItems.childCount) {
                            layoutCategoryItems.getChildAt(i).alpha = 1.0f
                        }
                        val dragged = event.localState as? StorageItem
                        if (dragged != null && categoryForTile(dragged, context) != catId) {
                            val dangling = layoutCategoryItems.findViewWithTag<View>(dragged.id)
                            if (dangling != null) {
                                layoutCategoryItems.removeView(dangling)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        private fun createChildTileView(context: Context, childItem: StorageItem): View {
            val inflater = LayoutInflater.from(context)
            val childView = inflater.inflate(R.layout.item_storage_nested_card, layoutCategoryItems, false)
            childView.tag = childItem.id

            val cardStorage = childView.findViewById<MaterialCardView>(R.id.cardStorage)
            val iconContainer = childView.findViewById<FrameLayout>(R.id.iconContainer)
            val imgIcon = childView.findViewById<ImageView>(R.id.imgStorageIcon)
            val txtLabel = childView.findViewById<TextView>(R.id.txtStorageLabel)
            val txtCapacity = childView.findViewById<TextView>(R.id.txtStorageCapacity)
            val progressBar = childView.findViewById<ProgressBar>(R.id.progressStorage)
            val txtNewBadge = childView.findViewById<TextView>(R.id.txtNewBadge)
            val btnEjectTile = childView.findViewById<ImageView>(R.id.btnEjectTile)
            val btnHideTile = childView.findViewById<ImageView>(R.id.btnHideTile)

            imgIcon.safeSetIcon(childItem.iconRes)
            applyCustomIcon(childItem, imgIcon)
            txtLabel.text = childItem.label
            txtCapacity.text = formatTileSubtitle(context, childItem)

            val showProgress = !childItem.isUnmounted && childItem.totalBytes > 0L
            if (showProgress) {
                progressBar.visibility = View.VISIBLE
                progressBar.max = 100
                progressBar.progress = childItem.usagePercent
            } else {
                progressBar.visibility = View.GONE
            }

            txtNewBadge.visibility = if (childItem.isNewlyMounted) View.VISIBLE else View.GONE

            if (!isEditMode && UsbEjectManager.isRemovableStorage(childItem)) {
                btnEjectTile.visibility = View.VISIBLE
                if (childItem.isUnmounted) {
                    btnEjectTile.setImageResource(R.drawable.ic_mount)
                    btnEjectTile.contentDescription = context.getString(R.string.cd_remount_storage)
                } else {
                    btnEjectTile.setImageResource(R.drawable.ic_eject)
                    btnEjectTile.contentDescription = context.getString(R.string.cd_safely_remove)
                }
                btnEjectTile.setOnClickListener { onEjectClick?.invoke(childItem) }
            } else {
                btnEjectTile.visibility = View.GONE
                btnEjectTile.setOnClickListener(null)
            }

            if (isEditMode) {
                val isCustom = childItem.isCustomTile
                val showBtn = (childItem.isHideable || isCustom) && !isColorPickMode
                btnHideTile.visibility = if (showBtn) View.VISIBLE else View.GONE
                if (isCustom) {
                    btnHideTile.setImageResource(R.drawable.ic_settings)
                    btnHideTile.setOnClickListener { onEditModeClick?.invoke(childItem) }
                } else {
                    btnHideTile.setImageResource(R.drawable.ic_close)
                    btnHideTile.setOnClickListener { onHideClick?.invoke(childItem) }
                }
            } else {
                btnHideTile.visibility = View.GONE
                btnHideTile.setOnClickListener(null)
            }

            val colorConfig = tileColors[childItem.id] ?: TileColorConfig()
            applyCustomColors(context, cardStorage, iconContainer, imgIcon, txtLabel, txtCapacity, colorConfig, R.color.quick_bar_bg)

            cardStorage.setOnClickListener {
                if (isEditMode) {
                    onEditModeClick?.invoke(childItem)
                } else {
                    onStorageClick(childItem)
                }
            }

            // Long press starts child tile drag reordering
            cardStorage.setOnLongClickListener {
                cardStorage.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onEnterEditModeRequested?.invoke()
                childView.alpha = 0.25f
                val clipData = ClipData.newPlainText("tile_id", childItem.id)
                val shadowBuilder = View.DragShadowBuilder(cardStorage)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cardStorage.startDragAndDrop(clipData, shadowBuilder, childItem, 0)
                } else {
                    @Suppress("DEPRECATION")
                    cardStorage.startDrag(clipData, shadowBuilder, childItem, 0)
                }
                true
            }

            // If this tile is a custom tile, accept drops to move items inside it
            if (childItem.isCustomTile) {
                val density = context.resources.displayMetrics.density
                cardStorage.setOnDragListener { _, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> {
                            val state = event.localState as? StorageItem
                            state != null && !state.isCategoryHeader && !state.isCustomTile && state.id != childItem.id
                        }
                        DragEvent.ACTION_DRAG_ENTERED -> {
                            val dragged = event.localState as? StorageItem
                            if (dragged != null && dragged.id != childItem.id && !dragged.isCustomTile) {
                                cardStorage.strokeWidth = (4 * density).toInt()
                                cardStorage.setStrokeColor(za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(context))
                                true
                            } else false
                        }
                        DragEvent.ACTION_DRAG_EXITED -> {
                            cardStorage.strokeWidth = (1 * density).toInt().coerceAtLeast(1)
                            cardStorage.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
                            true
                        }
                        DragEvent.ACTION_DROP -> {
                            cardStorage.strokeWidth = (1 * density).toInt().coerceAtLeast(1)
                            cardStorage.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
                            val dragged = event.localState as? StorageItem
                            if (dragged != null && dragged.id != childItem.id && !dragged.isCustomTile) {
                                CustomTileManager.setTileParent(context, dragged.id, childItem.id)
                                val order = CustomTileManager.loadTileOrder(context, childItem.id).toMutableList()
                                if (dragged.id !in order) {
                                    order.add(dragged.id)
                                    CustomTileManager.saveTileOrder(context, childItem.id, order)
                                }
                                itemView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                onTileDroppedIntoCustomTile?.invoke(dragged.id, childItem.id)
                                refreshDisplayedList(context)
                                safeNotifyDataSetChanged()
                            }
                            true
                        }
                        DragEvent.ACTION_DRAG_ENDED -> {
                            cardStorage.strokeWidth = (1 * density).toInt().coerceAtLeast(1)
                            cardStorage.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
                            true
                        }
                        else -> false
                    }
                }
            }

            return childView
        }

        private fun populateChildItems(context: Context, groupItems: List<StorageItem>, currentCatId: String) {
            layoutCategoryItems.removeAllViews()
            for (childItem in groupItems) {
                layoutCategoryItems.addView(createChildTileView(context, childItem))
            }
        }
    }

    inner class CategoryHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardCategoryHeader: MaterialCardView? = itemView.findViewById(R.id.cardCategoryHeader)
        private val iconContainer: FrameLayout? = itemView.findViewById(R.id.iconContainerHeader)
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgCategoryIcon)
        private val txtTitle: TextView = itemView.findViewById(R.id.txtCategoryTitle)
        private val txtSubtitle: TextView? = itemView.findViewById(R.id.txtCategorySubtitle)
        private val txtCount: TextView? = itemView.findViewById(R.id.txtCategoryCount)
        private val imgChevron: ImageView = itemView.findViewById(R.id.imgCategoryChevron)
        private val btnDeleteCategoryHeader: ImageView? = itemView.findViewById(R.id.btnDeleteCategoryHeader)

        private val longPressHandler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null

        fun bind(item: StorageItem) {
            val context = itemView.context
            val density = context.resources.displayMetrics.density

            // Enforce solid card background and stroke so it is ALWAYS preserved on scroll
            cardCategoryHeader?.setCardBackgroundColor(ContextCompat.getColor(context, R.color.mobile_glass_card))
            cardCategoryHeader?.strokeWidth = (1 * density).toInt().coerceAtLeast(1)
            cardCategoryHeader?.strokeColor = ContextCompat.getColor(context, R.color.mobile_glass_stroke)

            imgIcon.setImageResource(item.iconRes)
            val iconTint = if (isTv) {
                DefaultIconColorManager.getTvIconTint(context)
            } else {
                DefaultIconColorManager.getMobileIconTint(context)
            }
            imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
            txtTitle.text = item.label

            val catId = item.categoryId ?: ""
            val isExpanded = item.isCategoryExpanded
            val count = item.categoryItemCount
            val countText = if (count == 1) {
                context.getString(R.string.main_menu_section_item_count_single)
            } else {
                context.getString(R.string.main_menu_section_items_count, count)
            }

            if (isExpanded) {
                txtSubtitle?.visibility = View.VISIBLE
                txtSubtitle?.text = countText
                txtCount?.visibility = View.GONE
                imgChevron.setImageResource(R.drawable.ic_expand_less)
            } else {
                txtSubtitle?.visibility = View.GONE
                txtCount?.visibility = View.VISIBLE
                txtCount?.text = countText
                imgChevron.setImageResource(R.drawable.ic_chevron_right)
            }

            val isCustomCategory = !isTv && catId.isNotBlank() && MainMenuViewModeManager.loadCustomCategories(context).containsKey(catId)

            if (isEditMode && isCustomCategory) {
                btnDeleteCategoryHeader?.visibility = View.VISIBLE
                imgChevron.visibility = View.GONE
                btnDeleteCategoryHeader?.setOnClickListener {
                    onEditModeClick?.invoke(item)
                }
                itemView.setOnClickListener {
                    onEditModeClick?.invoke(item)
                }
            } else {
                btnDeleteCategoryHeader?.visibility = View.GONE
                imgChevron.visibility = View.VISIBLE
                btnDeleteCategoryHeader?.setOnClickListener(null)
                itemView.setOnClickListener {
                    val newExpanded = !item.isCategoryExpanded
                    item.isCategoryExpanded = newExpanded
                    MainMenuViewModeManager.setCategoryExpanded(context, catId, newExpanded)
                    onCategoryHeaderToggled?.invoke(catId, newExpanded)
                    refreshDisplayedList(context)
                    safeNotifyDataSetChanged()
                }
            }

            if (isTv) {
                itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        onTileFocused?.invoke(item)
                    }
                }
            }

            // Mobile: touch-hold on header starts drag for category reordering in edit mode
            if (!isTv && onLongPress != null) {
                itemView.setOnTouchListener { _, motionEvent ->
                    when (motionEvent.action) {
                        MotionEvent.ACTION_DOWN -> {
                            val durationMs = LongPressDurationManager.loadDurationMs(itemView.context)
                            longPressRunnable = Runnable {
                                itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onLongPress.invoke(item, this@CategoryHeaderViewHolder)
                            }
                            longPressHandler.postDelayed(longPressRunnable!!, durationMs)
                            false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                            longPressRunnable = null
                            val duration = motionEvent.eventTime - motionEvent.downTime
                            if (motionEvent.action == MotionEvent.ACTION_UP && duration < 500) {
                                if (isEditMode && isCustomCategory) {
                                    onEditModeClick?.invoke(item)
                                    true
                                } else if (!isEditMode) {
                                    itemView.performClick()
                                    true
                                } else false
                            } else false
                        }
                        else -> false
                    }
                }
            }
        }
    }

    inner class StorageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView as MaterialCardView
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgStorageIcon)
        private val txtLabel: TextView = itemView.findViewById(R.id.txtStorageLabel)
        private val txtCapacity: TextView = itemView.findViewById(R.id.txtStorageCapacity)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressStorage)
        private val txtNewBadge: TextView? = itemView.findViewById(R.id.txtNewBadge)
        private val circularProgress: CircularProgressView? = itemView.findViewById(R.id.circularProgress)
        private val btnHideTile: View = itemView.findViewById(R.id.btnHideTile)
        private val btnEjectTile: View? = itemView.findViewById(R.id.btnEjectTile)

        // Handler + Runnable used for the 2-second mobile long-press detection
        private val longPressHandler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null

        fun bind(item: StorageItem) {
            val context = itemView.context

            // Read the custom color config early so the TV focus listener can
            // restore the custom ring color when a tile loses focus.
            val colorConfig = tileColors[item.id] ?: TileColorConfig()

            // Ã¢â€â‚¬Ã¢â€â‚¬ TV focus + reorder mode visuals Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
            if (isTv) {
                val density = context.resources.displayMetrics.density
                val isReordering = item.id == reorderModeId
                if (isReordering) {
                    // Reorder mode: highlighted border + slight elevation Ã¢â‚¬â€ NO scale (avoids clipping)
                    card.onFocusChangeListener = null
                    card.scaleX = 1f
                    card.scaleY = 1f
                    card.elevation = 12f
                    val strokePx = (3 * density).toInt()
                    card.strokeWidth = strokePx
                    card.setStrokeColor(ContextCompat.getColor(context, R.color.ufm_primary))
                } else {
                    // Normal TV tile: accent border on focus, no elevation change.
                    // Elevation on a transparent-bg MaterialCardView causes Android to
                    // composite a visible grey surface underneath Ã¢â‚¬â€ avoid it entirely.
                    card.scaleX = 1f
                    card.scaleY = 1f
                    card.elevation = 0f
                    val accentColor = ContextCompat.getColor(context, R.color.tv_accent)
                    val strokePx = (2 * density).toInt()

                    // Helper: restore the correct unfocused stroke (custom ring or none)
                    fun applyUnfocusedStroke() {
                        if (colorConfig.ringColor != Color.TRANSPARENT) {
                            card.strokeWidth = strokePx
                            card.setStrokeColor(colorConfig.ringColor)
                        } else {
                            card.strokeWidth = 0
                            card.setStrokeColor(Color.TRANSPARENT)
                        }
                    }

                    if (card.hasFocus()) {
                        card.strokeWidth = strokePx
                        card.setStrokeColor(accentColor)
                        onTileFocused?.invoke(item)
                    } else {
                        applyUnfocusedStroke()
                    }
                    card.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                        card.animate().setDuration(150).start()
                        if (hasFocus) {
                            card.strokeWidth = strokePx
                            card.setStrokeColor(accentColor)
                            onTileFocused?.invoke(item)
                        } else {
                            applyUnfocusedStroke()
                        }
                    }
                }

                // TV: custom timer for OK / DPAD_CENTER Ã¢â€ â€™ configurable long-press threshold
                if (onLongPress != null) {
                    itemView.setOnKeyListener { _, keyCode, event ->
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                            when (event.action) {
                                KeyEvent.ACTION_DOWN -> {
                                    if (event.repeatCount == 0) {
                                        // First down: start our custom timer
                                        val durationMs = LongPressDurationManager.loadDurationMs(itemView.context)
                                        longPressRunnable = Runnable {
                                            itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            onLongPress.invoke(item, this@StorageViewHolder)
                                        }
                                        longPressHandler.postDelayed(longPressRunnable!!, durationMs)
                                    }
                                    false
                                }
                                KeyEvent.ACTION_UP -> {
                                    // Cancel the pending long-press if finger lifted before threshold
                                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                                    longPressRunnable = null

                                    val duration = event.eventTime - event.downTime
                                    if (!event.isCanceled && duration < 500) {
                                        when {
                                            isColorPickMode -> {
                                                onEditModeClick?.invoke(item)
                                                true
                                            }
                                            isEditMode && item.isCustomTile -> {
                                                // Custom tile: short OK opens Edit/Delete (gear action)
                                                onEditModeClick?.invoke(item)
                                                true
                                            }
                                            isEditMode && item.isHideable -> {
                                                onHideClick?.invoke(item)
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                else -> false
                            }
                        } else false
                    }
                }
            }
            // Ã¢â€â‚¬Ã¢â€â‚¬ Edit Mode (Pulse/Visibility) Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
            if (isEditMode) {
                // In palette mode, hide the button so D-Pad cannot accidentally trigger hide
                val isCustom = item.isCustomTile
                val showBtn = (item.isHideable || isCustom) && !isColorPickMode
                btnHideTile.visibility = if (showBtn) View.VISIBLE else View.GONE
                if (isCustom) {
                    (btnHideTile as? ImageView)?.setImageResource(R.drawable.ic_settings)
                    btnHideTile.setOnClickListener { onEditModeClick?.invoke(item) }
                } else if (!isTv) {
                    (btnHideTile as? ImageView)?.setImageResource(R.drawable.ic_close)
                    btnHideTile.setOnClickListener { onHideClick?.invoke(item) }
                } else {
                    // On TV, the button is display-only; short-press OK triggers the hide
                    (btnHideTile as? ImageView)?.setImageResource(R.drawable.ic_close)
                    btnHideTile.setOnClickListener(null)
                    btnHideTile.isClickable = false
                    btnHideTile.isFocusable = false
                }

                // Subtle pulse animation (MOBILE ONLY - Disable on TV to avoid "shatter")
                if (!isTv) {
                    card.animate().cancel()
                    card.scaleX = 1f
                    card.scaleY = 1f
                    card.animate()
                        .scaleX(1.02f)
                        .scaleY(1.02f)
                        .setDuration(200)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            if (isEditMode) {
                                card.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(200)
                                    .setInterpolator(AccelerateDecelerateInterpolator())
                                    .start()
                            } else {
                                card.scaleX = 1f
                                card.scaleY = 1f
                            }
                        }
                        .start()
                } else {
                    // TV specific: just ensure we are at base scale
                    card.animate().cancel()
                    card.scaleX = 1f
                    card.scaleY = 1f
                }
            } else {
                btnHideTile.visibility = View.GONE
                card.animate().cancel()
                card.scaleX = 1f
                card.scaleY = 1f
                card.elevation = 0f
            }

            // Eject / Remount button for removable storage volumes (USB, SD, Mock Drive)
            if (!isEditMode && UsbEjectManager.isRemovableStorage(item)) {
                btnEjectTile?.visibility = View.VISIBLE
                if (btnEjectTile is ImageView) {
                    if (item.isUnmounted) {
                        btnEjectTile.setImageResource(R.drawable.ic_mount)
                        btnEjectTile.contentDescription = context.getString(R.string.cd_remount_storage)
                    } else {
                        btnEjectTile.setImageResource(R.drawable.ic_eject)
                        btnEjectTile.contentDescription = context.getString(R.string.cd_safely_remove)
                    }
                }
                btnEjectTile?.setOnClickListener {
                    onEjectClick?.invoke(item)
                }
            } else {
                btnEjectTile?.visibility = View.GONE
                btnEjectTile?.setOnClickListener(null)
            }

            // Ã¢â€ â‚¬Ã¢â€ â‚¬ Mobile: configurable touch-hold Ã¢â€ â€™ start drag Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬
            if (!isTv && onLongPress != null) {
                itemView.setOnTouchListener { _, motionEvent ->
                    when (motionEvent.action) {
                        MotionEvent.ACTION_DOWN -> {
                            val durationMs = LongPressDurationManager.loadDurationMs(itemView.context)
                            longPressRunnable = Runnable {
                                itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onLongPress.invoke(item, this@StorageViewHolder)
                            }
                            longPressHandler.postDelayed(longPressRunnable!!, durationMs)
                            false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                            longPressRunnable = null
                            false
                        }
                        else -> false
                    }
                }
            }

            // Ã¢â€â‚¬Ã¢â€â‚¬ Dynamic height adjustments for LIST view (All Tiles) Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
            if (viewMode == MainMenuViewModeManager.ViewMode.LIST || viewMode == MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED) {
                val paddingTarget = if (isTv) {
                    card.getChildAt(0) as? ViewGroup
                } else {
                    (card.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ViewGroup
                }
                
                val density = context.resources.displayMetrics.density
                val iconFrame = itemView.findViewById<FrameLayout>(R.id.iconContainer)
                
                when (itemSize) {
                    MainMenuViewModeManager.ItemSize.LARGE -> {
                        paddingTarget?.setPadding((if(isTv) 24*density else 16*density).toInt(), (if(isTv) 20*density else 16*density).toInt(), (if(isTv) 24*density else 16*density).toInt(), (if(isTv) 20*density else 16*density).toInt())
                        txtLabel.textSize = if (isTv) 24f else 16f
                        txtCapacity.textSize = if (isTv) 18f else 13f
                        iconFrame?.layoutParams?.width = ((if (isTv) 88f else 52f) * density).toInt()
                        iconFrame?.layoutParams?.height = ((if (isTv) 88f else 52f) * density).toInt()
                        val pad = (if(isTv) 20f*density else 12f*density).toInt()
                        iconFrame?.setPadding(pad, pad, pad, pad)
                    }
                    MainMenuViewModeManager.ItemSize.MEDIUM -> {
                        paddingTarget?.setPadding((if(isTv) 16*density else 12*density).toInt(), (if(isTv) 12*density else 12*density).toInt(), (if(isTv) 16*density else 12*density).toInt(), (if(isTv) 12*density else 12*density).toInt())
                        txtLabel.textSize = if (isTv) 20f else 15f
                        txtCapacity.textSize = if (isTv) 16f else 12f
                        iconFrame?.layoutParams?.width = ((if (isTv) 64f else 44f) * density).toInt()
                        iconFrame?.layoutParams?.height = ((if (isTv) 64f else 44f) * density).toInt()
                        val pad = (if(isTv) 14f*density else 10f*density).toInt()
                        iconFrame?.setPadding(pad, pad, pad, pad)
                    }
                    MainMenuViewModeManager.ItemSize.SMALL -> {
                        paddingTarget?.setPadding((if(isTv) 12*density else 8*density).toInt(), (if(isTv) 8*density else 8*density).toInt(), (if(isTv) 12*density else 8*density).toInt(), (if(isTv) 8*density else 8*density).toInt())
                        txtLabel.textSize = if (isTv) 18f else 14f
                        txtCapacity.textSize = if (isTv) 14f else 11f
                        iconFrame?.layoutParams?.width = ((if (isTv) 48f else 36f) * density).toInt()
                        iconFrame?.layoutParams?.height = ((if (isTv) 48f else 36f) * density).toInt()
                        val pad = (if(isTv) 10f*density else 8f*density).toInt()
                        iconFrame?.setPadding(pad, pad, pad, pad)
                    }
                }
            } else if (viewMode == MainMenuViewModeManager.ViewMode.GRID && isTv) {
                val density = context.resources.displayMetrics.density
                val innerLayout = card.getChildAt(0) as? ViewGroup
                val iconFrame = itemView.findViewById<FrameLayout>(R.id.iconContainer)

                // TV grid mode: apply the exact row height calculated by StorageBrowserActivity
                if (gridItemHeightPx > 0) {
                    innerLayout?.let {
                        val lp = it.layoutParams
                        lp.height = gridItemHeightPx
                        it.layoutParams = lp
                    }
                }

                val imgIcon = itemView.findViewById<ImageView>(R.id.imgStorageIcon)
                
                // Scale inner content for 3 vs 4 columns on TV so text doesn't overly wrap
                val marginParams = card.layoutParams as? ViewGroup.MarginLayoutParams
                if (gridColumnCount == 4) {
                    marginParams?.setMargins((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                    innerLayout?.setPadding((8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())

                    // Container (circle background) is larger than the icon inside it.
                    // Use setLayoutParams() (not direct field mutation) so requestLayout() fires.
                    val containerPx = (48 * density).toInt()
                    val iconPx      = (24 * density).toInt()
                    iconFrame?.let { frame ->
                        val lp = frame.layoutParams
                        lp.width = containerPx
                        lp.height = containerPx
                        frame.setPadding(0, 0, 0, 0)   // clear XML padding so icon isn't squeezed
                        frame.layoutParams = lp
                    }
                    imgIcon?.let { iv ->
                        val lp = iv.layoutParams
                        lp.width = iconPx
                        lp.height = iconPx
                        iv.layoutParams = lp
                    }

                    txtLabel.textSize = 13f
                    val lpLabel = txtLabel.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                    lpLabel?.topMargin = (4 * density).toInt()
                    txtLabel.layoutParams = lpLabel

                    txtCapacity.textSize = 11f

                    progressBar.let { pb ->
                        val lp = pb.layoutParams
                        lp.width = 0
                        lp.height = (4 * density).toInt()
                        pb.layoutParams = lp
                    }
                } else {
                    // Default 3 columns (large readable standard)
                    marginParams?.setMargins((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
                    innerLayout?.setPadding((16 * density).toInt(), (20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt())

                    // Container (circle background) is larger than the icon inside it.
                    // Use setLayoutParams() (not direct field mutation) so requestLayout() fires.
                    val containerPx = (64 * density).toInt()
                    val iconPx      = (32 * density).toInt()
                    iconFrame?.let { frame ->
                        val lp = frame.layoutParams
                        lp.width = containerPx
                        lp.height = containerPx
                        frame.setPadding(0, 0, 0, 0)   // clear XML padding so icon isn't squeezed
                        frame.layoutParams = lp
                    }
                    imgIcon?.let { iv ->
                        val lp = iv.layoutParams
                        lp.width = iconPx
                        lp.height = iconPx
                        iv.layoutParams = lp
                    }

                    txtLabel.textSize = 16f
                    val lpLabel = txtLabel.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                    lpLabel?.topMargin = (12 * density).toInt()
                    txtLabel.layoutParams = lpLabel

                    txtCapacity.textSize = 12f

                    progressBar.layoutParams?.width  = 0
                    progressBar.layoutParams?.height = (4 * density).toInt()
                }

            } else if (viewMode == MainMenuViewModeManager.ViewMode.GRID && !isTv) {
                val density = context.resources.displayMetrics.density
                val innerLayout = card.getChildAt(0) as? ViewGroup
                val marginParams = card.layoutParams as? ViewGroup.MarginLayoutParams
                val iconFrame = itemView.findViewById<FrameLayout>(R.id.iconContainer)
                
                if (gridColumnCount == 4) {
                    marginParams?.setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                    innerLayout?.setPadding((4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
                    
                    iconFrame?.layoutParams?.width = (40 * density).toInt()
                    iconFrame?.layoutParams?.height = (40 * density).toInt()
                    val pad = (8f*density).toInt()
                    iconFrame?.setPadding(pad, pad, pad, pad)
                    
                    txtLabel.textSize = 10f
                    txtCapacity.textSize = 9f
                    
                    progressBar.layoutParams?.width = (24 * density).toInt()
                    progressBar.layoutParams?.height = (24 * density).toInt()
                } else {
                    marginParams?.setMargins((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                    innerLayout?.setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
                    
                    iconFrame?.layoutParams?.width = (56 * density).toInt()
                    iconFrame?.layoutParams?.height = (56 * density).toInt()
                    val pad = (16f*density).toInt()
                    iconFrame?.setPadding(pad, pad, pad, pad)
                    
                    txtLabel.textSize = 14f
                    txtCapacity.textSize = 11f
                    
                    progressBar.layoutParams?.width = (32 * density).toInt()
                    progressBar.layoutParams?.height = (32 * density).toInt()
                }
            }

            // Ã¢â€â‚¬Ã¢â€â‚¬ Tile content Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
            val isSpecialTile = item.isAppsTile || item.isRemoteTile || item.isTvRemoteTile ||
                    item.isSearchTile || item.isAnalyzerTile ||
                    item.isVaultTile  || item.isLegalTile   || item.isRateUsTile || item.isSafTile ||
                    item.isNetworkTile || item.isNetworkRoot || item.isPairedDevicesTile ||
                    item.isExtractsTile || item.isTipJarTile || item.isSyncTile || item.isAdvancedSyncTile || item.isSettingsTile || item.isFavoriteTile || item.isTwinWindowTile  || item.isRecentFilesTile || item.isTerminalTile || item.isShizukuTile || item.isOnlineStoragesTile || item.isOnlineStorage || item.isFileServerTile || item.isAboutTile || item.isSupportTile || item.isRecycleBinTile || item.isNotepadTile || item.isScannerTile || item.isSmartSortTile || item.isCustomTile || item.isAddStorageLocationTile || item.isSafCustomLocation

            if (isSpecialTile) {
                circularProgress?.visibility = View.GONE
                progressBar.visibility   = View.GONE
                txtNewBadge?.visibility  = View.GONE

                imgIcon.safeSetIcon(item.iconRes)
                applyCustomIcon(item, imgIcon)
                txtLabel.text = item.label
                txtCapacity.text = formatTileSubtitle(context, item)
            } else {
                val showProgress = !item.isUnmounted && item.totalBytes > 0L && (viewMode == MainMenuViewModeManager.ViewMode.LIST || viewMode == MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED)
                if (showProgress) {
                    when (itemSize) {
                        MainMenuViewModeManager.ItemSize.LARGE -> {
                            circularProgress?.visibility = View.VISIBLE
                            progressBar.visibility = View.GONE
                        }
                        else -> {
                            circularProgress?.visibility = View.GONE
                            progressBar.visibility = View.VISIBLE
                        }
                    }
                } else {
                    circularProgress?.visibility = View.GONE
                    progressBar.visibility = View.GONE
                }

                imgIcon.safeSetIcon(item.iconRes)
                applyCustomIcon(item, imgIcon)
                txtLabel.text = item.label
                txtCapacity.text = formatTileSubtitle(context, item)

                if (showProgress) {
                    progressBar.max      = 100
                    progressBar.progress = item.usagePercent

                    circularProgress?.let { chart ->
                        chart.percentLabel = "used"
                        chart.progress     = item.usagePercent
                        val progressColor  = when {
                            item.usagePercent >= 90 -> ColorblindPalette.denied(context)
                            item.usagePercent >= 75 -> ColorblindPalette.progressWarning(context)
                            else                    -> ContextCompat.getColor(context, R.color.tv_accent)
                        }
                        chart.setProgressColor(progressColor)
                    }
                }

                txtNewBadge?.visibility = if (item.isNewlyMounted) View.VISIBLE else View.GONE
            }

            // Ã¢â€â‚¬Ã¢â€â‚¬ Special tile accent styling Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
            val iconContainer = itemView.findViewById<FrameLayout>(R.id.iconContainer)

            when {
                item.isRateUsTile -> applyTileAccent(
                    context, card, iconContainer, imgIcon, txtLabel,
                    iconCircleRes  = R.drawable.bg_icon_circle_rate_us,
                    accentColorRes = R.color.tile_rate_us_accent,
                    strokeColorRes = R.color.tile_rate_us_stroke,
                    tvBgRes        = R.drawable.bg_glass_card_rate_us
                )
                item.isTipJarTile -> applyTileAccent(
                    context, card, iconContainer, imgIcon, txtLabel,
                    iconCircleRes  = R.drawable.bg_icon_circle_tip_jar,
                    accentColorRes = R.color.tile_tip_jar_accent,
                    strokeColorRes = R.color.tile_tip_jar_stroke,
                    tvBgRes        = R.drawable.bg_glass_card_tip_jar
                )
                item.isOnlineStoragesTile -> applyTileAccent(
                    context, card, iconContainer, imgIcon, txtLabel,
                    iconCircleRes  = R.drawable.bg_icon_circle_online_storages,
                    accentColorRes = R.color.tile_online_storages_accent,
                    strokeColorRes = R.color.tile_online_storages_stroke,
                    tvBgRes        = R.drawable.bg_glass_card_online_storages
                )
                else -> resetTileAccent(context, card, iconContainer, imgIcon, txtLabel)
            }

            // Apply custom tile colors (overrides accent tiles)
            // Note: colorConfig was already fetched at the top of bind() for the focus listener.
            val defaultTileBg = if (viewMode == MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED && !isTv) {
                R.color.quick_bar_bg
            } else {
                R.color.mobile_glass_card
            }
            applyCustomColors(context, card, iconContainer, imgIcon, txtLabel, txtCapacity, colorConfig, defaultTileBg)

            itemView.setOnClickListener {
                if (isEditMode) {
                    onEditModeClick?.invoke(item)
                } else {
                    onStorageClick(item)
                }
            }
        }

        /**
         * Applies per-tile accent colors:
         *  - Icon circle: tile-specific background drawable
         *  - Card stroke: colored halo border
         *  - Label text: accent color
         *  - TV: icon tint + card glass background tinted variant
         */
        private fun applyTileAccent(
            context: android.content.Context,
            card: MaterialCardView,
            iconContainer: FrameLayout?,
            icon: ImageView,
            label: TextView,
            iconCircleRes: Int,
            accentColorRes: Int,
            strokeColorRes: Int,
            tvBgRes: Int
        ) {
            val accentColor = ContextCompat.getColor(context, accentColorRes)

            if (isTv) {
                icon.setColorFilter(accentColor)
                iconContainer?.setBackgroundResource(iconCircleRes)
                // Tint the card background using the tile-specific glass variant
                val tvBgColor = ContextCompat.getColor(context, accentColorRes)
                card.setCardBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(tvBgColor, 40))
                // Foreground selector handles border — do NOT set card.strokeWidth here
            } else {
                icon.setColorFilter(accentColor)
                iconContainer?.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
                // Mobile: apply colored halo border
                val strokeColor = ContextCompat.getColor(context, strokeColorRes)
                val strokePx = (2 * context.resources.displayMetrics.density).toInt()
                card.strokeWidth = strokePx
                card.setStrokeColor(strokeColor)
            }

            // Accent label text color
            label.setTextColor(accentColor)
        }

        /**
         * Resets all tile-accent overrides so recycled ViewHolders don’t bleed into regular tiles.
         */
        private fun resetTileAccent(
            context: android.content.Context,
            card: MaterialCardView,
            iconContainer: FrameLayout?,
            icon: ImageView,
            label: TextView
        ) {
            val whiteTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, android.R.color.white))
            if (isTv) {
                icon.imageTintList = whiteTint
                // Card background is the glass layer — no inner layout background to reset
                card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.tv_glass_white_10))
                card.strokeWidth = 0
                label.setTextColor(ContextCompat.getColor(context, R.color.tv_text_primary))
                iconContainer?.setBackgroundResource(R.drawable.bg_glass_card)
            } else {
                icon.clearColorFilter()
                // Always restore to white — @color/mobile_icon_tint is #FFF in dark/AMOLED mode
                icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.mobile_icon_tint)
                )
                iconContainer?.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
                label.setTextColor(ContextCompat.getColor(context, R.color.mobile_card_text_primary))
                val density = context.resources.displayMetrics.density
                card.strokeWidth = (1 * density).toInt().coerceAtLeast(1)
                card.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
            }
        }

    }

    private fun formatTileSubtitle(context: android.content.Context, item: StorageItem): String {
        val isSpecialTile = item.isAppsTile || item.isRemoteTile || item.isTvRemoteTile ||
                item.isSearchTile || item.isAnalyzerTile ||
                item.isVaultTile || item.isLegalTile || item.isRateUsTile || item.isSafTile ||
                item.isNetworkTile || item.isNetworkRoot || item.isPairedDevicesTile ||
                item.isExtractsTile || item.isTipJarTile || item.isSyncTile || item.isAdvancedSyncTile ||
                item.isSettingsTile || item.isFavoriteTile || item.isTwinWindowTile || item.isRecentFilesTile ||
                item.isTerminalTile || item.isShizukuTile || item.isOnlineStoragesTile || item.isOnlineStorage ||
                item.isFileServerTile || item.isAboutTile || item.isSupportTile || item.isRecycleBinTile ||
                item.isNotepadTile || item.isScannerTile || item.isSmartSortTile || item.isCustomTile ||
                item.isAddStorageLocationTile || item.isSafCustomLocation

        return if (isSpecialTile) {
            when {
                item.isRecentFilesTile -> item.subtitle ?: context.getString(R.string.recent_files_subtitle)
                item.isAddStorageLocationTile -> context.getString(R.string.add_storage_location_subtitle)
                item.isSafCustomLocation -> item.subtitle ?: context.getString(R.string.saf_storage)
                item.isTwinWindowTile -> context.getString(R.string.twin_window_subtitle)
                item.isNotepadTile -> context.getString(R.string.notepad_tile_subtitle)
                item.isScannerTile -> context.getString(R.string.scanner_tile_subtitle)
                item.isTerminalTile -> context.getString(R.string.adb_terminal_subtitle)
                item.isPairedDevicesTile -> context.getString(R.string.manage_links_with_other_devices)
                item.isSettingsTile -> context.getString(R.string.font_size_tile_subtitle)
                item.isAppsTile -> context.getString(R.string.apps_tile_subtitle)
                item.isRemoteTile -> context.getString(R.string.remote_tile_subtitle)
                item.isTvRemoteTile -> item.subtitle ?: ""
                item.isSearchTile -> context.getString(R.string.search_tile_subtitle)
                item.isAnalyzerTile -> context.getString(R.string.analyzer_tile_subtitle)
                item.isVaultTile -> context.getString(R.string.vault_tile_subtitle)
                item.isLegalTile -> context.getString(R.string.policy_selection_subtitle)
                item.isRateUsTile -> context.getString(R.string.rate_us_subtitle)
                item.isAboutTile -> context.getString(R.string.about_tile_subtitle)
                item.isSupportTile -> context.getString(R.string.support_tile_subtitle)
                item.isSafTile -> context.getString(R.string.saf_tile_subtitle)
                item.isNetworkTile -> context.getString(R.string.network_tile_subtitle)
                item.isOnlineStoragesTile -> za.kilowatch.ultimatefilemanager.util.DeviceUtils.getOnlineStoragesSubtitle(context)
                item.isTipJarTile -> context.getString(R.string.tip_jar_subtitle)
                item.isSyncTile -> context.getString(R.string.sync_subtitle)
                item.isAdvancedSyncTile -> context.getString(R.string.advanced_sync_subtitle)
                item.isExtractsTile -> item.subtitle ?: context.getString(R.string.browse_extracted_apps)
                item.isFavoriteTile -> if (item.favoriteIsFolder) context.getString(R.string.favorite_folder) else context.getString(R.string.favorite_file)
                item.isRecycleBinTile -> item.subtitle ?: context.getString(R.string.recycle_bin_title)
                item.isCustomTile -> item.subtitle ?: ""
                item.isNetworkRoot -> {
                    item.subtitle ?: run {
                        val share = item.networkShare
                        if (share != null) {
                            val typeLabel = when (share.type) {
                                za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3 -> context.getString(R.string.add_online_storage_aws_s3)
                                za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> context.getString(R.string.add_online_storage_idrive_e2)
                                za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> share.type.name
                                za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> share.type.name
                                else -> share.type.name
                            }
                            "$typeLabel \u2022 ${share.host}"
                        } else ""
                    }
                }
                item.isOnlineStorage -> item.subtitle ?: ""
                item.isShizukuTile -> item.subtitle ?: ""
                item.isFileServerTile -> context.getString(R.string.file_server_tile_subtitle)
                item.isSmartSortTile -> context.getString(R.string.smart_sort_tile_subtitle)
                else -> ""
            }
        } else {
            if (item.isUnmounted || item.totalBytes <= 0L) {
                item.subtitle?.ifEmpty { null } ?: context.getString(R.string.storage_unmounted_tap_to_mount)
            } else {
                val freeFormatted = Formatter.formatFileSize(context, item.freeBytes)
                val totalFormatted = Formatter.formatFileSize(context, item.totalBytes)
                context.getString(R.string.storage_free_format, freeFormatted, totalFormatted)
            }
        }
    }

    private fun applyCustomColors(
        context: android.content.Context,
        card: MaterialCardView,
        iconContainer: FrameLayout?,
        icon: ImageView?,
        label: TextView,
        capacity: TextView,
        config: TileColorConfig,
        defaultBgRes: Int = R.color.mobile_glass_card
    ) {
        // Tile background color
        if (config.tileBgColor != Color.TRANSPARENT) {
            card.setCardBackgroundColor(config.tileBgColor)
        } else {
            card.setCardBackgroundColor(
                ContextCompat.getColor(context, defaultBgRes)
            )
        }

        // Ring color
        if (config.ringColor != Color.TRANSPARENT) {
            val strokePx = (2 * context.resources.displayMetrics.density).toInt()
            card.strokeWidth = strokePx
            card.setStrokeColor(config.ringColor)
        } else {
            if (!isTv) {
                val strokePx = (1 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                card.strokeWidth = strokePx
                card.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
            } else {
                card.strokeWidth = 0
                card.setStrokeColor(Color.TRANSPARENT)
            }
        }

        // Icon tint color
        if (config.iconColor != Color.TRANSPARENT) {
            icon?.setColorFilter(config.iconColor)
        } else {
            icon?.clearColorFilter()
            icon?.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.mobile_icon_tint)
            )
        }

        // Icon background color
        if (config.iconBgColor != Color.TRANSPARENT) {
            if (isTv) {
                val newDrawable = GradientDrawable()
                newDrawable.shape = GradientDrawable.OVAL
                newDrawable.setColor(config.iconBgColor)
                iconContainer?.background = newDrawable
            } else {
                val density = context.resources.displayMetrics.density
                val newDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10 * density
                    setColor(config.iconBgColor)
                    setStroke((1 * density).toInt(), ContextCompat.getColor(context, R.color.mobile_glass_stroke))
                }
                iconContainer?.background = newDrawable
            }
        } else {
            // Reset to default drawable
            if (isTv) {
                iconContainer?.setBackgroundResource(R.drawable.bg_glass_card)
            } else if (defaultBgRes == R.color.quick_bar_bg) {
                iconContainer?.setBackgroundResource(R.drawable.bg_squircle_nested_icon)
            } else {
                iconContainer?.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
            }
        }

        // Label text color
        if (config.labelColor != Color.TRANSPARENT) {
            label.setTextColor(config.labelColor)
            capacity.setTextColor(config.labelColor)
        } else {
            label.setTextColor(ContextCompat.getColor(context, R.color.mobile_card_text_primary))
            capacity.setTextColor(ContextCompat.getColor(context, R.color.mobile_card_text_secondary))
        }
    }

    /**
     * Applies a custom icon to the tile ImageView. Priority:
     * 1. Custom file icon (decoded bitmap)
     * 2. Saved built-in icon resource override
     * 3. Default item.iconRes (set by [bind])
     */
    private fun applyCustomIcon(item: StorageItem, icon: ImageView) {
        val customPath: String? = tileIcons[item.id]
        if (!customPath.isNullOrEmpty()) {
            val bm = BitmapFactory.decodeFile(customPath)
            if (bm != null) {
                icon.setImageBitmap(bm)
                return
            }
        }
        // Check for saved built-in icon resource override
        val savedRes = tileIconRes[item.id] ?: 0
        if (savedRes != 0 && savedRes != item.iconRes) {
            try {
                icon.setImageResource(savedRes)
            } catch (_: Exception) {
                // Stale resource ID survived migration (e.g. severely old prefs).
                // Fall through and let the caller render the default item.iconRes.
            }
        }
    }
}
