package za.kilowatch.ultimatefilemanager.storage

/**
 * Pure-function pattern resolver for batch rename.
 *
 * No side effects, no Android dependencies — independently testable.
 * Implements the resolver matrix from spec Section 5.1.
 */
object BatchRenamePatternResolver {

    private const val TOKEN_ORIGINAL = "{Original}"
    private const val TOKEN_PADDING = "{Padding}"

    /**
     * Resolve a single item's resulting name from the pattern.
     *
     * @param pattern  Raw EditText content (may contain "{Original}" and/or "{Padding}" tokens)
     * @param item     The file/folder being renamed
     * @param counter  1-based index of this item in the ordered list
     * @param useOriginal Whether the {Original} token should be substituted
     * @param usePadding  Whether the {Padding} token should be substituted
     * @param paddingLength Zero-pad width when usePadding is true (1–9)
     * @param paddingStart  Starting counter value when usePadding is true
     * @return The resulting display name WITHOUT extension (extension is applied by the caller
     *         based on item.isDirectory and item.extension)
     */
    fun resolve(
        pattern: String,
        item: BatchRenameItem,
        counter: Int,
        useOriginal: Boolean,
        usePadding: Boolean,
        paddingLength: Int,
        paddingStart: Int
    ): String {
        // 1. Strip tokens to get the base text
        var base = pattern
            .replace(TOKEN_ORIGINAL, "")
            .replace(TOKEN_PADDING, "")
            .trim()

        // 2. Substitute {Original} if active
        val withOriginal = if (useOriginal) {
            val nameToInsert = item.name  // bare name without extension
            pattern.replace(TOKEN_ORIGINAL, nameToInsert)
        } else {
            pattern
        }

        // 3. Substitute {Padding} if active
        val withPadding = if (usePadding) {
            val padValue = "(" + (paddingStart + counter - 1).toString().padStart(paddingLength, '0') + ")"
            withOriginal.replace(TOKEN_PADDING, padValue)
        } else {
            withOriginal
        }

        // 4. Remove any remaining tokens from the result (shouldn't normally remain)
        var result = withPadding
            .replace(TOKEN_ORIGINAL, "")
            .replace(TOKEN_PADDING, "")
            .trim()

        // 5. If base text is empty and neither token was active → invalid
        //    (handled by the caller checking the result)

        // 6. If neither token is active, append parenthetical counter
        //    But if tokens were in the pattern, the base text is what we get after substitution
        if (!useOriginal && !usePadding) {
            // Both tokens absent: pattern is just user text, append " (N)"
            if (result.isNotEmpty()) {
                result = "$result ($counter)"
            }
        } else {
            // At least one token was active — if the pattern was just tokens (base empty),
            // the result might be empty or whitespace after trimming.
            // The caller checks for empty result and handles it as invalid.
        }

        return result
    }

    /**
     * Returns the full resulting filename (including extension for files).
     *
     * @param resolvedName The name returned by [resolve]
     * @param item         The item being renamed
     */
    fun appendExtension(resolvedName: String, item: BatchRenameItem): String {
        if (resolvedName.isEmpty()) return ""
        if (item.isDirectory) return resolvedName
        return resolvedName + item.extension
    }
}
