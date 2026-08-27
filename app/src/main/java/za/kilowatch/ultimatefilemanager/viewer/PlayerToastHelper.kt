package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.PlayerPreferencesManager

/**
 * Renders a professional pill-style toast for player control feedback.
 *
 * Used for FR-19/FR-20: every player control button press shows a brief toast.
 * Callers pass already-resolved (localised) strings — no hardcoded English here.
 */
object PlayerToastHelper {

    fun show(context: Context, message: String, force: Boolean = false) {
        if (!force && !PlayerPreferencesManager.isButtonToastsEnabled(context)) {
            return
        }
        val density = context.resources.displayMetrics.density
        val toastView = LayoutInflater.from(context).inflate(R.layout.view_player_toast, null, false)
        toastView.findViewById<TextView>(R.id.toastText).text = message

        val bg = GradientDrawable().apply {
            cornerRadius = 28f * density
            setColor(Color.parseColor("#CC1B1B1B"))
            setStroke((1f * density).toInt(), Color.parseColor("#33FFFFFF"))
        }
        toastView.background = bg

        @Suppress("DEPRECATION")
        val toast = Toast(context).apply {
            duration = Toast.LENGTH_SHORT
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (200 * density).toInt())
            view = toastView
        }
        toast.show()
    }
}
