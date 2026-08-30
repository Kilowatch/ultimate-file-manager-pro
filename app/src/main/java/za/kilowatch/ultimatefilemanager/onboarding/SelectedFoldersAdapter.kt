package za.kilowatch.ultimatefilemanager.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.SafLocation

/**
 * Adapter for displaying the scrollable list of selected folders during onboarding setup.
 */
class SelectedFoldersAdapter(
    private val onRemoveClick: (SafLocation) -> Unit
) : RecyclerView.Adapter<SelectedFoldersAdapter.FolderViewHolder>() {

    private val items = mutableListOf<SafLocation>()

    fun submitList(newItems: List<SafLocation>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<SafLocation> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_selected_folder, parent, false)
        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgFolderIcon: ImageView = itemView.findViewById(R.id.imgFolderIcon)
        private val txtFolderName: TextView = itemView.findViewById(R.id.txtFolderName)
        private val txtStorageLabel: TextView = itemView.findViewById(R.id.txtStorageLabel)
        private val btnRemoveFolder: ImageView = itemView.findViewById(R.id.btnRemoveFolder)

        fun bind(location: SafLocation) {
            val context = itemView.context
            txtFolderName.text = location.displayName

            val displayPath = location.getDisplayPath()
            val storageName = when {
                displayPath.startsWith("/storage/emulated/0") -> context.getString(R.string.storage_internal)
                displayPath.startsWith("/storage/") -> context.getString(R.string.storage_sd_card)
                else -> context.getString(R.string.saf_storage)
            }

            txtStorageLabel.text = if (displayPath.isNotEmpty()) "$storageName — $displayPath" else storageName

            val iconRes = if (location.iconType == "terminal" || location.authority.contains("termux")) {
                R.drawable.ic_terminal
            } else {
                R.drawable.ic_folder
            }
            imgFolderIcon.setImageResource(iconRes)

            btnRemoveFolder.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onRemoveClick(location)
                }
            }
        }
    }
}
