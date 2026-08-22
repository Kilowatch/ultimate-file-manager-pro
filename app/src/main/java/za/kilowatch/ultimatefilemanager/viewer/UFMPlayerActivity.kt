package za.kilowatch.ultimatefilemanager.viewer

import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.View
import android.view.WindowManager
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.DlnaShareClient
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.IRandomAccessFile
import za.kilowatch.ultimatefilemanager.network.NetworkHttpProxyServer
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import za.kilowatch.ultimatefilemanager.settings.ControlsTimeoutManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.PlayerPreferencesManager
import za.kilowatch.ultimatefilemanager.settings.BackgroundVideoMode
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.util.ArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import za.kilowatch.ultimatefilemanager.network.NetworkFile

import za.kilowatch.ultimatefilemanager.network.SubtitleIntentHelper

/**
 * UFM Media Player — Full-featured audio/video player.
 *
 * Rewritten to bind to [UFMPlaybackService] instead of owning the ExoPlayer directly.
 * The service handles background playback, notification, and audio focus.
 * This Activity handles the full-screen player UI, controls, PiP, and now-playing views.
 */
class UFMPlayerActivity : AppCompatActivity() {

    // ── Views ───────────────────────────────────────────────────────
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null   // Set from service when bound (for track selection etc.)

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
    private lateinit var btnSkipBack: ImageButton
    private lateinit var btnSkipForward: ImageButton
    private lateinit var subtitleView: SubtitleView
    private lateinit var trackSheetLayout: View
    private lateinit var trackSheetList: LinearLayout
    private lateinit var trackSheetTitle: TextView

    // ── Next-Track Overlay ──────────────────────────────────────────
    private lateinit var nextTrackOverlay: NextTrackOverlayView
    private var hasPendingNextTrack = false

    // ── PiP Action Updates ─────────────────────────────────────────

    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
    private fun updatePiPActions(isPlaying: Boolean) {
        val prevIntent = Intent(this, UFMPlaybackService::class.java)
            .setAction(UFMPlaybackService.ACTION_SKIP_PREV)
        val prevPendingIntent = PendingIntent.getService(
            this, 12, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevAction = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_skip_previous),
            "Previous", "Previous",
            prevPendingIntent
        )

        val toggleIntent = Intent(this, UFMPlaybackService::class.java)
            .setAction(UFMPlaybackService.ACTION_TOGGLE)
        val togglePendingIntent = PendingIntent.getService(
            this, 10, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleAction = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this,
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
            getString(if (isPlaying) R.string.mini_player_pause_content_desc else R.string.mini_player_play_content_desc),
            getString(if (isPlaying) R.string.mini_player_pause_content_desc else R.string.mini_player_play_content_desc),
            togglePendingIntent
        )

