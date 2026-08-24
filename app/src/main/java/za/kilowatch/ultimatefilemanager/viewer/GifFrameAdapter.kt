package za.kilowatch.ultimatefilemanager.viewer

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.util.Collections

class GifFrameAdapter(
    private val framePaths: MutableList<String>,
    private val isTv: Boolean,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<GifFrameAdapter.FrameViewHolder>() {

    class FrameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtSeqNum: TextView = view.findViewById(R.id.txtSeqNum)
        val imgThumbnail: ImageView = view.findViewById(R.id.imgThumbnail)
        val txtFileName: TextView = view.findViewById(R.id.txtFileName)
        val txtFileSize: TextView = view.findViewById(R.id.txtFileSize)
        val imgDragHandle: ImageView = view.findViewById(R.id.imgDragHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FrameViewHolder {
        val layoutRes = if (isTv) R.layout.item_gif_frame_tv else R.layout.item_gif_frame
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return FrameViewHolder(view)
    }

    override fun onBindViewHolder(holder: FrameViewHolder, position: Int) {
        val path = framePaths[position]
        val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(path) ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(holder.itemView.context, path)
        val file = if (isSaf) za.kilowatch.ultimatefilemanager.storage.SafFile(path) else File(path)
        val sizeBytes = if (isSaf) za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(holder.itemView.context, path) else file.length()
        val imageModel: Any = if (isSaf) {
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getDocumentUriForPath(holder.itemView.context, path) ?: file
        } else {
            file
        }

        holder.txtSeqNum.text = "#${position + 1}"
        holder.txtFileName.text = file.name
        holder.txtFileSize.text = Formatter.formatShortFileSize(holder.itemView.context, sizeBytes)

        holder.imgThumbnail.load(imageModel)

        holder.imgDragHandle.setOnTouchListener { _, _ ->
            onStartDrag?.invoke(holder)
            false
        }
    }

    override fun getItemCount(): Int = framePaths.size

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(framePaths, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(framePaths, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        notifyItemRangeChanged(minOf(fromPosition, toPosition), maxOf(fromPosition, toPosition) - minOf(fromPosition, toPosition) + 1)
    }

    fun getFramePaths(): List<String> = framePaths
}

class GifItemTouchHelperCallback(
    private val adapter: GifFrameAdapter
) : ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled(): Boolean = true

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // No swipe deletion
    }
}
