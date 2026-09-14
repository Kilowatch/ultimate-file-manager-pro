package za.kilowatch.ultimatefilemanager.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper

/**
 * Safely dismisses a dialog, verifying that the host Activity is neither finishing nor destroyed,
 * and catching any WindowManager [IllegalArgumentException] (e.g. "View not attached to window manager").
 */
fun Dialog?.safeDismiss(activity: Activity? = null) {
    if (this == null) return
    try {
        val hostActivity = activity ?: resolveActivity(context)
        if (hostActivity != null && (hostActivity.isFinishing || hostActivity.isDestroyed)) {
            return
        }
        if (isShowing) {
            dismiss()
        }
    } catch (_: IllegalArgumentException) {
        // Ignored: View not attached to window manager
    } catch (_: Exception) {
        // Ignored: Host window state invalid
    }
}

private fun resolveActivity(context: Context?): Activity? {
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
