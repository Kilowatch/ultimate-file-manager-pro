package za.kilowatch.ultimatefilemanager.onboarding

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LongPressDurationManager

/**
 * Onboarding Step 4: Edit Mode Hold Duration.
 * Pre-selects Normal by default and includes an interactive touch & hold test gauge.
 */
class OnboardingHoldDurationActivity : BaseOnboardingStepActivity() {

    override val stepIndex: Int = 4
    override val stepTitleRes: Int = R.string.onboarding_hold_duration_title

    private var selectedStepIndex: Int = LongPressDurationManager.DEFAULT_STEP

    private lateinit var cardShort: MaterialCardView
    private lateinit var cardNormal: MaterialCardView
    private lateinit var cardLong: MaterialCardView

    private lateinit var radioShort: RadioButton
    private lateinit var radioNormal: RadioButton
    private lateinit var radioLong: RadioButton

    private lateinit var btnHoldTarget: FrameLayout
    private lateinit var progressHold: CircularProgressIndicator
    private lateinit var txtHoldPrompt: TextView
    private lateinit var txtHoldFeedback: TextView

    private var holdAnimator: ValueAnimator? = null
    private var isCompleted: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (isTv) R.layout.activity_onboarding_hold_duration_tv else R.layout.activity_onboarding_hold_duration)
        setupCommonViews(findViewById(R.id.main))

        cardShort = findViewById(R.id.cardOptionShort)
        cardNormal = findViewById(R.id.cardOptionNormal)
        cardLong = findViewById(R.id.cardOptionLong)

        radioShort = findViewById(R.id.radioOptionShort)
        radioNormal = findViewById(R.id.radioOptionNormal)
        radioLong = findViewById(R.id.radioOptionLong)

        btnHoldTarget = findViewById(R.id.btnHoldTarget)
        progressHold = findViewById(R.id.progressHold)
        txtHoldPrompt = findViewById(R.id.txtHoldPrompt)
        txtHoldFeedback = findViewById(R.id.txtHoldFeedback)

        if (isReconfigureMode) {
            selectedStepIndex = LongPressDurationManager.loadStep(this)
        } else {
            selectedStepIndex = LongPressDurationManager.DEFAULT_STEP
        }

        cardShort.setOnClickListener { selectStep(0) }
        cardNormal.setOnClickListener { selectStep(LongPressDurationManager.DEFAULT_STEP) }
        cardLong.setOnClickListener { selectStep(5) }

        setupInteractiveHold()
        updateUI()
    }

    private fun getDurationMs(): Long {
        return LongPressDurationManager.STEPS_MS.getOrElse(selectedStepIndex) { 2000L }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteractiveHold() {
        btnHoldTarget.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startHoldAnimation(v)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopHoldAnimation()
                    true
                }
                else -> false
            }
        }

        btnHoldTarget.setOnKeyListener { v, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) startHoldAnimation(v)
                        true
                    }
                    KeyEvent.ACTION_UP -> {
                        stopHoldAnimation()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    }

    private fun startHoldAnimation(targetView: View) {
        holdAnimator?.cancel()
        isCompleted = false
        progressHold.progress = 0
        txtHoldPrompt.text = "Holding..."

        val duration = getDurationMs()
        holdAnimator = ValueAnimator.ofInt(0, 100).apply {
            this.duration = duration
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Int
                progressHold.progress = progress
                if (progress >= 100 && !isCompleted) {
                    isCompleted = true
                    targetView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    txtHoldPrompt.text = "✓ Triggered!"
                    txtHoldFeedback.text = "Selection activated after ${duration}ms"
                }
            }
            start()
        }
    }

    private fun stopHoldAnimation() {
        holdAnimator?.cancel()
        holdAnimator = null
        if (!isCompleted) {
            progressHold.progress = 0
            txtHoldPrompt.text = "Press & Hold\nto Test"
            txtHoldFeedback.text = "Hold down the button above to test the threshold"
        }
    }

    private fun selectStep(step: Int) {
        selectedStepIndex = step
        stopHoldAnimation()
        updateUI()
    }

    private fun updateUI() {
        val activeColor = getActiveAccentColor()
        val inactiveStroke = getInactiveStrokeColor()

        val isShort = (selectedStepIndex <= 1)
        val isNormal = (selectedStepIndex in 2..4)
        val isLong = (selectedStepIndex >= 5)

        radioShort.isChecked = isShort
        radioNormal.isChecked = isNormal
        radioLong.isChecked = isLong

        cardShort.strokeWidth = if (isShort) dpToPx(2) else dpToPx(1)
        cardShort.strokeColor = if (isShort) activeColor else inactiveStroke

        cardNormal.strokeWidth = if (isNormal) dpToPx(2) else dpToPx(1)
        cardNormal.strokeColor = if (isNormal) activeColor else inactiveStroke

        cardLong.strokeWidth = if (isLong) dpToPx(2) else dpToPx(1)
        cardLong.strokeColor = if (isLong) activeColor else inactiveStroke
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        holdAnimator?.cancel()
    }

    override fun onSaveCurrentSelection() {
        LongPressDurationManager.saveStep(this, selectedStepIndex)
    }
}
