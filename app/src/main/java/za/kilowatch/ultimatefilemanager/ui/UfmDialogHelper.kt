package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Universal UFMStandard dialog helper providing canonical glassmorphism dialogs
 * across Mobile and Android TV.
 */
object UfmDialogHelper {

    /**
     * Shows a UFMStandard action confirmation or informational dialog.
     */
    fun showConfirmation(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        iconRes: Int = R.drawable.ic_warning,
        positiveText: CharSequence = context.getString(R.string.btn_ok),
        negativeText: CharSequence? = context.getString(R.string.cancel),
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null
    ): AlertDialog {
        val isTv = DeviceUtils.isTvDevice(context)
        val layoutRes = if (isTv) R.layout.dialog_confirm_action_tv else R.layout.dialog_confirm_action
        val dialogView = LayoutInflater.from(context).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<ImageView>(R.id.imgHeroIcon)?.setImageResource(iconRes)
        dialogView.findViewById<TextView>(R.id.txtTitle)?.text = title
        dialogView.findViewById<TextView>(R.id.txtMessage)?.text = message

        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirm)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        btnConfirm?.apply {
            text = positiveText
            setOnClickListener {
                dialog.dismiss()
                onPositive()
            }
        }

        if (negativeText != null) {
            btnCancel?.apply {
                visibility = View.VISIBLE
                text = negativeText
                setOnClickListener {
                    dialog.dismiss()
                    onNegative?.invoke()
                }
            }
        } else {
            btnCancel?.visibility = View.GONE
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    /**
     * Shows a UFMStandard unsaved changes prompt with Save, Discard, and Cancel options.
     */
    fun showUnsavedChanges(
        context: Context,
        onSave: () -> Unit,
        onDiscard: () -> Unit,
        onCancel: (() -> Unit)? = null
    ): AlertDialog {
        val isTv = DeviceUtils.isTvDevice(context)
        val layoutRes = if (isTv) R.layout.dialog_unsaved_changes_tv else R.layout.dialog_unsaved_changes
        val dialogView = LayoutInflater.from(context).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnSave)?.setOnClickListener {
            dialog.dismiss()
            onSave()
        }
        dialogView.findViewById<View>(R.id.btnDiscard)?.setOnClickListener {
            dialog.dismiss()
            onDiscard()
        }
        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    /**
     * Shows a UFMStandard "Go to Path" input dialog.
     */
    fun showGoToPath(
        context: Context,
        initialPath: String? = null,
        onPathSelected: (String) -> Unit
    ): AlertDialog {
        val isTv = DeviceUtils.isTvDevice(context)
        val layoutRes = if (isTv) R.layout.dialog_go_to_path_tv else R.layout.dialog_go_to_path
        val dialogView = LayoutInflater.from(context).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val edtPath = dialogView.findViewById<TextInputEditText>(R.id.edtPath)
        initialPath?.let {
            edtPath?.setText(it)
            edtPath?.setSelection(it.length)
        }

        val btnGo = dialogView.findViewById<MaterialButton>(R.id.btnGo)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        btnGo?.setOnClickListener {
            val path = edtPath?.text?.toString()?.trim().orEmpty()
            if (path.isNotEmpty()) {
                dialog.dismiss()
                onPathSelected(path)
            }
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    /**
     * Shows a UFMStandard Playback Speed selection dialog with glass rows.
     */
    fun showPlaybackSpeed(
        context: Context,
        currentSpeed: Float,
        onSpeedSelected: (Float) -> Unit
    ): AlertDialog {
        val isTv = DeviceUtils.isTvDevice(context)
        val layoutRes = if (isTv) R.layout.dialog_playback_speed_tv else R.layout.dialog_playback_speed
        val dialogView = LayoutInflater.from(context).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 4.0f)
        val labels = arrayOf("0.25x", "0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x", "4.0x")

        val rvSpeeds = dialogView.findViewById<RecyclerView>(R.id.rvSpeeds)
        rvSpeeds?.layoutManager = LinearLayoutManager(context)

        class SpeedAdapter : RecyclerView.Adapter<SpeedAdapter.ViewHolder>() {
            inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
                val txtLabel: TextView = v.findViewById(R.id.txtSpeedLabel)
                val imgCheck: ImageView = v.findViewById(R.id.imgCheck)
            }

            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
                val itemLayout = if (isTv) R.layout.item_playback_speed_tv else R.layout.item_playback_speed
                val v = LayoutInflater.from(parent.context).inflate(itemLayout, parent, false)
                return ViewHolder(v)
            }

            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                val speed = speeds[position]
                holder.txtLabel.text = labels[position]
                val isSelected = kotlin.math.abs(speed - currentSpeed) < 0.05f
                holder.imgCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    onSpeedSelected(speed)
                }
            }

            override fun getItemCount(): Int = speeds.size
        }

        rvSpeeds?.adapter = SpeedAdapter()
        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    /**
     * Shows a UFMStandard App Details & Extraction dialog.
     */
    fun showAppDetails(
        context: Context,
        appName: String,
        packageName: String,
        appIcon: Drawable?,
        details: CharSequence,
        onExtract: () -> Unit,
        onAppInfo: () -> Unit
    ): AlertDialog {
        val isTv = DeviceUtils.isTvDevice(context)
        val layoutRes = if (isTv) R.layout.dialog_app_details_tv else R.layout.dialog_app_details
        val dialogView = LayoutInflater.from(context).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<ImageView>(R.id.imgAppIcon)?.setImageDrawable(appIcon)
        dialogView.findViewById<TextView>(R.id.txtAppName)?.text = appName
        dialogView.findViewById<TextView>(R.id.txtAppDetails)?.text = details

        dialogView.findViewById<View>(R.id.btnExtract)?.setOnClickListener {
            dialog.dismiss()
            onExtract()
        }
        dialogView.findViewById<View>(R.id.btnAppInfo)?.setOnClickListener {
            dialog.dismiss()
            onAppInfo()
        }
        dialogView.findViewById<View>(R.id.btnClose)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }
}
