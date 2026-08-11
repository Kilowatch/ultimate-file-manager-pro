package za.kilowatch.ultimatefilemanager.storage

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R

/**
 * RecyclerView adapter for the batch rename preview list.
 *
 * Each row shows the file/folder icon and the resulting name after pattern resolution.
 * Uses [ListAdapter] with [DiffUtil] for efficient incremental updates.
 */
class BatchRenamePreviewAdapter(
    private val isTv: Boolean = false
) : ListAdapter<PreviewItem, BatchRenamePreviewAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_rename_preview, parent, false)
        if (isTv) {
            view.minimumHeight = (parent.context.resources.displayMetrics.density * 72).toInt()
            view.isFocusable = true
            view.setBackgroundResource(R.drawable.selector_tv_list_item)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val imgIcon: android.widget.ImageView = itemView.findViewById(R.id.imgIcon)
        private val txtOriginalName: android.widget.TextView = itemView.findViewById(R.id.txtOriginalName)
        private val txtResultName: android.widget.TextView = itemView.findViewById(R.id.txtResultName)

        fun bind(item: PreviewItem) {
            imgIcon.setImageResource(item.iconRes)

            val context = itemView.context
            val isError = item.resultingName.isEmpty()
            val isNoChange = isError || item.originalName == item.resultingName

            if (isNoChange) {
                // If there's no change (or error/empty pattern), show only one line without redundant duplication
                txtOriginalName.visibility = android.view.View.GONE
                txtResultName.text = item.originalName
                txtResultName.setTypeface(
                    txtResultName.typeface,
                    if (isError) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL
                )
            } else {
                txtOriginalName.visibility = android.view.View.VISIBLE
                txtOriginalName.text = item.originalName

                txtResultName.text = "→ ${item.resultingName}"
                txtResultName.setTypeface(txtResultName.typeface, android.graphics.Typeface.BOLD)
            }

            if (isTv) {
                val black = ContextCompat.getColor(context, R.color.tv_button_focused_yellow_text)
                val white = ContextCompat.getColor(context, R.color.tv_text_primary)
                val secondary = ContextCompat.getColor(context, R.color.tv_text_secondary)
                val accent = ContextCompat.getColor(context, R.color.tv_accent)
                val blackCsl = android.content.res.ColorStateList.valueOf(black)
                val accentCsl = android.content.res.ColorStateList.valueOf(accent)

                itemView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        txtOriginalName.setTextColor(black)
                        txtResultName.setTextColor(black)
                        imgIcon.imageTintList = blackCsl
                    } else {
                        txtOriginalName.setTextColor(secondary)
                        txtResultName.setTextColor(if (isNoChange) secondary else white)
                        imgIcon.imageTintList = accentCsl
                    }
                }

                val hasFocus = itemView.hasFocus()
                txtOriginalName.setTextColor(if (hasFocus) black else secondary)
                txtResultName.setTextColor(if (hasFocus) black else (if (isNoChange) secondary else white))
                imgIcon.imageTintList = if (hasFocus) blackCsl else accentCsl
            } else {
                txtOriginalName.setTextColor(ContextCompat.getColor(context, R.color.mobile_text_secondary))
                val colorRes = if (isNoChange) R.color.mobile_text_secondary else R.color.mobile_card_text_primary
                txtResultName.setTextColor(ContextCompat.getColor(context, colorRes))
                imgIcon.imageTintList = null
                itemView.setOnFocusChangeListener(null)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<PreviewItem>() {
        override fun areItemsTheSame(oldItem: PreviewItem, newItem: PreviewItem): Boolean {
            return oldItem.originalName == newItem.originalName
        }

        override fun areContentsTheSame(oldItem: PreviewItem, newItem: PreviewItem): Boolean {
            return oldItem == newItem
        }
    }
}
