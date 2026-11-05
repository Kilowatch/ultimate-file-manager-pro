package za.kilowatch.ultimatefilemanager.shortcuts

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * UFMStandard glass alert dialog shown when a home-screen shortcut target is missing, moved,
 * or on unmounted external storage.
 */
object FavShortcutErrorSheet {

    fun show(activity: Activity, customMessage: String? = null, onDismiss: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) {
            onDismiss()
            return
        }

        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.sheet_fav_shortcut_error_tv else R.layout.sheet_fav_shortcut_error
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        if (customMessage != null) {
            dialogView.findViewById<TextView>(R.id.txtMessage)?.text = customMessage
        }

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { onDismiss() }

        dialogView.findViewById<View>(R.id.btnOk)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        if (isTv) {
            dialogView.findViewById<View>(R.id.btnOk)?.requestFocus()
        }
    }
}
