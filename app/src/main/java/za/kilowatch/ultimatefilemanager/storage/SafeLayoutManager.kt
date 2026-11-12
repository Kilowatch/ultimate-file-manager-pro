package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Defensive [LinearLayoutManager] that catches internal RecyclerView inconsistency exceptions
 * ([IndexOutOfBoundsException]) during [onLayoutChildren] and scroll passes, and disables
 * predictive animations to prevent stale scrap view inconsistencies.
 */
open class SafeLinearLayoutManager : LinearLayoutManager {
    companion object {
        private const val TAG = "SafeLinearLayoutManager"
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, orientation: Int, reverseLayout: Boolean) : super(context, orientation, reverseLayout)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    override fun supportsPredictiveItemAnimations(): Boolean = false

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            Log.e(TAG, "Suppressed IndexOutOfBoundsException in onLayoutChildren", e)
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        return try {
            super.scrollVerticallyBy(dy, recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            Log.e(TAG, "Suppressed IndexOutOfBoundsException in scrollVerticallyBy", e)
            0
        }
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        return try {
            super.scrollHorizontallyBy(dx, recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            Log.e(TAG, "Suppressed IndexOutOfBoundsException in scrollHorizontallyBy", e)
            0
        }
    }
}

/**
 * Defensive [GridLayoutManager] that catches internal RecyclerView inconsistency exceptions
 * ([IndexOutOfBoundsException]) during [onLayoutChildren] and scroll passes, and disables
 * predictive animations to prevent stale scrap view inconsistencies.
 */
open class SafeGridLayoutManager : GridLayoutManager {
    companion object {
        private const val TAG = "SafeGridLayoutManager"
    }

    constructor(context: Context, spanCount: Int) : super(context, spanCount)
    constructor(context: Context, spanCount: Int, orientation: Int, reverseLayout: Boolean) : super(context, spanCount, orientation, reverseLayout)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    override fun supportsPredictiveItemAnimations(): Boolean = false

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            Log.e(TAG, "Suppressed IndexOutOfBoundsException in onLayoutChildren", e)
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        return try {
            super.scrollVerticallyBy(dy, recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            Log.e(TAG, "Suppressed IndexOutOfBoundsException in scrollVerticallyBy", e)
            0
        }
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        return try {
            super.scrollHorizontallyBy(dx, recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            Log.e(TAG, "Suppressed IndexOutOfBoundsException in scrollHorizontallyBy", e)
            0
        }
    }
}
