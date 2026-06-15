package za.kilowatch.ultimatefilemanager.storage

import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Manage Tiles sheet / dialog.
 *
 * Mobile  → standard BottomSheetDialogFragment.
 * TV      → full-screen centered Dialog with two-level D-pad navigation:
 *   Level 1: scroll tile rows (D-pad Up/Down). Press OK to select a tile.
 *   Level 2: Restore / Open / Cancel buttons in the action panel. Press Back to return.
 */
class ManageTilesBottomSheet : BottomSheetDialogFragment() {

    var onRestored: (() -> Unit)? = null
    var onTileClick: ((StorageItem) -> Unit)? = null

    companion object {
        const val TAG = "ManageTilesBottomSheet"
        fun newInstance() = ManageTilesBottomSheet()
    }

    private var allTiles: List<StorageItem> = emptyList()
    private var allTileIcons: Map<String, String> = emptyMap()
    private var allTileIconRes: Map<String, Int> = emptyMap()
    private var isTv: Boolean = false

    // TV state — stored at class level so Back key handler can access them
    private var selectedItem: StorageItem? = null
    private var lastFocusedRowIndex: Int = 0

    // Lazily stored TV view refs for Back key navigation
    private var tvRecycler:       RecyclerView?    = null
    private var tvTxtSelected:    TextView?        = null
    private var tvBtnRestore:     MaterialButton?  = null
    private var tvBtnOpen:        MaterialButton?  = null
    private var tvBtnCancel:      MaterialButton?  = null
    private var tvBtnRestoreAll:  MaterialButton?  = null

    fun withTiles(tiles: List<StorageItem>): ManageTilesBottomSheet {
        allTiles = tiles
        return this
    }

    fun withTileIcons(icons: Map<String, String>): ManageTilesBottomSheet {
        allTileIcons = icons
        return this
    }

    fun withTileIconRes(res: Map<String, Int>): ManageTilesBottomSheet {
        allTileIconRes = res
        return this
    }

