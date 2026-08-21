package za.kilowatch.ultimatefilemanager.indexing

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Shared UI helper for indexing dialogs.
 * Used by StorageBrowserActivity and future SearchActivity integration.
 */
object IndexingUiHelper {

    fun showIndexingOfferDialog(
        activity: Activity,
        storageLabel: String,
        storageId: String,
        onIndexNow: () -> Unit,
        onNotNow: () -> Unit
    ) {
        val isTv = DeviceUtils.isTvDevice(activity)
        if (isTv) {
            val yellow = try { activity.getColor(R.color.tv_button_focused_yellow) } catch (_: Exception) { 0xFFFFCC00.toInt() }
            val white  = try { activity.getColor(R.color.tv_text_primary) } catch (_: Exception) { 0xFFFFFFFF.toInt() }
            val black  = try { activity.getColor(R.color.tv_button_focused_yellow_text) } catch (_: Exception) { 0xFF000000.toInt() }
            val glass  = 0x26FFFFFF.toInt()

            val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                .setTitle(R.string.blazingfast_browsing)
                .setMessage(
                    "Indexing \"$storageLabel\" enables:\n\n" +
                    "• Instant search across all files\n" +
                    "• Faster folder loading\n" +
                    "• Duplicate file detection\n\n" +
                    activity.getString(R.string.this_only_needs_to_be_done_once_future_opens_are_always_incremental)
                )
                .setPositiveButton(activity.getString(R.string.index_now_recommended)) { _, _ -> onIndexNow() }
                .setNegativeButton(activity.getString(R.string.not_now)) { _, _ ->
                    IndexingRepository.getInstance(activity).setUserDeclinedIndexing(storageId)
                    showIndexingReminderDialog(activity, onNotNow)
                }
                .create()

            dialog.show()

            val yellowCsl = ColorStateList.valueOf(yellow)
            val glassCsl  = ColorStateList.valueOf(glass)

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                backgroundTintList = yellowCsl
                setTextColor(black)
                setOnFocusChangeListener { _, hasFocus ->
                    backgroundTintList = if (hasFocus) ColorStateList.valueOf(activity.getColor(R.color.tv_button_focused_yellow)) else yellowCsl
                }
                requestFocus()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                backgroundTintList = glassCsl
                setTextColor(white)
                setOnFocusChangeListener { _, hasFocus ->
                    backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                    setTextColor(if (hasFocus) black else white)
                }
            }
        } else {
            val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_indexing_offer, null)
            val txtStorage = dialogView.findViewById<TextView>(R.id.txtStorageLabel)
            txtStorage.text = storageLabel

            val btnIndexNow = dialogView.findViewById<android.view.View>(R.id.btnIndexNow)
            val btnNotNow = dialogView.findViewById<android.view.View>(R.id.btnNotNow)

            val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                .setView(dialogView)
                .create()

            btnIndexNow.setOnClickListener {
                dialog.dismiss()
                onIndexNow()
            }

            btnNotNow.setOnClickListener {
                dialog.dismiss()
                IndexingRepository.getInstance(activity).setUserDeclinedIndexing(storageId)
                showIndexingReminderDialog(activity, onNotNow)
            }

            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    fun showIndexingProgressDialog(
        activity: Activity,
        storageLabel: String,
        storageId: String,
        storagePath: String,
        storageType: String,
        onComplete: () -> Unit,
        onCancel: () -> Unit
    ) {
        val isTv = DeviceUtils.isTvDevice(activity)
        val dialogView = LayoutInflater.from(activity).inflate(
            if (isTv) R.layout.dialog_indexing_progress_tv else R.layout.dialog_indexing_progress,
            null
        )

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val txtProgressStats = dialogView.findViewById<TextView>(R.id.txtProgressStats)
        val btnRunBackground = dialogView.findViewById<MaterialButton>(R.id.btnRunBackground)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        val indexingManager = IndexingManager.getInstance(activity)

        btnCancel.setOnClickListener {
            indexingManager.stopIndexing()
            dialog.dismiss()
            onCancel()
        }

        btnRunBackground.setOnClickListener {
            dialog.dismiss()
            onComplete()
        }

        dialog.show()
        if (isTv) btnCancel.requestFocus()

        indexingManager.startBackgroundIndexing(
            storageId = storageId,
            storagePath = storagePath,
            storageType = storageType,
            onProgress = { current, _ ->
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed && dialog.isShowing) {
                        txtProgressStats.text = "Indexed $current Files"
                    }
                }
            },
            onComplete = {
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed && dialog.isShowing) {
                        dialog.dismiss()
                        onComplete()
                    }
                }
            },
            onError = { e ->
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed && dialog.isShowing) {
                        txtProgressStats.text = "Error: ${e.message}"
                        val errColor = if (isTv) R.color.tv_error_red else R.color.status_error
                        txtProgressStats.setTextColor(activity.getColor(errColor))
                        btnRunBackground.setText(R.string.continue_anyway)
                    }
                }
            }
        )
    }

    fun showDeletionProgressDialog(
        activity: Activity,
        folderName: String,
        isIndexing: Boolean = true
    ): androidx.appcompat.app.AlertDialog {
        val isTv = DeviceUtils.isTvDevice(activity)
        val dialogView = LayoutInflater.from(activity).inflate(
            if (isTv) R.layout.dialog_deletion_progress_tv else R.layout.dialog_deletion_progress,
            null
        )

        val txtDescription = dialogView.findViewById<TextView>(R.id.txtDescription)
        if (folderName.isNotEmpty()) {
            if (isIndexing) {
                txtDescription.text = activity.getString(R.string.deleting_folder_progress, folderName)
            } else {
                txtDescription.text = activity.getString(R.string.deleting_folder_only_progress, folderName)
            }
        } else {
            if (isIndexing) {
                txtDescription.setText(R.string.cleaning_up_index)
            } else {
                txtDescription.setText(R.string.deleting_items_progress)
            }
        }

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        return dialog
    }

    fun showIndexingReminderDialog(activity: Activity, onDismiss: () -> Unit) {
        val isTv = DeviceUtils.isTvDevice(activity)
        if (isTv) {
            val yellow = try { activity.getColor(R.color.tv_button_focused_yellow) } catch (_: Exception) { 0xFFFFCC00.toInt() }
            val black  = try { activity.getColor(R.color.tv_button_focused_yellow_text) } catch (_: Exception) { 0xFF000000.toInt() }

            val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                .setTitle(R.string.storage_indexer_reminder_title)
                .setMessage(R.string.storage_indexer_reminder_message)
                .setPositiveButton(R.string.btn_ok) { d, _ -> 
                    d.dismiss()
                    onDismiss()
                }
                .setOnCancelListener { 
                    onDismiss()
                }
                .create()

            dialog.show()

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                backgroundTintList = ColorStateList.valueOf(yellow)
                setTextColor(black)
                setOnFocusChangeListener { _, hasFocus ->
                    backgroundTintList = if (hasFocus) ColorStateList.valueOf(activity.getColor(R.color.tv_button_focused_yellow)) else ColorStateList.valueOf(yellow)
                }
                requestFocus()
            }
        } else {
            val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_indexing_reminder, null)
            val btnOk = dialogView.findViewById<android.view.View>(R.id.btnOk)

            val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                .setView(dialogView)
                .setOnCancelListener { onDismiss() }
                .create()

            btnOk.setOnClickListener {
                dialog.dismiss()
                onDismiss()
            }

            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }
}
