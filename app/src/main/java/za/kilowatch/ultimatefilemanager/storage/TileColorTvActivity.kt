package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * TV full-screen tile colour editor.
 * Left panel: icon header + 5 collapsible colour sections (D-Pad navigable).
 * Right panel: live tile preview (grid or list).
 *
 * Launched from [StorageBrowserActivity] via [createIntent].
 * Returns updated [TileColorConfig] as intent extras.
 */
class TileColorTvActivity : AppCompatActivity() {

    // ── State ─────────────────────────────────────────────────────────────
    private var ringColor:    Int = Color.TRANSPARENT
    private var iconColor:    Int = Color.TRANSPARENT
    private var iconBgColor:  Int = Color.TRANSPARENT
    private var tileBgColor:  Int = Color.TRANSPARENT
    private var labelColor:   Int = Color.TRANSPARENT

    // Icon selection state
    private var originalIconRes: Int = R.drawable.ic_storage_internal
    private var selectedIconRes: Int = R.drawable.ic_storage_internal
    private var customIconPath: String? = null

    private var isListView = false

    // Which section property the active custom picker was opened for
    private var pendingSection: ColorSection? = null

    private lateinit var sectionAdapter: ColorSectionAdapter

    // ── Result launchers ──────────────────────────────────────────────────