        val nextIntent = Intent(this, UFMPlaybackService::class.java)
            .setAction(UFMPlaybackService.ACTION_SKIP_NEXT)
        val nextPendingIntent = PendingIntent.getService(
            this, 13, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextAction = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_skip_next),
            "Next", "Next",
            nextPendingIntent
        )

        try {
            setPictureInPictureParams(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .setActions(listOf(prevAction, toggleAction, nextAction))
                    .build()
            )
        } catch (_: Exception) {}
    }

    // ── Queue Drawer ───────────────────────────────────────────────
    private var queueDrawerLayout: View? = null
    private var queueRecyclerView: RecyclerView? = null
    private var queueAdapter: QueueAdapter? = null
    private var isQueueDrawerOpen = false

    // ── Intent Extras (passed to service) ────────────────────────────
    private var playlist: ArrayList<String> = ArrayList()
    private var currentIndex = 0
    private var shareId: String = ""
    private var shareHost: String = ""
    private var shareUsername: String = ""
    private var shareName: String = ""
    private var provider: String = ""
    private var remotePathExtra: String = ""
    private var initialFileSize: Long = 0L

    // ── State ───────────────────────────────────────────────────────
    private var isShowingSheet = false
    private var currentSheetMode = -1
    private var currentAudioTracks: List<AudioTrackInfo> = emptyList()
    private var currentSubtitleTracks: List<SubtitleTrackInfo> = emptyList()
    private var externalSubtitleInfos: List<SubtitleTrackInfo> = emptyList()
    private var isPiP = false

    private val handler = Handler(Looper.getMainLooper())
    private var isTracking = false
    private var mjpegPlayer: za.kilowatch.ultimatefilemanager.media.MjpegPlayerHelper? = null

    private val speedGestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: android.view.MotionEvent) {
                showSpeedSheet()
            }
        })
    }

    // ── Service Binding ──────────────────────────────────────────────

    private var playbackService: UFMPlaybackService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? UFMPlaybackService.LocalBinder ?: return
            playbackService = binder.getService().also { svc ->
                // Set player reference for track operations
                player = svc.getPlayer()
                playerView.player = player
                // Register to receive callbacks
                svc.registerCallback(playbackCallback)
                // Push current state
                updatePlayPauseIcon()
                svc.getPlayer()?.let { p ->
                    if (p.isPlaying) handler.post(progressUpdater)
                }
                // Update queue display
                playbackCallback.onQueueChanged(svc.queueManager.queue)
            }
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            player = null
            playerView.player = null
            playbackService = null
            bound = false
        }
    }

    // ── Callback from Service ───────────────────────────────────────

    private val playbackCallback = object : UFMPlaybackService.PlaybackCallback {
        override fun onProgressUpdate(position: Long, duration: Long) {
            if (!isTracking && !isPiP) {
                if (duration > 0) {
                    seekBar.max = duration.toInt()
                    seekBar.progress = position.toInt()
                    updateTimeLabels(position.toInt(), duration.toInt())
                }
            }
        }

        override fun onTrackChanged(trackInfo: QueueItem?) {
            runOnUiThread {
                hasPendingNextTrack = false
                nextTrackOverlay.hideImmediately()
                if (trackInfo != null) {
                    val fileName = trackInfo.path.substringAfterLast("/")
                    txtTitle.text = trackInfo.title ?: fileName

                    // Switch audio/video mode
                    val isAudio = trackInfo.isVideo.not()
                    if (isAudio) {
                        audioPoster.visibility = View.VISIBLE
                        audioPosterScrim.visibility = View.VISIBLE
                        audioPlaceholder.visibility = View.VISIBLE
                        playerView.visibility = View.GONE
                        btnSubtitles.visibility = View.GONE
                    } else {
                        audioPoster.visibility = View.GONE
                        audioPosterScrim.visibility = View.GONE
                        audioPlaceholder.visibility = View.GONE
                        playerView.visibility = View.VISIBLE
                        btnSubtitles.visibility = View.VISIBLE
                    }

                    // Reset external subtitles for the new track, then scan in parallel
                    externalSubtitleInfos = emptyList()
                    val isNetwork = shareId.isNotEmpty() || shareHost.isNotEmpty()
                    if (trackInfo.isVideo && isNetwork) {
                        this@UFMPlayerActivity.lifecycleScope.launch {
                            val scanned = withContext(Dispatchers.IO) {
                                scanNetworkSubtitles(trackInfo.path)
                            }
                            // Update on main thread and refresh the track sheet
                            externalSubtitleInfos = scanned
                            player?.currentTracks?.let { detectAndUpdateTracks(it) }
                        }
                    } else if (trackInfo.isVideo) {
                        // Local file — run synchronously off main thread
                        this@UFMPlayerActivity.lifecycleScope.launch(Dispatchers.IO) {
                            val scanned = scanExternalSubtitles(trackInfo.path)
                            withContext(Dispatchers.Main) {
                                externalSubtitleInfos = scanned
                                player?.currentTracks?.let { detectAndUpdateTracks(it) }
                            }
                        }
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean, state: Int, isLocal: Boolean) {
            runOnUiThread {
                if (state == Player.STATE_BUFFERING && !isLocal) {
                    bufferingLayout.visibility = View.VISIBLE
                } else {
                    bufferingLayout.visibility = View.GONE
                }
                updatePlayPauseIcon()
                if (isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    playerView.keepScreenOn = true
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    playerView.keepScreenOn = false
                }
                // Update PiP actions if in PiP mode
                if (isPiP) updatePiPActions(isPlaying)
                if (isPlaying) {
                    resetHideTimer()
                    handler.post(progressUpdater)
                } else {
                    handler.removeCallbacks(progressUpdater)
                    handler.removeCallbacks(hideControlsRunnable)
                }
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata) {
            runOnUiThread {
                metadata.title?.toString()?.let { txtTitle.text = it }
                // Poster extraction happens in the service, but if we get a bitmap here
                // we could set it on audioPoster. For now, the service handles it internally.
            }
        }

        override fun onQueueChanged(queue: List<QueueItem>) {
            // Will be used by the queue drawer (T019-T020)
        }

        override fun onError(error: String) {
            runOnUiThread {
                bufferingLayout.visibility = View.GONE
                Toast.makeText(this@UFMPlayerActivity, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Auto-hide Controls ──────────────────────────────────────────

    private val hideControlsRunnable = Runnable {
        runOnUiThread {
            if (!isDestroyed && !isFinishing && !isShowingSheet) {
                controlsLayout.animate().alpha(0f).setDuration(300).withEndAction {
                    controlsLayout.visibility = View.GONE
                    subtitleView.setPadding(0, 0, 0, dp(16))
                }
                topBar.animate().alpha(0f).setDuration(300).withEndAction {
                    topBar.visibility = View.GONE
                }
            }
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            val svc = playbackService
            if (svc != null && !isTracking && !isPiP) {
                val pos = svc.currentPosition
                val dur = svc.duration
                if (dur > 0) {
                    seekBar.max = dur.toInt()
                    seekBar.progress = pos.toInt()
                    updateTimeLabels(pos.toInt(), dur.toInt())

                    // ── Next-track overlay check ──
                    val remaining = dur - pos
                    val queue = svc.queueManager
                    if (remaining <= 5000 && remaining > 0 && queue.size > 1 &&
                        queue.currentIndex < queue.size - 1 && !hasPendingNextTrack) {
                        val nextItem = queue.get(queue.currentIndex + 1)
                        if (nextItem != null) {
                            hasPendingNextTrack = true
                            val nextDur = if (nextItem.duration > 0) formatTime(nextItem.duration.toInt()) else ""
                            val fileName = nextItem.title ?: nextItem.path.substringAfterLast("/")
                            nextTrackOverlay.showNextTrack(
                                fileName = fileName,
                                durationText = nextDur,
                                onCancel = {
                                    svc.setSkipAutoplayOnce(true)
                                    nextTrackOverlay.hideOverlay()
                                },
                                onSkip = {
                                    svc.skipToNext()
                                    nextTrackOverlay.hideImmediately()
                                    hasPendingNextTrack = false
                                }
                            )
                        }
                    } else if (remaining > 5000 && hasPendingNextTrack) {
                        // Reset if user seeks backwards
                        hasPendingNextTrack = false
                        nextTrackOverlay.hideImmediately()
                    }
                }
            }
            if (playbackService?.isPlaying == true && !isFinishing && !isDestroyed) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────

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

        // Read extras
        shareId = intent.getStringExtra("shareId") ?: ""
        shareHost = intent.getStringExtra("shareHost") ?: ""
        shareUsername = intent.getStringExtra("shareUsername") ?: ""
        shareName = intent.getStringExtra("shareName") ?: ""
        provider = intent.getStringExtra("provider") ?: ""
        val initialPath = intent.getStringExtra("initialPath")
            ?: intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH)
            ?: intent.data?.path
            ?: intent.dataString
            ?: ""
        remotePathExtra = intent.getStringExtra(
            za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_REMOTE_PATH
        ) ?: ""
        // Prefer cache to avoid TransactionTooLargeException for large folders
        val cacheKey = intent.getStringExtra("playlistCacheKey") ?: ""
        val cachedPlaylist = PlaylistCache.take(cacheKey)

        playlist = when {
            cachedPlaylist != null -> ArrayList(cachedPlaylist)
            // Legacy path: small playlists still passed directly (e.g. from TwinWindow / network browser)
            intent.hasExtra("playlist") -> intent.getStringArrayListExtra("playlist") ?: ArrayList()
            else -> ArrayList()
        }

        if (playlist.isEmpty() && initialPath.isNotEmpty()) {
            playlist.add(initialPath)
        }
        currentIndex = playlist.indexOf(initialPath)
        if (currentIndex == -1) currentIndex = 0

        initViews()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isShowingSheet) {
                    dismissTrackSheet()
                } else {
                    stopPlaybackAndFinish()
                }
            }
        })

        val fileName = initialPath.substringAfterLast('/')
        txtTitle.text = if (fileName.isNotEmpty()) fileName else "Media Title"

        val ext = initialPath.substringAfterLast('.', "").lowercase()
        val isMjpeg = ext in setOf("mjpeg", "mjpg", "mjp")
        if (isMjpeg) {
            audioPoster.visibility = View.VISIBLE
            audioPoster.scaleType = ImageView.ScaleType.FIT_CENTER
            playerView.visibility = View.GONE
            audioPlaceholder.visibility = View.GONE
            audioPosterScrim.visibility = View.GONE
            loadingSpinner.visibility = View.VISIBLE

            lifecycleScope.launch(Dispatchers.IO) {
                val isLocal = shareId.isEmpty() && shareHost.isEmpty() && provider.isEmpty()
                val ds: za.kilowatch.ultimatefilemanager.media.MjpegDataSource? = if (isLocal) {
                    val f = File(initialPath)
                    if (f.exists()) za.kilowatch.ultimatefilemanager.media.FileMjpegDataSource(f) else null
                } else {
                    val isServerMode = intent.getBooleanExtra("isServerMode", false)
                    val rawShare = buildPlaybackNetworkShare(shareId, shareHost, shareUsername, shareName, provider, remotePathExtra, isServerMode)
                    if (rawShare != null) {
                        val (share, subPath) = resolveEffectiveShareAndPath(rawShare, remotePathExtra, initialPath)
                        val ra = when (share.type) {
                            ShareType.SMB -> SmbShareClient.openRandomAccessFile(share, subPath)
                            ShareType.FTP -> FtpShareClient.openRandomAccessFile(share, subPath)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.openRandomAccessFile(share, subPath)
                            ShareType.NFS -> NfsShareClient.openRandomAccessFile(share, subPath)
                            ShareType.DLNA -> DlnaShareClient.openRandomAccessFile(share, subPath)
                            ShareType.WEBDAV -> WebDavShareClient.openRandomAccessFile(share, subPath, initialFileSize)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openRandomAccessFile(share, subPath)
                            ShareType.ONEDRIVE -> OnedriveShareClient.openRandomAccessFile(share, subPath)
                            ShareType.DROPBOX -> DropboxShareClient.openRandomAccessFile(share, subPath)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openRandomAccessFile(share, subPath)
                            else -> null
                        }
                        if (ra != null) za.kilowatch.ultimatefilemanager.media.RemoteMjpegDataSource(ra) else null
                    } else null
                }

                if (ds == null) {
                    withContext(Dispatchers.Main) {
                        loadingSpinner.visibility = View.GONE
                        PlayerToastHelper.show(this@UFMPlayerActivity, "Playback error")
                    }
                    return@launch
                }

                val player = za.kilowatch.ultimatefilemanager.media.MjpegPlayerHelper(
                    dataSource = ds,
                    onFrameRendered = { currentMs, totalMs ->
                        if (!isTracking) {
                            seekBar.max = totalMs.toInt()
                            seekBar.progress = currentMs.toInt()
                            updateTimeLabels(currentMs.toInt(), totalMs.toInt())
                        }
                    },
                    onPlaybackStateChanged = { _ ->
                        updatePlayPauseIcon()
                    }
                )
                mjpegPlayer = player

                withContext(Dispatchers.Main) {
                    loadingSpinner.visibility = View.GONE
                    player.start(audioPoster, lifecycleScope)
                    updatePlayPauseIcon()
                }
            }
        } else {
            // Start & bind to the playback service
            UFMPlaybackService.start(this, intent)
            bindService(Intent(this, UFMPlaybackService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        }

        if (isTv) {
            btnPlayPause.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        updateSkipButtonVisibility()
        isPiP = false
        val ext = (intent.getStringExtra("initialPath") ?: intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: "").substringAfterLast('.', "").lowercase()
        if (ext in setOf("mjpeg", "mjpg", "mjp") || mjpegPlayer != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        if (playbackService?.isPlaying == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            playerView.keepScreenOn = true
        }
        // Re-attach player surface when returning from PiP
        if (!bound) {
            bindService(Intent(this, UFMPlaybackService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            playbackService?.getPlayer()?.let { p ->
                player = p
                playerView.player = p
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mjpegPlayer?.pause()
        updatePlayPauseIcon()
        // Never detach player here — PiP lifecycle handles surface transitions
        // Player stays attached so the PiP window and Activity surface stay linked
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        if (bound) {
            playbackService?.unregisterCallback()
            unbindService(serviceConnection)
            bound = false
        }
        mjpegPlayer?.release()
        mjpegPlayer = null
        playbackService = null
        player = null
        playerView.player = null
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(hideControlsRunnable)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-launched from notification — update intent extras if needed
        setIntent(intent)
        // If new playlist data arrived, update the service
        val newPlaylist = intent.getStringArrayListExtra("playlist")
        if (newPlaylist != null && newPlaylist.isNotEmpty()) {
            val newIndex = intent.getStringExtra("initialPath")?.let { path ->
                newPlaylist.indexOf(path).coerceAtLeast(0)
            } ?: 0
            // Service already has the queue — just notify to update UI
            playbackService?.registerCallback(playbackCallback)
        }
    }

    // ── PiP ─────────────────────────────────────────────────────────

    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val svc = playbackService
        if (svc?.isPlaying == true) {
            val item = svc.currentQueueItem
            if (item?.isVideo == true &&
                PlayerPreferencesManager.getBackgroundVideoMode(this) == BackgroundVideoMode.PIP &&
                !DeviceUtils.isTvDevice(this)
            ) {
                // Build PiP params with Previous, Play/Pause, Next
                val pipBuilder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))

                // Previous action
                val prevIntent = Intent(this, UFMPlaybackService::class.java)
                    .setAction(UFMPlaybackService.ACTION_SKIP_PREV)
                val prevPendingIntent = PendingIntent.getService(
                    this, 12, prevIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val prevAction = android.app.RemoteAction(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_skip_previous),
                    "Previous", "Previous",
                    prevPendingIntent
                )

                // Play/Pause action
                val toggleIntent = Intent(this, UFMPlaybackService::class.java)
                    .setAction(UFMPlaybackService.ACTION_TOGGLE)
                val togglePendingIntent = PendingIntent.getService(
                    this, 10, toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val toggleAction = android.app.RemoteAction(
                    android.graphics.drawable.Icon.createWithResource(this,
                        if (svc.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    if (svc.isPlaying) getString(R.string.mini_player_pause_content_desc)
                    else getString(R.string.mini_player_play_content_desc),
                    if (svc.isPlaying) getString(R.string.mini_player_pause_content_desc)
                    else getString(R.string.mini_player_play_content_desc),
                    togglePendingIntent
                )

                // Next action
                val nextIntent = Intent(this, UFMPlaybackService::class.java)
                    .setAction(UFMPlaybackService.ACTION_SKIP_NEXT)
                val nextPendingIntent = PendingIntent.getService(
                    this, 13, nextIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val nextAction = android.app.RemoteAction(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_skip_next),
                    "Next", "Next",
                    nextPendingIntent
                )

                pipBuilder.setActions(listOf(prevAction, toggleAction, nextAction))
                enterPictureInPictureMode(pipBuilder.build())
            } else {
                // Audio-only or PiP disabled — just finish, service continues
                finish()
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPiP = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            // PiP entered — keep player attached to surface (PiP needs the surface to render video)
            // Just hide the UI controls that are irrelevant in PiP mode
            controlsLayout.visibility = GONE
            topBar.visibility = GONE
        } else {
            // Returned from PiP — player is already attached, just restore UI
            controlsLayout.visibility = VISIBLE
            topBar.visibility = VISIBLE
        }
    }

    // ── Init Views ──────────────────────────────────────────────────

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
        btnSkipBack = findViewById(R.id.btnSkipBack)
        btnSkipForward = findViewById(R.id.btnSkipForward)
        subtitleView = findViewById(R.id.subtitleView)
        trackSheetLayout = findViewById(R.id.trackSheetLayout)
        trackSheetList = findViewById(R.id.trackSheetList)
        trackSheetTitle = findViewById(R.id.trackSheetTitle)

        subtitleView.visibility = View.GONE

        updateAlpha(btnAudioTrack, false)
        updateAlpha(btnSubtitles, false)

        val mainView = findViewById<View>(R.id.main)
        if (mainView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val isTv = DeviceUtils.isTvDevice(this)

                val pv = findViewById<View>(R.id.playerView)
                if (pv != null) {
                    pv.setPadding(0, 0, 0, if (isTv) 0 else systemBars.bottom)
                }

                val topBar = findViewById<View>(R.id.topBar)
                if (topBar != null) {
                    val topPadding = if (isTv) dp(12) else (systemBars.top + dp(8))
                    val sidePadding = if (isTv) dp(24) else (systemBars.left + dp(16))
                    topBar.setPadding(sidePadding, topPadding, sidePadding, dp(8))
                }

                val cl = findViewById<View>(R.id.controlsLayout)
                if (cl != null) {
                    val bottomPadding = if (isTv) dp(48) else (systemBars.bottom + dp(24))
                    val sidePadding = if (isTv) dp(48) else (systemBars.left + dp(24))
                    cl.setPadding(sidePadding, cl.paddingTop, sidePadding, bottomPadding)
                }
                WindowInsetsCompat.CONSUMED
            }
        }

        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            // Top-left back button: stop playback entirely
            stopPlaybackAndFinish()
        }

        // Shuffle/repeat initial state from service
        updateAlpha(btnShuffle, playbackService?.isShuffle ?: false)
        updateAlpha(btnRepeat, playbackService?.isRepeat ?: false)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = mjpegPlayer?.totalDurationMs ?: (playbackService?.duration ?: 0L)
                    updateTimeLabels(progress, if (dur > 0) dur.toInt() else 0)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isTracking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isTracking = false
                if (mjpegPlayer != null) {
                    mjpegPlayer?.seekTo(sb?.progress?.toLong() ?: 0L, audioPoster)
                } else {
                    playbackService?.seekTo(sb?.progress?.toLong() ?: 0L)
                }
                resetHideTimer()
            }
        })

        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    playbackService?.seekTo(seekBar.progress.toLong())
                    resetHideTimer()
                    true
                } else false
            } else false
        }

        // Button click handlers — all go through service
        listOf<View>(btnPlayPause, btnNext, btnPrev, btnShuffle, btnRepeat, btnAudioTrack, btnSubtitles).forEach {
            it.setOnClickListener { _ -> resetHideTimer() }
        }

        btnPlayPause.setOnClickListener {
            resetHideTimer()
            if (mjpegPlayer != null) {
                mjpegPlayer?.toggle(audioPoster, lifecycleScope)
                updatePlayPauseIcon()
                val playing = mjpegPlayer?.isPlaying == true
                PlayerToastHelper.show(this, getString(if (playing) R.string.player_toast_play else R.string.player_toast_pause))
                return@setOnClickListener
            }
            playbackService?.toggle()
            updatePlayPauseIcon()
            val playing = playbackService?.isPlaying == true
            PlayerToastHelper.show(this, getString(if (playing) R.string.player_toast_play else R.string.player_toast_pause))
        }

        btnNext.setOnClickListener {
            resetHideTimer()
            playbackService?.skipToNext()
            PlayerToastHelper.show(this, getString(R.string.player_toast_next))
        }

        btnPrev.setOnClickListener {
            resetHideTimer()
            playbackService?.skipToPrev()
            PlayerToastHelper.show(this, getString(R.string.player_toast_previous))
        }

        btnSkipBack.setOnClickListener {
            resetHideTimer()
            if (mjpegPlayer != null) {
                val skipMs = PlayerPreferencesManager.getSkipLengthMs(this)
                val target = (mjpegPlayer?.currentPositionMs ?: 0L) - skipMs
                mjpegPlayer?.seekTo(target.coerceAtLeast(0L), audioPoster)
                PlayerToastHelper.show(this, getString(R.string.player_skip_backward_toast, PlayerPreferencesManager.formatSkipLabel(this)))
                return@setOnClickListener
            }
            playbackService?.skipBackward()
            PlayerToastHelper.show(this, getString(R.string.player_skip_backward_toast, PlayerPreferencesManager.formatSkipLabel(this)))
        }

        btnSkipForward.setOnClickListener {
            resetHideTimer()
            if (mjpegPlayer != null) {
                val skipMs = PlayerPreferencesManager.getSkipLengthMs(this)
                val target = (mjpegPlayer?.currentPositionMs ?: 0L) + skipMs
                mjpegPlayer?.seekTo(target.coerceAtMost(mjpegPlayer?.totalDurationMs ?: 0L), audioPoster)
                PlayerToastHelper.show(this, getString(R.string.player_skip_forward_toast, PlayerPreferencesManager.formatSkipLabel(this)))
                return@setOnClickListener
            }
            playbackService?.skipForward()
            PlayerToastHelper.show(this, getString(R.string.player_skip_forward_toast, PlayerPreferencesManager.formatSkipLabel(this)))
        }

        btnShuffle.setOnClickListener {
            resetHideTimer()
            playbackService?.toggleShuffle()
            val on = playbackService?.isShuffle ?: false
            updateAlpha(btnShuffle, on)
            PlayerToastHelper.show(this, getString(if (on) R.string.player_toast_shuffle_on else R.string.player_toast_shuffle_off))
        }

        btnRepeat.setOnClickListener {
            resetHideTimer()
            playbackService?.toggleRepeat()
            val on = playbackService?.isRepeat ?: false
            updateAlpha(btnRepeat, on)
            PlayerToastHelper.show(this, getString(if (on) R.string.player_toast_repeat_on else R.string.player_toast_repeat_off))
        }

        btnAudioTrack.setOnClickListener {
            resetHideTimer()
            toggleTrackSheet(MODE_AUDIO)
            PlayerToastHelper.show(this, getString(R.string.player_toast_audio_track))
        }

        btnSubtitles.setOnClickListener {
            resetHideTimer()
            toggleTrackSheet(MODE_SUBTITLE)
            PlayerToastHelper.show(this, getString(R.string.player_toast_subtitles))
        }

        updateSkipButtonVisibility()

        // ── Queue Drawer Button in Controls Row ────────────────────
        // Find a place for the queue toggle — add to btnSubtitles' parent
        val controlsRow = findViewById<LinearLayout>(R.id.controlsLayout)
            ?.findViewWithTag<LinearLayout>("controlsButtonRow")
        if (controlsRow == null) {
            // Fallback: add programmatically to the last LinearLayout in controlsLayout
            try {
                val parent = (btnSubtitles.parent as? LinearLayout)
                if (parent != null && parent.childCount > 0) {
                    val queueBtn = ImageButton(this).apply {
                        id = android.view.View.generateViewId()
                        layoutParams = LinearLayout.LayoutParams(
                            dp(44), dp(44)
                        ).apply { marginStart = dp(2) }
                        setImageResource(R.drawable.ic_list_view_custom)
                        background = resources.getDrawable(
                            android.R.attr.selectableItemBackgroundBorderless,
                            theme
                        )
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.WHITE
                        )
                        setOnClickListener { toggleQueueDrawer() }
                        contentDescription = getString(R.string.queue_title)
                    }
                    parent.addView(queueBtn, parent.childCount)
                }
            } catch (_: Exception) {
                // Silently skip if layout is unexpected
            }
        }

        // ── Queue Drawer Init ───────────────────────────────────────
        initQueueDrawer()

        // ── Next-Track Overlay ──────────────────────────────────────
        nextTrackOverlay = NextTrackOverlayView(this).apply {
            id = R.id.nextTrackOverlay
            // Attach to the root FrameLayout (main)
            val root = findViewById<android.widget.FrameLayout>(R.id.main)
            if (root != null) {
                val lp = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
                lp.gravity = android.view.Gravity.BOTTOM
                root.addView(this, lp)
            }
        }
    }

    // ── UI Helpers ──────────────────────────────────────────────────

    private fun resetHideTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        controlsLayout.visibility = View.VISIBLE
        controlsLayout.alpha = 1f
        topBar.visibility = View.VISIBLE
        topBar.alpha = 1f
        subtitleView.setPadding(0, 0, 0, dp(100))

        if (playbackService?.isPlaying == true && !isShowingSheet) {
            handler.postDelayed(hideControlsRunnable, ControlsTimeoutManager.loadDurationMs(this))
        }
    }

    private fun updateAlpha(view: View, isActive: Boolean) {
        if (DeviceUtils.isTvDevice(this)) {
            view.alpha = if (isActive) 1.0f else 0.5f
        } else {
            view.alpha = if (isActive) 1.0f else 0.4f
        }
    }

    private fun updateSkipButtonVisibility() {
        if (!::btnSkipBack.isInitialized) return
        val enabled = PlayerPreferencesManager.isSkipEnabled(this)
        btnSkipBack.visibility = if (enabled) View.VISIBLE else View.GONE
        btnSkipForward.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun showSpeedSheet() {
        if (DeviceUtils.isTvDevice(this)) return
        if (playbackService?.isPlaying != true) return
        val current = playbackService?.getPlaybackSpeed() ?: 1.0f
        PlaybackSpeedBottomSheet.newInstance(current) { speed ->
            playbackService?.setPlaybackSpeed(speed)
            PlayerToastHelper.show(this, getString(R.string.player_speed_toast, "${speed}x"))
        }.show(supportFragmentManager, PlaybackSpeedBottomSheet.TAG)
    }

    private fun updatePlayPauseIcon() {
        val isPlaying = mjpegPlayer?.isPlaying ?: (playbackService?.isPlaying ?: false)
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        if (btnPlayPause is FloatingActionButton) {
            (btnPlayPause as FloatingActionButton).setImageResource(icon)
        } else if (btnPlayPause is ImageButton) {
            (btnPlayPause as ImageButton).setImageResource(icon)
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

    private fun stopPlaybackAndFinish() {
        mjpegPlayer?.release()
        mjpegPlayer = null
        if (bound) {
            playbackService?.unregisterCallback()
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            bound = false
        }
        stopService(Intent(this, UFMPlaybackService::class.java))
        finish()
    }

    private fun buildPlaybackNetworkShare(
        shareId: String?,
        shareHost: String?,
        shareUsername: String?,
        shareName: String?,
        provider: String,
        remotePathFromIntent: String? = null,
        isServerMode: Boolean = false
    ): NetworkShare? {
        if (!shareId.isNullOrEmpty()) {
            val repo = NetworkShareRepository.getInstance(this)
            val repoShare = repo.getAll().firstOrNull { it.id == shareId }
            if (repoShare != null) {
                return if (repoShare.isServerMode && !remotePathFromIntent.isNullOrEmpty()) {
                    repoShare.copy(remotePath = remotePathFromIntent)
                } else repoShare
            }

            val onlineRepo = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(this)
            val online = onlineRepo.getById(shareId)
            if (online != null) {
                return NetworkShare(
                    id = online.id,
                    name = online.displayName,
                    type = when (online.provider) {
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.ONEDRIVE -> ShareType.ONEDRIVE
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.DROPBOX -> ShareType.DROPBOX
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.AWS_S3 -> ShareType.AWS_S3
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.IDRIVE_E2 -> ShareType.IDRIVE_E2
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.WEBDAV -> ShareType.WEBDAV
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> ShareType.WEBDAV
                    },
                    host = when (online.provider) {
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE ->
                            za.kilowatch.ultimatefilemanager.network.RCloneShareClient.RCLONE_HOST_MARKER
                        else -> if (online.isWebDavProvider) online.webDavUrl ?: online.email
                                else online.s3Endpoint ?: online.email
                    },
                    username = when (online.provider) {
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> online.id
                        else -> if (online.isWebDavProvider) online.webDavUsername ?: ""
                                else online.s3AccessKey ?: ""
                    },
                    password = if (online.isWebDavProvider) online.webDavPassword ?: ""
                              else online.s3SecretKey ?: "",
                    readOnly = false
                )
            }
        }

        val type = try { ShareType.valueOf(provider) } catch (_: Exception) { return null }
        return NetworkShare(
            id = shareId ?: "",
            name = shareName ?: "",
            host = shareHost ?: "",
            username = shareUsername ?: "",
            type = type,
            remotePath = remotePathFromIntent ?: "",
            port = 0,
            isServerMode = isServerMode
        )
    }

    private fun resolveEffectiveShareAndPath(
        share: NetworkShare,
        remotePathOverride: String?,
        path: String
    ): Pair<NetworkShare, String> {
        if (share.type == ShareType.SMB && share.isServerMode) {
            val basePath = when {
                !remotePathOverride.isNullOrEmpty() -> remotePathOverride
                share.remotePath.isNotEmpty() -> share.remotePath
                else -> {
                    val trimmed = path.trimStart('/')
                    if (trimmed.contains('/')) "/" + trimmed.substringBefore('/') else "/$trimmed"
                }
            }
            val effectiveShare = share.copy(remotePath = basePath)
            val prefix = basePath.trimStart('/')
            val clean = path.trimStart('/')
            val subPath = when {
                clean.startsWith("$prefix/") -> clean.removePrefix("$prefix/")
                clean == prefix              -> ""
                else                         -> clean
            }
            return Pair(effectiveShare, subPath)
        }
        return Pair(share, path)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        resetHideTimer()
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (event?.action == android.view.MotionEvent.ACTION_DOWN) {
            if (isShowingSheet) {
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
        event?.let { speedGestureDetector.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }

    // ── Track Selection Sheet ───────────────────────────────────────

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

        val anySubSelected = currentSubtitleTracks.any { it.isSelected }
        val hasMultipleAudio = audioTracks.size > 1
        val hasSubtitles = currentSubtitleTracks.isNotEmpty()
        updateAlpha(btnAudioTrack, hasMultipleAudio)
        updateAlpha(btnSubtitles, hasSubtitles)

        if (anySubSelected) {
            if (subtitleView.visibility != View.VISIBLE) {
                subtitleView.visibility = View.VISIBLE
            }
        } else {
            subtitleView.visibility = View.GONE
        }
    }

    private fun scanExternalSubtitles(videoPath: String): List<SubtitleTrackInfo> {
        val isLocal = shareId.isEmpty() && shareHost.isEmpty()
        if (!isLocal) return emptyList()  // Network path: use scanNetworkSubtitles() instead

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

        return matched.sortedWith(NaturalSort.byName { it.name }).mapIndexed { index, file ->
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
     * Scan the network directory containing [videoPath] for companion subtitle files.
     *
     * This function performs a blocking directory listing, so it must be called from
     * a background thread / IO dispatcher.
     *
     * For each matched subtitle, it registers the file with [NetworkHttpProxyServer] so
     * ExoPlayer can load it as a local HTTP stream.
     */
    private suspend fun scanNetworkSubtitles(videoPath: String): List<SubtitleTrackInfo> {
        val share = resolveCurrentShare() ?: return emptyList()
        val videoBase = videoPath.substringAfterLast('/').substringBeforeLast('.')
        val parentDir  = videoPath.substringBeforeLast('/')

        val siblings: List<NetworkFile> = try {
            when (share.type) {
                ShareType.SMB     -> SmbShareClient.listFiles(share, parentDir)
                ShareType.SFTP,
                ShareType.SCP     -> SshShareClient.listFiles(share, parentDir)
                ShareType.NFS     -> NfsShareClient.listFiles(share, parentDir)
                ShareType.FTP     -> FtpShareClient.listFiles(share, parentDir)
                ShareType.WEBDAV  -> WebDavShareClient.listFiles(share, parentDir)
                else              -> emptyList()
            }
        } catch (e: Exception) {
            GoRoLog.e("UFMPlayerActivity", "scanNetworkSubtitles: failed to list $parentDir", e)
            return emptyList()
        }

        val matched = SubtitleIntentHelper.findNetworkSubtitles(
            videoPath.substringAfterLast('/'), siblings
        )

        return matched.mapIndexed { index, netFile ->
            val ext       = netFile.name.substringAfterLast('.', "").lowercase()
            val mime      = getMimeForSubtitleExtension(ext)
            val typeLabel = ext.uppercase()
            val langCode  = inferSubtitleLanguage(netFile.name.substringBeforeLast('.'), videoBase)
            val langDisplay = languageCodeToDisplay(langCode)

            // Register with HTTP proxy so ExoPlayer can stream it over HTTP locally
            val proxyUrl = NetworkHttpProxyServer.register(share, netFile.path, mime, netFile.size)

            SubtitleTrackInfo(
                trackGroup   = null,
                trackIndex   = index,
                language     = langDisplay,
                languageCode = langCode,
                label        = "${langDisplay} ($typeLabel)",
                sourceType   = typeLabel,
                isExternal   = true,
                isSelected   = false,
                subtitleUri  = Uri.parse(proxyUrl),
                subtitleMime = mime
            )
        }
    }

    /**
     * Look up the [NetworkShare] for the currently playing track from the share repository.
     * Returns null if the track is local or the share cannot be found.
     */
    private fun resolveCurrentShare(): za.kilowatch.ultimatefilemanager.network.NetworkShare? {
        if (shareId.isEmpty()) return null
        return try {
            NetworkShareRepository.getInstance(this).getAll()
                .firstOrNull { it.id == shareId }
        } catch (e: Exception) {
            GoRoLog.e("UFMPlayerActivity", "resolveCurrentShare failed", e)
            null
        }
    }


    private fun applyAudioTrack(trackInfo: AudioTrackInfo) {
        player?.let { p ->
            val override = TrackSelectionOverride(trackInfo.trackGroup, listOf(trackInfo.trackIndex))
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setOverrideForType(override)
                .build()
            p.currentTracks?.let { detectAndUpdateTracks(it) }
        }
    }

    private fun applySubtitleTrack(trackInfo: SubtitleTrackInfo?) {
        player?.let { p ->
            val builder = p.trackSelectionParameters.buildUpon()
            if (trackInfo == null) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
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

    private fun toggleTrackSheet(mode: Int) {
        if (isShowingSheet) {
            if (currentSheetMode == mode) {
                dismissTrackSheet()
                return
            } else {
                dismissTrackSheet()
            }
        }
        showTrackSheet(mode)
    }

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
            val msg = if (isAudio) getString(R.string.no_alternate_audio)
            else getString(R.string.no_subtitles_available)
            trackSheetList.addView(createTrackItemView(
                label = msg, type = null, isSelected = false, isTv = isTv,
                onClick = { dismissTrackSheet() }, enabled = false
            ))
        } else if (isAudio) {
            for (track in currentAudioTracks) {
                trackSheetList.addView(createTrackItemView(
                    label = track.label, type = null,
                    isSelected = track.isSelected, isTv = isTv,
                    onClick = { applyAudioTrack(track); dismissTrackSheet() }
                ))
            }
        } else {
            for (track in currentSubtitleTracks) {
                trackSheetList.addView(createTrackItemView(
                    label = track.label, type = track.sourceType,
                    isSelected = track.isSelected, isTv = isTv,
                    onClick = { applySubtitleTrack(track); dismissTrackSheet() }
                ))
            }
        }

        if (isTv) {
            val childCount = trackSheetList.childCount
            for (i in 0 until childCount) {
                val child = trackSheetList.getChildAt(i)
                if (i > 0) child.nextFocusUpId = trackSheetList.getChildAt(i - 1).id
                if (i < childCount - 1) child.nextFocusDownId = trackSheetList.getChildAt(i + 1).id
            }
        }

        trackSheetLayout.alpha = 0f
        trackSheetLayout.visibility = View.VISIBLE
        trackSheetLayout.animate().alpha(1f).setDuration(200).withEndAction {
            if (isTv) trackSheetList.getChildAt(0)?.requestFocus()
        }.start()
    }

    private fun dismissTrackSheet() {
        if (!isShowingSheet) return
        trackSheetLayout.animate().alpha(0f).setDuration(200).withEndAction {
            trackSheetLayout.visibility = View.GONE
            isShowingSheet = false
            currentSheetMode = -1
            if (playbackService?.isPlaying == true) resetHideTimer()
        }.start()
    }

    private fun createTrackItemView(
        label: String, type: String?, isSelected: Boolean, isTv: Boolean,
        onClick: () -> Unit, enabled: Boolean = true
    ): View {
        val dp = resources.displayMetrics.density
        val itemHeight = if (isTv) (64 * dp).toInt() else (48 * dp).toInt()
        val paddingH = if (isTv) (24 * dp).toInt() else (16 * dp).toInt()
        val labelTextSize = if (isTv) 18f else 14f

        val row = LinearLayout(this).apply {
            id = android.view.View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, itemHeight
            )
            setPadding(paddingH, 0, paddingH, 0)
            isClickable = true
            isFocusable = isTv
            isEnabled = enabled
            alpha = if (enabled) 1.0f else 0.5f
            if (isTv) background = resources.getDrawable(R.drawable.selector_tv_list_item, theme)
        }

        val checkSize = if (isTv) (28 * dp).toInt() else (20 * dp).toInt()
        val checkmark = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(checkSize, checkSize)
            setImageResource(R.drawable.ic_check)
            visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            if (isSelected) setColorFilter(
                if (isTv) android.graphics.Color.parseColor("#FBBF24")
                else androidx.core.content.ContextCompat.getColor(this@UFMPlayerActivity, R.color.ufm_primary)
            )
        }

        val labelView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (12 * dp).toInt() }
            text = label
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, labelTextSize)
            setTextColor(
                if (isTv) resources.getColor(R.color.tv_text_primary, theme)
                else android.graphics.Color.WHITE
            )
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }

        row.addView(checkmark)
        row.addView(labelView)

        if (type != null) {
            val badge = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (8 * dp).toInt() }
                text = type
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (isTv) 14f else 11f)
                setTextColor(resources.getColor(R.color.ufm_primary, theme))
                setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
                setBackgroundResource(R.drawable.bg_chip)
            }
            row.addView(badge)
        }

        row.setOnClickListener { if (enabled) onClick() }
        return row
    }

    // ── Track Data Helpers (called from player listener on service's player) ──

    fun onServiceTracksChanged(tracks: Tracks) {
        runOnUiThread { detectAndUpdateTracks(tracks) }
    }

    // ── Queue Drawer ───────────────────────────────────────────────

    private fun initQueueDrawer() {
        // Create drawer view as a slide-up panel above controls
        val root = findViewById<android.widget.FrameLayout>(R.id.main)
        if (root == null) return

        val overlay = View(this).apply {
            id = android.view.View.generateViewId()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x99000000.toInt())
            visibility = GONE
            setOnClickListener { toggleQueueDrawer() }
        }

        val recycler = RecyclerView(this).apply {
            id = android.view.View.generateViewId()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                dp(400)
            ).apply { gravity = android.view.Gravity.BOTTOM }
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(android.graphics.Color.parseColor("#1E1E2E"))
            visibility = GONE
        }

        val header = TextView(this).apply {
            id = android.view.View.generateViewId()
            text = getString(R.string.queue_title)
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(8))
            setBackgroundColor(android.graphics.Color.parseColor("#1E1E2E"))
            visibility = GONE
        }

        val emptyView = TextView(this).apply {
            id = android.view.View.generateViewId()
            text = getString(R.string.queue_empty)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF666666.toInt())
            visibility = GONE
        }

        // Add to root in order: overlay (bottom), then recycler+header (on top)
        root.addView(overlay)
        root.addView(recycler)
        root.addView(header)
        root.addView(emptyView)

        queueDrawerLayout = overlay
        queueRecyclerView = recycler
        queueDrawerLayout?.tag = "queueOverlay"
        recycler.tag = "queueRecycler"
        header.tag = "queueHeader"
        emptyView.tag = "queueEmpty"

        // Create adapter with empty initial data
        queueAdapter = QueueAdapter(
            items = mutableListOf(),
            currentIndex = 0,
            onItemClick = { position ->
                playbackService?.queueManager?.setCurrentIndex(position)
                playbackService?.skipToNext() // Actually just loads the track
                playbackService?.let { svc ->
                    // Reset and play from new index
                    val qm = svc.queueManager
                    qm.setCurrentIndex(position)
                    // We use a flag approach: call playCurrent through the service
                    svc.skipToNext() // Re-uses next-index logic
                }
                toggleQueueDrawer()
            },
            onItemMove = { from, to ->
                playbackService?.queueManager?.moveItem(from, to)
                queueAdapter?.updateData(
                    (playbackService?.queueManager?.queue ?: emptyList()).toMutableList(),
                    playbackService?.queueManager?.currentIndex ?: 0
                )
            },
            onItemDismiss = { position ->
                playbackService?.queueManager?.removeAt(position)
                queueAdapter?.updateData(
                    (playbackService?.queueManager?.queue ?: emptyList()).toMutableList(),
                    playbackService?.queueManager?.currentIndex ?: 0
                )
            }
        )

        recycler.adapter = queueAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return queueAdapter?.onItemMove(viewHolder.adapterPosition, target.adapterPosition) ?: false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                queueAdapter?.onItemDismiss(viewHolder.adapterPosition)
            }
        })
        touchHelper.attachToRecyclerView(recycler)
        queueAdapter?.setItemTouchHelper(touchHelper)
    }

    private fun toggleQueueDrawer() {
        val svc = playbackService
        if (svc == null) return
        val recycler = queueRecyclerView ?: return
        val overlay = queueDrawerLayout ?: return
        val header = recycler.rootView.findViewWithTag<TextView>("queueHeader") ?: return
        val emptyView = recycler.rootView.findViewWithTag<TextView>("queueEmpty") ?: return

        isQueueDrawerOpen = !isQueueDrawerOpen

        if (isQueueDrawerOpen) {
            // Update data
            val queue = svc.queueManager.queue.toMutableList()
            val currentIdx = svc.queueManager.currentIndex
            queueAdapter?.updateData(queue, currentIdx)

            val hasItems = queue.isNotEmpty()
            recycler.visibility = if (hasItems) View.VISIBLE else View.GONE
            header.visibility = View.VISIBLE
            emptyView.visibility = if (hasItems) View.GONE else View.VISIBLE
            overlay.visibility = View.VISIBLE

            // Animate slide-up
            recycler.translationY = dp(400).toFloat()
            recycler.animate().translationY(0f).setDuration(250).start()
        } else {
            recycler.animate().translationY(dp(400).toFloat()).setDuration(200).withEndAction {
                recycler.visibility = View.GONE
                header.visibility = View.GONE
                emptyView.visibility = View.GONE
            }.start()
            overlay.animate().alpha(0f).setDuration(200).withEndAction {
                overlay.visibility = View.GONE
                overlay.alpha = 1f
            }.start()
        }
    }

    companion object {
        const val MODE_AUDIO = 0
        const val MODE_SUBTITLE = 1
        val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Data Classes & Helpers (unchanged from original)
// ═══════════════════════════════════════════════════════════════════════

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
    val languageCode: String,
    val label: String,
    val sourceType: String,
    val isExternal: Boolean,
    val isSelected: Boolean,
    val subtitleUri: Uri? = null,
    val subtitleMime: String? = null
)

