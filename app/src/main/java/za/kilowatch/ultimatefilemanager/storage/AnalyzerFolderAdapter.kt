package za.kilowatch.ultimatefilemanager.storage

import android.content.Intent
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R

/**
 * Displays top folders by size. Each row shows folder name, file count,
 * formatted size, and a proportional fill-bar. Tapping opens FileBrowserActivity.
 */
class AnalyzerFolderAdapter(
    private val items : List<AnalyzerFolder>,
    private val isTv  : Boolean = false,
    private val onClick: (AnalyzerFolder) -> Unit
) : RecyclerView.Adapter<AnalyzerFolderAdapter.VH>() {

    private val maxSize = items.maxOfOrNull { it.totalSize } ?: 1L

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtName    : TextView    = v.findViewById(R.id.txtFolderName)
        val txtCount   : TextView    = v.findViewById(R.id.txtFolderCount)
        val txtSize    : TextView    = v.findViewById(R.id.txtFolderSize)
        val progressBar: ProgressBar = v.findViewById(R.id.progressFolder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (isTv) R.layout.item_analyzer_folder_tv else R.layout.item_analyzer_folder
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item    = items[position]
        val context = holder.itemView.context

        // Show only the last 2 path segments as the display name
        val segments = item.folderPath.trimEnd('/').split("/")
        holder.txtName.text  = when {
            segments.size >= 2 -> "…/${segments.takeLast(2).joinToString("/")}"
            else               -> item.folderPath
        }
        holder.txtCount.text        = "${item.fileCount} files"
        holder.txtSize.text         = Formatter.formatFileSize(context, item.totalSize)
        holder.progressBar.max      = 1000
        holder.progressBar.progress = ((item.totalSize.toFloat() / maxSize) * 1000).toInt()

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