    private val customPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val color = result.data?.getIntExtra(TvColorPickerActivity.EXTRA_COLOR, Color.WHITE) ?: return@registerForActivityResult
                applyColor(pendingSection ?: return@registerForActivityResult, color)
            }
        }

    private val copyToLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Copy sheet handled saving internally
            }
        }

    private val tvIconPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val path = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
                if (path != null) {
                    val sourceId = TvTileDataHolder.sourceTileId
                    val sourceFile = java.io.File(path)
                    if (sourceFile.exists() && sourceFile.length() > TileIconManager.MAX_SIZE_BYTES) {
                        android.widget.Toast.makeText(this, R.string.tile_icon_file_too_large, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val privatePath = TileIconManager.copyToPrivateStorage(this, sourceId, path)
                        if (privatePath != null) {
                            customIconPath = privatePath
                            selectedIconRes = originalIconRes
                            tvUpdateIconPreview()
                            updatePreview()
                        } else {
                            android.widget.Toast.makeText(this, R.string.tile_icon_invalid_file, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tile_color_tv)

        isListView = intent.getBooleanExtra(EXTRA_IS_LIST_VIEW, false)

        // Load initial config from holder (set by StorageBrowserActivity before launch)
        val holder = TvTileDataHolder
        ringColor   = holder.sourceConfig.ringColor
        iconColor   = holder.sourceConfig.iconColor
        iconBgColor = holder.sourceConfig.iconBgColor
        tileBgColor = holder.sourceConfig.tileBgColor
        labelColor  = holder.sourceConfig.labelColor

        // ── Populate preview ─────────────────────────────────────────────
        val tile = holder.tiles.find { it.id == holder.sourceTileId }
        val label    = tile?.label    ?: ""
        val subtitle = tile?.subtitle ?: ""
        val iconRes  = tile?.iconRes  ?: R.drawable.ic_storage_internal
        originalIconRes = iconRes
        selectedIconRes = iconRes

        // Load existing custom icon from storage
        val existingPath = TileIconManager.getTileIcon(this, holder.sourceTileId)
        if (existingPath != null) {
            customIconPath = existingPath
            selectedIconRes = iconRes
        } else {
            val savedRes = TileIconManager.getTileIconRes(this, holder.sourceTileId)
            if (savedRes != 0 && savedRes != iconRes) {
                selectedIconRes = savedRes
            }
        }

        findViewById<TextView>(R.id.txtTileColorTitle).text = label

        // Grid preview population
        findViewById<ImageView>(R.id.tvPreviewIcon)?.let { applyIconToView(it) }
        findViewById<TextView>(R.id.tvPreviewLabel)?.text    = label
        findViewById<TextView>(R.id.tvPreviewCapacity)?.text = subtitle

        // List preview population
        findViewById<ImageView>(R.id.tvPreviewListIcon)?.let { applyIconToView(it) }
        findViewById<TextView>(R.id.tvPreviewListLabel)?.text    = label
        findViewById<TextView>(R.id.tvPreviewListCapacity)?.text = subtitle

        // Show correct preview type
        val gridCard = findViewById<MaterialCardView>(R.id.tvPreviewTile)
        val listCard = findViewById<MaterialCardView>(R.id.tvPreviewListCard)
        if (isListView) {
            gridCard?.visibility = View.GONE
            listCard.visibility      = View.VISIBLE
        } else {
            gridCard?.visibility = View.VISIBLE
            listCard.visibility      = View.GONE
        }

        updatePreview()

        // ── Section list (5 colour sections) ────────────────────────────
        val sections = listOf(
            ColorSection.ICON,
            ColorSection.TILE_BG,
            ColorSection.RING,
            ColorSection.ICON_BG,
            ColorSection.LABEL
        )

        sectionAdapter = ColorSectionAdapter(
            sections = sections,
            labelFor = { section -> getString(sectionLabelRes(section)) },
            colorFor = { section -> colorFor(section) },
            onSwatchSelected = { section, color -> applyColor(section, color) },
            onCustomSelected = { section ->
                pendingSection = section
                customPickerLauncher.launch(
                    TvColorPickerActivity.createIntent(this, colorFor(section))
                )
            }
        )

        val recycler = findViewById<RecyclerView>(R.id.recyclerColorSections)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = sectionAdapter

        // ── Icon section buttons (right panel) ──────────────────────────
        tvUpdateIconPreview()

        findViewById<View>(R.id.btnTvBrowseIcon)?.setOnClickListener {
            tvIconPickerLauncher.launch(
                Intent(this, StorageBrowserActivity::class.java).apply {
                    putExtra(StorageBrowserActivity.EXTRA_TILE_ICON_PICKER, true)
                }
            )
        }

        findViewById<View>(R.id.btnTvIcons)?.setOnClickListener {
            showIconPickerDialog()
        }

        findViewById<View>(R.id.btnTvResetIcon)?.setOnClickListener {
            customIconPath = null
            selectedIconRes = originalIconRes
            tvUpdateIconPreview()
            updatePreview()
        }

        // ── Buttons ───────────────────────────────────────────────────────
        findViewById<View>(R.id.btnTvColorReset).setOnClickListener {
            ringColor   = Color.TRANSPARENT
            iconColor   = Color.TRANSPARENT
            iconBgColor = Color.TRANSPARENT
            tileBgColor = Color.TRANSPARENT
            labelColor  = Color.TRANSPARENT
            sectionAdapter.notifyDataSetChanged()
            updatePreview()
        }

        findViewById<View>(R.id.btnTvColorExport).setOnClickListener {
            val config = buildConfig()
            val code = TileColorCodec.encode(config)

            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tile_color_export_tv, null)
            val tvCode = dialogView.findViewById<TextView>(R.id.tvExportCode)
            val btnCopy = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnExportCopy)
            val btnClose = dialogView.findViewById<View>(R.id.btnExportClose)

            tvCode.text = code

            val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Translucent_NoTitleBar)
                .setView(dialogView)
                .create()

            btnCopy.setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText(getString(R.string.tile_color_code_clipboard_label), code)
                clipboard.setPrimaryClip(clip)
                btnCopy.text = getString(R.string.tile_color_export_copied)
                btnCopy.setIconResource(R.drawable.ic_check)
                android.widget.Toast.makeText(this, R.string.tile_color_code_copied_toast, android.widget.Toast.LENGTH_SHORT).show()
            }

            btnClose.setOnClickListener { dialog.dismiss() }

            dialog.show()
        }

        findViewById<View>(R.id.btnTvColorDone).setOnClickListener {
            val sourceId = TvTileDataHolder.sourceTileId
            TileIconManager.saveTileIconRes(this, sourceId, selectedIconRes)
            if (customIconPath != null) {
                TileIconManager.saveTileIcon(this, sourceId, customIconPath)
            } else if (selectedIconRes == originalIconRes) {
                TileIconManager.clearTileIcon(this, sourceId)
            }
            setResult(Activity.RESULT_OK, buildResultIntent())
            finish()
        }

        findViewById<View>(R.id.btnTvCopyTo).setOnClickListener {
            TvTileDataHolder.sourceConfig = buildConfig()
            copyToLauncher.launch(Intent(this, TileCopyTvActivity::class.java))
        }
    }

    // ── Icon picker dialog ─────────────────────────────────────────────────

    private fun showIconPickerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tv_icon_picker, null)
        val grid = dialogView.findViewById<RecyclerView>(R.id.tvIconPickerGrid)
        var pendingIconRes = selectedIconRes

        // Limit grid height so Apply/Cancel buttons stay visible
        val maxGridPx = (resources.displayMetrics.density * 280).toInt().coerceAtLeast(200)
        (grid.layoutParams as? ViewGroup.LayoutParams)?.height = maxGridPx

        grid.layoutManager = GridLayoutManager(this, 6)
        grid.adapter = IconPickerAdapter(BUILTIN_ICONS, pendingIconRes) { res ->
            pendingIconRes = res
        }

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Translucent_NoTitleBar)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btnIconPickerApply).setOnClickListener {
            selectedIconRes = pendingIconRes
            customIconPath = null  // file icon overrides built-in; reset when picking built-in
            tvUpdateIconPreview()
            updatePreview()
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnIconPickerCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── Colour helpers ────────────────────────────────────────────────────

    private fun colorFor(section: ColorSection): Int = when (section) {
        ColorSection.ICON    -> iconColor
        ColorSection.TILE_BG -> tileBgColor
        ColorSection.RING    -> ringColor
        ColorSection.ICON_BG -> iconBgColor
        ColorSection.LABEL   -> labelColor
    }

    private fun applyColor(section: ColorSection, color: Int) {
        when (section) {
            ColorSection.ICON    -> iconColor   = color
            ColorSection.TILE_BG -> tileBgColor = color
            ColorSection.RING    -> ringColor   = color
            ColorSection.ICON_BG -> iconBgColor = color
            ColorSection.LABEL   -> labelColor  = color
        }
        sectionAdapter.refreshSection(color)
        updatePreview()
    }

    private fun sectionLabelRes(section: ColorSection): Int = when (section) {
        ColorSection.ICON    -> R.string.tile_color_icon
        ColorSection.TILE_BG -> R.string.tile_color_tile_bg
        ColorSection.RING    -> R.string.tile_color_ring
        ColorSection.ICON_BG -> R.string.tile_color_icon_bg
        ColorSection.LABEL   -> R.string.tile_color_label
    }

    // ── Preview update ────────────────────────────────────────────────────

    private fun updatePreview() {
        val density = resources.displayMetrics.density

        // Grid card
        val gridCard = findViewById<MaterialCardView>(R.id.tvPreviewTile)
        if (gridCard != null) {
            gridCard.setCardBackgroundColor(
                if (tileBgColor != Color.TRANSPARENT) tileBgColor
                else ContextCompat.getColor(this, R.color.tv_glass_white_10)
            )
            if (ringColor != Color.TRANSPARENT) {
                gridCard.strokeWidth = (2 * density).toInt()
                gridCard.setStrokeColor(ringColor)
            } else {
                gridCard.strokeWidth = 0
            }
            applyIconToContainer(
                iconContainer = findViewById(R.id.tvPreviewIconContainer),
                icon          = findViewById(R.id.tvPreviewIcon),
                label         = findViewById(R.id.tvPreviewLabel),
                capacity      = findViewById(R.id.tvPreviewCapacity)
            )
        }

        // List card
        val listCard = findViewById<MaterialCardView>(R.id.tvPreviewListCard)
        if (listCard != null && listCard.visibility == View.VISIBLE) {
            listCard.setCardBackgroundColor(
                if (tileBgColor != Color.TRANSPARENT) tileBgColor
                else ContextCompat.getColor(this, R.color.tv_glass_white_10)
            )
            if (ringColor != Color.TRANSPARENT) {
                listCard.strokeWidth = (2 * density).toInt()
                listCard.setStrokeColor(ringColor)
            } else {
                listCard.strokeWidth = 0
            }
            applyIconToContainer(
                iconContainer = findViewById(R.id.tvPreviewListIconContainer),
                icon          = findViewById(R.id.tvPreviewListIcon),
                label         = findViewById(R.id.tvPreviewListLabel),
                capacity      = findViewById(R.id.tvPreviewListCapacity)
            )
        }
    }

    private fun applyIconToContainer(
        iconContainer: FrameLayout?,
        icon:          ImageView?,
        label:         TextView?,
        capacity:      TextView?
    ) {
        applyIconToView(icon)
        if (iconBgColor != Color.TRANSPARENT) {
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(iconBgColor)
            iconContainer?.background = d
        } else {
            iconContainer?.setBackgroundResource(R.drawable.bg_icon_circle_accent)
        }
        if (iconColor != Color.TRANSPARENT) icon?.setColorFilter(iconColor)
        else icon?.clearColorFilter()

        if (labelColor != Color.TRANSPARENT) {
            label?.setTextColor(labelColor)
            capacity?.setTextColor(labelColor)
        } else {
            label?.setTextColor(ContextCompat.getColor(this, R.color.tv_text_primary))
            capacity?.setTextColor(ContextCompat.getColor(this, R.color.tv_text_secondary))
        }
    }

    // ── Icon helpers ──────────────────────────────────────────────────────

    private fun applyIconToView(icon: ImageView?) {
        if (icon == null) return
        if (customIconPath != null) {
            val bm = BitmapFactory.decodeFile(customIconPath)
            if (bm != null) {
                icon.setImageBitmap(bm)
                return
            }
        }
        icon.setImageResource(selectedIconRes)
    }

    /** Refreshes the icon preview in the RecyclerView header. */
    private fun tvUpdateIconPreview() {
        findViewById<ImageView>(R.id.tvIconPreview)?.let { applyIconToView(it) }
    }

    // ── Result helpers ────────────────────────────────────────────────────

    private fun buildConfig() = TileColorConfig(
        ringColor   = ringColor,
        iconColor   = iconColor,
        iconBgColor = iconBgColor,
        tileBgColor = tileBgColor,
        labelColor  = labelColor
    )

    private fun buildResultIntent(): Intent = Intent().apply {
        putExtra(RESULT_RING_COLOR,   ringColor)
        putExtra(RESULT_ICON_COLOR,   iconColor)
        putExtra(RESULT_ICON_BG,      iconBgColor)
        putExtra(RESULT_TILE_BG,      tileBgColor)
        putExtra(RESULT_LABEL_COLOR,  labelColor)
    }

    // ── Companion ─────────────────────────────────────────────────────────

    companion object {
        const val EXTRA_IS_LIST_VIEW  = "is_list_view"
        const val RESULT_RING_COLOR   = "ring_color"
        const val RESULT_ICON_COLOR   = "icon_color"
        const val RESULT_ICON_BG      = "icon_bg"
        const val RESULT_TILE_BG      = "tile_bg"
        const val RESULT_LABEL_COLOR  = "label_color"

        val BUILTIN_ICONS = intArrayOf(
            R.drawable.ic_folder,
            R.drawable.ic_home,
            R.drawable.ic_storage_internal,
            R.drawable.ic_storage_usb,
            R.drawable.ic_storage_sdcard,
            R.drawable.ic_cloud,
            R.drawable.ic_network,
            R.drawable.ic_dropbox,
            R.drawable.ic_apps,
            R.drawable.ic_search,
            R.drawable.ic_analyzer,
            R.drawable.ic_lock,
            R.drawable.ic_star,
            R.drawable.ic_settings,
            R.drawable.ic_sync,
            R.drawable.ic_notepad,
            R.drawable.ic_scanner,
            R.drawable.ic_terminal,
            R.drawable.ic_file_server,
            R.drawable.ic_about,
            R.drawable.ic_delete,
            R.drawable.ic_sort,
            R.drawable.ic_twin_window,
            R.drawable.ic_tv
        )

        fun createIntent(context: Context, isListView: Boolean): Intent =
            Intent(context, TileColorTvActivity::class.java)
                .putExtra(EXTRA_IS_LIST_VIEW, isListView)
    }

    /** Colour property enum for the 5 customisable sections. */
    enum class ColorSection { ICON, TILE_BG, RING, ICON_BG, LABEL }
}

