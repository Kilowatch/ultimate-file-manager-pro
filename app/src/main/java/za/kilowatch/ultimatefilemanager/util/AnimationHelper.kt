package za.kilowatch.ultimatefilemanager.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.app.ActivityOptionsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import za.kilowatch.ultimatefilemanager.R

/**
 * Utility helper providing centralized modern animation and transition methods for UFM Pro.
 * Handles mobile folder navigation transitions, staggered item cascades, activity window animations,
 * and view mode layout morphing.
 */
object AnimationHelper {

    private const val PREFS = "ufm_prefs"
    const val KEY_ENABLE_FOLDER_TRANSITIONS = "pref_folder_transitions"

    /**
     * Checks if folder transitions are enabled.
     * Returns false on Android TV devices or when turned off in App Settings.
     */
    fun areFolderTransitionsEnabled(context: Context): Boolean {
        if (DeviceUtils.isTvDevice(context)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLE_FOLDER_TRANSITIONS, true)
    }

    /**
     * Sets whether folder transitions are enabled on mobile.
     */
    fun setFolderTransitionsEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLE_FOLDER_TRANSITIONS, enabled).apply()
    }

    /**
     * Executes a fluid directional folder transition on a RecyclerView.
     *
     * @param recyclerView The RecyclerView containing the folder file list.
     * @param isForward True when entering a subfolder (slides left), false when navigating up (slides right).
     * @param onTransitionMiddle Callback to update the adapter data mid-transition.
     */
    fun animateFolderTransition(
        recyclerView: RecyclerView,
        isForward: Boolean,
        onTransitionMiddle: () -> Unit
    ) {
        val context = recyclerView.context
        if (!areFolderTransitionsEnabled(context) || !recyclerView.isAttachedToWindow) {
            onTransitionMiddle()
            return
        }

        val width = recyclerView.width.toFloat()
        if (width <= 0f) {
            onTransitionMiddle()
            return
        }

        // Outgoing direction: forward -> slide out to left (-25%), backward -> slide out to right (+25%)
        val exitTranslationX = if (isForward) -width * 0.25f else width * 0.25f
        val entryStartTranslationX = if (isForward) width * 0.25f else -width * 0.25f

        recyclerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        recyclerView.animate()
            .translationX(exitTranslationX)
            .alpha(0f)
            .setDuration(110)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                if (!recyclerView.isAttachedToWindow) {
                    recyclerView.setLayerType(View.LAYER_TYPE_NONE, null)
                    return@withEndAction
                }
                // Swap adapter items at the midpoint of animation
                onTransitionMiddle()

                // Reset position for incoming entrance animation
                recyclerView.translationX = entryStartTranslationX
                recyclerView.alpha = 0f

                // Load staggered item entrance animation
                try {
                    val controller = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_staggered_slide_in)
                    recyclerView.layoutAnimation = controller
                    recyclerView.scheduleLayoutAnimation()
                } catch (_: Exception) { /* Fallback gracefully if layoutAnimation fails */ }

                recyclerView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(180)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .withEndAction {
                        recyclerView.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                    .start()
            }
            .start()
    }

    /**
     * Launches an Activity with smooth custom slide & fade window transitions on mobile.
     */
    fun startActivityWithTransition(
        activity: Activity,
        intent: Intent,
        isFinishCurrent: Boolean = false,
        requestCode: Int? = null
    ) {
        if (!areFolderTransitionsEnabled(activity)) {
            if (requestCode != null) {
                activity.startActivityForResult(intent, requestCode)
            } else {
                activity.startActivity(intent)
            }
            if (isFinishCurrent) activity.finish()
            return
        }

        val options = ActivityOptionsCompat.makeCustomAnimation(
            activity,
            R.anim.ufm_slide_in_right,
            R.anim.ufm_slide_out_left
        )

        if (requestCode != null) {
            activity.startActivityForResult(intent, requestCode, options.toBundle())
        } else {
            activity.startActivity(intent, options.toBundle())
        }

        if (isFinishCurrent) {
            activity.finish()
        }
    }

    /**
     * Applies custom close window transition when an Activity finishes or handles back press.
     */
    fun applyActivityCloseTransition(activity: Activity) {
        if (areFolderTransitionsEnabled(activity)) {
            activity.overridePendingTransition(R.anim.ufm_slide_in_left, R.anim.ufm_slide_out_right)
        }
    }

    /**
     * Animates layout changes smoothly when toggling view modes (List <-> Grid) or changing list/grid sizes.
     */
    fun animateViewModeSwitch(recyclerView: RecyclerView, onApplyMode: () -> Unit) {
        val context = recyclerView.context
        if (!areFolderTransitionsEnabled(context) || !recyclerView.isAttachedToWindow) {
            onApplyMode()
            return
        }

        recyclerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        recyclerView.animate()
            .alpha(0.3f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(120)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                if (!recyclerView.isAttachedToWindow) {
                    recyclerView.setLayerType(View.LAYER_TYPE_NONE, null)
                    return@withEndAction
                }
                onApplyMode()
                recyclerView.animate()
                    .alpha(1.0f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(180)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .withEndAction {
                        recyclerView.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                    .start()
            }
            .start()
    }
}
