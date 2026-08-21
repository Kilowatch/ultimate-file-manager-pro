package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.ui.HexColorHelper

class TileColorBottomSheet : BottomSheetDialogFragment() {

    private var tileId: String = ""
    private var tileName: String = ""
    private var currentConfig: TileColorConfig = TileColorConfig()

    private var onColorChanged: ((TileColorConfig) -> Unit)? = null
    private var onIconChanged:  ((TileIconConfig) -> Unit)?  = null
    private var onReset:         (() -> Unit)?                 = null
    private var onDone:          (() -> Unit)?                 = null
    private var onCopyTo:        ((TileColorConfig) -> Unit)?  = null

    private var selectedRingColor: Int    = Color.TRANSPARENT
    private var selectedIconColor: Int    = Color.TRANSPARENT
    private var selectedIconBgColor: Int  = Color.TRANSPARENT
    private var selectedTileBgColor: Int  = Color.TRANSPARENT
    private var selectedLabelColor: Int   = Color.TRANSPARENT
    private var tileIconRes: Int = R.drawable.ic_storage_internal
    private var originalIconRes: Int = R.drawable.ic_storage_internal
    private var selectedIconRes: Int = R.drawable.ic_storage_internal
    private var customIconPath: String? = null
    private var tileSubtitle: String? = null
    private var isListView: Boolean = false

    private var onBrowseIconClicked: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_tile_color, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tileId      = arguments?.getString(ARG_TILE_ID) ?: ""
        tileName    = arguments?.getString(ARG_TILE_NAME) ?: ""
        tileIconRes = arguments?.getInt(ARG_TILE_ICON, R.drawable.ic_storage_internal) ?: R.drawable.ic_storage_internal
        originalIconRes = tileIconRes
        selectedIconRes = tileIconRes
        customIconPath = arguments?.getString(ARG_CUSTOM_ICON_PATH)
        tileSubtitle = arguments?.getString(ARG_TILE_SUBTITLE)
        isListView  = arguments?.getBoolean(ARG_IS_LIST_VIEW, false) ?: false

        val ringColor   = arguments?.getInt(ARG_RING_CONFIG,        Color.TRANSPARENT) ?: Color.TRANSPARENT
        val iconColor   = arguments?.getInt(ARG_ICON_COLOR_CONFIG,  Color.TRANSPARENT) ?: Color.TRANSPARENT
        val iconBgColor = arguments?.getInt(ARG_ICON_BG_CONFIG,     Color.TRANSPARENT) ?: Color.TRANSPARENT
        val tileBgColor = arguments?.getInt(ARG_TILE_BG_CONFIG,     Color.TRANSPARENT) ?: Color.TRANSPARENT
        val labelColor  = arguments?.getInt(ARG_LABEL_CONFIG,       Color.TRANSPARENT) ?: Color.TRANSPARENT

        currentConfig = TileColorConfig(
            ringColor   = ringColor,
            iconColor   = iconColor,
            iconBgColor = iconBgColor,
            tileBgColor = tileBgColor,
            labelColor  = labelColor
        )

        selectedRingColor   = currentConfig.ringColor
        selectedIconColor   = currentConfig.iconColor
        selectedIconBgColor = currentConfig.iconBgColor
        selectedTileBgColor = currentConfig.tileBgColor
        selectedLabelColor  = currentConfig.labelColor

        // "Preview of Tile: {name}" — centred, from string resource (no hardcoding)
        view.findViewById<TextView>(R.id.txtTileName).text =
            getString(R.string.tile_color_preview_label, tileName)

        // Show grid or list preview depending on current view mode
        val gridContainer = view.findViewById<View>(R.id.previewGridContainer)
        val listCard      = view.findViewById<View>(R.id.previewListCard)
        if (isListView) {
            gridContainer.visibility = View.GONE
            listCard.visibility      = View.VISIBLE
            view.findViewById<TextView>(R.id.previewListLabel).text    = tileName
            view.findViewById<TextView>(R.id.previewListCapacity).text = tileSubtitle ?: ""
        } else {
            gridContainer.visibility = View.VISIBLE
            listCard.visibility      = View.GONE
            view.findViewById<TextView>(R.id.previewLabel).text    = tileName
            view.findViewById<TextView>(R.id.previewCapacity).text = tileSubtitle ?: ""
        }
        updatePreviewTile(view)

        // Setup expand/collapse handlers (body includes recycler + custom row)
        setupSectionExpandCollapse(view, R.id.iconColorSectionHeader, R.id.iconColorCollapsibleBody, R.id.iconColorExpandIcon)
        setupSectionExpandCollapse(view, R.id.tileBgSectionHeader,    R.id.tileBgCollapsibleBody,    R.id.tileBgExpandIcon)
        setupSectionExpandCollapse(view, R.id.ringSectionHeader,      R.id.ringCollapsibleBody,      R.id.ringExpandIcon)
        setupSectionExpandCollapse(view, R.id.iconBgSectionHeader,    R.id.iconBgCollapsibleBody,    R.id.iconBgExpandIcon)
        setupSectionExpandCollapse(view, R.id.labelSectionHeader,     R.id.labelCollapsibleBody,     R.id.labelExpandIcon)

