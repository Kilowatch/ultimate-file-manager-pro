package za.kilowatch.ultimatefilemanager.storage

import android.graphics.BitmapFactory
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
import za.kilowatch.ultimatefilemanager.R
import java.io.File

/**
 * Adapter for the mobile floating bottom dock on the main screen.
 * Displays user-docked storage/tool items followed by a trailing "+ Add" button.
 */
class FloatingBarAdapter(
    private val onTileClick: (StorageItem) -> Unit,
    private val onAddClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_TILE = 0
        private const val VIEW_TYPE_ADD = 1
    }

    private val items = mutableListOf<StorageItem>()
    private var tileColors: Map<String, TileColorConfig> = emptyMap()
    private var tileIcons: Map<String, String> = emptyMap()
    private var tileIconRes: Map<String, Int> = emptyMap()

    fun submitItems(newItems: List<StorageItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun submitList(newItems: List<StorageItem>) {
        submitItems(newItems)
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

    fun setTileColors(colors: Map<String, TileColorConfig>) {
        tileColors = colors
        notifyDataSetChanged()
    }

    fun setTileIcons(icons: Map<String, String>) {
        tileIcons = icons
        notifyDataSetChanged()
    }

    fun setTileIconRes(res: Map<String, Int>) {
        tileIconRes = res
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < items.size) VIEW_TYPE_TILE else VIEW_TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_TILE) {
            val v = inflater.inflate(R.layout.item_floating_bar_tile, parent, false)
            TileViewHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_floating_bar_add, parent, false)
            AddViewHolder(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is TileViewHolder) {
            val item = items[position]
            holder.txtLabel.text = item.label
            val config = tileColors[item.id]
            if (config != null && config.labelColor != android.graphics.Color.TRANSPARENT) {
                holder.txtLabel.setTextColor(config.labelColor)
            } else {
                holder.txtLabel.setTextColor(
                    ContextCompat.getColor(holder.txtLabel.context, R.color.mobile_card_text_primary)
                )
            }
            if (config != null && config.iconBgColor != android.graphics.Color.TRANSPARENT) {
                holder.containerIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(config.iconBgColor)
            } else {
                holder.containerIcon.backgroundTintList = null
            }
            bindTileIcon(item, holder.imgIcon, holder.containerIcon)
            holder.itemView.setOnClickListener {
                onTileClick(item)
            }
        } else if (holder is AddViewHolder) {
            holder.itemView.setOnClickListener {
                onAddClick()
            }
        }
    }

    override fun getItemCount(): Int {
        // Items + 1 for trailing "+ Add" button
        return items.size + 1
    }

    private fun bindTileIcon(item: StorageItem, imgView: ImageView, container: FrameLayout) {
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

    class TileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val containerIcon: FrameLayout = view.findViewById(R.id.containerIcon)
        val imgIcon: ImageView = view.findViewById(R.id.imgTileIcon)
        val txtLabel: TextView = view.findViewById(R.id.txtTileLabel)
    }

    class AddViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
