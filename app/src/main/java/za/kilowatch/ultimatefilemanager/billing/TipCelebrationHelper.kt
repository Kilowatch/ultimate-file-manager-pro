package za.kilowatch.ultimatefilemanager.billing

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R

/**
 * Orchestrates the "Coffee Eruption" celebration when a tip completes.
 *
 * Call [celebrate] from any Activity or Fragment after a successful purchase.
 * Everything is self-contained and self-cleaning — no lifecycle hooks needed.
 */
object TipCelebrationHelper {

    // ─── Local SKU Constant Decoupling ───────────────────────────────────────
    const val SKU_ESPRESSO = "tip_espresso_01"
    const val SKU_LATTE    = "tip_latte_05"
    const val SKU_BEANS    = "tip_beans_15"

    // ─── Per-tier particle colour palettes ───────────────────────────────────

    /** Espresso: warm amber / dark brown / cream */
    private val COLORS_ESPRESSO = intArrayOf(
        Color.parseColor("#FFBF69"),  // golden amber
        Color.parseColor("#B45309"),  // espresso brown
        Color.parseColor("#FFFBEB"),  // cream
        Color.parseColor("#F59E0B")   // warm yellow
    )

    /** Latte: creamy gold / caramel / soft brown */
    private val COLORS_LATTE = intArrayOf(
        Color.parseColor("#FDE68A"),  // creamy gold
        Color.parseColor("#CA8A04"),  // caramel
        Color.parseColor("#FBBF24"),  // latte yellow
        Color.parseColor("#D97706")   // warm brown
    )

    /** Bean Bag: rich purple / lavender / coffee (matching tile_tip_jar palette) */
    private val COLORS_BEANS = intArrayOf(
        Color.parseColor("#BE85F5"),  // lavender (dark-mode tile accent)
        Color.parseColor("#8B2FC9"),  // deep purple (light-mode tile accent)
        Color.parseColor("#C4B5FD"),  // soft violet
        Color.parseColor("#7C3AED")   // vivid purple
    )

    // ─── Public entry point ───────────────────────────────────────────────────

    /**
     * Trigger the full celebration for the given [sku].
     *
     * @param activity  The hosting Activity (used for vibration & context).
     * @param sku       One of [SKU_ESPRESSO/LATTE/BEANS].
     * @param rootView  A [ViewGroup] to overlay the confetti and dialog on top of.
     * @param coffeeIcon Optional [ImageView] of the coffee icon to animate (can be null).
     */
    fun celebrate(
        activity: Activity,
        sku: String,
        rootView: ViewGroup,
        coffeeIcon: ImageView? = null,
        onDismissed: (() -> Unit)? = null
    ) {
        val ctx = activity.applicationContext

        val colors = when (sku) {
            SKU_ESPRESSO -> COLORS_ESPRESSO
            SKU_LATTE    -> COLORS_LATTE
            SKU_BEANS    -> COLORS_BEANS
            else          -> COLORS_LATTE
        }

        val title = when (sku) {
            SKU_ESPRESSO -> ctx.getString(R.string.tip_celebrate_espresso_title)
            SKU_LATTE    -> ctx.getString(R.string.tip_celebrate_latte_title)
            SKU_BEANS    -> ctx.getString(R.string.tip_celebrate_beans_title)
            else          -> ctx.getString(R.string.tip_celebrate_espresso_title)
        }

        fireHaptic(activity)

        // Create a full-screen FrameLayout overlay so Gravity.CENTER always works,
        // regardless of whether rootView is a ConstraintLayout or FrameLayout.
        val overlay = FrameLayout(activity).apply {
            isClickable = false
            isFocusable = false
        }
        val overlayLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        rootView.addView(overlay, overlayLp)

        launchConfetti(rootView, colors, overlay)
        coffeeIcon?.let { animateCoffeeIcon(it) }
        showCelebrationCard(activity, rootView, overlay, title, onDismissed)
    }

    // ─── Haptic feedback ─────────────────────────────────────────────────────

