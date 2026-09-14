package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import za.kilowatch.ultimatefilemanager.R

/**
 * Reusable stepper component with + / − buttons, min/max boundary constraints,
 * and real-time value emission.
 */
class NumberStepperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val txtTitle: TextView
    private val txtSubtitle: TextView
    private val txtValue: TextView
    private val btnMinus: View
    private val btnPlus: View

    private var minValue: Int = 0
    private var maxValue: Int = 100
    private var step: Int = 1
    private var unit: String = ""
    private var currentValue: Int = 0
    private var onValueChanged: ((Int) -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_number_stepper, this, true)
        txtTitle = findViewById(R.id.txtStepperTitle)
        txtSubtitle = findViewById(R.id.txtStepperSubtitle)
        txtValue = findViewById(R.id.txtStepperValue)
        btnMinus = findViewById(R.id.btnMinus)
        btnPlus = findViewById(R.id.btnPlus)

        btnMinus.setOnClickListener {
            val next = (currentValue - step).coerceAtLeast(minValue)
            if (next != currentValue) {
                currentValue = next
                updateState()
                onValueChanged?.invoke(currentValue)
            }
        }

        btnPlus.setOnClickListener {
            val next = (currentValue + step).coerceAtMost(maxValue)
            if (next != currentValue) {
                currentValue = next
                updateState()
                onValueChanged?.invoke(currentValue)
            }
        }
    }

    /**
     * Configures the stepper parameters and initial value.
     */
    fun configure(
        title: CharSequence,
        subtitle: CharSequence? = null,
        min: Int,
        max: Int,
        step: Int,
        unit: String,
        initialValue: Int,
        onValueChanged: (Int) -> Unit
    ) {
        this.minValue = min
        this.maxValue = max
        this.step = step
        this.unit = unit
        this.currentValue = initialValue.coerceIn(min, max)
        this.onValueChanged = onValueChanged

        txtTitle.text = title
        if (subtitle.isNullOrBlank()) {
            txtSubtitle.visibility = View.GONE
        } else {
            txtSubtitle.text = subtitle
            txtSubtitle.visibility = View.VISIBLE
        }

        updateState()
    }

    /**
     * Programmatically sets the value without firing the callback.
     */
    fun setValue(newValue: Int) {
        this.currentValue = newValue.coerceIn(minValue, maxValue)
        updateState()
    }

    fun getValue(): Int = currentValue

    private fun updateState() {
        txtValue.text = if (unit.isBlank()) "$currentValue" else "$currentValue $unit"

        val canMinus = currentValue > minValue
        btnMinus.isEnabled = canMinus
        btnMinus.alpha = if (canMinus) 1.0f else 0.35f

        val canPlus = currentValue < maxValue
        btnPlus.isEnabled = canPlus
        btnPlus.alpha = if (canPlus) 1.0f else 0.35f
    }
}
