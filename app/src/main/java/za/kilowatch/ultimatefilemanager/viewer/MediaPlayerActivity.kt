package za.kilowatch.ultimatefilemanager.viewer

import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ControlsTimeoutManager
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import android.widget.Toast
import java.io.File

/**
 * Built-in media player supporting:
 *  - Audio: mp3, wav, ogg, m4a, aac, flac, opus, wma, amr
 *  - Video: mp4, mkv, avi, mov, wmv, webm, flv, 3gp, ts
 *
 * Layout: SurfaceView fills entire screen; toolbar + controls are translucent overlays.
 * Aspect ratio is maintained in portrait AND landscape via onVideoSizeChanged.
 * In landscape, controls auto-hide for a true full-screen experience.
 */
class MediaPlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var btnBack: View
    private lateinit var txtTitle: TextView
    private lateinit var txtDuration: TextView
    private lateinit var videoContainer: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var audioPlaceholder: View
    private lateinit var progressBar: ProgressBar
    private lateinit var toolbar: View
    private lateinit var controlsCard: View
    private lateinit var seekBar: SeekBar
    private lateinit var txtElapsed: TextView
    private lateinit var txtRemaining: TextView
    private lateinit var btnRewind: View
    private lateinit var btnPlayPause: View
    private lateinit var btnForward: View

    /** Icon inside play/pause button – ImageView on mobile, ImageView inside LinearLayout on TV */
    private var playPauseIcon: ImageView? = null

    // ── State ────────────────────────────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null
    private var isVideo = false
    private var isTv = false
    private var wasPlaying = false
    private var surfaceReady = false
    private var filePath: String? = null
    private var totalDurationMs = 0
    private var videoNaturalWidth = 0
    private var videoNaturalHeight = 0

    // ── Seek updater ─────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val seekUpdater = object : Runnable {
        override fun run() {
            updateSeek()
            handler.postDelayed(this, 500)
        }
    }

    // ── Controls auto-hide (video mode) ──────────────────────────────────────
    private val hideControlsRunnable = Runnable { hideControls() }

    // ─────────────────────────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        isVideo = intent.getBooleanExtra(FileViewerRouter.EXTRA_IS_VIDEO, false)

        // Keep screen on during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(
            if (isTv) R.layout.activity_media_player_tv
            else R.layout.activity_media_player
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        bindViews()
        setupClickListeners()
        setupSeekBar()
        setupTvFocusColors()

        val path = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: run {
            finish(); return
        }
        filePath = path
        val fileName = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME) ?: File(path).name
        txtTitle.text = fileName

        // Show/hide surface vs audio placeholder
        if (isVideo) {
            surfaceView.visibility = View.VISIBLE
            audioPlaceholder.visibility = View.GONE
            val holder = surfaceView.holder
            holder.addCallback(this)
            if (holder.surface.isValid) {
                surfaceCreated(holder)
            }
        } else {
            surfaceView.visibility = View.GONE
            audioPlaceholder.visibility = View.VISIBLE
            initMediaPlayer(path)
        }
    }

    // ── View binding ─────────────────────────────────────────────────────────

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        txtTitle = findViewById(R.id.txtTitle)
        txtDuration = findViewById(R.id.txtDuration)
        surfaceView = findViewById(R.id.surfaceView)
        audioPlaceholder = findViewById(R.id.audioPlaceholder)
        progressBar = findViewById(R.id.progressBar)
        controlsCard = findViewById(R.id.controlsCard)
        seekBar = findViewById(R.id.seekBar)
        txtElapsed = findViewById(R.id.txtElapsed)
        txtRemaining = findViewById(R.id.txtRemaining)
        btnRewind = findViewById(R.id.btnRewind)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnForward = findViewById(R.id.btnForward)

        // The video container (FrameLayout wrapping the SurfaceView — mobile only)
        videoContainer = if (!isTv) {
            findViewById(R.id.videoContainer)
        } else {
            // On TV the surface is direct child; create a dummy placeholder
            surfaceView.parent as? FrameLayout ?: FrameLayout(this)
        }

        // Toolbar overlay (mobile only; TV uses layoutTvHeader)
        toolbar = try { findViewById(R.id.toolbar) } catch (_: Exception) {
            try { findViewById(R.id.layoutTvHeader) } catch (_: Exception) { View(this) }
        }

        // Resolve the inner ImageView for play/pause icon
        playPauseIcon = if (isTv) {
            (btnPlayPause as? LinearLayout)?.getChildAt(0) as? ImageView
                ?: (btnPlayPause as? LinearLayout)?.findViewById(R.id.icPlayPause)
        } else {
            btnPlayPause as? ImageView
        }
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        btnBack.setOnClickListener { finish() }
        btnPlayPause.setOnClickListener { togglePlayPause() }

        btnRewind.setOnClickListener {
            mediaPlayer?.let { mp ->
                val pos = (mp.currentPosition - 10_000).coerceAtLeast(0)
                mp.seekTo(pos)
                updateSeek()
                resetHideControlsTimer()
            }
        }

        btnForward.setOnClickListener {
            mediaPlayer?.let { mp ->
                val pos = (mp.currentPosition + 10_000).coerceAtMost(mp.duration.coerceAtLeast(0))
                mp.seekTo(pos)
                updateSeek()
                resetHideControlsTimer()
            }
        }

        // Tap anywhere on video to toggle controls
        if (isVideo && !isTv) {
            surfaceView.setOnClickListener { toggleControls() }
        }
    }

    // ── SeekBar ───────────────────────────────────────────────────────────────

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let { mp ->
                        val newPos = (progress.toLong() * mp.duration / 1000).toInt()
                        mp.seekTo(newPos)
                        txtElapsed.text = formatTime(newPos)
                        txtRemaining.text = "-${formatTime(mp.duration - newPos)}"
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                handler.removeCallbacks(seekUpdater)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                handler.post(seekUpdater)
                resetHideControlsTimer()
            }
        })
    }

    // ── TV focus colour changes (yellow bg → black icon when focused) ─────────

    private fun setupTvFocusColors() {
        if (!isTv) return

        val iconTint = ContextCompat.getColor(this, R.color.tv_icon_tint)
        val blackIcon = Color.parseColor("#FF0F0F0F")

        fun applyFocusColors(container: View?, icon: ImageView?) {
            container?.setOnFocusChangeListener { _, hasFocus ->
                icon?.setColorFilter(if (hasFocus) blackIcon else iconTint)
            }
        }

        val icRewind  = (btnRewind  as? LinearLayout)?.findViewById<ImageView>(R.id.icRewind)
        val icForward = (btnForward as? LinearLayout)?.findViewById<ImageView>(R.id.icForward)

        applyFocusColors(btnRewind, icRewind)
        applyFocusColors(btnPlayPause, playPauseIcon)
        applyFocusColors(btnForward, icForward)

        // Back button — same focus colour treatment
        val icBack = btnBack as? ImageView
        applyFocusColors(btnBack, icBack)
    }

    // ── MediaPlayer init ──────────────────────────────────────────────────────

    private fun initMediaPlayer(path: String) {
        progressBar.visibility = View.VISIBLE

        val mp = MediaPlayer()
        mediaPlayer = mp

        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Use ContentResolver + FileDescriptor so Android 10+ scoped-storage
            // restrictions never block playback (works for both file:// and content:// URIs).
            val pfd = contentResolver.openFileDescriptor(Uri.fromFile(File(path)), "r")
                ?: throw IllegalStateException("Cannot open: $path")
            pfd.use { mp.setDataSource(it.fileDescriptor) }

            if (isVideo) {
                mp.setDisplay(surfaceView.holder)

                // ── Core fix: listen for the video's natural dimensions ──
                mp.setOnVideoSizeChangedListener { _, width, height ->
                    if (width > 0 && height > 0) {
                        videoNaturalWidth = width
                        videoNaturalHeight = height
                        adjustVideoSize()
                    }
                }
            }

            mp.setOnPreparedListener { player ->
                progressBar.visibility = View.GONE
                totalDurationMs = player.duration
                txtDuration.text = formatTime(totalDurationMs)
                txtDuration.visibility = View.VISIBLE
                txtRemaining.text = "-${formatTime(totalDurationMs)}"
                txtElapsed.text = formatTime(0)
                player.start()
                wasPlaying = true
                updatePlayPauseIcon()
                handler.post(seekUpdater)
                if (isTv) btnPlayPause.requestFocus()
                if (isVideo) resetHideControlsTimer()
            }

            mp.setOnCompletionListener {
                wasPlaying = false
                updatePlayPauseIcon()
                handler.removeCallbacks(seekUpdater)
                // Show controls again when video ends
                if (isVideo && !isTv) showControls()
                updateSeek()
            }

            mp.setOnErrorListener { _, what, _ ->
                progressBar.visibility = View.GONE
                val msg = if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED)
                    getString(R.string.playback_error_server)
                else
                    getString(R.string.playback_error_source)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                true
            }

            mp.prepareAsync()

        } catch (e: Exception) {
            progressBar.visibility = View.GONE
            mp.release()
            mediaPlayer = null
        }
    }

    // ── Aspect-ratio sizing ───────────────────────────────────────────────────

    /**
     * Resizes the SurfaceView to exactly match the video's aspect ratio, centred
     * inside the videoContainer (which fills the whole screen).
     *
     * Portrait → wide video letter-boxed (black bars top/bottom)
     * Landscape → video fills width; height constrained to screen height
     */
    private fun adjustVideoSize() {
        if (videoNaturalWidth <= 0 || videoNaturalHeight <= 0) return
        if (isTv) return // TV layout is managed by the system

        val container = try { videoContainer } catch (_: Exception) { return }

        fun resize() {
            val containerW = container.width
            val containerH = container.height
            if (containerW == 0 || containerH == 0) {
                // Layout not measured yet — try again next frame
                container.post { resize() }
                return
            }

            val videoAspect = videoNaturalWidth.toFloat() / videoNaturalHeight.toFloat()
            val containerAspect = containerW.toFloat() / containerH.toFloat()

            val surfaceW: Int
            val surfaceH: Int
            if (videoAspect >= containerAspect) {
                // Video is wider than the container → fit to container width, letterbox vertically
                surfaceW = containerW
                surfaceH = (containerW / videoAspect).toInt()
            } else {
                // Video is taller than the container → fit to container height, pillarbox horizontally
                surfaceH = containerH
                surfaceW = (containerH * videoAspect).toInt()
            }

            val params = surfaceView.layoutParams
            params.width = surfaceW
            params.height = surfaceH
            surfaceView.layoutParams = params
        }

        if (container.width > 0) resize() else container.post { resize() }
    }

    // ── Configuration changes (rotation) ─────────────────────────────────────

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isVideo || isTv) return

        // Wait for the layout pass triggered by the rotation to FULLY complete
        // before reading the container's new dimensions. A simple post() is not
        // reliable — the view may still have the old dimensions on the next frame.
        videoContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    videoContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    adjustVideoSize()
                }
            }
        )
    }

    // ── Play / Pause ──────────────────────────────────────────────────────────

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            wasPlaying = false
            handler.removeCallbacks(seekUpdater)
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            mp.start()
            wasPlaying = true
            handler.post(seekUpdater)
            if (isVideo) resetHideControlsTimer()
        }
        updatePlayPauseIcon()
        resetHideControlsTimer()
    }

    private fun updatePlayPauseIcon() {
        val playing = mediaPlayer?.isPlaying == true
        val res = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        playPauseIcon?.setImageResource(res)
        (btnPlayPause as? ImageView)?.setImageResource(res)
    }

    // ── Seek update ───────────────────────────────────────────────────────────

    private fun updateSeek() {
        val mp = mediaPlayer ?: return
        val current = try { mp.currentPosition } catch (e: Exception) { return }
        val total = try { mp.duration } catch (e: Exception) { 0 }
        if (total <= 0) return
        seekBar.progress = ((current.toLong() * 1000) / total).toInt()
        txtElapsed.text = formatTime(current)
        txtRemaining.text = "-${formatTime((total - current).coerceAtLeast(0))}"
    }

    // ── Controls visibility (video auto-hide) ─────────────────────────────────

    private fun resetHideControlsTimer() {
        if (!isVideo) return
        handler.removeCallbacks(hideControlsRunnable)
        showControls()
        if (mediaPlayer?.isPlaying == true) {
            handler.postDelayed(hideControlsRunnable, ControlsTimeoutManager.loadDurationMs(this))
        }
    }

    private fun showControls() {
        toolbar.animate().alpha(1f).setDuration(200).start()
        toolbar.visibility = View.VISIBLE
        controlsCard.animate().alpha(1f).setDuration(200).start()
        controlsCard.visibility = View.VISIBLE
    }

    private fun hideControls() {
        val playing = mediaPlayer?.isPlaying == true
        toolbar.animate().alpha(0f).setDuration(300).withEndAction {
            if (playing) toolbar.visibility = View.INVISIBLE
        }.start()
        controlsCard.animate().alpha(0f).setDuration(300).withEndAction {
            if (playing) controlsCard.visibility = View.INVISIBLE
        }.start()
    }

    private fun toggleControls() {
        val visible = controlsCard.visibility == View.VISIBLE && controlsCard.alpha > 0.5f
        if (visible) {
            hideControls()
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            resetHideControlsTimer()
        }
    }

    // ── Time formatter ────────────────────────────────────────────────────────

    private fun formatTime(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!surfaceReady) {
            surfaceReady = true
            filePath?.let { initMediaPlayer(it) }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Re-apply sizing when surface dimensions change (e.g. rotation)
        if (isVideo && !isTv) adjustVideoSize()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                wasPlaying = true
            }
        }
        handler.removeCallbacks(seekUpdater)
        handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (wasPlaying) {
            mediaPlayer?.start()
            handler.post(seekUpdater)
            if (isVideo) resetHideControlsTimer()
            updatePlayPauseIcon()
        }
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) mp.stop()
            mp.release()
        }
        mediaPlayer = null
        super.onDestroy()
    }

    // ── Key / D-pad events (TV) ───────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isTv) {
            // Any key press while video is playing reveals the controls
            resetHideControlsTimer()
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause(); return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                    btnRewind.performClick(); return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                    btnForward.performClick(); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Touch (mobile controls reveal) ────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isVideo && !isTv && event.action == MotionEvent.ACTION_UP) {
            toggleControls()
        }
        return super.onTouchEvent(event)
    }
}
