package za.kilowatch.ultimatefilemanager.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R

class CategoryExportAdapter(
    private val items: List<IconPackExportActivity.CategorySelection>
) : RecyclerView.Adapter<CategoryExportAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_export_category_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.label
        holder.subtitle.text = holder.itemView.context.getString(R.string.category_icons_count, item.iconIds.size)
        holder.checkBox.isChecked = item.isSelected
        holder.icon.setImageResource(getCategoryIconRes(item.categoryId))

        holder.headerLayout.setOnClickListener {
            item.isSelected = !item.isSelected
            holder.checkBox.isChecked = item.isSelected
        }
    }

    private fun getCategoryIconRes(categoryId: String): Int {
        return when (categoryId) {
            "file_types" -> R.drawable.ic_file
            "folders" -> R.drawable.ic_folder
            "toolbar" -> R.drawable.ic_edit
            "navigation" -> R.drawable.ic_arrow_back
            "settings" -> R.drawable.ic_settings
            "main_menu_tiles" -> R.drawable.ic_view_grid_medium
            "media_player" -> R.drawable.ic_music
            "utility" -> R.drawable.ic_gear
            "status" -> R.drawable.ic_shield_check
            "view_modes" -> R.drawable.ic_view_list
            "feature_tiles" -> R.drawable.ic_star
            else -> R.drawable.ic_apps
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val headerLayout: View = itemView.findViewById(R.id.layoutHeader)
        val icon: ImageView = itemView.findViewById(R.id.imgCategoryIcon)
        val checkBox: CheckBox = itemView.findViewById(R.id.cbHeader)
        val title: TextView = itemView.findViewById(R.id.txtHeaderTitle)
        val subtitle: TextView = itemView.findViewById(R.id.txtHeaderSubtitle)
    }
}
