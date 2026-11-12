package za.kilowatch.ultimatefilemanager.storage

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import za.kilowatch.ultimatefilemanager.util.SparseFileUtils
import java.io.File
import java.util.Locale

class AnalyzerIncompleteAdapter(
    private val items  : List<FileIndex>,
    private val isTv   : Boolean = false,
    private val onClick: (FileIndex) -> Unit
) : RecyclerView.Adapter<AnalyzerIncompleteAdapter.VH>() {

    private val allocationCache = mutableMapOf<String, za.kilowatch.ultimatefilemanager.util.FileAllocation>()

    private fun getAllocation(path: String): za.kilowatch.ultimatefilemanager.util.FileAllocation {
        return allocationCache.getOrPut(path) {
            SparseFileUtils.getFileAllocation(File(path))
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtName : TextView  = v.findViewById(R.id.txtIncompleteFileName)
        val txtPath : TextView  = v.findViewById(R.id.txtIncompleteFilePath)
        val txtSize : TextView  = v.findViewById(R.id.txtIncompleteFileSize)
        val txtBadge: TextView  = v.findViewById(R.id.txtIncompleteAllocatedBadge)
        val imgIcon : ImageView = v.findViewById(R.id.imgIncompleteFileIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (isTv) R.layout.item_analyzer_incomplete_file_tv else R.layout.item_analyzer_incomplete_file
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item    = items[position]
        val context = holder.itemView.context

        holder.txtName.text = item.filename
        holder.txtPath.text = item.folderPath
        holder.txtSize.text = Formatter.formatFileSize(context, item.size)

        val alloc = getAllocation(item.path)
        val allocatedFmt = Formatter.formatFileSize(context, alloc.allocatedBytes)
        holder.txtBadge.text = "$allocatedFmt (${String.format(Locale.getDefault(), "%.1f%%", alloc.allocationPercentage)})"

        holder.imgIcon.setImageResource(FileTypeIconProvider.iconForMime(context, item.mimeType))
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
