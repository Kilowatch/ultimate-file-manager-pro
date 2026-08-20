package za.kilowatch.ultimatefilemanager.settings

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.TileIconManager
import za.kilowatch.ultimatefilemanager.util.ThemeColors

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
        holder.expandArrow.rotation = if (isExpanded) 180f else 0f
        holder.divider.visibility = if (isExpanded) View.VISIBLE else View.GONE
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
                    holder.expandArrow.imageTintList = ColorStateList.valueOf(black)
                } else {
                    holder.categoryName.setTextColor(white)
                    holder.itemCount.setTextColor(holder.itemView.context.getColor(R.color.tv_accent))
                    holder.expandArrow.imageTintList = ColorStateList.valueOf(holder.itemView.context.getColor(R.color.tv_accent))
                }
            }
        }

        // Build children
        if (isExpanded) {
            category.icons.forEachIndexed { index, iconItem ->
                val inlineChild = createIconChildView(holder, iconItem)
                holder.childrenContainer.addView(inlineChild)

                if (index < category.icons.size - 1) {
                    holder.childrenContainer.addView(createDivider(holder.itemView.context))
                }
            }
        }
    }

    private fun createIconChildView(holder: ViewHolder, iconItem: IconItemData): View {
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density

        val paddingHorizontal = (density * (if (isTv) 24 else 14)).toInt()
        val paddingVertical = (density * (if (isTv) 16 else 12)).toInt()

        val isCustomized = IconCustomizationManager.getOverride(context, iconItem.id) != null ||
            (iconItem.id.startsWith("tile_") && (
                !TileIconManager.getTileIcon(context, iconItem.id.removePrefix("tile_")).isNullOrEmpty() ||
                TileIconManager.getTileIconRes(context, iconItem.id.removePrefix("tile_")) != 0
            ))

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
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            }
        }

        // Frosted Icon Badge (44dp)
        val badgeSize = (density * (if (isTv) 56 else 44)).toInt()
        val iconSize = (density * (if (isTv) 28 else 22)).toInt()
        val iconContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
                marginEnd = (density * 14).toInt()
            }
            setBackgroundResource(if (isTv) R.drawable.bg_glass_card else R.drawable.bg_btn_icon_frosted)
        }

        val iconIv = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        IconCustomizationManager.applyToView(context, iconIv, iconItem.id, iconItem.defaultRes)
        iconContainer.addView(iconIv)
        child.addView(iconContainer)

        // Text Layout (Title + Subtitle)
        val textContainer = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (density * 8).toInt()
            }
            orientation = LinearLayout.VERTICAL
        }

        val labelTv = TextView(context).apply {
            text = iconItem.label
            textSize = if (isTv) 18f else 15f
            typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
            setTextColor(context.getColor(if (isTv) R.color.tv_text_primary else R.color.mobile_card_text_primary))
        }
        textContainer.addView(labelTv)

        val statusTv = TextView(context).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (density * 2).toInt()
            }
            if (isCustomized) {
                text = context.getString(R.string.icon_status_customized)
                setTextColor(if (isTv) context.getColor(R.color.tv_accent) else ThemeColors.primary(context))
                typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
            } else {
                text = context.getString(R.string.icon_status_default)
                setTextColor(context.getColor(if (isTv) R.color.tv_text_secondary else R.color.mobile_text_secondary))
            }
        }
        textContainer.addView(statusTv)
        child.addView(textContainer)

        // Reset button (if customized and not TV)
        if (!isTv && isCustomized) {
            val resetBadge = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (density * 36).toInt(),
                    (density * 36).toInt()
                )
                setBackgroundResource(R.drawable.bg_btn_icon_frosted)
                isClickable = true
                isFocusable = true
            }
            val resetIv = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (density * 18).toInt(),
                    (density * 18).toInt(),
                    Gravity.CENTER
                )
                setImageResource(R.drawable.ic_refresh)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.mobile_stop_btn))
                contentDescription = context.getString(R.string.reset_icon)
            }
            resetBadge.addView(resetIv)
            resetBadge.setOnClickListener { onIconReset(iconItem) }
            child.addView(resetBadge)
        }

        if (isTv) {
            val black = context.getColor(R.color.tv_button_focused_yellow_text)
            val white = context.getColor(R.color.tv_text_primary)
            child.setOnFocusChangeListener { _, hasFocus ->
                labelTv.setTextColor(if (hasFocus) black else white)
            }
        }

        // Entire row click opens picker
        child.setOnClickListener { onIconClicked(iconItem) }

        return child
    }

    private fun createDivider(context: android.content.Context): View {
        val density = context.resources.displayMetrics.density
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                marginStart = (14 * density).toInt()
                marginEnd = (14 * density).toInt()
            }
            setBackgroundColor(context.getColor(R.color.mobile_glass_stroke))
        }
    }

    override fun getItemCount(): Int = categories.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val headerLayout: LinearLayout = itemView.findViewById(R.id.layoutHeader)
        val categoryName: TextView = itemView.findViewById(R.id.txtCategoryName)
        val itemCount: TextView = itemView.findViewById(R.id.txtItemCount)
        val expandArrow: ImageView = itemView.findViewById(R.id.imgExpandArrow)
        val divider: View = itemView.findViewById(R.id.divider)
        val childrenContainer: LinearLayout = itemView.findViewById(R.id.layoutChildren)
    }
}
