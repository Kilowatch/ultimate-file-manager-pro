package za.kilowatch.ultimatefilemanager.viewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.audio.AudioTagData
import java.io.File
import java.util.Locale

class MusicTaggerThumbAdapter(
    private val files: List<File>,
    private val tagCache: Map<String, AudioTagData>,
    private val onTrackClicked: (Int, File) -> Unit
) : RecyclerView.Adapter<MusicTaggerThumbAdapter.ThumbViewHolder>() {

    private var selectedIndex = 0

    fun setSelectedIndex(index: Int) {
        val prev = selectedIndex
        selectedIndex = index
        notifyItemChanged(prev)
        notifyItemChanged(selectedIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_music_tagger_thumb, parent, false)
        return ThumbViewHolder(view)
    }

    override fun onBindViewHolder(holder: ThumbViewHolder, position: Int) {
        val file = files[position]
        val tags = tagCache[file.absolutePath]

        val displayTrack = if (tags != null && tags.trackNumber.isNotBlank()) {
            tags.trackNumber.padStart(2, '0')
        } else {
            String.format(Locale.US, "%02d", position + 1)
        }

        val displayTitle = if (tags != null && tags.title.isNotBlank()) {
            tags.title
        } else {
            file.nameWithoutExtension
        }

        val displayArtist = if (tags != null && tags.artist.isNotBlank()) {
            "${tags.artist} · ${file.extension.uppercase(Locale.ROOT)}"
        } else {
            file.extension.uppercase(Locale.ROOT)
        }

        holder.txtTrackIndex.text = displayTrack
        holder.txtTrackTitle.text = displayTitle
        holder.txtTrackSubtitle.text = displayArtist

        val isSelected = position == selectedIndex
        val context = holder.itemView.context
        if (isSelected) {
            holder.card.strokeColor = za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(context)
            holder.card.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
        } else {
            holder.card.strokeColor = za.kilowatch.ultimatefilemanager.util.ThemeColors.outline(context)
            holder.card.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
        }

        holder.itemView.setOnClickListener {
            setSelectedIndex(holder.bindingAdapterPosition)
            onTrackClicked(holder.bindingAdapterPosition, file)
        }
    }

    override fun getItemCount(): Int = files.size

    class ThumbViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView.findViewById(R.id.cardTrackThumb)
        val txtTrackIndex: TextView = itemView.findViewById(R.id.txtTrackIndex)
        val txtTrackTitle: TextView = itemView.findViewById(R.id.txtTrackTitle)
        val txtTrackSubtitle: TextView = itemView.findViewById(R.id.txtTrackSubtitle)
    }
}
