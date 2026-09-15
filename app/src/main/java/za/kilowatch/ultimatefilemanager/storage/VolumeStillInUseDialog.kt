package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import kotlin.coroutines.resume

/**
 * The FR-09 gate: asks the user whether to unmount a volume the release phase could not
 * fully release.
 *
 * Suspend-shaped rather than callback-shaped so `UsbEjectManager` can hold the FR-10
 * re-entrancy guard across the whole flow — if the dialog deferred to a callback that
 * unmounted later, the guard would already have been released and a second eject could start
 * while the first was still waiting on the user.
 *
 * Everything that is not an explicit "unmount anyway" resolves to false: the secondary button,
 * Back, the cancel listener, a configuration change and an Activity that has gone away. The
 * only path to a destructive unmount is a deliberate tap on the primary button, which matters
 * because proceeding can leave data unwritten.
 */
object VolumeStillInUseDialog {

    /**
     * Shows the warning and returns true only if the user chose to unmount anyway.
     *
     * Must be called from a coroutine on the main dispatcher; it posts itself there anyway so
     * a caller on any dispatcher is safe.
     */
    suspend fun show(
        activity: Activity,
        volume: VolumeIdentity,
        report: ClaimReport
    ): Boolean = withContext(Dispatchers.Main) {
        if (activity.isFinishing || activity.isDestroyed) return@withContext false

        suspendCancellableCoroutine { continuation ->
            val isTv = DeviceUtils.isTvDevice(activity)
            val layoutRes = if (isTv) {
                R.layout.dialog_volume_still_in_use_tv
            } else {
                R.layout.dialog_volume_still_in_use
            }

            val dialogView = activity.layoutInflater.inflate(layoutRes, null)
            val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                .setView(dialogView)
                .create()

            dialogView.findViewById<TextView>(R.id.txtMessage)?.text =
                activity.getString(R.string.volume_still_in_use_msg, volume.label)
            dialogView.findViewById<TextView>(R.id.txtDetail)?.text =
                buildDetail(activity, report)

            // Settled at most once: the cancel listener and a button tap can both fire, and
            // resuming a continuation twice would throw.
            var settled = false
            fun settle(proceed: Boolean) {
                if (settled) return
                settled = true
                dialog.dismiss()
                if (continuation.isActive) continuation.resume(proceed)
            }

            dialogView.findViewById<View>(R.id.btnProceed)?.setOnClickListener { settle(true) }
            dialogView.findViewById<View>(R.id.btnKeepMounted)?.setOnClickListener { settle(false) }
            dialog.setOnCancelListener { settle(false) }
            // An outside tap is not consent to unmount a volume that is still in use.
            dialog.setCanceledOnTouchOutside(false)

            dialog.show()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            // Default TV focus lands on the safe exit, not on the destructive action.
            if (isTv) dialogView.findViewById<MaterialButton>(R.id.btnKeepMounted)?.requestFocus()

            // If the eject is cancelled while the dialog is up, take the dialog down with it.
            continuation.invokeOnCancellation {
                activity.runOnUiThread { dialog.dismiss() }
            }
        }
    }

    /**
     * One `label: count` line per non-zero claim type.
     *
     * Built here rather than in [ClaimReport] so the counts are formatted through
     * `strings.xml` and stay translatable — the report itself deliberately carries no text.
     */
    private fun buildDetail(activity: Activity, report: ClaimReport): String {
        val lines = mutableListOf<String>()
        if (report.fdCount > 0) {
            lines.add(activity.getString(R.string.volume_still_in_use_fd, report.fdCount))
        }
        if (report.mapCount > 0) {
            lines.add(activity.getString(R.string.volume_still_in_use_map, report.mapCount))
        }
        if (report.watcherCount > 0) {
            lines.add(activity.getString(R.string.volume_still_in_use_watcher, report.watcherCount))
        }
        if (report.failedCount > 0) {
            lines.add(activity.getString(R.string.volume_still_in_use_failed, report.failedCount))
        }
        return lines.joinToString("\n")
    }
}
