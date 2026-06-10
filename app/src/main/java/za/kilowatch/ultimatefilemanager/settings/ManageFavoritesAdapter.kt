package za.kilowatch.ultimatefilemanager.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider

class ManageFavoritesAdapter(
    private val isTv: Boolean,
    private val onDeleteClick: (FavoritesManager.FavoriteItem) -> Unit
) : RecyclerView.Adapter<ManageFavoritesAdapter.FavoriteViewHolder>() {

    private var favorites: MutableList<FavoritesManager.FavoriteItem> = mutableListOf()

    fun submitList(newList: List<FavoritesManager.FavoriteItem>) {
        favorites.clear()
        favorites.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val layoutRes = if (isTv) R.layout.item_manage_favorite_tv else R.layout.item_manage_favorite
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return FavoriteViewHolder(view)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        val favorite = favorites[position]
        holder.bind(favorite)
    }

    override fun getItemCount(): Int = favorites.size

    inner class FavoriteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtLabel: TextView = itemView.findViewById(R.id.txtLabel)
        private val txtPath: TextView = itemView.findViewById(R.id.txtPath)
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgIcon)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDelete)
        private val card: MaterialCardView = itemView as MaterialCardView

        fun bind(item: FavoritesManager.FavoriteItem) {
            txtLabel.text = item.label
            txtPath.text = item.path

            val ctx = itemView.context
            if (item.isFolder) {
                imgIcon.setImageResource(IconCustomizationManager.getEffectiveIconRes(ctx, "folder_default", R.drawable.ic_folder))
                imgIcon.setColorFilter(ctx.getColor(if (isTv) R.color.tv_icon_tint else R.color.mobile_icon_tint))
            } else {
                imgIcon.setImageResource(FileTypeIconProvider.iconForExtension(ctx, item.path.substringAfterLast('.', "")))
                imgIcon.setColorFilter(itemView.context.getColor(if (isTv) R.color.tv_icon_tint else R.color.mobile_icon_tint))
            }

            btnDelete.setOnClickListener {
                onDeleteClick(item)
            }

            // Also handle card click for TV D-pad OK support
            card.setOnClickListener {
                onDeleteClick(item)
            }

            if (isTv) {
                setupTvCardFocus(card, btnDelete)
            }
        }

        private fun setupTvCardFocus(card: MaterialCardView, btnDelete: ImageView) {
            val yellowFill  = itemView.context.getColor(R.color.tv_button_focused_yellow)
            val blackText   = itemView.context.getColor(R.color.tv_button_focused_yellow_text)
            val glassColor  = itemView.context.getColor(R.color.tv_glass_white_10)
            val primaryText = itemView.context.getColor(R.color.tv_text_primary)
            val secondText  = itemView.context.getColor(R.color.tv_text_secondary)

            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    card.setCardBackgroundColor(yellowFill)
                    txtLabel.setTextColor(blackText)
                    txtPath.setTextColor(blackText)
                    btnDelete.setColorFilter(blackText)
                } else {
                    card.setCardBackgroundColor(glassColor)
                    txtLabel.setTextColor(primaryText)
                    txtPath.setTextColor(secondText)
                    btnDelete.setColorFilter(secondText)
                }
            }

            // TV hover states for delete button specifically
            btnDelete.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    btnDelete.setColorFilter(itemView.context.getColor(R.color.tv_error_red))
                } else {
                    // Reset to parent state
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
