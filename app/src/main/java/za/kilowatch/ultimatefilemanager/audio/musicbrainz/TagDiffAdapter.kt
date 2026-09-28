package za.kilowatch.ultimatefilemanager.audio.musicbrainz

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import za.kilowatch.ultimatefilemanager.R

data class TagDiffItem(
    val key: String,
    val displayName: String,
    val oldValue: String,
    val newValue: String,
    var isSelected: Boolean = true,
    val artworkBytes: ByteArray? = null
)

class TagDiffAdapter(
    val items: MutableList<TagDiffItem> = mutableListOf(),
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<TagDiffAdapter.DiffViewHolder>() {

    fun submitList(newItems: List<TagDiffItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setAllSelected(selected: Boolean) {
        items.forEach { it.isSelected = selected }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiffViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tag_diff_field, parent, false)
        return DiffViewHolder(view)
    }

    override fun onBindViewHolder(holder: DiffViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DiffViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chkFieldSelected: MaterialCheckBox = itemView.findViewById(R.id.chkFieldSelected)
        private val txtFieldName: TextView = itemView.findViewById(R.id.txtFieldName)
        private val txtNewValue: TextView = itemView.findViewById(R.id.txtNewValue)
        private val txtOldValue: TextView = itemView.findViewById(R.id.txtOldValue)
        private val cardDiffArtPreview: MaterialCardView = itemView.findViewById(R.id.cardDiffArtPreview)
        private val imgDiffArtThumb: ImageView = itemView.findViewById(R.id.imgDiffArtThumb)

        fun bind(item: TagDiffItem) {
            chkFieldSelected.setOnCheckedChangeListener(null)
            chkFieldSelected.isChecked = item.isSelected

            txtFieldName.text = item.displayName.uppercase()
            txtNewValue.text = item.newValue.ifBlank { "(empty)" }
            txtOldValue.text = if (item.oldValue.isNotBlank()) "Current: ${item.oldValue}" else "Current: (empty)"

            if (item.artworkBytes != null && item.artworkBytes.isNotEmpty()) {
                cardDiffArtPreview.visibility = View.VISIBLE
                val bmp = BitmapFactory.decodeByteArray(item.artworkBytes, 0, item.artworkBytes.size)
                if (bmp != null) {
                    imgDiffArtThumb.setImageBitmap(bmp)
                } else {
                    imgDiffArtThumb.setImageResource(R.drawable.ic_file_audio)
                }
            } else {
                cardDiffArtPreview.visibility = View.GONE
            }

            chkFieldSelected.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                onSelectionChanged()
            }

            itemView.setOnClickListener {
                chkFieldSelected.isChecked = !chkFieldSelected.isChecked
            }
        }
    }
}
