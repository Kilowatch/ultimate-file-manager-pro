package za.kilowatch.ultimatefilemanager.viewer

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.settings.ControlsTimeoutManager
import za.kilowatch.ultimatefilemanager.settings.SideBySideVideoPreferenceManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File

class TwinWindowPlayerFragment : Fragment() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var btnBack: View
    private lateinit var txtTitle: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var txtElapsed: TextView
    private lateinit var txtRemaining: TextView
    private lateinit var btnRewind: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var btnFullscreen: ImageView
    private lateinit var btnRepeat: ImageView
    private lateinit var bufferingLayout: View
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var controlsLayout: View
    private lateinit var topBar: View
    private lateinit var audioPoster: ImageView
    private lateinit var audioPlaceholder: ImageView
    private lateinit var audioPosterScrim: View
    private lateinit var rootView: View

    private var filePath: String = ""
    private var fileName: String = ""
    private var isVideo: Boolean = true
    private var paneIndex: Int = 1
    private var startMuted: Boolean = false
    private var shareId: String? = null
    private var remotePath: String? = null

    private var isMuted: Boolean = false
    private var isLocalPlayer: Boolean = true
    private var isRepeat: Boolean = false
    private var isRepeatingPlayback: Boolean = false
    private var isTracking: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private var isTv: Boolean = false

    var onClosePlayer: (() -> Unit)? = null
    var onToggleFullscreen: ((paneIndex: Int, fullscreen: Boolean) -> Unit)? = null

    private var isFullscreen: Boolean = false
        set(value) {
            field = value
            btnFullscreen.setImageResource(if (value) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        }

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    val hasPlayer: Boolean
        get() = player != null

    fun showControls(): Boolean {
        if (controlsLayout.visibility != View.VISIBLE) {
            controlsLayout.visibility = View.VISIBLE
            controlsLayout.alpha = 1f
            topBar.visibility = View.VISIBLE
            topBar.alpha = 1f
            handler.removeCallbacks(hideControlsRunnable)
            if (player?.isPlaying == true) {
                handler.postDelayed(hideControlsRunnable, ControlsTimeoutManager.loadDurationMs(requireContext()))
            }
            return true
        }
        return false
    }

    fun showControlsAndFocus() {
        showControls()
        btnPlayPause.requestFocus()
    }

    companion object {
        private const val ARG_FILE_PATH = "file_path"
        private const val ARG_FILE_NAME = "file_name"
        private const val ARG_IS_VIDEO = "is_video"
        private const val ARG_PANE_INDEX = "pane_index"
        private const val ARG_START_MUTED = "start_muted"
        private const val ARG_SHARE_ID = "share_id"
        private const val ARG_REMOTE_PATH = "remote_path"

        fun newInstance(
            filePath: String,
            fileName: String,
            isVideo: Boolean,
            paneIndex: Int,
            startMuted: Boolean = false,
            shareId: String? = null,
            remotePath: String? = null
        ): TwinWindowPlayerFragment {
            val fragment = TwinWindowPlayerFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_FILE_PATH, filePath)
                putString(ARG_FILE_NAME, fileName)
                putBoolean(ARG_IS_VIDEO, isVideo)
                putInt(ARG_PANE_INDEX, paneIndex)
                putBoolean(ARG_START_MUTED, startMuted)
                putString(ARG_SHARE_ID, shareId)
                putString(ARG_REMOTE_PATH, remotePath)
            }
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            filePath = it.getString(ARG_FILE_PATH) ?: ""
            fileName = it.getString(ARG_FILE_NAME) ?: ""
            isVideo = it.getBoolean(ARG_IS_VIDEO, true)
            paneIndex = it.getInt(ARG_PANE_INDEX, 1)
            startMuted = it.getBoolean(ARG_START_MUTED, false)
            shareId = it.getString(ARG_SHARE_ID)
            remotePath = it.getString(ARG_REMOTE_PATH)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        isTv = DeviceUtils.isTvDevice(requireContext())
        rootView = inflater.inflate(
            if (isTv) R.layout.fragment_twin_player_tv else R.layout.fragment_twin_player,
            container, false
        )
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        view.isFocusable = true
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (controlsLayout.visibility != View.VISIBLE) {
                    resetHideTimer()
                    btnPlayPause.requestFocus()
                    return@setOnKeyListener true
                }
                resetHideTimer()
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePlayPause(); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND,
                    KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> { btnRewind.performClick(); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> { btnForward.performClick(); true }
                    else -> false
                }
            } else false
        }
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                resetHideTimer()
            }
            false
        }
        bindViews(view)
        setupControls()
        initPlayer()

        if (isTv) {
            controlsLayout.visibility = View.VISIBLE
            topBar.visibility = View.VISIBLE
        }
    }

    private fun bindViews(view: View) {
        playerView = view.findViewById(R.id.playerView)
        btnBack = view.findViewById(R.id.btnBack)
        txtTitle = view.findViewById(R.id.txtTitle)
        btnPlayPause = view.findViewById(R.id.btnPlayPause)
        seekBar = view.findViewById(R.id.seekBar)
        txtElapsed = view.findViewById(R.id.txtElapsed)
        txtRemaining = view.findViewById(R.id.txtRemaining)
        btnRewind = view.findViewById(R.id.btnRewind)
        btnForward = view.findViewById(R.id.btnForward)
        btnMute = view.findViewById(R.id.btnMute)
        volumeSeekBar = view.findViewById(R.id.volumeSeekBar)
        bufferingLayout = view.findViewById(R.id.bufferingLayout)
        loadingSpinner = view.findViewById(R.id.loadingSpinner)
        controlsLayout = view.findViewById(R.id.controlsLayout)
        topBar = view.findViewById(R.id.topBar)
        audioPoster = view.findViewById(R.id.audioPoster)
        audioPlaceholder = view.findViewById(R.id.audioPlaceholder)
        audioPosterScrim = view.findViewById(R.id.audioPosterScrim)
        btnFullscreen = view.findViewById(R.id.btnFullscreen)
        btnRepeat = view.findViewById(R.id.btnRepeat)
    }

    private fun setupControls() {
        txtTitle.text = fileName

        btnBack.setOnClickListener { onClosePlayer?.invoke() }

        btnFullscreen.setOnClickListener {
            isFullscreen = !isFullscreen
            onToggleFullscreen?.invoke(paneIndex, isFullscreen)
        }

        btnRepeat.setOnClickListener {
            isRepeat = !isRepeat
            updateRepeatIcon()
            resetHideTimer()
        }

        btnPlayPause.setOnClickListener { togglePlayPause() }

        btnRewind.setOnClickListener {
            player?.let { p ->
                val pos = (p.currentPosition - 10000).coerceAtLeast(0L)
                p.seekTo(pos)
                updateSeek()
                resetHideTimer()
            }
        }

        btnForward.setOnClickListener {
            player?.let { p ->
                val pos = (p.currentPosition + 10000).coerceAtMost(p.duration.coerceAtLeast(0L))
                p.seekTo(pos)
                updateSeek()
                resetHideTimer()
            }
        }

        btnMute.setOnClickListener {
            isMuted = !isMuted
            player?.volume = if (isMuted) 0f else (volumeSeekBar.progress / 100f)
            updateMuteIcon()
            resetHideTimer()
        }

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.volume = progress / 100f
                    isMuted = progress == 0
                    updateMuteIcon()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { resetHideTimer() }
        })

        if (startMuted) {
            isMuted = true
            volumeSeekBar.progress = 0
            updateMuteIcon()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0L
                    updateTimeLabels(progress, if (duration > 0) duration.toInt() else 0)
                    player?.seekTo(progress.toLong())
                    resetHideTimer()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isTracking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isTracking = false
                player?.seekTo(sb?.progress?.toLong() ?: 0L)
                resetHideTimer()
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(controlsLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomPadding = if (isTv) dp(24) else (systemBars.bottom + dp(12))
            val sidePadding = if (isTv) dp(24) else (systemBars.left + dp(12))
            v.setPadding(sidePadding, v.paddingTop, sidePadding, bottomPadding)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            lp.topMargin = if (isTv) dp(12) else systemBars.top
            lp.leftMargin = if (isTv) dp(12) else systemBars.left
            v.layoutParams = lp
            insets
        }

        listOf(btnBack, btnPlayPause, btnRewind, btnForward, btnMute, btnFullscreen, btnRepeat, seekBar, volumeSeekBar).forEach { v ->
            v.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) resetHideTimer()
            }
        }
        updateRepeatIcon()
    }

    private fun initPlayer() {
        if (filePath.isEmpty()) return

        loadingSpinner.visibility = View.VISIBLE

        if (!isVideo) {
            audioPoster.visibility = View.VISIBLE
            audioPosterScrim.visibility = View.VISIBLE
            audioPlaceholder.visibility = View.VISIBLE
            playerView.visibility = View.GONE
            extractAudioPoster()
        } else {
            audioPoster.visibility = View.GONE
            audioPosterScrim.visibility = View.GONE
            audioPlaceholder.visibility = View.GONE
            playerView.visibility = View.VISIBLE
        }

        val newPlayer = ExoPlayer.Builder(requireContext()).build()
        isLocalPlayer = shareId == null

        if (shareId != null) {
            // Network file — use UfmMedia3DataSource
            val repo = NetworkShareRepository.getInstance(requireContext())
            var share = repo.getById(shareId!!)
            if (share?.isServerMode == true && !remotePath.isNullOrEmpty()) {
                share = share.copy(remotePath = remotePath!!)
            }
            if (share != null) {
                val dataSourceFactory = DataSource.Factory {
                    UfmMedia3DataSource(share, filePath)
                }
                val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(
                        android.net.Uri.parse("ufm://${filePath.replace(" ", "%20")}")
                    ))
                newPlayer.setMediaSource(mediaSource)
                newPlayer.prepare()
            } else {
                // Share not found — close player and return
                loadingSpinner.visibility = View.GONE
                onClosePlayer?.invoke()
                return
            }
        } else {
            // Local file
            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(filePath)))
            newPlayer.setMediaItem(mediaItem)
            newPlayer.prepare()
        }
        player = newPlayer
        playerView.player = newPlayer

        newPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> if (!isLocalPlayer) bufferingLayout.visibility = View.VISIBLE
                    else -> bufferingLayout.visibility = View.GONE
                }
                if (state == Player.STATE_ENDED) {
                    handler.post {
                        if (isRepeat) {
                            isRepeatingPlayback = true
                            newPlayer.seekTo(0)
                            newPlayer.play()
                        } else {
                            newPlayer.seekTo(0)
                            newPlayer.pause()
                            updatePlayPauseIcon()
                        }
                    }
                }
                updatePlayPauseIcon()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                updatePlayPauseIcon()
                if (playing) {
                    val showOnRepeat = SideBySideVideoPreferenceManager.isShowControlsOnRepeat(requireContext())
                    if (isRepeatingPlayback && !showOnRepeat) {
                        isRepeatingPlayback = false
                        controlsLayout.visibility = View.GONE
                        topBar.visibility = View.GONE
                        handler.removeCallbacks(hideControlsRunnable)
                    } else {
                        isRepeatingPlayback = false
                        resetHideTimer()
                    }
                } else {
                    handler.removeCallbacks(hideControlsRunnable)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                bufferingLayout.visibility = View.GONE
            }
        })

        if (startMuted) {
            newPlayer.volume = 0f
        }

        newPlayer.playWhenReady = true
        newPlayer.play()
        handler.post(progressUpdater)
        resetHideTimer()
        if (isTv) {
            btnPlayPause.requestFocus()
        }
    }

    private fun extractAudioPoster() {
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(filePath)
                val art = retriever.embeddedPicture
                if (art != null) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                    requireActivity().runOnUiThread {
                        if (!isRemoving && isAdded) {
                            audioPoster.setImageBitmap(bmp)
                            audioPlaceholder.visibility = View.GONE
                        }
                    }
                }
                retriever.release()
            } catch (_: Exception) {}
        }.start()
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
                handler.removeCallbacks(progressUpdater)
            } else {
                p.play()
                handler.post(progressUpdater)
                resetHideTimer()
            }
            updatePlayPauseIcon()
        }
    }

    private fun updatePlayPauseIcon() {
        val playing = player?.isPlaying == true
        btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateMuteIcon() {
        btnMute.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_down)
    }

    private fun updateRepeatIcon() {
        val color = if (isRepeat) {
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.ufm_active_blue)
        } else {
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.ufm_text_hint)
        }
        btnRepeat.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (!isTracking && p.isPlaying && (!isTv || !seekBar.isFocused)) {
                    val pos = p.currentPosition.toInt()
                    val dur = p.duration
                    if (dur > 0L) {
                        seekBar.max = dur.toInt()
                        seekBar.progress = pos
                        updateTimeLabels(pos, dur.toInt())
                    }
                }
            }
            if (isAdded && !isRemoving) handler.postDelayed(this, 1000)
        }
    }

    private fun updateSeek() {
        player?.let { p ->
            val pos = p.currentPosition.toInt()
            val dur = p.duration
            if (dur > 0L) {
                seekBar.max = dur.toInt()
                seekBar.progress = pos
                updateTimeLabels(pos, dur.toInt())
            }
        }
    }

    private fun updateTimeLabels(posMs: Int, durMs: Int) {
        val remMs = durMs - posMs
        txtElapsed.text = formatTime(posMs)
        txtRemaining.text = "-" + formatTime(remMs.coerceAtLeast(0))
    }

    private fun formatTime(ms: Int): String {
        var s = ms / 1000
        val m = s / 60
        s %= 60
        return String.format("%02d:%02d", m, s)
    }

    private val hideControlsRunnable = Runnable {
        controlsLayout.animate().alpha(0f).setDuration(300).withEndAction {
            controlsLayout.visibility = View.GONE
        }
        topBar.animate().alpha(0f).setDuration(300).withEndAction {
            topBar.visibility = View.GONE
        }
    }

    private fun resetHideTimer() {
        isRepeatingPlayback = false
        handler.removeCallbacks(hideControlsRunnable)
        controlsLayout.visibility = View.VISIBLE
        controlsLayout.alpha = 1f
        topBar.visibility = View.VISIBLE
        topBar.alpha = 1f
        if (player?.isPlaying == true) {
            handler.postDelayed(hideControlsRunnable, ControlsTimeoutManager.loadDurationMs(requireContext()))
        }
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            if (it.isPlaying) it.pause()
        }
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onResume() {
        super.onResume()
        player?.let {
            if (!it.isPlaying) {
                it.play()
                handler.post(progressUpdater)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        playerView.player = null
    }

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density).toInt()
}
