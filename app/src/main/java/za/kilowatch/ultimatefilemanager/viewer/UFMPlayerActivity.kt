package za.kilowatch.ultimatefilemanager.viewer

import android.app.Activity
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.common.C
import za.kilowatch.ultimatefilemanager.network.IRandomAccessFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.DlnaShareClient
import android.media.MediaDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.IOException
import java.util.ArrayList
import android.content.Context
import android.widget.Toast
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.ui.SubtitleView
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.AutoplayPreferenceManager
import za.kilowatch.ultimatefilemanager.settings.ControlsTimeoutManager
import java.util.Locale

class UFMPlayerActivity : Activity() {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    
    private lateinit var btnPlayPause: View
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var txtElapsed: TextView
    private lateinit var txtRemaining: TextView
    private lateinit var txtTitle: TextView
    private lateinit var audioPoster: ImageView
    private lateinit var audioPlaceholder: ImageView
    private lateinit var audioPosterScrim: View
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var bufferingLayout: View
    private lateinit var controlsLayout: View
    private lateinit var topBar: View
    private lateinit var btnAudioTrack: ImageButton
    private lateinit var btnSubtitles: ImageButton
    private lateinit var subtitleView: SubtitleView
    private lateinit var trackSheetLayout: View
    private lateinit var trackSheetList: LinearLayout
    private lateinit var trackSheetTitle: TextView

    private var playlist: ArrayList<String> = ArrayList()
    private var currentIndex = 0
    private var shareId: String = ""
    private var shareHost: String = ""
    private var shareName: String = ""
    private var provider: String = ""
    private var remotePathExtra: String = ""
    private var initialFileSize: Long = 0L

