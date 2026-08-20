package za.kilowatch.ultimatefilemanager.settings

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.ThemeColors
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class ImportDetailsAdapter(
    private val details: BackupDetails
) : RecyclerView.Adapter<ImportDetailsAdapter.CategoryViewHolder>() {

    data class CategoryGroup(
        val categoryId: String,
        val titleRes: Int,
        val iconRes: Int,
        val items: List<BackupItem>
    )

    private val groups = mutableListOf<CategoryGroup>()

    init {
        rebuildGroups()
    }

    private fun rebuildGroups() {
        groups.clear()
        
        val allItems = mutableListOf<BackupItem>()
        allItems.addAll(details.sharedPrefs)
        allItems.addAll(details.shares)
        allItems.addAll(details.storages)
        allItems.addAll(details.ftpProfiles)
        allItems.addAll(details.renames)
        allItems.addAll(details.smartSortConfigs)
        allItems.addAll(details.customTiles)

        val grouped = allItems.groupBy { it.category }

        val categoryOrder = listOf(
            "shared_preferences",
            "network_shares",
            "online_storages",
            "ftp_server_profiles",
            "storage_renames",
            "smart_sort_configs",
            "custom_tiles"
        )

        for (cat in categoryOrder) {
            val children = grouped[cat] ?: continue
            if (children.isEmpty()) continue

            val (titleRes, iconRes) = when (cat) {
                "shared_preferences" -> Pair(R.string.backup_cat_shared_prefs, R.drawable.ic_settings)
                "network_shares" -> Pair(R.string.backup_cat_network_shares, R.drawable.ic_network)
                "online_storages" -> Pair(R.string.backup_cat_online_storages, R.drawable.ic_cloud)
                "ftp_server_profiles" -> Pair(R.string.backup_cat_ftp_server_profiles, R.drawable.ic_file_server)
                "storage_renames" -> Pair(R.string.backup_cat_storage_renames, R.drawable.ic_edit)
                "smart_sort_configs" -> Pair(R.string.backup_cat_smart_sort, R.drawable.ic_sort)
                "custom_tiles" -> Pair(R.string.backup_cat_custom_tiles, R.drawable.ic_apps)
                else -> Pair(R.string.backup_restore_title, R.drawable.ic_file_backup)
            }
            groups.add(CategoryGroup(cat, titleRes, iconRes, children))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_export_category_card, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val context = holder.itemView.context
        val isTv = DeviceUtils.isTvDevice(context)
        val group = groups[position]
        val primaryColor = ThemeColors.primary(context)
        val primaryCsl = ColorStateList.valueOf(primaryColor)

        holder.txtHeaderTitle.text = context.getString(group.titleRes)
        holder.imgCategoryIcon.setImageResource(group.iconRes)
        holder.imgCategoryIcon.imageTintList = primaryCsl
        holder.cbHeader.visibility = View.GONE // Hide checkbox for preview mode

        // Style the card for TV vs Mobile
        if (isTv) {
            holder.cardCategory.isFocusable = true
            holder.cardCategory.isClickable = false
            holder.layoutHeader.isFocusable = false
            holder.layoutHeader.isClickable = false

            holder.cardCategory.setCardBackgroundColor(ColorStateList.valueOf(context.getColor(R.color.tv_glass_white_10)))
            holder.cardCategory.strokeColor = context.getColor(R.color.tv_glass_border)
            holder.divider.setBackgroundColor(context.getColor(R.color.tv_divider))
            holder.txtHeaderTitle.setTextColor(context.getColor(R.color.tv_text_primary))

            holder.cardCategory.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    holder.cardCategory.setCardBackgroundColor(ColorStateList.valueOf(context.getColor(R.color.tv_surface_focused)))
                    holder.cardCategory.strokeColor = context.getColor(R.color.tv_focus_border_strong)
                } else {
                    holder.cardCategory.setCardBackgroundColor(ColorStateList.valueOf(context.getColor(R.color.tv_glass_white_10)))
                    holder.cardCategory.strokeColor = context.getColor(R.color.tv_glass_border)
                }
            }
        } else {
            holder.cardCategory.isFocusable = false
            holder.cardCategory.isClickable = false
            holder.layoutHeader.isFocusable = false
            holder.layoutHeader.isClickable = false

            holder.cardCategory.setCardBackgroundColor(ColorStateList.valueOf(context.getColor(R.color.mobile_glass_card)))
            holder.cardCategory.strokeColor = context.getColor(R.color.mobile_glass_stroke)
            holder.divider.setBackgroundColor(context.getColor(R.color.mobile_glass_stroke))
            holder.txtHeaderTitle.setTextColor(context.getColor(R.color.mobile_text_primary))
        }

        // Populate children dynamically
        holder.layoutChildren.removeAllViews()
        val inflater = LayoutInflater.from(context)
        group.items.forEach { childItem ->
            val childView = inflater.inflate(R.layout.item_export_child, holder.layoutChildren, false)
            val cbChild = childView.findViewById<CheckBox>(R.id.cbChild)
            val txtChildName = childView.findViewById<TextView>(R.id.txtChildName)
            val txtChildExtra = childView.findViewById<TextView>(R.id.txtChildExtra)

            cbChild.visibility = View.GONE // Hide checkbox for preview mode

            txtChildName.text = childItem.displayName
            txtChildExtra.text = childItem.extraInfo
            txtChildExtra.visibility = if (childItem.extraInfo.isNotEmpty()) View.VISIBLE else View.GONE

            if (isTv) {
                childView.isFocusable = false
                childView.isClickable = false
                txtChildName.setTextColor(context.getColor(R.color.tv_text_primary))
                txtChildExtra.setTextColor(context.getColor(R.color.tv_text_secondary))
            } else {
                childView.isFocusable = false
                childView.isClickable = false
                txtChildName.setTextColor(context.getColor(R.color.mobile_text_primary))
                txtChildExtra.setTextColor(context.getColor(R.color.mobile_text_secondary))
            }

            holder.layoutChildren.addView(childView)
        }
    }

    override fun getItemCount(): Int = groups.size

    class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardCategory: MaterialCardView = itemView.findViewById(R.id.cardCategory)
        val layoutHeader: ViewGroup = itemView.findViewById(R.id.layoutHeader)
        val imgCategoryIcon: ImageView = itemView.findViewById(R.id.imgCategoryIcon)
        val cbHeader: CheckBox = itemView.findViewById(R.id.cbHeader)
        val txtHeaderTitle: TextView = itemView.findViewById(R.id.txtHeaderTitle)
        val divider: View = itemView.findViewById(R.id.divider)
        val layoutChildren: ViewGroup = itemView.findViewById(R.id.layoutChildren)
    }
}
