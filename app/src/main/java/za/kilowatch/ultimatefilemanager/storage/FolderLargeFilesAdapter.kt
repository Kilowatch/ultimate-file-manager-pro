package za.kilowatch.ultimatefilemanager.storage

import android.graphics.Color
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.FileIndex

/**
 * Adapter for Folder Large Files list.
 */
class FolderLargeFilesAdapter(
    private var files: List<FileIndex>,
    private val isTv: Boolean = false,
    private val targetFolder: String? = null,
    var onSelectionChanged: (count: Int) -> Unit = {}
) : RecyclerView.Adapter<FolderLargeFilesAdapter.FileVH>() {

    val checkedFiles = mutableSetOf<FileIndex>()

    fun submitList(newFiles: List<FileIndex>) {
        files = newFiles
        notifyDataSetChanged()
    }

    fun removeFiles(deletedPaths: Set<String>) {
        val updated = files.filter { it.path !in deletedPaths }
        checkedFiles.removeAll { it.path in deletedPaths }
        submitList(updated)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
        val inf = LayoutInflater.from(parent.context)
        val layout = if (isTv) R.layout.item_folder_large_file_tv else R.layout.item_folder_large_file
        return FileVH(inf.inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: FileVH, position: Int) {
        val ctx = holder.itemView.context
        val fi = files[position]

        holder.txtName.text = fi.filename
        holder.txtPath.text = fi.folderPath
        holder.txtSize.text = Formatter.formatFileSize(ctx, fi.size)

        if (!targetFolder.isNullOrEmpty()) {
            val cleanFileFolder = fi.folderPath.trimEnd('/')
            val cleanTarget = targetFolder.trimEnd('/')
            if (cleanFileFolder.equals(cleanTarget, ignoreCase = true)) {
                holder.txtLocationBadge.text = ctx.getString(R.string.badge_in_folder)
                holder.txtLocationBadge.setBackgroundResource(R.drawable.bg_badge_in_folder)
                holder.txtLocationBadge.setTextColor(Color.parseColor("#00897B"))
            } else {
                holder.txtLocationBadge.text = ctx.getString(R.string.badge_subfolder)
                holder.txtLocationBadge.setBackgroundResource(R.drawable.bg_badge_subfolder)
                holder.txtLocationBadge.setTextColor(Color.parseColor("#0284C7"))
            }
            holder.txtLocationBadge.visibility = View.VISIBLE
        } else {
            holder.txtLocationBadge.visibility = View.GONE
        }

        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = checkedFiles.contains(fi)
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked) checkedFiles.add(fi) else checkedFiles.remove(fi)
            onSelectionChanged(checkedFiles.size)
        }
        holder.itemView.setOnClickListener { holder.checkbox.isChecked = !holder.checkbox.isChecked }
    }

    override fun getItemCount(): Int = files.size

    inner class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        val txtName: TextView = v.findViewById(R.id.txtLargeFileName)
        val txtPath: TextView = v.findViewById(R.id.txtLargeFilePath)
        val txtSize: TextView = v.findViewById(R.id.txtLargeFileSize)
        val txtLocationBadge: TextView = v.findViewById(R.id.txtLargeFileLocationBadge)
        val checkbox: CheckBox = v.findViewById(R.id.checkLargeFile)
    }
}
