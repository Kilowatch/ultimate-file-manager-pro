package za.kilowatch.ultimatefilemanager.storage

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R

/**
 * An ItemDecoration that draws sticky headers over a RecyclerView.
 * It looks for items of type TYPE_HEADER (3) and pins the top-most visible one.
 */
class DateGroupStickyHeaderDecoration(
    private val adapter: RecyclerView.Adapter<*>,
    private val headerViewType: Int = 3
) : RecyclerView.ItemDecoration() {

    private var currentHeader: View? = null
    private var currentHeaderPosition: Int = -1

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)

        val topChild = parent.getChildAt(0) ?: return
        val topChildPosition = parent.getChildAdapterPosition(topChild)
        if (topChildPosition == RecyclerView.NO_POSITION) return

        val headerPos = getHeaderPositionForItem(topChildPosition)
        if (headerPos == RecyclerView.NO_POSITION) return

        if (headerPos != currentHeaderPosition || currentHeader == null) {
            val viewHolder = adapter.createViewHolder(parent, headerViewType)
            @Suppress("UNCHECKED_CAST")
            (adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>).bindViewHolder(viewHolder, headerPos)
            currentHeader = viewHolder.itemView
            currentHeaderPosition = headerPos
            fixLayoutSize(parent, currentHeader!!)
        }

        val header = currentHeader ?: return

        // If the actual header view is currently on screen and below the top edge,
        // we don't need to draw the sticky header (prevents double drawing).
        val headerView = parent.findViewHolderForAdapterPosition(headerPos)?.itemView
        if (headerView != null && headerView.top >= 0) {
            return
        }

        val childInContact = getChildInContact(parent, header.bottom, headerPos)

        var dy = 0
        if (childInContact != null) {
            val childPos = parent.getChildAdapterPosition(childInContact)
            if (adapter.getItemViewType(childPos) == headerViewType) {
                // If the next header is pushing this one up, animate the transition
                dy = childInContact.top - header.bottom
            }
        }

        c.save()
        c.translate(0f, dy.toFloat())
        header.draw(c)
        c.restore()
    }

    private fun getHeaderPositionForItem(itemPosition: Int): Int {
        for (i in itemPosition downTo 0) {
            if (adapter.getItemViewType(i) == headerViewType) {
                return i
            }
        }
        return RecyclerView.NO_POSITION
    }

    private fun getChildInContact(parent: RecyclerView, contactPoint: Int, currentHeaderPos: Int): View? {
        var childInContact: View? = null
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val bounds = android.graphics.Rect()
            parent.getDecoratedBoundsWithMargins(child, bounds)
            if (bounds.bottom > contactPoint && bounds.top <= contactPoint) {
                childInContact = child
                break
            }
        }
        return childInContact
    }

    private fun fixLayoutSize(parent: ViewGroup, view: View) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.UNSPECIFIED)
        val childWidth = ViewGroup.getChildMeasureSpec(
            widthSpec,
            parent.paddingLeft + parent.paddingRight,
            view.layoutParams.width
        )
        val childHeight = ViewGroup.getChildMeasureSpec(
            heightSpec,
            parent.paddingTop + parent.paddingBottom,
            view.layoutParams.height
        )
        view.measure(childWidth, childHeight)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
