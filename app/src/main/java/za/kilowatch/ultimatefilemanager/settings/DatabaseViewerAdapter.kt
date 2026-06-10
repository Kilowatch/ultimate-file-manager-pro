package za.kilowatch.ultimatefilemanager.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import java.text.DecimalFormat

class DatabaseViewerAdapter(
    private val isTv: Boolean
) : RecyclerView.Adapter<DatabaseViewerAdapter.ViewHolder>() {

    private val items = mutableListOf<FileIndex>()

    fun submitList(newItems: List<FileIndex>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = if (isTv) R.layout.item_db_entry_tv else R.layout.item_db_entry
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view, isTv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View, private val isTv: Boolean) : RecyclerView.ViewHolder(itemView) {
        private val txtFilename: TextView = itemView.findViewById(R.id.txtFilename)
        private val txtPath: TextView     = itemView.findViewById(R.id.txtPath)
        private val txtSize: TextView     = itemView.findViewById(R.id.txtSize)
        private val txtType: TextView     = itemView.findViewById(R.id.txtType)
        private val txtStorage: TextView  = itemView.findViewById(R.id.txtStorage)

        fun bind(fileIndex: FileIndex) {
            txtFilename.text = fileIndex.filename
            txtPath.text = fileIndex.path
            txtSize.text = formatFileSize(fileIndex.size)
            txtType.text = fileIndex.mimeType
            txtStorage.text = fileIndex.storageId
            
            if (isTv) {
                val card = itemView as MaterialCardView
                val context = itemView.context
                val primaryText = context.getColor(R.color.tv_text_primary)
                val secondaryText = context.getColor(R.color.tv_text_secondary)
                val yellowText = context.getColor(R.color.tv_button_focused_yellow_text)
                
                card.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        txtFilename.setTextColor(yellowText)
                        txtPath.setTextColor(yellowText)
                        txtSize.setTextColor(yellowText)
                        txtType.setTextColor(yellowText)
                        txtStorage.setTextColor(yellowText)
                    } else {
                        txtFilename.setTextColor(primaryText)
                        txtPath.setTextColor(secondaryText)
                        txtSize.setTextColor(context.getColor(R.color.tv_button_focused_yellow))
                        txtType.setTextColor(secondaryText)
                        txtStorage.setTextColor(secondaryText)
                    }
                }
            }
        }

        private fun formatFileSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
        }
    }
}
