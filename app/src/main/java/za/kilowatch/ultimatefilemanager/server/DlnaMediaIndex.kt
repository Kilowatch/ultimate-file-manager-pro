package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Media file indexer for the DLNA Media Server.
 *
 * Scans configured shared folders via [UfmFileSystemBridge] and builds an
 * in-memory catalog of containers (directories) and media items keyed by
 * parent ID.  All public read methods are guarded by a [ReentrantReadWriteLock]
 * so the background scan can safely update the index while browse queries
 * arrive concurrently.
 *
 * ## Virtual container hierarchy
 *
 * ```
 * Root ("0")
 *  +-- Videos   ("videos")   — flat collection of all video files
 *  +-- Audio    ("audio")    — flat collection of all audio files
 *  +-- Images   ("images")   — flat collection of all image files
 *  +-- Browse Folders ("browse") — directory tree mirroring the shared folders
 * ```
 */
object DlnaMediaIndex {

    private const val TAG = "DlnaMediaIndex"

    /** Number of children returned per browse page. */
    private const val PAGE_SIZE = 200

    /** Maximum directory recursion depth (guard against symlink loops). */
    private const val MAX_RECURSION_DEPTH = 20

    /** Hard limit on the total number of indexed items (prevent OOM). */
    private const val MAX_TOTAL_ITEMS = 50_000

    // -----------------------------------------------------------------
    // Virtual container IDs
    // -----------------------------------------------------------------
    private const val ROOT_ID             = "0"
    private const val VIRTUAL_VIDEOS_ID   = "videos"
    private const val VIRTUAL_AUDIO_ID    = "audio"
    private const val VIRTUAL_IMAGES_ID   = "images"
    private const val VIRTUAL_BROWSE_ID   = "browse"

    /** IDs that are never removed during [rescan]. */
    private val VIRTUAL_IDS = setOf(
        ROOT_ID, VIRTUAL_VIDEOS_ID, VIRTUAL_AUDIO_ID,
        VIRTUAL_IMAGES_ID, VIRTUAL_BROWSE_ID
    )

    // -----------------------------------------------------------------
    // In-memory catalog
    // -----------------------------------------------------------------

    /** id -> container [MediaItem] (virtual containers + real directories). */
    private val containers = ConcurrentHashMap<String, MediaItem>()

    /** parentId -> ordered list of child [MediaItem]s. */
    private val items = ConcurrentHashMap<String, MutableList<MediaItem>>()

    /**
     * id -> [MediaItem] lookup index for O(1) retrieval in [getItem].
     * Every item written to [containers] or [items] is also indexed here.
     */
    private val itemIndex = ConcurrentHashMap<String, MediaItem>()

    /** Read/write lock for thread-safe index access. */
    private val lock = ReentrantReadWriteLock()

    // -----------------------------------------------------------------
    // Scan state
    // -----------------------------------------------------------------

    private var appContext: Context? = null
    private var sharedFolders: List<DlnaServerPrefs.SharedFolder> = emptyList()
    private var scanJob: Job? = null

    /**
     * Latch counted down when the current scan completes.
     * External consumers (e.g. the ContentDirectory SOAP handler) can wait
     * on this latch to defer browse/ browse requests until the index is ready.
     */
    private var scanLatch: CountDownLatch? = null

    /** Whether a scan is currently in progress. */
    @Volatile
    private var isScanning = false

    /** Monotonically increasing ID counter. Guarded by [generateId]. */
    private var nextItemId = 1L

    // -----------------------------------------------------------------
    // MIME type mapping (extension -> MIME)
    // -----------------------------------------------------------------

    private val extensionMimeMap: Map<String, String> = mapOf(
        // Video
        "mp4"  to "video/mp4",
        "mkv"  to "video/x-matroska",
        "avi"  to "video/x-msvideo",
        "mov"  to "video/quicktime",
        "wmv"  to "video/x-ms-wmv",
        "flv"  to "video/x-flv",
        "webm" to "video/webm",
        "m4v"  to "video/mp4",
        "3gp"  to "video/3gpp",
        "ts"   to "video/mp2t",
        "mpg"  to "video/mpeg",
        "mpeg" to "video/mpeg",
        // Audio
        "mp3"  to "audio/mpeg",
        "flac" to "audio/flac",
        "wav"  to "audio/x-wav",
        "ogg"  to "audio/ogg",
        "m4a"  to "audio/mp4",
        "aac"  to "audio/aac",
        "wma"  to "audio/x-ms-wma",
        "opus" to "audio/opus",
        // Images
        "jpg"  to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png"  to "image/png",
        "gif"  to "image/gif",
        "bmp"  to "image/bmp",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "svg"  to "image/svg+xml"
    )

