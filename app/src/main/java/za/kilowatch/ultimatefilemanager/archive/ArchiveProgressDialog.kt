package za.kilowatch.ultimatefilemanager.archive

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * High-fidelity, animated telemetry dialog for all archive operations (compress, extract, add, delete, move).
 * Compliant with UFMStandard (Glass styling, dual Mobile/TV layouts, embedded buttons).
 */
class ArchiveProgressDialog(
    private val activity: Activity,
    private val initialTitle: String? = null,
    private val initialArchiveName: String? = null
) {

    private val isTv = DeviceUtils.isTvDevice(activity)
    private var alertDialog: AlertDialog? = null

    // UI View References
    private var imgHeroIcon: ImageView? = null
    private var viewGlowRing1: View? = null
    private var viewGlowRing2: View? = null
    private var txtBadge: TextView? = null
    private var txtTitle: TextView? = null
    private var txtArchiveName: TextView? = null
    private var txtPercentage: TextView? = null
    private var txtFilesCount: TextView? = null
    private var progressTotal: LinearProgressIndicator? = null
    private var progressCurrentFile: LinearProgressIndicator? = null
    private var txtFileBadge: TextView? = null
    private var txtProgressCurrentFile: TextView? = null
    private var txtProgressDataSize: TextView? = null
    private var txtProgressSpeedEta: TextView? = null
    private var btnBackground: MaterialButton? = null
    private var btnCancel: MaterialButton? = null

    // Animators
    private var glowPulseAnimator: ObjectAnimator? = null
    private var progressSmoothAnimator: ValueAnimator? = null
    private var lastEmittedProgress: Int = 0

    // Callbacks
    private var onCancelListener: (() -> Unit)? = null
    private var onBackgroundListener: (() -> Unit)? = null

    init {
        val layoutRes = if (isTv) R.layout.dialog_archive_progress_tv else R.layout.dialog_archive_progress
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        imgHeroIcon = dialogView.findViewById(R.id.imgArchiveProgressIcon)
        viewGlowRing1 = dialogView.findViewById(R.id.viewGlowRing1)
        viewGlowRing2 = dialogView.findViewById(R.id.viewGlowRing2)
        txtBadge = dialogView.findViewById(R.id.txtArchiveBadge)
        txtTitle = dialogView.findViewById(R.id.txtArchiveProgressTitle)
        txtArchiveName = dialogView.findViewById(R.id.txtArchiveName)
        txtPercentage = dialogView.findViewById(R.id.txtArchivePercentage)
        txtFilesCount = dialogView.findViewById(R.id.txtArchiveFilesCount)
        progressTotal = dialogView.findViewById(R.id.progressArchiveTotal)
        progressCurrentFile = dialogView.findViewById(R.id.progressArchiveCurrentFile)
        txtFileBadge = dialogView.findViewById(R.id.txtFileBadge)
        txtProgressCurrentFile = dialogView.findViewById(R.id.txtProgressCurrentFile)
        txtProgressDataSize = dialogView.findViewById(R.id.txtProgressDataSize)
        txtProgressSpeedEta = dialogView.findViewById(R.id.txtProgressSpeedEta)
        btnBackground = dialogView.findViewById(R.id.btnProgressBackground)
        btnCancel = dialogView.findViewById(R.id.btnProgressCancel)

        if (initialTitle != null) txtTitle?.text = initialTitle
        if (initialArchiveName != null) txtArchiveName?.text = initialArchiveName

        btnCancel?.setOnClickListener {
            promptCancellation()
        }

        btnBackground?.setOnClickListener {
            dismiss()
            onBackgroundListener?.invoke()
        }

        alertDialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        alertDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    fun setOnCancelListener(listener: () -> Unit): ArchiveProgressDialog {
        this.onCancelListener = listener
        return this
    }

    fun setOnBackgroundListener(listener: () -> Unit): ArchiveProgressDialog {
        this.onBackgroundListener = listener
        return this
    }

    fun show(
        operation: ArchiveOperationType? = null,
        archiveName: String = "",
        totalFiles: Int = 0
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        alertDialog?.show()

        if (isTv) {
            val displayMetrics = activity.resources.displayMetrics
            val targetWidth = (650 * displayMetrics.density).toInt()
            alertDialog?.window?.setLayout(targetWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            btnBackground?.requestFocus()
        }

        if (operation != null) {
            update(
                ArchiveProgress(
                    operation = operation,
                    archiveName = archiveName,
                    totalFiles = totalFiles
                )
            )
        } else if (archiveName.isNotEmpty()) {
            txtArchiveName?.text = archiveName
        }

        startGlowAnimations()
    }

    fun dismiss() {
        stopAnimations()
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            alertDialog?.dismiss()
        } catch (_: Exception) {}
    }

    fun isShowing(): Boolean = alertDialog?.isShowing == true

    /**
     * Updates telemetry fields in real-time with smooth animations.
     */
    fun update(progress: ArchiveProgress) {
        if (activity.isFinishing || activity.isDestroyed || !isShowing()) return

        activity.runOnUiThread {
            // Update operation badge and icon based on type
            when (progress.operation) {
                ArchiveOperationType.EXTRACT -> {
                    txtBadge?.setText(R.string.archive_badge_extracting)
                    txtTitle?.setText(R.string.archive_progress_extracting)
                    imgHeroIcon?.setImageResource(R.drawable.ic_extract)
                }
                ArchiveOperationType.COMPRESS -> {
                    txtBadge?.setText(R.string.archive_badge_compressing)
                    txtTitle?.setText(R.string.archive_progress_compressing)
                    imgHeroIcon?.setImageResource(R.drawable.ic_file_archive)
                }
                ArchiveOperationType.ADD -> {
                    txtBadge?.setText(R.string.archive_badge_adding)
                    txtTitle?.setText(R.string.archive_progress_adding)
                    imgHeroIcon?.setImageResource(R.drawable.ic_copy)
                }
                ArchiveOperationType.DELETE -> {
                    txtBadge?.setText(R.string.archive_badge_deleting)
                    txtTitle?.setText(R.string.archive_progress_deleting)
                    imgHeroIcon?.setImageResource(R.drawable.ic_delete)
                }
                ArchiveOperationType.MOVE -> {
                    txtBadge?.setText(R.string.archive_badge_modifying)
                    txtTitle?.setText(R.string.archive_progress_moving)
                    imgHeroIcon?.setImageResource(R.drawable.ic_move)
                }
            }

            if (progress.archiveName.isNotEmpty()) {
                txtArchiveName?.text = progress.archiveName
            }

            // Smooth main progress indicator and percentage
            val targetPct = progress.percentage.coerceIn(0, 100)
            animateProgress(targetPct)

            // File count
            if (progress.totalFiles > 0) {
                txtFilesCount?.text = activity.getString(
                    R.string.archive_progress_files_count,
                    progress.fileIndex,
                    progress.totalFiles
                )
            } else if (progress.fileIndex > 0) {
                txtFilesCount?.text = "${progress.fileIndex} files"
            }

            // Sub-file progress bar (calculate pseudo or real file fraction)
            if (progress.totalFiles > 0) {
                val fileFraction = ((progress.fileIndex.toFloat() / progress.totalFiles) * 100).toInt()
                progressCurrentFile?.progress = fileFraction
            }

            // Current file name and badge
            if (progress.currentFileName.isNotEmpty()) {
                txtProgressCurrentFile?.text = progress.currentFileName
                val ext = progress.currentFileName.substringAfterLast('.', "")
                    .uppercase()
                    .take(4)
                txtFileBadge?.text = if (ext.isNotEmpty()) ext else "FILE"
                txtFileBadge?.visibility = View.VISIBLE
            }

            // Data size & speed/ETA
            val processedStr = Formatter.formatFileSize(activity, progress.bytesProcessed)
            val totalStr = if (progress.totalBytes > 0) Formatter.formatFileSize(activity, progress.totalBytes) else "?"
            txtProgressDataSize?.text = activity.getString(R.string.archive_progress_data_count, processedStr, totalStr)

            if (progress.speedBytesPerSec > 0) {
                val speedStr = "${Formatter.formatFileSize(activity, progress.speedBytesPerSec)}/s"
                val etaSec = (progress.estimatedRemainingMs / 1000).coerceAtLeast(1)
                txtProgressSpeedEta?.text = activity.getString(
                    R.string.archive_progress_speed_eta,
                    speedStr,
                    "${etaSec}s"
                )
            } else {
                txtProgressSpeedEta?.text = ""
            }
        }
    }

    private fun animateProgress(target: Int) {
        if (target == lastEmittedProgress) return
        progressSmoothAnimator?.cancel()
        val startVal = lastEmittedProgress
        progressSmoothAnimator = ValueAnimator.ofInt(startVal, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Int
                progressTotal?.progress = v
                txtPercentage?.text = "$v%"
            }
            start()
        }
        lastEmittedProgress = target
    }

    private fun startGlowAnimations() {
        val ring1 = viewGlowRing1 ?: return
        val ring2 = viewGlowRing2 ?: return

        val pvhScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.25f)
        val pvhScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.25f)
        val pvhAlpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 0.1f)

        glowPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(ring1, pvhScaleX, pvhScaleY, pvhAlpha).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        ring2.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .alpha(0.08f)
            .setDuration(2400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun stopAnimations() {
        glowPulseAnimator?.cancel()
        glowPulseAnimator = null
        progressSmoothAnimator?.cancel()
        progressSmoothAnimator = null
    }

    private fun promptCancellation() {
        MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setTitle(R.string.archive_cancel_confirm_title)
            .setMessage(R.string.archive_cancel_confirm_msg)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                dismiss()
                onCancelListener?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
