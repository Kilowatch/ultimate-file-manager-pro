package za.kilowatch.ultimatefilemanager.viewer

/**
 * In-memory store for large playlists that cannot safely be transmitted
 * through an Android Intent (Binder 1 MB limit).
 *
 * Usage:
 *  - Caller:   val key = PlaylistCache.put(paths)
 *              intent.putExtra("playlistCacheKey", key)
 *  - Receiver: val paths = PlaylistCache.take(intent.getStringExtra("playlistCacheKey") ?: "")
 *
 * Uses a [java.util.concurrent.ConcurrentHashMap] so rapid back-to-back launches
 * (e.g. the Activity and its bound Service both reading) do not race.
 */
object PlaylistCache {
    private val store = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Store [playlist] and return the unique key to pass via Intent. */
    fun put(playlist: List<String>): String {
        val key = System.currentTimeMillis().toString()
        store[key] = playlist
        return key
    }

    /** Store [playlist] with an explicit [key]. */
    fun put(key: String, playlist: List<String>) {
        if (key.isNotBlank()) {
            store[key] = playlist
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
     * Retrieve and remove the playlist for [key].
     * Returns `null` if [key] is blank or no entry exists (e.g. process was restarted).
     */
    fun take(key: String): List<String>? {
        if (key.isBlank()) return null
        return store.remove(key)
    }

    /**
     * Explicitly remove the playlist for [key].
     */
    fun remove(key: String): List<String>? {
        if (key.isBlank()) return null
        return store.remove(key)
    }
}
