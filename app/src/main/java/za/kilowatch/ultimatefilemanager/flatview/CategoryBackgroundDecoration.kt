package za.kilowatch.ultimatefilemanager.flatview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R

/**
 * ItemDecoration that renders the outer "Background" container for each Root Category
 * in Flat View, matching the main menu categorized card look (e.g. Storage category group).
 *
 * For each contiguous group of items belonging to the same category:
 * - 24dp corner radius (20dp on TV)
 * - mobile_glass_card background fill
 * - 1dp mobile_glass_stroke outline
 * - Seamlessly bounds the category header, subfolders, and child files into a unified card.
 * - Enforces bottom margin offsets on the last item of expanded categories so that
 *   there is proper vertical spacing between categories when subfolders/categories are expanded.
 */
class CategoryBackgroundDecoration(
    context: Context,
    private val isTv: Boolean
) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val marginHorizontal = if (isTv) 24f * density else 16f * density
    private val cornerRadius = if (isTv) 20f * density else 24f * density
    private val strokeWidth = 1f * density

    // Bottom padding inside the category background card below its last item when expanded
    private val cardBottomPadding = if (isTv) 12f * density else 10f * density
    // Spacing between the bottom of this category's card and the top of the next category's card
    private val outerCategoryMargin = if (isTv) 14f * density else 12f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.mobile_glass_card)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = this@CategoryBackgroundDecoration.strokeWidth
        color = ContextCompat.getColor(context, R.color.mobile_glass_stroke)
    }

    private val path = Path()
    private val rectF = RectF()
    private val radii = FloatArray(8)

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val adapter = parent.adapter as? FlatViewAdapter ?: return
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return

        val items = adapter.currentList
        val item = items.getOrNull(pos) ?: return

        val isLastItemInCategory = pos == items.lastIndex ||
                items.getOrNull(pos + 1)?.categoryPath != item.categoryPath

        if (isLastItemInCategory) {
            val isCollapsedRoot = item is FlatViewItem.RootCategoryHeader && !item.isExpanded
            if (!isCollapsedRoot) {
                // When expanded, the background card extends cardBottomPadding below this last child.
                // In addition, we need outerCategoryMargin between this category card and the next root category.
                // The next root category has its own layout marginTop (8dp on Mobile, 10dp on TV).
                val nextRootTopMargin = if (isTv) 10f * density else 8f * density
                val childBottomMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                val totalNeededOffset = cardBottomPadding + outerCategoryMargin
                val remainingOffset = (totalNeededOffset - nextRootTopMargin - childBottomMargin).coerceAtLeast(0f)
                outRect.bottom = remainingOffset.toInt()
            }
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? FlatViewAdapter ?: return
        val childCount = parent.childCount
        if (childCount == 0) return

        val items = adapter.currentList
        if (items.isEmpty()) return

        val left = marginHorizontal
        val right = parent.width - marginHorizontal
        if (right <= left) return

        var currentCategory: String? = null
        var groupFirstChildIndex = -1
        var groupFirstAdapterPos = -1
        var groupLastChildIndex = -1
        var groupLastAdapterPos = -1

        fun drawCurrentGroup() {
            if (currentCategory == null || groupFirstChildIndex == -1 || groupLastChildIndex == -1) return

            val firstChild = parent.getChildAt(groupFirstChildIndex)
            val lastChild = parent.getChildAt(groupLastChildIndex)

            val isFirstItemInCategory = groupFirstAdapterPos == 0 ||
                    items.getOrNull(groupFirstAdapterPos - 1)?.categoryPath != currentCategory

            val isLastItemInCategory = groupLastAdapterPos == items.lastIndex ||
                    items.getOrNull(groupLastAdapterPos + 1)?.categoryPath != currentCategory

            val top = if (isFirstItemInCategory) {
                (firstChild.top + firstChild.translationY).coerceAtLeast(0f)
            } else {
                0f
            }

            val bottom = if (isLastItemInCategory) {
                val baseBottom = lastChild.bottom + lastChild.translationY
                val item = items.getOrNull(groupLastAdapterPos)
                val extraPadding = if (item is FlatViewItem.RootCategoryHeader && !item.isExpanded) 0f else cardBottomPadding
                (baseBottom + extraPadding).coerceAtMost(parent.height.toFloat())
            } else {
                parent.height.toFloat()
            }

            if (bottom <= top) return

            val topRadius = if (isFirstItemInCategory) cornerRadius else 0f
            val bottomRadius = if (isLastItemInCategory) cornerRadius else 0f

            radii[0] = topRadius
            radii[1] = topRadius
            radii[2] = topRadius
            radii[3] = topRadius
            radii[4] = bottomRadius
            radii[5] = bottomRadius
            radii[6] = bottomRadius
            radii[7] = bottomRadius

            rectF.set(left, top, right, bottom)
            path.reset()
            path.addRoundRect(rectF, radii, Path.Direction.CW)

            c.drawPath(path, fillPaint)
            c.drawPath(path, strokePaint)
        }

        for (i in 0 until childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue

            val item = items.getOrNull(pos) ?: continue
            val catPath = item.categoryPath

            if (catPath != currentCategory) {
                drawCurrentGroup()
                currentCategory = catPath
                groupFirstChildIndex = i
                groupFirstAdapterPos = pos
                groupLastChildIndex = i
                groupLastAdapterPos = pos
            } else {
                groupLastChildIndex = i
                groupLastAdapterPos = pos
            }
        }

        drawCurrentGroup()
    }
}
