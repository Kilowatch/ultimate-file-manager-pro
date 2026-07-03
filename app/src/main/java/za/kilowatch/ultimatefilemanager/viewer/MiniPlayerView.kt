package za.kilowatch.ultimatefilemanager.viewer

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Mini-player bar shown at the bottom of file browser screens when media is playing.
 *
 * Shows current track info, slim progress bar, and transport controls.
 * Tap → opens UFMPlayerActivity.
 * Close (X) → pauses playback AND opens UFMPlayerActivity at current position.
 */
class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var miniThumbnail: ImageView
    private var miniTitle: TextView
    private var miniArtist: TextView
    private var miniProgressBar: ProgressBar
    private var miniBtnPlayPause: ImageButton
    private var miniBtnNext: ImageButton
    private var miniBtnClose: ImageButton

    private var service: UFMPlaybackService? = null
    private var isHidden = true

    init {
        inflate(context, R.layout.view_mini_player, this)

        miniThumbnail = findViewById(R.id.miniThumbnail)
        miniTitle = findViewById(R.id.miniTitle)
        miniArtist = findViewById(R.id.miniArtist)
        miniProgressBar = findViewById(R.id.miniProgressBar)
        miniBtnPlayPause = findViewById(R.id.miniBtnPlayPause)
        miniBtnNext = findViewById(R.id.miniBtnNext)
        miniBtnClose = findViewById(R.id.miniBtnClose)

        // Tap on the body → open full player
        setOnClickListener { openPlayer() }

        // Play/Pause toggle
        miniBtnPlayPause.setOnClickListener {
            service?.toggle()
            updatePlayPauseIcon()
        }

        // Skip Next
        miniBtnNext.setOnClickListener { service?.skipToNext() }

        // Close → pause AND open player
        miniBtnClose.setOnClickListener {
            service?.pause()
            openPlayer()
        }
    }

    /**
     * Bind to the playback service and start updating.
     */
    fun bind(service: UFMPlaybackService) {
        this.service = service
        updateCurrentTrack()
        updatePlayPauseIcon()
    }

    /**
     * Unbind from the service and hide.
     */
    fun unbind() {
        this.service = null
        animateHide()
    }

    /**
     * Update the current track info from the service.
     */
    fun updateCurrentTrack() {
        val svc = service ?: return
        val item = svc.currentQueueItem
        if (item != null) {
            miniTitle.text = item.title ?: item.path.substringAfterLast("/")
            miniArtist.text = item.artist ?: context.getString(R.string.audio_no_metadata)
            animateShow()
        } else {
            animateHide()
        }
    }

    /**
     * Update the progress bar (0-1000 range).
     */
    fun updateProgress(position: Long, duration: Long) {
        if (duration > 0) {
            val progress = ((position.toFloat() / duration.toFloat()) * 1000).toInt()
            miniProgressBar.progress = progress.coerceIn(0, 1000)
        }
    }

    /**
     * Update play/pause icon based on service state.
     */
    fun updatePlayPauseIcon() {
        val isPlaying = service?.isPlaying ?: false
        miniBtnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        miniBtnPlayPause.contentDescription = context.getString(
            if (isPlaying) R.string.mini_player_pause_content_desc
            else R.string.mini_player_play_content_desc
        )
    }

    private fun openPlayer() {
        context.startActivity(
            Intent(context, UFMPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
    }

    private fun animateShow() {
        if (!isHidden) return
        isHidden = false
        visibility = View.VISIBLE
        translationY = height.toFloat()
        animate().translationY(0f).setDuration(300).start()
    }

    private fun animateHide() {
        if (isHidden) return
        isHidden = true
        animate().translationY(height.toFloat()).setDuration(200).withEndAction {
            visibility = View.GONE
        }.start()
    }

    /** Returns true if the service is bound and media is playing. */
    val isActive: Boolean get() = service != null && service?.isPlaying == true
}
