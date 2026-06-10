package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.content.Context
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * TV full-screen tile copy activity.
 * State A: D-Pad navigable tile selection list.
 * State B: Inline confirmation with live preview.
 *
 * On confirm, saves the source config to all target tiles via [TileColorManager]
 * and returns RESULT_OK so the caller can refresh the adapter.
 */
class TileCopyTvActivity : AppCompatActivity() {

    private lateinit var selectionLayout: View
    private lateinit var confirmLayout:   View
    private lateinit var adapter:         TvTileCopyAdapter

    private var pendingTargetIds: List<String> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tile_copy_tv)

        val holder     = TvTileDataHolder
        val sourceConf = holder.sourceConfig
        val sourceId   = holder.sourceTileId
        val isListView = holder.isListView
        val targets    = holder.tiles.filter { it.id != sourceId }

        selectionLayout = findViewById(R.id.layoutSelectionState)
        confirmLayout   = findViewById(R.id.layoutConfirmState)

        // ── Selection state ───────────────────────────────────────────────
        adapter = TvTileCopyAdapter(targets, sourceConf)
        val recycler = findViewById<RecyclerView>(R.id.recyclerTilesCopy)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val btnToggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTvToggleSelectAll)
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

        findViewById<View>(R.id.btnTvApplySelected).setOnClickListener {
            val ids = adapter.getSelectedIds()
            if (ids.isNotEmpty()) showConfirmState(ids, sourceConf, isListView)
        }

        findViewById<View>(R.id.btnTvApplyAll).setOnClickListener {
            showConfirmState(targets.map { it.id }, sourceConf, isListView)
        }

        // ── Confirmation state ────────────────────────────────────────────
        // Confirmation buttons are wired here but only visible in State B
        findViewById<View>(R.id.btnTvConfirmCancel).setOnClickListener {
            showSelectionState()
        }

        findViewById<View>(R.id.btnTvConfirmApply).setOnClickListener {
            commitCopy(pendingTargetIds, sourceConf)
        }
    }

    // ── State transitions ─────────────────────────────────────────────────

    private fun showSelectionState() {
        selectionLayout.visibility = View.VISIBLE
        confirmLayout.visibility   = View.GONE
    }

    private fun showConfirmState(
        targetIds:  List<String>,
        sourceConf: TileColorConfig,
        isListView: Boolean
    ) {
        pendingTargetIds = targetIds

        // Count label
        findViewById<TextView>(R.id.txtConfirmCountTv).text =
            getString(R.string.tile_copy_confirm_count, targetIds.size)

        // Show correct preview
        val gridContainer = findViewById<View>(R.id.confTvGridContainer)
        val listCard      = findViewById<MaterialCardView>(R.id.confTvListCard)
        if (isListView) {
            gridContainer.visibility = View.GONE
            listCard.visibility      = View.VISIBLE
        } else {
            gridContainer.visibility = View.VISIBLE
            listCard.visibility      = View.GONE
        }

        // Populate preview with source tile info
        val sourceTileId = TvTileDataHolder.sourceTileId
        val sourceTile = TvTileDataHolder.tiles.find { it.id == sourceTileId }
        val isImported = sourceTileId == "imported"
        val iconRes  = sourceTile?.iconRes  ?: if (isImported) R.drawable.ic_import_code else R.drawable.ic_storage_internal
        val label    = sourceTile?.label    ?: if (isImported) getString(R.string.tile_color_preview_example_label) else ""
        val subtitle = sourceTile?.subtitle ?: if (isImported) getString(R.string.tile_color_preview_example_subtitle) else ""

        // Grid preview
        findViewById<ImageView>(R.id.confTvIcon)?.setImageResource(iconRes)
        findViewById<TextView>(R.id.confTvLabel)?.text    = label
        findViewById<TextView>(R.id.confTvCapacity)?.text = subtitle

        // List preview
        findViewById<ImageView>(R.id.confTvListIcon)?.setImageResource(iconRes)
        findViewById<TextView>(R.id.confTvListLabel)?.text    = label
        findViewById<TextView>(R.id.confTvListCapacity)?.text = subtitle

        applyConfigToConfirmPreview(sourceConf)

        selectionLayout.visibility = View.GONE
        confirmLayout.visibility   = View.VISIBLE
    }

    private fun commitCopy(targetIds: List<String>, sourceConf: TileColorConfig) {
        targetIds.forEach { id ->
            TileColorManager.saveTileColor(this, id, sourceConf)
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    // ── Preview colour application ─────────────────────────────────────────

    private fun applyConfigToConfirmPreview(conf: TileColorConfig) {
        val density = resources.displayMetrics.density

        fun applyCard(card: MaterialCardView?, iconContainer: FrameLayout?, icon: ImageView?, label: TextView?, capacity: TextView?) {
            card ?: return
            card.setCardBackgroundColor(
                if (conf.tileBgColor != Color.TRANSPARENT) conf.tileBgColor
                else ContextCompat.getColor(this, R.color.tv_glass_white_10)
            )
            if (conf.ringColor != Color.TRANSPARENT) {
                card.strokeWidth = (2 * density).toInt()
                card.setStrokeColor(conf.ringColor)
            } else {
                card.strokeWidth = 0
            }
            if (conf.iconBgColor != Color.TRANSPARENT) {
                val d = GradientDrawable()
                d.shape = GradientDrawable.OVAL
                d.setColor(conf.iconBgColor)
                iconContainer?.background = d
            } else {
                iconContainer?.setBackgroundResource(R.drawable.bg_icon_circle_accent)
            }
            if (conf.iconColor != Color.TRANSPARENT) icon?.setColorFilter(conf.iconColor)
            else icon?.clearColorFilter()

            if (conf.labelColor != Color.TRANSPARENT) {
                label?.setTextColor(conf.labelColor)
                capacity?.setTextColor(conf.labelColor)
            } else {
                label?.setTextColor(ContextCompat.getColor(this, R.color.tv_text_primary))
                capacity?.setTextColor(ContextCompat.getColor(this, R.color.tv_text_secondary))
            }
        }

        applyCard(
            card          = findViewById(R.id.confTvPreviewTile),
            iconContainer = findViewById(R.id.confTvIconContainer),
            icon          = findViewById(R.id.confTvIcon),
            label         = findViewById(R.id.confTvLabel),
            capacity      = findViewById(R.id.confTvCapacity)
        )
        applyCard(
            card          = findViewById(R.id.confTvListCard),
            iconContainer = findViewById(R.id.confTvListIconContainer),
            icon          = findViewById(R.id.confTvListIcon),
            label         = findViewById(R.id.confTvListLabel),
            capacity      = findViewById(R.id.confTvListCapacity)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RecyclerView adapter
// ─────────────────────────────────────────────────────────────────────────────

private class TvTileCopyAdapter(
    private val items:      List<StorageItem>,
    private val sourceConf: TileColorConfig
) : RecyclerView.Adapter<TvTileCopyAdapter.VH>() {

    private val selected = mutableSetOf<String>()

    fun selectAll()   { selected.addAll(items.map { it.id }); notifyDataSetChanged() }
    fun deselectAll() { selected.clear(); notifyDataSetChanged() }
    fun getSelectedIds(): List<String> = selected.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tile_select_tv, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    private fun drawDot(view: View, color: Int) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        if (color == Color.TRANSPARENT) {
            d.setColor(Color.TRANSPARENT)
            d.setStroke(2, ContextCompat.getColor(view.context, R.color.tv_glass_border))
        } else {
            d.setColor(color)
        }
        view.background = d
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chk:  CheckBox  = itemView.findViewById(R.id.chkTile)
        private val icon: ImageView = itemView.findViewById(R.id.tileIcon)
        private val name: TextView  = itemView.findViewById(R.id.tileName)
        private val dotRing:     View = itemView.findViewById(R.id.dotRing)
        private val dotTileBg:   View = itemView.findViewById(R.id.dotTileBg)
        private val dotIconColor: View = itemView.findViewById(R.id.dotIconColor)
        private val dotIconBg:   View = itemView.findViewById(R.id.dotIconBg)
        private val dotLabel:    View = itemView.findViewById(R.id.dotLabel)

        fun bind(item: StorageItem) {
            name.text = item.label
            icon.setImageResource(item.iconRes)
            chk.isChecked = item.id in selected

            // Source colour dots — shows what WILL be applied to every tile
            drawDot(dotRing,      sourceConf.ringColor)
            drawDot(dotTileBg,    sourceConf.tileBgColor)
            drawDot(dotIconColor, sourceConf.iconColor)
            drawDot(dotIconBg,    sourceConf.iconBgColor)
            drawDot(dotLabel,     sourceConf.labelColor)

            itemView.setOnClickListener {
                if (item.id in selected) selected.remove(item.id) else selected.add(item.id)
                chk.isChecked = item.id in selected
            }
        }
    }
}
