package za.kilowatch.ultimatefilemanager.viewer

/**
 * In-memory store for large playlists and network file metadata (sizes) that
 * cannot safely be transmitted through an Android Intent (Binder 1 MB limit).
 *
 * Usage:
 *  - Caller:   val key = PlaylistCache.put(paths, sizesMap)
 *              intent.putExtra("playlistCacheKey", key)
 *  - Receiver: val sizes = PlaylistCache.getSizes(key)
 *              val paths = PlaylistCache.take(key) // or get(key)
 *
 * Uses a [java.util.concurrent.ConcurrentHashMap] so rapid back-to-back launches
 * (e.g. the Activity and its bound Service both reading) do not race.
 */
object PlaylistCache {
    private val store = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val sizesStore = java.util.concurrent.ConcurrentHashMap<String, Map<String, Long>>()
    private val keyOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    private const val MAX_ENTRIES = 30

    /** Store [playlist] and optional [sizes] and return the unique key to pass via Intent. */
    fun put(playlist: List<String>, sizes: Map<String, Long>? = null): String {
        val key = "${System.currentTimeMillis()}_${counter.incrementAndGet()}"
        put(key, playlist, sizes)
        return key
    }

    /** Store [playlist] and optional [sizes] with an explicit [key]. */
    fun put(key: String, playlist: List<String>, sizes: Map<String, Long>? = null) {
        if (key.isNotBlank()) {
            store[key] = playlist
            if (!sizes.isNullOrEmpty()) {
                sizesStore[key] = sizes
            }
            keyOrder.add(key)
            trimToSize()
        }
    }

    /**
     * Retrieve the playlist for [key] without removing it.
     * Returns `null` if [key] is blank or no entry exists.
     */
    fun get(key: String): List<String>? {
        if (key.isBlank()) return null
        return store[key]
    }

    /**
     * Retrieve the sizes map for [key] without removing it.
     * Returns `null` if [key] is blank or no entry exists.
     */
    fun getSizes(key: String): Map<String, Long>? {
        if (key.isBlank()) return null
        return sizesStore[key]
    }

    /**
     * Retrieve and remove the playlist and sizes for [key].
     * Returns `null` if [key] is blank or no entry exists (e.g. process was restarted).
     */
    fun take(key: String): List<String>? {
        if (key.isBlank()) return null
        sizesStore.remove(key)
        keyOrder.remove(key)
        return store.remove(key)
    }

    /**
     * Retrieve and remove the sizes map for [key].
     */
    fun takeSizes(key: String): Map<String, Long>? {
        if (key.isBlank()) return null
        return sizesStore.remove(key)
    }

    /**
     * Explicitly remove the playlist and sizes for [key].
     */
    fun remove(key: String): List<String>? {
        if (key.isBlank()) return null
        sizesStore.remove(key)
        keyOrder.remove(key)
        return store.remove(key)
    }

    private fun trimToSize() {
        while (keyOrder.size > MAX_ENTRIES) {
            val oldest = keyOrder.pollFirst()
            if (oldest != null) {
                store.remove(oldest)
                sizesStore.remove(oldest)
            }
        }
    }
}
