package za.kilowatch.ultimatefilemanager.storage

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.FileIndex

/**
 * Combined adapter for the Junk & Old tab.
 * Two sections: junk/cache files first, then old (unmodified) files.
 * Section headers are static items injected between the two lists.
 */
class AnalyzerJunkOldAdapter(
    private val context: android.content.Context,
    private val junkFiles: List<FileIndex>,
    private val oldFiles : List<FileIndex>,
    private val isTv     : Boolean = false,
    private val onClick  : (FileIndex) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class File(val item: FileIndex) : Row()
    }

    private val rows: List<Row> = buildRows()

    private fun buildRows(): List<Row> {
        val list = mutableListOf<Row>()
        if (junkFiles.isNotEmpty()) {
            list.add(Row.Header(context.getString(R.string.cache_temp_files_junkfilessize, junkFiles.size)))
            junkFiles.forEach { list.add(Row.File(it)) }
        }
        if (oldFiles.isNotEmpty()) {
            list.add(Row.Header(context.getString(R.string.old_files_not_modified_in_180_days_oldfilessize, oldFiles.size)))
            oldFiles.forEach { list.add(Row.File(it)) }
        }
        return list
    }

    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.Header) VIEW_HEADER else VIEW_FILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_HEADER -> {
                val v = inf.inflate(R.layout.item_section_header, parent, false)
                HeaderVH(v)
            }
            else -> {
                val layout = if (isTv) R.layout.item_analyzer_junk_file_tv else R.layout.item_analyzer_junk_file
                FileVH(inf.inflate(layout, parent, false))
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).txtHeader.text = row.text
            is Row.File   -> {
                val vh = holder as FileVH
                val fi = row.item
                vh.txtName.text = fi.filename
                vh.txtPath.text = fi.folderPath
                vh.txtSize.text = Formatter.formatFileSize(ctx, fi.size)
                holder.itemView.setOnClickListener { onClick(fi) }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val txtHeader: TextView = v.findViewById(R.id.txtSectionHeader)
    }

    inner class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        val txtName : TextView = v.findViewById(R.id.txtJunkFileName)
        val txtPath : TextView = v.findViewById(R.id.txtJunkFilePath)
        val txtSize : TextView = v.findViewById(R.id.txtJunkFileSize)
    }

    companion object {
        private const val VIEW_HEADER = 0
        private const val VIEW_FILE   = 1
    }
}