fun languageCodeToDisplay(code: String): String {
    return when (code) {
        "afr" -> "Afrikaans"
        "ara" -> "Arabic"
        "ben" -> "Bengali"
        "bul" -> "Bulgarian"
        "cat" -> "Catalan"
        "ces" -> "Czech"
        "chi" -> "Chinese"
        "cmn" -> "Chinese (Mandarin)"
        "dan" -> "Danish"
        "deu", "ger" -> "German"
        "ell" -> "Greek"
        "eng" -> "English"
        "epo" -> "Esperanto"
        "est" -> "Estonian"
        "fin" -> "Finnish"
        "fra", "fre" -> "French"
        "gle" -> "Irish"
        "glg" -> "Galician"
        "heb" -> "Hebrew"
        "hin" -> "Hindi"
        "hrv" -> "Croatian"
        "hun" -> "Hungarian"
        "hye" -> "Armenian"
        "ind" -> "Indonesian"
        "isl" -> "Icelandic"
        "ita" -> "Italian"
        "jpn" -> "Japanese"
        "kat" -> "Georgian"
        "kor" -> "Korean"
        "lav" -> "Latvian"
        "lit" -> "Lithuanian"
        "mar" -> "Marathi"
        "mkd" -> "Macedonian"
        "mlt" -> "Maltese"
        "msa" -> "Malay"
        "nld", "dut" -> "Dutch"
        "nno" -> "Norwegian (Nynorsk)"
        "nob" -> "Norwegian (Bokmål)"
        "pol" -> "Polish"
        "por" -> "Portuguese"
        "ron" -> "Romanian"
        "rus" -> "Russian"
        "slk" -> "Slovak"
        "slv" -> "Slovenian"
        "spa" -> "Spanish"
        "srp" -> "Serbian"
        "swe" -> "Swedish"
        "tam" -> "Tamil"
        "tel" -> "Telugu"
        "tha" -> "Thai"
        "tur" -> "Turkish"
        "ukr" -> "Ukrainian"
        "urd" -> "Urdu"
        "vie" -> "Vietnamese"
        "yue" -> "Chinese (Cantonese)"
        "und" -> "Unknown"
        else -> code
    }
}

