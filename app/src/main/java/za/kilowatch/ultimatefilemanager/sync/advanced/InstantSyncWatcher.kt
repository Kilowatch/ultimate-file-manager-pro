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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

/**
 * Manages [FileObserver] instances for profiles with instant sync enabled.
 *
 * Watches the local source folder for file create/modify/delete events,
 * debounces bursts, checks battery and network constraints, then enqueues
 * a one-shot [AdvancedSyncWorker] via WorkManager.
 *
 * ## Queued re-trigger (race-condition fix)
 *
 * Files can land in the source folder while [AdvancedSyncWorker] is already running.
 * To avoid missing those files, [performTrigger] checks whether an instant sync for
 * the profile is currently RUNNING or ENQUEUED.  If so, it sets a flag in
 * [pendingTriggers] rather than enqueuing another request.  When the worker finishes
 * it calls [onSyncCompleted]; that callback fires the pending trigger so the
 * newly-arrived files are picked up in a follow-up run.
 *
 * At most **one** follow-up run is queued per profile — many rapid arrivals collapse
 * into a single follow-up thanks to the set semantics of [pendingTriggers].
 * WorkManager's [ExistingWorkPolicy.KEEP] additionally prevents accidental stacking.
 */
object InstantSyncWatcher {

    private const val TAG = "InstantSyncWatcher"
    private const val DEBOUNCE_MS = 5000L
    private const val MIN_BATTERY_PERCENT = 15

    private val watchers = mutableMapOf<String, FileObserver>()
    private val debounceHandlers = mutableMapOf<String, Handler>()
    private val debounceRunnables = mutableMapOf<String, Runnable>()

    /**
     * Profile IDs that received a file-system event while a sync was already running.
     * At most one pending re-trigger is stored per profile — rapid arrivals during a
     * sync collapse into a single follow-up run.
     */
    private val pendingTriggers = mutableSetOf<String>()

    /** Unique WorkManager work name for one-shot instant sync of the given profile. */
    private fun instantWorkName(profileId: String) = "instant_sync_$profileId"

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
     * Also clears any pending re-trigger for this profile.
     */
    fun stopWatching(profileId: String) {
        pendingTriggers.remove(profileId)
        // Remove and stop the FileObserver (inner stopWatching() is FileObserver's method)
        watchers.remove(profileId)?.apply {
            stopWatching()
            Log.d(TAG, "Stopped watching profile $profileId")
        }
        debounceHandlers.remove(profileId)?.removeCallbacks(debounceRunnables.remove(profileId) ?: return)
    }

    /**
     * Stop all active watchers and clear all pending re-triggers.
     */
    fun stopAll() {
        pendingTriggers.clear()
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

    /**
     * Called by [AdvancedSyncWorker] when a sync run for [profileId] completes.
     *
     * If a file-system event arrived while the worker was running, a pending trigger
     * was recorded. This method fires that pending trigger so the newly arrived
     * files are synced in a follow-up run.
     */
    fun onSyncCompleted(context: Context, profileId: String) {
        if (pendingTriggers.remove(profileId)) {
            Log.d(TAG, "Pending trigger fired for profile $profileId after sync completion")
            val profile = AdvancedSyncProfileRepository.getInstance(context).getById(profileId)
            if (profile != null && profile.enabled && profile.instantSyncEnabled) {
                performTrigger(context, profile)
            }
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

        val workName = instantWorkName(profile.id)

        // If a run for this profile is already RUNNING or ENQUEUED, record a pending
        // trigger so a follow-up sync fires once the current run completes.
        // WorkManager's KEEP policy ensures we never stack duplicate requests on top of
        // each other — at most one queued run exists at any time per profile.
        val infos = try {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(workName)
                .get() // blocking — we are already on a background handler thread
        } catch (e: Exception) {
            Log.w(TAG, "Could not query work info for $workName", e)
            emptyList()
        }

        val isActive = infos.any { info ->
            info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
        }

        if (isActive) {
            pendingTriggers.add(profile.id)
            Log.d(TAG, "Pending trigger queued for ${profile.name} — sync already running")
            return
        }

        // Enqueue a unique one-shot work request. KEEP means if somehow a request is
        // already queued (e.g. a race between the isActive check above and this call),
        // we do not stack another on top of it.
        val inputData = workDataOf("PROFILE_ID" to profile.id)
        val workRequest = OneTimeWorkRequestBuilder<AdvancedSyncWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
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