    // =================================================================
    // Initialization
    // =================================================================

    /**
     * Store the application context and shared folder list, then build the
     * five virtual containers (Root, Videos, Audio, Images, Browse Folders).
     *
     * Safe to call multiple times — the index is fully rebuilt every time.
     */
    fun initialize(context: Context, folders: List<DlnaServerPrefs.SharedFolder>) {
        appContext = context.applicationContext
        sharedFolders = folders
        buildVirtualContainers()
    }

    /**
     * Create the five virtual containers and wire them under root.
     * Called from [initialize] and also used during [rescan] to reset.
     */
    private fun buildVirtualContainers() {
        lock.write {
            containers.clear()
            items.clear()
            itemIndex.clear()
            nextItemId = 1L

            val root = MediaItem(
                id = ROOT_ID,
                parentId = "-1",
                title = "Root",
                uri = "",
                mimeType = "",
                size = 0,
                upnpClass = "object.container.storageFolder",
                isContainer = true
            )
            containers[ROOT_ID] = root
            itemIndex[ROOT_ID] = root

            val videos = MediaItem(
                id = VIRTUAL_VIDEOS_ID,
                parentId = ROOT_ID,
                title = "Videos",
                uri = "",
                mimeType = "",
                size = 0,
                upnpClass = "object.container.storageFolder",
                isContainer = true
            )
            containers[VIRTUAL_VIDEOS_ID] = videos
            itemIndex[VIRTUAL_VIDEOS_ID] = videos

            val audio = MediaItem(
                id = VIRTUAL_AUDIO_ID,
                parentId = ROOT_ID,
                title = "Audio",
                uri = "",
                mimeType = "",
                size = 0,
                upnpClass = "object.container.storageFolder",
                isContainer = true
            )
            containers[VIRTUAL_AUDIO_ID] = audio
            itemIndex[VIRTUAL_AUDIO_ID] = audio

            val images = MediaItem(
                id = VIRTUAL_IMAGES_ID,
                parentId = ROOT_ID,
                title = "Images",
                uri = "",
                mimeType = "",
                size = 0,
                upnpClass = "object.container.storageFolder",
                isContainer = true
            )
            containers[VIRTUAL_IMAGES_ID] = images
            itemIndex[VIRTUAL_IMAGES_ID] = images

            val browse = MediaItem(
                id = VIRTUAL_BROWSE_ID,
                parentId = ROOT_ID,
                title = "Browse Folders",
                uri = "",
                mimeType = "",
                size = 0,
                upnpClass = "object.container.storageFolder",
                isContainer = true
            )
            containers[VIRTUAL_BROWSE_ID] = browse
            itemIndex[VIRTUAL_BROWSE_ID] = browse

            items[ROOT_ID] = mutableListOf(videos, audio, images, browse)
            items[VIRTUAL_VIDEOS_ID] = mutableListOf()
            items[VIRTUAL_AUDIO_ID] = mutableListOf()
            items[VIRTUAL_IMAGES_ID] = mutableListOf()
            items[VIRTUAL_BROWSE_ID] = mutableListOf()
        }
    }

    // =================================================================
    // Background scan
    // =================================================================

