package za.kilowatch.ultimatefilemanager.indexing

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Battery-efficient global storage monitor.
 *
 * Registered once against [MediaStore.Files.getContentUri("external")] — a single OS-level
 * listener that covers ALL storage events: file creates, modifications, deletes, and folder creates.
 *
 * Never triggers a full re-index.  Every change is handled by either:
 *  - [IndexingRepository.indexFile]   — for individual file creates/modifications
 *  - [IndexingRepository.indexFolder] — for directory creates/modifications
 *  - [IndexingRepository.handleFileDeleted] — for deletions
 *
 * Burst detection: if ≥ [BURST_THRESHOLD] changes arrive within [BURST_WINDOW_MS], an incremental
 * WorkManager job is enqueued (not a full scan) to ensure completeness.
 */
class MediaStoreChangeObserver private constructor(
    private val context: Context,
    private val repository: IndexingRepository,
    handlerThread: HandlerThread
) : ContentObserver(Handler(handlerThread.looper)) {

    constructor(
        context: Context,
        repository: IndexingRepository = IndexingRepository.getInstance(context)
    ) : this(context, repository, getOrCreateObserverThread())

    companion object {
        private const val TAG = "MediaStoreObserver"

        @Volatile
        private var observerThread: HandlerThread? = null

        @Synchronized
        private fun getOrCreateObserverThread(): HandlerThread {
            val existing = observerThread
            if (existing != null && existing.isAlive) {
                return existing
            }
            return HandlerThread("MediaStoreObserverThread").also {
                it.start()
                observerThread = it
            }
        }
    }

    private val observerScope = CoroutineScope(Dispatchers.IO + Job())

    // Deduplicate rapid multi-event bursts for the same URI
    private val processedUris = ConcurrentHashMap<String, Long>()
    private val DEBOUNCE_MS = 1_000L

    // Burst tracking — many changes in a short window → schedule an incremental background job
    private var changeBurstCount = 0
    private val BURST_THRESHOLD = 50
    private val BURST_WINDOW_MS = 60_000L
    private var burstWindowStart = 0L

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        if (uri == null) return

        val uriString = uri.toString()
        val now = System.currentTimeMillis()

        // Debounce: ignore if we processed this URI less than DEBOUNCE_MS ago
        val lastSeen = processedUris[uriString] ?: 0L
        if (now - lastSeen < DEBOUNCE_MS) return
        processedUris[uriString] = now

        // Periodically purge stale entries from processedUris map to prevent unbounded memory growth
        if (processedUris.size > 500) {
            val cutoff = now - (DEBOUNCE_MS * 2)
            processedUris.entries.removeIf { it.value < cutoff }
        }

        observerScope.launch {
            try {
                val path = resolvePathFromUri(uri)

                if (path != null) {
                    if (path.endsWith(".ufm_tmp")) return@launch
                    
                    val file = File(path)
                    val (storageId, storageType) = resolveStorage(path).let { it.first to it.second }

                    // Respect "Not Now" — don't index anything on storages the user declined.
                    if (repository.hasUserDeclinedIndexing(storageId)) return@launch

                    when {
                        // --- Directory created or changed ---
                        file.exists() && file.isDirectory -> {
                            GoRoLog.d(TAG, "Directory event: $path")
                            // Index the directory entry itself
                            repository.indexFile(file, storageId, storageType)
                            // Index the immediate contents of the new/changed directory
                            repository.indexFolder(path, storageId, storageType)
                        }

                        // --- File created or modified ---
                        file.exists() -> {
                            if (!repository.isIndexing(storageId)) {
                                GoRoLog.d(TAG, "File event: $path")
                                repository.indexFile(file, storageId, storageType)

                                // Also ensure the parent directory is indexed.
                                // This catches the case where another app creates a new empty folder
                                // and then writes a file into it — the directory itself never triggers
                                // a MediaStore event, but the file event gets us here.
                                val parentDir = file.parentFile
                                if (parentDir != null && parentDir.exists()) {
                                    repository.indexFile(parentDir, storageId, storageType)
                                }
                            }
                        }

                        // --- File or directory deleted ---
                        else -> {
                            GoRoLog.d(TAG, "Deletion event: $path")
                            repository.handleFileDeleted(path, storageId)
                        }
                    }

                    // Burst tracking
                    trackBurst(storageId, file)
                }
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Failed processing MediaStore URI change: ${e.message}")
            }
        }
    }

    /**
     * Track whether a burst of changes is happening.
     * If the burst threshold is exceeded within the window, schedule an incremental background job
     * so we don't miss anything the per-URI debounce may have swallowed.
     */
    private fun trackBurst(storageId: String, file: File) {
        val now = System.currentTimeMillis()
        if (burstWindowStart == 0L || now - burstWindowStart > BURST_WINDOW_MS) {
            burstWindowStart = now
            changeBurstCount = 0
        }
        changeBurstCount++

        if (changeBurstCount >= BURST_THRESHOLD) {
            GoRoLog.d(TAG, "Burst detected ($changeBurstCount changes) — scheduling incremental background job for $storageId")
            try {
                // Enqueue a WorkManager job that will do an incremental MediaStore reconciliation
                // (it reads lastIndexedAt so it never becomes a full scan)
                IndexingWorker.enqueueIncrementalIndex(
                    context,
                    storageId,
                    file.parent ?: "/storage/emulated/0",
                    resolveStorage(file.absolutePath).second   // storageType
                )
            } catch (e: Exception) {
                GoRoLog.w(TAG, "Failed to enqueue incremental job: ${e.message}")
            }
            changeBurstCount = 0
            burstWindowStart = 0L
        }
    }

    /**
     * Resolve the physical filesystem path from a MediaStore [Uri] by querying [MediaStore.MediaColumns.DATA].
     */
    private fun resolvePathFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val rawPath = cursor.getString(idx)
                    // FUSE paths are case-insensitive. Canonicalize to avoid duplication if external apps write to "download" instead of "Download".
                    try { File(rawPath).canonicalPath } catch (e: Exception) { rawPath }
                } else null
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error querying MediaStore DATA: ${e.message}")
            null
        }
    }

    // Use the shared helper in IndexingRepository so all storage resolution is consistent.
    // Handles internal (/storage/emulated/0), SD cards (/storage/UUID), and USB OTG volumes.
    private fun resolveStorage(path: String) = IndexingRepository.resolveStorageForPath(path)
}
