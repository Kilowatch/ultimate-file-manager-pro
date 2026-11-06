package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import java.io.File

/**
 * Helper for presenting the mobile long-press triage dialog in UFM Viewer and Slide Show,
 * handling folder picker result intents, and dispatching atomic transfers via [ViewerTransferExecutor].
 */
object ViewerTransferHelper {

    /**
     * Extracts a chosen destination folder from a picker result Intent, persists it to
     * [ViewerTransferPreferenceManager], and returns the persisted [ViewerTransferPreferenceManager.ViewerDestination].
     */
    fun handlePickerResult(context: Context, data: Intent): ViewerTransferPreferenceManager.ViewerDestination? {
        val netShareId = data.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_SHARE_ID)
            ?: data.getStringExtra(StorageBrowserActivity.RESULT_SELECTED_SHARE_ID)
        val netPath = data.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_NET_PATH)
            ?: data.getStringExtra(StorageBrowserActivity.RESULT_SELECTED_NET_PATH)
        val localPath = data.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
            ?: data.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)

        if (!netShareId.isNullOrEmpty()) {
            val share = NetworkShareRepository.getInstance(context).getById(netShareId)
                ?: za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(context).getById(netShareId)?.toNetworkShare()
            val shareName = share?.name ?: context.getString(R.string.storage_network)
            val folderName = if (!netPath.isNullOrEmpty()) {
                netPath.trimEnd('/').substringAfterLast('/')
            } else {
                shareName
            }
            val destination = ViewerTransferPreferenceManager.ViewerDestination(
                isNetwork = true,
                shareId = netShareId,
                netPath = netPath ?: "",
                displayName = folderName.ifEmpty { shareName }
            )
            ViewerTransferPreferenceManager.setDestination(context, destination)
            return destination
        } else if (!localPath.isNullOrEmpty()) {
            val folderName = File(localPath).name.ifEmpty { localPath }
            val destination = ViewerTransferPreferenceManager.ViewerDestination(
                isNetwork = false,
                localPath = localPath,
                displayName = folderName
            )
            ViewerTransferPreferenceManager.setDestination(context, destination)
            return destination
        }

        return null
    }

    /**
     * Creates an Intent to launch the standard UFM destination picker via [StorageBrowserActivity].
     */
    fun createPickerIntent(context: Context): Intent {
        return Intent(context, StorageBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_VIEWER_DEST_PICKER, true)
        }
    }

    /**
     * Displays the glassmorphic UFMStandard triage transfer dialog.
     *
     * @param activity Hosting Activity.
     * @param fileName Simple file name of the current item.
     * @param srcLocalFile File object if local or SAF storage, or null if network.
     * @param srcShare NetworkShare if network storage, or null if local/SAF.
     * @param srcNetPath Relative path inside the share if network storage, or null if local/SAF.
     * @param onRequestPickDestination Called when the user needs to select or change the target folder.
     * @param onMoveSuccess Called when a Move operation finishes successfully (triggers post-move navigation).
     * @param onDismissOrCancel Called when the dialog is dismissed without triggering a move.
     */
    fun showTransferDialog(
        activity: AppCompatActivity,
        fileName: String,
        srcLocalFile: File?,
        srcShare: NetworkShare?,
        srcNetPath: String?,
        onRequestPickDestination: () -> Unit,
        onMoveSuccess: () -> Unit,
        onDismissOrCancel: () -> Unit = {}
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_viewer_transfer, null)

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtTransferTitle)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtTransferSubtitle)
        val cardDestination = dialogView.findViewById<LinearLayout>(R.id.cardDestination)
        val txtDestFolderName = dialogView.findViewById<TextView>(R.id.txtDestFolderName)
        val txtDestPath = dialogView.findViewById<TextView>(R.id.txtDestPath)
        val btnChangeDestination = dialogView.findViewById<MaterialButton>(R.id.btnChangeDestination)
        val btnMoveToDestination = dialogView.findViewById<MaterialButton>(R.id.btnMoveToDestination)
        val btnCopyToDestination = dialogView.findViewById<MaterialButton>(R.id.btnCopyToDestination)
        val btnCancelTransfer = dialogView.findViewById<MaterialButton>(R.id.btnCancelTransfer)

        txtTitle?.text = activity.getString(R.string.viewer_transfer_dialog_title)

        // Subtitle: filename • source folder location
        val sourceLocation = if (srcShare != null) {
            val netDir = srcNetPath?.substringBeforeLast('/', "")?.trim('/') ?: ""
            if (netDir.isNotEmpty()) "${srcShare.name}/$netDir" else srcShare.name
        } else {
            srcLocalFile?.parentFile?.absolutePath ?: ""
        }
        txtSubtitle?.text = if (sourceLocation.isNotEmpty()) "$fileName \u2022 $sourceLocation" else fileName

        // Populate Destination Card
        val currentDest = ViewerTransferPreferenceManager.getDestination(activity)
        if (currentDest != null) {
            txtDestFolderName?.text = currentDest.displayName
            val pathDesc = if (currentDest.isNetwork) {
                val share = NetworkShareRepository.getInstance(activity).getById(currentDest.shareId ?: "")
                    ?: za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(activity).getById(currentDest.shareId ?: "")?.toNetworkShare()
                val sharePrefix = share?.name ?: activity.getString(R.string.storage_network)
                val cleanPath = currentDest.netPath?.trim('/') ?: ""
                if (cleanPath.isNotEmpty()) "$sharePrefix/$cleanPath" else sharePrefix
            } else {
                currentDest.localPath ?: ""
            }
            txtDestPath?.text = pathDesc
            txtDestPath?.visibility = View.VISIBLE

            btnMoveToDestination?.text = activity.getString(R.string.viewer_transfer_move_to, currentDest.displayName)
            btnCopyToDestination?.text = activity.getString(R.string.viewer_transfer_copy_to, currentDest.displayName)
        } else {
            txtDestFolderName?.text = activity.getString(R.string.viewer_transfer_no_dest_prompt)
            txtDestPath?.visibility = View.GONE
            btnMoveToDestination?.text = activity.getString(R.string.viewer_transfer_move_generic)
            btnCopyToDestination?.text = activity.getString(R.string.viewer_transfer_copy_generic)
        }

        var transferActionTriggered = false

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val pickAction = {
            transferActionTriggered = true
            dialog.dismiss()
            onRequestPickDestination()
        }

        cardDestination?.setOnClickListener { pickAction() }
        btnChangeDestination?.setOnClickListener { pickAction() }

        btnMoveToDestination?.setOnClickListener {
            val dest = currentDest
            if (dest == null) {
                pickAction()
                return@setOnClickListener
            }
            transferActionTriggered = true
            dialog.dismiss()

            activity.lifecycleScope.launch {
                ViewerTransferExecutor.executeTransfer(
                    activity = activity,
                    fileName = fileName,
                    srcLocalFile = srcLocalFile,
                    srcShare = srcShare,
                    srcNetPath = srcNetPath,
                    dest = dest,
                    isMove = true
                ) { success, wasSkippedOrCancelled ->
                    if (success) {
                        onMoveSuccess()
                    } else if (wasSkippedOrCancelled) {
                        onDismissOrCancel()
                    }
                }
            }
        }

        btnCopyToDestination?.setOnClickListener {
            val dest = currentDest
            if (dest == null) {
                pickAction()
                return@setOnClickListener
            }
            transferActionTriggered = true
            dialog.dismiss()

            activity.lifecycleScope.launch {
                ViewerTransferExecutor.executeTransfer(
                    activity = activity,
                    fileName = fileName,
                    srcLocalFile = srcLocalFile,
                    srcShare = srcShare,
                    srcNetPath = srcNetPath,
                    dest = dest,
                    isMove = false
                ) { _, _ ->
                    onDismissOrCancel()
                }
            }
        }

        btnCancelTransfer?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!transferActionTriggered) {
                onDismissOrCancel()
            }
        }

        dialog.show()
    }
}
