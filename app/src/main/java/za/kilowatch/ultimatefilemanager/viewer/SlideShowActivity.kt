package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.recycle.RecycleBinManager
import za.kilowatch.ultimatefilemanager.storage.FileTagsManager
import za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil3.ImageLoader
import coil3.asDrawable
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.svg.SvgDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.DlnaShareClient
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient

import za.kilowatch.ultimatefilemanager.network.NetworkHttpProxyServer
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.TvShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import za.kilowatch.ultimatefilemanager.settings.ControlsTimeoutManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.io.FileOutputStream

class SlideShowActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var toolbar: View
    private lateinit var controlsLayout: View // Video controls (SeekBar, buttons)
    private var tvImageControlBar: View? = null // TV pan/zoom buttons for images
    private lateinit var txtTitle: TextView
    private lateinit var txtInfo: TextView

    private var playlist: ArrayList<String> = ArrayList()
    private var initialPath: String = ""
    private var shareId: String = ""
    private var shareHost: String = ""
    private var shareName: String = ""
    private var provider: String = ""
    private var remotePathExtra: String = ""
    private var initialFileSize: Long = 0L
    private var sizesMap: HashMap<String, Long> = HashMap()

    private var isTv = false
    private var controlsVisible = true

    // Single ExoPlayer instance shared across pages
    private var player: ExoPlayer? = null
    private var activePlayerView: PlayerView? = null

    // Video state tracking
    private lateinit var btnPlayPause: View
    private lateinit var btnNext: View
    private lateinit var btnPrev: View
    private lateinit var btnShuffle: View
    private lateinit var btnRepeat: View
    private lateinit var seekBar: SeekBar
    private lateinit var txtElapsed: TextView
    private lateinit var txtRemaining: TextView

    private var isShuffle = false
    private var isRepeat = false
    private var isTracking = false

    private var isSlideshowPlaying = false
    private var slideshowStep = 4 // step 4 = 2.5s (0.5s + 4 * 0.5s)
    private val slideshowIntervalMs: Long
        get() = ((0.5f + slideshowStep * 0.5f) * 1000).toLong()

    private val slideshowRunnable = object : Runnable {
        override fun run() {
            if (isSlideshowPlaying) {
                navigateNext()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val coilLoader by lazy {
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(AnimatedPngDecoder.Factory())
                add(AvifDecoder.Factory())
                add(JxlDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .build()
    }

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            updatePlayPauseIcon()
            if (state == Player.STATE_ENDED) {
                if (isRepeat) {
                    player?.seekTo(0)
                    player?.play()
                } else {
                    navigateNext()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
            if (isPlaying) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                resetHideTimer()
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                handler.removeCallbacks(hideControlsRunnable)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            GoRoLog.e("SlideShowPlayer", "Playback error: ${error.message}", error)
            Toast.makeText(this@SlideShowActivity, "Playback error: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            if (isSlideshowPlaying) {
                handler.postDelayed({ navigateNext() }, 2000)
            }
        }
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
            if (!isFinishing && !isDestroyed) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // Keep screen awake during slideshow
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_slideshow_tv
            else R.layout.activity_slideshow
        )

        // Start with controls hidden on TV so D-pad navigation works immediately
        controlsVisible = !isTv

        // Window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.toolbar)?.setPadding(0, sb.top, 0, 0)
            val viewPager = findViewById<ViewPager2>(R.id.viewPager)
            if (viewPager != null) {
                val topPadding = (8 * resources.displayMetrics.density).toInt()
                val recyclerView = viewPager.getChildAt(0) as? RecyclerView
                if (recyclerView != null) {
                    recyclerView.setPadding(0, topPadding, 0, sb.bottom)
                    recyclerView.clipToPadding = true
                } else {
                    viewPager.setPadding(0, topPadding, 0, sb.bottom)
                }
            }
            val controlsLayout = findViewById<View>(R.id.controlsLayout)
            if (controlsLayout != null) {
                val basePaddingBottom = (24 * resources.displayMetrics.density).toInt()
                controlsLayout.setPadding(
                    controlsLayout.paddingLeft,
                    controlsLayout.paddingTop,
                    controlsLayout.paddingRight,
                    basePaddingBottom + sb.bottom
                )
            }
            val layoutPhotoActions = findViewById<View>(R.id.layoutPhotoActions)
            if (layoutPhotoActions != null) {
                val basePaddingBottom = (12 * resources.displayMetrics.density).toInt()
                layoutPhotoActions.setPadding(
                    layoutPhotoActions.paddingLeft,
                    layoutPhotoActions.paddingTop,
                    layoutPhotoActions.paddingRight,
                    basePaddingBottom + sb.bottom
                )
            }
            WindowInsetsCompat.CONSUMED
        }

        // Parse Intents
        shareId = intent.getStringExtra("shareId") ?: ""
        shareHost = intent.getStringExtra("shareHost") ?: ""
        shareName = intent.getStringExtra("shareName") ?: ""
        provider = intent.getStringExtra("provider") ?: ""
        initialFileSize = intent.getLongExtra("initialSize", 0L)
        remotePathExtra = intent.getStringExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_REMOTE_PATH) ?: ""
        initialPath = intent.getStringExtra("initialPath") ?: ""
        @Suppress("UNCHECKED_CAST")
        sizesMap = (intent.getSerializableExtra("sizesMap") as? HashMap<String, Long>) ?: HashMap()
        
        // Prefer cache to avoid TransactionTooLargeException for large folders
        val cacheKey = intent.getStringExtra("playlistCacheKey") ?: ""
        val cachedPlaylist = PlaylistCache.take(cacheKey)

        playlist = when {
            cachedPlaylist != null -> ArrayList(cachedPlaylist)
            // Legacy path: small playlists still passed directly (e.g. from TwinWindow / network browser)
            intent.hasExtra("playlist") -> intent.getStringArrayListExtra("playlist") ?: ArrayList()
            else -> ArrayList()
        }

        // Fallback: scan parent dir if playlist is still empty (e.g. process was restarted)
        if (playlist.isEmpty() && initialPath.isNotEmpty()) {
            val parentDir = java.io.File(initialPath).parentFile
            val scanned = parentDir?.listFiles { f ->
                f.isFile && !f.name.startsWith(".") &&
                (f.extension.lowercase() in FileViewerRouter.IMAGE_EXTENSIONS ||
                 f.extension.lowercase() in FileViewerRouter.VIDEO_EXTENSIONS)
            }?.sortedWith(NaturalSort.byName { it.name }) ?: emptyList()
            playlist = ArrayList(scanned.map { it.absolutePath })
            if (playlist.isEmpty()) playlist.add(initialPath)
        }

        initViews()
        setupViewPager()
        setupControls()

        // Jump to initial item
        val startIndex = playlist.indexOf(initialPath).coerceAtLeast(0)
        viewPager.setCurrentItem(startIndex, false)
        updatePageState(startIndex)

        // On TV, hide toolbar and system bars initially (controls start hidden so
        // D-pad left/right navigates immediately)
        if (isTv) {
            toolbar.visibility = View.GONE
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }

    }

    private fun initViews() {
        viewPager = findViewById(R.id.viewPager)
        toolbar = findViewById(R.id.toolbar) ?: findViewById(R.id.layoutTvHeader)
        controlsLayout = findViewById(R.id.controlsLayout)
        tvImageControlBar = findViewById(R.id.tvImageControlBar)
        txtTitle = findViewById(R.id.txtTitle)
        txtInfo = findViewById(R.id.txtInfo)

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }

        // Setup image edit actions
        findViewById<View>(R.id.btnRotateLeft)?.setOnClickListener { rotateImage(-90f) }
        findViewById<View>(R.id.btnRotateRight)?.setOnClickListener { rotateImage(90f) }
        findViewById<View>(R.id.btnDrawToggle)?.setOnClickListener { toggleDrawMode() }
        findViewById<View>(R.id.btnCropToggle)?.setOnClickListener { toggleCropMode() }
        findViewById<View>(R.id.btnImageSave)?.setOnClickListener { saveImage() }

        // Delete and Rename actions
        findViewById<View>(R.id.btnRename)?.setOnClickListener { showRenameDialog() }
        findViewById<View>(R.id.btnDelete)?.setOnClickListener { showDeleteConfirmation() }
        findViewById<View>(R.id.btnRenameTv)?.setOnClickListener { showRenameDialog() }
        findViewById<View>(R.id.btnDeleteTv)?.setOnClickListener { showDeleteConfirmation() }

        // More options menu (3 dots)
        findViewById<View>(R.id.btnMore)?.setOnClickListener { showOverflowMenu(it) }

        // PDF dialog
        findViewById<View>(R.id.btnConvertToPdf)?.setOnClickListener { showConvertToPdfDialog() }

        setupSlideshowControls()

        if (isTv) {
            setupTvImageActions()
        }
    }

    private fun setupSlideshowControls() {
        val txtIntervalTv = findViewById<TextView>(R.id.txtSlideshowIntervalTv)
        val btnPlayPauseTv = findViewById<View>(R.id.btnSlideshowPlayPauseTv)
        val imgPlayPauseTv = findViewById<ImageView>(R.id.imgSlideshowPlayPauseTv)
        val btnDecTv = findViewById<View>(R.id.btnSlideshowDecTv)
        val btnIncTv = findViewById<View>(R.id.btnSlideshowIncTv)
        val btnSlideshowPlayPause = findViewById<View>(R.id.btnSlideshowPlayPause)
        val imgSlideshowPlayPause = findViewById<ImageView>(R.id.imgSlideshowPlayPause)

        fun updateIntervalDisplay() {
            val sec = 0.5f + slideshowStep * 0.5f
            val text = String.format(java.util.Locale.US, "%.1fs", sec)
            txtIntervalTv?.text = text
            if (isSlideshowPlaying && isCurrentImage()) {
                handler.removeCallbacks(slideshowRunnable)
                handler.postDelayed(slideshowRunnable, slideshowIntervalMs)
            }
        }

        fun toggleSlideshowPlayPause() {
            isSlideshowPlaying = !isSlideshowPlaying
            val iconRes = if (isSlideshowPlaying) R.drawable.ic_pause else R.drawable.ic_play
            imgSlideshowPlayPause?.setImageResource(iconRes)
            imgPlayPauseTv?.setImageResource(iconRes)
            handler.removeCallbacks(slideshowRunnable)
            if (isSlideshowPlaying) {
                if (isCurrentImage()) {
                    handler.postDelayed(slideshowRunnable, slideshowIntervalMs)
                } else if (isCurrentItemVideo()) {
                    player?.let { p ->
                        if (!p.isPlaying) p.play()
                    }
                }
            }
        }

        updateIntervalDisplay()

        btnSlideshowPlayPause?.setOnClickListener { toggleSlideshowPlayPause() }
        btnPlayPauseTv?.setOnClickListener { toggleSlideshowPlayPause() }

        btnDecTv?.setOnClickListener {
            slideshowStep = (slideshowStep - 1).coerceAtLeast(0)
            updateIntervalDisplay()
        }
        btnIncTv?.setOnClickListener {
            slideshowStep = (slideshowStep + 1).coerceAtMost(9)
            updateIntervalDisplay()
        }
    }

    private fun setupViewPager() {
        val adapter = SlideShowAdapter(this, playlist, shareId, shareHost, shareName, provider, remotePathExtra, coilLoader, viewPager) {
            toggleControls()
        }
        viewPager.adapter = adapter
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageState(position)
            }
        })
    }

    private fun setupControls() {
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnPrev = findViewById(R.id.btnPrev)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        seekBar = findViewById(R.id.seekBar)
        txtElapsed = findViewById(R.id.txtElapsed)
        txtRemaining = findViewById(R.id.txtRemaining)

        updateAlpha(btnShuffle, isShuffle)
        updateAlpha(btnRepeat, isRepeat)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0L
                    updateTimeLabels(progress, if (duration > 0) duration.toInt() else 0)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                isTracking = true
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isTracking = false
                player?.seekTo(sb?.progress?.toLong() ?: 0L)
                resetHideTimer()
            }
        })

        btnPlayPause.setOnClickListener {
            resetHideTimer()
            player?.let { p ->
                if (p.isPlaying) p.pause() else {
                    p.play()
                    handler.post(progressUpdater)
                }
            }
        }
        btnNext.setOnClickListener { resetHideTimer(); navigateNext() }
        btnPrev.setOnClickListener { resetHideTimer(); navigatePrev() }
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

        if (isTv) {
            btnPlayPause.isFocusable = true
            btnNext.isFocusable = true
            btnPrev.isFocusable = true
            btnShuffle.isFocusable = true
            btnRepeat.isFocusable = true
            seekBar.isFocusable = true
        }
    }

    private fun setupTvImageActions() {
        val panStep = 80f
        val zoomIn = 1.2f
        val zoomOut = 1f / zoomIn

        val iconTint = ContextCompat.getColor(this, R.color.tv_icon_tint)
        val blackIcon = android.graphics.Color.parseColor("#FF0F0F0F")

        fun wire(btnId: Int, action: () -> Unit) {
            val container = findViewById<ViewGroup>(btnId) ?: return
            val icon = container.getChildAt(0) as? ImageView
            container.setOnClickListener { action() }
            container.setOnFocusChangeListener { _, hasFocus ->
                icon?.setColorFilter(if (hasFocus) blackIcon else iconTint)
            }
        }

        wire(R.id.btnPanLeft) { getActiveZoomListener()?.pan(panStep, 0f) }
        wire(R.id.btnPanRight) { getActiveZoomListener()?.pan(-panStep, 0f) }
        wire(R.id.btnPanUp) { getActiveZoomListener()?.pan(0f, panStep) }
        wire(R.id.btnPanDown) { getActiveZoomListener()?.pan(0f, -panStep) }
        wire(R.id.btnZoomIn) { getActiveZoomListener()?.zoom(zoomIn) }
        wire(R.id.btnZoomOut) { getActiveZoomListener()?.zoom(zoomOut) }
        wire(R.id.btnRotateLeft) { rotateImage(-90f) }
        wire(R.id.btnRotateRight) { rotateImage(90f) }
        wire(R.id.btnFitReset) { getActiveZoomListener()?.reset() }
        wire(R.id.btnDrawToggleTv) { toggleDrawMode() }
        wire(R.id.btnCropToggleTv) { toggleCropMode() }
        wire(R.id.btnImageSaveTv) { saveImage() }
        wire(R.id.btnRename) { showRenameDialog() }
        wire(R.id.btnDelete) { showDeleteConfirmation() }

        // Header buttons TV focus states
        fun wireHeaderButton(btnId: Int) {
            val btn = findViewById<ImageView>(btnId) ?: return
            btn.setOnFocusChangeListener { _, hasFocus ->
                btn.setColorFilter(if (hasFocus) blackIcon else iconTint)
            }
        }
        wireHeaderButton(R.id.btnBack)
        wireHeaderButton(R.id.btnConvertToPdf)
        wireHeaderButton(R.id.btnRenameTv)
        wireHeaderButton(R.id.btnDeleteTv)
    }

    private fun getActiveViewHolder(): RecyclerView.ViewHolder? {
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return null
        return recyclerView.findViewHolderForAdapterPosition(viewPager.currentItem)
    }

    private fun getActiveZoomListener(): ZoomTouchListener? {
        val holder = getActiveViewHolder() as? SlideShowAdapter.ImageViewHolder
        return holder?.zoomTouchListener
    }

    private fun updatePageState(position: Int) {
        val path = playlist.getOrNull(position) ?: return
        val fileName = path.substringAfterLast("/")
        txtTitle.text = fileName

        val ext = path.substringAfterLast('.', "").lowercase()
        val isVideo = ext in FileViewerRouter.VIDEO_EXTENSIONS
        val isImage = ext in FileViewerRouter.IMAGE_EXTENSIONS
        val isGif = ext == "gif"

        // Stop video playback on swipe
        resetPlayer()
        handler.removeCallbacks(slideshowRunnable)
        if (isSlideshowPlaying && isImage) {
            handler.postDelayed(slideshowRunnable, slideshowIntervalMs)
        }

        val layoutPhotoActions = findViewById<View>(R.id.layoutPhotoActions)
        if (isTv) {
            findViewById<View>(R.id.btnConvertToPdf)?.visibility = if (isImage && !isGif) View.VISIBLE else View.GONE
            findViewById<View>(R.id.btnRenameTv)?.visibility = if (isImage) View.VISIBLE else View.GONE
            findViewById<View>(R.id.btnDeleteTv)?.visibility = View.VISIBLE
            findViewById<View>(R.id.btnDrawToggleTv)?.visibility = if (isImage && !isGif) View.VISIBLE else View.GONE
            findViewById<View>(R.id.btnCropToggleTv)?.visibility = if (isImage && !isGif) View.VISIBLE else View.GONE
            findViewById<View>(R.id.btnImageSaveTv)?.visibility = if (isImage) View.VISIBLE else View.GONE
            tvImageControlBar?.visibility = if (isImage && controlsVisible) View.VISIBLE else View.GONE
            controlsLayout.visibility = if (isVideo && controlsVisible) View.VISIBLE else View.GONE
            layoutPhotoActions?.visibility = View.GONE
        } else {
            controlsLayout.visibility = if (isVideo && controlsVisible) View.VISIBLE else View.GONE
            layoutPhotoActions?.visibility = if (isImage && controlsVisible) View.VISIBLE else View.GONE
            tvImageControlBar?.visibility = View.GONE

            // Hide edit/draw & rotate for GIF files on mobile
            findViewById<View>(R.id.btnDrawToggle)?.visibility = if (!isGif) View.VISIBLE else View.GONE
            findViewById<View>(R.id.btnCropToggle)?.visibility = if (!isGif) View.VISIBLE else View.GONE
            findViewById<View>(R.id.btnRotateLeft)?.visibility = if (!isGif) View.VISIBLE else View.GONE
            findViewById<View>(R.id.btnRotateRight)?.visibility = if (!isGif) View.VISIBLE else View.GONE
            findViewById<View>(R.id.btnImageSave)?.visibility = if (!isGif) View.VISIBLE else View.GONE
        }

        if (isImage) {
            txtInfo.visibility = View.GONE
            // Read local dimensions/sizes if available to update info text.
            // File.exists()/length() are native access()/stat() syscalls that can
            // block for >5s on slow TV storage (e.g. USB/NFS), and updatePageState()
            // runs synchronously inside ViewPager2.setCurrentItem() from onKeyDown —
            // so the filesystem stat is done off the main thread.
            val localFile = File(path)
            val isLocalImage = shareId.isEmpty()
            lifecycleScope.launch(Dispatchers.IO) {
                val size = if (isLocalImage && localFile.exists()) {
                    localFile.length()
                } else if (sizesMap.containsKey(path)) {
                    sizesMap[path]
                } else {
                    null
                }
                withContext(Dispatchers.Main) {
                    // Only apply if this page is still the one being shown (the user
                    // may have navigated on while the stat was in flight).
                    if (size != null && viewPager.currentItem == position) {
                        txtInfo.text = formatFileSize(size)
                        txtInfo.visibility = View.VISIBLE
                    }
                }
            }
        }

        if (isVideo) {
            setupAndPlayVideo(path)
        }
    }

    private fun setupAndPlayVideo(path: String) {
        val holder = getActiveViewHolder() as? SlideShowAdapter.VideoViewHolder ?: return
        activePlayerView = holder.playerView

        val isLocal = shareId.isEmpty() && shareHost.isEmpty()
        val context = this

        if (!isLocal) holder.progressBar.visibility = View.VISIBLE

        Thread {
            try {
                val extractorsFactory = za.kilowatch.ultimatefilemanager.media.UfmExtractorsFactory()
                val mediaSource = if (isLocal) {
                    val localFile = File(path)
                    val dataSourceFactory = DefaultDataSource.Factory(context)
                    DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.fromFile(localFile)))
                } else {
                    var share = NetworkShareRepository.getInstance(context).getById(shareId)
                        ?: NetworkShare(
                            id = shareId,
                            host = shareHost,
                            name = shareName,
                            type = ShareType.valueOf(provider)
                        )
                    // Server-mode SMB: override remotePath from intent extra
                    if (share.isServerMode && remotePathExtra.isNotEmpty()) {
                        share = share.copy(remotePath = remotePathExtra)
                    }

                    if (provider == "GOOGLE_DRIVE" || provider == "ONEDRIVE") {
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
                            .setUserAgent(Util.getUserAgent(context, "UFM"))
                        DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory).createMediaSource(MediaItem.fromUri(Uri.parse(url)))
                    } else if (provider == "NFS") {
                        val ext = path.substringAfterLast('.').lowercase()
                        val mime = za.kilowatch.ultimatefilemanager.util.MimeTypeHelper.getOrFallback(ext)
                        val fileSize = sizesMap[path] ?: 0L
                        val proxyUrl = NetworkHttpProxyServer.register(share, path, mime, fileSize)
                        val okhttpClient = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val dataSourceFactory = OkHttpDataSource.Factory(okhttpClient)
                            .setUserAgent(Util.getUserAgent(context, "UFM"))
                        DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory).createMediaSource(MediaItem.fromUri(Uri.parse(proxyUrl)))
                    } else {
                        val dataSourceFactory = DataSource.Factory { UfmMedia3DataSource(share, path) }
                        DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory).createMediaSource(MediaItem.fromUri(Uri.parse("ufm://${path.replace(" ", "%20")}")))
                    }
                }

                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    val newPlayer = ExoPlayer.Builder(context).build()
                    player = newPlayer
                    holder.playerView.player = newPlayer
                    newPlayer.setMediaSource(mediaSource)
                    newPlayer.prepare()
                    newPlayer.addListener(playerListener)
                    newPlayer.playWhenReady = true
                    newPlayer.play()
                    if (!isLocal) holder.progressBar.visibility = View.GONE
                    handler.post(progressUpdater)
                    resetHideTimer()
                }
            } catch (e: Exception) {
                GoRoLog.e("SlideShow", "ExoPlayer setup failed", e)
                runOnUiThread { holder.progressBar.visibility = View.GONE }
            }
        }.start()
    }

    private fun resetPlayer() {
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(hideControlsRunnable)
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        activePlayerView?.player = null
        activePlayerView = null
        seekBar.progress = 0
        updatePlayPauseIcon()
    }

    private fun navigateNext() {
        if (playlist.isEmpty()) return
        if (isShuffle) {
            val next = (0 until playlist.size).random()
            viewPager.currentItem = next
        } else {
            val current = viewPager.currentItem
            if (current < playlist.size - 1) {
                viewPager.currentItem = current + 1
            } else {
                viewPager.currentItem = 0 // Wrap around
            }
        }
    }

    private fun navigatePrev() {
        if (playlist.isEmpty()) return
        if (isShuffle) {
            val prev = (0 until playlist.size).random()
            viewPager.currentItem = prev
        } else {
            val current = viewPager.currentItem
            if (current > 0) {
                viewPager.currentItem = current - 1
            } else {
                viewPager.currentItem = playlist.size - 1 // Wrap around
            }
        }
    }

    private fun rotateImage(delta: Float) {
        getActiveZoomListener()?.rotateImage(delta)
    }

    private fun updateDrawingOverlayState(drawingOverlay: DrawingOverlayView) {
        val isEditActive = drawingOverlay.isDrawingMode || drawingOverlay.isCropMode
        drawingOverlay.visibility = if (isEditActive) View.VISIBLE else View.GONE
        viewPager.isUserInputEnabled = !isEditActive
    }

    private fun toggleDrawMode() {
        val holder = getActiveViewHolder() as? SlideShowAdapter.ImageViewHolder ?: return
        val drawingOverlay = holder.drawingOverlay

        drawingOverlay.isDrawingMode = !drawingOverlay.isDrawingMode
        if (drawingOverlay.isDrawingMode) {
            drawingOverlay.isCropMode = false
            Toast.makeText(this, R.string.drawing_mode, Toast.LENGTH_SHORT).show()
        }
        updateDrawingOverlayState(drawingOverlay)
    }

    private fun toggleCropMode() {
        val holder = getActiveViewHolder() as? SlideShowAdapter.ImageViewHolder ?: return
        val drawingOverlay = holder.drawingOverlay

        drawingOverlay.isCropMode = !drawingOverlay.isCropMode
        if (drawingOverlay.isCropMode) {
            drawingOverlay.isDrawingMode = false
            Toast.makeText(this, R.string.crop_mode_active, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.crop_mode_off, Toast.LENGTH_SHORT).show()
        }
        updateDrawingOverlayState(drawingOverlay)
    }

    private fun saveImage() {
        val position = viewPager.currentItem
        val path = playlist.getOrNull(position) ?: return
        val file = File(path)
        if (shareId.isNotEmpty()) {
            Toast.makeText(this, "Editing network files is not supported", Toast.LENGTH_SHORT).show()
            return
        }
        val holder = getActiveViewHolder() as? SlideShowAdapter.ImageViewHolder ?: return
        val drawingOverlay = holder.drawingOverlay

        holder.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val drawingBmp = drawingOverlay.getDrawingBitmap()
                val cropRect = drawingOverlay.computeCropRect()
                val srcBmp = coilLoader.execute(
                    ImageRequest.Builder(this@SlideShowActivity)
                        .data(file)
                        .allowHardware(false)
                        .build()
                ).image?.asDrawable(resources)?.let {
                    val bmp = Bitmap.createBitmap(it.intrinsicWidth, it.intrinsicHeight, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    it.setBounds(0, 0, c.width, c.height)
                    it.draw(c)
                    bmp
                } ?: return@launch

                val resultBmp = if (drawingBmp != null) {
                    val composite = srcBmp.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(composite)
                    val scaleX = srcBmp.width.toFloat() / drawingOverlay.width
                    val scaleY = srcBmp.height.toFloat() / drawingOverlay.height
                    canvas.drawBitmap(drawingBmp, Matrix().apply { postScale(scaleX, scaleY) }, null)
                    composite
                } else srcBmp

                val finalBmp = if (cropRect != null && drawingOverlay.isCropMode) {
                    val scaleX = resultBmp.width.toFloat() / drawingOverlay.width
                    val scaleY = resultBmp.height.toFloat() / drawingOverlay.height
                    val srcRect = android.graphics.Rect(
                        (cropRect.left * scaleX).toInt(),
                        (cropRect.top * scaleY).toInt(),
                        (cropRect.right * scaleX).toInt(),
                        (cropRect.bottom * scaleY).toInt()
                    )
                    Bitmap.createBitmap(resultBmp,
                        maxOf(0, srcRect.left), maxOf(0, srcRect.top),
                        minOf(srcRect.width(), resultBmp.width - maxOf(0, srcRect.left)),
                        minOf(srcRect.height(), resultBmp.height - maxOf(0, srcRect.top)))
                } else resultBmp

                val ext = file.extension.ifEmpty { "png" }
                val saveFile = File(file.parentFile, "${file.nameWithoutExtension}_edited.$ext")
                FileOutputStream(saveFile).use { out ->
                    when (ext.lowercase()) {
                        "jpg", "jpeg" -> finalBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        "png" -> finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        "webp" -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                finalBmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                            } else {
                                @Suppress("DEPRECATION")
                                finalBmp.compress(Bitmap.CompressFormat.WEBP, 90, out)
                            }
                        }
                        "jxl" -> out.write(com.awxkee.jxlcoder.JxlCoder.encode(finalBmp))
                        else -> finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                if (drawingBmp != null) drawingBmp.recycle()
                if (resultBmp !== srcBmp) resultBmp.recycle()
                if (finalBmp !== resultBmp) finalBmp.recycle()
                srcBmp.recycle()

                runOnUiThread {
                    holder.progressBar.visibility = View.GONE
                    Toast.makeText(this@SlideShowActivity,
                        getString(R.string.image_saved, saveFile.name), Toast.LENGTH_SHORT).show()
                    holder.loadImage(saveFile)
                    
                    drawingOverlay.clearDrawing()
                    drawingOverlay.isDrawingMode = false
                    drawingOverlay.isCropMode = false
                    updateDrawingOverlayState(drawingOverlay)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    holder.progressBar.visibility = View.GONE
                    Toast.makeText(this@SlideShowActivity,
                        getString(R.string.image_save_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showConvertToPdfDialog() {
        val position = viewPager.currentItem
        val filePath = playlist.getOrNull(position) ?: return
        val file = File(filePath)
        if (shareId.isNotEmpty()) {
            Toast.makeText(this, "PDF conversion is only supported for local files", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = za.kilowatch.ultimatefilemanager.ui.ConvertToPdfDialog().apply {
            arguments = Bundle().apply {
                putString("original_filename", file.nameWithoutExtension)
                putString("image_path", file.absolutePath)
            }
        }
        dialog.show(supportFragmentManager, "ConvertToPdfDialog")
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        controlsVisible = true
        toolbar.visibility = View.VISIBLE
        toolbar.alpha = 1f

        // Show status/navigation bars
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())

        val path = playlist.getOrNull(viewPager.currentItem) ?: return
        val ext = path.substringAfterLast('.', "").lowercase()
        val isVideo = ext in FileViewerRouter.VIDEO_EXTENSIONS
        val isImage = ext in FileViewerRouter.IMAGE_EXTENSIONS

        val layoutPhotoActions = findViewById<View>(R.id.layoutPhotoActions)
        if (isImage && !isTv) {
            layoutPhotoActions?.visibility = View.VISIBLE
            layoutPhotoActions?.alpha = 1f
        } else {
            layoutPhotoActions?.visibility = View.GONE
        }

        if (isVideo) {
            controlsLayout.visibility = View.VISIBLE
            controlsLayout.alpha = 1f
            resetHideTimer()
        } else {
            controlsLayout.visibility = View.GONE
        }

        if (isTv) {
            if (isImage) {
                tvImageControlBar?.visibility = View.VISIBLE
                tvImageControlBar?.alpha = 1f
                findViewById<View>(R.id.btnZoomIn)?.requestFocus()
            } else {
                tvImageControlBar?.visibility = View.GONE
                btnPlayPause.requestFocus()
            }
        }
    }

    private fun hideControls() {
        controlsVisible = false
        handler.removeCallbacks(hideControlsRunnable)

        // Hide status/navigation bars
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        toolbar.animate().alpha(0f).setDuration(250).withEndAction {
            toolbar.visibility = View.GONE
        }
        controlsLayout.animate().alpha(0f).setDuration(250).withEndAction {
            controlsLayout.visibility = View.GONE
        }
        tvImageControlBar?.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {
            tvImageControlBar?.visibility = View.GONE
        }
        val layoutPhotoActions = findViewById<View>(R.id.layoutPhotoActions)
        layoutPhotoActions?.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {
            layoutPhotoActions.visibility = View.GONE
        }
    }

    private fun showDeleteConfirmation() {
        val position = viewPager.currentItem
        val path = playlist.getOrNull(position) ?: return
        val fileName = path.substringAfterLast("/")
        val file = File(path)
        val isLocal = shareId.isEmpty() && shareHost.isEmpty()

        if (isLocal) {
            val isProtected = ProtectedFilesManager.isOrContainsProtected(this, path)
            if (isProtected) {
                ProtectedFilesManager.showProtectedDeleteDialog(this, isTv)
                return
            }

            val recycleEnabled = RecycleBinManager.isEnabled
            val titleRes = if (recycleEnabled) R.string.move_to_bin else R.string.delete_title
            val msg = if (recycleEnabled) {
                getString(R.string.recycle_bin_move_confirm, 1)
            } else {
                getString(R.string.delete_single_confirm, fileName)
            }
            val btnConfirmRes = if (recycleEnabled) R.string.move_to_bin else R.string.delete_confirm

            MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(getString(titleRes))
                .setMessage(msg)
                .setIcon(R.drawable.ic_delete)
                .setNegativeButton(getString(R.string.delete_cancel), null)
                .setPositiveButton(getString(btnConfirmRes)) { _, _ ->
                    performDeleteLocal(file, position, recycleEnabled)
                }
                .show()
        } else {
            MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(getString(R.string.delete_title))
                .setMessage(getString(R.string.delete_single_confirm, fileName))
                .setIcon(R.drawable.ic_delete)
                .setNegativeButton(getString(R.string.delete_cancel), null)
                .setPositiveButton(getString(R.string.delete_confirm)) { _, _ ->
                    performDeleteNetwork(path, position)
                }
                .show()
        }
    }

    private fun performDeleteLocal(file: File, position: Int, recycleEnabled: Boolean) {
        val fileName = file.name
        val filePath = file.absolutePath
        lifecycleScope.launch(Dispatchers.IO) {
            val success = if (recycleEnabled) {
                val (storageId, storageType, _) = IndexingRepository.resolveStorageForPath(filePath)
                RecycleBinManager.moveToTrash(this@SlideShowActivity, file, storageType, storageId, storageId)
            } else {
                file.delete()
            }

            if (success) {
                UfmApplication.indexingRepository.deleteTreeFromIndex(filePath)
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    val feedback = if (recycleEnabled) {
                        getString(R.string.item_moved_to_recycle_bin, fileName)
                    } else {
                        getString(R.string.item_deleted, fileName)
                    }
                    Toast.makeText(this@SlideShowActivity, feedback, Toast.LENGTH_SHORT).show()
                    onItemDeleted(position)
                } else {
                    Toast.makeText(this@SlideShowActivity, R.string.delete_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performDeleteNetwork(path: String, position: Int) {
        val fileName = path.substringAfterLast("/")
        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            try {
                val share = NetworkShareRepository.getInstance(this@SlideShowActivity).getById(shareId)
                    ?: NetworkShare(
                        id = shareId,
                        host = shareHost,
                        name = shareName,
                        type = ShareType.valueOf(provider)
                    )
                when (share.type) {
                    ShareType.SMB -> SmbShareClient.deleteFile(share, path)
                    ShareType.FTP -> FtpShareClient.deleteFile(share, path)
                    ShareType.TV  -> TvShareClient.deleteFile(share, path)
                    ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, path, false)
                    ShareType.ONEDRIVE -> OnedriveShareClient.deleteFile(share, path)
                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, path)
                    ShareType.DROPBOX -> DropboxShareClient.deleteFile(share, path)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, path)
                    ShareType.WEBDAV -> WebDavShareClient.deleteFile(share, path)
                    ShareType.NFS -> NfsShareClient.deleteFile(share, path)
                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                }
                success = true
            } catch (e: Exception) {
                GoRoLog.e("SlideShowActivity", "Failed to delete network file: $path", e)
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@SlideShowActivity, getString(R.string.item_deleted, fileName), Toast.LENGTH_SHORT).show()
                    onItemDeleted(position)
                } else {
                    Toast.makeText(this@SlideShowActivity, R.string.delete_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onItemDeleted(position: Int) {
        val path = playlist.getOrNull(position)
        if (path != null) {
            sizesMap.remove(path)
        }
        val adapter = viewPager.adapter as? SlideShowAdapter
        adapter?.removeItem(position)

        if (playlist.isEmpty()) {
            finish()
        } else {
            val nextPos = position.coerceAtMost(playlist.size - 1)
            viewPager.setCurrentItem(nextPos, false)
            updatePageState(nextPos)
        }
    }

    private fun showRenameDialog() {
        val position = viewPager.currentItem
        val path = playlist.getOrNull(position) ?: return
        val currentFileName = path.substringAfterLast("/")
        val isLocal = shareId.isEmpty() && shareHost.isEmpty()
        val file = File(path)

        val editText = EditText(this).apply {
            setText(currentFileName)
            val dotIndex = currentFileName.lastIndexOf('.')
            if (dotIndex > 0) {
                setSelection(0, dotIndex)
            } else {
                selectAll()
            }
            setPadding(64, 32, 64, 32)
        }

        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.rename_title))
            .setView(editText)
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .setPositiveButton(getString(R.string.rename_confirm)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != currentFileName) {
                    if (isLocal) {
                        performRenameLocal(file, newName, position)
                    } else {
                        performRenameNetwork(path, newName, position)
                    }
                }
            }
            .show()
    }

    private fun performRenameLocal(file: File, newName: String, position: Int) {
        val newFile = File(file.parent, newName)
        if (newFile.exists()) {
            Toast.makeText(this, R.string.rename_error, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val success = file.renameTo(newFile)
            if (success) {
                FileTagsManager.onPathMoved(this@SlideShowActivity, file.absolutePath, newFile.absolutePath)
                UfmApplication.indexingRepository.deleteTreeFromIndex(file.absolutePath)
            }
            withContext(Dispatchers.Main) {
                if (success) {
                    val adapter = viewPager.adapter as? SlideShowAdapter
                    adapter?.updateItem(position, newFile.absolutePath)
                    txtTitle.text = newName
                    Toast.makeText(this@SlideShowActivity, R.string.rename_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SlideShowActivity, R.string.rename_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performRenameNetwork(path: String, newName: String, position: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            val parentPath = path.substringBeforeLast('/', "")
            val targetPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"
            try {
                val share = NetworkShareRepository.getInstance(this@SlideShowActivity).getById(shareId)
                    ?: NetworkShare(
                        id = shareId,
                        host = shareHost,
                        name = shareName,
                        type = ShareType.valueOf(provider)
                    )
                when (share.type) {
                    ShareType.SMB -> SmbShareClient.rename(share, path, targetPath)
                    ShareType.FTP -> FtpShareClient.rename(share, path, targetPath)
                    ShareType.TV  -> TvShareClient.rename(share, path, targetPath)
                    ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(share, path, targetPath)
                    ShareType.ONEDRIVE -> OnedriveShareClient.rename(share, path, targetPath)
                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, path, targetPath)
                    ShareType.DROPBOX -> DropboxShareClient.rename(share, path, targetPath)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, path, targetPath)
                    ShareType.WEBDAV -> WebDavShareClient.rename(share, path, targetPath, false)
                    ShareType.NFS -> NfsShareClient.rename(share, path, targetPath)
                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                }
                success = true
            } catch (e: Exception) {
                GoRoLog.e("SlideShowActivity", "Failed to rename network file: $path", e)
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    val adapter = viewPager.adapter as? SlideShowAdapter
                    adapter?.updateItem(position, targetPath)
                    txtTitle.text = newName
                    Toast.makeText(this@SlideShowActivity, R.string.rename_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SlideShowActivity, R.string.rename_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSlideshowSpeedDialog() {
        val speeds = arrayOf("0.5s", "1.0s", "1.5s", "2.0s", "2.5s", "3.0s", "3.5s", "4.0s", "4.5s", "5.0s")
        val currentSelection = slideshowStep.coerceIn(0, 9)

        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.slideshow_speed_title))
            .setSingleChoiceItems(speeds, currentSelection) { dialog, which ->
                slideshowStep = which
                val sec = 0.5f + slideshowStep * 0.5f
                val txtIntervalTv = findViewById<TextView>(R.id.txtSlideshowIntervalTv)
                txtIntervalTv?.text = String.format(java.util.Locale.US, "%.1fs", sec)
                Toast.makeText(this, getString(R.string.slideshow_interval_seconds, sec), Toast.LENGTH_SHORT).show()
                if (isSlideshowPlaying && isCurrentImage()) {
                    handler.removeCallbacks(slideshowRunnable)
                    handler.postDelayed(slideshowRunnable, slideshowIntervalMs)
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.delete_cancel), null)
            .show()
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val position = viewPager.currentItem
        val path = playlist.getOrNull(position) ?: return
        val ext = path.substringAfterLast('.', "").lowercase()
        val isImage = ext in FileViewerRouter.IMAGE_EXTENSIONS
        val isLocal = shareId.isEmpty() && shareHost.isEmpty()

        if (isImage && isLocal && ext != "gif") {
            popup.menu.add(0, 1, 0, getString(R.string.pdf_convert_title))
        }
        popup.menu.add(0, 2, 1, getString(R.string.slideshow_speed_title))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showConvertToPdfDialog()
                2 -> showSlideshowSpeedDialog()
            }
            true
        }
        popup.show()
    }

    private fun resetHideTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible && isCurrentItemVideo() && isVideoPlaying()) {
            handler.postDelayed(hideControlsRunnable, ControlsTimeoutManager.loadDurationMs(this))
        }
    }

    private fun isCurrentItemVideo(): Boolean {
        val path = playlist.getOrNull(viewPager.currentItem) ?: return false
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in FileViewerRouter.VIDEO_EXTENSIONS
    }

    private fun isCurrentImage(): Boolean {
        val path = playlist.getOrNull(viewPager.currentItem) ?: return false
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in FileViewerRouter.IMAGE_EXTENSIONS
    }

    private fun isVideoPlaying(): Boolean {
        return player?.isPlaying == true
    }

    private fun updatePlayPauseIcon() {
        val isPlaying = player?.isPlaying ?: false
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        if (btnPlayPause is ImageButton) {
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

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> getString(R.string.bytes_b)
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(getString(R.string.q1f_mb), bytes / (1024.0 * 1024.0))
        }
    }

    private fun updateAlpha(view: View, isActive: Boolean) {
        view.alpha = if (isActive) 1.0f else (if (isTv) 0.5f else 0.4f)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            resetHideTimer()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isTv) {
            if (!controlsVisible) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        navigatePrev()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        navigateNext()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        showControls()
                        return true
                    }
                }
            } else {
                // If controls are visible and we press D-pad Left/Right/Up/Down on TV,
                // we keep them visible by resetting the auto-hide timer
                resetHideTimer()
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (isTv && controlsVisible) {
            hideControls()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isSlideshowPlaying && isCurrentImage()) {
            handler.removeCallbacks(slideshowRunnable)
            handler.postDelayed(slideshowRunnable, slideshowIntervalMs)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(slideshowRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(slideshowRunnable)
        resetPlayer()
        // Clean up slideshow temp cache images
        val cacheFiles = cacheDir.listFiles { f -> f.name.startsWith("ufm_slideshow_") }
        cacheFiles?.forEach { it.delete() }
    }
}

// ── Touch Zoom Listener ──
class ZoomTouchListener(
    private val viewPager: ViewPager2,
    private val imageView: ImageView
) : View.OnTouchListener {

    private val matrix = Matrix()
    private var minScaleFactor = 1f
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var imageWidth = 0
    private var imageHeight = 0
    private var rotationDegrees = 0f

    private val scaleDetector = ScaleGestureDetector(imageView.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focusX = detector.focusX
            val focusY = detector.focusY
            val oldScale = scaleFactor
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScaleFactor, minScaleFactor * 10f)
            val r = scaleFactor / oldScale
            translateX = focusX - (focusX - translateX) * r
            translateY = focusY - (focusY - translateY) * r
            
            viewPager.isUserInputEnabled = (scaleFactor <= minScaleFactor * 1.01f)
            updateMatrix()
            return true
        }
    })

    private val gestureDetector = GestureDetector(imageView.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scaleFactor > minScaleFactor * 1.01f) {
                reset()
            } else {
                val oldScale = scaleFactor
                scaleFactor = minScaleFactor * 3f
                val r = scaleFactor / oldScale
                val focusX = e.x
                val focusY = e.y
                translateX = focusX - (focusX - translateX) * r
                translateY = focusY - (focusY - translateY) * r
                viewPager.isUserInputEnabled = false
                updateMatrix()
            }
            return true
        }
    })

    fun setImageDimensions(w: Int, h: Int) {
        imageWidth = w
        imageHeight = h
        fitImageToView()
    }

    fun fitImageToView() {
        if (imageWidth == 0 || imageHeight == 0) return
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return

        val isSwapped = (rotationDegrees / 90f).toInt() % 2 != 0
        val w = if (isSwapped) imageHeight.toFloat() else imageWidth.toFloat()
        val h = if (isSwapped) imageWidth.toFloat() else imageHeight.toFloat()

        val scaleX = viewWidth / w
        val scaleY = viewHeight / h
        val fitScale = minOf(scaleX, scaleY)
        minScaleFactor = fitScale
        scaleFactor = fitScale

        translateX = (viewWidth - w * scaleFactor) / 2f
        translateY = (viewHeight - h * scaleFactor) / 2f

        updateMatrix()
    }

    fun reset() {
        rotationDegrees = 0f
        viewPager.isUserInputEnabled = true
        fitImageToView()
    }

    fun pan(dx: Float, dy: Float) {
        if (scaleFactor > minScaleFactor * 1.01f) {
            translateX += dx
            translateY += dy
            updateMatrix()
        }
    }

    fun zoom(factor: Float) {
        val oldScale = scaleFactor
        scaleFactor = (scaleFactor * factor).coerceIn(minScaleFactor, minScaleFactor * 10f)
        val r = scaleFactor / oldScale
        val focusX = imageView.width / 2f
        val focusY = imageView.height / 2f
        translateX = focusX - (focusX - translateX) * r
        translateY = focusY - (focusY - translateY) * r
        
        viewPager.isUserInputEnabled = (scaleFactor <= minScaleFactor * 1.01f)
        updateMatrix()
    }

    fun rotateImage(delta: Float) {
        rotationDegrees = (rotationDegrees + delta) % 360f
        if (rotationDegrees < 0) rotationDegrees += 360f
        fitImageToView()
    }

    private fun updateMatrix() {
        matrix.reset()
        matrix.postRotate(rotationDegrees, imageWidth / 2f, imageHeight / 2f)
        matrix.postScale(scaleFactor, scaleFactor)

        val pts = floatArrayOf(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        matrix.mapPoints(pts)

        matrix.reset()
        matrix.postRotate(rotationDegrees, imageWidth / 2f, imageHeight / 2f)

        val rect = android.graphics.RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        matrix.mapRect(rect)
        matrix.postTranslate(-rect.left, -rect.top)

        matrix.postScale(scaleFactor, scaleFactor)
        matrix.postTranslate(translateX, translateY)

        imageView.imageMatrix = matrix
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && scaleFactor > minScaleFactor * 1.01f) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        translateX += x - lastTouchX
                        translateY += y - lastTouchY
                        lastTouchX = x
                        lastTouchY = y
                        updateMatrix()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }
}