// ─────────────────────────────────────────────────────────────────────────────
// Icon picker adapter (for the dialog grid)
// ─────────────────────────────────────────────────────────────────────────────

private class IconPickerAdapter(
    private val icons: IntArray,
    initialSelection: Int,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<IconPickerAdapter.VH>() {

    private var selectedIcon: Int = initialSelection

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.tvIconPickerItemIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tv_icon_picker, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val iconRes = icons[position]
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        val isSelected = iconRes == selectedIcon

        holder.icon.setImageResource(iconRes)

        // Background circle — red ring when selected, translucent when not
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isSelected) {
                setColor(Color.TRANSPARENT)
                setStroke((3 * density).toInt(), 0xFFFF0000.toInt())
            } else {
                setColor(ContextCompat.getColor(ctx, R.color.tv_glass_white_20))
                setStroke(0, Color.TRANSPARENT)
            }
        }
        holder.icon.background = bg

        // Focus ring
        fun applyFocusRing(hasFocus: Boolean) {
            if (hasFocus) {
                val ring = GradientDrawable()
                ring.shape = GradientDrawable.OVAL
                ring.setColor(Color.TRANSPARENT)
                ring.setStroke((3 * density).toInt(), ContextCompat.getColor(ctx, R.color.tv_accent))
                holder.itemView.foreground = ring
            } else {
                holder.itemView.foreground = null
            }
        }
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> applyFocusRing(hasFocus) }
        applyFocusRing(holder.itemView.hasFocus())

        holder.itemView.setOnClickListener {
            selectedIcon = iconRes
            notifyDataSetChanged()
            onSelectionChanged(iconRes)
        }
    }

    override fun getItemCount() = icons.size
}