    /**
     * Clear the existing index (preserving virtual containers) and launch a
     * coroutine on [Dispatchers.IO] that walks each shared folder via
     * [UfmFileSystemBridge.listFiles].
     *
     * ## Guardrails
     *  - Hidden files (names starting with `.`) are skipped.
     *  - Recursion depth is capped at [MAX_RECURSION_DEPTH].
     *  - Total indexed items is capped at [MAX_TOTAL_ITEMS].
     *
     * When the scan finishes the [scanLatch] is counted down so any pending
     * browse requests can proceed.
     */
    fun rescan() {
        val ctx = appContext ?: run {
            Log.w(TAG, "rescan() called before initialize() — ignoring")
            return
        }
        val folders = sharedFolders
        if (folders.isEmpty()) {
            Log.d(TAG, "rescan() called with no shared folders — ignoring")
            return
        }

        // Cancel any running scan
        scanJob?.cancel()
        scanJob = null

        val latch = CountDownLatch(1)
        scanLatch = latch
        isScanning = true

        scanJob = CoroutineScope(Dispatchers.IO + CoroutineName("DlnaScan")).launch {
            Log.d(TAG, "Rescan starting for ${folders.size} shared folder(s)")

            // Reset non-virtual contents while keeping the virtual container
            // hierarchy intact.
            lock.write {
                items[VIRTUAL_VIDEOS_ID]!!.clear()
                items[VIRTUAL_AUDIO_ID]!!.clear()
                items[VIRTUAL_IMAGES_ID]!!.clear()
                items[VIRTUAL_BROWSE_ID]!!.clear()

                containers.keys.filter { it !in VIRTUAL_IDS }.forEach { containers.remove(it) }
                items.keys.filter { it !in VIRTUAL_IDS }.forEach { items.remove(it) }
                itemIndex.keys.filter { it !in VIRTUAL_IDS }.forEach { itemIndex.remove(it) }

                nextItemId = 100L
            }

            var totalItems = 0L

            for (folder in folders) {
                if (totalItems >= MAX_TOTAL_ITEMS) break

                // Create a container for the shared folder under "Browse Folders"
                val folderId = generateId()
                val folderContainer = MediaItem(
                    id = folderId,
                    parentId = VIRTUAL_BROWSE_ID,
                    title = folder.label,
                    uri = folder.uri,
                    mimeType = "",
                    size = 0,
                    upnpClass = "object.container.storageFolder",
                    isContainer = true
                )
                lock.write {
                    containers[folderId] = folderContainer
                    itemIndex[folderId] = folderContainer
                    items.getOrPut(VIRTUAL_BROWSE_ID) { mutableListOf() }.add(folderContainer)
                    items[folderId] = mutableListOf()
                }
                totalItems++

                val scanned = scanDirectory(ctx, folder.uri, folderId, depth = 0, runningTotal = totalItems)
                totalItems += scanned
            }

            Log.d(TAG, "Rescan completed — $totalItems items indexed")
            isScanning = false
            latch.countDown()
            // Only clear the latch if it hasn't been replaced by a newer rescan
            if (scanLatch === latch) scanLatch = null
        }
    }

