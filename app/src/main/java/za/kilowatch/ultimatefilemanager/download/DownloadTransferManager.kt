package za.kilowatch.ultimatefilemanager.download

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Singleton orchestrator coordinating both BitTorrent and HTTP download transfers.
 * Enforces pre-flight storage validations, network policies (Wi-Fi only vs all networks),
 * fast auto-resumption when Wi-Fi connects, and foreground service lifecycle.
 */
object DownloadTransferManager {

    private const val TAG = "DownloadTransferMgr"

    enum class TransferType { DIRECT_HTTP, TORRENT, MAGNET }
    enum class TransferState { DOWNLOADING, PAUSED, QUEUED_WIFI, COMPLETED, ERROR }

    data class TransferItem(
        val id: String,
        var title: String,
        val type: TransferType,
        var totalBytes: Long = 0L,
        var downloadedBytes: Long = 0L,
        var progressPercent: Int = 0,
        var speedBytesPerSec: Long = 0L,
        var etaSeconds: Long = 0L,
        var connectedPeers: Int = 0,
        var totalPeers: Int = 0,
        var connectedSeeds: Int = 0,
        var state: TransferState = TransferState.DOWNLOADING,
        val targetFolder: File,
        val sourceUriOrUrl: String,
        var errorMessage: String? = null
    )

    private val transfers = CopyOnWriteArrayList<TransferItem>()
    private val listeners = CopyOnWriteArrayList<TransferUpdateListener>()