    // ── Dialog creation ───────────────────────────────────────────────────────

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isTv = DeviceUtils.isTvDevice(requireContext())
        return if (isTv) {
            Dialog(requireContext()).apply {
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setGravity(Gravity.CENTER)
                    setDimAmount(0.6f)
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        if (selectedItem != null) {
                            // Level 2 → Level 1: deselect without dismissing
                            clearTvSelection()
                            true
                        } else {
                            dismiss()
                            true
                        }
                    } else false
                }
            }
        } else {
            super.onCreateDialog(savedInstanceState)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val layoutRes = if (isTv) R.layout.dialog_manage_tiles_tv else R.layout.bottom_sheet_manage_tiles
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onStart() {
        super.onStart()
        if (isTv) {
            dialog?.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val params = attributes
                params.gravity = Gravity.CENTER
                params.width  = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                attributes = params
            }
        }
    }

    // ── View binding ──────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (isTv) setupTvView(view) else setupMobileView(view)
    }

    // ─── TV setup ─────────────────────────────────────────────────────────────

    private fun setupTvView(view: View) {
        val ctx = requireContext()

        // Cache view refs at class level for Back key handler
        tvRecycler       = view.findViewById(R.id.recyclerHiddenTiles)
        tvTxtSelected    = view.findViewById(R.id.txtSelectedTile)
        tvBtnRestore     = view.findViewById(R.id.btnRestoreTile)
        tvBtnOpen        = view.findViewById(R.id.btnOpenTile)
        tvBtnCancel      = view.findViewById(R.id.btnCancelAction)
        tvBtnRestoreAll  = view.findViewById(R.id.btnRestoreAll)
        val txtNoHidden  = view.findViewById<TextView>(R.id.txtNoHiddenTiles)

        tvRecycler!!.layoutManager = LinearLayoutManager(ctx)

        fun refreshList() {
            val hidden      = TileOrderManager.loadHidden(ctx)
            val hiddenItems = allTiles.filter { it.id in hidden }

            txtNoHidden.visibility       = if (hiddenItems.isEmpty()) View.VISIBLE else View.GONE
            tvBtnRestoreAll!!.visibility = if (hiddenItems.isEmpty()) View.GONE    else View.VISIBLE

            tvRecycler!!.adapter = TvHiddenTileAdapter(hiddenItems) { item ->
                // OK pressed on a row → enter action level
                enterTvActionMode(item)
            }

            // Auto-focus the first tile
            tvRecycler!!.post {
                tvRecycler!!.layoutManager
                    ?.findViewByPosition(lastFocusedRowIndex)?.requestFocus()
                    ?: tvRecycler!!.getChildAt(0)?.requestFocus()
            }
        }

        // Load hidden parent map for restore location display
        val hiddenParents = TileOrderManager.loadHiddenParents(ctx)
        val customTiles = CustomTileManager.loadCustomTiles(ctx).associateBy { it.id }

        // Restore All
        tvBtnRestoreAll!!.setOnClickListener {
            // Clear hidden set and parents, restoring all tiles to their original parents
            TileOrderManager.saveHidden(ctx, emptySet(), emptyMap())
            onRestored?.invoke()
            refreshList()
        }

        // Restore selected tile
        tvBtnRestore!!.setOnClickListener {
            selectedItem?.let { item ->
                val updated = TileOrderManager.loadHidden(ctx).toMutableSet()
                updated.remove(item.id)
                val parentMap = TileOrderManager.loadHiddenParents(ctx).toMutableMap()
                val originalParentId = parentMap[item.id] ?: ""

                // If tile was from a custom tile, check if it still exists
                if (originalParentId.isNotEmpty()) {
                    val ct = customTiles[originalParentId]
                    if (ct != null) {
                        // Restore to custom tile
                        CustomTileManager.setTileParent(ctx, item.id, originalParentId)
                        val order = CustomTileManager.loadTileOrder(ctx, originalParentId).toMutableList()
                        if (item.id !in order) {
                            order.add(item.id)
                            CustomTileManager.saveTileOrder(ctx, originalParentId, order)
                        }
                    } else {
                        // Custom tile deleted — restore to main screen
                        CustomTileManager.setTileParent(ctx, item.id, null)
                    }
                    parentMap.remove(item.id)
                }
                TileOrderManager.saveHidden(ctx, updated, parentMap)
                onRestored?.invoke()
            }
            clearTvSelection()
            refreshList()
        }

        // Open selected tile
        tvBtnOpen!!.setOnClickListener {
            val item = selectedItem ?: return@setOnClickListener
            dismiss()
            onTileClick?.invoke(item)
        }

        // Cancel — same as Back key in action mode
        tvBtnCancel!!.setOnClickListener {
            clearTvSelection()
        }

        refreshList()
    }

    /**
     * Enters Level 2 (action mode) for [item]:
     * Shows RESTORE / OPEN / CANCEL, hides Restore All, disables list scroll.
     */
    private fun enterTvActionMode(item: StorageItem) {
        selectedItem = item

        // Remember focused row position for restoration
        val lm = tvRecycler?.layoutManager as? LinearLayoutManager
        lastFocusedRowIndex = lm?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0

        tvTxtSelected?.text       = item.label
        tvTxtSelected?.visibility = View.VISIBLE
        tvBtnRestore?.visibility  = View.VISIBLE
        tvBtnOpen?.visibility     = View.VISIBLE
        tvBtnCancel?.visibility   = View.VISIBLE
        tvBtnRestoreAll?.visibility = View.GONE

        // Focus the Restore button first (most common action)
        tvBtnRestore?.requestFocus()
    }

    /**
     * Returns to Level 1 (tile-scroll mode). Safe to call from Back key handler.
     */
    private fun clearTvSelection() {
        selectedItem = null

        tvTxtSelected?.visibility   = View.GONE
        tvBtnRestore?.visibility    = View.GONE
        tvBtnOpen?.visibility       = View.GONE
        tvBtnCancel?.visibility     = View.GONE
        tvBtnRestoreAll?.visibility = View.VISIBLE

        // Return focus to the list
        tvRecycler?.post {
            tvRecycler?.layoutManager
                ?.findViewByPosition(lastFocusedRowIndex)?.requestFocus()
                ?: tvRecycler?.getChildAt(0)?.requestFocus()
        }
    }

    // ─── Mobile setup ─────────────────────────────────────────────────────────

    private fun setupMobileView(view: View) {
        val ctx           = requireContext()
        val recycler      = view.findViewById<RecyclerView>(R.id.recyclerHiddenTiles)
        val txtNoHidden   = view.findViewById<TextView>(R.id.txtNoHiddenTiles)
        val btnRestoreAll = view.findViewById<MaterialButton>(R.id.btnRestoreAll)

        recycler.layoutManager = LinearLayoutManager(ctx)

        val hiddenParents = TileOrderManager.loadHiddenParents(ctx)
        val customTiles = CustomTileManager.loadCustomTiles(ctx).associateBy { it.id }

        fun refresh() {
            val hidden      = TileOrderManager.loadHidden(ctx)
            val hiddenItems = allTiles.filter { it.id in hidden }

            txtNoHidden.visibility   = if (hiddenItems.isEmpty()) View.VISIBLE else View.GONE
            btnRestoreAll.visibility = if (hiddenItems.isEmpty()) View.GONE    else View.VISIBLE

            recycler.adapter = MobileHiddenTileAdapter(
                items     = hiddenItems,
                onRestore = { item ->
                    val updated = TileOrderManager.loadHidden(ctx).toMutableSet()
                    updated.remove(item.id)
                    val parentMap = TileOrderManager.loadHiddenParents(ctx).toMutableMap()
                    val originalParentId = parentMap[item.id] ?: ""

                    // Check if parent custom tile still exists
                    if (originalParentId.isNotEmpty()) {
                        val ct = customTiles[originalParentId]
                        if (ct != null) {
                            CustomTileManager.setTileParent(ctx, item.id, originalParentId)
                            val order = CustomTileManager.loadTileOrder(ctx, originalParentId).toMutableList()
                            if (item.id !in order) {
                                order.add(item.id)
                                CustomTileManager.saveTileOrder(ctx, originalParentId, order)
                            }
                        } else {
                            CustomTileManager.setTileParent(ctx, item.id, null)
                        }
                        parentMap.remove(item.id)
                    }
                    TileOrderManager.saveHidden(ctx, updated, parentMap)
                    onRestored?.invoke()
                    refresh()
                },
                onOpen = { item ->
                    dismiss()
                    onTileClick?.invoke(item)
                }
            )
        }

        btnRestoreAll.setOnClickListener {
            TileOrderManager.saveHidden(ctx, emptySet(), emptyMap())
            onRestored?.invoke()
            refresh()
        }

        refresh()
    }

    // ── TV Adapter ────────────────────────────────────────────────────────────

    private inner class TvHiddenTileAdapter(
        private val items: List<StorageItem>,
        private val onSelect: (StorageItem) -> Unit
    ) : RecyclerView.Adapter<TvHiddenTileAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon:  ImageView = view.findViewById(R.id.imgTileIcon)
            val label: TextView  = view.findViewById(R.id.txtTileName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hidden_tile_row_tv, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.icon.setImageResource(item.iconRes)
            applyCustomIcon(item, holder.icon)
            holder.label.text = item.label
            holder.itemView.setOnClickListener { onSelect(item) }
        }

        override fun getItemCount() = items.size
    }

    // ── Mobile Adapter ────────────────────────────────────────────────────────

    private inner class MobileHiddenTileAdapter(
        private val items: List<StorageItem>,
        private val onRestore: (StorageItem) -> Unit,
        private val onOpen:    (StorageItem) -> Unit
    ) : RecyclerView.Adapter<MobileHiddenTileAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon:  ImageView = view.findViewById(R.id.imgTileIcon)
            val label: TextView  = view.findViewById(R.id.txtTileName)
            val chip:  View      = view.findViewById(R.id.chipRestore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hidden_tile_row, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.icon.setImageResource(item.iconRes)
            applyCustomIcon(item, holder.icon)
            holder.label.text = item.label
            holder.chip.setOnClickListener { onRestore(item) }
            holder.itemView.setOnClickListener { onOpen(item) }
        }

        override fun getItemCount() = items.size
    }

    private fun applyCustomIcon(item: StorageItem, icon: ImageView) {
        val customPath = allTileIcons[item.id]?.takeIf { it.isNotEmpty() }
        if (customPath != null) {
            val bitmap = BitmapFactory.decodeFile(customPath)
            if (bitmap != null) {
                icon.setImageBitmap(bitmap)
                return
            }
        }
        val savedRes = allTileIconRes[item.id] ?: 0
        if (savedRes != 0 && savedRes != item.iconRes) {
            icon.setImageResource(savedRes)
        }
    }
}
