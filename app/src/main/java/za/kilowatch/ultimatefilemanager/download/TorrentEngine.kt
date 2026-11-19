package za.kilowatch.ultimatefilemanager.download

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.ListenFailedAlert
import org.libtorrent4j.alerts.ListenSucceededAlert
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.PeerBanAlert
import org.libtorrent4j.alerts.PeerBlockedAlert
import org.libtorrent4j.alerts.PeerConnectAlert
import org.libtorrent4j.alerts.PeerDisconnectedAlert
import org.libtorrent4j.alerts.PeerErrorAlert
import org.libtorrent4j.alerts.StateChangedAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentCheckedAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.alerts.TorrentLogAlert
import org.libtorrent4j.alerts.TrackerAnnounceAlert
import org.libtorrent4j.alerts.TrackerErrorAlert
import org.libtorrent4j.alerts.TrackerReplyAlert
import org.libtorrent4j.alerts.TrackerWarningAlert
import org.libtorrent4j.swig.alert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * High-performance, privacy-hardened native BitTorrent engine based on libtorrent4j.
 * Strictly enforces ZERO-SEEDING:
 * - active_seeds = 0
 * - seeding_outgoing_connections = false
 * - Immediate post-download swarm ejection (pause handle, sever peer sockets, remove from session)
 */
class TorrentEngine(private val context: Context) {

    private val sessionManager = SessionManager(false)
    private val activeHashes = ConcurrentHashMap<String, Sha1Hash>()
    private val finishingHashes = ConcurrentHashMap.newKeySet<String>()
    private val sessionLock = Any()
    private val lastReannounceTimes = ConcurrentHashMap<String, Long>()
    private val torrentTrackers = ConcurrentHashMap<String, List<String>>()
    private val torrentFilePriorities = ConcurrentHashMap<String, Array<Priority>>()
    private val magnetFetchExecutor = Executors.newSingleThreadExecutor()
    private var progressScheduler: ScheduledExecutorService? = null

    interface TorrentListener {
        fun onTorrentAdded(id: String, name: String, totalBytes: Long)
        fun onTorrentProgress(
            id: String,
            progressPercent: Int,
            downloadSpeedBytesPerSec: Long,
            etaSeconds: Long,
            downloadedBytes: Long,
            connectedPeers: Int,
            totalPeers: Int,
            connectedSeeds: Int
        )
        fun onTorrentFinished(id: String, saveDir: File, fileNames: List<String>)
        fun onTorrentError(id: String, error: String)
    }

    var listener: TorrentListener? = null

    init {
        setupAlertListener()
        val sp = createSettingsPack()
        val params = SessionParams(sp)
        sessionManager.start(params)
        // Also re-apply to ensure runtime session picks up non-constructor params
        try {
            sessionManager.applySettings(sp)
        } catch (e: Exception) {
            Log.w(TAG, "Error applying initial settings pack", e)
        }
        try {
            sessionManager.startDht()
        } catch (e: Exception) {
            Log.w(TAG, "Error starting DHT", e)
        }
        startProgressLoop()
        Log.i(TAG, "libtorrent4j SessionManager started with DHT, tracker SSL bypass, and zero-seeding configuration.")
    }

    private fun createSettingsPack(): SettingsPack {
        val sp = SettingsPack()

        // 1. Enable DHT, LSD, and UPnP for robust magnet peer and metadata discovery
        sp.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)

        // 2. Encryption (PE): Allow both plain and encrypted incoming/outgoing
        sp.setInteger(settings_pack.int_types.out_enc_policy.swigValue(), settings_pack.enc_policy.pe_enabled.swigValue())
        sp.setInteger(settings_pack.int_types.in_enc_policy.swigValue(), settings_pack.enc_policy.pe_enabled.swigValue())
        sp.setInteger(settings_pack.int_types.allowed_enc_level.swigValue(), settings_pack.enc_level.pe_both.swigValue())

        // 3. Zero-seeding: active_seeds = 0 so torrents never enter or remain in seeding state
        sp.activeSeeds(0)
        sp.seedingOutgoingConnections(false)

        // 4. Concurrency limits & active downloads (hardened against native heap exhaustion on low-memory TV devices)
        sp.connectionsLimit(200)
        sp.activeDownloads(20)
        sp.activeLimit(30)
        sp.setInteger(settings_pack.int_types.active_tracker_limit.swigValue(), 100)

