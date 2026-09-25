package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import za.kilowatch.ultimatefilemanager.R

/**
 * Root overlay container for Edge Swipe Menu.
 * When the drawer is closed, passes all touches through to underlying activity views,
 * only intercepting touches directed at the edge handle indicator.
 * When the drawer is open, captures touches within the scrim and drawer card.
 */
class EdgeMenuRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var isLeftEdge: Boolean = true
    var edgeThresholdPx: Float = 0f
    var isMenuOpen: Boolean = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isMenuOpen) {
            val handle = findViewById<View>(R.id.viewEdgeHandle)
            if (handle != null && handle.visibility == View.VISIBLE) {
                val rect = Rect()
                handle.getGlobalVisibleRect(rect)
                if (rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    return super.dispatchTouchEvent(ev)
                }
            }
            // Allow all other touches to pass directly through to the underlying activity
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // When menu is open, let children (scrim, drawer card) handle touch events
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only consume touches if menu is open
        return isMenuOpen
    }
}