        // ═══════════════════════════════════════════════════
        // Icon selection section
        // ═══════════════════════════════════════════════════
        setupSectionExpandCollapse(view, R.id.iconSectionHeader, R.id.iconSectionCollapsibleBody, R.id.iconSectionExpandIcon)

        // Update header preview
        updateIconHeaderPreview(view)

        // Built-in icon gallery
        view.findViewById<RecyclerView>(R.id.recyclerBuiltinIcons)?.let { rv ->
            rv.layoutManager = GridLayoutManager(context, 4)
            rv.adapter = BuiltinIconAdapter(BUILTIN_ICONS) { iconRes ->
                selectedIconRes = iconRes
                customIconPath = null
                updateIconHeaderPreview(view)
                updatePreviewTile(view)
            }
        }

        // Browse file button
        view.findViewById<View>(R.id.btnBrowseIcon)?.setOnClickListener {
            onBrowseIconClicked?.invoke()
        }

        // Reset icon button
        view.findViewById<View>(R.id.btnResetIcon)?.setOnClickListener {
            selectedIconRes = originalIconRes
            customIconPath = null
            updateIconHeaderPreview(view)
            updatePreviewTile(view)
        }

        // Icon tint recycler
        setupRecyclerView(view.findViewById(R.id.recyclerIconColorTint), COLOR_PRESETS.clone()) { color ->
            selectedIconColor = color
            clearCustomColor(view, R.id.recyclerIconColorTint)
            updateColorSelection(view, R.id.recyclerIconColorTint, color)
            updateHeaderDot(view, R.id.iconColorDot, color)
            updatePreviewTile(view)
        }

        // Tile background recycler
        setupRecyclerView(view.findViewById(R.id.recyclerTileBgColors), COLOR_PRESETS.clone()) { color ->
            selectedTileBgColor = color
            clearCustomColor(view, R.id.recyclerTileBgColors)
            updateColorSelection(view, R.id.recyclerTileBgColors, color)
            updateHeaderDot(view, R.id.tileBgColorDot, color)
            updatePreviewTile(view)
        }

        setupRecyclerView(view.findViewById(R.id.recyclerRingColors), COLOR_PRESETS.clone()) { color ->
            selectedRingColor = color
            clearCustomColor(view, R.id.recyclerRingColors)
            updateColorSelection(view, R.id.recyclerRingColors, color)
            updateHeaderDot(view, R.id.ringColorDot, color)
            updatePreviewTile(view)
        }

        setupRecyclerView(view.findViewById(R.id.recyclerIconColors), COLOR_PRESETS.clone()) { color ->
            selectedIconBgColor = color
            clearCustomColor(view, R.id.recyclerIconColors)
            updateColorSelection(view, R.id.recyclerIconColors, color)
            updateHeaderDot(view, R.id.iconBgColorDot, color)
            updatePreviewTile(view)
        }

        setupRecyclerView(view.findViewById(R.id.recyclerLabelColors), COLOR_PRESETS.clone()) { color ->
            selectedLabelColor = color
            clearCustomColor(view, R.id.recyclerLabelColors)
            updateColorSelection(view, R.id.recyclerLabelColors, color)
            updateHeaderDot(view, R.id.labelColorDot, color)
            updatePreviewTile(view)
        }

        view.findViewById<View>(R.id.btnDone).setOnClickListener {
            val config = TileColorConfig(
                ringColor   = selectedRingColor,
                iconColor   = selectedIconColor,
                iconBgColor = selectedIconBgColor,
                tileBgColor = selectedTileBgColor,
                labelColor  = selectedLabelColor
            )
            onColorChanged?.invoke(config)
            onIconChanged?.invoke(TileIconConfig(
                selectedIconRes = selectedIconRes,
                customIconPath  = customIconPath,
                originalIconRes = originalIconRes
            ))
            onDone?.invoke()
            dismiss()
        }

        view.findViewById<View>(R.id.btnReset).setOnClickListener {
            selectedRingColor   = Color.TRANSPARENT
            selectedIconColor   = Color.TRANSPARENT
            selectedIconBgColor = Color.TRANSPARENT
            selectedTileBgColor = Color.TRANSPARENT
            selectedLabelColor  = Color.TRANSPARENT
            selectedIconRes     = originalIconRes
            customIconPath      = null
            notifyColorChange(view)
            updatePreviewTile(view)
            updateIconHeaderPreview(view)
            updateHeaderDot(view, R.id.iconColorDot,   Color.TRANSPARENT)
            updateHeaderDot(view, R.id.tileBgColorDot, Color.TRANSPARENT)
            updateHeaderDot(view, R.id.ringColorDot,   Color.TRANSPARENT)
            updateHeaderDot(view, R.id.iconBgColorDot, Color.TRANSPARENT)
            updateHeaderDot(view, R.id.labelColorDot,  Color.TRANSPARENT)
        }

