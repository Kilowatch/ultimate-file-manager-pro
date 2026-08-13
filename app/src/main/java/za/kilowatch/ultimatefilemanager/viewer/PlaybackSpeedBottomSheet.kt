package za.kilowatch.ultimatefilemanager.viewer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import za.kilowatch.ultimatefilemanager.R

/**
 * Mobile-only playback speed selector. Lists 0.5x–3.0x; selecting a speed
 * invokes [onSpeedSelected] and dismisses. Tapping outside dismisses without change.
 */
class PlaybackSpeedBottomSheet : BottomSheetDialogFragment() {

    private var onSpeedSelected: ((Float) -> Unit)? = null

    companion object {
        const val TAG = "PlaybackSpeedBottomSheet"
        private const val ARG_CURRENT_SPEED = "current_speed"

        val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

        fun newInstance(currentSpeed: Float, onSpeedSelected: (Float) -> Unit): PlaybackSpeedBottomSheet {
            return PlaybackSpeedBottomSheet().apply {
                this.onSpeedSelected = onSpeedSelected
                arguments = Bundle().apply { putFloat(ARG_CURRENT_SPEED, currentSpeed) }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_playback_speed, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        val container = view.findViewById<LinearLayout>(R.id.speedListContainer)
        val current = arguments?.getFloat(ARG_CURRENT_SPEED, 1.0f) ?: 1.0f
        val primary = ContextCompat.getColor(context, R.color.ufm_primary)
        val normal = ContextCompat.getColor(context, R.color.ufm_text_primary)
        val density = resources.displayMetrics.density

        for (speed in SPEEDS) {
            val row = TextView(context).apply {
                text = "${speed}x"
                textSize = 16f
                setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
                setTextColor(if (speed == current) primary else normal)
                setTypeface(typeface, if (speed == current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                val outValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                if (outValue.resourceId != 0) {
                    background = context.getDrawable(outValue.resourceId)
                }
                setOnClickListener {
                    onSpeedSelected?.invoke(speed)
                    dismiss()
                }
            }
            container.addView(row)
        }
    }
}
