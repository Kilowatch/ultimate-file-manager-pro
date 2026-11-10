package za.kilowatch.ultimatefilemanager.download

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Helper to display the mandatory First-Use Legal Gate and Non-Piracy Notice
 * adhering strictly to UFMStandard frosted glass design on both Mobile and Android TV.
 */
object DownloadLegalGateHelper {

    /**
     * Checks if the user accepted the legal gate. If not, displays the interactive gate dialog.
     * Invokes [onAccepted] once accepted. If the user cancels or dismisses, finishes the hosting activity.
     */
    fun checkAndGate(activity: Activity, onAccepted: () -> Unit) {
        if (DownloadPreferencesManager.isLegalAccepted(activity)) {
            onAccepted()
            return
        }

        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.dialog_download_legal_gate_tv else R.layout.dialog_download_legal_gate
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        val cardAgree = dialogView.findViewById<View>(R.id.cardLegalAgree)
        val checkAgree = dialogView.findViewById<MaterialCheckBox>(R.id.checkLegalAgree)
        val btnContinue = dialogView.findViewById<MaterialButton>(R.id.btnLegalContinue)
        val btnCancel = dialogView.findViewById<View>(R.id.btnLegalCancel)

        var dialog: AlertDialog? = null

        cardAgree?.setOnClickListener {
            checkAgree?.let { cb ->
                cb.isChecked = !cb.isChecked
            }
        }

        if (isTv) {
            btnContinue?.alpha = 0.4f
        }

        checkAgree?.setOnCheckedChangeListener { _, isChecked ->
            btnContinue?.isEnabled = isChecked
            if (isTv) {
                btnContinue?.alpha = if (isChecked) 1.0f else 0.4f
            }
        }

        btnCancel?.setOnClickListener {
            dialog?.dismiss()
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.finish()
            }
        }

        btnContinue?.setOnClickListener {
            DownloadPreferencesManager.setLegalAccepted(activity, true)
            dialog?.dismiss()
            onAccepted()
        }

        dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.finish()
                }
            }
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        if (isTv) {
            val displayMetrics = activity.resources.displayMetrics
            val targetWidth = minOf(
                (displayMetrics.widthPixels * 0.75).toInt(),
                (740 * displayMetrics.density).toInt()
            )
            dialog.window?.setLayout(
                targetWidth,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            cardAgree?.requestFocus()
        }
    }

    /**
     * Shows a read-only informational legal notice from settings or menu.
     * Adheres strictly to UFMStandard frosted glass layout on Mobile and Android TV.
     */
    fun showLegalNoticeDialog(context: Context) {
        val isTv = DeviceUtils.isTvDevice(context)
        val layoutRes = if (isTv) R.layout.dialog_download_legal_gate_tv else R.layout.dialog_download_legal_gate
        val dialogView = LayoutInflater.from(context).inflate(layoutRes, null)

        val cardAgree = dialogView.findViewById<View>(R.id.cardLegalAgree)
        val btnContinue = dialogView.findViewById<MaterialButton>(R.id.btnLegalContinue)
        val btnCancel = dialogView.findViewById<View>(R.id.btnLegalCancel)

        // Read-only configuration
        cardAgree?.visibility = View.GONE
        btnCancel?.visibility = View.GONE
        btnContinue?.isEnabled = true
        if (isTv) {
            btnContinue?.alpha = 1.0f
        }
        btnContinue?.text = context.getString(R.string.download_legal_understood)

        var dialog: AlertDialog? = null

        btnContinue?.setOnClickListener {
            dialog?.dismiss()
        }

        dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        if (isTv) {
            val displayMetrics = context.resources.displayMetrics
            val targetWidth = minOf(
                (displayMetrics.widthPixels * 0.75).toInt(),
                (740 * displayMetrics.density).toInt()
            )
            dialog.window?.setLayout(
                targetWidth,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            btnContinue?.requestFocus()
        }
    }
}