private fun inferSubtitleLanguage(nameWithoutExt: String, videoBaseName: String): String {
    // Extract a potential ISO 639 language code from the subtitle file name
    // after the video base name. e.g., "movie.ru.srt" -> "rus"
    val suffix = nameWithoutExt.removePrefix(videoBaseName).trimStart('.')
    val langCode = suffix.lowercase().take(3)
    if (langCode.length == 3) return langCode
    val shortCode = suffix.lowercase().take(2)
    // Map 2-letter ISO to 3-letter
    return when (shortCode) {
        "af" -> "afr"; "ar" -> "ara"; "bn" -> "ben"; "bg" -> "bul"
        "ca" -> "cat"; "cs" -> "ces"; "da" -> "dan"; "de" -> "deu"
        "el" -> "ell"; "en" -> "eng"; "eo" -> "epo"; "et" -> "est"
        "fi" -> "fin"; "fr" -> "fra"; "ga" -> "gle"; "gl" -> "glg"
        "he" -> "heb"; "hi" -> "hin"; "hr" -> "hrv"; "hu" -> "hun"
        "hy" -> "hye"; "id" -> "ind"; "is" -> "isl"; "it" -> "ita"
        "ja" -> "jpn"; "ka" -> "kat"; "ko" -> "kor"; "lv" -> "lav"
        "lt" -> "lit"; "mk" -> "mkd"; "ml" -> "mlt"; "mr" -> "mar"
        "ms" -> "msa"; "nl" -> "nld"; "nb" -> "nob"; "nn" -> "nno"
        "pl" -> "pol"; "pt" -> "por"; "ro" -> "ron"; "ru" -> "rus"
        "sk" -> "slk"; "sl" -> "slv"; "sq" -> "sqi"; "sr" -> "srp"
        "sv" -> "swe"; "ta" -> "tam"; "te" -> "tel"; "th" -> "tha"
        "tr" -> "tur"; "uk" -> "ukr"; "ur" -> "urd"; "vi" -> "vie"
        "zh" -> "chi"
        else -> "und"
    }
}

