package za.kilowatch.ultimatefilemanager.storage

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import java.io.File

/**
 * Adapter for the Edge Swipe Menu drawer list.
 */
class EdgeMenuAdapter(
    private val onTileClick: (StorageItem) -> Unit
) : RecyclerView.Adapter<EdgeMenuAdapter.EdgeTileViewHolder>() {

    private val items = mutableListOf<StorageItem>()
    private var tileColors: Map<String, TileColorConfig> = emptyMap()
    private var tileIcons: Map<String, String> = emptyMap()
    private var tileIconRes: Map<String, Int> = emptyMap()

    fun submitItems(newItems: List<StorageItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateTileDecorations(
        colors: Map<String, TileColorConfig>,
        icons: Map<String, String>,
        res: Map<String, Int>
    ) {
        tileColors = colors
        tileIcons = icons
        tileIconRes = res
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EdgeTileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_edge_menu_tile, parent, false)
        return EdgeTileViewHolder(view)
    }

    override fun onBindViewHolder(holder: EdgeTileViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val config = tileColors[item.id]

        holder.txtTitle.text = item.label

        // Subtitle handling (e.g. storage usage, or description)
        val subtitle = when {
            item.subtitle?.isNotEmpty() == true -> item.subtitle
            item.totalBytes > 0 -> {
                val used = android.text.format.Formatter.formatFileSize(context, item.usedBytes)
                val total = android.text.format.Formatter.formatFileSize(context, item.totalBytes)
                "$used / $total"
            }
            item.isTwinWindowTile -> context.getString(R.string.twin_window_subtitle)
            item.isNotepadTile -> context.getString(R.string.notepad_tile_subtitle)
            item.isScannerTile -> context.getString(R.string.scanner_tile_subtitle)
            item.isAppsTile -> context.getString(R.string.apps_tile_subtitle)
            item.isSearchTile -> context.getString(R.string.search_tile_subtitle)
            item.isAnalyzerTile -> context.getString(R.string.analyzer_tile_subtitle)
            item.isSmartSortTile -> context.getString(R.string.smart_sort_title)
            item.isVaultTile -> context.getString(R.string.vault_tile_subtitle)
            item.isRecycleBinTile -> context.getString(R.string.recycle_bin_title)
            item.isSyncTile -> context.getString(R.string.sync_subtitle)
            item.isAdvancedSyncTile -> context.getString(R.string.advanced_sync_subtitle)
            item.isFileServerTile -> context.getString(R.string.file_server_title)
            item.isSettingsTile -> context.getString(R.string.settings_title)
            item.isNetworkTile -> context.getString(R.string.network_tile_subtitle)
            item.isOnlineStoragesTile -> za.kilowatch.ultimatefilemanager.util.DeviceUtils.getOnlineStoragesSubtitle(context)
            else -> ""
        }

        if (!subtitle.isNullOrEmpty()) {
            holder.txtSubtitle.visibility = View.VISIBLE
            holder.txtSubtitle.text = subtitle
        } else {
            holder.txtSubtitle.visibility = View.GONE
        }

        // Custom colors
        if (config != null && config.labelColor != Color.TRANSPARENT) {
            holder.txtTitle.setTextColor(config.labelColor)
        } else {
            holder.txtTitle.setTextColor(ContextCompat.getColor(context, R.color.mobile_card_text_primary))
        }

        if (config != null && config.iconBgColor != Color.TRANSPARENT) {
            holder.containerIcon.backgroundTintList = ColorStateList.valueOf(config.iconBgColor)
        } else {
            holder.containerIcon.backgroundTintList = null
        }

        if (config != null && config.tileBgColor != Color.TRANSPARENT) {
            holder.card.setCardBackgroundColor(config.tileBgColor)
        } else {
            holder.card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.mobile_glass_card))
        }

        if (config != null && config.ringColor != Color.TRANSPARENT) {
            val strokePx = (1.5f * context.resources.displayMetrics.density).toInt()
            holder.card.strokeWidth = strokePx
            holder.card.setStrokeColor(config.ringColor)
        } else {
            holder.card.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
            holder.card.setStrokeColor(ContextCompat.getColor(context, R.color.mobile_glass_stroke))
        }

        bindTileIcon(item, holder.imgIcon)

        holder.itemView.setOnClickListener {
            onTileClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun bindTileIcon(item: StorageItem, imgView: ImageView) {
        val customPath = tileIcons[item.id]
        val customRes = tileIconRes[item.id]

        if (!customPath.isNullOrEmpty() && File(customPath).exists()) {
            try {
                val bmp = BitmapFactory.decodeFile(customPath)
                if (bmp != null) {
                    imgView.setImageBitmap(bmp)
                    imgView.colorFilter = null
                    return
                }
            } catch (_: Exception) {}
        }

        val iconRes = if (customRes != null && customRes != 0) customRes else item.iconRes
        imgView.setImageResource(iconRes)

        val config = tileColors[item.id]
        if (config != null && config.iconColor != 0) {
            imgView.colorFilter = PorterDuffColorFilter(config.iconColor, PorterDuff.Mode.SRC_IN)
        } else {
            imgView.setColorFilter(
                ContextCompat.getColor(imgView.context, R.color.mobile_icon_tint),
                PorterDuff.Mode.SRC_IN
            )
        }
    }

    class EdgeTileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cardEdgeTile)
        val containerIcon: FrameLayout = view.findViewById(R.id.containerIcon)
        val imgIcon: ImageView = view.findViewById(R.id.imgTileIcon)
        val txtTitle: TextView = view.findViewById(R.id.txtTileTitle)
        val txtSubtitle: TextView = view.findViewById(R.id.txtTileSubtitle)
    }
}
