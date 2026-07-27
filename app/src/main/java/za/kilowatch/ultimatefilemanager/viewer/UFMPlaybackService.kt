package za.kilowatch.ultimatefilemanager.viewer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
// MediaStyle requires a system MediaSession token to render progress — using standard NotificationCompat instead
import androidx.core.app.NotificationCompat.BigTextStyle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.*
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the ExoPlayer, MediaSession, and notification.
 * Lifecycle:
 *  - Started when the user plays a file (via UFMPlayerActivity).
 *  - Continues playing when the Activity unbinds (minimized).
 *  - Stops when the queue is empty & autoplay is off, or user taps Stop.
 */
class UFMPlaybackService : Service() {

    // ── Player & Session ────────────────────────────────────────────
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    val queueManager = QueueManager()

    // ── State ───────────────────────────────────────────────────────
    var isShuffle = false
        private set
    var isRepeat = false
        private set
    var skipAutoplayOnce = false
        private set

    private var networkShare: NetworkShare? = null
    private var initialFileSize: Long = 0L
    private var isAudioOnlyBackground = false  // true when video switched to audio-only
    private var metadataThread: Thread? = null

    // ── Wake Lock ───────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Audio Focus ─────────────────────────────────────────────────
    private val audioManager: AudioManager? by lazy { getSystemService(AUDIO_SERVICE) as? AudioManager }
    private var audioFocusLossCount = 0
    private var audioFocusLossWindowStart = 0L
    private var sessionAutoResumeDisabled = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        handleAudioFocusChange(focusChange)
    }

    // ── Notification ────────────────────────────────────────────────
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private var currentNotificationMetadata: MediaMetadata? = null

    companion object {
        const val NOTIFICATION_ID = 7003
        const val CHANNEL_ID = "ufm_media_playback"

        private val alive = AtomicBoolean(false)
        fun isServiceAlive(): Boolean = alive.get()

        // ── Actions ─────────────────────────────────────────────────
        const val ACTION_PLAY = "za.kilowatch.ultimatefilemanager.action.PLAY"
        const val ACTION_PAUSE = "za.kilowatch.ultimatefilemanager.action.PAUSE"
        const val ACTION_TOGGLE = "za.kilowatch.ultimatefilemanager.action.TOGGLE"
        const val ACTION_STOP = "za.kilowatch.ultimatefilemanager.action.STOP"
        const val ACTION_SKIP_NEXT = "za.kilowatch.ultimatefilemanager.action.SKIP_NEXT"
        const val ACTION_SKIP_PREV = "za.kilowatch.ultimatefilemanager.action.SKIP_PREV"
        const val ACTION_SEEK_TO = "za.kilowatch.ultimatefilemanager.action.SEEK_TO"
        const val EXTRA_SEEK_POSITION = "seek_position"

        /** Convenience: start the service with a playback intent. */
        fun start(context: Context, intent: Intent) {
            val serviceIntent = Intent(context, UFMPlaybackService::class.java).apply {
                putExtras(intent.extras ?: Bundle())
                action = ACTION_PLAY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        /** Convenience: stop the service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, UFMPlaybackService::class.java))
        }

        /** Check if the service is currently running. */
        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
            val runningServices = manager.getRunningServices(50)
            return runningServices.any { it.service.className == UFMPlaybackService::class.java.name }
        }
    }

    // ── Binder ──────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): UFMPlaybackService = this@UFMPlaybackService
    }

    private val binder = LocalBinder()

    /** Callback interface for bound Activities to receive playback updates. */
    interface PlaybackCallback {
        fun onProgressUpdate(position: Long, duration: Long)
        fun onTrackChanged(trackInfo: QueueItem?)
        fun onPlaybackStateChanged(isPlaying: Boolean, state: Int, isLocal: Boolean)
        fun onMetadataChanged(metadata: MediaMetadata)
        fun onQueueChanged(queue: List<QueueItem>)
        fun onError(error: String)
    }

    private var playbackCallback: PlaybackCallback? = null

    fun registerCallback(callback: PlaybackCallback) {
        playbackCallback = callback
        // Push current state immediately
        player?.let { p ->
            callback.onPlaybackStateChanged(p.isPlaying, p.playbackState, isCurrentLocal)
            callback.onProgressUpdate(p.currentPosition, p.duration)
        }
        callback.onQueueChanged(queueManager.queue)
    }

    fun unregisterCallback() {
        playbackCallback = null
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        alive.set(true)
        createNotificationChannel()
        registerHeadsetPlugReceiver()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> handlePlayAction(intent)
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> toggle()
            ACTION_STOP -> handleStopAction()
            ACTION_SKIP_NEXT -> skipToNext()
            ACTION_SKIP_PREV -> skipToPrev()
            ACTION_SEEK_TO -> {
                val pos = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)
                seekTo(pos)
            }
            else -> {
                // Direct launch from intent with extras (from UFMPlayerActivity)
                if (intent?.hasExtra("initialPath") == true && !serviceAlreadyStarted()) {
                    handlePlayAction(intent)
                }
            }
        }

        // If media is playing, show sticky notification
        if (player?.isPlaying == true) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        unregisterHeadsetPlugReceiver()
        // If paused, stop entirely. If playing, keep alive.
        if (player?.isPlaying != true) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        alive.set(false)
        stopNotificationUpdates()
        metadataThread?.interrupt()
        unregisterHeadsetPlugReceiver()
        releaseWakeLock()
        abandonAudioFocus()
        playbackCallback = null
        mediaSession?.release()
        mediaSession = null
        player?.removeListener(playerListener)
        player?.stop()
        player?.release()
        player = null
    }

    // ── Playback Control (called from Binder / Activity) ────────────

    /**
     * Start playback with the given queue and credentials.
     * Called either from Activity intent extras or from the initial launch.
     */
    fun startPlayback(
        paths: ArrayList<String>,
        startIndex: Int,
        shareId: String?,
        shareHost: String?,
        shareUsername: String?,
        shareName: String?,
        provider: String?,
        remotePath: String?,
        fileSize: Long,
        isServerMode: Boolean = false
    ) {
        // Build QueueItems from the path list
        val items = paths.mapIndexed { index, path ->
            val ext = path.substringAfterLast('.', "").lowercase()
            QueueItem(
                path = path,
                isVideo = !FileViewerRouter.isAudio(ext),
                fileSize = fileSize,
                shareId = shareId,
                shareHost = shareHost,
                shareUsername = shareUsername,
                shareName = shareName,
                provider = provider,
                remotePath = remotePath
            )
        }

        queueManager.setQueue(items, startIndex)
        initialFileSize = fileSize
        networkShare = provider?.let { buildNetworkShare(shareId, shareHost, shareUsername, shareName, it, remotePath, isServerMode) }

        playCurrent()
        playbackCallback?.onQueueChanged(queueManager.queue)
    }

    /**
     * Update the queue (e.g. after reorder/remove/add from UI).
     */
    fun updateQueue(items: List<QueueItem>, newIndex: Int? = null) {
        val wasPlaying = player?.isPlaying == true
        val currentPos = player?.currentPosition ?: 0L

        queueManager.setQueue(items, newIndex ?: queueManager.currentIndex)

        // If the current track changed, restart playback
        if (newIndex != null && newIndex != queueManager.currentIndex) {
            playCurrent()
        }

        playbackCallback?.onQueueChanged(queueManager.queue)
    }

    fun play() {
        player?.play()
        requestAudioFocus()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        updateNotification()
        startNotificationUpdates()
    }

    fun pause() {
        player?.pause()
        releaseWakeLock()
        updateNotification()
        stopNotificationUpdates()
    }

    fun toggle() {
        if (player?.isPlaying == true) pause() else play()
    }

    fun seekTo(position: Long) {
        player?.seekTo(position)
    }

    fun skipToNext() {
        val nextIdx = queueManager.nextIndex(isShuffle) ?: return
        queueManager.currentIndex = nextIdx
        playCurrent()
    }

    fun skipToPrev() {
        // If more than 5 seconds in, restart current track
        val pos = player?.currentPosition ?: 0L
        if (pos > 5000L) {
            player?.seekTo(0)
            return
        }
        val prevIdx = queueManager.prevIndex(isShuffle) ?: return
        queueManager.currentIndex = prevIdx
        playCurrent()
    }

    fun toggleShuffle() {
        isShuffle = !isShuffle
    }

    fun toggleRepeat() {
        isRepeat = !isRepeat
    }

    fun setSkipAutoplayOnce(value: Boolean) {
        skipAutoplayOnce = value
    }

    fun getPlayer(): ExoPlayer? = player

    /** For mini-player/Activity to read current position. */
    val currentPosition: Long get() = player?.currentPosition ?: 0L
    val duration: Long get() = player?.duration ?: 0L
    val isPlaying: Boolean get() = player?.isPlaying == true
    val currentQueueItem: QueueItem? get() = queueManager.currentItem

    /** Whether the currently playing source is local (vs network). */
    var isCurrentLocal: Boolean = false
        private set

    // ── Internal Playback ───────────────────────────────────────────

    private fun playCurrent() {
        val item = queueManager.currentItem ?: return
        val fileName = item.path.substringAfterLast('/')

        // Reset skip-autoplay-once on new track
        skipAutoplayOnce = false

        // Build ExoPlayer if needed
        if (player == null) {
            val newPlayer = ExoPlayer.Builder(this).build()
            player = newPlayer
            mediaSession = MediaSession.Builder(this, newPlayer)
                .setCallback(MediaSessionCallback(this@UFMPlaybackService))
                .build()
            newPlayer.addListener(playerListener)
        } else {
            // Remove stale listener before re-adding
            player?.removeListener(playerListener)
            player?.addListener(playerListener)
        }

        val p = player ?: return

        // Determine if audio or video
        val isAudio = FileViewerRouter.isAudio(item.path.substringAfterLast('.'))
        isAudioOnlyBackground = isAudio  // Audio is always audio-only in background

        // Build media source
        val isNetwork = networkShare != null
        isCurrentLocal = !isNetwork
        val mediaSource = if (isNetwork) {
            // Network file
            var share = networkShare ?: run {
                GoRoLog.e("UFMPlaybackService", "playCurrent: networkShare is null but isNetwork=true")
                return
            }

                // Strip server-mode prefix to avoid duplicated path segments (e.g. "MM/MM/file.mp4")
            val cleanPath = stripSharePrefix(share, item.path)
            val dataSourceFactory = DataSource.Factory {
                UfmMedia3DataSource(share, cleanPath)
            }
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse("ufm://${item.path.replace(" ", "%20")}")))
        } else {
            // Local file
            val localUri = Uri.parse(item.path)
            GoRoLog.i("UFMPlaybackService", "playCurrent: local file uri=$localUri")
            val dataSourceFactory = DefaultDataSource.Factory(this)
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(localUri))
        }

        p.stop()
        p.clearMediaItems()
        p.setMediaSource(mediaSource)
        p.prepare()
        p.playWhenReady = true
        p.play()

        // Request audio focus
        requestAudioFocus()

        // Extract metadata in background
        extractMetadata(item)

        // Build & show notification
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        startNotificationUpdates()

        // Notify callback
        playbackCallback?.onTrackChanged(item)
        playbackCallback?.onPlaybackStateChanged(true, Player.STATE_READY, isCurrentLocal)
    }

    // ── Player Listener ─────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val p = player ?: return

            if (state == Player.STATE_ENDED) {
                handler.post {
                    if (isRepeat) {
                        if (isCurrentLocal) {
                            p?.seekTo(0)
                            p?.play()
                        } else {
                            playCurrent()
                        }
                    } else if (skipAutoplayOnce) {
                        // Cancel was tapped earlier — stop here
                        skipAutoplayOnce = false
                        pause()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        PlayerStateManager.saveState(
                            this@UFMPlaybackService,
                            queueManager.queue,
                            queueManager.currentIndex,
                            p.currentPosition,
                            false, isShuffle, isRepeat
                        )
                    } else if (queueManager.size > 1) {
                        skipToNext()
                    } else {
                        // Last item and no autoplay
                        pause()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }

            if (state == Player.STATE_BUFFERING) {
                playbackCallback?.onPlaybackStateChanged(p.isPlaying, state, isCurrentLocal)
            }

            if (state != Player.STATE_BUFFERING) {
                updateNotification()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playbackCallback?.onPlaybackStateChanged(isPlaying, player?.playbackState ?: Player.STATE_IDLE, isCurrentLocal)
            if (isPlaying) {
                acquireWakeLock()
                startForeground(NOTIFICATION_ID, buildNotification())
            } else {
                releaseWakeLock()
            }
            updateNotification()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            GoRoLog.e("UFMPlaybackService", "Player error: ${error.message}", error)
            playbackCallback?.onError(error.localizedMessage ?: "Playback error")

            // On network error, try to skip to next
            if (queueManager.size > 1) {
                skipToNext()
            } else {
                stopSelf()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val item = queueManager.currentItem
            playbackCallback?.onTrackChanged(item)
            if (item != null) extractMetadata(item)
        }
    }

    // ── Audio Focus ─────────────────────────────────────────────────

    private fun requestAudioFocus() {
        audioManager?.requestAudioFocus(
            audioFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
    }

    private fun abandonAudioFocus() {
        audioManager?.abandonAudioFocus(audioFocusListener)
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Resume playback if we paused for a transient loss
                if (player?.isPlaying == false && !sessionAutoResumeDisabled) {
                    player?.play()
                }
                player?.volume = 1.0f
                audioFocusLossCount = 0
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss — pause
                pause()
                abortAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss — pause
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Duck volume
                player?.volume = 0.3f
            }
        }

        // Track focus loss events to prevent rapid cycling
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            val now = System.currentTimeMillis()
            if (now - audioFocusLossWindowStart > 60_000) {
                audioFocusLossCount = 1
                audioFocusLossWindowStart = now
            } else {
                audioFocusLossCount++
                if (audioFocusLossCount >= 3) {
                    sessionAutoResumeDisabled = true
                }
            }
        }
    }

    private fun abortAudioFocus() {
        audioManager?.abandonAudioFocus(audioFocusListener)
    }

    // ── Wake Lock ───────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UFM:MediaPlayback"
            ).apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(30 * 60 * 1000L) // max 30 min
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    // ── Notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_name))
            .setDescription(getString(R.string.notification_channel_description))
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val item = queueManager.currentItem
        val title = item?.title ?: item?.path?.substringAfterLast('/') ?: getString(R.string.app_name)
        val artist = item?.artist ?: getString(R.string.audio_no_metadata)
        val isPlaying = player?.isPlaying == true

        // Open player Activity
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, UFMPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dur = player?.duration ?: 0L
        val pos = player?.currentPosition ?: 0L
        val timeDisplay = if (dur > 0 && dur < Long.MAX_VALUE) {
            formatTime(pos.toInt()) + " / " + formatTime(dur.toInt())
        } else ""

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            // No MediaStyle — standard notification always renders progress + subText in expanded view
            // Actions: 0=play/pause, 1=next, 2=prev
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) getString(R.string.mini_player_pause_content_desc) else getString(R.string.mini_player_play_content_desc),
                togglePendingIntent()
            )
            .addAction(R.drawable.ic_skip_next, "Skip", skipNextPendingIntent())
            .addAction(R.drawable.ic_skip_previous, "Previous", skipPrevPendingIntent())
            .setDeleteIntent(stopPendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // Add seekbar + time for expanded notification
        if (dur > 0 && dur < Long.MAX_VALUE) {
            builder.setProgress(dur.toInt(), pos.toInt(), false)
        }
        // Show time display as subText (visible in expanded standard notifications)
        if (timeDisplay.isNotEmpty()) {
            builder.setSubText(timeDisplay)
        }

        return builder.build()
    }

    private fun updateNotification() {
        if (player != null) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun showErrorNotification(message: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_network)
            .setContentTitle(getString(R.string.notification_error_network))
            .setContentText(message)
            .setAutoCancel(true)
            .setTimeoutAfter(5000)
        notificationManager.notify(NOTIFICATION_ID + 1, builder.build())
    }

    // ── Pending Intents for Notification Actions ────────────────────

    private fun togglePendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this, 1,
            Intent(this, UFMPlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun skipNextPendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this, 2,
            Intent(this, UFMPlaybackService::class.java).setAction(ACTION_SKIP_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun skipPrevPendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this, 3,
            Intent(this, UFMPlaybackService::class.java).setAction(ACTION_SKIP_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this, 4,
            Intent(this, UFMPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── Intent Handling ─────────────────────────────────────────────

    private fun handlePlayAction(intent: Intent) {
        val initialPath = intent.getStringExtra("initialPath") ?: intent.getStringExtra("extra_file_path") ?: run {
            GoRoLog.e("UFMPlaybackService", "handlePlayAction: no initialPath or extra_file_path in intent")
            return
        }
        // Prefer cache written by UFMPlayerActivity (avoids TransactionTooLargeException)
        val serviceCacheKey = intent.getStringExtra("serviceCacheKey") ?: ""
        val cachedList = PlaylistCache.take(serviceCacheKey)
        val legacyList = intent.getStringArrayListExtra("playlist")
        val playlistFinal: ArrayList<String> = when {
            cachedList != null -> ArrayList(cachedList)
            legacyList != null -> legacyList
            else -> arrayListOf(initialPath)
        }
        val startIndex = playlistFinal.indexOf(initialPath).coerceAtLeast(0)

        startPlayback(
            paths = playlistFinal,
            startIndex = startIndex,
            shareId = intent.getStringExtra("shareId"),
            shareHost = intent.getStringExtra("shareHost"),
            shareUsername = intent.getStringExtra("shareUsername"),
            shareName = intent.getStringExtra("shareName"),
            provider = intent.getStringExtra("provider"),
            remotePath = intent.getStringExtra(NetworkBrowserActivity.EXTRA_REMOTE_PATH),
            fileSize = intent.getLongExtra("initialSize", 0L),
            isServerMode = intent.getBooleanExtra("isServerMode", false)
        )
    }

    private fun handleStopAction() {
        PlayerStateManager.clearState(this)
        queueManager.clear()
        stopSelf()
    }

    private fun serviceAlreadyStarted(): Boolean {
        return player != null
    }

    // ── Metadata Extraction ─────────────────────────────────────────

    private fun extractMetadata(item: QueueItem) {
        metadataThread?.interrupt()
        metadataThread = Thread {
            // Check if cancelled before starting
            if (Thread.currentThread().isInterrupted) return@Thread
            var retriever: MediaMetadataRetriever? = null
            try {
                if (Thread.currentThread().isInterrupted) return@Thread
                retriever = MediaMetadataRetriever()
                val isAudio = FileViewerRouter.isAudio(item.path.substringAfterLast('.'))

                if (item.shareId != null && networkShare != null) {
                    // Network file
                    if (networkShare?.type == ShareType.GOOGLE_DRIVE || networkShare?.type == ShareType.ONEDRIVE) {
                        val (url, token) = if (networkShare?.type == ShareType.GOOGLE_DRIVE) {
                            GoogleDriveShareClient.getStreamingUrlAndTokenSync(networkShare!!, item.path)
                        } else {
                            OnedriveShareClient.getStreamingUrlAndTokenSync(networkShare!!, item.path)
                        }
                        retriever.setDataSource(url, mapOf("Authorization" to "Bearer $token"))
                    } else {
                        val randomAccessFile = buildRandomAccessFile(networkShare!!, item.path) ?: return@Thread
                        retriever.setDataSource(CommonMediaDataSource(randomAccessFile))
                    }
                } else {
                    retriever.setDataSource(item.path)
                }

                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)

                val builder = MediaMetadata.Builder()
                title?.let { builder.setTitle(it) }
                artist?.let { builder.setArtist(it) }
                album?.let { builder.setAlbumTitle(it) }

                val metadata = builder.build()

                // Update queue item metadata in-place
                val updatedItems = queueManager.queue.toMutableList()
                val idx = queueManager.currentIndex
                if (idx in updatedItems.indices) {
                    updatedItems[idx] = updatedItems[idx].copy(
                        title = title ?: updatedItems[idx].title,
                        artist = artist ?: updatedItems[idx].artist,
                        album = album ?: updatedItems[idx].album
                    )
                    queueManager.setQueue(updatedItems, idx)
                }

                playbackCallback?.onMetadataChanged(metadata)
                // Must update notification on main thread — ExoPlayer enforces thread checks
                handler.post { updateNotification() }

            } catch (e: Exception) {
                GoRoLog.d("UFMPlaybackService", "Metadata extraction skipped (non-fatal): ${e.message?.take(80)}")
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }
        }.also { t -> t.name = "metadata-extract"; t.start() }
    }

    // ── Network Share Helpers ───────────────────────────────────────

    private fun buildNetworkShare(
        shareId: String?,
        shareHost: String?,
        shareUsername: String?,
        shareName: String?,
        provider: String,
        remotePathFromIntent: String? = null,
        isServerMode: Boolean = false
    ): NetworkShare? {
        // First try to look up the full share from the repository (includes password, port, domain, etc.)
        if (!shareId.isNullOrEmpty()) {
            val repo = NetworkShareRepository.getInstance(this)
            val allShares = repo.getAll()
            val repoShare = allShares.firstOrNull { it.id == shareId }
            if (repoShare != null) {
                // Server-mode shares: the browser's in-memory remotePath reflects the user's current
                // navigation (e.g. "PrivateDL"), but the stored value may be empty. Override it.
                val resolved = if (repoShare.isServerMode && !remotePathFromIntent.isNullOrEmpty()) {
                    repoShare.copy(remotePath = remotePathFromIntent)
                } else repoShare
                return resolved
            }

            // Also check OnlineStorageRepository
            val onlineRepo = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(this)
            val online = onlineRepo.getById(shareId)
            if (online != null) {
                val mappedShare = NetworkShare(
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
                return mappedShare
            }
        }

        // Fallback: create from extras
        val type = try { ShareType.valueOf(provider) } catch (_: Exception) {
            return null
        }

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

    /**
     * Strips the share-name prefix from [path] for server-mode SMB shares.
     * In server-mode, [share.remotePath] already encodes the share name (e.g. "/MM"),
     * and the file path includes it as a leading segment too. This matches the
     * [stripSharePrefix] logic in NetworkBrowserActivity to avoid duplicate path segments.
     */
    private fun stripSharePrefix(share: NetworkShare, path: String): String {
        // Server-mode SMB shares encode the share name in remotePath (e.g. "/MM").
        // File paths include this as a leading segment — strip it to avoid
        // duplication when splitSharePath extracts the shareName.
        if (!share.isServerMode || share.remotePath.isEmpty()) return path
        val prefix = share.remotePath.trimStart('/')
        val clean = path.trimStart('/')
        return when {
            clean.startsWith("$prefix/") -> clean.removePrefix("$prefix/")
            clean == prefix              -> ""
            else                         -> clean
        }
    }

    private fun buildRandomAccessFile(share: NetworkShare, path: String): IRandomAccessFile? {
        return try {
            when (share.type) {
                ShareType.SMB -> SmbShareClient.openRandomAccessFile(share, stripSharePrefix(share, path))
                ShareType.FTP -> FtpShareClient.openRandomAccessFile(share, path)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.openRandomAccessFile(share, path)
                ShareType.NFS -> NfsShareClient.openRandomAccessFile(share, path)
                ShareType.DLNA -> DlnaShareClient.openRandomAccessFile(share, path)
                ShareType.WEBDAV -> WebDavShareClient.openRandomAccessFile(share, path)
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openRandomAccessFile(share, path)
                ShareType.ONEDRIVE -> OnedriveShareClient.openRandomAccessFile(share, path)
                ShareType.DROPBOX -> DropboxShareClient.openRandomAccessFile(share, path)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openRandomAccessFile(share, path)
                else -> null
            }
        } catch (e: Exception) {
            GoRoLog.e("UFMPlaybackService", "Failed to open remote file: $path", e)
            null
        }
    }

    // ── Headset Plug Detection ──────────────────────────────────────

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    private fun registerHeadsetPlugReceiver() {
        registerReceiver(headsetReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    private fun unregisterHeadsetPlugReceiver() {
        try { unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun formatTime(ms: Int): String {
        if (ms < 0) return "0:00"
        var s = ms / 1000
        val m = s / 60
        s %= 60
        return String.format("%d:%02d", m, s)
    }

    // ── Periodic notification progress updater ─────────────────────

    private val notificationUpdater = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true) {
                startForeground(NOTIFICATION_ID, buildNotification())
                handler.postDelayed(this, 1000) // Update every 1 second
            }
        }
    }

    /** Start the periodic notification progress updates. */
    private fun startNotificationUpdates() {
        handler.removeCallbacks(notificationUpdater)
        handler.postDelayed(notificationUpdater, 5000)
    }

    /** Stop the periodic notification progress updates. */
    private fun stopNotificationUpdates() {
        handler.removeCallbacks(notificationUpdater)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
}
