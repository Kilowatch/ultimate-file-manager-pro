package za.kilowatch.ultimatefilemanager.settings

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class ExportSelectionAdapter(
    private val rawItems: List<BackupItem>
) : RecyclerView.Adapter<ExportSelectionAdapter.CategoryViewHolder>() {

    data class CategoryGroup(
        val categoryId: String,
        val titleRes: Int,
        val items: List<BackupItem>
    )

    private val groups = mutableListOf<CategoryGroup>()

    init {
        rebuildGroups()
    }

    private fun rebuildGroups() {
        groups.clear()
        val grouped = rawItems.groupBy { it.category }
        val categoryOrder = listOf(
            "shared_preferences",
            "network_shares",
            "online_storages",
            "ftp_server_profiles",
            "storage_renames",
            "smart_sort_configs"
        )
        for (cat in categoryOrder) {
            val children = grouped[cat] ?: continue
            if (children.isEmpty()) continue
            val titleRes = when (cat) {
                "shared_preferences" -> R.string.backup_cat_shared_prefs
                "network_shares" -> R.string.backup_cat_network_shares
                "online_storages" -> R.string.backup_cat_online_storages
                "ftp_server_profiles" -> R.string.backup_cat_ftp_server_profiles
                "storage_renames" -> R.string.backup_cat_storage_renames
                "smart_sort_configs" -> R.string.backup_cat_smart_sort
                else -> R.string.backup_restore_title
            }
            groups.add(CategoryGroup(cat, titleRes, children))
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

        holder.txtHeaderTitle.text = context.getString(group.titleRes)

        // Setup layouts, focusability and colors for TV vs Mobile
        if (isTv) {
            holder.layoutHeader.isFocusable = true
            holder.layoutHeader.isClickable = true
            holder.cbHeader.isFocusable = false
            holder.cbHeader.isClickable = false

            holder.cardCategory.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(context.getColor(R.color.tv_glass_white_10)))
            holder.cardCategory.strokeColor = context.getColor(R.color.tv_glass_border)
            holder.divider.setBackgroundColor(context.getColor(R.color.tv_divider))

            holder.layoutHeader.setBackgroundColor(Color.TRANSPARENT)
            holder.txtHeaderTitle.setTextColor(context.getColor(R.color.tv_text_primary))

            holder.layoutHeader.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.setBackgroundColor(context.getColor(R.color.tv_button_focused_yellow))
                    holder.txtHeaderTitle.setTextColor(context.getColor(R.color.tv_button_focused_yellow_text))
                } else {
                    v.setBackgroundColor(Color.TRANSPARENT)
                    holder.txtHeaderTitle.setTextColor(context.getColor(R.color.tv_text_primary))
                }
            }
        } else {
            holder.layoutHeader.isFocusable = false
            holder.layoutHeader.isClickable = true
            holder.cbHeader.isFocusable = false
            holder.cbHeader.isClickable = false

            holder.cardCategory.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(context.getColor(R.color.mobile_glass_card)))
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

            txtChildName.text = childItem.displayName
            txtChildExtra.text = childItem.extraInfo
            txtChildExtra.visibility = if (childItem.extraInfo.isNotEmpty()) View.VISIBLE else View.GONE

            if (isTv) {
                childView.isFocusable = true
                childView.isClickable = true
                cbChild.isFocusable = false
                cbChild.isClickable = false

                childView.setBackgroundColor(Color.TRANSPARENT)
                txtChildName.setTextColor(context.getColor(R.color.tv_text_primary))
                txtChildExtra.setTextColor(context.getColor(R.color.tv_text_secondary))

                childView.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.setBackgroundColor(context.getColor(R.color.tv_button_focused_yellow))
                        txtChildName.setTextColor(context.getColor(R.color.tv_button_focused_yellow_text))
                        txtChildExtra.setTextColor(Color.parseColor("#333333"))
                    } else {
                        v.setBackgroundColor(Color.TRANSPARENT)
                        txtChildName.setTextColor(context.getColor(R.color.tv_text_primary))
                        txtChildExtra.setTextColor(context.getColor(R.color.tv_text_secondary))
                    }
                }
            } else {
                childView.isClickable = true
                childView.isFocusable = false
                txtChildName.setTextColor(context.getColor(R.color.mobile_text_primary))
                txtChildExtra.setTextColor(context.getColor(R.color.mobile_text_secondary))
            }

            holder.layoutChildren.addView(childView)
        }

        bindHeaderAndChildren(holder, group, isTv, context)
    }

    private fun bindHeaderAndChildren(
        holder: CategoryViewHolder,
        group: CategoryGroup,
        isTv: Boolean,
        context: android.content.Context
    ) {
        // 1. Update Header Checked State: checked if any sub-item is selected
        val isHeaderChecked = group.items.any { it.isSelected }
        holder.cbHeader.setOnCheckedChangeListener(null)
        holder.cbHeader.isChecked = isHeaderChecked

        // 2. Setup Header Click
        holder.layoutHeader.setOnClickListener {
            holder.cbHeader.toggle()
        }

        holder.cbHeader.setOnCheckedChangeListener { _, isChecked ->
            // Set all children items in group
            group.items.forEach { it.isSelected = isChecked }
            // Update children check boxes
            for (i in 0 until holder.layoutChildren.childCount) {
                val childView = holder.layoutChildren.getChildAt(i)
                val cbChild = childView.findViewById<CheckBox>(R.id.cbChild)
                cbChild.setOnCheckedChangeListener(null)
                cbChild.isChecked = isChecked

                // Rebind child listener
                val childItem = group.items[i]
                cbChild.setOnCheckedChangeListener { _, childChecked ->
                    childItem.isSelected = childChecked
                    // Re-evaluate header checkbox
                    val anySelected = group.items.any { it.isSelected }
                    holder.cbHeader.setOnCheckedChangeListener(null)
                    holder.cbHeader.isChecked = anySelected
                    // Re-bind header checked listener
                    bindHeaderAndChildren(holder, group, isTv, context)
                }
            }
        }

        // 3. Setup Children Listeners
        for (i in 0 until holder.layoutChildren.childCount) {
            val childView = holder.layoutChildren.getChildAt(i)
            val cbChild = childView.findViewById<CheckBox>(R.id.cbChild)
            val childItem = group.items[i]

            cbChild.setOnCheckedChangeListener(null)
            cbChild.isChecked = childItem.isSelected

            childView.setOnClickListener {
                cbChild.toggle()
            }

            cbChild.setOnCheckedChangeListener { _, childChecked ->
                childItem.isSelected = childChecked
                val anySelected = group.items.any { it.isSelected }
                holder.cbHeader.setOnCheckedChangeListener(null)
                holder.cbHeader.isChecked = anySelected
                bindHeaderAndChildren(holder, group, isTv, context)
            }
        }
    }

    override fun getItemCount(): Int = groups.size

    class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardCategory: MaterialCardView = itemView.findViewById(R.id.cardCategory)
        val layoutHeader: ViewGroup = itemView.findViewById(R.id.layoutHeader)
        val cbHeader: CheckBox = itemView.findViewById(R.id.cbHeader)
        val txtHeaderTitle: TextView = itemView.findViewById(R.id.txtHeaderTitle)
        val divider: View = itemView.findViewById(R.id.divider)
        val layoutChildren: ViewGroup = itemView.findViewById(R.id.layoutChildren)
    }
}
