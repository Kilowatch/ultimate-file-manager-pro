package za.kilowatch.ultimatefilemanager.storage

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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LongPressDurationManager
import za.kilowatch.ultimatefilemanager.ui.CircularProgressView
import za.kilowatch.ultimatefilemanager.network.ShareType

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
 */
class StorageAdapter(
    private val isTv: Boolean,
    private val onStorageClick: (StorageItem) -> Unit,
    private val onLongPress: ((StorageItem, RecyclerView.ViewHolder) -> Unit)? = null,
    var onHideClick: ((StorageItem) -> Unit)? = null,
    var onEditModeClick: ((StorageItem) -> Unit)? = null
) : RecyclerView.Adapter<StorageAdapter.StorageViewHolder>() {

    private val items = mutableListOf<StorageItem>()
    private var tileColors: Map<String, TileColorConfig> = emptyMap()
    private var tileIcons: Map<String, String> = emptyMap()
    private var tileIconRes: Map<String, Int> = emptyMap()

    fun setTileColors(colors: Map<String, TileColorConfig>) {
        tileColors = colors
        notifyDataSetChanged()
    }

    fun getTileColor(tileId: String): TileColorConfig {
        return tileColors[tileId] ?: TileColorConfig()
    }

    fun setTileIcons(icons: Map<String, String>) {
        tileIcons = icons
        notifyDataSetChanged()
    }

    fun getTileIcon(tileId: String): String? {
        return tileIcons[tileId]?.takeIf { it.isNotEmpty() }
    }

    fun setTileIconRes(res: Map<String, Int>) {
        tileIconRes = res
        notifyDataSetChanged()
    }

    /** When true, tiles pulse and show a hide (X) button if they are hideable. */
    var isEditMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /**
     * Set to the ID of the tile currently in TV reorder mode.
     * While set, that tile renders with an enlarged scale and a highlighted stroke.
     * Call [notifyDataSetChanged] after mutating this.
     */
    var reorderModeId: String? = null

    fun submitList(newItems: List<StorageItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun addItem(item: StorageItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun removeById(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    /**
     * Swap item at [from] with item at [to] and animate the move.
     * Used by [androidx.recyclerview.widget.ItemTouchHelper] during live drag on mobile.
     */
    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun getItems(): List<StorageItem> = items.toList()

    companion object {
        const val VIEW_TYPE_STORAGE = 0
        const val VIEW_TYPE_GRID = 1
    }

    var viewMode = MainMenuViewModeManager.ViewMode.LIST
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var itemSize = MainMenuViewModeManager.ItemSize.MEDIUM
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var gridColumnCount = 3
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /**
     * When > 0, the adapter forces the inner ConstraintLayout of each TV grid tile to
     * exactly this height (px). Set by [StorageBrowserActivity] after layout so that
     * exactly 2 complete rows fill the RecyclerView Ã¢â‚¬â€ no partial rows.
     */
    var gridItemHeightPx: Int = -1
        set(value) {
            field = value
            notifyDataSetChanged()
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
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int {
        return if (viewMode == MainMenuViewModeManager.ViewMode.GRID) VIEW_TYPE_GRID else VIEW_TYPE_STORAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StorageViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_GRID) {
            if (isTv) R.layout.item_storage_grid_tv else R.layout.item_storage_grid
        } else {
            if (isTv) R.layout.item_storage_card_tv else R.layout.item_storage_card
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return StorageViewHolder(view)
    }

    override fun onBindViewHolder(holder: StorageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class StorageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView as MaterialCardView
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgStorageIcon)
        private val txtLabel: TextView = itemView.findViewById(R.id.txtStorageLabel)
        private val txtCapacity: TextView = itemView.findViewById(R.id.txtStorageCapacity)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressStorage)
        private val txtNewBadge: TextView? = itemView.findViewById(R.id.txtNewBadge)
        private val circularProgress: CircularProgressView? = itemView.findViewById(R.id.circularProgress)
        private val btnHideTile: View = itemView.findViewById(R.id.btnHideTile)

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
                    } else {
                        applyUnfocusedStroke()
                    }
                    card.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                        card.animate().setDuration(150).start()
                        if (hasFocus) {
                            card.strokeWidth = strokePx
                            card.setStrokeColor(accentColor)
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
                    card.animate()
                        .scaleX(1.02f)
                        .scaleY(1.02f)
                        .setDuration(400)
                        .setInterpolator(android.view.animation.CycleInterpolator(1f))
                        .withEndAction {
                            if (isEditMode) {
                                card.animate()
                                    .scaleX(1.02f)
                                    .scaleY(1.02f)
                                    .setDuration(400)
                                    .setInterpolator(android.view.animation.CycleInterpolator(1f))
                                    .start()
                            }
                        }
                        .start()
                } else {
                    // TV specific: just ensure we are at base scale
                    card.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }
            } else {
                btnHideTile.visibility = View.GONE
                card.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }

            // Ã¢â€â‚¬Ã¢â€â‚¬ Mobile: configurable touch-hold Ã¢â€ â€™ start drag Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
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
            if (viewMode == MainMenuViewModeManager.ViewMode.LIST) {
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
                if (gridColumnCount == 4) {
                    innerLayout?.setPadding((8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt())

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
                        lp.width = containerPx
                        lp.height = containerPx
                        pb.layoutParams = lp
                    }
                } else {
                    // Default 3 columns (large readable standard)
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

                    progressBar.layoutParams?.width  = containerPx
                    progressBar.layoutParams?.height = containerPx
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
                    item.isExtractsTile || item.isTipJarTile || item.isSyncTile || item.isSettingsTile || item.isFavoriteTile || item.isTwinWindowTile  || item.isTerminalTile || item.isShizukuTile || item.isOnlineStoragesTile || item.isOnlineStorage || item.isFileServerTile || item.isAboutTile || item.isRecycleBinTile || item.isNotepadTile || item.isScannerTile || item.isSmartSortTile || item.isCustomTile

            if (isSpecialTile) {
                circularProgress?.visibility = View.GONE
                progressBar.visibility   = View.GONE
                txtNewBadge?.visibility  = View.GONE

                imgIcon.setImageResource(item.iconRes)
                applyCustomIcon(item, imgIcon)
                txtLabel.text = item.label
                txtCapacity.text = when {
                    item.isTwinWindowTile -> context.getString(R.string.twin_window_subtitle)
                    item.isNotepadTile    -> context.getString(R.string.notepad_tile_subtitle)
                    item.isScannerTile    -> context.getString(R.string.scanner_tile_subtitle)
                    item.isTerminalTile -> context.getString(R.string.adb_terminal_subtitle)
                    item.isPairedDevicesTile -> context.getString(R.string.manage_links_with_other_devices)
                    item.isSettingsTile   -> context.getString(R.string.font_size_tile_subtitle)
                    item.isAppsTile      -> context.getString(R.string.apps_tile_subtitle)
                    item.isRemoteTile    -> context.getString(R.string.remote_tile_subtitle)
                    item.isTvRemoteTile  -> item.subtitle ?: ""
                    item.isSearchTile    -> context.getString(R.string.search_tile_subtitle)
                    item.isAnalyzerTile  -> context.getString(R.string.analyzer_tile_subtitle)

                    item.isVaultTile     -> context.getString(R.string.vault_tile_subtitle)
                    item.isLegalTile     -> context.getString(R.string.policy_selection_subtitle)
                    item.isRateUsTile    -> context.getString(R.string.rate_us_subtitle)
                    item.isAboutTile     -> context.getString(R.string.about_tile_subtitle)
                    item.isSafTile       -> context.getString(R.string.saf_tile_subtitle)
                    item.isNetworkTile   -> context.getString(R.string.network_tile_subtitle)
                    item.isOnlineStoragesTile -> za.kilowatch.ultimatefilemanager.util.DeviceUtils.getOnlineStoragesSubtitle(context)
                    item.isTipJarTile    -> context.getString(R.string.tip_jar_subtitle)
                    item.isSyncTile      -> context.getString(R.string.sync_subtitle)
                    item.isExtractsTile  -> item.subtitle ?: context.getString(R.string.browse_extracted_apps)
                    item.isFavoriteTile  -> if (item.favoriteIsFolder) context.getString(R.string.favorite_folder) else context.getString(R.string.favorite_file)
                    item.isRecycleBinTile -> item.subtitle ?: context.getString(R.string.recycle_bin_title)
                    item.isCustomTile -> item.subtitle ?: ""
                    item.isNetworkRoot   -> {
                        item.subtitle ?: run {
                            val share = item.networkShare
                            if (share != null) {
                                val typeLabel = when (share.type) {
                                    ShareType.AWS_S3    -> context.getString(R.string.add_online_storage_aws_s3)
                                    ShareType.IDRIVE_E2 -> context.getString(R.string.add_online_storage_idrive_e2)
                                    ShareType.NFS       -> share.type.name
                                    ShareType.DLNA      -> share.type.name
                                    else               -> share.type.name
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
                val showProgress = viewMode == MainMenuViewModeManager.ViewMode.LIST
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

                imgIcon.setImageResource(item.iconRes)
                applyCustomIcon(item, imgIcon)
                txtLabel.text = item.label

                val freeFormatted  = Formatter.formatFileSize(context, item.freeBytes)
                val totalFormatted = Formatter.formatFileSize(context, item.totalBytes)
                txtCapacity.text   = context.getString(R.string.storage_free_format, freeFormatted, totalFormatted)

                if (showProgress) {
                    progressBar.max      = 100
                    progressBar.progress = item.usagePercent

                    circularProgress?.let { chart ->
                        chart.percentLabel = "used"
                        chart.progress     = item.usagePercent
                        val progressColor  = when {
                            item.usagePercent >= 90 -> ContextCompat.getColor(context, R.color.ufm_progress_critical)
                            item.usagePercent >= 75 -> ContextCompat.getColor(context, R.color.ufm_progress_warning)
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
            applyCustomColors(context, card, iconContainer, imgIcon, txtLabel, txtCapacity, colorConfig)

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
                // Foreground selector handles border Ã¢â‚¬â€ do NOT set card.strokeWidth here
            } else {
                iconContainer?.setBackgroundResource(iconCircleRes)
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
         * Resets all tile-accent overrides so recycled ViewHolders donÃ¢â‚¬â„¢t bleed into regular tiles.
         */
        private fun resetTileAccent(
            context: android.content.Context,
            card: MaterialCardView,
            iconContainer: FrameLayout?,
            icon: ImageView,
            label: TextView
        ) {
            if (isTv) {
                icon.setColorFilter(ContextCompat.getColor(context, android.R.color.white))
                // Card background is the glass layer Ã¢â‚¬â€ no inner layout background to reset
                card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.tv_glass_white_10))
                card.strokeWidth = 0
                label.setTextColor(ContextCompat.getColor(context, R.color.tv_text_primary))
                iconContainer?.setBackgroundResource(R.drawable.bg_glass_card)
            } else {
                icon.clearColorFilter()
                iconContainer?.setBackgroundResource(R.drawable.bg_icon_circle_accent)
                label.setTextColor(ContextCompat.getColor(context, R.color.mobile_card_text_primary))
                card.strokeWidth = 0
                card.setStrokeColor(Color.TRANSPARENT)
            }
        }

        private fun applyCustomColors(
            context: android.content.Context,
            card: MaterialCardView,
            iconContainer: FrameLayout?,
            icon: ImageView?,
            label: TextView,
            capacity: TextView,
            config: TileColorConfig
        ) {
            // Tile background color
            if (config.tileBgColor != Color.TRANSPARENT) {
                card.setCardBackgroundColor(config.tileBgColor)
            } else {
                card.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.mobile_glass_card)
                )
            }

            // Ring color
            if (config.ringColor != Color.TRANSPARENT) {
                val strokePx = (2 * context.resources.displayMetrics.density).toInt()
                card.strokeWidth = strokePx
                card.setStrokeColor(config.ringColor)
            } else {
                card.strokeWidth = 0
                card.setStrokeColor(Color.TRANSPARENT)
            }

            // Icon tint color
            if (config.iconColor != Color.TRANSPARENT) {
                icon?.setColorFilter(config.iconColor)
            } else {
                icon?.clearColorFilter()
            }

            // Icon background color
            if (config.iconBgColor != Color.TRANSPARENT) {
                val newDrawable = GradientDrawable()
                newDrawable.shape = GradientDrawable.OVAL
                newDrawable.setColor(config.iconBgColor)
                iconContainer?.background = newDrawable
            } else {
                // Reset to default drawable
                iconContainer?.setBackgroundResource(R.drawable.bg_icon_circle_accent)
            }

            // Label text color
            if (config.labelColor != Color.TRANSPARENT) {
                label.setTextColor(config.labelColor)
                capacity.setTextColor(config.labelColor)
            } else {
                // Reset to default colors
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
                icon.setImageResource(savedRes)
            }
        }
    }
}
