package za.kilowatch.ultimatefilemanager.storage

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R

/**
 * RecyclerView adapter for the batch rename preview list.
 *
 * Each row is a card with two labeled sections — the original name on top and
 * the new name (with the changed portion highlighted) on the bottom — plus a
 * sequence number vertically centered on the left and an optional conflict badge.
 */
class BatchRenamePreviewAdapter(
    private val isTv: Boolean = false
) : ListAdapter<PreviewItem, BatchRenamePreviewAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_rename_preview, parent, false)
        if (isTv) {
            view.minimumHeight = (parent.context.resources.displayMetrics.density * 84).toInt()
            view.isFocusable = true
            view.setBackgroundResource(R.drawable.selector_tv_list_item)
        }
        return ViewHolder(view, isTv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View, private val isTv: Boolean) : RecyclerView.ViewHolder(itemView) {
        private val txtIndex: TextView = itemView.findViewById(R.id.txtIndex)
        private val txtOriginalName: TextView = itemView.findViewById(R.id.txtOriginalName)
        private val txtResultName: TextView = itemView.findViewById(R.id.txtResultName)
        private val txtConflictBadge: TextView = itemView.findViewById(R.id.txtConflictBadge)
        private val cardView: LinearLayout = itemView.findViewById(R.id.cardPreview)
        private val cardOriginal: View = itemView.findViewById(R.id.cardOriginal)
        private val cardNew: View = itemView.findViewById(R.id.cardNew)

        fun bind(item: PreviewItem) {
            val context = itemView.context

            txtIndex.text = item.index.toString()

            val hasValue = !item.resultingName.isNullOrBlank()
            val isChanged = hasValue && item.resultingName != item.originalName
            txtOriginalName.text = item.originalName

            if (isChanged) {
                txtResultName.text = buildHighlighted(context, item.originalName, item.resultingName)
                cardNew.visibility = View.VISIBLE
            } else {
                cardNew.visibility = View.GONE
            }

            bindConflictBadge(context, item.conflict)
            applyColors(context, !isChanged)

            val density = context.resources.displayMetrics.density

            // 1. Main Big Outer Card background
            val outerBgColor = ContextCompat.getColor(context, if (isTv) R.color.tv_bg_card_dark else R.color.bg_glass_card)
            val outerStrokeColor = ContextCompat.getColor(context, R.color.tv_glass_white_10)

            val outerBg = GradientDrawable().apply {
                cornerRadius = density * 14f
                setColor(outerBgColor)
                setStroke((1 * density).toInt(), outerStrokeColor)
            }
            cardView.background = outerBg

            // 2. Two Small Inner Section Cards background
            val innerBgColor = ContextCompat.getColor(context, R.color.tv_glass_white_10)
            val innerStrokeColor = ContextCompat.getColor(context, R.color.tv_glass_white_10)

            val innerBgOriginal = GradientDrawable().apply {
                cornerRadius = density * 8f
                setColor(innerBgColor)
                setStroke((1 * density).toInt(), innerStrokeColor)
            }
            val innerBgNew = GradientDrawable().apply {
                cornerRadius = density * 8f
                setColor(innerBgColor)
                setStroke((1 * density).toInt(), innerStrokeColor)
            }

            cardOriginal.background = innerBgOriginal
            cardNew.background = innerBgNew

            if (isTv) {
                applyTvSizing()
            } else {
                itemView.setOnFocusChangeListener(null)
            }
        }

        private fun buildHighlighted(context: android.content.Context, original: String, result: String): android.text.Spannable {
            val highlight = BatchRenameDiff.compute(original, result)
            val spannable = SpannableString(result)
            if (highlight != null) {
                val accent = ContextCompat.getColor(
                    context,
                    if (isTv) R.color.tv_accent else R.color.ufm_primary
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    highlight.start, highlight.endExclusive,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    ForegroundColorSpan(accent),
                    highlight.start, highlight.endExclusive,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return spannable
        }

        private fun bindConflictBadge(context: android.content.Context, conflict: PreviewConflict?) {
            if (conflict == null) {
                txtConflictBadge.visibility = View.GONE
                return
            }

            val labelRes = when (conflict) {
                PreviewConflict.DUPLICATE -> R.string.batch_rename_conflict_duplicate
                PreviewConflict.INVALID_CHARS -> R.string.batch_rename_conflict_invalid
                PreviewConflict.COLLISION -> R.string.batch_rename_conflict_collision
            }
            val colorRes = when {
                conflict.isBlocking && isTv -> R.color.tv_error_red
                conflict.isBlocking -> R.color.ufm_error
                else -> R.color.ufm_progress_warning
            }

            txtConflictBadge.text = context.getString(labelRes)
            txtConflictBadge.setTextColor(
                ContextCompat.getColor(context, android.R.color.white)
            )

            val badgeBg = GradientDrawable()
            badgeBg.cornerRadius = context.resources.displayMetrics.density * 8f
            badgeBg.setColor(ContextCompat.getColor(context, colorRes))
            txtConflictBadge.background = badgeBg
            txtConflictBadge.visibility = View.VISIBLE
        }

        private fun applyColors(context: android.content.Context, isUnchanged: Boolean) {
            val secondary = ContextCompat.getColor(
                context, if (isTv) R.color.tv_text_secondary else R.color.mobile_text_secondary
            )

            val resultColor = when {
                isTv && isUnchanged -> R.color.tv_text_secondary
                isTv -> R.color.tv_text_primary
                isUnchanged -> R.color.mobile_text_secondary
                else -> R.color.mobile_card_text_primary
            }

            txtOriginalName.setTextColor(secondary)
            txtResultName.setTextColor(ContextCompat.getColor(context, resultColor))

            itemView.findViewById<TextView>(R.id.txtSectionOriginalLabel)
                .setTextColor(secondary)
            itemView.findViewById<TextView>(R.id.txtSectionNewLabel)
                .setTextColor(secondary)
        }

        private fun applyTvSizing() {
            txtIndex.textSize = 16f
            txtOriginalName.textSize = 16f
            txtResultName.textSize = 16f
            itemView.findViewById<TextView>(R.id.txtSectionOriginalLabel).textSize = 11f
            itemView.findViewById<TextView>(R.id.txtSectionNewLabel).textSize = 11f
            txtConflictBadge.textSize = 12f
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