        // Export button
        view.findViewById<View>(R.id.btnExport).setOnClickListener {
            val config = TileColorConfig(
                ringColor   = selectedRingColor,
                iconColor   = selectedIconColor,
                iconBgColor = selectedIconBgColor,
                tileBgColor = selectedTileBgColor,
                labelColor  = selectedLabelColor
            )
            val code = TileColorCodec.encode(config)
            
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_tile_color_export, null)
            val tvCode = dialogView.findViewById<TextView>(R.id.tvExportCode)
            val btnCopy = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnExportCopy)
            
            tvCode.text = code
            
            // Set preview row dots and hexes
            setExportPreviewRow(dialogView, R.id.dotExportIcon, R.id.hexExportIcon, config.iconColor)
            setExportPreviewRow(dialogView, R.id.dotExportTileBg, R.id.hexExportTileBg, config.tileBgColor)
            setExportPreviewRow(dialogView, R.id.dotExportRing, R.id.hexExportRing, config.ringColor)
            setExportPreviewRow(dialogView, R.id.dotExportIconBg, R.id.hexExportIconBg, config.iconBgColor)
            setExportPreviewRow(dialogView, R.id.dotExportLabel, R.id.hexExportLabel, config.labelColor)

            val dialog = android.app.AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setPositiveButton(R.string.close, null)
                .create()
                
            btnCopy.setOnClickListener {
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText(getString(R.string.tile_color_code_clipboard_label), code)
                clipboard.setPrimaryClip(clip)
                btnCopy.text = getString(R.string.tile_color_export_copied)
                btnCopy.setIconResource(R.drawable.ic_check)
                android.widget.Toast.makeText(requireContext(), R.string.tile_color_code_copied_toast, android.widget.Toast.LENGTH_SHORT).show()
            }
            
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_surface)
            dialog.show()
        }

        // Copy to… button
        view.findViewById<View>(R.id.btnCopyTo).setOnClickListener {
            val config = TileColorConfig(
                ringColor   = selectedRingColor,
                iconColor   = selectedIconColor,
                iconBgColor = selectedIconBgColor,
                tileBgColor = selectedTileBgColor,
                labelColor  = selectedLabelColor
            )
            onCopyTo?.invoke(config)
        }

        // Initialise recycler selections from saved config
        updateColorSelection(view, R.id.recyclerIconColorTint, selectedIconColor)
        updateColorSelection(view, R.id.recyclerTileBgColors,  selectedTileBgColor)
        updateColorSelection(view, R.id.recyclerRingColors,    selectedRingColor)
        updateColorSelection(view, R.id.recyclerIconColors,    selectedIconBgColor)
        updateColorSelection(view, R.id.recyclerLabelColors,   selectedLabelColor)

        // Header dots
        updateHeaderDot(view, R.id.iconColorDot,   selectedIconColor)
        updateHeaderDot(view, R.id.tileBgColorDot, selectedTileBgColor)
        updateHeaderDot(view, R.id.ringColorDot,   selectedRingColor)
        updateHeaderDot(view, R.id.iconBgColorDot, selectedIconBgColor)
        updateHeaderDot(view, R.id.labelColorDot,  selectedLabelColor)

        // Custom colour preview chips for non-preset saved colours
        val isCustomIconTint = selectedIconColor   != Color.TRANSPARENT && COLOR_PRESETS.none { it == selectedIconColor }
        val isCustomTileBg   = selectedTileBgColor != Color.TRANSPARENT && COLOR_PRESETS.none { it == selectedTileBgColor }
        val isCustomRing     = selectedRingColor   != Color.TRANSPARENT && COLOR_PRESETS.none { it == selectedRingColor }
        val isCustomIcon     = selectedIconBgColor != Color.TRANSPARENT && COLOR_PRESETS.none { it == selectedIconBgColor }
        val isCustomLabel    = selectedLabelColor  != Color.TRANSPARENT && COLOR_PRESETS.none { it == selectedLabelColor }

        view.findViewById<View>(R.id.customIconColorPreview)?.visibility = if (isCustomIconTint) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.customTileBgPreview)?.visibility    = if (isCustomTileBg)  View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.customRingPreview)?.visibility      = if (isCustomRing)     View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.customIconBgPreview)?.visibility    = if (isCustomIcon)     View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.customLabelPreview)?.visibility     = if (isCustomLabel)    View.VISIBLE else View.GONE

        if (isCustomIconTint) view.findViewById<View>(R.id.customIconColorPreview)?.setBackgroundColor(selectedIconColor)
        if (isCustomTileBg)   view.findViewById<View>(R.id.customTileBgPreview)?.setBackgroundColor(selectedTileBgColor)
        if (isCustomRing)     view.findViewById<View>(R.id.customRingPreview)?.setBackgroundColor(selectedRingColor)
        if (isCustomIcon)     view.findViewById<View>(R.id.customIconBgPreview)?.setBackgroundColor(selectedIconBgColor)
        if (isCustomLabel)    view.findViewById<View>(R.id.customLabelPreview)?.setBackgroundColor(selectedLabelColor)

        // Custom colour button handlers
        view.findViewById<View>(R.id.btnCustomIconColor).setOnClickListener {
            showColorPickerDialog(selectedIconColor) { color ->
                selectedIconColor = color
                updateColorSelection(view, R.id.recyclerIconColorTint, color)
                showCustomColorIndicator(view, R.id.recyclerIconColorTint, color)
                updateHeaderDot(view, R.id.iconColorDot, color)
                updatePreviewTile(view)
            }
        }
        view.findViewById<View>(R.id.btnCustomTileBg).setOnClickListener {
            showColorPickerDialog(selectedTileBgColor) { color ->
                selectedTileBgColor = color
                updateColorSelection(view, R.id.recyclerTileBgColors, color)
                showCustomColorIndicator(view, R.id.recyclerTileBgColors, color)
                updateHeaderDot(view, R.id.tileBgColorDot, color)
                updatePreviewTile(view)
            }
        }
        view.findViewById<View>(R.id.btnCustomRing).setOnClickListener {
            showColorPickerDialog(selectedRingColor) { color ->
                selectedRingColor = color
                updateColorSelection(view, R.id.recyclerRingColors, color)
                showCustomColorIndicator(view, R.id.recyclerRingColors, color)
                updateHeaderDot(view, R.id.ringColorDot, color)
                updatePreviewTile(view)
            }
        }
        view.findViewById<View>(R.id.btnCustomIconBg).setOnClickListener {
            showColorPickerDialog(selectedIconBgColor) { color ->
                selectedIconBgColor = color
                updateColorSelection(view, R.id.recyclerIconColors, color)
                showCustomColorIndicator(view, R.id.recyclerIconColors, color)
                updateHeaderDot(view, R.id.iconBgColorDot, color)
                updatePreviewTile(view)
            }
        }
        view.findViewById<View>(R.id.btnCustomLabel).setOnClickListener {
            showColorPickerDialog(selectedLabelColor) { color ->
                selectedLabelColor = color
                updateColorSelection(view, R.id.recyclerLabelColors, color)
                showCustomColorIndicator(view, R.id.recyclerLabelColors, color)
                updateHeaderDot(view, R.id.labelColorDot, color)
                updatePreviewTile(view)
            }
        }
    }




    private fun showCustomColorIndicator(view: View, recyclerId: Int, color: Int) {
        val recycler = view.findViewById<RecyclerView>(recyclerId)
        (recycler.adapter as? ColorSwatchAdapter)?.setCustomColor(color)
        val previewId = when (recyclerId) {
            R.id.recyclerIconColorTint -> R.id.customIconColorPreview
            R.id.recyclerTileBgColors  -> R.id.customTileBgPreview
            R.id.recyclerRingColors    -> R.id.customRingPreview
            R.id.recyclerIconColors    -> R.id.customIconBgPreview
            R.id.recyclerLabelColors   -> R.id.customLabelPreview
            else -> return
        }
        view.findViewById<View>(previewId)?.apply {
            visibility = View.VISIBLE
            setBackgroundColor(color)
        }
    }

    private fun clearCustomColor(view: View, recyclerId: Int) {
        val recycler = view.findViewById<RecyclerView>(recyclerId)
        (recycler.adapter as? ColorSwatchAdapter)?.clearCustomColor()
        val previewId = when (recyclerId) {
            R.id.recyclerIconColorTint -> R.id.customIconColorPreview
            R.id.recyclerTileBgColors  -> R.id.customTileBgPreview
            R.id.recyclerRingColors    -> R.id.customRingPreview
            R.id.recyclerIconColors    -> R.id.customIconBgPreview
            R.id.recyclerLabelColors   -> R.id.customLabelPreview
            else -> return
        }
        view.findViewById<View>(previewId)?.visibility = View.GONE
    }

    private fun updatePreviewTile(view: View) {
        val context = view.context
        val density = context.resources.displayMetrics.density

        // ── Grid preview ──────────────────────────────────────────────────────
        val previewTile = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.previewTile)
        if (previewTile != null) {
            // Tile background
            if (selectedTileBgColor != Color.TRANSPARENT) {
                previewTile.setCardBackgroundColor(selectedTileBgColor)
            } else {
                previewTile.setCardBackgroundColor(ContextCompat.getColor(context, R.color.mobile_glass_card))
            }
            if (selectedRingColor != Color.TRANSPARENT) {
                previewTile.strokeWidth = (2 * density).toInt()
                previewTile.setStrokeColor(selectedRingColor)
            } else {
                previewTile.strokeWidth = (1 * density).toInt()
                previewTile.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
            }
            val iconContainer = view.findViewById<android.widget.FrameLayout>(R.id.previewIconContainer)
            val previewIcon   = view.findViewById<android.widget.ImageView>(R.id.previewIcon)
            applyIconToImageView(previewIcon)
            if (selectedIconColor != Color.TRANSPARENT) previewIcon?.setColorFilter(selectedIconColor)
            else previewIcon?.clearColorFilter()
            if (selectedIconBgColor != Color.TRANSPARENT) {
                val d = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(selectedIconBgColor)
                    setStroke((1 * density).toInt(), ContextCompat.getColor(context, R.color.mobile_glass_stroke))
                }
                iconContainer?.background = d
            } else {
                iconContainer?.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
            }
            val previewLabel    = view.findViewById<android.widget.TextView>(R.id.previewLabel)
            val previewCapacity = view.findViewById<android.widget.TextView>(R.id.previewCapacity)
            if (selectedLabelColor != Color.TRANSPARENT) {
                previewLabel?.setTextColor(selectedLabelColor)
                previewCapacity?.setTextColor(selectedLabelColor)
            } else {
                previewLabel?.setTextColor(ContextCompat.getColor(context, R.color.mobile_card_text_primary))
                previewCapacity?.setTextColor(ContextCompat.getColor(context, R.color.mobile_text_secondary))
            }
        }

        // ── List preview ──────────────────────────────────────────────────────
        val listCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.previewListCard)
        if (listCard != null && listCard.visibility == View.VISIBLE) {
            // Tile background
            if (selectedTileBgColor != Color.TRANSPARENT) {
                listCard.setCardBackgroundColor(selectedTileBgColor)
            } else {
                listCard.setCardBackgroundColor(ContextCompat.getColor(context, R.color.mobile_glass_card))
            }
            if (selectedRingColor != Color.TRANSPARENT) {
                listCard.strokeWidth = (2 * density).toInt()
                listCard.setStrokeColor(selectedRingColor)
            } else {
                listCard.strokeWidth = (1 * density).toInt()
                listCard.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
            }
            val listIconContainer = view.findViewById<android.widget.FrameLayout>(R.id.previewListIconContainer)
            val listIcon          = view.findViewById<android.widget.ImageView>(R.id.previewListIcon)
            applyIconToImageView(listIcon)
            if (selectedIconColor != Color.TRANSPARENT) listIcon?.setColorFilter(selectedIconColor)
            else listIcon?.clearColorFilter()
            if (selectedIconBgColor != Color.TRANSPARENT) {
                val d = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(selectedIconBgColor)
                    setStroke((1 * density).toInt(), ContextCompat.getColor(context, R.color.mobile_glass_stroke))
                }
                listIconContainer?.background = d
            } else {
                listIconContainer?.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
            }
            val listLabel    = view.findViewById<android.widget.TextView>(R.id.previewListLabel)
            val listCapacity = view.findViewById<android.widget.TextView>(R.id.previewListCapacity)
            if (selectedLabelColor != Color.TRANSPARENT) {
                listLabel?.setTextColor(selectedLabelColor)
                listCapacity?.setTextColor(selectedLabelColor)
            } else {
                listLabel?.setTextColor(ContextCompat.getColor(context, R.color.mobile_card_text_primary))
                listCapacity?.setTextColor(ContextCompat.getColor(context, R.color.mobile_card_text_secondary))
            }
        }
    }

    // ── Icon helpers ───────────────────────────────────────────────────────────

    private fun applyIconToImageView(icon: ImageView?) {
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

    private fun updateIconHeaderPreview(view: View) {
        val headerIcon = view.findViewById<ImageView>(R.id.currentIconPreview) ?: return
        applyIconToImageView(headerIcon)
    }

    /** Called by [StorageBrowserActivity] when the user picks a file from the internal browser. */
    fun onIconPicked(privatePath: String) {
        customIconPath = privatePath
        selectedIconRes = originalIconRes  // file overrides built-in selection
        view?.let { v ->
            updateIconHeaderPreview(v)
            updatePreviewTile(v)
        }
    }

    fun setOnBrowseIconClickedListener(listener: () -> Unit): TileColorBottomSheet {
        onBrowseIconClicked = listener
        return this
    }

    fun setOnIconChangedListener(listener: (TileIconConfig) -> Unit): TileColorBottomSheet {
        onIconChanged = listener
        return this
    }

    // ── Built-in icon gallery adapter ──────────────────────────────────────

    private inner class BuiltinIconAdapter(
        private val icons: IntArray,
        private val onIconSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<BuiltinIconAdapter.VH>() {

        private var selectedIcon: Int = selectedIconRes

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(itemView.tag as Int)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val size = (density * 48).toInt()
            val pad = (density * 4).toInt()
            val container = FrameLayout(ctx)
            container.layoutParams = ViewGroup.LayoutParams(size, size)
            container.setPadding(pad, pad, pad, pad)
            val ivId = View.generateViewId()
            val iv = ImageView(ctx)
            iv.id = ivId
            iv.layoutParams = ViewGroup.LayoutParams(size - pad * 2, size - pad * 2)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            container.addView(iv)
            container.tag = ivId
            return VH(container)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val iconRes = icons[position]
            val isSelected = iconRes == selectedIcon
            holder.icon.setImageResource(iconRes)
            holder.icon.setPadding(12, 12, 12, 12)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isSelected) {
                    setColor(0x33000000.toInt())
                    setStroke(3, 0xFFFF0000.toInt())
                } else {
                    setColor(0x0FFFFFFF.toInt())
                    setStroke(1, 0x44000000.toInt())
                }
            }
            holder.icon.background = bg
            holder.itemView.setOnClickListener {
                selectedIcon = iconRes
                notifyDataSetChanged()
                onIconSelected(iconRes)
            }
        }

        override fun getItemCount() = icons.size
    }

    // ── Color picker dialog ────────────────────────────────────────────────────

