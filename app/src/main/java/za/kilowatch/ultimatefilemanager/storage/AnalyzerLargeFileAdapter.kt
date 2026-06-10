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

/**
 * Displays files sorted by size descending. Each row shows icon, filename, path, and size.
 * Tapping opens the file via the host activity's file-open flow.
 */
class AnalyzerLargeFileAdapter(
    private val items  : List<FileIndex>,
    private val isTv   : Boolean = false,
    private val onClick: (FileIndex) -> Unit
) : RecyclerView.Adapter<AnalyzerLargeFileAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtName : TextView  = v.findViewById(R.id.txtLargeFileName)
        val txtPath : TextView  = v.findViewById(R.id.txtLargeFilePath)
        val txtSize : TextView  = v.findViewById(R.id.txtLargeFileSize)
        val imgIcon : ImageView = v.findViewById(R.id.imgLargeFileIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (isTv) R.layout.item_analyzer_large_file_tv else R.layout.item_analyzer_large_file
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item    = items[position]
        val context = holder.itemView.context

        holder.txtName.text = item.filename
        holder.txtPath.text = item.folderPath
        holder.txtSize.text = Formatter.formatFileSize(context, item.size)
        holder.imgIcon.setImageResource(FileTypeIconProvider.iconForMime(context, item.mimeType))

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    private fun mimeToIcon(mime: String): Int = FileTypeIconProvider.iconForMime(mime)
}
