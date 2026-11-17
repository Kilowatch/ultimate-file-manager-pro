package za.kilowatch.ultimatefilemanager.flatview

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.DefaultIconColorManager
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import java.util.Date

class FlatViewAdapter(
    private val isTv: Boolean,
    private val onCategoryToggle: (FlatViewItem.RootCategoryHeader) -> Unit,
    private val onSubfolderToggle: (FlatViewItem.SubfolderHeader) -> Unit,
    private val onOpenFolder: (String) -> Unit,
    private val onFileClick: (FlatViewItem.FileEntry, View) -> Unit,
    private val onFileLongClick: (FlatViewItem.FileEntry) -> Unit,
    private val onCategoryCheckChanged: (FlatViewItem.RootCategoryHeader, Boolean) -> Unit,
    private val onSubfolderCheckChanged: (FlatViewItem.SubfolderHeader, Boolean) -> Unit,
    private val onFileCheckChanged: (FlatViewItem.FileEntry, Boolean) -> Unit
) : ListAdapter<FlatViewItem, RecyclerView.ViewHolder>(DiffCallback) {

    var isEditMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    companion object {
        private const val VIEW_TYPE_ROOT_CATEGORY = 0
        private const val VIEW_TYPE_SUBFOLDER = 1
        private const val VIEW_TYPE_FILE = 2

        private val DiffCallback = object : DiffUtil.ItemCallback<FlatViewItem>() {
            override fun areItemsTheSame(oldItem: FlatViewItem, newItem: FlatViewItem): Boolean {
                return when {
                    oldItem is FlatViewItem.RootCategoryHeader && newItem is FlatViewItem.RootCategoryHeader ->
                        oldItem.categoryPath == newItem.categoryPath
                    oldItem is FlatViewItem.SubfolderHeader && newItem is FlatViewItem.SubfolderHeader ->
                        oldItem.folderPath == newItem.folderPath
                    oldItem is FlatViewItem.FileEntry && newItem is FlatViewItem.FileEntry ->
                        oldItem.fileIndex.path == newItem.fileIndex.path
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: FlatViewItem, newItem: FlatViewItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is FlatViewItem.RootCategoryHeader -> VIEW_TYPE_ROOT_CATEGORY
            is FlatViewItem.SubfolderHeader -> VIEW_TYPE_SUBFOLDER
            is FlatViewItem.FileEntry -> VIEW_TYPE_FILE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ROOT_CATEGORY -> {
                val layoutId = if (isTv) R.layout.item_flat_root_category_tv else R.layout.item_flat_root_category
                val view = inflater.inflate(layoutId, parent, false)
                RootCategoryViewHolder(view)
            }
            VIEW_TYPE_SUBFOLDER -> {
                val layoutId = if (isTv) R.layout.item_flat_subfolder_tv else R.layout.item_flat_subfolder
                val view = inflater.inflate(layoutId, parent, false)
                SubfolderViewHolder(view)
            }
            VIEW_TYPE_FILE -> {
                val layoutId = if (isTv) R.layout.item_flat_file_tv else R.layout.item_flat_file
                val view = inflater.inflate(layoutId, parent, false)
                FileViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is FlatViewItem.RootCategoryHeader -> (holder as RootCategoryViewHolder).bind(item)
            is FlatViewItem.SubfolderHeader -> (holder as SubfolderViewHolder).bind(item)
            is FlatViewItem.FileEntry -> (holder as FileViewHolder).bind(item)
        }
    }

    inner class RootCategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRootCategory: MaterialCardView? = itemView.findViewById(R.id.cardRootCategory)
        private val layoutCategoryHeader: View = itemView.findViewById(R.id.layoutCategoryHeader)
        private val checkSelectCategory: AppCompatCheckBox? = itemView.findViewById(R.id.checkSelectCategory)
        private val imgCategoryIcon: ImageView = itemView.findViewById(R.id.imgCategoryIcon)
        private val txtCategoryTitle: TextView = itemView.findViewById(R.id.txtCategoryTitle)
        private val txtCategorySubtitle: TextView? = itemView.findViewById(R.id.txtCategorySubtitle)
        private val txtCategoryCountPill: TextView? = itemView.findViewById(R.id.txtCategoryCountPill)
        private val btnOpenCategory: ImageView? = itemView.findViewById(R.id.btnOpenCategory)
        private val imgCategoryChevron: ImageView = itemView.findViewById(R.id.imgCategoryChevron)

        fun bind(item: FlatViewItem.RootCategoryHeader) {
            val context = itemView.context
            txtCategoryTitle.text = item.categoryName

            val filesText = if (item.totalFiles == 1) {
                context.getString(R.string.main_menu_section_item_count_single)
            } else {
                context.getString(R.string.main_menu_section_items_count, item.totalFiles)
            }

            if (item.isExpanded) {
                // Highlight header when expanded (like main menu categories)
                if (!isTv) {
                    layoutCategoryHeader.setBackgroundResource(R.drawable.bg_category_header_highlight)
                }
                txtCategorySubtitle?.visibility = View.VISIBLE
                txtCategorySubtitle?.text = filesText
                txtCategoryCountPill?.visibility = View.GONE
                imgCategoryChevron.setImageResource(R.drawable.ic_flat_collapse_all)
            } else {
                // Unhighlight header when collapsed
                if (!isTv) {
                    layoutCategoryHeader.background = null
                }
                txtCategorySubtitle?.visibility = View.GONE
                txtCategoryCountPill?.visibility = View.VISIBLE
                txtCategoryCountPill?.text = filesText
                imgCategoryChevron.setImageResource(R.drawable.ic_chevron_right)
            }

            if (isEditMode) {
                checkSelectCategory?.visibility = View.VISIBLE
                checkSelectCategory?.isChecked = item.isSelected
                checkSelectCategory?.setOnClickListener {
                    val target = checkSelectCategory.isChecked
                    onCategoryCheckChanged(item, target)
                }
            } else {
                checkSelectCategory?.visibility = View.GONE
                checkSelectCategory?.setOnClickListener(null)
            }

            layoutCategoryHeader.setOnClickListener {
                if (isEditMode) {
                    val target = !item.isSelected
                    checkSelectCategory?.isChecked = target
                    onCategoryCheckChanged(item, target)
                } else {
                    onCategoryToggle(item)
                }
            }

            btnOpenCategory?.setOnClickListener {
                onOpenFolder(item.categoryPath)
            }
        }
    }

    inner class SubfolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardSubfolder: MaterialCardView? = itemView.findViewById(R.id.cardSubfolder)
        private val layoutSubfolderRow: View = itemView.findViewById(R.id.layoutSubfolderRow)
        private val checkSelectSubfolder: AppCompatCheckBox? = itemView.findViewById(R.id.checkSelectSubfolder)
        private val imgSubfolderIcon: ImageView = itemView.findViewById(R.id.imgSubfolderIcon)
        private val txtSubfolderName: TextView = itemView.findViewById(R.id.txtSubfolderName)
        private val txtSubfolderCount: TextView = itemView.findViewById(R.id.txtSubfolderCount)
        private val btnOpenSubfolder: ImageView? = itemView.findViewById(R.id.btnOpenSubfolder)
        private val imgSubfolderChevron: ImageView = itemView.findViewById(R.id.imgSubfolderChevron)

        fun bind(item: FlatViewItem.SubfolderHeader) {
            val context = itemView.context
            // Displays stripped subfolder name (e.g. "Hello")
            txtSubfolderName.text = item.subfolderDisplayName
            txtSubfolderCount.text = context.getString(R.string.flat_view_folder_files_count, item.fileCount)

            if (item.isExpanded) {
                imgSubfolderChevron.setImageResource(R.drawable.ic_flat_collapse_all)
            } else {
                imgSubfolderChevron.setImageResource(R.drawable.ic_chevron_right)
            }

            if (isEditMode) {
                checkSelectSubfolder?.visibility = View.VISIBLE
                checkSelectSubfolder?.isChecked = item.isSelected
                checkSelectSubfolder?.setOnClickListener {
                    val target = checkSelectSubfolder.isChecked
                    onSubfolderCheckChanged(item, target)
                }
            } else {
                checkSelectSubfolder?.visibility = View.GONE
                checkSelectSubfolder?.setOnClickListener(null)
            }

            layoutSubfolderRow.setOnClickListener {
                if (isEditMode) {
                    val target = !item.isSelected
                    checkSelectSubfolder?.isChecked = target
                    onSubfolderCheckChanged(item, target)
                } else {
                    onSubfolderToggle(item)
                }
            }

            btnOpenSubfolder?.setOnClickListener {
                onOpenFolder(item.folderPath)
            }
        }
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val layoutFileRow: View = itemView.findViewById(R.id.layoutFileRow)
        private val checkSelectFile: AppCompatCheckBox = itemView.findViewById(R.id.checkSelectFile)
        private val imgFileIcon: ImageView = itemView.findViewById(R.id.imgFileIcon)
        private val txtFileName: TextView = itemView.findViewById(R.id.txtFileName)
        private val txtFileInfo: TextView = itemView.findViewById(R.id.txtFileInfo)

        fun bind(item: FlatViewItem.FileEntry) {
            val file = item.fileIndex
            val context = itemView.context

            txtFileName.text = file.filename

            val formattedSize = Formatter.formatFileSize(context, file.size)
            val formattedDate = DateFormat.getDateFormat(context).format(Date(file.lastModified))
            txtFileInfo.text = "$formattedSize • $formattedDate"

            // Icon resolution
            val iconRes = resolveFileIcon(file.filename, file.mimeType)
            imgFileIcon.setImageResource(iconRes)
            val iconTint = if (isTv) {
                DefaultIconColorManager.getTvIconTint(context)
            } else {
                DefaultIconColorManager.getMobileIconTint(context)
            }
            imgFileIcon.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)

            if (isEditMode) {
                checkSelectFile.visibility = View.VISIBLE
                checkSelectFile.isChecked = item.isSelected
                checkSelectFile.setOnClickListener {
                    onFileCheckChanged(item, checkSelectFile.isChecked)
                }
            } else {
                checkSelectFile.visibility = View.GONE
                checkSelectFile.setOnClickListener(null)
            }

            layoutFileRow.setOnClickListener { v ->
                if (isEditMode) {
                    val target = !item.isSelected
                    checkSelectFile.isChecked = target
                    onFileCheckChanged(item, target)
                } else {
                    onFileClick(item, v)
                }
            }

            layoutFileRow.setOnLongClickListener {
                onFileLongClick(item)
                true
            }
        }
    }

    private fun resolveFileIcon(filename: String, mimeType: String?): Int {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when {
            mimeType?.startsWith("image/") == true || ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg") ->
                R.drawable.ic_file_image
            mimeType?.startsWith("video/") == true || ext in listOf("mp4", "mkv", "avi", "mov", "webm", "flv", "3gp") ->
                R.drawable.ic_file_video
            mimeType?.startsWith("audio/") == true || ext in listOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus") ->
                R.drawable.ic_file_audio
            ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") ->
                R.drawable.ic_file_archive
            ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt") ->
                R.drawable.ic_file_text
            ext in listOf("apk", "apks", "xapk") ->
                R.drawable.ic_file_apk
            else -> R.drawable.ic_file_generic
        }
    }
}