    @Volatile
    private var appContext: Context? = null
    private var torrentEngine: TorrentEngine? = null
    private var httpEngine: HttpDownloadEngine? = null
    private var isNetworkCallbackRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())

    interface TransferUpdateListener {
        fun onTransfersChanged()
        fun onTransferProgress(item: TransferItem)
        fun onPreflightFailed(item: TransferItem, result: StoragePreflightValidator.ValidationResult)
    }

    fun addListener(listener: TransferUpdateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: TransferUpdateListener) {
        listeners.remove(listener)
    }

    fun getAllTransfers(): List<TransferItem> = transfers.toList()

    private fun initEnginesIfNeeded(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (torrentEngine == null) {
            torrentEngine = TorrentEngine(appCtx).apply {
                listener = object : TorrentEngine.TorrentListener {
                    override fun onTorrentAdded(id: String, name: String, totalBytes: Long) {
                        mainHandler.post {
                            val item = transfers.find { it.id == id || it.sourceUriOrUrl.contains(id, ignoreCase = true) }
                            if (item != null) {
                                if (name.isNotBlank()) {
                                    item.title = name
                                }
                                if (totalBytes > 0) {
                                    item.totalBytes = totalBytes
                                    val preflight = StoragePreflightValidator.validate(appCtx, item.targetFolder, totalBytes)
                                    if (!preflight.isSuccessful) {
                                        pauseTransfer(item.id)
                                        notifyPreflightFailed(item, preflight)
                                    }
                                }
                                notifyTransfersChanged()
                            }
                        }
                    }

                    override fun onTorrentProgress(
                        id: String,
                        progressPercent: Int,
                        downloadSpeedBytesPerSec: Long,
                        etaSeconds: Long,
                        downloadedBytes: Long,
                        connectedPeers: Int,
                        totalPeers: Int,
                        connectedSeeds: Int
                    ) {
                        mainHandler.post {
                            val item = transfers.find { it.id == id || it.sourceUriOrUrl.contains(id, ignoreCase = true) }
                            if (item != null && item.state == TransferState.DOWNLOADING) {
                                item.progressPercent = progressPercent
                                item.speedBytesPerSec = downloadSpeedBytesPerSec
                                item.etaSeconds = etaSeconds
                                item.downloadedBytes = downloadedBytes
                                item.connectedPeers = connectedPeers
                                item.totalPeers = totalPeers
                                item.connectedSeeds = connectedSeeds
                                notifyProgress(item)
                            }
                        }
                    }

                    override fun onTorrentFinished(id: String, saveDir: File, fileNames: List<String>) {
                        mainHandler.post {
                            val item = transfers.find { it.id == id || it.sourceUriOrUrl.contains(id, ignoreCase = true) }
                            if (item != null) {
                                item.state = TransferState.COMPLETED
                                item.progressPercent = 100
                                item.downloadedBytes = item.totalBytes
                                item.speedBytesPerSec = 0L
                                item.etaSeconds = 0L
                                notifyTransfersChanged()
                                checkServiceState(appCtx)
                            }
                        }
                    }

                    override fun onTorrentError(id: String, error: String) {
                        mainHandler.post {
                            val item = transfers.find { it.id == id || it.sourceUriOrUrl.contains(id, ignoreCase = true) }
                            if (item != null) {
                                item.state = TransferState.ERROR
                                item.errorMessage = error
                                notifyTransfersChanged()
                                checkServiceState(appCtx)
                            }
                        }
                    }
                }
            }
        }

        if (httpEngine == null) {
            httpEngine = HttpDownloadEngine(appCtx).apply {
                listener = object : HttpDownloadEngine.HttpListener {
                    override fun onHttpStarted(id: String, fileName: String, totalBytes: Long) {
                        mainHandler.post {
                            val item = transfers.find { it.id == id }
                            if (item != null) {
                                item.title = fileName
                                item.totalBytes = totalBytes
                                notifyTransfersChanged()
                            }
                        }
                    }

                    override fun onHttpProgress(
                        id: String,
                        progressPercent: Int,
                        downloadSpeedBytesPerSec: Long,
                        etaSeconds: Long,
                        downloadedBytes: Long
                    ) {
                        mainHandler.post {
                            val item = transfers.find { it.id == id }
                            if (item != null && item.state == TransferState.DOWNLOADING) {
                                item.progressPercent = progressPercent
                                item.speedBytesPerSec = downloadSpeedBytesPerSec
                                item.etaSeconds = etaSeconds
                                item.downloadedBytes = downloadedBytes
                                notifyProgress(item)
                            }
                        }
                    }

                    override fun onHttpFinished(id: String, file: File) {
                        mainHandler.post {
                            val item = transfers.find { it.id == id }
                            if (item != null) {
                                item.state = TransferState.COMPLETED
                                item.progressPercent = 100
                                item.downloadedBytes = item.totalBytes
                                item.speedBytesPerSec = 0L
                                item.etaSeconds = 0L
                                notifyTransfersChanged()
                                checkServiceState(appCtx)
                            }
                        }
                    }

                    override fun onHttpError(id: String, error: String) {
                        mainHandler.post {
                            val item = transfers.find { it.id == id }
                            if (item != null) {
                                item.state = TransferState.ERROR
                                item.errorMessage = error
                                notifyTransfersChanged()
                                checkServiceState(appCtx)
                            }
                        }
                    }
                }
            }
        }

        registerNetworkMonitorIfNeeded(appCtx)
    }

    private fun registerNetworkMonitorIfNeeded(context: Context) {
        if (isNetworkCallbackRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

                    if (isWifi) {
                        // Auto-resume transfers that were queued waiting for Wi-Fi
                        mainHandler.post {
                            for (item in transfers) {
                                if (item.state == TransferState.QUEUED_WIFI) {
                                    resumeTransfer(context, item.id)
                                }
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    // If lost network and policy is WIFI_ONLY, pause active downloads
                    if (DownloadPreferencesManager.isWifiOnly(context)) {
                        mainHandler.post {
                            for (item in transfers) {
                                if (item.state == TransferState.DOWNLOADING) {
                                    pauseTransfer(item.id, queueForWifi = true)
                                }
                            }
                        }
                    }
                }
            })
            isNetworkCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register NetworkCallback", e)
        }
    }

    fun startHttpDownload(context: Context, url: String, targetFolder: File, customFileName: String? = null) {
        initEnginesIfNeeded(context)
        val id = UUID.randomUUID().toString()
        val defaultName = url.substringBefore("?").substringAfterLast("/").ifBlank { "download" }
        val fileName = customFileName ?: defaultName

        val item = TransferItem(
            id = id,
            title = fileName,
            type = TransferType.DIRECT_HTTP,
            targetFolder = targetFolder,
            sourceUriOrUrl = url
        )

        // Storage pre-flight check
        val preflight = StoragePreflightValidator.validate(context, targetFolder, 0L)
        if (!preflight.isSuccessful) {
            notifyPreflightFailed(item, preflight)
            return
        }

        // Network policy check
        if (shouldQueueForWifi(context)) {
            item.state = TransferState.QUEUED_WIFI
            transfers.add(item)
            notifyTransfersChanged()
            return
        }

        transfers.add(item)
        notifyTransfersChanged()
        httpEngine?.startDownload(id, url, targetFolder, customFileName)
        startServiceIfNeeded(context)
    }

    fun startTorrentDownload(context: Context, magnetUri: String, targetFolder: File) {
        initEnginesIfNeeded(context)
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
        val id = sha1?.toHex() ?: magnetUri.hashCode().toString()

        val existing = transfers.find { it.id == id }
        if (existing != null) {
            if (existing.state == TransferState.PAUSED || existing.state == TransferState.QUEUED_WIFI) {
                resumeTransfer(context, id)
            }
            return
        }

        val item = TransferItem(
            id = id,
            title = "Fetching magnet metadata...",
            type = TransferType.MAGNET,
            targetFolder = targetFolder,
            sourceUriOrUrl = magnetUri
        )

        // Storage pre-flight check
        val preflight = StoragePreflightValidator.validate(context, targetFolder, 0L)
        if (!preflight.isSuccessful) {
            notifyPreflightFailed(item, preflight)
            return
        }

        if (shouldQueueForWifi(context)) {
            item.state = TransferState.QUEUED_WIFI
            transfers.add(item)
            notifyTransfersChanged()
            return
        }

        transfers.add(item)
        notifyTransfersChanged()
        torrentEngine?.downloadMagnet(magnetUri, targetFolder)
        startServiceIfNeeded(context)
    }

    fun startTorrentFileDownload(context: Context, torrentFile: File, targetFolder: File) {
        initEnginesIfNeeded(context)
        try {
            val torrentInfo = TorrentInfo(torrentFile)
            val id = torrentInfo.infoHash().toHex()
            val name = torrentInfo.name()
            val totalBytes = torrentInfo.totalSize()

            val existing = transfers.find { it.id == id }
            if (existing != null) {
                if (existing.state == TransferState.PAUSED || existing.state == TransferState.QUEUED_WIFI) {
                    resumeTransfer(context, id)
                } else if (existing.state == TransferState.DOWNLOADING) {
                    torrentEngine?.downloadTorrentFile(torrentFile, targetFolder)
                }
                return
            }

            val item = TransferItem(
                id = id,
                title = name.ifBlank { torrentFile.nameWithoutExtension },
                type = TransferType.TORRENT,
                totalBytes = totalBytes,
                targetFolder = targetFolder,
                sourceUriOrUrl = torrentFile.absolutePath
            )

            // Storage pre-flight check with known size
            val preflight = StoragePreflightValidator.validate(context, targetFolder, totalBytes)
            if (!preflight.isSuccessful) {
                notifyPreflightFailed(item, preflight)
                return
            }

            if (shouldQueueForWifi(context)) {
                item.state = TransferState.QUEUED_WIFI
                transfers.add(item)
                notifyTransfersChanged()
                return
            }

            transfers.add(item)
            notifyTransfersChanged()
            torrentEngine?.downloadTorrentFile(torrentFile, targetFolder)
            startServiceIfNeeded(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect and start torrent file download", e)
        }
    }

    fun startTorrentFileDownload(context: Context, torrentUri: Uri, targetFolder: File) {
        val file = if (torrentUri.scheme == "file" && !torrentUri.path.isNullOrBlank()) {
            File(torrentUri.path!!)
        } else {
            val tempTorrentFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.torrent")
            try {
                context.contentResolver.openInputStream(torrentUri)?.use { input ->
                    tempTorrentFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempTorrentFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy .torrent file to cache", e)
                return
            }
        }
        startTorrentFileDownload(context, file, targetFolder)
    }

    fun pauseTransfer(id: String, queueForWifi: Boolean = false) {
        val item = transfers.find { it.id == id } ?: return
        item.state = if (queueForWifi) TransferState.QUEUED_WIFI else TransferState.PAUSED
        item.speedBytesPerSec = 0L

        when (item.type) {
            TransferType.DIRECT_HTTP -> httpEngine?.pause(id)
            TransferType.TORRENT, TransferType.MAGNET -> torrentEngine?.pause(id)
        }
        notifyTransfersChanged()
        appContext?.let { checkServiceState(it) }
    }

    fun resumeTransfer(context: Context, id: String) {
        appContext = context.applicationContext
        val item = transfers.find { it.id == id } ?: return
        if (shouldQueueForWifi(context)) {
            item.state = TransferState.QUEUED_WIFI
            notifyTransfersChanged()
            return
        }

        item.state = TransferState.DOWNLOADING
        when (item.type) {
            TransferType.DIRECT_HTTP -> httpEngine?.resume(id)
            TransferType.TORRENT, TransferType.MAGNET -> torrentEngine?.resume(id)
        }
        notifyTransfersChanged()
        startServiceIfNeeded(context)
    }

    fun cancelTransfer(id: String, deleteFiles: Boolean = true) {
        val item = transfers.find { it.id == id } ?: return
        transfers.remove(item)

        when (item.type) {
            TransferType.DIRECT_HTTP -> httpEngine?.cancel(id, deleteFiles)
            TransferType.TORRENT, TransferType.MAGNET -> torrentEngine?.cancel(id, deleteFiles)
        }

        if (deleteFiles) {
            cleanupIncompleteFiles(item)
        }

        notifyTransfersChanged()
        appContext?.let { checkServiceState(it) }
    }

    private fun cleanupIncompleteFiles(item: TransferItem) {
        try {
            if (item.title.isNotBlank() && item.title != "Fetching magnet metadata...") {
                val target = File(item.targetFolder, item.title)
                if (target.exists()) {
                    if (target.isDirectory) {
                        target.deleteRecursively()
                    } else {
                        target.delete()
                    }
                }
                val partFile = File(item.targetFolder, "${item.title}.part")
                if (partFile.exists()) partFile.delete()
                val partsFile = File(item.targetFolder, ".${item.title}.parts")
                if (partsFile.exists()) partsFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup incomplete file for ${item.title}", e)
        }
    }

    private fun shouldQueueForWifi(context: Context): Boolean {
        if (!DownloadPreferencesManager.isWifiOnly(context)) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNet = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(activeNet) ?: return true
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
               !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun startServiceIfNeeded(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val active = transfers.any { it.state == TransferState.DOWNLOADING }
        if (active) {
            val serviceIntent = Intent(appCtx, DownloadService::class.java)

            // Prefer standard startService() when the app process is in foreground.
            // When started via startService(), the service still promotes itself to foreground
            // in onCreate() with safeStartForeground(), but Android DOES NOT arm the fatal
            // 5-second ForegroundServiceDidNotStartInTimeException watchdog.
            val isForeground = try {
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                    Lifecycle.State.STARTED
                )
            } catch (_: Exception) {
                true
            }

            if (isForeground) {
                try {
                    appCtx.startService(serviceIntent)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "startService failed in foreground (${e.message}), attempting startForegroundService fallback")
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(appCtx, serviceIntent)
                } else {
                    appCtx.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DownloadService: ${e.message}")
            }
        }
    }

    private fun checkServiceState(context: Context) {
        val hasActive = transfers.any { it.state == TransferState.DOWNLOADING }
        if (!hasActive) {
            try {
                val appCtx = context.applicationContext
                appCtx.stopService(Intent(appCtx, DownloadService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop DownloadService: ${e.message}")
            }
        }
    }

    fun onServiceTimeout() {
        mainHandler.post {
            var anyChanged = false
            for (item in transfers) {
                if (item.state == TransferState.DOWNLOADING) {
                    item.state = TransferState.PAUSED
                    item.speedBytesPerSec = 0L
                    item.errorMessage = "Paused: Android 6-hour execution limit reached"
                    when (item.type) {
                        TransferType.DIRECT_HTTP -> httpEngine?.pause(item.id)
                        TransferType.TORRENT, TransferType.MAGNET -> torrentEngine?.pause(item.id)
                    }
                    anyChanged = true
                }
            }
            if (anyChanged) {
                notifyTransfersChanged()
            }
            appContext?.let { checkServiceState(it) }
        }
    }

    private fun notifyTransfersChanged() {
        for (l in listeners) {
            l.onTransfersChanged()
        }
    }

    private fun notifyProgress(item: TransferItem) {
        for (l in listeners) {
            l.onTransferProgress(item)
        }
    }

    private fun notifyPreflightFailed(item: TransferItem, result: StoragePreflightValidator.ValidationResult) {
        mainHandler.post {
            for (l in listeners) {
                l.onPreflightFailed(item, result)
            }
        }
    }
}
