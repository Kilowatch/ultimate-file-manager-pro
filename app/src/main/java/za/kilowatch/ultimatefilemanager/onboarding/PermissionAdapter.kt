package za.kilowatch.ultimatefilemanager.onboarding

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R

/**
 * RecyclerView adapter for displaying permission cards on the Welcome Screen.
 * Supports both mobile (vertical) and TV (horizontal tile) layouts.
 *
 * @param isTv Whether to use the TV tile layout
 * @param onGrantClick Callback when the user taps the Grant/Enable button
 */
class PermissionAdapter(
    private val isTv: Boolean,
    private val onGrantClick: (PermissionItem, Int) -> Unit
) : RecyclerView.Adapter<PermissionAdapter.PermissionViewHolder>() {

    private val items = mutableListOf<PermissionItem>()

    fun submitList(newItems: List<PermissionItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateItem(position: Int, item: PermissionItem) {
        if (position in items.indices) {
            items[position] = item
            notifyItemChanged(position)
        }
    }

    fun getItems(): List<PermissionItem> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PermissionViewHolder {
        val layoutRes = if (isTv) R.layout.item_permission_card_tv else R.layout.item_permission_card
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return PermissionViewHolder(view)
    }

    override fun onBindViewHolder(holder: PermissionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PermissionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView as MaterialCardView
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgPermIcon)
        private val txtTitle: TextView = itemView.findViewById(R.id.txtPermTitle)
        private val txtDesc: TextView = itemView.findViewById(R.id.txtPermDesc)
        private val txtStatus: TextView = itemView.findViewById(R.id.txtPermStatus)
        private val btnGrant: MaterialButton = itemView.findViewById(R.id.btnGrant)

        fun bind(item: PermissionItem) {
            val context = itemView.context

            // TV D-pad focus: highlight card with teal border + elevation + scale
            if (isTv) {
                val focusColor = context.getColor(R.color.ufm_primary)
                val defaultColor = context.getColor(R.color.ufm_surface_variant)
                val yellowTextColor = context.getColor(R.color.tv_button_focused_yellow_text)
                val whiteTextColor = context.getColor(R.color.tv_text_primary)

                card.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        card.strokeColor = focusColor
                        card.strokeWidth = (3 * context.resources.displayMetrics.density).toInt()
                        card.cardElevation = 12f * context.resources.displayMetrics.density
                        v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                    } else {
                        card.strokeColor = defaultColor
                        card.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
                        card.cardElevation = 2f * context.resources.displayMetrics.density
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    }
                }

                val yellowCsl = android.content.res.ColorStateList.valueOf(
                    context.getColor(R.color.tv_button_focused_yellow)
                )
                val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

                // Grant button: force glass-white default, swap to yellow on focus
                btnGrant.backgroundTintList = glassCsl
                btnGrant.setOnFocusChangeListener { _, hasFocus ->
                    btnGrant.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                    btnGrant.setTextColor(if (hasFocus) yellowTextColor else whiteTextColor)
                }
            }

            imgIcon.setImageResource(item.iconRes)
            txtTitle.text = context.getString(item.titleRes)
            txtDesc.text = context.getString(item.descRes)

            when (item.status) {
                PermissionStatus.GRANTED -> {
                    txtStatus.text = context.getString(R.string.status_granted)
                    if (isTv) {
                        txtStatus.setTextColor(context.getColor(R.color.ufm_granted))
                    } else {
                        txtStatus.setTextColor(0xFF4ADE80.toInt()) // green on dark
                        txtStatus.setBackgroundResource(R.drawable.bg_status_badge_accent)
                    }
                    btnGrant.isEnabled = false
                    btnGrant.setText(R.string.extracted_str_2)
                    btnGrant.alpha = 0.5f
                }
                PermissionStatus.DENIED -> {
                    txtStatus.text = context.getString(R.string.status_denied)
                    if (isTv) {
                        txtStatus.setTextColor(context.getColor(R.color.ufm_denied))
                    } else {
                        txtStatus.setTextColor(0xFFFF6B6B.toInt()) // red on dark
                        txtStatus.setBackgroundResource(R.drawable.bg_status_badge_required)
                    }
                    btnGrant.isEnabled = true
                    btnGrant.text = context.getString(R.string.btn_open_settings)
                    btnGrant.alpha = 1f
                }
                PermissionStatus.NOT_REQUESTED -> {
                    if (item.isOptional) {
                        txtStatus.text = context.getString(R.string.status_optional)
                        if (isTv) {
                            txtStatus.setTextColor(context.getColor(R.color.ufm_optional))
                        } else {
                            txtStatus.setTextColor(0xFFAAAAAA.toInt()) // gray on dark
                            txtStatus.setBackgroundResource(R.drawable.bg_status_badge_accent)
                        }
                        btnGrant.text = context.getString(R.string.btn_enable)
                    } else {
                        txtStatus.text = context.getString(R.string.status_required)
                        if (isTv) {
                            txtStatus.setTextColor(context.getColor(R.color.ufm_pending))
                        } else {
                            txtStatus.setTextColor(0xFFFFAA00.toInt()) // amber on dark
                            txtStatus.setBackgroundResource(R.drawable.bg_status_badge_required)
                        }
                        btnGrant.text = context.getString(R.string.btn_grant)
                    }
                    btnGrant.isEnabled = true
                    btnGrant.alpha = 1f
                }
            }

            btnGrant.setOnClickListener {
                onGrantClick(item, bindingAdapterPosition)
            }

            // On TV, pressing OK on the focused card should also trigger the grant
            if (isTv) {
                card.setOnClickListener {
                    if (btnGrant.isEnabled) {
                        onGrantClick(item, bindingAdapterPosition)
                    }
                }
            }
        }
    }
}
