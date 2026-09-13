package za.kilowatch.ultimatefilemanager.indexing.recents

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath
import java.io.File

/**
 * Live change detection for Recent Files:
 * 1. MediaStore observer for instant system-wide file modifications.
 * 2. FileObserver on primary user directories with a 500ms debounce.
 * 3. ContentObserver for persisted SAF trees in Limited tier.
 * 4. BroadcastReceiver for media unmount / eject events.
 */
class RecentsChangeWatcher(
    private val context: Context,
    private val onDataChanged: () -> Unit
) {

    companion object {
        private const val TAG = "RecentsChangeWatcher"
        private const val DEBOUNCE_MS = 500L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var debounceJob: Job? = null
    private val fileObservers = mutableListOf<FileObserver>()
    private var mediaStoreObserver: ContentObserver? = null
    private val safObservers = mutableMapOf<Uri, ContentObserver>()
    private var mediaReceiver: BroadcastReceiver? = null

    private fun triggerDebouncedUpdate() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            onDataChanged()
        }
    }

    fun start() {
        stop()

        // 1. Register MediaStore ContentObserver (instant device-wide)
        val handler = Handler(Looper.getMainLooper())
        mediaStoreObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                triggerDebouncedUpdate()
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                mediaStoreObserver!!
            )
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to register MediaStore ContentObserver: ${e.message}")
        }

        // 2. Register FileObservers on all storage volumes and top-level user directories (Full tier)
        val watchDirs = mutableListOf<File>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volumes = sm?.storageVolumes ?: emptyList()
        for (vol in volumes) {
            val rootPath = vol.safeDirectoryPath ?: continue
            val rootDir = File(rootPath)
            if (rootDir.exists() && rootDir.canRead()) {
                watchDirs.add(rootDir)
                rootDir.listFiles()?.filter {
                    it.isDirectory && !it.name.startsWith(".") && !it.name.equals("Android", ignoreCase = true)
                }?.forEach {
                    watchDirs.add(it)
                }
            }
        }
        if (watchDirs.isEmpty()) {
            val defaultRoot = File("/storage/emulated/0")
            if (defaultRoot.exists() && defaultRoot.canRead()) {
                watchDirs.add(defaultRoot)
                defaultRoot.listFiles()?.filter {
                    it.isDirectory && !it.name.startsWith(".") && !it.name.equals("Android", ignoreCase = true)
                }?.forEach {
                    watchDirs.add(it)
                }
            }
        }

        for (dir in watchDirs) {
            if (!dir.exists() || !dir.canRead()) continue
            try {
                val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    object : FileObserver(dir, CREATE or MODIFY or MOVED_TO or DELETE) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path != null && !path.startsWith(".")) {
                                triggerDebouncedUpdate()
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    object : FileObserver(dir.absolutePath, CREATE or MODIFY or MOVED_TO or DELETE) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path != null && !path.startsWith(".")) {
                                triggerDebouncedUpdate()
                            }
                        }
                    }
                }
                observer.startWatching()
                fileObservers.add(observer)
            } catch (e: Exception) {
                GoRoLog.w(TAG, "Failed to start FileObserver on ${dir.path}: ${e.message}")
            }
        }

        // 3. Register ContentObserver for SAF trees (Limited tier)
        try {
            val persisted = context.contentResolver.persistedUriPermissions
            for (perm in persisted) {
                if (!perm.isReadPermission) continue
                val obs = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        triggerDebouncedUpdate()
                    }
                }
                context.contentResolver.registerContentObserver(perm.uri, true, obs)
                safObservers[perm.uri] = obs
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to register SAF ContentObservers: ${e.message}")
        }

        // 4. Register Media Eject BroadcastReceiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addDataScheme("file")
        }
        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val uri = intent?.data
                val path = uri?.path
                if (path != null) {
                    val volId = path.removePrefix("/storage/").replace("/", "_")
                    scope.launch(Dispatchers.IO) {
                        RecentsRepository.getInstance(context).onVolumeUnmounted(volId)
                        triggerDebouncedUpdate()
                    }
                } else {
                    triggerDebouncedUpdate()
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(mediaReceiver, filter)
            }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to register mediaReceiver: ${e.message}")
        }
    }

    fun stop() {
        debounceJob?.cancel()

        mediaStoreObserver?.let {
            try { context.contentResolver.unregisterContentObserver(it) } catch (_: Exception) { }
            mediaStoreObserver = null
        }

        fileObservers.forEach {
            try { it.stopWatching() } catch (_: Exception) { }
        }
        fileObservers.clear()

        safObservers.values.forEach {
            try { context.contentResolver.unregisterContentObserver(it) } catch (_: Exception) { }
        }
        safObservers.clear()

        mediaReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) { }
            mediaReceiver = null
        }
    }
}