    private var isShuffle = false
    private var isRepeat = false
    private var isShowingSheet = false
    private var currentSheetMode = -1
    private var currentAudioTracks: List<AudioTrackInfo> = emptyList()
    private var currentSubtitleTracks: List<SubtitleTrackInfo> = emptyList()
    private var externalSubtitleInfos: List<SubtitleTrackInfo> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private var isTracking = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_BUFFERING) {
                bufferingLayout.visibility = View.VISIBLE
            } else {
                bufferingLayout.visibility = View.GONE
            }

            if (state == Player.STATE_ENDED) {
                handler.post {
                    if (isRepeat) {
                        playCurrent()
                    } else if (AutoplayPreferenceManager.isEnabled(this@UFMPlayerActivity)) {
                        playNext()
                    } else {
                        finish()
                    }
                }
            }
            updatePlayPauseIcon()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
            if (isPlaying) {
                resetHideTimer()
            } else {
                handler.removeCallbacks(hideControlsRunnable)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            GoRoLog.e("UFMPlayer", "Player error: ${error.message}", error)
            runOnUiThread {
                bufferingLayout.visibility = View.GONE
                Toast.makeText(this@UFMPlayerActivity, "Playback Error: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            detectAndUpdateTracks(tracks)
        }
    }
    
    // Auto-hide UI runnable
    private val hideControlsRunnable = Runnable {
        runOnUiThread {
            if (!isDestroyed && !isFinishing && !isShowingSheet) {
                controlsLayout.animate().alpha(0f).setDuration(300).withEndAction {
                    controlsLayout.visibility = View.GONE
                    // Increase subtitle bottom margin when controls are hidden
                    subtitleView.setPadding(0, 0, 0, dp(16))
                }
                topBar.animate().alpha(0f).setDuration(300).withEndAction {
                    topBar.visibility = View.GONE
                }
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_ufm_player_tv else R.layout.activity_ufm_player)

        shareId = intent.getStringExtra("shareId") ?: ""
        shareHost = intent.getStringExtra("shareHost") ?: ""
        shareName = intent.getStringExtra("shareName") ?: ""
        provider = intent.getStringExtra("provider") ?: ""
        initialFileSize = intent.getLongExtra("initialSize", 0L)
        val initialPath = intent.getStringExtra("initialPath") ?:
                         intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: ""
        remotePathExtra = intent.getStringExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_REMOTE_PATH) ?: ""
        playlist = intent.getStringArrayListExtra("playlist") ?: ArrayList()

        if (playlist.isEmpty() && initialPath.isNotEmpty()) {
            playlist.add(initialPath)
        }
        
        currentIndex = playlist.indexOf(initialPath)
        if (currentIndex == -1) currentIndex = 0

        initViews()
        playCurrent()
        if (isTv) {
            btnPlayPause.requestFocus()
        }
    }

    private fun resetHideTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        controlsLayout.visibility = View.VISIBLE
        controlsLayout.alpha = 1f
        topBar.visibility = View.VISIBLE
        topBar.alpha = 1f
        // Restore subtitle bottom margin when controls are visible
        subtitleView.setPadding(0, 0, 0, dp(100))

        // Only auto-hide if playing and sheet is not showing
        if (player?.isPlaying == true && !isShowingSheet) {
            handler.postDelayed(hideControlsRunnable, ControlsTimeoutManager.loadDurationMs(this))
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event?.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_BACK -> {
                    if (isShowingSheet) {
                        dismissTrackSheet()
                        return true
                    }
                }
            }
        }
        resetHideTimer()
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (event?.action == android.view.MotionEvent.ACTION_DOWN) {
            if (isShowingSheet) {
                // Check if the touch is outside the track sheet
                val sheetRect = android.graphics.Rect()
                trackSheetLayout.getGlobalVisibleRect(sheetRect)
                val touchX = event.rawX.toInt()
                val touchY = event.rawY.toInt()
                if (!sheetRect.contains(touchX, touchY)) {
                    dismissTrackSheet()
                    return true
                }
            }
            resetHideTimer()
        }
        return super.onTouchEvent(event)
    }

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnPrev = findViewById(R.id.btnPrev)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        seekBar = findViewById(R.id.seekBar)
        txtElapsed = findViewById(R.id.txtElapsed)
        txtRemaining = findViewById(R.id.txtRemaining)
        txtTitle = findViewById(R.id.txtTitle)
        txtTitle.isSelected = true
        audioPoster = findViewById(R.id.audioPoster)
        audioPlaceholder = findViewById(R.id.audioPlaceholder)
        audioPosterScrim = findViewById(R.id.audioPosterScrim)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        bufferingLayout = findViewById(R.id.bufferingLayout)
        controlsLayout = findViewById(R.id.controlsLayout)
        topBar = findViewById(R.id.topBar)
        btnAudioTrack = findViewById(R.id.btnAudioTrack)
        btnSubtitles = findViewById(R.id.btnSubtitles)
        subtitleView = findViewById(R.id.subtitleView)
        trackSheetLayout = findViewById(R.id.trackSheetLayout)
        trackSheetList = findViewById(R.id.trackSheetList)
        trackSheetTitle = findViewById(R.id.trackSheetTitle)

        // SubtitleView — default style (white text, black edge)
        subtitleView.visibility = View.GONE

        // Initial inactive state for track buttons
        updateAlpha(btnAudioTrack, false)
        updateAlpha(btnSubtitles, false)

        val mainView = findViewById<View>(R.id.main)
        if (mainView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val isTv = DeviceUtils.isTvDevice(this)

                // Pad playerView bottom to keep video content above the navigation bar
                val playerView = findViewById<View>(R.id.playerView)
                if (playerView != null) {
                    playerView.setPadding(0, 0, 0, if (isTv) 0 else systemBars.bottom)
                }

                // Adjust back button margin
                val btnBack = findViewById<View>(R.id.btnBack)
                if (btnBack != null) {
                    val lp = btnBack.layoutParams as android.view.ViewGroup.MarginLayoutParams
                    lp.topMargin = if (isTv) dp(24) else systemBars.top
                    lp.leftMargin = if (isTv) dp(24) else systemBars.left
                    btnBack.layoutParams = lp
                }

                // Pad controlsLayout
                val controlsLayout = findViewById<View>(R.id.controlsLayout)
                if (controlsLayout != null) {
                    val bottomPadding = if (isTv) dp(48) else (systemBars.bottom + dp(24))
                    val sidePadding = if (isTv) dp(48) else (systemBars.left + dp(24))
                    controlsLayout.setPadding(sidePadding, controlsLayout.paddingTop, sidePadding, bottomPadding)
                }

                WindowInsetsCompat.CONSUMED
            }
        }

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }

        updateAlpha(btnShuffle, isShuffle)
        updateAlpha(btnRepeat, isRepeat)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0L
                    updateTimeLabels(progress, if (duration > 0) duration.toInt() else 0)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isTracking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isTracking = false
                player?.seekTo(sb?.progress?.toLong() ?: 0L)
                resetHideTimer()
            }
        })

        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    player?.seekTo(seekBar.progress.toLong())
                    resetHideTimer()
                    true
                } else false
            } else false
        }

        // Also reset timer on button clicks
        listOf<View>(btnPlayPause, btnNext, btnPrev, btnShuffle, btnRepeat, btnAudioTrack, btnSubtitles).forEach {
            it.setOnClickListener { _ -> resetHideTimer() }
        }
        // Custom click handling for specific actions
        btnPlayPause.setOnClickListener {
            resetHideTimer()
            player?.let { p ->
                if (p.isPlaying) p.pause() else {
                    p.play()
                    handler.post(progressUpdater)
                }
                updatePlayPauseIcon()
            }
        }
        btnNext.setOnClickListener { resetHideTimer(); playNext() }
        btnPrev.setOnClickListener { resetHideTimer(); playPrev() }
        btnShuffle.setOnClickListener {
            resetHideTimer()
            isShuffle = !isShuffle
            updateAlpha(btnShuffle, isShuffle)
        }
        btnRepeat.setOnClickListener {
            resetHideTimer()
            isRepeat = !isRepeat
            updateAlpha(btnRepeat, isRepeat)
        }
        btnAudioTrack.setOnClickListener {
            resetHideTimer()
            toggleTrackSheet(MODE_AUDIO)
        }
        btnSubtitles.setOnClickListener {
            resetHideTimer()
            toggleTrackSheet(MODE_SUBTITLE)
        }
    }

    private fun updateAlpha(view: View, isActive: Boolean) {
        if (DeviceUtils.isTvDevice(this)) {
            view.alpha = if (isActive) 1.0f else 0.5f
        } else {
            view.alpha = if (isActive) 1.0f else 0.4f
        }
    }

    /**
     * Called whenever the player's track list changes (initial load or after track selection).
     * Detects available audio and subtitle tracks, updates current track lists and button states.
     */
    private fun detectAndUpdateTracks(tracks: Tracks) {
        val audioTracks = mutableListOf<AudioTrackInfo>()
        val embeddedSubtitles = mutableListOf<SubtitleTrackInfo>()

        for (group in tracks.groups) {
            val trackType = group.type
            val format = if (group.length > 0) group.getTrackFormat(0) else continue
            val isSelected = group.isTrackSelected(0)

            when (trackType) {
                C.TRACK_TYPE_AUDIO -> {
                    val langCode = format.language ?: "und"
                    val langDisplay = languageCodeToDisplay(langCode)
                    val label = (format.label?.ifEmpty { null } ?: langDisplay)
                    audioTracks.add(
                        AudioTrackInfo(
                            trackGroup = group.getMediaTrackGroup(),
                            trackIndex = 0,
                            language = langDisplay,
                            languageCode = langCode,
                            label = label,
                            isSelected = isSelected
                        )
                    )
                }
                C.TRACK_TYPE_TEXT -> {
                    val langCode = format.language ?: "und"
                    val langDisplay = languageCodeToDisplay(langCode)
                    val label = (format.label?.ifEmpty { null } ?: langDisplay)
                    embeddedSubtitles.add(
                        SubtitleTrackInfo(
                            trackGroup = group.getMediaTrackGroup(),
                            trackIndex = 0,
                            language = langDisplay,
                            languageCode = langCode,
                            label = label,
                            sourceType = "Embedded",
                            isExternal = false,
                            isSelected = isSelected
                        )
                    )
                }
            }
        }

        currentAudioTracks = audioTracks
        val allSubtitles = embeddedSubtitles + externalSubtitleInfos
        currentSubtitleTracks = allSubtitles

        // If no subtitle track is selected among available ones, subtitles are off
        val anySubSelected = currentSubtitleTracks.any { it.isSelected }

        // Update button states
        val hasMultipleAudio = audioTracks.size > 1
        val hasSubtitles = currentSubtitleTracks.isNotEmpty()
        updateAlpha(btnAudioTrack, hasMultipleAudio)
        updateAlpha(btnSubtitles, hasSubtitles)

        // Show/hide subtitle view based on whether a subtitle track is active
        if (anySubSelected) {
            if (subtitleView.visibility != View.VISIBLE) {
                subtitleView.visibility = View.VISIBLE
            }
        } else {
            subtitleView.visibility = View.GONE
        }

        GoRoLog.d("UFMPlayer", "Tracks detected: ${audioTracks.size} audio, ${currentSubtitleTracks.size} subtitle (${embeddedSubtitles.size} embedded + ${externalSubtitleInfos.size} external)")
    }

    /**
     * Scans the local file's parent directory for external subtitle files matching the video filename.
     * Infers language from filename pattern (e.g., movie.ru.srt → "rus").
     * Only applies to local playback; returns empty list for network files.
     */
    private fun scanExternalSubtitles(videoPath: String): List<SubtitleTrackInfo> {
        val isLocal = shareId.isEmpty() && shareHost.isEmpty()
        if (!isLocal) return emptyList()

        val videoFile = java.io.File(videoPath)
        val parentDir = videoFile.parentFile ?: return emptyList()
        val videoBaseName = videoFile.nameWithoutExtension

        val subtitleFiles = parentDir.listFiles { f ->
            f.isFile && f.extension.lowercase() in SUBTITLE_EXTENSIONS
        } ?: return emptyList()

        val matched = subtitleFiles.filter { f ->
            val fName = f.nameWithoutExtension.lowercase()
            fName == videoBaseName.lowercase() || fName.startsWith(videoBaseName.lowercase() + ".")
        }

        if (matched.isEmpty()) return emptyList()

        return matched.sortedBy { it.name }.mapIndexed { index, file ->
            val ext = file.extension.lowercase()
            val langCode = inferSubtitleLanguage(file.nameWithoutExtension, videoBaseName)
            val langDisplay = languageCodeToDisplay(langCode)
            val typeLabel = when (ext) {
                "srt" -> "SRT"
                "vtt" -> "VTT"
                "ass", "ssa" -> "ASS"
                "sub" -> "SUB"
                else -> ext.uppercase()
            }
            SubtitleTrackInfo(
                trackGroup = null,
                trackIndex = index,
                language = langDisplay,
                languageCode = langCode,
                label = "${langDisplay} ($typeLabel)",
                sourceType = typeLabel,
                isExternal = true,
                isSelected = false,
                subtitleUri = Uri.fromFile(file),
                subtitleMime = getMimeForSubtitleExtension(ext)
            )
        }
    }

    /**
     * Applies the given audio track as the active audio track.
     * Uses TrackSelectionOverride to switch seamlessly without restarting playback.
     */
    private fun applyAudioTrack(trackInfo: AudioTrackInfo) {
        player?.let { p ->
            val override = TrackSelectionOverride(trackInfo.trackGroup, listOf(trackInfo.trackIndex))
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setOverrideForType(override)
                .build()
            // Refresh the track list to reflect the new selection
            p.currentTracks?.let { detectAndUpdateTracks(it) }
        }
    }

    /**
     * Applies the given subtitle track, or disables subtitles if trackInfo is null (Off).
     * Both embedded and external subtitle tracks appear as TrackGroups in currentTracks
     * once injected via MediaItem.Builder.setSubtitles(), so they are selected the same way.
     */
    private fun applySubtitleTrack(trackInfo: SubtitleTrackInfo?) {
        player?.let { p ->
            val builder = p.trackSelectionParameters.buildUpon()
            if (trackInfo == null) {
                // Disable subtitles
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                // Enable and select this subtitle track
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                if (trackInfo.trackGroup != null) {
                    val override = TrackSelectionOverride(trackInfo.trackGroup, listOf(trackInfo.trackIndex))
                    builder.setOverrideForType(override)
                }
            }
            p.trackSelectionParameters = builder.build()
            p.currentTracks?.let { detectAndUpdateTracks(it) }
        }
    }

    /**
     * Toggles the track sheet: dismisses if same button pressed, switches if different,
     * opens if closed.
     */
    private fun toggleTrackSheet(mode: Int) {
        if (isShowingSheet) {
            if (currentSheetMode == mode) {
                // Same button pressed — dismiss
                dismissTrackSheet()
                return
            } else {
                // Different button — dismiss current, show new
                dismissTrackSheet()
            }
        }
        showTrackSheet(mode)
    }

    /**
     * Shows the track selection bottom sheet for the given mode (AUDIO or SUBTITLE).
     * Populates the track list dynamically, announces button state changes,
     * and handles selection via click/D-pad.
     */
    private fun showTrackSheet(mode: Int) {
        isShowingSheet = true
        currentSheetMode = mode

        val isTv = DeviceUtils.isTvDevice(this)
        val tracks = if (mode == MODE_AUDIO) currentAudioTracks else currentSubtitleTracks
        val isAudio = mode == MODE_AUDIO

        trackSheetTitle.text = getString(
            if (isAudio) R.string.audio_track else R.string.subtitles
        )

        trackSheetList.removeAllViews()

        // For subtitles, always add "Off" as the first option
        if (!isAudio) {
            trackSheetList.addView(createTrackItemView(
                label = getString(R.string.subtitles_off),
                type = null,
                isSelected = currentSubtitleTracks.none { it.isSelected },
                isTv = isTv,
                onClick = {
                    applySubtitleTrack(null)
                    dismissTrackSheet()
                }
            ))
        }

        if (tracks.isEmpty()) {
            // No tracks available — show a message row
            val msg = if (isAudio) {
                getString(R.string.no_alternate_audio)
            } else {
                getString(R.string.no_subtitles_available)
            }
            trackSheetList.addView(createTrackItemView(
                label = msg,
                type = null,
                isSelected = false,
                isTv = isTv,
                onClick = { dismissTrackSheet() },
                enabled = false
            ))
        } else if (isAudio) {
            for (track in currentAudioTracks) {
                trackSheetList.addView(createTrackItemView(
                    label = track.label,
                    type = null,
                    isSelected = track.isSelected,
                    isTv = isTv,
                    onClick = {
                        applyAudioTrack(track)
                        dismissTrackSheet()
                    }
                ))
            }
        } else {
            for (track in currentSubtitleTracks) {
                trackSheetList.addView(createTrackItemView(
                    label = track.label,
                    type = track.sourceType,
                    isSelected = track.isSelected,
                    isTv = isTv,
                    onClick = {
                        applySubtitleTrack(track)
                        dismissTrackSheet()
                    }
                ))
            }
        }

        // Set up D-pad focus chaining for TV so the ScrollView scrolls naturally
        if (isTv) {
            val childCount = trackSheetList.childCount
            for (i in 0 until childCount) {
                val child = trackSheetList.getChildAt(i)
                if (i > 0) {
                    child.nextFocusUpId = trackSheetList.getChildAt(i - 1).id
                }
                if (i < childCount - 1) {
                    child.nextFocusDownId = trackSheetList.getChildAt(i + 1).id
                }
                // First/last items have no explicit up/down — focus leaves the ScrollView naturally
            }
        }

        // Animate in
        trackSheetLayout.alpha = 0f
        trackSheetLayout.visibility = View.VISIBLE
        trackSheetLayout.animate()
            .alpha(1f)
            .setDuration(200)
            .withEndAction {
                // Auto-focus first focusable item on TV
                if (isTv) {
                    trackSheetList.getChildAt(0)?.requestFocus()
                }
            }
            .start()
    }

    /**
     * Dismisses the track selection sheet with a fade-out animation.
     */
    private fun dismissTrackSheet() {
        if (!isShowingSheet) return
        trackSheetLayout.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                trackSheetLayout.visibility = View.GONE
                isShowingSheet = false
                currentSheetMode = -1
                // Re-show controls if they were hidden
                if (player?.isPlaying == true) {
                    resetHideTimer()
                }
            }
            .start()
    }

    /**
     * Creates a single track item view row for the track selection sheet.
     * Consists of a checkmark icon, language label, and an optional type badge.
     * On TV, the row is focusable with the yellow selector background.
     */
    private fun createTrackItemView(
        label: String,
        type: String?,
        isSelected: Boolean,
        isTv: Boolean,
        onClick: () -> Unit,
        enabled: Boolean = true
    ): View {
        val dp = resources.displayMetrics.density
        val itemHeight = if (isTv) (64 * dp).toInt() else (48 * dp).toInt()
        val paddingH = if (isTv) (24 * dp).toInt() else (16 * dp).toInt()
        val textSize = if (isTv) 18f else 14f

        val row = LinearLayout(this)
        row.id = android.view.View.generateViewId()
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            itemHeight
        )
        row.setPadding(paddingH, 0, paddingH, 0)
        row.isClickable = true
        row.isFocusable = isTv
        row.isEnabled = enabled
        row.alpha = if (enabled) 1.0f else 0.5f

        if (isTv) {
            row.background = resources.getDrawable(R.drawable.selector_tv_list_item, theme)
            row.setPadding(paddingH, 0, paddingH, 0)
        }

        // Checkmark icon
        val checkmark = ImageView(this)
        val checkSize = if (isTv) (28 * dp).toInt() else (20 * dp).toInt()
        checkmark.layoutParams = LinearLayout.LayoutParams(checkSize, checkSize)
        checkmark.setImageResource(R.drawable.ic_check)
        if (isSelected) {
            checkmark.visibility = View.VISIBLE
            checkmark.setColorFilter(
                if (isTv) android.graphics.Color.parseColor("#FBBF24")
                else androidx.core.content.ContextCompat.getColor(this, R.color.ufm_primary)
            )
        } else {
            checkmark.visibility = View.INVISIBLE
        }

        // Language label
        val labelView = TextView(this)
        labelView.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = (12 * dp).toInt() }
        labelView.text = label
        labelView.textSize = textSize
        labelView.setTextColor(
            if (isTv) resources.getColor(R.color.tv_text_primary, theme)
            else android.graphics.Color.WHITE
        )
        labelView.ellipsize = android.text.TextUtils.TruncateAt.END
        labelView.maxLines = 1

        row.addView(checkmark)
        row.addView(labelView)

        // Type badge (for subtitles: "Embedded", "SRT", "VTT", etc.)
        if (type != null) {
            val badge = TextView(this)
            badge.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * dp).toInt() }
            badge.text = type
            badge.textSize = if (isTv) 14f else 11f
            badge.setTextColor(resources.getColor(R.color.ufm_primary, theme))
            badge.setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
            badge.setBackgroundResource(R.drawable.bg_chip)
            row.addView(badge)
        }

        row.setOnClickListener { if (enabled) onClick() }

        return row
    }

    private fun playCurrent() {
        if (playlist.isEmpty()) return
        val path = playlist[currentIndex]
        val fileName = path.substringAfterLast("/")
        txtTitle.text = fileName
        
        loadingSpinner.visibility = View.VISIBLE
        
        val isAudio = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.isAudio(fileName.substringAfterLast("."))
        if (isAudio) {
            audioPoster.visibility = View.VISIBLE
            audioPosterScrim.visibility = View.VISIBLE
            audioPlaceholder.visibility = View.VISIBLE
            audioPoster.setImageDrawable(null)
            playerView.visibility = View.GONE
            // Hide subtitle button for audio-only
            btnSubtitles.visibility = View.GONE
        } else {
            audioPoster.visibility = View.GONE
            audioPosterScrim.visibility = View.GONE
            audioPlaceholder.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            // Restore subtitle button for video
            btnSubtitles.visibility = View.VISIBLE
        }

        resetPlayer()

        val isLocal = shareId.isEmpty() && shareHost.isEmpty()

        // ── Local file playback ──
        if (isLocal) {
            val localFile = java.io.File(path)
            if (!localFile.exists()) {
                GoRoLog.e("UFMPlayer", "Local file not found: $path")
                bufferingLayout.visibility = View.GONE
                return
            }

            bufferingLayout.visibility = View.VISIBLE

            // Scan for external subtitle files (off main thread)
            val externalSubs: List<SubtitleTrackInfo> = if (!isAudio) {
                scanExternalSubtitles(path)
            } else emptyList()
            externalSubtitleInfos = externalSubs

            Thread {
                try {
                    val videoUri = android.net.Uri.fromFile(localFile)

                    // Build MediaItem with external subtitle tracks
                    val mediaItemBuilder = MediaItem.Builder().setUri(videoUri)
                    if (externalSubs.isNotEmpty()) {
                        val subtitleList = externalSubs.mapNotNull { sub: SubtitleTrackInfo ->
                            val uri = sub.subtitleUri ?: return@mapNotNull null
                            val mime = sub.subtitleMime ?: return@mapNotNull null
                            MediaItem.Subtitle(uri, mime, sub.languageCode, 0)
                        }
                        if (subtitleList.isNotEmpty()) {
                            mediaItemBuilder.setSubtitles(subtitleList)
                        }
                    }
                    val mediaItem = mediaItemBuilder.build()

                    val dataSourceFactory = DefaultDataSource.Factory(this@UFMPlayerActivity)
                    val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
                        .createMediaSource(mediaItem)

                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread

                        val newPlayer = ExoPlayer.Builder(this@UFMPlayerActivity).build()
                        player = newPlayer
                        playerView.player = newPlayer

                        newPlayer.setMediaSource(mediaSource)
                        newPlayer.prepare()

                        newPlayer.addListener(playerListener)
                        newPlayer.playWhenReady = true
                        newPlayer.play()
                        handler.post(progressUpdater)
                    }
                } catch (e: Exception) {
                    GoRoLog.e("UFMPlayer", "Local playback setup failed", e)
                    runOnUiThread { bufferingLayout.visibility = View.GONE }
                }
            }.start()

            // Audio poster extraction for local files
            if (isAudio) {
                Thread {
                    var retriever: MediaMetadataRetriever? = null
                    try {
                        retriever = MediaMetadataRetriever()
                        retriever.setDataSource(path)
                        val art = retriever.embeddedPicture
                        if (art != null) {
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                            runOnUiThread {
                                if (!isDestroyed) {
                                    audioPoster.setImageBitmap(bmp)
                                    audioPlaceholder.visibility = View.GONE
                                }
                            }
                        }
                    } catch (e: Exception) {
                        GoRoLog.e("UFMPlayer", "Failed to extract poster", e)
                    } finally {
                        try { retriever?.release() } catch (e: Exception) {}
                    }
                }.start()
            }
            return
        }

        // ── Network file playback ──
        var share = NetworkShareRepository.getInstance(this).getById(shareId)
        // Server-mode shares need remotePath from the intent (browser updates it at navigation time)
        if (share?.isServerMode == true && remotePathExtra.isNotEmpty()) {
            share = share.copy(remotePath = remotePathExtra)
        }
        if (share == null && shareHost.isNotEmpty()) {
            GoRoLog.d("UFMPlayer", "Share not found in repo, creating dummy for $shareHost")
            share = za.kilowatch.ultimatefilemanager.network.NetworkShare(
                id = shareId,
                host = shareHost,
                name = shareName,
                type = when (provider) {
                    "GOOGLE_DRIVE" -> za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE
                    "DLNA" -> za.kilowatch.ultimatefilemanager.network.ShareType.DLNA
                    else -> za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE
                }
            )
        }

        if (share == null) {
            GoRoLog.e("UFMPlayer", "NetworkShare not found for ID: $shareId and no fallback host")
            bufferingLayout.visibility = View.GONE
            return
        }

        bufferingLayout.visibility = View.VISIBLE
        
        Thread {
            try {
                val mediaSource = if (provider == "GOOGLE_DRIVE" || provider == "ONEDRIVE") {
                    val (url, token) = if (provider == "GOOGLE_DRIVE") {
                        GoogleDriveShareClient.getStreamingUrlAndTokenSync(share, path)
                    } else {
                        OnedriveShareClient.getStreamingUrlAndTokenSync(share, path)
                    }
                    val okhttpClient = okhttp3.OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val dataSourceFactory = OkHttpDataSource.Factory(okhttpClient)
                        .setDefaultRequestProperties(
                            if (token.isNotEmpty()) mapOf("Authorization" to "Bearer $token") else emptyMap()
                        )
                        .setUserAgent(Util.getUserAgent(this@UFMPlayerActivity, "UFM"))
                    DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(MediaItem.fromUri(Uri.parse(url)))
                } else if (provider == "NFS") {
                    val ext = path.substringAfterLast('.').lowercase()
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                        ?: "video/mp4"
                    val proxyUrl = za.kilowatch.ultimatefilemanager.network.NetworkHttpProxyServer.register(
                        share, path, mime, initialFileSize
                    )
                    val okhttpClient = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val dataSourceFactory = OkHttpDataSource.Factory(okhttpClient)
                        .setUserAgent(Util.getUserAgent(this@UFMPlayerActivity, "UFM"))
                    DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(MediaItem.fromUri(Uri.parse(proxyUrl)))
                } else /* SMB, FTP, SFTP, SCP, NFS, DLNA — via UfmMedia3DataSource */ {
                    val dataSourceFactory = DataSource.Factory { UfmMedia3DataSource(share, path) }
                    DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(MediaItem.fromUri(Uri.parse("ufm://${path.replace(" ", "%20")}")))
                }
                
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    
                    val newPlayer = ExoPlayer.Builder(this@UFMPlayerActivity).build()
                    player = newPlayer
                    playerView.player = newPlayer
                    
                    newPlayer.setMediaSource(mediaSource)
                    newPlayer.prepare()
                    
                    newPlayer.addListener(playerListener)
                    newPlayer.playWhenReady = true
                    newPlayer.play()
                    handler.post(progressUpdater)
                }
            } catch (e: Exception) {
                GoRoLog.e("UFMPlayer", "Setup failed", e)
                runOnUiThread { bufferingLayout.visibility = View.GONE }
            }
        }.start()

        // AUDIO POSTER EXTRACTOR (Direct API 14+ native extraction without data sources)
        if (isAudio) {
            Thread {
                var retriever: MediaMetadataRetriever? = null
                var randomAccessFile: IRandomAccessFile? = null
                try {
                    retriever = MediaMetadataRetriever()
                    
                    if (provider == "GOOGLE_DRIVE" || provider == "ONEDRIVE") {
                        val (url, token) = if (provider == "GOOGLE_DRIVE") {
                            GoogleDriveShareClient.getStreamingUrlAndTokenSync(share, path)
                        } else {
                            OnedriveShareClient.getStreamingUrlAndTokenSync(share, path)
                        }
                        retriever.setDataSource(url, mapOf("Authorization" to "Bearer $token"))
                    } else {
                        randomAccessFile = when (share.type) {
                            ShareType.SMB -> SmbShareClient.openRandomAccessFile(share, path)
                            ShareType.FTP -> FtpShareClient.openRandomAccessFile(share, path)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.openRandomAccessFile(share, path)
                            ShareType.NFS -> NfsShareClient.openRandomAccessFile(share, path)
                            ShareType.DLNA -> DlnaShareClient.openRandomAccessFile(share, path)
                            else -> throw IllegalStateException("Unsupported share type for random access")
                        }
                        retriever.setDataSource(CommonMediaDataSource(randomAccessFile))
                    }
                    val art = retriever.embeddedPicture
                    
                    if (art != null) {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                        runOnUiThread {
                            if (!isDestroyed) {
                                audioPoster.setImageBitmap(bmp)
                                audioPlaceholder.visibility = View.GONE
                            }
                        }
                    }
                } catch (e: Exception) {
                   GoRoLog.e("UFMPlayer", "Failed to extract poster", e)
                } finally {
                    try { retriever?.release() } catch (e: Exception) {}
                    try { randomAccessFile?.close() } catch (e: Exception) {}
                }
            }.start()
        }
    }

    private fun resetPlayer() {
        handler.removeCallbacks(progressUpdater)
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        seekBar.progress = 0
        updatePlayPauseIcon()
    }

    private fun playNext() {
        if (playlist.isEmpty()) return
        if (isShuffle) {
            currentIndex = (0 until playlist.size).random()
        } else {
            currentIndex = (currentIndex + 1) % playlist.size
        }
        playCurrent()
    }

    private fun playPrev() {
        if (playlist.isEmpty()) return
        val pos = player?.currentPosition ?: 0L
        if (pos > 5000L) {
            player?.seekTo(0)
            return
        }
        if (isShuffle) {
            currentIndex = (0 until playlist.size).random()
        } else {
            currentIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        }
        playCurrent()
    }

    private fun updatePlayPauseIcon() {
        val isPlaying = player?.isPlaying ?: false
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        if (btnPlayPause is FloatingActionButton) {
            (btnPlayPause as FloatingActionButton).setImageResource(icon)
        } else if (btnPlayPause is ImageButton) {
            (btnPlayPause as ImageButton).setImageResource(icon)
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (!isTracking && p.isPlaying && (!DeviceUtils.isTvDevice(this@UFMPlayerActivity) || !seekBar.isFocused)) {
                    val pos = p.currentPosition.toInt()
                    val dur = p.duration
                    if (dur > 0L) {
                        seekBar.max = dur.toInt()
                        seekBar.progress = pos
                        updateTimeLabels(pos, dur.toInt())
                    }
                }
            }
            if (!isFinishing && !isDestroyed) handler.postDelayed(this, 1000)
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

    private fun dp(px: Int): Int {
        return (px * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        resetPlayer()
    }
}

class UfmMedia3DataSource(
    private val share: NetworkShare,
    private val path: String
) : DataSource {

    private var randomAccess: IRandomAccessFile? = null
    private var fileLength: Long = 0
    private var bytesRemaining: Long = 0
    private var streamPosition: Long = 0
    private var opened = false
    private var currentUri: Uri? = null

    // 2 MB cache buffer for read-ahead to minimize network IO calls
    private val CACHE_SIZE = 2 * 1024 * 1024 
    private val cacheBuffer = ByteArray(CACHE_SIZE)
    private var cacheStartPos: Long = -1L
    private var cacheEndPos: Long = -1L

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        val randomAccessFile = try {
            when (share.type) {
                ShareType.SMB -> SmbShareClient.openRandomAccessFile(share, path)
                ShareType.FTP -> FtpShareClient.openRandomAccessFile(share, path)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.openRandomAccessFile(share, path)
                ShareType.NFS -> NfsShareClient.openRandomAccessFile(share, path)
                ShareType.DLNA -> DlnaShareClient.openRandomAccessFile(share, path)
                else -> throw IllegalStateException("Unsupported share type for random access")
            }
        } catch (e: Exception) {
            throw IOException("Failed to open file on ${share.type}: ${e.message}")
        }
        randomAccess = randomAccessFile
        fileLength = try {
            randomAccessFile.size
        } catch (e: Exception) {
            -1L
        }
        streamPosition = dataSpec.position
        bytesRemaining = if (fileLength < 0) {
            C.LENGTH_UNSET.toLong()
        } else if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            fileLength - streamPosition
        } else {
            dataSpec.length
        }
        cacheStartPos = -1L
        cacheEndPos = -1L
        opened = true
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val bytesToRead = if (length > bytesRemaining && bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining.toInt()
        } else {
            length
        }
        if (bytesToRead <= 0) return C.RESULT_END_OF_INPUT

        // Fast path: fulfill from cache
        if (streamPosition >= cacheStartPos && streamPosition < cacheEndPos) {
            val availableInCache = (cacheEndPos - streamPosition).toInt()
            val toCopy = minOf(bytesToRead, availableInCache)
            System.arraycopy(cacheBuffer, (streamPosition - cacheStartPos).toInt(), buffer, offset, toCopy)
            streamPosition += toCopy
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
            return toCopy
        }

        // Cache miss: Load large chunk block from network into cache
        val fetchSize = if (fileLength >= 0) {
            minOf(CACHE_SIZE.toLong(), fileLength - streamPosition).toInt()
        } else {
            CACHE_SIZE
        }
        if (fetchSize <= 0) return C.RESULT_END_OF_INPUT

        try {
            val readLength = randomAccess!!.read(streamPosition, cacheBuffer, fetchSize)
            if (readLength <= 0) return C.RESULT_END_OF_INPUT

            cacheStartPos = streamPosition
            cacheEndPos = streamPosition + readLength

            val toCopy = minOf(bytesToRead, readLength)
            System.arraycopy(cacheBuffer, 0, buffer, offset, toCopy)
            streamPosition += toCopy
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
            return toCopy
        } catch (e: Exception) {
            return C.RESULT_END_OF_INPUT
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (opened) {
            try { randomAccess?.close() } catch (e: Exception) {}
            opened = false
            randomAccess = null
            cacheStartPos = -1L
            cacheEndPos = -1L
        }
    }
}

