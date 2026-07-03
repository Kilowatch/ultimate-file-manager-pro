package za.kilowatch.ultimatefilemanager.viewer

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R

/**
 * Adapter for the playback queue RecyclerView with drag-to-reorder and swipe-to-remove.
 */
class QueueAdapter(
    private var items: MutableList<QueueItem>,
    private val currentIndex: Int,
    private val onItemClick: (Int) -> Unit,
    private val onItemMove: (Int, Int) -> Unit,
    private val onItemDismiss: (Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    /** Touch helper for drag-to-reorder and swipe-to-dismiss. */
    private var itemTouchHelper: ItemTouchHelper? = null

    fun setItemTouchHelper(helper: ItemTouchHelper) {
        itemTouchHelper = helper
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.dialog_queue_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isCurrent = position == currentIndex

        holder.txtTitle.text = item.title ?: item.path.substringAfterLast("/")
        holder.txtTrackIndex.text = "${position + 1}"
        holder.txtDuration.text = if (item.duration > 0) formatDuration(item.duration) else ""

        // Playing indicator
        holder.imgPlayingIndicator.visibility = if (isCurrent) View.VISIBLE else View.GONE
        holder.txtTitle.setTextColor(
            if (isCurrent) ContextCompat.getColor(holder.itemView.context, R.color.ufm_primary)
            else ContextCompat.getColor(holder.itemView.context, android.R.color.white)
        )

        // Drag handle — start drag on touch
        holder.imgDragHandle.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                itemTouchHelper?.startDrag(holder)
            }
            false
        }

        holder.itemView.setOnClickListener { onItemClick(position) }
    }

    override fun getItemCount(): Int = items.size

    /** Update the data set and current index. */
    fun updateData(newItems: MutableList<QueueItem>, newIndex: Int) {
        items = newItems
        notifyDataSetChanged()
    }

    /** Get current items for drag state. */
    fun getItems(): MutableList<QueueItem> = items

    /** Swap items for drag-to-reorder. */
    fun onItemMove(from: Int, to: Int): Boolean {
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
        onItemMove(from, to)
        return true
    }

    /** Remove item for swipe-to-dismiss. */
    fun onItemDismiss(position: Int) {
        items.removeAt(position)
        notifyItemRemoved(position)
        onItemDismiss(position)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgDragHandle: ImageView = itemView.findViewById(R.id.imgDragHandle)
        val imgPlayingIndicator: ImageView = itemView.findViewById(R.id.imgPlayingIndicator)
        val txtTrackIndex: TextView = itemView.findViewById(R.id.txtTrackIndex)
        val txtTitle: TextView = itemView.findViewById(R.id.txtQueueItemTitle)
        val txtDuration: TextView = itemView.findViewById(R.id.txtQueueItemDuration)
    }

    companion object {
        private fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "$min:${"%02d".format(sec)}"
        }
    }
}
