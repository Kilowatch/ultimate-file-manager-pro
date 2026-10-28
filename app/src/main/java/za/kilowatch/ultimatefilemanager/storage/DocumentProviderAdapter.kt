package za.kilowatch.ultimatefilemanager.storage

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R

class DocumentProviderAdapter(
    private val isTv: Boolean,
    private val onProviderClick: (DocumentProviderInfo) -> Unit
) : RecyclerView.Adapter<DocumentProviderAdapter.ProviderViewHolder>() {

    private val items = mutableListOf<DocumentProviderInfo>()

    fun submitList(newItems: List<DocumentProviderInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProviderViewHolder {
        val layoutRes = if (isTv) R.layout.item_document_provider_tv else R.layout.item_document_provider
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ProviderViewHolder(view, isTv)
    }

    override fun onBindViewHolder(holder: ProviderViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, onProviderClick)
    }

    class ProviderViewHolder(
        itemView: View,
        private val isTv: Boolean
    ) : RecyclerView.ViewHolder(itemView) {

        val cardProvider: MaterialCardView = itemView.findViewById(R.id.cardProvider)
        private val imgProviderIcon: ImageView = itemView.findViewById(R.id.imgProviderIcon)
        private val txtProviderName: TextView = itemView.findViewById(R.id.txtProviderName)
        private val txtProviderPackage: TextView = itemView.findViewById(R.id.txtProviderPackage)
        private val txtCapabilityTag: TextView = itemView.findViewById(R.id.txtCapabilityTag)

        fun bind(item: DocumentProviderInfo, onClick: (DocumentProviderInfo) -> Unit) {
            val context = itemView.context

            txtProviderName.text = item.label
            txtProviderPackage.text = item.authority

            if (item.icon != null) {
                imgProviderIcon.setImageDrawable(item.icon)
            } else {
                imgProviderIcon.setImageResource(R.drawable.ic_folder)
            }

            txtCapabilityTag.text = when {
                item.isTreeCapable -> context.getString(R.string.capability_folder_mount)
                else               -> context.getString(R.string.capability_files_only)
            }

            if (isTv) {
                cardProvider.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        cardProvider.strokeColor = ContextCompat.getColor(context, R.color.tv_accent)
                        cardProvider.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
                        cardProvider.setCardBackgroundColor(ContextCompat.getColor(context, R.color.tv_glass_white_20))
                    } else {
                        cardProvider.strokeColor = ContextCompat.getColor(context, R.color.tv_glass_border)
                        cardProvider.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
                        cardProvider.setCardBackgroundColor(ContextCompat.getColor(context, R.color.tv_glass_white_10))
                    }
                }
            }

            cardProvider.setOnClickListener {
                onClick(item)
            }
        }
    }
}
