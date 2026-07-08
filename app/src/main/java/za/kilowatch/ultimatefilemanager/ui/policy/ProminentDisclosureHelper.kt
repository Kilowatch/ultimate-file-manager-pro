package za.kilowatch.ultimatefilemanager.ui.policy

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Helper for the Google Play-required Prominent Disclosure
 * for Installed Application Information (QUERY_ALL_PACKAGES).
 *
 * Shows a one-time dialog before the user can access the App Manager
 * or Debloater features, listing exactly what data is read, why, and
 * that it stays on-device.
 */
object ProminentDisclosureHelper {

    private const val PREFS_NAME = "acceptance_prefs"
    private const val KEY_ACCEPTED_TIME = "prominent_disclosure_accepted_time"

    /**
     * @return true if the user has already accepted the prominent disclosure.
     */
    fun isAccepted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_ACCEPTED_TIME, 0L) > 0L
    }

    /**
     * Persists the current timestamp as acceptance time.
     */
    fun markAccepted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ACCEPTED_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * If the user has already accepted, [onContinue] is called immediately.
     * Otherwise a modal dialog is shown with the full disclosure text.
     *
     * @param activity The hosting Activity (needed for the dialog).
     * @param onContinue Called after the user taps "I Understand".
     * @param onCancel Called when the user taps "Cancel" or presses Back.
     */
    fun showIfNeeded(
        activity: Activity,
        onContinue: () -> Unit,
        onCancel: () -> Unit
    ) {
        if (isAccepted(activity)) {
            onContinue()
            return
        }

        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.dialog_prominent_disclosure_tv
        else R.layout.dialog_prominent_disclosure

        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)


        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener { onCancel() }
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        // Wire buttons
        dialogView.findViewById<MaterialButton>(R.id.btnPdAccept).setOnClickListener {
            markAccepted(activity)
            dialog.dismiss()
            onContinue()
        }

        dialogView.findViewById<MaterialButton>(R.id.btnPdCancel).setOnClickListener {
            dialog.dismiss()
            onCancel()
        }

        // TV focus styling
        if (isTv) {
            val yellow = activity.getColor(R.color.tv_button_focused_yellow)
            val yellowText = activity.getColor(R.color.tv_button_focused_yellow_text)
            val white = activity.getColor(R.color.tv_text_primary)
            val glass = 0x26FFFFFF.toInt()
            val yellowCsl = ColorStateList.valueOf(yellow)
            val glassCsl = ColorStateList.valueOf(glass)

            val btnAccept = dialogView.findViewById<MaterialButton>(R.id.btnPdAccept)
            val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnPdCancel)

            // Accept button: yellow default, focus retains yellow
            btnAccept.backgroundTintList = yellowCsl
            btnAccept.setTextColor(yellowText)
            btnAccept.setOnFocusChangeListener { _, hasFocus ->
                btnAccept.backgroundTintList = if (hasFocus) yellowCsl else yellowCsl
                btnAccept.setTextColor(if (hasFocus) yellowText else yellowText)
            }
            btnAccept.requestFocus()

            // Cancel button: glass default, yellow on focus
            btnCancel.backgroundTintList = glassCsl
            btnCancel.setTextColor(white)
            btnCancel.setOnFocusChangeListener { _, hasFocus ->
                btnCancel.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnCancel.setTextColor(if (hasFocus) yellowText else white)
            }
        }
    }
}
