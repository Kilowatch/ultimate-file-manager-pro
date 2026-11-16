package za.kilowatch.ultimatefilemanager.bookmarks.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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

class BookmarkAdapter(
    private val context: Context,
    var isGrid: Boolean = false,
    private val onItemClick: (BookmarkEntity) -> Unit,
    private val onItemLongClick: (BookmarkEntity) -> Unit,
    private val onStarToggle: (BookmarkEntity) -> Unit,
    private val onOverflowClick: (BookmarkEntity, View) -> Unit
) : ListAdapter<BookmarkEntity, BookmarkAdapter.BookmarkViewHolder>(BookmarkDiffCallback()) {

    val selectedIds = mutableSetOf<String>()
    var isSelectionMode = false

    override fun getItemViewType(position: Int): Int {
        return if (isGrid) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_GRID) R.layout.item_bookmark_card_grid else R.layout.item_bookmark_card
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return BookmarkViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BookmarkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardBookmark: MaterialCardView = itemView.findViewById(R.id.cardBookmark)
        private val imgThumbnail: ImageView = itemView.findViewById(R.id.imgThumbnail)
        private val imgSourceAppBadge: ImageView = itemView.findViewById(R.id.imgSourceAppBadge)
        private val txtTitle: TextView = itemView.findViewById(R.id.txtTitle)
        private val txtSubtitle: TextView = itemView.findViewById(R.id.txtSubtitle)
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

            // Thumbnail loading
            loadThumbnail(item)

            // Source app badge loading
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
                onOverflowClick(item, anchor)
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
            if (!thumbPath.isNullOrBlank() && File(thumbPath).exists()) {
                imgThumbnail.load(File(thumbPath))
            } else if (!item.faviconPath.isNullOrBlank() && File(item.faviconPath).exists()) {
                imgThumbnail.load(File(item.faviconPath))
            } else {
                // Content type default icons
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

        private fun extractDomain(urlStr: String?): String {
            if (urlStr.isNullOrBlank()) return ""
            return try {
                URI(urlStr).host?.removePrefix("www.") ?: urlStr
            } catch (_: Exception) {
                urlStr
            }
        }
    }

    class BookmarkDiffCallback : DiffUtil.ItemCallback<BookmarkEntity>() {
        override fun areItemsTheSame(oldItem: BookmarkEntity, newItem: BookmarkEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: BookmarkEntity, newItem: BookmarkEntity): Boolean = oldItem == newItem
    }

    companion object {
        const val VIEW_TYPE_LIST = 1
        const val VIEW_TYPE_GRID = 2
    }
}