class CommonMediaDataSource(
    private val randomAccess: IRandomAccessFile
) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= randomAccess.size) return -1
        val safeBuffer = ByteArray(size)
        val read = randomAccess.read(position, safeBuffer, size)
        if (read > 0) {
            System.arraycopy(safeBuffer, 0, buffer, offset, read)
        }
        return read
    }
    override fun getSize(): Long = randomAccess.size
    override fun close() = randomAccess.close()
}

// ── Track Selection Data Classes & Constants ──────────────────────────────────

private const val MODE_AUDIO = 0
private const val MODE_SUBTITLE = 1

private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")

data class AudioTrackInfo(
    val trackGroup: TrackGroup,
    val trackIndex: Int,
    val language: String,
    val languageCode: String,
    val label: String,
    val isSelected: Boolean
)

data class SubtitleTrackInfo(
    val trackGroup: TrackGroup?,
    val trackIndex: Int,
    val language: String,
    val languageCode: String = "und",
    val label: String,
    val sourceType: String,
    val isExternal: Boolean,
    val isSelected: Boolean,
    val subtitleUri: Uri? = null,
    val subtitleMime: String? = null
)

/**
 * Maps a subtitle file extension to its corresponding Media3 MIME type.
 */
private fun getMimeForSubtitleExtension(ext: String): String {
    return when (ext.lowercase()) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        "sub" -> "text/x-microdvd"
        else -> "application/x-subrip"
    }
}

