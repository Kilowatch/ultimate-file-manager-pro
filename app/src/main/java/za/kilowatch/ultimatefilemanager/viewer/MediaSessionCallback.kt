package za.kilowatch.ultimatefilemanager.viewer

import android.content.Intent
import android.view.KeyEvent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo

/**
 * Handles MediaSession callbacks.
 *
 * In Media3 1.10.1, basic transport commands (play/pause/seek/next/prev)
 * are routed directly from MediaController → Player by the session framework,
 * so this callback mainly handles:
 *  - Media button events (Bluetooth headset)
 *  - Custom commands
 */
class MediaSessionCallback(
    private val service: UFMPlaybackService
) : MediaSession.Callback {

    /**
     * Handle media button events (e.g. Bluetooth headset single/double/triple press).
     */
    override fun onMediaButtonEvent(
        session: MediaSession,
        controller: ControllerInfo,
        intent: Intent
    ): Boolean {
        val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> service.toggle()
                KeyEvent.KEYCODE_MEDIA_PLAY -> service.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> service.pause()
                KeyEvent.KEYCODE_MEDIA_NEXT -> service.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> service.skipToPrev()
                KeyEvent.KEYCODE_MEDIA_STOP -> service.stopSelf()
            }
            return true
        }
        return false
    }
}
