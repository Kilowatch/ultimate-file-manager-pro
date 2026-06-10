package za.kilowatch.ultimatefilemanager.storage

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Data class representing an installed application.
 */
data class AppItem(
    val name: String,
    val packageName: String,
    val isSystem: Boolean,
    val appSizeBytes: Long,
    val dataSizeBytes: Long,
    val installedDate: Long,
    val icon: android.graphics.drawable.Drawable?,
    val sourceDir: String = "",
    val splitSourceDirs: List<String> = emptyList(),
    val hasObb: Boolean = false,
    val versionName: String = "",
    val versionCode: Long = 0L,
    var debloatInfo: DebloatApp? = null
)

/**
 * RecyclerView adapter for displaying installed applications.
 *
 * @param onAppClick Callback when the user taps an app card
 */
class AppAdapter(
    private val onAppClick: (AppItem) -> Unit,
    private val onAppLongClick: ((AppItem) -> Unit)? = null,
    /** If true, use the compact item_app_twin layout (twin-window pane). */
    private val compactLayout: Boolean = false
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    val items = mutableListOf<AppItem>()
    val selectedItems = mutableSetOf<AppItem>()
    private var isTv: Boolean = false
    private var showDebloatBadges: Boolean = false

    fun submitList(newItems: List<AppItem>, showBadges: Boolean = false) {
        showDebloatBadges = showBadges
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setTvMode(tvMode: Boolean) {
        isTv = tvMode
    }

    fun toggleSelection(item: AppItem) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item)
        } else {
            selectedItems.add(item)
        }
        notifyItemChanged(items.indexOf(item))
    }

    fun getSelectedItems(): List<AppItem> = selectedItems.toList()

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val layoutRes = when {
            compactLayout && isTv -> R.layout.item_app_twin_tv
            compactLayout         -> R.layout.item_app_twin
            isTv                  -> R.layout.item_app_tv
            else                  -> R.layout.item_app
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position], isTv)
    }

    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // card is null for the compact twin layout (ConstraintLayout root)
        private val card: MaterialCardView? = itemView as? MaterialCardView
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
        private val txtName: TextView = itemView.findViewById(R.id.txtAppName)
        private val txtPackage: TextView? = itemView.findViewById(R.id.txtAppPackage)
        private val txtSize: TextView = itemView.findViewById(R.id.txtAppSize)
        private val txtDebloatBadge: TextView? = itemView.findViewById(R.id.txtDebloatBadge)
        private val selectionOverlay: View? = itemView.findViewById(R.id.selectionOverlay)

        fun bind(item: AppItem, isTv: Boolean) {
            val context = itemView.context

            if (item.icon != null) {
                imgIcon.setImageDrawable(item.icon)
            } else {
                imgIcon.setImageResource(R.drawable.ic_apps)
            }

            txtName.text = item.name
            txtPackage?.text = item.packageName
            txtSize.text = Formatter.formatFileSize(context, item.appSizeBytes)

            // Debloat Badge (full-screen card layout only)
            if (txtDebloatBadge != null) {
                if (showDebloatBadges && item.debloatInfo != null) {
                    txtDebloatBadge.visibility = View.VISIBLE
                    txtDebloatBadge.text = item.debloatInfo?.recommendation ?: ""
                    val color = when (item.debloatInfo?.recommendation?.lowercase()) {
                        "recommended"        -> 0xFF4CAF50.toInt()
                        "advanced"           -> 0xFFFFC107.toInt()
                        "expert"             -> 0xFFFF9800.toInt()
                        "dangerous", "unsafe"-> 0xFFF44336.toInt()
                        else                 -> 0xFF9E9E9E.toInt()
                    }
                    txtDebloatBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                } else {
                    txtDebloatBadge.visibility = View.GONE
                }
            }

            // TV focus styling (card layout only)
            if (isTv && card != null) {
                val yellow = context.getColor(R.color.tv_button_focused_yellow)
                val black  = context.getColor(R.color.tv_button_focused_yellow_text)
                val white  = context.getColor(R.color.tv_text_primary)
                val hint   = context.getColor(R.color.tv_text_hint)
                val glassBg = context.getColor(R.color.tv_glass_white_10)

                card.setOnFocusChangeListener { _, hasFocus ->
                    val isSelected = selectedItems.contains(item)
                    if (hasFocus) {
                        card.setCardBackgroundColor(yellow)
                        txtName.setTextColor(black)
                        txtPackage?.setTextColor(black)
                        txtSize.setTextColor(black)
                        card.strokeWidth = 0
                    } else {
                        // Restore state based on selection
                        if (isSelected && !compactLayout) {
                            card.setCardBackgroundColor(context.getColor(R.color.ufm_selection_highlight))
                            card.strokeWidth = 4
                            card.setStrokeColor(android.content.res.ColorStateList.valueOf(context.getColor(R.color.ufm_primary)))
                        } else {
                            card.setCardBackgroundColor(glassBg)
                            card.strokeWidth = 0
                        }
                        txtName.setTextColor(white)
                        txtPackage?.setTextColor(hint)
                        txtSize.setTextColor(hint)
                    }
                }
            }

            // Selection visual
            val isSelected = selectedItems.contains(item)
            if (card != null) {
                // Card-based layout
                if (isSelected) {
                    if (!isTv || !card.hasFocus()) {
                        card.strokeWidth = 4
                        card.strokeColor = context.getColor(R.color.ufm_primary)
                        card.setCardBackgroundColor(context.getColor(R.color.ufm_selection_highlight))
                    }
                } else {
                    card.strokeWidth = 0
                    if (isTv) {
                        if (!card.hasFocus()) card.setCardBackgroundColor(context.getColor(R.color.tv_glass_white_10))
                    } else {
                        card.setCardBackgroundColor(context.getColor(R.color.mobile_glass_card))
                    }
                }
            }

            // Compact layouts (Mobile & TV) both now have selectionOverlay
            selectionOverlay?.visibility = if (isSelected) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                if (selectedItems.isNotEmpty() && onAppLongClick != null) {
                    onAppLongClick.invoke(item)
                } else {
                    onAppClick(item)
                }
            }

            itemView.setOnLongClickListener {
                onAppLongClick?.invoke(item)
                true
            }
        }
    }
}

