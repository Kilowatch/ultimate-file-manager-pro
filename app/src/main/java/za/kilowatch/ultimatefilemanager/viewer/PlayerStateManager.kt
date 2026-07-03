package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializable snapshot of the playback state for process-death recovery.
 */
data class PlayerState(
    val queue: List<QueueItem>,
    val currentIndex: Int,
    val currentPosition: Long,
    val isPlaying: Boolean,
    val isShuffle: Boolean,
    val isRepeat: Boolean,
    val timestamp: Long
)

/**
 * Persists and restores playback state across process death.
 *
 * Data is stored in "ufm_player_state" SharedPreferences as a serialised JSON blob.
 * State is automatically cleared when the user explicitly stops playback.
 */
object PlayerStateManager {

    private const val PREFS_NAME = "ufm_player_state"
    private const val KEY_STATE = "state_json"

    private const val MAX_STALENESS_MS = 30 * 60 * 1000L // 30 minutes

    // ── Save ────────────────────────────────────────────────────────

    fun saveState(
        context: Context,
        queue: List<QueueItem>,
        currentIndex: Int,
        currentPosition: Long,
        isPlaying: Boolean,
        isShuffle: Boolean,
        isRepeat: Boolean
    ) {
        val json = JSONObject().apply {
            put("currentIndex", currentIndex)
            put("currentPosition", currentPosition)
            put("isPlaying", isPlaying)
            put("isShuffle", isShuffle)
            put("isRepeat", isRepeat)
            put("timestamp", System.currentTimeMillis())

            val queueArray = JSONArray()
            queue.forEach { item ->
                queueArray.put(JSONObject().apply {
                    put("path", item.path)
                    item.title?.let { put("title", it) }
                    item.artist?.let { put("artist", it) }
                    item.album?.let { put("album", it) }
                    put("duration", item.duration)
                    put("isVideo", item.isVideo)
                    put("fileSize", item.fileSize)
                    item.shareId?.let { put("shareId", it) }
                    item.shareHost?.let { put("shareHost", it) }
                    item.shareUsername?.let { put("shareUsername", it) }
                    item.shareName?.let { put("shareName", it) }
                    item.provider?.let { put("provider", it) }
                    item.remotePath?.let { put("remotePath", it) }
                })
            }
            put("queue", queueArray)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, json.toString())
            .apply()
    }

    // ── Restore ─────────────────────────────────────────────────────

    fun restoreState(context: Context): PlayerState? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_STATE, null) ?: return null

        return try {
            val json = JSONObject(raw)

            // Staleness check
            val timestamp = json.optLong("timestamp", 0L)
            if (System.currentTimeMillis() - timestamp > MAX_STALENESS_MS) {
                clearState(context)
                return null
            }

            val queueArray = json.optJSONArray("queue") ?: return null
            val queue = mutableListOf<QueueItem>()
            for (i in 0 until queueArray.length()) {
                val item = queueArray.getJSONObject(i)
                queue.add(
                    QueueItem(
                        path = item.getString("path"),
                        title = item.optString("title", null),
                        artist = item.optString("artist", null),
                        album = item.optString("album", null),
                        duration = item.optLong("duration", 0L),
                        isVideo = item.optBoolean("isVideo", false),
                        fileSize = item.optLong("fileSize", 0L),
                        shareId = item.optString("shareId", null),
                        shareHost = item.optString("shareHost", null),
                        shareUsername = item.optString("shareUsername", null),
                        shareName = item.optString("shareName", null),
                        provider = item.optString("provider", null),
                        remotePath = item.optString("remotePath", null)
                    )
                )
            }

            if (queue.isEmpty()) return null

            PlayerState(
                queue = queue,
                currentIndex = json.optInt("currentIndex", 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0)),
                currentPosition = json.optLong("currentPosition", 0L),
                isPlaying = json.optBoolean("isPlaying", false),
                isShuffle = json.optBoolean("isShuffle", false),
                isRepeat = json.optBoolean("isRepeat", false),
                timestamp = timestamp
            )
        } catch (e: Exception) {
            // Corrupted state — clear and return null
            clearState(context)
            null
        }
    }

    // ── Clear ───────────────────────────────────────────────────────

    /** Call when the user explicitly stops playback. */
    fun clearState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_STATE)
            .apply()
    }
}
