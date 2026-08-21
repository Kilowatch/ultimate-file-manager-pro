package za.kilowatch.ultimatefilemanager.storage

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R

/**
 * Bottom sheet that lets the user stamp a [TileColorConfig] from one tile onto
 * any combination of other tiles, or onto all tiles at once.
 *
 * Both "Apply to Selected" and "Apply to All" show a premium confirmation
 * dialog before committing — displaying a live preview tile (grid or list
 * depending on [isListView]) and the exact count of tiles to be updated.
 */
class TileCopyBottomSheet : BottomSheetDialogFragment() {

    private var sourceConfig: TileColorConfig = TileColorConfig()
    private var sourceTileId: String          = ""
    private var tiles: List<StorageItem>      = emptyList()
    private var isListView: Boolean           = false

    /** Called with the list of tile IDs that should receive the source config. */
    var onApply: ((targetIds: List<String>) -> Unit)? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.bottom_sheet_tile_copy, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Targets = all tiles except the source tile
        val targets = tiles.filter { it.id != sourceTileId }

        // ── Source colour dots (preview strip) ────────────────────────────────
        drawDot(view, R.id.sourceRingDot,      sourceConfig.ringColor)
        drawDot(view, R.id.sourceTileBgDot,    sourceConfig.tileBgColor)
        drawDot(view, R.id.sourceIconColorDot, sourceConfig.iconColor)
        drawDot(view, R.id.sourceIconBgDot,    sourceConfig.iconBgColor)
        drawDot(view, R.id.sourceLabelDot,     sourceConfig.labelColor)

