package za.kilowatch.ultimatefilemanager.util

import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Encapsulates the scroll state (item index, pixel offset, and child focus target)
 * of a directory in RecyclerView for consistent position restoration across folder navigation.
 */
data class FolderScrollState(
    val position: Int,
    val offset: Int,
    val targetChildPath: String? = null
) {
    companion object {
        /**
         * Captures the current scroll state from a RecyclerView with a LinearLayoutManager or GridLayoutManager.
         */
        fun capture(recyclerView: RecyclerView, targetChildPath: String? = null): FolderScrollState? {
            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return null
            val pos = lm.findFirstVisibleItemPosition()
            if (pos == RecyclerView.NO_POSITION) return null
            val offset = lm.findViewByPosition(pos)?.top ?: 0
            return FolderScrollState(pos, offset, targetChildPath)
        }

        /**
         * Restores the scroll state onto a RecyclerView via layout manager offset scrolling.
         */
        fun restore(recyclerView: RecyclerView, state: FolderScrollState) {
            recyclerView.post {
                (recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(state.position, state.offset)
            }
        }

        /**
         * Serializes a map of FolderScrollState entries into an Android Bundle.
         */
        fun toBundle(states: Map<String, FolderScrollState>): Bundle {
            val bundle = Bundle()
            for ((key, state) in states) {
                val sub = Bundle().apply {
                    putInt("pos", state.position)
                    putInt("offset", state.offset)
                    state.targetChildPath?.let { putString("child", it) }
                }
                bundle.putBundle(key, sub)
            }
            return bundle
        }

        /**
         * Deserializes a map of FolderScrollState entries from an Android Bundle.
         */
        fun fromBundle(bundle: Bundle?): MutableMap<String, FolderScrollState> {
            val map = mutableMapOf<String, FolderScrollState>()
            if (bundle == null) return map
            for (key in bundle.keySet()) {
                val sub = bundle.getBundle(key) ?: continue
                val pos = sub.getInt("pos", -1)
                val offset = sub.getInt("offset", 0)
                val child = sub.getString("child")
                if (pos != -1) {
                    map[key] = FolderScrollState(pos, offset, child)
                }
            }
            return map
        }
    }
}
