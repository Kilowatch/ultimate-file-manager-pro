package za.kilowatch.ultimatefilemanager.storage

/**
 * Character-level diff between an original and a resulting filename.
 *
 * Used by the batch rename preview to highlight only the portion of the name
 * that actually changes, rather than bolding the entire new name.
 */
object BatchRenameDiff {

    /**
     * The character range inside a resulting name that differs from the original.
     *
     * @property start         Index (inclusive) into the resulting name.
     * @property endExclusive  Index (exclusive) into the resulting name.
     */
    data class Highlight(
        val start: Int,
        val endExclusive: Int
    )

    /**
     * Compute the changed range of [result] relative to [original].
     *
     * Uses the longest common prefix and longest common suffix: the unchanged
     * edges are kept plain and only the differing middle is highlighted. When
     * there is no common prefix or suffix, the entire [result] is highlighted.
     *
     * @return The [Highlight] range, or `null` when the two names are identical
     *         (i.e. the rename is a no-op for this item).
     */
    fun compute(original: String, result: String): Highlight? {
        if (original == result) return null

        val minLen = minOf(original.length, result.length)

        // Longest common prefix.
        var prefix = 0
        while (prefix < minLen && original[prefix] == result[prefix]) {
            prefix++
        }

        // Longest common suffix, without overlapping the already-matched prefix.
        var suffix = 0
        while (suffix < minLen - prefix &&
            original[original.length - 1 - suffix] == result[result.length - 1 - suffix]
        ) {
            suffix++
        }

        val start = prefix
        val endExclusive = result.length - suffix

        // Pathological fallback: if the middle collapsed (shouldn't happen when
        // the strings differ), highlight the whole result rather than nothing.
        if (endExclusive <= start) {
            return Highlight(0, result.length)
        }

        return Highlight(start, endExclusive)
    }
}