// ─────────────────────────────────────────────────────────────────────────────
// Section adapter (header + colour sections)
// ─────────────────────────────────────────────────────────────────────────────

private class ColorSectionAdapter(
    private val sections:         List<TileColorTvActivity.ColorSection>,
    private val labelFor:         (TileColorTvActivity.ColorSection) -> String,
    private val colorFor:         (TileColorTvActivity.ColorSection) -> Int,
    private val onSwatchSelected: (TileColorTvActivity.ColorSection, Int) -> Unit,
    private val onCustomSelected: (TileColorTvActivity.ColorSection) -> Unit
) : RecyclerView.Adapter<ColorSectionAdapter.VH>() {

    var expandedPos = -1
        private set

    private var swatchAdapter: TvSectionSwatchAdapter? = null
    private var expandedDotView: View? = null

    private val sectionPresets = listOf(
        Color.TRANSPARENT,
        0xFFB71C1C.toInt(), 0xFF880E4F.toInt(), 0xFF4A148C.toInt(), 0xFF0D47A1.toInt(),
        0xFF006064.toInt(), 0xFF1B5E20.toInt(), 0xFFE65100.toInt(), 0xFF3E2723.toInt(),
        0xFFE53935.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF2196F3.toInt(),
        0xFF009688.toInt(), 0xFF4CAF50.toInt(), 0xFFFF9800.toInt(), 0xFFFFFFFF.toInt()
    )

    fun refreshSection(newColor: Int) {
        swatchAdapter?.updateSelected(newColor)
        expandedDotView?.let { dot ->
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            if (newColor == Color.TRANSPARENT) {
                d.setColor(Color.TRANSPARENT)
                d.setStroke(3, ContextCompat.getColor(dot.context, R.color.tv_glass_border))
            } else {
                d.setColor(newColor)
            }
            dot.background = d
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_section_tv, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(sections[position], position == expandedPos)

    override fun getItemCount() = sections.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val header: View         = itemView.findViewById(R.id.sectionHeader)
        private val dot:    View         = itemView.findViewById(R.id.sectionColorDot)
        private val lbl:    TextView     = itemView.findViewById(R.id.sectionLabel)
        private val arrow:  ImageView    = itemView.findViewById(R.id.sectionExpandArrow)
        private val grid:   RecyclerView = itemView.findViewById(R.id.sectionSwatchGrid)

        fun bind(section: TileColorTvActivity.ColorSection, expanded: Boolean) {
            lbl.text = labelFor(section)
            val color = colorFor(section)

            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            if (color == Color.TRANSPARENT) {
                d.setColor(Color.TRANSPARENT)
                d.setStroke(3, ContextCompat.getColor(dot.context, R.color.tv_glass_border))
            } else {
                d.setColor(color)
            }
            dot.background = d

            arrow.rotation = if (expanded) 180f else 0f
            grid.visibility = if (expanded) View.VISIBLE else View.GONE

            if (expanded) {
                expandedDotView = dot
                if (swatchAdapter == null) {
                    val adapter = TvSectionSwatchAdapter(
                        presets         = sectionPresets,
                        initialSelected = color,
                        onSelected      = { c -> onSwatchSelected(section, c) },
                        onCustomClicked = { onCustomSelected(section) }
                    )
                    swatchAdapter = adapter
                    grid.layoutManager = androidx.recyclerview.widget.GridLayoutManager(grid.context, 5)
                    grid.adapter = adapter
                }
            } else {
                expandedDotView = null
                if (bindingAdapterPosition == expandedPos || expandedPos == -1) swatchAdapter = null
            }

            header.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_ID.toInt()) return@setOnClickListener
                val prev = expandedPos
                expandedPos = if (expandedPos == pos) -1 else pos
                swatchAdapter = null
                if (prev >= 0) notifyItemChanged(prev)
                if (expandedPos >= 0) notifyItemChanged(expandedPos)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section swatch adapter (within expanded section) — unchanged
// ─────────────────────────────────────────────────────────────────────────────

private class TvSectionSwatchAdapter(
    private val presets:         List<Int>,
    initialSelected:             Int,
    private val onSelected:      (Int) -> Unit,
    private val onCustomClicked: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var selectedColor: Int = initialSelected

    companion object {
        private const val TYPE_SWATCH  = 0
        private const val TYPE_CUSTOM  = 1
        private const val TAG_CUSTOM   = "custom_label_added"
    }

    fun updateSelected(newColor: Int) {
        val oldPos = presets.indexOf(selectedColor)
        val newPos = presets.indexOf(newColor)
        selectedColor = newColor
        if (oldPos >= 0) notifyItemChanged(oldPos)
        if (newPos >= 0) notifyItemChanged(newPos)
        if (newPos < 0)  notifyItemChanged(presets.size)
    }

    override fun getItemCount() = presets.size + 1

    override fun getItemViewType(position: Int) =
        if (position < presets.size) TYPE_SWATCH else TYPE_CUSTOM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_swatch_tv, parent, false)
        return if (viewType == TYPE_SWATCH) SwatchVH(v) else CustomVH(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SwatchVH -> holder.bind(presets[position])
            is CustomVH -> holder.bind()
        }
    }

    inner class SwatchVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val swatch: View = itemView.findViewById(R.id.swatchColor)
        private val check:  View = itemView.findViewById(R.id.swatchCheck)

        fun bind(color: Int) {
            val ctx     = swatch.context
            val density = ctx.resources.displayMetrics.density

            val fill = GradientDrawable()
            fill.shape = GradientDrawable.RECTANGLE
            fill.cornerRadius = 6f
            if (color == Color.TRANSPARENT) {
                fill.setColor(Color.TRANSPARENT)
                fill.setStroke(3, ContextCompat.getColor(ctx, R.color.tv_glass_border))
            } else {
                fill.setColor(color)
            }
            swatch.background = fill

            check.visibility = if (color == selectedColor) View.VISIBLE else View.GONE

            fun applyFocusRing(hasFocus: Boolean) {
                if (hasFocus) {
                    val ring = GradientDrawable()
                    ring.shape = GradientDrawable.RECTANGLE
                    ring.cornerRadius = 10f
                    ring.setColor(Color.TRANSPARENT)
                    ring.setStroke((3 * density).toInt(), ContextCompat.getColor(ctx, R.color.tv_accent))
                    itemView.foreground = ring
                } else {
                    itemView.foreground = null
                }
            }
            itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> applyFocusRing(hasFocus) }
            applyFocusRing(itemView.hasFocus())

            itemView.setOnClickListener { onSelected(color) }
        }
    }

    inner class CustomVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val swatch: View = itemView.findViewById(R.id.swatchColor)
        private val check:  View = itemView.findViewById(R.id.swatchCheck)

        fun bind() {
            val ctx     = swatch.context
            val density = ctx.resources.displayMetrics.density

            swatch.setBackgroundColor(ContextCompat.getColor(ctx, R.color.tv_glass_white_20))
            check.visibility = View.GONE

            if (itemView.tag != TAG_CUSTOM) {
                val tv = TextView(ctx)
                tv.text = "…"
                tv.textSize = 18f
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_primary))
                tv.gravity = android.view.Gravity.CENTER
                tv.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                (itemView as? ViewGroup)?.addView(tv)
                itemView.tag = TAG_CUSTOM
            }

            fun applyFocusRing(hasFocus: Boolean) {
                if (hasFocus) {
                    val ring = GradientDrawable()
                    ring.shape = GradientDrawable.RECTANGLE
                    ring.cornerRadius = 10f
                    ring.setColor(Color.TRANSPARENT)
                    ring.setStroke((3 * density).toInt(), ContextCompat.getColor(ctx, R.color.tv_accent))
                    itemView.foreground = ring
                } else {
                    itemView.foreground = null
                }
            }
            itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> applyFocusRing(hasFocus) }
            applyFocusRing(itemView.hasFocus())

            itemView.setOnClickListener { onCustomClicked() }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Singleton tile data holder (avoids intent bundle limits)
// ─────────────────────────────────────────────────────────────────────────────

object TvTileDataHolder {
    var tiles:        List<StorageItem> = emptyList()
    var sourceConfig: TileColorConfig   = TileColorConfig()
    var sourceTileId: String            = ""
    var isListView:   Boolean           = false
}
