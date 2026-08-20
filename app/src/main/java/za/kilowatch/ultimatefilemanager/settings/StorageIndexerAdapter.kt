package za.kilowatch.ultimatefilemanager.settings

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.StorageItem

/**
 * Adapter for the Storage Indexer list.
 */
class StorageIndexerAdapter(
    private val isTv: Boolean,
    private val onStorageClick: (StorageItem) -> Unit
) : ListAdapter<StorageItem, StorageIndexerAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (isTv) R.layout.item_storage_indexer_tv else R.layout.item_storage_indexer
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val iconView = view.findViewById<ImageView>(R.id.iconStorage)
        private val labelView = view.findViewById<TextView>(R.id.txtLabel)
        private val statusView = view.findViewById<TextView>(R.id.txtStatus)
        private val countView = view.findViewById<TextView>(R.id.txtFileCount)
        private val icLightning = view.findViewById<ImageView>(R.id.icLightning)

        init {
            if (isTv) {
                val yellow = itemView.context.getColor(R.color.tv_button_focused_yellow)
                val white = itemView.context.getColor(R.color.tv_text_primary)
                val blackText = itemView.context.getColor(R.color.tv_button_focused_yellow_text)

                itemView.setOnFocusChangeListener { _, hasFocus ->
                    itemView.backgroundTintList = ColorStateList.valueOf(if (hasFocus) yellow else 0x1AFFFFFF)
                    labelView.setTextColor(if (hasFocus) blackText else white)
                    statusView.setTextColor(if (hasFocus) blackText else itemView.context.getColor(R.color.tv_text_secondary))
                    countView.setTextColor(if (hasFocus) blackText else itemView.context.getColor(R.color.tv_button_focused_yellow))
                }
            }
            itemView.setOnClickListener { onStorageClick(getItem(bindingAdapterPosition)) }
        }

        fun bind(item: StorageItem) {
            iconView.setImageResource(item.iconRes)
            labelView.text = item.label
            
            val isIndexed = item.isIndexed
            
            icLightning.visibility = if (isIndexed) View.VISIBLE else View.GONE
            statusView.text = if (isIndexed) {
                itemView.context.getString(R.string.storage_indexer_status_ready)
            } else {
                itemView.context.getString(R.string.storage_indexer_status_never)
            }

            if (isIndexed) {
                countView.text = itemView.context.getString(R.string.storage_indexer_indexed_count, item.indexedFileCount.toString())
                countView.visibility = View.VISIBLE
            } else {
                countView.visibility = View.GONE
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<StorageItem>() {
        override fun areItemsTheSame(oldItem: StorageItem, newItem: StorageItem): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: StorageItem, newItem: StorageItem): Boolean = oldItem == newItem
    }
}
