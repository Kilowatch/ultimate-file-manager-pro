package za.kilowatch.ultimatefilemanager.viewer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.ViewGroup
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Helper to integrate the mini-player bar into any activity.
 *
 * Usage in onResume/onStop:
 *   miniPlayerHelper = MiniPlayerHelper(this).apply { bindIfNeeded() }
 *   miniPlayerHelper?.unbind()
 */
class MiniPlayerHelper(private val activity: Activity) {

    private var miniPlayerView: MiniPlayerView? = null
    private var playbackService: UFMPlaybackService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? UFMPlaybackService.LocalBinder ?: return
            playbackService = binder.getService().also { svc ->
                // Create mini-player view if needed
                if (miniPlayerView == null) {
                    miniPlayerView = createMiniPlayerView()
                }
                miniPlayerView?.bind(svc)
                bound = true
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            miniPlayerView?.unbind()
            playbackService = null
            bound = false
        }
    }

    /** Call in onResume. Binds to the service if it's running. */
    fun bindIfNeeded() {
        if (bound) return
        // Don't show on TV
        if (DeviceUtils.isTvDevice(activity)) return
        // Only bind if service is running
        if (!UFMPlaybackService.isServiceAlive()) return

        try {
            val intent = Intent(activity, UFMPlaybackService::class.java)
            activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            // Service not available
        }
    }

    /** Call in onStop. Unbinds and cleans up. */
    fun unbind() {
        if (bound) {
            try {
                activity.unbindService(connection)
            } catch (_: Exception) {}
            bound = false
        }
        playbackService = null
    }

    /** Create and add the mini-player view to the activity's root. */
    private fun createMiniPlayerView(): MiniPlayerView {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?:
            activity.window.decorView.findViewById(android.R.id.content)
        val mpv = MiniPlayerView(activity).apply {
            id = android.view.View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(mpv)
        return mpv
    }
}
