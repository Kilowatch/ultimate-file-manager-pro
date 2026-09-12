package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView
import androidx.annotation.Keep

/**
 * A ScrollView that respects android:maxHeight in XML and programmatic maxHeightPx.
 * When content height is less than maxHeightPx, it sizes naturally to wrap_content.
 * When content height exceeds maxHeightPx, it clamps to maxHeightPx and allows vertical scrolling.
 */
@Keep
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = 0

    init {
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
        maxHeightPx = a.getDimensionPixelSize(0, 0)
        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var newHeightSpec = heightMeasureSpec
        if (maxHeightPx > 0) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val constrainedSize = if (mode == MeasureSpec.UNSPECIFIED || size <= 0) {
                maxHeightPx
            } else {
                minOf(size, maxHeightPx)
            }
            newHeightSpec = MeasureSpec.makeMeasureSpec(constrainedSize, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, newHeightSpec)
    }
}
