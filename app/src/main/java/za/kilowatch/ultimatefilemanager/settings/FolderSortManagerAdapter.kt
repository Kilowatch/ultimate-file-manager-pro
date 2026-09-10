package za.kilowatch.ultimatefilemanager.settings

import android.graphics.Color
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
import za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager

/**
 * RecyclerView adapter for [FolderSortManagerActivity].
 * Displays each [SortFilterPreferenceManager.FolderSortEntry] with its path,
 * a short settings summary, a network badge, and a delete action.
 */
class FolderSortManagerAdapter(
    private val isTv: Boolean,
    private val onDelete: (SortFilterPreferenceManager.FolderSortEntry) -> Unit
) : ListAdapter<SortFilterPreferenceManager.FolderSortEntry, FolderSortManagerAdapter.EntryViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SortFilterPreferenceManager.FolderSortEntry>() {
            override fun areItemsTheSame(
                a: SortFilterPreferenceManager.FolderSortEntry,
                b: SortFilterPreferenceManager.FolderSortEntry
            ) = a.key == b.key

            override fun areContentsTheSame(
                a: SortFilterPreferenceManager.FolderSortEntry,
                b: SortFilterPreferenceManager.FolderSortEntry
            ) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val layout = if (isTv) R.layout.item_folder_sort_entry_tv else R.layout.item_folder_sort_entry
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return EntryViewHolder(view)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val txtPath: TextView = itemView.findViewById(R.id.txtFolderPath)
        private val txtSummary: TextView = itemView.findViewById(R.id.txtSortSummary)
        private val imgNetwork: View? = itemView.findViewById(R.id.imgNetworkBadge)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDeleteFolderSort)
        private val btnDeleteContainer: View? = itemView.findViewById(R.id.btnDeleteContainer)
        private val card: MaterialCardView = itemView.findViewById(R.id.cardFolderSort)

        fun bind(entry: SortFilterPreferenceManager.FolderSortEntry) {
            val ctx = itemView.context

            txtPath.text = entry.displayPath

            // Build a short human-readable summary of the active settings
            val modeName = when (entry.state.sortMode) {
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.NAME -> ctx.getString(R.string.sort_by_name)
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.SIZE -> ctx.getString(R.string.sort_by_size)
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.DATE -> ctx.getString(R.string.sort_by_date)
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortMode.TYPE -> ctx.getString(R.string.sort_by_type)
            }
            val orderName = if (entry.state.sortOrder == za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.SortOrder.ASC)
                ctx.getString(R.string.folder_sort_manager_settings_summary_asc)
            else
                ctx.getString(R.string.folder_sort_manager_settings_summary_desc)
            val filterName = when (entry.state.filterType) {
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.ALL       -> ctx.getString(R.string.filter_all)
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.IMAGES    -> ctx.getString(R.string.filter_images)
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.VIDEOS    -> ctx.getString(R.string.filter_videos)
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.AUDIO     -> ctx.getString(R.string.filter_audio)
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.DOCUMENTS -> ctx.getString(R.string.filter_documents)
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.ARCHIVES  -> ctx.getString(R.string.filter_archives)
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.APKS      -> ctx.getString(R.string.filter_apks)
                else -> ctx.getString(R.string.filter_all)
            }
            txtSummary.text = "$modeName · $orderName · $filterName"

            // Network badge visibility
            imgNetwork?.visibility = if (entry.isNetwork) View.VISIBLE else View.GONE

            // Delete action
            val deleteAction = { onDelete(entry) }
            btnDelete.contentDescription = ctx.getString(R.string.folder_sort_manager_delete_cd)
            btnDelete.setOnClickListener { deleteAction() }
            btnDeleteContainer?.setOnClickListener { deleteAction() }

            if (isTv) {
                card.setOnClickListener { deleteAction() }
                setupTvCardFocus(card, btnDelete)
            } else {
                card.setCardBackgroundColor(ctx.getColor(R.color.mobile_glass_card))
                txtPath.setTextColor(ctx.getColor(R.color.mobile_card_text_primary))
                txtSummary.setTextColor(ctx.getColor(R.color.mobile_text_secondary))
            }
        }

        private fun setupTvCardFocus(card: MaterialCardView, btnDelete: ImageView) {
            val ctx = itemView.context
            val yellowFill  = ColorblindPalette.focusFill(card.context)
            val blackText   = ColorblindPalette.focusFillText(card.context)
            val glassColor  = ctx.getColor(R.color.tv_glass_white_10)
            val primaryText = ctx.getColor(R.color.tv_text_primary)
            val secondText  = ctx.getColor(R.color.tv_text_secondary)

            // Initial
            card.setCardBackgroundColor(glassColor)
            txtPath.setTextColor(primaryText)
            txtSummary.setTextColor(secondText)
            btnDelete.setColorFilter(secondText)

            card.setOnFocusChangeListener { _, hasFocus ->
                // The FR-06 marker is NOT assigned here. This card declares
                // `@drawable/selector_tv_card` as its XML foreground, and that selector's
                // focused state already carries the marker. Writing to `foreground` would
                // overwrite the whole selector, and since `marker()` returns null on blur
                // the card would permanently lose its glow rings and accent border the
                // first time it was focused -- with Colorblind Mode Off (FR-19).
                if (hasFocus) {
                    card.setCardBackgroundColor(yellowFill)
                    txtPath.setTextColor(blackText)
                    txtSummary.setTextColor(Color.parseColor("#333333"))
                    btnDelete.setColorFilter(blackText)
                } else {
                    card.setCardBackgroundColor(glassColor)
                    txtPath.setTextColor(primaryText)
                    txtSummary.setTextColor(secondText)
                    btnDelete.setColorFilter(secondText)
                }
            }

            btnDelete.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    btnDelete.setColorFilter(ColorblindPalette.tvErrorRed(card.context))
                } else {
                    if (card.hasFocus()) {
                        btnDelete.setColorFilter(blackText)
                    } else {
                        btnDelete.setColorFilter(secondText)
                    }
                }
            }
        }
    }
}
