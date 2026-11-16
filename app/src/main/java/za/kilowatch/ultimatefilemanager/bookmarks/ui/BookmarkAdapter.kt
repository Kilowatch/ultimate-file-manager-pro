package za.kilowatch.ultimatefilemanager.bookmarks.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkContentType
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkEntity
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class BookmarkRowItem {
    data class Header(val groupName: String, val count: Int) : BookmarkRowItem()
    data class Bookmark(val entity: BookmarkEntity) : BookmarkRowItem()
}

class BookmarkAdapter(
    private val context: Context,
    var isGrid: Boolean = false,
    private val onItemClick: (BookmarkEntity) -> Unit,
    private val onItemLongClick: (BookmarkEntity) -> Unit,
    private val onStarToggle: (BookmarkEntity) -> Unit,
    private val onActionReadArticle: (BookmarkEntity) -> Unit,
    private val onActionOpenBrowser: (BookmarkEntity) -> Unit,
    private val onActionCopy: (BookmarkEntity) -> Unit,
    private val onActionSaveStorage: (BookmarkEntity) -> Unit,
    private val onActionMoveGroup: (BookmarkEntity) -> Unit,
    private val onActionViewNote: (BookmarkEntity) -> Unit,
    private val onActionDelete: (BookmarkEntity) -> Unit
) : ListAdapter<BookmarkRowItem, RecyclerView.ViewHolder>(BookmarkRowDiffCallback()) {

    val selectedIds = mutableSetOf<String>()
    var isSelectionMode = false

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is BookmarkRowItem.Header -> VIEW_TYPE_HEADER
            is BookmarkRowItem.Bookmark -> if (isGrid) VIEW_TYPE_GRID else VIEW_TYPE_LIST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_bookmark_group_header, parent, false)
                HeaderViewHolder(view)
            }
            VIEW_TYPE_GRID -> {
                val view = inflater.inflate(R.layout.item_bookmark_card_grid, parent, false)
                BookmarkViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_bookmark_card, parent, false)
                BookmarkViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BookmarkRowItem.Header -> (holder as HeaderViewHolder).bind(item)
            is BookmarkRowItem.Bookmark -> (holder as BookmarkViewHolder).bind(item.entity)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtGroupName: TextView = itemView.findViewById(R.id.txtGroupName)
        private val txtGroupCount: TextView = itemView.findViewById(R.id.txtGroupCount)

        fun bind(header: BookmarkRowItem.Header) {
            txtGroupName.text = header.groupName
            txtGroupCount.text = "${header.count}"
        }
    }

    inner class BookmarkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardBookmark: MaterialCardView = itemView.findViewById(R.id.cardBookmark)
        private val iconContainer: View? = itemView.findViewById(R.id.iconContainer)
        private val imgThumbnail: ImageView = itemView.findViewById(R.id.imgThumbnail)
        private val imgSourceAppBadge: ImageView = itemView.findViewById(R.id.imgSourceAppBadge)
        private val txtTitle: TextView = itemView.findViewById(R.id.txtTitle)
        private val txtSubtitle: TextView = itemView.findViewById(R.id.txtSubtitle)
        private val txtNotes: TextView? = itemView.findViewById(R.id.txtNotes)
        private val txtGroupBadge: TextView = itemView.findViewById(R.id.txtGroupBadge)
        private val txtReaderBadge: TextView? = itemView.findViewById(R.id.txtReaderBadge)
        private val btnStar: ImageView = itemView.findViewById(R.id.btnStar)
        private val btnOverflow: ImageView = itemView.findViewById(R.id.btnOverflow)

        fun bind(item: BookmarkEntity) {
            val isSelected = selectedIds.contains(item.id)

            // Selection styling
            if (isSelected) {
                val primaryColor = za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(context)
                cardBookmark.strokeColor = primaryColor
                cardBookmark.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
                cardBookmark.setCardBackgroundColor(context.getColor(R.color.mobile_glass_card_thick))
            } else {
                cardBookmark.strokeColor = context.getColor(R.color.mobile_glass_stroke)
                cardBookmark.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
                cardBookmark.setCardBackgroundColor(context.getColor(R.color.mobile_glass_card))
            }

            txtTitle.text = item.title

            // Subtitle formatting
            val dateStr = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(item.createdAt))
            val domainOrSize = when {
                !item.url.isNullOrBlank() -> extractDomain(item.url)
                item.fileSizeBytes > 0 -> android.text.format.Formatter.formatFileSize(context, item.fileSizeBytes)
                else -> item.sourceAppName
            }
            txtSubtitle.text = if (domainOrSize.isNotEmpty()) "$domainOrSize • $dateStr" else dateStr

            if (!item.notes.isNullOrBlank()) {
                txtNotes?.text = item.notes
                txtNotes?.visibility = View.VISIBLE
            } else {
                txtNotes?.visibility = View.GONE
            }

            txtGroupBadge.text = item.groupName

            // Reader badge (visible if article text parsed)
            txtReaderBadge?.visibility = if (!item.cachedArticleText.isNullOrBlank()) View.VISIBLE else View.GONE

            // Star visual state
            if (item.isStarred) {
                btnStar.setImageResource(R.drawable.ic_star)
                btnStar.setColorFilter(Color.parseColor("#FFD700"))
            } else {
                btnStar.setImageResource(R.drawable.ic_star)
                btnStar.setColorFilter(context.getColor(R.color.mobile_icon_tint))
            }

            // Thumbnail loading adhering to UFMStandard
            loadThumbnail(item)

            // Source app badge loading (anchored bottom-right)
            loadSourceBadge(item.sourcePackage)

            // Click listeners
            cardBookmark.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(item.id)
                } else {
                    onItemClick(item)
                }
            }

            cardBookmark.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    toggleSelection(item.id)
                } else {
                    toggleSelection(item.id)
                }
                onItemLongClick(item)
                true
            }

            btnStar.setOnClickListener {
                onStarToggle(item)
            }

            btnOverflow.setOnClickListener { anchor ->
                showUfmGlassCardMenu(anchor, item)
            }
        }

        private fun toggleSelection(id: String) {
            if (selectedIds.contains(id)) {
                selectedIds.remove(id)
            } else {
                selectedIds.add(id)
            }
            notifyItemChanged(bindingAdapterPosition)
        }

        private fun loadThumbnail(item: BookmarkEntity) {
            val thumbPath = item.thumbnailPath
            val density = context.resources.displayMetrics.density

            if (!thumbPath.isNullOrBlank() && (File(thumbPath).exists() || thumbPath.startsWith("http"))) {
                // Real image/video thumbnail: clear squircle background & padding, apply squircle corner outline
                iconContainer?.setBackgroundResource(0)
                imgThumbnail.setPadding(0, 0, 0, 0)
                imgThumbnail.clearColorFilter()
                imgThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                imgThumbnail.clipToOutline = true
                imgThumbnail.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val radius = 10f * density
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                if (File(thumbPath).exists()) {
                    imgThumbnail.load(File(thumbPath))
                } else {
                    imgThumbnail.load(thumbPath)
                }
            } else if (!item.faviconPath.isNullOrBlank() && File(item.faviconPath).exists()) {
                // Favicon: squircle bubble background with padding
                iconContainer?.setBackgroundResource(R.drawable.bg_squircle_file_bubble)
                val pad = (8 * density).toInt()
                imgThumbnail.setPadding(pad, pad, pad, pad)
                imgThumbnail.clearColorFilter()
                imgThumbnail.scaleType = ImageView.ScaleType.FIT_CENTER
                imgThumbnail.clipToOutline = false
                imgThumbnail.load(File(item.faviconPath))
            } else {
                // Default content type icon: centered in squircle bubble with accent tint
                iconContainer?.setBackgroundResource(R.drawable.bg_squircle_file_bubble)
                val pad = (10 * density).toInt()
                imgThumbnail.setPadding(pad, pad, pad, pad)
                val defaultIcon = when (item.contentType) {
                    BookmarkContentType.LINK -> R.drawable.ic_link
                    BookmarkContentType.IMAGE -> R.drawable.ic_file_image
                    BookmarkContentType.VIDEO -> R.drawable.ic_file_video
                    BookmarkContentType.AUDIO -> R.drawable.ic_file_audio
                    BookmarkContentType.DOCUMENT -> R.drawable.ic_file_pdf
                    BookmarkContentType.TEXT_NOTE -> R.drawable.ic_notepad
                    else -> R.drawable.ic_bookmark_hub
                }
                imgThumbnail.setImageResource(defaultIcon)
                imgThumbnail.scaleType = ImageView.ScaleType.FIT_CENTER
                imgThumbnail.clipToOutline = false
                imgThumbnail.setColorFilter(context.getColor(R.color.mobile_icon_tint))
            }
        }

        private fun loadSourceBadge(pkg: String) {
            if (pkg.isNotBlank()) {
                try {
                    val icon = context.packageManager.getApplicationIcon(pkg)
                    imgSourceAppBadge.setImageDrawable(icon)
                    imgSourceAppBadge.visibility = View.VISIBLE
                } catch (_: Exception) {
                    imgSourceAppBadge.visibility = View.GONE
                }
            } else {
                imgSourceAppBadge.visibility = View.GONE
            }
        }

        private fun showUfmGlassCardMenu(anchor: View, item: BookmarkEntity) {
            val popupView = LayoutInflater.from(context).inflate(R.layout.popup_bookmark_card_menu, null)
            val popupWidth = (215 * context.resources.displayMetrics.density).toInt()
            val popupWindow = PopupWindow(
                popupView,
                popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = 16f * context.resources.displayMetrics.density
                isOutsideTouchable = true
                isFocusable = true
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                animationStyle = android.R.style.Animation_Dialog
            }

            val isUrl = !item.url.isNullOrBlank()

            // Read Article
            val menuItemReadArticle = popupView.findViewById<View>(R.id.menuItemReadArticle)
            menuItemReadArticle.visibility = if (isUrl) View.VISIBLE else View.GONE
            menuItemReadArticle.setOnClickListener {
                popupWindow.dismiss()
                onActionReadArticle(item)
            }

            // Open in Browser
            val menuItemOpenBrowser = popupView.findViewById<View>(R.id.menuItemOpenBrowser)
            menuItemOpenBrowser.visibility = if (isUrl) View.VISIBLE else View.GONE
            menuItemOpenBrowser.setOnClickListener {
                popupWindow.dismiss()
                onActionOpenBrowser(item)
            }

            // Copy Link / Text
            val txtCopyLabel = popupView.findViewById<TextView>(R.id.txtCopyLabel)
            txtCopyLabel.setText(if (isUrl) R.string.bookmark_action_copy_link else R.string.bookmark_action_copy_text)
            popupView.findViewById<View>(R.id.menuItemCopy)?.setOnClickListener {
                popupWindow.dismiss()
                onActionCopy(item)
            }

            // Save to Storage
            val menuItemSaveToStorage = popupView.findViewById<View>(R.id.menuItemSaveToStorage)
            menuItemSaveToStorage.visibility = if (item.contentType != BookmarkContentType.TEXT_NOTE) View.VISIBLE else View.GONE
            menuItemSaveToStorage.setOnClickListener {
                popupWindow.dismiss()
                onActionSaveStorage(item)
            }

            // Move to Collection
            popupView.findViewById<View>(R.id.menuItemMoveGroup)?.setOnClickListener {
                popupWindow.dismiss()
                onActionMoveGroup(item)
            }

            // View Note
            val menuItemViewNote = popupView.findViewById<View>(R.id.menuItemViewNote)
            menuItemViewNote.visibility = if (!item.notes.isNullOrBlank()) View.VISIBLE else View.GONE
            menuItemViewNote.setOnClickListener {
                popupWindow.dismiss()
                onActionViewNote(item)
            }

            // Delete
            popupView.findViewById<View>(R.id.menuItemDelete)?.setOnClickListener {
                popupWindow.dismiss()
                onActionDelete(item)
            }

            val xOffset = -(popupWidth - anchor.width)
            popupWindow.showAsDropDown(anchor, xOffset, (4 * context.resources.displayMetrics.density).toInt())
        }

        private fun extractDomain(urlStr: String?): String {
            if (urlStr.isNullOrBlank()) return ""
            return try {
                URI(urlStr).host?.removePrefix("www.") ?: urlStr
            } catch (_: Exception) {
                urlStr
            }
        }
    }

    class BookmarkRowDiffCallback : DiffUtil.ItemCallback<BookmarkRowItem>() {
        override fun areItemsTheSame(oldItem: BookmarkRowItem, newItem: BookmarkRowItem): Boolean {
            return when {
                oldItem is BookmarkRowItem.Header && newItem is BookmarkRowItem.Header -> oldItem.groupName == newItem.groupName
                oldItem is BookmarkRowItem.Bookmark && newItem is BookmarkRowItem.Bookmark -> oldItem.entity.id == newItem.entity.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: BookmarkRowItem, newItem: BookmarkRowItem): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_LIST = 1
        const val VIEW_TYPE_GRID = 2
    }
}
