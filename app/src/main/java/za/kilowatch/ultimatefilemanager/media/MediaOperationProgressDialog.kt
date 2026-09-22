package za.kilowatch.ultimatefilemanager.media

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * UFMStandard indeterminate progress popup dialog for media operations
 * (FFmpeg video conversion, subtitle extraction, audio extraction).
 *
 * Implements UFM glass styling, dual Mobile/TV layouts, animated circular progress,
 * and hero icon badge.
 */
class MediaOperationProgressDialog(
    private val context: Context,
    private val titleText: String,
    private val subtitleText: String,
    private val iconRes: Int = R.drawable.ic_convert_video
) {
    private val isTv = DeviceUtils.isTvDevice(context)
    private var dialog: AlertDialog? = null
    private var txtSubtitle: TextView? = null

    fun show() {
        val layoutRes = if (isTv) R.layout.dialog_media_action_progress_tv else R.layout.dialog_media_action_progress
        val view = LayoutInflater.from(context).inflate(layoutRes, null)
        val imgIcon = view.findViewById<ImageView>(R.id.imgMediaProgressIcon)
        val txtTitle = view.findViewById<TextView>(R.id.txtProgressTitle)
        txtSubtitle = view.findViewById(R.id.txtProgressSubtitle)

        imgIcon?.setImageResource(iconRes)
        txtTitle?.text = titleText
        txtSubtitle?.text = subtitleText

        dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(view)
            .setCancelable(false)
            .create()

        try {
            dialog?.show()
            dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        } catch (_: Exception) {}
    }

    fun updateSubtitle(newSubtitle: String) {
        (context as? Activity)?.runOnUiThread {
            txtSubtitle?.text = newSubtitle
        } ?: run {
            txtSubtitle?.text = newSubtitle
        }
    }

    fun dismiss() {
        try {
            dialog?.dismiss()
        } catch (_: Exception) {}
        dialog = null
    }
}
