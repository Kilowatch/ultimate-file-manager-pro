package za.kilowatch.ultimatefilemanager.download

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import org.libtorrent4j.AlertListener
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

    private val sessionManager = SessionManager(true)
    private val activeHashes = ConcurrentHashMap<String, Sha1Hash>()
    private val lastReannounceTimes = ConcurrentHashMap<String, Long>()
    private val torrentTrackers = ConcurrentHashMap<String, List<String>>()
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

        // 4. Concurrency limits & unconstrained active downloads (removes 5-item limit)
        sp.connectionsLimit(300)
        sp.activeDownloads(9999)
        sp.activeLimit(9999)
        sp.setInteger(settings_pack.int_types.active_tracker_limit.swigValue(), 9999)

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

        // 9. Alert mask: Ensure all tracker, peer, error, and status alerts are received
        sp.setInteger(settings_pack.int_types.alert_mask.swigValue(), alert.all_categories.to_int())

        return sp
    }

    private fun setupAlertListener() {
        sessionManager.addListener(object : AlertListener {
            override fun types(): IntArray? {
                // Register for all alert types so no tracker, peer, or session events are missed
                return IntArray(org.libtorrent4j.alerts.Alerts.NUM_ALERT_TYPES) { it }
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
                        Log.w(TAG, "Tracker error [${alert.trackerUrl()}]: error='$errMsg' code=${alert.error()}")
                    }
                    is TrackerWarningAlert -> {
                        Log.w(TAG, "Tracker warning [${alert.trackerUrl()}]: ${alert.warningMessage()}")
                    }
                    is TrackerReplyAlert -> {
                        Log.i(TAG, "Tracker reply [${alert.trackerUrl()}]: received ${alert.numPeers()} peers")
                    }
                    is TrackerAnnounceAlert -> {
                        Log.i(TAG, "Announcing to tracker: ${alert.trackerUrl()} (event=${alert.getEvent()})")
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
                            Log.i(TAG, "TorrentCheckedAlert: hash checked, unpaused ${handle.infoHash().toHex()} (trackers=${handle.trackers().size})")
                        }
                    }
                    is StateChangedAlert -> {
                        val handle = alert.handle()
                        val name = handle?.torrentFile()?.name() ?: runCatching { handle?.getName() }.getOrDefault("")
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
                            Log.i(TAG, "AddTorrentAlert: unpaused, resumed, trackers injected (${handle.trackers().size}) for $id")
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
                            val name = info?.name() ?: runCatching { handle.getName() }.getOrDefault("")
                            val total = info?.totalSize() ?: runCatching { handle.status().totalWanted() }.getOrDefault(0L)
                            listener?.onTorrentAdded(id, name, total)
                        }
                    }
                    is TorrentErrorAlert -> {
                        val handle = alert.handle()
                        val name = handle?.torrentFile()?.name() ?: runCatching { handle?.getName() }.getOrDefault("")
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
        val id = handle.infoHash().toHex()
        lastReannounceTimes.remove(id)
        torrentTrackers.remove(id)
        val torrentInfo = runCatching { handle.torrentFile() }.getOrNull()
        val saveDir = File(runCatching { handle.savePath() }.getOrDefault(""))

        try {
            // Immediate pause and peer ejection
            handle.pause()
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            sessionManager.remove(handle) // Completely drop handle from active session so IP is never announced
            activeHashes.remove(id)
            Log.i(TAG, "Torrent $id finished. Immediately severed peer sockets and ejected from swarm.")

            val downloadedFiles = mutableListOf<String>()
            if (torrentInfo != null) {
                val numFiles = torrentInfo.numFiles()
                val pathsToScan = mutableListOf<String>()
                for (i in 0 until numFiles) {
                    val relPath = torrentInfo.files().filePath(i)
                    downloadedFiles.add(relPath)
                    val fullFile = File(saveDir, relPath)
                    if (fullFile.exists()) {
                        pathsToScan.add(fullFile.absolutePath)
                    }
                }

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
     * Starts downloading from a local .torrent file.
     */
    fun downloadTorrentFile(torrentFile: File, saveDir: File): String? {
        return try {
            if (!saveDir.exists()) saveDir.mkdirs()
            val torrentInfo = TorrentInfo(torrentFile)
            val sha1 = torrentInfo.infoHash()
            val id = sha1.toHex()

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

            Log.i(TAG, "Starting torrent file: ${torrentInfo.name()} (isPrivate=${torrentInfo.isPrivate()}, size=${torrentInfo.totalSize()})")

            val flags = TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPDATE_SUBSCRIBE)
            sessionManager.download(torrentInfo, saveDir, null, null, null, flags)
            activeHashes[id] = sha1

            val handle = runCatching { sessionManager.find(sha1) }.getOrNull()
            if (handle != null) {
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
        lastReannounceTimes.remove(id)
        torrentTrackers.remove(id)
        val sha1 = activeHashes.remove(id) ?: runCatching {
            org.libtorrent4j.Sha1Hash.parseHex(id)
        }.getOrNull()

        if (sha1 != null) {
            val handle = runCatching { sessionManager.find(sha1) }.getOrNull()
            if (handle != null && runCatching { handle.isValid }.getOrDefault(false)) {
                val torrentInfo = runCatching { handle.torrentFile() }.getOrNull()
                val savePath = runCatching { handle.savePath() }.getOrNull()

                if (deleteFiles) {
                    runCatching {
                        sessionManager.remove(handle, org.libtorrent4j.swig.session_handle.delete_files)
                    }
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
                } else {
                    runCatching { sessionManager.remove(handle) }
                }
            }
        }
    }

    fun updateProgress() {
        val now = System.currentTimeMillis()
        for ((id, sha1) in activeHashes) {
            val handle = runCatching { sessionManager.find(sha1) }.getOrNull() ?: continue
            val isValid = runCatching { handle.isValid }.getOrDefault(false)
            if (!isValid) continue

            val status = runCatching { handle.status() }.getOrNull() ?: continue

            if (status.isFinished || status.isSeeding || status.progress() >= 1.0f) {
                handleTorrentFinished(handle)
                continue
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
            val numConnections = runCatching { status.numConnections() }.getOrDefault(0)
            val connectCandidates = runCatching { status.connectCandidates() }.getOrDefault(0)

            // Diagnostic logging for tracker endpoints (every 5 seconds)
            val trackers = runCatching { handle.trackers() }.getOrDefault(emptyList())
            if (now % 5000 < 1000) {
                for (t in trackers) {
                    val epInfo = t.endpoints().joinToString { ep ->
                        val v1 = ep.infohashV1()
                        "ep(fails=${v1?.fails()}, work=${v1?.isWorking()}, up=${v1?.updating()}, msg='${v1?.message()}')"
                    }
                    Log.i(TAG, "Torrent ${handle.getName()} tracker [${t.url()}]: tier=${t.tier()}, $epInfo")
                }
            }

            // Periodic peer swarm diagnostic logging (every 3 seconds)
            if (now % 3000 < 1000) {
                val peerList = runCatching { handle.peerInfo() }.getOrDefault(emptyList())
                val peerSummary = if (peerList.isNotEmpty()) {
                    peerList.take(5).joinToString { p -> "${p.ip()}(${p.client()}, ${p.flags()})" }
                } else "none"
                Log.d(TAG, "Torrent ${handle.getName()}: state=${status.state()}, conns=$numConnections, candidates=$connectCandidates, peers=$connectedPeers/$totalPeers, seeds=$connectedSeeds, rate=$downloadRate, samplePeers=[$peerSummary]")
            }

            // Safe periodic reannounce ONLY when 0 peers are in swarm after 3 minutes (prevents tracker hammering bans)
            if (status.state() == org.libtorrent4j.TorrentStatus.State.DOWNLOADING && connectedPeers == 0 && totalPeers == 0) {
                val lastTime = lastReannounceTimes[id] ?: 0L
                if (now - lastTime > 180_000L) {
                    lastReannounceTimes[id] = now
                    runCatching {
                        handle.unsetFlags(TorrentFlags.PAUSED)
                        handle.resume()
                        handle.forceReannounce()
                        Log.i(TAG, "Periodic reannounce triggered for ${handle.getName()} ($id)")
                    }
                }
            }

            if (status.hasMetadata()) {
                val info = runCatching { handle.torrentFile() }.getOrNull()
                val name = info?.name() ?: runCatching { handle.getName() }.getOrDefault("")
                if (name.isNotBlank() && totalBytes > 0) {
                    listener?.onTorrentAdded(id, name, totalBytes)
                }
            }

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
    }

    fun stop() {
        try {
            progressScheduler?.shutdownNow()
            sessionManager.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SessionManager", e)
        }
    }

    private fun injectTrackersIfPresent(handle: TorrentHandle) {
        val id = handle.infoHash().toHex()
        val trackers = torrentTrackers[id] ?: emptyList()
        val existing = runCatching { handle.trackers().map { it.url() } }.getOrDefault(emptyList())
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
    }
}
