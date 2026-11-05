package za.kilowatch.ultimatefilemanager.viewer.subtitles

import android.content.Context
import android.view.accessibility.CaptioningManager
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Lifecycle-aware wrapper around [CaptioningManager.CaptioningChangeListener] to observe
 * user caption style and font scale changes in Android system settings.
 *
 * Automatically dispatches notifications to [onStyleChanged] so active player views
 * can re-render subtitles immediately without restarting playback.
 */
class CaptioningObserver(
    context: Context,
    private val onStyleChanged: () -> Unit
) {
    companion object {
        private const val TAG = "CaptioningObserver"
    }

    private val appContext = context.applicationContext
    private val captioningManager = appContext.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
    private var isRegistered = false

    private val listener = object : CaptioningManager.CaptioningChangeListener() {
        override fun onUserStyleChanged(userStyle: CaptioningManager.CaptionStyle) {
            GoRoLog.d(TAG, "onUserStyleChanged triggered")
            onStyleChanged()
        }

        override fun onFontScaleChanged(fontScale: Float) {
            GoRoLog.d(TAG, "onFontScaleChanged triggered: $fontScale")
            onStyleChanged()
        }
    }

    /**
     * Registers the listener with [CaptioningManager]. Safe to call multiple times.
     */
    fun register() {
        if (!isRegistered && captioningManager != null) {
            try {
                captioningManager.addCaptioningChangeListener(listener)
                isRegistered = true
                GoRoLog.d(TAG, "CaptioningChangeListener registered successfully")
            } catch (t: Throwable) {
                GoRoLog.w(TAG, "Failed to register CaptioningChangeListener: ${t.message}")
            }
        }
    }

    /**
     * Unregisters the listener to prevent leaks. Safe to call multiple times.
     */
    fun unregister() {
        if (isRegistered && captioningManager != null) {
            try {
                captioningManager.removeCaptioningChangeListener(listener)
                isRegistered = false
                GoRoLog.d(TAG, "CaptioningChangeListener unregistered")
            } catch (t: Throwable) {
                GoRoLog.w(TAG, "Failed to unregister CaptioningChangeListener: ${t.message}")
            }
        }
    }
}
