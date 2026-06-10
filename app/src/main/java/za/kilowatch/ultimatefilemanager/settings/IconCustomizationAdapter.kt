package za.kilowatch.ultimatefilemanager.settings

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R

data class IconCategoryData(
    val id: String,
    val label: String,
    val icons: List<IconItemData>
)

data class IconItemData(
    val id: String,
    val label: String,
    val defaultRes: Int,
    val builtinAlternatives: List<Int>
)

class IconCustomizationAdapter(
    private val categories: MutableList<IconCategoryData>,
    private val isTv: Boolean,
    private val onIconClicked: (IconItemData) -> Unit,
    private val onIconReset: (IconItemData) -> Unit
) : RecyclerView.Adapter<IconCustomizationAdapter.ViewHolder>() {

    private val expandedCategories = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_icon_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        val isExpanded = category.id in expandedCategories

        holder.categoryName.text = category.label
        holder.itemCount.text = "${category.icons.size}"
        holder.expandArrow.setImageResource(R.drawable.ic_expand_more)
        holder.expandArrow.rotation = if (isExpanded) 180f else 0f
        holder.childrenContainer.removeAllViews()
        holder.childrenContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE

        // Header click toggles expand
        holder.headerLayout.setOnClickListener {
            if (isExpanded) {
                expandedCategories.remove(category.id)
            } else {
                expandedCategories.add(category.id)
            }
            notifyItemChanged(position)
        }

        if (isTv) {
            holder.headerLayout.setBackgroundResource(R.drawable.selector_tv_list_item)
            val black = holder.itemView.context.getColor(R.color.tv_button_focused_yellow_text)
            val white = holder.itemView.context.getColor(R.color.tv_text_primary)
            val secondary = holder.itemView.context.getColor(R.color.tv_text_secondary)
            
            holder.headerLayout.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    holder.categoryName.setTextColor(black)
                    holder.itemCount.setTextColor(black)
                    holder.expandArrow.imageTintList = android.content.res.ColorStateList.valueOf(black)
                } else {
                    holder.categoryName.setTextColor(white)
                    holder.itemCount.setTextColor(secondary)
                    holder.expandArrow.imageTintList = android.content.res.ColorStateList.valueOf(white)
                }
            }
        }

        // Build children
        if (isExpanded) {
            for (iconItem in category.icons) {
                val inlineChild = createIconChildView(holder, iconItem)
                holder.childrenContainer.addView(inlineChild)
            }
        }
    }

    private fun createIconChildView(holder: ViewHolder, iconItem: IconItemData): View {
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density

        val paddingHorizontal = (density * (if (isTv) 24 else 16)).toInt()
        val paddingVertical = (density * (if (isTv) 16 else 12)).toInt()

        val child = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)

            if (isTv) {
                isFocusable = true
                isFocusableInTouchMode = false
                setBackgroundResource(R.drawable.selector_tv_list_item)
            } else {
                val outValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            }
        }

        // Icon preview
        val iconSize = (density * (if (isTv) 64 else 56)).toInt()
        val iconMarginEnd = (density * (if (isTv) 16 else 12)).toInt()
        val iconPadding = (density * (if (isTv) 12 else 10)).toInt()

        val iconIv = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).also {
                it.setMargins(0, 0, iconMarginEnd, 0)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            setBackgroundResource(if (isTv) R.drawable.bg_glass_card else R.drawable.bg_icon_circle_accent)
        }
        IconCustomizationManager.applyToView(context, iconIv, iconItem.id, iconItem.defaultRes)
        child.addView(iconIv)

        // Label
        val labelTv = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = iconItem.label
            textSize = if (isTv) 20f else 16f
            setTextColor(context.getColor(if (isTv) R.color.tv_text_primary else android.R.color.primary_text_dark))
        }
        child.addView(labelTv)

        // Reset button (hidden on TV, user can reset in the customize dialog)
        if (!isTv) {
            val resetBtn = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = context.getString(R.string.reset_icon)
                textSize = 13f
                setTextColor(context.getColor(android.R.color.holo_red_light))
                setPadding((density * 8).toInt(), (density * 4).toInt(), (density * 8).toInt(), (density * 4).toInt())
                setOnClickListener { onIconReset(iconItem) }
            }
            child.addView(resetBtn)
        }

        if (isTv) {
            val black = context.getColor(R.color.tv_button_focused_yellow_text)
            val white = context.getColor(R.color.tv_text_primary)
            child.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    labelTv.setTextColor(black)
                } else {
                    labelTv.setTextColor(white)
                }
            }
        }

        // Entire row click opens picker
        child.setOnClickListener { onIconClicked(iconItem) }

        return child
    }

    override fun getItemCount(): Int = categories.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val headerLayout: LinearLayout = itemView.findViewById(R.id.layoutHeader)
        val categoryName: TextView = itemView.findViewById(R.id.txtCategoryName)
        val itemCount: TextView = itemView.findViewById(R.id.txtItemCount)
        val expandArrow: ImageView = itemView.findViewById(R.id.imgExpandArrow)
        val childrenContainer: LinearLayout = itemView.findViewById(R.id.layoutChildren)
    }
}