// ── SlideShowAdapter ──
class SlideShowAdapter(
    private val context: Context,
    private val playlist: ArrayList<String>,
    private val shareId: String,
    private val shareHost: String,
    private val shareName: String,
    private val provider: String,
    private val remotePath: String,
    private val coilLoader: ImageLoader,
    private val viewPager: ViewPager2,
    private val onItemClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_VIDEO = 1
    }

    override fun getItemViewType(position: Int): Int {
        val path = playlist[position]
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext in FileViewerRouter.VIDEO_EXTENSIONS) TYPE_VIDEO else TYPE_IMAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VIDEO) {
            val view = inflater.inflate(R.layout.item_slideshow_video, parent, false)
            VideoViewHolder(view, onItemClick)
        } else {
            val view = inflater.inflate(R.layout.item_slideshow_image, parent, false)
            ImageViewHolder(view, viewPager, coilLoader, shareId, shareHost, shareName, provider, remotePath, onItemClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val path = playlist[position]
        if (holder is ImageViewHolder) {
            holder.bind(path)
        } else if (holder is VideoViewHolder) {
            holder.bind(path)
        }
    }

    override fun getItemCount(): Int = playlist.size

    fun removeItem(position: Int) {
        if (position in 0 until playlist.size) {
            playlist.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, playlist.size - position)
        }
    }

    fun updateItem(position: Int, newPath: String) {
        if (position in 0 until playlist.size) {
            playlist[position] = newPath
            notifyItemChanged(position)
        }
    }

    // Image Page ViewHolder
    class ImageViewHolder(
        itemView: View,
        private val viewPager: ViewPager2,
        private val coilLoader: ImageLoader,
        private val shareId: String,
        private val shareHost: String,
        private val shareName: String,
        private val provider: String,
        private val remotePath: String,
        private val onItemClick: () -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imageView)
        val drawingOverlay: DrawingOverlayView = itemView.findViewById(R.id.drawingOverlay)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        var zoomTouchListener: ZoomTouchListener? = null

        fun bind(path: String) {
            zoomTouchListener = ZoomTouchListener(viewPager, imageView)
            imageView.setOnTouchListener(zoomTouchListener)

            imageView.scaleType = ImageView.ScaleType.MATRIX

            // Single tap listener to toggle controls
            itemView.setOnClickListener { onItemClick() }
            imageView.setOnClickListener { onItemClick() }

            val isLocal = shareId.isEmpty() && shareHost.isEmpty()
            if (isLocal) {
                loadImage(File(path))
            } else {
                val cacheFile = File(itemView.context.cacheDir, "ufm_slideshow_${path.hashCode()}.${File(path).extension}")
                if (cacheFile.exists()) {
                    loadImage(cacheFile)
                } else {
                    progressBar.visibility = View.VISIBLE
                    downloadNetworkFile(itemView.context, shareId, shareHost, shareName, provider, remotePath, path, cacheFile) { success ->
                        progressBar.visibility = View.GONE
                        if (success) {
                            loadImage(cacheFile)
                        } else {
                            Toast.makeText(itemView.context, "Failed to load network image: ${File(path).name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        fun loadImage(file: File) {
            val request = ImageRequest.Builder(itemView.context)
                .data(file)
                .allowHardware(false)
                .target(
                    onStart = { progressBar.visibility = View.VISIBLE },
                    onSuccess = { image ->
                        progressBar.visibility = View.GONE
                        val drawable = image.asDrawable(itemView.resources)
                        imageView.setImageDrawable(drawable)
                        (drawable as? Animatable)?.start()

                        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: imageView.width
                        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: imageView.height

                        imageView.post {
                            zoomTouchListener?.setImageDimensions(w, h)
                        }
                    },
                    onError = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val bmp = try {
                                val exif = android.media.ExifInterface(file.absolutePath)
                                exif.thumbnailBitmap ?: exif.thumbnailBytes?.let { bytes ->
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } ?: za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(file.absolutePath, 0, 1920, 1080)
                            } catch (_: Throwable) {
                                za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper.extractVideoFrame(file.absolutePath, 0, 1920, 1080)
                            }
                            withContext(Dispatchers.Main) {
                                progressBar.visibility = View.GONE
                                if (bmp != null) {
                                    imageView.setImageBitmap(bmp)
                                    val w = bmp.width
                                    val h = bmp.height
                                    imageView.post {
                                        zoomTouchListener?.setImageDimensions(w, h)
                                    }
                                }
                            }
                        }
                    }
                )
                .build()
            coilLoader.enqueue(request)
        }

        private fun downloadNetworkFile(
            context: Context,
            shareId: String,
            shareHost: String,
            shareName: String,
            provider: String,
            shareRemotePath: String,
            fileRemotePath: String,
            destFile: File,
            onComplete: (Boolean) -> Unit
        ) {
            var share = NetworkShareRepository.getInstance(context).getById(shareId)
                ?: NetworkShare(
                    id = shareId,
                    host = shareHost,
                    name = shareName,
                    type = ShareType.valueOf(provider)
                )
            // Server-mode shares need remotePath from the intent (browser updates it at navigation time)
            if (share.isServerMode && shareRemotePath.isNotEmpty()) {
                share = share.copy(remotePath = shareRemotePath)
            }

            CoroutineScope(Dispatchers.IO).launch {
                var success = false
                try {
                    val inStream = when (share.type) {
                        ShareType.SMB -> SmbShareClient.openInputStream(share, fileRemotePath)
                        ShareType.FTP -> FtpShareClient.openInputStream(share, fileRemotePath)
                        ShareType.TV  -> TvShareClient.openInputStream(share, fileRemotePath)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, fileRemotePath)
                        ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, fileRemotePath).first
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, fileRemotePath).first
                        ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, fileRemotePath).first
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, fileRemotePath).first
                        ShareType.WEBDAV                      -> WebDavShareClient.openInputStream(share, fileRemotePath).first
                        ShareType.NFS                         -> NfsShareClient.openInputStream(share, fileRemotePath)
                        ShareType.DLNA                        -> DlnaShareClient.openInputStream(share, fileRemotePath)
                    }
                    destFile.parentFile?.mkdirs()
                    inStream.use { inp ->
                        destFile.outputStream().use { out ->
                            inp.copyTo(out)
                        }
                    }
                    success = true
                } catch (e: Exception) {
                    GoRoLog.e("SlideShowAdapter", "Failed to download $fileRemotePath", e)
                }
                withContext(Dispatchers.Main) {
                    onComplete(success)
                }
            }
        }
    }

    // Video Page ViewHolder
    class VideoViewHolder(
        itemView: View,
        private val onItemClick: () -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)

        fun bind(path: String) {
            itemView.setOnClickListener { onItemClick() }
            playerView.setOnClickListener { onItemClick() }
        }
    }
}
