package za.kilowatch.ultimatefilemanager.sync.advanced

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.FileObserver
// Note: Recursive FileObserver watching (SUBTREE flag) is not available in this SDK.
// Only the immediate directory is watched. For full recursive support, migrate to
// FileObserver(File, Int, Int) with SUBTREE when the compile SDK supports it.
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

/**
 * Manages [FileObserver] instances for profiles with instant sync enabled.
 *
 * Watches the local source folder for file create/modify/delete events,
 * debounces bursts, checks battery and network constraints, then enqueues
 * a one-shot [AdvancedSyncWorker] via WorkManager.
 */
object InstantSyncWatcher {

    private const val TAG = "InstantSyncWatcher"
    private const val DEBOUNCE_MS = 5000L
    private const val MIN_BATTERY_PERCENT = 15

    private val watchers = mutableMapOf<String, FileObserver>()
    private val debounceHandlers = mutableMapOf<String, Handler>()
    private val debounceRunnables = mutableMapOf<String, Runnable>()

    /**
     * Start watching the source folder of the given profile.
     * If a watcher already exists for this profile ID, it is restarted.
     */
    fun startWatching(context: Context, profile: AdvancedSyncProfile) {
        stopWatching(profile.id)

        val localPath = profile.localUri
        val dir = File(localPath)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "Cannot watch: $localPath does not exist or is not a directory")
            return
        }

        val observer = createObserver(context, profile, dir)
        observer.startWatching()
        watchers[profile.id] = observer

        val handler = Handler(Looper.getMainLooper())
        debounceHandlers[profile.id] = handler

        Log.d(TAG, "Started watching ${profile.name} at $localPath")
    }

    /**
     * Stop watching the given profile. Safe to call even if not watching.
     */
    fun stopWatching(profileId: String) {
        // Remove and stop the FileObserver (inner stopWatching() is FileObserver's method)
        watchers.remove(profileId)?.apply {
            stopWatching()
            Log.d(TAG, "Stopped watching profile $profileId")
        }
        debounceHandlers.remove(profileId)?.removeCallbacks(debounceRunnables.remove(profileId) ?: return)
    }

    /**
     * Stop all active watchers.
     */
    fun stopAll() {
        val ids = watchers.keys.toList()
        ids.forEach { stopWatching(it) }
    }

    /**
     * Check if a profile is currently being watched.
     */
    fun isWatching(profileId: String): Boolean = watchers.containsKey(profileId)

    /**
     * Re-register watchers for all profiles that have instant sync enabled.
     * Call this on app start / BOOT_COMPLETED.
     */
    fun rewatchAll(context: Context) {
        stopAll()
        val repo = AdvancedSyncProfileRepository.getInstance(context)
        repo.getAll().filter { it.instantSyncEnabled && it.enabled }.forEach { profile ->
            startWatching(context, profile)
        }
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun createObserver(
        context: Context, profile: AdvancedSyncProfile, dir: File
    ): FileObserver {
        val mask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO

        return object : FileObserver(dir.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) {
                    onFileEvent(context, profile, dir)
                }
            }
        }
    }

    private fun onFileEvent(context: Context, profile: AdvancedSyncProfile, dir: File) {
        // Debounce: cancel previous pending trigger, schedule new one
        val handler = debounceHandlers[profile.id] ?: return
        val previous = debounceRunnables[profile.id]
        if (previous != null) {
            handler.removeCallbacks(previous)
        }

        val runnable = Runnable {
            performTrigger(context, profile)
        }
        debounceRunnables[profile.id] = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun performTrigger(context: Context, profile: AdvancedSyncProfile) {
        // Check battery level
        if (!isBatteryOk(context)) {
            Log.d(TAG, "Battery too low, skipping instant sync for ${profile.name}")
            return
        }

        // Check network constraint
        if (profile.wifiOnly && !isOnWifi(context)) {
            Log.d(TAG, "Not on WiFi, skipping instant sync for ${profile.name}")
            return
        }

        // Enqueue one-shot work
        val inputData = workDataOf("PROFILE_ID" to profile.id)
        val workRequest = OneTimeWorkRequestBuilder<AdvancedSyncWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "Instant sync triggered for ${profile.name}")
    }

    private fun isBatteryOk(context: Context): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true
        val percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return percent < 0 || percent >= MIN_BATTERY_PERCENT
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