private fun showColorPickerDialog(initialColor: Int, onColorSelected: (Int) -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)

        val palette      = dialogView.findViewById<za.kilowatch.ultimatefilemanager.ui.HsvPaletteView>(R.id.hsvPalette)
        val hueSlider    = dialogView.findViewById<za.kilowatch.ultimatefilemanager.ui.HueSliderView>(R.id.hueSlider)
        val colorPreview = dialogView.findViewById<View>(R.id.colorPreview)
        val hexInput     = dialogView.findViewById<EditText>(R.id.hexInput)

        // ── Hex-only InputFilter ────────────────────────────────────────────
        hexInput.filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
            source.filter { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
        })

        // ── Re-entrancy guard ───────────────────────────────────────────────
        var isUpdatingHex = false

        // ── Helper: push colour from visual controls into hex field ─────────
        fun syncHexFromColor(color: Int) {
            if (isUpdatingHex) return
            isUpdatingHex = true
            hexInput.setText(HexColorHelper.formatHex(color).removePrefix("#"))
            hexInput.setSelection(hexInput.text.length)
            isUpdatingHex = false
        }

        // ── Hex TextWatcher (hex → visual controls) ─────────────────────────
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingHex) return
                val hex = s?.toString() ?: ""
                HexColorHelper.parseHex(hex)?.let { color ->
                    palette.setColor(color)
                    hueSlider.currentHue = palette.currentHue
                    colorPreview.setBackgroundColor(color)
                }
            }
        })

        // Set initial colour
        palette.setColor(initialColor)
        hueSlider.currentHue = palette.currentHue
        colorPreview.setBackgroundColor(initialColor)

        // Prefill hex field from saved colour
        if (initialColor != android.graphics.Color.TRANSPARENT) {
            hexInput.setText(HexColorHelper.formatHex(initialColor).removePrefix("#"))
            hexInput.setSelection(hexInput.text.length)
        }

        // Sync: palette → preview + hex
        palette.onColorChanged = { color ->
            hueSlider.currentHue = palette.currentHue
            colorPreview.setBackgroundColor(color)
            syncHexFromColor(color)
        }

        // Sync: hue slider → palette → preview + hex
        hueSlider.onHueChanged = { hue ->
            palette.setHue(hue)
            colorPreview.setBackgroundColor(palette.selectedColor)
            syncHexFromColor(palette.selectedColor)
        }

        // Preset swatches
        val presets = mapOf(
            R.id.swatchBlack  to 0xFF000000.toInt(),
            R.id.swatchWhite  to 0xFFFFFFFF.toInt(),
            R.id.swatchRed    to 0xFFE53935.toInt(),
            R.id.swatchOrange to 0xFFFB8C00.toInt(),
            R.id.swatchYellow to 0xFFFDD835.toInt(),
            R.id.swatchGreen  to 0xFF43A047.toInt(),
            R.id.swatchBlue   to 0xFF1E88E5.toInt(),
            R.id.swatchPurple to 0xFF8E24AA.toInt()
        )
        presets.forEach { (viewId, color) ->
            dialogView.findViewById<View>(viewId)?.apply {
                setBackgroundColor(color)
                setOnClickListener {
                    palette.setColor(color)
                    hueSlider.currentHue = palette.currentHue
                    colorPreview.setBackgroundColor(color)
                    syncHexFromColor(color)
                }
            }
        }

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btnSelect).setOnClickListener {
            // Priority: valid hex → use hex; otherwise → use visual selection
            val hexText = hexInput.text?.toString() ?: ""
            val color = HexColorHelper.parseHex(hexText) ?: palette.selectedColor
            onColorSelected(color)
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_surface)
        dialog.show()
    }

    /**
     * Wires tap-to-expand/collapse for a section card.
     * [bodyId] is the container LinearLayout that wraps BOTH the RecyclerView
     * and the Custom-button row, so both collapse together.
     */
    private fun setupSectionExpandCollapse(view: View, headerId: Int, bodyId: Int, iconId: Int) {
        val header     = view.findViewById<View>(headerId)
        val body       = view.findViewById<View>(bodyId)
        val expandIcon = view.findViewById<android.widget.ImageView>(iconId)

        var isExpanded = true

        header?.setOnClickListener {
            isExpanded = !isExpanded
            body?.visibility = if (isExpanded) View.VISIBLE else View.GONE
            expandIcon?.animate()?.rotation(if (isExpanded) 0f else 180f)?.setDuration(200)?.start()
        }
    }

    /** Updates the small colour-dot in a section header to reflect the chosen colour. */
    private fun updateHeaderDot(view: View, dotId: Int, color: Int) {
        val dot = view.findViewById<View>(dotId) ?: return
        if (color == android.graphics.Color.TRANSPARENT) {
            // Show a translucent outline so the dot is visible but signals "none"
            val d = android.graphics.drawable.GradientDrawable()
            d.shape = android.graphics.drawable.GradientDrawable.OVAL
            d.setColor(android.graphics.Color.TRANSPARENT)
            d.setStroke(2, ContextCompat.getColor(dot.context, R.color.mobile_glass_stroke))
            dot.background = d
        } else {
            val d = android.graphics.drawable.GradientDrawable()
            d.shape = android.graphics.drawable.GradientDrawable.OVAL
            d.setColor(color)
            dot.background = d
        }
    }

    private fun notifyColorChange(view: View) {
        updateColorSelection(view, R.id.recyclerIconColorTint, selectedIconColor)
        updateColorSelection(view, R.id.recyclerTileBgColors,  selectedTileBgColor)
        updateColorSelection(view, R.id.recyclerRingColors,    selectedRingColor)
        updateColorSelection(view, R.id.recyclerIconColors,    selectedIconBgColor)
        updateColorSelection(view, R.id.recyclerLabelColors,   selectedLabelColor)
    }

    private fun setupRecyclerView(recyclerView: RecyclerView, colors: IntArray, onColorSelected: (Int) -> Unit) {
        recyclerView.layoutManager = GridLayoutManager(context, 6)
        recyclerView.adapter = ColorSwatchAdapter(colors) { color ->
            onColorSelected(color)
        }
    }

    private fun updateColorSelection(view: View, recyclerId: Int, selectedColor: Int) {
        val recycler = view.findViewById<RecyclerView>(recyclerId)
        (recycler.adapter as? ColorSwatchAdapter)?.setSelectedColor(selectedColor)
    }

    fun setTileInfo(id: String, name: String): TileColorBottomSheet {
        arguments = Bundle().apply {
            putString(ARG_TILE_ID, id)
            putString(ARG_TILE_NAME, name)
        }
        return this
    }

    fun setCurrentConfig(config: TileColorConfig): TileColorBottomSheet {
        arguments = Bundle().apply {
            putInt(ARG_RING_CONFIG, config.ringColor)
            putInt(ARG_ICON_BG_CONFIG, config.iconBgColor)
            putInt(ARG_LABEL_CONFIG, config.labelColor)
        }
        return this
    }

    fun setOnColorChangedListener(onChanged: (TileColorConfig) -> Unit): TileColorBottomSheet {
        onColorChanged = onChanged
        return this
    }

    fun setOnDoneListener(onDone: () -> Unit): TileColorBottomSheet {
        this.onDone = onDone
        return this
    }

    fun setOnResetListener(onReset: () -> Unit): TileColorBottomSheet {
        this.onReset = onReset
        return this
    }
    private fun setExportPreviewRow(view: View, dotId: Int, hexId: Int, color: Int) {
        val dot = view.findViewById<View>(dotId)
        val hexText = view.findViewById<TextView>(hexId)

        if (color == Color.TRANSPARENT) {
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(Color.TRANSPARENT)
            d.setStroke(2, view.context.getColor(R.color.mobile_glass_stroke))
            dot.background = d
            hexText.text = getString(R.string.tile_color_export_none)
        } else {
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(color)
            dot.background = d
            hexText.text = String.format("#%08X", color)
        }
    }

    fun setOnCopyToListener(listener: (TileColorConfig) -> Unit): TileColorBottomSheet {
        onCopyTo = listener
        return this
    }

    companion object {
        const val TAG = "TileColorBottomSheet"
        private const val ARG_TILE_ID           = "tile_id"
        private const val ARG_TILE_NAME         = "tile_name"
        private const val ARG_TILE_ICON         = "tile_icon"
        private const val ARG_TILE_SUBTITLE     = "tile_subtitle"
        private const val ARG_IS_LIST_VIEW      = "is_list_view"
        private const val ARG_RING_CONFIG       = "ring_config"
        private const val ARG_ICON_COLOR_CONFIG = "icon_color_config"
        private const val ARG_ICON_BG_CONFIG    = "icon_bg_config"
        private const val ARG_TILE_BG_CONFIG    = "tile_bg_config"
        private const val ARG_LABEL_CONFIG      = "label_config"
        private const val ARG_CUSTOM_ICON_PATH  = "custom_icon_path"
        // Legacy keys kept for backward-compat (unused in new code)
        private const val ARG_RING_COLOR        = "ring_color"
        private const val ARG_ICON_BG_COLOR     = "icon_bg_color"
        private const val ARG_LABEL_COLOR       = "label_color"

        val BUILTIN_ICONS = za.kilowatch.ultimatefilemanager.settings.ALL_BUILTIN_ICONS


























        private val COLOR_PRESETS = intArrayOf(
            Color.TRANSPARENT,
            -0xF4436F,  // Red
            -0xE91E63,  // Pink
            -0x9C27B0,  // Purple
            -0x2196F3,  // Blue
            -0xFBCD,    // Cyan
            -0x4CAF50,  // Green
            -0xF9800,   // Orange
            -0x167,     // Teal
            -0xC107,    // Amber
            -0x9E8D8B,  // Grey
            -0x1        // White
        )

        fun newInstance(
            tileId: String,
            tileName: String,
            tileIconRes: Int,
            tileSubtitle: String?,
            config: TileColorConfig,
            isListView: Boolean = false,
            customIconPath: String? = null
        ): TileColorBottomSheet {
            return TileColorBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TILE_ID,       tileId)
                    putString(ARG_TILE_NAME,     tileName)
                    putInt(ARG_TILE_ICON,        tileIconRes)
                    putString(ARG_TILE_SUBTITLE, tileSubtitle)
                    putBoolean(ARG_IS_LIST_VIEW, isListView)
                    putInt(ARG_RING_CONFIG,       config.ringColor)
                    putInt(ARG_ICON_COLOR_CONFIG, config.iconColor)
                    putInt(ARG_ICON_BG_CONFIG,    config.iconBgColor)
                    putInt(ARG_TILE_BG_CONFIG,    config.tileBgColor)
                    putInt(ARG_LABEL_CONFIG,      config.labelColor)
                    putString(ARG_CUSTOM_ICON_PATH, customIconPath)
                }
            }
        }
    }
}

