package za.kilowatch.ultimatefilemanager.settings

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
                za.kilowatch.ultimatefilemanager.storage.SortFilterSheet.FilterType.APKS      -> ctx.getString(R.string.filter_apks)
                else -> ctx.getString(R.string.filter_all)
            }
            txtSummary.text = "$modeName · $orderName · $filterName"

            // Network badge visibility
            imgNetwork?.visibility = if (entry.isNetwork) View.VISIBLE else View.GONE

            // Delete button
            btnDelete.contentDescription = ctx.getString(R.string.folder_sort_manager_delete_cd)
            btnDelete.setOnClickListener { onDelete(entry) }

            // TV focus handling
            if (isTv) {
                val accentCsl = ColorStateList.valueOf(ctx.getColor(R.color.tv_button_focused_yellow))
                val defaultCsl = ColorStateList.valueOf(ctx.getColor(R.color.tv_text_primary))
                btnDelete.setOnFocusChangeListener { _, hasFocus ->
                    btnDelete.imageTintList = if (hasFocus) accentCsl else defaultCsl
                }
            }
        }
    }
}
