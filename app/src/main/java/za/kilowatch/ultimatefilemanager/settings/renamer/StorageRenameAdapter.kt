package za.kilowatch.ultimatefilemanager.settings.renamer

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.StorageItem
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

data class RenameListItem(
    val deviceId: String,
    val displayTitle: String,
    val displaySubtitle: String,
    val sizeBytes: Long,
    val isOnline: Boolean,
    val iconRes: Int
)

class StorageRenameAdapter(
    private val isTv: Boolean,
    private val onClick: (RenameListItem) -> Unit
) : ListAdapter<RenameListItem, StorageRenameAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_storage_rename, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardItem: MaterialCardView = itemView.findViewById(R.id.cardItem)
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgIcon)
        private val txtTitle: TextView = itemView.findViewById(R.id.txtTitle)
        private val txtSubtitle: TextView = itemView.findViewById(R.id.txtSubtitle)
        private val txtStatus: TextView = itemView.findViewById(R.id.txtStatus)
        private val txtSize: TextView = itemView.findViewById(R.id.txtSize)

        fun bind(item: RenameListItem) {
            val context = itemView.context
            
            txtTitle.text = item.displayTitle
            if (item.displaySubtitle.isNotEmpty()) {
                txtSubtitle.visibility = View.VISIBLE
                txtSubtitle.text = item.displaySubtitle
            } else {
                txtSubtitle.visibility = View.GONE
            }
            
            imgIcon.setImageResource(item.iconRes)
            
            val formattedSize = Formatter.formatFileSize(context, item.sizeBytes)
            txtSize.text = formattedSize
            
            if (item.isOnline) {
                txtStatus.text = context.getString(R.string.status_online)
                txtStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.ufm_success))
                imgIcon.alpha = 1.0f
                txtTitle.alpha = 1.0f
            } else {
                txtStatus.text = context.getString(R.string.status_offline)
                txtStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.ufm_error))
                imgIcon.alpha = 0.5f
                txtTitle.alpha = 0.7f
            }

            if (isTv) {
                setupTvCardFocus(cardItem, context)
            }
            
            cardItem.setOnClickListener {
                onClick(item)
            }
        }

        private fun setupTvCardFocus(card: MaterialCardView, context: Context) {
            val yellowFill = context.getColor(R.color.tv_button_focused_yellow)
            val blackText = context.getColor(R.color.tv_button_focused_yellow_text)
            val glassColor = context.getColor(R.color.tv_glass_white_10)
            val primaryText = context.getColor(R.color.tv_text_primary)
            val secondText = context.getColor(R.color.tv_text_secondary)
            val badgeTintNormal = if (getItem(adapterPosition).isOnline) context.getColor(R.color.ufm_success) else context.getColor(R.color.ufm_error)

            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    card.setCardBackgroundColor(yellowFill)
                    txtTitle.setTextColor(blackText)
                    txtSubtitle.setTextColor(blackText)
                    txtSize.setTextColor(blackText)
                    imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(blackText)
                    txtStatus.setTextColor(yellowFill)
                    txtStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(blackText)
                } else {
                    card.setCardBackgroundColor(glassColor)
                    txtTitle.setTextColor(primaryText)
                    txtSubtitle.setTextColor(secondText)
                    txtSize.setTextColor(secondText)
                    imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(primaryText)
                    txtStatus.setTextColor(context.getColor(android.R.color.white))
                    txtStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(badgeTintNormal)
                }
            }
            
            // Initial state
            card.setCardBackgroundColor(glassColor)
            txtTitle.setTextColor(primaryText)
            txtSubtitle.setTextColor(secondText)
            txtSize.setTextColor(secondText)
            imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(primaryText)
            txtStatus.setTextColor(context.getColor(android.R.color.white))
            txtStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(badgeTintNormal)
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<RenameListItem>() {
        override fun areItemsTheSame(oldItem: RenameListItem, newItem: RenameListItem): Boolean {
            return oldItem.deviceId == newItem.deviceId
        }

        override fun areContentsTheSame(oldItem: RenameListItem, newItem: RenameListItem): Boolean {
            return oldItem == newItem
        }
    }
}
