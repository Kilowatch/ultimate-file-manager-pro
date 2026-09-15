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
 * Process-wide, ref-counted registry of [FileObserver]s used by [RecentsChangeWatcher].
 *
 * Exists so that watcher teardown can be driven from outside any instance: the eject
 * release phase holds no reference to a `RecentsChangeWatcher`, and the class is created
 * per-Activity so several may be alive at once. Ref-counting means a directory watched by
 * two live instances gets one underlying observer, and it is torn down only when the last
 * subscriber releases it — or when [releaseVolume] force-stops everything under a volume.
 *
 * All state is guarded by the monitor on this object, and [FileObserver] callbacks arrive on
 * the observer's own thread rather than the main one.
 *
 * [dispatch] is itself `@Synchronized`, so it *does* hold the monitor while it invokes the
 * listeners. That is safe for three specific reasons, not by construction: the monitor is
 * reentrant, so a listener that re-entered the registry would not deadlock; the listener list
 * is snapshotted with `toList()` before iteration, so a listener that acquires or releases
 * would not corrupt the walk; and in practice the listeners only debounce and post, touching no
 * registry state at all. A listener that did blocking work here would stall every other
 * directory's events, so keep them short.
 */
private object FileObserverRegistry {

    private const val TAG = "RecentsFsWatcher"

    private val MASK = FileObserver.CREATE or FileObserver.MODIFY or
            FileObserver.MOVED_TO or FileObserver.DELETE

    private class Entry(val observer: FileObserver) {
        val listeners = mutableListOf<() -> Unit>()
    }

    /** Absolute directory path -> shared observer and its subscribers. */
    private val entries = mutableMapOf<String, Entry>()

    /** Registers [listener] for [dir], creating and starting the observer on first use. */
    @Synchronized
    fun acquire(dir: File, listener: () -> Unit) {
        val key = dir.absolutePath

        entries[key]?.let { entry ->
            if (entry.listeners.none { it === listener }) entry.listeners.add(listener)
            return
        }

        if (!dir.exists() || !dir.canRead()) return

        try {
            val observer = createObserver(dir)
            observer.startWatching()
            entries[key] = Entry(observer).apply { listeners.add(listener) }
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to start FileObserver on $key: ${e.message}")
        }
    }

    /** Removes [listener] everywhere; stops any observer left with no subscribers. */
    @Synchronized
    fun release(listener: () -> Unit) {
        val it = entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            entry.listeners.removeAll { it === listener }
            if (entry.listeners.isEmpty()) {
                try { entry.observer.stopWatching() } catch (_: Exception) { }
                it.remove()
            }
        }
    }

    /**
     * Force-stops every observer whose directory is [volumePath] itself or lies beneath it,
     * regardless of how many listeners it still has. Returns the number stopped.
     *
     * Callers holding a now-stale listener are unaffected: a later [release] simply finds
     * nothing to remove, and a later [acquire] re-registers from scratch.
     */
    @Synchronized
    fun releaseVolume(volumePath: String): Int {
        val root = volumePath.trimEnd('/')
        if (root.isEmpty()) return 0
        val prefix = "$root/"

        var stopped = 0
        val it = entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val key = entry.key
            if (key == root || key.startsWith(prefix)) {
                try { entry.value.observer.stopWatching() } catch (_: Exception) { }
                it.remove()
                stopped++
            }
        }
        return stopped
    }

    /**
     * Absolute paths of every directory still registered at or beneath [volumePath].
     *
     * This is the **only** window onto inotify state in the process: a watch descriptor
     * readlinks as `anon_inode:inotify` and appears in no `/proc/self/maps` line, so neither
     * the descriptor sweep nor the map scan can see one. A non-empty result after
     * [releaseVolume] therefore explains an unmount that fails with the mount busy while both
     * of those scans come back clean — and it is the one surviving-claim class that FR-09
     * could not otherwise report.
     */
    @Synchronized
    fun activeUnder(volumePath: String): List<String> {
        val root = volumePath.trimEnd('/')
        if (root.isEmpty()) return emptyList()
        val prefix = "$root/"
        return entries.keys.filter { it == root || it.startsWith(prefix) }
    }

    private fun createObserver(dir: File): FileObserver {
        val onFsEvent: (String?) -> Unit = { path ->
            if (path == null || !path.startsWith(".")) dispatch(dir.absolutePath)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, MASK) {
                override fun onEvent(event: Int, path: String?) = onFsEvent(path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, MASK) {
                override fun onEvent(event: Int, path: String?) = onFsEvent(path)
            }
        }
    }

    @Synchronized
    private fun dispatch(key: String) {
        entries[key]?.listeners?.toList()?.forEach { listener ->
            try { listener() } catch (e: Exception) {
                GoRoLog.w(TAG, "FileObserver listener failed for $key: ${e.message}")
            }
        }
    }
}

/**
 * Live change detection for Recent Files:
 * 1. MediaStore observer for instant system-wide file modifications.
 * 2. FileObserver on primary user directories with a 500ms debounce.
 * 3. ContentObserver for persisted SAF trees in Limited tier.
 * 4. BroadcastReceiver for media unmount / eject events.
 *
 * The observers from (2) live in a process-wide, ref-counted registry rather than in this
 * instance, so the eject release phase can stop them without holding a reference here.
 */
class RecentsChangeWatcher(
    private val context: Context,
    private val onDataChanged: () -> Unit
) {

    companion object {
        private const val TAG = "RecentsChangeWatcher"
        private const val DEBOUNCE_MS = 500L

        /**
         * Stops filesystem watching for [volumePath] and everything beneath it, for every
         * live watcher instance. Safe to call from any thread, at any time, and with no
         * instance in hand — that is the point: the eject release phase has neither.
         *
         * Note this does **not** make the app survive an eject on its own. inotify watch
         * descriptors readlink as `anon_inode:inotify` and can never appear as a path
         * symlink, so `vold` cannot see them and they cannot be the cause of a kill. What
         * they can do is keep an inode reference that leaves the mount busy and fails the
         * unmount — a different symptom. This call is hygiene, not the fix.
         *
         * @return the number of underlying observers stopped.
         */
        fun stopWatchingVolume(volumePath: String): Int =
            FileObserverRegistry.releaseVolume(volumePath)

        /**
         * Directories still watched under [volumePath] — the read-only companion to
         * [stopWatchingVolume], used by the release phase to *measure* that the stop worked
         * rather than assume it. See [FileObserverRegistry.activeUnder].
         */
        fun activeWatchesUnder(volumePath: String): List<String> =
            FileObserverRegistry.activeUnder(volumePath)
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var debounceJob: Job? = null
    private var mediaStoreObserver: ContentObserver? = null
    private val safObservers = mutableMapOf<Uri, ContentObserver>()
    private var mediaReceiver: BroadcastReceiver? = null

    /**
     * Stable identity for this instance's registration in the shared registry. Held as a
     * field so `release` can match it by reference; a fresh lambda per call would not.
     */
    private val onFsEvent: () -> Unit = { triggerDebouncedUpdate() }

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
            FileObserverRegistry.acquire(dir, onFsEvent)
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

        FileObserverRegistry.release(onFsEvent)

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
