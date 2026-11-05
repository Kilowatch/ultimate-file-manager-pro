package za.kilowatch.ultimatefilemanager.viewer.subtitles

import android.content.Context
import androidx.fragment.app.FragmentManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Unified launcher that displays either [SubtitleStyleTvDialog] or [SubtitleStyleBottomSheet]
 * based on runtime form-factor detection.
 */
object SubtitleStyleDialog {

    fun show(fragmentManager: FragmentManager, context: Context, onDismiss: (() -> Unit)? = null) {
        if (DeviceUtils.isTvDevice(context)) {
            val tvDialog = SubtitleStyleTvDialog.newInstance(onDismiss)
            tvDialog.show(fragmentManager, SubtitleStyleTvDialog.TAG)
        } else {
            val bottomSheet = SubtitleStyleBottomSheet.newInstance(onDismiss)
            bottomSheet.show(fragmentManager, SubtitleStyleBottomSheet.TAG)
        }
    }
}