    /**
     * Recursively walk a single directory via [UfmFileSystemBridge.listFiles].
     *
     * - Directories become container [MediaItem]s added under [parentId] with
     *   an empty child list that gets populated on the recursive call.
     * - Media files become leaf [MediaItem]s added under [parentId] and also
     *   duplicated under the appropriate virtual category (Videos / Audio /
     *   Images) when the MIME type matches.
     *
     * @return the number of items added to the index by this call (including
     *         sub-directory contents and virtual-category duplicates).
     */
    private fun scanDirectory(
        ctx: Context,
        dirUri: String,
        parentId: String,
        depth: Int,
        runningTotal: Long
    ): Long {
        if (depth > MAX_RECURSION_DEPTH) {
            Log.w(TAG, "Max recursion depth ($MAX_RECURSION_DEPTH) reached at $dirUri")
            return 0L
        }
        if (runningTotal >= MAX_TOTAL_ITEMS) return 0L

        var added = 0L
        var localTotal = runningTotal

        try {
            val files = UfmFileSystemBridge.listFiles(ctx, dirUri)

            for (file in files) {
                if (localTotal >= MAX_TOTAL_ITEMS) break

                val name = file.name

                // Skip hidden files / directories
                if (name.startsWith('.')) continue

                if (file.isDirectory) {
                    // ── Directory → container item ───────────────────────────
                    val dirId = generateId()
                    val childUri = buildChildUri(dirUri, name)

                    val containerItem = MediaItem(
                        id = dirId,
                        parentId = parentId,
                        title = name,
                        uri = childUri,
                        mimeType = "",
                        size = 0,
                        upnpClass = "object.container.storageFolder",
                        isContainer = true
                    )

                    lock.write {
                        containers[dirId] = containerItem
                        itemIndex[dirId] = containerItem
                        items.getOrPut(parentId) { mutableListOf() }.add(containerItem)
                        items[dirId] = mutableListOf()
                    }
                    localTotal++
                    added++

                    val subAdded = scanDirectory(ctx, childUri, dirId, depth + 1, localTotal)
                    localTotal += subAdded
                    added += subAdded

                } else {
                    // ── File → media item ────────────────────────────────────
                    val mime = getMimeType(name)
                    if (mime.isEmpty()) continue // unknown / unsupported type

                    val childUri = buildChildUri(dirUri, name)

                    val mediaItem = MediaItem(
                        id = generateId(),
                        parentId = parentId,
                        title = name,
                        uri = childUri,
                        mimeType = mime,
                        size = file.size,
                        upnpClass = getUpnpClass(mime, isContainer = false),
                        isContainer = false
                    )

                    lock.write {
                        itemIndex[mediaItem.id] = mediaItem
                        items.getOrPut(parentId) { mutableListOf() }.add(mediaItem)
                    }
                    localTotal++
                    added++

                    // Duplicate into the appropriate virtual category container
                    val categoryId = categoryForMime(mime)
                    if (categoryId != null) {
                        val catItem = mediaItem.copy(
                            id = generateId(),
                            parentId = categoryId
                        )
                        lock.write {
                            itemIndex[catItem.id] = catItem
                            items.getOrPut(categoryId) { mutableListOf() }.add(catItem)
                        }
                        localTotal++
                        added++
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning directory: $dirUri", e)
        }

        return added
    }

    // =================================================================
    // Query methods – all guarded by the read lock
    // =================================================================

    /**
     * Return a paginated slice of children for [parentId].
     *
     * @param parentId  The container ID whose children to retrieve.
     * @param startIndex Zero-based offset into the child list.
     * @param count     Maximum number of items to return (default 200).
     * @return Up to [count] items, or an empty list if the parent has not
     *         been indexed yet (e.g. scan in progress).
     */
    fun getChildren(parentId: String, startIndex: Int = 0, count: Int = PAGE_SIZE): List<MediaItem> {
        lock.read {
            val children = items[parentId]
            if (children != null) {
                return children.drop(startIndex).take(count)
            }
        }
        return emptyList()
    }

    /**
     * Look up a single item or container by its unique ID.
     *
     * @return The [MediaItem] if found, or `null` if the ID is unknown.
     */
    fun getItem(id: String): MediaItem? {
        lock.read {
            return itemIndex[id]
        }
    }

    /**
     * Return the total number of children (direct descendants) for [parentId].
     */
    fun getTotalMatches(parentId: String): Int {
        lock.read {
            return items[parentId]?.size ?: 0
        }
    }

    // =================================================================
    // MIME / UPnP class mapping
    // =================================================================

    /**
     * Derive the MIME type from a file name by its extension.
     *
     * @return The MIME string (e.g. `"video/mp4"`) or an empty string if the
     *         extension is not recognised.
     */
    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return extensionMimeMap[ext] ?: ""
    }

    /**
     * Map a MIME type to the appropriate UPnP class string.
     *
     * | MIME prefix   | UPnP class                         |
     * |---------------|------------------------------------|
     * | `video/`      | `object.item.videoItem`            |
     * | `audio/`      | `object.item.audioItem.musicTrack` |
     * | `image/`      | `object.item.imageItem.photo`      |
     * | container     | `object.container.storageFolder`   |
     */
    fun getUpnpClass(mimeType: String, isContainer: Boolean): String {
        if (isContainer) return "object.container.storageFolder"
        return when {
            mimeType.startsWith("video/") -> "object.item.videoItem"
            mimeType.startsWith("audio/") -> "object.item.audioItem.musicTrack"
            mimeType.startsWith("image/") -> "object.item.imageItem.photo"
            else -> "object.item.videoItem"
        }
    }

    // =================================================================
    // Internal helpers
    // =================================================================

    /**
     * Thread-safe ID generator.
     *
     * Synchronised so that [scanDirectory] calls from concurrent rescan
     * attempts (though currently serialised by [scanJob]) never collide.
     */
    @Synchronized
    private fun generateId(): String = "item${nextItemId++}"

    /**
     * Build a child URI by appending [childName] to [parentUri].
     *
     * Examples:
     * ```
     * buildChildUri("file:///sdcard/Movies", "subdir")
     *   → "file:///sdcard/Movies/subdir"
     *
     * buildChildUri("smb://shareId/movies/", "clip.mp4")
     *   → "smb://shareId/movies/clip.mp4"
     * ```
     */
    private fun buildChildUri(parentUri: String, childName: String): String {
        return "${parentUri.trimEnd('/')}/$childName"
    }

    /**
     * Return the virtual category container ID for a recognised MIME type, or
     * `null` if the type does not map to a category.
     */
    private fun categoryForMime(mimeType: String): String? {
        return when {
            mimeType.startsWith("video/") -> VIRTUAL_VIDEOS_ID
            mimeType.startsWith("audio/") -> VIRTUAL_AUDIO_ID
            mimeType.startsWith("image/") -> VIRTUAL_IMAGES_ID
            else -> null
        }
    }
}