        // ── RecyclerView ──────────────────────────────────────────────────────
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerTiles)
        val adapter  = TileCopyAdapter(targets, sourceConfig.ringColor)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        // ── Select-all toggle ──────────────────────────────────────────────────
        val btnToggle = view.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnToggleSelectAll
        )
        var allSelected = false
        btnToggle.setOnClickListener {
            allSelected = !allSelected
            if (allSelected) {
                adapter.selectAll()
                btnToggle.text = getString(R.string.tile_copy_deselect_all)
            } else {
                adapter.deselectAll()
                btnToggle.text = getString(R.string.tile_copy_select_all)
            }
        }

        // ── Apply to Selected → confirmation dialog ────────────────────────────
        view.findViewById<View>(R.id.btnApplySelected).setOnClickListener {
            val ids = adapter.getSelectedIds()
            if (ids.isNotEmpty()) showConfirmDialog(ids)
        }

        // ── Apply to All → confirmation dialog ────────────────────────────────
        view.findViewById<View>(R.id.btnApplyAll).setOnClickListener {
            showConfirmDialog(targets.map { it.id })
        }
    }

    // ── Confirmation dialog ───────────────────────────────────────────────────

    private fun showConfirmDialog(targetIds: List<String>) {
        val ctx        = requireContext()
        val sourceTile = tiles.find { it.id == sourceTileId }
        val dialogView = LayoutInflater.from(ctx)
            .inflate(R.layout.dialog_tile_copy_confirm, null)

        // Count label
        dialogView.findViewById<TextView>(R.id.txtConfirmCount).text =
            getString(R.string.tile_copy_confirm_count, targetIds.size)

        // Show grid or list preview depending on current view mode
        val gridContainer = dialogView.findViewById<View>(R.id.confGridContainer)
        val listCard      = dialogView.findViewById<MaterialCardView>(R.id.confListCard)
        if (isListView) {
            gridContainer.visibility = View.GONE
            listCard.visibility      = View.VISIBLE
        } else {
            gridContainer.visibility = View.VISIBLE
            listCard.visibility      = View.GONE
        }

        // Populate icon, label, subtitle from source tile
        val isImported = sourceTileId == "imported"
        val iconRes  = sourceTile?.iconRes ?: if (isImported) R.drawable.ic_import_code else R.drawable.ic_storage_internal
        val label    = sourceTile?.label   ?: if (isImported) getString(R.string.tile_color_preview_example_label) else ""
        val subtitle = sourceTile?.subtitle ?: if (isImported) getString(R.string.tile_color_preview_example_subtitle) else ""

        dialogView.findViewById<ImageView>(R.id.confPreviewIcon)?.setImageResource(iconRes)
        dialogView.findViewById<TextView>(R.id.confPreviewLabel)?.text    = label
        dialogView.findViewById<TextView>(R.id.confPreviewCapacity)?.text = subtitle

        dialogView.findViewById<ImageView>(R.id.confListIcon)?.setImageResource(iconRes)
        dialogView.findViewById<TextView>(R.id.confListLabel)?.text    = label
        dialogView.findViewById<TextView>(R.id.confListCapacity)?.text = subtitle

        // Apply source colours to both preview cards
        applyConfigToConfirmPreview(dialogView)

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_surface)

        dialogView.findViewById<View>(R.id.btnConfirmCancel)
            .setOnClickListener { dialog.dismiss() }

        dialogView.findViewById<View>(R.id.btnConfirmApply)
            .setOnClickListener {
                onApply?.invoke(targetIds)
                dialog.dismiss()
                dismiss()   // also close the copy sheet
            }

        dialog.show()
    }

    /** Stamps all [sourceConfig] colour fields onto the confirm dialog preview. */
    private fun applyConfigToConfirmPreview(dialogView: View) {
        val ctx     = dialogView.context
        val density = ctx.resources.displayMetrics.density

        // ── Grid preview ──────────────────────────────────────────────────────
        val gridCard = dialogView.findViewById<MaterialCardView>(R.id.confPreviewTile)
        if (gridCard != null) {
            // Tile background
            if (sourceConfig.tileBgColor != Color.TRANSPARENT)
                gridCard.setCardBackgroundColor(sourceConfig.tileBgColor)
            else
                gridCard.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.mobile_glass_card)
                )
            // Ring stroke
            if (sourceConfig.ringColor != Color.TRANSPARENT) {
                gridCard.strokeWidth = (2 * density).toInt()
                gridCard.setStrokeColor(sourceConfig.ringColor)
            } else {
                gridCard.strokeWidth = (1 * density).toInt()
                gridCard.setStrokeColor(ContextCompat.getColor(ctx, R.color.mobile_glass_stroke))
            }
            // Icon tint
            val gridIcon = dialogView.findViewById<ImageView>(R.id.confPreviewIcon)
            if (sourceConfig.iconColor != Color.TRANSPARENT)
                gridIcon?.setColorFilter(sourceConfig.iconColor)
            else
                gridIcon?.clearColorFilter()
            // Icon background
            val gridIconBg = dialogView.findViewById<FrameLayout>(R.id.confPreviewIconContainer)
            if (sourceConfig.iconBgColor != Color.TRANSPARENT) {
                val d = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(sourceConfig.iconBgColor)
                    setStroke((1 * density).toInt(), ContextCompat.getColor(ctx, R.color.mobile_glass_stroke))
                }
                gridIconBg?.background = d
            } else {
                gridIconBg?.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
            }
            // Label colour
            val gridLabel    = dialogView.findViewById<TextView>(R.id.confPreviewLabel)
            val gridCapacity = dialogView.findViewById<TextView>(R.id.confPreviewCapacity)
            if (sourceConfig.labelColor != Color.TRANSPARENT) {
                gridLabel?.setTextColor(sourceConfig.labelColor)
                gridCapacity?.setTextColor(sourceConfig.labelColor)
            } else {
                gridLabel?.setTextColor(ContextCompat.getColor(ctx, R.color.mobile_card_text_primary))
                gridCapacity?.setTextColor(ContextCompat.getColor(ctx, R.color.mobile_text_secondary))
            }
        }

        // ── List preview ──────────────────────────────────────────────────────
        val listCard = dialogView.findViewById<MaterialCardView>(R.id.confListCard)
        if (listCard != null && listCard.visibility == View.VISIBLE) {
            if (sourceConfig.tileBgColor != Color.TRANSPARENT)
                listCard.setCardBackgroundColor(sourceConfig.tileBgColor)
            else
                listCard.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.mobile_glass_card)
                )
            if (sourceConfig.ringColor != Color.TRANSPARENT) {
                listCard.strokeWidth = (2 * density).toInt()
                listCard.setStrokeColor(sourceConfig.ringColor)
            } else {
                listCard.strokeWidth = (1 * density).toInt()
                listCard.setStrokeColor(ContextCompat.getColor(ctx, R.color.mobile_glass_stroke))
            }
            val listIcon = dialogView.findViewById<ImageView>(R.id.confListIcon)
            if (sourceConfig.iconColor != Color.TRANSPARENT)
                listIcon?.setColorFilter(sourceConfig.iconColor)
            else
                listIcon?.clearColorFilter()
            val listIconBg = dialogView.findViewById<FrameLayout>(R.id.confListIconContainer)
            if (sourceConfig.iconBgColor != Color.TRANSPARENT) {
                val d = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(sourceConfig.iconBgColor)
                    setStroke((1 * density).toInt(), ContextCompat.getColor(ctx, R.color.mobile_glass_stroke))
                }
                listIconBg?.background = d
            } else {
                listIconBg?.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
            }
            val listLabel    = dialogView.findViewById<TextView>(R.id.confListLabel)
            val listCapacity = dialogView.findViewById<TextView>(R.id.confListCapacity)
            if (sourceConfig.labelColor != Color.TRANSPARENT) {
                listLabel?.setTextColor(sourceConfig.labelColor)
                listCapacity?.setTextColor(sourceConfig.labelColor)
            } else {
                listLabel?.setTextColor(ContextCompat.getColor(ctx, R.color.mobile_card_text_primary))
                listCapacity?.setTextColor(ContextCompat.getColor(ctx, R.color.mobile_card_text_secondary))
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun drawDot(view: View, dotId: Int, color: Int) {
        val dot = view.findViewById<View>(dotId) ?: return
        val d   = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        if (color == Color.TRANSPARENT) {
            d.setColor(Color.TRANSPARENT)
            d.setStroke(2, ContextCompat.getColor(dot.context, R.color.mobile_glass_stroke))
        } else {
            d.setColor(color)
        }
        dot.background = d
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val TAG = "TileCopyBottomSheet"

        fun newInstance(
            sourceConfig: TileColorConfig,
            sourceTileId: String,
            tiles: List<StorageItem>,
            isListView: Boolean = false
        ): TileCopyBottomSheet = TileCopyBottomSheet().also {
            it.sourceConfig  = sourceConfig
            it.sourceTileId  = sourceTileId
            it.tiles         = tiles
            it.isListView    = isListView
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────────

private class TileCopyAdapter(
    private val items: List<StorageItem>,
    /** Ring colour from the source config — shown as a preview dot on every row. */
    private val sourceDotColor: Int
) : RecyclerView.Adapter<TileCopyAdapter.VH>() {

    private val selected = mutableSetOf<String>()

    fun selectAll()   { selected.addAll(items.map { it.id }); notifyDataSetChanged() }
    fun deselectAll() { selected.clear(); notifyDataSetChanged() }
    fun getSelectedIds(): List<String> = selected.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tile_select, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chk:  CheckBox  = itemView.findViewById(R.id.chkTile)
        private val icon: ImageView = itemView.findViewById(R.id.tileIcon)
        private val name: TextView  = itemView.findViewById(R.id.tileName)
        private val dot:  View      = itemView.findViewById(R.id.tileColorDot)

        fun bind(item: StorageItem) {
            name.text = item.label
            icon.setImageResource(item.iconRes)
            chk.isChecked = item.id in selected

            // Source colour dot — same on every row (shows what WILL be applied)
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            if (sourceDotColor == Color.TRANSPARENT) {
                d.setColor(Color.TRANSPARENT)
                d.setStroke(2, ContextCompat.getColor(itemView.context, R.color.mobile_glass_stroke))
            } else {
                d.setColor(sourceDotColor)
            }
            dot.background = d

            itemView.setOnClickListener {
                if (item.id in selected) selected.remove(item.id) else selected.add(item.id)
                chk.isChecked = item.id in selected
            }
        }
    }
}