class ColorSwatchAdapter(
    private val colors: IntArray,
    private val onColorSelected: (Int) -> Unit
) : RecyclerView.Adapter<ColorSwatchAdapter.ViewHolder>() {

    private var selectedColor: Int = Color.TRANSPARENT
    private var customColor: Int? = null

    fun setSelectedColor(color: Int) {
        customColor = null  // Clear custom when preset selected
        val oldPosition = colors.indexOfFirst { it == selectedColor }
        val newPosition = colors.indexOfFirst { it == color }
        selectedColor = color
        if (oldPosition >= 0) notifyItemChanged(oldPosition)
        if (newPosition >= 0) notifyItemChanged(newPosition)
    }

    fun setCustomColor(color: Int) {
        customColor = color
        selectedColor = color  // Set as selected so it shows checkmark
        notifyDataSetChanged()
    }

    fun clearCustomColor() {
        customColor = null
        // Don't change selectedColor, keep current selection visible
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_swatch, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val color = colors[position]
        val isSelected = if (customColor != null) {
            color == customColor
        } else {
            color == selectedColor
        }
        holder.bind(color, isSelected)
    }

    override fun getItemCount(): Int = colors.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorView: View = itemView.findViewById(R.id.colorView)
        private val checkMark: android.widget.ImageView = itemView.findViewById(R.id.checkMark)

        fun bind(color: Int, isSelected: Boolean) {
            val drawable = colorView.background as? GradientDrawable
                ?: GradientDrawable().also { colorView.background = it }

            drawable.shape = GradientDrawable.OVAL

            if (color == Color.TRANSPARENT) {
                drawable.setColor(Color.TRANSPARENT)
                drawable.setStroke(2, itemView.context.getColor(R.color.mobile_glass_stroke))
            } else {
                drawable.setColor(color)
                if (color == 0xFFFFFFFF.toInt()) {
                    drawable.setStroke(2, itemView.context.getColor(R.color.mobile_glass_stroke))
                } else {
                    drawable.setStroke(0, Color.TRANSPARENT)
                }
            }

            checkMark.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
            if (isSelected) {
                // Tint checkmark based on color brightness
                val brightness = calculateBrightness(color)
                checkMark.setColorFilter(if (brightness > 0.5f) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }

            itemView.setOnClickListener {
                onColorSelected(color)
            }
        }

        private fun calculateBrightness(color: Int): Float {
            val r = android.graphics.Color.red(color) / 255f
            val g = android.graphics.Color.green(color) / 255f
            val b = android.graphics.Color.blue(color) / 255f
            return (r * 0.299f + g * 0.587f + b * 0.114f)
        }
    }
}