/**
 * Infers an ISO 639-2 language code from a subtitle filename
 * by extracting the language part between the video base name and the extension.
 * Returns "und" (undefined) if no language pattern is detected.
 *
 * Examples:
 *   "movie.en.srt"   → "eng"
 *   "movie.ru.srt"   → "rus"
 *   "movie.srt"      → "und"
 */
private fun inferSubtitleLanguage(fileNameWithoutExt: String, videoBaseName: String): String {
    val name = fileNameWithoutExt.lowercase()
    val base = videoBaseName.lowercase().substringBeforeLast(".")
    val suffix = name.removePrefix(base).removePrefix(".")
    return when (suffix) {
        "en" -> "eng"
        "ru" -> "rus"
        "de" -> "deu"
        "fr" -> "fra"
        "es" -> "spa"
        "it" -> "ita"
        "pt" -> "por"
        "nl" -> "nld"
        "pl" -> "pol"
        "ja" -> "jpn"
        "ko" -> "kor"
        "zh" -> "zho"
        "ar" -> "ara"
        "hi" -> "hin"
        "tr" -> "tur"
        "sv" -> "swe"
        "da" -> "dan"
        "fi" -> "fin"
        "no" -> "nor"
        "cs" -> "ces"
        "hu" -> "hun"
        "ro" -> "ron"
        "uk" -> "ukr"
        "el" -> "ell"
        "he" -> "heb"
        "th" -> "tha"
        "vi" -> "vie"
        "id" -> "ind"
        "ms" -> "msa"
        "tl" -> "tgl"
        else -> "und"
    }
}

/**
 * Converts an ISO 639-2/B three-letter language code to a human-readable display name.
 * Falls back to the uppercase code if the locale is not recognised.
 */
private fun languageCodeToDisplay(code: String): String {
    if (code == "und") return "Unknown"
    return try {
        Locale(code).displayLanguage.ifEmpty { code.uppercase() }
    } catch (_: Exception) {
        code.uppercase()
    }
}
