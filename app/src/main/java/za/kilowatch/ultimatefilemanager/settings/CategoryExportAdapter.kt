package za.kilowatch.ultimatefilemanager.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
        holder.checkBox.isChecked = item.isSelected

        holder.headerLayout.setOnClickListener {
            item.isSelected = !item.isSelected
            holder.checkBox.isChecked = item.isSelected
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val headerLayout: View = itemView.findViewById(R.id.layoutHeader)
        val checkBox: CheckBox = itemView.findViewById(R.id.cbHeader)
        val title: TextView = itemView.findViewById(R.id.txtHeaderTitle)
    }
}