    private fun fireHaptic(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as VibratorManager
                manager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 80, 40, 120), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 80, 40, 120), -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 40, 60, 80, 40, 120), -1)
                }
            }
        } catch (_: Exception) { /* vibration is non-critical */ }
    }

    // ─── Confetti ─────────────────────────────────────────────────────────────

    private fun launchConfetti(root: ViewGroup, colors: IntArray, overlay: FrameLayout) {
        val confettiView = ConfettiView(root.context, colors, durationMs = 3_500L)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlay.addView(confettiView, lp)
        confettiView.start(count = 90)

        // Auto-remove after animation
        confettiView.postDelayed({ confettiView.stop() }, 3_800L)
    }

    // ─── Coffee icon pulse animation ─────────────────────────────────────────

    private fun animateCoffeeIcon(icon: ImageView) {
        val scaleUpX  = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.35f)
        val scaleUpY  = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.35f)
        val scaleDownX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1.35f, 1f)
        val scaleDownY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1.35f, 1f)

        val up = AnimatorSet().apply {
            playTogether(scaleUpX, scaleUpY)
            duration = 200
        }
        val down = AnimatorSet().apply {
            playTogether(scaleDownX, scaleDownY)
            duration = 350
            interpolator = OvershootInterpolator(3f)
        }

        AnimatorSet().apply {
            playSequentially(up, down)
            start()
        }
    }

    // ─── Celebration card dialog ──────────────────────────────────────────────

    @SuppressLint("InflateParams")
    private fun showCelebrationCard(
        activity: Activity,
        root: ViewGroup,
        overlay: FrameLayout,
        title: String,
        onDismissed: (() -> Unit)? = null
    ) {
        val ctx = activity

        // Semi-transparent scrim
        val scrim = View(ctx).apply {
            setBackgroundColor(
                applyAlpha(ContextCompat.getColor(ctx, R.color.ufm_background), 0.45f)
            )
            alpha = 0f
            isClickable = true // consume touches on the scrim
        }
        val scrimLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlay.addView(scrim, scrimLp)

        // Card
        val card = MaterialCardView(ctx).apply {
            radius = dpToPx(ctx, 20f)
            cardElevation = dpToPx(ctx, 16f)
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.dialog_glass_bg))
            strokeWidth = 0
        }

        val cardContent = FrameLayout(ctx)
        val contentPad = dpToPx(ctx, 28f).toInt()
        cardContent.setPadding(contentPad, contentPad, contentPad, contentPad)

        val inner = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // Coffee icon inside card
        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_coffee)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.ufm_primary)
            )
            val iconSize = dpToPx(ctx, 56f).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize).also {
                it.bottomMargin = dpToPx(ctx, 16f).toInt()
            }
        }

        val titleTv = TextView(ctx).apply {
            text = title
            textSize = 20f
            setTextColor(ContextCompat.getColor(ctx, R.color.ufm_text_primary))
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(ctx, 10f).toInt() }
        }

        val subtitleTv = TextView(ctx).apply {
            text = ctx.getString(R.string.tip_celebrate_subtitle)
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.ufm_text_secondary))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
        }

        inner.addView(icon)
        inner.addView(titleTv)
        inner.addView(subtitleTv)
        cardContent.addView(inner)
        card.addView(cardContent)

        val cardW = minOf(dpToPx(ctx, 320f).toInt(), root.width - dpToPx(ctx, 48f).toInt())
        val cardLp = FrameLayout.LayoutParams(cardW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        overlay.addView(card, cardLp)

        // Animate: slide up + fade in
        card.translationY = dpToPx(ctx, 80f)
        card.alpha = 0f

        val slideIn = ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, dpToPx(ctx, 80f), 0f).apply {
            duration = 420
            interpolator = OvershootInterpolator(1.5f)
        }
        val fadeInCard  = ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f).apply { duration = 280 }
        val fadeInScrim = ObjectAnimator.ofFloat(scrim, View.ALPHA, 0f, 1f).apply { duration = 280 }

        AnimatorSet().apply {
            playTogether(slideIn, fadeInCard, fadeInScrim)
            start()
        }

        // Dismiss on scrim tap or after 4.5 s
        val dismiss = { dismissCard(card, scrim, overlay, root, onDismissed) }
        scrim.setOnClickListener { dismiss() }
        card.setOnClickListener { dismiss() }
        root.postDelayed({ dismiss() }, 4_500L)
    }

    private fun dismissCard(card: View, scrim: View, overlay: FrameLayout, root: ViewGroup, onDismissed: (() -> Unit)? = null) {
        val fadeOut = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card,  View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(scrim, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(card,  View.TRANSLATION_Y, 0f, dpToPx(card.context, 40f))
            )
            duration = 280
        }
        fadeOut.start()
        root.postDelayed({
            root.removeView(overlay)
            onDismissed?.invoke()
        }, 300L)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun dpToPx(ctx: Context, dp: Float): Float =
        dp * ctx.resources.displayMetrics.density

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
