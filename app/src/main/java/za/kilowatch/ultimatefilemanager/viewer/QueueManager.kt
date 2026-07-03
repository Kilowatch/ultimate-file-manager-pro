package za.kilowatch.ultimatefilemanager.viewer

/**
 * Represents a single item in the playback queue.
 */
data class QueueItem(
    val path: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val duration: Long = 0L,
    val isVideo: Boolean = false,
    val fileSize: Long = 0L,
    // Network share credentials (nullable = local file)
    val shareId: String? = null,
    val shareHost: String? = null,
    val shareUsername: String? = null,
    val shareName: String? = null,
    val provider: String? = null,
    val remotePath: String? = null
)

/**
 * Mutable playback queue with index tracking, shuffle, repeat, and navigation.
 *
 * Shuffle excludes the current index when choosing the next track (per clarification).
 */
class QueueManager {

    private val items = mutableListOf<QueueItem>()
    /** Public getter; write via [setCurrentIndex]. */
    var currentIndex = 0
        internal set

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
    val currentItem: QueueItem? get() = items.getOrNull(currentIndex)
    val queue: List<QueueItem> get() = items.toList()

    // ── Queue Mutation ──────────────────────────────────────────────

    /** Set an entirely new queue and reset index to 0. */
    fun setQueue(newItems: List<QueueItem>, startIndex: Int = 0) {
        items.clear()
        items.addAll(newItems)
        currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    /** Append items to the end of the queue. */
    fun addToQueue(newItems: List<QueueItem>) {
        items.addAll(newItems)
    }

    /** Insert items after the current track (play next). */
    fun playNext(newItems: List<QueueItem>) {
        val insertAt = (currentIndex + 1).coerceAtMost(items.size)
        items.addAll(insertAt, newItems)
    }

    /** Remove a single item at [index]. Returns true if successful. */
    fun removeAt(index: Int): Boolean {
        if (index < 0 || index >= items.size) return false
        items.removeAt(index)
        // Adjust current index if needed
        if (index < currentIndex) currentIndex--
        else if (index == currentIndex && currentIndex >= items.size) {
            currentIndex = (items.size - 1).coerceAtLeast(0)
        }
        return true
    }

    /** Move an item from [fromIndex] to [toIndex]. */
    fun moveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || fromIndex >= items.size) return
        if (toIndex < 0 || toIndex >= items.size) return
        if (fromIndex == toIndex) return

        val item = items.removeAt(fromIndex)
        items.add(toIndex, item)

        // Adjust current index
        currentIndex = when {
            fromIndex == currentIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
    }

    /** Clear the entire queue. */
    fun clear() {
        items.clear()
        currentIndex = 0
    }

    // ── Navigation ──────────────────────────────────────────────────

    /**
     * Advance to the next item.
     * - If [isShuffle] and size > 1, picks a random index excluding [currentIndex].
     * - Otherwise wraps forward with modulo.
     */
    fun nextIndex(isShuffle: Boolean): Int? {
        if (items.isEmpty()) return null
        if (items.size == 1) return 0

        return if (isShuffle) {
            val candidates = items.indices.filter { it != currentIndex }
            if (candidates.isEmpty()) currentIndex else candidates.random()
        } else {
            (currentIndex + 1) % items.size
        }
    }

    /**
     * Go to the previous item.
     * - If [isShuffle], picks a random index.
     * - Otherwise wraps backward.
     */
    fun prevIndex(isShuffle: Boolean): Int? {
        if (items.isEmpty()) return null
        if (items.size == 1) return 0

        return if (isShuffle) {
            items.indices.filter { it != currentIndex }.ifEmpty { listOf(currentIndex) }.random()
        } else {
            if (currentIndex - 1 < 0) items.size - 1 else currentIndex - 1
        }
    }

    /** Set the current index if valid. */
    fun setCurrentIndex(index: Int): Boolean {
        if (index < 0 || index >= items.size) return false
        currentIndex = index
        return true
    }

    /** Return indices that can be shuffled into (excludes current). */
    fun shuffleCandidates(): List<Int> {
        if (items.size <= 1) return emptyList()
        return items.indices.filter { it != currentIndex }
    }

    /** Get item at index, or null. */
    fun get(index: Int): QueueItem? = items.getOrNull(index)
}