private fun getMimeForSubtitleExtension(ext: String): String {
    return when (ext) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ass"
        "sub" -> "text/x-microdvd"
        else -> "application/x-subrip"
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Custom Media3 DataSource for network file playback (unchanged)
// ═══════════════════════════════════════════════════════════════════════

class UfmMedia3DataSource(
    private val share: NetworkShare,
    private val path: String,
    private val knownSize: Long = -1L
) : DataSource {

    private var randomAccess: IRandomAccessFile? = null
    private var fileLength: Long = 0
    private var bytesRemaining: Long = 0
    private var streamPosition: Long = 0
    private var opened = false
    private var currentUri: Uri? = null

    private val CACHE_SIZE = 2 * 1024 * 1024
    private val cacheBuffer = ByteArray(CACHE_SIZE)
    private var cacheStartPos: Long = -1L
    private var cacheEndPos: Long = -1L

    override fun addTransferListener(transferListener: TransferListener) {}

    /**
     * Open a fresh random-access handle for the given share/path.
     * Used by [open] and by the read-failure retry in [read].
     */
    private fun openHandle(share: NetworkShare, path: String, fallbackSize: Long): IRandomAccessFile? {
        return try {
            val ra = when (share.type) {
                ShareType.SMB -> SmbShareClient.openRandomAccessFile(share, path)
                ShareType.FTP -> FtpShareClient.openRandomAccessFile(share, path)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.openRandomAccessFile(share, path)
                ShareType.NFS -> NfsShareClient.openRandomAccessFile(share, path)
                ShareType.DLNA -> DlnaShareClient.openRandomAccessFile(share, path)
                ShareType.WEBDAV -> WebDavShareClient.openRandomAccessFile(share, path, knownSize)
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openRandomAccessFile(share, path)
                ShareType.ONEDRIVE -> OnedriveShareClient.openRandomAccessFile(share, path)
                ShareType.DROPBOX -> DropboxShareClient.openRandomAccessFile(share, path)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openRandomAccessFile(share, path)
                else -> throw IllegalStateException("Unsupported share type: ${share.type}")
            }
            // If the fresh handle reports a size, trust it; otherwise keep the known size.
            if (ra.size > 0) fileLength = ra.size
            ra
        } catch (e: Exception) {
            GoRoLog.e("UFMPlayer", "openHandle failed for $path", e)
            null
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        opened = true
        try {
            randomAccess = openHandle(share, path, knownSize)
            if (randomAccess == null) return -1L
            fileLength = randomAccess?.size ?: -1L
            streamPosition = dataSpec.position
            val remaining = if (dataSpec.length != -1L) {
                dataSpec.length
            } else {
                if (fileLength != -1L) fileLength - streamPosition else -1L
            }
            bytesRemaining = if (remaining == -1L) 0L else remaining
            cacheStartPos = -1L
            cacheEndPos = -1L
            return remaining
        } catch (e: Exception) {
            GoRoLog.e("UFMPlayer", "UfmMedia3DataSource open failed", e)
            return -1L
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val ra = randomAccess ?: return C.RESULT_END_OF_INPUT
        try {
            // Check if the requested range is in the cache
            val requestEnd = streamPosition + length
            if (streamPosition in cacheStartPos until cacheEndPos) {
                val cacheOffset = (streamPosition - cacheStartPos).toInt()
                val cacheAvailable = (cacheEndPos - streamPosition).toInt()
                val toCopy = minOf(length, cacheAvailable, buffer.size - offset)
                if (toCopy > 0) {
                    System.arraycopy(cacheBuffer, cacheOffset, buffer, offset, toCopy)
                    streamPosition += toCopy
                    bytesRemaining -= toCopy
                    return toCopy
                }
            }

            val fetchSize = minOf(CACHE_SIZE, buffer.size)
            val remainingInFile = if (fileLength > 0) (fileLength - streamPosition).coerceAtLeast(0L) else Long.MAX_VALUE
            val actualFetchSize = minOf(fetchSize.toLong(), remainingInFile).coerceAtMost(CACHE_SIZE.toLong()).toInt()
            if (actualFetchSize <= 0) return C.RESULT_END_OF_INPUT

            val read = ra.read(streamPosition, cacheBuffer, actualFetchSize)
            if (read <= 0) return C.RESULT_END_OF_INPUT

            cacheStartPos = streamPosition
            cacheEndPos = streamPosition + read

            val toCopy = minOf(length, read, buffer.size - offset)
            if (toCopy > 0) {
                System.arraycopy(cacheBuffer, 0, buffer, offset, toCopy)
                streamPosition += toCopy
                bytesRemaining -= toCopy
            }
            return toCopy
        } catch (e: Exception) {
            // A read failure (e.g. SMB2 credit exhaustion or a NAS connection reset) must NOT
            // be reported as EOF — that makes ExoPlayer think the file ended and throw
            // "Source error / UnrecognizedInputFormatException". Reopen the handle on a fresh
            // connection (fresh credits) and retry once, exactly like the internal player's
            // resilience model. Only report EOF if the reopen also fails.
            GoRoLog.w("UFMPlayer", "UfmMedia3DataSource read failed at $streamPosition — reopening handle", e)
            try { randomAccess?.close() } catch (_: Exception) {}
            randomAccess = null
            return try {
                randomAccess = openHandle(share, path, fileLength)
                if (randomAccess == null) {
                    GoRoLog.e("UFMPlayer", "UfmMedia3DataSource reopen failed", null)
                    return C.RESULT_END_OF_INPUT
                }
                // Repopulate the cache exactly like the success path so subsequent reads work.
                val newRa = randomAccess!!
                val retryRemaining = if (fileLength > 0) (fileLength - streamPosition).coerceAtLeast(0L) else Long.MAX_VALUE
                val retryFetchSize = minOf(minOf(CACHE_SIZE, buffer.size).toLong(), retryRemaining).coerceAtMost(CACHE_SIZE.toLong()).toInt()
                val n = newRa.read(streamPosition, cacheBuffer, retryFetchSize)
                if (n <= 0) {
                    C.RESULT_END_OF_INPUT
                } else {
                    cacheStartPos = streamPosition
                    cacheEndPos = streamPosition + n
                    val toCopy = minOf(length, n, buffer.size - offset)
                    if (toCopy > 0) {
                        System.arraycopy(cacheBuffer, 0, buffer, offset, toCopy)
                        streamPosition += toCopy
                        bytesRemaining -= toCopy
                    }
                    toCopy
                }
            } catch (e2: Exception) {
                GoRoLog.e("UFMPlayer", "UfmMedia3DataSource reopen+read failed", e2)
                C.RESULT_END_OF_INPUT
            }
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        opened = false
        try { randomAccess?.close() } catch (_: Exception) {}
        randomAccess = null
        streamPosition = 0
        cacheStartPos = -1L
        cacheEndPos = -1L
    }
}

class CommonMediaDataSource(
    private val randomAccess: IRandomAccessFile
) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val safeBuffer = ByteArray(size)
        val read = randomAccess.read(position, safeBuffer, size)
        if (read > 0) {
            System.arraycopy(safeBuffer, 0, buffer, offset, read)
        }
        return read
    }

    override fun getSize(): Long = -1L

    override fun close() = randomAccess.close()
}

// IRandomAccessFile.size() is available via the fileLength property