        // 5. TCP & uTP protocols enabled
        sp.setBoolean(settings_pack.bool_types.enable_incoming_tcp.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_outgoing_tcp.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)

        // 6. Private Trackers & HTTPS SSL Fix for Android:
        // Android lacks desktop /etc/ssl/certs/ca-certificates.crt, which causes native OpenSSL in libtorrent
        // to reject HTTPS private tracker connections unless validate_https_trackers is disabled.
        sp.setBoolean(settings_pack.bool_types.validate_https_trackers.swigValue(), false)
        sp.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.prefer_udp_trackers.swigValue(), false)

        // 7. Client identification: Private trackers whitelist standard clients (e.g. qBittorrent 4.6.5)
        sp.setString(settings_pack.string_types.user_agent.swigValue(), "qBittorrent/4.6.5")
        sp.setString(settings_pack.string_types.peer_fingerprint.swigValue(), "-qB4650-")
        sp.setString(settings_pack.string_types.handshake_client_version.swigValue(), "qBittorrent/4.6.5")

        // 8. Listen interfaces: bind to standard port range on IPv4 (avoid dead-end IPv6 routes on mobile/cellular)
        sp.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:6881-6889,0.0.0.0:0")

        // 9. Alert mask: Only subscribe to critical state, error, tracker, storage, and peer alerts.
        // DO NOT enable all_categories, which generates thousands of internal dht/picker/session log alerts per second
        // causing Scudo chunk header race aborts on 32-bit Android ARM devices.
        val alertMask = alert.error_notification
            .or_(alert.status_notification)
            .or_(alert.tracker_notification)
            .or_(alert.storage_notification)
            .or_(alert.peer_notification)
            .or_(alert.connect_notification)
            .to_int()
        sp.setInteger(settings_pack.int_types.alert_mask.swigValue(), alertMask)

        return sp
    }

    private fun setupAlertListener() {
        val targetAlertTypes = intArrayOf(
            AlertType.TORRENT_FINISHED.swig(),
            AlertType.TORRENT_REMOVED.swig(),
            AlertType.TORRENT_DELETED.swig(),
            AlertType.TORRENT_PAUSED.swig(),
            AlertType.TORRENT_RESUMED.swig(),
            AlertType.TORRENT_CHECKED.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.ADD_TORRENT.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.METADATA_FAILED.swig(),
            AlertType.STATE_CHANGED.swig(),
            AlertType.TRACKER_ANNOUNCE.swig(),
            AlertType.TRACKER_REPLY.swig(),
            AlertType.TRACKER_WARNING.swig(),
            AlertType.TRACKER_ERROR.swig(),
            AlertType.LISTEN_SUCCEEDED.swig(),
            AlertType.LISTEN_FAILED.swig(),
            AlertType.PEER_CONNECT.swig(),
            AlertType.PEER_DISCONNECTED.swig(),
            AlertType.PEER_ERROR.swig(),
            AlertType.PEER_BLOCKED.swig(),
            AlertType.PEER_BAN.swig()
        )

        sessionManager.addListener(object : AlertListener {
            override fun types(): IntArray? {
                // Register only for explicitly handled alerts, avoiding thousands of noisy internal log alerts
                return targetAlertTypes
            }

            override fun alert(alert: Alert<*>) {
                when (alert) {
                    is TorrentFinishedAlert -> {
                        val handle = alert.handle()
                        if (handle != null && runCatching { handle.isValid }.getOrDefault(false)) {
                            handleTorrentFinished(handle)
                        }
                    }
                    is TrackerErrorAlert -> {
                        val errObj = alert.error()
                        val errMsg = alert.errorMessage().ifBlank { errObj?.toString() ?: "unknown" }
                        Log.w(TAG, "Tracker error [${alert.trackerUrl().safeJniUrl()}]: error='$errMsg' code=${alert.error()}")
                    }
                    is TrackerWarningAlert -> {
                        Log.w(TAG, "Tracker warning [${alert.trackerUrl().safeJniUrl()}]: ${alert.warningMessage()}")
                    }
                    is TrackerReplyAlert -> {
                        Log.i(TAG, "Tracker reply [${alert.trackerUrl().safeJniUrl()}]: received ${alert.numPeers()} peers")
                    }
                    is TrackerAnnounceAlert -> {
                        Log.i(TAG, "Announcing to tracker: ${alert.trackerUrl().safeJniUrl()} (event=${alert.getEvent()})")
                    }
                    is PeerConnectAlert -> {
                        Log.i(TAG, "Peer connected: ${alert.endpoint()} (dir=${alert.direction()}, type=${alert.socketType()})")
                    }
                    is PeerDisconnectedAlert -> {
                        Log.w(TAG, "Peer disconnected: ${alert.endpoint()} op=${alert.operation()} err=${alert.error()} reason=${alert.reason()}")
                    }
                    is PeerErrorAlert -> {
                        Log.w(TAG, "Peer error: ${alert.endpoint()} op=${alert.operation()} err=${alert.error()}")
                    }
                    is PeerBlockedAlert -> {
                        Log.w(TAG, "Peer blocked: ${alert.reason()}")
                    }
                    is PeerBanAlert -> {
                        Log.w(TAG, "Peer banned: ${alert.endpoint()}")
                    }
                    is ListenFailedAlert -> {
                        Log.w(TAG, "Listen failed: interface=${alert.listenInterface()}, address=${alert.address()}:${alert.port()}, error=${alert.error()}")
                    }
                    is ListenSucceededAlert -> {
                        Log.i(TAG, "Listen succeeded on: ${alert.address()}:${alert.port()} (${alert.socketType()})")
                    }
                    is TorrentCheckedAlert -> {
                        val handle = alert.handle()
                        if (handle != null && runCatching { handle.isValid }.getOrDefault(false)) {
                            injectTrackersIfPresent(handle)
                            runCatching {
                                handle.unsetFlags(TorrentFlags.PAUSED)
                                handle.resume()
                            }
                            val trackerCount = runCatching { handle.trackers().size }.getOrDefault(0)
                            Log.i(TAG, "TorrentCheckedAlert: hash checked, unpaused ${handle.infoHash().toHex()} (trackers=$trackerCount)")
                        }
                    }
                    is StateChangedAlert -> {
                        val handle = alert.handle()
                        val name = handle?.torrentFile()?.name() ?: handle?.infoHash()?.toHex().orEmpty()
                        Log.d(TAG, "State changed [$name]: ${alert.prevState} -> ${alert.state}")
                        if (alert.state == org.libtorrent4j.TorrentStatus.State.DOWNLOADING && handle != null) {
                            injectTrackersIfPresent(handle)
                            runCatching {
                                handle.unsetFlags(TorrentFlags.PAUSED)
                                handle.resume()
                            }
                        }
                    }
                    is AddTorrentAlert -> {
                        val handle = alert.handle()
                        if (handle != null && runCatching { handle.isValid }.getOrDefault(false)) {
                            val sha1 = handle.infoHash()
                            val id = sha1.toHex()
                            activeHashes[id] = sha1
                            injectTrackersIfPresent(handle)
                            runCatching {
                                handle.unsetFlags(TorrentFlags.PAUSED)
                                handle.resume()
                            }
                            val trackerCount = runCatching { handle.trackers().size }.getOrDefault(0)
                            Log.i(TAG, "AddTorrentAlert: unpaused, resumed, trackers injected ($trackerCount) for $id")
                        }
                    }
                    is MetadataReceivedAlert -> {
                        val handle = alert.handle()
                        if (handle != null && runCatching { handle.isValid }.getOrDefault(false)) {
                            val sha1 = handle.infoHash()
                            val id = sha1.toHex()
                            activeHashes[id] = sha1
                            runCatching {
                                handle.unsetFlags(TorrentFlags.PAUSED)
                                handle.resume()
                            }
                            val info = runCatching { handle.torrentFile() }.getOrNull()
                            val name = info?.name() ?: id
                            val total = info?.totalSize() ?: runCatching { handle.status().totalWanted() }.getOrDefault(0L)
                            listener?.onTorrentAdded(id, name, total)
                        }
                    }
                    is TorrentErrorAlert -> {
                        val handle = alert.handle()
                        val name = handle?.torrentFile()?.name() ?: handle?.infoHash()?.toHex().orEmpty()
                        Log.e(TAG, "Torrent error [$name]: error=${alert.error()} msg=${alert.message()}")
                    }
                    is TorrentAlert<*> -> {
                        val handle = alert.handle()
                        if (handle != null && runCatching { handle.isValid }.getOrDefault(false)) {
                            val sha1 = handle.infoHash()
                            val id = sha1.toHex()
                            activeHashes[id] = sha1
                        }
                    }
                }
            }
        })
    }

    private fun startProgressLoop() {
        progressScheduler = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({
                try {
                    updateProgress()
                } catch (e: Exception) {
                    Log.w(TAG, "Error in progress loop", e)
                }
            }, 1000, 1000, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Called the millisecond a download reaches 100% completion.
     * Enforces immediate swarm disconnection:
     * - Pauses the torrent handle
     * - Ejects from session (ceases seeding, cuts peer sockets, deregisters DHT)
     * - Triggers MediaScanner scan so files are instantly visible in UFM & system
     */
    private fun handleTorrentFinished(handle: TorrentHandle) {
        val id = runCatching { handle.infoHash().toHex() }.getOrNull() ?: return
        if (!finishingHashes.add(id)) {
            // Already being handled / finalized by another thread
            return
        }
        activeHashes.remove(id)
        lastReannounceTimes.remove(id)
        torrentTrackers.remove(id)
        val priorities = torrentFilePriorities.remove(id)
        val torrentInfo = runCatching { handle.torrentFile() }.getOrNull()
        val saveDir = File(runCatching { handle.savePath() }.getOrDefault(""))

        try {
            // Immediate pause and peer ejection
            runCatching { handle.pause() }
            runCatching { handle.unsetFlags(TorrentFlags.AUTO_MANAGED) }
            synchronized(sessionLock) {
                runCatching { sessionManager.remove(handle) } // Completely drop handle from active session so IP is never announced
            }
            Log.i(TAG, "Torrent $id finished. Immediately severed peer sockets and ejected from swarm.")

            val downloadedFiles = mutableListOf<String>()
            if (torrentInfo != null) {
                val numFiles = torrentInfo.numFiles()
                val pathsToScan = mutableListOf<String>()
                for (i in 0 until numFiles) {
                    val relPath = torrentInfo.files().filePath(i)
                    val isIgnored = priorities != null && i < priorities.size && priorities[i] == Priority.IGNORE
                    val fullFile = File(saveDir, relPath)

                    if (isIgnored) {
                        // Clean up zero-byte boundary stub files if created by libtorrent for unselected items
                        if (fullFile.exists() && fullFile.isFile && fullFile.length() == 0L) {
                            runCatching { fullFile.delete() }
                        }
                    } else {
                        downloadedFiles.add(relPath)
                        if (fullFile.exists()) {
                            pathsToScan.add(fullFile.absolutePath)
                        }
                    }
                }

                // Prune any empty subdirectories left behind by deselected files
                runCatching { pruneEmptyDirectories(File(saveDir, torrentInfo.name())) }

                // Index in Android MediaStore
                if (pathsToScan.isNotEmpty()) {
                    MediaScannerConnection.scanFile(
                        context,
                        pathsToScan.toTypedArray(),
                        null
                    ) { path, uri ->
                        Log.d(TAG, "Scanned $path into MediaStore: $uri")
                    }
                }
            }

            listener?.onTorrentFinished(id, saveDir, downloadedFiles)
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing torrent completion for $id", e)
        }
    }

    private fun pruneEmptyDirectories(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                pruneEmptyDirectories(child)
            }
        }
        if (dir.listFiles()?.isEmpty() == true) {
            dir.delete()
        }
    }

    /**
     * Starts downloading from a Magnet URL.
     */
    fun downloadMagnet(magnetUri: String, saveDir: File): String? {
        return try {
            if (!saveDir.exists()) saveDir.mkdirs()
            val ec = error_code()
            val p = libtorrent.parse_magnet_uri(magnetUri, ec)
            val infoHashes = p.info_hashes
            val sha1 = if (infoHashes != null && infoHashes.has_v1()) {
                org.libtorrent4j.Sha1Hash(infoHashes.v1)
            } else if (infoHashes != null) {
                org.libtorrent4j.Sha1Hash(infoHashes.get_best())
            } else {
                null
            }

            // Append reliable public fallback trackers if magnet has none
            val effectiveMagnet = if (!magnetUri.contains("tr=")) {
                "$magnetUri&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce"
            } else {
                magnetUri
            }

            val flags = TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPDATE_SUBSCRIBE)
            sessionManager.download(effectiveMagnet, saveDir, flags)
            val id = sha1?.toHex() ?: magnetUri.hashCode().toString()
            if (sha1 != null) {
                activeHashes[id] = sha1
            }
            id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start magnet download: $magnetUri", e)
            null
        }
    }

    /**
     * Starts downloading from a local .torrent file with optional selective file priorities.
     */
    fun downloadTorrentFile(torrentFile: File, saveDir: File, filePriorities: Array<Priority>? = null): String? {
        return try {
            if (!saveDir.exists()) saveDir.mkdirs()
            val torrentInfo = TorrentInfo(torrentFile)
            val sha1 = torrentInfo.infoHash()
            val id = sha1.toHex()

            if (filePriorities != null) {
                torrentFilePriorities[id] = filePriorities
            }

            // Extract tracker URLs from .torrent bencoded file
            val extracted = try {
                parseTrackersFromTorrentBytes(torrentFile.readBytes())
            } catch (e: Exception) {
                emptyList()
            }
            if (extracted.isNotEmpty()) {
                torrentTrackers[id] = extracted
                Log.i(TAG, "Parsed ${extracted.size} trackers for $id: $extracted")
            }

            Log.i(TAG, "Starting torrent file: ${torrentInfo.name()} (isPrivate=${torrentInfo.isPrivate()}, size=${torrentInfo.totalSize()}, selective=${filePriorities != null})")

            val flags = TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPDATE_SUBSCRIBE)
            sessionManager.download(torrentInfo, saveDir, null, filePriorities, null, flags)
            activeHashes[id] = sha1

            val handle = runCatching { sessionManager.find(sha1) }.getOrNull()
            if (handle != null) {
                if (filePriorities != null) {
                    runCatching { handle.prioritizeFiles(filePriorities) }
                }
                injectTrackersIfPresent(handle)
                runCatching {
                    handle.unsetFlags(TorrentFlags.PAUSED)
                    handle.resume()
                    handle.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL)
                }
            }

            listener?.onTorrentAdded(id, torrentInfo.name(), torrentInfo.totalSize())
            id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download torrent file: ${torrentFile.path}", e)
            null
        }
    }

    /**
     * Fetches metadata for a magnet link from the swarm.
     * Caches the bencoded bytes into a temporary .torrent file and invokes [onResult].
     */
    fun fetchMagnetMetadata(
        magnetUri: String,
        timeoutSeconds: Int = 20,
        onResult: (File?) -> Unit
    ) {
        val tempDir = File(context.cacheDir, "torrents").apply { mkdirs() }
        magnetFetchExecutor.execute {
            try {
                // Ensure DHT is active
                try {
                    sessionManager.startDht()
                } catch (e: Exception) {
                    // Ignored if already started
                }

                val effectiveMagnet = if (!magnetUri.contains("tr=")) {
                    "$magnetUri&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce"
                } else {
                    magnetUri
                }

                val bytes = sessionManager.fetchMagnet(effectiveMagnet, timeoutSeconds, tempDir)
                if (bytes != null && bytes.isNotEmpty()) {
                    val tempFile = File(tempDir, "magnet_${System.currentTimeMillis()}.torrent")
                    tempFile.writeBytes(bytes)
                    onResult(tempFile)
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch magnet metadata", e)
                onResult(null)
            }
        }
    }

    fun pause(id: String) {
        val sha1 = activeHashes[id] ?: runCatching { org.libtorrent4j.Sha1Hash.parseHex(id) }.getOrNull()
        if (sha1 != null) {
            runCatching { sessionManager.find(sha1)?.pause() }
        }
    }

    fun resume(id: String) {
        val sha1 = activeHashes[id] ?: runCatching { org.libtorrent4j.Sha1Hash.parseHex(id) }.getOrNull()
        if (sha1 != null) {
            runCatching {
                val handle = sessionManager.find(sha1)
                if (handle != null) {
                    injectTrackersIfPresent(handle)
                    handle.unsetFlags(TorrentFlags.PAUSED)
                    handle.resume()
                    handle.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL)
                }
            }
        }
    }

    fun cancel(id: String, deleteFiles: Boolean = true) {
        finishingHashes.add(id)
        lastReannounceTimes.remove(id)
        torrentTrackers.remove(id)
        torrentFilePriorities.remove(id)
        val sha1 = activeHashes.remove(id) ?: runCatching {
            org.libtorrent4j.Sha1Hash.parseHex(id)
        }.getOrNull()

        if (sha1 != null) {
            val handle = runCatching { sessionManager.find(sha1) }.getOrNull()
            if (handle != null && runCatching { handle.isValid }.getOrDefault(false)) {
                val torrentInfo = runCatching { handle.torrentFile() }.getOrNull()
                val savePath = runCatching { handle.savePath() }.getOrNull()

                runCatching { handle.pause() }
                synchronized(sessionLock) {
                    if (deleteFiles) {
                        runCatching {
                            sessionManager.remove(handle, org.libtorrent4j.swig.session_handle.delete_files)
                        }
                    } else {
                        runCatching { sessionManager.remove(handle) }
                    }
                }

                if (deleteFiles) {
                    if (torrentInfo != null && !savePath.isNullOrBlank()) {
                        try {
                            val saveDir = File(savePath).canonicalFile
                            val numFiles = torrentInfo.numFiles()
                            for (i in 0 until numFiles) {
                                val relPath = torrentInfo.files().filePath(i)
                                val f = File(saveDir, relPath).canonicalFile
                                if (f.toPath().startsWith(saveDir.toPath()) && f.exists()) {
                                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                                }
                            }
                            val rootTorrentDir = File(saveDir, torrentInfo.name()).canonicalFile
                            if (rootTorrentDir.toPath().startsWith(saveDir.toPath()) && rootTorrentDir.exists() && rootTorrentDir.isDirectory) {
                                rootTorrentDir.deleteRecursively()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed explicit disk cleanup during torrent cancellation", e)
                        }
                    }
                }
            }
        }
    }

    fun updateProgress() {
        val now = System.currentTimeMillis()
        for ((id, sha1) in activeHashes) {
            if (finishingHashes.contains(id)) continue
            // Wrap the entire per-handle block so a native exception from one handle never
            // disrupts the progress loop for all other active torrents.
            runCatching {
                val handle = sessionManager.find(sha1) ?: return@runCatching
                // Re-check validity immediately before every native call to close the TOCTOU window
                // between the initial isValid check and subsequent handle.status() / handle.trackers()
                // calls.  libtorrent's internal alert/RTC threads can invalidate a handle at any point.
                if (!handle.isValid || finishingHashes.contains(id)) return@runCatching

                val status = handle.status()

                // Re-validate after status() — the handle may have been invalidated during the call
                if (!handle.isValid || finishingHashes.contains(id)) return@runCatching

                if (status.isFinished || status.isSeeding || status.progress() >= 1.0f) {
                    handleTorrentFinished(handle)
                    return@runCatching
                }

                val progress = (status.progress() * 100).toInt()
                val downloadRate = status.downloadRate().toLong()

                // Calculate ETA
                val totalBytes = status.totalWanted()
                val doneBytes = status.totalWantedDone()
                val remainingBytes = if (totalBytes > doneBytes) totalBytes - doneBytes else 0L
                val eta = if (downloadRate > 0) remainingBytes / downloadRate else 0L

                val connectedPeers = runCatching { status.numPeers() }.getOrDefault(0)
                val totalPeers = runCatching { status.listPeers() }.getOrDefault(0)
                val connectedSeeds = runCatching { status.numSeeds() }.getOrDefault(0)

                // Safe periodic reannounce ONLY when 0 peers are in swarm after 3 minutes (prevents tracker hammering bans)
                if (status.state() == org.libtorrent4j.TorrentStatus.State.DOWNLOADING && connectedPeers == 0 && totalPeers == 0 && !finishingHashes.contains(id)) {
                    val lastTime = lastReannounceTimes[id] ?: 0L
                    if (now - lastTime > 180_000L) {
                        lastReannounceTimes[id] = now
                        runCatching {
                            handle.unsetFlags(TorrentFlags.PAUSED)
                            handle.resume()
                            handle.forceReannounce()
                            Log.i(TAG, "Periodic reannounce triggered for $id")
                        }
                    }
                }

                // NOTE: onTorrentAdded is intentionally NOT called here. It is fired once,
                // reliably, inside MetadataReceivedAlert on libtorrent's own thread — avoiding
                // any JNI race from reading handle.torrentFile() on a pool thread.

                if (!finishingHashes.contains(id)) {
                    listener?.onTorrentProgress(
                        id,
                        progress,
                        downloadRate,
                        eta,
                        doneBytes,
                        connectedPeers,
                        totalPeers,
                        connectedSeeds
                    )
                }
            }.onFailure { e ->
                Log.w(TAG, "updateProgress: skipping $id — native error reading handle state", e)
            }
        }
    }

    fun stop() {
        try {
            progressScheduler?.shutdownNow()
            synchronized(sessionLock) {
                sessionManager.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SessionManager", e)
        }
    }

    private fun injectTrackersIfPresent(handle: TorrentHandle) {
        val id = handle.infoHash().toHex()
        val trackers = torrentTrackers[id] ?: emptyList()
        // Read existing tracker URLs via JNI — sanitise each one through safeJniUrl() to prevent
        // NewStringUTF from aborting the process when libtorrent returns non-UTF-8 announce URLs.
        val existing = runCatching {
            handle.trackers().mapNotNull { entry ->
                runCatching { entry.url().safeJniUrl() }.getOrNull()
            }
        }.getOrDefault(emptyList())
        for (tUrl in trackers) {
            if (!existing.contains(tUrl)) {
                runCatching {
                    handle.addTracker(org.libtorrent4j.AnnounceEntry(tUrl))
                    Log.i(TAG, "Injected tracker to handle [$id]: $tUrl")
                }
            }
        }
    }

    private fun parseTrackersFromTorrentBytes(data: ByteArray): List<String> {
        val trackers = mutableListOf<String>()
        try {
            // 1. announce
            val annKey = "8:announce".toByteArray(Charsets.US_ASCII)
            val annIdx = indexOfBytes(data, annKey)
            if (annIdx != -1) {
                val start = annIdx + annKey.size
                val colon = indexOfByte(data, ':'.code.toByte(), start)
                if (colon != -1) {
                    val lenStr = String(data, start, colon - start, Charsets.US_ASCII)
                    val len = lenStr.toIntOrNull()
                    if (len != null && len > 0 && colon + 1 + len <= data.size) {
                        val url = String(data, colon + 1, len, Charsets.UTF_8).trim()
                        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("udp://") || url.startsWith("wss://")) {
                            trackers.add(url)
                        }
                    }
                }
            }

            // 2. announce-list
            val listKey = "13:announce-list".toByteArray(Charsets.US_ASCII)
            val listIdx = indexOfBytes(data, listKey)
            if (listIdx != -1) {
                var pos = listIdx + listKey.size
                while (pos < data.size && data[pos] != 'e'.code.toByte()) {
                    if (data[pos] == 'l'.code.toByte()) {
                        pos++
                        continue
                    }
                    val colon = indexOfByte(data, ':'.code.toByte(), pos)
                    if (colon != -1 && (colon - pos) in 1..5) {
                        val lenStr = String(data, pos, colon - pos, Charsets.US_ASCII)
                        val len = lenStr.toIntOrNull()
                        if (len != null && len > 0 && colon + 1 + len <= data.size) {
                            val url = String(data, colon + 1, len, Charsets.UTF_8).trim()
                            if ((url.startsWith("http://") || url.startsWith("https://") || url.startsWith("udp://") || url.startsWith("wss://")) && !trackers.contains(url)) {
                                trackers.add(url)
                            }
                            pos = colon + 1 + len
                            continue
                        }
                    }
                    pos++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting trackers from torrent bytes", e)
        }
        return trackers
    }

    private fun indexOfBytes(source: ByteArray, target: ByteArray, fromIndex: Int = 0): Int {
        if (target.isEmpty() || fromIndex >= source.size) return -1
        for (i in fromIndex..(source.size - target.size)) {
            var found = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun indexOfByte(source: ByteArray, target: Byte, fromIndex: Int = 0): Int {
        for (i in fromIndex until source.size) {
            if (source[i] == target) return i
        }
        return -1
    }

    companion object {
        private const val TAG = "TorrentEngine"

        /**
         * Sanitises a raw C-string returned by libtorrent's JNI layer (e.g. announce_entry_url_get)
         * before it is handed to NewStringUTF.  libtorrent stores tracker URLs as std::string with
         * no guaranteed encoding; non-UTF-8 bytes cause CheckJNI to SIGABRT the process.
         *
         * Strategy:
         *  1. Attempt strict UTF-8 decode — fast path for the 99 % case.
         *  2. Fall back to ISO-8859-1 → UTF-8 re-encode, which is lossless for byte values and
         *     always produces a valid UTF-8 string (at the cost of some mojibake in the URL, which
         *     is still far better than a native crash).
         */
        fun String?.safeJniUrl(): String {
            if (this == null) return ""
            return try {
                val bytes = this.toByteArray(Charsets.ISO_8859_1)
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: Exception) {
                // Last resort: strip every byte outside printable ASCII so the URL is at least safe
                this.filter { it.code in 32..126 }
            }
        }
    }
}
