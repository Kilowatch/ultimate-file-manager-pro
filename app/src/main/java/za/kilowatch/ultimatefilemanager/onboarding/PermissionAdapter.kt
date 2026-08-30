package za.kilowatch.ultimatefilemanager.onboarding

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
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
            val density = context.resources.displayMetrics.density

            imgIcon.setImageResource(item.iconRes)
            txtTitle.text = context.getString(item.titleRes)
            if (item.id == "storage_access" && item.status == PermissionStatus.GRANTED) {
                val isAllFiles = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                        android.os.Environment.isExternalStorageManager()
                if (isAllFiles) {
                    txtDesc.text = context.getString(R.string.settings_all_files_access_enabled)
                } else {
                    val count = za.kilowatch.ultimatefilemanager.storage.SafLocationRepository.getLocations(context).size
                    txtDesc.text = context.getString(R.string.selected_folders_count, count)
                }
            } else {
                txtDesc.text = context.getString(item.descRes)
            }

            if (isTv) {
                val focusYellow = context.getColor(R.color.tv_button_focused_yellow)
                val defaultBorder = context.getColor(R.color.tv_glass_border)
                val defaultBg = context.getColor(R.color.tv_glass_white_10)
                val yellowText = context.getColor(R.color.tv_button_focused_yellow_text)
                val whiteText = context.getColor(R.color.tv_text_primary)
                val secText = context.getColor(R.color.tv_text_secondary)
                val accentCyan = context.getColor(R.color.tv_accent)

                card.isFocusable = true
                card.isClickable = true

                card.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        card.strokeColor = focusYellow
                        card.strokeWidth = (2 * density).toInt()
                        card.setCardBackgroundColor(focusYellow)
                        txtTitle.setTextColor(yellowText)
                        txtDesc.setTextColor(Color.parseColor("#88000000"))
                        imgIcon.imageTintList = ColorStateList.valueOf(yellowText)
                    } else {
                        card.strokeColor = defaultBorder
                        card.strokeWidth = (1 * density).toInt()
                        card.setCardBackgroundColor(defaultBg)
                        txtTitle.setTextColor(whiteText)
                        txtDesc.setTextColor(secText)
                        imgIcon.imageTintList = ColorStateList.valueOf(accentCyan)
                    }
                }

                val yellowCsl = ColorStateList.valueOf(focusYellow)
                val glassCsl = ColorStateList.valueOf(0x26FFFFFF)

                btnGrant.backgroundTintList = glassCsl
                btnGrant.setOnFocusChangeListener { _, hasFocus ->
                    btnGrant.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                    btnGrant.setTextColor(if (hasFocus) yellowText else whiteText)
                    btnGrant.iconTint = ColorStateList.valueOf(if (hasFocus) yellowText else whiteText)
                }
            }

            when (item.status) {
                PermissionStatus.GRANTED -> {
                    txtStatus.text = context.getString(R.string.status_granted)
                    if (isTv) {
                        txtStatus.setTextColor(context.getColor(R.color.ufm_granted))
                        txtStatus.setBackgroundResource(R.drawable.bg_status_badge)
                        btnGrant.isEnabled = false
                        btnGrant.text = context.getString(R.string.status_granted)
                        btnGrant.icon = ContextCompat.getDrawable(context, R.drawable.ic_check)
                        btnGrant.alpha = 0.6f
                    } else {
                        txtStatus.setTextColor(0xFF4ADE80.toInt())
                        txtStatus.setBackgroundResource(R.drawable.bg_status_badge_granted)
                        if (item.id == "storage_access") {
                            btnGrant.isEnabled = true
                            btnGrant.text = context.getString(R.string.btn_manage)
                            btnGrant.icon = null
                            btnGrant.setTextColor(Color.WHITE)
                            btnGrant.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.ufm_primary))
                            btnGrant.alpha = 1f
                        } else {
                            btnGrant.isEnabled = false
                            btnGrant.text = context.getString(R.string.status_granted)
                            btnGrant.icon = ContextCompat.getDrawable(context, R.drawable.ic_check)
                            btnGrant.iconTint = ColorStateList.valueOf(0xFF4ADE80.toInt())
                            btnGrant.setTextColor(0xFF4ADE80.toInt())
                            btnGrant.backgroundTintList = ColorStateList.valueOf(0x264ADE80)
                            btnGrant.alpha = 1f
                        }
                    }
                }
                PermissionStatus.DENIED -> {
                    txtStatus.text = context.getString(R.string.status_denied)
                    if (isTv) {
                        txtStatus.setTextColor(context.getColor(R.color.ufm_denied))
                        txtStatus.setBackgroundResource(R.drawable.bg_status_badge)
                        btnGrant.isEnabled = true
                        btnGrant.icon = null
                        btnGrant.text = context.getString(R.string.btn_open_settings)
                        btnGrant.alpha = 1f
                    } else {
                        txtStatus.setTextColor(0xFFFF6B6B.toInt())
                        txtStatus.setBackgroundResource(R.drawable.bg_status_badge_denied)
                        btnGrant.isEnabled = true
                        btnGrant.icon = null
                        btnGrant.text = context.getString(R.string.btn_open_settings)
                        btnGrant.setTextColor(Color.WHITE)
                        btnGrant.backgroundTintList = ColorStateList.valueOf(0xFFE53935.toInt())
                        btnGrant.alpha = 1f
                    }
                }
                PermissionStatus.NOT_REQUESTED -> {
                    btnGrant.icon = null
                    btnGrant.isEnabled = true
                    btnGrant.alpha = 1f
                    if (item.isOptional) {
                        txtStatus.text = context.getString(R.string.status_optional)
                        if (isTv) {
                            txtStatus.setTextColor(context.getColor(R.color.ufm_optional))
                            txtStatus.setBackgroundResource(R.drawable.bg_status_badge)
                        } else {
                            txtStatus.setTextColor(0xFF38BDF8.toInt())
                            txtStatus.setBackgroundResource(R.drawable.bg_status_badge_accent)
                            btnGrant.setTextColor(Color.WHITE)
                            btnGrant.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.ufm_primary))
                        }
                        btnGrant.text = context.getString(R.string.btn_enable)
                    } else {
                        txtStatus.text = context.getString(R.string.status_required)
                        if (isTv) {
                            txtStatus.setTextColor(context.getColor(R.color.ufm_pending))
                            txtStatus.setBackgroundResource(R.drawable.bg_status_badge)
                        } else {
                            txtStatus.setTextColor(0xFFFFAA00.toInt())
                            txtStatus.setBackgroundResource(R.drawable.bg_status_badge_required)
                            btnGrant.setTextColor(Color.WHITE)
                            btnGrant.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.ufm_primary))
                        }
                        btnGrant.text = context.getString(R.string.btn_grant)
                    }
                }
            }

            btnGrant.setOnClickListener {
                onGrantClick(item, bindingAdapterPosition)
            }

            // Clicking the card triggers grant if button is enabled or if storage_access
            card.setOnClickListener {
                if (btnGrant.isEnabled || item.id == "storage_access") {
                    onGrantClick(item, bindingAdapterPosition)
                }
            }
        }
    }
}
