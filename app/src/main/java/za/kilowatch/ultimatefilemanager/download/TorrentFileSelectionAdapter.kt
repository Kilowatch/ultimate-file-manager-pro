package za.kilowatch.ultimatefilemanager.download

import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import za.kilowatch.ultimatefilemanager.R

/**
 * Tree-aware RecyclerView adapter that renders a collapsible, checkable projection
 * of [TorrentFileNode]. Supports real-time text query filtering, category filtering,
 * depth indentation, and automatic parent/child selection synchronization.
 */
class TorrentFileSelectionAdapter(
    private val rootNode: TorrentFileNode,
    private val isTv: Boolean,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class CategoryFilter {
        ALL, VIDEOS, AUDIO, IMAGES, ARCHIVES, DOCS
    }

    data class VisibleItem(
        val node: TorrentFileNode,
        val depth: Int
    )

    private val visibleItems = mutableListOf<VisibleItem>()
    private var searchQuery: String = ""
    private var activeCategory: CategoryFilter = CategoryFilter.ALL

    companion object {
        private const val VIEW_TYPE_FOLDER = 1
        private const val VIEW_TYPE_FILE = 2

        private val VIDEO_EXTS = setOf("mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "iso")
        private val AUDIO_EXTS = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "alac", "ape")
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "jxl", "avif")
        private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz")
        private val DOC_EXTS = setOf("srt", "sub", "vtt", "pdf", "txt", "nfo", "doc", "docx", "epub", "xml", "json")
    }

    init {
        setHasStableIds(true)
        rebuildVisibleList()
    }

    override fun getItemId(position: Int): Long {
        return visibleItems[position].node.id.toLong()
    }

    override fun getItemViewType(position: Int): Int {
        return if (visibleItems[position].node.isDirectory) VIEW_TYPE_FOLDER else VIEW_TYPE_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_FOLDER) {
            val view = inflater.inflate(R.layout.item_torrent_folder, parent, false)
            FolderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_torrent_file, parent, false)
            FileViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = visibleItems[position]
        if (holder is FolderViewHolder) {
            holder.bind(item.node, item.depth)
        } else if (holder is FileViewHolder) {
            holder.bind(item.node, item.depth)
        }
    }

    override fun getItemCount(): Int = visibleItems.size

    fun setSearchQuery(query: String) {
        val trimmed = query.trim()
        if (searchQuery != trimmed) {
            searchQuery = trimmed
            rebuildVisibleList()
        }
    }

    fun setCategoryFilter(category: CategoryFilter) {
        if (activeCategory != category) {
            activeCategory = category
            rebuildVisibleList()
        }
    }

    fun selectAll() {
        rootNode.setSelectionRecursive(true)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun deselectAll() {
        rootNode.setSelectionRecursive(false)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun rebuildVisibleList() {
        visibleItems.clear()
        val isFiltering = searchQuery.isNotEmpty() || activeCategory != CategoryFilter.ALL

        fun matchesFilter(node: TorrentFileNode): Boolean {
            if (!node.isDirectory) {
                val matchesQuery = searchQuery.isEmpty() || node.name.contains(searchQuery, ignoreCase = true)
                val ext = node.name.substringAfterLast('.', "").lowercase()
                val matchesCat = when (activeCategory) {
                    CategoryFilter.ALL -> true
                    CategoryFilter.VIDEOS -> ext in VIDEO_EXTS
                    CategoryFilter.AUDIO -> ext in AUDIO_EXTS
                    CategoryFilter.IMAGES -> ext in IMAGE_EXTS
                    CategoryFilter.ARCHIVES -> ext in ARCHIVE_EXTS
                    CategoryFilter.DOCS -> ext in DOC_EXTS
                }
                return matchesQuery && matchesCat
            } else {
                return node.children.any { matchesFilter(it) }
            }
        }

        fun collectNodes(node: TorrentFileNode, depth: Int) {
            // Don't show dummy root itself in the list if it has children; only show its children
            val isRoot = node === rootNode
            if (!isRoot) {
                if (isFiltering && !matchesFilter(node)) {
                    return
                }
                visibleItems.add(VisibleItem(node, depth))
            }

            if (node.isDirectory) {
                val shouldExpand = if (isFiltering) true else node.isExpanded
                if (isRoot || shouldExpand) {
                    val nextDepth = if (isRoot) 0 else depth + 1
                    for (child in node.children) {
                        collectNodes(child, nextDepth)
                    }
                }
            }
        }

        collectNodes(rootNode, 0)
        notifyDataSetChanged()
    }

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val viewIndent: View? = itemView.findViewById(R.id.viewIndent)
        private val btnExpandCollapse: ImageView? = itemView.findViewById(R.id.btnExpandCollapse)
        private val checkFolder: MaterialCheckBox? = itemView.findViewById(R.id.checkFolder)
        private val imgFolderIcon: ImageView? = itemView.findViewById(R.id.imgFolderIcon)
        private val txtFolderName: TextView? = itemView.findViewById(R.id.txtFolderName)
        private val txtFolderStats: TextView? = itemView.findViewById(R.id.txtFolderStats)

        fun bind(node: TorrentFileNode, depth: Int) {
            val ctx = itemView.context
            // Adjust indentation
            viewIndent?.layoutParams?.width = (depth * 18 * ctx.resources.displayMetrics.density).toInt()
            viewIndent?.requestLayout()

            txtFolderName?.text = node.name
            val leafCount = node.getLeafFileCount()
            val sizeStr = formatBytes(node.sizeBytes)
            txtFolderStats?.text = "$leafCount files • $sizeStr"

            // Expand / Collapse icon
            val isFiltering = searchQuery.isNotEmpty() || activeCategory != CategoryFilter.ALL
            btnExpandCollapse?.setImageResource(
                if (isFiltering || node.isExpanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right
            )

            btnExpandCollapse?.setOnClickListener {
                if (!isFiltering) {
                    node.isExpanded = !node.isExpanded
                    rebuildVisibleList()
                }
            }

            // Checkbox state (all, none, or partial)
            checkFolder?.checkedState = when {
                node.isAllSelected() -> MaterialCheckBox.STATE_CHECKED
                node.isPartiallySelected() -> MaterialCheckBox.STATE_INDETERMINATE
                else -> MaterialCheckBox.STATE_UNCHECKED
            }

            // Row click toggles selection of folder and all children
            itemView.setOnClickListener {
                val newState = !node.isAllSelected()
                node.setSelectionRecursive(newState)
                notifyDataSetChanged()
                onSelectionChanged()
            }

            // TV Focus styling: invert text and icon colors to dark on yellow focus
            if (isTv) {
                itemView.setOnFocusChangeListener { _, hasFocus ->
                    val textColor = ContextCompat.getColor(
                        ctx,
                        if (hasFocus) R.color.tv_button_focused_yellow_text else R.color.tv_text_primary
                    )
                    val subTextColor = ContextCompat.getColor(
                        ctx,
                        if (hasFocus) R.color.tv_button_focused_yellow_text else R.color.tv_text_secondary
                    )
                    val iconTint = ContextCompat.getColor(
                        ctx,
                        if (hasFocus) R.color.tv_button_focused_yellow_text else R.color.tv_accent
                    )

                    txtFolderName?.setTextColor(textColor)
                    txtFolderStats?.setTextColor(subTextColor)
                    btnExpandCollapse?.imageTintList = ColorStateList.valueOf(iconTint)
                    imgFolderIcon?.imageTintList = ColorStateList.valueOf(iconTint)
                    checkFolder?.buttonTintList = ColorStateList.valueOf(iconTint)
                }
                // Initial un-focused state
                txtFolderName?.setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_primary))
                txtFolderStats?.setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_secondary))
                btnExpandCollapse?.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.tv_accent))
                imgFolderIcon?.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.tv_accent))
                checkFolder?.buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.tv_accent))
            }

            // TV D-pad Left/Right arrow navigation to expand/collapse
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && !isFiltering) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !node.isExpanded) {
                        node.isExpanded = true
                        rebuildVisibleList()
                        return@setOnKeyListener true
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && node.isExpanded) {
                        node.isExpanded = false
                        rebuildVisibleList()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val viewIndent: View? = itemView.findViewById(R.id.viewIndent)
        private val checkFile: MaterialCheckBox? = itemView.findViewById(R.id.checkFile)
        private val imgFileIcon: ImageView? = itemView.findViewById(R.id.imgFileIcon)
        private val txtFileName: TextView? = itemView.findViewById(R.id.txtFileName)
        private val txtFileSize: TextView? = itemView.findViewById(R.id.txtFileSize)

        fun bind(node: TorrentFileNode, depth: Int) {
            val ctx = itemView.context
            // Adjust indentation
            viewIndent?.layoutParams?.width = (depth * 18 * ctx.resources.displayMetrics.density).toInt()
            viewIndent?.requestLayout()

            txtFileName?.text = node.name
            txtFileSize?.text = formatBytes(node.sizeBytes)

            // Resolve file icon based on extension
            val ext = node.name.substringAfterLast('.', "").lowercase()
            val iconRes = when {
                ext in VIDEO_EXTS -> R.drawable.ic_file_video
                ext in AUDIO_EXTS -> R.drawable.ic_file_audio
                ext in IMAGE_EXTS -> R.drawable.ic_file_image
                ext in ARCHIVE_EXTS -> R.drawable.ic_file_archive
                ext in setOf("srt", "sub", "vtt") -> R.drawable.ic_file_subtitle
                ext in setOf("pdf") -> R.drawable.ic_file_pdf
                ext in setOf("txt", "nfo", "doc", "docx", "epub") -> R.drawable.ic_file_text
                ext in setOf("xml", "json", "html", "js", "py", "kt") -> R.drawable.ic_file_code
                ext in setOf("iso") -> R.drawable.ic_file_iso
                ext in setOf("apk") -> R.drawable.ic_file_apk
                else -> R.drawable.ic_file_generic
            }
            imgFileIcon?.setImageResource(iconRes)

            checkFile?.isChecked = node.isSelected

            itemView.setOnClickListener {
                node.isSelected = !node.isSelected
                notifyDataSetChanged()
                onSelectionChanged()
            }

            // TV Focus styling: invert text and icon colors to dark on yellow focus
            if (isTv) {
                itemView.setOnFocusChangeListener { _, hasFocus ->
                    val textColor = ContextCompat.getColor(
                        ctx,
                        if (hasFocus) R.color.tv_button_focused_yellow_text else R.color.tv_text_primary
                    )
                    val subTextColor = ContextCompat.getColor(
                        ctx,
                        if (hasFocus) R.color.tv_button_focused_yellow_text else R.color.tv_text_secondary
                    )
                    val iconTint = ContextCompat.getColor(
                        ctx,
                        if (hasFocus) R.color.tv_button_focused_yellow_text else R.color.tv_icon_tint
                    )

                    txtFileName?.setTextColor(textColor)
                    txtFileSize?.setTextColor(subTextColor)
                    imgFileIcon?.imageTintList = ColorStateList.valueOf(iconTint)
                    checkFile?.buttonTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, if (hasFocus) R.color.tv_button_focused_yellow_text else R.color.tv_accent)
                    )
                }
                // Initial un-focused state
                txtFileName?.setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_primary))
                txtFileSize?.setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_secondary))
                imgFileIcon?.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.tv_icon_tint))
                checkFile?.buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.tv_accent))
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
