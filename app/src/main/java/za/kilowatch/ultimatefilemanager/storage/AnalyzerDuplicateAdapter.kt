package za.kilowatch.ultimatefilemanager.storage

import android.graphics.Color
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.FileIndex

/**
 * Two view-type adapter for the Duplicates tab.
 *
 * - VIEW_TYPE_GROUP  = expandable group header (hash fingerprint + wasted space badge)
 * - VIEW_TYPE_FILE   = child file row with checkbox
 *
 * The host activity observes [checkedFiles] and shows/hides a delete FAB accordingly.
 */
class AnalyzerDuplicateAdapter(
    private var groups : List<DuplicateGroup>,
    private val isTv   : Boolean = false,
    private val targetFolder: String? = null,
    var onSelectionChanged: (count: Int) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // Flat list: alternating GROUP + FILE items
    private data class GroupItem(val group: DuplicateGroup)
    private data class FileItem(val file: FileIndex, val groupHash: String)
    private sealed class Row { data class G(val g: GroupItem) : Row(); data class F(val f: FileItem) : Row() }

    private val collapsedGroups = mutableSetOf<String>()   // collapsed hash keys
    private var rows: List<Row> = buildRows(groups)

    /** Currently selected file paths (for deletion). */
    val checkedFiles = mutableSetOf<FileIndex>()

    private fun buildRows(gs: List<DuplicateGroup>): List<Row> {
        val result = mutableListOf<Row>()
        for (g in gs) {
            result.add(Row.G(GroupItem(g)))
            if (!collapsedGroups.contains(g.hash)) {
                g.files.forEach { f -> result.add(Row.F(FileItem(f, g.hash))) }
            }
        }
        return result
    }

    fun submitList(newGroups: List<DuplicateGroup>) {
        groups = newGroups
        refresh()
    }

    private fun refresh() {
        rows = buildRows(groups)
        notifyDataSetChanged()
    }

    /** Remove file rows for a set of paths (after deletion). */
    fun removeFiles(deletedPaths: Set<String>) {
        val updated = groups.map { g ->
            g.copy(files = g.files.filter { it.path !in deletedPaths })
        }.filter { it.files.size > 1 }
        checkedFiles.removeAll { it.path in deletedPaths }
        submitList(updated)
    }

    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.G) VIEW_GROUP else VIEW_FILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_GROUP) {
            val layout = if (isTv) R.layout.item_analyzer_duplicate_group_tv else R.layout.item_analyzer_duplicate_group
            GroupVH(inf.inflate(layout, parent, false))
        } else {
            val layout = if (isTv) R.layout.item_analyzer_duplicate_file_tv else R.layout.item_analyzer_duplicate_file
            FileVH(inf.inflate(layout, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        when (val row = rows[position]) {
            is Row.G -> {
                val g  = row.g.group
                val vh = holder as GroupVH
                vh.txtHash.text     = "⋯${g.hash.takeLast(12)}"
                val countStr = if (g.files.size == 1) ctx.getString(R.string.analyzer_duplicate_copy_singular, 1) else ctx.getString(R.string.analyzer_duplicate_copy_plural, g.files.size)
                val savesStr = ctx.getString(R.string.analyzer_duplicate_saves, Formatter.formatFileSize(ctx, g.wastedBytes))
                vh.txtWasted.text   = "$countStr · $savesStr"
                val collapsed = collapsedGroups.contains(g.hash)
                vh.txtToggle.text = if (collapsed) "▶" else "▼"
                // Show amber "⚠ Quick match only" badge for groups skipped by Phase-2 full-hash
                vh.txtUnverifiedBadge.visibility = if (g.isVerified) android.view.View.GONE else android.view.View.VISIBLE
                vh.itemView.setOnClickListener {
                    if (collapsed) collapsedGroups.remove(g.hash) else collapsedGroups.add(g.hash)
                    refresh()
                }
            }
            is Row.F -> {
                val fi = row.f.file
                val vh = holder as FileVH
                vh.txtName.text   = fi.filename
                vh.txtPath.text   = fi.folderPath
                vh.txtSize.text   = Formatter.formatFileSize(ctx, fi.size)

                if (!targetFolder.isNullOrEmpty()) {
                    val cleanFileFolder = fi.folderPath.trimEnd('/')
                    val cleanTarget = targetFolder.trimEnd('/')
                    vh.txtLocationBadge.visibility = View.VISIBLE
                    when {
                        cleanFileFolder.equals(cleanTarget, ignoreCase = true) -> {
                            vh.txtLocationBadge.text = ctx.getString(R.string.badge_in_folder)
                            vh.txtLocationBadge.setBackgroundResource(R.drawable.bg_badge_in_folder)
                            vh.txtLocationBadge.setTextColor(Color.parseColor("#00897B"))
                        }
                        cleanFileFolder.startsWith("$cleanTarget/", ignoreCase = true) -> {
                            vh.txtLocationBadge.text = ctx.getString(R.string.badge_subfolder)
                            vh.txtLocationBadge.setBackgroundResource(R.drawable.bg_badge_subfolder)
                            vh.txtLocationBadge.setTextColor(Color.parseColor("#0284C7"))
                        }
                        else -> {
                            vh.txtLocationBadge.text = ctx.getString(R.string.badge_across_storage)
                            vh.txtLocationBadge.setBackgroundResource(R.drawable.bg_badge_across_storage)
                            vh.txtLocationBadge.setTextColor(Color.parseColor("#F57C00"))
                        }
                    }
                } else {
                    vh.txtLocationBadge.visibility = View.GONE
                }

                vh.checkbox.setOnCheckedChangeListener(null)
                vh.checkbox.isChecked = checkedFiles.contains(fi)
                vh.checkbox.setOnCheckedChangeListener { _, checked ->
                    if (checked) checkedFiles.add(fi) else checkedFiles.remove(fi)
                    onSelectionChanged(checkedFiles.size)
                }
                vh.itemView.setOnClickListener { vh.checkbox.isChecked = !vh.checkbox.isChecked }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class GroupVH(v: View) : RecyclerView.ViewHolder(v) {
        val txtHash           : TextView = v.findViewById(R.id.txtDupHash)
        val txtWasted         : TextView = v.findViewById(R.id.txtDupWasted)
        val txtToggle         : TextView = v.findViewById(R.id.txtDupToggle)
        val txtUnverifiedBadge: TextView = v.findViewById(R.id.txtUnverifiedBadge)
    }

    inner class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        val txtName          : TextView = v.findViewById(R.id.txtDupFileName)
        val txtPath          : TextView = v.findViewById(R.id.txtDupFilePath)
        val txtSize          : TextView = v.findViewById(R.id.txtDupFileSize)
        val txtLocationBadge : TextView = v.findViewById(R.id.txtDupLocationBadge)
        val checkbox         : CheckBox = v.findViewById(R.id.checkDupFile)
    }

    companion object {
        private const val VIEW_GROUP = 0
        private const val VIEW_FILE  = 1
    }
}
