package za.kilowatch.ultimatefilemanager.storage

/**
 * Immutable state for the batch rename dialog, held in [BatchRenameViewModel].
 */
data class BatchRenameState(
    /** Raw EditText content including token markers ("{Original}", "{Padding}"). */
    val patternText: String = "",
    /** Text to find and replace in resulting names. */
    val replaceText: String = "",
    /** Replacement text. */
    val replaceWith: String = "",
    /** Whether the custom extension toggle is active. */
    val useCustomExtension: Boolean = false,
    /** Custom extension override string (e.g. "csv" or ".csv"). */
    val customExtension: String = "",
    /** Custom date overrides. */
    val customYear: String = "",
    val customMonth: String = "",
    val customDay: String = "",
    /** Whether the "Keep Original Text" toggle is active. */
    val useOriginal: Boolean = false,
    /** Whether the "Add Number Padding" toggle is active. */
    val usePadding: Boolean = false,
    /** Zero-pad width (1–9). */
    val paddingLength: Int = 3,
    /** Counter start value (≥ 0). */
    val paddingStart: Int = 1,
    /** Active selection state flags for token chips (for two-way UI sync). */
    val hasNumberToken: Boolean = false,
    val hasPaddedNumberToken: Boolean = false,
    val hasNameToken: Boolean = false,
    val hasFullNameToken: Boolean = false,
    val hasYearToken: Boolean = false,
    val hasMonthToken: Boolean = false,
    val hasDayToken: Boolean = false,
    val hasUpperToken: Boolean = false,
    val hasLowerToken: Boolean = false,
    /** The items being renamed (ordered: folders first, then files). */
    val items: List<BatchRenameItem> = emptyList(),
    /** Resolved preview for each item — regenerated on every pattern/option change. */
    val previewItems: List<PreviewItem> = emptyList(),
    /** True when the Rename button should be enabled. */
    val isRenameEnabled: Boolean = false,
    /** Inline error message resource key (null when pattern is valid). */
    val patternError: String? = null
)

/**
 * A single row in the batch rename preview list.
 */
data class PreviewItem(
    /** Original display name (e.g. "photo.jpg" or "MyFolder"). */
    val originalName: String,
    /** Resolved resulting name after pattern substitution. */
    val resultingName: String,
    /** 1-based sequence number matching execution order. */
    val index: Int,
    /** Conflict affecting this row, or null when the rename is clean. */
    val conflict: PreviewConflict?
)

/**
 * A problem detected in a preview row's resulting name.
 *
 * [isBlocking] conflicts (DUPLICATE / INVALID_CHARS) disable the Rename button;
 * [COLLISION] only warns the user before committing.
 */
enum class PreviewConflict {
    DUPLICATE,
    INVALID_CHARS,
    COLLISION;

    val isBlocking: Boolean get() = this != COLLISION
}
